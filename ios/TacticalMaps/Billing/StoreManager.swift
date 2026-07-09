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

    @Published private(set) var isPurchased = false
    @Published private(set) var product: Product?
    @Published private(set) var purchasing = false
    @Published private(set) var restoring = false
    /// Set after a Restore attempt so the paywall can show the outcome; the UI
    /// clears it once shown.
    @Published var restoreOutcome: String?
    @Published private(set) var loadState: ProductLoadState = .loading

    /// True for TestFlight / Sandbox builds (receipt is `sandboxReceipt`), where
    /// IAPs are free. Used to reassure testers they won't be charged.
    var isSandbox: Bool {
        Bundle.main.appStoreReceiptURL?.lastPathComponent == "sandboxReceipt"
    }

    /// Hard ceiling so a stalled StoreKit request can't hang the paywall forever.
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

    /// Kick off the purchase flow. Only call when `product` is loaded.
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

    /// "Restore purchase" - re-syncs with the App Store and re-reads entitlements.
    /// Always reports an outcome so the button never feels like it did nothing.
    func restore() async {
        restoring = true
        defer { restoring = false }
        let wasPurchased = isPurchased
        try? await AppStore.sync()
        await refreshEntitlement()
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

    /// Check entitlements and grant unlock if we find a verified, non-revoked one.
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

    /// Picks up transactions that got approved outside the app (Ask to Buy,
    /// another device, interrupted purchases).
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

/// Runs `operation` with a timeout. First one to finish wins, loser gets cancelled.
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
