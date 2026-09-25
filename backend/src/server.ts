import { randomUUID } from "node:crypto";

import { applicationDefault, getApps, initializeApp } from "firebase-admin/app";
import { FieldValue, getFirestore } from "firebase-admin/firestore";
import Fastify from "fastify";
import { z } from "zod";

import { authenticate, requireHumanVerification } from "./auth.js";
import { config } from "./config.js";

if (getApps().length === 0) {
  initializeApp({
    credential: applicationDefault(),
    projectId: config.GOOGLE_CLOUD_PROJECT,
  });
}

const db = getFirestore();
const app = Fastify({
  logger: {
    redact: ["req.headers.authorization", "body.phone"],
  },
});

const contactSchema = z.object({
  name: z.string().trim().min(1).max(80),
  phone: z.string().regex(/^\+[1-9]\d{7,14}$/, "phone must use E.164 format"),
});

const emergencyEventSchema = z.object({
  emergency_event_id: z.uuid(),
  contact_id: z.string().min(1).max(128),
  trigger_type: z.enum(["screen_button", "ble"]),
  location_snapshot: z
    .object({
      latitude: z.number().min(-90).max(90),
      longitude: z.number().min(-180).max(180),
      accuracy_m: z.number().nonnegative(),
      captured_at: z.iso.datetime(),
      address: z.string().max(500).nullable(),
      geocoded_at: z.iso.datetime().nullable(),
    })
    .nullable(),
  initial_note: z.string().max(1000).nullable(),
});

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
        return {
          outcome: "existing" as const,
          state: existingEvent.get("state") as string,
        };
      }

      transaction.create(eventRef, {
        uid: request.user.uid,
        contact_id: parsed.data.contact_id,
        trigger_type: parsed.data.trigger_type,
        state: "accepted",
        location_snapshot: parsed.data.location_snapshot,
        initial_note: parsed.data.initial_note,
        created_at: FieldValue.serverTimestamp(),
        updated_at: FieldValue.serverTimestamp(),
      });
      return { outcome: "created" as const, state: "accepted" };
    });

    if (result.outcome === "contact_not_found") {
      return reply.code(404).send({ error: "contact_not_found" });
    }
    if (result.outcome === "event_id_conflict") {
      return reply.code(409).send({ error: "emergency_event_id_conflict" });
    }

    return reply.code(result.outcome === "created" ? 202 : 200).send({
      emergency_event_id: parsed.data.emergency_event_id,
      state: result.state,
      idempotent_replay: result.outcome === "existing",
    });
  },
);

app.setErrorHandler((error, _request, reply) => {
  app.log.error(error);
  void reply.code(500).send({ error: "internal_error" });
});

await app.listen({ host: "0.0.0.0", port: config.PORT });