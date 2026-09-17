# TacMap — Cross-platform architecture

This is the current cross-platform brief for the iOS and Android apps. Shared
contracts and fixtures are preferred over platform-specific interpretations so
the native interfaces can differ without changing behaviour or data.

## 1. WGS84 is the model boundary

Waypoints, symbols, drawing vertices, track points, calibration control points,
and Unit Sync objects use WGS84 latitude/longitude as their geographic ground
truth. MGRS and UTM are presentation and input formats computed at the edge.
Changing basemaps therefore never reprojects mission objects.

```text
                  WGS84 mission model
             (symbols, drawings, tracks)
                          |
          +---------------+----------------+
          |               |                |
    online raster     PDF / GeoPDF       MBTiles
   (Esri / OSM)      parsed/calibrated    offline
          |               |                |
          +---------------+----------------+
                          |
              custom map renderer + HUD
```

The native `MapSource` abstractions expose four active source families on both
platforms:

- an independently switchable online raster source, disabled on a fresh install:
  Esri satellite/topographic/OpenStreetMap-style
  tiles or OpenTopoMap;
- a GeoPDF whose geospatial metadata is parsed on-device;
- a PDF calibrated with three or more fiduciaries; and
- an imported MBTiles raster pyramid.

Both apps own their projection, camera, gestures, tile requests, and overlay
rendering. iOS uses `TileMapView`/`TileMapContainer`; Android uses the
Compose/Canvas `TileMapView` hosted by `CustomMapScreen`. Neither uses Apple
Maps or Google Maps to render a basemap. iOS still uses Apple’s
`MKLocalSearch` only for optional place-name lookup; the `MapKit` coordinate
structs that remain in some APIs are data types, not a basemap view.

## 2. Camera and browse mode

The HUD reads either the user’s GPS fix or the map centre. A pan, pinch, or
rotation moves the app into browse mode; the centre-on-location action returns
to the live fix. Each custom renderer publishes the same camera contract:

- screen point ↔ WGS84 coordinate;
- visible region and metres per point;
- centre and heading; and
- projected positions for symbols, drawings, the grid, presence, and edit
  handles.

This contract keeps selection, move/edit operations, search results, and
measurement independent of the underlying raster source.

## 3. PDF and fiduciary calibration

GeoPDF ingest recognises OGC `LGIDict` and Adobe geospatial `/VP/Measure`
metadata. A PDF without usable georeferencing can be calibrated by pairing at
least three page points with known ground coordinates. The fitter solves:

```text
lon = a*x + b*y + c
lat = d*x + e*y + f
```

For more than three fiduciaries, the implementation solves the least-squares
normal equations independently for longitude and latitude. Back-projected
ground error is reported as an RMS residual in metres. Datum choices are
normalised to WGS84 before mission data is stored.

Calibrated PDFs can be tiled into MBTiles on-device. Imported PDF and MBTiles
bytes are copied into app-private storage. Their calibration and active/retained
selection records are persisted separately, so switching to an online source
does not discard the last imported map.

## 4. Durable mission state

Waypoints, drawings/layers, calibration metadata, sync/chat replay state,
bounded TacMap Chat history and drafts, and the track log are committed to
versioned, authenticated AES-256-GCM envelopes before the corresponding UI
mutation is published. iOS protects the data key through the Keychain; Android
wraps it with Android Keystore. Optional authentication-bound mode tightens key
release at the cost documented in
`security/ADR-002-key-lifetime-and-rotation.md`.

Imported map bytes and the local crash report are deliberately not inside the
mission envelope. They remain app-private and receive the platform’s file
protection, but can reveal an area of interest if the filesystem is compromised.

## 5. Import and export

GeoJSON (RFC 7946) is the canonical mission-object interchange format, with
coordinates ordered `[longitude, latitude]`. Both implementations preserve
stable object identity, layers, simplestyle-compatible drawing properties, and
TacMap symbol metadata. KML/KMZ and GeoJSON imports resolve identity before one
durable publication.

**Export All Mission Objects** exports waypoints, symbols, drawings, and layers
as GeoJSON. Route recording is an independent store and is exported separately
as GPX. Combining mission objects, tracks, and map assets requires a future
versioned mission-package format rather than overloading GeoJSON.

Imports use the platform document picker. A selected PDF or MBTiles file is
copied to a managed private location before activation, including after process
recreation; no platform-wide storage permission is required.

## 6. Search contract

