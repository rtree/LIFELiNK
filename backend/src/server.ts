import { createHash, randomInt, randomUUID } from "node:crypto";

import formbody from "@fastify/formbody";
import websocket from "@fastify/websocket";
import { applicationDefault, getApps, initializeApp } from "firebase-admin/app";
import { FieldValue, Timestamp, getFirestore } from "firebase-admin/firestore";
import Fastify from "fastify";
import twilio from "twilio";
import { z } from "zod";

import { authenticate, requireHumanVerification } from "./auth.js";
import { config } from "./config.js";
import { notifyDiscordContacts, registerDiscordRoutes, relayCallTranscript } from "./discord.js";
import {
  injectEmergencyUpdate,
  isValidTwilioRequest,
  mediaStreamTwiml,
  placeEmergencyCall,
  registerMediaBridge,
} from "./voice.js";
import { registerWorldIdRoutes } from "./worldid.js";

if (getApps().length === 0) {
  initializeApp({
    credential: applicationDefault(),
    projectId: config.GOOGLE_CLOUD_PROJECT,
  });
}

const db = getFirestore();
const app = Fastify({
  logger: {
    redact: [
      "req.headers.authorization",
      "body.phone",
      "body.latitude",
      "body.longitude",
      "body.accuracy_m",
      "body.location_snapshot.latitude",
      "body.location_snapshot.longitude",
      "body.location_snapshot.accuracy_m",
      "body.location.latitude",
      "body.location.longitude",
      "body.location.accuracy_m",
    ],
  },
});

await app.register(formbody);
await app.register(websocket);

registerWorldIdRoutes(app, db);
registerDiscordRoutes(app, db);

registerMediaBridge(app, async (eventId, callSid) => {
  const eventRef = db.collection("emergency_events").doc(eventId);
  const event = await db.runTransaction(async (transaction) => {
    const snapshot = await transaction.get(eventRef);
    if (!snapshot.exists) return null;
    const storedCallSid = snapshot.get("twilio_call_sid") as string | undefined;
    if (storedCallSid && storedCallSid !== callSid) return null;
    if (!new Set(["accepted", "dialing", "in_progress"]).has(snapshot.get("state") as string)) {
      return null;
    }
    if (!storedCallSid) {
      transaction.update(eventRef, {
        twilio_call_sid: callSid,
        state: "in_progress",
        updated_at: FieldValue.serverTimestamp(),
      });
    }
    return snapshot;
  });
  if (!event) {
    return null;
  }
  const location = event.get("location_snapshot") as
    | {
        address?: string | null;
        latitude?: number;
        longitude?: number;
        accuracy_m?: number;
        captured_at?: string;
        battery_percent?: number | null;
        battery_charging?: boolean | null;
        motion_state?: string | null;
        motion_peak_g?: number | null;
      }
    | null;
  return {
    address: location?.address ?? null,
    latitude: location?.latitude ?? null,
    longitude: location?.longitude ?? null,
    accuracyM: location?.accuracy_m ?? null,
    capturedAt: location?.captured_at ?? null,
    batteryPercent: location?.battery_percent ?? null,
    batteryCharging: location?.battery_charging ?? null,
    motionState: location?.motion_state ?? null,
    motionPeakG: location?.motion_peak_g ?? null,
    initialNote: (event.get("initial_note") as string | null) ?? null,
    carrierConference: event.get("mode") === "carrier_conference",
  };
}, (eventId, speaker, text) => relayCallTranscript(db, app.log, eventId, speaker, text));

const contactSchema = z.object({
  name: z.string().trim().min(1).max(80),
  phone: z.string().regex(/^\+[1-9]\d{7,14}$/, "phone must use E.164 format"),
});

const deviceSignalsSchema = {
  battery_percent: z.number().int().min(0).max(100).nullish(),
  battery_charging: z.boolean().nullish(),
  motion_state: z.enum(["still", "moving", "shaking"]).nullish(),
  motion_peak_g: z.number().nonnegative().nullish(),
};

