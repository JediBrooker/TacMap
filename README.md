# TacticalMaps

Field-navigation prototype: tactical-style map with live MGRS, GeoPDF/calibrated-PDF
basemaps, and waypoint/drawing overlays exported as GeoJSON. iOS (SwiftUI + MapKit)
and Android (Kotlin + Compose + Google Maps) ship from one shared design.

## Repository layout

```
.
├── ios/         # SwiftUI app, XcodeGen-driven
├── android/     # Kotlin/Compose app, Gradle
├── samples/     # Test PDF (Holsworthy North 1:25,000)
└── docs/        # ARCHITECTURE.md — shared abstractions
```

## Prototype scope (what's wired up today)

| Feature                                          | iOS                        | Android                   |
|--------------------------------------------------|---------------------------|---------------------------|
| Apple/Google satellite fallback basemap          | ✅ MapKit                  | ✅ Google Maps Compose    |
| Live user location                               | ✅ CoreLocation           | ✅ FusedLocationProvider  |
| MGRS header                                      | ✅ NGA mgrs-ios           | ✅ NGA mgrs (Java)        |
| Crosshair browse mode                            | ✅                        | ✅                       |
| Centre-on-location button                        | ✅                        | ✅                       |
| Waypoint model + disk persistence + demo seeds  | ✅                        | ✅                       |
| GeoJSON export                                   | ✅ (preview screen)       | ✅ (no UI hook yet)       |
| MapSource / Fiduciary / Affine calibration code  | ✅ (math, stubs)          | ✅ (math, stubs)          |
| GeoPDF tag parsing                               | ⏳ stubbed                | ⏳ stubbed                |
| Fiduciary placement UI                           | ⏳ stubbed                | ⏳ stubbed                |
| Drawing layer                                    | ⏳ stubbed (toolbar btn)  | ⏳ stubbed (toolbar btn)  |
| GDAL pipeline for raster tiles                   | ❌ future                 | ❌ future                 |

---

## iOS — first-time setup

Xcode is required (Command Line Tools are not enough — SwiftUI previews and the
iOS simulator both need the full IDE).

```bash
# 1. Install Xcode from the App Store, then accept the license & components.
sudo xcode-select -s /Applications/Xcode.app/Contents/Developer
xcodebuild -runFirstLaunch

# 2. Install XcodeGen (a one-time tool that generates .xcodeproj from project.yml).
brew install xcodegen

# 3. Generate the Xcode project.
cd ios
xcodegen generate

# 4. Open it.
open TacticalMaps.xcodeproj
```

First Xcode build will resolve the `mgrs-ios` Swift Package (NGA's MGRS library).
Wait for the package graph to finish before pressing ▶.

### Running in the iOS Simulator
- Pick an iPhone 15 / iPad Pro target.
- Launch ▶. The app will prompt for location — grant **While Using**.
- In the Simulator menu, **Features › Location › Custom Location…** to drop a
  pin somewhere with a real MGRS grid (e.g. Holsworthy NSW: -33.99, 150.94).

### Importing the demo GeoPDF
- In the Simulator, drag `samples/Holsworthy_North_1-25000.pdf` onto the device
  window (it lands in Files).
- Tap the **Import PDF Map** button in TacticalMaps and pick it.
- (The PDF will load as a placeholder — calibration UI is stubbed pending the
  fiduciary-placement work in `docs/ARCHITECTURE.md`.)

---

## Android — first-time setup

Android Studio is required. The bundled JBR satisfies the Java requirement; you
don't need a separate JDK.

```bash
# 1. Launch Android Studio once and run the first-launch SDK wizard.
open -a "Android Studio"
# - Accept the standard SDK + emulator components.
# - When prompted, create an AVD (Pixel 7, API 34).

# 2. Get a Google Maps API key.
# https://developers.google.com/maps/documentation/android-sdk/get-api-key
# Then add it to ~/.gradle/gradle.properties (preferred, kept out of git):
#   MAPS_API_KEY=AIza…
# Or set it per-project in android/local.properties.

# 3. Open the project.
open -a "Android Studio" android
```

Studio will sync Gradle on first open — it downloads the wrapper jar, Android
build tools, Compose BOM, Maps Compose, and NGA `mgrs`. Then **Run ▶** on your
AVD or a connected device.

Without a Maps API key the map will render as a grey grid with a watermark, but
all other UI (MGRS header, crosshair, telemetry, waypoints) still works.

---

## Verifying with the simulator/emulator

1. Launch the app, grant location.
2. Confirm the MGRS header (top, monospaced green) shows your current grid.
3. Pan the map — the crosshair should appear and the header label should flip to
   **MGRS (Map Centre)**, updating live as you drag.
4. Tap **Centre on My Location** — crosshair clears, header returns to **Your Location**.
5. Open **Waypoints** (iOS): a demo set is seeded. Tap one to fly to it.
6. iOS: open **Export GeoJSON** to preview the FeatureCollection.

---

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the shared model, fiduciary
calibration math, and the planned GDAL ingest pipeline.
