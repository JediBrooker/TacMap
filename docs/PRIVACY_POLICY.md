# TacMap — Privacy Policy

*Last updated: 28 August 2026*

*Applies to the iOS and Android editions of TacMap*

This policy explains how the TacMap mobile application (the “App”), published
by Christian Brooker, handles information. TacMap has no developer-operated
user accounts, advertising, analytics, or remote crash-reporting service. The
App can nevertheless make the specific network requests described below. Those
requests may expose an IP address, a query, a viewed map area, store metadata,
or encrypted Unit Sync traffic to the relevant service provider.

## 1. Information the developer does not collect

TacMap does not:

- require a TacMap account, email address, or profile;
- include advertising, behavioural analytics, advertising identifiers, or a
  developer-operated telemetry or crash-upload service;
- sell or rent personal information;
- read contacts, photos, microphone, camera, calendar, or Bluetooth;
- read compass/orientation sensors except while foreground Heading Up mode is
  enabled. Those samples rotate the map, stay in memory, and are not stored or
  transmitted; or
- upload mission content to a TacMap account or developer database.

Apple and Google may provide opt-in operating-system crash diagnostics to app
developers under their own settings and policies. TacMap also writes a short
crash report locally; it is transmitted only if you explicitly export it.

## 2. Information stored on your device

| Data | Purpose and storage |
|---|---|
| **Live GPS fix** | Used for the location marker, MGRS/coordinate readout, camera centring, and optional live presence. A fix is held in memory unless you explicitly start track recording. |
| **Device compass heading** | Used only while foreground Heading Up mode is enabled to rotate the map. Samples remain in memory and are not stored or transmitted. Unit Sync presence heading is instead the course reported with the live GPS fix. |
| **Recorded track** | An explicitly started recording appends precise coordinates, timestamps, and available altitude data to an encrypted, app-private track log. Speed is used for optional live Unit Sync presence but is not stored in the track log. A stopped recording remains available until you discard it; exporting creates a separate GPX copy. |
| **Waypoints, symbols, drawings, layers, notes, and styles** | Stored in encrypted, app-private mission files so they survive relaunch. |
| **TacMap Chat history and replay state** | Text and reports that you send or receive, their room/selected-unit scope, local routing status, and anti-replay state are kept in a bounded encrypted, app-private file for that sync room. TacMap Chat v1 has no delivered/read receipts. |
| **Calibration and imported-map selection metadata** | Stored in encrypted, app-private files. This can reveal the identity and geographic coverage of an imported map. |
| **Imported PDF/GeoPDF and MBTiles maps** | Copied into app-private storage as their original file bytes. They receive the operating system’s file protection but are not encrypted by TacMap’s mission-data key. iOS Files/Finder file sharing is disabled. Android uses the system document picker and requests no broad storage permission. |
| **App preferences** | OPSEC gates, layer visibility, camera state, and other interface choices are kept in app-private preferences. |
| **Purchase entitlement** | A locally verified permanent-unlock marker is kept in the iOS Keychain or Android app-private preferences so a known owner can continue offline. |

TacMap mission files use AES-256-GCM at rest. The data key is protected by the
iOS Keychain or Android Keystore. Imported map bytes and local crash reports
are the documented exceptions. Device compromise, an unlocked device, exports,
and optional network services remain separate risks; see the published threat
model for those limits.

Deleting the App normally removes its app-private files under the platform’s
rules. Exported copies, files you shared to another app, store transaction
records, and data retained by a service provider are controlled separately.

## 3. Network requests

### 3.1 Online maps — disabled by default and independently switchable

While **Online basemap tiles** is enabled, the App’s own raster renderer requests
the tiles you view from Esri (satellite, topographic, and OpenStreetMap-style
tiles) or OpenTopoMap. The provider receives your IP address and tile
coordinates/zoom, which reveal the area and movement of the map view.

Both editions use the same custom map renderer. TacMap does not use Apple Maps
or Google Maps to render the basemap. This setting is off for a fresh install;
an update preserves an existing user's stored choice. Leave it off and use an imported PDF/GeoPDF or MBTiles pack if the area of
interest must not leave the device.

### 3.2 Online lookups — disabled by default and independently switchable

While **Online lookups** is enabled:

