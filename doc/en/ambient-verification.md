# Ambient Sound Collection: Feasibility Study and On-Device Verification (ambient)

Last updated: 2026-09-26 / Status: **Manual 3-way calling on the Galaxy and mic reachability after screen-off/lock have been confirmed by the user. Carrier conference + Twilio AI is the next experimental candidate. Twilio join, app-driven call automation, and local recording remain unverified/unimplemented.**

The goal is to turn ambient sound into short textual observations and pass them to the AI on the call and to friends on Discord.
This file is a **standalone entry point for resuming ambient-sound work in a separate session**, and it consolidates
"can we even record under the current state" and "what should be verified on real devices."
**The next session should read Chapter 0, especially 0.4–0.11.** Verification notes for the phone-based route go into 0.5,
and verification notes for the independent local-recording route go into Chapter 5. Chapters 1–9 preserve the investigation
of the separate local-recording alternative.

Invariant (fix this first): **Raw audio is never persisted.** Temporary transmission for analysis is acceptable, but
only the derived text may be stored. No audio bytes may remain in `updates`, logs, or temp files.

---

## 0. For whoever resumes this session

**Reading order**: [handover.md](handover.md) (project-wide invariants, environment, pitfalls) → this file → if needed,
the P2 section of [tasks.md](tasks.md). **Do not touch the environment without reading `handover.md` first.**

### Current state (as of 2026-09-26)

| Item | Status |
| --- | --- |
| Code baseline before this doc update | `d9f43d8` (main, pushed). This update is documentation only. See Git history for the latest doc commit |
| Cloud Run | Baseline per the handover is `lifelink-backend-00029-pfg`, `maxScale=1`. This doc update did not re-query or change cloud state |
| Device | Samsung SM-S942Z / Android 16 / serial `RFGL41GKP0Z`, SoftBank (MCC/MNC `44020`). Via SSH alias `beacon-host` |
| Manual carrier conference | **User confirmed: sequential calls to two consented numbers → add call → merge → 3-way mutual audio → Galaxy mic still reaches both parties after screen-off/lock** |
| Twilio AI joining the conference | **Unverified.** Do not read the manual two-number test as proof of Twilio Media Streams success |
| Default dialer | Observed as `com.samsung.android.dialer` at the time. LIFELiNK's `ROLE_DIALER` / `InCallService` are not implemented |
| Manifest | `RECORD_AUDIO` and `FOREGROUND_SERVICE_MICROPHONE` are declared |
| Runtime microphone permission | Last check showed `granted=false`. Not re-checked this time. **Not a prerequisite for the phone-audio route** |
| Local recording code | **None.** Neither `AudioRecord` nor a microphone FGS is implemented. The existing connectedDevice FGS is a separate thing |
| backend | Existing outbound calling, Media Stream, and Discord are working. Event preparation/inbound acceptance for the new inbound AI call is not implemented. A standalone audio-chunk API and `type: "ambient"` are also not implemented |

P2-08 is complete. The local-recording proposals P2-09–11 remain on the table, but **the carrier-conference experiments
P2-12–16 will be evaluated first next**. This is neither a decision to replace the MVP nor a completed productization.
What was done this time is investigation, read-only device inspection, and documentation only — no changes were made to
the app code, the APK, Cloud Run, Twilio numbers/webhooks, or the Firestore schema.

### Next steps (in this order)

1. **Confirm the conclusions and boundaries in 0.4–0.11.** First read-only check the intended use and existing
   configuration of the candidate Twilio number, and record the restoration baseline.
2. Contract the event preparation for the new mode, matching of incoming calls to events, end states, and mixed-audio
   display in [plan.md](plan.md) first.
3. Add a minimal, isolated experimental entry point to the existing backend, and **using the standard Samsung dialer,
   manually call the AI number → add the other party → manually merge**. Confirm mutual audio among AI↔Galaxy↔contact,
   audio while locked, and Discord transcription/replies, all against the same real event.
4. Only after success, implement and verify `ROLE_DIALER` / the in-call UI and the on-screen experimental SOS.
5. Finally, add a **Beacon SOS route (existing/experimental, default existing)** to Settings, and verify automated
   control while locked.

Detailed staged gates and rollback are in 0.8–0.10. Do not change the dialer, Beacon, and AI all at once from the start.
If returning to the local-recording proposal, proceed in order: microphone permission → schema study in 8.1 →
on-device verification of the microphone FGS (Chapter 5) → audio-chunk API.

### 0.1 Environment commands (excerpt from Chapter 3 of [handover.md](handover.md))

```sh
# Android build (JDK 21, not 17)
cd /Users/araki/operations/LIFELiNK/android
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew assembleDebug

# Deploy to the physical device
scp app/build/outputs/apk/debug/app-debug.apk beacon-host:/tmp/lifelink-debug.apk
ssh beacon-host '"$HOME/Library/Android/sdk/platform-tools/adb" -s RFGL41GKP0Z install -r /tmp/lifelink-debug.apk'

# backend deploy (existing env/secret are preserved. Always pass --project)
gcloud run deploy lifelink-backend --source backend --region=asia-northeast1 \
  --project=ethglobaltokyo2026lifelink --quiet
```

### 0.2 Verification commands

```sh
ADB='"$HOME/Library/Android/sdk/platform-tools/adb" -s RFGL41GKP0Z'

# Whether microphone permission has been granted
ssh beacon-host "$ADB shell dumpsys package com.rtree.LIFELiNK | grep 'RECORD_AUDIO: granted'"

# Which app currently holds the microphone (confirm recording is happening at the OS level)
ssh beacon-host "$ADB shell dumpsys media.audio_flinger | grep -i -A5 'Input thread'"
ssh beacon-host "$ADB shell dumpsys audio | grep -i -A10 'recording'"

# Whether the FGS is alive and what type it is
ssh beacon-host "$ADB shell dumpsys activity services com.rtree.LIFELiNK | grep -iE 'ServiceRecord|foreground|type'"

# Screen off / lock control (for verification)
ssh beacon-host "$ADB shell input keyevent KEYCODE_SLEEP"   # screen off
ssh beacon-host "$ADB shell input keyevent KEYCODE_WAKEUP"  # screen on
ssh beacon-host "$ADB shell dumpsys window | grep -m2 -E 'mCurrentFocus|mDreamingLockscreen'"

# App logs (easiest to piggyback on the existing Beacon logging mechanism)
ssh beacon-host "$ADB logcat -s LIFELiNK.BeaconLog"
```

**zsh pitfall**: `UID` is a read-only variable, so `UID=$(...)` fails. Use `OWNER` or similar instead.
`echo ===` also triggers expansion errors, so quote it.

