import Foundation
import Combine

/// When to ask for an App Store review. Pure rules, no StoreKit in here so it
/// unit tests without a simulator. Android mirrors this in ReviewPromptPolicy.kt
/// so both platforms nag (well, ask once) under the same conditions.
///
/// The rule: the user has had at least two different kinds of win (a map
/// landed in the library, an offline bake finished, a mission export actually
/// went somewhere) AND has opened the app on three separate days. Then we ask,
/// once per install, and only on a quiet moment back on the main map. Whether
/// iOS actually shows the card is up to the OS, we don't get told and dont care.
struct ReviewPromptPolicy {
    enum Event: String, CaseIterable {
        case mapImported
        case offlineBakeCompleted
        case missionExported
    }

    struct State: Equatable {
        var events: Set<Event> = []
        /// "yyyy-MM-dd" keys in the device's calendar, so a session that runs
        /// over midnight counts as two days. Close enough
        var launchDays: Set<String> = []
        var asked = false
    }

    static let requiredDistinctEvents = 2
    static let requiredLaunchDays = 3

    var calendar: Calendar = .current
    var now: () -> Date = Date.init

    func dayKey(_ date: Date) -> String {
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
    }

    func recordingLaunch(_ state: State) -> State {
        var next = state
        next.launchDays.insert(dayKey(now()))
        return next
    }

    func recording(_ event: Event, in state: State) -> State {
        var next = state
        next.events.insert(event)
        return next
    }

    func shouldPrompt(_ state: State) -> Bool {
        !state.asked
            && state.events.count >= Self.requiredDistinctEvents
            && state.launchDays.count >= Self.requiredLaunchDays
    }

    func markingAsked(_ state: State) -> State {
        var next = state
        next.asked = true
        return next
    }

    /// Scripted runs (UI tests, the DEBUG_HOOKS screenshot harness) never get
    /// the card, it would land right on top of whatever the test is looking at.
    static func isSuppressed(environment: [String: String] = ProcessInfo.processInfo.environment) -> Bool {
        environment.keys.contains { $0.hasPrefix("TACMAP_UITEST_") || $0.hasPrefix("TACMAP_DEBUG_") }
    }
}

/// Where the policy state lives. UserDefaults in the app, a dictionary in tests.
protocol ReviewPromptStore: AnyObject {
    func load() -> ReviewPromptPolicy.State
    func save(_ state: ReviewPromptPolicy.State)
}

final class UserDefaultsReviewPromptStore: ReviewPromptStore {
    static let eventsKey = "reviewPrompt.events"
    static let launchDaysKey = "reviewPrompt.launchDays"
    static let askedKey = "reviewPrompt.asked"

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func load() -> ReviewPromptPolicy.State {
        let raw = defaults.stringArray(forKey: Self.eventsKey) ?? []
        return ReviewPromptPolicy.State(
            // an event name from a future build we dont know about is just dropped
            events: Set(raw.compactMap(ReviewPromptPolicy.Event.init(rawValue:))),
            launchDays: Set(defaults.stringArray(forKey: Self.launchDaysKey) ?? []),
            asked: defaults.bool(forKey: Self.askedKey)
        )
    }

    func save(_ state: ReviewPromptPolicy.State) {
        defaults.set(state.events.map(\.rawValue).sorted(), forKey: Self.eventsKey)
        defaults.set(state.launchDays.sorted(), forKey: Self.launchDaysKey)
        defaults.set(state.asked, forKey: Self.askedKey)
    }
}

/// Glue between the places that know a win happened (import controller, bake
/// controller, share sheet) and the one view that is allowed to ask (ContentView
/// on the main map). Main thread only.
final class ReviewPromptCoordinator: ObservableObject {
    static let shared = ReviewPromptCoordinator(store: UserDefaultsReviewPromptStore(),
                                                suppressed: ReviewPromptPolicy.isSuppressed())

    /// true once the rule is met and nobody has asked yet. The view watches this
    /// and waits for a quiet moment before calling `takeRequest`.
    @Published private(set) var isEligible = false

    private let store: ReviewPromptStore
    private let policy: ReviewPromptPolicy
    private let suppressed: Bool
    private var state: ReviewPromptPolicy.State

    init(store: ReviewPromptStore, policy: ReviewPromptPolicy = ReviewPromptPolicy(), suppressed: Bool = false) {
        self.store = store
        self.policy = policy
        self.suppressed = suppressed
        self.state = store.load()
        refresh()
    }

    /// Call whenever the user actually reaches the map (launch, foreground).
    /// Same day twice is a no-op thanks to the set.
    func noteLaunch() {
        update(policy.recordingLaunch(state))
    }

    func record(_ event: ReviewPromptPolicy.Event) {
        update(policy.recording(event, in: state))
    }

    /// The view is about to ask. Flips the once-per-install flag before the
    /// request goes out so a crash or a silently swallowed card never retries.
    /// Returns false if there is nothing to ask for.
    func takeRequest() -> Bool {
        guard isEligible else { return false }
        update(policy.markingAsked(state))
        return true
    }

    private func update(_ next: ReviewPromptPolicy.State) {
        guard next != state else { return }
        state = next
        store.save(next)
        refresh()
    }

    private func refresh() {
        let eligible = !suppressed && policy.shouldPrompt(state)
        if eligible != isEligible { isEligible = eligible }
    }
}
