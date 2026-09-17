import Foundation
import StoreKit
import UIKit

/// StoreKit 2 wrapper for our one-time non-consumable unlock. Basically just
/// manages the single IAP that permanently removes the trial gate.
///
/// Exposes `isPurchased` and `priceText` for the paywall. Entitlement comes
/// from `Transaction.currentEntitlements` so it auto-restores on a new
/// device / reinstall once user signs into the same Apple ID.
@MainActor
final class StoreManager: ObservableObject {
    /// Has to match the IAP product ID in App Store Connect
    /// and the local `TacticalMaps.storekit` testing config.
    static let productID = "com.tacticalmaps.app.unlock"

    /// Tracks where the product fetch is at. Drives the paywall's loading /
    /// error / retry UI so it doesn't get stuck on a dead "Loading..." screen
    /// (that's the exact failure App Review hit when the IAP wasn't approved yet).
    enum ProductLoadState: Equatable {
        case loading      // fetch in flight
        case loaded       // product available, purchase enabled
        case unavailable  // fetch succeeded but App Store returned no product
        case failed       // fetch threw or timed out
    }

    enum EntitlementRefreshResult: Equatable {
        case authoritative(Bool)
        case unavailable
        case persistenceFailed
    }

    enum PurchaseOperation: Equatable {
        case idle
        case purchasing(UInt64)
        case pending(UInt64)
    }

    enum StoreIssueKind: Equatable {
        case entitlementPersistence
        case entitlementUnavailable
        case verification
        case purchaseFailed
        case purchaseCancelled
        case purchasePending
    }

    struct StoreIssue: Equatable {
        let kind: StoreIssueKind
        let message: String
        let retryable: Bool
        let purchaseOperationID: UInt64?
    }

    /// A deliberately small seam around StoreKit's `Product`. It keeps the
    /// production flow type-safe while allowing product refresh and purchase
    /// outcomes to be exercised without an App Store session.
    struct ProductOffer: @unchecked Sendable {
        let displayPrice: String
        let purchase: @Sendable () async throws -> PurchaseResult
    }

    enum PurchaseResult: @unchecked Sendable {
        case verified(isRevoked: Bool, finish: @Sendable () async -> Void)
        case unverified
        case userCancelled
        case pending
        case unknown
    }

    @Published private(set) var isPurchased: Bool
    @Published private(set) var purchaseOperation: PurchaseOperation = .idle
    @Published private(set) var restoring = false
    @Published private(set) var redeeming = false
    /// Set after a Restore attempt so the paywall can show the outcome; the UI
    /// clears it once shown.
    @Published var restoreOutcome: String?
    /// Set only when redemption needs follow-up. Successful redemption changes
    /// `isPurchased` immediately, which dismisses the paywall.
    @Published var redemptionOutcome: String?
    /// Serious StoreKit delivery/verification issues which can also happen
    /// while the paywall is not mounted (for example, a refund update received
    /// by an already-unlocked app). RootGate presents this state globally.
    @Published var storeIssue: StoreIssue?
    @Published private(set) var loadState: ProductLoadState = .unavailable

    /// True for TestFlight / Sandbox builds (receipt is `sandboxReceipt`), where
    /// IAPs are free. Used to reassure testers they won't be charged.
    var isSandbox: Bool {
        Bundle.main.appStoreReceiptURL?.lastPathComponent == "sandboxReceipt"
    }

    /// Hard ceiling so a stalled StoreKit request can't hang the paywall forever.
    private static let loadTimeout: Double = 15
    private static let entitlementTimeout: Double = 15
    /// Lifecycle reconciliation is useful, but it should not contact the store
    /// for every brief app-switcher trip. Explicit user checks bypass this.
    static let foregroundRefreshCooldown: TimeInterval = 15 * 60

    private struct InFlightRefresh {
        let id: UInt64
        let task: Task<EntitlementRefreshResult, Never>
    }