- Open-Meteo receives the map-centre coordinate for elevation and weather
  (coarsened to roughly 110 m), or a 24 × 24 coordinate grid covering the
  visible map area for the terrain heat-map (coarsened to roughly 11 m);
- iOS can send a typed place-name/address query and camera region to Apple’s
  place-search service through `MKLocalSearch`; and
- Android can send a typed place-name/address query and location bias through
  the device’s platform `Geocoder` provider (commonly Google or the device
  vendor on Google-enabled devices).

This setting is off for a fresh install, and an update preserves an existing
user's stored choice. You can enable it at any time in Privacy & OPSEC settings;
leave it off for an offline posture.

MGRS, partial-grid, latitude/longitude, waypoint, drawing, type, note, and layer
searches run on-device. Coordinate-shaped input — including malformed or
out-of-range coordinate text — is not forwarded to either place provider.

### 3.3 Unit Sync — optional

Unit Sync is off until you enter a join code. Joining opens a WebSocket to the
configured relay (the default service is hosted on Cloudflare, and you may
self-host it).

Mission payloads are sealed on-device with AES-256-GCM. Synced map objects,
presence, and **Entire room** Chat use keys derived from the join code, so every
join-code holder can decrypt content shared to the room. A **Selected unit**
message instead uses a pairwise key derived from the two selected live endpoint
sessions; the relay, other room members, and join-code holders outside that pair
cannot decrypt it. Protocol envelope and control fields are not
mission-payload ciphertext.

The relay and its hosting/network providers can still observe or process:

- your IP address, the routing room identifier, and the authorization token sent
  during the WebSocket handshake (the relay retains a hash of that token);
- clear outer object/version/kind, request, acknowledgement, and deletion fields;
- room co-membership, connection and session identifiers, public actor keys,
  and signed actor/session announcements;
- Chat sender/session/key ID, whether the scope is Entire room or Selected unit,
  and, for Selected unit, the exact recipient actor/session/key ID;
- connection times, message timing, frequency, and sizes; and
- encrypted mission objects and tombstones held for synchronisation.

Encrypted mission objects and actor records can remain at the relay until the
room has been idle for seven days. Live location presence is forwarded to
connected peers and held only as current in-memory session state. TacMap Chat
key adverts and ciphertext are forwarded only to currently connected,
Chat-capable sessions and are not written to the relay's room storage. There is
no offline Chat mailbox. A relay acknowledgement shown as **Routed** or **Sent
to room** does not mean that a recipient decrypted, displayed, or read a
message; TacMap Chat v1 has no endpoint delivery/read receipts. The UI’s
“online” member state is relay-reported: signed session messages authenticate
their origin, but an untrusted relay can delay, replay, or suppress liveness.
Treat it as an indication, not proof that a person is presently connected.

Leaving the room closes the connection and stops further Unit Sync traffic.
The bounded local Chat history remains sealed in app-private storage for that
room unless the App's data is deleted; leaving does not create a relay copy.
Turning off **Share my location** stops live-position broadcasts without
leaving the room. On iOS and Android, **Background Unit Sync location** is a
separate OPSEC switch and is off by default. When both location-sharing switches
are on in a joined v3 room, TacMap can keep its authenticated socket active after
the screen locks and send an encrypted position at the selected best-effort
cadence (one, five, fifteen, thirty, or sixty minutes). iOS continues the
foreground-started Core Location session with the system background-location
indicator visible. Android runs a location foreground service with an ongoing
notification and does not request `ACCESS_BACKGROUND_LOCATION`. Both platforms
ignore inbound mission traffic while locked or backgrounded and reconnect for a
verified snapshot after you return.
If either control is off when you try to join a v3 room, TacMap pauses before
joining and asks whether to enable both; cancelling leaves the room unjoined.
The signed last-known lifetime is bounded to the selected cadence plus delivery
grace (never more than 65 minutes); older/foreground-only senders remain on the
45-second window. Turning either switch off prevents screen-off broadcasts and
rotates or closes the authenticated session so an extended marker is withdrawn.

### 3.4 App Store and Google Play

TacMap uses StoreKit or Google Play Billing for its trial and one-time unlock.
The App can reconcile ownership when it launches and on throttled foreground
transitions; opening a paywall also loads product and localised-price details.
These contacts are independent of the online-map and online-lookup gates.