const locationSchema = z.object({
  latitude: z.number().min(-90).max(90),
  longitude: z.number().min(-180).max(180),
  accuracy_m: z.number().nonnegative(),
  captured_at: z.iso.datetime(),
  address: z.string().trim().max(500).nullable(),
  geocoded_at: z.iso.datetime().nullable(),
  ...deviceSignalsSchema,
});

// Hackathon privacy policy: GPS coordinates are accepted from the device (still
// useful transiently) but never written to Firestore. `accuracy_m` IS kept, and
// so are battery and motion, because none of them reveal where the person is.
function sanitizeLocationForPersistence(location: z.infer<typeof locationSchema>) {
  return {
    address: location.address,
    accuracy_m: location.accuracy_m,
    captured_at: location.captured_at,
    geocoded_at: location.geocoded_at,
    battery_percent: location.battery_percent ?? null,
    battery_charging: location.battery_charging ?? null,
    motion_state: location.motion_state ?? null,
    motion_peak_g: location.motion_peak_g ?? null,
  };
}

const emergencyEventSchema = z.object({
  emergency_event_id: z.uuid(),
  contact_id: z.string().min(1).max(128),
  trigger_type: z.enum(["screen_button", "ble"]),
  trigger_source: z.enum(["beacon", "gatt"]).nullish(),
  location_snapshot: z
    .object({
      latitude: z.number().min(-90).max(90),
      longitude: z.number().min(-180).max(180),
      accuracy_m: z.number().nonnegative(),
      captured_at: z.iso.datetime(),
      address: z.string().max(500).nullable(),
      geocoded_at: z.iso.datetime().nullable(),
      ...deviceSignalsSchema,
    })
    .nullable(),
  initial_note: z.string().max(1000).nullable(),
  mode: z.enum(["outbound", "carrier_conference"]).default("outbound"),
});

const JOIN_CODE_TTL_MS = 10 * 60 * 1000;

function hashJoinCode(code: string): string {
  return createHash("sha256").update(code).digest("hex");
}

function issueJoinCode() {
  const code = randomInt(0, 1_000_000).toString().padStart(6, "0");
  return {
    code,
    hash: hashJoinCode(code),
    expiresAt: Timestamp.fromMillis(Date.now() + JOIN_CODE_TTL_MS),
  };
}

const emergencyUpdateSchema = z.discriminatedUnion("type", [
  z.object({
    update_id: z.uuid(),
    type: z.literal("note"),
    text: z.string().trim().min(1).max(1000),
  }),
  z.object({
    update_id: z.uuid(),
    type: z.literal("location"),
    location: locationSchema,
  }),
]);

function formatEmergencyUpdate(
  update: z.infer<typeof emergencyUpdateSchema>,
): string {
  if (update.type === "note") {
    return `A new note from the person: ${update.text}`;
  }
  const location = update.location;
  const parts = [
    `Updated location: ${location.address ?? "unknown area"}`,
    `accuracy about ${Math.round(location.accuracy_m)} meters`,
    `captured at ${location.captured_at}`,
  ];
  if (typeof location.battery_percent === "number") {
    parts.push(
      `phone battery ${location.battery_percent} percent` +
        (location.battery_charging === true ? " and charging" : ""),
    );
  }
  if (location.motion_state === "shaking") {
    parts.push("the phone is being shaken hard right now");
  } else if (location.motion_state === "moving") {
    parts.push("the phone is moving");
  }
  return `${parts.join(". ")}.`;
}

app.get("/health", async () => ({ status: "ok" }));

app.get(
  "/v1/me",
  { preHandler: authenticate },
  async (request) => ({
    uid: request.user.uid,
    human_verified: request.user.human_verified === true,
  }),
);

app.post(
  "/v1/contacts",
  { preHandler: authenticate },
  async (request, reply) => {
    const parsed = contactSchema.safeParse(request.body);
    if (!parsed.success) {
      return reply.code(400).send({
        error: "invalid_contact",
        details: z.flattenError(parsed.error),
      });
    }

    const contactId = randomUUID();
    await db
      .collection("users")
      .doc(request.user.uid)
      .collection("contacts")
      .doc(contactId)
      .set({
        name: parsed.data.name,
        phone_e164: parsed.data.phone,
        enabled: true,
        created_at: FieldValue.serverTimestamp(),
        updated_at: FieldValue.serverTimestamp(),
      });

    return reply.code(201).send({
      contact_id: contactId,
      name: parsed.data.name,
      phone_masked: `***${parsed.data.phone.slice(-4)}`,
    });
  },
);

