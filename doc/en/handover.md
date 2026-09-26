# Handover (new sessions start here)

Last updated: 2026-09-26

This file collects the knowledge that used to exist only inside chat conversations, so that work can continue even if the conversation history is discarded and a new session is started. **Order**: this file → [doc/en/tasks.md](doc/en/tasks.md) (what to do next) → [doc/en/plan.md](doc/en/plan.md) (the design source of truth, only the sections you need) → [doc/en/*.md](doc/en) (records of things actually measured/observed).

## 1. Current status

- The MVP main line (P0-01 through P0-21) is complete and preserved under the tag **`mvp-0.1`** and in [doc/en/mvp0.1.md](doc/en/mvp0.1.md). If something breaks, return to this point.
- Working end-to-end flow: physical button (+Beacon) or on-screen button → Safety gate → Cloud Run → Twilio calls the registered destination → the OpenAI Realtime AI speaks the prefecture and memo → at the same time, the Discord Bot sends a DM to consenting friends → the call transcript is relayed to the DM incrementally → the friend's reply is saved to the same event and delivered to the AI during the call.
- Next actions: follow the "Next actions" order in [doc/en/tasks.md](doc/en/tasks.md). **P1-16 (chat UI), P1-17 (English localization, 3-tab B2C UI), and P1-20 (repeated/long-press SOS, periodic location, battery, motion) were verified on a real device and completed on 2026-09-26.**
- **Major direction change on 2026-09-26**: the migration of the situation store (Chapter 8a, formerly P2-01–06) and the exhaustive handling of failure paths (formerly P3-01–04) have been **moved to PX-14–PX-23** and will not be implemented for the hackathon. Hardening will instead be pursued by repeatedly running the end-to-end flow on real devices. Chapter 8a of [doc/en/plan.md](doc/en/plan.md) remains as a design document.
- **Ambient sound capture (P2-07–P2-11) is being worked on in a separate session.** The entry point for that session is Chapter 0 of [doc/en/ambient-verification.md](doc/en/ambient-verification.md), which contains the current status, next steps, commands, and files not to touch. If you are working on ambient sound, move there after reading this file.
- **2026-09-26 21:00: SOSV2-ambientMode is complete and is now the default for Beacon.** When LIFELiNK is set as the default phone app, pressing +Beacon while the device is locked triggers a Discord emergency DM → the device calls the AI number (Twilio `PNbe2564…`, env `TWILIO_AI_INBOUND_NUMBER`) with a participation code → it calls the contact (cut off after 60 seconds) → the calls are automatically merged into the carrier's IMS conference. The originating device has the microphone ON, uses the earpiece, minimum volume, and the screen off. The old method is now `SOSV1-nope` in Settings. The mechanism and evidence are in [doc/en/ambient-verification.md](doc/en/ambient-verification.md); the contract is in the "Frozen contract for the carrier conference experiment" section of [doc/en/plan.md](doc/en/plan.md); remaining tasks are P2-17–20 in [doc/en/tasks.md](doc/en/tasks.md). Current backend rev is `00033-77z` (example rollback target: `00029-pfg` is pre-SOSV2).
- **2026-09-26 addition: successful manual 3-way call report.** Using a Galaxy device on SoftBank, the user confirmed: calling 2 numbers → adding → merging → 3-way mutual audio → even after locking, the Galaxy's microphone reaches both parties. Next, the **carrier IMS conference + Twilio AI phone leg** will be tested in stages via P2-12–16 (this is not a Twilio Conference). The existing infrastructure and SOS will be preserved; the order is: first confirm the contract for number usage/inbound authorization → manual AI conference via the standard dialer → custom call UI → Beacon opt-in. This round is documentation only; Twilio participation and automatic control have not yet been verified. Details are in sections 0.4–0.11 of [doc/en/ambient-verification.md](doc/en/ambient-verification.md). Acquiring microphone permission / recording FGS is not a prerequisite for this call route.

## 2. Invariants that must not be broken

| Invariant | What happens if broken |
| --- | --- |
| Cloud Run must be **`maxScale=1`** | The Realtime session table during calls (`voice.ts`) and the Discord relay queue (`discord.ts`) live in the instance's in-memory state. With 2 or more instances, in-call memos and Discord replies will not reach the AI, and transcript relaying will also be dropped. Do not scale up for the sake of performance; if you need to fix this, redesign toward a Firestore subscription model first. |
| gcloud/firebase must **always use `--project=ethglobaltokyo2026lifelink`** | The host's default `gcloud config` points to a different project (`beacontesttokyo` from a past hackathon). Forgetting the flag will cause you to inspect unrelated resources and misdiagnose issues. |
| World ID's app/RP/action must **not be re-created** | `app_30fbdcf47be73f8a3603f0633b8aeb7c` / `rp_f73bfaa54987b8ce` / action `verify-emergency-caller` (production) are already registered in production. Re-creating them invalidates existing proofs and signing keys. The key is stored in Secret `key-world-id-rp-signing`. |
| The Twilio outbound number must be **only** Secret `key-twilio-from-number` (SID `PN25e30a4c7e287953ff4ebce4d33c3771`) | Outbound SOS calls use only this number. The other number, +1629280xxxx (SID `PNbe25648b5f32bd261cb3ac9039855fd3`), was **repurposed on 2026-09-26 with user approval, moved from the old project to LIFELiNK's AI inbound-call experiment** (P2-12–16). The old Voice URL `https://beacontest-backend-998360239501.asia-northeast1.run.app/v1/twilio/incoming` (POST) was removed and left empty. It has been confirmed that there is no need to revert it to the old project. |
| **Only the backend (Admin SDK) may write to Firestore** | Client-side writes are fully denied by the rules. Do not add code that writes directly from Android (direct reads are fine — P1-16's live feed actually reads directly). |
| **Do not create mock or dummy data** | This is the policy in `.github/copilot-instructions.md`. The UI must read only real Firestore collections from the start. Anything not yet implemented should be displayed as "in progress." |
| **Do not migrate or delete** the existing `emergency_events`/`updates` data from P0 | This is the evidence trail of the MVP 0.1 real-device verification. P2 uses `emergencySessions` starting from new events. Backup: `gs://ethglobaltokyo2026lifelink-firestore-backups/mvp-0.1-2026-09-26`. |
| Audio must be **G.711 μ-law / 8kHz / mono (`audio/pcmu`)** | This format is matched on both the Twilio Media Streams side and the OpenAI Realtime side. Changing only one side causes silence, noise, or transcription failures. |
| **Both the app and the AI's speech, as well as Discord, must be in English** | Per the user's instruction at 2026-09-26 15:5x, everything was switched to English. `voice.ts`'s `instructions`, `buildInitialMessage`, `injectEmergencyUpdate`, `server.ts`'s `formatEmergencyUpdate`, and `discord.ts`'s DMs, buttons, and invitation page are all in English. Input speech transcription is also `language: "en"`. **The earlier note ("keep phone call speech in Japanese"), dated before this instruction, has been superseded by it.** Note that during a demo call to a Japanese speaker, the other party will be responded to in English. |
| Schema changes must **update [doc/en/plan.md](doc/en/plan.md) before the code** | Chapters 6, 6a, "P0-16 frozen schema/API," and 8a are the source of truth. `firestore.rules` / `firestore.indexes.json` are derived from them. |

## 3. Environment (host, build, deploy)

The agent runs on a guest VM; Android builds and real-device operations are performed on the **host macOS** (SSH alias `beacon-host`, `10.211.55.2`). Details are in [doc/en/host-setup.md](doc/en/host-setup.md).

```sh
# Android build (JDK 21, not 17)
cd android
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew assembleDebug

# Deploy to the real device (Samsung SM-S942Z / Android 16, serial RFGL41GKP0Z)
scp app/build/outputs/apk/debug/app-debug.apk beacon-host:/tmp/lifelink-debug.apk
ssh beacon-host '"$HOME/Library/Android/sdk/platform-tools/adb" -s RFGL41GKP0Z install -r /tmp/lifelink-debug.apk'

# Backend deploy (existing env/secrets are preserved)
gcloud run deploy lifelink-backend --source backend --region=asia-northeast1 \
  --project=ethglobaltokyo2026lifelink --quiet

# Firestore rules and indexes (forgetting indexes causes queries to fail)
npx -y firebase-tools@latest deploy --only firestore:rules,firestore:indexes \
  --project ethglobaltokyo2026lifelink
```

## 4. Verification commands (prove that things are working)

```sh
# Backend liveness and deployment content
curl -s https://lifelink-backend-1023311564471.asia-northeast1.run.app/health
gcloud run services describe lifelink-backend --region=asia-northeast1 \
  --project=ethglobaltokyo2026lifelink \
  --format='value(spec.template.metadata.annotations."autoscaling.knative.dev/maxScale",status.latestReadyRevisionName)'

# Confirm Discord Interactions verifies signatures (unsigned requests get 401)
curl -s -o /dev/null -w '%{http_code}\n' -X POST \
  https://lifelink-backend-1023311564471.asia-northeast1.run.app/v1/discord/interactions

# Check whether Secret versions are ENABLED (values are not shown)
for s in key-twilio-sid key-twilio-authToken key-twilio-from-number \
  key-openai-ethglobaltokyo-nolimit key-world-id-rp-signing \
  key-discord-bot-token key-discord-oauth-client-secret; do
  echo "$s $(gcloud secrets versions list $s --project=ethglobaltokyo2026lifelink \
    --filter='state=ENABLED' --sort-by=~name --limit=1 --format='value(name,state)')"
done

# Most recent emergency event and delivery status (Firestore)
gcloud firestore databases describe --database='(default)' \
  --project=ethglobaltokyo2026lifelink --format='value(locationId,type)'

# Backend logs (evidence of calls, DMs, and Beacon execution)
gcloud logging read \
  'resource.type="cloud_run_revision" AND resource.labels.service_name="lifelink-backend"' \
  --project=ethglobaltokyo2026lifelink --limit=50 --format='value(timestamp,textPayload,jsonPayload.msg)'

# Android-side Beacon reception log
ssh beacon-host '"$HOME/Library/Android/sdk/platform-tools/adb" -s RFGL41GKP0Z logcat -s LIFELiNK.BeaconLog'
```

The pass/fail checklist is in Chapter 5 of [doc/en/mvp0.1.md](doc/en/mvp0.1.md).

## 5. Known pitfalls (things that will cost you an hour if you don't know them)

- **Discord DMs always fail with `50278` unless the Bot and the recipient are in the same server.** OAuth consent alone is not enough for delivery. The operational pattern is to put both the Bot and the recipient into a dedicated LIFELiNK test server (the steps are also shown inside the app).
- **+Beacon advertises nothing while IDLE.** Using the manufacturer's app (requires NFC), set it to RUNNING, button-detection mode, interval 1 second, **transmit duration 60 seconds**, TxPower 0 dBm. If pressing the button produces no response, suspect this setting first. Details in [doc/en/beacon-verification.md](doc/en/beacon-verification.md).
- **Beacon link information is local to the device (SharedPreferences).** Reinstalling the APK stops the watch service and requires re-linking. Account sync is not implemented (see "Remaining gaps" in Chapter 1a-1 of [doc/en/plan.md](doc/en/plan.md)).
- **Unless the Galaxy device is excluded from battery optimization, reception while locked becomes unreliable.** Set the app's battery setting to "Unrestricted."
- **While the screen is OFF, BLE reception is throttled by the OS** (5–14 packets per 60 seconds, gaps of 11–23 seconds). The 60-second transmit duration and the 75-second gap-detection threshold are decisions based on this measurement; shortening them will cause missed detections. As a trade-off, repeated presses of the same button within 75 seconds are treated as a single press.
- **Firestore indexes are deployed separately from rules.** Forgetting this causes list queries to fail with "requires an index."
- **When the AI's voice momentarily disappears during a call, suspect "the moment a friend's reply was injected into the AI."** In the call at 2026-09-26 16:07 (`8111fb87-…`), the timestamp of `OpenAI Realtime error` at 07:07:52.917 matched, to the second, the timestamp of the Discord friend's `friend_comment`. The cause was that `injectEmergencyUpdate` unconditionally sent `response.create` even while the AI was speaking, and Realtime rejected it because "a response is already in progress." In rev `00029-pfg`, this was fixed to **queue the injection until the response finishes, and flush it on `response.done`** (this was a design that had only been written in Chapter 5 of [doc/en/plan.md](doc/en/plan.md) but never implemented). At the same time, error logging was changed to output the error body instead of just `event.type`, so if this happens again, check `jsonPayload.openAiError`.
- **A separate issue is one-directional audio where the AI's voice is never heard from the start of the call** — this is often transient and not usually a backend bug. In the call at 2026-09-26 14:57 (`04e6fb02-…`), the other party said "I can't hear you. It seems like you can hear me." The very next call at 15:06 (`b53d0cea-…`), with no code changes, was normal, both while locked and unlocked. How to diagnose:
  1. If `outputAudioFrames` in the `Twilio Media Stream closed` log is non-zero, the backend is sending audio to Twilio (it was 480 even in the failing case).
  2. If `streamError` in `Twilio Media Stream status` is null, the Stream itself is fine.
  3. If `transcript_contact` entries are appearing in `updates`, the upstream direction (caller → AI) is alive.
  If all three check out, redial without touching the code.
- **`sanitizeLocationForPersistence()`, despite its name, deliberately keeps `accuracy_m`, `battery_*`, and `motion_*`** (these do not reveal the actual location). Only latitude/longitude and street-level addresses are stripped. (History: during the 2026-09-26 privacy work, `accuracy_m` was briefly discarded too, but that went too far and it was restored.)
- **`getCurrentLocation()` never completes once the app goes to the background.** If the periodic acquisition loop is simply run as a plain `while`, the `await()` blocks forever the moment the screen turns off, and it does not resume even when brought back to the foreground (this actually happened, and only one location update was ever sent). Wrap it in `repeatOnLifecycle(STARTED)` and cut it off with `withTimeoutOrNull`.
- **When placing Compose's `LazyColumn` inside a parent's `verticalScroll`, add `heightIn(max = ...)`.** Leaving it unbounded causes a crash, and fixing `height` leaves a large blank area when there are few messages (this was actually hit in P1-16).
- **`targetSdk 36` forces edge-to-edge.** Unless `Modifier.statusBarsPadding()` is added to your own `topBar`, the logo overlaps the status bar.
- **`lightColorScheme` must explicitly specify every role it uses.** Omitting `tertiaryContainer` causes the M3 default pink to be used, making all bubbles in the live feed appear pink.
- **The live feed shows only the single latest event owned by the current user.** Both on-screen-button calls and Beacon-triggered calls are picked up through the same path, but there is no UI for selecting a past event. To view a transcript during real-device verification, you need to place one new real call.
- **In SOSV2 merging, IMS drops the original two calls and replaces them with a single new conference call** (`IMS_MERGED_SUCCESSFULLY`, with no parent-child relationship). Immediately after the call is answered, `conferenceableCalls` is empty, so `conference()` must be retried every second. Retrying only once leaves the AI stuck on hold (this actually happened).
- **While LIFELiNK is the default phone app, regular incoming calls also go through LIFELiNK's screen.** To revert, use "Change the phone app" in Settings. To check: `adb shell cmd role get-role-holders android.app.role.DIALER`. The role is preserved even after reinstalling the APK, but Beacon monitoring stops, so it needs to be restarted.
- **During a conference SOS, call volume is set to minimum and restored once the entire call ends.** If the process crashes mid-call, the volume can remain stuck at minimum.
- **World ID rejects a second proof from the same human for the same action with `nullifier_replayed`.** Re-authentication must always use a unique action (`-reverify-<uuid>`). Using claim status as the determining factor breaks the "release → re-authenticate" flow (this actually happened; fixed in rev `00035-bsv` to instead check for the presence of a nullifier).

## 6. Known gaps (not bugs, but known unimplemented items)

| Item | Status | Tracked in |
| --- | --- | --- |
| Account sync of Beacon link information | Design only (proposal: `users/{uid}/linkedTriggers`) | [doc/en/plan.md](doc/en/plan.md) Chapter 6, Chapter 1a-1 |
| `GET /v1/contacts` / `GET /v1/emergency-events` (listing endpoints) | **Decided not to implement** (P1-16). The live feed reads directly from Firestore from Android | [doc/en/plan.md](doc/en/plan.md) Chapter 6a, "Android live feed implementation policy" |
| Situation store (`emergencySessions`/`facts`/`timeline`/`delegations`) | Schema frozen, rules and indexes deployed, but zero implementation. **Decided not to implement for the hackathon**, moved to PX | [doc/en/tasks.md](doc/en/tasks.md) PX-14–PX-19, [doc/en/plan.md](doc/en/plan.md) Chapter 8a |
| Full UI (20 screens under `doc/uimock/`) | 3 tabs (Home/Members/Settings) implemented. Not yet at parity with all 20 mock screens | [doc/en/plan.md](doc/en/plan.md) Chapter 4a |
| Ambient sound capture | **Achieved and made the default via SOSV2-ambientMode (carrier 3-way conference)**. Remaining: direct calling of contacts when the AI does not join (P2-17), consent wording (P2-20). Independent recording (P2-09–11) remains unimplemented as an alternative | [doc/en/ambient-verification.md](doc/en/ambient-verification.md), [doc/en/tasks.md](doc/en/tasks.md) P2-12–20 |
| English localization | **Complete.** Android, AI speech, and Discord are all in English (the only remaining Japanese is the technical conformity number). `values/strings.xml` extraction has not been done | [doc/en/plan.md](doc/en/plan.md) Chapter 4a |
| Comprehensive handling of failure paths (permission denial, connectivity loss, external API failure) | Not done. Moved to PX; policy changed to fix issues while running real-world operation | [doc/en/tasks.md](doc/en/tasks.md) PX-20–PX-23 |
| Notifying the call recipient that "the content is being shared with friends" | Deliberately omitted for the demo | [doc/en/plan.md](doc/en/plan.md) Chapter 4a, to be reconsidered at productization |

## 7. Recently fixed design drift (do not let it recur)

- 2026-09-26: `trigger_source` and `participant_uids` were not being written to `emergency_events` (Chapter 6 of [doc/en/plan.md](doc/en/plan.md) and `firestore.rules` assumed they were). Fixed the backend to write `trigger_source` (defaulting to `beacon` when `ble`) and `participant_uids: []`.
- 2026-09-26: Chapter 6b of [doc/en/plan.md](doc/en/plan.md) contained an incorrect statement about "a fixed UUID shared by all users," which has been removed. In fact, registration is per-user slot; `BB192440-…`/Major `11665`/Minor `31295` is the **standby advertisement**, not a button press.

## 8. What to do when closing out a session

1. Append anything learned during the work that "exists only here" to sections 2, 5, and 6 of this file.
2. Reflect decisions and their rationale in [doc/en/plan.md](doc/en/plan.md), and reflect the next steps in the "Next actions" section of [doc/en/tasks.md](doc/en/tasks.md).
3. Record facts confirmed on a real device, along with the observed values, in the relevant file under `doc/ja-jp/` (or its English counterpart under `doc/en/`).
4. Commit, push, and leave the working tree clean before finishing.
