import { randomUUID } from "node:crypto";

import {
  IDKit,
  proofOfHuman,
  type IDKitRequest,
} from "@worldcoin/idkit-core";
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

type PendingWorldIdRequest = {
  request: IDKitRequest;
  uid: string;
  expiresAt: number;
};

const pendingRequests = new Map<string, PendingWorldIdRequest>();

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

async function verifyAndGrantHumanClaim(
  app: FastifyInstance,
  db: Firestore,
  uid: string,
  rawResponse: unknown,
): Promise<"verified" | "invalid" | "replayed"> {
  const parsed = idkitResponseSchema.safeParse(rawResponse);
  if (!parsed.success) return "invalid";
  const idkitResponse = parsed.data;
  if (
    idkitResponse.environment !== config.WORLD_ID_ENVIRONMENT ||
    (idkitResponse.action && idkitResponse.action !== config.WORLD_ID_ACTION)
  ) {
    return "invalid";
  }

  const verifyResponse = await fetch(
    `https://developer.world.org/api/v4/verify/${config.WORLD_ID_RP_ID}`,
    {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(rawResponse),
    },
  );
  if (!verifyResponse.ok) {
    app.log.warn({ status: verifyResponse.status, uid }, "World ID proof rejected");
    return "invalid";
  }

  const nullifiers = extractNullifiers(idkitResponse);
  if (nullifiers.length === 0) return "invalid";
  try {
    await Promise.all(
      nullifiers.map((nullifier) =>
        db
          .collection("world_id_nullifiers")
          .doc(`${config.WORLD_ID_ACTION}_${nullifier}`)
          .create({
            action: config.WORLD_ID_ACTION,
            nullifier,
            uid,
            verified_at: new Date().toISOString(),
          }),
      ),
    );
  } catch (error) {
    app.log.warn({ error, uid }, "World ID nullifier replay rejected");
    return "replayed";
  }

  const existing = await getAuth().getUser(uid);
  await getAuth().setCustomUserClaims(uid, {
    ...existing.customClaims,
    human_verified: true,
  });
  return "verified";
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

  app.post("/v1/world-id/start", { preHandler: authenticate }, async (request, reply) => {
    const { signingKeyHex } = requireWorldIdConfig();
    const signed = signRequest({
      signingKeyHex,
      action: config.WORLD_ID_ACTION,
    });
    const idkitRequest = await IDKit.request({
      app_id: config.WORLD_ID_APP_ID as `app_${string}`,
      action: config.WORLD_ID_ACTION,
      rp_context: {
        rp_id: config.WORLD_ID_RP_ID,
        nonce: signed.nonce,
        created_at: signed.createdAt,
        expires_at: signed.expiresAt,
        signature: signed.sig,
      },
      allow_legacy_proofs: false,
      environment: config.WORLD_ID_ENVIRONMENT,
    }).preset(proofOfHuman({ signal: request.user.uid }));

    const flowId = randomUUID();
    pendingRequests.set(flowId, {
      request: idkitRequest,
      uid: request.user.uid,
      expiresAt: Date.now() + 15 * 60 * 1000,
    });
    return reply.send({
      flow_id: flowId,
      connector_uri: idkitRequest.connectorURI,
      expires_at: new Date(Date.now() + 15 * 60 * 1000).toISOString(),
    });
  });

  app.get(
    "/v1/world-id/status/:flowId",
    { preHandler: authenticate },
    async (request, reply) => {
      const { flowId } = request.params as { flowId: string };
      const pending = pendingRequests.get(flowId);
      if (!pending || pending.uid !== request.user.uid) {
        return reply.code(404).send({ error: "world_id_flow_not_found" });
      }
      if (pending.expiresAt < Date.now()) {
        pendingRequests.delete(flowId);
        return reply.code(410).send({ error: "world_id_flow_expired" });
      }

      const status = await pending.request.pollOnce();
      if (status.type === "failed") {
        pendingRequests.delete(flowId);
        return reply.send({ state: "failed", error: status.error ?? "unknown" });
      }
      if (status.type !== "confirmed" || !status.result) {
        return reply.send({ state: status.type });
      }

      const outcome = await verifyAndGrantHumanClaim(
        app,
        db,
        request.user.uid,
        status.result,
      );
      pendingRequests.delete(flowId);
      if (outcome === "replayed") {
        return reply.code(409).send({ error: "world_id_nullifier_already_used" });
      }
      if (outcome !== "verified") {
        return reply.code(400).send({ error: "world_id_verification_failed" });
      }
      return reply.send({ state: "verified", human_verified: true });
    },
  );

  app.post("/v1/world-id/verify", { preHandler: authenticate }, async (request, reply) => {
    const parsed = idkitResponseSchema.safeParse(request.body);
    if (!parsed.success) {
      return reply.code(400).send({
        error: "invalid_world_id_response",
        details: z.flattenError(parsed.error),
      });
    }
    const outcome = await verifyAndGrantHumanClaim(
      app,
      db,
      request.user.uid,
      request.body,
    );
    if (outcome === "replayed") {
      return reply.code(409).send({ error: "world_id_nullifier_already_used" });
    }
    if (outcome !== "verified") {
      return reply.code(400).send({ error: "world_id_verification_failed" });
    }

    return reply.send({ human_verified: true });
  });
}
