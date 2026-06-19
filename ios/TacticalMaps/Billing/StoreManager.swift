import Foundation
import StoreKit

/// StoreKit 2 wrapper for the single one-time, non-consumable unlock that
/// permanently removes the trial gate.
///
/// Exposes `isPurchased` (the entitlement) and `priceText` (the store's
/// localized price) for the paywall. The entitlement is sourced from
/// `Transaction.currentEntitlements`, so it restores automatically on a new
/// device / reinstall once the user signs into the same Apple ID.
@MainActor
final class StoreManager: ObservableObject {
    /// Must match the In-App Purchase product ID in App Store Connect
    /// (and the local `TacticalMaps.storekit` testing config).
    static let productID = "com.tacticalmaps.app.unlock"

    /// Where the one-time product fetch currently stands. Drives the paywall's
    /// loading / error / retry UI so it can never sit on a dead "Loading…"
    /// screen — the failure App Review hit when the IAP wasn't yet approved.
    enum ProductLoadState: Equatable {
        case loading      // fetch in flight
        case loaded       // product available, purchase enabled
        case unavailable  // fetch succeeded but App Store returned no product
        case failed       // fetch threw or timed out
    }

    @Published private(set) var isPurchased = false
    @Published private(set) var product: Product?
    @Published private(set) var purchasing = false
    @Published private(set) var loadState: ProductLoadState = .loading

    /// Hard ceiling on the product fetch so a stalled StoreKit request can't
    /// hang the paywall forever.
    private static let loadTimeout: Double = 15

    private var updatesTask: Task<Void, Never>?

    init() {
        updatesTask = listenForTransactions()
        Task {
            await loadProduct()
            await refreshEntitlement()
        }
    }

    deinit { updatesTask?.cancel() }

    /// Localized price string for the unlock, e.g. "$5.00". nil while loading.
    var priceText: String? { product?.displayPrice }

    func loadProduct() async {
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
                print("[Store] product load returned no products for \(Self.productID)")
            }
        } catch {
            product = nil
            loadState = .failed
            print("[Store] product load failed: \(error)")
        }
    }

    /// Begin the purchase flow. Safe to call only when `product` is loaded.
    func purchase() async {
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
            print("[Store] purchase failed: \(error)")
        }
    }

    /// "Restore purchase" — re-sync with the App Store and re-read entitlements.
    func restore() async {
        try? await AppStore.sync()
        await refreshEntitlement()
    }

    /// Present the App Store's own code-redemption sheet for promo codes
    /// (App Store Connect → your IAP → "Promo Codes", up to 100 free per
    /// version). A redeemed code grants the real `unlock` entitlement and
    /// produces a normal transaction that `listenForTransactions` already
    /// picks up, flipping `isPurchased` — so no app-side validation is needed.
    /// This is Apple's UI, so it's review-safe (no guideline 3.1.1 risk).
    /// No-op in the Simulator / StoreKit local testing.
    func presentRedeemSheet() {
        SKPaymentQueue.default().presentCodeRedemptionSheet()
    }

    /// Grant the unlock if a verified, non-revoked entitlement exists.
    func refreshEntitlement() async {
        var hasEntitlement = false
        for await result in Transaction.currentEntitlements {
            if case .verified(let transaction) = result,
               transaction.productID == Self.productID,
               transaction.revocationDate == nil {
                hasEntitlement = true
            }
        }
        isPurchased = hasEntitlement
    }

    /// Listen for transactions approved outside the app (Ask to Buy, another
    /// device, interrupted purchases).
    private func listenForTransactions() -> Task<Void, Never> {
        Task.detached { [weak self] in
            for await result in Transaction.updates {
                if case .verified(let transaction) = result,
                   transaction.productID == Self.productID {
                    await self?.refreshEntitlement()
                    await transaction.finish()
                }
            }
        }
    }
}

private struct TimeoutError: Error {}

/// Runs `operation`, throwing `TimeoutError` if it doesn't finish within
/// `seconds`. Whichever finishes first wins; the loser is cancelled.
private func withTimeout<T: Sendable>(
    seconds: Double,
    operation: @escaping @Sendable () async throws -> T
) async throws -> T {
    try await withThrowingTaskGroup(of: T.self) { group in
        group.addTask { try await operation() }
        group.addTask {
            try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
            throw TimeoutError()
        }
        defer { group.cancelAll() }
        return try await group.next()!
    }
}
