import XCTest

/// Captures App Store marketing screenshots deterministically.
///
/// Runs the real app, grants location, drops a friendly unit + hostile
/// unit + Assembly Area task at the crosshair (panning between each so
/// they don't stack), then visits symbol builder, drawings panel and
/// About screen. Each snap is attached to the test result for extraction with
/// `xcrun xcresulttool export attachments`.
///
/// Location is set at device level by the wrapper script
/// (xcrun simctl location set) so the basemap shows Shoalwater Bay.
final class ScreenshotTests: XCTestCase {
    private var app: XCUIApplication!
    private let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
    private static let screenshotRoomEnvironmentKey = "TACMAP_SCREENSHOT_ROOM_CODE"

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        // Force online basemaps + lookups on for the shots regardless of state
        // persisted by an earlier UI-test run. Read by OpsecSettings.init.
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

    /// A connected marketing capture is opt-in. Normal UI regression runs omit
    /// this environment value and therefore never contact the Unit Sync relay.
    /// Capture orchestration must generate a fresh v3 room for every run and
    /// pass the same value to any peer/situation seeding process.
    private func runScopedScreenshotRoomCode() -> String? {
        guard let raw = ProcessInfo.processInfo.environment[
            Self.screenshotRoomEnvironmentKey
        ]?.trimmingCharacters(in: .whitespacesAndNewlines), !raw.isEmpty else {
            return nil
        }
        guard raw.hasPrefix("3:") else {
            XCTFail("\(Self.screenshotRoomEnvironmentKey) must be a fresh v3 room beginning with '3:'")
            return nil
        }
        return raw
    }

