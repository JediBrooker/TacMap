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
| Imported map files (PDF/GeoPDF/MBTiles) | **Untrusted** | Parsed by PDFBox/pdfium (Android) or CoreGraphics (iOS) and SQLite. On iOS a small in-app reader also walks the PDF's cross-reference and object streams to work out optional-content (layer) visibility, because CoreGraphics ignores OCMDs. It never writes the file and falls back to the plain file on anything unexpected. Offsets, lengths and object numbers from the file are range checked before they are combined, so a crafted value is rejected rather than crashing the app. Its budgets cover the whole pass, not each object: at most 64 MiB of stream data decoded per pass (32 MiB per stream), at most 32 MiB of decoded object streams kept at once, a cross-reference table of at most 2,000,000 entries, at most 1,000,000 object reads, nesting depth 64, and parsing work capped at twice the file plus twice what was decoded plus 16 MiB, so time grows linearly with the file. Hitting any budget means the plain file is drawn. Memory for what it parses is budgeted as well: one parsed object may take about 16 MiB (counted as 64 bytes per element plus its name and string bytes), a keyword or name at most 4 KiB and a string at most 1 MiB, and the arrays loaded for one visibility expression share a single 16 MiB allowance. An object over that is skipped; if it is the catalog or the layer settings, the plain file is drawn. When a stored map is reopened at launch in the foreground and has not yet drawn cleanly once, this pass runs under the render crash guard. A map that has already drawn (the usual case), or one restored by a background relaunch, is opened without it. On an MBTiles pack SQLite runs only TacMap's own fixed queries: SQL stored in the pack (a view that computes anything, a generated column, a virtual table) gets the pack refused, and opening a pack is crash-guarded too (§7). A hostile file can try to exhaust memory or time, or declare a misleading georeference (see §7). |
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
  The same persistent device signing public key is advertised across rooms;
  room-scoped actor IDs do not prevent cross-room device correlation.
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

