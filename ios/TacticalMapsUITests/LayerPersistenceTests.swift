import XCTest

/// Regression test for the bug where Layers toggles (labels/overlays) reset on
/// every relaunch because `LayerVisibility` was in-memory only. They now
/// persist to UserDefaults, so a toggle set before quitting must survive a
/// cold relaunch.
final class LayerPersistenceTests: XCTestCase {
    private let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")

    func testUnitLabelsTogglePersistsAcrossRelaunch() {
        var app = XCUIApplication()
        app.launch()
        allowLocation()
        sleep(3)

        // Turn Unit Labels OFF.
        openLayers(app)
        let toggle = app.switches["Unit Labels"]
        XCTAssertTrue(toggle.waitForExistence(timeout: 10), "Unit Labels toggle not found")
        if toggle.value as? String == "0" { flip(toggle); sleep(1) }   // normalise to ON
        XCTAssertEqual(toggle.value as? String, "1", "precondition: should start ON")
        flip(toggle)
        sleep(1)
        XCTAssertEqual(toggle.value as? String, "0", "toggle should read OFF after tap")
        app.buttons["Done"].tap()
        sleep(1)

        // Cold relaunch.
        app.terminate()
        sleep(1)
        app = XCUIApplication()
        app.launch()
        allowLocation()
        sleep(3)

        // Must still be OFF.
        openLayers(app)
        let toggleAfter = app.switches["Unit Labels"]
        XCTAssertTrue(toggleAfter.waitForExistence(timeout: 10))
        XCTAssertEqual(toggleAfter.value as? String, "0",
                       "Unit Labels should persist OFF across a relaunch")

        // Restore ON so we leave a clean default.
        flip(toggleAfter)
        sleep(1)
        app.buttons["Done"].tap()
    }

    /// Tap the switch control itself (right edge). A plain `.tap()` on a
    /// SwiftUI Form Toggle can land on the row label and fail to flip it.
    private func flip(_ sw: XCUIElement) {
        sw.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.5)).tap()
    }

    private func allowLocation() {
        for label in ["Allow While Using App", "Allow Once"] {
            let b = springboard.buttons[label]
            if b.waitForExistence(timeout: 3) { b.tap(); break }
        }
    }

    private func openLayers(_ app: XCUIApplication) {
        let menu = app.buttons["Menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 10), "Menu button missing")
        menu.tap()
        let layers = app.buttons["Layers"]
        XCTAssertTrue(layers.waitForExistence(timeout: 5), "Layers row missing")
        layers.tap()
        sleep(1)
    }
}
