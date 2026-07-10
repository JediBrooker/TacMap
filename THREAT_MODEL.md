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
| Your device | Trusted (see §7 caveats) | Holds cleartext data at rest. |
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
| Apple Maps (iOS) / Google Maps SDK (Android) | Default online basemap tiles | Viewing the map on the online default basemap | Your IP + the coordinates/zoom you view = your area of interest, over time | **On** when using the online default basemap | Use offline basemap packs; see §6 |
| `server.arcgisonline.com` (Esri World Imagery) | Optional satellite raster basemap | Selecting the Esri imagery layer online | Your IP + requested tile coordinates = your AO | Off unless selected | Pre-cache / offline packs |
| `tile.opentopomap.org` | Optional topographic raster basemap | Selecting the OpenTopoMap layer online | Your IP + requested tile coordinates = your AO | Off unless selected | Pre-cache / offline packs |
| `api.open-meteo.com/v1/forecast` | Weather lookup | Opening the weather dialog | Your IP + the exact coordinates queried | **Off** (online lookups gate) | Leave online lookups off |
| `api.open-meteo.com/v1/elevation` | Elevation + terrain heatmap | Elevation/terrain features | Your IP + the exact coordinates queried | **Off** (online lookups gate) | Leave online lookups off |
| Sync relay (default: `tacmap-sync.<...>.workers.dev`) | Encrypted unit sync transport | Joining a sync room | Ciphertext + routing ID + your IP + traffic timing (see §4) | Off until you join a room | Self-host the relay; see §8 |
| `play.google.com/redeem` / `apps.apple.com/redeem` | Voucher / licence redemption | You tapping "redeem" | Standard store request; no map or unit data | User-initiated only | n/a |

**Read this table as the whole story.** If an endpoint is not listed here, the
app does not contact it. There is no analytics SDK, no crash telemetry, no ad
network. Crash reports are written to local storage only and shared by you
manually.

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
- **Offline basemap packs** let you operate with no tile requests at all. This is
  the recommended posture for any real operation. [TODO: link the offline pack
  build/import guide here.]
- [PLANNED] Online basemaps gated behind the same explicit OPSEC toggle as
  lookups, with a persistent in-map warning whenever online tiles are active.

Rule of thumb: **if you can see the internet, the internet can see your AO.**
Pre-stage offline maps before you need them.

---

## 7. What TacMap does NOT protect you from

Stated plainly, because a tool that hides its limits cannot be trusted.

- **Relay traffic analysis.** Content is encrypted; the fact and pattern of
  communication is not. Co-membership, approximate location by IP, and activity
  tempo are inferable at the relay. Self-hosting moves this trust to you but does
  not remove it. A LAN/mesh transport removes the internet vector entirely
  [PLANNED].
- **Online basemap AO leakage.** See §6. Online tiles reveal your area of
  interest regardless of sync.
- **Device compromise or capture.** Mission data (waypoints, drawings, track
  logs) is stored as **plaintext JSON in the app's private storage** (`filesDir`).
  TacMap adds **no application-level encryption** to it. At rest it is protected
  only by (a) OS app-sandboxing, (b) the platform's own file-based / full-disk
  encryption, which protects the data while the device is **locked** on any modern
  iOS/Android device, and (c) exclusion from cloud and ADB backups
  (`allowBackup=false`, `fullBackupContent=false`). The optional in-app PIN lock is
  a **UI deterrent for a borrowed device, not encryption** — the code says so
  explicitly. A forensic extraction of an **unlocked or rooted** device therefore
  recovers everything. Treat a lost unlocked device as a compromise of all data on
  it.
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
- Sync off until you join a room.

**For real operations, additionally:**

- Pre-stage **offline basemap packs**; do not use online tiles.
- **Self-host the sync relay** so no traffic transits an account you do not
  control. The relay only forwards sealed blobs by routing ID, so a minimal
  self-hosted deployment is enough. Point the app at it in
  Settings → relay URL. [TODO: link self-host deploy guide.]
- Use generated join codes and rotate them per activity.
- Treat device loss as a data-loss event until §7 at-rest items are verified.

---

## 9. For auditors

- Sync crypto: `android/.../sync/SyncCrypto.kt`, `ios/.../Sync/SyncCrypto.swift`,
  and the matching `SyncCryptoTest` suites.
- Egress: every network call is in the services listed in §5. There is no
  analytics, telemetry, or ad SDK; verify by searching the source for outbound
  URLs.
- OPSEC defaults: `settings/OpsecSettings.kt` and the iOS equivalent.
- Crash handling: `CrashReporter` (local file only).

Issues and disclosures welcome via the repository.

---

*This is a living document. Items marked TODO/VERIFY are not yet substantiated
in this draft and must not be cited as guarantees until confirmed against the
code.*
