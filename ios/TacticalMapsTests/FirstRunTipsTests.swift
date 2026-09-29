import XCTest
@testable import TacticalMaps

final class FirstRunTipsTests: XCTestCase {
    private var defaults: UserDefaults!
    private let suite = "FirstRunTipsTests"

    override func setUp() {
        super.setUp()
        defaults = UserDefaults(suiteName: suite)
        defaults.removePersistentDomain(forName: suite)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suite)
        super.tearDown()
    }

    func testTipsShowOnceUntilReset() {
        XCTAssertTrue(FirstRunTips.shouldShow(defaults: defaults, environment: [:]))
        FirstRunTips.markSeen(defaults: defaults)
        XCTAssertFalse(FirstRunTips.shouldShow(defaults: defaults, environment: [:]))
        FirstRunTips.reset(defaults: defaults)
        XCTAssertTrue(FirstRunTips.shouldShow(defaults: defaults, environment: [:]))
    }

    func testOlderSeenVersionShowsAgain() {
        defaults.set(FirstRunTips.currentVersion - 1, forKey: FirstRunTips.defaultsKey)
        XCTAssertTrue(FirstRunTips.shouldShow(defaults: defaults, environment: [:]))
    }

    func testUITestLaunchesNeverShowTips() {
        XCTAssertFalse(FirstRunTips.shouldShow(defaults: defaults,
                                               environment: ["TACMAP_UITEST_OFFLINE_BASEMAP": "1"]))
    }

    func testTourUITestsCanAskForTheTour() {
        FirstRunTips.markSeen(defaults: defaults)
        XCTAssertTrue(FirstRunTips.shouldShow(defaults: defaults,
                                              environment: ["TACMAP_UITEST_TOUR": "1",
                                                            "TACMAP_UITEST_OFFLINE_BASEMAP": "1"]))
    }

    func testTourPointsAtEachMapControlOnce() {
        let steps = FirstRunTips.steps
        XCTAssertEqual(steps.map(\.id), Array(steps.indices))
        for step in steps {
            XCTAssertFalse(step.title.isEmpty)
            XCTAssertFalse(step.body.isEmpty)
        }
        let targets = steps.compactMap(\.target)
        XCTAssertEqual(Set(targets).count, targets.count, "A control is highlighted twice")
        XCTAssertEqual(Set(targets), [.crosshair, .header, .add, .mapHold, .menu, .labels, .night, .compass, .lock])
        XCTAssertNil(steps.last?.target, "The tour ends on a card that points at nothing")
    }

    // MARK: - Card placement (same cases as Android's MapTourPlacementTest)

    private func place(top: CGFloat, bottom: CGFloat, card: CGFloat,
                       screen: CGFloat = 1000) -> TourCalloutPlacement.Result {
        TourCalloutPlacement.compute(spotTop: top, spotBottom: bottom, cardHeight: card,
                                     screenHeight: screen, topInset: 40, bottomInset: 30,
                                     gap: 8, arrowHeight: 11)
    }

    func testControlNearTheTopGetsTheCardBelowIt() {
        XCTAssertEqual(place(top: 100, bottom: 150, card: 300),
                       .init(cardY: 169, arrowY: 158, arrow: .up))
    }

    func testControlNearTheBottomGetsTheCardAboveIt() {
        XCTAssertEqual(place(top: 850, bottom: 900, card: 300),
                       .init(cardY: 531, arrowY: 831, arrow: .down))
    }

    func testWhenBothSidesFitTheRoomierOneWins() {
        XCTAssertEqual(place(top: 600, bottom: 640, card: 300).arrow, .down)
    }

    func testCardThatFitsNeitherSideStaysOnScreen() {
        let result = place(top: 296, bottom: 330, card: 280, screen: 640)
        XCTAssertEqual(result.arrow, .none)
        XCTAssertGreaterThanOrEqual(result.cardY, 40)
        XCTAssertLessThanOrEqual(result.cardY + 280, 640 - 30)
    }
}
