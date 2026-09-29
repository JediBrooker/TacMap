import SwiftUI

/// The guided map tour: shown once after the first unlock and replayable from
/// About or Settings. Each step highlights one real map control. Bump
/// `currentVersion` when the tour changes enough to show again.
enum FirstRunTips {
    static let currentVersion = 2
    static let defaultsKey = "onboarding.tipsSeenVersion"

    struct Step: Identifiable {
        let id: Int
        /// The control to highlight; nil shows the card in the middle.
        let target: TourTarget?
        let title: String
        let body: String
    }

    static var steps: [Step] {
        let content: [(TourTarget?, String, String)] = [
            (.crosshair, Messages.tourWelcomeTitle(), Messages.tourWelcomeBody()),
            (.header, Messages.tourHeaderTitle(), Messages.tourHeaderBody()),
            (.add, Messages.tourAddTitle(), Messages.tourAddBody()),
            (.mapHold, Messages.tourHoldTitle(), Messages.tourHoldBody()),
            (.menu, Messages.tourMenuTitle(), Messages.tourMenuBody()),
            (.labels, Messages.tourLabelsTitle(), Messages.tourLabelsBody()),
            (.night, Messages.tourNightTitle(), Messages.tourNightBody()),
            (.compass, Messages.tourCompassTitle(), Messages.tourCompassBody()),
            (.lock, Messages.tourLockTitle(), Messages.tourLockBody()),
            (nil, Messages.tourEditTitle(), Messages.tourEditBody()),
        ]
        return content.enumerated().map { index, step in
            Step(id: index, target: step.0, title: step.1, body: step.2)
        }
    }

    /// UI tests drive the map directly, so the tour stays away unless a test
    /// asks for it.
    static func shouldShow(defaults: UserDefaults = .standard,
                           environment: [String: String] = ProcessInfo.processInfo.environment) -> Bool {
        if environment["TACMAP_UITEST_TOUR"] == "1" { return true }
        if environment.keys.contains(where: { $0.hasPrefix("TACMAP_UITEST_") }) { return false }
        return defaults.integer(forKey: defaultsKey) < currentVersion
    }

    static func markSeen(defaults: UserDefaults = .standard) {
        defaults.set(currentVersion, forKey: defaultsKey)
    }

    static func reset(defaults: UserDefaults = .standard) {
        defaults.removeObject(forKey: defaultsKey)
    }
}

/// Map controls the tour can point at. `crosshair` and `mapHold` are places
/// on the map rather than views, so they have no anchor.
enum TourTarget: Hashable {
    case crosshair, header, add, mapHold, menu, labels, night, compass, lock
}

/// Collects the frames of the tagged controls for the tour overlay.
struct TourAnchorKey: PreferenceKey {
    static var defaultValue: [TourTarget: Anchor<CGRect>] = [:]

    static func reduce(value: inout [TourTarget: Anchor<CGRect>],
                       nextValue: () -> [TourTarget: Anchor<CGRect>]) {
        value.merge(nextValue()) { $1 }
    }
}

extension View {
    /// Marks this view as the control a tour step highlights.
    func tourTarget(_ target: TourTarget) -> some View {
        anchorPreference(key: TourAnchorKey.self, value: .bounds) { [target: $0] }
    }
}
