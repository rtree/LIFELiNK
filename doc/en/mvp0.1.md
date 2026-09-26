# LIFELiNK MVP 0.1 — Reproduction Steps as of the "It Just Works" Milestone (2026-09-26)

A snapshot taken at the point, around 13:00 JST on 2026-09-26, when the following end-to-end flow was confirmed working with a real device and real accounts. The purpose of this document is to make it possible to return to this state if a future refactor breaks something. **Do not write secret values here** (record only Secret names and versions).

> Physical button (+ Beacon) or on-screen button → Android Safety gate → Cloud Run → Twilio calls the registered contact → the OpenAI Realtime AI converses while conveying the current location (prefecture) and the note → at the same time, the Discord Bot DMs consenting friends → the call transcript (counterpart/AI) is relayed to the DM in real time → the friend can send "reply with situation" any number of times → the reply is saved to the same event and conveyed by the AI to the counterpart during the call.

**Changes since this snapshot**: On 2026-09-26, a fix was made to write `participant_uids` (always an empty array) and `trigger_source` (defaults to `beacon` when the trigger is `ble`) to `emergency_events`, and revision `lifelink-backend-00026-g6n` was deployed (because the fields assumed by [doc/en/plan.md](doc/en/plan.md) Chapter 6 and `firestore.rules` were not being written). The operating conditions remain as described in this file.

## 1. Fixed Points (verified working with this combination)

| Item | Value |
| --- | --- |
| Git | `25d9352` on `main` (the commit adding this file is tagged `mvp-0.1`) |
| Cloud Run | service `lifelink-backend` / region `asia-northeast1` / revision `lifelink-backend-00025-qb8` |
| Container image | `asia-northeast1-docker.pkg.dev/ethglobaltokyo2026lifelink/cloud-run-source-deploy/lifelink-backend@sha256:e96e3dffedfab516a07ff63aa9cd8b7f0ad4388a3610d8bebd4412076002d7f5` |
| Cloud Run config | SA `lifelink-backend@ethglobaltokyo2026lifelink.iam.gserviceaccount.com`, `maxScale=1` (required, explained below), `minScale=0`, concurrency 80, timeout 3600s |
| GCP/Firebase project | `ethglobaltokyo2026lifelink` (number `1023311564471`). **The default in `gcloud config` may point to a different project, so always specify `--project` explicitly** |
| Firestore | `(default)`, `asia-northeast1`, Native. Rules/indexes are the repository's `firestore.rules` / `firestore.indexes.json` (4 indexes) |
| Android | `com.rtree.LIFELiNK`, versionName `0.1.0` / versionCode 1, minSdk 26 / target 36. Verification device: Samsung SM-S942Z (Android 16) |
| debug APK | SHA-256 `bf68da348e14bb8fae35f620b32d7196e3406b4f9dddd32fc9f58c58a3eeaf5b` (build as of `25d9352`) |
| Build environment | JDK 21 (`/opt/homebrew/opt/openjdk@21`), Android SDK `~/Library/Android/sdk`, Node 22 (Dockerfile; locally v24 also works) |

### Cloud Run Environment Variables

| Variable | Value / Reference |
| --- | --- |
| `GOOGLE_CLOUD_PROJECT` | `ethglobaltokyo2026lifelink` |
| `BACKEND_URL` | `https://lifelink-backend-1023311564471.asia-northeast1.run.app` |
| `OPENAI_REALTIME_MODEL` | `gpt-realtime` |
| `WORLD_ID_APP_ID` / `WORLD_ID_RP_ID` | `app_30fbdcf47be73f8a3603f0633b8aeb7c` / `rp_f73bfaa54987b8ce` |
| `WORLD_ID_ACTION` / `WORLD_ID_ENVIRONMENT` | `verify-emergency-caller` / `production` |
| `DISCORD_APPLICATION_ID` / `DISCORD_PUBLIC_KEY` | Not set (uses the defaults `1553217776179486882` / `ed97e676…fdb84` from `backend/src/config.ts`) |
| `TWILIO_ACCOUNT_SID` | Secret `key-twilio-sid` (version 1) |
| `TWILIO_AUTH_TOKEN` | Secret `key-twilio-authToken` (version 1) |
| `TWILIO_FROM_NUMBER` | Secret `key-twilio-from-number` (version 1. Phone Number SID `PN25e30a4c7e287953ff4ebce4d33c3771`) |
| `OPENAI_API_KEY` | Secret `key-openai-ethglobaltokyo-nolimit` (version 1) |
| `WORLD_ID_RP_SIGNING_KEY` | Secret `key-world-id-rp-signing` (version 1) |
| `DISCORD_BOT_TOKEN` | Secret `key-discord-bot-token` (version 1) |
| `DISCORD_CLIENT_SECRET` | Secret `key-discord-oauth-client-secret` (version 1) |

