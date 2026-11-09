import XCTest

/// Captures a clean paywall screenshot for App Store Connect IAP review.
/// 3.1: there is no launch gate anymore (a lapsed trial is the free viewer,
/// the map stays up), so the sheet is always reached via the menu's
/// "Unlock Full Version" row, trial active or not. Scheme's StoreKit config
/// makes the real price render.
final class PaywallScreenshotTests: XCTestCase {
    private let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")

    func testCapturePaywall() {
        let app = XCUIApplication()
        app.launchEnvironment["TACMAP_UITEST_SKIP_TIPS"] = "1"
        app.launch()
        for label in ["Allow While Using App", "Allow Once"] {
            let b = springboard.buttons[label]
            if b.waitForExistence(timeout: 3) { b.tap(); break }
        }
        sleep(4)

        // The Unlock row sits under the trial banner (trial active) or the
        // free viewer banner (trial lapsed). Either way the menu is up.
        let menu = app.buttons["Menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 8), "the map and its menu are always up, there is no launch gate")
        menu.tap()
        let unlock = app.buttons["Unlock Full Version"]
        if unlock.waitForExistence(timeout: 5) { unlock.tap() }
        sleep(4) // let the sheet present + StoreKit price load

        let shot = XCUIScreen.main.screenshot()
        let att = XCTAttachment(screenshot: shot)
        att.name = "paywall"
        att.lifetime = .keepAlways
        add(att)
    }
}
