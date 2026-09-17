import XCTest
@testable import TacticalMaps

@MainActor
final class StoreManagerTests: XCTestCase {
    private struct QueryUnavailable: Error {}
    private struct CacheUnavailable: Error {}
    private struct PurchaseUnavailable: Error {}

    private actor BoolGate {
        private var continuation: CheckedContinuation<Bool, Never>?
        private var pendingValue: Bool?

        func wait() async -> Bool {
            if let pendingValue {
                self.pendingValue = nil
                return pendingValue
            }
            return await withCheckedContinuation { continuation = $0 }
        }

        func open(_ value: Bool) {
            if let continuation {
                continuation.resume(returning: value)
                self.continuation = nil
            } else {
                pendingValue = value
            }
        }
    }

    private actor Counter {
        private var count = 0

        func increment() -> Int {
            count += 1
            return count
        }

        func value() -> Int { count }
    }

    private func noUpdates(_ manager: StoreManager) -> Task<Void, Never> {
        Task { }
    }

    private func waitUntil(_ condition: () -> Bool) async -> Bool {
        for _ in 0..<1_000 {
            if condition() { return true }
            await Task.yield()
        }
        return condition()
    }

    private func waitUntil(_ condition: () async -> Bool) async -> Bool {
        for _ in 0..<1_000 {
            if await condition() { return true }
            await Task.yield()
        }
        return await condition()
    }

    private func offer(
        price: String,
        result: StoreManager.PurchaseResult = .userCancelled
    ) -> StoreManager.ProductOffer {
        StoreManager.ProductOffer(displayPrice: price) { result }
    }

    func testAuthoritativeNoEntitlementClearsVerifiedCache() async {
        var cacheWrites: [Bool] = []
        let manager = StoreManager(
            entitlementQuery: { false },
            readCachedEntitlement: { true },
            writeCachedEntitlement: { cacheWrites.append($0) },
            presentOfferCodeRedemption: {},
            makeUpdatesTask: noUpdates
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
            presentOfferCodeRedemption: {},
            makeUpdatesTask: noUpdates
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
            presentOfferCodeRedemption: {},
            makeUpdatesTask: noUpdates
        )

        let result = await manager.refreshEntitlement()

        XCTAssertEqual(result, .authoritative(true))
        XCTAssertTrue(manager.isPurchased)
        XCTAssertEqual(cacheWrites, [true])
    }

    func testLifetimeStartRefreshesOnceAndListenerStartsForCachedPurchaser() async {
        var cacheWrites: [Bool] = []
        var queryCount = 0
        var listenerStarts = 0
        let manager = StoreManager(
            entitlementQuery: {
                queryCount += 1
                return true
            },
            readCachedEntitlement: { true },
            writeCachedEntitlement: { cacheWrites.append($0) },
            presentOfferCodeRedemption: {},
            makeUpdatesTask: { _ in
                listenerStarts += 1
                return Task { }
            }
        )

        XCTAssertEqual(listenerStarts, 1, "listener must start during app-lifetime manager construction")
        await manager.start()
        await manager.start()
        await manager.appDidBecomeActive()

        XCTAssertTrue(manager.isPurchased)
        XCTAssertEqual(cacheWrites, [true])
        XCTAssertEqual(queryCount, 1)
        XCTAssertEqual(listenerStarts, 1)
    }

    func testLaunchAndActiveRefreshesCoalesceWhileQueryIsInFlight() async {
        let queryGate = BoolGate()
        var queryCount = 0
        let manager = StoreManager(
            entitlementQuery: {
                queryCount += 1
                return await queryGate.wait()
            },
            readCachedEntitlement: { false },
            writeCachedEntitlement: { _ in },
            presentOfferCodeRedemption: {},
            makeUpdatesTask: noUpdates,
            monotonicNow: { 100 }
        )

        let launch = Task { await manager.start() }
        let launchQueryStarted = await waitUntil { queryCount == 1 }
        XCTAssertTrue(launchQueryStarted)
        let active = Task { await manager.appDidBecomeActive() }
        for _ in 0..<20 { await Task.yield() }

        XCTAssertEqual(queryCount, 1, "lifecycle callers must share the in-flight query")
        await queryGate.open(true)
        await launch.value
        await active.value
        XCTAssertTrue(manager.isPurchased)
    }

