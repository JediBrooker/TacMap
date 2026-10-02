# TacMap 3.0 store media

Created 2 October 2026 from the integrated PDF/Sync candidate **3.0.0 (73)**,
commit `4e406f5`, using actual app screens on existing simulators/emulator.
The primary checkout's older app was not used for these captures. Capture
harnesses live in `scripts/store_capture/`; app changes are confined to an
ignored capture snapshot. The Android capture application has a separate
application ID to preserve the existing app's data.

## Current screenshot selection

Upload these **five current images per device category**, in this order:
`10-pdfmap.png`, `11-calibration.png`, `12-calibration-entry.png`,
`13-map-library.png`, `07-import-export.png`. They cover offline GeoPDF use,
manual calibration, datum/MGRS entry, the reported fit, retained maps and
interchange. They reuse the existing Archivo/JetBrains typography, dark grid,
reticle, device frame and green/amber/blue accents.

| Category | Directory | PNG dimensions |
| --- | --- | --- |
| iPhone | `ios/iphone-6.9` | 1320 × 2868 |
| iPad | `ios/ipad-13` | 2064 × 2752 |
| Android phone | `android/phone` | 1080 × 1920 |
| Android tablet | `android/tablet` | 1440 × 2560 |

Android output uses 9:16 portrait to meet the current large-screen guidance
and screenshot recommendation format.

Other screenshot files are historical assets, not a current 3.0 selection.
The two replaced slides are 07 and 10; new slides are 11–13. Unframed evidence
is retained under `raw/3.0.0`. The fit uses four well-separated printed UTM
intersections, entered as MGRS in NAD83 / zone 10. The displayed residual is
reported by TacMap; it does not establish the map's real-world accuracy.
USGS San Francisco North is public-domain source material. Android's fixture
normalizes that map to a renderer-compatible PDF and preserves its geospatial
metadata; a second copy omits that metadata to exercise manual calibration.

## Marketing edit: both platforms

The videos now use an original 120 BPM electronic score, animated benefit
headlines, beat-timed cuts and short dip dissolves. The landscape masters have
large, readable crops of the **actual app footage** beside the complete native
screen. The dark MGRS grid, Archivo/JetBrains typography and amber/blue/green
chapter colours match the screenshots. Native UI is present throughout;
there are no invented product screens or simulated app actions.

| File | Length | Format | Intended use |
| --- | --- | --- | --- |
| `ios/marketing/tacmap-features-3.0.mp4` | 90.00 s | 1920 × 1080 landscape | Complete iOS marketing master: website, social and presentations |
| `android/video/tacmap-features-3.0.mp4` | 120.00 s | 1920 × 1080 landscape | Complete Android marketing master and Google Play YouTube video |
| `ios/previews/iphone-6.9/*.mp4` | 3 × 30.00 s | 886 × 1920 portrait | App Store Connect iPhone previews |
| `ios/previews/ipad-13/*.mp4` | 3 × 30.00 s | 1200 × 1600 portrait | App Store Connect iPad previews |

The narrative is **Know the ground → Read the terrain → Plan the move → Move
together → Make it yours**, closing on night mode with “Your map. Your mission.”
Each platform uses its own real UI recordings from 3.0.0 (73).

## Feature coverage

All public feature groups appear in both full marketing masters. The iOS
App Store set covers the same groups across its three previews.

| Chapter | Features shown |
| --- | --- |
| Know the ground | Offline GeoPDF; PDF/MBTiles import; sheet datum; manual MGRS control points; live fit and per-point residual review; retained map library; multi-page PDF chooser; offline tile generation |
| Read the terrain | Live coordinates/elevation; MGRS grid and north-reference compass; coordinate/place search; distance/bearing and area measurements; elevation profile and line of sight; active GPS route recording and GPX workflow; weather/UAV advice; offline Sun/Moon |
| Plan the move | APP-6 unit builder; military/tactical/SAR/generic markers; routes and drawing styles; areas/fills; free-hand drawing; range rings; basemap/layer/label controls |
| Move together | Connected encrypted Unit Sync; room and individual Chat |
| Make it yours | GeoJSON/KML/KMZ/GPX and custom-pack interchange; language and coordinate options; privacy/background sharing; PIN/biometric lock; tour/help entry; night mode |

