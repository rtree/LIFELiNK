import { createHash, createPublicKey, randomBytes, verify } from "node:crypto";

import { getAuth } from "firebase-admin/auth";
import { FieldValue, Timestamp, type Firestore } from "firebase-admin/firestore";
import type { FastifyInstance, FastifyReply } from "fastify";
import { z } from "zod";

import { authenticate } from "./auth.js";
import { config } from "./config.js";
import { injectEmergencyUpdate } from "./voice.js";

const DISCORD_API = "https://discord.com/api/v10";
const INVITE_TTL_MS = 24 * 60 * 60 * 1000;
const REPLY_MAX_LENGTH = 500;

declare module "fastify" {
  interface FastifyRequest {
    rawBody?: string;
  }
}

type DiscordApiResult = { ok: true; body: any } | { ok: false; status: number; code?: number };

async function discordBotRequest(path: string, body: unknown, retried = false): Promise<DiscordApiResult> {
  if (!config.DISCORD_BOT_TOKEN) return { ok: false, status: 0 };
  const response = await fetch(`${DISCORD_API}${path}`, {
    method: "POST",
    headers: {
      authorization: `Bot ${config.DISCORD_BOT_TOKEN}`,
      "content-type": "application/json",
    },
    body: JSON.stringify(body),
  });
  const json = await response.json().catch(() => ({}));
  if (response.status === 429 && !retried) {
    await new Promise((resolve) => setTimeout(resolve, Math.min(5_000, Number(json?.retry_after ?? 1) * 1000)));
    return discordBotRequest(path, body, true);
  }
  if (!response.ok) return { ok: false, status: response.status, code: json?.code };
  return { ok: true, body: json };
}

const replyButtonRow = (eventId: string, label = "状況を返信") => ({
  type: 1,
  components: [{ type: 2, style: 1, label, custom_id: `reply:${eventId}` }],
});

async function sendDirectMessage(
  discordUserId: string,
  content: string,
  button: { label: string; customId: string },
): Promise<{ ok: true; channelId: string; messageId: string } | { ok: false; status: number; code?: number }> {
  const channel = await discordBotRequest("/users/@me/channels", { recipient_id: discordUserId });
  if (!channel.ok) return channel;
  const message = await discordBotRequest(`/channels/${channel.body.id}/messages`, {
    content,
    allowed_mentions: { parse: [] },
    components: [
      { type: 1, components: [{ type: 2, style: 1, label: button.label, custom_id: button.customId }] },
    ],
  });
  if (!message.ok) return message;
  return { ok: true, channelId: channel.body.id, messageId: message.body.id };
}

const ed25519PublicKey = createPublicKey({
  key: Buffer.concat([Buffer.from("302a300506032b6570032100", "hex"), Buffer.from(config.DISCORD_PUBLIC_KEY, "hex")]),
  format: "der",
  type: "spki",
});

function isValidDiscordSignature(signature: unknown, timestamp: unknown, rawBody: string | undefined) {
  if (typeof signature !== "string" || typeof timestamp !== "string" || rawBody === undefined) return false;
  try {
    return verify(null, Buffer.from(timestamp + rawBody), ed25519PublicKey, Buffer.from(signature, "hex"));
  } catch {
    return false;
  }
}

const sha256 = (value: string) => createHash("sha256").update(value).digest("hex");
const redirectUri = () => `${config.BACKEND_URL}/v1/discord/oauth/callback`;

function htmlPage(reply: FastifyReply, status: number, title: string, body: string) {
  const escape = (text: string) => text.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);
  return reply
    .code(status)
    .header("content-type", "text/html; charset=utf-8")
    .header("cache-control", "no-store")
    .header("referrer-policy", "no-referrer")
    .send(
      `<!doctype html><html lang="ja"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">` +
        `<title>${escape(title)}</title><style>body{font-family:sans-serif;max-width:32rem;margin:2rem auto;padding:0 1rem;line-height:1.6}a.btn{display:inline-block;background:#5865F2;color:#fff;padding:.8rem 1.2rem;border-radius:8px;text-decoration:none}</style></head>` +
        `<body><h1>${escape(title)}</h1>${body}</body></html>`,
    );
}

async function ownerDisplayName(uid: string) {
  const user = await getAuth().getUser(uid).catch(() => null);
  return user?.displayName || "LIFELiNK 利用者";
}

const invitePathPattern = /^[A-Za-z0-9]{10,128}\.[A-Za-z0-9]{10,40}\.[A-Za-z0-9_-]{20,64}$/;
const inviteParamSchema = z.object({ invite: z.string().regex(invitePathPattern) });
const callbackQuerySchema = z.object({ code: z.string().min(1), state: z.string().regex(invitePathPattern) });
const contactParamSchema = z.object({ id: z.string().regex(/^\d{5,25}$/) });

