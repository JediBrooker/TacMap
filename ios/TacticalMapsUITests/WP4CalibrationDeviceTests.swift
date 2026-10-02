import XCTest

final class WP4CalibrationDeviceTests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
        let auditMarker = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().appendingPathComponent(".wp4-device-audit")
        guard FileManager.default.fileExists(atPath: auditMarker.path) else {
            throw XCTSkip("Manual device audit: create .wp4-device-audit and run the documented stages")
        }
    }

    private var fixture: String {
        URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().appendingPathComponent("testdata/geopdf/tacmap_grid_rot5_plain.pdf").path
    }

    func testPlainMapImportShowsCalibrationAndResumesAfterTermination() {
        let app = XCUIApplication()
        app.launchEnvironment["TACMAP_DEBUG_IMPORT_PDF"] = fixture
        app.launchEnvironment["TACMAP_DEBUG_GRID"] = "1"
        app.launchEnvironment["TACMAP_DEBUG_CALIBRATION_POINT"] = "254.1299411,194.8365854"
        app.launchEnvironment["TACMAP_UITEST_OFFLINE_BASEMAP"] = "1"
        app.launch()
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let deny = springboard.buttons["Don’t Allow"]
        if deny.waitForExistence(timeout: 3) { deny.tap() }
        let datum = app.buttons["calibration.datum.WGS84"]
        if datum.waitForExistence(timeout: 5) { datum.tap() }
        XCTAssertTrue(app.buttons["calibration.add"].waitForExistence(timeout: 20), app.debugDescription)
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "WP4 plain PDF calibration"
        screenshot.lifetime = .keepAlways
        add(screenshot)
        print(app.debugDescription)
        app.buttons["calibration.add"].tap()
        XCTAssertTrue(app.textFields["calibration.entry.field"].waitForExistence(timeout: 5))
        app.textFields["calibration.entry.field"].tap()
        app.textFields["calibration.entry.field"].typeText("10SEG 47000 77000")
        app.buttons["calibration.entry.save"].tap()
        XCTAssertTrue(app.buttons["calibration.points"].waitForExistence(timeout: 5))
        app.terminate()
        app.launchEnvironment.removeValue(forKey: "TACMAP_DEBUG_IMPORT_PDF")
        app.launchEnvironment["TACMAP_DEBUG_CALIBRATION_POINT"] = "705.947379,234.3654892"
        app.launch()
        XCTAssertTrue(app.buttons["calibration.add"].waitForExistence(timeout: 15), app.debugDescription)
        let resumed = XCTAttachment(screenshot: app.screenshot())
        resumed.name = "WP4 calibration resumed after termination"
        resumed.lifetime = .keepAlways
        add(resumed)
        let cases = [("10SEG 51000 77000", "705.947379,234.3654892"),
                     ("10SEG 51000 83000", "646.6540233,912.091646"),
                     ("10SEG 47000 83000", "194.8365854,872.5627422")]
        for (index, item) in cases.enumerated() {
            if index > 0 {
                app.terminate()
                app.launchEnvironment["TACMAP_DEBUG_CALIBRATION_POINT"] = item.1
                app.launch()
                XCTAssertTrue(app.buttons["calibration.add"].waitForExistence(timeout: 15), app.debugDescription)
            }
            app.buttons["calibration.add"].tap()
            let field = app.textFields["calibration.entry.field"]
            XCTAssertTrue(field.waitForExistence(timeout: 5), app.debugDescription)
            field.tap()
            field.typeText(item.0)
            app.buttons["calibration.entry.save"].tap()
        }
        XCTAssertEqual(app.buttons["calibration.points"].label, "Points (4)")
        let finished = XCTAttachment(screenshot: app.screenshot())
        finished.name = "WP4 four grid intersection fiduciaries"
        finished.lifetime = .keepAlways
        add(finished)
        XCTAssertTrue(app.buttons["calibration.finish"].isEnabled, app.debugDescription)
        app.buttons["calibration.finish"].tap()
        let saveAnyway = app.buttons["Finish anyway"]
        if saveAnyway.waitForExistence(timeout: 2) { saveAnyway.tap() }
        XCTAssertFalse(app.buttons["calibration.add"].waitForExistence(timeout: 3), app.debugDescription)
    }

    func testSecondImportKeepsFirstAndConfirmedDeletionRemovesFirst() {
        let app = XCUIApplication()
        app.launchEnvironment["TACMAP_DEBUG_IMPORT_PDF"] = URL(fileURLWithPath: fixture).deletingLastPathComponent()
            .appendingPathComponent("tacmap_grid_sf_iso.pdf").path
        app.launchEnvironment["TACMAP_DEBUG_GRID"] = "1"
        app.launchEnvironment["TACMAP_UITEST_OFFLINE_BASEMAP"] = "1"
        app.launch()
        XCTAssertTrue(app.buttons["Menu"].waitForExistence(timeout: 15), app.debugDescription)
        app.buttons["Menu"].tap()
        XCTAssertTrue(app.buttons["Layers and Labels"].waitForExistence(timeout: 5))
        app.buttons["Layers and Labels"].tap()
        let first = app.buttons["maps.row.tacmap_grid_rot5_plain"]
        let second = app.buttons["maps.row.tacmap_grid_sf_iso"]
        for _ in 0..<8 {
            if first.exists { break }
            app.collectionViews.firstMatch.swipeUp()
        }
        XCTAssertTrue(first.waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertTrue(second.exists, app.debugDescription)
        let kept = XCTAttachment(screenshot: app.screenshot())
        kept.name = "WP4 second import keeps calibrated first map"
        kept.lifetime = .keepAlways
        add(kept)
        app.buttons["maps.menu.tacmap_grid_rot5_plain"].tap()
        let delete = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] %@", "Delete")).firstMatch
        XCTAssertTrue(delete.waitForExistence(timeout: 5), app.debugDescription)
        delete.tap()
        XCTAssertTrue(app.buttons.matching(identifier: "maps.delete.confirm").firstMatch.waitForExistence(timeout: 5), app.debugDescription)
        app.buttons.matching(identifier: "maps.delete.confirm").firstMatch.tap()
        XCTAssertFalse(first.waitForExistence(timeout: 3), app.debugDescription)
        XCTAssertTrue(second.exists)
        let deleted = XCTAttachment(screenshot: app.screenshot())
        deleted.name = "WP4 confirmed delete keeps second map"
        deleted.lifetime = .keepAlways
        add(deleted)
    }

    func testPrepareCalibrationDraftForRecoveryAudit() {
        let app = XCUIApplication()
        app.launchEnvironment["TACMAP_DEBUG_GRID"] = "1"
        app.launchEnvironment["TACMAP_UITEST_OFFLINE_BASEMAP"] = "1"
        app.launch()
        app.buttons["Menu"].tap()
        app.buttons["Layers and Labels"].tap()
        let menu = app.buttons["maps.menu.tacmap_grid_sf_iso"]
        for _ in 0..<8 {
            if menu.exists { break }
            app.collectionViews.firstMatch.swipeUp()
        }
        XCTAssertTrue(menu.exists, app.debugDescription)
        menu.tap()
        let calibrate = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] %@", "Calibrate")).firstMatch
        XCTAssertTrue(calibrate.waitForExistence(timeout: 5), app.debugDescription)
        calibrate.tap()
        XCTAssertTrue(app.buttons["calibration.add"].waitForExistence(timeout: 10), app.debugDescription)
        app.buttons["calibration.add"].tap()
        let field = app.textFields["calibration.entry.field"]
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap()
        field.typeText("10SEG 49000 80000")
        app.buttons["calibration.entry.save"].tap()
        app.terminate()
    }

    func testCorruptLibraryRetryRebuildsAndRelaunches() throws {
        let app = XCUIApplication()
        app.launchEnvironment["TACMAP_DEBUG_GRID"] = "1"
        app.launchEnvironment["TACMAP_UITEST_OFFLINE_BASEMAP"] = "1"
        app.launch()
        let retry = app.buttons["Retry"]
        guard retry.waitForExistence(timeout: 10) else { throw XCTSkip("Requires host-prepared corrupt library audit") }
        let corrupt = XCTAttachment(screenshot: app.screenshot())
        corrupt.name = "WP4 corrupt library preserves files before Retry"
        corrupt.lifetime = .keepAlways
        add(corrupt)
        retry.tap()
        if app.buttons["calibration.close"].waitForExistence(timeout: 5) {
            app.buttons["calibration.close"].tap()
            let keep = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] %@", "Keep points for later")).firstMatch
            if keep.waitForExistence(timeout: 2) { keep.tap() }
        }
        XCTAssertTrue(app.buttons["Menu"].waitForExistence(timeout: 20), app.debugDescription)
        app.buttons["Menu"].tap()
        app.buttons["Layers and Labels"].tap()
        let recovered = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "maps.row.Recovered map"))
        for _ in 0..<8 {
            if recovered.count > 0 { break }
            app.collectionViews.firstMatch.swipeUp()
        }
        XCTAssertGreaterThan(recovered.count, 0, app.debugDescription)
        let rebuilt = XCTAttachment(screenshot: app.screenshot())
        rebuilt.name = "WP4 recovered library lists original map"
        rebuilt.lifetime = .keepAlways
        add(rebuilt)
        app.terminate()
        app.launch()
        XCTAssertFalse(app.buttons["Retry"].waitForExistence(timeout: 3), app.debugDescription)
    }

}
