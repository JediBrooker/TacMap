import XCTest

/// Explicitly enabled simulator audit. Every map/control point stays on the real UI/import path.
final class Task4PDFDeviceMatrixTests: XCTestCase {
    private var entries: [[String: Any]] = []
    private var output: URL!
    private var records: [[String: Any]] = []

    override func setUpWithError() throws {
        continueAfterFailure = false
        let env = ProcessInfo.processInfo.environment
        guard let manifest = env["TACMAP_DEVICE_MANIFEST"], let directory = env["TACMAP_DEVICE_OUTPUT"] else {
            throw XCTSkip("Opt-in device matrix requires the reviewed manifest and evidence directory")
        }
        let root = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(contentsOf: URL(fileURLWithPath: manifest))) as? [String: Any])
        entries = try XCTUnwrap(root["entries"] as? [[String: Any]])
        if let ids = env["TACMAP_DEVICE_FIXTURE_IDS"] {
            let selected = Set(ids.split(separator: ",").map(String.init))
            entries = entries.filter { selected.contains($0["id"] as? String ?? "") }
        }
        XCTAssertFalse(entries.isEmpty)
        output = URL(fileURLWithPath: directory)
        try FileManager.default.createDirectory(at: output, withIntermediateDirectories: true)
    }

    private func application() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-AppleLanguages", "(en)", "-AppleLocale", "en_US", "-AppleInterfaceStyle", "Light"]
        app.launchEnvironment = ["TACMAP_DEBUG_GRID": "1", "TACMAP_DEBUG_DEVICE_AUDIT": "1", "TACMAP_UITEST_OFFLINE_BASEMAP": "1"]
        return app
    }

    private func now() -> String {
        let f = ISO8601DateFormatter(); f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f.string(from: Date())
    }

    private func record(_ value: [String: Any]) throws {
        records.append(value)
        try JSONSerialization.data(withJSONObject: records, options: [.prettyPrinted, .sortedKeys])
            .write(to: output.appendingPathComponent("screens.json"), options: .atomic)
    }

    private func capture(_ app: XCUIApplication, id: String, camera: String, outcome: String,
                         requested: [Double]? = nil) throws {
        let time = now()
        let screenshot = app.screenshot()
        let name = "\(id)__\(camera)"
        try screenshot.pngRepresentation.write(to: output.appendingPathComponent(name + ".png"))
        try app.debugDescription.write(to: output.appendingPathComponent(name + "-ui.txt"), atomically: true, encoding: .utf8)
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
        var item: [String: Any] = ["fixture": id, "camera": camera, "outcome": outcome, "screenshotTime": time,
                                  "file": output.appendingPathComponent(name + ".png").path]
        if let requested { item["requestedCamera"] = requested }
        try record(item)
        print("TASK4_IOS_CAPTURE \(id) \(camera) \(outcome) \(time)")
    }

    private func settle(_ app: XCUIApplication, seconds: TimeInterval = 3) {
        XCTAssertTrue(app.buttons["Menu"].waitForExistence(timeout: 30), app.debugDescription)
        Thread.sleep(forTimeInterval: seconds)
    }

    private func verifyActiveIdentity(_ app: XCUIApplication, entry: [String: Any],
                                      camera: String = "active_library_identity") throws {
        let id = try XCTUnwrap(entry["id"] as? String)
        let name = URL(fileURLWithPath: try XCTUnwrap(entry["file"] as? String)).deletingPathExtension().lastPathComponent
        app.buttons["Menu"].tap()
        XCTAssertTrue(app.buttons["Layers and Labels"].waitForExistence(timeout: 5))
        app.buttons["Layers and Labels"].tap()
        let active = app.switches.matching(NSPredicate(format: "label CONTAINS %@", name)).firstMatch
        for _ in 0..<12 { if active.exists && active.isHittable { break }; app.collectionViews.firstMatch.swipeUp() }
        XCTAssertTrue(active.waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertTrue(active.isHittable, "Actual Imported Map section must identify the selected PDF")
        try capture(app, id: id, camera: camera, outcome: "actual-active-pdf-name-verified")
        app.buttons["Done"].tap()
    }

    private func verifyBlankNotPersisted(_ app: XCUIApplication, entry: [String: Any]) throws {
        let id = try XCTUnwrap(entry["id"] as? String)
        let name = URL(fileURLWithPath: try XCTUnwrap(entry["file"] as? String)).deletingPathExtension().lastPathComponent
        app.alerts.firstMatch.buttons.firstMatch.tap()
        app.buttons["Menu"].tap()
        XCTAssertTrue(app.buttons["Layers and Labels"].waitForExistence(timeout: 5))
        app.buttons["Layers and Labels"].tap()
        let row = app.buttons["maps.row." + name]
        var prior = ""
        for _ in 0..<24 {
            XCTAssertFalse(row.exists, "The blank precommit probe must leave no imported library entry")
            let visible = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "maps.row.")).allElementsBoundByIndex
                .filter { $0.isHittable }.map { $0.identifier }.joined(separator: "|")
            if !visible.isEmpty && visible == prior { break }
            prior = visible
            app.collectionViews.firstMatch.swipeUp()
        }
        XCTAssertFalse(row.exists)
        try capture(app, id: id, camera: "blank_library_absence", outcome: "blank-entry-absent-from-library")
        app.buttons["Done"].tap()
    }

    private func calibrate(_ app: XCUIApplication, entry: [String: Any]) throws {
        let points = try XCTUnwrap(entry["calibrationPoints"] as? [[String: Any]])
        XCTAssertEqual(points.count, 4)
        let datum = app.buttons["calibration.datum.WGS84"]
        if datum.waitForExistence(timeout: 5) { datum.tap() }
        for (index, point) in points.enumerated() {
            if index > 0 {
                app.terminate()
                app.launchEnvironment.removeValue(forKey: "TACMAP_DEBUG_IMPORT_PDF")
                app.launchEnvironment["TACMAP_DEBUG_CALIBRATION_POINT"] = "\(point["pageX"]!),\(point["pageY"]!)"
                app.launch()
            }
            XCTAssertTrue(app.buttons["calibration.add"].waitForExistence(timeout: 20), app.debugDescription)
            Thread.sleep(forTimeInterval: 1)
            app.buttons["calibration.add"].tap()
            let field = app.textFields["calibration.entry.field"]
            XCTAssertTrue(field.waitForExistence(timeout: 8), app.debugDescription)
            field.tap(); field.typeText(try XCTUnwrap(point["reference"] as? String))
            app.buttons["calibration.entry.save"].tap()
            XCTAssertEqual(app.buttons["calibration.points"].label, "Points (\(index + 1))")
        }
        let id = try XCTUnwrap(entry["id"] as? String)
        try capture(app, id: id, camera: "four_real_ui_fiduciaries", outcome: "calibrationPreview")
        XCTAssertTrue(app.buttons["calibration.finish"].isEnabled, app.debugDescription)
        app.buttons["calibration.finish"].tap()
        if app.buttons["Finish anyway"].waitForExistence(timeout: 2) { app.buttons["Finish anyway"].tap() }
        XCTAssertFalse(app.buttons["calibration.add"].waitForExistence(timeout: 2), app.debugDescription)
        app.terminate()
        app.launchEnvironment.removeValue(forKey: "TACMAP_DEBUG_IMPORT_PDF")
        app.launchEnvironment.removeValue(forKey: "TACMAP_DEBUG_CALIBRATION_POINT")
        app.launch()
        settle(app)
        XCTAssertFalse(app.buttons["calibration.add"].exists, "Finish must durably close the calibration on relaunch")
    }

    func testActualMergedMatrixWhenManifestProvided() throws {
        for entry in entries {
            let id = try XCTUnwrap(entry["id"] as? String)
            let expected = try XCTUnwrap(entry["expectedImportOutcome"] as? String)
            let app = application()
            app.launchEnvironment["TACMAP_DEBUG_IMPORT_PDF"] = try XCTUnwrap(entry["file"] as? String)
            if let point = (entry["calibrationPoints"] as? [[String: Any]])?.first {
                app.launchEnvironment["TACMAP_DEBUG_CALIBRATION_POINT"] = "\(point["pageX"]!),\(point["pageY"]!)"
            }
            print("TASK4_IOS_IMPORT \(id) expected=\(expected) \(now())")
            app.launch()
            if expected == "addRejected" {
                XCTAssertTrue(app.buttons["Calibrate now"].waitForExistence(timeout: 30), app.debugDescription)
                XCTAssertTrue(app.buttons["Later"].exists)
                if let reason = entry["expectedGeorefReason"] as? String {
                    let phrase = reason == "rmsGate" ? "its control points disagree with each other" : reason
                    XCTAssertTrue(app.alerts.firstMatch.label.contains(phrase) || app.debugDescription.contains(phrase), app.debugDescription)
                }
                try capture(app, id: id, camera: "outcome", outcome: "rejected-not-active")
                app.buttons["Later"].tap()
                XCTAssertFalse(app.buttons["calibration.add"].exists)
                app.terminate(); continue
            }
            if expected == "renderFailure:blank" {
                let message = app.staticTexts["Nothing on this PDF page could be drawn."]
                XCTAssertTrue(message.waitForExistence(timeout: 40), app.debugDescription)
                try capture(app, id: id, camera: "outcome", outcome: "blank-renderer-failure")
                try verifyBlankNotPersisted(app, entry: entry)
                app.terminate(); continue
            }
            if expected == "addAndCalibrate" {
                try calibrate(app, entry: entry)
            } else {
                settle(app, seconds: id == "real_usgs_sf_north" ? 6 : 3)
                XCTAssertFalse(app.buttons["calibration.add"].exists)
                XCTAssertFalse(app.buttons["Calibrate now"].exists)
                XCTAssertFalse(app.alerts.firstMatch.exists, app.debugDescription)
            }
            try verifyActiveIdentity(app, entry: entry)
            try capture(app, id: id, camera: "fit", outcome: expected == "addAndCalibrate" ? "calibrated-durable" : "activated-georef")
            app.launchEnvironment.removeValue(forKey: "TACMAP_DEBUG_IMPORT_PDF")
            let center = try XCTUnwrap(entry["center"] as? [Double])
            for zoom in [14, 16, 18, 20] {
                app.terminate()
                app.launchEnvironment["TACMAP_DEBUG_CAMERA"] = "\(center[0]),\(center[1]),\(zoom),0"
                app.launch(); settle(app)
                try capture(app, id: id, camera: "z\(zoom)", outcome: "render", requested: center + [Double(zoom), 0])
                if let offsets = entry["measurementCentersByZoom"] as? [String: [Double]], let offset = offsets[String(zoom)] {
                    app.terminate()
                    app.launchEnvironment["TACMAP_DEBUG_CAMERA"] = "\(offset[0]),\(offset[1]),\(zoom),0"
                    app.launch(); settle(app)
                    try capture(app, id: id, camera: "z\(zoom)_offset", outcome: "render", requested: offset + [Double(zoom), 0])
                }
            }
            if ["sf_iso", "rot5_iso", "edge_iso", "render_dense", "real_usgs_sf_north"].contains(id) {
                app.pinch(withScale: 0.5, velocity: -1); Thread.sleep(forTimeInterval: 2)
                try capture(app, id: id, camera: "gesture_zoom_out", outcome: "zoom-sweep")
                app.pinch(withScale: 2, velocity: 1); Thread.sleep(forTimeInterval: 2)
                try capture(app, id: id, camera: "gesture_zoom_in", outcome: "zoom-sweep")
                let a = app.coordinate(withNormalizedOffset: CGVector(dx: 0.35, dy: 0.60))
                let b = app.coordinate(withNormalizedOffset: CGVector(dx: 0.65, dy: 0.60))
                a.press(forDuration: 0.05, thenDragTo: b); Thread.sleep(forTimeInterval: 2)
                try capture(app, id: id, camera: "gesture_pan_seam", outcome: "pan-seam")
            }
            app.terminate()
        }
    }
    func testActualLiveAndUIBakedTilesWhenManifestProvided() throws {
        for entry in entries where ["rot5_iso", "render_dense", "real_usgs_sf_north"].contains(entry["id"] as? String ?? "") {
            let id = try XCTUnwrap(entry["id"] as? String)
            let center = try XCTUnwrap(entry["measurementCenter"] as? [Double] ?? entry["center"] as? [Double])
            let app = application()
            app.launchEnvironment["TACMAP_DEBUG_IMPORT_PDF"] = try XCTUnwrap(entry["file"] as? String)
            app.launchEnvironment["TACMAP_DEBUG_CAMERA"] = "\(center[0]),\(center[1]),14,0"
            app.launch(); settle(app, seconds: id == "real_usgs_sf_north" ? 6 : 3)
            XCTAssertFalse(app.alerts.firstMatch.exists, app.debugDescription)
            try verifyActiveIdentity(app, entry: entry)
            app.buttons["Menu"].tap()
            XCTAssertTrue(app.buttons["Layers and Labels"].waitForExistence(timeout: 5))
            app.buttons["Layers and Labels"].tap()
            let scroll = app.collectionViews.firstMatch
            XCTAssertTrue(scroll.waitForExistence(timeout: 5), app.debugDescription)
            try capture(app, id: id, camera: "normal_bake_sheet_initial", outcome: "normal-ui-bake-actions-search")
            let prepare = app.buttons["Generate Offline Tiles…"]
            let existingBake = app.buttons["Remove Offline Tiles"]
            for _ in 0..<12 {
                if prepare.isHittable || existingBake.isHittable { break }
                XCTAssertTrue(scroll.exists, "Normal Layers sheet must remain presented")
                let start = scroll.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.75))
                let end = scroll.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.35))
                start.press(forDuration: 0.05, thenDragTo: end)
            }
            if existingBake.exists && existingBake.isHittable {
                try capture(app, id: id, camera: "old_bake_normal_removal", outcome: "preserved-old-bake-explicit-ui-removal")
                existingBake.tap()
            }
            XCTAssertTrue(prepare.waitForExistence(timeout: 10), app.debugDescription)
            // A retained bake can already be decoded before removal. Cold reopen after
            // normal removal gives the genuine live baseline for the comparison.
            app.buttons["Done"].tap()
            XCUIDevice.shared.press(.home); Thread.sleep(forTimeInterval: 1)
            app.terminate()
            app.launchEnvironment.removeValue(forKey: "TACMAP_DEBUG_IMPORT_PDF")
            app.launch(); settle(app, seconds: id == "real_usgs_sf_north" ? 6 : 3)
            try verifyActiveIdentity(app, entry: entry, camera: "active_library_identity_after_removal")
            try capture(app, id: id, camera: "live_z14", outcome: "cold-live-after-normal-bake-removal", requested: center + [14, 0])
            app.buttons["Menu"].tap(); app.buttons["Layers and Labels"].tap()
            XCTAssertTrue(scroll.waitForExistence(timeout: 5), app.debugDescription)
            for _ in 0..<12 {
                if prepare.exists && prepare.isHittable { break }
                let start = scroll.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.75))
                let end = scroll.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.35))
                start.press(forDuration: 0.05, thenDragTo: end)
            }
            XCTAssertTrue(prepare.waitForExistence(timeout: 5), app.debugDescription)
            XCTAssertTrue(prepare.isEnabled, app.debugDescription)
            prepare.tap()
            let generate = app.buttons["Generate"]
            XCTAssertTrue(generate.waitForExistence(timeout: 40), app.debugDescription)
            for _ in 0..<8 { if generate.isHittable { break }; app.collectionViews.firstMatch.swipeUp() }
            XCTAssertTrue(generate.isEnabled, app.debugDescription)
            try capture(app, id: id, camera: "ui_bake_proposal", outcome: "normal-ui-bake-proposal")
            generate.tap()
            let remove = app.buttons["Remove Offline Tiles"]
            XCTAssertTrue(remove.waitForExistence(timeout: 360), app.debugDescription)
            let info = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Offline tiles: zoom ")).firstMatch
            XCTAssertTrue(info.exists, app.debugDescription)
            let rangeWord = try XCTUnwrap(info.label.split(separator: " ").first(where: { $0.contains("–") }))
            let limits = rangeWord.split(separator: "–")
            XCTAssertEqual(limits.count, 2)
            let minimum = try XCTUnwrap(limits.first.flatMap { Int($0) })
            let maximum = try XCTUnwrap(limits.last.flatMap { Int($0) })
            XCTAssertLessThanOrEqual(minimum, 14)
            XCTAssertGreaterThanOrEqual(maximum, 14, "The comparison must use a baked zoom, not a live fallback")
            print("TASK4_IOS_BAKE_RANGE \(id) \(info.label)")
            try capture(app, id: id, camera: "ui_bake_complete", outcome: "normal-ui-bake-complete")
            app.buttons["Done"].tap()
            Thread.sleep(forTimeInterval: 3)
            try capture(app, id: id, camera: "baked_z14", outcome: "baked-after-normal-ui-generation", requested: center + [14, 0])
            XCUIDevice.shared.press(.home); Thread.sleep(forTimeInterval: 1)
            app.terminate()
            app.launchEnvironment.removeValue(forKey: "TACMAP_DEBUG_IMPORT_PDF")
            app.launch(); settle(app)
            try capture(app, id: id, camera: "baked_z14_reopen", outcome: "baked-durable-reopen", requested: center + [14, 0])
            XCUIDevice.shared.press(.home); Thread.sleep(forTimeInterval: 1)
            app.terminate()
        }
    }

    func testActualColdLiveBaselineAfterNormalBakeRemovalWhenManifestProvided() throws {
        for entry in entries where ["rot5_iso", "render_dense", "real_usgs_sf_north"].contains(entry["id"] as? String ?? "") {
            let id = try XCTUnwrap(entry["id"] as? String)
            let center = try XCTUnwrap(entry["measurementCenter"] as? [Double] ?? entry["center"] as? [Double])
            let app = application()
            app.launchEnvironment["TACMAP_DEBUG_IMPORT_PDF"] = try XCTUnwrap(entry["file"] as? String)
            app.launchEnvironment["TACMAP_DEBUG_CAMERA"] = "\(center[0]),\(center[1]),14,0"
            app.launch(); settle(app)
            try verifyActiveIdentity(app, entry: entry)
            app.buttons["Menu"].tap(); app.buttons["Layers and Labels"].tap()
            let scroll = app.collectionViews.firstMatch
            XCTAssertTrue(scroll.waitForExistence(timeout: 5), app.debugDescription)
            let remove = app.buttons["Remove Offline Tiles"]
            for _ in 0..<12 {
                if remove.exists && remove.isHittable { break }
                let start = scroll.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.75))
                let end = scroll.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.35))
                start.press(forDuration: 0.05, thenDragTo: end)
            }
            XCTAssertTrue(remove.exists && remove.isHittable, app.debugDescription)
            try capture(app, id: id, camera: "normal_final_bake_removal", outcome: "normal-ui-remove-preserved-final-bake")
            remove.tap()
            XCTAssertTrue(app.buttons["Generate Offline Tiles…"].waitForExistence(timeout: 10), app.debugDescription)
            app.buttons["Done"].tap()
            XCUIDevice.shared.press(.home); Thread.sleep(forTimeInterval: 1)
            app.terminate()
            app.launchEnvironment.removeValue(forKey: "TACMAP_DEBUG_IMPORT_PDF")
            app.launch(); settle(app, seconds: id == "real_usgs_sf_north" ? 6 : 3)
            try verifyActiveIdentity(app, entry: entry, camera: "active_library_identity_after_removal")
            try capture(app, id: id, camera: "cold_live_z14_after_removal", outcome: "genuine-live-cold-reopen", requested: center + [14, 0])
            XCUIDevice.shared.press(.home); Thread.sleep(forTimeInterval: 1)
            app.terminate()
        }
    }

    func testActualPhaseEightGridOnOffWhenManifestProvided() throws {
        for entry in entries where ["sf_iso", "edge_iso", "syd_iso"].contains(entry["id"] as? String ?? "") {
            let id = try XCTUnwrap(entry["id"] as? String)
            let phases = try XCTUnwrap(entry["measurementPhaseCenters"] as? [String: [Double]])
            let center = try XCTUnwrap(phases["phase8m"])
            let app = application()
            app.launchEnvironment["TACMAP_DEBUG_IMPORT_PDF"] = try XCTUnwrap(entry["file"] as? String)
            app.launchEnvironment["TACMAP_DEBUG_CAMERA"] = "\(center[0]),\(center[1]),20,0"
            app.launch(); settle(app)
            try verifyActiveIdentity(app, entry: entry)
            try capture(app, id: id, camera: "z20_phase8_grid_on", outcome: "phase8-native-grid-on", requested: center + [20, 0])
            app.buttons["Menu"].tap()
            app.buttons["Layers and Labels"].tap()
            let grid = app.switches["MGRS Grid"].firstMatch
            XCTAssertTrue(grid.waitForExistence(timeout: 5), app.debugDescription)
            XCTAssertEqual(grid.value as? String, "1")
            for _ in 0..<12 { if grid.isHittable { break }; app.collectionViews.firstMatch.swipeDown() }
            XCTAssertTrue(grid.isHittable, app.debugDescription)
            try capture(app, id: id, camera: "normal_grid_switch_on", outcome: "normal-ui-switch-before-off")
            grid.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)).tap()
            let off = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == %@", "0"), object: grid)
            XCTAssertEqual(XCTWaiter.wait(for: [off], timeout: 5), .completed, app.debugDescription)
            try capture(app, id: id, camera: "normal_grid_switch_off", outcome: "normal-ui-switch-confirmed-off")
            app.buttons["Done"].tap()
            Thread.sleep(forTimeInterval: 2)
            try capture(app, id: id, camera: "z20_phase8_grid_off", outcome: "phase8-native-grid-off", requested: center + [20, 0])
            app.terminate()
        }
    }

    func testActualExistingCanberraAuditedViewWhenManifestProvided() throws {
        let entry = try XCTUnwrap(entries.first { $0["id"] as? String == "cbr50k_iso" })
        let centers = try XCTUnwrap(entry["measurementCentersByZoom"] as? [String: [Double]])
        let center = try XCTUnwrap(centers["20"])
        let app = application()
        app.launchEnvironment["TACMAP_DEBUG_CAMERA"] = "\(center[0]),\(center[1]),20,0"
        app.launch(); settle(app)
        if app.alerts.buttons["Open Anyway"].waitForExistence(timeout: 2) {
            try capture(app, id: "cbr50k_iso", camera: "normal_guard_recovery", outcome: "existing-normal-crash-suspect-ui")
            app.alerts.buttons["Open Anyway"].tap(); settle(app)
        }
        app.buttons["Menu"].tap(); app.buttons["Layers and Labels"].tap()
        let name = URL(fileURLWithPath: try XCTUnwrap(entry["file"] as? String)).deletingPathExtension().lastPathComponent
        let row = app.buttons["maps.row." + name]
        for _ in 0..<24 { if row.exists && row.isHittable { break }; app.collectionViews.firstMatch.swipeUp() }
        XCTAssertTrue(row.exists && row.isHittable, "Existing imported Canberra row must remain durable")
        row.tap(); app.buttons["Done"].tap()
        // Existing normal selection updates the source; reopen with camera hook only.
        XCUIDevice.shared.press(.home); Thread.sleep(forTimeInterval: 1)
        app.terminate(); app.launch(); settle(app)
        try verifyActiveIdentity(app, entry: entry)
        try capture(app, id: "cbr50k_iso", camera: "z20_offset_audited", outcome: "existing-normal-library-selection", requested: center + [20, 0])
        XCUIDevice.shared.press(.home); Thread.sleep(forTimeInterval: 1)
        app.terminate()
    }

}
