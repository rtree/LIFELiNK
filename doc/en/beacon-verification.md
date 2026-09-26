# "+Beacon" Button (PB-BTN-01) On-Device Verification Log (2026-09-26)

A summary of the results of verifying LIFELiNK's physical button path (iBeacon) on a Samsung Galaxy (SM-S942Z / Android 16 / API 36, charging via USB). The authoritative source for the detailed background and decisions is Chapter 6b of [doc/en/plan.md](/doc/en/plan.md); the authoritative source for tasks is [doc/en/tasks.md](/doc/en/tasks.md) (P0-14a / P0-15 / P3-02 through P3-04).

## 1. Button Specifications and Configuration

- Specification sheet: `doc/reference/＋Beacon ボタン_製品仕様書.pdf` (Braveridge, Version 1.0.0). Shipped in IDLE (radio stopped); it only starts advertising once switched to RUNNING via NFC. Removing/reinserting the battery does not change the state. Low battery is signaled via the topmost Major bit.
- Configuration is read and written using "Check/Change Settings via NFC" in the manufacturer's app `com.braveridge.pbeacon_button`.
- Adopted settings: **Button Detection Mode / Advertising Interval 1 second / Advertising Transmission Duration 60 seconds / TxPower 0 dBm**. The transmission duration can be selected from `Continuous / 10 seconds / 60 seconds / 3600 seconds`.
- Device ID `49C6FC80585C0E3A`, FW 1.0.5.

## 2. Mapping Between Advertisements and Operations (Confirmed by Measurement)

| Operation | Slot | UUID | Major (raw value) | Minor |
| --- | --- | --- | --- | --- |
| Idle (no operation, constant) | Beacon0 | `BB192440-9E4F-497D-8ACE-7B2BA67CC2FE` | 11665 | 31295 |
| Button 1 short press | Beacon1 | `581E31D6-E7BA-407A-B12E-949ACE475485` | 7290 | 36652 |
| Button 1 long press | Beacon1 | same as above | 23674 (7290 + `0x4000`) | 36652 |
| Button 2 short press | Beacon2 | `AA82CE42-BFC7-4182-B760-1CCA10116876` | 12975 | 16823 |
| Button 2 long press | Beacon2 | same as above | 29359 (12975 + `0x4000`) | 16823 |

- Major bit15 (`0x8000`) = low battery, bit14 (`0x4000`) = long press, lower 14 bits = configuration value.
- Pressing the button transmits the corresponding slot for the transmission duration, then returns to Beacon0 (idle). A re-press during transmission extends the transmission duration.
- The number of advertisement packets does not correspond to the number of button presses. iBeacon has no press counter, so **re-pressing the same button with the same press type during transmission cannot be distinguished at the advertisement level**.

## 3. LIFELiNK's Trigger Specification (Current)

- Buttons are linked per user. At link time, all observed slots (UUID, lower 14 bits of Major, Minor) and the most-frequently-received slot (= the idle slot) are saved locally on the device. Devices that are unlinked or whose idle slot has not been determined do not trigger a call. The BLE address is not used for the determination.
- **All 4 patterns (Button 1/2, short press/long press) trigger an immediate call** (this anticipates holding the button down or repeatedly pressing it during a panic; false triggers are tolerated for the hackathon). The condition is "the instant the state changes to something other than idle" or "a press packet after being held down and dropping out for more than `transmission duration + 15 seconds` (75 seconds with the 60-second setting)".
- Double-firing is prevented by checking for in-progress events at the Android Safety gate and by idempotency on the backend side. If the button is still held after the call ends, another call is triggered.
- Received via a PendingIntent `ScanFilter` (`SCAN_MODE_LOW_LATENCY`, matching the saved slot while ignoring bits 14/15); the Receiver compares against the previous state. A match whose `ScanResult.timestampNanos` is older than 10 seconds is discarded.
- Kept resident via a `connectedDevice` Foreground Service, which permanently displays "LIFELiNK monitoring" in the notification area (if the notification disappears, that is the signal that monitoring has stopped). A heartbeat is logged every 60 seconds. The user is guided to exclude the app from battery optimization.
- The default is a dry run (no call is placed). Turning the on-screen switch OFF enables actual calling.

## 4. Verification Results

