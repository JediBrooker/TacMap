# Google Play store assets

Generated for the Play Console "Main store listing" + "Store settings" pages.
Location featured: **Shoalwater Bay Training Area, QLD** (Australian Army).

## Hi-res icon
- `play-icon-512.png` — **512×512** (required). Renders the Android adaptive
  launcher icon (background + foreground vector) so it matches the on-device
  icon. Regenerate with `python3 scripts/generate_play_icon.py`.

## Feature graphic
- `feature-graphic.png` — **1024×500** (required). Regenerate with
  `python3 scripts/generate_feature_graphic.py`.

## Phone screenshots — `phone/` (1080×2400, portrait)
Upload 2–8 under "Phone screenshots".
1. `01-main.png` — live MGRS HUD over the coastline ("Your Location")
2. `02-symbols-on-map.png` — NATO APP-6 friendly infantry + hostile armour + an
   Assembly Area tactical task on the map
3. `03-military-unit.png` — APP-6 unit builder (affiliation / echelon / function)
4. `04-draw-area.png` — drawing an area overlay (exports as GeoJSON)
5. `05-about.png` — version, attributions, on-device-data note

## Tablet screenshots — `tablet/` (2560×1600, landscape)
Upload under "7-inch / 10-inch tablet screenshots" (same numbering as phone).

## Notes
- 1080×2400 (20:9) is the native Pixel resolution and uploads to Play as-is.
  If the uploader ever rejects the ratio, pad the width to 1200×2400 (2:1).
- Tablet shots are 2560×1600 (16:10) — within Play's limits.
- Still needed in Play Console (not generatable here): short/full descriptions,
  privacy-policy URL, Data Safety + content-rating forms.
- Signed release bundle: `android/app/build/outputs/bundle/release/app-release.aab`
  (rebuild with `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./android/gradlew -p android bundleRelease`).
  Version code 10 / versionName 1.0.0. Upload under Test and release.
