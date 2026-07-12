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
    }

    @Published private(set) var isPurchased: Bool
    @Published private(set) var product: Product?
    @Published private(set) var purchasing = false
    @Published private(set) var restoring = false
    /// Set after a Restore attempt so the paywall can show the outcome; the UI
    /// clears it once shown.
    @Published var restoreOutcome: String?
    @Published private(set) var loadState: ProductLoadState = .unavailable

    /// True for TestFlight / Sandbox builds (receipt is `sandboxReceipt`), where
    /// IAPs are free. Used to reassure testers they won't be charged.
    var isSandbox: Bool {
        Bundle.main.appStoreReceiptURL?.lastPathComponent == "sandboxReceipt"
    }

    /// Hard ceiling so a stalled StoreKit request can't hang the paywall forever.
    private static let loadTimeout: Double = 15

    private var updatesTask: Task<Void, Never>?
    private static let cachedEntitlementAccount = "store.entitlement.cached.v1"
    private let entitlementQuery: () async throws -> Bool
    private let writeCachedEntitlement: (Bool) -> Void

    convenience init() {
        self.init(
            entitlementQuery: Self.queryCurrentEntitlement,
            readCachedEntitlement: {
                KeychainStore.data(for: Self.cachedEntitlementAccount) == Data([1])
            },
            writeCachedEntitlement: { purchased in
                if purchased {
                    _ = KeychainStore.set(Data([1]), for: Self.cachedEntitlementAccount)
                } else {
                    _ = KeychainStore.removeData(for: Self.cachedEntitlementAccount)
                }
            }
        )
    }

    init(
        entitlementQuery: @escaping () async throws -> Bool,
        readCachedEntitlement: () -> Bool,
        writeCachedEntitlement: @escaping (Bool) -> Void
    ) {
        // Keychain cache is local-only and permits a verified owner to launch
        // offline. StoreKit is not touched until a visible user action.
        self.entitlementQuery = entitlementQuery
        self.writeCachedEntitlement = writeCachedEntitlement
        isPurchased = readCachedEntitlement()
    }

    deinit { updatesTask?.cancel() }

    /// Localized price string for the unlock, e.g. "$5.00". nil while loading.
    var priceText: String? { product?.displayPrice }

    func loadProduct() async {
        activateStoreAccess()
        loadState = .loading
        do {
            let products = try await withTimeout(seconds: Self.loadTimeout) {
                try await Product.products(for: [Self.productID])
            }
            if let first = products.first {
                product = first
                loadState = .loaded
            } else {
                // No error, but the App Store returned nothing. Happens when the
                // IAP isn't approved/Ready-to-Submit yet (i.e. during review).
                product = nil
                loadState = .unavailable
                print("[Store] product unavailable")
            }
        } catch {
            product = nil
            loadState = .failed
            print("[Store] product load failed")
        }
    }

    /// Kick off the purchase flow. Only call when `product` is loaded.
    func purchase() async {
        activateStoreAccess()
        guard let product else { return }
        purchasing = true
        defer { purchasing = false }
        do {
            switch try await product.purchase() {
            case .success(let verification):
                if case .verified(let transaction) = verification {
                    await transaction.finish()
                    await refreshEntitlement()
                }
            case .userCancelled, .pending:
                break
            @unknown default:
                break
            }
        } catch {
            print("[Store] purchase failed")
        }
    }

    /// "Restore purchase" - re-syncs with the App Store and re-reads entitlements.
    /// Always reports an outcome so the button never feels like it did nothing.
    func restore() async {
        activateStoreAccess()
        restoring = true
        defer { restoring = false }
        let wasPurchased = isPurchased
        do {
            try await AppStore.sync()
        } catch {
            // An offline/failed sync says nothing about ownership. Preserve the
            // last verified state instead of treating transport failure as a
            // completed, authoritative "not entitled" answer.
            restoreOutcome = "Couldn't contact the App Store. Your existing unlock state was kept; try again when online."
            return
        }
        let refresh = await refreshEntitlement()
        guard case .authoritative = refresh else {
            restoreOutcome = "Couldn't verify purchases right now. Your existing unlock state was kept; try again when online."
            return
        }
        restoreOutcome = isPurchased
            ? (wasPurchased ? "Already unlocked." : "Purchase restored.")
            : "No previous purchase found on this Apple ID."
    }

    /// Opens the App Store's "Redeem Gift Card or Code" screen.
    ///
    /// Promo codes for a non-consumable IAP can only be redeemed in the App
    /// Store app itself. Apple's `presentCodeRedemptionSheet()` is for
    /// subscription offer codes only (which we dont have) so it would just
    /// dead-end. We deep-link to the store's redeem screen instead (kinda the
    /// iOS equivalent of Android's `play.google.com/redeem`). User pastes the
    /// code there and it produces a normal transaction that
    /// `listenForTransactions` / restore picks up.
    func presentRedeemSheet() {
        guard let url = URL(string: "https://apps.apple.com/redeem") else { return }
        UIApplication.shared.open(url)
    }

    /// Apply a completed entitlement query authoritatively. A thrown query is
    /// inconclusive (offline, StoreKit unavailable, or verification failure),
    /// so the last locally verified state is deliberately retained.
    @discardableResult
    func refreshEntitlement() async -> EntitlementRefreshResult {
        do {
            let hasEntitlement = try await entitlementQuery()
            isPurchased = hasEntitlement
            writeCachedEntitlement(hasEntitlement)
            return .authoritative(hasEntitlement)
        } catch {
            print("[Store] entitlement refresh unavailable; retaining verified cache")
            return .unavailable
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
        updatesTask = listenForTransactions()
    }

    /// Picks up transactions that got approved outside the app (Ask to Buy,
    /// another device, interrupted purchases).
    private func listenForTransactions() -> Task<Void, Never> {
        let unlockProductID = Self.productID
        return Task.detached { [weak self] in
            for await result in Transaction.updates {
                if case .verified(let transaction) = result,
                   transaction.productID == unlockProductID {
                    await self?.refreshEntitlement()
                    await transaction.finish()
                }
            }
        }
    }
}

private struct TimeoutError: Error {}

private final class TimeoutRace<T: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var finished = false
    private var tasks: [Task<Void, Never>] = []

    func setTasks(_ tasks: [Task<Void, Never>]) {
        lock.lock(); self.tasks = tasks; let done = finished; lock.unlock()
        if done { tasks.forEach { $0.cancel() } }
    }

    func resolve(_ result: Result<T, Error>, _ continuation: CheckedContinuation<T, Error>) {
        lock.lock()
        guard !finished else { lock.unlock(); return }
        finished = true
        let tasks = self.tasks
        lock.unlock()
        tasks.forEach { $0.cancel() }
        continuation.resume(with: result)
    }
}

/// Runs `operation` with a timeout. First one to finish wins, loser gets cancelled.
private func withTimeout<T: Sendable>(
    seconds: Double,
    operation: @escaping @Sendable () async throws -> T
) async throws -> T {
    try await withCheckedThrowingContinuation { continuation in
        let race = TimeoutRace<T>()
        let work = Task {
            do { race.resolve(.success(try await operation()), continuation) }
            catch { race.resolve(.failure(error), continuation) }
        }
        let timeout = Task {
            do {
                try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
                race.resolve(.failure(TimeoutError()), continuation)
            } catch { }
        }
        race.setTasks([work, timeout])
    }
}
