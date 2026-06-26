import XCTest

/// Captures App Store marketing screenshots deterministically.
///
/// Runs the real app, grants location, drops a friendly unit + a hostile
/// unit + an Assembly Area task at the crosshair (panning between each so
/// they don't stack), then visits the symbol builder, drawings panel and
/// About screen. Each `snap` is attached to the test result; the
/// `scripts/ios_screenshots.sh` wrapper extracts them from the .xcresult.
///
/// Location is set at the device level by the wrapper script
/// (`xcrun simctl location set`), so the basemap shows Shoalwater Bay.
final class ScreenshotTests: XCTestCase {
    private var app: XCUIApplication!
    private let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")

    override func setUpWithError() throws {
        continueAfterFailure = true
        app = XCUIApplication()
        app.launch()
        allowLocationIfNeeded()
        // Let MapKit load satellite tiles + the first location fix settle.
        sleep(8)
    }

    // MARK: - Helpers

    private func allowLocationIfNeeded() {
        for label in ["Allow While Using App", "Allow Once"] {
            let btn = springboard.buttons[label]
            if btn.waitForExistence(timeout: 4) { btn.tap(); break }
        }
    }

    private func snap(_ name: String) {
        let shot = XCUIScreen.main.screenshot()
        let att = XCTAttachment(screenshot: shot)
        att.name = name
        att.lifetime = .keepAlways
        add(att)
    }

    private func tap(_ label: String, timeout: TimeInterval = 10) -> Bool {
        let b = app.buttons[label]
        guard b.waitForExistence(timeout: timeout) else {
            NSLog("[shots] button not found: \(label)")
            return false
        }
        b.tap()
        return true
    }

    private func openMenu() {
        _ = tap("Menu")
        sleep(1)
    }