export function registerDiscordRoutes(app: FastifyInstance, db: Firestore) {
  const invitesOf = (uid: string) => db.collection("users").doc(uid).collection("discordInvites");
  const contactsOf = (uid: string) => db.collection("users").doc(uid).collection("discordContacts");

  app.post("/v1/discord/invites", { preHandler: authenticate }, async (request, reply) => {
    const token = randomBytes(24).toString("base64url");
    const inviteRef = invitesOf(request.user.uid).doc();
    const expiresAt = Timestamp.fromMillis(Date.now() + INVITE_TTL_MS);
    await inviteRef.create({
      token_hash: sha256(token),
      created_at: FieldValue.serverTimestamp(),
      expires_at: expiresAt,
      used_at: null,
      used_by_discord_user_id: null,
    });
    return reply.code(201).send({
      invite_url: `${config.BACKEND_URL}/v1/discord/invite/${request.user.uid}.${inviteRef.id}.${token}`,
      expires_at: expiresAt.toDate().toISOString(),
    });
  });

  async function findInvite(ownerUid: string, inviteId: string, token: string) {
    const doc = await invitesOf(ownerUid).doc(inviteId).get();
    if (!doc.exists || doc.get("token_hash") !== sha256(token)) return null;
    const expired = (doc.get("expires_at") as Timestamp).toMillis() < Date.now();
    return { doc, ownerUid, expired, used: doc.get("used_at") != null };
  }

  app.get("/v1/discord/invite/:invite", async (request, reply) => {
    const parsed = inviteParamSchema.safeParse(request.params);
    if (!parsed.success) return htmlPage(reply, 404, "招待が見つかりません", "<p>URL を確認してください。</p>");
    const [ownerUid = "", inviteId = "", token = ""] = parsed.data.invite.split(".");
    const invite = await findInvite(ownerUid, inviteId, token);
    if (!invite || invite.expired || invite.used) {
      return htmlPage(reply, 410, "この招待は使えません", "<p>期限切れか使用済みです。発行者に新しい招待を依頼してください。</p>");
    }
    const owner = await ownerDisplayName(invite.ownerUid);
    const authorize = new URL("https://discord.com/oauth2/authorize");
    authorize.searchParams.set("client_id", config.DISCORD_APPLICATION_ID);
    authorize.searchParams.set("response_type", "code");
    authorize.searchParams.set("redirect_uri", redirectUri());
    authorize.searchParams.set("scope", "identify");
    authorize.searchParams.set("state", parsed.data.invite);
    const escapedOwner = owner.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);
    return htmlPage(
      reply,
      200,
      "LIFELiNK 緊急連絡先への招待",
      `<p><b>${escapedOwner}</b> さんが、あなたを LIFELiNK の緊急連絡先（Discord）に招待しています。</p>` +
        `<p>同意すると、${escapedOwner} さんが緊急ボタンを押したときに、LIFELiNK Bot から Discord の DM が届きます。DM には次の情報だけが含まれます。</p>` +
        `<ul><li>緊急連絡であること・発信者名</li><li>都道府県レベルの現在地と取得時刻（詳細住所・座標は含みません）</li><li>発信者が入力した状況メモ</li></ul>` +
        `<p>DM の「状況を返信」から送った内容は、そのイベントの参考情報として記録されます。LIFELiNK はあなたの Discord ユーザー ID と表示名だけを保存し、友人一覧やメッセージは読みません。</p>` +
        `<p><a class="btn" href="${authorize.toString().replace(/&/g, "&amp;")}">同意して Discord で本人確認</a></p>`,
    );
  });

  app.get("/v1/discord/oauth/callback", async (request, reply) => {
    const parsed = callbackQuerySchema.safeParse(request.query);
    if (!parsed.success) return htmlPage(reply, 400, "登録できませんでした", "<p>認可がキャンセルされたか、URL が不正です。</p>");
    const [ownerUid, inviteId, token] = parsed.data.state.split(".");
    if (!ownerUid || !inviteId || !token || !config.DISCORD_CLIENT_SECRET) {
      return htmlPage(reply, 400, "登録できませんでした", "<p>招待情報が不正です。</p>");
    }
    const invite = await findInvite(ownerUid, inviteId, token);
    if (!invite || invite.expired || invite.used) {
      return htmlPage(reply, 410, "この招待は使えません", "<p>期限切れか使用済みです。</p>");
    }

    const tokenResponse = await fetch(`${DISCORD_API}/oauth2/token`, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        client_id: config.DISCORD_APPLICATION_ID,
        client_secret: config.DISCORD_CLIENT_SECRET,
        grant_type: "authorization_code",
        code: parsed.data.code,
        redirect_uri: redirectUri(),
      }),
    });
    if (!tokenResponse.ok) {
      app.log.warn({ status: tokenResponse.status }, "Discord OAuth code exchange failed");
      return htmlPage(reply, 502, "登録できませんでした", "<p>Discord での本人確認に失敗しました。もう一度お試しください。</p>");
    }
    const { access_token: accessToken } = (await tokenResponse.json()) as { access_token: string };
    const meResponse = await fetch(`${DISCORD_API}/users/@me`, { headers: { authorization: `Bearer ${accessToken}` } });
    if (!meResponse.ok) {
      return htmlPage(reply, 502, "登録できませんでした", "<p>Discord アカウント情報を取得できませんでした。</p>");
    }
    const me = (await meResponse.json()) as { id: string; username: string; global_name?: string | null };
    const displayName = me.global_name || me.username;

    const contactRef = contactsOf(invite.ownerUid).doc(me.id);
    const outcome = await db.runTransaction(async (transaction) => {
      const fresh = await transaction.get(invite.doc.ref);
      if (!fresh.exists || fresh.get("used_at") != null) return "used";
      transaction.update(invite.doc.ref, {
        used_at: FieldValue.serverTimestamp(),
        used_by_discord_user_id: me.id,
      });
      transaction.set(contactRef, {
        discord_user_id: me.id,
        display_name_snapshot: displayName,
        status: "active",
        invite_id: inviteId,
        consented_at: FieldValue.serverTimestamp(),
        created_at: FieldValue.serverTimestamp(),
        updated_at: FieldValue.serverTimestamp(),
        last_test_dm: null,
      });
      return "linked";
    });
    if (outcome === "used") return htmlPage(reply, 410, "この招待は使えません", "<p>使用済みです。</p>");
    return htmlPage(
      reply,
      200,
      "登録が完了しました",
      "<p>LIFELiNK の緊急連絡先として登録されました。このページは閉じて構いません。発行者がテスト DM を送ると、LIFELiNK Bot から DM が届きます。</p>",
    );
  });

  app.get("/v1/discord/contacts", { preHandler: authenticate }, async (request) => {
    const snapshot = await contactsOf(request.user.uid).where("status", "==", "active").get();
    return {
      contacts: snapshot.docs.map((doc) => {
        const test = doc.get("last_test_dm") as Record<string, any> | null;
        return {
          id: doc.id,
          display_name: doc.get("display_name_snapshot") as string,
          last_test_dm: test
            ? {
                status: test.status,
                error_code: test.error_code ?? null,
                acknowledged: test.acknowledged_at != null,
              }
            : null,
        };
      }),
    };
  });

  app.delete("/v1/discord/contacts/:id", { preHandler: authenticate }, async (request, reply) => {
    const parsed = contactParamSchema.safeParse(request.params);
    if (!parsed.success) return reply.code(400).send({ error: "invalid_contact_id" });
    const ref = contactsOf(request.user.uid).doc(parsed.data.id);
    if (!(await ref.get()).exists) return reply.code(404).send({ error: "contact_not_found" });
    await ref.update({ status: "revoked", updated_at: FieldValue.serverTimestamp() });
    return reply.code(204).send();
  });

  app.post("/v1/discord/contacts/:id/test", { preHandler: authenticate }, async (request, reply) => {
    const parsed = contactParamSchema.safeParse(request.params);
    if (!parsed.success) return reply.code(400).send({ error: "invalid_contact_id" });
    const ref = contactsOf(request.user.uid).doc(parsed.data.id);
    const contact = await ref.get();
    if (!contact.exists || contact.get("status") !== "active") return reply.code(404).send({ error: "contact_not_found" });
    const owner = await ownerDisplayName(request.user.uid);
    const result = await sendDirectMessage(
      parsed.data.id,
      `【LIFELiNK テスト】${owner} さんの緊急連絡先として登録されています。これはテストです。届いていたら下のボタンを押してください。`,
      { label: "受信を確認", customId: `ack:${request.user.uid}` },
    );
    await ref.update({
      last_test_dm: result.ok
        ? { status: "sent", message_id: result.messageId, attempted_at: FieldValue.serverTimestamp(), acknowledged_at: null }
        : { status: "failed", error_code: result.code ?? null, http_status: result.status, attempted_at: FieldValue.serverTimestamp() },
      updated_at: FieldValue.serverTimestamp(),
    });
    if (!result.ok) {
      app.log.warn({ status: result.status, code: result.code }, "Discord test DM failed");
      return reply.code(502).send({ error: "discord_dm_failed", discord_code: result.code ?? null, http_status: result.status });
    }
    return reply.code(202).send({ status: "sent" });
  });

  app.register(async (scope) => {
    scope.addContentTypeParser("application/json", { parseAs: "string" }, (request, body, done) => {
      request.rawBody = body as string;
      try {
        done(null, JSON.parse(body as string));
      } catch (error) {
        done(error as Error, undefined);
      }
    });

    scope.post("/v1/discord/interactions", async (request, reply) => {
      if (!isValidDiscordSignature(request.headers["x-signature-ed25519"], request.headers["x-signature-timestamp"], request.rawBody)) {
        return reply.code(401).send({ error: "invalid_signature" });
      }
      const interaction = request.body as any;
      if (interaction.type === 1) return { type: 1 };

      const user = interaction.user ?? interaction.member?.user;
      const customId: string = interaction.data?.custom_id ?? "";
      const ephemeral = (content: string) => ({ type: 4, data: { content, flags: 64 } });
      if (!user?.id) return ephemeral("ユーザーを確認できませんでした。");

      if (interaction.type === 3 && customId.startsWith("ack:")) {
        const ref = contactsOf(customId.slice(4)).doc(user.id);
        const contact = await ref.get();
        if (!contact.exists || contact.get("status") !== "active") return ephemeral("この連絡先は登録されていません。");
        await ref.update({ "last_test_dm.acknowledged_at": FieldValue.serverTimestamp(), updated_at: FieldValue.serverTimestamp() });
        return ephemeral("受信を確認しました。ありがとうございます。");
      }

      if (interaction.type === 3 && customId.startsWith("reply:")) {
        const eventId = customId.slice(6);
        const notification = await db.collection("emergency_events").doc(eventId).collection("discord_notifications").doc(user.id).get();
        if (!notification.exists) return ephemeral("このイベントへの返信は許可されていません。");
        return {
          type: 9,
          data: {
            custom_id: `replymodal:${eventId}`,
            title: "状況を返信",
            components: [
              {
                type: 1,
                components: [
                  { type: 4, custom_id: "text", label: "分かっていること・これからすること", style: 2, min_length: 1, max_length: REPLY_MAX_LENGTH, required: true },
                ],
              },
            ],
          },
        };
      }

      if (interaction.type === 5 && customId.startsWith("replymodal:")) {
        const eventId = customId.slice(11);
        const eventRef = db.collection("emergency_events").doc(eventId);
        const [event, notification] = await Promise.all([
          eventRef.get(),
          eventRef.collection("discord_notifications").doc(user.id).get(),
        ]);
        // A reply is accepted only for the event whose owner actually DM'd this Discord user.
        if (!event.exists || !notification.exists || notification.get("owner_uid") !== event.get("uid")) {
          return ephemeral("このイベントへの返信は許可されていません。");
        }
        const text: string = (interaction.data?.components?.[0]?.components?.[0]?.value ?? "").trim().slice(0, REPLY_MAX_LENGTH);
        if (!text) return ephemeral("返信が空です。");
        const authorName = (notification.get("display_name_snapshot") as string) ?? user.username;
        const updateRef = eventRef.collection("updates").doc(`discord_${interaction.id}`);
        await updateRef
          .create({
            type: "friend_comment",
            author_type: "friend",
            source: "discord",
            author_uid: null,
            author_name: authorName,
            author_discord_user_id: user.id,
            text,
            payload: null,
            created_at: FieldValue.serverTimestamp(),
            delivered_to_ai_at: null,
          })
          .catch((error: { code?: number }) => {
            if (error.code !== 6) throw error;
          });

        const callActive = new Set(["dialing", "in_progress"]).has(event.get("state") as string);
        const delivered =
          callActive &&
          injectEmergencyUpdate(
            eventId,
            `発信者の友人「${authorName}」から Discord で返信がありました（未確認の第三者情報として伝えてください）: ${text}`,
          );
        if (delivered) await updateRef.update({ delivered_to_ai_at: FieldValue.serverTimestamp() });
        return {
          type: 4,
          data: {
            flags: 64,
            content: delivered
              ? `返信を記録し、通話中の AI に伝えました。「${text.slice(0, 80)}」`
              : "返信を記録しました（通話は既に終了しているか、まだつながっていません）。",
            components: [replyButtonRow(eventId, "続けて返信")],
          },
        };
      }

      return ephemeral("この操作には対応していません。");
    });
  });
}