    private enum ProductLoadOutcome: @unchecked Sendable {
        case loaded(ProductOffer?)
        case failed
    }

    private var updatesTask: Task<Void, Never>?
    private var refreshTask: InFlightRefresh?
    private var productLoadTask: Task<ProductLoadOutcome, Never>?
    private var productOffer: ProductOffer?
    private var lifetimeStarted = false
    private var nextPurchaseOperationID: UInt64 = 0
    private var productLoadGeneration: UInt64 = 0
    private var nextRefreshID: UInt64 = 0
    private var latestRefreshID: UInt64 = 0
    private var lastAppliedRefreshID: UInt64 = 0
    private var lastEntitlementQueryStartedAt: TimeInterval?
    /// Invalidates an entitlement query which was overtaken by a newer,
    /// durably-applied transaction update while the query was suspended.
    private var entitlementGeneration: UInt64 = 0
    private static let cachedEntitlementAccount = "store.entitlement.cached.v1"
    private let entitlementQuery: () async throws -> Bool
    private let writeCachedEntitlement: (Bool) throws -> Void
    private let presentOfferCodeRedemption: () async throws -> Void
    private let makeUpdatesTask: (StoreManager) -> Task<Void, Never>
    private let synchronizeAppStore: () async throws -> Void
    private let monotonicNow: () -> TimeInterval
    private let productLoader: @Sendable () async throws -> ProductOffer?
    private let productLoadTimeoutSeconds: Double
    private let entitlementTimeoutSeconds: Double

    convenience init() {
        self.init(
            entitlementQuery: Self.queryCurrentEntitlement,
            readCachedEntitlement: {
                KeychainStore.data(for: Self.cachedEntitlementAccount) == Data([1])
            },
            writeCachedEntitlement: { purchased in
                let persisted = purchased
                    ? KeychainStore.set(Data([1]), for: Self.cachedEntitlementAccount)
                    : KeychainStore.removeData(for: Self.cachedEntitlementAccount)
                guard persisted else { throw EntitlementCacheError.writeFailed }
            },
            presentOfferCodeRedemption: Self.presentSystemOfferCodeRedemption,
            makeUpdatesTask: { manager in manager.listenForTransactions() },
            synchronizeAppStore: { try await AppStore.sync() },
            monotonicNow: { ProcessInfo.processInfo.systemUptime },
            productLoader: { try await StoreManager.loadProductOfferFromStore() },
            productLoadTimeoutSeconds: Self.loadTimeout,
            entitlementTimeoutSeconds: Self.entitlementTimeout
        )
    }

    init(
        entitlementQuery: @escaping () async throws -> Bool,
        readCachedEntitlement: () -> Bool,
        writeCachedEntitlement: @escaping (Bool) throws -> Void,
        presentOfferCodeRedemption: @escaping () async throws -> Void,
        makeUpdatesTask: @escaping (StoreManager) -> Task<Void, Never>,
        synchronizeAppStore: @escaping () async throws -> Void = { try await AppStore.sync() },
        monotonicNow: @escaping () -> TimeInterval = { ProcessInfo.processInfo.systemUptime },
        productLoader: (@Sendable () async throws -> ProductOffer?)? = nil,
        productLoadTimeoutSeconds: Double = 15,
        entitlementTimeoutSeconds: Double = 15
    ) {
        // The Keychain cache permits a verified owner to launch offline. `start`
        // then listens for outside-app transactions and refreshes StoreKit state.
        self.entitlementQuery = entitlementQuery
        self.writeCachedEntitlement = writeCachedEntitlement
        self.presentOfferCodeRedemption = presentOfferCodeRedemption
        self.makeUpdatesTask = makeUpdatesTask
        self.synchronizeAppStore = synchronizeAppStore
        self.monotonicNow = monotonicNow
        self.productLoader = productLoader ?? { try await StoreManager.loadProductOfferFromStore() }
        self.productLoadTimeoutSeconds = productLoadTimeoutSeconds
        self.entitlementTimeoutSeconds = entitlementTimeoutSeconds
        isPurchased = readCachedEntitlement()
        // Transaction.updates is an app-lifetime responsibility, not a
        // paywall responsibility. Start it even when the verified cache lets
        // the owner bypass the paywall entirely.
        activateStoreAccess()
    }

