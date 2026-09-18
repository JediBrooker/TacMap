# TacMap Threat Model

**Status:** maintained release document · **Audience:** users, unit security staff, code auditors
**Scope:** what information TacMap can expose, to whom, and where its guarantees stop.

This document is deliberately plain. If you use TacMap to hold or share unit
information, you should be able to read this in five minutes and know exactly
what leaves your device, what a hostile party could learn, and what the app
does **not** protect you from.

Nothing here is marketing. Where a guarantee has a limit, the limit is stated.

---

## 1. What TacMap is

An offline-first tactical mapping tool for iOS and Android: MGRS grid, drawing
and measurement, military symbology, waypoints and track logs, import/export,
and an **optional** unit sync feature that shares live presence, map objects,
and live TacMap Chat messages between devices that share a join code.

Core navigation, imported-map, drawing, measurement, and mission-object work can
run without a network. Unit Sync requires a path to its relay. Online tiles and
lookups require their separately enabled providers, and platform-store ownership
reconciliation can occur independently of those OPSEC gates.

---

## 2. Critical information (what an adversary wants)

If TacMap is holding real data, the sensitive items are:

- **Positions** — your live position, and peers' positions, headings, speeds.
- **Unit identity** — callsigns, affiliation, echelon, function, HQ status.
- **Scheme of manoeuvre** — waypoints, routes, tracks, drawn control measures.
- **Messages and reports** — operational text, reply context, and who sent it
  to whom.
- **Area of interest** — the ground you are looking at, even without any markup.
- **Association and tempo** — who is working with whom, and when activity spikes.

The last two matter even when the first three are encrypted. Keep reading.

---

## 3. Trust boundaries

TacMap treats the following as **untrusted** once data crosses into them:

| Boundary | Trusted? | Why it matters |
|---|---|---|
| Imported symbol packs | **Untrusted** | User-selected bounded JSON and passive PNG artwork; labels and depicted meaning are not authenticated. |
| Your device | Trusted (see §7 caveats) | Holds the at-rest key, and can decrypt mission data. |
| The sync relay | **Untrusted** | Routes encrypted traffic; can see metadata. |
| Basemap / lookup providers | **Untrusted** | See the coordinates you request. |
| The network path (ISP, Wi-Fi, carrier) | **Untrusted** | Sees who you talk to and when. |
| Other members of your sync room | Partly trusted by you | They hold the room key and see room-shared content. A Selected unit chat uses a separate pairwise session key, but its recipient can still copy the plaintext. |

The design goal is that **only your device and the intended sharing scope** see
mission payloads in cleartext. Room objects, presence, and Entire room chat are
readable by room-key holders. Selected unit chat is readable only by the two
selected live endpoint sessions. The relay sees ciphertext together with the
clear protocol metadata and control fields documented below. Tile/lookup
providers see the coordinates or queries required for an action you enable.

---

## 4. What the sync relay can and cannot see

This is the core statement. Read it before trusting sync.

**One-paragraph version.** When you join a sync room, your device turns the
human join code (e.g. `bravo-tonight`) into three separate values using a slow
password-stretch (PBKDF2-HMAC-SHA256, 210,000 iterations). One value is a
routing ID the relay uses to connect you to your room. A second is the
encryption key, which **never leaves your device**. A third is an authorization
token sent to the relay inside the TLS-protected WebSocket handshake; the relay
hashes and pins it for room admission. Every map object and every presence update
is sealed with AES-256-GCM using the encryption key before it is sent. The relay
forwards and may retain that ciphertext, but it also handles clear routing,
object/version/kind, request/acknowledgement, actor, session, chat scope and
recipient routing, and control fields.
It cannot reverse the routing ID straight back to the key. But the routing ID
and the key are both derived from the same join code, so the protection is only
ever as strong as that code:
a weak or human-memorable code can be guessed offline (see *Your join code is
the whole ballgame*, below). With a strong, generated code the relay can route
the traffic and inspect the metadata listed below, but—absent compromise of a
client or the code—it cannot decrypt correctly authenticated mission-payload
ciphertext.

**What the relay CAN see:**

- The **routing room ID** (a 256-bit value derived from your join code; not
  directly reversible, but it doubles as an offline *verifier* for guessed
  codes - see *Your join code is the whole ballgame*, below).
- The room **authorization token** during each connection handshake and the hash
  retained for later room admission checks. This token is distinct from the
  mission-payload encryption key but is also derived from the join code.
