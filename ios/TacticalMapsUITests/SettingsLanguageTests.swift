import XCTest

final class SettingsLanguageTests: XCTestCase {
    func testLanguageSwitchUpdatesSettingsAndSurvivesRelaunch() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchEnvironment["TACMAP_UITEST_OFFLINE_BASEMAP"] = "1"
        app.launch()
        openSettings(app)
        choose("Deutsch", app)
        XCTAssertTrue(app.staticTexts["Sprache"].firstMatch.waitForExistence(timeout: 5))
        XCTAssertFalse(app.textFields.matching(NSPredicate(format: "label CONTAINS[c] %@", "wss://")).firstMatch.exists)
        app.terminate()
        app.launch()
        openSettings(app)
        XCTAssertTrue(app.staticTexts["Sprache"].firstMatch.waitForExistence(timeout: 5))
        choose("English", app)
        XCTAssertTrue(app.staticTexts["Language"].firstMatch.waitForExistence(timeout: 5))
    }

    private func openSettings(_ app: XCUIApplication) {
        let menu = app.buttons["map.menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 15))
        menu.tap()
        let settings = app.buttons["menu.settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 5))
        if !settings.isHittable { app.swipeUp() }
        settings.tap()
    }

    private func choose(_ language: String, _ app: XCUIApplication) {
        let picker = app.buttons["settings.language"]
        XCTAssertTrue(picker.waitForExistence(timeout: 5))
        picker.tap()
        app.buttons[language].tap()
    }
}
