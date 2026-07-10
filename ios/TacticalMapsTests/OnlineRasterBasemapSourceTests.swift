import XCTest
import MapKit
@testable import TacticalMaps

/// Guards the keyed-Esri swap. Asserts the composed tile URL, since a wrong host
/// or dropped token is the real regression risk, not a compile error.
final class OnlineRasterBasemapSourceTests: XCTestCase {

    private func tileURL(_ style: BasemapStyle) -> String {
        let overlay = OnlineRasterBasemapSource(style).makeOverlay()
        return overlay.url(forTilePath: MKTileOverlayPath(x: 3, y: 2, z: 4, contentScaleFactor: 1)).absoluteString
    }

    func testEsriTileURLUsesKeyedIbasemapsEndpointWithToken() throws {
        try XCTSkipUnless(EsriKey.isAvailable, "needs a build-injected Esri key")
        let s = tileURL(.esriSatellite)
        XCTAssertTrue(s.contains("ibasemaps-api.arcgis.com"), "must hit the keyed endpoint: \(s)")
        XCTAssertTrue(s.contains("token="), "must carry the token")
        XCTAssertFalse(s.contains("server.arcgisonline.com"), "must not hot-link the old endpoint")
        XCTAssertTrue(s.contains("/4/2/3"), "z/y/x order preserved")
    }

    func testTerrainNeedsNoKeyAndCarriesNoToken() {
        let s = tileURL(.terrain)
        XCTAssertTrue(s.contains("opentopomap.org"))
        XCTAssertFalse(s.contains("token="), "terrain must not append a token")
    }

    func testEsriStyleIsFlaggedAsKeyed() {
        XCTAssertTrue(BasemapStyle.esriSatellite.requiresEsriKey)
        XCTAssertFalse(BasemapStyle.terrain.requiresEsriKey)
    }
}
