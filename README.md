# LIFELiNK

> **You are never alone.** One button calls someone you trust—even when you cannot speak.

[Try the Android app / Download APK](https://lifelink-56179519-0e8c-4306-abad-ef9019fcec0e-4qp2xid4rq-an.a.run.app/) · ETHGlobal Tokyo 2026 · [World: Best Use of IDKit](https://ethglobal.com/events/tokyo2026/prizes/world)

## The idea

LIFELiNK calls a **pre-registered contact** when you press SOS on screen or long-press a linked BLE button. An AI explains what it knows; trusted friends get a Discord alert and can send updates into the call. The owner sees a live event feed.

What if crypto's next everyday use were not a wallet, stablecoin, or speculative asset, but the trust layer behind an ordinary phone call? **World ID lets us check that an account belongs to a unique human without collecting their legal ID just to enable SOS.** That's a concrete use of privacy-preserving verification in daily life, not a claim that Web2 cannot build safety apps.

Proof of Human is **not legal KYC**: it does not identify the caller by name, certify that an emergency is real, or replace recipient consent. The app still handles necessary personal data such as phone numbers, account details, and call transcripts. Optional profile details, including name and birth date, are separate from World ID verification.

## How it works

1. **Verify once.** Sign in with Google, then complete IDKit's **Proof of Human** request in World App. Our backend signs the request, verifies the result with World's v4 API, binds its nullifier to the Firebase account, and grants `human_verified`. World ID is the human gate; Google is the app login.
2. **Press SOS.** Three screen taps, a two-second hold, or a linked +Beacon long press starts one idempotent emergency event. The backend checks `human_verified` **before** allowing an event or a call. An unverified user receives 403; no phone call is started.
3. **Talk through AI.** The default **SOSV2-ambientMode** calls the AI and the contact from the user's SIM, then merges them into a carrier three-way call. With LIFELiNK set as the default phone app, the AI can hear the phone's surroundings while the screen stays dark. The older **SOSV1** instead has Twilio call the contact directly; it is also used when LIFELiNK is not the default phone app. Both routes use Twilio Media Streams and OpenAI Realtime for the AI leg.
4. **Bring in trusted people.** Consenting Discord contacts receive an alert and call transcripts. Their replies can be relayed by the AI during the call; the owner sees updates in a Firestore-backed feed. Only prefecture-level location—not raw coordinates—is persisted or spoken.

| Component | Job |
| --- | --- |
| Android / Kotlin / Compose + BLE | SOS button, +Beacon monitoring, World App handoff, live feed, SOSV2 carrier conference |
| World ID 4 / IDKit Core + Firebase Auth | Proof of Human verified server-side; custom claim gates the emergency endpoint |
| Cloud Run / Fastify / Firestore | Authorization, consent, idempotent events, live updates; secrets in Secret Manager |
| Twilio + OpenAI Realtime | Phone and AI audio bridge (G.711 μ-law, 8 kHz) |
| Discord OAuth + Bot | Opt-in friend alerts, transcripts, signed replies |

The current backend keeps live AI sessions in memory, so its Cloud Run service is deliberately limited to **one instance**. This is a hackathon prototype, **not** a guaranteed emergency-services replacement; BLE reception, carrier conferencing, call delivery, and consent/retention still need production hardening. In particular, the current invite does not explicitly disclose transcript sharing, and the person answering the call is not told about it; both must be addressed before release.

## World — Best Use of IDKit

The [prize criteria](https://ethglobal.com/events/tokyo2026/prizes/world) ask for a working application, a real **trust moment**, a **minimum sufficient credential** verified on the server, a successful and an alternative path, and a short integration debrief. Here is ours:

- **Trust moment:** Before the backend creates an SOS and initiates calls to real people using paid telephony and AI. Google login alone does not stop one person from creating many accounts to consume these resources.
- **Credential choice:** **Proof of Human** answers the needed question—unique human—without asking for passport ownership, legal identity, or a Selfie Check score. The proof is verified with World's v4 API on our backend; its nullifier is bound to one account, and `human_verified` is checked again at the SOS endpoint. No wallet or gas is needed.
- **Working success path:** On a real Android phone, World App completed the production proof, the backend set the claim, and SOS led to an actual call. A locked-phone +Beacon → SOSV2 carrier three-way call with AI and contact was also verified on the tested Samsung/SoftBank setup. [Evidence and current status](doc/ja-jp/tasks.md).
- **Meaningful alternative path:** Removing World ID verification in Settings was tested on the physical phone: SOS stopped; verifying again restored it. For an account without the claim, the backend returns **403 before calling Twilio**. An unavailable or failed first proof likewise cannot grant the claim. This is an authorization boundary, not just a UI badge.

**Integration debrief:** Time to first successful proof was **not measured**, so we cannot give a reliable duration. In practice, the SDK felt straightforward; the documentation, Skill, and Developer Portal MCP made the integration particularly easy to build with a coding agent. We did not find a major missing capability or documentation blocker. We did encounter a small Node `file://` WASM-loading workaround and a transient DNS error during polling; neither changed that overall experience. If we had to pick **one improvement**, an official end-to-end Android → World App → backend example would make this already smooth agentic workflow even faster.

## See it, build it, and know the limits

- [APK download and installation](https://lifelink-56179519-0e8c-4306-abad-ef9019fcec0e-4qp2xid4rq-an.a.run.app/) · [Implementation plan](doc/en/plan.md) · [Current tasks](doc/en/tasks.md) · [Reproducible MVP evidence](doc/en/mvp0.1.md)
- World proof and claim: [backend/src/worldid.ts](backend/src/worldid.ts), [backend/src/auth.ts](backend/src/auth.ts); event/call flow: [backend/src/server.ts](backend/src/server.ts), [backend/src/voice.ts](backend/src/voice.ts); SOSV2: [conference orchestration](android/app/src/main/java/com/rtree/LIFELiNK/ConferenceSosOrchestrator.kt).
- Detailed docs: [English](doc/en) · [日本語](doc/ja-jp). Existing World ID RP and production action must be reused; keys belong in Secret Manager, never in the repository.

**A proof of human should open a lifeline, not another account screen.**