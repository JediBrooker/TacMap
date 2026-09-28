import XCTest
@testable import TacticalMaps

/// Pins offline sun/moon times to testdata/sun_moon_times.json, the same
/// fixture the Android suite reads.
final class SunMoonCalculatorTests: XCTestCase {
    private var fixture: [String: Any]!

    override func setUpWithError() throws {
        try super.setUpWithError()
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        for _ in 0..<8 {
            let candidate = dir.appendingPathComponent("testdata/sun_moon_times.json")
            if FileManager.default.fileExists(atPath: candidate.path) {
                fixture = try XCTUnwrap(
                    JSONSerialization.jsonObject(with: Data(contentsOf: candidate)) as? [String: Any]
                )
                return
            }
            dir = dir.deletingLastPathComponent()
        }
        XCTFail("Could not locate testdata/sun_moon_times.json")
    }

    private func dbl(_ value: Any?) -> Double { (value as? NSNumber)?.doubleValue ?? .nan }

    func testEventsMatchSharedFixture() {
        let tolerance = dbl(fixture["toleranceSeconds"])
        for testCase in fixture["cases"] as! [[String: Any]] {
            let name = testCase["name"] as! String
            let interval = DateInterval(
                start: Date(timeIntervalSince1970: dbl(testCase["windowStartUnix"])),
                end: Date(timeIntervalSince1970: dbl(testCase["windowEndUnix"]))
            )
            let day = SunMoonCalculator.day(latitude: dbl(testCase["lat"]),
                                            longitude: dbl(testCase["lon"]),
                                            interval: interval)
            let actual: [String: Date?] = [
                "bmnt": day.bmnt, "bmct": day.bmct, "sunrise": day.sunrise, "sunset": day.sunset,
                "eect": day.eect, "eent": day.eent, "moonrise": day.moonrise, "moonset": day.moonset,
            ]
            let expected = testCase["expectedUnix"] as! [String: Any]
            for (key, date) in actual {
                if expected[key] is NSNull {
                    XCTAssertNil(date, "\(name) \(key)")
                } else {
                    let unix = try? XCTUnwrap(date, "\(name) \(key)").timeIntervalSince1970
                    XCTAssertEqual(unix ?? .nan, dbl(expected[key]), accuracy: tolerance, "\(name) \(key)")
                }
            }
            XCTAssertEqual(day.moonIllumination, dbl(testCase["moonIllumination"]),
                           accuracy: dbl(fixture["illuminationTolerance"]), name)
            XCTAssertEqual(day.moonWaxing, testCase["moonWaxing"] as? Bool, name)
        }
    }

    func testLocalDayFollowsTheDeviceCalendarAcrossDaylightSavingChanges() throws {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = try XCTUnwrap(TimeZone(identifier: "America/New_York"))
        // 2026-11-01 12:00 UTC is 07:00 EST on the 25-hour DST-end day.
        let now = Date(timeIntervalSince1970: 1_793_534_400)
        let today = SunMoonCalculator.localDay(containing: now, calendar: calendar)
        XCTAssertEqual(today.start.timeIntervalSince1970, 1_793_505_600)
        XCTAssertEqual(today.duration, 25 * 3600)
        let tomorrow = SunMoonCalculator.localDay(containing: now, dayOffset: 1, calendar: calendar)
        XCTAssertEqual(tomorrow.start, today.end)
    }
}
