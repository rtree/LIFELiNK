import { signRequest } from "@worldcoin/idkit-core/signing";
import { getAuth } from "firebase-admin/auth";
import type { Firestore } from "firebase-admin/firestore";
import type { FastifyInstance } from "fastify";
import { z } from "zod";

import { authenticate } from "./auth.js";
import { config } from "./config.js";

function requireWorldIdConfig() {
  if (!config.WORLD_ID_RP_SIGNING_KEY) {
    throw new Error("World ID signing key is not configured");
  }
  return { signingKeyHex: config.WORLD_ID_RP_SIGNING_KEY };
}

// World ID 4.0 uniqueness/session response shapes we care about; the rest of the
// payload is forwarded to the verifier as-is without re-encoding.
const idkitResponseSchema = z.object({
  protocol_version: z.string(),
  environment: z.enum(["production", "staging"]),
  action: z.string().optional(),
  responses: z
    .array(
      z.object({
        nullifier: z.string().optional(),
        session_nullifier: z.array(z.string()).optional(),
      }),
    )
    .min(1),
});

function extractNullifiers(idkitResponse: z.infer<typeof idkitResponseSchema>): string[] {
  return idkitResponse.responses
    .map((response) => response.nullifier ?? response.session_nullifier?.[0])
    .filter((nullifier): nullifier is string => typeof nullifier === "string");
}

export function registerWorldIdRoutes(app: FastifyInstance, db: Firestore): void {
  app.post("/v1/world-id/sign", { preHandler: authenticate }, async (_request, reply) => {
    const { signingKeyHex } = requireWorldIdConfig();
    const signed = signRequest({ signingKeyHex, action: config.WORLD_ID_ACTION });

    return reply.send({
      app_id: config.WORLD_ID_APP_ID,
      rp_id: config.WORLD_ID_RP_ID,
      action: config.WORLD_ID_ACTION,
      environment: config.WORLD_ID_ENVIRONMENT,
      sig: signed.sig,
      nonce: signed.nonce,
      created_at: signed.createdAt,
      expires_at: signed.expiresAt,
    });
  });

  app.post("/v1/world-id/verify", { preHandler: authenticate }, async (request, reply) => {
    const parsed = idkitResponseSchema.safeParse(request.body);
    if (!parsed.success) {
      return reply.code(400).send({
        error: "invalid_world_id_response",
        details: z.flattenError(parsed.error),
      });
    }
    const idkitResponse = parsed.data;

    if (idkitResponse.environment !== config.WORLD_ID_ENVIRONMENT) {
      return reply.code(400).send({ error: "world_id_environment_mismatch" });
    }
    if (idkitResponse.action && idkitResponse.action !== config.WORLD_ID_ACTION) {
      return reply.code(400).send({ error: "world_id_action_mismatch" });
    }

    const verifyResponse = await fetch(
      `https://developer.world.org/api/v4/verify/${config.WORLD_ID_RP_ID}`,
      {
        method: "POST",
        headers: { "content-type": "application/json" },
        // Forward the IDKit payload exactly as received; do not re-encode fields.
        body: JSON.stringify(request.body),
      },
    );

    if (!verifyResponse.ok) {
      app.log.warn(
        { status: verifyResponse.status, uid: request.user.uid },
        "World ID proof verification rejected",
      );
      return reply.code(400).send({ error: "world_id_verification_failed" });
    }

    const nullifiers = extractNullifiers(idkitResponse);
    if (nullifiers.length === 0) {
      return reply.code(400).send({ error: "world_id_missing_nullifier" });
    }

    try {
      // Firestore `create()` fails atomically if the doc already exists, giving
      // us the UNIQUE(action, nullifier) constraint the World ID docs require.
      await Promise.all(
        nullifiers.map((nullifier) =>
          db
            .collection("world_id_nullifiers")
            .doc(`${config.WORLD_ID_ACTION}_${nullifier}`)
            .create({
              action: config.WORLD_ID_ACTION,
              nullifier,
              uid: request.user.uid,
              verified_at: new Date().toISOString(),
            }),
        ),
      );
    } catch (error) {
      app.log.warn({ error, uid: request.user.uid }, "World ID nullifier replay rejected");
      return reply.code(409).send({ error: "world_id_nullifier_already_used" });
    }

    const existing = await getAuth().getUser(request.user.uid);
    await getAuth().setCustomUserClaims(request.user.uid, {
      ...existing.customClaims,
      human_verified: true,
    });

    return reply.send({ human_verified: true });
  });
}
