import Foundation

/// Tracks the free-trial window. Drop-in replacement for the old
/// UserDefaults version — same API (`startIfNeeded`, `isTrialActive`,
/// `daysRemaining`), so no call-site changes in TacticalMapsApp/ContentView.
///
/// Differences from the old version:
///  1. The first-launch timestamp lives in the **Keychain**, which survives
///     app deletion → a reinstall does NOT restart the trial.
///  2. A monotonically-increasing "latest seen" timestamp is also stored, so
///     winding the device clock backwards can't extend the trial: the
///     effective "now" is max(wall clock, latest seen).
///  3. UserDefaults is still written as a mirror for fast/legacy reads and to
///     migrate existing installs (an existing UserDefaults stamp seeds the
///     Keychain so current trial users aren't reset).
struct TrialManager {
    static let trialDays = 3

    private static let legacyKey = "trialFirstLaunch"      // old UserDefaults key
    private static let kcFirstLaunch = "trialFirstLaunch"
    private static let kcLatestSeen = "trialLatestSeen"
    private static let dayInterval: TimeInterval = 24 * 60 * 60
    private let defaults = UserDefaults.standard

    /// First-launch timestamp. Resolution order:
    /// Keychain → legacy UserDefaults (migrated in) → stamp now.
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

    /// Clock-rollback guard: returns the later of the wall clock and the
    /// latest timestamp we've ever observed, advancing the stored value.
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