app.post(
  "/v1/locations",
  { preHandler: authenticate },
  async (request, reply) => {
    const parsed = locationSchema.safeParse(request.body);
    if (!parsed.success) {
      return reply.code(400).send({
        error: "invalid_location",
        details: z.flattenError(parsed.error),
      });
    }

    const locationRef = db
      .collection("users")
      .doc(request.user.uid)
      .collection("locations")
      .doc();
    await locationRef.set({
      ...sanitizeLocationForPersistence(parsed.data),
      created_at: FieldValue.serverTimestamp(),
    });

    return reply.code(201).send({ location_id: locationRef.id });
  },
);

app.post(
  "/v1/emergency-events",
  { preHandler: requireHumanVerification },
  async (request, reply) => {
    const parsed = emergencyEventSchema.safeParse(request.body);
    if (!parsed.success) {
      return reply.code(400).send({
        error: "invalid_emergency_event",
        details: z.flattenError(parsed.error),
      });
    }

    const contactRef = db
      .collection("users")
      .doc(request.user.uid)
      .collection("contacts")
      .doc(parsed.data.contact_id);
    const eventRef = db
      .collection("emergency_events")
      .doc(parsed.data.emergency_event_id);

    const carrierConference = parsed.data.mode === "carrier_conference";
    if (carrierConference && !config.TWILIO_AI_INBOUND_NUMBER) {
      return reply.code(503).send({ error: "carrier_conference_unavailable" });
    }
    const joinCode = carrierConference ? issueJoinCode() : null;

    const result = await db.runTransaction(async (transaction) => {
      const [contact, existingEvent] = await Promise.all([
        transaction.get(contactRef),
        transaction.get(eventRef),
      ]);

      if (!contact.exists || contact.get("enabled") !== true) {
        return { outcome: "contact_not_found" as const };
      }

      if (existingEvent.exists) {
        if (existingEvent.get("uid") !== request.user.uid) {
          return { outcome: "event_id_conflict" as const };
        }
        const state = existingEvent.get("state") as string;
        // The code is only stored hashed, so a retried request gets a fresh one while unused.
        const reissue =
          joinCode !== null &&
          existingEvent.get("mode") === "carrier_conference" &&
          state === "accepted" &&
          !existingEvent.get("join_used_at");
        if (reissue) {
          transaction.update(eventRef, {
            join_code_hash: joinCode.hash,
            join_expires_at: joinCode.expiresAt,
            updated_at: FieldValue.serverTimestamp(),
          });
        }
        return {
          outcome: "existing" as const,
          state,
          reissued: reissue,
          destination: contact.get("phone_e164") as string,
        };
      }

      transaction.create(eventRef, {
        uid: request.user.uid,
        participant_uids: [],
        contact_id: parsed.data.contact_id,
        trigger_type: parsed.data.trigger_type,
        trigger_source:
          parsed.data.trigger_type === "ble"
            ? (parsed.data.trigger_source ?? "beacon")
            : null,
        state: "accepted",
        mode: parsed.data.mode,
        ...(joinCode
          ? {
              join_code_hash: joinCode.hash,
              join_expires_at: joinCode.expiresAt,
              join_used_at: null,
            }
          : {}),
        location_snapshot: parsed.data.location_snapshot
          ? sanitizeLocationForPersistence(parsed.data.location_snapshot)
          : null,
        initial_note: parsed.data.initial_note,
        created_at: FieldValue.serverTimestamp(),
        updated_at: FieldValue.serverTimestamp(),
      });
      return {
        outcome: "created" as const,
        state: "accepted",
        destination: contact.get("phone_e164") as string,
        reissued: joinCode !== null,
      };
    });

    if (result.outcome === "contact_not_found") {
      return reply.code(404).send({ error: "contact_not_found" });
    }
    if (result.outcome === "event_id_conflict") {
      return reply.code(409).send({ error: "emergency_event_id_conflict" });
    }

    if (result.outcome === "created") {
      const snapshot = parsed.data.location_snapshot;
      void notifyDiscordContacts(db, app.log, {
        eventId: parsed.data.emergency_event_id,
        ownerUid: request.user.uid,
        prefecture: snapshot?.address ?? null,
        capturedAt: snapshot?.captured_at ?? null,
        note: parsed.data.initial_note ?? null,
      }).catch((error) => app.log.error({ error }, "Discord notification failed"));
    }

    if (result.outcome === "created" && !carrierConference) {
      try {
        const callSid = await placeEmergencyCall(
          parsed.data.emergency_event_id,
          result.destination,
        );
        await eventRef.update({
          state: "dialing",
          twilio_call_sid: callSid,
          updated_at: FieldValue.serverTimestamp(),
        });
        result.state = "dialing";
      } catch (error) {
        app.log.error({ error, emergencyEventId: parsed.data.emergency_event_id }, "Twilio call failed");
        await eventRef.update({
          state: "failed",
          failure_code: "twilio_call_failed",
          updated_at: FieldValue.serverTimestamp(),
        });
        return reply.code(502).send({
          error: "twilio_call_failed",
          emergency_event_id: parsed.data.emergency_event_id,
        });
      }
    }

    return reply.code(result.outcome === "created" ? 202 : 200).send({
      emergency_event_id: parsed.data.emergency_event_id,
      state: result.state,
      idempotent_replay: result.outcome === "existing",
      ...(joinCode && result.reissued
        ? {
            ai_number: config.TWILIO_AI_INBOUND_NUMBER,
            join_code: joinCode.code,
            join_expires_at: joinCode.expiresAt.toDate().toISOString(),
            contact_phone: result.destination,
          }
        : {}),
    });
  },
);