    deinit {
        updatesTask?.cancel()
        refreshTask?.task.cancel()
        productLoadTask?.cancel()
    }

    /// Localized price string for the unlock, e.g. "$5.00". nil while loading.
    var priceText: String? { productOffer?.displayPrice }

    var purchasing: Bool {
        if case .purchasing = purchaseOperation { return true }
        return false
    }

    var purchasePending: Bool {
        if case .pending = purchaseOperation { return true }
        return false
    }

    var commerceOperationActive: Bool {
        purchaseOperation != .idle || restoring || redeeming
    }

    /// Performs the app-lifetime entitlement refresh once. The transaction
    /// listener itself is installed synchronously during construction so a
    /// cached purchaser cannot bypass it with the paywall.
    func start() async {
        guard !lifetimeStarted else { return }
        lifetimeStarted = true
        _ = await refreshEntitlementIfDue()
    }

    /// Called for active scene transitions. Startup and foreground checks share
    /// one in-flight query and a monotonic 15-minute cooldown.
    func appDidBecomeActive() async {
        _ = await refreshEntitlementIfDue()
    }

    func loadProduct() async {
        activateStoreAccess()
        productLoadGeneration &+= 1
        let generation = productLoadGeneration
        productLoadTask?.cancel()
        productOffer = nil
        loadState = .loading

        let loader = productLoader
        let timeout = productLoadTimeoutSeconds
        let task = Task { @MainActor in
            do {
                return ProductLoadOutcome.loaded(
                    try await withTimeout(seconds: timeout) { try await loader() }
                )
            } catch {
                return ProductLoadOutcome.failed
            }
        }
        productLoadTask = task
        let outcome = await task.value
        guard generation == productLoadGeneration, !task.isCancelled else { return }
        productLoadTask = nil

        switch outcome {
        case .loaded(let offer?):
            productOffer = offer
            loadState = .loaded
        case .loaded(nil):
            // No error, but the App Store returned nothing. Happens when the
            // IAP isn't approved/Ready-to-Submit yet (i.e. during review).
            productOffer = nil
            loadState = .unavailable
        case .failed:
            productOffer = nil
            loadState = .failed
        }
    }

    /// Paywall-scoped product requests should not outlive the screen which
    /// initiated them. A later appearance always starts with a fresh product.
    func cancelProductLoad() {
        productLoadGeneration &+= 1
        productLoadTask?.cancel()
        productLoadTask = nil
        productOffer = nil
        loadState = .unavailable
    }

