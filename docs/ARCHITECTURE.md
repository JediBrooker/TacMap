# TacticalMaps — Architecture

This document is the cross-platform brief. It captures the shared abstractions and
the math, so the iOS and Android teams can move in lockstep without diverging.

## 1. Shared model: store everything in WGS84

The single most important architectural choice: **all overlays (waypoints,
polylines, polygons) are stored in WGS84 (lat/lon)**. This is the format that
travels across every basemap:

```
           +-----------------------------+
           |  WGS84 overlay store         |  <-- ground truth (waypoints, lines)
           +--------------+--------------+
                          |
        +-----------------+-----------------+
        |                 |                 |
  +-----+-----+     +-----+-----+     +-----+--------+
  |  Apple/   |     |  GeoPDF   |     | Calibrated   |
  |  Google   |     |  source   |     | PDF source   |
  |  satellite|     |  (tags    |     | (fiduciaries |
  |  source   |     |   parsed) |     |  fit affine) |
  +-----------+     +-----------+     +--------------+
```

When the user swaps basemaps, no re-projection of overlays is required. MGRS is
presentation-only: it is computed on the fly from WGS84 via NGA's `mgrs-ios` /
`mgrs` (Java) libraries.

The `MapSource` abstraction (Swift protocol / Kotlin sealed interface) hides the
basemap implementation from everything else. Four concrete kinds:

- `AppleSatelliteMapSource` / `OpenStreetMapSourceAndroid` — the default
  fallback (Apple satellite on iOS, Google satellite on Android).
- `PDFMapSource(kind = .geoPDF)` — PDF with OGC GeoPDF tags; calibration parsed.
- `PDFMapSource(kind = .calibratedPDF)` — hand-calibrated via 3+ fiduciaries.
- `OfflineTileMapSource` / `OfflineTileMapSourceAndroid` — a sideloaded MBTiles
  raster pyramid served offline via `MKTileOverlay` (iOS) / a Google Maps
  `TileProvider` (Android).

## 2. Browse mode

The header MGRS reads either the **user's GPS fix** (default) or the **map
centre** (browse mode). Browse mode is entered when the user pans/pinches the
map and cleared by pressing **Centre on My Location**.

Detection is platform-specific but conceptually identical:

- iOS: `MKMapView` is wrapped in `UIViewRepresentable`; a `UIPanGestureRecognizer`
  / `UIPinchGestureRecognizer` sets a one-shot flag, which the next
  `regionDidChangeAnimated:` interprets as "user-driven".
- Android: `CameraPositionState.cameraMoveStartedReason == REASON_GESTURE` flags
  user-driven idle events.

## 3. Fiduciary calibration

For non-GeoPDF maps (or GeoPDFs whose tags we can't trust), the user places
**fiduciaries**: each is a point on the PDF page paired with a known MGRS grid
reference. With N ≥ 3 fiduciaries we fit a 2D affine transform from PDF page
coordinates to WGS84:

```
  lon = a*x + b*y + c
  lat = d*x + e*y + f
```

Six unknowns; each fiduciary gives two equations. For N = 3 the system is
exactly determined; for N > 3 we solve the normal equations
`(Aᵀ A) x = Aᵀ b` (closed-form least squares), independently for X and Y. We
then back-project each fiduciary, compute the equirectangular distance error,
and report an RMS residual in metres. The UI should surface this number — a
map whose RMS exceeds, say, 50 m on a 1:25,000 sheet is likely badly
calibrated and the user should add or replace fiduciaries.

Implementations:
- iOS: `Calibration/AffineTransform2D.swift` (`AffineFitter.fit`)
- Android: `calibration/AffineTransform2D.kt` (`AffineFitter.fit`)

Both use Cramer's rule for the 3×3 normal-equations solve — fine for prototype
scale, and avoids pulling in a linear-algebra dependency.

### Why affine, not projective?

A 6-DoF affine captures translation, rotation, scale, and shear. Across a single
1:25,000 sheet (≈ 7 km × 7 km), the distortion from ignoring earth curvature
and the source projection is sub-pixel for reasonable fiduciaries. For larger
maps or higher-precision work, swap in a projective (8-DoF homography) fit; the
`AffineTransform2D` interface is the swap-in seam.

## 4. Export

GeoJSON (RFC 7946) is the canonical export format: coordinates as
`[longitude, latitude]`, CRS implicit WGS84. Both platforms emit identical
output:

```json
{
  "type": "FeatureCollection",
  "generator": "TacticalMaps iOS prototype",
  "features": [
    {
      "type": "Feature",
      "id": "<uuid>",
      "geometry": { "type": "Point", "coordinates": [lon, lat] },
      "properties": { "name": "…", "kind": "camp", "elevation_m": 2345 }
    }
  ]
}
```

GPX and KML can be added later as alternate serialisers — they read the same
WGS84 model.

## 5. Planned: GeoPDF ingest pipeline (GDAL)

GeoPDFs ship two non-interchangeable conventions:

- **OGC GeoPDF** — the original, encoded in a `LGIDict` dictionary per page.
- **Adobe Geospatial** — ISO 32000 extension, encoded under `/Measure`.

Production-grade ingest requires both. The pragmatic plan:

1. Server-side (or local CLI) GDAL job rips the GeoPDF:
   - `gdalinfo` reads the projection + neat-line.
   - `gdal_translate` reprojects to EPSG:3857 (Web Mercator).
   - `gdal2tiles.py` generates an MBTiles raster pyramid.
2. The MBTiles file is sideloaded to the device.
3. On-device, `MKTileOverlay` (iOS) / `TileOverlay` (Android) serves the tiles.

Until that pipeline ships, the in-app PDF importer falls back to fiduciary
calibration.

The on-device serving half (step 3) is now implemented: the app reads a
sideloaded `.mbtiles` directly via `MBTilesStore` and serves it through
`MKTileOverlay` / a Google Maps `TileProvider`. Producing the MBTiles from a
GeoPDF is still an offline GDAL step the user runs on a desktop.

## 6. Open questions

- **Coordinate datum on PDFs**: most defence-issued 1:25,000 sheets use MGA94
  (GDA94) or MGA2020 (GDA2020), not WGS84. The difference is up to ~1.8 m, which
  matters for some uses. We should ask the user to flag the datum on import and
  apply the appropriate shift before storing fiduciary WGS84 coordinates.
- **Drawing layer schema**: GeoJSON `LineString` / `Polygon` are the obvious
  encoding, but we need to decide how to attach style (colour, dash pattern,
  width) — GeoJSON's `properties` bag is unstructured. A small in-house schema
  with a documented set of style keys is probably the right move.
- **Offline tiles**: sideloaded MBTiles are now served offline on both platforms
  (copied into the app sandbox on import). Open questions remain: how large do we
  let them get on a phone, and is an in-app tiler worth it so users don't need a
  desktop GDAL step? AppleSatellite/GoogleSatellite remain online-only.
