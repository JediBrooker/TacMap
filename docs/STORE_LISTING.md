# Store listing copy - App Store & Google Play

Paste-ready listing text. **Repositioned for a broad field audience** - outdoor
recreation (hikers, hunters, overlanders), public safety / search-and-rescue,
professional / field-GIS, and military - while keeping the wedges against the
nearest competitor (TacticMap): pay-once vs subscription, offline-anywhere vs
region-locked, open interchange (GeoJSON/KML) vs a proprietary format, broad
device support, and zero data collection. Keep this in sync with the screenshots
in `docs/store/`.

> Positioning one-liner: **"Buy once. Works offline anywhere on Earth. Your
> maps, your data, your tools."**

---

## App name / title
- **App Store**: 30-char title is `TacMap` (the brand carries the tactical nod).
- **Google Play title (≤30)**: **TacMap: Offline GPS Field Maps** *(30)*

## App Store subtitle (≤30 chars)
- **Offline GPS field maps + grid** *(29)*
- Alt (keeps MGRS visible): **Offline field maps, MGRS grid** *(29)*

## Google Play short description (≤80 chars)
- **Pay once. Offline field maps for hiking, hunting, SAR, survey & tactical work.** *(78)*

## App Store promotional text (≤170 chars, editable without review)
- **One-time unlock, no subscription. Import any GeoPDF or scanned map, navigate fully offline anywhere on Earth, and export GeoJSON/KML/GPX to the tools you already use.** *(166)*

---

## Full description

Both stores render plain text only (markdown asterisks show up literally), so
the paste-ready copy below uses line breaks and `•` bullets that display
correctly. The two versions are intentionally near-identical for consistent
branding, with platform-correct tweaks (device wording, biometrics, pricing).
Both are well under the 4,000-character limit.

### Apple App Store

```
TacMap is a serious offline map for the field - buy it once and own it. No subscription. No account. Nothing collected, ever.

Built for anyone who works or plays where the signal drops: hikers, hunters and overlanders; search-and-rescue, fire and EMS crews; survey, forestry and drone-mapping teams; and military and cadet users who live in MGRS and NATO symbology. One map, offline anywhere, and your data stays yours.


WORKS OFFLINE, ANYWHERE ON EARTH
• Import any GeoPDF or scanned map sheet and georeference it on-device
• Calibrate any image or paper map with a quick 3-point fix - it reports its accuracy in metres so you know you can trust it
• Sideload MBTiles offline tile sets - no regional lock-in, no curated country list, no network required
• Terrain heatmap - a DEM-shaded elevation overlay you can toggle on the fly

KNOW EXACTLY WHERE YOU ARE
• Live position readout in MGRS (to 10 figures), lat/long, and UTM - with elevation at the crosshair
• Compass with true-north marker, in degrees or NATO mils (6400)
• Measure distance, area, and bearing - in degrees and mils
• Drop and label waypoints; record and export your route as a GPX track

MARK UP THE MAP
• Drawing tools: points, lines, areas, and freehand sketch - each with its own colour, width, and opacity, organised on named layers
• Full undo/redo
• Marker sets beyond the military kit: Search & Rescue (point last seen, IPP, ICP, helispot…), Points of Interest, and airsoft / milsim
• NATO APP-6 symbology for tactical users - build any unit from affiliation × echelon × function, add an HQ flag, and place control measures and task graphics (phase lines, boundaries, axis of advance, block/breach/seize/screen, FLOT/FEBA, and more)

SHARE THE PICTURE, LIVE
• Unit Sync - share drawings and symbols across devices in real time over an end-to-end encrypted channel
• Live presence - see your team on the map with callsign, heading, and position, all end-to-end encrypted
• The relay never sees your plaintext; a connected indicator shows you're linked up
• Conflict alerts - if a teammate edits the same object, you get a notification instead of a silent overwrite
• Weather + drone flight-safety widget - live wind, gusts, visibility, and a SAFE / CAUTION / DANGER read for the map centre

OPEN BY DESIGN - YOUR DATA STAYS YOURS
• Import and export GeoJSON (RFC 7946) - round-trips cleanly through QGIS, ArcGIS, Felt, Leaflet, and Google Earth
• Import KML / KMZ from Google Earth and ATAK
• Record and export GPX tracks
• Export All - one-tap backup of every symbol, drawing, waypoint, and track as a single GeoJSON file
• No proprietary format. Everything you make exports to the tools you already use.

PRIVATE BY DEFAULT
• All processing on-device. Zero data collected - no telemetry, no ads, no third-party SDKs.
• Optional Face ID / Touch ID app lock
• Universal iPhone and iPad - not gated behind the newest OS


PAY ONCE - US$4.99
3-day free trial, then a one-time unlock. No subscription, ever.
```

