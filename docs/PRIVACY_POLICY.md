# TacticalMaps — Privacy Policy

*Last updated: 8 July 2026*  
*Effective: at first installation of the app*

This Privacy Policy describes how the TacticalMaps mobile application
(“TacticalMaps”, “the App”, “we”, “our”, “us”) handles your information.
It covers both the iOS and Android editions, published by Christian Brooker.

We believe a navigation tool you take into the field should not phone home,
build a profile of you, or feed an ad network. **TacticalMaps does not
collect, store on our servers, sell, share, or rent your personal data —
at all.** Everything you make stays on your device. Below we tell you exactly
what that means in practice.

---

## 1. Information we do NOT collect

We want to be explicit, because the App Store “Nutrition Label” format makes
this confusing for users. TacticalMaps **does not**:

- Require you to create an account, log in, or supply an email
- Use any third-party analytics SDK (no Google Analytics, no Firebase
  Analytics, no Mixpanel, no Amplitude, no Segment)
- Use any advertising SDK or identifier (no IDFA, no AdMob, no Meta SDK)
- Use any crash-reporting SDK that uploads telemetry (no Sentry, no
  Crashlytics; we rely only on Apple’s and Google’s built-in OS crash
  reporting, which is governed by their own privacy policies and which
  you can disable in your device settings)
- Collect, store, or transmit your historical location, route, or movement
  (the optional Unit Sync location sharing described in §3b is real-time,
  ephemeral, end-to-end encrypted, and user-initiated — see that section)
- Track which buttons you tap, how long you use the app, or anything else
  about your behaviour
- Read your contacts, photos (other than via the system Files / Share
  pickers you explicitly invoke), microphone, camera, calendar, motion
  sensors, Bluetooth, or any other sensor not listed below

---

## 2. Information stored only on your device

| Data | Why | Where it lives |
|---|---|---|
| **Your live GPS position** (when you grant Location permission) | To show your location on the map, compute the live MGRS readout, anchor the camera, and time-stamp drawings/waypoints | RAM only — never written to disk by us |
| **Waypoints** you create | Persist between launches | `Application Support/waypoints.json` inside the App’s sandbox |
| **Drawings** you create (lines, polygons, points) and their style | Persist between launches | `Application Support/drawings.json` inside the App’s sandbox |
| **PDF maps** you import | Render the map, parse its GeoPDF metadata, apply fiduciary calibration | `Documents/<filename>.pdf` inside the App’s sandbox (exposed to the Files app on iOS so you can manage them) |
| **Fiduciary calibration points** | Re-align imported PDFs to the satellite basemap | RAM only in v1.0; future versions may persist them per PDF |
| **Layer visibility toggles, last camera position** | UX polish so the app reopens where you left it | iOS `UserDefaults` (sandboxed) |

Nothing in this table is uploaded anywhere by us. It stays on the device until
you delete the app (which removes it permanently, per Apple/Google behaviour),
or until you export it yourself via the Share Sheet.

---

## 3. Network requests we DO make

The App makes outbound HTTPS requests only in the following narrow cases:

| When | To | What we send | Why |
|---|---|---|---|
| Every time the map camera settles on a new location (debounced to once every 400ms) | **`api.open-meteo.com`** | The latitude/longitude of the map centre, rounded to 4 decimal places (≈11 m) | To fetch terrain elevation for the crosshair readout |
| Every time you type in the **Search** field (debounced to 350ms after you stop typing) | Apple’s **MapKit Local Search** service | Your search query (e.g. “coffee”, “Holsworthy”) and your current camera region (to bias results) | To return matching place names and addresses |
| Every time MapKit needs satellite imagery for a tile you are viewing | Apple’s **Maps** service | The tile coordinate | To render the satellite basemap |

We do not include any account identifier, device identifier, advertising ID,
or your historical location with these requests. The request is no more
identifying than typing the same query into the iOS or Android Maps app would
be.

**Apple’s privacy policy for MapKit:**
<https://www.apple.com/legal/internet-services/maps/terms-en.html>

**Open-Meteo’s privacy policy:**
<https://open-meteo.com/en/privacy>

**Google’s privacy policy (Android edition uses Google Maps):**
<https://policies.google.com/privacy>

---

## 3b. Unit Sync — optional real-time team sharing

If you choose to use the **Unit Sync** feature, the App opens a WebSocket
connection to our relay server at
`tacmap-sync.christianbrooker.workers.dev` (hosted on Cloudflare Workers).
This is **entirely opt-in** — the feature is off by default and no
connection is made unless you enter a join code.