### 0.3 Scope of change and invariants

- **This session is documentation only. The following is a plan for the next session, not a list of already-approved
  configuration changes.**
- Preserve the existing Home SOS, the default behavior of the outbound-call API, the Safety gate, World ID call
  authorization, and Discord consent/delivery.
- Do not rewrite the Realtime lifecycle in `backend/src/voice.ts`. Any shared logic needed to support inbound calls
  must be reviewed as a separate diff, and the injection queue via `pending` / `responseActive` / `response.done`
  must be preserved. The idea of adding an independently recorded PCM stream to the existing input, and **the current
  proposal of receiving one carrier-merged phone leg through the normal Media Stream, are different things**.
- Cloud Run shares the existing service, and **`maxScale=1` must be maintained**. Sharing Firestore alone does not
  distribute the in-memory session table or Discord queue.
- `emergency_events` / `updates` are preserved. New fields must also be frozen in [plan.md](plan.md) before code.
  Do not migrate/delete old data or introduce the Chapter 8a status store.
- Only the backend Admin SDK writes to Firestore. The UI subscribes to real documents; do not create mocks.
- Do not arbitrarily change the current outbound Twilio number `key-twilio-from-number` (SID
  `PN25e30a4c7e287953ff4ebce4d33c3771`). The separate number +1629280xxxx (SID `PNbe25648b5f32bd261cb3ac9039855fd3`)
  **was repurposed for the AI-inbound experiment with user approval on 2026-09-26**. The old project's Voice URL
  (`beacontest-backend-…/v1/twilio/incoming`) has been unset and left empty; the FriendlyName is
  `LIFELiNK AI inbound (experimental)`.
- Always use `--project=ethglobaltokyo2026lifelink` for gcloud/firebase. Do not change the World ID app/RP/action or keys.
- Maintain the `audio/pcmu` audio format (G.711 μ-law / 8 kHz / mono), English UI/AI/Discord, and the policy of never
  persisting audio.

### 0.4 Conclusion: not "bypassing recording permission" but "becoming a participant in the call"

**What looks promising is a 3-way call via Android Telecom / carrier IMS Conference — not a Twilio Conference.**
Two ordinary phone calls are placed from the Galaxy, one to a registered contact and one to the Twilio AI number, and
the carrier merges them. Twilio joins only as one leg for the AI's side of the call, connecting to OpenAI Realtime via
bidirectional Media Streams.

```text
Galaxy (user's mic / earpiece audio)
            ↕ ordinary SIM call
    Carrier / IMS 3-way conference
       ↙                 ↘
Registered contact       Twilio's AI-answering number (one phone leg)
                              ↕ <Connect><Stream> / WSS
                        Existing Cloud Run ↔ OpenAI Realtime
                              ↕ same emergency_event_id
                        Firestore updates ↔ Discord DM/replies
```

- The carrier's call path carries the Galaxy's microphone audio to the other party. The app does not need to capture
  call PCM itself, and **this route requires no `AudioRecord` or microphone FGS**. There is no need to wait for prior
  grant of `RECORD_AUDIO` before trying this either.
- However, `ROLE_DIALER` is not "a privilege to record freely while locked." It does not grant the call-recording
  privilege `CAPTURE_AUDIO_OUTPUT`.
- The existing scheme is backend→contact only, and the user's device never joins the call. The new scheme has the user
  join as well, so **it increases the chance of carrying nearby speech to both the AI and the contact**. Ambient sound
  quality varies with the call's noise suppression, mic distance, being in a pocket, mute state, etc.
- Recognition of non-speech sounds, distant sounds, and speaker separation are unverified. Do not jump from "three
  people can hear each other's voices" to "any ambient sound can be accurately detected."
- No secure unlock bypass or keyguard circumvention is required. **Showing the in-call UI over the lock screen and
  unlocking the device without authentication are different things.**
- With this scheme, the AI's or contact's voice may be audible from the Galaxy's speaker. Speaker/earpiece/Bluetooth
  routing and volume/leakage policy must be confirmed during the experiment against the goal of "a situation where the
  user cannot speak out loud." Do not call the bidirectional conference "a silent, covert SOS."

### 0.5 Evidence: separating confirmed from unverified

**On 2026-09-26, the user reported a successful on-device test (this is not a record of the agent placing or listening
to the call)**:

| Step | Reported result |
| --- | --- |
| 1. Manually and sequentially call the two consented numbers | Success |
| 2. Confirm "add call" partway through the first call | Displayed and usable |
| 3. Confirm "merge" after answering the second call | Displayed and usable |
| 4. After merging, all three parties can hear each other | Success |
| 5. After screen-off/lock, the Galaxy's mic still reaches both parties | Success |

With this report, **the previous caveat "manual 3-way conferencing has not been confirmed on this device/current
SIM/carrier" is resolved**. The exact time, duration, screen-off duration, charging state, audio route, and the other
party's device model were not reported. Do not transcribe numbers/personal information into this document; supplement
these conditions in the next test.

Prior read-only ADB observations:
- `model=SM-S942Z`, `android=16`, `carrier=SoftBank,`, `mccmnc=44020,`, default phone app `com.samsung.android.dialer`.
- The dumpsys output included `allow_hold_in_ims_call = true`, `ims_conference_size_limit_int = 5`, etc.
  `carrier_volte_available_bool` appeared as both true and false across multiple settings/history entries, so which one
  is actually in effect could not be determined.
- Do not conclude "hold/add/conference is impossible" purely from strings like `CALL_SIMULTANEOUS_DISABLED_SAME_ACCOUNT`
  in a settings dump. **Prioritize the actual merge success above over speculation from a config dump.** The internal
  IMS mechanism and actual behavior at up to 5 parties have not been proven.

**2026-09-26 19:12–19:17 JST, on-device results for E2/E3 (rev `00030-j5p`, APK `a965dfe`, manual via the standard
Samsung dialer)**:

| event | Description | Result |
| --- | --- | --- |
| `792bfc59-…` | Conference SOS → call AI number → code verification → Galaxy↔AI two-way | Auto-sent code (`tel:…,,code`) succeeded in joining. First AI utterance, transcript of Galaxy's voice, Discord relay. `completed` on AI disconnect. Received 2,419 / sent 154 frames |
| `de1742ee-…` | Same as above + "add call" to contact → "merge" | **3-way call established (user confirmed)**. AI answered the contact's Japanese question with a Japanese status explanation. `completed` at 10:17:41 |

Observed issues (targets for future improvement):
- **AI reacted to hold music**: while the contact was being added, the AI leg was on hold, and the "You are currently
  on hold, please wait a moment" hold announcement entered `transcript_contact`, causing the AI to keep talking as if
  someone were there (nobody actually heard it).