- The **IP addresses** of connected devices, and therefore approximate
  geographic origin.
- **Traffic metadata:** when devices connect, how often they send, message sizes
  and timing.
- That a set of devices **belong to the same room** (co-membership).
- Clear outer object IDs, versions, kinds, writer IDs, request IDs,
  acknowledgements, and delete/control message types around encrypted payloads.
- Public actor keys, actor/session identifiers, signatures, and the signed
  session announcements used by clients to display relay-reported membership.
- TacMap Chat's clear sender/session/key ID and whether a frame is **Entire
  room** or **Selected unit**. For a selected-unit frame it also sees the exact
  recipient actor/session/key ID. It sees ephemeral X25519 public keys and
  transport acknowledgements, but chat frames are live-only and are not written
  to relay storage.

**What the relay CANNOT see:**

- Positions, headings, speeds.
- Callsigns, affiliation, echelon, function, HQ status.
- Waypoints, routes, tracks, drawn control measures.
- Chat text, report contents, reply references, and in-message timestamps.
  Those fields remain inside the ciphertext. Chat v1 has no delivery/read
  receipts.
- The join code itself, although the routing ID lets an observer confirm offline
  guesses and a relay also receives the separately derived authorization token.
- The contents of any synced object. All of it is AES-256-GCM ciphertext.

**What payload authentication prevents.** Each sealed object binds its own
routing metadata (object ID, version, kind) into the encryption as associated
data. A relay that relabels an object under a different ID or moves it between
rooms produces an authentication failure and the client rejects it. An outside
party with only a leaked routing ID also cannot pass room admission without the
separate authorization token. The relay can still suppress, delay, replay, or
serve older genuinely authenticated state; those limits are explicit in §7.

TacMap Chat applies the same rule to recipient scope. The binary header bound
into both AES-GCM and the sender's Ed25519 signature contains the room, sender,
session, counter, message ID, key ID, scope, and—when selected—the exact
recipient actor/session/key. A relay cannot turn a selected-unit message into
an Entire room message or silently retarget it. Selected-unit encryption uses a
session X25519 secret that neither the relay nor other join-code holders have.

**The honest limit.** Content is protected. **Metadata is not.** A relay
operator, or anyone who compromises or coerces the relay or its host, can learn
that a group of IPs form a unit, roughly where they are, and when they are
active. Selected-unit routing also exposes who is messaging whom, even though
the words remain encrypted. That is enough to infer association and operational
tempo. See §7.

The member list is also not independent proof of liveness. A signed session
announcement authenticates the device that created it, but the relay decides
when to forward a join or leave and can replay, delay, suppress, or reorder
genuinely signed session state. The UI therefore describes members as
relay-reported sessions; confirm critical presence out-of-band.

**Your join code is the whole ballgame.** The routing ID, the encryption key,
and the write token are all derived from the join code through one fixed,
app-wide salt. There is no per-room salt on purpose: the relay has to route by a
value anyone with the code can compute. Two things follow. Because the salt is a
constant, an attacker can precompute a `code -> (routing ID, key, token)`
dictionary once and reuse it against every room, forever. And because the relay
sees every routing ID, that ID is a free offline **confirmation oracle**: guess
codes, derive their routing IDs, match them against observed rooms, and any hit
hands over the encryption key (read) and the write token (write). PBKDF2 at
210,000 iterations makes each guess cost real work, but a short or memorable
code - a couple of dictionary words like `bravo-tonight` - still falls to a GPU
rig in minutes. **So sync is only private if your join code has real entropy.**
The app generates a strong ~78-bit code for you and refuses codes under 14
characters; use the generator and pass the code out-of-band. A code you invent
yourself, especially a memorable one, is guessable and is not covered by the
guarantees above. (A memory-hard KDF - Argon2id/scrypt - would raise the
per-guess cost further and is a planned hardening; it does not remove the need
for an entropic code.)

The relay is auditable: its payloads are sealed, while its source shows the clear
admission token, envelope metadata, actor/session records, and control traffic it
also handles. You do not have to trust ours. **You can self-host it** (see §8).

Crypto reference for auditors: `SyncCrypto.kt` / `SyncCrypto.swift`, the TacMap
Chat crypto implementations, ADR-004, and their test suites. Android and iOS
produce byte-identical sealed format (`iv(12) || ciphertext || tag(16)`).

