import XCTest

/// Measure two points on the map and open the elevation profile. With fake
/// terrain (a ridge part-way along, no network) the chart and line of sight
/// render and are screenshotted; without it, online lookups being off is
/// explained instead.
final class ElevationProfileUITests: XCTestCase {

    func testMeasuredLineShowsProfileAndLineOfSight() {
        let app = launch(fakeTerrain: true)
        measureTwoPoints(in: app)

        XCTAssertTrue(app.otherElements["profile.chart"].waitForExistence(timeout: 10), "No profile chart")
        let verdict = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'Blocked' OR label BEGINSWITH 'Clear'")).firstMatch
        XCTAssertTrue(verdict.waitForExistence(timeout: 5), "No line of sight verdict")
        sleep(1)
        attachScreenshot("profile-2m-observer")

        // Raise the observer to a 100 m mast.
        let stepper = app.steppers["profile.observerHeight"]
        XCTAssertTrue(stepper.waitForExistence(timeout: 5))
        for _ in 0..<9 { stepper.buttons.element(boundBy: 1).tap() }
        sleep(1)
        attachScreenshot("profile-100m-observer")

        // Drag across the chart to read a height.
        let chart = app.otherElements["profile.chart"]
        chart.coordinate(withNormalizedOffset: CGVector(dx: 0.2, dy: 0.5))
            .press(forDuration: 0.1, thenDragTo: chart.coordinate(withNormalizedOffset: CGVector(dx: 0.6, dy: 0.5)))
        let readout = app.staticTexts.matching(NSPredicate(format: "label CONTAINS 'from the start'")).firstMatch
        XCTAssertTrue(readout.waitForExistence(timeout: 5), "Dragging the chart shows no readout")
        attachScreenshot("profile-readout")
    }

    func testProfileExplainsWhenOnlineLookupsAreOff() {
        let app = launch(fakeTerrain: false)
        measureTwoPoints(in: app)
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS 'online lookups are off'"))
            .firstMatch.waitForExistence(timeout: 10), "The lookups-off message did not show")
    }

    /// Long-press the map, choose Measure From Here, tap a second point and
    /// open the profile from the Measure bar.
    private func measureTwoPoints(in app: XCUIApplication) {
        let window = app.windows.firstMatch
        window.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.62)).press(forDuration: 1.6)
        let measure = app.buttons["Measure From Here"]
        XCTAssertTrue(measure.waitForExistence(timeout: 5), "No point menu")
        measure.tap()
        window.coordinate(withNormalizedOffset: CGVector(dx: 0.72, dy: 0.66)).tap()
        let profile = app.buttons["measure.profile"]
        XCTAssertTrue(profile.waitForExistence(timeout: 5), "No profile button on the Measure bar")
        let enabled = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true"), object: profile)
        XCTAssertEqual(XCTWaiter().wait(for: [enabled], timeout: 5), .completed, "Profile stays disabled after two points")
        attachScreenshot("measure-two-points")
        profile.tap()
    }

    private func launch(fakeTerrain: Bool) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchEnvironment["TACMAP_UITEST_OFFLINE_BASEMAP"] = "1"
        if fakeTerrain { app.launchEnvironment["TACMAP_UITEST_FAKE_TERRAIN"] = "1" }
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

    private func attachScreenshot(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
