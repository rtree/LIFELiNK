import type { FastifyInstance, FastifyRequest } from "fastify";
import twilio from "twilio";
import WebSocket, { type RawData } from "ws";

import { config } from "./config.js";

type InitialContext = {
  address: string | null;
  latitude: number | null;
  longitude: number | null;
  accuracyM: number | null;
  capturedAt: string | null;
  batteryPercent: number | null;
  batteryCharging: boolean | null;
  motionState: string | null;
  motionPeakG: number | null;
  initialNote: string | null;
};

type TwilioStartMessage = {
  event: "start";
  start: {
    callSid: string;
    streamSid: string;
    customParameters?: { emergencyEventId?: string };
  };
};

type TwilioMediaMessage = {
  event: "media";
  media: { payload: string };
};

// Realtime rejects `response.create` while a response is already in flight, and
// the rejected turn leaves the caller hearing nothing. Injections are therefore
// queued and released on `response.done`.
type RealtimeSession = {
  socket: WebSocket;
  responseActive: boolean;
  pending: string[];
};

const activeRealtimeSessions = new Map<string, RealtimeSession>();

function flushPendingInjections(session: RealtimeSession): void {
  if (session.responseActive || session.socket.readyState !== WebSocket.OPEN) {
    return;
  }
  const text = session.pending.shift();
  if (text === undefined) {
    return;
  }
  session.socket.send(
    JSON.stringify({
      type: "conversation.item.create",
      item: {
        type: "message",
        role: "user",
        content: [
          {
            type: "input_text",
            text: `New information from the emergency app. Tell the person on the call briefly. ${text}`,
          },
        ],
      },
    }),
  );
  session.socket.send(JSON.stringify({ type: "response.create" }));
  session.responseActive = true;
}

export function injectEmergencyUpdate(
  emergencyEventId: string,
  text: string,
): boolean {
  const session = activeRealtimeSessions.get(emergencyEventId);
  if (!session || session.socket.readyState !== WebSocket.OPEN) {
    return false;
  }
  session.pending.push(text);
  flushPendingInjections(session);
  return true;
}

function requireVoiceConfig() {
  if (
    !config.TWILIO_ACCOUNT_SID ||
    !config.TWILIO_AUTH_TOKEN ||
    !config.TWILIO_FROM_NUMBER ||
    !config.OPENAI_API_KEY
  ) {
    throw new Error("Voice secrets are not configured");
  }

  return {
    accountSid: config.TWILIO_ACCOUNT_SID,
    authToken: config.TWILIO_AUTH_TOKEN,
    fromNumber: config.TWILIO_FROM_NUMBER,
    openAiApiKey: config.OPENAI_API_KEY,
  };
}

export async function placeEmergencyCall(
  emergencyEventId: string,
  destination: string,
): Promise<string> {
  const voiceConfig = requireVoiceConfig();
  const response = new twilio.twiml.VoiceResponse();
  const stream = response.connect().stream({
    statusCallback: `${config.BACKEND_URL}/v1/twilio/stream-status?emergencyEventId=${encodeURIComponent(emergencyEventId)}`,
    url: config.BACKEND_URL.replace(/^https:/, "wss:") + "/v1/twilio/media",
  });
  stream.parameter({ name: "emergencyEventId", value: emergencyEventId });

  const call = await twilio(voiceConfig.accountSid, voiceConfig.authToken).calls.create({
    from: voiceConfig.fromNumber,
    statusCallback: `${config.BACKEND_URL}/v1/twilio/status?emergencyEventId=${encodeURIComponent(emergencyEventId)}`,
    statusCallbackEvent: ["initiated", "ringing", "answered", "completed"],
    statusCallbackMethod: "POST",
    to: destination,
    twiml: response.toString(),
  });
  return call.sid;
}

export function isValidTwilioRequest(
  request: FastifyRequest,
  protocol: "https" | "wss" = "https",
): boolean {
  if (!config.TWILIO_AUTH_TOKEN) {
    return false;
  }
  const signature = request.headers["x-twilio-signature"];
  if (typeof signature !== "string") {
    return false;
  }
  const baseUrl = protocol === "wss"
    ? config.BACKEND_URL.replace(/^https:/, "wss:")
    : config.BACKEND_URL;
  const url = `${baseUrl}${request.url}`;
  const params =
    request.body && typeof request.body === "object"
      ? (request.body as Record<string, string>)
      : {};
  return twilio.validateRequest(config.TWILIO_AUTH_TOKEN, signature, url, params);
}

