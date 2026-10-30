# LibreCR iDrive destination search and routes — 20 October 2026

The LibreCR map now has a selectable **Search destination** control using the
signed RHMI layout's native list. It supports the iDrive controller and preserves
the physical shortcut/bookmark action. Native BMW input handles character entry,
delete and full deletion. After two characters, a 300 ms debounce requests up to
five Google Places suggestions, with a full-results page. Choosing a suggestion
resolves its location, starts a driving route and returns to the glucose/map page.

For a pending, active or failed route, **Navigation · options** offers a new search,
recalculation, stop and return to map. The map follows vehicle CDS position. A
localized banner shows route calculation/errors, destination and total route
distance/duration. Totals describe the calculated route, not live remaining travel.
Spoken guidance, turn instructions and automatic off-route recalculation are not
included. Missing GPS, Places/Directions failures and command timeouts are visible.

Glucose rendering, freshness and full/split behavior are retained. Native focus
transitions maintain the map binding regardless of focus-event ordering. Leaving
the participating pages releases it after 300 ms. Navigation responses cannot
reopen a hidden page. Old or stopped Directions callbacks cannot restore a route.
The car-disconnect path cancels its outstanding route before clearing the overlay.

The version-1 bridge advertises optional command capability and route metadata.
Both endpoints retain UID/version/request-ID validation. Commands use only map
queries/place IDs, never glucose or SDK keys. Navigation resolves only a place
from the current suggestions. Searches and place lookups have bounded timeouts.

Validation:

- LibreCRDroid: **132 unit tests passed**, zero failures/errors/skips, using Java 21
  for Robolectric's Android 36 coverage. Includes native search/selection, deletion,
  navigation menu, stale/spoofed replies, debounce, command timeout, focus ordering,
  bookmark behavior, unchanged-frame metadata, glucose freshness and viewport tests.
- AAIdrive: **15 selected map tests passed**, zero failures/errors/skips. Includes
  Places error reporting, UID access, route sharing, existing MapApp integration,
  route cancellation/stale responses, route totals, absent GPS and empty routes.
- LibreCR debug and release builds passed. Debug lint: **0 errors, 87 warnings**.
- AAIdrive gmapNonalyticsFullDebug build passed.
- Romanian split active-route and French full error-state renderer previews were
  inspected. Generated previews use a four-color test pattern, not live map tiles.
- The complete 12-file companion patch applies to clean compatible AAIdrive sources
  and exactly reproduces the built companion files.
- Both delivered APK signatures verify and match their previously delivered
  fullscreen-fix APKs. The delivered LibreCR APK is the debug-signed build to retain
  update compatibility; the separately compiled release build uses a different key.

Delivered together in `app/build/outputs/apk/idrive-navigation/` in LibreCRDroid
and `build/outputs/librecr-navigation/` in the AAIdrive workspace:

- `LibreCRDroid-idrive-navigation.apk`
- `AAIdrive-LibreCR-navigation.apk`
- `SHA256SUMS`

Install both as updates, leave Maps enabled in AAIdrive and reconnect Connected
Apps. Open LibreCR · Glucose, then select Search destination with the controller.
No APK was installed and no vehicle test was performed here. Real Places/Directions
responses depend on the configured Google key and enabled APIs; head-unit display,
GPS, attribution visibility and input require verification in the vehicle.
