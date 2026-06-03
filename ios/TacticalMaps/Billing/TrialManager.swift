import Foundation

/// Tracks the free-trial window. The first launch is stamped in
/// UserDefaults and the trial runs for `trialDays` days from then; after
/// that the user must buy the one-time unlock (see `StoreManager`).
///
/// NOTE: UserDefaults is cleared when the app is deleted, so a reinstall
/// restarts the trial. That's an accepted trade-off for a low-price
/// one-time unlock and matches the Android behaviour. The *purchase*
/// itself is restored from the App Store account, so it survives reinstall.
struct TrialManager {
    static let trialDays = 3

    private static let key = "trialFirstLaunch"
    private static let dayInterval: TimeInterval = 24 * 60 * 60
    private let defaults = UserDefaults.standard

    /// First-launch timestamp, stamped on first access.
    private var firstLaunch: Date {
        if let stored = defaults.object(forKey: Self.key) as? Date { return stored }
        let now = Date()
        defaults.set(now, forKey: Self.key)
        return now
    }

    private var trialEnd: Date {
        firstLaunch.addingTimeInterval(Double(Self.trialDays) * Self.dayInterval)
    }

    /// Call once at launch so the trial clock starts even if the user never
    /// reaches the paywall.
    func startIfNeeded() { _ = firstLaunch }

    func isTrialActive(now: Date = Date()) -> Bool { now < trialEnd }

    /// Whole days remaining, rounded up (so "2.3 days left" reads as 3), 0 once expired.
    func daysRemaining(now: Date = Date()) -> Int {
        let remaining = trialEnd.timeIntervalSince(now)
        guard remaining > 0 else { return 0 }
        return Int(ceil(remaining / Self.dayInterval))
    }
}