    func testForegroundRefreshUsesInjectedMonotonicFifteenMinuteCooldown() async {
        var now: TimeInterval = 1_000
        var queryCount = 0
        let manager = StoreManager(
            entitlementQuery: {
                queryCount += 1
                return true
            },
            readCachedEntitlement: { false },
            writeCachedEntitlement: { _ in },
            presentOfferCodeRedemption: {},
            makeUpdatesTask: noUpdates,
            monotonicNow: { now }
        )

        await manager.start()
        await manager.appDidBecomeActive()
        now += StoreManager.foregroundRefreshCooldown - 1
        await manager.appDidBecomeActive()
        XCTAssertEqual(queryCount, 1)

        now += 1
        await manager.appDidBecomeActive()
        XCTAssertEqual(queryCount, 2, "the cooldown boundary must permit a new query")
    }

    func testCheckAgainBypassesCooldownAndKeepsActionableUnavailableFeedback() async {
        var queryCount = 0
        let manager = StoreManager(
            entitlementQuery: {
                queryCount += 1
                throw QueryUnavailable()
            },
            readCachedEntitlement: { true },
            writeCachedEntitlement: { _ in },
            presentOfferCodeRedemption: {},
            makeUpdatesTask: noUpdates,
            monotonicNow: { 100 }
        )

        await manager.start()
        manager.storeIssue = StoreManager.StoreIssue(
            kind: .purchaseFailed,
            message: "Previous issue",
            retryable: true,
            purchaseOperationID: nil
        )
        await manager.checkEntitlementAgain()

        XCTAssertEqual(queryCount, 2, "an explicit check must bypass the lifecycle cooldown")
        XCTAssertTrue(manager.isPurchased)
        XCTAssertNotNil(manager.storeIssue)
        XCTAssertNotEqual(manager.storeIssue?.message, "Previous issue")
        XCTAssertTrue(manager.storeIssue?.message.contains("still couldn't verify") == true)
    }

    func testRestoreForcesPostSyncQueryAndStalePreSyncResultCannotOverwriteIt() async {
        let preSyncQuery = BoolGate()
        var queryCount = 0
        var syncCount = 0
        var cacheWrites: [Bool] = []
        let manager = StoreManager(
            entitlementQuery: {
                queryCount += 1
                if queryCount == 1 { return await preSyncQuery.wait() }
                return true
            },
            readCachedEntitlement: { false },
            writeCachedEntitlement: { cacheWrites.append($0) },
            presentOfferCodeRedemption: {},
            makeUpdatesTask: noUpdates,
            synchronizeAppStore: { syncCount += 1 },
            monotonicNow: { 100 }
        )

        let lifecycle = Task { await manager.appDidBecomeActive() }
        let preSyncQueryStarted = await waitUntil { queryCount == 1 }
        XCTAssertTrue(preSyncQueryStarted)
        let restore = Task { await manager.restore() }
        let postSyncQueryStarted = await waitUntil { queryCount == 2 }
        XCTAssertTrue(postSyncQueryStarted)
        await restore.value

        XCTAssertEqual(syncCount, 1)
        XCTAssertTrue(manager.isPurchased)
        XCTAssertEqual(cacheWrites, [true])

        await preSyncQuery.open(false)
        await lifecycle.value
        XCTAssertTrue(manager.isPurchased, "the older pre-sync query must not revoke the restored unlock")
        XCTAssertEqual(cacheWrites, [true], "the stale result must not reach durable state")
    }

