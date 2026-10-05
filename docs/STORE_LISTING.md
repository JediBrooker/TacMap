# Store listing copy - App Store & Google Play

Positioning notes and release-note history. **The maintained listing text is
`docs/store/localizations/en-US.json` and `de-DE.json`**; that is what ships.
For paste-ready fields run

```sh
python3 scripts/export_store_localizations.py --output <folder>
```

which also checks every field against the store limits. Don't keep a second
copy of the description here: the old paste-ready block drifted from the JSON
and would have reverted the 3.0 copy if anyone had pasted it.

The copy is pitched at a broad field audience (hikers, hunters, overlanders,
public safety / search-and-rescue, field-GIS and military users) and keeps the
wedges against the nearest competitor (TacticMap): pay once vs subscription,
offline anywhere vs region-locked, open interchange (GeoJSON/KML/GPX) vs a
proprietary format, and no TacMap account, ads or analytics.

> Positioning one-liner: **"Buy once. Works offline anywhere on Earth. Your
> maps, your data, your tools."**

---

## Current field values (3.0, from the JSON)
- **App Store name**: `TacMap` (de-DE `TacMap: Offline-Karten`).
- **App Store subtitle (≤30)**: `Offline GPS field maps + grid` *(29)*;
  de-DE `Offline-Karten, GPS und MGRS` *(28)*.
- **Google Play title (≤30)**: `TacMap: Offline GPS Field Maps` *(30)*;
  de-DE `TacMap: Offline-Karten und GPS`.
- **Google Play short description (≤80)**: `Offline field maps, MGRS, waypoints
  and encrypted team sharing. No subscription.` *(80)*; de-DE `Offline-Karten,
  MGRS, Wegpunkte und verschlüsselter Austausch. Ohne Abo.` *(72)*.
- **App Store promotional text (≤170, editable without review)**: `New in 3.0:
  GeoPDFs line up with the printed MGRS grid, easy PDF calibration, sharp maps
  at every zoom, line of sight, red night mode and KML export.` *(149)*; the
  German one is 154. Previous (2.1): range rings, offline sun & moon times,
  symbol list sorting and Keep screen on. 2.2 never shipped; its features are
  in 3.0.

## Full description
Both stores render plain text only (markdown asterisks show up literally), so
the JSON uses line breaks and `•` bullets. Google Play reuses the App Store
description (`googlePlay.descriptionFromAppStore`) with the explicit
`descriptionReplacements` for the app-lock and store-price lines.

Only claim what the apps actually import. Maps come in as PDF, GeoPDF or
MBTiles; there is no image import, so a scan or phone photo of a paper map has
to be saved as PDF first and the copy has to say so. 3.0.0 shipped saying "a
photo of a paper map" without that qualifier; fixed in the 3.0.1 copy.

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
- Google Play has no keyword field (it indexes the description); the JSON
  description already works those terms in naturally.

## What's New (release note snippet)

The source of truth is `docs/store/localizations/en-US.json` and `de-DE.json`
(`appStore.whatsNew` / `googlePlay.releaseNotes`); run
`scripts/export_store_localizations.py` to check limits. The JSON only holds
the current release, so copy the notes into the history below when a version
ships.

### 3.0.1 (build 74)
Bug-fix release. The App Store notes list the iOS fixes and the Google Play
notes the Android ones; both mention the fix for maps going missing after an
update from 2.x and the relay hardening. Keep them in plain user language with
no exploit detail. See the JSON for the text (en-US App Store 463 / 4,000,
Play 355 / 500; de-DE App Store 623 / 4,000, Play 453 / 500).

### 3.0.0 (build 73)
3.0.0 replaced the unreleased 2.2.0: builds 70-72 only went to TestFlight, so
3.0's notes carried the 2.2 features too. The shipped App Store text (MAPS,
FIELD TOOLS, UNIT SYNC sections, en-US 1,333 and de-DE 1,668 characters) is
`docs/store/localizations/*.json` at e1714b1. Its calibration bullet wrongly
said a photo of a paper map could be calibrated; don't reuse it as is.

Google Play, English (384 / 500 Unicode characters):
```
TacMap 3.0
• GeoPDFs line up with the printed MGRS grid; USGS sheets import again.
• Calibrate a plain PDF from grid references, with per-point accuracy.
• Sharp maps at every zoom, offline tiles, several maps.
• Elevation profile and line of sight.
• Red night mode and KML/KMZ export.
• Hold the map for a point menu; guided tour.
• Steadier Unit Sync; fingerprint or face App Lock.
```