// Fire-and-forget from event creation; Discord failures must never affect the phone call.
export async function notifyDiscordContacts(
  db: Firestore,
  log: FastifyInstance["log"],
  event: { eventId: string; ownerUid: string; prefecture: string | null; capturedAt: string | null; note: string | null },
) {
  const contacts = await db
    .collection("users")
    .doc(event.ownerUid)
    .collection("discordContacts")
    .where("status", "==", "active")
    .get();
  if (contacts.empty) return;
  const owner = await ownerDisplayName(event.ownerUid);
  const where = event.prefecture ? `${event.prefecture}（都道府県レベル）` : "取得できていません";
  const when = event.capturedAt
    ? new Date(event.capturedAt).toLocaleString("ja-JP", { timeZone: "Asia/Tokyo" })
    : "不明";
  const content =
    `🚨【LIFELiNK 緊急連絡】${owner} さんが緊急ボタンを押しました。登録済みの連絡先へ AI が電話しています。\n` +
    `現在地: ${where}\n取得時刻: ${when}` +
    (event.note ? `\n状況メモ: ${event.note.slice(0, 300)}` : "") +
    `\n分かっていることがあれば「状況を返信」から送ってください。`;

  await Promise.all(
    contacts.docs.map(async (contact) => {
      const ref = db.collection("emergency_events").doc(event.eventId).collection("discord_notifications").doc(contact.id);
      try {
        await ref.create({
          owner_uid: event.ownerUid,
          display_name_snapshot: contact.get("display_name_snapshot"),
          status: "pending",
          created_at: FieldValue.serverTimestamp(),
          attempted_at: null,
        });
      } catch (error) {
        if ((error as { code?: number }).code === 6) return;
        throw error;
      }
      const result = await sendDirectMessage(contact.id, content, { label: "状況を返信", customId: `reply:${event.eventId}` });
      await ref.update(
        result.ok
          ? { status: "sent", channel_id: result.channelId, message_id: result.messageId, attempted_at: FieldValue.serverTimestamp() }
          : { status: "failed", error_code: result.code ?? null, http_status: result.status, attempted_at: FieldValue.serverTimestamp() },
      );
      if (!result.ok) log.warn({ status: result.status, code: result.code, emergencyEventId: event.eventId }, "Discord emergency DM failed");
    }),
  ).catch((error) => log.error({ error, emergencyEventId: event.eventId }, "Discord notification error"));
}

