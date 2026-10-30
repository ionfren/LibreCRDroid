# Google Maps fullscreen repair — 20 October 2026

The user reported that the map stayed as narrow as the glucose card after enabling
fullscreen. Read-only phone inspection showed AAIdrive Map_Widescreen=true and no
saved LibreCRDroid display override (Automatic). Automatic previously waited for
a car SPLITSCREEN event and otherwise always retained the narrow fallback.

The companion AAIdrive service now includes its Widescreen preference in every
map response. LibreCRDroid Automatic follows it when no native mode event is
available. Native sidebar events and explicit LibreCRDroid Full screen/Split
screen overrides retain their existing priority. The metadata is optional in
protocol version 1, so older clients/servers remain compatible.

The renderer is unchanged: the glucose card keeps its size. On the tested
1280×480 display, the map request grows from 314×381 to 782×381 pixels while the
RHMI image expands from 743 to 1211 pixels wide. The map setting is retained
while a resized projection loads, so loading/error states cannot restore the
narrow fallback. Settings-only changes are processed even when the map frame's
sequence is unchanged. The page is not reopened and focus is not taken again.

Validation:

- LibreCRDroid debug build passed; 123 unit tests passed.
- AAIdrive gmapNonalyticsFullDebug build passed; 7 selected tests passed.
- Debug lint: 0 errors, 87 warnings.
- New regressions cover Automatic without native mode events, growth without a
  new glucose reading, retained mode during reloading and unchanged frame sequences.
- The existing renderer pixel test verifies equal glucose number size in split
  and full viewports, all map quadrants and independent glucose expiry.
- Both delivered APK signatures verify. The regenerated eight-file companion
  patch applies to compatible clean sources and matches the built checkout.

Install both APK updates from `app/build/outputs/apk/idrive-google-maps-fullscreen/`,
keep AAIdrive Widescreen enabled and LibreCRDroid display mode on Automatic, then
reconnect Connected Apps. The phone's explicit Full screen choice remains available.
No phone installation or vehicle display test was performed for this repair.

SHA-256:

```text
4c158f0de338fc5d13ae67ff8e73049185306b8089baa6a62820d4c46660bd6d  LibreCRDroid-idrive-fullscreen-fix.apk
22b8c8837789b6ff2e7ae2fcc0099525c97aebcbad3b57df83ef701ef5b9954b  AAIdrive-LibreCR-fullscreen-fix.apk
```
