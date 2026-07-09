# Store listing copy — App Store & Google Play

Paste-ready listing text. **Deliberately leads with the wedges against the
nearest competitor (TacticMap):** pay-once vs subscription, global/offline-
anywhere vs region-locked, open interchange (GeoJSON/KML) vs a proprietary
format, broad device support, and zero data collection. Keep this in sync with
the screenshots in `docs/store/`.

> Positioning one-liner: **"Buy once. Works offline anywhere on Earth. Exports
> to the GIS tools your unit already uses."**

---

## App name / title
- **TacMap — Offline Tactical Maps**  *(App Store: 30-char title is `TacMap`; use the subtitle for the rest)*

## App Store subtitle (≤30 chars)
- **MGRS, NATO symbols, offline**

## Google Play short description (≤80 chars)
- **Pay once. Offline MGRS maps, NATO APP-6 symbology, GeoPDF & GeoJSON/KML.**

## App Store promotional text (≤170 chars, editable without review)
- **One-time unlock — no subscription. Import any GeoPDF or scanned map, work fully offline anywhere, and export GeoJSON/KML straight into QGIS, ArcGIS or Google Earth.**

---

## Full description

Both stores render plain text only (markdown asterisks show up literally), so
the paste-ready copy below uses line breaks and `•` bullets that display
correctly. The two versions are intentionally near-identical for consistent
branding, with platform-correct tweaks (device wording, biometrics, pricing).
Both are well under the 4,000-character limit.

### Apple App Store

```
TacMap is a field-grade tactical map for your phone — buy it once and own it. No subscription. No account. Nothing collected.

Built for military, search-and-rescue, and back-country use, where the network isn't there and the map still has to be right.


WORKS OFFLINE, ANYWHERE ON EARTH
• Import any GeoPDF or scanned defence map sheet and georeference it on-device
• Calibrate any image map with a quick 3-point fix — reports its accuracy in metres so you know you can trust it
• Sideload MBTiles offline tile sets — no regional lock-in, no curated country list, no network required
• Terrain heatmap overlay — DEM-shaded elevation visualisation you can toggle on the fly

MILITARY-GRADE TOOLING
• Live MGRS readout to 10 figures, plus WGS84 lat/long, UTM, and elevation at the crosshair
• NATO mils compass (6400) with true-north marker
• NATO APP-6 symbology — build any unit from affiliation × echelon × function, add an HQ flag, and place tactical control measures and task graphics (block, breach, seize, screen, axis of advance, phase lines, boundaries, FLOT/FEBA, and more)
• Drawing tools: points, lines, areas, and freehand sketch — each with its own colour, width, and opacity, organised on named layers
• Distance, area, and bearing measurement in degrees AND NATO mils
• Full undo/redo

SHARE THE PICTURE, IN REAL TIME
• Unit Sync — share drawings and symbols live across devices over an end-to-end encrypted channel
• Live presence — see where your team is on the map in real time, with callsign, affiliation, and heading, all end-to-end encrypted
• The relay never sees your plaintext; a connected indicator on the header shows you're linked up
• Conflict detection — if a teammate edits the same object, you get a clear notification instead of a silent overwrite
• Weather + UAV flight-safety widget — live wind, gusts, visibility, and a SAFE / CAUTION / DANGER drone-flight read for the map centre

OPEN BY DESIGN — YOUR DATA STAYS YOURS
• Import and export GeoJSON (RFC 7946) — round-trips cleanly through QGIS, ArcGIS, Felt, Leaflet, and Google Earth
• Import KML / KMZ from Google Earth and ATAK exports
• Record and export GPX tracks
• Export All — one-tap backup of every symbol, drawing, waypoint, and track as a single GeoJSON file
• No proprietary format. Everything you draw exports to the tools your unit already uses.

RUNS ON THE GEAR YOU ALREADY FIELD
• Universal iPhone and iPad
• Optional Face ID / Touch ID lock to secure the app
• All processing on-device. Zero data collected — no telemetry, no ads, no third-party SDKs.


PAY ONCE — US$4.99
3-day free trial, then a one-time unlock. No subscription, ever.
```

### Google Play