app.post(
  "/v1/emergency-events/:eventId/updates",
  { preHandler: authenticate },
  async (request, reply) => {
    const parsed = emergencyUpdateSchema.safeParse(request.body);
    if (!parsed.success) {
      return reply.code(400).send({
        error: "invalid_emergency_update",
        details: z.flattenError(parsed.error),
      });
    }

    const { eventId } = request.params as { eventId: string };
    const eventRef = db.collection("emergency_events").doc(eventId);
    const event = await eventRef.get();
    if (!event.exists || event.get("uid") !== request.user.uid) {
      return reply.code(404).send({ error: "emergency_event_not_found" });
    }
    if (!event.get("twilio_call_sid")) {
      return reply.code(409).send({ error: "call_not_started" });
    }
    if (!new Set(["dialing", "in_progress"]).has(event.get("state") as string)) {
      return reply.code(409).send({ error: "call_not_active" });
    }

    const updateRef = eventRef.collection("updates").doc(parsed.data.update_id);
    const existingUpdate = await updateRef.get();
    if (existingUpdate.exists && existingUpdate.get("delivered_to_ai_at")) {
      return reply.code(200).send({
        update_id: parsed.data.update_id,
        delivered_to_ai: true,
        idempotent_replay: true,
      });
    }

    const text = formatEmergencyUpdate(parsed.data);
    if (!existingUpdate.exists) {
      await updateRef.create({
        type: parsed.data.type,
        author_type: "owner",
        author_uid: request.user.uid,
        author_name: request.user.name ?? null,
        text,
        mentioned_uids: [],
        payload:
          parsed.data.type === "location"
            ? sanitizeLocationForPersistence(parsed.data.location)
            : { text: parsed.data.text },
        created_at: FieldValue.serverTimestamp(),
        delivered_to_ai_at: null,
      });
    }

    if (!injectEmergencyUpdate(eventId, text)) {
      return reply.code(409).send({
        error: "realtime_session_not_ready",
        update_id: parsed.data.update_id,
      });
    }
    await updateRef.update({ delivered_to_ai_at: FieldValue.serverTimestamp() });
    return reply.code(202).send({
      update_id: parsed.data.update_id,
      delivered_to_ai: true,
      idempotent_replay: existingUpdate.exists,
    });
  },
);

