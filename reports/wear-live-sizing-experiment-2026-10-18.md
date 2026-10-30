# Wear OS live indicator sizing experiment

Tested on the Wear OS 7 / API 37 round emulator, using simulated display text only.
The installed application on Pixel Watch 4 was not changed by this experiment.

## Requested sizing

- Glucose: 28dp text size.
- Delta: 14dp text size.
- Reading age: 11dp text size.
- Arrow: 1.5 times the normal text size in the styled-text attempt.

## Results

1. `Notification.Builder.setContentTitle()` removes `AbsoluteSizeSpan` and
   `RelativeSizeSpan`. Bold styling survives, but the requested sizes do not.
2. The native indicator uses `setShortCriticalText(String)`, which cannot carry
   font spans. Injecting a `SpannableString` into that extra does not work: the
   platform cannot read it as the expected string.
3. API 37's `Notification.Metric.FixedText(CharSequence)` also converts styled
   input to a plain string, so the newer compact-content mechanism does not
   preserve the sizes either.
4. A pre-rendered 480 by 96 pixel image preserves the desired proportions in
   the asset, but the watch-face host scales it down to the notification icon
   slot. The text becomes too small to read. This does not provide a larger
   custom indicator.

These are observations from the emulator, not a claim that every watch renderer
has identical pixel dimensions. They explain why changing the font sizes in app
code cannot directly change the native status chip's text sizes.

The existing readable native-chip implementation remains in use on the watch.
App-owned canvas rendering in the application's complications can control text
sizes; the system-owned chip does not expose equivalent font-size parameters.

## Evidence

- [Native chip capture](wear-live-size-native.png)
- [Pre-rendered image inside the system icon slot](wear-live-size-forced.png)
- [Requested image before system scaling](wear-live-size-requested.png)
- Reproducible instrumentation probe:
  `wear/src/androidTest/kotlin/re/abbot/librecr/app/live/WearLiveUpdatesSizingTest.kt`.
  It passed on the emulator and is restricted to emulator hardware/model checks.
- [Android Live Updates guidance](https://developer.android.com/develop/ui/views/notifications/live-update)

The probe posts a temporary notification, captures both variants, and cancels it
at the end. It does not write simulated sensor readings or session data.
