# Store screenshot coverage: PDF and calibration

Updated 2 October 2026. **The coverage gap is closed in the new 3.0 selection.**
The initial audit found only GeoPDF map views, no calibration captures, and an
Android phone import/export slide containing the wrong screen. New native app
captures replace that mismatch and add calibration coverage for all four sizes.

| Feature | Current slide | iPhone | iPad | Android phone | Android tablet |
| --- | --- | --- | --- | --- | --- |
| PDF and offline tile import controls | 07 | Yes | Yes | Yes | Yes |
| Imported GeoPDF with live MGRS grid | 10 | Yes | Yes | Yes | Yes |
| Manual calibration with four control points | 11 | Yes | Yes | Yes | Yes |
| App-reported fit residual and Finish control | 11 | Yes | Yes | Yes | Yes |
| MGRS entry and printed-map datum | 12 | Yes | Yes | Yes | Yes |
| Retained PDF and calibration library | 13 | Yes | Yes | Yes | Yes |

The images use the existing store theme and their documented dimensions.
Raw captures, hashes and dimensions accompany the assets. The fit value is
actual app output; it is not a claim of surveyed positional accuracy.

Captures come from the integrated **3.0.0 (73)** candidate at `4e406f5`, rather
than the older primary checkout. Existing simulators and the existing Android
emulator were reused. App-owned capture data was isolated/backed up. The
public-domain USGS San Francisco North map supplies both georeferenced and
metadata-free fixtures.

The new previews also demonstrate PDF page selection and offline tile
generation, alongside calibration and all other feature groups. See the
[complete asset, coverage and submission guide](FEATURE_VIDEO.md) for the
five-image upload selection, video files and official store requirements.
Previous 2.0 approvals are historical; these files are new 3.0 media prepared
for review and upload, not evidence of store approval or publication.
