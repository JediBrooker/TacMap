import XCTest
@testable import TacticalMaps

final class DecimalInputTests: XCTestCase {
    func testDecimalGrammarAndRegionalSeparators() {
        let de = Locale(identifier: "de_DE")
        let en = Locale(identifier: "en_US")
        XCTAssertEqual(DecimalInput.parse(" -123,5 ", locale: de), -123.5)
        XCTAssertEqual(DecimalInput.parse("123.5", locale: de), 123.5)
        XCTAssertEqual(DecimalInput.parse("+0,5e2", locale: de), 50)
        XCTAssertEqual(DecimalInput.parse(".5", locale: en), 0.5)
        XCTAssertEqual(DecimalInput.parse("1,234", locale: de), 1.234)
        XCTAssertNil(DecimalInput.parse("1,234", locale: en))
        XCTAssertNil(DecimalInput.parse("1,5", locale: Locale(identifier: "de_CH")))
    }

    func testRejectsGroupingCoordinatesPartialAndNonFiniteValues() {
        for text in ["", "1.234,5", "1,234.5", "1 234", "1’234", "12 m", "12,5,6", "1\n2", "NaN", "Infinity", "1e999", "0x10", "12;34", "--1"] {
            XCTAssertNil(DecimalInput.parse(text, locale: Locale(identifier: "de_DE")), text)
            XCTAssertNil(DecimalInput.parse(text, locale: Locale(identifier: "en_US")), text)
        }
    }

    func testCanonicalStoredValuesRoundTripWithoutRounding() {
        for value in [0.0, -0.25, 123.4567890123, 1e-12, 1e20] {
            XCTAssertEqual(DecimalInput.parse(String(value), locale: Locale(identifier: "de_DE")), value)
        }
    }
}
