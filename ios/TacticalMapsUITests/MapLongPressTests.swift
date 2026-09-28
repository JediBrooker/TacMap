import XCTest

/// The point menu opens only on a deliberate, still hold on empty map.
/// Dragging the map, however slowly and however long the finger stays down,
/// must keep panning instead.
final class MapLongPressTests: XCTestCase {

    func testDraggingTheMapNeverOpensThePointMenu() {
        let app = launchOnMap()
        let window = app.windows.firstMatch
        let start = window.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.58))
        let end = window.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.45))
        start.press(forDuration: 0.1, thenDragTo: end, withVelocity: .slow, thenHoldForDuration: 1.5)
        XCTAssertFalse(app.buttons["Copy Coordinates"].waitForExistence(timeout: 2),
                       "A slow drag opened the point menu")
    }

    func testStillHoldOpensThePointMenu() {
        let app = launchOnMap()
        let window = app.windows.firstMatch
        window.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.58)).press(forDuration: 1.6)
        XCTAssertTrue(app.buttons["Copy Coordinates"].waitForExistence(timeout: 5),
                      "A still hold on empty map did not open the point menu")
    }

    private func launchOnMap() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchEnvironment["TACMAP_UITEST_OFFLINE_BASEMAP"] = "1"
        app.launch()
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        for label in ["Don’t Allow", "Don't Allow", "Allow While Using App"] {
            let button = springboard.buttons[label]
            if button.waitForExistence(timeout: label == "Don’t Allow" ? 8 : 1) {
                button.tap()
                break
            }
        }
        XCTAssertTrue(app.buttons["Menu"].waitForExistence(timeout: 15))
        return app
    }
}