**What the relay stores, and for how long.** Per room the relay stores a hash of
the admission token, the protocol version, the room's sequence and counter
high-water values, record/byte counters, the time of last activity (written at
most hourly while devices send anything, each time the last connection goes,
and daily while anyone is connected; no message traffic writes it more than
once an hour, but a device that keeps reconnecting writes it on each
disconnect), the latest sealed record of each
synced object, tombstones (the sealed, signed delete proof with its signer's
public key and session ID), and one pin per device actor (public key, first-seen
time, the hour of its latest session announcement, and that signed
announcement). Once idle expiry drops a pin, the relay keeps that device's
epoch floor instead: its pseudonymous room-scoped actor ID and the number
(epoch) of its latest accepted session, nothing else. It also keeps relay-only
bookkeeping rows: the hour each delete landed, the time of the last idle
expiry, the sequence number of the last expiry or compaction, the newest
session-announcement hour among the device pins dropped at expiry (one value,
no device identity), an index marker (0 in rooms created since SP1; in older
rooms the hour the relay first indexed their deletes, which is about the SP1
deploy time, not the room's creation), and a marker while an expiry pass is
unfinished. No stored time that outlives idle
expiry records when the room was created (device pins, with their first-seen
times, go at expiry). An epoch floor holds no time either, but current apps
start a device's session number at the Unix minute of its first session in the
room and only count up from there, so a floor shows that the device joined no
later than that minute (roughly when, for a device with few sessions since).
Presence, chat, and chat keys are never written to storage.

- About 7 days after the last recorded activity, with no connection open, the
  relay deletes every live object and swaps every actor pin for its epoch
  floor. A room that never stored an object or a delete and in which no
  device ever announced a session (a legacy v2 room nobody wrote to, or a
  drive-by connection) is deleted entirely. Otherwise, rooms used only for
  position sharing or chat included, the relay keeps the token
  hash, protocol, sequence and high-water values, counters, last-activity time,
  the bookkeeping above, the floors and tombstones, so a device that returns
  later is not rolled back, is not locked out by the counter window, and
  cannot bring back an object someone deleted, and nobody holding the join
  code can replay another device's older session (§7). Those few values hold
  no mission content. A device's next session is always accepted and turns
  its floor back into a pin.
- About 90 days after the last recorded activity (or after the last idle
  expiry, if no activity was ever recorded), with no connection open, the
  relay deletes everything it still holds for the room: token hash, counters,
  timestamps, bookkeeping, epoch floors and every remaining tombstone. Nothing
  is left. Any recorded activity in between restarts both clocks. A device that comes back after that finds a fresh, empty room: its
  sequence fence is lower than the one it saw before, so the app shows the
  rollback warning, and a device whose version counters had run more than
  10,000 ahead gets counter-window rejections. Treat it as a new room and move
  to a new join code. In legacy v2 rooms whoever connects first afterwards pins
  the fresh room, as at idle expiry before SP1; v3 room IDs are bound to the
  join code. See §7,
  *Expired rooms are remembered for up to 90 days*.
- In v3 rooms a tombstone is deleted once it is 30 days old **and** its author
  device is not connected now and has not started a session (signed hello)
  in the last 30 days. Once idle expiry has dropped the device
  pins, the relay no longer knows each device's last connection and counts
  from the latest connection of any device whose pin was dropped, so a
  tombstone can outlast its own author's absence by up to the gap between that
  author's last connection and the last device's, even while other devices
  keep using the room. In legacy v2 rooms (no device pins) a tombstone is
  deleted once it is 30 days old and the whole room has been unused for 30
  days; a v2 room in steady use keeps them. Until then deletes survive idle
  expiry. Legacy v2 tombstones are stored as the v2 record itself, so they
  keep its relay-set deletion time (millisecond precision) and the deleting
  client's id until they are compacted or the room is purged.

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
code - a couple of dictionary words like `bravo-tonight` - remains vulnerable
to offline dictionary attacks; this app makes no benchmark-based cracking-time
guarantee. **So sync is only private if your join code has real entropy.**
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
| `api.open-meteo.com/v1/elevation` | Elevation, terrain heatmap + elevation profile | Elevation/terrain features, or opening a line's elevation profile, after enabling online lookups | Your IP + the map-centre coordinate coarsened to roughly 110 m for elevation, a 24 × 24 coordinate grid coarsened to roughly 11 m covering the visible area for terrain, or up to 200 points coarsened to roughly 11 m along the profiled line (which reveals the route or sight line) | **Off** until online lookups are enabled | Leave online lookups off |
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

**This table inventories app-initiated service requests.** User-opened links
can hand a URL to the system browser; document providers may download a selected
cloud file, and share destinations follow the user's choice. Those explicit
user/OS actions and ambient platform traffic are outside TacMap's HTTP gates.
*We* add no analytics SDK, remote crash telemetry, or
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
- **Deletes are not remembered forever.** To reclaim space from departed
  devices, the relay drops a tombstone once it is 30 days old and its author
  has been away for 30 days (§4 has the exact rule, including legacy v2
  rooms), and after 90 idle days it drops the whole room. A device that was
  offline longer than that, still holding the deleted object, can publish it
  again and the relay accepts it. Peers that kept the delete in their own
  replay state keep rejecting it, so those peers and new joiners can disagree
  about that object until someone edits or deletes it again. For operations
  where devices sit unused for more than a month, rotate to a fresh join code
  instead of reusing the room.
- **A room insider can still fill the room.** Record and byte quotas are
  room-wide. Anyone holding the join code can pin many throwaway device
  identities or keep a large set of tombstones alive by checking in more often
  than every 30 days, until honest writes are refused as over quota and new
  devices cannot join. Each throwaway identity also leaves an epoch floor that
  keeps counting against the quota after idle expiry, until the 90-day purge.
  The relay cannot tell an insider from a teammate. The
  remedy is the same as for any compromised code: move the unit to a new join
  code.
- **A room insider can replay another device's old session only once the
  relay has forgotten the room.** Every member sees each device's signed
  session announcement and records, so anyone holding the join code can
  capture them. The relay refuses an announcement that is not newer than the
  device's latest one, and since 3.0.1 it keeps that number (the epoch floor,
  §4) through idle expiry until the 90-day purge, so neither an old session nor
  the old object versions, positions or chat it carried can be brought back
  while the room exists, also in a room only ever used for position sharing.
  After the 90-day purge the relay has nothing left to check against, and an
  insider can replay a captured session (its records, presence and chat) to
  devices that never saw a newer one, such as a fresh install. Devices that
  kept their replay state reject it. A relay that
  is itself hostile can always do this (see *A coerced relay* above), and
  older or self-hosted relays without the floor forget it at the first idle
  expiry. After a purge, move to a new join code.
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

  On iOS TacMap stores the raw AES key as a Keychain generic-password item;
  it does not create or hold that key through a Secure Enclave API. DEVICE mode
  uses `AfterFirstUnlockThisDeviceOnly`; AUTH mode uses
  `WhenUnlockedThisDeviceOnly` with user presence. Protection depends on iOS
  Keychain/data-protection enforcement and the selected access policy. TacMap
  does not attest hardware enforcement or promise protection from a compromised
  OS or secure-hardware exploit.

  **Locking is an access/lifecycle gate, not memory erasure.** An explicitly
  started track recording retains a private copy of the same general 256-bit
  mission DEK so it can append to that already prepared encrypted log after the
  general key locks. This is software ownership and lifetime scope, not a
  cryptographically restricted track-only key: disclosure of that copy could
  open other DEK-sealed stores. Stop, discard, recording failure, permission loss
  and process death end its intended lifetime. Android zero-fills its retained
  array; iOS releases its `Data` value. Neither implementation proves erasure of
  every runtime/allocator copy. Process death does not restart recording; AUTH
  recovery needs authentication. See
  [ADR-002](https://github.com/JediBrooker/TacMap/blob/main/docs/security/ADR-002-key-lifetime-and-rotation.md).

  Android detaches mission stores and clears its general-key cache on Activity
  pause. The one exception is TacMap's own share sheet: that translucent chooser
  pauses the Activity with the map still visible behind it, so the same lock runs
  at the following onStop instead (as soon as you actually leave: the share
  target opens, Home, recents or screen off), and closing the sheet back into
  TacMap locks nothing. While that sheet is up the mission stores stay attached,
  the key stays cached and Unit Sync keeps processing. Before clearing the cache,
  Android waits up to two seconds for Unit Sync sealed writes already queued on
  its persistence worker, which normally includes the presence clean point; if
  another sync write holds the store at that moment, the clean point fails
  closed instead and the next reload applies the safe presence floor. Once cleared, the
  general key is not unwrapped again in either mode until the foreground unlock
  (device-mode resume, the App Lock PIN, a platform credential or a confirmed
  protection change). A write still in flight behind the lock fails closed
  instead of re-caching the key: a Unit Sync write that missed the two seconds
  is dropped without a security stop (if it carried the sync revision record of
  a local edit, only the object ids are remembered, in memory, and that record
  is written after the foreground unlock, before Unit Sync reconnects), and a
  map import still copying, or a
  map-library change still being written, when the app leaves the foreground
  (or while the Activity is recreated for a configuration change it doesn't
  handle) fails without deleting anything and has to be retried. A PDF bake that
  finishes in the background doesn't try to write: its finished tiles wait,
  unrecorded, in the app-private bake work folder (cleared at the next process
  start) until the foreground unlock, then are recorded on their PDF's library
  entry. With no map screen open at that point the bake is recorded straight
  into the sealed library, under the same write-counter check. Cancelling the
  bake while it waits deletes the file.
  iOS keeps the map view mounted to preserve authorized recording:
  previously decrypted mission/library objects, track points and rendered tiles
  may remain in memory beneath the opaque lock view. AUTH mode clears the
  general key cache; DEVICE mode may retain it under the accepted lifecycle
  policy. UI and inbound Sync gates prevent ordinary locked-state
  access/processing (on iOS the UI gate is the cover window described next, for
  the auth-bound key as well as App Lock), but do not defend retained memory
  against code executing inside a compromised process.

  On iOS the App Lock view is shown in its own window above alert level in every
  scene of the app, and so is the "Mission data locked" screen shown while the
  mission-data key is locked (with the auth-bound key, after every trip out of the
  foreground), so either one also covers whatever TacMap had presented when it
  locked: sheets (Unit Sync with the join code, waypoint and drawing lists, the
  map library, export), the system share sheet, file pickers and alerts. Those
  stay presented underneath (nothing is dismissed, so they are back after unlock)
  but while locked they cannot be seen or touched, are hidden from VoiceOver, and
  lose text-input focus; if anything underneath claims keyboard focus while
  locked, the lock window takes it back. App Lock comes first; once its PIN is
  entered, the mission data screen takes its place in the same window until the
  key is unlocked, so a sheet left open is never uncovered in between. TacMap Chat
  is still closed and its secrets cleared on lock. App Lock arms when the app
  enters the background, not on a transient `.inactive`; the auth-bound key locks
  on any `.inactive`. Prompts drawn by iOS itself (permission alerts, Face ID) are
  outside the app and can still appear above it. Alerts TacMap raises while one of
  these screens is up wait underneath it until unlock.

  The optional in-app PIN lock remains a **UI deterrent for a borrowed device,
  not encryption**, and is independent of all of the above.

  Overwritten plaintext from a pre-encryption build is replaced in place by an
  atomic rename. On flash storage the old blocks may survive until wear-levelling
  reclaims them, protected only by the platform's own full-disk encryption.

  Per-room Chat and rollback-state files, and on Android the sealed legacy-v2
  wire-id casing file (object UUIDs only, no content), use DEK-bound opaque
  filenames so a filesystem or backup index does not disclose the routing room ID. On upgrade,
  both apps scan and rename every canonical legacy room filename after the DEK is
  available, including inactive rooms; a locked key causes no filesystem changes
  and interrupted passes resume on the next unlocked launch.

- **Data that is still plaintext on disk.** Imported basemaps are not sealed under
  the app key: MBTiles packs and imported PDF/GeoPDF sheets sit in app-private
  storage (on iOS, in a `completeUntilFirstUserAuthentication` directory that is
  excluded from backup and not exposed through Files/Finder file sharing; on
  Android, app-private storage with backup disabled) but as their original bytes.
  They reveal your area of interest to anyone who extracts them at the filesystem
  level. The files have opaque names (`map-<uuid>` on iOS, `import-<32 hex>` on
  Android). The imported-map library (each map's original file name, content hash,
  page, embedded or hand-made georeference and calibration points, its offline-tile
  bake record and its crash-guard token) and any calibration still in progress are
  sealed under the mission-data key, so deleting a map deletes its calibration with
  it. Every PDF entry, and every newly imported MBTiles pack in the current
  imported-map library implementation, is bound to its file's SHA-256 (taken in the same pass that copied it in). An MBTiles pack carried
  over by the one-time migration from an older build has no recorded hash: it is only
  checked by size and modification time, and the background re-check skips it. At launch the active map is
  shown after a size and modification-time check only; its content hash is re-checked
  in the background, so a file swapped in place with the same size and time is drawn
  until that check (seconds) marks it unavailable. If the active map is a PDF whose
  file is missing, or whose size, time or hash no longer match, the selection and its
  entry are kept but nothing is drawn, baked tiles included (the map shows "couldn't
  be drawn"), until bytes with that exact hash are back, so a calibration is never
  applied to a different file; an MBTiles pack falls back to the online basemap. The
  hash is re-checked on every Try Again, whether or not the sheet was already flagged,
  and (Android) at each document open and whenever a new tile source is built; a memo
  keyed on path, size, mtime and file identity keeps repeat checks of an untouched file
  to a stat. A sheet that has not passed the check yet can't start an offline-tile
  bake either (the estimate re-checks the bytes itself). Re-importing the same bytes
  finds the existing entry. On Android the picked document's URI is
  held in memory and the saved instance state only, never in plaintext
  preferences; older builds' leftover record is read once and wiped, and orphaned
  read grants are released at startup. Encrypting the map files themselves would
  mean SQLCipher and streaming decryption; it is not done.
  The same goes for **baked offline tiles** ("Generate Offline Tiles"): a plaintext
  MBTiles raster of the imported sheet's AO, `offline_tiles/tacmap-bake-<uuid>.mbtiles`
  in Application Support (Android: `filesDir/offline_tiles`), written first as
  `pdf_bake_work/<uuid>.mbtiles.partial`. A bake cancelled at any point, including
  while its writer is still being created, deletes its `.partial` (on iOS the writer is
  created synchronously on the bake thread, so the cancel cleanup always runs after it);
  anything left in `pdf_bake_work` is cleared at process start.
  The file's metadata never names the sheet: `name` is always the fixed "TacMap offline
  tiles". The `.partial` is written with no on-disk rollback journal: both apps ask
  SQLite for `journal_mode=OFF`, read the answer back, fall back to `MEMORY` (the iOS
  system SQLite runs in defensive mode and ignores `OFF`), and fail the bake closed if
  neither sticks, so no stray `-journal` copy of tile pages is left next to it.
  On iOS the file and both directories are `NSFileProtectionCompleteUntilFirstUserAuthentication`
  and excluded from backup; Android has `allowBackup=false` and data-extraction rules
  that exclude everything. Only the bake record (file name, bake key, zooms) is kept,
  on the PDF's sealed library entry, and it is attached in one library write only while
  that entry is still the same bytes, crash-guard token and georeference the bake was
  made from; a calibration, page change or revert to the embedded georeference drops
  it. A bake never becomes a map of its own and never changes the active map.
  "Remove Offline Tiles" clears the record in one library write first (if that write
  fails nothing is deleted), then deletes the named file and its
  `-journal`/`-wal`/`-shm` sidecars directly, but only if the name starts with
  `tacmap-bake-` (only plain files inside `offline_tiles`, under the same managed-files
  lock the reconcile and the bake publish take). A bake-only sweep follows, and also
  runs at the start of every restore of the active map (launch, unlock and Retry, on
  both apps). It
  deletes regular `tacmap-bake-*.mbtiles` files and their sidecars directly inside
  `offline_tiles` that no library entry's bake record names. It never recurses or
  follows symlinks, and runs under the same managed-files lock as bake publish, which
  moves the file and writes its record as one step. It runs only on an authoritative
  read of the library: a library that loaded, or a first launch where no library was
  ever written, no legacy store is left to migrate and no map file is in the managed
  directories (that names nothing and has nothing to delete). A library
  that was written before and is gone now, or that was quarantined as unreadable (a
  `.corrupt-<time>` copy next to it), counts as unreadable, never as empty. If the library
  is locked or won't decrypt or decode, or legacy stores are still waiting for the
  migration (a quarantined legacy store included), the sweep is skipped. The same rule
  holds for the managed-file reconcile and the calibration-draft prune: none of them ever
  runs from a library state the app made up to stand in for one it couldn't read. Nor
  from an older copy of the library a screen kept in memory: on Android the reconcile's
  keep set, its list of files still being written, the bake sweep and the draft prune all
  come from the sealed library read under the managed-files lock, and every library write
  is checked under that lock against the sealed library's write counter. A second copy of
  the map screen, or a bake that finishes after the screen that started it has closed,
  therefore can't write back an older library or get another screen's imports,
  calibrations or baked tiles deleted; it re-reads the library and applies its change on
  top, or the change is refused. The Unit Sync notification brings the running app to the
  front instead of opening a second copy of it, and so do the launcher icon and the store's
  Open button when the running copy was started some other way. A bake record the app would not have written names nothing, and nothing
  treats a name outside the `tacmap-bake-<id>.mbtiles` form as a bake. It does not
  depend on the PDF being present, so plaintext tiles left by a failed delete don't
  outlive the next launch. Files from the pre-WP2 tiler (`tacmap-<uuid>.mbtiles`) and
  user-imported packs never match the prefix; a pre-WP2 bake that was migrated into
  the library is a map entry of its own and goes with its PDF. On Android, Remove runs
  off the main thread. The managed-file reconcile after every library write deletes a
  bake file no record names any more.
  Delete Map is one sealed library write (the entry, anything derived from it, and its
  bake record go together), after a running bake or estimate for that map has been
  stopped; only then are the PDF, its bake file and their sidecars unlinked and the
  reconcile run, so a crash at any point only leaves orphans the next reconcile or
  sweep removes. If the write fails nothing is deleted, the map stays in the library,
  and a "Map change not saved" alert with Retry is shown. The alert is hosted by the
  Layers sheet while it is open, otherwise by the main screen once nothing else is
  presented over it (it waits behind any other sheet), and only Not Now or a Retry
  that succeeds clears it, so a failed delete can't be closed unseen along with the
  sheet.
  If the sealed library can't be read (wrong key, damage, a newer schema) it is moved
  aside as a recovery copy and nothing on disk is deleted. Retry rebuilds the list from
  the map files still in app storage: each is re-hashed and re-inspected under the same
  parsing limits, gets a neutral "Recovered map n" name and no calibration (original names
  and hand calibrations are lost; all drafts survive); a file that won't
  inspect stays listed as unavailable so it can still be deleted. Baked tiles aren't
  re-adopted. The rebuilt sealed index records `recoveryPreservesOrphans=true`, retained
  by later writes, so no reconcile, bake sweep or draft prune runs on recovery or later
  launches. Explicit georeference changes and bake replacement remove only their known
  superseded bake after the sealed write. Unmatched map files, files beyond the recovery entry cap, bakes and drafts
  stay on disk; only an explicit Delete Map or Remove Offline Tiles deletes its known
  owned files. Older valid indexes omit this flag and keep normal cleanup. The one-time migration from older builds
  never deletes anything it can't vouch for: a stored PDF content key must match a fresh hash before its calibration
  is migrated, and a document that will not open never gets a fabricated page count.
  If the mission-data key is locked, or a write the migration needs (calibration drafts first, then the
  library) fails, nothing is written, cleared or deleted and Retry is offered. A write that fails leaves no
  record that the library was ever written: Android records a sealed store as sealed-only only after its new
  bytes are flushed to a temporary file and just before they are renamed into place (the record still always
  comes before sealed bytes reach the real file, so a sealed store never accepts plaintext again), and iOS
  records it after the bytes. If the app dies in the instant between Android's record and the rename, or the
  rename itself fails, while the old stores are still waiting, the next launch or Retry treats it as the
  migration it was and salvages (below:
  what converts keeps its name and calibration, every other map file is adopted, nothing is cleaned up or
  deleted) rather than as a damaged library. On iOS, if saving the record fails after the bytes went down, the
  library is already written. The migration then keeps the opaque links that library names, clears and
  deletes nothing, and the restore uses that library as if the app had been killed right after the write.
  If a legacy store is damaged
  or was quarantined (only its `.corrupt-<time>` copy is left), or a legacy PDF is present but can't be read
  or converted, the app converts what it can, re-adopts every other map file in app storage the same way as
  the rebuild above (one that won't inspect is listed as unavailable), records `recoveryPreservesOrphans=true`,
  leaves every old store and quarantine copy untouched and tells the user once; no cleanup ever runs for that
  library. On iOS a file still under its pre-3.0 name keeps that name only inside the sealed index and is
  hard-linked (or copied) to an opaque file name like a migrated file; the old name is unlinked after the write.
  In a salvage those links are made before the write, so if the app is killed part way through, the retry can list
  the same map twice, and a kill between the write and the unlink leaves the old name behind as a second, unlisted
  link to the same bytes that Delete Map does not remove (after a plain migration the next reconcile removes such
  leftovers). Nothing is lost in either case.
  On iOS, old hand calibration points whose page space can't be rebuilt stay in their sealed legacy store rather
  than being dropped; Android can't rebuild them either and brings that map back uncalibrated. A library that was
  never written, with no old stores left to migrate, while map files sit in app storage is salvaged the same way
  (every file adopted, `recoveryPreservesOrphans=true`, no cleanup), so neither a missing nor a quarantined index
  can authorise deleting those files. When old stores that read cleanly are migrated instead, the library written from them permits
  cleanup, so the reconcile after it keeps the files those stores name and removes any other map file in app
  storage, much like 2.x's own cold-start cleanup, which kept only the active and retained map. Downgrading to an implementation that knows only one retained map is
  unsupported: its cleanup can delete additional library files it does not
  recognize as retained, requiring re-import. This is a library-format/lifecycle
  boundary, not a marketing-version boundary.
- **Untrusted MBTiles metadata.** `tiles` and `metadata` may each be a table or
  a view (MBTiles 1.3, used by deduplicated packs); anything else is refused.
  No SQL stored in the pack runs when it is read: a view is only admitted as a
  plain projection of one table or an equi-join of two (column names and
  aliases only, checked on its text before anything reads it; the join may only
  match columns with `=`, several such matches joined by `AND`, written as
  `JOIN ... ON`, `USING` or a comma join with `WHERE`, so no function, literal,
  other operator, filter or subquery), and every table a read touches has
  to be an ordinary table with no generated column and not a virtual table.
  These checks read the schema rows SQLite actually runs: each name is looked up
  the way SQLite resolves it (any letter case) and has to match exactly one row
  whose own text declares that name, without `IF NOT EXISTS` (which SQLite never
  stores), so a hand-edited duplicate or a row that misnames its object can't
  stand in for the live view or table. The
  plain views real tools write (node-mbtiles, TileMill, mbutil, MapTiler,
  martin, planetiler, gdal2mbtiles, tippecanoe and tile-join) still open; a
  pack whose views compute anything is refused. Every connection to a pack
  also caps a single value at 4 MiB + 64 KiB
  and a schema statement at 100,000 bytes (iOS through SQLite's own limits;
  Android checks the same lengths itself and, from Android 12, caps SQLite's
  heap at 128 MiB for the process), turns off untrusted schema functions where
  SQLite supports that and turns off automatic indexes. Before Android 12 a
  value read through a view can still be materialised whole before its length
  is checked (bounded by the 4 GiB file). A view has no rowid for incremental
  reads, so it is read with key-addressed
  queries under the same bounds. A view's join isn't bounded by the file size
  alone, so when either relation is a view the admission queries share a 30 s
  budget and each later query on a view gets 2 s; an interrupted admission
  rejects the pack and an interrupted tile read returns no tile (two tables get
  no budget: their check is one scan the file size bounds). Packs are opened
  and checked off the main thread; while the saved pack is checked at launch
  the map stays blank rather than loading online tiles. Opening the saved or a
  newly chosen pack is covered by a crash guard (`mbtiles_open_guard.json`,
  entry ids only): if the app dies while a pack is being opened or is drawing
  its first screen, the next launch doesn't reopen it automatically, shows the
  online map in memory and asks (Open Anyway, Delete Map, Not Now), the same
  way as for a PDF. A pack that kills
  its own import is removed at the next launch like a PDF, and the library
  recovery never reopens a pack it died on. Both readers reject more than 64 metadata
  rows using bounded row/type descriptors before copying text. Keys are bounded
  to 32 characters; known name/format/min-max zoom/bounds fields to
  128/32/16/256. Copied UTF-8 prefixes are at most four bytes per allowed
  character, and unknown values are not copied. Descriptive fields may truncate;
  invalid types, duplicate known keys, invalid UTF-8/NUL in copied prefixes and
  oversized strict numeric/bounds values fail closed. This bounds app-level
  copies, including Android CursorWindow projections, rather than every native
  SQLite allocation or hostile-file CPU cost. Complex Unicode label truncation
  can differ between platforms; the byte bounds still apply. Tile zoom
  aggregation rejects non-integer or out-of-range (0...30) rows before projecting
  scalar extrema. Native SQLite/image decoders remain in-process dependencies.
  Only the two bake-key/tile-size extensions are read on demand, through bounded
  row/type and strict UTF-8/NUL/duplicate/128-character checks. Invalid extensions
  read as missing for bake validation while an ordinary map remains admissible.
  No new image-format whitelist is implied.
- **Untrusted PDF parsing.** An imported PDF is copied once (hashed in the same
  pass) and parsed once, off the main thread, under the same limits on both apps:
  512 MiB per PDF (4 GiB per MBTiles pack), at most 500 pages, page boxes of
  36 to 14,400 pt, 50 pages scanned for a georeference with a 65,536-number read
  budget per page, and a 30 s watchdog. On Android PDFBox gets a 16 MiB heap and a
  64 MiB scratch cap, and an out-of-memory or stack overflow inside the parser is
  reported as "too complex", never as a successful import. PDFBox's scratch spill goes
  to `cacheDir/pdfbox` as plaintext stream bytes; it is deleted when the parse ends and
  the folder is wiped at process start, so a parse killed mid-way leaves it only until
  the next launch. CoreGraphics, PDFBox and pdfium can't be interrupted, so a timed-out
  parse is abandoned rather than stopped, and pdfium (Android rendering) runs in-process
  and could still crash natively on a hostile file. A marker written before parsing
  starts stops an import crash loop: if it is still there at the next launch the copy is
  removed, the import is not retried and the user is told. Before the map is added, a
  probe draws it once under the render crash guard; a sheet that can't be drawn is
  refused and nothing is saved. Drawing (calibration previews included) is covered by
  the same guard, so a sheet that crashes the renderer is not reopened automatically.
  On iOS that includes opening a stored sheet when it is restored at launch in the
  foreground and has not drawn cleanly yet, where the optional-content reader (§3) reads
  the raw file before CoreGraphics does. Once a sheet has drawn, or on a background
  relaunch, that restore open is not guarded.
  A declared georeference that can't be verified is refused with a reason and the sheet
  can be calibrated by hand instead; it is never quietly placed around the current
  camera.
  The shared warp split bound is 0.0625 canonical render-job pixels, with
  unchanged depth, minimum-cell, pixel/allocation, cancellation and tile caps.
  Both native live/bake paths draw up to four cells directly; larger plans use
  bounded staging. This accuracy bound does not guarantee one physical screen
  pixel at deliberate overzoom or correct intrinsic PDF artwork registration.
  Renderer version 2 is part of the bake key: older-geometry bakes fail current
  key checks and fall back to live rendering, preserving the source and old
  bake file until normal removal/regeneration.
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
- **Expired rooms are remembered for up to 90 days.** A room that ever stored
  anything, or in which any device announced a session (so every v3 room
  that was actually used, position sharing only included), keeps about a
  dozen small relay rows (token hash, sequence and
  high-water values, counters, timestamps) plus its remaining tombstones and
  one epoch floor per device (room-scoped device ID and latest session number)
  after idle expiry, so devices returning within that time resume cleanly and
  old sessions stay refused (§4).
  They hold no mission content, but until they go they show that the room
  existed, roughly when it was last used, how many devices took part and,
  through each floor, when each device joined at the latest, and the token
  hash lets whoever holds relay storage confirm a join-code guess just as the
  routing ID does (see *A weak join code*). After 90 days with no activity the relay deletes
  all of it; rooms that never stored anything and never saw a session
  announcement go at 7 days. Room creation is
  only rate limited per IP address, so relay storage holds rows for every room
  used in the last 90 days. A device returning after the purge gets the
  rollback warning and must move to a new join code (§4). If even 90 days is
  too long for an operation, self-host and wipe the relay's storage when the
  operation ends.
- **A room member can get another member's connection dropped.** When the
  relay's room-wide processing backlog is full it closes the connection with
  the largest backlog. A member who runs several device identities can keep
  each of their own backlogs just under an honest device's normal burst (for
  example its resend right after reconnecting), so the honest device is the one
  closed, again on every reconnect. Each identity shows up as a room member and
  counts toward the room quota, and the closed device reconnects on its own;
  treat it like any other misbehaving member who holds the join code.
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
  Selected-unit chat is blocked on the sender once the peer's signed presence
  advertises a retention window above 45 seconds. Room chat remains live-only;
  a Routed status never claims receipt. A peer can still miss a direct message
  before its background bridge presence arrives (ADR-004 sections 1 and 9).
- **Durable replay boundaries (iOS and Android).** Accepted object/session
  changes are sealed before model or authenticated peer state is published.
  iOS snapshot validation and replay/journal encoding and sealing run on
  background workers, including replay load and legacy actor-pin repairs;
  model stores commit once per batch on the UI thread.
  A leave or lifecycle change invalidates pending publication, and rejoining
  waits for the previous replay cleanup. Chat-history writes keep their existing
  durable-before-send and durable-before-receive ordering. The separate chat
  history store still seals synchronously on the UI thread; SP3 moves map
  replay and model-revision journal writes, rather than all application storage.
  Presence counters use strides of 16 plus a 60-second flush. An inexact file
  reload raises each stored counter's acceptance floor by 15, capped at the
  largest valid counter, so a crash cannot make an already exposed position
  acceptable again. Clean points write exact counters; a failed clean point
  keeps the preceding safe floor (ADR-001 section 8). On Android the pause gives
  its clean point up to two seconds to seal before the mission key locks; one
  that misses that bound fails against the lock and keeps the floor.
- **A joined room survives a pause (iOS and Android).** Backgrounding pauses
  mission processing even when the mission-data key stays available. Key lock,
  App Lock or detached stores also close the ordinary session; an explicitly
  eligible v3 background-presence bridge is the separate exception above.
  Android Activity pause (behind TacMap's own share sheet, the onStop after it)
  detaches mission stores, lets the queued clean point seal and then clears the
  general key cache. Without the eligible presence bridge, the socket closes
  without a signed leave; with it, only presence continues. The joined room is kept,
  v2 or v3, with or without location sharing: the derived room keys, the device signing seed and the replay-state
  object stay **in memory only**, and the app reconnects by itself once the
  key is unlocked and fresh stores are attached. On iOS a transient
  `.inactive` scene phase (Control Center, a notification banner) keeps the
  socket, chat key and inbound processing when the key stays unlocked
  (device-bound mode) and no App Lock overlay is shown; in auth-bound mode the
  key locks on `.inactive` and the session ends as before. That in-memory
  material is cleared on leave, on a join-code change and at process death.
  Android detaches the mission stores and clears the general-key cache, and
  nothing re-caches it before the foreground unlock;
  iOS gates inbound processing and covers the mounted UI, presented sheets
  included, without erasing every already decrypted model or tile. With the
  auth-bound key iOS likewise never reads the key again before the user's own
  unlock: a save still queued when the app left the foreground fails quietly
  instead of prompting for Face ID or passcode, re-caching the key behind the
  lock or stopping Unit Sync, and the sync revision record of a local edit it
  carried is written after the unlock instead. The
  authorized recording-key exception and DEVICE cache policy above still apply.
  A background return needs a fresh connection and verified snapshot before
  mission frames are adopted. The background
  presence bridge has no recorder-key access and does not decrypt/adopt mission
  frames; its bounded structural snapshot drain is scratch only.
- **Peer device keys are authenticated; human identity remains out-of-band.** Each
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
  snapshot**: whenever the app is not active (from `.inactive`, before iOS takes
  the snapshot) an opaque cover is shown in its own window above everything
  TacMap has on screen, including open sheets, the share sheet, file pickers and
  alerts, so a join code, waypoint list or export preview stays out of the
  thumbnail too. With the toggle off and App Lock on, the lock view only goes up
  once the app is in the background. A deliberate screenshot of the live app is
  still possible.
- Online lookups off; enabling them sends provider queries described in §5.
- Online basemaps off; enabling them exposes viewed tile coordinates as described in §5.
- Mission data encrypted at rest with a device-bound key.
- Sync off until you join a room.
- TacMap Chat unavailable until an upgraded v3 relay acknowledges the current
  session's signed ephemeral chat key. Messages are live-only: there is no
  offline mailbox, and **Routed** does not mean delivered or read.
  Initial local key establishment retains peer keys already verified against
  the current socket's authenticated hello/session. Full secret teardown
  clears both the actual peer-key map and any staged recipient publication,
  so a completed inbound batch cannot advertise an endpoint after its keys
  have been discarded. Replacement hello and connection teardown still
  invalidate the corresponding peer authority.
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
  bounded inbound queue has admitted the message, waiting when that queue is full. WebSocket
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
- Debug-only verification hooks: `TACMAP_DEBUG_IMPORT_PDF`, `TACMAP_DEBUG_CAMERA`, `TACMAP_DEBUG_CALIBRATION_POINT`,
  `TACMAP_DEBUG_GRID` and (Android) `TACMAP_DEBUG_OPSEC_ALLOW_SCREENSHOTS` are read
  from the launch environment (Android: launch intent extras) only in debug builds
  (`#if DEBUG` in `ios/.../App/DebugHooks.swift`, `BuildConfig.DEBUG` in
  `android/.../app/DebugLaunchHooks.kt` + `MainActivity`) and are compiled out of
  release builds. The iOS calibration-point hook only moves the normal camera to
  a raw page coordinate; point capture, coordinate entry, fitting and persistence
  still use the normal UI. The Android screenshot switch never touches the stored OPSEC
  setting and ends with the process. They exist for on-device screenshot/alignment tests; see
  `docs/DEBUG_HOOKS.md`. iOS `TACMAP_DEBUG_DEVICE_AUDIT=1` additionally opts
  into read-only actual camera/viewport and opaque source identity/type/context
  log telemetry in `TileMapView`, plus existing completed PDF job/cell geometry
  from render workers. A lock bounds the latter to 64 jobs/8192 drawn cells per
  process; vector timing is captured before observation, so EWMA samples exclude
  audit overhead. It includes no PDF title/path/content, content hash or key
  material, changes no camera/import/calibration/render behavior or tolerance,
  and all observer code, calls and state are excluded from Release. The same
  exact opt-in additionally observes actual compositor-applied image-backed tile
  geometry, bitmap dimensions and own/ancestor/child quality on its existing main
  owner; at most 64 items and 64 changed frames are recorded per view, with an
  explicit incomplete flag above that item bound. Actual camera, opaque
  source/context ID and runtime generation bind these records; no pixels are
  read and render inputs/output/tolerance remain unchanged.
  Android's same explicit debug intent opt-in enables a bounded in-memory observer
  of completed PDF job/cell geometry and actually painted tile bounds. It is off
  by default, writes no log/file and makes no network call. Instrumentation may
  explicitly save its local snapshot. Records include viewed-area geometry and
  opaque source IDs, but no title/path/PDF content, content hashes or key material;
  64 jobs / 8192 cells plus 64 frame items are the limits, with omissions reported.
  It changes no render input, warp tolerance, cache or OPSEC preference. Vector
  observations run after EWMA sampling and raster bake observations after draw
  timing. `BuildConfig.DEBUG` and R8 remove the observer/call sites from Release.
  iOS transports admitted job/frame JSON in bounded `TASK4_CHUNK` groups:
  opaque UUID, kind, index/count/total bytes and at most 600 raw bytes per chunk.
  Each record is capped at 2 MiB/4096 chunks; oversize emits `TASK4_OMITTED`.
  Missing, conflicting or invalid chunks make evidence incomplete. Its frame
  observer joins opaque painted CGImage identity to at most 128 weak delivery
  observations per source on Main. Only an actual baked decode is labelled
  `decoded-bake`; other deliveries are `live`, and absent/deallocated provenance
  is `unproved`. These observations retain no bitmap strongly and change no
  delivery, cache, fallback or rendering behavior. All code/state/calls are
  Release-excluded.
  iOS screenshot inputs `TACMAP_UITEST_ONLINE`, `TACMAP_UITEST_OFFLINE_BASEMAP` and
  `TACMAP_UITEST_NIGHT_MODE` are also compiled only in Debug, apply transiently,
  and do not alter saved choices. Release uses saved/default OPSEC settings.
- Imported-PDF crash-loop guard: `pdf_render_guard.json` in app support (Android:
  `noBackupFilesDir`), outside backups, holds only random UUIDs (no file names or paths, which would reveal the
  AO). The imported-MBTiles open guard, `mbtiles_open_guard.json` next to it, likewise holds only library entry
  UUIDs. Rendered PDF tiles are memory-only; the explicit "Generate Offline Tiles"
  bake is the only rendered output written to disk.

Issues and disclosures welcome via the repository.

---

*This is maintained release documentation. Revalidate it against every candidate
build; planned work and unverified device-specific behaviour are not guarantees.*