    func testSuccessfulOfferCodeRedemptionRefreshesUnlock() async {
        var sheetPresented = false
        var cacheWrites: [Bool] = []
        let manager = StoreManager(
            entitlementQuery: { true },
            readCachedEntitlement: { false },
            writeCachedEntitlement: { cacheWrites.append($0) },
            presentOfferCodeRedemption: { sheetPresented = true },
            makeUpdatesTask: noUpdates
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
            presentOfferCodeRedemption: { throw QueryUnavailable() },
            makeUpdatesTask: noUpdates
        )

        await manager.redeemOfferCode()

        XCTAssertFalse(manager.isPurchased)
        XCTAssertNotNil(manager.redemptionOutcome)
        XCTAssertFalse(manager.redeeming)
    }

    func testRefreshCacheFailurePreservesKnownGoodUnlock() async {
        let manager = StoreManager(
            entitlementQuery: { false },
            readCachedEntitlement: { true },
            writeCachedEntitlement: { _ in throw CacheUnavailable() },
            presentOfferCodeRedemption: {},
            makeUpdatesTask: noUpdates
        )

        let result = await manager.refreshEntitlement()

        XCTAssertEqual(result, .persistenceFailed)
        XCTAssertTrue(manager.isPurchased, "failed cache removal must not publish a revocation")
        XCTAssertNotNil(manager.storeIssue)
    }

    func testVerifiedPurchaseCachesThenPublishesThenFinishes() async {
        var events: [String] = []
        var manager: StoreManager!
        manager = StoreManager(
            entitlementQuery: { false },
            readCachedEntitlement: { false },
            writeCachedEntitlement: { purchased in
                XCTAssertTrue(purchased)
                events.append("cache")
            },
            presentOfferCodeRedemption: {},
            makeUpdatesTask: noUpdates
        )

        let processed = await manager.processVerifiedEntitlementUpdate(purchased: true) {
            events.append(manager.isPurchased ? "published" : "not-published")
            events.append("finish")
        }

        XCTAssertTrue(processed)
        XCTAssertTrue(manager.isPurchased)
        XCTAssertEqual(events, ["cache", "published", "finish"])
    }

    func testVerifiedPurchaseCacheFailureLeavesTransactionUnfinished() async {
        var finished = false
        let manager = StoreManager(
            entitlementQuery: { false },
            readCachedEntitlement: { false },
            writeCachedEntitlement: { _ in throw CacheUnavailable() },
            presentOfferCodeRedemption: {},
            makeUpdatesTask: noUpdates
        )

        let processed = await manager.processVerifiedEntitlementUpdate(purchased: true) {
            finished = true
        }

        XCTAssertFalse(processed)
        XCTAssertFalse(manager.isPurchased)
        XCTAssertFalse(finished, "failed durable grant must remain unfinished for StoreKit redelivery")
        XCTAssertNotNil(manager.storeIssue)
    }

    func testVerifiedRevocationUsesAggregateEntitlementAndPreservesReplacementUnlock() async {
        var events: [String] = []
        var manager: StoreManager!
        manager = StoreManager(
            entitlementQuery: { true },
            readCachedEntitlement: { true },
            writeCachedEntitlement: { purchased in
                XCTAssertTrue(purchased)
                events.append("aggregate-cache")
            },
            presentOfferCodeRedemption: {},
            makeUpdatesTask: noUpdates
        )

        let processed = await manager.processVerifiedEntitlementUpdate(purchased: false) {
            events.append(manager.isPurchased ? "replacement-retained" : "revoked")
            events.append("finish")
        }

        XCTAssertTrue(processed)
        XCTAssertTrue(manager.isPurchased)
        XCTAssertEqual(events, ["aggregate-cache", "replacement-retained", "finish"])
    }