---

## 5. Network egress table

Every outbound connection TacMap can make, what triggers it, what the far end
learns, and its default state. The online-map and lookup gates start off for a
fresh install and can be enabled independently. An update preserves choices
that an existing user already stored.

| Endpoint | Purpose | Triggered by | What the provider learns | Default | Mitigation |
|---|---|---|---|---|---|
| `ibasemaps-api.arcgis.com` (Esri World Imagery) | Satellite basemap tiles (the initial style after opt-in) | Viewing the map with online basemaps enabled | Your IP + the tile coordinates/zoom you view = your area of interest, over time | **Off** until online basemaps are enabled | Leave the gate off; use offline packs, see §6 |
| `static-map-tiles-api.arcgis.com` (Esri) | Topographic + OSM-street basemap tiles (licensed) | Selecting those styles with online basemaps enabled | Your IP + requested tile coordinates = your AO | **Off** until online basemaps are enabled | Leave the gate off; use offline packs |
| `a.tile.opentopomap.org`, `b.tile.opentopomap.org`, `c.tile.opentopomap.org` | OpenTopoMap community topo tiles (the one keyless style) | Selecting the OSM-Topo style with online basemaps enabled | Your IP + requested tile coordinates = your AO | **Off** until online basemaps are enabled | Leave the gate off; use offline packs |
| `api.open-meteo.com/v1/forecast` | Weather lookup | Opening the weather dialog after enabling online lookups | Your IP + the map-centre coordinate, coarsened to roughly 110 m | **Off** until online lookups are enabled | Leave online lookups off |
| `api.open-meteo.com/v1/elevation` | Elevation + terrain heatmap | Elevation/terrain features after enabling online lookups | Your IP + the map-centre coordinate coarsened to roughly 110 m for elevation, or a 24 × 24 coordinate grid coarsened to roughly 11 m covering the visible area for terrain | **Off** until online lookups are enabled | Leave online lookups off |
| Place-name search — iOS `MKLocalSearch` (Apple), Android platform `Geocoder` | Turning a typed place name into a coordinate | Typing 2+ non-coordinate characters in Search after online lookups are enabled | Your IP + the search string; iOS also supplies the camera region and Android can supply a location bias. These can reveal your AO (for example, searching a FOB/village name) | **Off** until online lookups are enabled; coordinate-shaped text stays local | Leave online lookups off; navigate by MGRS/grid instead |
| Sync relay (default: `tacmap-sync.<...>.workers.dev`) | Encrypted unit sync and live TacMap Chat transport | Joining a sync room | Encrypted mission/chat payloads; routing ID; admission token during the handshake; clear envelope, actor/session/key IDs, chat scope and selected recipient, request/acknowledgement and control fields; your IP and traffic timing/size (see §4) | Off until you join a room | Self-host the relay; see §8 |
| Apple App Store / Google Play | Ownership reconciliation, product details, purchase, restore, redemption | App launch and throttled foreground refresh; product/price loading while a paywall is visible; user purchase/restore/redeem actions | Standard store account, app/product, transaction/token, device, IP, timing, and diagnostic metadata; no map, location, or Unit Sync payload | Lifecycle reconciliation can occur even when OPSEC gates are off | Airplane mode or external network policy is the only complete suppression; see ADR-003 |

The three basemap rows apply to **both platforms** — iOS and Android draw the
same Esri/OSM raster tiles only after explicit opt-in. Neither app uses Apple Maps or
Google Maps for basemap rendering (§6). On iOS, the custom renderer does not
create an `MKMapView`, so Apple's `geod` daemon is not asked for basemap tiles
(measured zero, §6). On Android, the Google Maps SDK has been removed from the
app entirely (§6). Apple's `MKLocalSearch` remains an opt-in place-search path
and is listed separately above.

**Read this table as the whole story.** If an endpoint is not listed here, the
app does not contact it. *We* add no analytics SDK, remote crash telemetry, or
ad network; our own crash reports are written to local storage only and shared
by you manually. Neither platform instantiates a third-party basemap engine.
iOS links Apple frameworks for coordinate types and opt-in place search, not
basemap rendering. Play Billing and StoreKit may reconcile entitlement at
launch/foreground as well as during user purchase actions; see ADR-003.

---

## 6. The area-of-interest problem (basemaps and lookups)