app.get(
  "/v1/emergency-events/:eventId",
  { preHandler: authenticate },
  async (request, reply) => {
    const { eventId } = request.params as { eventId: string };
    const event = await db.collection("emergency_events").doc(eventId).get();
    if (!event.exists || event.get("uid") !== request.user.uid) {
      return reply.code(404).send({ error: "emergency_event_not_found" });
    }
    const expiresAt = event.get("join_expires_at") as Timestamp | undefined;
    if (
      event.get("mode") === "carrier_conference" &&
      event.get("state") === "accepted" &&
      expiresAt &&
      expiresAt.toMillis() < Date.now()
    ) {
      await event.ref.update({
        state: "failed",
        failure_code: "join_code_expired",
        updated_at: FieldValue.serverTimestamp(),
      });
      return reply.send({
        emergency_event_id: event.id,
        state: "failed",
        failure_code: "join_code_expired",
      });
    }
    return reply.send({
      emergency_event_id: event.id,
      state: event.get("state") as string,
      failure_code: (event.get("failure_code") as string | null) ?? null,
    });
  },
);

app.post("/v1/twilio/status", async (request, reply) => {
  if (!isValidTwilioRequest(request)) {
    return reply.code(403).send({ error: "invalid_twilio_signature" });
  }
  const query = request.query as { emergencyEventId?: string };
  const body = request.body as { CallSid?: string; CallStatus?: string };
  if (!query.emergencyEventId || !body.CallSid || !body.CallStatus) {
    return reply.code(400).send({ error: "invalid_twilio_status" });
  }
  const eventRef = db.collection("emergency_events").doc(query.emergencyEventId);
  const eventOutcome = await db.runTransaction(async (transaction) => {
    const event = await transaction.get(eventRef);
    if (!event.exists) return "missing" as const;
    const storedCallSid = event.get("twilio_call_sid") as string | undefined;
    if (storedCallSid && storedCallSid !== body.CallSid) return "mismatch" as const;
    transaction.update(eventRef, {
      twilio_call_sid: body.CallSid,
      updated_at: FieldValue.serverTimestamp(),
    });
    return "matched" as const;
  });
  if (eventOutcome === "missing") {
    return reply.code(404).send({ error: "emergency_event_not_found" });
  }
  if (eventOutcome === "mismatch") {
    return reply.code(409).send({ error: "twilio_call_sid_mismatch" });
  }
  const stateByStatus: Record<string, string> = {
    initiated: "dialing",
    ringing: "dialing",
    "in-progress": "in_progress",
    completed: "completed",
    busy: "failed",
    failed: "failed",
    "no-answer": "failed",
    canceled: "failed",
  };
  await eventRef.update({
    state: stateByStatus[body.CallStatus] ?? body.CallStatus,
    twilio_call_sid: body.CallSid,
    updated_at: FieldValue.serverTimestamp(),
  });
  return reply.code(204).send();
});

app.post("/v1/twilio/stream-status", async (request, reply) => {
  if (!isValidTwilioRequest(request)) {
    return reply.code(403).send({ error: "invalid_twilio_signature" });
  }
  const query = request.query as { emergencyEventId?: string };
  const body = request.body as {
    CallSid?: string;
    StreamEvent?: string;
    StreamError?: string;
    StreamSid?: string;
  };
  app.log.info(
    {
      emergencyEventId: query.emergencyEventId ?? null,
      callSid: body.CallSid ?? null,
      streamSid: body.StreamSid ?? null,
      streamEvent: body.StreamEvent ?? null,
      streamError: body.StreamError ?? null,
    },
    "Twilio Media Stream status",
  );
  return reply.code(204).send();
});

function sendTwiml(reply: import("fastify").FastifyReply, twiml: string) {
  return reply.type("text/xml").send(twiml);
}

function hangupTwiml(): string {
  const response = new twilio.twiml.VoiceResponse();
  response.hangup();
  return response.toString();
}

