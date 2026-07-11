# TacMap Threat Model

**Status:** working draft · **Audience:** users, unit security staff, code auditors
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
and an **optional** unit sync feature that shares live presence and map objects
between devices that share a join code.

Everything except sync works with no network at all. Sync is off until a user
joins a room.

---

## 2. Critical information (what an adversary wants)

If TacMap is holding real data, the sensitive items are:

- **Positions** — your live position, and peers' positions, headings, speeds.
- **Unit identity** — callsigns, affiliation, echelon, function, HQ status.
- **Scheme of manoeuvre** — waypoints, routes, tracks, drawn control measures.
- **Area of interest** — the ground you are looking at, even without any markup.
- **Association and tempo** — who is working with whom, and when activity spikes.

The last two matter even when the first three are encrypted. Keep reading.

---

## 3. Trust boundaries

TacMap treats the following as **untrusted** once data crosses into them:

| Boundary | Trusted? | Why it matters |
|---|---|---|
| Your device | Trusted (see §7 caveats) | Holds the at-rest key, and can decrypt mission data. |
| The sync relay | **Untrusted** | Routes encrypted traffic; can see metadata. |
| Basemap / lookup providers | **Untrusted** | See the coordinates you request. |
| The network path (ISP, Wi-Fi, carrier) | **Untrusted** | Sees who you talk to and when. |
| Other members of your sync room | Trusted by you | They hold the room key and see everything you share. |

The design goal is that **only your device and your own room members** ever see
critical information in cleartext. Everything else on this list sees ciphertext
or coordinate requests, never unit data.

---

## 4. What the sync relay can and cannot see

This is the core statement. Read it before trusting sync.

**One-paragraph version.** When you join a sync room, your device turns the
human join code (e.g. `bravo-tonight`) into three separate values using a slow
password-stretch (PBKDF2-HMAC-SHA256, 210,000 iterations). One value is a
routing ID the relay uses to connect you to your room. A second is the
encryption key, which **never leaves your device**. A third is a write token
that only travels inside the connection handshake. Every map object and every
presence update is sealed with AES-256-GCM using the encryption key before it is
sent. The relay only ever forwards sealed blobs. It cannot reverse the routing
ID straight back to the key. But the routing ID and the key are both derived
from the same join code, so the protection is only ever as strong as that code:
a weak or human-memorable code can be guessed offline (see *Your join code is
the whole ballgame*, below). With a strong, generated code the relay can route
your traffic but cannot read it.

**What the relay CAN see:**

- The **routing room ID** (a 256-bit value derived from your join code; not
  directly reversible, but it doubles as an offline *verifier* for guessed
  codes - see *Your join code is the whole ballgame*, below).
- The **IP addresses** of connected devices, and therefore approximate
  geographic origin.
- **Traffic metadata:** when devices connect, how often they send, message sizes
  and timing.
- That a set of devices **belong to the same room** (co-membership).

**What the relay CANNOT see:**

- Positions, headings, speeds.
- Callsigns, affiliation, echelon, function, HQ status.
- Waypoints, routes, tracks, drawn control measures.
- The join code, or anything typed by users.
- The contents of any synced object. All of it is AES-256-GCM ciphertext.

**Why a hostile relay still cannot cheat.** Each sealed object binds its own
routing metadata (object ID, version, kind) into the encryption as associated
data. A relay that tries to replay an object under a different ID, or move it
between rooms, produces an authentication failure and the client rejects it. A
leaked routing ID alone cannot write to a room either, because writing requires
the separate write token.

**The honest limit.** Content is protected. **Metadata is not.** A relay
operator, or anyone who compromises or coerces the relay or its host, can learn
that a group of IPs form a unit, roughly where they are, and when they are
active. That is enough to infer association and operational tempo. See §7.

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

The relay is auditable: it only ever handles sealed blobs plus routing IDs. You
do not have to trust ours. **You can self-host it** (see §8).