- **Speaker of mixed audio is indistinguishable**: both the contact's and the Galaxy user's own utterances land in the
  same `transcript_contact`. The AI took the contact's mention of "Araki-san" as the user's own name.
- **Weakly-grounded claims about ambient sound**: the AI said "I faintly heard something like footsteps." Whether this
  was a real sound is unverified — a false-alarm risk.
- Transcription was configured with `language: "en"`, but Japanese utterances were still transcribed in Japanese, and
  the AI also replied in Japanese (this deviates from the fixed-English policy, though matching the other party's
  language caused no actual harm).

**2026-09-26 19:30–20:40 JST improvements and confirmations (user confirmed)**:
- After the conference-oriented AI instructions in rev `00031-dfn`: confirmed the AI stays silent while on hold, states
  that it is guessing the speaker, and continues even with the screen off.
- **E4 (custom dialer) achieved**: made LIFELiNK the default phone app, and from the on-screen SOS: AI → contact →
  automatic merge. The IMS merge ends the original two calls with `IMS_MERGED_SUCCESSFULLY` and replaces them with a
  single new conference call (no parent/child relationship is attached). Immediately after answering,
  `conferenceableCalls` is empty and `conference()` is a no-op, so retrying every 1 second achieves the merge in about
  3 seconds.
- Confirmed that with the originating device set to mic on, earpiece routing, minimum call volume, and the call screen
  kept off, the audio was "barely audible."
- Remaining: 60-second ring timeout, normal inbound calls through LIFELiNK, and launching from the Beacon (P2-16).

Unverified:
- ~~Whether the Twilio AI can join as a third participant and hear both the Galaxy and the contact, and whether both
  can hear the AI.~~ → confirmed established below in E2/E3.
- Whether LIFELiNK can acquire the role and use `placeCall()` / `conference()` as a third-party dialer.
- Placing/adding/merging calls, and answering inbound calls, initiated by the Beacon while the screen is off/locked.
  **This is a separate test from successfully keeping the lock state after the call is already established.**
- Transcription from the merged call audio, Discord relay/reply injection, the AI's reaction to hold music or echo,
  and event synchronization on call end.
- Classification of ambient sounds other than call audio, and reliability over long durations/network interruptions.

### 0.6 Investigation findings (integrating the 10 assigned topics; incorrect conclusions are not adopted)

| Topic | Conclusion / positioning |
| --- | --- |
| Default dialer eligibility | An ordinary third-party app can meet the requirements and acquire `ROLE_DIALER` if the user selects it. A decorative SOS-only screen is not sufficient |
| Carrier conference API | Uses `Call.conference(otherCall)`. Governed by the other call's `conferenceableCalls`, state, and carrier capability. Distinguish an API call succeeding from the merge actually succeeding |
| Placing calls while locked | The approach is to control managed-SIM calls via Telecom. BAL (Background Activity Launch), call state, and OEM behavior all require on-device verification; do not rely on automated UI taps |
| Answering an inbound call via the Beacon | A separate approach exists: calling `answer()` on the ringing `Call` in `InCallService`. Requires identifying who is calling for which event, plus consent, expiry, and de-duplication |
| Local capture of call PCM | Not possible with normal dialer privileges. **Do not conflate "cannot capture locally" with "no audio reaches Twilio, the other party on the call"** |
| Twilio audio | The goal is a participating leg of the carrier conference plus a bidirectional Stream; there is no need to build a `<Conference>` on the Twilio side |
| Self-managed VoIP | `ConnectionService` / Core-Telecom are mechanisms for call merging/UI, and do not automatically achieve arbitrary audio mixing with a SIM call |
| OEM/carrier | This manual conference on this Galaxy + SoftBank combination is user-confirmed. It has not been generalized to other SIMs, other device models, or third-party dialer APIs |
| Play / consent | Must satisfy the responsibilities, incoming-call notifications, permissions, and disclosures required of a legitimate phone app. Store review has not been evaluated; do not assert "will definitely be rejected" or "will definitely pass" |
| Alternative configurations | Local microphone FGS, notification actions, external recorders, answering inbound calls, etc. are alternatives described below and do not replace the main experiment here |

**Candidate Android implementation APIs (no code yet)**:

- Use `RoleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)` to prompt the user for role selection ahead of
  time. Do not start the initial setup in the middle of an SOS.
- Handle `Intent.ACTION_DIAL` (dial-pad input) and provide the incoming/ongoing `InCallService` UI. This requires an
  exported service protected by `android.permission.BIND_INCALL_SERVICE`, an intent-filter for
  `android.telecom.InCallService`, and consistency between the UI/ringing metadata and the implementation. A null
  bind or a misconfiguration that loses eligibility will fall back to the standard dialer or lose the role.
- Use the **managed call** via the SIM's existing `TelephonyConnectionService`. Do not replace it with a custom
  self-managed `PhoneAccount`.
- Use `TelecomManager.placeCall(Uri, Bundle)` and `CALL_PHONE`. If SIM/PhoneAccount selection is needed, configure it
  ahead of time and do not ignore the confirmation dialog.
- `InCallService.canAddCall()` / `onCanAddCallChanged()` → check whether adding is possible.
  `Call.getConferenceableCalls()` / `onConferenceableCallsChanged()` → check which parties can actually be merged, then
  call `Call.conference(otherCall)`. Observe the result via the state/details/parent-child callbacks; do not treat a
  fixed-duration wait or a `void` return as completion.
- `TelecomManager.startConference(List<Uri>, Bundle)` (API 31+) is an ad-hoc conference request, but carrier support
  is unverified. `Call.addConferenceParticipants()` also depends on capability support. **Do not depend on these
  one-shot APIs from the outset.**
- `Call.answer(VideoProfile.STATE_AUDIO_ONLY)` is for answering inbound calls. `TelecomManager.acceptRingingCall()` has
  been deprecated since API 29.
- For audio-route switching on API 34+, consider `requestCallEndpointChange()` with the available/current endpoint
  callbacks. Sort out the legacy `setAudioRoute()` API and the minSdk branching during implementation.
- compile/targetSdk is 36, minSdk is 26. Do not directly use API 37 features described in the latest web documentation.
- There is also a separate official path using a physical wearable associated via `CompanionDeviceManager`, combined
  with `MANAGE_ONGOING_CALLS` + `InCallService`. **It has not been confirmed that this Beacon, which is advertisement-only,
  meets those conditions.** Do not adopt it as a universal workaround that avoids needing the dialer role.

**Twilio constraints (mind the perspective)**:

- `<Connect><Stream>` is bidirectional, one per Call. What is received is **only the `inbound_track` as seen from Twilio's
  side**. If the audio arriving from the carrier already mixes the Galaxy and the contact, that mix is the input — there
  is no separate track dedicated to either party.
- AI output is sent back on the same phone leg. Delivery to the Galaxy/contact is handled by the carrier conference.
- `<Connect><Stream>` blocks subsequent TwiML. Appending a `<Dial><Conference>` afterward to run concurrently is not
  appropriate, and in any case this scheme does not use a Twilio conference at all.
- The bidirectional Stream is started via TwiML — different from starting it via the Stream REST resource. WSS and
  signature verification are required.
- Do not attach a query string to the Stream URL; pass event-correlation information via `<Parameter>`. Do not confuse
  this with the query string on the HTTP status-callback URL.
- DTMF on a bidirectional Stream flows only from Twilio→media server. If authorization via a number/code entry is used,
  design that input mechanism first.
- The audio format remains the current `audio/pcmu`. Per-speaker separation and distant-sound capture are not
  guaranteed. Do not enable the recording feature.

**Alternatives and how they are treated this time**:

1. **Alternative: Beacon→backend→Twilio calls the user's own phone number → the user answers on the next action**.
   This alone could establish a user↔AI audio path, and Discord could continue on the same event.
   However, getting the contact onto the call would still require additional add/merge steps on the carrier side.
   Must not auto-answer unregistered inbound calls or unrelated calls, and the current Beacon's constraint of "cannot
   distinguish repeated presses of the same operation" (0.9) also applies. Not adopted for the main experiment this
   time; kept as a re-evaluation candidate if call automation gets stuck.
2. **Start a microphone FGS from the foreground and keep it running after lock**: the primary alternative for
   independent recording. See Chapters 1–9; unverified on-device.
3. **Start from a user action on a lock-screen notification**: a documented while-in-use exception, but requires an
   extra tap. Merely showing a notification is not sufficient.
4. **Unlocking / showing a notification / battery-optimization exclusion**: display, launching an Activity, launching an
   FGS, and using the microphone are each governed by separate conditions. Do not assume that acquiring more
   permissions, excluding battery optimization, or using an overlay or FCM alone removes all microphone restrictions.
5. **External microphone / independent recorder with SD storage**: separate hardware may record under different
   constraints than Android, but transfer, power, consent, and device procurement become separate problems. Saving
   audio to SD conflicts with the current "do not persist" policy. There is no proof of concept for a specific device
   or an immediate transfer path, so this is not adopted this time.
6. **Accessibility / root / OEM privileges**: not adopted as a substitute for the normal permission design of a
   consumer app. Do not implement automated taps on the standard phone UI or bypasses of lock authentication.

### 0.7 Turning the user's A/B/screen proposals into minimal changes

**A. Proposal: use an unused Twilio number as the AI-answering number**:
- Can share the same Twilio account and existing Cloud Run — the experimental policy is to avoid a separate Cloud Run,
  separate DB, or separate Discord bot.
- First confirm the intended use/owner, voice capability, Voice URL/method, fallback, status callback, and any
  TwiML App/Studio association of the unused candidate number. Repurposing a number from an old project requires
  explicit confirmation. If no suitable candidate exists, do not purchase a number arbitrarily; defer to the user.
- There is also a possibility of sharing **only the inbound configuration** of the current outbound number, but this
  should be decided only after checking for conflicts with inbound use. The minimum number of numbers and the minimum
  risk of change are not the same thing. No number to use has been finalized yet.
- Do not replace the existing outbound `TWILIO_FROM_NUMBER` with the experimental number. Manage any additional
  configuration/numbers needed per the Secret Manager policy.

**B. Proposal: share Firestore/Discord/backend, and isolate only the inbound webhook**:
- "Callback from A's number" means **Twilio notifying the backend via an HTTP webhook when the Galaxy calls A's
  number**, and the TwiML returned by the webhook connects the Media Stream to the existing WSS. It does not mean the
  AI calls back to the backend.
- However, **adding just one webhook is not sufficient**. Currently, `POST /v1/emergency-events` creates an event,
  starts Discord, and immediately calls the registered contact outbound via `placeEmergencyCall()`. The experimental
  mode needs an explicit path that "prepares the event/DM but does not place the outbound call." Deliberately failing
  the Twilio outbound call just to create the event is prohibited (it becomes `failed` and can trigger unintended
  calls or DMs).
- The existing bridge only accepts active states (`accepted` / `dialing` / `in_progress`), binds `twilio_call_sid` if
  unset, and rejects if it differs from the existing value. The status callback also depends on the SID. This time,
  design how to bind **the single CallSid of the AI inbound leg**. Since the Galaxy→contact call is carrier-managed, its
  state is not something a Twilio callback will report.
- The call should be tied to the same `emergency_event_id`, sharing location/notes/Discord DM/replies/transcript.
  Being an incoming call does not mean the Firebase/World ID Safety gate can be bypassed.
- Signature verification proves "this arrived from Twilio," not that the caller is authorized as the LIFELiNK owner.
  **Do not read out sensitive information based solely on Caller ID matching, or solely on looking up "the most recent
  event."**
- In the next session, design a pending event plus a short expiry and a one-time verification mechanism via an
  authenticated API. With a PSTN `tel:` call, an arbitrary event ID does not automatically reach the inbound webhook.
  Consider candidates such as a short-lived pairing code entered manually, and verify manually if needed. The
  scheme/fields/API are not yet frozen. Do not log the raw code, and include rate limiting, expiry, single-use
  consumption, retry idempotency, and rejection of unrecognized inbound calls in the contract.
- Complete authorization verification and CallSid binding before handing off to the Stream. Verify the Twilio HTTP and
  WSS `X-Twilio-Signature`, the public URL, `To`, the expected account, and consistency between the event and the SID.
  The new entry point should default to OFF and target a limited test set.
- Also suppress duplicate Streams/reconnects for the same AI leg and duplicate Realtime sessions. Do not distribute the
  session table right away.
- `transcript_contact` was traditionally "the other party on the call," but in a conference it may become **a mix of
  Galaxy and contact audio**. Do not rename the existing field; instead, define its meaning in experimental mode, the
  UI/Discord labeling, and the AI instructions first. Do not assert without grounds that "the registered contact said
  this" or "the user said this."

**C. Proposal: about 2 screens plus alpha in the app, switching between experimental SOS and Settings**:
- Screen idea 1: **Dial / Call back**. `ACTION_DIAL`-style number entry, place/call back, guidance for role and SIM
  settings, a separate `Experimental SOS`. Given the current contract where the backend resolves the owner's
  registered destination, additional design is needed for securely handing that number to the device for dialing.
  If a call-history display is added, evaluate the real data and the necessary call-log permission separately; do not
  create a dummy history.