Google Play, German (411 / 500 Unicode characters):
```
TacMap 3.0
• GeoPDFs passen auf das gedruckte MGRS-Gitter; USGS-Blätter wieder importierbar.
• PDF per Gitterkoordinaten kalibrieren, mit Genauigkeit je Punkt.
• Scharfe Karten beim Zoomen, Offline-Kacheln, mehrere Karten.
• Höhenprofil und Sichtlinie.
• Roter Nachtmodus und KML/KMZ-Export.
• Punktmenü per langem Drücken; geführte Tour.
• Stabilere Synchronisierung; App-Sperre per Fingerabdruck oder Gesicht.
```

2.2.0 drafts below are kept for history only; that version never shipped.

### 2.2.0 — Apple App Store, English (1,117 / 4,000 characters)
```
• Elevation profile: tap the terrain button on the Measure bar or a selected line to see the ground along it, with lowest and highest points, climb and descent. Uses online lookups.
• Line of sight: on a two-point line, set observer and target heights to see where terrain blocks the view, allowing for earth curvature. Dead ground is greyed out.
• Night mode: turns the whole screen red and dims it to protect your night vision. Toggle it from the moon button on the map and set the brightness in Settings → Display.
• KML and KMZ export: send layers, symbols and drawings to Google Earth, ATAK and GIS tools. KMZ with Symbols includes each symbol's image.
• Hold an empty spot on the map for a second to place a symbol, measure from it, add range rings, see sun and moon times or copy the coordinate.
• A guided tour points at each map control. Replay it any time from About & Credits.
• Range rings now follow their symbol when you move it.
• Fixes: PDF calibration now shows its accuracy and explains why a fit fails, and the elevation readout no longer shows GPS altitude for a crosshair away from your position.
```

### 2.2.0 — Google Play, English (441 / 500 Unicode characters)
```
• Elevation profile and line of sight along measured or drawn lines.
• Night mode: a red, dimmed screen protects your night vision.
• KML and KMZ export, with symbol images for Google Earth and ATAK.
• Hold the map to place a symbol, measure, add range rings, see sun and moon times or copy the coordinate.
• A guided tour of the map controls.
• Fingerprint or face unlock for App Lock.
• Chat announces new messages and opens on the newest.
```

### 2.2.0 — Apple App Store, German (de-DE) (1,432 / 4,000 characters)
```
• Höhenprofil: Tippe in der Messleiste oder bei einer ausgewählten Linie auf die Geländetaste, um das Gelände entlang der Linie mit tiefstem und höchstem Punkt, Anstieg und Abstieg zu sehen. Nutzt Online-Abfragen.
• Sichtlinie: Lege bei einer Linie zwischen zwei Punkten die Beobachter- und Zielhöhe fest und sieh, wo das Gelände die Sicht verdeckt – unter Berücksichtigung der Erdkrümmung. Nicht einsehbares Gelände wird grau dargestellt.
• Nachtmodus: Färbt den ganzen Bildschirm rot und dunkelt ihn ab, um deine Nachtsicht zu schützen. Du schaltest ihn über die Mondtaste auf der Karte um und stellst die Helligkeit unter Einstellungen → Anzeige ein.
• KML- und KMZ-Export: Gib Ebenen, Symbole und Zeichnungen an Google Earth, ATAK und GIS-Programme weiter. „KMZ mit Symbolen“ enthält das Bild jedes Symbols.
• Halte eine freie Stelle der Karte eine Sekunde lang gedrückt, um dort ein Symbol zu platzieren, von dort zu messen, Entfernungsringe hinzuzufügen, Sonnen- und Mondzeiten anzuzeigen oder die Koordinate zu kopieren.
• Eine geführte Tour zeigt dir jedes Bedienelement der Karte. Unter „Über TacMap und Mitwirkende“ kannst du sie jederzeit erneut abspielen.
• Entfernungsringe folgen jetzt ihrem Symbol, wenn du es verschiebst.
• Korrekturen: Die PDF-Kalibrierung zeigt jetzt ihre Genauigkeit an und erklärt, warum sie fehlschlägt. Die Höhenanzeige zeigt für ein Fadenkreuz abseits deines Standorts keine GPS-Höhe mehr an.
```