Crypto reference for auditors: `SyncCrypto.kt` / `SyncCrypto.swift` and their
test suites. Android and iOS produce byte-identical wire format
(`iv(12) || ciphertext || tag(16)`).

---

## 5. Network egress table

Every outbound connection TacMap can make, what triggers it, what the far end
learns, and its default state. If a row is off by default, TacMap makes no such
request until you opt in.

| Endpoint | Purpose | Triggered by | What the provider learns | Default | Mitigation |
|---|---|---|---|---|---|
| `ibasemaps-api.arcgis.com` (Esri World Imagery) | Satellite basemap tiles (the default style) | Viewing the map with online basemaps enabled | Your IP + the tile coordinates/zoom you view = your area of interest, over time | **Off** (online basemaps gate) | Leave the gate off; use offline packs, see §6 |
| `static-map-tiles-api.arcgis.com` (Esri) | Topographic + OSM-street basemap tiles (licensed) | Selecting those styles with online basemaps enabled | Your IP + requested tile coordinates = your AO | **Off** (online basemaps gate) | Leave the gate off; use offline packs |
| `a.tile.opentopomap.org` | OpenTopoMap community topo tiles (the one keyless style) | Selecting the OSM-Topo style with online basemaps enabled | Your IP + requested tile coordinates = your AO | **Off** (online basemaps gate) | Leave the gate off; use offline packs |
| `api.open-meteo.com/v1/forecast` | Weather lookup | Opening the weather dialog | Your IP + the exact coordinates queried | **Off** (online lookups gate) | Leave online lookups off |
| `api.open-meteo.com/v1/elevation` | Elevation + terrain heatmap | Elevation/terrain features | Your IP + the exact coordinates queried | **Off** (online lookups gate) | Leave online lookups off |
| Place-name search — iOS `MKLocalSearch` (Apple), Android `Geocoder` (Google/OEM on GMS devices) | Turning a typed place name into a coordinate | Typing 2+ characters in Search | Your IP + the search string, which can itself reveal your AO (searching a FOB/village name) | **Off** (online lookups gate) | Leave online lookups off; navigate by MGRS/grid instead |
| Sync relay (default: `tacmap-sync.<...>.workers.dev`) | Encrypted unit sync transport | Joining a sync room | Ciphertext + routing ID + your IP + traffic timing (see §4) | Off until you join a room | Self-host the relay; see §8 |
| `play.google.com/redeem` / `apps.apple.com/redeem` | Voucher / licence redemption | You tapping "redeem" | Standard store request; no map or unit data | User-initiated only | n/a |

The three basemap rows apply to **both platforms** — iOS and Android draw the
same Esri/OSM raster tiles, gated off by default. There is **no Apple-Maps row
and no Google-Maps row any more**: both platforms render the map themselves now
(§6). On iOS, Apple's `geod` daemon is never invoked and fetches nothing (measured
zero, §6). On Android, the Google Maps SDK has been removed from the app entirely —
there is no map SDK left to check in with Google on launch (§6).

**Read this table as the whole story.** If an endpoint is not listed here, the
app does not contact it. *We* add no analytics SDK, no crash telemetry, no ad
network; our own crash reports are written to local storage only and shared by
you manually. **Neither platform links a map SDK any more** — iOS dropped MapKit
and Android dropped the Google Maps SDK, so there is no longer any third-party
map-engine telemetry on either one. The only Google dependency left on Android is
Play Billing, which binds to the Play Store service and exchanges data only on a
user-initiated purchase or restore (no map or unit data, ever); see §6.

---

## 6. The area-of-interest problem (basemaps and lookups)

Even with sync fully off, requesting online map tiles or online
weather/elevation tells the provider which ground you care about. Panning to a
grid square fetches tiles for that square from your IP. A provider, or anyone
with access to its logs, can reconstruct your area of interest and how it moves
over time. This is the same class of exposure as the 2018 fitness-app heatmap
incident.

