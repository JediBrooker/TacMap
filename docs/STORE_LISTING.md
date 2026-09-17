# Store listing copy - App Store & Google Play

Paste-ready listing text. **Repositioned for a broad field audience** - outdoor
recreation (hikers, hunters, overlanders), public safety / search-and-rescue,
professional / field-GIS, and military - while keeping the wedges against the
nearest competitor (TacticMap): pay-once vs subscription, offline-anywhere vs
region-locked, open interchange (GeoJSON/KML/GPX) vs a proprietary format, broad
device support, and no TacMap account, ads, or analytics. Keep this in sync with
the screenshots in `docs/store/`.

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
- **New in 2.0: encrypted TacMap Chat, Heading Up compass mode, more reliable Unit Sync, and safer PDF, GeoPDF and MBTiles import on iOS and Android.**

---

## Full description

Both stores render plain text only (markdown asterisks show up literally), so
the paste-ready copy below uses line breaks and `•` bullets that display
correctly. The two versions are intentionally near-identical for consistent
branding, with platform-correct tweaks (device wording, biometrics, pricing).
Both are well under the 4,000-character limit.

### Apple App Store

```
TacMap is a serious offline map for the field - buy it once and own it. No subscription. No TacMap account, ads, or analytics.

Built for field use where signal drops: hiking, hunting, overlanding, SAR, fire/EMS, survey, forestry, drone mapping, and military or cadet MGRS users. One map, offline anywhere, and your data stays yours.


WORKS OFFLINE, ANYWHERE ON EARTH
• Import a GeoPDF or a scanned map sheet saved as PDF and georeference it on-device
• Calibrate an ungeoreferenced PDF with a 3-point fit and review its reported residual
• Sideload MBTiles offline tile sets - no regional lock-in, no curated country list, no network required
• Optional terrain heatmap - a DEM-shaded elevation overlay while online lookups are enabled

KNOW EXACTLY WHERE YOU ARE
• Live position readout in MGRS (to 10 figures), lat/long, and UTM - plus online crosshair elevation while lookups are enabled
• North Up or Heading Up map orientation - use touch rotation or let the phone compass rotate the map as you turn
• North-reference indicator in degrees or NATO mils (6400), labelled for the selected true, magnetic, or grid north
• Measure distance, area, and bearing - in degrees and mils
• Drop and label waypoints; record and export your route as a GPX track

MARK UP THE MAP
• Drawing tools: points, lines, areas, and freehand sketch - each with its own colour, width, and opacity, organised on named layers
• Full undo/redo
• Search & Rescue, Points of Interest, airsoft, and milsim marker sets
• NATO APP-6 symbology - build units, add HQ flags, and place control measures and task graphics

SHARE THE PICTURE, LIVE
• Unit Sync - when connected, share drawings and symbols across iOS and Android over an end-to-end encrypted channel
• Live presence - see your team on the map with callsign, heading, and position, all end-to-end encrypted
• TacMap Chat - send encrypted text or reports to the entire room or one selected live unit, with an unread indicator on the map
• Optional screen-off presence on iOS and Android, with a separate OPSEC switch and selectable best-effort update interval
• The relay cannot decrypt mission content, but sees admission/routing, actor/session/control, Chat scope and recipient, IP, timing, and size metadata. “Routed” is not delivery/read proof; member status is relay-reported
• Conflict alerts - if a teammate edits the same object, you get a notification instead of a silent overwrite
• Optional online weather + drone flight-safety widget - wind, gusts, visibility, and a SAFE / CAUTION / DANGER read for the map centre

OPEN BY DESIGN - YOUR DATA STAYS YOURS
• Import and export GeoJSON (RFC 7946) - round-trips cleanly through QGIS, ArcGIS, Felt, Leaflet, and Google Earth
• Import KML / KMZ from Google Earth and ATAK
• Record and export GPX tracks
• Export All Mission Objects - one-tap GeoJSON for every symbol, drawing, waypoint, and layer
• Recorded tracks remain separate GPX files, so mission-object export never hides or changes track data
• No proprietary format. Everything you make exports to the tools you already use.

PRIVACY CONTROLS
• Mission data is encrypted in app-private storage; imported map bytes remain private under device file protection
• Online basemaps and lookups are disabled on first launch and can be enabled independently; while enabled, providers receive your IP and requested map area, query, or lookup coordinates. Coordinate and mission search stays on-device
• No TacMap account, behavioural analytics, advertising identifiers, ads, or developer crash uploads
• Optional Face ID / Touch ID app lock
• Universal iPhone and iPad - not gated behind the newest OS


PAY ONCE - LOCALISED PRICE SHOWN BY THE APP STORE
3-day free trial, then a one-time unlock. No subscription, ever.
```