- Screen idea 2: **Incoming / In call**. Handle incoming/answer/reject, ongoing/hold, add/merge/end, mute, audio
  route, DTMF, etc., according to state.
- "Two screens" is a rough sizing of the presentation, not a cap on implementation effort. While holding the role, there
  is a responsibility to handle calls that Telecom hands over beyond just SOS. Minimal support is needed for
  lock-screen incoming-call notifications, answering from a notification, normal incoming calls, role loss, and
  process re-creation. Auto-dialing public emergency numbers is a non-goal, and this must not interfere with the
  system's handling of emergency dialing as the default dialer.
- Proposal to add a **Beacon SOS route: Existing / Carrier conference (experimental)** to Settings. Default is
  Existing, explicit opt-in. This is a choice between old and new routes, not a toggle that disables button detection,
  World ID, or the Safety gate. Keep the old Home SOS intact, and make sure the on-screen experimental SOS is clearly
  labeled as experimental regardless of the selected state.
- The setting is fixed at event start; changing it mid-flight must not switch an in-progress call to a different
  scheme. The proposal starts with device-local settings, but the reload behavior on restart and the backend's event
  mode contract must be made explicit. Account sync is not required this time.

### 0.8 Recommended experiment order (stop and roll back at each stage)

| Stage | What to do | Condition to proceed / how to roll back |
| --- | --- | --- |
| E0 Baseline | Confirm `d9f43d8` and `mvp-0.1`, the pre-experiment APK, the Cloud Run revision/image, env/secret references, and number configuration. Record any necessary non-secret metadata | Fix the numbers to use and consent, and the restoration target. Do not change configuration yet |
| E1 Contract and minimal backend addition | Freeze the mode, pending event, verification, SID, and end-state contract in plan first. Add an isolated experimental entry point and inbound TwiML, and configure only the number's inbound hook | Confirm regression of existing outbound + Discord, rejection of unrecognized inbound calls, and no duplicate events/DMs/AI connections from HTTP retries. On failure, stop the experimental entry point and restore the number configuration |
| E2 Manual AI-only | Prepare a real authenticated event and manually call the AI number using the standard Samsung dialer with verification. Confirm Galaxy↔AI | Real CallSid/StreamSid match the event, bidirectional audio, real transcript, and Discord replies reach the AI. Do not add the other party yet; isolate causes |
| E3 Manual 3-way + lock | Add the consented contact and manually merge after they answer. Confirm each audio direction among Galaxy/contact/AI and with the screen off | Meet the audio/Discord/end-state checks in 0.10. On failure, end all regular calls and revert to the existing route |
| E4 Automate from the screen | Implement the role and a minimal real in-call UI, and place/add/merge from the on-screen experimental SOS | Confirm the merge via callbacks, and verify normal incoming calls/reject/end and role recovery on-device. Still do not change the Beacon default |
| E5 Beacon opt-in | Add the old/experimental switch in Settings and test starting from the locked state | No re-dialing on the same advertisement, no manipulating unrelated calls, and timeout/abort/recovery work. On failure, revert to Existing |

The first hypothesis for the dialing order is **AI number first → connect and verify → add contact → merge**.
This order is not a proven optimum. Check the audio, hold music, session expiry, and initial AI utterance while the
AI leg is on hold. Since the AI may finish its initial explanation before the contact joins, also consider whether a
re-explanation after merging is needed. If the reverse order (contact→AI) turns out to be better, update this along
with the observed reasoning. Do not implement unlimited retries or automatic redialing with a fixed wait.

### 0.9 Safety, rollback, and Beacon-specific pitfalls

- Keep the existing route intact during experiments. However, **automatic fallback on mid-experiment failure should be
  OFF for the initial experiments** as a recommendation. If the device stays connected to the contact while the
  backend also calls the same person, that results in a duplicate alert. First end the call owned by the experiment,
  confirm the state, and only then start the existing SOS via a human action.
- "Call owned [by the experiment]" is limited to the `Call`/event tracked at experiment start. Implementations that
  hang up, merge, or answer some other ordinary call are prohibited. The default should be not to start the experiment
  if an unrelated call is already in progress.
- Define a finite termination procedure for each case: the caller hangs up, the other party doesn't answer, the AI leg
  disconnects, the carrier refuses to merge, network drops, or the role is lost. Twilio's `completed` is not proof
  that "the three parties were able to help each other." Distinguish AI-leg termination from termination of the entire
  carrier conference.
- The timeout duration, overall cap, and the per-user concurrent-event cap are to be decided at contract time. Do not
  paper over an unknown CallSid by placing additional calls.
- **The + Beacon's long-press bit 14 (`0x4000`) has been measured on this device.** Not all generic iBeacons support
  long-press detection, but do not write back "long-press detection is unverified" for this device. The current
  spec treats short-press/long-press × button 1/2 (4 patterns total) as candidates (see
  [beacon-verification.md](beacon-verification.md)).
- Advertisement transmission is 60 seconds, and loss is judged at 75 seconds. **Repeated presses of the same button in
  the same way cannot be distinguished on the advertisement.** Packet count must not be treated as press count. A
  different button or a short/long switch is a different state, but assigning them to answer/end actions requires a
  separate state machine and on-device measurement. Therefore, "press again to answer the incoming call" cannot be
  guaranteed as-is. Do not shorten the reception threshold just to switch routes.
- First confirm with a synthetic trigger / the existing dry-run that "only the selected route is logged, zero phone
  calls," and only then place a real call with a consented number. This is not meant to fabricate an event for
  real-data UI purposes. After updating the APK, confirm Beacon monitoring resumes and settings are preserved.
- Rollback order: **stop the experiment start → end the calls/Stream owned by the experiment → set Beacon back to
  Existing → if needed, set the default phone app back to Samsung's → restore any changed number configuration →
  if needed, restore the pre-experiment backend revision/APK → confirm existing SOS + Discord once**. Roll back only
  the experimental diff. Do not use a `reset --hard` that wipes other work's updates, and do not delete existing
  Firestore data.
- Since it's the same Cloud Run, branches or feature flags alone do not fully isolate failures. Deploy during a
  window with no active calls, and preserve the existing env/secret and `maxScale=1`. `mvp-0.1` is the last-resort
  fallback baseline; normally roll back to the newer pre-experiment revision instead.
