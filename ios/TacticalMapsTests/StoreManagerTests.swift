import XCTest
@testable import TacticalMaps

@MainActor
final class StoreManagerTests: XCTestCase {
    private struct QueryUnavailable: Error {}

    func testAuthoritativeNoEntitlementClearsVerifiedCache() async {
        var cacheWrites: [Bool] = []
        let manager = StoreManager(
            entitlementQuery: { false },
            readCachedEntitlement: { true },
            writeCachedEntitlement: { cacheWrites.append($0) }
        )

        XCTAssertTrue(manager.isPurchased)
        let result = await manager.refreshEntitlement()

        XCTAssertEqual(result, .authoritative(false))
        XCTAssertFalse(manager.isPurchased)
        XCTAssertEqual(cacheWrites, [false])
    }

    func testUnavailableQueryRetainsLastVerifiedCache() async {
        var cacheWrites: [Bool] = []
        let manager = StoreManager(
            entitlementQuery: { throw QueryUnavailable() },
            readCachedEntitlement: { true },
            writeCachedEntitlement: { cacheWrites.append($0) }
        )

        let result = await manager.refreshEntitlement()

        XCTAssertEqual(result, .unavailable)
        XCTAssertTrue(manager.isPurchased)
        XCTAssertTrue(cacheWrites.isEmpty, "an inconclusive query must not rewrite the verified cache")
    }

    func testVerifiedEntitlementGrantsAndCachesUnlock() async {
        var cacheWrites: [Bool] = []
        let manager = StoreManager(
            entitlementQuery: { true },
            readCachedEntitlement: { false },
            writeCachedEntitlement: { cacheWrites.append($0) }
        )

        let result = await manager.refreshEntitlement()

        XCTAssertEqual(result, .authoritative(true))
        XCTAssertTrue(manager.isPurchased)
        XCTAssertEqual(cacheWrites, [true])
    }
}
