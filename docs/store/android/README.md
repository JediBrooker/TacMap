# Google Play store assets

**3.0 assets (2 October 2026):** five screenshots each for phone and tablet
cover GeoPDF, calibration, MGRS/datum entry, fit and retained maps.
Upload order: **10, 11, 12, 13, 07**. The marketing video is on YouTube
(https://www.youtube.com/watch?v=JePjVg2b77E) and set as the listing video.
See the [media and upload guide](../FEATURE_VIDEO.md) and
[coverage audit](../PDF_CALIBRATION_AUDIT.md).

Publish with `scripts/play_publisher.py` (Play Developer API, keyless gcloud
impersonation; see the script header).

## Hi-res icon
- `play-icon-512.png`: **512x512** (required). Regenerate with
  `python3 scripts/generate_play_icon.py`.

## Feature graphic
- `feature-graphic.png`: **1024x500** (required). Regenerate with
  `python3 scripts/generate_feature_graphic.py`.

## Screenshots
- `phone/`: 1080x1920 (9:16 portrait)
- `tablet/`: 1440x2560, uploaded to both the 7" and 10" tablet slots

## Notes
- Listing copy lives in `docs/store/localizations/*.json`; export with
  `scripts/export_store_localizations.py`.
- Still human-owned in Play Console: privacy-policy URL, Data Safety,
  foreground-service/location, target-audience and content-rating forms.
- Signed bundle: `cd android && ./gradlew :app:bundleRelease` (see
  [`android/PLAY_STORE_PREP.md`](../../../android/PLAY_STORE_PREP.md)); bump
  versionCode every build.