### Google Play

Same body as the App Store block above, with these platform swaps:
- `PRIVATE BY DEFAULT` - replace the two Apple device/lock lines with:
  - `• Optional PIN or biometric app lock`
  - `• Broad Android support - not gated behind the newest OS version`
- Final line: `PAY ONCE - A$5` (instead of `US$4.99`).

---

## App Store keywords (≤100 chars, comma-separated, no spaces)
`mgrs,utm,topo,hunting,hiking,overland,geopdf,geojson,kml,waypoint,gpx,nato,sar,survey,gis,trail`  *(95 chars)*

- Deliberately omits words already in the title/subtitle (`tactical`, `offline`,
  `map/maps`, `field`, `grid`, `gps`) - those index from there, so the budget
  goes to new-audience terms.
- Balanced across the four audiences: outdoor (`hunting, hiking, overland, topo,
  trail, waypoint, gpx`), public-safety (`sar`), professional / field-GIS
  (`mgrs, utm, geopdf, geojson, kml, survey, gis`), military (`nato`).
- ~5 chars spare - add one short term to lean an audience harder if you like:
  `4x4` (overland), `rescue` (SAR), or `camp`.
- Google Play has no keyword field (it indexes the description); the copy above
  already works those terms in naturally.

## What's New (release note snippet)

### 1.2.0
- **New: Live presence** - see your team on the map in real time. Set your callsign, affiliation, and echelon, and every connected device shows your position with heading - all end-to-end encrypted.
- **New: Export All** - one-tap backup of every symbol, drawing, waypoint, and track as a single GeoJSON file.
- **New: Sync conflict alerts** - if a teammate edits the same object, you get a notification instead of a silent overwrite.
- Authenticated sync relay - stronger security for Unit Sync connections.
- Data durability improvements - symbols, drawings, and tracks survive app kills and low-memory conditions more reliably.
- Bug fixes and stability improvements.

### 1.1.0
- **New: Unit Sync** - share your drawings and symbols live across devices over an end-to-end encrypted channel. The relay never sees your plaintext.
- **New: Weather + UAV flight-safety** - live wind, gusts and visibility with a SAFE / CAUTION / DANGER drone-flight read for the map centre.
- **New: terrain heatmap** - toggle a DEM-shaded elevation overlay from the Layers sheet.
- **New: import KML / KMZ** from Google Earth and ATAK.
- Faster offline map handling and field-readability polish.

---

### Why this copy (rationale for future edits)
- **Big-tent audience** - the lead line and the "built for…" line name outdoor
  recreation, public safety / SAR, professional / field-GIS, and military. The
  old copy led with "military" alone, which narrows the market and draws extra
  App Review scrutiny (spell out in review notes that it is *not* a
  weapons/defence system - see `docs/APPSTORE_CHECKLIST.md`).
- **Wedges still lead the pitch** - "buy once / no subscription" and "offline
  anywhere on Earth" are the cleanest differentiators for any store browser vs
  the subscription, region-locked competitor.
- **Military tooling is now depth, not identity** - MGRS / NATO / mils moved
  under "know exactly where you are" and "mark up the map" so the outdoor / pro
  segments don't bounce, while users who search for it still find it (`nato`,
  `mgrs`, `utm`).
- **Marker sets** (SAR / POI / airsoft) are called out in the copy - a concrete
  signal the app serves the new audiences, not just military units.
- **GeoJSON + KML interop** and **zero data collected** carry the GIS-interop
  and OPSEC / privacy stories for the professional and tactical crowds.
- **Screenshots match** in `docs/store/{ios,android}/`: slide 06 is
  Satellite / Topographic / Street basemaps, 08 is Measurement (range / area /
  bearing), 10 is a georeferenced GeoPDF with Search & Rescue markers. App
  previews (iOS) live in `docs/store/ios/previews/`.
