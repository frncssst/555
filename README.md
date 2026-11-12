# Host Spike

Phase 1 test app for the main-phone host agent. It answers four questions before any real product code is written:

1. Can an app that is **not** the default phone app answer and decline the ringing cellular call on command?
2. Can it receive and send SMS, and see MMS, **without** being the default SMS app?
3. Can it tell when the main SIM arrives in or leaves a phone?
4. Does it survive 48 hours in the background on the OnePlus 15 (and a Pixel) without being killed?

The app never blocks calls, never changes your default phone or SMS app, and keeps everything on the phone. The only network use is a small web page on your own Wi-Fi that stands in for a linked device.

## Build and install

**Option A, Android Studio (simplest)**

1. Open this folder in Android Studio (File → Open). Let it sync; it downloads the Android Gradle Plugin the first time.
2. Plug in the phone with USB debugging on, then press Run.

Installing through Android Studio or `adb` avoids Android's "restricted settings" block on SMS permissions that applies to some sideloaded apps.

**Option B, GitHub Actions**

Push the folder to a GitHub repo. The included workflow builds a debug APK and attaches it to the run as an artifact.

Requirements: Android 13 or later on the phone. The build targets API 35 and runs on Android 16 and 17.

## Set up on the phone (about 3 minutes)

In the app, work down section 1:

1. **Grant permissions.** Phone, call answering, SMS and notifications.
2. **Allow call screening.** Lets the app see the caller's number before the phone rings. It does not replace Google Phone's spam protection.
3. **Exempt from battery optimisation.**
4. **OnePlus only:** tap *Open app settings* → Battery → set to *Unrestricted*, and allow background activity. If a permission shows as a restricted setting, use the ⋮ menu → *Allow restricted settings*.
5. **Save your main number**, then **Start host service**. A persistent "Host spike running" notification appears.

The checklist at the top turns all ✓ when set up is complete.

## Tests

Run each test, then read the result in *Results* and the event log. Use **Save log** to export everything to Downloads.

### Test 1: remote answer and decline (the key test)

Needs a second phone to call from, and ideally a third device on the same Wi-Fi.

1. On the third device, open the address shown in section 2 of the app (it includes a token).
2. Call the main number from the second phone.
3. While it rings, tap **Answer on main phone** on the web page. Repeat with **Take it here (decline to forward)**.
4. Single-device alternative: set section 3 to *Answer automatically* or *Decline automatically*, then call in.

| Check | Pass |
|---|---|
| `answer_requested ok=true`, followed by `call_answered trigger=answer` | Remote answer works |
| `decline_requested returned=true`, followed by `call_ended_while_ringing trigger=decline` | Remote decline works |
| After decline, the call rings your softphone (Crazytel) | Decline-to-forward works from the app, as it did by hand in Phase 0 |
| `call_ringing` shows the caller's number | Caller number available. If it says "screening not invoked", try a caller who is **not** in your contacts and note the difference |
| `ms_from_command` | Hand-over latency. Target under 2 seconds |

### Test 2: SMS and MMS without being the default SMS app

1. Text the main number from another phone. Expect `sms_received` with the right sender.
2. Send a test SMS from section 4, or from the web page. Expect `sms_send_requested`, then `sms_sent ok=true`, then `sms_delivered`.
3. Check Google Messages: does the sent message appear in the conversation? Note yes or no.
4. Send a picture message to the main number. Expect `mms_seen` with `part_types` including `image/...`. Note `ms_since_message_date`.

| Check | Pass |
|---|---|
| Incoming SMS logged within a few seconds | SMS receive works |
| Sent and delivered reports arrive | SMS send works |
| Sent message visible in Google Messages | No duplicate store needed for sent texts |
| `mms_seen` appears after Google Messages downloads the picture | MMS readable after the fact |

### Test 3: SIM detection

1. Note the `sims_initial` event and its `is_host` value.
2. Power off, remove the SIM, power on. Expect `sims_changed` with `is_host=false`.
3. Put the SIM back. Expect `sims_changed` with `is_host=true`.
4. If `number` shows "(not provided by SIM)", the carrier does not store the number on the SIM. Record this: the real app would then need another way to recognise the main SIM.

### Test 4: 48 hours in the background

1. Start the service, leave the phone unplugged overnight and used normally for two days.
2. Make a few test calls and texts across the period, including after the screen has been off for hours.
3. At the end, check *Results*:

| Check | Pass |
|---|---|
| Service stops = 0, apart from ones you caused | Not killed |
| No incoming call or SMS missing from the log | No missed events |
| Longest heartbeat gap | Gaps over 20 minutes mean the phone deferred the app's timer in deep sleep. That is fine **if** calls and texts were still logged on time |
| Battery change over the run | Compare with a normal day |
| Reboot once: `boot_completed` then `service_started` | Survives reboots |

## Version 0.2 changes (after the first 17-hour run)

- **All messages, any app:** the app now reads the phone's message store for texts and picture messages in both directions, including ones you send from Google Messages (`sms_stored`, `mms_stored`).
- **Picture messages:** the store is also checked on every heartbeat, because change notifications missed an MMS in 0.1, and an MMS with no content yet is re-read after 3, 10, 30 and 120 seconds.
- **SIM blips:** raw SIM events are still logged, but the host role only changes if the change lasts two minutes (`host_changed`). No service, such as on a flight, keeps the role and is logged as an unknown period.
- **Heartbeat:** uses an alarm Android allows during Doze instead of an in-app timer, and logs whether Battery Saver was on.
- **Privacy:** message text is no longer logged.

Re-test after installing 0.2: receive one MMS, send one text and one MMS from Google Messages, reboot the phone without opening the app, and send one Answer or Decline from another device on the same Wi-Fi.

## Known unknowns this app is designed to expose

- The answer and decline methods are deprecated. They may silently do nothing, return false, or throw on current Android when the app is not the default phone app. Every attempt is logged with its outcome either way.
- Android only lets a non-default call screening app see some calls, commonly numbers not in contacts.
- Some carriers leave the phone number off the SIM, which affects how the real app identifies the main SIM.
- OnePlus battery management is stricter than stock Android.

## Privacy

From version 0.2 the log contains phone numbers and message lengths, but no message text. Logs saved from version 0.1 still contain the first 40 characters of each incoming text. It stays in the app's private storage unless you save or copy it. Clear it when the tests are done. The web page only works on your local network and needs the token.

## What to send back

Use **Save log** and share the file, or paste the *Results* summary. Note anything you saw that the log cannot: whether the softphone rang after a decline, and whether sent texts appeared in Google Messages.
