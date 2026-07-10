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
sent. The relay only ever forwards sealed blobs. It has no way to derive the key
from the routing ID, because both come out of the same expensive one-way
derivation. So the relay can route your traffic but cannot read it.

**What the relay CAN see:**

- The **routing room ID** (a 256-bit opaque value; not your join code, and not
  reversible to it).
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
| Google Maps SDK provisioning (**Android only**) | SDK config / telemetry check-in | Launching the app while the Google map view is still the host on Android, **regardless of the basemap gate** | Your IP + that a Google-Maps app launched. **Not** the coordinates you view | On on Android (can't be disabled while the SDK is linked); **absent on iOS** | iOS is already off the SDK. Removed on Android once its renderer lands [PLANNED]. See §6 |
| `api.open-meteo.com/v1/forecast` | Weather lookup | Opening the weather dialog | Your IP + the exact coordinates queried | **Off** (online lookups gate) | Leave online lookups off |
| `api.open-meteo.com/v1/elevation` | Elevation + terrain heatmap | Elevation/terrain features | Your IP + the exact coordinates queried | **Off** (online lookups gate) | Leave online lookups off |
| Sync relay (default: `tacmap-sync.<...>.workers.dev`) | Encrypted unit sync transport | Joining a sync room | Ciphertext + routing ID + your IP + traffic timing (see §4) | Off until you join a room | Self-host the relay; see §8 |
| `play.google.com/redeem` / `apps.apple.com/redeem` | Voucher / licence redemption | You tapping "redeem" | Standard store request; no map or unit data | User-initiated only | n/a |

The three basemap rows apply to **both platforms** — iOS and Android draw the
same Esri/OSM raster tiles, gated off by default. There is **no Apple-Maps row
any more**: iOS renders the map itself now (§6), so Apple's `geod` daemon is
never invoked and fetches nothing (measured zero, §6).

**Read this table as the whole story.** If an endpoint is not listed here, the
app does not contact it. *We* add no analytics SDK, no crash telemetry, no ad
network; our own crash reports are written to local storage only and shared by
you manually. The one remaining piece of third-party telemetry is the Google
Maps SDK provisioning check-in **on Android only**, which is Google's, not ours,
and which we cannot disable while their SDK is linked. iOS no longer links any
map SDK. That row is auditable only in the sense that you can see it in a packet
capture, not in our source, because it isn't in our source - which is itself the
reason Android is following iOS off that SDK [PLANNED].

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
  recommended posture for any real operation. [TODO: link the offline pack
  build/import guide here.]

### The two platforms, measured

Both platforms draw the same Esri/OSM raster tiles and gate them off by default.
The difference is the map engine underneath, and that difference is measured on
each. **iOS is now fully closed** (in-app renderer, no Apple `geod`, zero Apple
egress — see "The iOS side, now closed" below). **Android's tile gate is complete
but its Google map host still phones home on launch** — detail here:

On Android, with online basemaps off the Google map host uses `MapType.NONE`,
which fetches no basemap tiles (the Esri/OSM raster overlay is simply not built).
On a Pixel emulator (API 36), 45 seconds on the map from a fresh launch used:

| Basemap gate | App network |
|---|---|
| Off (`MapType.NONE`, no raster overlay) | ~280 KB cold / ~24 KB warm, then **0** at idle |
| On (Esri/OSM raster overlay) | ~1.6 MB (the ~1.3 MB difference is tiles) |

So enabling the basemap adds ~1.3 MB of tiles; with the gate off that traffic
simply does not happen, and a stationary map settles to zero.

The honest caveat: **off is not silent on Android.** That ~280 KB (cold) / ~24 KB
(warm) is the Google Maps SDK itself phoning home on launch for provisioning and
telemetry - it happens whenever the Google map view is mounted, whatever the gate
says. It carries your IP and the fact that a Google-Maps app started. It does
**not** carry the coordinates you are looking at (that's what the tile gate
stops). The only way to remove it is to stop linking Google's SDK. iOS has now
done exactly that (below); Android is following, porting the same in-app renderer
to drop the SDK [PLANNED]. Until that lands, for a fully dark launch on Android,
use airplane mode.

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
`ibasemaps-api.arcgis.com` (the Esri basemap you turned on), with **zero contact
to any Apple map host** (`*.ls.apple.com`, `gspe*`, `cdn.apple-mapkit`). So on
iOS the AO no longer leaks to Apple at all. What the online-basemaps gate now buys
you is the ordinary thing it says: with it off, the app makes no basemap tile
request of any kind, and an imported offline pack or GeoPDF genuinely hides your
AO — there is no Apple fetch underneath it any more.

The remaining ambient exposure is symmetric across the two providers you can
still choose to use: turning the basemap gate **on** sends your tile coordinates
to Esri/OpenTopoMap (your choice, with a persistent red banner while it's active),
and on **Android only** the Google Maps SDK still phones home ~280 KB on launch
until its renderer lands (above). iOS has no such SDK left.

Rule of thumb: **if you can see the internet, the internet can see your AO.**
Pre-stage offline maps before you need them; the gate and the in-app renderer now
make "offline pack loaded, radio on, nothing leaks" true on iOS, and on Android
for tiles (the SDK launch check-in aside).

---

## 7. What TacMap does NOT protect you from

Stated plainly, because a tool that hides its limits cannot be trusted.

- **Relay traffic analysis.** Content is encrypted; the fact and pattern of
  communication is not. Co-membership, approximate location by IP, and activity
  tempo are inferable at the relay. Self-hosting moves this trust to you but does
  not remove it. A LAN/mesh transport removes the internet vector entirely
  [PLANNED].
- **Area-of-interest leakage via online basemaps/lookups.** See §6. If you turn
  the online-basemaps or online-lookups gate on, the tile/query coordinates go to
  the provider (Esri/OpenTopoMap/Open-Meteo) from your IP. That is inherent to
  using an online map and is off by default. (iOS no longer leaks to Apple's
  `geod` at all, and on Android the Google SDK launch check-in carries no
  coordinates — both covered in §6.)
- **Device compromise or capture.** Mission data (waypoints, drawings, track
  logs, PDF calibration) is **encrypted at rest** with AES-256-GCM. The key never
  exists in plaintext on disk: on Android it is wrapped by a non-exportable
  Android Keystore key, on iOS it lives in the Keychain
  (`AfterFirstUnlockThisDeviceOnly`, never synced to iCloud, never in a backup).
  What that does and does not defeat depends on one setting:

  - **Default (device-bound key).** A forensic extraction of the *filesystem* —
    a disk image, `adb pull`, a backup, a seized locked handset, a device you
    binned or sent for repair — recovers **ciphertext only**. But the keystore
    releases the key to this app automatically, so an attacker who achieves
    **code execution as the app on a rooted or jailbroken device** can simply ask
    the keystore to decrypt, and recovers everything. Non-exportable means the
    key cannot be *copied*, not that it cannot be *used*.

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

- **Data that is still plaintext on disk.** Imported basemaps are not encrypted:
  MBTiles packs and imported PDF/GeoPDF sheets sit in app-private storage as they
  were imported. They reveal your area of interest to anyone who extracts them.
  Only the *calibration sidecar* (which sheet, what ground it covers, the fitted
  affine) is sealed. Encrypting the packs themselves would mean SQLCipher and
  streaming decryption; it is not done.
- **Your own room members.** Everyone with the join code sees everything shared
  in that room. Rotate codes and manage membership accordingly.
- **A weak join code.** The 210k-iteration stretch raises the cost of guessing,
  but a short or predictable code is still guessable against retained ciphertext.
  Use the in-app generator; do not invent your own.
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
  thumbnail).
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
  control. The relay only forwards sealed blobs by routing ID, so a minimal
  self-hosted deployment is enough. Point the app at it in
  Settings → relay URL. [TODO: link self-host deploy guide.]
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
  outbound URLs. Note the iOS caveat in §6: `geod` makes requests on the app's
  behalf that no source search will reveal, because they are not in our binary.
- OPSEC defaults: `settings/OpsecSettings.kt` and the iOS equivalent.
- Crash handling: `CrashReporter` (local file only).

Issues and disclosures welcome via the repository.

---

*This is a living document. Items marked TODO/VERIFY are not yet substantiated
in this draft and must not be cited as guarantees until confirmed against the
code.*
