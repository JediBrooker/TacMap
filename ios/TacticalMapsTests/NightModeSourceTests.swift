import XCTest
@testable import TacticalMaps

/// Sheets are separate hosting controllers, so each needs night mode's
/// luminance conversion. Every sheet must go through `nightSheet`; a plain
/// `.sheet` would show blue and green content as black at night.
final class NightModeSourceTests: XCTestCase {
    func testSheetsGoThroughNightSheet() throws {
        let tests = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let sources = tests.deletingLastPathComponent().appendingPathComponent("TacticalMaps")
        let files = try XCTUnwrap(FileManager.default.enumerator(at: sources, includingPropertiesForKeys: nil))
            .compactMap { $0 as? URL }
            .filter { $0.pathExtension == "swift" && $0.lastPathComponent != "NightModeContent.swift" }
        XCTAssertFalse(files.isEmpty, "No app sources found at \(sources.path)")

        var offenders: [String] = []
        for file in files {
            let lines = try String(contentsOf: file, encoding: .utf8).components(separatedBy: "\n")
            for (index, line) in lines.enumerated() {
                let code = line.components(separatedBy: "//").first ?? ""
                for presenter in [".sheet(", ".fullScreenCover(", ".popover("] where code.contains(presenter) {
                    offenders.append("\(file.lastPathComponent):\(index + 1) uses \(presenter)")
                }
            }
        }
        XCTAssertEqual(offenders, [], "Use nightSheet so sheets turn red in night mode")
    }

    func testBrightnessIsClampedToTheSupportedRange() {
        XCTAssertEqual(OpsecSettings.clampedNightBrightness(5), OpsecSettings.nightModeBrightnessRange.upperBound)
        XCTAssertEqual(OpsecSettings.clampedNightBrightness(0), OpsecSettings.nightModeBrightnessRange.lowerBound)
        XCTAssertEqual(OpsecSettings.clampedNightBrightness(.nan), OpsecSettings.defaultNightModeBrightness)
    }
}
