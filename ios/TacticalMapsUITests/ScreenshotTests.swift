import XCTest

/// Captures App Store marketing screenshots deterministically.
///
/// Runs the real app, grants location, drops a friendly unit + hostile
/// unit + Assembly Area task at the crosshair (panning between each so
/// they don't stack), then visits symbol builder, drawings panel and
/// About screen. Each snap is attched to the test result and
/// scripts/ios_screenshots.sh extracts them from the .xcresult.
///
/// Location is set at device level by the wrapper script
/// (xcrun simctl location set) so the basemap shows Shoalwater Bay.
final class ScreenshotTests: XCTestCase {
    private var app: XCUIApplication!
    private let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")

    override func setUpWithError() throws {
        continueAfterFailure = true
        app = XCUIApplication()
        // Force online basemaps + lookups on for the shots (OPSEC defaults them
        // off, which would render a dark basemap). Read by OpsecSettings.init.
        // EXCEPT the GeoPDF slide: it wants the imported PDF sheet as the basemap,
        // and an online satellite layer would render on top of it.
        if name.contains("GeoPdf") {
            app.launchEnvironment["TACMAP_UITEST_OFFLINE_BASEMAP"] = "1"
        } else {
            app.launchEnvironment["TACMAP_UITEST_ONLINE"] = "1"
        }
        app.launch()
        allowLocationIfNeeded()
        // Let the raster basemap stream its first tiles + the location fix settle.
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

    // Small pan so the next Add at Crosshair lands at a fresh spot
    // instead of stacking on the previous symbol. dx/dy normalized.
    private func panMap(dx: CGFloat, dy: CGFloat) {
        let from = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        let to = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5 + dx, dy: 0.5 + dy))
        from.press(forDuration: 0.05, thenDragTo: to)
        sleep(1)
    }

    // Menu > Symbology > Add at Crosshair > [configure] > Save > Done.
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
        // 1) Hero - live MGRS HUD over the basemap.
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

        // 3) Hostile unit - change Affiliation to Hostile (red diamond).
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

    // MARK: - Marketing set (1.1.0 - new features)

    // Taps the first button whose label contains `text` (case-insensitive)
    // so menu rows with trailing ellipses ("Unit Sync...", "Import / Export...")
    // match without hard-coding the exact glyph.
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

    // Dismiss whatever sheet is up. Try a close-style button, else just
    // drag the sheet down. Best-effort so the run doesn't abort on one sheet.
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
        // 01 - live HUD: MGRS + UTM + mils compass over the basemap.
        snap("m01-hud")

        // 02 - the consolidated command menu.
        openMenu()
        sleep(1)
        snap("m02-menu")
        _ = tap("Close")
        sleep(1)

        // 03 - Unit Sync (the flagship): join a room so it shows Connected.
        openMenu()
        _ = tapContaining("Unit Sync")
        sleep(2)
        let codeField = app.textFields.firstMatch
        if codeField.waitForExistence(timeout: 6) {
            codeField.tap(); sleep(1)
            codeField.typeText("WOLFPACKSHOOT26")
            sleep(1)
            _ = tapContaining("Join")     // "Join / create room"
            sleep(18)                       // WebSocket handshake to the live relay
        }
        snap("m03-unit-sync")
        dismissSheet()

        // 04 - Weather + UAV flight-safety.
        openMenu()
        _ = tapContaining("Weather")
        sleep(3)               // open-meteo fetch
        snap("m04-weather")
        dismissSheet()

        // 05 - Layers & Labels: basemap selector + terrain heatmap.
        openMenu()
        _ = tap("Layers and Labels")
        sleep(2)
        snap("m05-layers")
        dismissSheet()

        // 06 - Import / Export sub-page (the new grouping).
        openMenu()
        _ = tapContaining("Import / Export")
        sleep(2)
        snap("m06-import-export")
        dismissSheet()         // back out of the sub-page / menu

        // 07 - APP-6 symbol builder with live preview.
        openMenu()
        _ = tap("Symbology")
        _ = tap("Add at Crosshair")
        sleep(1)
        snap("m07-symbol-builder")
        _ = tap("Save")
        sleep(1)
        _ = tap("Done")
        sleep(1)

        // 08 - a second hostile unit + assembly-area, for a populated map.
        panMap(dx: 0.16, dy: -0.12)
        addSymbol {
            if tap("Affiliation", timeout: 6) { sleep(1); _ = tap("Hostile", timeout: 6); sleep(1) }
        }
        panMap(dx: -0.18, dy: 0.14)
        addSymbol { _ = tap("Tasks", timeout: 6); sleep(1) }
        panMap(dx: 0.02, dy: -0.02)
        sleep(2)
        snap("m08-symbols")

        // 09 - GPX recording: the live REC indicator on the map.
        openMenu()
        _ = tapContaining("Start Track Recording")
        sleep(2)
        snap("m09-recording")

        // 10 - search (place name or full / partial MGRS).
        openMenu()
        _ = tapContaining("Search")
        sleep(2)
        snap("m10-search")
        dismissSheet()
    }

    // Hero shot: join the unit-sync room that the host script
    // (scripts/sync_push_situation.mjs) pre-populated with a NATO APP-6
    // company-attack overlay. Snapshot shows the shared picture and the
    // on-screen Unit Sync indicator goes Connected - symbology (the #1
    // feature) + live encrypted sync in one frame.
    func testCaptureHero() {
        openMenu()
        _ = tapContaining("Unit Sync")
        sleep(2)
        let codeField = app.textFields.firstMatch
        if codeField.waitForExistence(timeout: 6) {
            codeField.tap(); sleep(1)
            codeField.typeText("WOLFPACKSHOOT26")
            sleep(1)
            _ = tapContaining("Join")
            sleep(20)               // connect + snapshot + render the situation
        }
        dismissSheet()
        sleep(2)

        // Turn on map labels (stay on the default Apple Satellite basemap, which
        // loads reliably, graphics are bright/white so they read on it).
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

    // Capture each basemap (Satellite / Esri / OpenTopoMap terrain) as a
    // clean map for the "Satellite or terrain" fan slide. OTM rate-limits
    // its tiles so the terrain step waits ages for them to stream in.
    func testCaptureBasemaps() {
        sleep(2)
        // No Apple/MapKit basemap any more - the map is a custom raster renderer.
        // Three visually distinct offline-capable basemaps for the fan.
        let maps: [(String, String, UInt32)] = [
            ("Satellite (Esri)", "bm-satellite", 10),
            ("Topographic (Esri)", "bm-esri", 12),
            ("Topographic (OpenTopoMap)", "bm-terrain", 95)
        ]
        for (label, name, wait) in maps {
            openMenu()
            _ = tap("Layers and Labels")
            sleep(1)
            let pred = NSPredicate(format: "label CONTAINS[c] %@", label)
            let row = app.buttons.matching(pred).firstMatch
            var tries = 0
            while !row.isHittable && tries < 6 { app.swipeUp(); sleep(1); tries += 1 }
            if row.exists { row.tap() } else { _ = tapContaining(label) }
            sleep(1)
            dismissSheet()
            if name == "bm-terrain" {
                // OpenTopoMap rate-limits; nudge the map repeatedly so MapKit
                // keeps re-requesting tiles until they fill in (~3.5 min).
                for _ in 0..<12 {
                    panMap(dx: 0.06, dy: 0.05); sleep(8)
                    panMap(dx: -0.06, dy: -0.05); sleep(8)
                }
            } else {
                sleep(wait)
            }
            snap(name)
        }
    }

    // Capture the GeoPDF-import hero: import a US Topo GeoPDF (pre-copied
    // into the app's Documents so it shows in Files under On My iPhone >
    // TacMap) then overlay the re-centred NATO situation. Device location
    // is set to the PDF/situation centre so Centre on My Location frames it.
    func testCaptureGeoPdf() {
        sleep(2)
        // 1) import the GeoPDF. "PDF Map…" lives inside the Import / Export
        //    sub-page (navRow), so open that first.
        openMenu()
        _ = tapContaining("Import / Export")
        sleep(2)
        _ = tapContaining("PDF Map")
        sleep(3)

        // The document picker opens at its remembered location. The seeded GeoPDF
        // lives in the app's own Documents → surfaced under "On My iPhone/iPad ›
        // TacMap". Navigate there if the file isn't already on screen.
        func fileQuery() -> XCUIElement {
            // The georeferenced USGS SF North US Topo (FortIrwin has no georef and
            // lands at the camera fallback). Seeded into the app's Documents.
            app.descendants(matching: .any)
                .matching(NSPredicate(format: "label CONTAINS[c] %@", "USGS_SF_North")).firstMatch
        }
        func tapFirst(containing text: String, timeout: TimeInterval = 5) -> Bool {
            let e = app.descendants(matching: .any)
                .matching(NSPredicate(format: "label CONTAINS[c] %@", text)).firstMatch
            guard e.waitForExistence(timeout: timeout) else { return false }
            e.tap(); return true
        }
        if !fileQuery().waitForExistence(timeout: 4) {
            // iPhone: bottom tab "Browse" → Locations list (there can be more than
            // one "Browse" element in Files, so take the first hittable one). iPad:
            // the sidebar is already shown, so this is best-effort.
            let browse = app.buttons.matching(NSPredicate(format: "label ==[c] %@", "Browse")).firstMatch
            if browse.waitForExistence(timeout: 4), browse.isHittable { browse.tap(); sleep(1) }
            // "On My iPhone" / "On My iPad" → TacMap (the app's Documents container)
            _ = tapFirst(containing: "On My i"); sleep(1)
            let folder = app.descendants(matching: .any)
                .matching(NSPredicate(format: "label MATCHES[c] %@", "TacMap")).firstMatch
            if folder.waitForExistence(timeout: 5), folder.isHittable { folder.tap() }
            sleep(1)
        }
        let file = fileQuery()
        if file.waitForExistence(timeout: 8) { file.tap() }
        sleep(9)                       // import + georeference + persist + fly to PDF
        dismissSheet()
        // Centre on device location (= PDF centre). With an offline map loaded the
        // single button splits into "My Location" / "Map", so match either.
        // No sync join here - the slide's message is the georeferenced imported
        // sheet aligned to the MGRS grid, matching the Android PDF slide.
        _ = tapContaining("My Location")
        sleep(5)
        snap("pdf-hero")
    }
}
