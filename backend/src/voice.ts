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

const activeRealtimeSessions = new Map<string, WebSocket>();

export function injectEmergencyUpdate(
  emergencyEventId: string,
  text: string,
): boolean {
  const socket = activeRealtimeSessions.get(emergencyEventId);
  if (!socket || socket.readyState !== WebSocket.OPEN) {
    return false;
  }
  socket.send(
    JSON.stringify({
      type: "conversation.item.create",
      item: {
        type: "message",
        role: "user",
        content: [
          {
            type: "input_text",
            text: `緊急連絡アプリから新しい情報です。相手に簡潔に伝えてください。${text}`,
          },
        ],
      },
    }),
  );
  socket.send(JSON.stringify({ type: "response.create" }));
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
    ? `${Math.max(0, Math.round((Date.now() - Date.parse(context.capturedAt)) / 1000))}秒前`
    : "取得時刻不明";
  // Hackathon privacy policy: never speak raw GPS coordinates or accuracy.
  // `context.address` is already prefecture-level only (Android reverse-geocodes
  // to adminArea before sending), so this is the coarsest location we ever say.
  return [
    "これはLIFELiNK緊急連絡アプリからの自動電話です。",
    context.address ? `本人がいるのは${context.address}付近です。` : "現在地の都道府県は取得できていません。",
    `位置情報は${freshness}に取得されました。`,
    context.initialNote ? `本人からのメモは「${context.initialNote}」です。` : "本人からの状況メモはありません。",
    "新しい情報が入り次第お伝えします。",
  ].join(" ");
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
              "あなたは緊急連絡を補助する日本語の音声AIです。事実を推測せず、簡潔に話してください。相手の質問に答え、不明な情報は不明と伝えてください。",
            output_modalities: ["audio"],
            audio: {
              input: {
                format: { type: "audio/pcmu" },
                transcription: { model: "gpt-4o-mini-transcribe", language: "ja" },
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
        activeRealtimeSessions.set(emergencyEventId, openAiSocket);
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
        pendingInitialMessage = `次の文章をそのまま最初に読み上げてください。${initialMessage}`;
        if (openAiSocket.readyState === WebSocket.OPEN) {
          activeRealtimeSessions.set(emergencyEventId, openAiSocket);
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
      if (event.type === "error") {
        app.log.error({ openAiEvent: event.type }, "OpenAI Realtime error");
      }
    });

    const closeBoth = () => {
      if (
        emergencyEventId &&
        activeRealtimeSessions.get(emergencyEventId) === openAiSocket
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
      if (emergencyEventId) onTranscript(emergencyEventId, "system", "通話が終了しました");
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