Apple or Google may process the signed-in store account, app/product identifier,
transaction or purchase-token information, device/service data, IP address,
timing, and diagnostics under their policies. TacMap does not attach map
coordinates, tracks, mission objects, imported maps, callsigns, Unit Sync data,
or mission keys to store requests. There is no TacMap purchase-validation
server.

## 4. Permissions

Heading Up reads the device compass only while that foreground mode is active;
it does not require a separate runtime permission. When a usable location fix
is available, the App may use it locally to correct magnetic heading to true
north. Without one, the compass remains explicitly marked as magnetic north.

### iOS

- **Location While Using the App** supports the live position, MGRS readout,
  optional live presence, and a user-started GPX track. TacMap presents the
  permission prompt on the first map presentation so live location can work
  immediately after consent. It declares the background-location mode and
  enables it only while an explicit track recording is active or while the
  separate Background Unit Sync OPSEC switch is on and a v3 room is actively
  sharing location. The system background-location indicator remains visible.
  The App does not request “Always” authorisation, cannot relaunch this session
  after termination, and cannot guarantee an exact wall-clock interval.
- **Face ID** is requested only if you enable a Face ID-protected app or
  mission-data lock.
- The system document picker and share sheet appear only when you choose an
  import or export action.

### Android

- **Precise/approximate location** supports the same live-map and Unit Sync
  features and is requested on the first map presentation. Precise foreground
  location is required to record a GPS track.
- **Foreground service (location)** keeps either a recording that you started,
  or explicitly opted-in v3 Background Unit Sync presence, running while the
  App is backgrounded or the screen is locked. The service displays an ongoing
  notification for the active feature.
- **Notifications** is requested on Android 13 and later for that foreground
  service notification.
- **Internet/network state** supports the optional services and store contacts
  described above. **Billing** supports the one-time unlock.

Android does not request broad file/media storage access or the
`ACCESS_BACKGROUND_LOCATION` permission. You can revoke granted permissions in
system Settings; doing so disables the affected feature.

## 5. Imports and exports you initiate

PDF/GeoPDF and MBTiles maps selected through the system document picker are
copied into TacMap's app-private storage so the imported map remains available.
GeoJSON, KML, and KMZ files are instead read under the picker's scoped access,
parsed into mission objects, and then released; TacMap persists the resulting
mission objects in its encrypted stores rather than retaining the source file.

**Export All Mission Objects** creates a GeoJSON file containing waypoints,
symbols, drawings, and layer information. A recorded route is exported
separately as GPX. GPX contains a timestamp for each recorded point; GeoJSON
contains mission-object and layer creation times rather than per-vertex timing.
These files can also contain precise coordinates, notes, and elevations. TacMap
writes them locally and presents the system share/save interface; the App does
not choose or upload to a destination for you. After you select another app,
service, or person, their privacy and retention rules apply.

## 6. Children

TacMap is not directed to children under 13. It does not operate a user-account
or advertising service. If a child uses an optional provider or platform store,
that provider’s policies and the device account settings apply.

## 7. Your choices and rights

You can inspect, edit, export, or delete mission objects in the App; discard a
saved track; remove an imported map; leave Unit Sync; turn off both online
gates; revoke permissions; or delete the App. TacMap does not maintain a
developer database containing your mission content against which an access or
deletion request can be run. Service providers may hold the limited metadata
described above; contact the relevant provider for requests concerning their
records.

Privacy and consumer rights vary by jurisdiction. Contact us if you believe
this policy is inaccurate or if you need help identifying the relevant data
controller or service provider.

## 8. Changes to this policy

Material changes will be published at the same public policy URL with a revised
date. Release notes will call out changes that materially alter data handling.

## 9. Contact and source

- **Email:** christianbrooker@gmail.com
- **Issues:** <https://github.com/JediBrooker/TacMap/issues>
- **Source:** <https://github.com/JediBrooker/TacMap>
- **Threat model:** <https://tacmap.app/threat-model>

TacMap is open source under the MIT License. The source is available for audit,
but the exact behaviour of Apple, Google, Esri, OpenTopoMap, Open-Meteo,
Cloudflare, network operators, and apps you share to is governed by those
parties’ systems and policies.
