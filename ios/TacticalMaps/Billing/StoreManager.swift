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

    @Published private(set) var isPurchased = false
    @Published private(set) var product: Product?
    @Published private(set) var purchasing = false

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
        do {
            product = try await Product.products(for: [Self.productID]).first
        } catch {
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
                    isPurchased = true
                    await transaction.finish()
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

    /// Grant the unlock if a verified, non-revoked entitlement exists.
    func refreshEntitlement() async {
        for await result in Transaction.currentEntitlements {
            if case .verified(let transaction) = result,
               transaction.productID == Self.productID,
               transaction.revocationDate == nil {
                isPurchased = true
                return
            }
        }
    }

    /// Listen for transactions approved outside the app (Ask to Buy, another
    /// device, interrupted purchases).
    private func listenForTransactions() -> Task<Void, Never> {
        Task.detached { [weak self] in
            for await result in Transaction.updates {
                if case .verified(let transaction) = result {
                    await self?.grant(transaction)
                    await transaction.finish()
                }
            }
        }
    }

    private func grant(_ transaction: Transaction) {
        if transaction.productID == Self.productID, transaction.revocationDate == nil {
            isPurchased = true
        }
    }
}