| Date/Time | Condition | Result |
| --- | --- | --- |
| 09:31–09:35 | Dry run, state transition log | Confirmed the mapping between operations and slots (table above). Discovered and fixed a bug in the old implementation **where the idle advertisement (Beacon0) was triggering calls** |
| 10:24–10:27 | Screen ON, only long press triggers (old spec) | Fully matched the expected table. Press → state-change packet approx. 1–2 seconds; packet → acceptance 5–8ms |
| 10:28–10:37 | Screen locked, no foreground service | **Zero packets received**. Scanning stopped immediately after locking; Samsung Freecess froze the app after about 1 minute |
| 10:57–11:02 | Locked, resident FGS + optimization exclusion | Not frozen, heartbeat continued, long press accepted while locked (press → acceptance 3–7 seconds). During the 60-second burst there was a gap in reception of up to about 42 seconds, causing 1 false detection under the old "30-second gap" rule → rule changed to 75 seconds |
| 11:04–11:11 | Dry run OFF, actual call | 1 long press → 1 `POST /v1/emergency-events` → Twilio `completed` in 46 seconds. Approximately 7 seconds from press to incoming call (1.4 seconds from packet to API response; the remainder is Twilio dialing/connecting). A re-press during the burst was not accepted (as per the rule) |
| 11:34–11:41 | 10-second transmission duration, all patterns trigger immediately | Screen ON: short press, long press, and rapid presses were each accepted once (rapid presses counted only once). 10 repetitions at 30-second intervals while locked: 10/10 accepted, 0 false detections |
| 11:49–11:51 | 10-second transmission duration, actual calls | 2 successful calls, 1 missed. Immediately after the screen turned OFF there was a reception gap of about 38 seconds, and the entire 10-second burst fell within that gap |
| 12:04–12:17 | Screen OFF 60 seconds / ON 15 seconds × 10 (automated via adb, no button operation) | Idle advertisements arrive roughly every 1 second, but while the screen is OFF only 5–14 packets arrive per 60 seconds, with repeated gaps of 10–23 seconds. Time to first reception after turning OFF ranged from 0.2 to 22.5 seconds. Estimated miss rate: **approx. 15% for a 10-second burst, 0% for 30 seconds or more** |

## 5. Decision

- The transmission duration is **60 seconds** (gap threshold 75 seconds). This is longer than the reception gaps observed while the screen is OFF (up to approx. 23–38 seconds), which almost eliminates missed detections. In exchange, re-pressing the same button with the same press type requires waiting 75 seconds. Switching to a different button, or from short press to long press (or vice versa), triggers immediately even within the 60 seconds. When repeatedly pressing for a demo, use a separate physical unit.
- Beacon is treated as a backup path with a delay of about 2 seconds while the screen is ON, and several seconds to several tens of seconds while locked. For use cases that require guaranteed delivery, a press count, and ACKs, GATT (Seeed XIAO nRF52840, Model XIAO-nRF52840, FCC ID Z4T-XIAONRF52840, Technical Conformity Certification 211-220207) is the primary candidate.
- This is also consistent with another team's PoC (PB-BTN-01, exact-match filter on the long-press Major, observed within 10 seconds, at least 10 seconds since the last acceptance). It is also consistent with the result that reception failed during extended idle periods while locked.

## 6. Known Constraints and Follow-up Tasks

- Reception while the screen is OFF is throttled by the OS (measured while charging; this may worsen further when not connected via USB) → P3-03 will measure the effect of disconnecting USB and re-registering the scan.
- Updating the APK (`adb install -r`) stops the monitoring service. After an update, the user must press "Start monitoring" again.
- The app cannot distinguish whether the button is IDLE, out of battery, or out of range → P3-04 will implement a liveness indicator and warning based on the last-received time of the idle advertisement.
- Behavior during extended lock periods (15 minutes to 2 hours), recovery after reboot, and behavior when notification permission is denied have not been verified → P3-02.
- Measurement logs: the on-screen "Beacon log" (selectable/bulk-copyable) and logcat tags `LIFELiNK.BeaconLog` (decisions) and `LIFELiNK.Beacon` (per-packet). Screen OFF/ON, unlock events, charging state, and reception gaps of 3 seconds or more ("Reception resumed: after ◯ seconds") are also recorded in the in-app log.
