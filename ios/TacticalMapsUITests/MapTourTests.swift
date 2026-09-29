import XCTest

/// The guided tour walks through every map control, can go back, closes on
/// the last step, and can be replayed from About. Each step is screenshotted
/// so the spotlight and callout placement can be checked by eye.
final class MapTourTests: XCTestCase {

    private let stepCount = 10

    func testTourWalksThroughEveryStep() {
        let app = launch(tour: true)
        let next = app.buttons["tour.next"]
        XCTAssertTrue(next.waitForExistence(timeout: 15), "The tour did not start on first launch")

        for step in 1...stepCount {
            sleep(1) // let the spotlight settle
            attachScreenshot("tour-step-\(step)")
            if step == 3 {
                // Back returns to the previous step and Next comes back again.
                app.buttons["tour.back"].tap()
                XCTAssertTrue(app.buttons["tour.back"].waitForExistence(timeout: 2))
                next.tap()
            }
            if step < stepCount {
                XCTAssertTrue(app.buttons["tour.skip"].exists, "Skip is missing on step \(step)")
            }
            next.tap()
        }
        XCTAssertTrue(next.waitForNonExistenceCompat(timeout: 5), "The tour did not close after the last step")
        XCTAssertTrue(app.buttons["Menu"].isHittable, "The map is not usable after the tour")
    }

    func testAboutReplaysTheTour() {
        let app = launch(tour: false)
        XCTAssertFalse(app.buttons["tour.next"].exists, "UI test launches should not start the tour")

        app.buttons["Menu"].tap()
        let about = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "About")).firstMatch
        XCTAssertTrue(about.waitForExistence(timeout: 5))
        about.tap()
        let replay = app.buttons["about.replayTour"]
        XCTAssertTrue(replay.waitForExistence(timeout: 5), "About has no Replay Tour button")
        attachScreenshot("about-replay")
        replay.tap()

        XCTAssertTrue(app.buttons["tour.next"].waitForExistence(timeout: 5), "Replay Tour did not start the tour")
        app.buttons["tour.skip"].tap()
        XCTAssertTrue(app.buttons["tour.next"].waitForNonExistenceCompat(timeout: 5), "Skip did not close the tour")
    }

    private func launch(tour: Bool) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchEnvironment["TACMAP_UITEST_OFFLINE_BASEMAP"] = "1"
        if tour { app.launchEnvironment["TACMAP_UITEST_TOUR"] = "1" }
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

private extension XCUIElement {
    func waitForNonExistenceCompat(timeout: TimeInterval) -> Bool {
        let gone = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: self)
        return XCTWaiter().wait(for: [gone], timeout: timeout) == .completed
    }
}
