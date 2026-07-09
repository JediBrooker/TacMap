import Foundation

/// Tracks the free-trial window. Drop-in replacement for the old UserDefaults
/// version, same API (`startIfNeeded`, `isTrialActive`, `daysRemaining`) so
/// no call-site changes needed.
///
/// vs the old version: first-launch timestamp now lives in Keychain so it
/// survives app deletion (reinstall doesn't restart trial). Also stores a
/// monotonic "latest seen" timestamp so rolling the clock back can't extend
/// the trial. UserDefaults still gets written as a mirror for legacy reads
/// and to migrate existing installs (their UD stamp seeds the Keychain so
/// they don't get reset).
struct TrialManager {
    static let trialDays = 3

    private static let legacyKey = "trialFirstLaunch"      // old UserDefaults key
    private static let kcFirstLaunch = "trialFirstLaunch"
    private static let kcLatestSeen = "trialLatestSeen"
    private static let dayInterval: TimeInterval = 24 * 60 * 60
    private let defaults = UserDefaults.standard

    /// First-launch timestamp. Checks Keychain first, then falls back to
    /// legacy UserDefaults (migrates it in), then stamps a new one.
    private var firstLaunch: Date {
        if let stored = KeychainStore.date(for: Self.kcFirstLaunch) { return stored }
        if let legacy = defaults.object(forKey: Self.legacyKey) as? Date {
            KeychainStore.set(legacy, for: Self.kcFirstLaunch)
            return legacy
        }
        let now = Date()
        KeychainStore.set(now, for: Self.kcFirstLaunch)
        defaults.set(now, forKey: Self.legacyKey)
        return now
    }

    private var trialEnd: Date {
        firstLaunch.addingTimeInterval(Double(Self.trialDays) * Self.dayInterval)
    }

    /// Clock-rollback guard. Returns whichever is later: wall clock or the
    /// highest timestamp we've ever seen. Advances the stored value.
    private func effectiveNow(_ now: Date) -> Date {
        let seen = KeychainStore.date(for: Self.kcLatestSeen) ?? .distantPast
        let effective = max(now, seen)
        if effective > seen { KeychainStore.set(effective, for: Self.kcLatestSeen) }
        return effective
    }

    /// Call once at launch so the trial clock starts even if the user never
    /// reaches the paywall.
    func startIfNeeded() {
        _ = firstLaunch
        _ = effectiveNow(Date())
    }

    func isTrialActive(now: Date = Date()) -> Bool {
        effectiveNow(now) < trialEnd
    }

    /// Whole days remaining, rounded up (so "2.3 days left" reads as 3), 0 once expired.
    func daysRemaining(now: Date = Date()) -> Int {
        let remaining = trialEnd.timeIntervalSince(effectiveNow(now))
        guard remaining > 0 else { return 0 }
        return Int(ceil(remaining / Self.dayInterval))
    }
}
