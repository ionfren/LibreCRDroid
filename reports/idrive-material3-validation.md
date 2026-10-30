# iDrive Material 3 dashboard — 19 October 2026

Replaced the oversized single-value image with rounded tonal cards, the app’s
Google Sans Rounded font and dark teal/range palette. Glucose uses a 78px design
size instead of 148px; the trend glyph uses 32px instead of 130px. Wide screens
allocate their extra width to the chart. No RHMI registration or focus changes.

The right card shows three hours of persisted chart history plus the live recent
ring, the selected unit, configured target band and range colors. The live reading
updates the chart tail immediately while preserving valid uncapped LO history.
Gaps of six minutes or more are not connected. Expiration and sensor states hide
the current value/trend while leaving historical samples at their actual times.
The storage query runs once a minute; the one-second clock continues to enforce
freshness. Unchanged content does not transmit another image.

Validation:

- `:app:testDebugUnitTest`: 111 tests, zero failures/errors (23 iDrive tests).
- `:app:assembleDebug` and `:app:assembleRelease`: passed.
- `:app:lintDebug`: zero errors, 88 warnings.
- Native graphics reviewed at 743×365, 800×380, 800×480, 1280×480 and 1440×540.
- Romanian, English and French previews; mg/dL, mmol/L, custom targets, missing
  history, stale state and gaps rendered. A pixel-level test verifies that the
  graph does not bridge a missing interval.
- Data tests cover timestamp validation, ordering, minute deduplication, three-hour
  filtering, uncapped history, newest live tail, targets and unchanged-second ticks.

Durable previews (demonstration data):

- `reports/idrive-material3-wide.jpg`
- `reports/idrive-material3-compact.jpg`
- `reports/idrive-material3-stale.jpg`

APK: `app/build/outputs/apk/debug/LibreCRDroid-0.1.6-idrive-material3.apk`.
The debug variant retains the signing configuration used by the previously
installed 0.1.6 debug build. The release output is also available at
`app/build/outputs/apk/release/app-release.apk` with its separate signing identity.
No installation or vehicle test was performed during this change. Reconnect
AAIdrive / Connected Apps after installing the updated APK.
