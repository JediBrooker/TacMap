import XCTest
@testable import TacticalMaps

final class LocalizationTests: XCTestCase {
    private func resources(_ language: String) throws -> Bundle {
        let path = try XCTUnwrap(Bundle.main.path(forResource: language, ofType: "lproj"))
        return try XCTUnwrap(Bundle(path: path))
    }

    func testGermanAndEnglishResourcesAreBundled() throws {
        let german = try resources("de")
        let english = try resources("en")
        XCTAssertEqual(german.localizedString(forKey: "Save", value: nil, table: nil), "Speichern")
        XCTAssertEqual(english.localizedString(forKey: "Save", value: nil, table: nil), "Save")
        XCTAssertEqual(german.localizedString(forKey: "Platoon", value: nil, table: nil), "Zug")
        XCTAssertEqual(german.localizedString(forKey: "Friendly", value: nil, table: nil), "Eigene Kräfte")
    }

    func testInterpolationPreservesUserContent() throws {
        let german = try resources("de")
        let detail = "100% – Karte {1} \"Alpha\""
        let format = german.localizedString(forKey: "Import failed: %1$@", value: nil, table: nil)
        XCTAssertEqual(String(format: format, detail), "Import fehlgeschlagen: \(detail)")
    }

    func testGermanZeroOneAndManyPluralForms() throws {
        let german = try resources("de")
        let format = german.localizedString(forKey: "count.point", value: nil, table: nil)
        XCTAssertEqual(String.localizedStringWithFormat(format, 0), "0 Punkte")
        XCTAssertEqual(String.localizedStringWithFormat(format, 1), "1 Punkt")
        XCTAssertEqual(String.localizedStringWithFormat(format, 2), "2 Punkte")
    }

    func testSaveErrorPrefixesRemainConsistentInBothLanguages() throws {
        for language in ["en", "de"] {
            let bundle = try resources(language)
            let prefix = bundle.localizedString(forKey: "Could not save", value: nil, table: nil)
            for key in [
                "Could not save new drawing to disk: %1$@",
                "Could not save imported drawings to disk: %1$@",
                "Could not save reassigned drawings to disk: %1$@",
                "Could not save new waypoint to disk: %1$@",
                "Could not save imported waypoints to disk: %1$@",
                "Could not save reassigned waypoints to disk: %1$@",
                "Could not save %1$@ to disk: %2$@"
            ] {
                XCTAssertTrue(bundle.localizedString(forKey: key, value: nil, table: nil)
                    .hasPrefix(prefix), "\(language): \(key)")
            }
        }
    }

    func testGermanChatAndImportPlurals() throws {
        let german = try resources("de")
        for (key, singular, plural) in [
            ("count.unread", "1 ungelesene Nachricht", "2 ungelesene Nachrichten"),
            ("count.new_waypoint", "1 neuer Wegpunkt", "2 neue Wegpunkte"),
            ("count.new_drawing", "1 neue Zeichnung", "2 neue Zeichnungen")
        ] {
            let format = german.localizedString(forKey: key, value: nil, table: nil)
            XCTAssertEqual(String.localizedStringWithFormat(format, 1), singular)
            XCTAssertEqual(String.localizedStringWithFormat(format, 2), plural)
        }
    }

    func testPermissionPromptsAreLocalized() throws {
        let german = try resources("de")
        let prompt = german.localizedString(forKey: "NSLocationWhenInUseUsageDescription",
                                            value: nil, table: "InfoPlist")
        XCTAssertTrue(prompt.contains("Standort"))
        XCTAssertTrue(prompt.contains("MGRS"))
    }

    func testSymbolWireValuesStayLanguageIndependent() {
        XCTAssertEqual(SymbolAffiliation.friend.rawValue, "friend")
        XCTAssertEqual(SymbolEchelon.platoon.rawValue, "platoon")
    }
}