```
TacMap is a field-grade tactical map for your phone — buy it once and own it. No subscription. No account. Nothing collected.

Built for military, search-and-rescue, and back-country use, where the network isn't there and the map still has to be right.


WORKS OFFLINE, ANYWHERE ON EARTH
• Import any GeoPDF or scanned defence map sheet and georeference it on-device
• Calibrate any image map with a quick 3-point fix — reports its accuracy in metres so you know you can trust it
• Sideload MBTiles offline tile sets — no regional lock-in, no curated country list, no network required
• Terrain heatmap overlay — DEM-shaded elevation visualisation you can toggle on the fly

MILITARY-GRADE TOOLING
• Live MGRS readout to 10 figures, plus WGS84 lat/long, UTM, and elevation at the crosshair
• NATO mils compass (6400) with true-north marker
• NATO APP-6 symbology — build any unit from affiliation × echelon × function, add an HQ flag, and place tactical control measures and task graphics (block, breach, seize, screen, axis of advance, phase lines, boundaries, FLOT/FEBA, and more)
• Drawing tools: points, lines, areas, and freehand sketch — each with its own colour, width, and opacity, organised on named layers
• Distance, area, and bearing measurement in degrees AND NATO mils
• Full undo/redo

SHARE THE PICTURE, IN REAL TIME
• Unit Sync — share drawings and symbols live across devices over an end-to-end encrypted channel
• Live presence — see where your team is on the map in real time, with callsign, affiliation, and heading, all end-to-end encrypted
• The relay never sees your plaintext; a connected indicator on the header shows you're linked up
• Conflict detection — if a teammate edits the same object, you get a clear notification instead of a silent overwrite
• Weather + UAV flight-safety widget — live wind, gusts, visibility, and a SAFE / CAUTION / DANGER drone-flight read for the map centre

OPEN BY DESIGN — YOUR DATA STAYS YOURS
• Import and export GeoJSON (RFC 7946) — round-trips cleanly through QGIS, ArcGIS, Felt, Leaflet, and Google Earth
• Import KML / KMZ from Google Earth and ATAK exports
• Record and export GPX tracks
• Export All — one-tap backup of every symbol, drawing, waypoint, and track as a single GeoJSON file
• No proprietary format. Everything you draw exports to the tools your unit already uses.

RUNS ON THE GEAR YOU ALREADY FIELD
• Broad Android support — not gated behind the newest OS version
• Optional PIN or biometric lock to secure the app
• All processing on-device. Zero data collected — no telemetry, no ads, no third-party SDKs.


PAY ONCE — A$5
3-day free trial, then a one-time unlock. No subscription, ever.
```

---

## App Store keywords (≤100 chars, comma-separated, no spaces)
`mgrs,nato,app-6,tactical,offline,map,gis,geojson,kml,geopdf,military,grid,utm,navigation,sar`

## What's New (release note snippet)

### 1.2.0
- **New: Live presence** — see your team on the map in real time. Set your callsign, affiliation, and echelon, and every connected device shows your position with heading — all end-to-end encrypted.
- **New: Export All** — one-tap backup of every symbol, drawing, waypoint, and track as a single GeoJSON file.
- **New: Sync conflict alerts** — if a teammate edits the same object, you get a notification instead of a silent overwrite.
- Authenticated sync relay — stronger security for Unit Sync connections.
- Data durability improvements — symbols, drawings, and tracks survive app kills and low-memory conditions more reliably.
- Bug fixes and stability improvements.

### 1.1.0
- **New: Unit Sync** — share your drawings and symbols live across devices over an end-to-end encrypted channel. The relay never sees your plaintext.
- **New: Weather + UAV flight-safety** — live wind, gusts and visibility with a SAFE / CAUTION / DANGER drone-flight read for the map centre.
- **New: terrain heatmap** — toggle a DEM-shaded elevation overlay from the Layers sheet.
- **New: import KML / KMZ** from Google Earth and ATAK.
- Faster offline map handling and field-readability polish.

---

### Why this copy (rationale for future edits)
- **Lead with "buy once / no subscription"** — the competitor is subscription
  ($1.99/mo–$12.99/yr); this is the cleanest differentiator for a store browser.
- **"Offline anywhere on Earth" + GeoPDF/calibration** — the competitor's offline
  coverage is essentially one region and it has no scanned-map georeferencing.
- **GeoJSON + KML interop** — the competitor uses a proprietary overlay format;
  open export is a concrete reason-to-switch for GIS-literate units.
- **"Runs on the gear you already field"** — counters the competitor's high OS
  floor without naming it.
- **"Zero data collected"** — reinforces the privacy stance and OPSEC posture.
- **Real-time Unit Sync, Weather + UAV safety, and terrain heatmap** — all
  shipped on both platforms in 1.1.0 and verified in code; the E2E-encrypted
  ("relay never sees plaintext") framing doubles as a privacy/OPSEC selling
  point rather than just a feature line.
