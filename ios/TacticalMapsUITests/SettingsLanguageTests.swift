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

/// Explicit release captures, separate from the short CI language-switch smoke test.
final class LocalizationCaptureTests: XCTestCase {
    func testEnglishScreens() { capture(language: "en", largeText: false) }
    func testGermanScreens() { capture(language: "de", largeText: false) }
    func testGermanLargeTextScreens() { capture(language: "de", largeText: true) }

    func testExpandedEnglishScreens() { capture(language: "en", largeText: false, expanded: true) }

    private func capture(language: String, largeText: Bool, expanded: Bool = false) {
        continueAfterFailure = false
        let destinations: [(String, String)] = [
            ("Search…", "Suchen …"),
            ("Symbology", "Symbole"),
            ("Drawings", "Zeichnungen"),
            ("Layers and Labels", "Ebenen und Beschriftungen"),
            ("Weather & UAV Safety", "Wetter und Drohnensicherheit"),
            ("Import / Export…", "Importieren / Exportieren …"),
            ("TacMap Chat…", "TacMap Chat …"),
            ("Unit Sync…", "Einheitensynchronisierung …"),
            ("App Lock…", "App-Sperre …"),
            ("Settings, Privacy & OPSEC", "Einstellungen, Datenschutz und OPSEC"),
            ("About & Credits", "Über TacMap und Mitwirkende")
        ]
        let app = XCUIApplication()
        app.launchEnvironment["TACMAP_UITEST_OFFLINE_BASEMAP"] = "1"
        if expanded { app.launchEnvironment["TACMAP_UITEST_EXPANDED_TEXT"] = "1" }
        app.launchArguments = ["-app.displayLanguage", language]
        if largeText {
            app.launchArguments += ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]
        }
        for (index, destination) in destinations.enumerated() {
            app.launch()
            let menu = app.buttons["map.menu"]
            XCTAssertTrue(menu.waitForExistence(timeout: 20))
            if index == 0 { attach(app, "00-map", expanded ? "expanded" : language, largeText) }
            menu.tap()
            let label = language == "de" ? destination.1 : destination.0
            let button = expanded
                ? app.buttons.matching(NSPredicate(format: "label CONTAINS %@", label)).firstMatch
                : app.buttons[label].firstMatch
            XCTAssertTrue(button.waitForExistence(timeout: 5), label)
            for _ in 0..<5 where !button.isHittable { app.swipeUp() }
            XCTAssertTrue(button.isHittable, label)
            if index == 0 { attach(app, "01-menu", expanded ? "expanded" : language, largeText) }
            button.tap()
            // Wait for menu dismissal/navigation before taking a real rendered capture.
            RunLoop.current.run(until: Date().addingTimeInterval(1.5))
            attach(app, String(format: "%02d", index + 2) + "-" + destination.0, expanded ? "expanded" : language, largeText)
            app.terminate()
        }
    }

    private func attach(_ app: XCUIApplication, _ name: String, _ language: String, _ largeText: Bool) {
        let prefix = language + (largeText ? "-large-" : "-") + name
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = prefix
        screenshot.lifetime = .keepAlways
        add(screenshot)
        let tree = XCTAttachment(string: app.debugDescription)
        tree.name = prefix + "-accessibility"
        tree.lifetime = .keepAlways
        add(tree)
    }
}