    /// Kick off the purchase flow. Only call when a fresh offer is loaded.
    func purchase() async {
        activateStoreAccess()
        guard !commerceOperationActive else { return }
        guard let productOffer else {
            setStoreIssue(
                kind: .purchaseFailed,
                message: "Purchase options aren't loaded. Return to the unlock screen and tap Try Again.",
                retryable: false
            )
            return
        }

        nextPurchaseOperationID &+= 1
        let operationID = nextPurchaseOperationID
        purchaseOperation = .purchasing(operationID)
        var reloadProduct = false
        do {
            switch try await productOffer.purchase() {
            case .verified(let isRevoked, let finish):
                let processed = await processVerifiedEntitlementUpdate(
                    purchased: !isRevoked,
                    finish: finish
                )
                if processed {
                    completePurchaseOperation(operationID)
                } else if case .purchasing(let currentID) = purchaseOperation,
                          currentID == operationID {
                    // Keep controls locked until a fresh entitlement query or
                    // a redelivered verified transaction resolves the outcome.
                    purchaseOperation = .pending(operationID)
                }
            case .unverified:
                setStoreIssue(
                    kind: .verification,
                    message: "Apple returned a purchase TacMap couldn't verify. Your existing unlock was kept. Tap Check Again, then Restore purchase if needed.",
                    retryable: true
                )
                completePurchaseOperation(operationID)
                reloadProduct = true
            case .userCancelled:
                setStoreIssue(
                    kind: .purchaseCancelled,
                    message: "Purchase cancelled. You have not been charged.",
                    retryable: false
                )
                completePurchaseOperation(operationID)
            case .pending:
                purchaseOperation = .pending(operationID)
                setStoreIssue(
                    kind: .purchasePending,
                    message: "Your purchase is awaiting approval. TacMap will unlock when Apple completes it; tap Check Again after approval.",
                    retryable: true,
                    purchaseOperationID: operationID
                )
            case .unknown:
                setStoreIssue(
                    kind: .purchaseFailed,
                    message: "The App Store returned an unknown purchase result. Tap Check Again before trying the purchase again.",
                    retryable: true
                )
                completePurchaseOperation(operationID)
                reloadProduct = true
            }
        } catch {
            setStoreIssue(
                kind: .purchaseFailed,
                message: "The purchase couldn't be completed. Check your connection, tap Check Again, and try once more.",
                retryable: true
            )
            completePurchaseOperation(operationID)
            reloadProduct = true
        }

        // A non-cancellation failure can mean the StoreKit product snapshot is
        // stale. Invalidate it and make exactly one fresh fetch; never retry the
        // purchase automatically.
        if reloadProduct {
            await loadProduct()
        }
    }

    private static func loadProductOfferFromStore() async throws -> ProductOffer? {
        guard let product = try await Product.products(for: [productID]).first else {
            return nil
        }
        return ProductOffer(displayPrice: product.displayPrice) {
            switch try await product.purchase() {
            case .success(let verification):
                switch verification {
                case .verified(let transaction):
                    return .verified(
                        isRevoked: transaction.revocationDate != nil,
                        finish: { await transaction.finish() }
                    )
                case .unverified:
                    return .unverified
                }
            case .userCancelled:
                return .userCancelled
            case .pending:
                return .pending
            @unknown default:
                return .unknown
            }
        }
    }

    /// "Restore purchase" - re-syncs with the App Store and re-reads entitlements.
    /// Always reports an outcome so the button never feels like it did nothing.
    func restore() async {
        activateStoreAccess()
        guard !commerceOperationActive else { return }
        restoring = true
        defer { restoring = false }
        let wasPurchased = isPurchased
        do {
            try await synchronizeAppStore()
        } catch {
            // An offline/failed sync says nothing about ownership. Preserve the
            // last verified state instead of treating transport failure as a
            // completed, authoritative "not entitled" answer.
            restoreOutcome = "Couldn't contact the App Store. Your existing unlock state was kept; try again when online."
            return
        }
        // A query which began before AppStore.sync cannot observe the state the
        // sync just fetched. Always create a fresh, newer query here.
        let refresh = await refreshEntitlement()
        if case .persistenceFailed = refresh {
            // `refreshEntitlement` publishes a global issue for silent
            // lifecycle checks. Restore already has its own outcome alert, so
            // keep this explicit action to one useful message.
            storeIssue = nil
            restoreOutcome = "TacMap found the latest purchase status but couldn't save it securely. Restart the device, then tap Restore purchase again."
            return
        }
        guard case .authoritative = refresh else {
            restoreOutcome = "Couldn't verify purchases right now. Your existing unlock state was kept; try again when online."
            return
        }
        restoreOutcome = isPurchased
            ? (wasPurchased ? "Already unlocked." : "Purchase restored.")
            : "No previous purchase found on this Apple ID."
    }

