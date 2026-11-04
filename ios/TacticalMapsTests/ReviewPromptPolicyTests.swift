import XCTest
@testable import TacticalMaps

final class ReviewPromptPolicyTests: XCTestCase {
    private final class MemoryStore: ReviewPromptStore {
        var state = ReviewPromptPolicy.State()
        var saves = 0
        func load() -> ReviewPromptPolicy.State { state }
        func save(_ state: ReviewPromptPolicy.State) { self.state = state; saves += 1 }
    }

    private var clock = Date(timeIntervalSince1970: 1_700_000_000)   // 2023-11-14 UTC

    private func policy() -> ReviewPromptPolicy {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "UTC")!
        return ReviewPromptPolicy(calendar: cal, now: { self.clock })
    }

    private func advance(days: Int) {
        clock = clock.addingTimeInterval(TimeInterval(days) * 86_400)
    }

    func testFreshInstallDoesNotPrompt() {
        XCTAssertFalse(policy().shouldPrompt(ReviewPromptPolicy.State()))
    }

    func testNeedsTwoDistinctEventsAndThreeDays() {
        let p = policy()
        var s = ReviewPromptPolicy.State()
        s = p.recordingLaunch(s)
        advance(days: 1); s = p.recordingLaunch(s)
        advance(days: 1); s = p.recordingLaunch(s)
        XCTAssertEqual(s.launchDays.count, 3)
        XCTAssertFalse(p.shouldPrompt(s), "three days but no wins yet")

        s = p.recording(.mapImported, in: s)
        s = p.recording(.mapImported, in: s)
        XCTAssertFalse(p.shouldPrompt(s), "the same win twice is still one kind of win")

        s = p.recording(.offlineBakeCompleted, in: s)
        XCTAssertTrue(p.shouldPrompt(s))
    }

    func testTwoEventsOnFewerThanThreeDaysWaits() {
        let p = policy()
        var s = ReviewPromptPolicy.State()
        s = p.recording(.mapImported, in: s)
        s = p.recording(.missionExported, in: s)
        s = p.recordingLaunch(s)
        advance(days: 1); s = p.recordingLaunch(s)
        XCTAssertFalse(p.shouldPrompt(s))
        advance(days: 5); s = p.recordingLaunch(s)
        XCTAssertTrue(p.shouldPrompt(s))
    }

    func testSameDayLaunchesCountOnce() {
        let p = policy()
        var s = ReviewPromptPolicy.State()
        s = p.recordingLaunch(s)
        clock = clock.addingTimeInterval(3_600)
        s = p.recordingLaunch(s)
        XCTAssertEqual(s.launchDays.count, 1)
    }

    func testDayKeyFollowsTheCalendarTimeZone() {
        // 23:30 in Sydney is still the 14th in UTC but the 15th locally
        var sydney = Calendar(identifier: .gregorian)
        sydney.timeZone = TimeZone(identifier: "Australia/Sydney")!
        let p = ReviewPromptPolicy(calendar: sydney, now: { self.clock })
        XCTAssertEqual(p.dayKey(clock), "2023-11-15")
        XCTAssertEqual(policy().dayKey(clock), "2023-11-14")
    }

    func testAskedOnceNeverAgain() {
        let p = policy()
        var s = ReviewPromptPolicy.State(events: [.mapImported, .missionExported],
                                         launchDays: ["a", "b", "c"], asked: false)
        XCTAssertTrue(p.shouldPrompt(s))
        s = p.markingAsked(s)
        XCTAssertFalse(p.shouldPrompt(s))
        s = p.recording(.offlineBakeCompleted, in: s)
        advance(days: 10); s = p.recordingLaunch(s)
        XCTAssertFalse(p.shouldPrompt(s), "more wins after the ask dont reopen it")
    }

    func testScriptedRunsAreSuppressed() {
        XCTAssertTrue(ReviewPromptPolicy.isSuppressed(environment: ["TACMAP_UITEST_OFFLINE_BASEMAP": "1"]))
        XCTAssertTrue(ReviewPromptPolicy.isSuppressed(environment: ["TACMAP_DEBUG_GRID": "1"]))
        XCTAssertFalse(ReviewPromptPolicy.isSuppressed(environment: ["PATH": "/usr/bin"]))
        XCTAssertFalse(ReviewPromptPolicy.isSuppressed(environment: [:]))
    }

    func testUserDefaultsStoreRoundTrips() {
        let suite = "ReviewPromptPolicyTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = UserDefaultsReviewPromptStore(defaults: defaults)
        XCTAssertEqual(store.load(), ReviewPromptPolicy.State())

        let s = ReviewPromptPolicy.State(events: [.offlineBakeCompleted, .mapImported],
                                         launchDays: ["2026-01-02", "2026-01-01"], asked: true)
        store.save(s)
        XCTAssertEqual(UserDefaultsReviewPromptStore(defaults: defaults).load(), s)

        // junk from a newer build is ignored, not fatal
        defaults.set(["mapImported", "somethingFromTheFuture"], forKey: UserDefaultsReviewPromptStore.eventsKey)
        XCTAssertEqual(store.load().events, [.mapImported])
    }

    func testCoordinatorBecomesEligibleAndTakesTheRequestOnce() {
        let store = MemoryStore()
        let coordinator = ReviewPromptCoordinator(store: store, policy: policy())
        XCTAssertFalse(coordinator.isEligible)
        XCTAssertFalse(coordinator.takeRequest())

        coordinator.noteLaunch()
        advance(days: 1); coordinator.noteLaunch()
        advance(days: 1); coordinator.noteLaunch()
        coordinator.record(.mapImported)
        XCTAssertFalse(coordinator.isEligible)
        coordinator.record(.missionExported)
        XCTAssertTrue(coordinator.isEligible)

        XCTAssertTrue(coordinator.takeRequest())
        XCTAssertFalse(coordinator.isEligible)
        XCTAssertTrue(store.state.asked, "the once flag hits disk before anything is shown")
        XCTAssertFalse(coordinator.takeRequest())

        // a fresh coordinator over the same store remembers
        let again = ReviewPromptCoordinator(store: store, policy: policy())
        XCTAssertFalse(again.isEligible)
    }

    func testSuppressedCoordinatorStillRecordsButNeverAsks() {
        let store = MemoryStore()
        let coordinator = ReviewPromptCoordinator(store: store, policy: policy(), suppressed: true)
        coordinator.record(.mapImported)
        coordinator.record(.offlineBakeCompleted)
        for _ in 0..<3 { coordinator.noteLaunch(); advance(days: 1) }
        XCTAssertEqual(store.state.events.count, 2)
        XCTAssertFalse(coordinator.isEligible)
        XCTAssertFalse(coordinator.takeRequest())
    }

    func testRepeatedNoOpsDontTouchTheStore() {
        let store = MemoryStore()
        let coordinator = ReviewPromptCoordinator(store: store, policy: policy())
        coordinator.noteLaunch()
        coordinator.noteLaunch()
        coordinator.record(.mapImported)
        coordinator.record(.mapImported)
        XCTAssertEqual(store.saves, 2)
    }
}