### What is transmitted

| Data | Encrypted? | Stored on server? |
|---|---|---|
| **Waypoints, drawings, and layers** you create while syncing | Yes — AES-256-GCM, end-to-end encrypted with a key derived from the join code. The relay **never** holds the key and **cannot** decrypt the content. | Ciphertext only, per-room, deleted when all devices disconnect or after 7 days of inactivity |
| **Your GPS position, callsign, unit type, heading, speed** (only if you enable "Share my location") | Yes — same AES-256-GCM encryption. The relay sees only opaque ciphertext. | **Not stored** — relayed in-memory to connected peers and discarded |
| **A random device identifier** (UUID, not linked to your Apple/Google account) | No (used for message routing) | In-memory only while the socket is open |

### What is NOT transmitted

- Your Apple ID, Google account, email, phone number, or any real-world
  identity — there are no accounts
- Your location history — presence is a 5-second heartbeat, not a track;
  the relay does not log or store it
- Any data from other apps on your device

### End-to-end encryption

The relay is **E2E-blind by design**. The encryption key is derived
on-device from the unit join code via PBKDF2 (210,000 iterations) and
never leaves the device. The relay cannot read your waypoints, drawings,
location, callsign, or unit composition — it only routes opaque
ciphertext between devices that share the same join code.

### How to disable

You can stop sharing at any time:

- **Location sharing**: toggle "Share my location" off in the Unit Sync
  dialog. Your position is no longer broadcast; existing peers are
  notified you have left.
- **All sync**: tap "Leave room" to disconnect entirely. No further data
  is sent or received.
- **Never used**: if you never enter a join code, no sync connection is
  ever made and none of the above applies.

---

## 4. Permissions we request

### iOS

- **Location — While Using the App** (`NSLocationWhenInUseUsageDescription`).
  Required to display your live position, the live MGRS readout, and
  (optionally) to broadcast your position to your unit via Unit Sync. You
  can decline; the App still works but the “Your Location” mode shows
  nothing and live presence sharing is unavailable.
- **Location — Always** (`NSLocationAlwaysAndWhenInUseUsageDescription`)
  is declared so future versions can offer optional background track logging.
  **v1.0 does not use background location.** Even if you grant Always
  permission today, the App will not record or transmit your location in
  the background.
- **Files / Documents picker.** Used only when you import a PDF map.
- **Share Sheet.** Used when you export GeoJSON or share data.

### Android

- **`ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`**. Same reasons as iOS
  Location — live position display, MGRS readout, and optional Unit Sync
  presence sharing.
- **`INTERNET`**. To make the network requests listed in §3 and §3b above.

You can revoke any permission at any time via your device’s Settings.

---

## 5. Data exports you initiate

When you tap **Export GeoJSON** or **Export All Data**, the App serialises
your waypoints and drawings into a `.geojson` file in the App’s temporary
directory and presents it to the iOS / Android Share Sheet. **We do not
upload that file.** It goes only to whichever destination you choose
(Files, Mail, AirDrop, Messages, etc.). The receiving app is governed by
its own privacy policy.

---

## 6. Children

TacticalMaps is not directed at children under 13 and does not knowingly
collect any information from them. If you believe a child has used the App
in a way that requires deletion of data, please contact us at the address
below — although note that we hold no server-side data to delete.

---

## 7. Your rights (GDPR / CCPA / similar)

Because we do not collect, process, or store any personal data on our
servers, the standard data-subject rights (access, rectification, erasure,
portability, restriction, objection) have no data to attach to. Everything
you create lives on your device under your direct control and can be
removed by deleting the App.

If you believe this is incorrect for your jurisdiction, contact us and we
will respond within 30 days.

---

## 8. Changes to this policy

If we change this policy, we will:

- Update the **Last updated** date at the top
- Bump the App’s version number
- Publish the new policy at the same public URL the App links to

We will not silently broaden data collection.

---

## 9. Contact

For any privacy question, complaint, or request:

- **Email:** christianbrooker@gmail.com
- **GitHub issues:** <https://github.com/JediBrooker/TacticalMaps/issues>

---

## 10. Open source acknowledgement

TacticalMaps is open source under the MIT License (see `LICENSE` in the
repository). The full source is available at
<https://github.com/JediBrooker/TacticalMaps>. You can audit every network
call, every persisted file, and every permission for yourself.