- Obtain the call participants' prior consent and explanation for the AI, the transmission of the user's voice over
  the phone, and the Discord sharing of transcripts. Audio passes through the carrier, Twilio, and OpenAI. Do not
  explain "not stored" as if it meant "never leaves the device." Do not store audio in Twilio recordings, files,
  Firestore, or logs. Separately confirm each external service's own retention terms.

### 0.10 On-device verification checklist and record format for the next round

Phone calls only to consented test numbers. Never call public emergency numbers.

- [ ] E2: Bind incoming calls only to an authenticated experimental event. Reject unknown/expired/mismatched-code/reused
      attempts, and never speak sensitive information.
- [ ] E2: Galaxy's speech → AI response → Galaxy hears it. Do not consider a merely-open Stream sufficient to pass.
- [ ] E3: Confirm each direction separately: Galaxy→contact, contact→Galaxy, Galaxy→AI, contact→AI, AI→Galaxy, AI→contact.
- [ ] E3: Above audio continues after screen-off/lock. Record mute state and output endpoint. Treat in-pocket/distance
      as a separate follow-up quality test.
- [ ] E3: Mixed-audio transcript must not be misattributed to a specific individual's speech. Do not assert unclear
      audio as definite content.
- [ ] E3: Transcript reaches the same event's Discord DM, the consented friend's reply is stored and conveyed to the
      AI, and no response conflict occurs even if the reply arrives while the AI is speaking.
- [ ] E3: Confirm AI behavior during hold/right after merge, echo/double read-back, and whether the initial explanation
      is missed.
- [ ] E3/E4: On disconnect, the device/Twilio/event state stays consistent and the next SOS can begin. No leftover call
      keeps running unintentionally.
- [ ] E4: Confirm role acquisition/denial/return, answering/rejecting/calling back a normal incoming call, the on-screen
      experimental SOS, and stopping when the merge is not possible.
- [ ] E5: Confirm starting from the Beacon while locked → automatic dialing/merging. No duplicate dialing from
      duplicate advertisements, repeated presses, or mid-flight setting changes.
- [ ] Regression: with Existing selected, the traditional backend→contact + Discord flow still works the same way.

What to record: date/time, experiment stage, code commit / APK, Cloud Run revision, device and SIM/carrier,
screen/lock/charging state, audio endpoint, dialing order, times and callbacks for hold/answer/merge/end,
event ID / CallSid / StreamSid, audio results per direction, Discord delivery/reply results, and remaining issues.
**Do not record full phone numbers, secrets, or audio payloads.** Twilio's frame counts are supporting evidence for
send/receive activity, not proof that a person on the carrier side actually heard anything.

### 0.11 Request text and open items for the next session

> Read [handover.md](handover.md) → Chapter 0 of `ambient-verification.md` → tasks P2-12–16 in [tasks.md](tasks.md).
> Manual 3-way calling on the Galaxy + SoftBank and mic reachability after lock are user-confirmed. Twilio AI joining
> is unverified. Preserve the existing Cloud Run / Firestore / Discord and the normal SOS, and start by read-only
> confirming the intended use of the number and contracting the inbound experiment.
> The first end-to-end test is a manual conference via the standard Samsung dialer. Only after success proceed to a
> custom dialer UI → on-screen SOS → Beacon opt-in.
> Do not use Twilio Conference, recording privileges, or the Chapter 8a status-store migration. No code was changed
> in this documentation session.

Needs human confirmation: which answering number may be used, the test counterpart and time window, tolerance for
audio output/leakage, and the impact of and consent for the role change.
Contracts to decide before implementation: the inbound-event verification scheme, additional mode/SID/state fields,
how the registered destination number is handed to the device, the timing of the AI's first utterance, how mixed-audio
transcripts represent speakers, and the timeout/end/retry policy.
**None of these are frozen yet.** Do not make the microphone-recording proposal's `updates.type: ambient` a required
dependency of the phone-based route.

Official references (entry points for the specs confirmed during this call investigation):
- Default phone app / InCallService: <https://developer.android.com/develop/connectivity/telecom/dialer-app>
- `InCallService`: <https://developer.android.com/reference/android/telecom/InCallService>
- `Call` (conferenceable calls / conference / answer): <https://developer.android.com/reference/android/telecom/Call>
- `TelecomManager` (placeCall / startConference): <https://developer.android.com/reference/android/telecom/TelecomManager>
- Call/recording conflicts: <https://developer.android.com/media/platform/sharing-audio-input>
- `CAPTURE_AUDIO_OUTPUT`: <https://developer.android.com/reference/android/Manifest.permission#CAPTURE_AUDIO_OUTPUT>
- Audio-source permission constraints: <https://developer.android.com/reference/android/media/MediaRecorder.AudioSource>
- Background Activity launches: <https://developer.android.com/guide/components/activities/background-starts>
- Twilio Media Streams: <https://www.twilio.com/docs/voice/media-streams>
- TwiML Stream: <https://www.twilio.com/docs/voice/twiml/stream>
- Media Streams messages: <https://www.twilio.com/docs/voice/media-streams/websocket-messages>
- Twilio webhook verification: <https://www.twilio.com/docs/usage/security#validating-requests>
- Twilio Conference for comparison (not used this time): <https://www.twilio.com/docs/voice/twiml/conference>

---

## 1. Conclusions on the separate local-recording alternative

The following is a route separate from the phone-based Chapter 0. The past statements "permission isn't even declared"
and "the device cannot convey ambient sound while on a call" have been corrected.

| Question | Answer |
| --- | --- |
| Can the current APK record sound | **No.** Permission declarations and a Settings permission-grant flow exist, but there is no
microphone FGS or recording code (Chapter 2) |
| Can it record with the screen on and the app in the foreground, if implemented | **Yes.** An ordinary `RECORD_AUDIO`
+ `microphone` FGS suffices |
| Can it keep recording with the screen off / while locked | **If the FGS is properly started, there is no rule that
forbids it.** But it is not guaranteed either, and Samsung's power saving is the biggest risk. On-device verification
is needed (Chapter 5) |
| Can pressing the Beacon while locked **start recording** | Ordinary background launch runs into while-in-use
restrictions and is subject to `SecurityException` on API 34+. Exceptions such as a notification action require
conditions and on-device confirmation (Chapter 3) |
| Isn't recording impossible because of the Twilio call in progress? | With the existing outbound scheme, the device
does not join the call at all. With the Chapter 0 conference scheme, the call itself carries the mic audio, but whether
a *separate* local recording is possible is a different question (Chapter 4) |
| Can the mic-in-use indicator be hidden | There is no public API for an ordinary app to hide it. Plan on the premise
that it will be shown to the user |

---

## 2. Current state of local recording (2026-09-26, corrected after P2-08)

