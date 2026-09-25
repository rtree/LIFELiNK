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
    streamSid: string;
    customParameters?: { emergencyEventId?: string };
  };
};

type TwilioMediaMessage = {
  event: "media";
  media: { payload: string };
};

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

export function isValidTwilioRequest(request: FastifyRequest): boolean {
  if (!config.TWILIO_AUTH_TOKEN) {
    return false;
  }
  const signature = request.headers["x-twilio-signature"];
  if (typeof signature !== "string") {
    return false;
  }
  const url = `${config.BACKEND_URL}${request.url}`;
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
  return [
    "これはLIFELiNK緊急連絡アプリからの自動電話です。",
    context.address ? `住所は${context.address}です。` : "住所は取得できていません。",
    context.latitude !== null && context.longitude !== null
      ? `座標は緯度${context.latitude}、経度${context.longitude}です。`
      : "座標は取得できていません。",
    context.accuracyM !== null
      ? `位置精度は約${Math.round(context.accuracyM)}メートルです。`
      : "位置精度は不明です。",
    `位置情報は${freshness}に取得されました。`,
    context.initialNote ? `本人からのメモは「${context.initialNote}」です。` : "本人からの状況メモはありません。",
    "新しい情報が入り次第お伝えします。",
  ].join(" ");
}

export function registerMediaBridge(
  app: FastifyInstance,
  loadInitialContext: (eventId: string) => Promise<InitialContext | null>,
): void {
  app.get("/v1/twilio/media", { websocket: true }, (twilioSocket, request) => {
    if (!isValidTwilioRequest(request)) {
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
          twilioSocket.close(1008, "missing emergency event");
          return;
        }
        const context = await loadInitialContext(emergencyEventId);
        if (!context) {
          twilioSocket.close(1008, "emergency event not found");
          return;
        }
        const initialMessage = buildInitialMessage(context);
        openAiSocket.send(
          JSON.stringify({
            type: "conversation.item.create",
            item: {
              type: "message",
              role: "user",
              content: [{ type: "input_text", text: `次の文章をそのまま最初に読み上げてください。${initialMessage}` }],
            },
          }),
        );
        openAiSocket.send(JSON.stringify({ type: "response.create" }));
      }

      if (message.event === "media" && openAiSocket.readyState === WebSocket.OPEN) {
        openAiSocket.send(
          JSON.stringify({
            type: "input_audio_buffer.append",
            audio: (message as TwilioMediaMessage).media.payload,
          }),
        );
      }
    });

    openAiSocket.on("message", (rawMessage) => {
      const event = JSON.parse(rawMessage.toString()) as {
        type: string;
        delta?: string;
      };
      if (
        streamSid &&
        event.delta &&
        (event.type === "response.output_audio.delta" || event.type === "response.audio.delta")
      ) {
        twilioSocket.send(
          JSON.stringify({
            event: "media",
            streamSid,
            media: { payload: event.delta },
          }),
        );
      }
      if (event.type === "error") {
        app.log.error({ openAiEvent: event.type }, "OpenAI Realtime error");
      }
    });

    const closeBoth = () => {
      if (twilioSocket.readyState === WebSocket.OPEN) {
        twilioSocket.close();
      }
      if (openAiSocket.readyState === WebSocket.OPEN) {
        openAiSocket.close();
      }
    };
    twilioSocket.on("close", closeBoth);
    openAiSocket.on("close", closeBoth);
    openAiSocket.on("error", (error) => app.log.error(error, "OpenAI WebSocket failed"));
  });
}