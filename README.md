# LibreCRDroid

> **⚠️ WARNING — for testing and research only**
> This application is not a certified medical device. Do not make medical
> decisions based on displayed values. Always verify blood glucose with a
> certified glucometer.
> 
**⚠️Tested only on Pixel 10 pro XL and Pixel watch 4**
> 
Kotlin/Android port of [LibreCRKit](../LibreCRKit) (Swift/iOS) — full Libre 3
protocol: NFC activation, BLE GATT transport, security handshake (Phase 5/6,
AES white-box, P-256 ECDH/ECDSA), per-minute glucose decoding, and persistent
background connection.

The protocol layer is **byte-for-byte identical to the Swift implementation**,
proven by golden-vector tests.

---

## Module architecture

### `:protocol` — pure Kotlin/JVM library
No Android dependencies. Runs as plain JUnit on any dev machine.

- **BLE framing** — 2B LE offset writes + 18B chunks; notification reassembly
- **`CRC16`** —  CRC16 + NFC commands 
- **`AesCcm`** — AES-CCM (SP800-38C), configurable tag; AES block = JCA
- **`LibAes`** — table-driven white-box AES (Phase 5/6 block primitive);
  `libaes_*.bin` tables are byte-identical copies of the Swift resources
- **`Ecc`** — P-256 ECDH + ECDSA-SHA256 via JCA
- **`SensorCert`** — 140 B parse + ECDSA verify against bundled Abbott keys
- **`PairingFlow`** — full state machine: fresh first-pair and cached reconnect
  (Phase 5 raw key caching)
- **Data plane** — `DataPlaneCrypto`, `RealtimeGlucoseReading`, `PatchStatus`,
  `HistoricalReadingPage`, `ClinicalReadingRecord`

### `:app` — Android phone application
- `BluetoothGatt` transport with serialized GATT operation queue
- Targeted BLE scanner (`autoConnect=false`, 60s cold-start scan timeout)
- BLE state machine: `CONNECTING → HANDSHAKE → ONLINE → RECONNECTING`
- `connectedDevice` foreground service — persistent background connection
- **Automatic reconnect** after BLE loss (attempt-indexed backoff:
  500ms → 2s → 2s → 5s → 10s → 20s → 30s max)
- No-data watchdog (reconnect if no reading arrives for >N minutes)
- DataStore persistence: NFC session, cached Phase 5 key, last glucose
- **Sensor errors** (`PatchStatus`) surfaced visually: `CheckSensor`,
  `SensorEnded`, `ReplaceSensor`, unknown codes
- System notifications for sensor attention states
- Structured log viewer with hex dump of every GATT operation
- Compose UI: home screen (glucose + trend + delta + status), settings, log
- **LibreView cloud upload** — each reading forwarded to Abbott's `/api/measurements`
  endpoint; cached session, lifeCount dedup, automatic re-auth on token rejection
- **Glucose widgets** — Material 3 Jetpack Glance widgets in the selected unit, with
  range colors, the sensor trend in an expressive shape, rate per minute, a
  host-rendered elapsed-time clock and, when enlarged, a three-hour sparkline.
- **Android Auto notifications** — opt-in experimental CGM messaging card with a
  dedicated on/off switch, quiet refreshes and explicit stale/error states.

### `:wear` — Wear OS watch application
- Direct BLE connection watch → sensor (independent of phone)
- `connectedDevice` foreground service on the watch
- **Fast reconnect** with the same attempt-indexed backoff strategy
- Watchface complications: glucose value + trend + delta + reading age
- Visual error badge on complications for sensor errors (red/yellow)
- Bidirectional phone ↔ watch sync via Wear Data Layer:
  glucose readings, sensor states, session handoff, BLE logs