The permissions declared in `android/app/src/main/AndroidManifest.xml` are `INTERNET` /
`ACCESS_COARSE_LOCATION` / `ACCESS_FINE_LOCATION` / `BLUETOOTH`(maxSdk 30) /
`BLUETOOTH_ADMIN`(maxSdk 30) / `BLUETOOTH_SCAN` / `FOREGROUND_SERVICE` /
`FOREGROUND_SERVICE_CONNECTED_DEVICE` / `POST_NOTIFICATIONS` /
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, plus `RECORD_AUDIO` /
`FOREGROUND_SERVICE_MICROPHONE` added in P2-08.

| What's needed | Current state |
| --- | --- |
| `RECORD_AUDIO` | **Declared**. The last observation of the runtime grant was not granted |
| `FOREGROUND_SERVICE_MICROPHONE` | **Declared** |
| `foregroundServiceType="microphone"` | **Absent** (`BeaconMonitorService` uses `connectedDevice` alone) |
| `AudioRecord` / `MediaRecorder` / `SpeechRecognizer` | **Not present in the code at all** |
| Runtime request of microphone permission | **Implemented in Settings' Listening / Allow microphone** |

`compileSdk 36` / `targetSdk 36` / `minSdk 26`. Since targetSdk is 36, all of the Android 14/15 FGS restrictions apply.

---

## 3. The biggest constraint: while-in-use (cannot be launched from the background)

`RECORD_AUDIO` is a while-in-use permission, and **it is prohibited on a separate axis even when covered by an
exemption from background-start restrictions**. Official wording: "if an app wants to launch a foreground service that
needs while-in-use permissions (for example, body sensor, camera, microphone, or location permissions), it cannot
create the service while the app is in the background, **even if the app falls into one of the exemptions from
background start restrictions**"
(<https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start>).

Behavior differs by API level:

- API 30–33: the FGS launches, but **the microphone stays silent**. Logcat shows
  `Foreground service started from background can not have location/camera/microphone access`.
- **API 34+ (i.e., us): `startForeground()` itself throws `SecurityException`**.
  Moreover, `PermissionChecker.checkSelfPermission()` still returns `PERMISSION_GRANTED` even in the background, so
  **an app's own check cannot prevent this**.
- API 34+: a `microphone` FGS cannot be launched from `BOOT_COMPLETED`.

### Exemptions from the while-in-use restriction (candidates involving a user action)

Among the officially listed exemptions, the two considered here are (this is not an exhaustive list of all exemptions):

- **When the user launches it by interacting with a notification** (notification / notification action)
- When the user launches it by interacting with an app widget

→ **So the answer to "is it possible if we just show a notification on the lock screen" is "yes, but a user tap is
required."** Merely showing a notification is not enough — the user needs to **tap** an action such as
`Start listening`. A full-screen intent has its own separate constraints (call/alarm use cases, permission state,
etc.), so it should not be treated as a universal justification for starting ordinary recording.

Feasibility by route, therefore:

| Route | Can recording be started |
| --- | --- |
| Open the app and press the on-screen SOS | **Yes** (app is in the foreground, satisfying while-in-use) |
| Press the Beacon button while locked → auto-start recording | **No** (`SecurityException`) |
| Press the Beacon button while locked → show a notification, and the user taps the action | **Yes** (official
exemption) |

`systemExempted` FGS has eligibility requirements such as system integration, of which `ROLE_EMERGENCY` is one example.
There is no confirmed fact that LIFELiNK qualifies, so this must not be used as if a mere declaration bypasses the
microphone restriction.

---

## 4. Local recording and the phone call carrying audio are different things

When `AudioManager.getMode()` is `MODE_IN_CALL` / `MODE_IN_COMMUNICATION`, "The call always receives audio. The app can
capture audio if it is an accessibility service," and ordinary apps get silence
(<https://developer.android.com/media/platform/sharing-audio-input>).

**However, in LIFELiNK's design, Twilio calls out from the backend to the emergency contact, and the user's Android
device does not participate in the call.** There is no telephony call on the device, so this rule does not apply.

**In the new experiment in Chapter 0, the device does participate in the call.** In this case, an ordinary app's
independent `AudioRecord` can be silenced, but audio still reaches the call itself. The AI hearing it through the
carrier conference's Twilio leg is a separate path, and the earlier statement that "since the device is on a call, it
is fundamentally impossible to convey ambient speech to the AI" was incorrect.

The remaining conflict is the rule that "ordinary apps cannot capture simultaneously (the app with the foreground UI
wins)." Without registering `AudioRecord.registerAudioRecordingCallback()` and monitoring `isClientSilenced()`,
**you can end up capturing silence while believing it is working**. This must be implemented.

---

## 5. On-device verification of local recording (not yet performed)

Samsung SM-S942Z / Android 16 / serial `RFGL41GKP0Z`. Procedure is in Chapter 3 of [handover.md](handover.md).

- [ ] With microphone permission granted, whether starting the `microphone` FGS from the foreground yields non-silent PCM
- [ ] **Whether recording continues with the screen off / while locked** (log `isClientSilenced()` every time). This is
      the biggest unknown
- [ ] Whether pressing the Beacon while locked → tapping a notification action can start the FGS (whether the Chapter 3
      exemption holds on-device)
- [ ] Try launching directly from the background and confirm whether API 34+ really throws `SecurityException` (a
      negative confirmation)
- [ ] The difference with/without adding the app to Samsung's "Sleeping apps" list — One UI's power saving is not
      documented by Google and is **the biggest real-world cause of a correctly implemented FGS getting killed**
- [ ] The difference with/without battery-optimization exclusion (Doze does not stop the microphone, but **it does
      stop the network**, so a state can occur where recording succeeds but sending fails)
- [ ] Battery consumption while recording (%/hour)

Measurement tip: record "is it recording" and "is it sending" as separate facts. Conflating them leads to
misattributing the cause. Since Doze/power-saving network restrictions and OEM behavior are both in play, observe
capture and transmission separately. The commands to use are collected in 0.2. Append observations below together
with the date/time, screen state, lock state, and charging state.

### Observation log (append entries here)

Local recording has not yet been measured. The user's success report for the manual 3-way call is recorded in **0.5**.

---

## 6. Sound-to-text comparison if adopting standalone local recording

The following is a comparison for the original recording proposal. Prices and latency are approximations from the
time of investigation and should be re-verified upon adoption. With the carrier-conference approach, the existing
Realtime handles the single ordinary audio stream arriving from Twilio, so this separate chunking scheme is not
required.