### 2.2.0 — Google Play, German (de-DE) (484 / 500 Unicode characters)
```
• Höhenprofil und Sichtlinie entlang gemessener oder gezeichneter Linien.
• Roter, abgedunkelter Nachtmodus schützt deine Nachtsicht.
• KML- und KMZ-Export mit Symbolbildern für Google Earth und ATAK.
• Karte gedrückt halten: Symbol platzieren, messen, Entfernungsringe, Sonne und Mond oder Koordinate kopieren.
• Geführte Tour durch die Bedienelemente der Karte.
• App-Sperre mit Fingerabdruck oder Gesichtsentsperrung.
• Der Chat meldet neue Nachrichten und öffnet bei der neuesten.
```

### 2.1.0 — Apple App Store, English (470 / 4,000 characters; plain text)
```
• Range rings: add up to 10 rings at your chosen spacing around any symbol. They sync, export and undo like other drawings.
• Sun & moon: nautical and civil twilight (BMNT/EENT, BMCT/EECT), sunrise, sunset, moonrise, moonset and moon illumination for the map centre, calculated offline.
• Sort the symbol list by newest, name, distance from the crosshair, affiliation or layer.
• Keep screen on: an optional Display setting stops the screen locking while TacMap is open.
```

### 2.1.0 — Google Play, English (277 / 500 Unicode characters)
```
• Range rings around any symbol: up to 10 at your chosen spacing.
• Offline sun & moon times: BMNT/EENT, civil twilight, sunrise, sunset, moonrise, moonset and moon illumination.
• Sort symbols by newest, name, distance, affiliation or layer.
• Optional Keep screen on setting.
```

### 2.1.0 — Apple App Store, German (de-DE) (580 / 4,000 characters)
```
• Entfernungsringe: Bis zu 10 Ringe im gewählten Abstand um jedes Symbol. Sie werden wie andere Zeichnungen synchronisiert, exportiert und rückgängig gemacht.
• Sonne & Mond: Nautische und bürgerliche Dämmerung (BMNT/EENT, BMCT/EECT), Sonnenauf- und -untergang, Mondauf- und -untergang sowie Mondbeleuchtung für die Kartenmitte – offline berechnet.
• Symbolliste nach Neueste, Name, Entfernung zum Fadenkreuz, Zugehörigkeit oder Ebene sortieren.
• Bildschirm eingeschaltet lassen: Eine optionale Anzeige-Einstellung verhindert die automatische Sperre, solange TacMap geöffnet ist.
```

### 2.1.0 — Google Play, German (de-DE) (345 / 500 Unicode characters)
```
• Entfernungsringe um jedes Symbol: bis zu 10 im gewählten Abstand.
• Offline-Zeiten für Sonne & Mond: BMNT/EENT, bürgerliche Dämmerung, Sonnenauf- und -untergang, Mondauf- und -untergang und Mondbeleuchtung.
• Symbole nach Neueste, Name, Entfernung, Zugehörigkeit oder Ebene sortieren.
• Optionale Einstellung „Bildschirm eingeschaltet lassen“.
```

### 2.0.2 — Apple App Store (as published; en-AU 66, de-DE 148 characters)
Shipped as build 68 with custom symbol packs. The localisation-only draft that
was in the JSON at the time was not used.
```
- Imports custom symbol packs.
- Improved translations for German.
```
```
Neu in TacMap: Mit eigenen Symbolpaketen passt du deine Karten an deine Bedürfnisse an. Außerdem ist TacMap jetzt vollständig auf Deutsch verfügbar.
```

### 2.0.1 — Apple App Store (as published; en-AU 32 characters)
```
German translation/localisation.
```

Google Play notes for 2.0.1 and 2.0.2 were entered in Play Console and not
recorded here.

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
- **Military tooling is now depth, not identity** - MGRS / NATO / mils sit
  under NAVIGATE AND PLAN rather than leading the copy, so the outdoor / pro
  segments don't bounce, while users who search for it still find it (`nato`,
  `mgrs`, `utm`).
- **Marker sets** (SAR / POI / airsoft) are called out in the copy - a concrete
  signal the app serves the new audiences, not just military units.
- **GeoJSON + KML interop** and the specific no-account/no-analytics/privacy-
  control posture carry the GIS-interop and privacy stories honestly: online
  basemaps/lookups are disabled on first launch, can be enabled independently,
  and can disclose the IP plus requested map area, query, or lookup coordinates
  to their providers. Platform stores can also reconcile entitlement.
- **Screenshots are platform-specific** in `docs/store/{ios,android}/`. The
  3.0 set is the five slides named in each folder's README (10, 11, 12, 13,
  07); review every image against the native build it ships with rather than
  copying captions across platforms.
- **Space budget** - the German App Store description is the long one
  (3,328 / 4,000 characters in 3.0.1; en-US is 2,852). Check both with the
  export script before adding a bullet.