Even with sync fully off, requesting online map tiles or online
weather/elevation tells the provider which ground you care about. Panning to a
grid square fetches tiles for that square from your IP. A provider, or anyone
with access to its logs, can reconstruct your area of interest and how it moves
over time. This is the same class of exposure as the 2018 fitness-app heatmap
incident.

TacMap's controls:

- **Online lookups (place search, weather, elevation, terrain) are off for a
  fresh install** and can be enabled independently.
- **Online basemaps are off for a fresh install**, behind their own OPSEC toggle
  (Settings → Privacy & OPSEC → Online basemap tiles). While off, no Esri and no
  OpenTopoMap tile is ever requested: the app does not construct the tile
  provider at all, so there is no URL to fetch.
- **A persistent red banner** sits across the top of the map whenever an online
  tile source is active, so you never discover it by accident.
- **Offline basemap packs** render with no tile requests of our own. This is the
  recommended posture for any real operation: pre-stage an MBTiles pack or a
  GeoPDF sheet before you deploy, then leave both online gates off in the field.

### The two platforms, both now closed

Both platforms draw the same Esri/OSM raster tiles only after explicit opt-in,
and render through app-owned tile engines rather than an Apple or Google
basemap view. The map is a Compose/Canvas renderer on Android and a custom UIKit
renderer on iOS. Each draws only the raster source you chose: an Esri/OSM online
style when the basemap gate is on, an offline pack/GeoPDF when you have imported
one, or nothing when both are off.

