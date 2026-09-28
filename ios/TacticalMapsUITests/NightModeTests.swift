import XCTest

/// Night mode must turn everything the app draws red: the map, the menu and a
/// presented sheet. Screenshots are sampled on a grid; no sampled pixel may
/// carry noticeable green or blue.
final class NightModeTests: XCTestCase {

    func testNightModeTurnsMapMenuAndSheetsRed() {
        let app = XCUIApplication()
        app.launchEnvironment["TACMAP_UITEST_OFFLINE_BASEMAP"] = "1"
        app.launchEnvironment["TACMAP_UITEST_NIGHT_MODE"] = "1"
        app.launch()

        dismissLocationPrompt()
        XCTAssertTrue(app.buttons["Menu"].waitForExistence(timeout: 15))
        sleep(2) // let the map and overlay settle
        assertOnlyRed(XCUIScreen.main.screenshot(), "map")

        app.buttons["Menu"].tap()
        let settings = app.buttons.matching(
            NSPredicate(format: "label CONTAINS %@", "Privacy & OPSEC")
        ).firstMatch
        XCTAssertTrue(settings.waitForExistence(timeout: 5))
        assertOnlyRed(XCUIScreen.main.screenshot(), "menu")

        settings.tap()
        XCTAssertTrue(app.switches["settings.nightMode"].waitForExistence(timeout: 5)
                      || app.navigationBars.firstMatch.waitForExistence(timeout: 5))
        sleep(1)
        assertOnlyRed(XCUIScreen.main.screenshot(), "settings sheet")
    }

    /// The system location prompt is drawn outside the app, so night mode
    /// cannot (and need not) tint it; answer it before sampling the screen.
    private func dismissLocationPrompt() {
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        for label in ["Don’t Allow", "Don't Allow", "Allow While Using App"] {
            let button = springboard.buttons[label]
            if button.waitForExistence(timeout: label == "Don’t Allow" ? 8 : 1) {
                button.tap()
                return
            }
        }
    }

    private func assertOnlyRed(_ screenshot: XCUIScreenshot, _ context: String,
                               file: StaticString = #filePath, line: UInt = #line) {
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = "night-\(context)"
        attachment.lifetime = .keepAlways
        add(attachment)

        guard let image = screenshot.image.cgImage else {
            XCTFail("No screenshot image for \(context)", file: file, line: line)
            return
        }
        let width = image.width
        let height = image.height
        var pixels = [UInt8](repeating: 0, count: width * height * 4)
        let drawn = pixels.withUnsafeMutableBytes { buffer -> Bool in
            guard let context = CGContext(
                data: buffer.baseAddress,
                width: width,
                height: height,
                bitsPerComponent: 8,
                bytesPerRow: width * 4,
                space: CGColorSpace(name: CGColorSpace.sRGB)!,
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
            ) else { return false }
            context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
            return true
        }
        XCTAssertTrue(drawn, "Could not read \(context) pixels", file: file, line: line)

        var sampled = 0
        var coloured = 0
        var lit = 0
        var redLevels = Set<Int>()
        // Skip the bottom 4% where the system home indicator is drawn.
        let usableHeight = Int(Double(height) * 0.96)
        for y in stride(from: 0, to: usableHeight, by: max(1, usableHeight / 80)) {
            for x in stride(from: 0, to: width, by: max(1, width / 40)) {
                let index = (y * width + x) * 4
                let red = Int(pixels[index])
                let green = Int(pixels[index + 1])
                let blue = Int(pixels[index + 2])
                sampled += 1
                if max(green, blue) > 24 { coloured += 1 }
                if red > 24 { lit += 1 }
                redLevels.insert(red / 8)
            }
        }
        XCTAssertEqual(coloured, 0, "\(coloured) of \(sampled) sampled \(context) pixels are not red",
                       file: file, line: line)
        XCTAssertGreaterThan(lit, sampled / 200, "The \(context) screenshot is almost black",
                             file: file, line: line)
        // A flat single colour means the overlay hid the app instead of tinting it.
        XCTAssertGreaterThan(redLevels.count, 4, "The \(context) screenshot is one flat colour",
                             file: file, line: line)
    }
}