- **Watch log sync** — watch's BLE log visible in the phone's Log screen
- One-button session handoff phone → watch
- Live glucose notification / Ongoing Activity: trend → value → delta/min → elapsed minutes,
  for direct BLE and phone-relayed readings. On Wear OS 7, also requests native Live Update
  promotion (subject to system settings and the watch manufacturer's implementation).
- The ongoing activity icon contains a monochrome trend arrow, glucose and delta. The complete
  text and elapsed minutes appear in the notification and launcher Recents; tiny watch-face
  indicators may only show the icon. The regular app launcher icon stays unchanged.
- The Wear status chip puts two spaces on each side of the middle dot between glucose and delta
  (for example `120  ·  -2 · 0′`). The arrow is aligned to the right edge of its icon slot to bring
  it closer to glucose; the system controls the remaining icon-to-text gap. The ongoing
  icon also includes age beside delta. Launcher Recents uses a system-driven stopwatch so age
  advances while the app process sleeps. The notification exposes the original reading timestamp.
- Tap the watch glucose screen to enable/hide Live Updates. Hiding/dismissing is remembered
  across readings and restarts. The required BLE foreground-service notification remains.
  Values expire after the existing 330-second display window; relayed cards also have an OS
  timeout. The existing stale-complication alarm refreshes the notification without extra alarms.

---

## Feature matrix

| Feature | Phone | Watch |
|---|---|---|
| BLE connection to Libre 3 | ✅ | ✅ |
| Phase 5/6 handshake (fresh first-pair) | ✅ | ✅ |
| Cached Phase 5 key reconnect | ✅ | ✅ |
| Per-minute realtime glucose | ✅ | ✅ |
| Backfill (clinical per-min + historical 5-min) | ✅ | ✅ |
| Automatic reconnect with backoff | ✅ | ✅ |
| Foreground service (background) | ✅ | ✅ |
| NFC sensor activation | ✅ | — |
| Sensor error display | ✅ | ✅ (badge) |
| System notifications | ✅ | ✅ |
| Structured hex log | ✅ | ✅ |
| Bidirectional sync | ✅ ↔ ✅ | ✅ ↔ ✅ |
| Watch log on phone | ✅ | ✅ |
| Watchface complications | — | ✅ |
| LibreView cloud upload | ✅ | — |

---

## Build and test

```sh
# Protocol golden-vector tests (byte-perfect parity with Swift):
./gradlew :protocol:test

# Debug APKs:
./gradlew :app:assembleDebug :wear:assembleDebug

# Quick Kotlin compile check:
./gradlew :app:compileDebugKotlin :wear:compileDebugKotlin
```

## BMW iDrive through AAIdrive

An experimental dedicated glucose page is available for NBT/NBT Evo Connected Apps.
Install the official [AAIdrive](https://github.com/BimmerGestalt/AAIdrive/releases)
on the same phone and first confirm its BMW/MINI connection works. Enable
**Settings → BMW iDrive → Dedicated iDrive screen** in LibreCRDroid (off by default).
Open AAIdrive, check for LibreCRDroid under **Addons**, then reconnect Connected Apps
if the phone was already connected during installation. **LibreCR · Glucose**
(localized to the app language) opens automatically once its first presentation is ready.
It opens once per connection; reading updates and transport retries do not take focus
back after you leave the page. You can reopen it from the car's Connected Apps /
ConnectedDrive menu.

The page uses a dark Material 3 Expressive inspired dashboard with rounded tonal
cards, Google Sans Rounded, a compact glucose value/trend, delta per minute and
reading age/time. A three-hour glucose chart fills the remaining card, using the
selected unit, configured target range and glucose range colors. Persisted history
is refreshed once a minute and combined with the live recent ring. Known uncapped
history values are preserved. Missing intervals of six minutes or longer are not
connected, and expired readings are not extended to the current time.

The image and chart width follow native RHMI split-screen events. The glucose card
keeps its size in both modes. **Settings → BMW iDrive → Car display** offers explicit
Full screen and Split screen choices for head units that do not report their mode.
Automatic reserves room for the native sidebar until the car reports a mode.
Changes apply immediately without reopening the page or taking focus.

Both phone BLE and watch-relayed readings use the existing shared data funnel.
An independent one-second clock hides the current number after six minutes, even
without another reading; historical chart points retain their timestamps. Warmup,
unavailable readings and sensor errors also hide the numeric value. Only changed
visible content transmits an image. Hiding the page stops image transfers.

The LibreCR page no longer binds the AAIdrive map service, displays a map, offers
navigation/search controls or requires a Google Maps SDK key. The installed
AAIdrive continues to provide the Connected Apps transport and signed OEM resources.
The existing AAIdrive installation works; no companion map update is required.
Disconnecting or disabling the feature disposes the remote app. The addon adds
no BLE connection, cloud polling, screen-capture permission or foreground service.

The addon reads the original `smartthings` RHMI layout, image database and app
certificate from the installed AAIdrive package (`me.hufman.androidautoidrive`) at
runtime. These OEM resources are not copied into this repository or APK. It uses
IDriveConnectKit 0.8 and IDriveConnectKitAndroid 0.8, resolves the BMW/MINI security
service independently, and registers its own app/menu identity. A forked or future
AAIdrive package with different assets may require adaptation. Missing resources,
authentication waits and connection retries are reported on the phone's BMW iDrive
settings page. Automatic transport retries continue while AAIdrive keeps the addon
bound. Reconnect Connected Apps after installing/updating AAIdrive or fixing missing
resources.

If LibreCRDroid appears under AAIdrive Addons but not on the dashboard, check the
status and **Connection diagnostics** in Settings → BMW iDrive. **Screen registered**
means the startup RPCs completed; it does not acknowledge that the car drew the icon.
Use **Copy diagnostics** to share the last stage/error. The custom AM launcher has
its own identity, separate from the signed RHMI application's inherited entry, so
the head unit cannot replace it by matching the RHMI app ID. Selecting the launcher
focuses the existing glucose page and re-registers the launcher to clear its loading
indicator. Reconnect Connected Apps after updating from the initial addon build.

Local tests cover units, freshness, sensor states, history merging, uncapped values,
missing intervals, RHMI focus and image delivery, and full/split chart rendering.
Native-graphics previews are written to `app/build/reports/idrive-chart-preview/`
by `IDriveGlucoseRendererTest`; preview data is synthetic.
The original layout
from the official AAIdrive 1.4.4 release was also checked locally for compatibility;
its actual vehicle authentication and display have **not** been verified on hardware.
Before relying on this experimental display, test screen-off updates, music/calls,
phone/watch handoff, expiration after six minutes without readings, disconnect and
reconnect, toggling the setting, and a physical shortcut if supported by the head unit.
Keep the phone/watch glucose alarms active; this page does not implement car alarms
or an overlay over navigation, instrument-cluster output, or HUD output.

## Glucose widgets and Android Auto

Add **LibreCR · Compact glucose** or **LibreCR · Glucose with chart** from the
phone's widget picker. No configuration is required; the widgets use the latest
accepted phone or watch-relayed reading, the unit and the target range from
Settings. Tap a widget to open LibreCR on hosts that allow launching the phone
activity.

Both providers share one responsive Material 3 layout set and differ only in their
default size. They resize from a 2×1 bar through a 2×2 tile and the compact card
up to wide and tall cards that add a three-hour sparkline (target band, out-of-range
segments colored at the exact crossing, latest reading highlighted). The reading is
colored by range; the trend arrow sits in a calm cookie shape when in range and in a
burst when out of range, so the state does not rely on color alone. Surfaces use
the launcher's corner radius and the wallpaper (Material You) palette on Android 12+
with the teal brand colors as fallback, and every color has a light and dark variant.
Text uses the system Material 3 Expressive type scale (rounded Google Sans Flex) on
Pixels that ship it, Google Sans on older Pixels and the platform font elsewhere.
Android 15+ widget pickers show a preview generated from the real layout; Android
12–14 use a static preview with the same design. Picker previews always use sample
data, never your readings.

New readings and sensor-state changes trigger updates immediately. A minute ticker
runs while widgets are placed and the application process is alive. The reading-age
clock runs in the host, independently of the app process. At the existing six-minute
freshness threshold the widget hides the value, trend and delta and labels the data
as stale. Unavailable readings, warmup and sensor errors have explicit states.
An inexact, non-wakeup alarm and Android's 30-minute widget refresh provide fallback
updates after process death; Doze may delay those updates and the stale-state change.
There is no exact-alarm permission or additional foreground service.

These are standard app widgets that follow Google's car-widget guidance and the
widget quality guidelines: built with Glance, responsive to any grid cell down to
2×1 (short car-grid rows get the one-row layout), light and dark plus dynamic
theming — colors are system color resources, so a host can apply its own palette —
full-bleed bounds, no scrolling, and secondary rows that drop out before the
reading would be cropped by a small cell or a large font scale.
If your Android Auto version offers a car-widget picker, check for these LibreCR
widgets there. Availability, placement next to Maps/Waze and visibility when another
app is fullscreen are controlled by Android Auto and must be tested on the target
vehicle. Installing this APK does not enable Google's widget rollout.

### Experimental Android Auto notification

Enable **Settings → Android Auto → Persistent notifications** (off by default).
Allow LibreCRDroid notifications on the phone and enable message notifications and
the first message line in Android Auto settings. For a sideloaded APK, Android Auto
may also require **Developer settings → Unknown sources** for messaging notifications;
see Google's [testing guide](https://developer.android.com/training/cars/testing).
The setup steps are also shown directly in the Android Auto settings page. A delivery
status distinguishes a dismissed card, blocked permission and a successful phone post;
**Send notification again** retries with real current CGM data and a fresh session.
Successful posting on the phone is not an acknowledgement that the car displayed it.

The implementation declares the `notification` car capability and posts a single
`MessagingStyle` notification with reply/mark-as-read actions, using
[`CarAppExtender`](https://developer.android.com/reference/androidx/car/app/notification/CarAppExtender)
and `CarNotificationManager`. It follows the transport used by
[GlucoDataAuto](https://github.com/pachi81/GlucoDataAuto). The reply action only refreshes
local content; no voice reply is stored or sent. This does not create a separate car
app, a media session, an overlay, or a navigation template.

The card contains mg/dL, the sensor trend arrow, delta in mg/dL/min, elapsed reading
age and the original measurement time. Shared widget freshness rules hide obsolete
values after six minutes and handle unavailable readings, warmup and sensor errors.
Each accepted phone or watch-relayed update refreshes the card; a 60-second keepalive
also updates its age while the application process is running and Android Auto is
connected. The transport timestamp is refreshed for the car card, while the displayed
measurement timestamp and age always refer to the real reading. Doze or process death
can delay refreshes; this feature adds no foreground service or exact alarm.

Only the first post in a connection requests car heads-up importance; it may briefly
show a banner. Later refreshes use default car importance, a quiet phone channel and
the same notification ID. The channel has HIGH importance with no sound/vibration,
matching the GlucoDataAuto transport. Upgrades migrate the original LOW channel to a
new channel because Android cannot raise an existing channel's importance in place;
explicit user importance/blocking choices are preserved. The notification uses a
dismissible incoming-message envelope. Turning the switch off or disconnecting cancels the card.
Closing/marking it read hides it for the rest of the connection; reconnecting or
turning the option off/on allows it again. Dismissal is retained across process
restarts within the same phone boot. If an entire disconnect/reconnect happens while
the process is dead, a retained dismissal may require toggling the switch again.
[`CarConnection`](https://developer.android.com/reference/androidx/car/app/connection/CarConnection)
gates posting on phone projection only; Android Automotive OS native connections do
not enable it.

Android Auto decides the card's appearance, truncation, dashboard placement and
lifetime. Reposting a message **does not guarantee permanent visibility beside
Maps/Waze**. The Material 3 settings preview illustrates content,
not a custom car layout. Test this feature on the intended host before relying on
its visibility.

Google's [notification API](https://developer.android.com/training/cars/communication/notification-messaging)
is intended for actual messaging apps. This CGM-only use does not meet the
[car quality requirement MF-5](https://developer.android.com/docs/quality-guidelines/car-app-quality)
for a peer-to-peer messaging service, so it must not be presented as an approved
Google Play Android Auto integration. Keep this distinction when testing a sideloaded
build or preparing a Play release.

---

## Verification — "byte for byte"

Parity with Swift is enforced by golden vectors exported from the authoritative
Swift source:

1. **In the Swift repo:** `swift run librecr-vectors` writes
   `vectors/librecr_vectors.json` — canonical input→output pairs.
2. Copy that JSON to `protocol/src/test/resources/golden/`.
3. **`:protocol` JUnit tests** recompute every primitive and assert byte
   equality: CRC16, NFC commands, framing, AES-CCM (M4/M8), `LibAes`
   keySetup/block/phase5block, Phase 5 challenge, Phase 6 → session material,
   data-plane decrypt + glucose/patchStatus/historical/clinical parse, ECDH,
   ECDSA. Plus a `PairingFlow` loopback (cached + command-gated) exercising the
   full handshake state machine offline.

On device, `BleLog` emits a timestamped hex dump of every GATT write/notify and
each handshake stage — diff-able against an iOS capture.

---

## BLE reconnect strategy

On connection loss, the watch (and phone) reconnect automatically using an
attempt-indexed backoff:

| Attempt | Delay |
|---|---|
| 1 | 500 ms |
| 2 | 2 s |
| 3 | 2 s |
| 4 | 5 s |
| 5 | 10 s |
| 6 | 20 s |
| 7+ | 30 s (max) |

- Reconnect scan timeout: **12 seconds** (only cold start uses 60s)
- Backoff reset only after the first valid reading, not on mere GATT connect
- Single centralized funnel (`requestSensorReconnect`) — validated triggers:
  `gatt_disconnect`, `stale_fragment_timeout`, `no_data_watchdog`
- Cached Phase 5 key survives transient disconnects; discarded only on crypto
  mismatch or BLE address change

---

## NFC sensor activation

Press **Activează / preia** and hold the phone on the Libre 3 sensor. The app:

1. Reads `patchInfo` with `A1`
2. Sends `A0` (ACTIVATE) if the sensor is in storage state (`state == 1`),
   or `A8` (SWITCH_RECEIVER) to take over an already-active sensor
3. Parses the response: BLE address (bytes 3–8, reversed), BLE PIN (bytes 9–12),
   activation time (bytes 13–16)
4. Saves the `ImportedSession` automatically to DataStore — no manual JSON paste

---

## Sensor error states

`PatchStatus.sensorError` keeps normal end-of-wear separate from shutdown:

| `errorData` | Meaning | `sensorAttention` |
|---|---|---|
| 0 | Normal / check patchState | — |
| 3 | Insertion / start failure | `CheckSensor` |
| 5 | Expired (end-of-wear) | `SensorEnded` |
| 6 | Terminated | `SensorEnded` |
| 7 | Transmission error | `ReplaceSensor` |
| 8 | Terminated | `ReplaceSensor` |
| other | Unknown | `ReplaceSensor` |

Attention states are shown as a visual card on the phone's home screen and as a
colored badge on watch complications.

---

## LibreView cloud upload

Each glucose reading received from the sensor is automatically forwarded to
Abbott's **LibreView** cloud (the same backend used by LibreLink and LibreLinkUp),
enabling viewing in LibreView and sharing with followers via LibreLinkUp.

### How it works

1. The app authenticates with your LibreView credentials (`email` + `password`)
   against the regional LibreView API (`api.libreview.io` and regional mirrors).
2. A session token is cached in memory and reused across readings.
3. Each reading is posted to `/api/measurements` in the LibreLink-compatible
   format (glucose value, trend, timestamp, device serial, sensor serial).
4. The upload is serialized (one at a time), deduplicated by `lifeCount` so
   the same sensor minute is never submitted twice, and re-authenticated once
   automatically if the token is rejected (`status 20 — wrongDeviceInToken`).
5. Upload status is shown in the Settings → Cloud screen:
   uploading / last successful upload (time + mg/dL) / error.


---

## Phone ↔ watch sync

Bidirectional communication uses the Wear Data Layer (`MessageClient`) on
`/librecr/*` paths:

| Path | Direction | Content |
|---|---|---|
| `/librecr/glucose` | watch → phone | current glucose reading |
| `/librecr/glucose/replay` | watch → phone | backfill on reconnect |
| `/librecr/session` | phone → watch | NFC session handoff |
| `/librecr/sensor_status` | either → either | `errorData` + `patchState` |
| `/librecr/log/request` | phone → watch | request watch log |
| `/librecr/log` | watch → phone | watch BLE log |

---

## Status

- ✅ Protocol layer ported and byte-perfect (78 golden + loopback tests passing)
- ✅ Android BLE transport, state machine, foreground service, Compose UI
- ✅ Wear OS app with complications, reconnect, Data Layer sync
- ✅ Full NFC activation (A0/A8, response parsing, automatic session save)
- ✅ Sensor errors visible on phone and watch
- ✅ Fast reconnect with indexed backoff (max 30s, never 60s)
- ✅ Watch log visible on phone
- ✅ LibreView cloud upload (per-reading, cached session, auto re-auth)
- ⏳ On-device validation with a real sensor — diff `BleLog` against iOS capture

## Automatic watch–phone handoff

The phone's **Settings → Libre 3 connection → Automatic watch–phone handoff**
switch is off by default. With it on, the phone maintains a foreground backup
monitor while the watch collects. Its notification makes this background role
visible. Bluetooth permissions and Android foreground-service eligibility are
required; force-stopping the application prevents automatic recovery until it is
opened again.

Three failed watch reconnects request phone takeover. Independently, three
minutes without a new live sensor packet cause the phone to query the watch and,
if no healthy receiver answers, try the sensor itself. A dead watch is never
required to acknowledge a release. A healthy watch reported by a fresh status
response is left collecting, even if its relayed values stopped reaching the phone.
Both receivers use a replaceable wakeup alarm alongside the foreground monitor:
normal live packets postpone the alarm, rather than causing periodic wakeups.
The three-minute threshold is a target; Android may defer inexact alarms in Doze.
Unusable live sensor readings still prove the link is alive; historical replay,
duplicate values and delayed DataItems do not renew liveness.

A service-start acknowledgement is only a launch acknowledgement. The new
collector must deliver a fresh sensor packet, or a fresh status proving that it
has done so, within a 150-second grace period. An interrupted transfer is stored
and recovered after process restart. New commands carry a persisted sequence and
token; obsolete commands cannot restart a cancelled transfer.

After five usable readings in adjacent sensor minutes, the phone can return to
the original watch. The watch must be nearby and ready, with at least 15% battery
(or charging above 5%). At 5% or lower, an uncharged watch may proactively request
the phone. If the phone also loses the sensor, it can try an available watch
without waiting for five impossible readings. Failed watch transfers introduce
progressively longer pauses (up to 30 minutes) instead of oscillating indefinitely.

The watch keeps collecting when the phone is absent. Once its first three bounded
reconnects fail, it uses Android's persistent controller connection instead of
recreating GATT every 20 seconds. The phone also uses a passive controller wait
without holding a wake lock throughout the wait. If the wearer leaves both
receivers behind, neither can receive live readings while the sensor is out of
range; reconnection/backfill resumes when a receiver can reach it again.

Both receivers retain a separate backup of the peer's reusable reconnect key for
the same sensor. Receiving a backup never modifies a running BLE session, and an
older peer key cannot replace a newer proven local key. Per-link encryption
material is never backed up. A watch reboot checks a live phone before starting
BLE; an abandoned automatic release can recover on the watch without an ACK.
While automatically parked, the watch keeps its own foreground recovery monitor,
including after reboot, so a failed phone does not strand it. An explicit manual phone takeover parks the watch until another user-directed
handoff, and remains available with the automatic switch off.

Validation includes deterministic coordinator tests for peer loss, a dead watch,
all receivers out of range, low battery, stale/duplicate/wrong-sensor packets,
lost acknowledgements, service-start failure, transfer deadlines, and process
restart. Real-device validation must additionally cover a powered-off watch,
Android Doze/background starts, physical RF loss, key synchronization while
separated, and recovery after recharging. Do not infer a successful radio
reconnection from a passing JVM state-machine test.
