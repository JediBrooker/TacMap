import XCTest
import CoreLocation
@testable import TacticalMaps

/// Tests the pure offline-fallback cache behind the elevation HUD: exact hits,
/// nearest-within-threshold (what keeps the readout alive with no signal), and
/// bounded eviction.
final class ElevationCacheTests: XCTestCase {

    private func coord(_ lat: Double, _ lon: Double) -> CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: lat, longitude: lon)
    }

    func testExactHitOnRoundedKey() {
        var cache = ElevationCache()
        cache.insert(coord(37.7749, -122.4194), metres: 120)
        // Same point to 4 dp → hit.
        XCTAssertEqual(cache.exact(coord(37.77491, -122.41944)), 120)
        // Far away → miss.
        XCTAssertNil(cache.exact(coord(37.9, -122.4)))
    }

    func testNearestWithinThresholdReturnsClosest() {
        var cache = ElevationCache()
        cache.insert(coord(10.005, 10.0), metres: 50)   // ~556 m north of query
        cache.insert(coord(10.02,  10.0), metres: 90)   // ~2224 m north of query
        let q = coord(10.0, 10.0)
        XCTAssertEqual(cache.nearest(to: q, within: 1000), 50)   // only the near one
        XCTAssertEqual(cache.nearest(to: q, within: 3000), 50)   // both in range, near one wins
        XCTAssertNil(cache.nearest(to: q, within: 100))          // none close enough
    }

    func testBoundedCacheEvictsOldest() {
        var cache = ElevationCache(capacity: 2)
        cache.insert(coord(1, 1), metres: 1)
        cache.insert(coord(2, 2), metres: 2)
        cache.insert(coord(3, 3), metres: 3)   // evicts (1,1)
        XCTAssertNil(cache.exact(coord(1, 1)))
        XCTAssertEqual(cache.exact(coord(2, 2)), 2)
        XCTAssertEqual(cache.exact(coord(3, 3)), 3)
    }

    func testReinsertRefreshesValueAndRecency() {
        var cache = ElevationCache(capacity: 2)
        cache.insert(coord(1, 1), metres: 1)
        cache.insert(coord(2, 2), metres: 2)
        cache.insert(coord(1, 1), metres: 11)  // refresh (1,1): new value, now most-recent
        cache.insert(coord(3, 3), metres: 3)   // evicts (2,2), not the refreshed (1,1)
        XCTAssertEqual(cache.exact(coord(1, 1)), 11)
        XCTAssertNil(cache.exact(coord(2, 2)))
        XCTAssertEqual(cache.exact(coord(3, 3)), 3)
    }
}
