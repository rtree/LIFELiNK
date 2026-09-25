import { getAuth, type DecodedIdToken } from "firebase-admin/auth";
import type { FastifyReply, FastifyRequest } from "fastify";

declare module "fastify" {
  interface FastifyRequest {
    user: DecodedIdToken;
  }
}

export async function authenticate(
  request: FastifyRequest,
  reply: FastifyReply,
): Promise<void> {
  const authorization = request.headers.authorization;
  if (!authorization?.startsWith("Bearer ")) {
    await reply.code(401).send({ error: "missing_bearer_token" });
    return;
  }

  try {
    request.user = await getAuth().verifyIdToken(authorization.slice(7), true);
  } catch {
    await reply.code(401).send({ error: "invalid_bearer_token" });
  }
}

export async function requireHumanVerification(
  request: FastifyRequest,
  reply: FastifyReply,
): Promise<void> {
  await authenticate(request, reply);
  if (reply.sent) {
    return;
  }

  if (request.user.human_verified !== true) {
    await reply.code(403).send({ error: "world_id_verification_required" });
  }
}