Some options are demonstrated through their real controls, including custom
pack import, biometric lock, language choice and guided-tour entry. This is a
marketing overview of every feature group, rather than a tutorial executing
every configuration. Heading Up needs a physical device heading sensor;
simulator footage shows the orientation controls and north-reference compass.
The UAV status displayed is advisory, as stated in the actual app screen.

## iOS: upload the three portrait cuts

Apple allows up to three previews per device size/localization, each 15–30
seconds. **Do not upload the 90-second landscape marketing master to App Store
Connect.** Use the three 30-second portrait cuts in this viewing/upload order:

1. `02-offline-maps.mp4` — Know the ground: PDF maps, calibration and offline tiles.
2. `03-navigation.mp4` — Read the terrain: navigation, measurement, route recording and field information.
3. `01-tactical-sync.mp4` — Plan and move together: symbology, drawings, collaboration, interchange and settings.

The existing filenames are retained to replace the previous assets. Each
cut has animated, muted-playback-friendly copy and the original score.
Apple cuts retain the complete native UI at its original aspect ratio, with
no device frames, magnified crops, lifestyle footage or standalone title cards.
The landscape marketing framing is used only in the full masters.

Encoding: H.264 High Level 4.0, progressive 30 fps, 11 Mb/s target CBR,
AAC stereo 48 kHz at 256 kb/s CBR, fast-start MP4, below 500 MB. Each file is
exactly 30.00 seconds, including its audio stream within that limit.

## Google Play: upload the Android marketing master to YouTube

The Android master is 120.00 seconds at 1920 × 1080, progressive 30 fps,
H.264 High with AAC stereo at 48 kHz / 256 kb/s CBR. It opens immediately
on the real map and shows actual app footage throughout. There are no black
sidebars, prices, rankings, award claims or download calls to action.

The current Play guidance does not prescribe a hard maximum video duration;
two minutes is an editorial choice. The real app remains visible for 100% of
the running time, meeting the recommended minimum 80% app experience.
Google Play accepts a **YouTube URL**, not the MP4 directly. Upload the file
as public or unlisted, allow embedding, disable monetization/ads, avoid age
restriction and copyright claims, then paste the full watch URL into the
listing preview-video field. No store or YouTube publication was performed.

## Music and editable sources

“TacMap — Ground / Move” is an original instrumental composition generated
locally from synthesised oscillators and percussion. It contains no licensed
library track, third-party samples, vocals or stock sound effects. The
reproducible score generator and motion-graphics renderer are in
`scripts/store_capture/marketing_videos.py`; editorial files with headline
copy, chapter order, focus regions and capture timestamps are in
`scripts/store_capture/storyboards/marketing/`. The source score is retained
as `marketing/tacmap-original-score.flac`.

## Verification and submission

`3.0.0-manifest.json` records dimensions, codec/profile, frame rate, exact
duration, file sizes and hashes. Video sidecars retain all scene timings,
copy, native source timestamps and screenshot-match evidence. Clean native
clips are checked against their captured UI before graphics are added. Final
scene contact sheets are inspected for headline layout, relevant UI visibility
and coverage. Audio is checked for an audible, unclipped stereo soundtrack.
This verification confirms the supplied assets; final acceptance remains
subject to store review of the submission.

Current rules consulted:
[Apple preview specifications](https://developer.apple.com/help/app-store-connect/reference/app-information/app-preview-specifications),
[Apple preview content guidance](https://developer.apple.com/app-store/app-previews/),
[Google Play listing asset guidance](https://support.google.com/googleplay/android-developer/answer/9866151?hl=en).
