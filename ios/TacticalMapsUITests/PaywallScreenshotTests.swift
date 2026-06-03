import XCTest

/// Captures a clean screenshot of the paywall for the App Store Connect IAP
/// review screenshot. Opens it on demand via the menu's "Unlock Full Version"
/// row (trial active) or directly if the trial has lapsed. The scheme's
/// StoreKit config makes the real price render.
final class PaywallScreenshotTests: XCTestCase {
    private let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")

    func testCapturePaywall() {
        let app = XCUIApplication()
        app.launch()
        for label in ["Allow While Using App", "Allow Once"] {
            let b = springboard.buttons[label]
            if b.waitForExistence(timeout: 3) { b.tap(); break }
        }
        sleep(4)

        // Trial active -> reach the paywall via the menu's Unlock row.
        // Trial lapsed -> the gate paywall is already on screen.
        let menu = app.buttons["Menu"]
        if menu.waitForExistence(timeout: 8) {
            menu.tap()
            let unlock = app.buttons["Unlock Full Version"]
            if unlock.waitForExistence(timeout: 5) { unlock.tap() }
        }
        sleep(4) // let the sheet present + StoreKit price load

        let shot = XCUIScreen.main.screenshot()
        let att = XCTAttachment(screenshot: shot)
        att.name = "paywall"
        att.lifetime = .keepAlways
        add(att)
    }
}
