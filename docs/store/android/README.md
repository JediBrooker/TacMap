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

## 2.2 set: 8 slides, same order and text as iOS

Real captures from the debug build on the `TacMap_API_36` emulator (tablet via
`wm size 1600x2560` + `wm density 320` on the same AVD), framed by
`scripts/compose_store_set.py`, from the build that includes #46 and #47 (task
graphics keep their ground size, profile is a bottom sheet). The hero uses the
shared `docs/store/store_situation.geojson`, same as iOS.

1. `01-hero.png` — the shared NATO situation with range rings on the enemy position
2. `02-line-of-sight.png` — elevation profile and line of sight (real Copernicus DEM heights)
3. `03-night-mode.png` — the same map with red night mode on
4. `04-unit-sync.png` — Unit Sync connected with a second live unit (iOS) / TacMap Chat in a live room (Android)
5. `05-sun-moon.png` — Sun & Moon sheet from the long-press point menu
6. `06-pdfmap.png` — imported USGS GeoPDF with Search & Rescue markers
7. `07-symbol-builder.png` — APP-6 symbol builder with live preview
8. `08-export.png` — Import / Export with KML, KMZ with Symbols, GeoJSON and GPX

## Phone screenshots — `phone/` (1080×1920, 9:16 portrait)
Upload all 8 (Play maximum). 9:16 at 1080 px or more keeps them eligible for
Play's promotional placements.

## Tablet screenshots — `tablet/` (1440×2560, 9:16 portrait)
Play requires 9:16 or 16:9 for tablet screenshots, so the old 1600×2560 (10:16)
set would have been rejected. Upload the same 8 under both 7-inch and 10-inch
tablet sections.

## Promo video — `video/play-promo-2.2.mp4` (1920×1080, ~33 s)
Landscape cut of real Android screen recordings with captions. Upload it to
YouTube (public or unlisted, ads off, embeddable) and paste the URL into the
Play listing. Built by `scripts/build_preview_video.py` in `frame` mode.

## Notes
- Phone shots are 1080×1920 and tablet shots are 1440×2560, both 9:16.
- Short/full descriptions: paste-ready copy lives in
  [`docs/STORE_LISTING.md`](../../STORE_LISTING.md) (leads with the
  pay-once / offline-anywhere / open-interop wedges).
- Still human-owned in Play Console: privacy-policy URL, Data Safety,
  foreground-service/location, target-audience, and content-rating forms.
- Before approving any additional screen, require the final triangle-and-N compass, the
  TacMap Chat shortcut whenever Unit Sync is connected, the current relay
  metadata disclosure, **Export All Mission Objects**, and visible **REC** and
  **Search** UI in their corresponding captures. Reject the obsolete claim that
  the relay “only ever sees ciphertext.”
- Signed release bundle: `android/app/build/outputs/bundle/release/app-release.aab`
  (rebuild and verify it with the commands in
  [`android/PLAY_STORE_PREP.md`](../../../android/PLAY_STORE_PREP.md)).
  Check the versionCode in `android/app/build.gradle.kts` is newer than the last
  upload before building.