    func testAggregateRevocationCanAuthoritativelyClearUnlock() async {
        var cacheWrites: [Bool] = []
        var finished = false
        let manager = StoreManager(
            entitlementQuery: { false },
            readCachedEntitlement: { true },
            writeCachedEntitlement: { cacheWrites.append($0) },
            presentOfferCodeRedemption: {},
            makeUpdatesTask: noUpdates
        )

        let processed = await manager.processVerifiedRevocationUpdate {
            finished = true
        }

        XCTAssertTrue(processed)
        XCTAssertFalse(manager.isPurchased)
        XCTAssertEqual(cacheWrites, [false])
        XCTAssertTrue(finished)
    }

    func testRevocationQueryCannotOverwriteNewerReplacementTransaction() async {
        let queryGate = BoolGate()
        var queryCount = 0
        var cacheWrites: [Bool] = []
        var replacementFinished = false
        var revocationFinished = false
        let manager = StoreManager(
            entitlementQuery: {
                queryCount += 1
                return await queryGate.wait()
            },
            readCachedEntitlement: { true },
            writeCachedEntitlement: { cacheWrites.append($0) },
            presentOfferCodeRedemption: {},
            makeUpdatesTask: noUpdates
        )

        let revocation = Task {
            await manager.processVerifiedRevocationUpdate {
                revocationFinished = true
            }
        }
        let revocationQueryStarted = await waitUntil { queryCount == 1 }
        XCTAssertTrue(revocationQueryStarted)

        let replacementProcessed = await manager.processVerifiedEntitlementUpdate(purchased: true) {
            replacementFinished = true
        }
        await queryGate.open(false)
        let revocationProcessed = await revocation.value

        XCTAssertTrue(replacementProcessed)
        XCTAssertTrue(revocationProcessed)
        XCTAssertTrue(replacementFinished)
        XCTAssertTrue(revocationFinished)
        XCTAssertTrue(manager.isPurchased)
        XCTAssertEqual(cacheWrites, [true], "stale aggregate false must not clear the newer verified replacement")
    }

    func testEveryProductAppearanceFetchesFreshOffer() async {
        let loads = Counter()
        let manager = StoreManager(
            entitlementQuery: { false },
            readCachedEntitlement: { false },
            writeCachedEntitlement: { _ in },
            presentOfferCodeRedemption: {},
            makeUpdatesTask: noUpdates,
            productLoader: {
                let count = await loads.increment()
                return StoreManager.ProductOffer(displayPrice: count == 1 ? "$1.99" : "$2.99") {
                    .userCancelled
                }
            }
        )

        await manager.loadProduct()
        XCTAssertEqual(manager.priceText, "$1.99")
        await manager.loadProduct()

        let loadCount = await loads.value()
        XCTAssertEqual(loadCount, 2)
        XCTAssertEqual(manager.priceText, "$2.99")
        XCTAssertEqual(manager.loadState, .loaded)
    }

    func testStaleFirstProductGenerationCannotOverwriteNewerOffer() async {
        let firstLoadGate = BoolGate()
        let loads = Counter()
        let manager = StoreManager(
            entitlementQuery: { false },
            readCachedEntitlement: { false },
            writeCachedEntitlement: { _ in },
            presentOfferCodeRedemption: {},
            makeUpdatesTask: noUpdates,
            productLoader: {
                let count = await loads.increment()
                if count == 1 {
                    _ = await firstLoadGate.wait()
                    return StoreManager.ProductOffer(displayPrice: "$0.99") { .userCancelled }
                }
                return StoreManager.ProductOffer(displayPrice: "$3.99") { .userCancelled }
            }
        )

        let firstLoad = Task { await manager.loadProduct() }
        let firstProductLoadStarted = await waitUntil { await loads.value() == 1 }
        XCTAssertTrue(firstProductLoadStarted)
        await manager.loadProduct()
        XCTAssertEqual(manager.priceText, "$3.99")

        await firstLoadGate.open(true)
        await firstLoad.value
        XCTAssertEqual(manager.priceText, "$3.99")
        XCTAssertEqual(manager.loadState, .loaded)
    }

