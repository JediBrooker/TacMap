# App Store screenshots

> Listing copy lives in `docs/store/localizations/*.json` (see also
> [`docs/STORE_LISTING.md`](../../STORE_LISTING.md)).

**3.0 assets (2 October 2026):** five screenshots per device cover GeoPDF,
manual calibration, MGRS/datum entry, reported fit and saved maps.
Upload order: **10, 11, 12, 13, 07**. App Store Connect also has three
30-second previews per device size (in order 02-offline-maps,
03-navigation, 01-tactical-sync). Video files aren't kept in git; see the
[media and upload guide](../FEATURE_VIDEO.md) and
[coverage audit](../PDF_CALIBRATION_AUDIT.md).

Publish with `scripts/asc_publisher.py` (needs `ASC_KEY_ID` / `ASC_ISSUER`
in the env and the .p8 in `~/.appstoreconnect/private_keys/`).

## Sizes
- `iphone-6.9/`: 1320x2868 (APP_IPHONE_67, mandatory)
- `ipad-13/`: 2064x2752 (APP_IPAD_PRO_3GEN_129, required while universal)
- `paywall.png`: IAP review screenshot

## Capture
Capture harnesses live in `scripts/store_capture/`. Reuse an existing
simulator, don't create screenshot-specific clones.

## Review checklist
- Online maps/weather/places need their explicit OPSEC gates visible.
- Reject any screenshot claiming the relay "only ever sees ciphertext"; the
  disclosure must say routing, session and traffic metadata stay visible.
- Import/export captures must say **Export All Mission Objects**.
- Any connected Unit Sync capture must show the TacMap Chat shortcut.
