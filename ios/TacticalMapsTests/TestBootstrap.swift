import XCTest
@testable import TacticalMaps

/// Principal class of the unit test bundle (INFOPLIST_KEY_NSPrincipalClass in
/// project.yml), XCTest instantiates it once when the bundle loads.
///
/// The tests run inside the real app, and by then RootGate has already decided
/// full version vs free viewer from the simulator's keychain trial, which on a
/// long lived sim is usually lapsed. The units under test want today's default
/// (everything unlocked) unless a test flips it on purpose, so every test case
/// starts as the full version.
final class TestBootstrap: NSObject, XCTestObservation {
    override init() {
        super.init()
        FeatureAccess.shared.isFullVersion = true
        XCTestObservationCenter.shared.addTestObserver(self)
    }

    func testCaseWillStart(_ testCase: XCTestCase) {
        FeatureAccess.shared.isFullVersion = true
    }
}