    func testPaywallDisappearInvalidatesLoadedProductOffer() async {
        let manager = StoreManager(
            entitlementQuery: { false },
            readCachedEntitlement: { false },
            writeCachedEntitlement: { _ in },
            presentOfferCodeRedemption: {},
            makeUpdatesTask: noUpdates,
            productLoader: {
                StoreManager.ProductOffer(displayPrice: "$1.99") { .userCancelled }
            }
        )

        await manager.loadProduct()
        XCTAssertEqual(manager.loadState, .loaded)
        XCTAssertEqual(manager.priceText, "$1.99")

        manager.cancelProductLoad()

        XCTAssertEqual(manager.loadState, .unavailable)
        XCTAssertNil(manager.priceText)
    }

    func testPurchaseFailureReloadsProductExactlyOnceWithoutRepurchasing() async {
        let loads = Counter()
        let purchaseAttempts = Counter()
        let manager = StoreManager(
            entitlementQuery: { false },
            readCachedEntitlement: { false },
            writeCachedEntitlement: { _ in },
            presentOfferCodeRedemption: {},
            makeUpdatesTask: noUpdates,
            productLoader: {
                let count = await loads.increment()
                if count == 1 {
                    return StoreManager.ProductOffer(displayPrice: "$1.99") {
                        _ = await purchaseAttempts.increment()
                        throw PurchaseUnavailable()
                    }
                }
                return StoreManager.ProductOffer(displayPrice: "$2.99") {
                    _ = await purchaseAttempts.increment()
                    return .userCancelled
                }
            }
        )

        await manager.loadProduct()
        await manager.purchase()

        let loadCount = await loads.value()
        let purchaseAttemptCount = await purchaseAttempts.value()
        XCTAssertEqual(loadCount, 2)
        XCTAssertEqual(purchaseAttemptCount, 1)
        XCTAssertEqual(manager.priceText, "$2.99")
        XCTAssertEqual(manager.purchaseOperation, .idle)
        XCTAssertEqual(manager.storeIssue?.kind, .purchaseFailed)
    }

    func testPurchaseCancellationDoesNotReloadAndMatchesAndroidCopy() async {
        let loads = Counter()
        let manager = StoreManager(
            entitlementQuery: { false },
            readCachedEntitlement: { false },
            writeCachedEntitlement: { _ in },
            presentOfferCodeRedemption: {},
            makeUpdatesTask: noUpdates,
            productLoader: {
                _ = await loads.increment()
                return StoreManager.ProductOffer(displayPrice: "$1.99") { .userCancelled }
            }
        )

        await manager.loadProduct()
        await manager.purchase()

        let loadCount = await loads.value()
        XCTAssertEqual(loadCount, 1)
        XCTAssertEqual(manager.purchaseOperation, .idle)
        XCTAssertEqual(manager.storeIssue?.kind, .purchaseCancelled)
        XCTAssertEqual(manager.storeIssue?.message, "Purchase cancelled. You have not been charged.")
        XCTAssertFalse(manager.storeIssue?.retryable ?? true)
    }

    func testPendingPurchaseBlocksCommerceUntilAuthoritativeQueryResolution() async {
        let purchaseAttempts = Counter()
        var syncCount = 0
        var redemptionCount = 0
        let manager = StoreManager(
            entitlementQuery: { false },
            readCachedEntitlement: { false },
            writeCachedEntitlement: { _ in },
            presentOfferCodeRedemption: { redemptionCount += 1 },
            makeUpdatesTask: noUpdates,
            synchronizeAppStore: { syncCount += 1 },
            productLoader: {
                StoreManager.ProductOffer(displayPrice: "$1.99") {
                    _ = await purchaseAttempts.increment()
                    return .pending
                }
            }
        )

        await manager.loadProduct()
        await manager.purchase()
        XCTAssertTrue(manager.purchasePending)
        XCTAssertTrue(manager.commerceOperationActive)
        XCTAssertEqual(manager.storeIssue?.kind, .purchasePending)

        await manager.purchase()
        await manager.restore()
        await manager.redeemOfferCode()
        let purchaseAttemptCount = await purchaseAttempts.value()
        XCTAssertEqual(purchaseAttemptCount, 1)
        XCTAssertEqual(syncCount, 0)
        XCTAssertEqual(redemptionCount, 0)

        let refreshResult = await manager.refreshEntitlement()
        XCTAssertEqual(refreshResult, .authoritative(false))
        XCTAssertEqual(manager.purchaseOperation, .idle)
        XCTAssertNil(manager.storeIssue, "the matching pending issue should clear with authoritative resolution")
    }