Search runs in two ordered stages:

1. a pure local engine handles full/partial MGRS, decimal coordinates, and
   deterministic mission search across name, notes, type, and layer; then
2. when the user has enabled online lookups, Apple place search (iOS) or the
   platform Geocoder (Android) appends place results.

Coordinate-shaped input, including invalid/out-of-range text, is consumed by
the local stage and cannot fall through to a network provider. Selecting a
mission result centres the camera and highlights exactly one waypoint or
drawing.

Shared fixtures in `testdata/` pin search order, import identity, drawing-style
units, and symbol-edit normalisation on both platforms.

## 7. Network and Unit Sync boundaries

Online maps and online lookups have separate OPSEC gates and are off for a fresh
install. Persisted existing-user choices take precedence over those defaults.
The map gate controls construction of Esri/OpenTopoMap tile sources;
the lookup gate controls Open-Meteo and place-provider requests. StoreKit/Play
Billing ownership reconciliation is a separate lifecycle path and is not
disabled by those gates.

Unit Sync payloads are encrypted end-to-end with a room-derived key. The relay
routes and temporarily/durably stores ciphertext, but receives the admission
token and clear routing, object/version/kind, actor/session, acknowledgement,
and control fields as well as source IPs, co-membership, and traffic timing/size.
Signed actor and session frames authenticate their origin; they do not make
relay-reported liveness authoritative. Protocol details and rollback limits are in
`security/ADR-001-sync-protocol-v3.md` and `THREAT_MODEL.md`.

Both clients serialize and bound delivered WebSocket messages before protocol
parsing. iOS uses the platform message-size ceiling and requests the next
message only after the current main-actor handler completes. Apple's public
`URLSessionWebSocketTask` API handles RFC 6455 Ping/Pong and other control
frames internally, so TacMap cannot apply its application receive budget to
those individual frames; this remains a platform-transport availability
limitation. Android's bounded RFC 6455 draft checks declared payload length,
fragmented byte/count aggregates, and every raw data/control frame before
retaining data, then backpressures its reader until the main-thread consumer
completes. Neither Unit Sync transport accepts redirects or offers WebSocket
compression.

TacMap Chat is a strict, backwards-compatible v3 extension with two explicit
recipient scopes. After `hello-ack`, each unlocked socket advertises one signed,
memory-only X25519 public key; Chat remains unavailable until the relay returns
the exact `chat-key-ack`. **Entire room** derives a domain-separated chat subkey
from the room key and is therefore readable by every join-code holder.
**Selected unit** derives an endpoint-only key from the two advertised session
keys and binds the exact recipient actor/session/key tuple into the KDF, AEAD
associated data, and sender signature. The relay verifies the clear outer
signature and routes only to live Chat-capable sockets; it never stores Chat
keys or ciphertext and never broadens an unavailable direct recipient to the
room. A relay `chat-ack` means only **Routed** (or **Sent to room**), not delivered
or read; TacMap Chat v1 has no endpoint receipts. The byte contract and shared
vectors are in `security/ADR-004-tacmap-chat-v1.md` and
`testdata/tacmap_chat_v1.json`.

On both platforms, screen-off location presence is a separate, default-off OPSEC lease.
The platform location service keeps only a foreground-started v3 session alive and outbound
presence is rate-limited to the selected one/five/fifteen/thirty/sixty-minute cadence. Each
update carries a backwards-compatible, sender-signed retention advertisement;
receivers extend a marker beyond 45 seconds only for that exact authenticated
session and never beyond 65 minutes. Revoking extended eligibility rotates the
session so the previous marker is withdrawn instead of waiting for expiry.
Inbound mission frames are discarded behind the mission-key lock; foreground
return replaces the socket and reconciles from a verified snapshot. The track
recorder and Unit Sync hold independent background-location leases so stopping
one cannot disable the other. Android uses an ongoing location foreground
service for the opted-in presence-only session; iOS uses the system background-
location indicator. Neither path enables itself during an update.

## 8. Current extension points

- Additional PDF projections and datum transforms can be added behind the
  calibration parser without changing mission storage.
- A saved basemap library can build on the retained imported-map descriptor and
  content-hash calibration records.
- An encrypted mission package needs a versioned manifest, streaming limits,
  authenticated contents, conflict rules, and explicit track/map inclusion.
- A LAN/mesh Unit Sync transport could replace the relay while retaining the
  same object and replay contracts.
