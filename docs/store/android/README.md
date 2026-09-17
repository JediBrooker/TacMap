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

## Phone screenshots — `phone/` (1080×2100, portrait)
Upload 2–8 under "Phone screenshots"; choose the strongest current subset from
the ten source images rather than relying on this folder order as a console
selection.

The approved 2.0 subset is **01 and 02**. Both were captured non-destructively
from version 2.0.0 (64) on the existing release-test emulator. Screens 03–10
still show pre-2.0 UI and must not be uploaded until recaptured from the final
build; do not create another AVD.

1. `01-hero.png` — current field-tools menu, including TacMap Chat
2. `02-unit-sync.png` — current Unit Sync setup and relay disclosure
3. `03-symbols.png` — symbols and drawings
4. `04-recording.png` — user-started GPX route recording
5. `05-weather.png` — opt-in online weather/drone status
6. `06-basemaps.png` — Esri/OpenTopoMap choices (online gate required)
7. `07-import-export.png` — document interchange
8. `08-symbol-builder.png` — APP-6 symbol builder
9. `09-search.png` — local coordinate/mission search plus optional places
10. `10-pdfmap.png` — imported PDF/GeoPDF map

## Tablet screenshots — `tablet/` (1600×2560, portrait)
Upload under the relevant tablet screenshot sections if tablet distribution is
retained. The ten names match the phone set. None of the checked-in tablet
screenshots is approved for 2.0; recapture any submitted images from the final
build.

## Notes
- Phone shots are 1080×2100 and tablet shots are 1600×2560. Validate current
  Play Console size/count rules on upload.
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
  The current source defaults to version code 64 / versionName 2.0.0. Inject a
  newer unique code if this candidate has already been uploaded.
