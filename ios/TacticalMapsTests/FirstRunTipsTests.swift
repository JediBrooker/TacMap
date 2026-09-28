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

    func testEveryTipHasTitleAndBody() {
        XCTAssertEqual(FirstRunTips.tips.count, 4)
        for tip in FirstRunTips.tips {
            XCTAssertFalse(tip.title.isEmpty)
            XCTAssertFalse(tip.body.isEmpty)
        }
    }
}
