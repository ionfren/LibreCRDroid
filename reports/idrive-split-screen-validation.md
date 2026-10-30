# iDrive split-screen repair — 19 October 2026

The user’s vehicle photo confirms that the Material dashboard is visible on the
head unit, but the native navigation sidebar crops its right side. The old view
used the physical display capabilities for every frame and ignored mode events.

The repair handles application-level RHMI SPLITSCREEN events (event 7, parameter
7) before dispatching component events. Boolean and integral 0/1 payloads update
the remembered mode. The remote image component is resized before its JPEG is
posted. Mode changes while hidden are remembered without image transfers or
focus changes; identical/malformed notifications do not cause redraws.

Sidebar dimensions use the existing IDriveConnectKit 0.8 SidebarRHMIDimensions
implementation. Automatic starts at a sidebar-safe width until the car reports
its mode. New persisted Automatic / Split screen / Full screen choices under
Settings → BMW iDrive → Car display provide an immediate manual override for
head units that do not report the event. The native sidebar itself is unchanged.

The renderer preserves the full-width glucose card scale in split mode and gives
the chart a narrower card, shorter title and fewer time labels. All three hours,
target boundaries, right axis and newest reading remain inside the image.
Reading age/time moved inside the glucose card to avoid the native BMW status
bar that overlays the top-right corner.

Validation:

- App unit tests: 117, zero failures/errors (29 iDrive tests).
- Debug and release APK builds: passed.
- Lint debug: 0 errors, 89 warnings.
- Native graphics reviewed at split 743×480 and 838×540, and full 1211×480.
- Pixel regression verifies the glucose value retains its size and the newest
  chart point stays inside the split viewport.
- Transport regression decodes the emitted RHMI image after application events,
  tests mode changes without a new glucose value, and confirms no focus stealing.
- View tests cover hidden transitions, manual overrides, numeric payloads,
  invalid/repeated events and narrow physical screens.

Artifacts:

- `app/build/outputs/apk/debug/LibreCRDroid-0.1.6-idrive-split-screen.apk`
- `reports/idrive-split-screen.jpg`
- `reports/idrive-split-screen-evo.jpg`
- `reports/idrive-full-screen-fixed.jpg`

Previews use demonstration data. No phone installation or vehicle test of this
repair was performed. Vehicle delivery/interpretation of the split-screen event
still needs confirmation; the explicit Split screen option handles missing or
inconsistent notifications. Reconnect Connected Apps after installing the APK.

References:

- [RHMI events](https://bimmergestalt.github.io/BMWConnectedAnalysis/rhmi-events/)
- [IDriveConnectKit dimensions](https://github.com/BimmerGestalt/IDriveConnectKit/blob/main/core/src/commonMain/kotlin/io/bimmergestalt/idriveconnectkit/RHMIDimensions.kt)