function buildInitialMessage(context: InitialContext): string {
  const freshness = context.capturedAt
    ? `${Math.max(0, Math.round((Date.now() - Date.parse(context.capturedAt)) / 1000))} seconds ago`
    : "at an unknown time";
  // Hackathon privacy policy: never speak raw GPS coordinates. `context.address`
  // is already prefecture-level only (Android reverse-geocodes to adminArea
  // before sending), so this is the coarsest location we ever say. Accuracy is
  // spoken on purpose: it conveys how trustworthy the area is without locating
  // the person.
  const lines = [
    "This is an automated call from the LIFELiNK emergency app.",
    context.address
      ? `The person is somewhere around ${context.address}.`
      : "Their area could not be determined.",
    context.accuracyM != null
      ? `That area is accurate to about ${Math.round(context.accuracyM)} meters.`
      : "The accuracy of that area is unknown.",
    `The location was captured ${freshness}.`,
  ];
  if (context.batteryPercent != null) {
    lines.push(
      `Their phone battery is at ${context.batteryPercent} percent${
        context.batteryCharging === true ? " and charging" : ""
      }.`,
    );
  }
  if (context.motionState === "shaking") {
    lines.push("Their phone is being shaken hard right now.");
  } else if (context.motionState === "moving") {
    lines.push("Their phone is moving.");
  }
  lines.push(
    context.initialNote
      ? `Their note says: ${context.initialNote}.`
      : "They did not leave a note.",
  );
  lines.push("I will pass on anything new as it arrives.");
  return lines.join(" ");
}

export type CallTranscriptSpeaker = "contact" | "ai" | "system";