Cloud Run references use `:latest`. Since version 1 is the latest for all of them, this is currently equivalent to `:1`. Each Secret has `roles/secretmanager.secretAccessor` granted individually to the SA above.

## 2. External Service Configuration

- **Twilio**: account `status: active`, `type: Full`. The outbound number is the Phone Number SID above. The TwiML / Media Stream URLs are specified by the backend at call time (`/v1/twilio/voice`, `/v1/twilio/status`, `wss …/v1/twilio/media`). The other number (+1629280xxxx) belongs to a previous project — do not touch it.
- **OpenAI Realtime**: `gpt-realtime`, audio `audio/pcmu`, voice `marin`, server VAD. Input audio transcription is `gpt-4o-mini-transcribe` (`ja`).
- **World ID**: the app/RP/action above (production). Proof of humanity sets the Firebase custom claim `human_verified`, which is a required condition for `POST /v1/emergency-events`.
- **Firebase Auth**: Google provider enabled. Android's `google-services.json` is in the repository (debug SHA-1 registered).
- **Discord app "LIFELiNK"** (Application ID `1553217776179486882`)
  - OAuth2 Redirects: `https://lifelink-backend-1023311564471.asia-northeast1.run.app/v1/discord/oauth/callback` (configured manually in the Portal; cannot be changed via the Bot token)
  - Interactions Endpoint URL: `https://lifelink-backend-1023311564471.asia-northeast1.run.app/v1/discord/interactions` (configured by `PATCH /applications/@me` using the Bot token; passes Discord's signed PING verification)
  - The Bot has been added to the test server: `https://discord.com/oauth2/authorize?client_id=1553217776179486882&scope=bot&permissions=0&integration_type=0`. **If the recipient is not on the same server, the DM fails with `50278`**
- **+Beacon (PB-BTN-01)**: In the manufacturer's app, set to RUNNING, button-detection mode, 1-second interval, **60-second transmission time**, TxPower 0 dBm. See [doc/en/beacon-verification.md](doc/en/beacon-verification.md) for details.

## 3. Firestore Data (real schema)

**Real-data backup**: at 14:13 JST on 2026-09-26, the entire `(default)` database was exported to `gs://ethglobaltokyo2026lifelink-firestore-backups/mvp-0.1-2026-09-26` (operation `SUCCESSFUL`, 104 documents, 59.6 KB. The bucket is in `asia-northeast1`, uniform access, public access prevented). Because it contains phone numbers, Discord IDs, and call transcripts, do not make the bucket public. To restore, run `gcloud firestore import gs://ethglobaltokyo2026lifelink-firestore-backups/mvp-0.1-2026-09-26 --project=ethglobaltokyo2026lifelink` (documents with the same ID are overwritten; newly created documents are not removed).

| Path | Contents |
| --- | --- |
| `users/{uid}` | User |
| `users/{uid}/contacts/{contact_id}` | Registered phone contact (`phone_e164`, `enabled`) |
| `users/{uid}/locations/{id}` | Prefecture and timestamp only (coordinates/accuracy are not stored) |
| `users/{uid}/discordInvites/{invite_id}` | Invite (only the SHA-256 of the token is stored, valid for 24 hours, one-time use) |
| `users/{uid}/discordContacts/{discord_user_id}` | Consented Discord contact (`status: active/revoked`, `last_test_dm`) |
| `emergency_events/{id}` | Emergency event (`uid`, `contact_id`, `trigger_type`, `state`, `location_snapshot`, `initial_note`, `twilio_call_sid`) |
| `emergency_events/{id}/updates/{id}` | Time-series feed: `note` / `location` (from the user themselves), `friend_comment` (Discord reply, doc id `discord_{interaction_id}`), `transcript_contact` / `transcript_ai` (call transcript), `system` (call ended). Once conveyed to the AI, `delivered_to_ai_at` is set |
| `emergency_events/{id}/discord_notifications/{discord_user_id}` | Snapshot of the DM recipient and delivery status (`owner_uid`, `status: pending/sent/failed`, `channel_id`, `message_id`, `error_code`) |
| `world_id_nullifiers/…` | World ID replay prevention |

All writes go through the backend (Admin SDK). Client-side writes are entirely denied by the rules. The canonical schema is documented in [doc/en/plan.md](doc/en/plan.md) (Chapters 6, 6a, and "P0-16 Frozen Schema/API").

## 4. Steps to Reproduce from Scratch

1. **Code**: `git checkout mvp-0.1`
2. **Firestore**: `npx -y firebase-tools@latest deploy --only firestore:rules,firestore:indexes --project ethglobaltokyo2026lifelink`
3. **Secrets**: verify that the Secrets in the table above exist and have an enabled version, using `gcloud secrets versions list <name> --project=…` (do not display the values). If missing, paste them directly via "Add new version" in the Console. Grant `roles/secretmanager.secretAccessor` to the SA for each Secret.
4. **backend** (env/secrets of the existing service are preserved):
   ```sh
   gcloud run deploy lifelink-backend --source backend --region=asia-northeast1 \
     --project=ethglobaltokyo2026lifelink --quiet \
     --update-secrets=DISCORD_BOT_TOKEN=key-discord-bot-token:latest,DISCORD_CLIENT_SECRET=key-discord-oauth-client-secret:latest
   ```
   If recreating the service, pass all the environment variables in the table above via `--set-env-vars` / `--set-secrets`, and include `--service-account`, `--max-instances=1`, and `--timeout=3600`. To simply revert to the exact same image, run `gcloud run deploy lifelink-backend --image <image@sha256 above> --region=asia-northeast1 --project=…`.
   Verify: `/health` returns 200, and `POST /v1/discord/interactions` returns 401 without a signature.
5. **Discord**: verify that the Redirect URL and Interactions Endpoint URL match the above (use `GET /applications/@me` with the Bot token; do not display the token). Confirm the Bot and the recipient are on the same server.
6. **Android**:
   ```sh
   cd android
   export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
   export ANDROID_HOME="$HOME/Library/Android/sdk"
   ./gradlew assembleDebug
   # Access the device via beacon-host (doc/en/host-setup.md)
   scp app/build/outputs/apk/debug/app-debug.apk beacon-host:/tmp/lifelink-debug.apk
   ssh beacon-host '"$HOME/Library/Android/sdk/platform-tools/adb" -s <serial> install -r /tmp/lifelink-debug.apk'
   ```
7. **Initial app setup** (after a reinstall, "Start monitoring" must be performed again each time):
   Google sign-in → prove humanity with World ID → get and save the current location → register an emergency contact (phone) → follow the steps in the Discord section (create a server → add the Bot → invite a friend → invite URL → consent → test DM → "Confirm receipt") → briefly press button 1/2 and then tap "Link this Beacon" on the card (BB192440 must show "Waiting") → set transmission time to "60 seconds" → exclude the app from battery optimization and set its battery usage to "Unrestricted" → "Start monitoring (persistent)" → the notification area shows "LIFELiNK monitoring active" → turn dry-run OFF.

## 5. Verification Checklist (MVP 0.1 acceptance criteria)

- [ ] Regardless of button 1/2, short-press/long-press, exactly one call comes in (about 2 seconds with the screen ON; may be delayed a few seconds while locked)
- [ ] At the start, the AI states "this is an automated call from LIFELiNK," "the prefecture," "elapsed time since the location was captured," and "the note"
- [ ] The "send in-call note" action on Android during the call is conveyed by the AI
- [ ] Exactly one 🚨 emergency DM reaches the Discord friend
- [ ] Call utterances are streamed to the DM in order as `📞 Call counterpart:` / `🤖 AI:`, and "The call has ended" appears when it ends
- [ ] The friend can reply multiple times using "reply with situation" / "continue replying," and the AI conveys it to the counterpart each time during the call
- [ ] `emergency_events/{id}` in Firestore retains `updates` (note/transcript_*/friend_comment/system) and `discord_notifications` (`sent`)

For investigative commands (events, Twilio status), see [doc/en/plan.md](doc/en/plan.md) and the procedures in the conversation logs. To check Twilio call status, load the Secret into an environment variable and call `GET /2010-04-01/Accounts/{sid}/Calls/{CallSid}.json` (do not display the value).

## 6. Known Constraints at This Point in Time (be careful when refactoring)

- **Assumes `maxScale=1`**: the in-call Realtime session table (`activeRealtimeSessions` in `voice.ts`) and the transcript relay queue (`discord.ts`) are in-instance memory. If the instance count is increased, notes/Discord replies received on a different instance will not reach the AI. To scale, this should be changed to subscribe to and inject Firestore's `updates`.
- Android is a single-screen (`MainActivity.kt`) verification UI containing all the functionality. The full UI ([doc/uimock/](doc/uimock/), Chapter 4a) has not been started yet.
- Data is in P0's `emergency_events`/`updates`. Migration to P2's `emergencySessions`/`facts`/`timeline` (Chapter 8a) has not happened yet.
- The Beacon's link information, dry-run setting, and transmission-time setting are stored locally on the device (`SharedPreferences`). Account sync has not been implemented.
- Updating the APK stops the monitoring service. BLE reception is throttled while the screen is OFF ([doc/en/beacon-verification.md](doc/en/beacon-verification.md)).
- For the demo, no notice is given to the call counterpart that "the call content will be shared with a friend."
- Comprehensive verification of failure scenarios (permission denial, connectivity loss, external API outages) is deferred to P3.
- GPS coordinates, accuracy, and detailed addresses are never stored, spoken, or sent in DMs (only the prefecture is).
