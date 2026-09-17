import XCTest
@testable import TacticalMaps

final class LocalizationTests: XCTestCase {
    func testRetainedPluralSummaryAndNestedErrorsUseTheSelectedLanguage() {
        let original = AppLanguage.shared.selection
        defer { AppLanguage.shared.select(original) }
        AppLanguage.shared.select(.en)
        let summary = Messages.importCompleteSummaryMessage("", "")
            .withArgument(0, Messages.newWaypointCountMessage(1))
            .withArgument(1, Messages.newDrawingCountMessage(2))
        let error = WaypointMutationError.persistenceFailed(DataKey.LockedError()).displayMessage
        XCTAssertEqual(summary.text, "Imported 1 new waypoint and 2 new drawings.")
        XCTAssertTrue(error.text.contains("Mission data key is locked"))
        let mapIssue = MapSelectionPersistenceIssue(id: UUID(), pendingMessage: error)
        let identity = mapIssue.id
        AppLanguage.shared.select(.de)
        XCTAssertEqual(summary.text, "1 neuer Wegpunkt und 2 neue Zeichnungen importiert.")
        XCTAssertFalse(mapIssue.message.contains("Mission data key is locked"))
        XCTAssertEqual(mapIssue.id, identity)
        XCTAssertEqual(mapIssue.pendingMessage, error)
        XCTAssertFalse(MissionLayerMutationError.locked(store: .drawings).localizedDescription.contains("drawings"))
        XCTAssertFalse(MissionLayerMutationError.locked(store: .waypoints).localizedDescription.contains("waypoints"))
    }

    func testRetainedPermissionGuidanceRefreshesWithoutRestartingRecording() {
        let language = AppLanguage.shared
        let original = language.selection
        defer { language.select(original) }
        language.select(.en)
        var starts = 0
        let coordinator = RecordingCoordinator(
            requestAuthorization: {},
            initializeDurableRecording: { starts += 1; return true },
            stopRecording: {}, setBackgroundUpdates: { _ in }, recordingError: { nil }
        )
        coordinator.start(authorization: .denied)
        let guidance = coordinator.guidance
        let control = LiveLocationPermissionPolicy.control(for: .denied)
        XCTAssertEqual(control.title, "Open Location Settings")
        XCTAssertTrue(guidance?.message.contains("Allow access in Settings") == true)
        language.select(.de)
        XCTAssertEqual(control.title, "Standorteinstellungen öffnen")
        XCTAssertTrue(guidance?.message.contains("Erlaube den Zugriff in den Einstellungen") == true)
        XCTAssertEqual(coordinator.guidance, guidance)
        XCTAssertEqual(coordinator.state, .idle)
        XCTAssertEqual(starts, 0)
        XCTAssertEqual(control.action, .openSettings)
        XCTAssertEqual(Messages.recordingStatusStarting(), "WIRD GESTARTET")
        XCTAssertEqual(Messages.recordingStatusInterrupted(), "UNTERBROCHEN")
        XCTAssertEqual(Messages.recordingStatusIdle(), "INAKTIV")
    }

    func testStoreFailureChangesLanguageAndSuccessfulRetryClearsIt() throws {
        let language = AppLanguage.shared
        let original = language.selection
        defer { language.select(original) }
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        var failWrite = true
        var writes = 0
        let store = WaypointStore(storageURL: directory.appendingPathComponent("waypoints.json")) { _, _, _ in
            writes += 1
            if failWrite { throw NSError(domain: "test", code: 1, userInfo: [NSLocalizedDescriptionKey: "disk 100% / field.gpx"]) }
        }
        let waypoint = Waypoint(name: "User name bleibt", latitude: -33, longitude: 151)
        language.select(.en)
        XCTAssertThrowsError(try store.addDurably(waypoint))
        XCTAssertTrue(store.loadError?.contains("Could not save new waypoint") == true)
        language.select(.de)
        XCTAssertTrue(store.loadError?.contains("disk 100% / field.gpx") == true)
        XCTAssertFalse(store.loadError?.contains("Could not save new waypoint") == true)
        XCTAssertTrue(store.waypoints.isEmpty)
        XCTAssertEqual(writes, 1)
        failWrite = false
        XCTAssertTrue(try store.addDurably(waypoint))
        XCTAssertNil(store.loadError)
        XCTAssertEqual(store.waypoints, [waypoint])
        XCTAssertEqual(writes, 2)
    }

    func testBuiltinLayerDisplayChangesWithoutMutatingSavedNames() throws {
        let language = AppLanguage.shared
        let original = language.selection
        defer { language.select(original) }
        language.select(.en)
        let layer = DrawingLayer.seedDefaults[0]
        let custom = DrawingLayer(name: "Friendly", defaultColorHex: "#123456")
        let edited = DrawingLayer(id: DrawingLayer.legacyFallbackID, name: "My team", defaultColorHex: "#123456")
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let before = try encoder.encode(layer)
        language.select(.de)
        XCTAssertEqual(layer.displayName, "Eigene Kräfte")
        XCTAssertEqual(layer.name, "Friendly")
        XCTAssertEqual(custom.displayName, "Friendly")
        XCTAssertEqual(edited.displayName, "My team")
        XCTAssertEqual(try encoder.encode(layer), before)
        let germanSeed = DrawingLayer.seedDefaults[0]
        XCTAssertEqual(germanSeed.name, "Eigene Kräfte")
        language.select(.en)
        XCTAssertEqual(germanSeed.displayName, "Friendly")
        XCTAssertEqual(germanSeed.name, "Eigene Kräfte")
    }

    func testSyncSecurityWarningRefreshesWithoutChangingItsLifecycle() {
        let language = AppLanguage.shared
        let original = language.selection
        defer { language.select(original) }
        let lifecycle = SyncIssueLifecycle()
        let warned = lifecycle.beginConnection()
        language.select(.en)
        let message = Messages.syncTheRelayServedAnOlderSnapshotNewerAuthenticatedLocalMessage()
        lifecycle.report(message, kind: .security, generation: warned)
        let english = lifecycle.issue?.message
        language.select(.de)
        XCTAssertNotEqual(lifecycle.issue?.message, english)
        XCTAssertEqual(lifecycle.issue?.pendingMessage, message)
        XCTAssertEqual(lifecycle.issue?.generation, warned)
        XCTAssertEqual(lifecycle.issue?.kind, .security)
        XCTAssertNotNil(lifecycle.connectionSucceeded(generation: warned, verifiedCleanSnapshot: true))
        let next = lifecycle.beginConnection()
        lifecycle.report(Messages.syncUnitSyncDisconnectedCheckTheRelayOrNetworkReconnectingMessage(), kind: .connection, generation: next)
        XCTAssertEqual(lifecycle.issue?.pendingMessage, message)
        XCTAssertNil(lifecycle.connectionSucceeded(generation: next, verifiedCleanSnapshot: true))
    }

    func testNestedSyncRecoveryRefreshesBothParts() {
        let language = AppLanguage.shared
        let original = language.selection
        defer { language.select(original) }
        let detail = SyncRemoteModelMutationError.invalidPayload.localizedMessage
        let recovery = Messages.syncTheUnitSyncRoomIsFullSoThisSavedMessage()
        let combined = Messages.syncRecoveryDetailMessage("", "")
            .withArgument(0, detail).withArgument(1, recovery)
        language.select(.en)
        XCTAssertEqual(combined.text, detail.text + " " + recovery.text)
        let english = combined.text
        language.select(.de)
        XCTAssertEqual(combined.text, detail.text + " " + recovery.text)
        XCTAssertNotEqual(combined.text, english)
    }

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
