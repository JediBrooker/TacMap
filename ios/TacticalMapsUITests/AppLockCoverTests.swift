import XCTest

/// threat-model-1: with App Lock on, whatever was open when the app went to the
/// background (Unit Sync with the join code, Export with every object, the
/// system share sheet) has to end up under the lock view, not on top of it,
/// and still be there after unlock.
final class AppLockCoverTests: XCTestCase {
    /// throwaway PIN for the sim, the teardown turns App Lock off again
    private let pin = "2580"
    private let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")

    override func setUp() {
        continueAfterFailure = false
    }

    func testLockCoversOpenSheetsAndShareSheet() {
        let app = launch()
        addTeardownBlock { [self] in
            // relaunch so a run that bailed mid-lock still gets cleaned up
            let app = launch()
            setAppLock(app, on: false)
        }
        setAppLock(app, on: true)

        // Unit Sync, the sheet that shows the join code
        openMenuRow(app, "Unit Sync")
        let syncBar = app.navigationBars["Unit Sync"]
        XCTAssertTrue(syncBar.waitForExistence(timeout: 5))
        let syncDone = syncBar.buttons["Done"]
        backgroundAndReturn(app)
        assertLockOnTop(app, hiding: [syncBar, syncDone], context: "sync")
        unlock(app)
        XCTAssertTrue(syncDone.waitForExistence(timeout: 5))
        XCTAssertTrue(syncDone.isHittable, "the sync sheet should still be open after unlock")
        syncDone.tap()

        // Export, the GeoJSON of every waypoint and drawing
        openMenuRow(app, "Import / Export")
        // Import and Export both have a GeoJSON row, the export one comes last
        let geoJSONRows = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "GeoJSON"))
        XCTAssertTrue(geoJSONRows.firstMatch.waitForExistence(timeout: 5),
                      "no GeoJSON row in: \(app.buttons.allElementsBoundByIndex.map(\.label))")
        geoJSONRows.element(boundBy: geoJSONRows.count - 1).tap()
        let exportBar = app.navigationBars["Export"]
        XCTAssertTrue(exportBar.waitForExistence(timeout: 5))
        let exportDone = exportBar.buttons["Done"]
        let share = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Share GeoJSON")).firstMatch
        XCTAssertTrue(share.waitForExistence(timeout: 10))
        backgroundAndReturn(app)
        assertLockOnTop(app, hiding: [exportBar, exportDone, share], context: "export")
        unlock(app)
        XCTAssertTrue(share.waitForExistence(timeout: 5))
        XCTAssertTrue(share.isHittable, "the export sheet should still be open after unlock")

        // the UIKit share sheet on top of the export sheet
        share.tap()
        let shareSheet = app.otherElements["ActivityListView"]
        let sheetUp = shareSheet.waitForExistence(timeout: 10)
        attach("share-sheet-open")
        if sheetUp {
            backgroundAndReturn(app)
            assertLockOnTop(app, hiding: [shareSheet, exportDone], context: "share sheet")
            unlock(app)
            XCTAssertTrue(shareSheet.waitForExistence(timeout: 5), "the share sheet should still be open after unlock")
            let close = app.buttons.matching(NSPredicate(format: "label IN %@", ["Close", "Cancel"])).firstMatch
            if close.waitForExistence(timeout: 3) { close.tap() } else { app.swipeDown(velocity: .fast) }
        }
        XCTAssertTrue(exportDone.waitForExistence(timeout: 5))
        exportDone.tap()
    }

    // MARK: - helpers

    private func launch() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchEnvironment["TACMAP_UITEST_SKIP_TIPS"] = "1"
        app.launchEnvironment["TACMAP_UITEST_OFFLINE_BASEMAP"] = "1"
        app.launch()
        answerLocationPrompt()
        // App Lock left on by an earlier run starts the app locked
        if app.staticTexts["TacMap Locked"].waitForExistence(timeout: 3) { unlock(app) }
        XCTAssertTrue(app.buttons["Menu"].waitForExistence(timeout: 15))
        return app
    }

    private func answerLocationPrompt() {
        for label in ["Don’t Allow", "Don't Allow", "Allow While Using App"] {
            let button = springboard.buttons[label]
            if button.waitForExistence(timeout: label == "Don’t Allow" ? 4 : 1) {
                button.tap()
                return
            }
        }
    }

    private func openMenuRow(_ app: XCUIApplication, _ label: String) {
        app.buttons["Menu"].tap()
        let row = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", label)).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 5), "\(label) menu row missing")
        row.tap()
    }

    private func setAppLock(_ app: XCUIApplication, on: Bool) {
        openMenuRow(app, "App Lock")
        let bar = app.navigationBars["App Lock"]
        XCTAssertTrue(bar.waitForExistence(timeout: 5))
        let current = app.secureTextFields["Current PIN"]
        if current.waitForExistence(timeout: 2) {
            if !on {
                current.tap()
                current.typeText(pin)
                app.buttons["Turn Off App Lock"].tap()
                XCTAssertTrue(app.secureTextFields["New PIN"].waitForExistence(timeout: 5), "App Lock did not turn off")
            }
        } else if on {
            let new = app.secureTextFields["New PIN"]
            new.tap()
            new.typeText(pin)
            let confirm = app.secureTextFields["Confirm PIN"]
            confirm.tap()
            confirm.typeText(pin)
            app.buttons["Enable App Lock"].tap()
            XCTAssertTrue(current.waitForExistence(timeout: 5), "App Lock did not turn on")
        }
        bar.buttons["Done"].tap()
        XCTAssertTrue(bar.waitForNonExistence(timeout: 5))
    }

    private func backgroundAndReturn(_ app: XCUIApplication) {
        XCUIDevice.shared.press(.home)
        sleep(2)
        app.activate()
    }

    private func assertLockOnTop(_ app: XCUIApplication, hiding sheet: [XCUIElement], context: String) {
        let title = app.staticTexts["TacMap Locked"]
        XCTAssertTrue(title.waitForExistence(timeout: 10), "\(context): lock view never showed")
        attach("locked-\(context)")
        XCTAssertTrue(title.isHittable, "\(context): something is drawn over the lock view")
        XCTAssertTrue(app.secureTextFields["PIN"].isHittable, "\(context): PIN field not reachable")
        for element in sheet {
            XCTAssertFalse(element.exists && element.isHittable,
                           "\(context): \(element) is still reachable while locked")
        }
    }

    private func unlock(_ app: XCUIApplication) {
        let field = app.secureTextFields["PIN"]
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap()
        field.typeText(pin)
        XCTAssertTrue(app.staticTexts["TacMap Locked"].waitForNonExistence(timeout: 5), "PIN did not unlock")
    }

    private func attach(_ name: String) {
        let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
    }
}