    @discardableResult
    private func joinScreenshotRoom(_ roomCode: String, waitSeconds: UInt32) -> Bool {
        let codeField = app.textFields.firstMatch
        guard codeField.waitForExistence(timeout: 6) else {
            XCTFail("Unit Sync join-code field is missing")
            return false
        }
        codeField.tap()
        codeField.typeText(roomCode)
        guard requireTapContaining("Join") else { return false }
        if app.buttons["Enable & Join"].waitForExistence(timeout: 2) {
            app.buttons["Enable & Join"].tap()
        }
        sleep(waitSeconds)
        return true
    }

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
        // Sim runners can write straight to the host, which saves digging the
        // PNG back out of the xcresult (and survives a run that wedges).
        if let dir = ProcessInfo.processInfo.environment["STORE_OUT"] {
            let url = URL(fileURLWithPath: dir).appendingPathComponent(name + ".png")
            try? shot.pngRepresentation.write(to: url)
        }
    }

    private func tap(_ label: String, timeout: TimeInterval = 10) -> Bool {
        let b = app.buttons[label]
        guard b.waitForExistence(timeout: timeout) else {
            XCTFail("Required screenshot control is missing: \(label)")
            return false
        }
        guard b.isHittable else {
            XCTFail("Required screenshot control is not hittable: \(label)")
            return false
        }
        b.tap()
        return true
    }

    @discardableResult
    private func requireTapContaining(_ text: String, timeout: TimeInterval = 10) -> Bool {
        let b = app.buttons.matching(
            NSPredicate(format: "label CONTAINS[c] %@", text)
        ).firstMatch
        guard b.waitForExistence(timeout: timeout) else {
            XCTFail("Required screenshot control containing '\(text)' is missing")
            return false
        }
        guard b.isHittable else {
            XCTFail("Required screenshot control containing '\(text)' is not hittable")
            return false
        }
        b.tap()
        return true
    }

    @discardableResult
    private func tapSymbolKind(_ name: String, timeout: TimeInterval = 6) -> Bool {
        tap("\(name) symbol kind", timeout: timeout)
    }

    /// The add row is below every saved symbol. A populated persisted test map
    /// therefore has to scroll the lazy List before XCTest can query the button.
    @discardableResult
    private func tapAddAtCrosshair() -> Bool {
        let button = app.buttons["Add at Crosshair"]
        var remainingScrolls = 24
        while !(button.exists && button.isHittable), remainingScrolls > 0 {
            app.swipeUp()
            remainingScrolls -= 1
        }
        guard button.exists, button.isHittable else {
            XCTFail("Required screenshot control is missing after scrolling the Symbology list: Add at Crosshair")
            return false
        }
        button.tap()
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
        guard tapAddAtCrosshair() else { return }
        sleep(1)
        configure()
        guard tap("Save") else { return }
        sleep(1)
        guard tap("Done") else { return } // dismiss the Symbology list back to the map
        sleep(1)
    }

    // MARK: - The capture run

    /// A symbol edit must register with the map's visible undo manager, and both
    /// directions must update immediately. This guards the screenshot workflows
    /// against accidentally exercising controls that only look interactive.
    func testUndoRedoAfterSymbolCreation() {
        addSymbol { }

        let undo = app.buttons["Undo"]
        XCTAssertTrue(undo.waitForExistence(timeout: 5), "Undo control did not appear after adding a symbol")
        XCTAssertTrue(undo.isEnabled, "Undo control is disabled after adding a symbol")
        undo.tap()

        let redo = app.buttons["Redo"]
        XCTAssertTrue(redo.waitForExistence(timeout: 5), "Redo control did not appear after undoing the symbol")
        XCTAssertTrue(redo.isEnabled, "Redo control is disabled after undoing the symbol")
        redo.tap()

        XCTAssertTrue(undo.waitForExistence(timeout: 5), "Undo control disappeared after redoing the symbol")
        XCTAssertTrue(undo.isEnabled, "Undo control is disabled after redoing the symbol")
        undo.tap() // Leave the persistent map in its original state.

        XCTAssertTrue(redo.waitForExistence(timeout: 5), "Redo control did not return after cleanup undo")
        XCTAssertTrue(redo.isEnabled, "Redo control is disabled after cleanup undo")
    }

    func testCaptureScreenshots() {
        // 1) Hero - live MGRS HUD over the basemap.
        snap("01-main")

        // 2) Friendly Infantry Platoon (defaults: friend / platoon / infantry).
        //    Grab the APP-6 builder shot while this sheet is open.
        openMenu()
        _ = tap("Symbology")
        _ = tapAddAtCrosshair()
        sleep(1)
        snap("02-symbol-builder")   // WaypointEditSheet: affiliation/echelon/function + live preview
        _ = tap("Save")
        sleep(1)
        _ = tap("Done")
        sleep(1)

        // 3) Hostile unit - change Affiliation to Hostile (red diamond).
        panMap(dx: 0.16, dy: -0.12)
        addSymbol {
            if requireTapContaining("Affiliation", timeout: 6) {
                sleep(1)
                // Menu-style picker: pick the Hostile row.
                _ = tap("Hostile", timeout: 6)
                sleep(1)
            }
        }

        // 4) Assembly Area task graphic.
        panMap(dx: -0.18, dy: 0.14)
        addSymbol {
            _ = tapSymbolKind("Tasks") // segmented control -> control measure (Assembly Area default)
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

    /// One run that captures the current marketing set. Required UI controls
    /// are asserted so a stale marketing path cannot produce false-green shots.
    func testCaptureMarketing() {
        // 01 - live HUD: MGRS + UTM + mils compass over the basemap.
        snap("m01-hud")

        // 02 - the consolidated command menu.
        openMenu()
        sleep(1)
        snap("m02-menu")
        _ = tap("Close")
        sleep(1)

        // 03 - Unit Sync. Regression runs capture the deterministic offline
        // disclosure state. Release capture orchestration may opt into a fresh,
        // run-scoped room through TACMAP_SCREENSHOT_ROOM_CODE.
        openMenu()
        _ = tapContaining("Unit Sync")
        sleep(2)
        if let roomCode = runScopedScreenshotRoomCode() {
            _ = joinScreenshotRoom(roomCode, waitSeconds: 18)
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
        _ = tapAddAtCrosshair()
        sleep(1)
        snap("m07-symbol-builder")
        _ = tap("Save")
        sleep(1)
        _ = tap("Done")
        sleep(1)

        // 08 - a second hostile unit + assembly-area, for a populated map.
        panMap(dx: 0.16, dy: -0.12)
        addSymbol {
            if requireTapContaining("Affiliation", timeout: 6) {
                sleep(1); _ = tap("Hostile", timeout: 6); sleep(1)
            }
        }
        panMap(dx: -0.18, dy: 0.14)
        addSymbol { _ = tapSymbolKind("Tasks"); sleep(1) }
        panMap(dx: 0.02, dy: -0.02)
        sleep(2)
        snap("m08-symbols")

        // 09 - GPX recording: the live REC indicator on the map.
        // Symbol creation leaves the most recent object selected. Close its
        // bottom editor before capturing so the slide actually demonstrates
        // recording rather than hiding the map behind unrelated controls.
        let closeSymbolEditor = app.buttons["Close symbol editor"]
        if closeSymbolEditor.exists && closeSymbolEditor.isHittable {
            closeSymbolEditor.tap()
            sleep(1)
        }
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

    // Hero shot: join the fresh, run-scoped room that the host script
    // (scripts/sync_push_situation.mjs) pre-populated with a NATO APP-6
    // company-attack overlay. Snapshot shows the shared picture and the
    // on-screen Unit Sync indicator goes Connected - symbology (the #1
    // feature) + live encrypted sync in one frame.
    func testCaptureHero() throws {
        guard let roomCode = runScopedScreenshotRoomCode() else {
            throw XCTSkip("Set TACMAP_SCREENSHOT_ROOM_CODE to a fresh v3 room for a connected hero capture")
        }
        openMenu()
        _ = tapContaining("Unit Sync")
        sleep(2)
        _ = joinScreenshotRoom(roomCode, waitSeconds: 20)
        dismissSheet()
        sleep(2)

        // The screenshot launch environment explicitly enables the Esri
        // Satellite source; turn on labels while remaining on that source.
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
    // Every prerequisite is asserted: a renamed or unreachable control must fail
    // the screenshot instead of silently saving the default military symbol.
    private func addSARMarker(symbol: String? = nil) {
        openMenu()
        guard tap("Symbology") else { return }
        guard tapAddAtCrosshair() else { return }
        sleep(1)
        guard tapSymbolKind("Markers") else { return }
        sleep(1)
        // The Set row shows its current value "Airsoft / Milsim"; open it + pick SAR.
        guard requireTapContaining("Airsoft", timeout: 4) else { return }
        sleep(1)
        guard requireTapContaining("Search & Rescue") else { return }
        sleep(1)

        // The Symbol row is a navigationLink picker. When a specific symbol is
        // requested, materialise it by scrolling and require that exact choice.
        if let symbol = symbol {
            let symRow = app.buttons.matching(NSPredicate(format: "label BEGINSWITH[c] %@", "Symbol")).firstMatch
            guard symRow.waitForExistence(timeout: 3), symRow.isHittable else {
                XCTFail("Required Search & Rescue Symbol picker is missing")
                return
            }
            symRow.tap()
            sleep(1)

            // Use the picker's exact catalogue label. A persisted marker on the
            // map has the same name followed by its coordinate, so a contains
            // query can resolve that obscured map annotation instead of the
            // visible picker row and produce a misleading non-hittable result.
            let pickerLabel: String
            switch symbol {
            case "Last Known Position":
                pickerLabel = "Last Known Position (LKP)"
            case "Initial Planning Point":
                pickerLabel = "Initial Planning Point (IPP)"
            default:
                pickerLabel = symbol
            }
            let option = app.buttons[pickerLabel]
            var remainingScrolls = 8
            while !(option.exists && option.isHittable), remainingScrolls > 0 {
                app.swipeUp()
                remainingScrolls -= 1
            }
            guard option.waitForExistence(timeout: 2), option.isHittable else {
                XCTFail("Required Search & Rescue symbol is missing: \(symbol)")
                return
            }
            option.tap()
            sleep(1)
        }
        guard tap("Save") else { return }
        sleep(1)
        guard tap("Done") else { return }
        sleep(1)
    }

    private func descendant(containing text: String) -> XCUIElement {
        app.descendants(matching: .any)
            .matching(NSPredicate(format: "label CONTAINS[c] %@", text)).firstMatch
    }

    /// Select the host-seeded fixture from Files and require the imported-map
    /// control to become reachable. Because TacMap deliberately disables iOS
    /// file sharing, the wrapper seeds USGS_SF_North.pdf at the root of the
    /// simulator's On My iPhone File Provider, not the app-private Documents.
    private func importSeededGeoPDF() {
        openMenu()
        guard requireTapContaining("Import / Export") else { return }
        sleep(2)
        guard requireTapContaining("PDF Map") else { return }
        sleep(3)

        let file = descendant(containing: "USGS_SF_North")
        if !file.waitForExistence(timeout: 4) {
            let browse = app.buttons.matching(
                NSPredicate(format: "label ==[c] %@", "Browse")
            ).firstMatch
            guard browse.waitForExistence(timeout: 4), browse.isHittable else {
                XCTFail("Files Browse control is missing while selecting the seeded GeoPDF")
                return
            }
            browse.tap()
            sleep(1)

            let location = descendant(containing: "On My i")
            guard location.waitForExistence(timeout: 5), location.isHittable else {
                XCTFail("Files is missing its On My iPhone/iPad location")
                return
            }
            location.tap()
            sleep(1)
        }

        guard file.waitForExistence(timeout: 8), file.isHittable else {
            XCTFail("Seeded fixture USGS_SF_North.pdf is missing from On My iPhone")
            return
        }
        file.tap()

        let importedMapButton = app.buttons["Map"]
        guard importedMapButton.waitForExistence(timeout: 45), importedMapButton.isHittable else {
            XCTFail("USGS_SF_North.pdf was selected but did not become the active imported map")
            return
        }
    }

    // Capture the GeoPDF-import hero: import a US Topo GeoPDF (pre-copied
    // into Files so it shows at the root of On My iPhone) then overlay the
    // re-centred NATO situation. Device location
    // is set to the PDF/situation centre so Centre on My Location frames it.
    func testCaptureGeoPdf() {
        sleep(2)
        importSeededGeoPDF()
        // Centre on device location (= PDF centre). With an offline map loaded the
        // single button splits into "My Location" / "Map", so match either.
        guard requireTapContaining("My Location") else { return }
        sleep(4)
        // Zoom out so more of the imported sheet is in frame (was street-level
        // before). Pinch keeps the PDF centred; don't recentre after or it snaps
        // the zoom back.
        for scale in (ProcessInfo.processInfo.environment["STORE_PDF_PINCH"]?
            .split(separator: ",").compactMap { Double($0) } ?? [0.5, 0.6]) {
            app.pinch(withScale: CGFloat(scale), velocity: scale > 1 ? 1.5 : -1.5); sleep(2)
        }
        // A rerun on the same sim already has the markers from last time.
        if ProcessInfo.processInfo.environment["STORE_PDF_NO_MARKERS"] == "1" {
            // iPad pinches drift, so hop back to the location (= the markers).
            if ProcessInfo.processInfo.environment["STORE_PDF_RECENTRE"] == "1" {
                _ = requireTapContaining("My Location")
            }
            sleep(3)
            snap("pdf-hero")
            return
        }
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
    // 2.2 store set. 8 slides, same order on iPhone, iPad and Android:
    // hero, line-of-sight, night-mode, unit-sync, sun-moon, pdf-hero (the
    // GeoPDF test above), symbol-builder, export. Run the testStore* ones in
    // name order on a fresh-ish sim: 01 imports the situation and the rest
    // reuse it since the app keeps its data between launches.
    //
    // Host setup before running:
    //   - xcrun simctl location <udid> set -33.700,150.305   (situation centre)
    //   - copy docs/store/store_situation.geojson into the sim's
    //     "On My iPhone" (FileProvider.LocalStorage group, File Provider Storage)
    // Tweaks can be passed without editing code via TEST_RUNNER_STORE_* env
    // (xcodebuild strips the TEST_RUNNER_ prefix for the runner).
    // ============================================================

    private func storeEnv(_ key: String) -> String? {
        ProcessInfo.processInfo.environment["STORE_" + key]
    }

    private func storeDoubles(_ key: String, _ fallback: [Double]) -> [Double] {
        guard let raw = storeEnv(key) else { return fallback }
        let vals = raw.split(separator: ",").compactMap { Double($0.trimmingCharacters(in: .whitespaces)) }
        return vals.isEmpty ? fallback : vals
    }

    // Same Files dance as the GeoPDF import, just the GeoJSON row. Import is
    // the first "GeoJSON…" row on the page (export has one too, further down).
    private func importSeededGeoJSON(_ fileName: String) {
        openMenu()
        guard requireTapContaining("Import / Export") else { return }
        sleep(2)
        let rows = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] %@", "GeoJSON"))
        guard rows.firstMatch.waitForExistence(timeout: 6) else {
            XCTFail("GeoJSON import row is missing")
            return
        }
        rows.element(boundBy: 0).tap()
        sleep(3)

        let file = descendant(containing: fileName)
        if !file.waitForExistence(timeout: 4) {
            let browse = app.buttons.matching(NSPredicate(format: "label ==[c] %@", "Browse")).firstMatch
            if browse.waitForExistence(timeout: 4), browse.isHittable { browse.tap(); sleep(1) }
            let location = descendant(containing: "On My i")
            guard location.waitForExistence(timeout: 5), location.isHittable else {
                XCTFail("Files is missing its On My iPhone/iPad location")
                return
            }
            location.tap()
            sleep(1)
        }
        guard file.waitForExistence(timeout: 8), file.isHittable else {
            XCTFail("Seeded \(fileName) is missing from On My iPhone")
            return
        }
        file.tap()
        sleep(3)
        // Some builds confirm the import first, some just do it.
        for label in ["Import", "Add", "OK"] {
            let b = app.buttons[label]
            if b.exists && b.isHittable { b.tap(); sleep(2); break }
        }
        dismissSheet()
    }

    private func centreAndZoom() {
        // After the GeoPDF slide the imported sheet is still the active map,
        // so put a named basemap back first when asked.
        if let basemap = storeEnv("BASEMAP") {
            openMenu()
            if tap("Layers and Labels") {
                sleep(1)
                let row = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] %@", basemap)).firstMatch
                var tries = 0
                while !(row.exists && row.isHittable) && tries < 6 { app.swipeUp(); sleep(1); tries += 1 }
                if row.exists { row.tap(); sleep(1) }
                dismissSheet()
                sleep(4)
            }
        }
        _ = tapContaining("My Location")
        sleep(3)
        // First fix can land after the tap on a fresh sim, so have another go
        // while the recentre pill is still up.
        let pill = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] %@", "Centre on My Location")).firstMatch
        if pill.exists && pill.isHittable { pill.tap(); sleep(3) }
        for scale in storeDoubles("PINCH", [0.6]) {
            app.pinch(withScale: CGFloat(scale), velocity: scale > 1 ? 1.5 : -1.5)
            sleep(2)
        }
        // iPad pinches drift the camera, so snap back onto the location after.
        if storeEnv("RECENTRE_AFTER") == "1", pill.exists, pill.isHittable {
            pill.tap()
            sleep(3)
            // Recentre resets the zoom, so a slow zoom-out afterwards (slow so
            // it doesn't drift).
            let v = CGFloat(storeDoubles("PINCH2_V", [-0.3])[0])
            for scale in storeDoubles("PINCH2", []) {
                app.pinch(withScale: CGFloat(scale), velocity: v)
                sleep(2)
            }
        }
        // Measured drift correction, dx/dy in screen fractions.
        let pan = storeDoubles("PAN_AFTER", [])
        if pan.count == 2 { panMap(dx: CGFloat(pan[0]), dy: CGFloat(pan[1])); sleep(2) }
    }

    private func labelsOn() {
        openMenu()
        guard tap("Layers and Labels") else { return }
        sleep(2)
        for label in storeEnv("LABELS")?.split(separator: "|").map(String.init)
                ?? ["Unit Labels", "Task Labels", "Drawing Labels"] {
            let sw = app.switches[label]
            guard sw.waitForExistence(timeout: 3) else { continue }
            if (sw.value as? String) == "0" {
                // Newer iOS lands a centre tap on the row label, not the switch.
                sw.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).tap()
                sleep(1)
            }
        }
        dismissSheet()
    }

    /// Long-press a normalised map point and pick a row off the point menu.
    /// The menu wants a still 1 s hold, so press a bit longer than that.
    @discardableResult
    private func pointMenu(at p: CGVector, choose row: String) -> Bool {
        app.windows.firstMatch.coordinate(withNormalizedOffset: p).press(forDuration: 1.6)
        let b = app.buttons[row]
        guard b.waitForExistence(timeout: 5) else {
            // Soft fail: a hard XCTFail here leaves xcodebuild hanging on this
            // Xcode and the whole result bundle is lost.
            NSLog("[store] point menu row missing: \(row)")
            snap("debug-point-menu")
            return false
        }
        b.tap()
        sleep(1)
        return true
    }

    private func fillRingSheet() {
        if let spacing = storeEnv("RING_SPACING") {
            let field = app.textFields["rangeRings.spacing"]
            if field.waitForExistence(timeout: 3) {
                field.tap()
                let current = (field.value as? String) ?? ""
                field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: current.count + 2) + spacing)
            }
        }
        // Default is 3 rings; RING_COUNT nudges the stepper up or down.
        if let want = storeEnv("RING_COUNT").flatMap(Int.init) {
            let stepper = app.steppers.firstMatch
            if stepper.waitForExistence(timeout: 2) {
                let delta = want - 3
                let button = stepper.buttons.element(boundBy: delta > 0 ? 1 : 0)
                for _ in 0..<abs(delta) { button.tap() }
            }
        }
        let add = app.buttons["Add Rings"]
        if add.waitForExistence(timeout: 3) { add.tap(); sleep(2) }
        else { NSLog("[store] Add Rings missing"); snap("debug-ring-sheet") }
    }

    private func closeSelection() {
        for label in ["Close symbol editor", "Close drawing editor"] {
            let b = app.buttons[label]
            if b.exists && b.isHittable { b.tap(); sleep(1) }
        }
    }

    func testStore01Hero() {
        if storeEnv("SKIP_IMPORT") != "1" {
            importSeededGeoJSON("store_situation")
        }
        centreAndZoom()
        labelsOn()
        // Framing pass: no ring target means just show me the map so I can
        // pick a spot. RING_SYMBOL_AT taps a symbol and adds rings from its
        // editor (so they follow it), RING_AT long-presses empty ground.
        if let at = storeEnv("RING_SYMBOL_AT") {
            let p = at.split(separator: ",").compactMap { Double($0) }
            app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: p[0], dy: p[1])).tap()
            sleep(2)
            let rings = app.buttons["symbol.rangeRings"]
            if rings.waitForExistence(timeout: 5) { rings.tap(); sleep(1); fillRingSheet() }
            else { NSLog("[store] no rings button after tapping symbol at \(at)"); snap("debug-ring-symbol") }
        } else if storeEnv("RING_AT") != nil {
            let ring = storeDoubles("RING_AT", [0.5, 0.5])
            if pointMenu(at: CGVector(dx: ring[0], dy: ring[1]), choose: "Range Rings Here") { fillRingSheet() }
        } else {
            sleep(6)
            snap("hero-pre")
            return
        }
        closeSelection()
        sleep(6)
        snap("hero")
    }

    func testStore02LineOfSight() {
        centreAndZoom()
        if storeEnv("LOS_PRE") == "1" { sleep(6); snap("los-pre"); return }
        let a = storeDoubles("LOS_FROM", [0.28, 0.62])
        let b = storeDoubles("LOS_TO", [0.74, 0.40])
        guard pointMenu(at: CGVector(dx: a[0], dy: a[1]), choose: "Measure From Here") else { return }
        app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: b[0], dy: b[1])).tap()
        sleep(1)
        let profile = app.buttons["measure.profile"]
        guard profile.waitForExistence(timeout: 5) else { XCTFail("No profile button"); return }
        profile.tap()
        let chart = app.otherElements["profile.chart"]
        XCTAssertTrue(chart.waitForExistence(timeout: 20), "No profile chart")
        sleep(3)
        if let h = storeEnv("OBSERVER_TAPS"), let n = Int(h) {
            let stepper = app.steppers["profile.observerHeight"]
            if stepper.waitForExistence(timeout: 3) {
                for _ in 0..<n { stepper.buttons.element(boundBy: 1).tap() }
            }
            sleep(1)
        }
        if storeEnv("TOUCH_CHART") != "0" {
            chart.coordinate(withNormalizedOffset: CGVector(dx: 0.2, dy: 0.5))
                .press(forDuration: 0.1, thenDragTo: chart.coordinate(withNormalizedOffset: CGVector(dx: 0.55, dy: 0.5)))
            sleep(1)
        }
        snap("line-of-sight")
    }

    func testStore03NightMode() {
        centreAndZoom()
        closeSelection()
        let moon = app.buttons["map.nightMode"]
        guard moon.waitForExistence(timeout: 6), moon.isHittable else {
            XCTFail("Night mode button is missing from the map")
            return
        }
        moon.tap()
        sleep(3)
        snap("night-mode")
        // Put it back so the next launch isn't all red.
        moon.tap()
        sleep(1)
    }

    /// Chat needs a second chat-ready unit or Send stays disabled, so this is
    /// run on two sims: one generates the room (or it's joined by hand), the
    /// other gets STORE_JOIN_CODE and they talk. STORE_CHAT is "|" separated,
    /// STORE_CHAT_GAP is the pause after each send so the other side can reply.
    func testStore04UnitSync() {
        if storeEnv("CHAT_ONLY") != "1" { joinStoreRoom() }
        openMenu()
        guard requireTapContaining("TacMap Chat") else { return }
        sleep(2)
        let gap = UInt32(storeEnv("CHAT_GAP") ?? "3") ?? 3
        let messages = (storeEnv("CHAT") ?? "").split(separator: "|").map(String.init)
        let placeholder = NSPredicate(format: "placeholderValue ==[c] %@", "Message")
        for text in messages {
            var field = app.textFields.matching(placeholder).firstMatch
            if !field.waitForExistence(timeout: 3) { field = app.textViews.matching(placeholder).firstMatch }
            guard field.waitForExistence(timeout: 20) else { NSLog("[store] no chat field"); snap("debug-chat"); return }
            field.tap()
            field.typeText(text)
            let review = app.buttons["Review room send"]
            // Stays disabled until the other sim is chat-ready.
            let enabled = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true"), object: review)
            _ = XCTWaiter().wait(for: [enabled], timeout: TimeInterval(storeEnv("READY_WAIT") ?? "180") ?? 180)
            review.tap()
            let send = app.buttons.matching(NSPredicate(format: "label BEGINSWITH[c] %@", "Send to")).firstMatch
            if send.waitForExistence(timeout: 5) { send.tap() } else { snap("debug-send") }
            sleep(gap)
        }
        if app.keyboards.count > 0 { app.swipeDown(); sleep(1) }
        sleep(UInt32(storeEnv("CHAT_HOLD") ?? "2") ?? 2)
        snap("unit-sync")
    }

    private func joinStoreRoom() {
        openMenu()
        _ = tapContaining("Unit Sync")
        sleep(2)
        let nameField = app.textFields.matching(
            NSPredicate(format: "placeholderValue BEGINSWITH[c] %@", "Room name")
        ).firstMatch
        if nameField.waitForExistence(timeout: 4), nameField.isHittable {
            nameField.tap()
            nameField.typeText(storeEnv("ROOM_NAME") ?? "WOLFPACK")
        }
        if let code = storeEnv("JOIN_CODE") {
            let codeField = app.textFields.matching(
                NSPredicate(format: "placeholderValue ==[c] %@", "Unit join code")
            ).firstMatch
            guard codeField.waitForExistence(timeout: 4) else { NSLog("[store] no join code field"); return }
            codeField.tap()
            codeField.typeText(code)
        } else {
            guard requireTapContaining("Generate strong code") else { return }
            sleep(1)
        }
        // The keyboard can sit on top of the join row.
        if app.keyboards.count > 0 { app.swipeDown(); sleep(1) }
        guard requireTapContaining("Join / create room") else { return }
        if app.buttons["Enable & Join"].waitForExistence(timeout: 3) { app.buttons["Enable & Join"].tap() }
        sleep(8)
        if let callsign = storeEnv("CALLSIGN") {
            let cs = app.textFields.matching(NSPredicate(format: "placeholderValue ==[c] %@", "Callsign")).firstMatch
            if cs.waitForExistence(timeout: 4) {
                cs.tap()
                // Earlier runs leave the old callsign in there, clear it first.
                let current = (cs.value as? String) ?? ""
                let stale = current == cs.placeholderValue ? 0 : current.count
                cs.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: stale) + callsign + "\n")
                sleep(1)
            }
        }
        dismissSheet()
    }

    /// Shared-picture version of the Unit Sync slide: already connected (room
    /// persists between launches), a second sim sharing its location, map at
    /// the hero framing with the chat shortcut showing.
    func testStore04bSyncMap() {
        if storeEnv("JOIN_CODE") != nil { joinStoreRoom() }
        if storeEnv("SYNC_SHEET") == "1" {
            // Members view: scroll the sheet past the join code so it's not
            // readable, leaving status, identity and the relay-reported units.
            sleep(UInt32(storeEnv("SYNC_WAIT") ?? "10") ?? 10)
            openMenu()
            _ = tapContaining("Unit Sync")
            sleep(2)
            let from = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.8))
            let to = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: CGFloat(storeDoubles("SYNC_SCROLL", [0.45])[0])))
            from.press(forDuration: 0.1, thenDragTo: to)
            sleep(2)
            snap("unit-sync")
            return
        }
        centreAndZoom()
        closeSelection()
        sleep(UInt32(storeEnv("SYNC_WAIT") ?? "10") ?? 10)
        snap("unit-sync")
    }

    /// Keeps this sim online in the room (chat sheet open) so the other sim
    /// can see it. Nothing is captured.
    func testStoreStayOnline() {
        if storeEnv("JOIN_CODE") != nil { joinStoreRoom() }
        sleep(UInt32(storeEnv("STAY") ?? "300") ?? 300)
    }

    func testStore05SunMoon() {
        centreAndZoom()
        closeSelection()
        let at = storeDoubles("SUNMOON_AT", [0.3, 0.85])
        guard pointMenu(at: CGVector(dx: at[0], dy: at[1]), choose: "Sun and Moon Here") else { return }
        sleep(2)
        // iPad opens this at the medium detent and the moon rows fall off the
        // bottom, so pull the grabber up to full height.
        if let grab = storeEnv("SUNMOON_GRAB").map({ $0.split(separator: ",").compactMap { Double($0) } }), grab.count == 2 {
            let from = app.coordinate(withNormalizedOffset: CGVector(dx: grab[0], dy: grab[1]))
            from.press(forDuration: 0.2, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: grab[0], dy: 0.08)))
            sleep(2)
        }
        snap("sun-moon")
        dismissSheet()
    }

    func testStore07SymbolBuilder() {
        openMenu()
        guard tap("Symbology") else { return }
        guard tapAddAtCrosshair() else { return }
        sleep(2)
        snap("symbol-builder")
        _ = tap("Cancel")
        sleep(1)
        dismissSheet()
    }

    func testStore08Export() {
        openMenu()
        guard requireTapContaining("Import / Export") else { return }
        sleep(2)
        snap("export")
        dismissSheet()
    }

    /// 2.2 app preview driver. Record the sim with `simctl io recordVideo`
    /// while this runs, then cut the beats out with ffmpeg. Beats, in order:
    /// the picture, night mode on/off, line of sight, sun & moon, export.
    /// Paced with sleeps so each beat has a clean second or two to cut.
    func testStorePreview() {
        centreAndZoom()
        closeSelection()
        // Just hold on the picture, a pan from the centre grabs whatever
        // drawing runs under the crosshair.
        sleep(5)

        let moon = app.buttons["map.nightMode"]
        if moon.waitForExistence(timeout: 5) {
            moon.tap(); sleep(3)
            moon.tap(); sleep(2)
        }

        for scale in storeDoubles("PREVIEW_PINCH", []) {
            app.pinch(withScale: CGFloat(scale), velocity: scale > 1 ? 1.5 : -1.5)
            sleep(2)
        }
        let a = storeDoubles("LOS_FROM", [0.28, 0.62])
        let b = storeDoubles("LOS_TO", [0.74, 0.40])
        if pointMenu(at: CGVector(dx: a[0], dy: a[1]), choose: "Measure From Here") {
            sleep(1)
            app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: b[0], dy: b[1])).tap()
            sleep(2)
            let profile = app.buttons["measure.profile"]
            if profile.waitForExistence(timeout: 5) {
                profile.tap()
                let chart = app.otherElements["profile.chart"]
                if chart.waitForExistence(timeout: 20) {
                    sleep(3)
                    chart.coordinate(withNormalizedOffset: CGVector(dx: 0.1, dy: 0.5))
                        .press(forDuration: 0.3, thenDragTo: chart.coordinate(withNormalizedOffset: CGVector(dx: 0.8, dy: 0.5)),
                               withVelocity: .slow, thenHoldForDuration: 1.0)
                    sleep(1)
                    let stepper = app.steppers["profile.observerHeight"]
                    if stepper.waitForExistence(timeout: 3) {
                        for _ in 0..<9 { stepper.buttons.element(boundBy: 1).tap() }
                    }
                    sleep(3)
                }
                dismissSheet()
            }
            // Measure mode keeps its own Done on the bar.
            let done = app.buttons.matching(NSPredicate(format: "label ==[c] %@", "Done")).firstMatch
            if done.exists && done.isHittable { done.tap(); sleep(1) }
        }

        let at = storeDoubles("SUNMOON_AT", [0.3, 0.85])
        if pointMenu(at: CGVector(dx: at[0], dy: at[1]), choose: "Sun and Moon Here") {
            sleep(4)
            dismissSheet()
        }

        openMenu()
        if requireTapContaining("Import / Export") { sleep(4) }
        dismissSheet()
        sleep(2)
    }

    // ============================================================
    // App preview drivers - screen-recorded via `simctl io recordVideo`
    // while these run, then trimmed/encoded to App Store Connect specs.
    // Paced deliberately (sleeps) so the ~20-25s of footage is watchable.
    // ============================================================

    // Preview 1 - build the tactical picture, then share it live over Unit Sync.
    func testPreview1TacticalSync() throws {
        guard let roomCode = runScopedScreenshotRoomCode() else {
            throw XCTSkip("Set TACMAP_SCREENSHOT_ROOM_CODE to a fresh v3 room for the Sync preview")
        }
        sleep(1)
        // Sync-first: join the room and a full company situation streams in over
        // the E2E-encrypted channel. That IS the story - no slow manual symbol
        // placement, just join and the shared picture appears, then linger on it.
        openMenu()
        _ = tapContaining("Unit Sync")
        sleep(2)
        _ = joinScreenshotRoom(roomCode, waitSeconds: 13)
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
        importSeededGeoPDF()
        guard requireTapContaining("My Location") else { return }
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