    /// Presents Apple's in-app offer-code sheet. iOS 16.3 added offer-code
    /// redemption for non-consumables such as TacMap's permanent unlock.
    func redeemOfferCode() async {
        activateStoreAccess()
        guard !commerceOperationActive else { return }
        redemptionOutcome = nil
        redeeming = true
        defer { redeeming = false }

        do {
            try await presentOfferCodeRedemption()
        } catch {
            redemptionOutcome = "Couldn't open Apple's code redemption sheet. Try again."
            return
        }

        // The redemption sheet may have changed the receipt, so this explicit
        // action also bypasses lifecycle coalescing/cooldown.
        let refresh = await refreshEntitlement()
        switch refresh {
        case .persistenceFailed:
            storeIssue = nil
            redemptionOutcome = "Your code may have been redeemed, but TacMap couldn't save the unlock securely. Restart the device, then tap Restore purchase."
        case .unavailable:
            redemptionOutcome = "Your code may have been redeemed, but TacMap couldn't verify the unlock. Check your connection, then tap Restore purchase."
        case .authoritative:
            break
        }
    }

    private static func presentSystemOfferCodeRedemption() async throws {
        guard let scene = UIApplication.shared.connectedScenes
            .compactMap({ $0 as? UIWindowScene })
            .first(where: { $0.activationState == .foregroundActive }) else {
            throw RedemptionPresentationError.noActiveWindow
        }
        try await AppStore.presentOfferCodeRedeemSheet(in: scene)
    }

    /// Explicit user-driven reconciliation. This bypasses the lifecycle
    /// cooldown and starts a fresh query even if a pre-action query is still in
    /// flight (Restore relies on that post-sync ordering guarantee).
    @discardableResult
    func refreshEntitlement() async -> EntitlementRefreshResult {
        await runEntitlementRefresh(forceNewQuery: true)
    }

    /// Retries after a user taps the global alert. An inconclusive retry must
    /// replace the dismissed alert with useful next steps instead of vanishing.
    func checkEntitlementAgain() async {
        storeIssue = nil
        let result = await refreshEntitlement()
        if case .unavailable = result {
            setStoreIssue(
                kind: .entitlementUnavailable,
                message: "TacMap still couldn't verify your App Store status. Your existing unlock was kept. Check your connection and try Check Again, or use Restore purchase from the unlock screen.",
                retryable: true
            )
        }
    }

    @discardableResult
    private func refreshEntitlementIfDue() async -> EntitlementRefreshResult? {
        if let refreshTask { return await refreshTask.task.value }

        let now = monotonicNow()
        if let lastEntitlementQueryStartedAt {
            let elapsed = now - lastEntitlementQueryStartedAt
            if elapsed >= 0, elapsed < Self.foregroundRefreshCooldown {
                return nil
            }
        }
        return await runEntitlementRefresh(forceNewQuery: false)
    }

    /// Apply a completed entitlement query authoritatively. A thrown query is
    /// inconclusive (offline, StoreKit unavailable, or verification failure),
    /// so the last locally verified state is deliberately retained.
    private func runEntitlementRefresh(
        forceNewQuery: Bool
    ) async -> EntitlementRefreshResult {
        if !forceNewQuery, let refreshTask {
            return await refreshTask.task.value
        }

        if forceNewQuery {
            refreshTask?.task.cancel()
        }

        nextRefreshID &+= 1
        let refreshID = nextRefreshID
        latestRefreshID = refreshID
        let startingGeneration = entitlementGeneration
        lastEntitlementQueryStartedAt = monotonicNow()
        let query = entitlementQuery
        let timeout = entitlementTimeoutSeconds
        let task = Task { @MainActor [weak self] in
            let hasEntitlement: Bool
            do {
                hasEntitlement = try await withTimeout(seconds: timeout) {
                    try await query()
                }
            } catch {
                return EntitlementRefreshResult.unavailable
            }
            guard !Task.isCancelled, let self else {
                return EntitlementRefreshResult.unavailable
            }
            return self.applyEntitlementRefresh(
                hasEntitlement: hasEntitlement,
                refreshID: refreshID,
                startingGeneration: startingGeneration
            )
        }
        refreshTask = InFlightRefresh(id: refreshID, task: task)
        let result = await task.value
        if refreshTask?.id == refreshID { refreshTask = nil }
        return result
    }