### Google Play

Same body as the App Store block above, with these platform swaps:
- `PRIVACY CONTROLS` - replace the two Apple device/lock lines with:
  - `• Optional PIN or biometric app lock`
  - `• Broad Android support - not gated behind the newest OS version`
- Keep the final line as the store-localised-price wording; do not hard-code a
  currency that may differ from the product configured in Play Console.

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

### 2.0.0 — Apple App Store (893 / 4,000 characters; plain text)
```
• TacMap Chat: send end-to-end encrypted text or reports to the entire room or one selected live unit, with a map shortcut and unread indicator. “Routed” means relay-accepted, not delivered or read.
• Heading Up: let the phone compass rotate the map automatically, or keep North Up and rotate by touch. North-reference and mils feedback now stay clear across iOS and Android.
• More reliable Unit Sync: faster reconnects, implausible-GPS rejection, bounded last-known markers, explicit leave handling, and optional screen-off presence on both platforms.
• Safer offline maps: stronger PDF, GeoPDF and MBTiles validation, bounded rendering, fail-atomic imports, retained calibration identity, and clearer malformed-map errors.
• Extensive Android lifecycle, recording, map-rendering and persistence fixes, plus matching iOS hardening, privacy-default tests and security improvements throughout.
```

### 2.0.0 — Google Play (479 / 500 Unicode characters)
```
New in TacMap 2.0:
• TacMap Chat sends end-to-end encrypted text or reports to a room or selected live unit.
• Heading Up rotates the map with your phone compass.
• Unit Sync reconnects faster, rejects implausible GPS jumps, and supports optional screen-off presence.
• PDF, GeoPDF and MBTiles imports now have stronger validation and safer recovery.
• Many Android stability, lifecycle, recording, rendering, persistence, privacy and security fixes, with matching iOS hardening.
```

### 1.2.4 (draft — use only after the version is configured and submitted)
- Reliability fixes for purchases, recording, imports/exports, app lock, and encrypted persistence.
- Matching offline-first search across iOS and Android, including local mission objects and coordinate input that never reaches an online place provider.
- Improved layer and symbol editing, accessibility labels, and retained imported-map handling on both platforms.
- **Export All Mission Objects** now names the GeoJSON scope clearly; recorded tracks continue to export separately as GPX.
- Clearer Unit Sync delivery, membership, and relay-metadata status.

### 1.2.0
- **New: Live presence** - see your team on the map in real time. Set your callsign, affiliation, and echelon, and every connected device shows your position with heading - all end-to-end encrypted.
- **New: mission-object export** - one-tap GeoJSON for symbols, drawings,
  waypoints, and layers; track recording remains a separate GPX workflow.
- **New: Sync conflict alerts** - if a teammate edits the same object, you get a notification instead of a silent overwrite.
- Authenticated sync relay - stronger security for Unit Sync connections.
- Data durability improvements - symbols, drawings, and tracks survive app kills and low-memory conditions more reliably.
- Bug fixes and stability improvements.

### 1.1.0
- **New: Unit Sync** - share drawings and symbols live over an end-to-end
  encrypted channel. The relay cannot decrypt mission payloads but receives the
  admission token plus clear envelope/control and traffic metadata.
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
- **GeoJSON + KML interop** and the specific no-account/no-analytics/privacy-
  control posture carry the GIS-interop and privacy stories honestly: online
  basemaps/lookups are disabled on first launch, can be enabled independently,
  and can disclose the IP plus requested map area, query, or lookup coordinates
  to their providers. Platform stores can also reconcile entitlement.
- **Screenshots are platform-specific** in `docs/store/{ios,android}/`. Use only
  the release-approved subsets named in each folder's README. Android phone
  screens 01 and 02 are approved for 2.0; its remaining phone/tablet artwork is
  still blocked. Review every image against its submitted native build rather
  than copying captions across platforms.
