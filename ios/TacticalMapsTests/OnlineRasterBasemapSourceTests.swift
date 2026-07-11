import XCTest
import MapKit
@testable import TacticalMaps

/// Guards the 4-basemap wiring. Asserts the composed tile URL + tile size, since
/// a wrong host, dropped token, or tile-size mismatch (Esri static = 512px) is
/// the real regression risk, not a compile error. Mirrors Android's
/// RasterTileProviderTest.
final class OnlineRasterBasemapSourceTests: XCTestCase {

    private func overlay(_ style: BasemapStyle) -> MKTileOverlay {
        OnlineRasterBasemapSource(style).makeOverlay()
    }
    private func tileURL(_ style: BasemapStyle) -> String {
        overlay(style).url(forTilePath: MKTileOverlayPath(x: 3, y: 2, z: 4, contentScaleFactor: 1)).absoluteString
    }

    func testEsriSatelliteKeyed256pxIbasemaps() throws {
        try XCTSkipUnless(EsriKey.isAvailable, "needs a build-injected Esri key")
        let s = tileURL(.esriSatellite)
        XCTAssertTrue(s.contains("ibasemaps-api.arcgis.com"), s)
        XCTAssertTrue(s.contains("World_Imagery"))
        XCTAssertTrue(s.contains("token="))
        XCTAssertFalse(s.contains("server.arcgisonline.com"))
        XCTAssertTrue(s.contains("/4/2/3"), "z/y/x order")
        XCTAssertEqual(overlay(.esriSatellite).tileSize, CGSize(width: 256, height: 256))
    }

    func testEsriTopoKeyed512pxStaticOutdoor() throws {
        try XCTSkipUnless(EsriKey.isAvailable)
        let s = tileURL(.esriTopo)
        XCTAssertTrue(s.contains("static-map-tiles-api.arcgis.com"), s)
        XCTAssertTrue(s.contains("/arcgis/outdoor/"))
        XCTAssertTrue(s.contains("token="))
        XCTAssertEqual(overlay(.esriTopo).tileSize, CGSize(width: 512, height: 512))
    }

    func testOsmStreetKeyedEsriOpenStyleNotOsmOrg() throws {
        try XCTSkipUnless(EsriKey.isAvailable)
        let s = tileURL(.osmStreet)
        XCTAssertTrue(s.contains("static-map-tiles-api.arcgis.com"))
        XCTAssertTrue(s.contains("/open/osm-style/"))
        XCTAssertTrue(s.contains("token="))
        XCTAssertFalse(s.contains("tile.openstreetmap.org"), "must not hit the community OSM server")
        XCTAssertEqual(overlay(.osmStreet).tileSize, CGSize(width: 512, height: 512))
    }

    func testOsmTopoCommunityOpenTopoMapNoToken() {
        let s = tileURL(.osmTopo)
        XCTAssertTrue(s.contains("tile.opentopomap.org"))
        XCTAssertFalse(s.contains("token="), "community source carries no token")
        XCTAssertEqual(overlay(.osmTopo).tileSize, CGSize(width: 256, height: 256))
    }

    func testKeyFlagsAndAttribution() {
        XCTAssertTrue(BasemapStyle.esriSatellite.requiresEsriKey)
        XCTAssertTrue(BasemapStyle.esriTopo.requiresEsriKey)
        XCTAssertTrue(BasemapStyle.osmStreet.requiresEsriKey)
        XCTAssertFalse(BasemapStyle.osmTopo.requiresEsriKey, "the one community source needs no key")
        for style in BasemapStyle.allCases {
            XCTAssertFalse(style.attribution.isEmpty, "\(style) needs attribution")
        }
    }

    func testDefaultIsEsriSatelliteWhenKeyed() throws {
        try XCTSkipUnless(EsriKey.isAvailable)
        XCTAssertEqual(OnlineRasterBasemapSource.defaultStyle, .esriSatellite)
    }
}