    private func applyEntitlementRefresh(
        hasEntitlement: Bool,
        refreshID: UInt64,
        startingGeneration: UInt64
    ) -> EntitlementRefreshResult {
        // A forced post-action query supersedes any query which began before
        // it. The older result must never overwrite the newer receipt state.
        guard refreshID == latestRefreshID else {
            return lastAppliedRefreshID > refreshID
                ? .authoritative(isPurchased)
                : .unavailable
        }

        // A verified transaction update is newer than a query begun before it.
        // Its checked cache write is already authoritative, so do not overwrite
        // it with the older result when this suspended query resumes.
        guard startingGeneration == entitlementGeneration else {
            return .authoritative(isPurchased)
        }

        do {
            try writeCachedEntitlement(hasEntitlement)
        } catch {
            setStoreIssue(
                kind: .entitlementPersistence,
                message: "TacMap verified your App Store status but couldn't save it securely. Your existing unlock was kept. Restart the device, then tap Check Again.",
                retryable: true
            )
            return .persistenceFailed
        }

        entitlementGeneration &+= 1
        lastAppliedRefreshID = refreshID
        isPurchased = hasEntitlement
        resolveCurrentPendingPurchase()
        return .authoritative(hasEntitlement)
    }

    /// Applies a verified active purchase as one ordered delivery: durable
    /// cache first, published access second, finish last. Revocations are
    /// delegated to a fresh aggregate entitlement reconciliation. Returning
    /// false intentionally leaves the transaction unfinished for redelivery.
    @discardableResult
    func processVerifiedEntitlementUpdate(
        purchased: Bool,
        finish: @escaping () async -> Void
    ) async -> Bool {
        // A revoked transaction is only one item in StoreKit's history. It can
        // coexist with a replacement/newer active entitlement, so it must never
        // directly clear the aggregate unlock.
        guard purchased else {
            return await processVerifiedRevocationUpdate(finish: finish)
        }

        do {
            try writeCachedEntitlement(true)
        } catch {
            setStoreIssue(
                kind: .entitlementPersistence,
                message: "Apple verified your purchase, but TacMap couldn't save the unlock securely. The purchase remains pending in TacMap; restart the device and tap Check Again.",
                retryable: true
            )
            return false
        }

        entitlementGeneration &+= 1
        isPurchased = true
        await finish()
        resolveCurrentPendingPurchase()
        return true
    }

    @discardableResult
    func processVerifiedRevocationUpdate(
        finish: @escaping () async -> Void
    ) async -> Bool {
        switch await refreshEntitlement() {
        case .authoritative:
            await finish()
            return true
        case .persistenceFailed:
            return false
        case .unavailable:
            setStoreIssue(
                kind: .entitlementUnavailable,
                message: "Apple reported an unlock-status change, but TacMap couldn't verify your current aggregate entitlement. Your existing state was kept; tap Check Again.",
                retryable: true
            )
            return false
        }
    }

    private static func queryCurrentEntitlement() async throws -> Bool {
        var targetVerificationError: Error?
        for await result in Transaction.currentEntitlements {
            switch result {
            case .verified(let transaction):
                if transaction.productID == productID,
                   transaction.revocationDate == nil {
                    return true
                }
            case .unverified(let transaction, let error):
                // An unverifiable record for our unlock is not evidence that
                // the user does not own it. Make the refresh inconclusive.
                if transaction.productID == productID {
                    targetVerificationError = error
                }
            }
        }
        if let targetVerificationError { throw targetVerificationError }
        return false
    }

    private func activateStoreAccess() {
        guard updatesTask == nil else { return }
        updatesTask = makeUpdatesTask(self)
    }