    func testVerifiedPendingCompletionDoesNotClearUnrelatedIssue() async {
        let manager = StoreManager(
            entitlementQuery: { false },
            readCachedEntitlement: { false },
            writeCachedEntitlement: { _ in },
            presentOfferCodeRedemption: {},
            makeUpdatesTask: noUpdates,
            productLoader: {
                StoreManager.ProductOffer(displayPrice: "$1.99") { .pending }
            }
        )
        await manager.loadProduct()
        await manager.purchase()
        manager.storeIssue = StoreManager.StoreIssue(
            kind: .purchaseFailed,
            message: "Keep this issue",
            retryable: true,
            purchaseOperationID: nil
        )

        let processed = await manager.processVerifiedEntitlementUpdate(purchased: true) {}
        XCTAssertTrue(processed)

        XCTAssertEqual(manager.purchaseOperation, .idle)
        XCTAssertEqual(manager.storeIssue?.message, "Keep this issue")
    }

    func testEntitlementQueryTimeoutRetainsKnownGoodCache() async {
        let manager = StoreManager(
            entitlementQuery: {
                try await Task.sleep(nanoseconds: 5_000_000_000)
                return false
            },
            readCachedEntitlement: { true },
            writeCachedEntitlement: { _ in XCTFail("timeout must not rewrite the cache") },
            presentOfferCodeRedemption: {},
            makeUpdatesTask: noUpdates,
            entitlementTimeoutSeconds: 0.01
        )

        let result = await manager.refreshEntitlement()

        XCTAssertEqual(result, .unavailable)
        XCTAssertTrue(manager.isPurchased)
    }

    func testSupersededEntitlementRefreshCancelsQueryAndReturnsPromptly() async {
        let firstQueryGate = BoolGate()
        let queries = Counter()
        let cancellationObserved = expectation(description: "superseded query observed cancellation")
        let oldRefreshFinished = expectation(description: "superseded refresh returned")
        let manager = StoreManager(
            entitlementQuery: {
                let count = await queries.increment()
                if count == 1 {
                    return await withTaskCancellationHandler {
                        await firstQueryGate.wait()
                    } onCancel: {
                        cancellationObserved.fulfill()
                    }
                }
                return true
            },
            readCachedEntitlement: { false },
            writeCachedEntitlement: { _ in },
            presentOfferCodeRedemption: {},
            makeUpdatesTask: noUpdates,
            entitlementTimeoutSeconds: 5
        )

        let oldRefresh = Task {
            let result = await manager.refreshEntitlement()
            oldRefreshFinished.fulfill()
            return result
        }
        let firstQueryStarted = await waitUntil { await queries.value() == 1 }
        XCTAssertTrue(firstQueryStarted)

        let newerResult = await manager.refreshEntitlement()
        XCTAssertEqual(newerResult, .authoritative(true))
        await fulfillment(of: [cancellationObserved, oldRefreshFinished], timeout: 0.5)

        // The query deliberately stays suspended after observing cancellation;
        // release it only after proving the old wrapper already returned.
        await firstQueryGate.open(false)
        let oldResult = await oldRefresh.value
        XCTAssertEqual(oldResult, .unavailable)
        XCTAssertTrue(manager.isPurchased)
    }
}