TacMap's controls:

- **Online lookups (weather, elevation, terrain) are off by default.** They stay
  off until you explicitly enable them.
- **Online basemaps are off by default**, behind their own OPSEC toggle
  (Settings → Privacy & OPSEC → Online basemap tiles). While off, no Esri and no
  OpenTopoMap tile is ever requested: the app does not construct the tile
  provider at all, so there is no URL to fetch.
- **A persistent red banner** sits across the top of the map whenever an online
  tile source is active, so you never discover it by accident.
- **Offline basemap packs** render with no tile requests of our own. This is the
  recommended posture for any real operation: pre-stage an MBTiles pack or a
  GeoPDF sheet before you deploy, then leave both online gates off in the field.

### The two platforms, both now closed

Both platforms draw the same Esri/OSM raster tiles, gate them off by default, and
now render through the **same in-app tile renderer** — no Apple MapKit, no Google
Maps SDK. Neither platform links a map engine that can phone a map provider on
launch. The map is a Compose/Canvas renderer on Android and a Metal/CoreGraphics
renderer on iOS, both of which draw only the raster source you chose: an Esri/OSM
online style when the basemap gate is on, an offline pack/GeoPDF when you've
imported one, or nothing when both are off.

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
tile request of any kind and a stationary map settles to zero; with it **on**,
tiles go to Esri/OpenTopoMap (your choice, behind the persistent red banner). The
one honest caveat that remains is not ours: on a device with Google Play Services
installed, the OS's own GMS processes have their own ambient network chatter, and
Play Billing (still linked, for the paid unlock) binds to the Play Store service.
Neither is TacMap requesting anything, and neither carries map or unit data. For a
guaranteed-dark launch, airplane mode still removes every vector at once.

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
is no MapKit view in the tree, so `geod` is never asked for a tile.

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
which sends the query you type to Apple's servers - but only if you enable
online lookups and use the search box. See §5.) What the online-basemaps gate now buys
you is the ordinary thing it says: with it off, the app makes no basemap tile
request of any kind, and an imported offline pack or GeoPDF genuinely hides your
AO — there is no Apple fetch underneath it any more.

