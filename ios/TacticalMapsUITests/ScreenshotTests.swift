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
}