app.post("/v1/twilio/inbound", async (request, reply) => {
  if (!isValidTwilioRequest(request)) {
    return reply.code(403).send({ error: "invalid_twilio_signature" });
  }
  const body = request.body as { CallSid?: string; To?: string };
  if (!config.TWILIO_AI_INBOUND_NUMBER || body.To !== config.TWILIO_AI_INBOUND_NUMBER) {
    return sendTwiml(reply, hangupTwiml());
  }
  const response = new twilio.twiml.VoiceResponse();
  const gather = response.gather({
    input: ["dtmf"],
    numDigits: 6,
    timeout: 15,
    action: `${config.BACKEND_URL}/v1/twilio/inbound/join`,
    method: "POST",
  });
  gather.say({ voice: "Polly.Joanna" }, "LIFELiNK. Enter your code.");
  response.hangup();
  app.log.info({ callSid: body.CallSid ?? null }, "AI inbound call awaiting join code");
  return sendTwiml(reply, response.toString());
});

app.post("/v1/twilio/inbound/join", async (request, reply) => {
  if (!isValidTwilioRequest(request)) {
    return reply.code(403).send({ error: "invalid_twilio_signature" });
  }
  const body = request.body as { CallSid?: string; Digits?: string; To?: string };
  if (
    !body.CallSid ||
    !body.Digits ||
    !/^\d{6}$/.test(body.Digits) ||
    body.To !== config.TWILIO_AI_INBOUND_NUMBER
  ) {
    app.log.warn({ callSid: body.CallSid ?? null }, "AI inbound join rejected: malformed");
    return sendTwiml(reply, hangupTwiml());
  }
  const callSid = body.CallSid;
  const candidates = await db
    .collection("emergency_events")
    .where("join_code_hash", "==", hashJoinCode(body.Digits))
    .limit(2)
    .get();
  const eventId = candidates.size === 1 ? (candidates.docs[0]?.id ?? null) : null;
  const bound = eventId
    ? await db.runTransaction(async (transaction) => {
        const eventRef = db.collection("emergency_events").doc(eventId);
        const event = await transaction.get(eventRef);
        const expiresAt = event.get("join_expires_at") as Timestamp | undefined;
        if (
          !event.exists ||
          event.get("mode") !== "carrier_conference" ||
          event.get("state") !== "accepted" ||
          event.get("join_used_at") ||
          event.get("twilio_call_sid") ||
          !expiresAt ||
          expiresAt.toMillis() < Date.now()
        ) {
          return false;
        }
        transaction.update(eventRef, {
          join_used_at: FieldValue.serverTimestamp(),
          twilio_call_sid: callSid,
          state: "in_progress",
          updated_at: FieldValue.serverTimestamp(),
        });
        return true;
      })
    : false;
  if (!bound || !eventId) {
    app.log.warn({ callSid, matches: candidates.size }, "AI inbound join rejected");
    return sendTwiml(reply, hangupTwiml());
  }
  app.log.info({ callSid, emergencyEventId: eventId }, "AI inbound call joined event");
  return sendTwiml(reply, mediaStreamTwiml(eventId));
});

app.post("/v1/twilio/inbound/status", async (request, reply) => {
  if (!isValidTwilioRequest(request)) {
    return reply.code(403).send({ error: "invalid_twilio_signature" });
  }
  const body = request.body as { CallSid?: string; CallStatus?: string };
  const ended = new Set(["completed", "busy", "failed", "no-answer", "canceled"]);
  if (!body.CallSid || !body.CallStatus || !ended.has(body.CallStatus)) {
    return reply.code(204).send();
  }
  const events = await db
    .collection("emergency_events")
    .where("twilio_call_sid", "==", body.CallSid)
    .limit(1)
    .get();
  const event = events.docs[0];
  if (event && event.get("mode") === "carrier_conference") {
    await event.ref.update({
      state: "completed",
      updated_at: FieldValue.serverTimestamp(),
    });
    app.log.info(
      { callSid: body.CallSid, emergencyEventId: event.id, callStatus: body.CallStatus },
      "AI inbound call ended",
    );
  }
  return reply.code(204).send();
});

app.setErrorHandler((error, _request, reply) => {
  app.log.error(error);
  void reply.code(500).send({ error: "internal_error" });
});

await app.listen({ host: "0.0.0.0", port: config.PORT });