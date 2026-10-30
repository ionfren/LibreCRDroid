# iDrive Google Maps integration — 20 October 2026

The iDrive glucose chart is replaced by a live Google Maps card. The glucose card
keeps its existing Material styling, size, value, trend, unit and reading age/time.
Full-screen and split-screen viewport handling are retained. The map uses the
remaining space and draws the complete received rectangular frame. Its corners
are rounded, with a small SDK content inset to protect Google attribution. The
history SQL query and chart-specific presentation code are removed.

The map renderer lives in the companion gmap AAIdrive build. It uses the existing
SDK key, map style/traffic settings, car CDS location and already-calculated AAIdrive
route. It makes no additional Directions requests. Destination controls remain in
AAIdrive. No glucose or SDK credentials pass through the map-only Messenger protocol.

The exported companion service accepts requests only from LibreCRDroid's UID.
The client checks AAIdrive's UID, protocol version, request ID, frame size, JPEG
header and decoded dimensions. Resizing invalidates older responses; hiding the
page unbinds the service. The service also releases its private virtual display,
image reader and location subscription after idle timeout. Missing responses or
prolonged tile loading replace the map with a localized status. Glucose expiry
continues independently on the existing one-second freshness clock.

Validation completed locally:

- LibreCRDroid: 120 tests passed, including 32 iDrive tests; zero failures/errors.
- AAIdrive: 5 selected tests passed (bridge access, route copying/clearing and the
  existing MapApp tests); zero failures/errors.
- LibreCRDroid debug and release builds passed; debug lint: 0 errors, 87 warnings.
- Companion gmapNonalyticsFullDebug build passed; both delivered APK signatures verify.
- Renderer native-graphics tests check all four quadrants of a synthetic frame,
  stable glucose card size and glucose expiry during active map delivery.
- Client tests exercise visibility, resizing, spoofed/stale responses, invalid
  frames, stalled service and prolonged loading; RHMI tests cover map viewport
  updates and hiding/showing the page.
- Loading/error views generated for Romanian, English and French, at split
  743×480 and 838×540, and full 1211×480. Romanian loading/error previews reviewed.
- The complete companion patch applies against compatible clean AAIdrive sources
  and reproduces all eight modified/added files in the built checkout.

Delivered APKs are in `app/build/outputs/apk/idrive-google-maps/`:

- `LibreCRDroid-0.1.6-idrive-google-maps.apk`
- `AAIdrive-GoogleMaps-LibreCR-bridge.apk`
- `SHA256SUMS`

Install both as updates, keep Maps enabled in AAIdrive, then reconnect Connected
Apps. Open LibreCR · Glucose. The phone's BMW iDrive settings also show map status.

The included `idrive-google-maps-loading.jpg` is a loading-state preview with demo
glucose data, not a screenshot of a running Google map. No installation or vehicle
test was performed for this change. Real tile delivery, SDK-key restrictions,
vehicle GPS, the displayed route and head-unit behavior require a vehicle check.

SHA-256:

```text
6ab64a32b90f0f56dc6c05bb6569d47ac596bd580323b286f0a5ba2155f16abd  LibreCRDroid-0.1.6-idrive-google-maps.apk
9a2fb4427f16e208e52e99c1be72065ab06d8d1f36cbcbc666e17b6c88967eeb  AAIdrive-GoogleMaps-LibreCR-bridge.apk
```
