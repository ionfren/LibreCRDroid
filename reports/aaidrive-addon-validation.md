# AAIdrive addon MVP — 18 October 2026

Implemented a dedicated glucose page inside the existing phone APK. Opt in under
Settings → BMW iDrive, then reconnect AAIdrive/Connected Apps. AAIdrive discovers
the exported connection service, and its binding owns the remote page lifetime.

Validation completed locally:

- `:app:assembleDebug` and `:app:assembleRelease` passed.
- `:app:testDebugUnitTest`: 88 tests, zero failures (10 addon tests).
- `:app:lintDebug`: zero errors; repository and dependency warnings remain.
- Native graphics previews checked at 800×480, 1280×480 and 1440×540, plus stale state.
- Original RHMI layout in the official AAIdrive 1.4.4 nomap/nonalytics release
  parsed and initialized successfully with the new view: state 19, image model 531,
  focus event and image-model delivery verified in a local JVM smoke check.
- Release APK signature verified; no OEM `smartthings` resources bundled in either APK.

The new tests cover mg/dL and mmol/L, fractional delta, sensor trend preservation,
expiry exactly at six minutes, errors/unavailable/warmup/no-data states, invalid
timestamps, local connection intent validation, focus-driven image delivery and
reopening, unsupported layouts, and image rendering.

Hardware authentication, cross-package asset loading on the actual phone, menu
placement, shortcut behavior, connection reliability and display updates with the
phone screen off require a real BMW test. No APK was installed on a phone or car.
Check reconnect, disabling/re-enabling, music/calls, watch relay and expiry with no
new readings. The phone settings show resource/authentication/transport status;
diagnostic logging records connection failures without glucose values.

Artifacts: `app/build/outputs/apk/release/app-release.apk`,
`app/build/outputs/apk/debug/app-debug.apk`,
`app/build/reports/idrive-preview/`, and
`app/build/reports/tests/testDebugUnitTest/index.html`.

## Follow-up: addon discovered, menu entry absent

The user reports LibreCRDroid is visible in AAIdrive Addons and the phone says
“Screen registered in iDrive”, but the dashboard entry is absent. This places the
reported failure after discovery and successful startup RPCs, at menu visibility.

The initial build reused the RHMI app ID for its AM placeholder. The Application
Manager protocol allows a same-ID RHMI application to replace a placeholder with
its own inherited entry. The corrected build gives the custom launcher a distinct
`.launcher` ID, opens the existing RHMI page explicitly, and re-registers the AM
entry after selection to clear the loading indicator. The category remains
`OnlineServices` (Connected Apps / ConnectedDrive).

Also corrected the inherited list column layout (`57,100,*`) before sending the
one-column shortcut list, and removed an unnecessary AV instance-ID prerequisite
from this RHMI-only connection. Phone diagnostics now retain the failing startup
stage and unwrapped BMW error without requiring general diagnostic logging.

Regression coverage includes complete startup RPCs with a simulated head unit,
separate AM/RHMI identities, launcher selection and spinner reset, unrelated menu
events, initialization failures and cleanup, column-layout setup, missing AV instance
ID, and generated Etch errors with a null standard exception message. Physical menu
visibility after this repair still requires confirmation on the user's BMW.

Protocol reference: [Application Manager](https://bimmergestalt.github.io/BMWConnectedAnalysis/am/).

Repair validation: `:app:assembleRelease`, `:app:testDebugUnitTest` (95 tests, zero
failures), and `:app:lintDebug` passed. The signed repair artifact is
`app/build/outputs/apk/release/LibreCRDroid-idrive-menu-fix.apk`.

## Device update

ADB became available on the user's Pixel 10 Pro XL. The installed package was still
0.1.5 and no BMW addon service binding was active. The release APK could not update
it because its signing certificate differed. The installed certificate matched the
local debug certificate; no uninstall or app-data deletion was performed.

Bumped the phone application to versionCode 5 / versionName 0.1.6, built both
variants, and successfully updated the phone with `adb install -r` using the debug
APK. Package Manager confirmed version 0.1.6. Opened LibreCRDroid and AAIdrive.
The addon binding was still inactive at this check; Connected Apps must reconnect
before the repaired menu registration can be verified. The matching-signature
artifact is `app/build/outputs/apk/debug/LibreCRDroid-0.1.6-idrive.apk`.