    /// Picks up transactions that got approved outside the app (Ask to Buy,
    /// another device, interrupted purchases).
    private func listenForTransactions() -> Task<Void, Never> {
        let unlockProductID = Self.productID
        return Task.detached { [weak self] in
            for await result in Transaction.updates {
                switch result {
                case .verified(let transaction) where transaction.productID == unlockProductID:
                    if transaction.revocationDate == nil {
                        await self?.processVerifiedEntitlementUpdate(
                            purchased: true,
                            finish: { await transaction.finish() }
                        )
                    } else {
                        await self?.processVerifiedRevocationUpdate(
                            finish: { await transaction.finish() }
                        )
                    }
                case .unverified(let transaction, _)
                    where transaction.productID == unlockProductID:
                    await self?.reportUnverifiedTransaction()
                default:
                    break
                }
            }
        }
    }

    private func reportUnverifiedTransaction() {
        setStoreIssue(
            kind: .verification,
            message: "The App Store sent an unlock update TacMap couldn't verify. Your existing unlock was kept. Tap Check Again; use Restore purchase if the problem continues.",
            retryable: true
        )
    }

    private func setStoreIssue(
        kind: StoreIssueKind,
        message: String,
        retryable: Bool,
        purchaseOperationID: UInt64? = nil
    ) {
        storeIssue = StoreIssue(
            kind: kind,
            message: message,
            retryable: retryable,
            purchaseOperationID: purchaseOperationID
        )
    }

    private func completePurchaseOperation(_ operationID: UInt64) {
        guard case .purchasing(let currentID) = purchaseOperation,
              currentID == operationID else { return }
        purchaseOperation = .idle
    }

    private func resolveCurrentPendingPurchase() {
        guard case .pending(let operationID) = purchaseOperation else { return }
        purchaseOperation = .idle
        if storeIssue?.kind == .purchasePending,
           storeIssue?.purchaseOperationID == operationID {
            storeIssue = nil
        }
    }
}

private struct TimeoutError: Error {}

private enum EntitlementCacheError: Error {
    case writeFailed
}

private enum RedemptionPresentationError: Error {
    case noActiveWindow
}

private final class TimeoutRace<T: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var result: Result<T, Error>?
    private var continuation: CheckedContinuation<T, Error>?
    private var tasks: [Task<Void, Never>] = []

    func installContinuation(_ continuation: CheckedContinuation<T, Error>) {
        lock.lock()
        if let result {
            lock.unlock()
            continuation.resume(with: result)
        } else {
            self.continuation = continuation
            lock.unlock()
        }
    }

    func setTasks(_ tasks: [Task<Void, Never>]) {
        lock.lock(); self.tasks = tasks; let done = result != nil; lock.unlock()
        if done { tasks.forEach { $0.cancel() } }
    }

    func resolve(_ result: Result<T, Error>) {
        lock.lock()
        guard self.result == nil else { lock.unlock(); return }
        self.result = result
        let continuation = self.continuation
        self.continuation = nil
        let tasks = self.tasks
        self.tasks = []
        lock.unlock()
        tasks.forEach { $0.cancel() }
        continuation?.resume(with: result)
    }

    func cancel() {
        resolve(.failure(CancellationError()))
    }
}

/// Runs `operation` with a timeout. First one to finish wins, loser gets
/// cancelled. Parent cancellation is also a race outcome, so a superseded
/// caller resumes promptly even when the cancelled operation is slow to unwind.
private func withTimeout<T: Sendable>(
    seconds: Double,
    operation: @escaping @Sendable () async throws -> T
) async throws -> T {
    let race = TimeoutRace<T>()
    return try await withTaskCancellationHandler {
        try await withCheckedThrowingContinuation { continuation in
            race.installContinuation(continuation)
            let work = Task {
                do { race.resolve(.success(try await operation())) }
                catch { race.resolve(.failure(error)) }
            }
            let timeout = Task {
                do {
                    try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
                    race.resolve(.failure(TimeoutError()))
                } catch { }
            }
            race.setTasks([work, timeout])
            if Task.isCancelled { race.cancel() }
        }
    } onCancel: {
        race.cancel()
    }
}
