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
        // Custom raster renderer (no MapKit). Centre on the device location once
        // and zoom out a couple steps so all three basemaps frame the SAME wide
        // regional view. Fewer tiles at that zoom means the frame fills reliably,
        // and the relief reads well on satellite, topo and terrain alike. The old
        // version left the camera at a default world view and panned during the
        // terrain step, which is what left big black gaps in the snaps.
        _ = tapContaining("My Location")
        sleep(3)
        // Zoom OUT to a regional view. OTM rate-limits hard, so the fewer tiles
        // it has to serve, the more reliably terrain fills - a wide frame needs a
        // handful of tiles instead of a screenful. The Blue Mountains relief also
        // reads better wide than at street level.
        app.pinch(withScale: 0.38, velocity: -2.0); sleep(2)
        app.pinch(withScale: 0.42, velocity: -2.0); sleep(2)
        app.pinch(withScale: 0.5,  velocity: -1.5); sleep(2)

        // Three visually distinct basemaps for the fan, all served by Esri's CDN
        // so they fill reliably. (OpenTopoMap was dropped - its own tile servers
        // rate-limit the simulator to a black map; Street/OSM via Esri renders.)
        let maps: [(String, String, UInt32)] = [
            ("Satellite (Esri)", "bm-satellite", 16),
            ("Topographic (Esri)", "bm-esri", 30),
            ("Street (OpenStreetMap)", "bm-street", 30)
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
            // All three come from Esri's CDN now, so they fill fast - just hold
            // dead still while the tiles stream in (no panning at snap time).
            sleep(wait)
            snap(name)
        }
    }

    // Place a Search & Rescue marker at the crosshair. Menu > Symbology > Add
    // at Crosshair > Markers segment > Set: Search & Rescue > [Symbol] > Save.
    // The Type segment defaults to Military and the Set to Airsoft, so both get
    // switched every call. `symbol` is best-effort (Set snaps to Point Last Seen).
    // Only taps a matching button if it actually exists AND is hittable, so a
    // covered/off-screen control degrades gracefully instead of failing the run.
    @discardableResult
    private func softTap(containing text: String, timeout: TimeInterval = 3) -> Bool {
        let b = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] %@", text)).firstMatch
        guard b.waitForExistence(timeout: timeout), b.isHittable else { return false }
        b.tap(); return true
    }

    private func addSARMarker(symbol: String? = nil) {
        openMenu()
        guard tap("Symbology") else { return }
        guard tap("Add at Crosshair") else { return }
        sleep(1)
        _ = tap("Markers")                       // Type segment
        sleep(1)
        // The Set row shows its current value "Airsoft / Milsim"; open it + pick SAR.
        if softTap(containing: "Airsoft", timeout: 4) {
            sleep(1); _ = softTap(containing: "Search & Rescue"); sleep(1)
        }
        // Optional specific symbol (Set defaults to Point Last Seen). The Symbol
        // row is a navigationLink picker - tap it (its label BEGINS WITH the
        // title "Symbol", which avoids matching the name field that also shows
        // the symbol name), pick from the pushed list, else back out. All guarded
        // so a fiddly picker degrades to the default instead of failing/sticking.
        if let symbol = symbol {
            let symRow = app.buttons.matching(NSPredicate(format: "label BEGINSWITH[c] %@", "Symbol")).firstMatch
            if symRow.waitForExistence(timeout: 3), symRow.isHittable {
                symRow.tap(); sleep(1)
                if !softTap(containing: symbol, timeout: 4) {
                    // Not found/hittable in the list - back out so we don't stick.
                    let back = app.navigationBars.buttons.firstMatch
                    if back.exists, back.isHittable { back.tap() }
                }
                sleep(1)
            }
        }
        _ = tap("Save"); sleep(1)
        _ = tap("Done"); sleep(1)
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
        _ = tapContaining("My Location")
        sleep(4)
        // Zoom out so more of the imported sheet is in frame (was street-level
        // before). Pinch keeps the PDF centred; don't recentre after or it snaps
        // the zoom back.
        app.pinch(withScale: 0.5, velocity: -1.5); sleep(2)
        app.pinch(withScale: 0.6, velocity: -1.2); sleep(2)
        // Drop a small search-and-rescue picture on the imported sheet: an ICP,
        // point last seen, a helispot and a casualty - panning between each so
        // they don't stack. Shows marking up a brought-your-own map for the SAR
        // crowd, aligned to the live MGRS grid.
        addSARMarker(symbol: "Last Known Position")
        panMap(dx: 0.15, dy: -0.11); addSARMarker()                              // Point Last Seen (default)
        panMap(dx: -0.22, dy: 0.05); addSARMarker(symbol: "Initial Planning Point")
        panMap(dx: 0.12, dy: 0.16);  addSARMarker(symbol: "Search Segment")
        panMap(dx: -0.03, dy: -0.05); sleep(2)
        snap("pdf-hero")
    }

    // Capture the measure tool for the range/area/bearing slide. Menu > Measure,
    // then single-tap the map to drop vertices - the toolbar shows running
    // distance, last-leg bearing in NATO mils, and enclosed area (3+ points).
    func testCaptureMeasure() {
        sleep(2)
        _ = tapContaining("My Location")
        sleep(3)
        app.pinch(withScale: 0.55, velocity: -1.2); sleep(2)   // wide enough for real legs
        openMenu()
        _ = tap("Measure")
        sleep(1)
        // Single taps add a vertex at the tapped point. Four points give a
        // distance + last-leg bearing + enclosed area. Kept clear of the top
        // HUD and the bottom measure toolbar.
        let pts: [(CGFloat, CGFloat)] = [(0.34, 0.36), (0.58, 0.43), (0.67, 0.60), (0.42, 0.66)]
        for (dx, dy) in pts {
            app.coordinate(withNormalizedOffset: CGVector(dx: dx, dy: dy)).tap()
            sleep(1)
        }
        sleep(2)
        snap("measure")
    }

    // ============================================================
    // App preview drivers - screen-recorded via `simctl io recordVideo`
    // while these run, then trimmed/encoded to App Store Connect specs.
    // Paced deliberately (sleeps) so the ~20-25s of footage is watchable.
    // ============================================================

    // Preview 1 - build the tactical picture, then share it live over Unit Sync.
    func testPreview1TacticalSync() {
        sleep(1)
        // Sync-first: join the room and a full company situation streams in over
        // the E2E-encrypted channel. That IS the story - no slow manual symbol
        // placement, just join and the shared picture appears, then linger on it.
        openMenu()
        _ = tapContaining("Unit Sync")
        sleep(2)
        let f = app.textFields.firstMatch
        if f.waitForExistence(timeout: 6) {
            f.tap(); sleep(1)
            f.typeText("WOLFPACKSHOOT26")
            sleep(1)
            _ = tapContaining("Join")
            sleep(13)                                                   // connect + snapshot + render
        }
        dismissSheet()
        sleep(2)
        // Labels on so the shared units/tasks read, then explore the picture.
        openMenu()
        _ = tap("Layers and Labels")
        sleep(2)
        for l in ["Unit Labels", "Task Labels"] {
            let s = app.switches[l]
            if s.waitForExistence(timeout: 2), (s.value as? String) == "0" { s.tap(); sleep(1) }
        }
        dismissSheet()
        sleep(3)
        panMap(dx: 0.08, dy: 0.05); sleep(2)
        panMap(dx: -0.10, dy: -0.03); sleep(3)
    }

    // Preview 2 - bring your own map: import a GeoPDF and watch it georeference.
    // Name contains "GeoPdf" so setUp uses the offline basemap (the imported sheet).
    func testPreview2GeoPdf() {
        sleep(2)
        openMenu()
        _ = tapContaining("Import / Export")
        sleep(2)
        _ = tapContaining("PDF Map")
        sleep(3)
        func find(_ text: String) -> XCUIElement {
            app.descendants(matching: .any)
                .matching(NSPredicate(format: "label CONTAINS[c] %@", text)).firstMatch
        }
        if !find("USGS_SF_North").waitForExistence(timeout: 4) {
            let browse = app.buttons.matching(NSPredicate(format: "label ==[c] %@", "Browse")).firstMatch
            if browse.waitForExistence(timeout: 4), browse.isHittable { browse.tap(); sleep(1) }
            let loc = find("On My i"); if loc.waitForExistence(timeout: 5) { loc.tap(); sleep(1) }
            let folder = app.descendants(matching: .any)
                .matching(NSPredicate(format: "label MATCHES[c] %@", "TacMap")).firstMatch
            if folder.waitForExistence(timeout: 5), folder.isHittable { folder.tap(); sleep(1) }
        }
        let file = find("USGS_SF_North")
        if file.waitForExistence(timeout: 8) { file.tap() }
        sleep(9)                                                        // import + georeference + fly to sheet
        dismissSheet()
        _ = tapContaining("My Location")
        sleep(3)
        // Slowly explore the georeferenced sheet, aligned to the MGRS grid.
        app.pinch(withScale: 0.55, velocity: -1.2); sleep(2)
        panMap(dx: 0.10, dy: 0.07); sleep(2)
        panMap(dx: -0.16, dy: -0.05); sleep(2)
        panMap(dx: 0.06, dy: -0.02); sleep(3)
    }

    // Preview 3 - field tools: measure a leg, then drop Search & Rescue markers.
    func testPreview3Navigation() {
        sleep(1)
        _ = tapContaining("My Location"); sleep(1)
        app.pinch(withScale: 0.6, velocity: -1.2); sleep(1)
        // Measure - the fast, visual beat: tap a path and the readout shows live
        // distance, last-leg bearing in NATO mils, and enclosed area.
        openMenu()
        _ = tap("Measure"); sleep(1)
        let pts: [(CGFloat, CGFloat)] = [(0.35, 0.36), (0.58, 0.44), (0.63, 0.62), (0.40, 0.66)]
        for (dx, dy) in pts { app.coordinate(withNormalizedOffset: CGVector(dx: dx, dy: dy)).tap(); sleep(1) }
        sleep(4)                                                       // hold on the readout
        _ = tapContaining("Done"); sleep(2)
        // Then a Search & Rescue marker to close the "field tools" story.
        addSARMarker(symbol: "Last Known Position")
        sleep(3)
    }
}
