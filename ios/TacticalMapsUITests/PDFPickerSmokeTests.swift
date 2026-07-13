import XCTest

final class PDFPickerSmokeTests: XCTestCase {
    private let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    func testPDFMapActionOpensDocumentPicker() {
        let app = XCUIApplication()
        app.launchEnvironment["TACMAP_UITEST_OFFLINE_BASEMAP"] = "1"
        app.launch()
        allowLocationIfNeeded()

        let menu = app.buttons["Menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 10), "Menu did not appear")
        menu.tap()

        tapButton(containing: "Import / Export", in: app)
        tapButton(containing: "PDF Map", in: app)

        let cancel = app.buttons["Cancel"]
        XCTAssertTrue(
            cancel.waitForExistence(timeout: 10),
            "The iOS document picker did not open for PDF import"
        )
        cancel.tap()
    }

    private func tapButton(containing text: String, in app: XCUIApplication) {
        let predicate = NSPredicate(format: "label CONTAINS[c] %@", text)
        let button = app.buttons.matching(predicate).firstMatch
        XCTAssertTrue(button.waitForExistence(timeout: 10), "Missing button containing \(text)")
        button.tap()
    }

    private func allowLocationIfNeeded() {
        for label in ["Allow While Using App", "Allow Once"] {
            let button = springboard.buttons[label]
            if button.waitForExistence(timeout: 3) {
                button.tap()
                return
            }
        }
    }
}
