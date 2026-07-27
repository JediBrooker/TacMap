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
            writeCachedEntitlement: { cacheWrites.append($0) },
            presentOfferCodeRedemption: {}
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
            writeCachedEntitlement: { cacheWrites.append($0) },
            presentOfferCodeRedemption: {}
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
            writeCachedEntitlement: { cacheWrites.append($0) },
            presentOfferCodeRedemption: {}
        )

        let result = await manager.refreshEntitlement()

        XCTAssertEqual(result, .authoritative(true))
        XCTAssertTrue(manager.isPurchased)
        XCTAssertEqual(cacheWrites, [true])
    }

    func testStartingPurchaseUIRefreshesOutsideAppEntitlement() async {
        var cacheWrites: [Bool] = []
        let manager = StoreManager(
            entitlementQuery: { true },
            readCachedEntitlement: { false },
            writeCachedEntitlement: { cacheWrites.append($0) },
            presentOfferCodeRedemption: {}
        )

        await manager.start()

        XCTAssertTrue(manager.isPurchased)
        XCTAssertEqual(cacheWrites, [true])
    }

    func testSuccessfulOfferCodeRedemptionRefreshesUnlock() async {
        var sheetPresented = false
        var cacheWrites: [Bool] = []
        let manager = StoreManager(
            entitlementQuery: { true },
            readCachedEntitlement: { false },
            writeCachedEntitlement: { cacheWrites.append($0) },
            presentOfferCodeRedemption: { sheetPresented = true }
        )

        await manager.redeemOfferCode()

        XCTAssertTrue(sheetPresented)
        XCTAssertTrue(manager.isPurchased)
        XCTAssertEqual(cacheWrites, [true])
        XCTAssertNil(manager.redemptionOutcome)
        XCTAssertFalse(manager.redeeming)
    }

    func testOfferCodePresentationFailureShowsFeedback() async {
        let manager = StoreManager(
            entitlementQuery: { false },
            readCachedEntitlement: { false },
            writeCachedEntitlement: { _ in },
            presentOfferCodeRedemption: { throw QueryUnavailable() }
        )

        await manager.redeemOfferCode()

        XCTAssertFalse(manager.isPurchased)
        XCTAssertNotNil(manager.redemptionOutcome)
        XCTAssertFalse(manager.redeeming)
    }
}
