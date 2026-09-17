import XCTest
import Foundation
@testable import TacticalMaps

final class AOBearingNetworkPolicyTests: XCTestCase {
    func testLookupAndTileSessionsRejectEveryRedirect() {
        let redirected = URLRequest(
            url: URL(string: "https://unlisted.example/forwarded-coordinate")!
        )

        XCTAssertNil(NetworkSession.redirectDelegate.permittedRedirectRequest(redirected))
        XCTAssertNil(OnlineRasterTileSource.redirectDelegate.permittedRedirectRequest(redirected))
        XCTAssertTrue(NetworkSession.ephemeral.delegate === NetworkSession.redirectDelegate)
        XCTAssertTrue(OnlineRasterTileSource.session.delegate === OnlineRasterTileSource.redirectDelegate)
    }
}
