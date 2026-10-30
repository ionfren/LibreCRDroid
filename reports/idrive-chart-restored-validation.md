# Restored iDrive glucose chart — 20 October 2026

The LibreCR iDrive page again displays a three-hour glucose chart beside the
existing Material-style glucose card. The map, map-status settings, navigation
banner, destination controls and map service client have been removed from the
LibreCRDroid app. The installed AAIdrive is used only for its normal Connected
Apps transport and signed resources; it does not need an update for this change.
The separate AAIdrive project and its standalone map functionality are unchanged.

The chart combines persistent chart samples refreshed once a minute with the live
recent ring and latest reading. It preserves uncapped historical values when the
headline contains a capped value, removes invalid/future timestamps, de-duplicates
by minute and shows only three hours. The current number expires independently
after six minutes; historical points stay at their actual minutes. Intervals of
six minutes or longer remain gaps. No storage query runs on the one-second clock.

The glucose card is unchanged. The graph takes the remaining width and keeps its
latest point, target range, y axis and time labels inside both split/full viewports.
RHMI sidebar events resize both the component and JPEG. Explicit Full screen and
Split screen choices remain in Settings → BMW iDrive → Car display. Automatic
starts sidebar-safe until the head unit reports its mode; it no longer depends on
AAIdrive map preferences. Updates do not reopen the page or take focus.

Validation:

- Debug build passed; 118 unit tests passed, no failures/errors/skips.
- Debug lint: 0 errors, 87 warnings.
- Native graphics tests verify matching glucose value size, chart endpoint bounds,
  current-value expiration and absence of lines across missing intervals.
- Data tests cover three-hour bounds, ordering, minute merging, future/invalid
  readings, stable second ticks and uncapped history preservation.
- Preview matrix: Romanian/English/French; mg/dL and mmol/L; 743×480 and 838×540
  split, 1211×480 and 1345×540 full; empty and stale states. Romanian split/full,
  empty and French mmol/L previews visually reviewed.
- Delivered APK signature verifies; DEX inspection confirms the AAIdrive map client,
  service reference and destination view are absent from the APK.

Install only `app/build/outputs/apk/idrive-chart/LibreCRDroid-idrive-chart-restored.apk`
as an update, then reconnect Connected Apps. No phone installation or vehicle test
was performed. The two included restored-chart JPGs use synthetic demonstration
readings. Previous map source files were backed up under `app/build/backups/` before
removal; companion integration sources and historical reports remain for reference.

SHA-256: `1fb556e4c1e706585a0b383e3aa1a03772693306290518d1df13946a468aa9b7`