The remaining ambient exposure is now symmetric across both platforms and comes
only from the providers you can still choose to use: turning the basemap gate
**on** sends your tile coordinates to Esri/OpenTopoMap (your choice, with a
persistent red banner while it's active). Neither platform links a map SDK, so
there is no map-engine launch check-in on either one.

Rule of thumb: **if you can see the internet, the internet can see your AO.**
Pre-stage offline maps before you need them; the gate and the in-app renderer now
make "offline pack loaded, radio on, nothing leaks" true on **both** platforms.

---

## 7. What TacMap does NOT protect you from

Stated plainly, because a tool that hides its limits cannot be trusted.

- **Relay traffic analysis.** Content is encrypted; the fact and pattern of
  communication is not. Co-membership, approximate location by IP, and activity
  tempo are inferable at the relay. Self-hosting moves this trust to you but does
  not remove it. A LAN/mesh transport removes the internet vector entirely
  [PLANNED].
- **A coerced relay can suppress or roll back, not just observe.** AEAD stops the
  relay forging a blob, replaying it under a different id, or moving it between
  rooms (§4), and **every mutation is now signed** on top: presence carries a
  per-device signature over a monotonic timestamp, and object writes/deletes carry
  a per-device signature bound to the object id + version (a delete is a *sealed,
  signed proof*, so a relay with no room key can't forge one at all). So a relay
  can't roll back a peer's live position, forge a deletion, or replay an older
  object version to a client that already tracks that object. What can't be
  prevented is *omission and join-time rollback*: the relay can silently drop an
  update (you never see the new enemy contact), or serve a freshly joining /
  reinstalled client an older-but-genuinely-signed object snapshot, because that
  client holds no prior version to detect the rollback against. Treat the shared
  picture as advisory and confirm critical changes out-of-band. A signed monotonic
  room sequence, so late joiners can detect a truncated/rolled-back snapshot, is
  the remaining hardening.
- **Area-of-interest leakage via online basemaps/lookups.** See §6. If you turn
  the online-basemaps or online-lookups gate on, the tile/query coordinates go to
  the provider (Esri/OpenTopoMap/Open-Meteo) from your IP. That is inherent to
  using an online map and is off by default. (Neither platform links a map SDK
  any more: iOS no longer touches Apple's `geod`, and Android no longer links the
  Google Maps SDK — both covered in §6.)
- **Device compromise or capture.** Mission data (waypoints, drawings, track
  logs, PDF calibration) is **encrypted at rest** with AES-256-GCM. The key never
  exists in plaintext on disk: on Android it is wrapped by a non-exportable
  Android Keystore key, on iOS it lives in the Keychain
  (`AfterFirstUnlockThisDeviceOnly`, never synced to iCloud, never in a backup).
  What that does and does not defeat depends on one setting:

  - **Default (device-bound key).** A forensic extraction of the *filesystem* —
    a disk image, `adb pull`, a backup, a **powered-off or before-first-unlock**
    seized handset, a device you binned or sent for repair — recovers
    **ciphertext only**. Two caveats. A device seized **after** first unlock and
    still powered on holds the key's class key resident, so a current forensic
    exploit could lift the data key - effectively the code-execution case below.
    And the keystore releases the key to this app automatically, so an attacker
    who achieves **code execution as the app on a rooted or jailbroken device**
    can simply ask the keystore to decrypt, and recovers everything.
    Non-exportable means the key cannot be *copied*, not that it cannot be *used*.
    Auth-bound mode (below) is the answer to both.

  - **"Require unlock to decrypt mission data" (auth-bound key), opt-in.** The
    key is regenerated with a hardware user-authentication requirement, so the
    TEE (Android) or Secure Enclave (iOS) refuses to release it without a fresh
    device credential or biometric. Root or jailbreak alone recovers nothing.
    The costs are real: after the process dies, nothing reads or writes mission
    data until you authenticate, **including background track recording**; and on
    Android, removing your device lockscreen destroys the key and the data with
    it.

  A note against overclaiming: on iOS the key is a raw AES key, so it is **not**
  "in the Secure Enclave" — the SEP only holds P-256 keys. It is in the Keychain,
  whose class keys the SEP wraps and holds. That is a genuine hardware guarantee,
  and it is a different sentence.

  The optional in-app PIN lock remains a **UI deterrent for a borrowed device,
  not encryption**, and is independent of all of the above.

  Overwritten plaintext from a pre-encryption build is replaced in place by an
  atomic rename. On flash storage the old blocks may survive until wear-levelling
  reclaims them, protected only by the platform's own full-disk encryption.

- **Data that is still plaintext on disk.** Imported basemaps are not sealed under
  the app key: MBTiles packs and imported PDF/GeoPDF sheets sit in app-private
  storage (on iOS, in a `FileProtection`-covered directory and no longer exposed
  through Files/Finder file sharing) but as their original bytes. They reveal your
  area of interest to anyone who extracts them at the filesystem level. Only the
  *calibration sidecar* (which sheet, what ground it covers, the fitted affine) is
  sealed. Encrypting the packs themselves would mean SQLCipher and streaming
  decryption; it is not done.
- **Crash logs are local and plaintext.** An uncaught-exception / fatal-signal
  handler writes a short stack trace to app-private storage (never transmitted -
  you export it yourself from About). It is not sealed: the fatal-signal path has
  to be async-signal-safe (no allocation or crypto), and doing keystore crypto
  inside a crash handler is fragile. Stack traces rarely contain coordinates but
  can carry an imported map's file name; clear it if that matters.
- **Your own room members.** Everyone with the join code sees everything shared
  in that room. Rotate codes and manage membership accordingly.
- **Peer identity: established devices are signed; brand-new ones are not.** Each
  device holds a per-device Ed25519 key and signs every **presence** update *and*
  every **object write/delete**; the first time you see a client id you pin its
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
- **Export metadata.** GPX and GeoJSON exports embed **precise per-point
  timestamps** alongside coordinates and elevation, plus the fixed string `TacMap`
  as the creator. They do **not** embed device identifiers, hardware IDs, your
  sync client ID, or callsigns. The residual exposure is the timestamps: a shared
  track file reveals exactly when you were at each point (pattern of life). Scrub
  timing before sharing a track outside your unit if that matters.
- **Authorisation.** This is not a technical control and TacMap cannot grant it.
  A well-engineered app is **not** an accredited one. Whether you are permitted
  to hold or transmit official information in this tool is a decision for your
  chain of command and your security authority, not for the app. If in doubt,
  ask before you load real data.

---

## 8. Recommended posture and self-hosting

**OPSEC-first defaults (already set):**

- Screen capture blocked (keeps live position out of screenshots and the recents
  thumbnail). This is literal on Android (`FLAG_SECURE` blocks screenshots,
  screen recording, and the recents thumbnail). On iOS there is no public API to
  block an in-app screenshot, so the toggle only covers the **app-switcher
  snapshot** (an opaque cover while the app is backgrounded) - a deliberate
  screenshot of the live map is still possible.
- Online lookups off.
- Online basemaps off.
- Mission data encrypted at rest with a device-bound key.
- Sync off until you join a room.

**For real operations, additionally:**

- Pre-stage **offline basemap packs**; do not use online tiles. On iOS this is
  not sufficient on its own, see §6: fly the device in **airplane mode**, or put
  it on a network you control, if your AO must not reach Apple.
- Turn on **"Require unlock to decrypt mission data"** if device capture is a
  more realistic threat to you than a track cut short by a reboot. Read the
  trade-off in §7 first, and on Android do not remove your lockscreen afterwards.
- **Self-host the sync relay** so no traffic transits an account you do not
  control. The relay is a single Cloudflare Worker + Durable Object
  (`sync/src/index.ts`); deploy it to your own account and point the app at it in
  Settings → relay URL. It only ever forwards sealed blobs by routing ID, so a
  minimal deployment is enough.
- Use generated join codes and rotate them per activity.
- Treat a lost **unlocked** device as a compromise of all mission data on it. A
  lost **locked** device, with the default device-bound key, yields ciphertext.

---

## 9. For auditors

- Sync crypto: `android/.../sync/SyncCrypto.kt`, `ios/.../Sync/SyncCrypto.swift`,
  and the matching `SyncCryptoTest` suites.
- At-rest crypto: `util/SealedEnvelope.{kt,swift}` (AES-256-GCM, wire format
  `magic(7) || iv(12) || ct || tag(16)`, store label bound as AEAD associated
  data) and `util/DataKey.{kt,swift}` (key custody + the auth-bound toggle).
  Both `SealedEnvelopeTest` suites open the *same* fixture blobs, generated by a
  third implementation, so Android and iOS are pinned to one wire format rather
  than to each other.
- Egress: every network call the *app* makes is in the services listed in §5.
  There is no analytics, telemetry, or ad SDK; verify by searching the source for
  outbound URLs. (Historically iOS leaked map tiles through Apple's `geod` daemon,
  which no source search would reveal; §6 documents how that was closed - there
  is no MapKit view left, so `geod` is never asked for a tile.)
- OPSEC defaults: `settings/OpsecSettings.kt` and the iOS equivalent.
- Crash handling: `CrashReporter` (local file only).

Issues and disclosures welcome via the repository.

---

*This is a living document. Items marked TODO/VERIFY are not yet substantiated
in this draft and must not be cited as guarantees until confirmed against the
code.*