export function registerMediaBridge(
  app: FastifyInstance,
  loadInitialContext: (
    eventId: string,
    callSid: string,
  ) => Promise<InitialContext | null>,
  onTranscript: (eventId: string, speaker: CallTranscriptSpeaker, text: string) => void,
): void {
  app.get("/v1/twilio/media", { websocket: true }, (twilioSocket, request) => {
    if (!isValidTwilioRequest(request, "wss")) {
      app.log.warn("Rejected Media Stream with invalid Twilio signature");
      twilioSocket.close(1008, "invalid Twilio signature");
      return;
    }

    const voiceConfig = requireVoiceConfig();
    const openAiSocket = new WebSocket(
      `wss://api.openai.com/v1/realtime?model=${encodeURIComponent(config.OPENAI_REALTIME_MODEL)}`,
      { headers: { Authorization: `Bearer ${voiceConfig.openAiApiKey}` } },
    );
    let streamSid: string | null = null;
    let emergencyEventId: string | null = null;
    let pendingInitialMessage: string | null = null;
    const session: RealtimeSession = {
      socket: openAiSocket,
      responseActive: false,
      pending: [],
    };
    const pendingInputAudio: string[] = [];
    let inboundAudioFrames = 0;
    let outputAudioFrames = 0;
    let speechTurns = 0;

    const sendInitialMessage = () => {
      if (!pendingInitialMessage || openAiSocket.readyState !== WebSocket.OPEN) {
        return;
      }
      openAiSocket.send(
        JSON.stringify({
          type: "conversation.item.create",
          item: {
            type: "message",
            role: "user",
            content: [{ type: "input_text", text: pendingInitialMessage }],
          },
        }),
      );
      openAiSocket.send(JSON.stringify({ type: "response.create" }));
      session.responseActive = true;
      pendingInitialMessage = null;
    };

    openAiSocket.on("open", () => {
      openAiSocket.send(
        JSON.stringify({
          type: "session.update",
          session: {
            type: "realtime",
            model: config.OPENAI_REALTIME_MODEL,
            instructions:
              "You are an English-speaking voice AI assisting an emergency call. " +
              "Never guess or invent facts. Speak briefly and calmly. Answer the " +
              "other person's questions, and say plainly when you do not know something.",
            output_modalities: ["audio"],
            audio: {
              input: {
                format: { type: "audio/pcmu" },
                transcription: { model: "gpt-4o-mini-transcribe", language: "en" },
                turn_detection: {
                  type: "server_vad",
                  create_response: true,
                  interrupt_response: true,
                },
              },
              output: { format: { type: "audio/pcmu" }, voice: "marin" },
            },
          },
        }),
      );
      if (emergencyEventId) {
        activeRealtimeSessions.set(emergencyEventId, session);
      }
      pendingInputAudio.splice(0).forEach((audio) => {
        openAiSocket.send(
          JSON.stringify({ type: "input_audio_buffer.append", audio }),
        );
      });
      sendInitialMessage();
    });

    twilioSocket.on("message", async (rawMessage: RawData) => {
      const message = JSON.parse(rawMessage.toString()) as
        | TwilioStartMessage
        | TwilioMediaMessage
        | { event: string };

      if (message.event === "start") {
        const start = message as TwilioStartMessage;
        streamSid = start.start.streamSid;
        emergencyEventId = start.start.customParameters?.emergencyEventId ?? null;
        if (!emergencyEventId) {
          app.log.warn("Closing Media Stream without emergency event ID");
          twilioSocket.close(1008, "missing emergency event");
          return;
        }
        const context = await loadInitialContext(
          emergencyEventId,
          start.start.callSid,
        );
        if (!context) {
          app.log.warn(
            { emergencyEventId, callSid: start.start.callSid },
            "Closing Media Stream because event context did not match",
          );
          twilioSocket.close(1008, "emergency event not found");
          return;
        }
        const initialMessage = buildInitialMessage(context);
        pendingInitialMessage = `Read the following out loud first, exactly as written. ${initialMessage}`;
        if (openAiSocket.readyState === WebSocket.OPEN) {
          activeRealtimeSessions.set(emergencyEventId, session);
        }
        sendInitialMessage();
      }

      if (message.event === "media") {
        const audio = (message as TwilioMediaMessage).media.payload;
        inboundAudioFrames += 1;
        if (openAiSocket.readyState === WebSocket.OPEN) {
          openAiSocket.send(
            JSON.stringify({ type: "input_audio_buffer.append", audio }),
          );
        } else if (pendingInputAudio.length < MAX_PENDING_INPUT_FRAMES) {
          pendingInputAudio.push(audio);
        }
      }
    });

    openAiSocket.on("message", (rawMessage) => {
      const event = JSON.parse(rawMessage.toString()) as {
        type: string;
        delta?: string;
        transcript?: string;
        error?: unknown;
      };
      if (emergencyEventId && event.transcript?.trim()) {
        if (event.type === "conversation.item.input_audio_transcription.completed") {
          onTranscript(emergencyEventId, "contact", event.transcript.trim());
        } else if (event.type === "response.output_audio_transcript.done") {
          onTranscript(emergencyEventId, "ai", event.transcript.trim());
        }
      }
      if (
        streamSid &&
        event.delta &&
        (event.type === "response.output_audio.delta" || event.type === "response.audio.delta")
      ) {
        outputAudioFrames += 1;
        twilioSocket.send(
          JSON.stringify({
            event: "media",
            streamSid,
            media: { payload: event.delta },
          }),
        );
      }
      if (event.type === "input_audio_buffer.speech_started" && streamSid) {
        speechTurns += 1;
        twilioSocket.send(JSON.stringify({ event: "clear", streamSid }));
      }
      if (event.type === "response.created") {
        session.responseActive = true;
      }
      if (
        event.type === "response.done" ||
        event.type === "response.cancelled" ||
        event.type === "error"
      ) {
        session.responseActive = false;
        flushPendingInjections(session);
      }
      if (event.type === "error") {
        app.log.error(
          { emergencyEventId, openAiError: event.error ?? event },
          "OpenAI Realtime error",
        );
      }
    });

    const closeBoth = () => {
      if (
        emergencyEventId &&
        activeRealtimeSessions.get(emergencyEventId)?.socket === openAiSocket
      ) {
        activeRealtimeSessions.delete(emergencyEventId);
      }
      if (twilioSocket.readyState === WebSocket.OPEN) {
        twilioSocket.close();
      }
      if (openAiSocket.readyState === WebSocket.OPEN) {
        openAiSocket.close();
      }
    };
    twilioSocket.on("close", (code, reason) => {
      app.log.info(
        {
          code,
          reason: reason.toString(),
          emergencyEventId,
          inboundAudioFrames,
          outputAudioFrames,
          speechTurns,
        },
        "Twilio Media Stream closed",
      );
      if (emergencyEventId) onTranscript(emergencyEventId, "system", "Call ended");
      closeBoth();
    });
    openAiSocket.on("close", (code, reason) => {
      app.log.info(
        { code, reason: reason.toString(), emergencyEventId },
        "OpenAI Realtime WebSocket closed",
      );
      closeBoth();
    });
    openAiSocket.on("error", (error) => app.log.error(error, "OpenAI WebSocket failed"));
  });
}

const MAX_PENDING_INPUT_FRAMES = 100;