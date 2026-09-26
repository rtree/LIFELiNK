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
  carrierConference: boolean;
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

const CONFERENCE_INSTRUCTIONS =
  "You are a voice AI assisting an emergency. You joined a carrier three-way phone call: " +
  "the person who pressed SOS and their trusted contact may both be on it, and you hear one " +
  "mixed audio stream. The first human voice you hear before anyone else joins is most likely " +
  "the SOS sender; a new voice after that is most likely the contact. You can only guess who is " +
  "speaking, so when unsure say 'someone on the call' and never state it as fact. The SOS sender " +
  "may be unable to speak; silence is normal, keep listening. Automated announcements such as " +
  "hold messages ('please hold', '保留中', 'お待ちください') mean nobody can hear you: stay " +
  "silent until a person speaks. Describe background sounds only when clearly audible, and say " +
  "they are uncertain ('it sounds like'). Never guess or invent facts. Speak briefly and calmly, " +
  "in the language the person on the call uses.";

const HOLD_ANNOUNCEMENT = /保留|お待ちください|please hold|on hold|remain on the line/i;
// Only hold prompts and no human for this long means the SOS sender never merged the AI back.
const CONFERENCE_ABANDONED_MS = 90_000;

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

export function mediaStreamTwiml(emergencyEventId: string): string {
  const response = new twilio.twiml.VoiceResponse();
  const stream = response.connect().stream({
    statusCallback: `${config.BACKEND_URL}/v1/twilio/stream-status?emergencyEventId=${encodeURIComponent(emergencyEventId)}`,
    url: config.BACKEND_URL.replace(/^https:/, "wss:") + "/v1/twilio/media",
  });
  stream.parameter({ name: "emergencyEventId", value: emergencyEventId });
  return response.toString();
}

export async function placeEmergencyCall(
  emergencyEventId: string,
  destination: string,
): Promise<string> {
  const voiceConfig = requireVoiceConfig();
  const call = await twilio(voiceConfig.accountSid, voiceConfig.authToken).calls.create({
    from: voiceConfig.fromNumber,
    statusCallback: `${config.BACKEND_URL}/v1/twilio/status?emergencyEventId=${encodeURIComponent(emergencyEventId)}`,
    statusCallbackEvent: ["initiated", "ringing", "answered", "completed"],
    statusCallbackMethod: "POST",
    to: destination,
    twiml: mediaStreamTwiml(emergencyEventId),
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
    context.carrierConference
      ? "This is the LIFELiNK emergency AI joining this call. The person who pressed SOS " +
        "is on this call, and their trusted contact may be on it too."
      : "This is an automated call from the LIFELiNK emergency app.",
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
    let carrierConference = false;
    let callLimitTimer: NodeJS.Timeout | null = null;
    let conferenceInstructions: string | null = null;
    let holdOnlySince: number | null = null;
    let abandonTimer: NodeJS.Timeout | null = null;
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
      if (conferenceInstructions) {
        openAiSocket.send(
          JSON.stringify({
            type: "session.update",
            session: { type: "realtime", instructions: conferenceInstructions },
          }),
        );
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
            instructions: sessionInstructions(carrierConference),
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
        if (context.carrierConference) {
          carrierConference = true;
          if (openAiSocket.readyState === WebSocket.OPEN) {
            openAiSocket.send(
              JSON.stringify({
                type: "session.update",
                session: { type: "realtime", instructions: sessionInstructions(true) },
              }),
            );
          }
          callLimitTimer = setTimeout(() => {
            app.log.info({ emergencyEventId }, "Carrier conference AI leg reached the time limit");
            twilioSocket.close();
          }, CARRIER_CONFERENCE_MAX_MS);
        }
        if (context.carrierConference) {
          conferenceInstructions = CONFERENCE_INSTRUCTIONS;
          const eventId = emergencyEventId;
          abandonTimer = setInterval(() => {
            if (holdOnlySince && Date.now() - holdOnlySince >= CONFERENCE_ABANDONED_MS) {
              app.log.info({ emergencyEventId: eventId }, "AI left on hold in carrier conference, hanging up");
              onTranscript(eventId, "system", "The AI left the call because it was kept on hold");
              // Closing the stream ends <Connect>, and with no further TwiML Twilio hangs up.
              twilioSocket.close();
            }
          }, 5_000);
        }
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
          const heard = event.transcript.trim();
          if (conferenceInstructions) {
            if (HOLD_ANNOUNCEMENT.test(heard)) {
              holdOnlySince ??= Date.now();
            } else {
              holdOnlySince = null;
            }
          }
          onTranscript(emergencyEventId, "contact", heard);
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
      if (callLimitTimer) {
        clearTimeout(callLimitTimer);
        callLimitTimer = null;
      }
      if (abandonTimer) {
        clearInterval(abandonTimer);
        abandonTimer = null;
      }
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
// The AI leg leaves after this; the carrier call between the humans is not ours to end.
const CARRIER_CONFERENCE_MAX_MS = 60 * 60 * 1000;

const BASE_INSTRUCTIONS =
  "You are an English-speaking voice AI assisting an emergency call. " +
  "Never guess or invent facts. Speak briefly and calmly. Answer the " +
  "other person's questions, and say plainly when you do not know something.";

const CARRIER_CONFERENCE_INSTRUCTIONS =
  " This is a three-way phone call merged by the carrier, and it happens in steps. First the " +
  "phone of the person who pressed SOS calls you. A few seconds later that phone puts you on hold " +
  "while it rings their trusted contact, so for up to about a minute you may hear hold music, " +
  "recorded announcements, or silence; nobody can hear you then, so wait quietly. When the contact " +
  "answers, the calls are merged and the contact joins; briefly tell them who you are and what you " +
  "know. If the contact does not answer, you come back to the person who pressed SOS alone." +
  " You hear everyone mixed into one audio stream, so you cannot know for sure who is speaking. " +
  "The first voice you hear after joining is most likely the person who pressed SOS; a new voice " +
  "after the merge is most likely the contact. When it matters, say who you think is speaking and " +
  "that it is a guess, or ask. " +
  "Recorded announcements such as 'this call is on hold' or 'please wait' are automated " +
  "messages, not people: do not answer them and stay silent until a person speaks. " +
  "Describe background sounds only as possibilities, for example 'it sounds like', " +
  "and never state a sound as a fact." +
  " Everything said on this call is transcribed and relayed live to the person's trusted friends " +
  "in a Discord direct message, and they may be reading along. Their replies reach you as new " +
  "information from the emergency app; pass them on and say they came from a friend on Discord.";

function sessionInstructions(carrierConference: boolean): string {
  return carrierConference
    ? BASE_INSTRUCTIONS + CARRIER_CONFERENCE_INSTRUCTIONS
    : BASE_INSTRUCTIONS;
}