| Approach | Does raw audio leave the device | Latency | Non-speech sounds (screams, glass) | Cost | Implementation cost |
| --- | --- | --- | --- | --- | --- |
| A. MediaPipe + YAMNet (on-device) | **No** | ~12 ms | **Yes (the only one)** | **$0** | Medium |
| B. `SpeechRecognizer` (on-device) | Not guaranteed | ~1 s | No | $0 | Medium–high |
| C. Piggyback on the existing Realtime session | Yes | ~1 s | No | Token-based billing | Low (**but breaks the call**) |
| D. Separate Realtime transcription session | Yes | ~1 s | No | $0.017/min | Medium |
| **E. Chunked transcription (HTTP)** | Yes | 10–15 s | No | **$0.003/min** | **Low** |

Cost source: <https://developers.openai.com/api/docs/pricing> (Transcription models).

**Recommendation for the local-recording proposal: pursue E first, and add A as a second phase if there's headroom.
The current experiment priority is Chapter 0.** POST 15-second PCM chunks to the backend, send them to
`gpt-4o-mini-transcribe`, and store only the resulting text. Do not send silent chunks (below an RMS threshold). This
cuts bandwidth and cost by an order of magnitude. Adding A covers things like "Screaming" and "Glass" at
**an additional cost of $0**.

**Avoid C (haphazardly mixing separately captured PCM into the existing call input).**
Realtime's input buffer is a single stream with no means of speaker separation, and if the call transcription breaks
there is no recovery. Carelessly modifying the injection logic could also undermine the `response.create` race-condition
mitigation from rev `00029-pfg`. Do not conflate receiving this carrier-conference leg with option C — though the
speaker-identification limits of mixed audio still remain.

Avoid B as well. The official reference explicitly states "this API is not intended to be used for continuous
recognition," and both `EXTRA_PREFER_OFFLINE` and segmented sessions are documented as "may have no effect"
(<https://developer.android.com/reference/android/speech/SpeechRecognizer>).

The Android platform has **no official acoustic-event classification API available to third-party apps**. The
SoundTrigger family is exclusive to the user-selected `VoiceInteractionService` and is limited to hotword-style targets.

---

## 7. Privacy and policy

- **"Not stored" and "not sent" are different promises.** Adopting E/D means raw audio temporarily leaves the device.
  OpenAI's retention policy on their side is outside our control. This must be stated explicitly in Chapter 12 of
  [plan.md](plan.md).
- The mic-in-use indicator (Android 12+) **cannot be hidden**. `WindowInsets.getPrivacyIndicatorBounds()` is only an
  API for learning where it appears.
- Providing an Activity with a `VIEW_PERMISSION_USAGE` intent filter lets users open an explanation screen from the
  privacy dashboard about "why is it listening." Worth including for an emergency app.
- When the device-wide microphone toggle is OFF, the app receives **silence, not an error**.
  `SensorPrivacyManager.supportsSensorToggle()` can be used to check support.
- Battery-optimization exclusion is explicitly listed as an allowed use case for a "Safety app" under Play policy.
  LIFELiNK qualifies.

---

## 8. Implementation design proposal (**not frozen**) and open items

### 8.1 Proposed schema (write to [plan.md](plan.md) before implementing)

Proposal to add `type: "ambient"` to `emergency_events/{id}/updates/{update_id}`, fitting the existing schema in
Chapter 6a:

```yaml
type: ambient
author_type: system
author_uid: null
author_name: "Ambient"
text: string            # e.g. "Two people are arguing loudly." / "Glass breaking (0.82)"
payload:
  provider: string      # "openai:gpt-4o-mini-transcribe" | "yamnet"
  captured_at: string   # ISO8601, start time of the chunk
  confidence: number | null
created_at: timestamp
delivered_to_ai_at: timestamp | null
```

On the Android side, the live feed can surface this with just one added line, `"ambient" -> ...`, in the
`EmergencyFeed.kt` mapper. Add `AMBIENT` to `EmergencyFeedKind`, either center-aligned like `SYSTEM` or as a dedicated
pale-colored bubble.

### 8.2 Injection into the AI must always be throttled

If `injectEmergencyUpdate` is called for every single chunk as-is, **the call turns into a live play-by-play and the
important exchange gets buried**. Even location updates alone come in every 10 seconds while a call is in progress. At
minimum:

- Only inject when the meaning of the text has changed from the previous observation
- Enforce a minimum interval (e.g., 30 seconds)
- Treat critical labels such as "scream" or "glass" as exceptions for immediate injection

This policy itself has not yet been confirmed with a human. Get agreement before implementing.

### 8.3 Open items (to ask / decide with a human)

- **How and with what wording to obtain consent**. On which screen, and with what wording, should explicit consent be
  obtained for "listening to the surroundings via the microphone during an emergency, converting it to text, and
  sharing it with contacts and friends"? This touches Play's prominent-disclosure requirements.
- **"Not stored" and "not sent" are different promises** (Chapter 7). If the chunked-send approach is adopted, raw
  audio is temporarily handed to OpenAI. Should this distinction be stated explicitly in Chapter 12 of
  [plan.md](plan.md) and reflected in the consent wording too?
- **Conditions for starting recording**. Only while an SOS call is active, or continuously while monitoring? If
  continuous, the battery and privacy impact is an order of magnitude larger.
- **Conditions for stopping recording**. Stop when the call ends, or always stop after a fixed duration? Forgetting to
  stop would be a serious incident.
- Whether to set `AudioRecord` to `setPrivacySensitive(true)`. Setting it to true prevents other apps (including
  assistants) from taking over the mic, but also means this app cannot capture simultaneously with other apps. Is
  true the right choice for an emergency use case?
- Whether `audio/pcmu` works in a transcription session (if it does, device→backend could be unified at 8 kHz μ-law,
  cutting bandwidth to 1/4). However, since Chapter 6's recommendation is the HTTP chunk approach, this is not
  needed for now.
- Confirm OpenAI's own audio-data retention policy.

---

## 9. Sources

- Foreground service types / background-start restrictions / while-in-use exemptions:
  <https://developer.android.com/develop/background-work/services/fgs/service-types>,
  <https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start>,
  <https://developer.android.com/about/versions/14/changes/fgs-types-required>
- Concurrent capture and in-call rules: <https://developer.android.com/media/platform/sharing-audio-input>
- Indicators / microphone toggle: <https://developer.android.com/training/permissions/explaining-access>
- Doze / App Standby: <https://developer.android.com/training/monitoring-device-state/doze-standby>
- MediaPipe Audio Classifier (YAMNet):
  <https://developers.google.com/edge/mediapipe/solutions/audio/audio_classifier/android>
- OpenAI Realtime transcription: <https://developers.openai.com/api/docs/guides/realtime-transcription>
</content>