const transcriptQueues = new Map<string, Promise<void>>();

// Persists each finalized call utterance and mirrors it to every friend already DM'd for this event, in order.
export function relayCallTranscript(
  db: Firestore,
  log: FastifyInstance["log"],
  eventId: string,
  speaker: "contact" | "ai" | "system",
  text: string,
) {
  const previous = transcriptQueues.get(eventId) ?? Promise.resolve();
  const next = previous
    .then(async () => {
      const eventRef = db.collection("emergency_events").doc(eventId);
      await eventRef.collection("updates").add({
        type: speaker === "contact" ? "transcript_contact" : speaker === "ai" ? "transcript_ai" : "system",
        author_type: speaker,
        author_uid: null,
        author_name: speaker === "contact" ? "電話の相手" : speaker === "ai" ? "LIFELiNK AI" : "システム",
        text,
        payload: null,
        created_at: FieldValue.serverTimestamp(),
        delivered_to_ai_at: null,
      });
      const recipients = await eventRef.collection("discord_notifications").where("status", "==", "sent").get();
      const label = speaker === "contact" ? "📞 電話の相手" : speaker === "ai" ? "🤖 AI" : "ℹ️";
      await Promise.all(
        recipients.docs.map(async (recipient) => {
          const channelId = recipient.get("channel_id") as string | undefined;
          if (!channelId) return;
          const result = await discordBotRequest(`/channels/${channelId}/messages`, {
            content: `${label}: ${text.slice(0, 1800)}`,
            allowed_mentions: { parse: [] },
            components: [replyButtonRow(eventId)],
          });
          if (!result.ok) log.warn({ status: result.status, code: result.code, emergencyEventId: eventId }, "Discord transcript relay failed");
        }),
      );
    })
    .catch((error) => log.error({ error, emergencyEventId: eventId }, "Call transcript relay error"));
  transcriptQueues.set(eventId, next);
  void next.finally(() => {
    if (transcriptQueues.get(eventId) === next) transcriptQueues.delete(eventId);
  });
}
