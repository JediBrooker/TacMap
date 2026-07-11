import XCTest
@testable import TacticalMaps

/// Guards against plist regressions that Apple flags at review time or that
/// leave users without a required usage description.
final class InfoPlistTests: XCTestCase {

    private var appInfo: [String: Any] {
        Bundle.main.infoDictionary ?? Bundle(for: type(of: self)).infoDictionary ?? [:]
    }

    func testFaceIDUsageDescriptionIsPresent() {
        let desc = appInfo["NSFaceIDUsageDescription"] as? String ?? ""
        XCTAssertFalse(desc.isEmpty,
            "NSFaceIDUsageDescription missing from Info.plist. Add it in project.yml "
            + "so Apple doesn't reject the build and users see why Face ID is needed.")
    }
}
