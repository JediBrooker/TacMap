# TacticalMaps

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![iOS](https://img.shields.io/badge/iOS-16.0%2B-blue.svg)](#)
[![Swift](https://img.shields.io/badge/Swift-5.9-orange.svg)](#)
[![Android](https://img.shields.io/badge/Android-API%2026%2B-green.svg)](#)
[![iOS Build](https://github.com/JediBrooker/TacticalMaps/actions/workflows/ios.yml/badge.svg)](https://github.com/JediBrooker/TacticalMaps/actions/workflows/ios.yml)
[![Android Build](https://github.com/JediBrooker/TacticalMaps/actions/workflows/android.yml/badge.svg)](https://github.com/JediBrooker/TacticalMaps/actions/workflows/android.yml)

Field-navigation prototype: tactical-style map with live MGRS, GeoPDF/calibrated-PDF
basemaps, drawing overlays exportable as GeoJSON, and fiduciary calibration for
any PDF that lacks proper georeferencing. iOS (SwiftUI + MapKit) and Android
(Kotlin + Compose + Google Maps) ship from one repository.

<p align="center">
  <img src="docs/screenshots/01-main-sf.png" width="260" alt="Main view (live location, satellite)">
  &nbsp;
  <img src="docs/screenshots/02-pdf-basemap.png" width="260" alt="USGS US Topo PDF rendered as basemap">
  &nbsp;
  <img src="docs/screenshots/06-drawing-mode.png" width="260" alt="Live drawing mode">
</p>

<details>
<summary>More screenshots</summary>

<p align="center">
  <img src="docs/screenshots/03-hamburger-menu.png" width="260" alt="Hamburger menu">
  &nbsp;
  <img src="docs/screenshots/05-drawings-panel.png" width="260" alt="Drawings panel">
  &nbsp;
  <img src="docs/screenshots/07-layers.png" width="260" alt="Layers sheet (USGS PDF loaded)">
</p>

<p align="center">
  <img src="docs/screenshots/08-search.png" width="260" alt="Search sheet">
  &nbsp;
  <img src="docs/screenshots/04-about-credits.png" width="260" alt="About & Credits">
</p>

</details>

The basemap screenshot (£2) is the **USGS San Francisco North** 1:24,000 US Topo
quadrangle (public domain) rendered live over the satellite. Run
`scripts/fetch_samples.sh` to drop the same PDF into `samples/` for testing.

---

## What it does today

### Live navigation HUD

- **Live MGRS** in a tactical-green monospace at the top, spaced as
  `56HLH 13225 37516`. Header flips between **Your Location** (GPS fix) and
  **Map Centre** (when you pan away) automatically.
- **WGS84 lat/lon** + **elevation** (metres above sea level) live at the
  crosshair, fetched from Open-Meteo's Copernicus DEM (≈30 m global resolution)
  on a 400 ms debounce so we don't hammer the network during a pan.
- **NATO mils compass** (6400 per circle). The N marker rotates with the map
  so it always points to true north; the 4-digit mils readout in the lower
  half stays static. Tap to snap back to north-up.
- **Centre-pivot rotation** — the default `MKMapView` rotation drags the
  centre around with your fingers. Ours overrides it so the map spins in
  place around the screen centre.

### Drawing & waypoints

- Drop **waypoints** with kind (camp, water, observation point, drop zone,
  hazard) and elevation.
- Draw **polylines, polygons, and points** — tap successive points on the
  map, undo last vertex, finish to commit. In-progress shapes render dashed.
- **Export everything as GeoJSON** following the [Mapbox simplestyle-spec]
  (stroke / stroke-width / fill / fill-opacity / marker-color / marker-symbol
  with [Maki icon] names). Opens directly in **geojson.io, GitHub gists,
  Mapbox, Felt, Leaflet, QGIS, ArcGIS, Google Earth**.

[Mapbox simplestyle-spec]: https://github.com/mapbox/simplestyle-spec
[Maki icon]: https://github.com/mapbox/maki

### GeoPDF basemap

- Import any **GeoPDF** via the Files app. The PDF replaces the satellite
  basemap and stays anchored to its true geographic bounds when you pan / zoom
  / rotate.
- **LGIDict parser** handles the OGC GeoPDF format used by ADF, AUSLIG, USGS,
  and most government topo PDFs:
  - Multi-entry LGIDicts (picks the one with `/Description (Layers)`)
  - PDF-string-encoded reals (e.g. `(135.83)` instead of `135.83`)
  - Projections: **LL** (geographic), **UT** (UTM), **TC** (Transverse
    Mercator routed through UTM when the central meridian matches a zone)
- **Adobe Geospatial fallback** for newer PDFs that use `/VP/Measure` +
  `/GPTS` instead of LGIDict.
- **Fiduciary calibration UI** for any PDF without proper metadata — tap
  3 known features on the PDF, enter their MGRS, and `AffineFitter` solves
  a least-squares affine to re-derive bounds. Shows RMS residual in metres
  so you know how trustworthy the fit is.

### Offline raster basemap (MBTiles)

- Sideload a **`.mbtiles`** raster pyramid (e.g. `gdal_translate` +
`gdal2tiles.py` of any GeoPDF / raster) and the app serves it through a tile
overlay with **no network** — the real offline-field path, and the
ToS-compliant alternative to caching Apple/Google's own tiles. Import via
**☰ → Import Offline Tiles**; the bounds metadata frames the camera, and the
Layers sheet lets you unload it. iOS + Android.

### Search

- **Place name / address** via `MKLocalSearch`, biased toward the current
  camera area.
- **Full MGRS** — type `56HLH 13225 37516`, jump straight there.
- **Partial grid** — type just **4 / 6 / 8 / 10 figures** (e.g. `1885`) and
  we resolve against your current GZD + 100km square prefix, then drop
  you at the centre of the implied square (1 km / 100 m / 10 m / 1 m
  precision respectively).
- Crash-safe: regex pre-validates MGRS shape before calling NGA's parser
  (which used to `fatalError` on partial input).

---

## Repository layout

```
.
├── ios/                            SwiftUI app, XcodeGen-driven
│   ├── project.yml                 → .xcodeproj generation
│   ├── TacticalMaps/               app source
│   └── Vendor/mgrs-ios/            vendored fork with a 4-line Snyder UTM patch
├── android/                        Kotlin + Compose, Gradle
├── docs/
│   ├── ARCHITECTURE.md             shared design notes
│   ├── PRIVACY_POLICY.md           public privacy policy (host this)
│   ├── APPSTORE_CHECKLIST.md       submission checklist
│   └── screenshots/                README hero images
├── scripts/
│   └── generate_icon.swift         re-generate the 1024×1024 App Store icon
└── samples/                        (intentionally empty in the public repo)
```

---

## iOS — build & run

```bash
# 1. Tools (one-off)
brew install xcodegen
sudo xcode-select -s /Applications/Xcode.app/Contents/Developer
xcodebuild -runFirstLaunch

# 2. Generate the Xcode project
cd ios
xcodegen generate

# 3. Open
open TacticalMaps.xcodeproj
```

First build resolves Swift packages (mgrs is vendored, but pure-Swift deps
still download). Pick an iPhone simulator and press ▶.

**Generate the App Store icon** (if you tweak the design in
`scripts/generate_icon.swift`):

```bash
swift scripts/generate_icon.swift
```

---

## Android — build & run

```bash
# 1. Tools (one-off)
brew install --cask android-studio android-commandlinetools
open -a "Android Studio"   # Run the first-launch SDK wizard + create an AVD

# 2. Get a Google Maps API key
#    https://developers.google.com/maps/documentation/android-sdk/get-api-key
#    Enable "Maps SDK for Android" on the project. Restrict the key by
#    Android package (com.tacticalmaps) + debug + release SHA-1.
#    Add the key to android/local.properties (gitignored):
#       MAPS_API_KEY=AIza…
#    (Falls back to the MAPS_API_KEY env var if the property is unset.)

# 3. Open in Studio
open -a "Android Studio" android
```

Without an API key the map will render as a grey grid + watermark, but every
other UI element still works.

---

## Testing

Pure-logic unit tests run on both platforms and gate CI:

```bash
# iOS — XCTest (affine fit, MGRS, GeoJSON geometry, MBTiles, map geometry, …)
cd ios && xcodegen generate
xcodebuild test -scheme TacticalMaps -destination 'platform=iOS Simulator,name=iPhone 16 Pro'

# Android — JVM unit tests (no emulator needed)
cd android && ./gradlew testDebugUnitTest
```

Cross-platform invariants (the affine solve, MGRS formatting, GeoJSON geometry)
are pinned by shared golden vectors in [`testdata/`](testdata/) that **both**
suites load, so the Swift and Kotlin ports can't silently drift.

---

## Architecture overview

The single most important architectural choice: **all overlays (waypoints,
drawings, fiduciaries) are stored in WGS84**. MGRS is presentation-only,
computed on the fly via NGA's `mgrs-ios`. This means swapping basemaps
(satellite ↔ GeoPDF ↔ calibrated PDF) never requires re-projecting overlays.

Full design + math in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

---

## Roadmap

- **Wave 2 projections** — Lambert Conformal Conic (French IGN, Canadian
  NRCan, US state plane), arbitrary-central-meridian TM (UK OSGB36, NZ NZTM),
  non-WGS84 datum shifts.
- **Per-PDF fiduciary library** — the *active* calibrated PDF's fiduciaries +
  affine already persist across launches (`PDFSessionStore`). A keyed library so
  switching between several PDFs remembers each one's calibration is still TODO.
- **Datum shift** — calibration now lets you flag the sheet's datum (WGS84 /
  GDA94 / GDA2020) and shifts fiduciaries to WGS84 via the ICSM Helmert; Lambert
  Conformal Conic + arbitrary-TM datum work remains (see Wave 2 above).
- **Route logging** — the iOS `UIBackgroundModes: [location]` declaration is
  in place; logger UI + GPX export TBD.
- **iCloud sync** for waypoints + drawings.
- **Android feature parity** — drawing, search, waypoints + APP-6 symbols,
  GeoPDF import & fiduciary calibration, GeoJSON import/export, and the offline
  MBTiles basemap are all wired on Android now. The main iOS-only item left is
  the live DEM elevation readout in the HUD.

---

## App Store status

The iOS build is App-Store-ready in terms of assets:

- 1024×1024 icon, launch screen, acknowledgements view all in place
- Privacy policy at [`docs/PRIVACY_POLICY.md`](docs/PRIVACY_POLICY.md)
- Step-by-step submission checklist at
  [`docs/APPSTORE_CHECKLIST.md`](docs/APPSTORE_CHECKLIST.md)

It has not been submitted yet — if you do, see the checklist for the
Apple-side steps (Developer Program enrolment, name reservation, screenshots,
TestFlight).

---

## Privacy

We collect **nothing**. No accounts, no telemetry, no third-party SDKs, no
advertising IDs. Only outbound HTTPS calls are to Apple's Maps service (basemap
tiles + search) and Open-Meteo (elevation). Full disclosure in
[`docs/PRIVACY_POLICY.md`](docs/PRIVACY_POLICY.md).

---

## License

MIT — see [LICENSE](LICENSE). Includes vendored NGA `mgrs-ios` (MIT)
with a small Snyder UTM patch for Xcode 26 compatibility.