    /// Small map pan so the next "Add at Crosshair" lands at a fresh spot
    /// rather than stacking on the previous symbol. dx/dy are normalized.
    private func panMap(dx: CGFloat, dy: CGFloat) {
        let from = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        let to = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5 + dx, dy: 0.5 + dy))
        from.press(forDuration: 0.05, thenDragTo: to)
        sleep(1)
    }

    /// Menu → Symbology → Add at Crosshair → [configure] → Save → Done.
    private func addSymbol(configure: () -> Void) {
        openMenu()
        guard tap("Symbology") else { return }
        guard tap("Add at Crosshair") else { return }
        sleep(1)
        configure()
        _ = tap("Save")
        sleep(1)
        _ = tap("Done")           // dismiss the Symbology list back to the map
        sleep(1)
    }

    // MARK: - The capture run

    func testCaptureScreenshots() {
        // 1) Hero — live MGRS HUD over the basemap.
        snap("01-main")

        // 2) Friendly Infantry Platoon (defaults: friend / platoon / infantry).
        //    Grab the APP-6 builder shot while this sheet is open.
        openMenu()
        _ = tap("Symbology")
        _ = tap("Add at Crosshair")
        sleep(1)
        snap("02-symbol-builder")   // WaypointEditSheet: affiliation/echelon/function + live preview
        _ = tap("Save")
        sleep(1)
        _ = tap("Done")
        sleep(1)

        // 3) Hostile unit — change Affiliation to Hostile (red diamond).
        panMap(dx: 0.16, dy: -0.12)
        addSymbol {
            if tap("Affiliation", timeout: 6) {
                sleep(1)
                // Menu-style picker: pick the Hostile row.
                _ = tap("Hostile", timeout: 6)
                sleep(1)
            }
        }

        // 4) Assembly Area task graphic.
        panMap(dx: -0.18, dy: 0.14)
        addSymbol {
            _ = tap("Tasks", timeout: 6)   // segmented control -> control measure (Assembly Area default)
            sleep(1)
        }

        // Re-centre roughly between the three placements for the group shot.
        panMap(dx: 0.02, dy: -0.02)
        sleep(2)
        snap("03-symbols-on-map")

        // 5) Drawings panel.
        openMenu()
        _ = tap("Drawings")
        sleep(2)
        snap("04-drawings")

        // 6) About & Credits.
        openMenu()
        _ = tap("About & Credits")
        sleep(2)
        snap("05-about")
    }

    /// Recapture the hamburger menu + the Layers and Labels sheet from the
    /// current build (so the README shows the renamed "Layers and Labels"
    /// title rather than the older "Layers"). Extract `menu` / `layers`
    /// attachments from the .xcresult.
    func testCaptureMenuAndLayers() {
        openMenu()
        sleep(1)
        snap("menu")
        _ = tap("Close")
        sleep(1)

        openMenu()
        _ = tap("Layers and Labels")
        sleep(2)
        snap("layers")
    }

    // MARK: - Marketing set (1.1.0 — new features)

    /// Taps the first button whose label CONTAINS `text` (case-insensitive),
    /// so menu rows with trailing ellipses ("Unit Sync…", "Import / Export…")
    /// match without hard-coding the exact glyph.
    @discardableResult
    private func tapContaining(_ text: String, timeout: TimeInterval = 10) -> Bool {
        let pred = NSPredicate(format: "label CONTAINS[c] %@", text)
        let b = app.buttons.matching(pred).firstMatch
        guard b.waitForExistence(timeout: timeout) else {
            NSLog("[shots] no button containing: \(text)")
            return false
        }
        b.tap()
        return true
    }

    /// Dismiss whatever sheet/sub-page is up: try a close-style button, else
    /// drag the sheet down. Best-effort so the run never aborts on one sheet.
    private func dismissSheet() {
        for label in ["Close", "Done", "Cancel"] {
            let b = app.buttons[label]
            if b.exists && b.isHittable { b.tap(); sleep(1); return }
        }
        let top = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.10))
        let bottom = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.96))
        top.press(forDuration: 0.1, thenDragTo: bottom)
        sleep(1)
    }

    /// One run that captures the full 1.1.0 marketing set. Every step is
    /// best-effort (guarded), so a single missing control can't abort the rest.
    func testCaptureMarketing() {
        // 01 — live HUD: MGRS + UTM + mils compass over the basemap.
        snap("m01-hud")

        // 02 — the consolidated command menu.
        openMenu()
        sleep(1)
        snap("m02-menu")
        _ = tap("Close")
        sleep(1)

        // 03 — Unit Sync (the flagship): join a room so it shows Connected.
        openMenu()
        _ = tapContaining("Unit Sync")
        sleep(2)
        let codeField = app.textFields.firstMatch
        if codeField.waitForExistence(timeout: 6) {
            codeField.tap(); sleep(1)
            codeField.typeText("WOLFPACK-6")
            sleep(1)
            _ = tapContaining("Join")     // "Join / create room"
            sleep(5)                       // WebSocket handshake to the live relay
        }
        snap("m03-unit-sync")
        dismissSheet()

        // 04 — Weather + UAV flight-safety.
        openMenu()
        _ = tapContaining("Weather")
        sleep(3)               // open-meteo fetch
        snap("m04-weather")
        dismissSheet()

        // 05 — Layers & Labels: basemap selector + terrain heatmap.
        openMenu()
        _ = tap("Layers and Labels")
        sleep(2)
        snap("m05-layers")
        dismissSheet()

        // 06 — Import / Export sub-page (the new grouping).
        openMenu()
        _ = tapContaining("Import / Export")
        sleep(2)
        snap("m06-import-export")
        dismissSheet()         // back out of the sub-page / menu

        // 07 — APP-6 symbol builder with live preview.
        openMenu()
        _ = tap("Symbology")
        _ = tap("Add at Crosshair")
        sleep(1)
        snap("m07-symbol-builder")
        _ = tap("Save")
        sleep(1)
        _ = tap("Done")
        sleep(1)

        // 08 — a second hostile unit + assembly-area, for a populated map.
        panMap(dx: 0.16, dy: -0.12)
        addSymbol {
            if tap("Affiliation", timeout: 6) { sleep(1); _ = tap("Hostile", timeout: 6); sleep(1) }
        }
        panMap(dx: -0.18, dy: 0.14)
        addSymbol { _ = tap("Tasks", timeout: 6); sleep(1) }
        panMap(dx: 0.02, dy: -0.02)
        sleep(2)
        snap("m08-symbols")

        // 09 — GPX recording: the live REC indicator on the map.
        openMenu()
        _ = tapContaining("Start Track Recording")
        sleep(2)
        snap("m09-recording")

        // 10 — search (place name or full / partial MGRS).
        openMenu()
        _ = tapContaining("Search")
        sleep(2)
        snap("m10-search")
        dismissSheet()
    }

    /// Hero shot: join the unit-sync room that the host script
    /// (scripts/sync_push_situation.mjs) pre-populated with a NATO APP-6
    /// company-attack overlay. The snapshot delivers the shared picture and
    /// the on-screen Unit Sync indicator goes Connected — symbology (the #1
    /// feature) + live encrypted sync in one frame.
    func testCaptureHero() {
        openMenu()
        _ = tapContaining("Unit Sync")
        sleep(2)
        let codeField = app.textFields.firstMatch
        if codeField.waitForExistence(timeout: 6) {
            codeField.tap(); sleep(1)
            codeField.typeText("DAGGER-15")
            sleep(1)
            _ = tapContaining("Join")
            sleep(8)               // connect + snapshot + render the situation
        }
        dismissSheet()
        sleep(2)

        // Turn on map labels (stay on the default Apple Satellite basemap, which
        // loads reliably — graphics are bright/white so they read on it).
        openMenu()
        _ = tap("Layers and Labels")
        sleep(2)
        for label in ["Unit Labels", "Task Labels", "Drawing Labels"] {
            let sw = app.switches[label]
            if sw.waitForExistence(timeout: 3), (sw.value as? String) == "0" {
                sw.tap(); sleep(1)
            }
        }
        dismissSheet()
        sleep(6)
        snap("hero")
    }
}
