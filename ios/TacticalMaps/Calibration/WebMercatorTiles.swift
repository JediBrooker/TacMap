import Foundation

/// Pure Web-Mercator (EPSG:3857) XYZ tile math — the slippy-map scheme MBTiles
/// and OSM/Google tiles use. Swift mirror of the Android `WebMercatorTiles`
/// (kept in lockstep). XYZ: tile (0,0) is the north-west corner, y increases south.
enum WebMercatorTiles {

    private static let maxLat = 85.05112878   // Web-Mercator clamp

    struct Box { let north, south, east, west: Double }

    struct Range {
        let z, minX, maxX, minY, maxY: Int
        var count: Int { max(0, maxX - minX + 1) * max(0, maxY - minY + 1) }
    }

    static func lonToTileX(_ lon: Double, _ z: Int) -> Double {
        (lon + 180.0) / 360.0 * Double(1 << z)
    }

    static func latToTileY(_ lat: Double, _ z: Int) -> Double {
        let clamped = min(max(lat, -maxLat), maxLat)
        let r = clamped * .pi / 180.0
        return (1.0 - log(tan(r) + 1.0 / cos(r)) / .pi) / 2.0 * Double(1 << z)
    }

    static func tileXToLon(_ x: Double, _ z: Int) -> Double {
        x / Double(1 << z) * 360.0 - 180.0
    }

    static func tileYToLat(_ y: Double, _ z: Int) -> Double {
        let n = .pi - 2.0 * .pi * y / Double(1 << z)
        return atan(sinh(n)) * 180.0 / .pi
    }

    static func tileBounds(_ z: Int, _ x: Int, _ y: Int) -> Box {
        Box(
            north: tileYToLat(Double(y), z),
            south: tileYToLat(Double(y + 1), z),
            east:  tileXToLon(Double(x + 1), z),
            west:  tileXToLon(Double(x), z)
        )
    }

    /// Inclusive integer tile range covering a WGS84 box at zoom z, clamped to grid.
    ///
    /// Antimeridian note: a box crossing ±180° (minLon > maxLon) yields minX >
    /// maxX, so `count` is 0 and the caller (`PDFTiler`) treats the bake as
    /// failed rather than tiling wrong ground. Full wrap-around tiling is
    /// intentionally unsupported — the linear calibration affine cannot represent
    /// the ±180° discontinuity (that needs unwrapped-longitude fiduciaries fixed
    /// upstream, not a tiler workaround).
    static func tileRange(minLat: Double, maxLat: Double,
                          minLon: Double, maxLon: Double, z: Int) -> Range {
        let maxIdx = (1 << z) - 1
        func clamp(_ v: Int) -> Int { min(max(v, 0), maxIdx) }
        let minX = clamp(Int(floor(lonToTileX(minLon, z))))
        let maxX = clamp(Int(floor(lonToTileX(maxLon, z))))
        let minY = clamp(Int(floor(latToTileY(maxLat, z))))   // north → smaller y
        let maxY = clamp(Int(floor(latToTileY(minLat, z))))
        return Range(z: z, minX: minX, maxX: maxX, minY: minY, maxY: maxY)
    }
}
