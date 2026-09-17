import XCTest
@testable import TacticalMaps

final class DisplayFormatTests: XCTestCase {
    func testSelectedLanguagePreservesDeviceRegion() {
        let mixed = DisplayFormat.locale(languageTag: "de", device: Locale(identifier: "en_AU"))
        XCTAssertEqual(mixed.language.languageCode?.identifier, "de")
        XCTAssertEqual(mixed.region?.identifier, "AU")
        let device = Locale(identifier: "de_CH")
        XCTAssertEqual(DisplayFormat.locale(languageTag: nil, device: device), device)
    }

    func testNumbersAndMetricThresholds() {
        let de = Locale(identifier: "de_DE")
        let en = Locale(identifier: "en_US")
        XCTAssertEqual(DisplayFormat.number(-12.5, decimals: 1, locale: de), "-12,5")
        XCTAssertEqual(DisplayFormat.number(1234.5, decimals: 2, locale: en), "1234.50")
        XCTAssertEqual(DisplayFormat.number(12.5, decimals: 1, locale: Locale(identifier: "de_CH")), "12.5")
        XCTAssertEqual(DisplayFormat.distance(999, locale: de), "999 m")
        XCTAssertEqual(DisplayFormat.distance(1250, locale: de), "1,25 km")
        XCTAssertEqual(DisplayFormat.distance(100_000, locale: de), "100 km")
        XCTAssertEqual(DisplayFormat.area(9999, locale: de), "9999 m²")
        XCTAssertEqual(DisplayFormat.area(12_500, locale: de), "1,25 ha")
        XCTAssertEqual(DisplayFormat.area(1_250_000, locale: en), "1.25 km²")
        XCTAssertEqual(DisplayFormat.number(.nan, decimals: 1, locale: de), "—")
    }

    func testPercentSpacingAndDateZone() {
        let de = Locale(identifier: "de_DE")
        let en = Locale(identifier: "en_US")
        XCTAssertEqual(DisplayFormat.percent(25, locale: de).replacingOccurrences(of: "\u{00a0}", with: " "), "25 %")
        XCTAssertEqual(DisplayFormat.percent(25, locale: en), "25%")
        XCTAssertEqual(DisplayFormat.percent(0, locale: en), "0%")
        XCTAssertEqual(DisplayFormat.percent(100, locale: en), "100%")
        let date = Date(timeIntervalSince1970: 0)
        XCTAssertTrue(DisplayFormat.dateTime(date, locale: de, timeZone: TimeZone(secondsFromGMT: 0)!).contains("1970"))
        XCTAssertTrue(DisplayFormat.dateTime(date, locale: de, timeZone: TimeZone(secondsFromGMT: -3600)!).contains("1969"))
    }

    func testGridMagneticDecimalsPreserveDirectionAndMils() {
        let de = Locale(identifier: "de_DE")
        XCTAssertEqual(GridMagnetic.label(degrees: -12.5, mils: false, locale: de), "G-M 12,5°W")
        XCTAssertEqual(GridMagnetic.label(degrees: 12.5, mils: false, locale: Locale(identifier: "en_US")), "G-M 12.5°E")
        XCTAssertTrue(GridMagnetic.label(degrees: 12.5, mils: true, locale: de).contains("222"))
    }

    func testTimeUsesExplicitZoneWithoutChangingInstant() {
        let date = Date(timeIntervalSince1970: 0)
        let locale = Locale(identifier: "de_DE")
        XCTAssertEqual(DisplayFormat.time(date, locale: locale, timeZone: TimeZone(secondsFromGMT: 0)!), "00:00")
        XCTAssertEqual(DisplayFormat.time(date, locale: locale, timeZone: TimeZone(secondsFromGMT: 3600)!), "01:00")
        XCTAssertEqual(date.timeIntervalSince1970, 0)
    }

    func testLanguageSwitchRefreshesPresentationWithoutChangingMeasurements() {
        let original = AppLanguage.shared.selection
        defer { AppLanguage.shared.select(original) }
        let session = MeasureSession()
        session.start()
        session.addPoint(.init(latitude: 0, longitude: 0))
        session.addPoint(.init(latitude: 0, longitude: 1))
        let distance = session.totalDistanceMeters
        func signedBytes() -> Data {
            SyncSigning.presenceMessage("dev-1", 1_700_000_000_000, 37.8065, -122.4103,
                                        90, 1.5, "ALPHA-1", "FRIEND", "TEAM", "INFANTRY", true)
        }
        let before = signedBytes()
        AppLanguage.shared.select(.de)
        XCTAssertEqual(DisplayFormat.currentLocale.language.languageCode?.identifier, "de")
        XCTAssertEqual(signedBytes(), before)
        AppLanguage.shared.select(.en)
        XCTAssertEqual(DisplayFormat.currentLocale.language.languageCode?.identifier, "en")
        XCTAssertEqual(session.totalDistanceMeters, distance)
        XCTAssertEqual(session.points.count, 2)
        XCTAssertTrue(session.isActive)
    }
}
