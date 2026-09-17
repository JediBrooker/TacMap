import XCTest
@testable import TacticalMaps

final class LocalizationTests: XCTestCase {
    func testLanguageChoicePersistsAndInvalidChoiceFallsBackToDevice() throws {
        let name = "LocalizationTests." + UUID().uuidString
        let defaults = try XCTUnwrap(UserDefaults(suiteName: name))
        defer { defaults.removePersistentDomain(forName: name) }
        let language = AppLanguage(defaults: defaults)
        XCTAssertEqual(language.selection, .system)
        language.select(.de)
        XCTAssertEqual(AppLanguage(defaults: defaults).selection, .de)
        defaults.set("unsupported", forKey: AppLanguage.preferenceKey)
        XCTAssertEqual(AppLanguage(defaults: defaults).selection, .system)
    }

    func testLanguageChangesRefreshTextPluralsAndCatalogs() {
        let language = AppLanguage.shared
        let original = language.selection
        defer { language.select(original) }
        language.select(.en)
        XCTAssertEqual(L10n.text("Save"), "Save")
        XCTAssertEqual(MarkerCatalog.teamColors[0].name, "Red")
        language.select(.de)
        XCTAssertEqual(L10n.text("Save"), "Speichern")
        XCTAssertEqual(L10n.quantity("point", 1), "1 Punkt")
        XCTAssertEqual(L10n.quantity("point", 2), "2 Punkte")
        XCTAssertEqual(MarkerCatalog.teamColors[0].name, "Rot")
        language.select(.en)
        XCTAssertEqual(L10n.text("Save"), "Save")
        XCTAssertEqual(L10n.quantity("point", 2), "2 points")
        XCTAssertEqual(MarkerCatalog.teamColors[0].name, "Red")
    }

    func testStableMessagesRefreshAndPreserveArguments() {
        let language = AppLanguage.shared
        let original = language.selection
        defer { language.select(original) }
        let detail = "100% – Karte {1} %@"
        language.select(.de)
        XCTAssertEqual(Messages.settingsLanguageTitle(), "Sprache")
        XCTAssertEqual(Messages.importFailed(detail), "Import fehlgeschlagen: \(detail)")
        XCTAssertEqual(Messages.pointCount(2), "2 Punkte")
        language.select(.en)
        XCTAssertEqual(Messages.settingsLanguageTitle(), "Language")
        XCTAssertEqual(Messages.importFailed(detail), "Import failed: \(detail)")
        XCTAssertEqual(Messages.pointCount(2), "2 points")
    }

    func testGeneratedLanguagesPreserveSavedChoiceValues() {
        XCTAssertEqual(AppLanguage.Choice(rawValue: "system"), .system)
        XCTAssertEqual(AppLanguage.Choice(rawValue: "en"), .en)
        XCTAssertEqual(AppLanguage.Choice(rawValue: "de"), .de)
        let bundled = Bundle.main.object(forInfoDictionaryKey: "CFBundleLocalizations") as? [String]
        XCTAssertEqual(Set(bundled ?? []), Set(SupportedLanguage.resourceFolders.keys))
    }

    func testPendingTrackMessageResolvesAfterLanguageSwitch() {
        let original = AppLanguage.shared.selection
        defer { AppLanguage.shared.select(original) }
        let detail = "100% {1} %@ / saved.gpx"
        let pending = Messages.trackReencryptFailedMessage(detail)
        AppLanguage.shared.select(.en)
        XCTAssertEqual(pending.text, "Could not encrypt the recovered track: " + detail)
        AppLanguage.shared.select(.de)
        XCTAssertEqual(pending.text, "Der wiederhergestellte Track konnte nicht verschlüsselt werden: " + detail)
        XCTAssertEqual(pending.arguments, [detail])
        XCTAssertEqual(LocalizedMessage.literal(detail).text, detail)
        AppLanguage.shared.select(.en)
        XCTAssertEqual(pending.text, "Could not encrypt the recovered track: " + detail)
    }

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