That is a change from earlier builds, where Android hosted the map in Google's
SDK. With online basemaps off, that SDK still fetched no basemap tiles — but the
SDK *itself* phoned home on launch (~280 KB cold / ~24 KB warm on a Pixel emulator)
for provisioning and telemetry, whatever the tile gate said. That check-in carried
your IP and the fact that a Google-Maps app had started (not the coordinates you
viewed — that's what the tile gate stops). **That is now gone.** The Google Maps
SDK has been removed from the app: the three Maps dependencies are dropped, the
`com.google.android.geo.API_KEY` manifest entry and `MapsInitializer` call are
deleted, and the built APK's dex contains **zero** `com.google.android.gms.maps`
or `com.google.maps.android` classes (verifiable with `dexdump` on any release
build). There is no longer any code path that performs the provisioning check-in,
because the code that performed it is not in the binary.

So on Android, as on iOS, with the basemap gate **off** the app makes no basemap
tile request; with it **on**, tiles go to Esri/OpenTopoMap (your choice, behind
the persistent red banner). On a device with Google Play Services, system GMS
processes can have ambient network traffic of their own. Separately, TacMap's
Play Billing integration can bind to the Play Store for the entitlement contacts
listed in §5; those requests do not include map or unit payloads. When no network
contact at all is acceptable, disable all relevant radios or use an externally
enforced network policy and verify the actual device configuration.

### The iOS side, now closed

For most of this app's life, iOS had a hole here we could not close from inside
MapKit. MapKit has no "no basemap" mode; the only way to suppress Apple's basemap
was to cover it with a `canReplaceMapContent` overlay, which stops MapKit
*drawing* the basemap but not *fetching* it. Apple's tiles are pulled by `geod`,
a system daemon outside our sandbox, which kept fetching tiles for the on-screen
region no matter what we drew on top. Measured on a freshly erased iPhone 17 Pro
simulator, sitting on the map for 35 seconds grew geod's tile store
(`Caches/com.apple.geod/Vault/MapTiles`) by ~457 KB whether the basemap toggle
was on or off — the same tiles either way.

**As of build 33 this is fixed.** iOS no longer uses MKMapView at all. The map is
rendered by an in-app tile renderer we wrote (`TileMapView`), which draws only the
raster source you chose — an Esri/OSM online style when the basemap gate is on, an
offline pack/GeoPDF when you've imported one, or nothing when both are off. There
is no `MKMapView` in the tree, so `geod` is never asked for a tile.

This is measured, the same WAL way. After checkpointing geod's `MapTiles.sqlitedb-wal`
to zero and then panning the renderer aggressively across fresh ground:

| Map engine | geod tile-store growth while panning |
|---|---|
| Old MKMapView | ~457 KB (fetched regardless of the gate) |
| New in-app renderer | **0 bytes** |

During that pan the app fetched tiles the whole time — every request went to
`ibasemaps-api.arcgis.com` (the Esri basemap you turned on), with **zero
basemap-tile contact to any Apple map host** (`*.ls.apple.com`, `gspe*`,
`cdn.apple-mapkit`). So on iOS the AO no longer leaks to Apple *through the
basemap*. (One thing still can: place-name **search** uses `MKLocalSearch`,
which sends the query you type and camera region to Apple's servers - but only if you enable
online lookups and use the search box. See §5.) What the online-basemaps gate now buys
you is the ordinary thing it says: with it off, the app makes no basemap tile
request of any kind, and an imported offline pack or GeoPDF genuinely hides your
AO — there is no Apple fetch underneath it any more.

The remaining ambient exposure is now symmetric across both platforms and comes
only from the providers you can still choose to use: turning the basemap gate
**on** sends your tile coordinates to Esri/OpenTopoMap (your choice, with a
persistent red banner while it is active). No Apple or Google basemap engine is
started on either platform.

Rule of thumb: **an online map or coordinate lookup exposes your AO to its
provider.** Pre-stage offline maps before you need them. With both online gates
off, the in-app renderers make no basemap or lookup request on either platform;
Unit Sync, store reconciliation, operating-system traffic, and any other app on
the device remain separate network paths.

---

## 7. What TacMap does NOT protect you from

Stated plainly, because a tool that hides its limits cannot be trusted.

- **Relay traffic analysis.** Content is encrypted; the fact and pattern of
  communication is not. Co-membership, approximate location by IP, and activity
  tempo are inferable at the relay. Selected-unit chat also reveals the sender
  and recipient actor/session tuple to the relay. Self-hosting moves this trust
  to you but does not remove it. A LAN/mesh transport removes the internet vector entirely
  [PLANNED].
- **A coerced relay can suppress or roll back, not just observe.** AEAD stops the
  relay forging a blob, replaying it under a different id, or moving it between
  rooms (§4), and **every mutation is now signed** on top: presence carries a
  per-device signature over a session-bound monotonic counter, and object writes/deletes carry
  a per-device signature bound to the object id + version (a delete is a *sealed,
  signed proof*, so a relay with no room key can't forge one at all). So a relay
  can't forge a deletion or replay an older object version to a client that
  already tracks that object. A persisted accepted-hello epoch only rejects
  session replay at or below that client's high-water. A malicious relay can
  still present an obsolete but genuinely signed higher epoch that the client
  has never seen, then replay presence from that session; a fresh/reinstalled
  client has no high-water at all. Omission and join-time rollback also remain:
  the relay can silently drop an update (you never see the new enemy contact) or
  serve an older genuinely signed snapshot. Treat the shared picture as advisory
  and confirm critical changes out-of-band. Detecting these cases requires an
  external transparency log or out-of-band trust anchor; a relay-controlled
  monotonic sequence alone is not sufficient.
- **Area-of-interest leakage via online basemaps/lookups.** See §6. While the
  online-basemaps or online-lookups gate is on, the tile/query coordinates go to
  the provider (Esri/OpenTopoMap/Open-Meteo, Apple place search, or the Android
  platform Geocoder) from your IP. That is inherent to using an online map or
  lookup and is disabled by default. Coordinate-shaped search text is handled
  locally and does not reach a place provider. iOS no longer asks Apple's
  `geod` for basemap tiles, and Android no longer links the Google Maps SDK (§6).
- **Device compromise or capture.** Mission data (waypoints, drawings, track
  logs, PDF calibration, chat history/drafts, and chat replay state) is
  **encrypted at rest** with AES-256-GCM. The key is
  not written as plaintext application data: on Android it is wrapped by an
  Android Keystore key whose bytes are not exportable through that API; on iOS
  it lives in the Keychain as `AfterFirstUnlockThisDeviceOnly`. The iOS item does
  not sync through iCloud Keychain or restore onto another device, but a protected
  backup can restore it to the same device. What this does and does not defeat
  depends on the platform, device implementation, boot state, and one setting:

  - **Default (device-bound key).** An ordinary extraction of TacMap's files gets
    ciphertext plus wrapped-key/Keychain state rather than a plaintext mission
    store. This is useful protection for backups and powered-off or
    before-first-unlock capture, subject to the platform and device's own data
    protection. It is not a blanket guarantee for every locked device. After
    first unlock, a live exploit may be able to run as TacMap and ask the
    Keystore/Keychain to decrypt. On Android this code does not request StrongBox
    or require/attest hardware-backed `KeyInfo`, so it does not claim that the
    wrapping key is hardware-backed on every supported device. Non-exportable
    means the API does not return key bytes, not that a compromised system cannot
    use the key.

  - **"Require unlock to decrypt mission data" (auth-bound key), opt-in.** The
    same DEK is re-protected/re-wrapped with the platform's user-authentication
    access policy, so
    the Android Keystore or iOS Keychain requires a recent device credential or
    biometric before use. This raises the bar after process death, but the exact
    hardware enforcement varies and is not verified by the Android app; a fully
    compromised OS or secure-hardware vulnerability is outside this guarantee.
    Android also records AUTH as a dedicated user-auth-required Keystore alias:
    while that alias exists, rolled-back mutable preferences cannot select the
    older DEVICE wrapper. After a verified transition the app removes the DEVICE
    wrapper and KEK. Preference/Keystore write failure can still make the data
    unavailable, but it fails closed instead of silently lowering protection.
    The costs are real: after the process dies, nothing reads or writes mission
    data until you authenticate, **including background track recording**; and on
    Android, removing your device lockscreen can invalidate the key and make the
    data unreadable.

  A note against overclaiming: on iOS the key is a raw AES key, so it is **not**
  "in the Secure Enclave" — the SEP only holds P-256 keys. It is in the Keychain,
  whose class keys the SEP wraps and holds. That is a genuine hardware guarantee,
  and it is a different sentence.

  The optional in-app PIN lock remains a **UI deterrent for a borrowed device,
  not encryption**, and is independent of all of the above.

  Overwritten plaintext from a pre-encryption build is replaced in place by an
  atomic rename. On flash storage the old blocks may survive until wear-levelling
  reclaims them, protected only by the platform's own full-disk encryption.

  Per-room Chat and rollback-state files use DEK-bound opaque filenames so a
  filesystem or backup index does not disclose the routing room ID. On upgrade,
  both apps scan and rename every canonical legacy room filename after the DEK is
  available, including inactive rooms; a locked key causes no filesystem changes
  and interrupted passes resume on the next unlocked launch.

- **Data that is still plaintext on disk.** Imported basemaps are not sealed under
  the app key: MBTiles packs and imported PDF/GeoPDF sheets sit in app-private
  storage (on iOS, in a `FileProtection`-covered directory and no longer exposed
  through Files/Finder file sharing) but as their original bytes. They reveal your
  area of interest to anyone who extracts them at the filesystem level. Only the
  *calibration sidecar* (which sheet, what ground it covers, the fitted affine) is
  sealed. Encrypting the packs themselves would mean SQLCipher and streaming
  decryption; it is not done.
- **Custom symbol packs.** Pack import is an explicit local document-picker action.
  The app does not fetch GitHub releases, follow artwork URLs, execute SVG/scripts,
  or contact a pack author. A cloud-backed system document provider may download
  the file selected by the user; that provider is outside TacMap's network gates.
  Pack files are bounded to 16 MiB, 1,000 symbols, 32 KiB per PNG and 256 × 256
  pixels. The installed library is capped at 32 packs / 32 MiB and sealed with
  the mission-data key using a separate authenticated store label. Locked keys,
  failed authentication and malformed existing stores block replacement rather
  than silently starting an empty library. No original import copy is retained
  by TacMap; the source file remains wherever the user selected it.
  Placed custom markers embed their name and PNG in the encrypted waypoint store
  and existing authenticated Unit Sync payload. Merely importing a pack does
  not broadcast the library. Exported GeoJSON includes the placed artwork/name
  as plaintext, like its other mission fields. Artwork increases traffic size,
  still visible to the relay, and existing sync/import size limits still apply.
  A content hash checks artwork identity, not author trust, operational accuracy
  or official approval. Other members can copy or mislabel the symbols. Older
  clients may show fallback markers; participating devices need compatible builds.
- **Crash logs are local and plaintext.** An uncaught-exception / fatal-signal
  handler writes a short stack trace to app-private storage (never transmitted -
  you export it yourself from About). It is not sealed: the fatal-signal path has
  to be async-signal-safe (no allocation or crypto), and doing keystore crypto
  inside a crash handler is fragile. Stack traces rarely contain coordinates but
  can carry an imported map's file name; clear it if that matters.
- **Your own room members and chat recipients.** Everyone with the join code can
  decrypt map objects, presence, and **Entire room** chat. A **Selected unit**
  message instead uses an ephemeral X25519 session secret and is not decryptable
  merely from the join code. Its selected recipient can still screenshot, copy,
  quote, or retransmit it. Direct chat is live-only in v1: there is no relay
  mailbox, and an offline or changed recipient session fails rather than
  widening delivery to the room. It has session forward secrecy after key
  erasure, not a Signal-style double ratchet or post-compromise security.
- **Opted-in screen-off presence (iOS and Android).** Background Unit Sync
  location is off by default and requires its OPSEC switch, **Share my
  location**, and an active v3 room. On iOS, TacMap continues the
  foreground-started Core Location session and leaves the system background
  location indicator visible. On Android, TacMap runs a location foreground
  service with an ongoing notification; it does not request
  `ACCESS_BACKGROUND_LOCATION`. Both platforms intentionally retain only the
  current room/session signing material needed to send encrypted positions at
  a best-effort bounded cadence. Neither path retains or unlocks the auth-bound
  mission-data key for this purpose.
  The extension lifetime is signed separately from the backwards-compatible
  location payload, is capped at 65 minutes, and requires the exact active
  session; legacy/foreground-only locations retain the 45-second window. A
  sharing or eligibility change rotates/closes the session to withdraw any
  extended marker. Cached fixes older than two minutes are not rebroadcast,
  and receiver expiry uses process-monotonic time so wall-clock changes cannot
  prolong a marker.
  Inbound mission/session frames are ignored while the app is locked or
  backgrounded, and a new verified snapshot is required on foreground return.
  If the authenticated socket drops, sharing pauses rather than creating a new
  background session. Force-quit, termination, permission loss, and unavailable
  GPS/network also stop delivery.
- **Peer identity: established devices are signed; brand-new ones are not.** Each
  device holds a per-device Ed25519 key and signs every **presence** update *and*
  every **object write/delete**, chat-key advert, and chat frame; the first time you see a client id you pin its
  public key (trust-on-first-use), and every later message from that id - presence
  or object - must carry a valid signature under the pinned key. So one room member
  **cannot** impersonate another *established* device: not its callsign/position,
  and not a waypoint/drawing edit or deletion attributed to it (a changed key is
  rejected as a possible swap). What this does **not** stop: anyone holding the
  join code can still introduce a brand-new fake client id with its own key - TOFU
  can't distinguish a genuinely new peer from a fake one, so room membership
  remains the trust boundary (§7). Out-of-band identity confirmation (comparing
  pinned key fingerprints) is the remaining hardening.
- **A weak join code.** The 210k-iteration stretch raises the cost of guessing,
  but a short or predictable code is still guessable - offline, against the
  relay-visible routing ID, and once per code for the whole user base because
  the salt is a global constant (see §4, *Your join code is the whole
  ballgame*). The Unit Sync screen generates a strong ~78-bit code and rejects
  codes under 14 characters; use the generator and do not invent your own.
- **Export metadata.** A GPX track embeds a timestamp for every recorded point,
  along with its coordinates, optional elevation, and the fixed creator string
  `TacMap`. Mission-object GeoJSON instead carries each waypoint, drawing, and
  layer's creation time; it does not add per-vertex timestamps or a generated-at
  field. Neither format adds a hardware ID or sync client ID. GeoJSON does carry
  user-entered names and notes, which can contain callsigns or other sensitive
  text. A shared GPX track therefore exposes exact pattern-of-life timing; scrub
  it before sharing outside your unit if that matters.
- **Authorisation.** This is not a technical control and TacMap cannot grant it.
  A well-engineered app is **not** an accredited one. Whether you are permitted
  to hold or transmit official information in this tool is a decision for your
  chain of command and your security authority, not for the app. If in doubt,
  ask before you load real data.

---

## 8. Recommended posture and self-hosting

**Fresh-install defaults:**

- Screen capture blocked (keeps live position out of screenshots and the recents
  thumbnail). This is literal on Android (`FLAG_SECURE` blocks screenshots,
  screen recording, and the recents thumbnail). On iOS there is no public API to
  block an in-app screenshot, so the toggle only covers the **app-switcher
  snapshot** (an opaque cover while the app is backgrounded) - a deliberate
  screenshot of the live map is still possible.
- Online lookups off; enabling them sends provider queries described in §5.
- Online basemaps off; enabling them exposes viewed tile coordinates as described in §5.
- Mission data encrypted at rest with a device-bound key.
- Sync off until you join a room.
- TacMap Chat unavailable until an upgraded v3 relay acknowledges the current
  session's signed ephemeral chat key. Messages are live-only: there is no
  offline mailbox, and **Routed** does not mean delivered or read.
- Relay ingress is bounded per socket by both frame count and encoded UTF-8
  bytes: 1 MiB per frame and 4 MiB/200 frames per 10 seconds. Binary frames are
  bounded before decoding and protocol pings accept no padding fields.
- Client receive paths are bounded before JSON parsing as well. iOS configures
  the platform WebSocket's 1 MiB message ceiling and receives delivered
  messages serially. Apple's public WebSocket API handles RFC 6455 control
  frames internally, so the app cannot count individual Ping/Pong frames; a
  hostile relay's control-frame flood remains a native-transport availability
  risk, mitigated by the relay rate limit and operating-system networking
  stack. Android rejects oversized declared frames before payload allocation,
  caps every fragmented aggregate and its fragment count, rate-limits every raw
  data/control frame, and applies reader-thread backpressure until the
  serialized protocol consumer has handled the current message. WebSocket
  compression and redirects are not enabled for Unit Sync.
- Background Unit Sync location off until you explicitly enable it in OPSEC.

**For real operations, additionally:**

- Pre-stage **offline basemap packs** and leave online tiles/lookups off. The
  app then makes no provider request for the area of interest. Store entitlement
  reconciliation and operating-system traffic are separate; use **airplane
  mode** or a network you control when no network contact at all is acceptable.
- Turn on **"Require unlock to decrypt mission data"** if device capture is a
  more realistic threat to you than a track cut short by a reboot. Read the
  trade-off in §7 first, and on Android do not remove your lockscreen afterwards.
- **Self-host the sync relay** so no traffic transits an account you do not
  control. The relay is a single Cloudflare Worker + Durable Object
  (`sync/src/index.ts`); deploy it to your own account and configure the relay endpoint in your
  deployment/build. The distributed app has no user-editable relay URL setting. It stores/forwards encrypted mission objects, but only
  forwards live Chat frames. It still handles the clear admission, envelope,
  actor/session/key, Chat scope/selected-recipient, acknowledgement, and
  control metadata described in §4.
- Use generated join codes and rotate them per activity.
- Treat a lost **unlocked** device as a compromise of all mission data on it. A
  powered-off or before-first-unlock device presents a stronger platform data-
  protection boundary; a device merely locked after first unlock is not promised
  safe against a live forensic exploit. Use auth-bound mode where that trade-off
  fits, and follow your platform/security authority's capture procedures.

---

## 9. For auditors

- Sync crypto: `android/.../sync/SyncCrypto.kt`, `ios/.../Sync/SyncCrypto.swift`,
  and the matching `SyncCryptoTest` suites.
- TacMap Chat's exact signed key-advert, room/direct header, AAD, KDF, relay
  routing, metadata, and lifecycle contract is in
  `security/ADR-004-tacmap-chat-v1.md`. Cross-platform bytes are pinned by
  `testdata/tacmap_chat_v1.json`.
- At-rest crypto: `util/SealedEnvelope.{kt,swift}` (AES-256-GCM, wire format
  `magic(7) || iv(12) || ct || tag(16)`, store label bound as AEAD associated
  data) and `util/DataKey.{kt,swift}` (key custody + the auth-bound toggle).
  Both `SealedEnvelopeTest` suites open the *same* fixture blobs, generated by a
  third implementation, so Android and iOS are pinned to one wire format rather
  than to each other.
- Egress: every network call the *app* makes is in the services listed in §5.
  There is no analytics, telemetry, or ad SDK; verify by searching the source for
  outbound URLs. (Historically iOS leaked map tiles through Apple's `geod`
  daemon, which no source search would reveal; §6 documents how that was closed
  — there is no `MKMapView`, so `geod` is never asked for a basemap tile.)
- OPSEC defaults: `settings/OpsecSettings.kt` and the iOS equivalent.
- Crash handling: `CrashReporter` (local file only).

Issues and disclosures welcome via the repository.

---

*This is maintained release documentation. Revalidate it against every candidate
build; planned work and unverified device-specific behaviour are not guarantees.*
