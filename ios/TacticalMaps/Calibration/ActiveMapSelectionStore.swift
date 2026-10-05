import Foundation

/// Remembers which basemap was actually visible when the app closed.
///
/// The sealed document deliberately records two independent choices:
///
/// - `active`: the basemap that was visible when the app closed.
/// - `retained`: the last imported PDF or MBTiles map kept in the local
///   library, even while an online basemap is active.
///
/// Keeping those entries separate prevents an online selection from silently
/// orphaning an imported map and prevents a retained map from being mistaken
/// for the active basemap on a cold launch.
///
/// - online style (`esriSatellite`, `osmTopo`, ...)
/// - imported PDF (the sensitive PDF details remain in PDFSessionStore)
/// - offline MBTiles path relative to Application Support
///
/// MBTiles names can identify an area of operations, so the descriptor uses
/// SafeStore rather than plaintext UserDefaults.
enum ActiveMapSelectionStore {
    enum RestoreResult {
        case noSelection
        case restored(MapSource)
        /// Stored selection exists but is locked, corrupt, or its backing file
        /// is gone. Callers keep the safe default basemap in this case.
        case unavailable
    }

    enum RetainedRestoreResult {
        case noRetainedMap
        case restored(MapSource)
        case unavailable(LocalizedMessage)
    }

    enum RetainedMapRemovalError: LocalizedError, LocalizedMessageError {
        case lockedOrUnreadable
        case active
        case unmanagedFile
        case persistenceFailed(Error)
        case rollbackFailed(Error)

        var errorDescription: String? { localizedMessage.text }

        var localizedMessage: LocalizedMessage {
            switch self {
            case .lockedOrUnreadable:
                return Messages.displayTheSavedMapLibraryIsLockedOrUnreadableUnlockMessage()
            case .active:
                return Messages.displaySwitchToAnOnlineBasemapBeforeDeletingTheImportedMessage()
            case .unmanagedFile:
                return Messages.displayTheImportedMapIsOutsideAppManagedStorageSoMessage()
            case .persistenceFailed(let error):
                return Messages.displayTheImportedMapLibraryCouldNotBeUpdatedMessage("").withArgument(0, error.displayMessage)
            case .rollbackFailed(let error):
                return Messages.displayTheImportedMapCouldNotBeDeletedAndItsMessage("").withArgument(0, error.displayMessage)
            }
        }
    }

    private enum Kind: String, Codable {
        case online
        case pdf
        case offlineTiles
    }

    private struct PersistedSelection: Codable, Equatable {
        let kind: Kind
        let value: String?
    }

    private struct PersistedState: Codable {
        let schemaVersion: Int
        var active: PersistedSelection?
        var retained: PersistedSelection?
        /// last online style the user picked, kept while an imported map is
        /// active so "Use Online Map" and the crash guard go back to it (H1).
        /// Optional, files from before it decode with nil
        var preferredOnline: String? = nil
    }

    private struct DecodedState {
        var state: PersistedState
        let migrated: Bool
    }

    private static let label = "map_source/active_selection"
    private static let currentSchema = 2
    private static var pendingUnavailableIssue: (path: String, message: LocalizedMessage)?
    private static let managedOfflineDirectoryNames: Set<String> = [
        "ImportedMaps",
        "offline_tiles"
    ]

    /// Providers are mutable only so unit tests can isolate Application Support,
    /// the selection file, and the legacy imported-map directory. Production
    /// keeps the defaults below.
    static var applicationSupportDirectoryProvider: () -> URL = {
        FileManager.default.urls(
            for: .applicationSupportDirectory,
            in: .userDomainMask
        )[0]
    }

    static var storageURLProvider: () -> URL = {
        let support = applicationSupportDirectoryProvider()
        return support.appendingPathComponent("active-map-selection.json")
    }

    static var importedMapsDirectoryProvider: () throws -> URL = {
        try ImportedMapFileCopier.importedMapsDirectory()
    }

    // MARK: - legacy reader (WP5): the imported-map library replaced this store,
    // it's only read once by ImportedMapLibraryMigration and then removed

    enum LegacySelection: Equatable {
        case online(BasemapStyle)
        case pdf
        /// the resolved, validated MBTiles file (nil when it's gone)
        case offlineTiles(URL?)
    }

    enum LegacySnapshot: Equatable {
        case none
        /// the key failed somewhere in the read, try again after unlock
        case locked
        /// key fine but the selector won't authenticate or decode (the read just
        /// quarantined it), or only its .corrupt-* copy is left, or it was sealed
        /// here before and no library was ever written. 3.0.1 L3: salvage, not block
        case uncertain(LegacyMigrationCause)
        case loaded(active: LegacySelection?, retained: LegacySelection?)
    }

    /// No writes, no publication: just what the old store says.
    static func legacySnapshot() -> LegacySnapshot {
        switch loadState() {
        case .empty: return legacyStoreQuarantined ? (keyAvailable ? .uncertain(.quarantinedOnly) : .locked) : .none
        case .locked: return .locked
        // a failed unseal with the key there is damage, not a lock (L3 re-check)
        case .corrupt: return keyAvailable ? .uncertain(.corrupt) : .locked
        case .loaded(let decoded):
            func map(_ s: PersistedSelection?) -> LegacySelection? {
                guard let s else { return nil }
                switch s.kind {
                case .online:
                    let style = s.value.flatMap(BasemapStyle.init(rawValue:)) ?? OnlineRasterBasemapSource.defaultStyle
                    return .online(style)
                case .pdf: return .pdf
                case .offlineTiles: return .offlineTiles(s.value.flatMap(restoredOfflineFile(for:)))
                }
            }
            return .loaded(active: map(decoded.state.active), retained: map(decoded.state.retained))
        }
    }

    /// drop the old descriptor once the library holds everything it said
    static func removeLegacyStore() {
        try? FileManager.default.removeItem(at: storageURLProvider())
    }

    static var legacyStoreExists: Bool {
        FileManager.default.fileExists(atPath: storageURLProvider().path)
    }

    private static var keyAvailable: Bool { (try? SafeStore.keyProvider()) != nil }

    /// S1: the old selector is gone but not because the library took over: read()
    /// quarantined it (.corrupt-* sibling), or it was sealed here before and no
    /// library was ever written (clearLegacy only runs after that write). Never
    /// reads as "no legacy left", the migration salvages instead. No key = can't
    /// tell = true
    static var legacyStoreQuarantined: Bool {
        let url = storageURLProvider()
        guard !FileManager.default.fileExists(atPath: url.path) else { return false }
        if SafeStore.quarantineSiblingExists(url) { return true }
        guard let sealedBefore = try? SafeStore.wasSealedBefore(url, label: label) else { return true }
        guard sealedBefore else { return false }
        let library = ImportedMapLibrary.storageURLProvider()
        return (try? SafeStore.wasSealedBefore(library, label: ImportedMapLibrary.label)) != true
    }

    /// Legacy writer, kept so tests can build frozen pre-library fixtures for
    /// the migration. The app writes ImportedMapLibrary now.
    @discardableResult
    static func save(_ source: MapSource, clearRetained: Bool = false) -> Bool {
        guard let selection = selection(for: source) else { return false }
        // Clearing the library is only valid while selecting a usable online
        // source. Imported sources are, by definition, the retained entry.
        guard !clearRetained || selection.kind == .online else { return false }

        var state: PersistedState
        switch loadState() {
        case .empty:
            state = PersistedState(schemaVersion: currentSchema,
                                   active: nil,
                                   retained: nil)
        case .loaded(let decoded):
            state = decoded.state
        case .locked, .corrupt:
            // Never overwrite a retained imported-map reference merely because
            // its encryption key is locked or its bytes need recovery.
            return false
        }

        if state.preferredOnline == nil, state.active?.kind == .online {
            state.preferredOnline = state.active?.value
        }
        if selection.kind == .online { state.preferredOnline = selection.value }
        state.active = selection
        if clearRetained {
            state.retained = nil
        } else if selection.kind != .online {
            state.retained = selection
        }
        return write(state)
    }

    private static func selection(for source: MapSource) -> PersistedSelection? {
        switch source {
        case let online as OnlineRasterBasemapSource:
            return PersistedSelection(kind: .online, value: online.style.rawValue)
        case is PDFMapSource:
            // PDFSessionStore already holds the sensitive filename, bounds,
            // calibration and placement in its own sealed payload.
            return PersistedSelection(kind: .pdf, value: nil)
        case let offline as OfflineTileMapSource:
            guard let relativePath = managedRelativePath(for: offline.url) else {
                // Only app-managed MBTiles may become a persistent selection.
                // Refuse external, traversing, or symlink-escaped files without
                // replacing the last known-good descriptor.
                return nil
            }
            return PersistedSelection(
                kind: .offlineTiles,
                value: relativePath
            )
        default:
            return nil
        }
    }

    private static func write(_ state: PersistedState) -> Bool {
        do {
            let url = storageURLProvider()
            try FileManager.default.createDirectory(
                at: url.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )
            try SafeStore.write(
                JSONEncoder().encode(state),
                to: url,
                label: label
            )
            if pendingUnavailableIssue?.path == url.standardizedFileURL.path {
                pendingUnavailableIssue = nil
            }
            return true
        } catch {
            // A locked auth-bound key is expected before the user unlocks
            // mission data. Do not clear or replace the previous selection.
            NSLog("[ActiveMapSelectionStore] could not persist active basemap")
            return false
        }
    }

    /// the online style to fall back to, nil when none was ever stored (or the
    /// file is locked). Keyed styles without a key are the caller's problem
    static func preferredOnlineStyle() -> BasemapStyle? {
        guard case .loaded(let decoded) = loadState() else { return nil }
        let s = decoded.state
        let raw = s.preferredOnline ?? (s.active?.kind == .online ? s.active?.value : nil)
        return raw.flatMap(BasemapStyle.init(rawValue:))
    }

    static func restore() -> RestoreResult {
        switch loadState() {
        case .empty:
            return .noSelection
        case .locked, .corrupt:
            return .unavailable
        case .loaded(let decoded):
            guard let selection = decoded.state.active else { return .noSelection }
            guard let source = source(for: selection) else { return .unavailable }
            // A legacy descriptor is readable, but do not publish from it until
            // the active+retained v2 snapshot is durably established. Retry can
            // safely decode the same legacy bytes after a locked/disk-full write.
            if decoded.migrated, !write(decoded.state) { return .unavailable }
            return .restored(source)
        }
    }

    static func restoreRetained() -> RetainedRestoreResult {
        switch loadState() {
        case .empty:
            if let issue = pendingUnavailableIssue,
               issue.path == storageURLProvider().standardizedFileURL.path {
                return .unavailable(issue.message)
            }
            return .noRetainedMap
        case .locked:
            return .unavailable(Messages.displaySavedImportedMapDetailsAreLockedUnlockMissionDataMessage())
        case .corrupt:
            return .unavailable(Messages.displaySavedImportedMapDetailsCouldNotBeAuthenticatedTheMessage())
        case .loaded(let decoded):
            guard let selection = decoded.state.retained else { return .noRetainedMap }
            guard let source = source(for: selection) else {
                return .unavailable(Messages.displayTheSavedImportedMapIsMissingOrUnreadableRemoveMessage())
            }
            if decoded.migrated, !write(decoded.state) {
                return .unavailable(Messages.displayTheSavedImportedMapEntryCouldNotBeUpgradedMessage())
            }
            return .restored(source)
        }
    }

    // reconcileManagedImportedMapFiles is gone: it deleted every imported file
    // the one retained entry didn't name, which after the library migration
    // would wipe the user's other maps. ImportedMapLibrary.reconcile owns cleanup.

    /// Removes the retained library entry. The candidate descriptor is written
    /// first while the managed backing file is still recoverable. If deletion
    /// then fails, the old descriptor (and PDF session, where applicable) is
    /// restored so Retry still has a truthful source to operate on.
    static func removeRetainedMap(deleteBackingFile: Bool) throws {
        let decoded: DecodedState
        switch loadState() {
        case .loaded(let value): decoded = value
        case .empty:
            if pendingUnavailableIssue?.path == storageURLProvider().standardizedFileURL.path {
                pendingUnavailableIssue = nil
            }
            return
        case .locked, .corrupt: throw RetainedMapRemovalError.lockedOrUnreadable
        }
        guard let retained = decoded.state.retained else { return }
        let retainedWasStoredActive = decoded.state.active == retained
        guard !retainedWasStoredActive || source(for: retained) == nil else {
            throw RetainedMapRemovalError.active
        }

        let fileToDelete: URL?
        if deleteBackingFile, let file = backingFile(for: retained) {
            guard validatedManagedImportedFile(file, expectedKind: retained.kind) != nil else {
                throw RetainedMapRemovalError.unmanagedFile
            }
            fileToDelete = file
        } else {
            fileToDelete = nil
        }

        var candidate = decoded.state
        candidate.retained = nil
        if retainedWasStoredActive { candidate.active = nil }
        guard write(candidate) else {
            throw RetainedMapRemovalError.persistenceFailed(CocoaError(.fileWriteUnknown))
        }

        let pdfSnapshot = retained.kind == .pdf
            ? PDFSessionStore.snapshotActiveSession()
            : nil
        if retained.kind == .pdf, !PDFSessionStore.clear() {
            // OD3-R3-1: clear() can drop our value and still read one back (a
            // stale copy in a lower prefs domain did exactly that on the sim).
            // put the exact session bytes back too, not just the selector, or
            // the library ends up pointing at that stale session instead of
            // the real file and Retry works on the wrong map
            let pdfRestored = pdfSnapshot.map(PDFSessionStore.restoreActiveSession) ?? true
            let selectorRestored = write(decoded.state)
            guard pdfRestored, selectorRestored else {
                throw RetainedMapRemovalError.rollbackFailed(CocoaError(.fileWriteUnknown))
            }
            throw RetainedMapRemovalError.persistenceFailed(CocoaError(.fileWriteUnknown))
        }

        if let fileToDelete {
            do {
                try FileManager.default.removeItem(at: fileToDelete)
            } catch let error as CocoaError where error.code == .fileNoSuchFile {
                // The stale reference has now been cleared truthfully.
            } catch {
                let pdfRestored = pdfSnapshot.map(PDFSessionStore.restoreActiveSession) ?? true
                // Attempt both rollback stores even if the first one fails;
                // short-circuiting here would unnecessarily discard a still-
                // recoverable selector after a PDF-session restore problem.
                let selectorRestored = write(decoded.state)
                guard pdfRestored, selectorRestored else {
                    throw RetainedMapRemovalError.rollbackFailed(error)
                }
                throw RetainedMapRemovalError.persistenceFailed(error)
            }
        }
    }

    /// Resolve only the backing URL for deletion. In particular, do not build
    /// an OfflineTileMapSource here: its MBTilesStore would open SQLite and then
    /// immediately unlink the database out from under that live handle.
    private static func backingFile(for selection: PersistedSelection) -> URL? {
        switch selection.kind {
        case .pdf:
            // a kept session whose file is gone (OD-F4) has nothing to delete
            guard let url = PDFSessionStore.load()?.url, FileManager.default.fileExists(atPath: url.path) else {
                return nil
            }
            return url
        case .offlineTiles:
            guard let path = selection.value else { return nil }
            return restoredOfflineFile(for: path)
        case .online:
            return nil
        }
    }

    private static func loadState() -> SafeStore.Load<DecodedState> {
        let url = storageURLProvider()
        let result = SafeStore.read(url, label: label, decode: decodeState)
        if case .corrupt = result {
            pendingUnavailableIssue = (
                url.standardizedFileURL.path,
                Messages.displaySavedImportedMapDetailsCouldNotBeAuthenticatedTheMessage()
            )
        }
        return result
    }

    private static func decodeState(_ data: Data) throws -> DecodedState {
        let decoder = JSONDecoder()
        if let state = try? decoder.decode(PersistedState.self, from: data),
           state.schemaVersion == currentSchema {
            return DecodedState(state: state, migrated: false)
        }
        // v1 stored only the active selection. If that selection was imported,
        // it is also the best truthful retained-library entry for migration.
        let legacy = try decoder.decode(PersistedSelection.self, from: data)
        let retained: PersistedSelection?
        if legacy.kind == .online {
            // Before the retained field existed, PDFSessionStore was already a
            // sealed one-map library. Use that durable evidence during the
            // one-time migration rather than silently orphaning a saved PDF.
            // Only whether it's there: load() commits what it decides (parks v1
            // points, rewrites or clears the session) before the migration's
            // own read gets to see it, and that lost points and files
            retained = PDFSessionStore.hasStoredSession
                ? PersistedSelection(kind: .pdf, value: nil)
                : nil
        } else {
            retained = legacy
        }
        return DecodedState(
            state: PersistedState(schemaVersion: currentSchema,
                                  active: legacy,
                                  retained: retained),
            migrated: true
        )
    }

    private static func source(for selection: PersistedSelection) -> MapSource? {
        switch selection.kind {
        case .online:
            guard let raw = selection.value,
                  let style = BasemapStyle(rawValue: raw) else { return nil }
            // A build without the configured Esri key cannot render keyed
            // styles. Fall back to the normal usable online default.
            let available = style.requiresEsriKey && !EsriKey.isAvailable
                ? OnlineRasterBasemapSource.defaultStyle
                : style
            return OnlineRasterBasemapSource(available)

        case .pdf:
            return PDFSessionStore.load()

        case .offlineTiles:
            guard let storedPath = selection.value,
                  let file = restoredOfflineFile(for: storedPath)
            else { return nil }
            return OfflineTileMapSource(url: file)
        }
    }

    /// New descriptors use `ImportedMaps/name.mbtiles` or
    /// `offline_tiles/name.mbtiles`. A legacy descriptor contains only the
    /// basename and is resolved against ImportedMaps for compatibility.
    private static func restoredOfflineFile(for storedPath: String) -> URL? {
        let candidate: URL
        if isSafeFileName(storedPath) {
            guard let imported = try? importedMapsDirectoryProvider() else {
                return nil
            }
            candidate = imported.appendingPathComponent(storedPath, isDirectory: false)
        } else {
            guard let components = safeRelativeComponents(storedPath) else {
                return nil
            }
            candidate = components.enumerated().reduce(
                applicationSupportDirectoryProvider()
            ) { partial, item in
                partial.appendingPathComponent(
                    item.element,
                    isDirectory: item.offset < components.count - 1
                )
            }
        }
        return validatedManagedOfflineFile(candidate)
    }

    private static func managedRelativePath(for file: URL) -> String? {
        guard let resolvedFile = validatedManagedOfflineFile(file) else {
            return nil
        }
        let support = resolvedApplicationSupportDirectory()
        guard let components = relativeComponents(from: support, to: resolvedFile) else {
            return nil
        }
        return components.joined(separator: "/")
    }

    private static func validatedManagedImportedFile(_ file: URL,
                                                     expectedKind: Kind) -> URL? {
        switch expectedKind {
        case .offlineTiles:
            return validatedManagedOfflineFile(file)
        case .pdf:
            guard file.isFileURL,
                  file.pathExtension.caseInsensitiveCompare("pdf") == .orderedSame,
                  let imported = try? importedMapsDirectoryProvider(),
                  let resolved = validatedRegularFile(file),
                  resolved.deletingLastPathComponent().standardizedFileURL
                    == imported.standardizedFileURL.resolvingSymlinksInPath().standardizedFileURL
            else { return nil }
            return resolved
        case .online:
            return nil
        }
    }

    /// Validate after resolving symlinks so neither a stored `..` component nor
    /// an intermediate symlink can escape Application Support. The first
    /// relative component is also restricted to the two directories managed by
    /// the app's import and PDF-tiling flows.
    private static func validatedManagedOfflineFile(_ file: URL) -> URL? {
        guard file.isFileURL,
              file.pathExtension.caseInsensitiveCompare("mbtiles") == .orderedSame,
              let resolvedFile = validatedRegularFile(file)
        else { return nil }
        let support = resolvedApplicationSupportDirectory()
        guard relativeComponents(from: support, to: resolvedFile) != nil else { return nil }
        return resolvedFile
    }

    private static func validatedRegularFile(_ file: URL) -> URL? {
        guard let originalValues = try? file.resourceValues(
            forKeys: [.isRegularFileKey, .isSymbolicLinkKey]
        ), originalValues.isRegularFile == true,
           originalValues.isSymbolicLink != true else { return nil }
        let resolved = file.standardizedFileURL
            .resolvingSymlinksInPath()
            .standardizedFileURL
        guard let resolvedValues = try? resolved.resourceValues(
            forKeys: [.isRegularFileKey, .isSymbolicLinkKey]
        ), resolvedValues.isRegularFile == true,
           resolvedValues.isSymbolicLink != true else { return nil }
        return resolved
    }

    private static func resolvedApplicationSupportDirectory() -> URL {
        applicationSupportDirectoryProvider()
            .standardizedFileURL
            .resolvingSymlinksInPath()
            .standardizedFileURL
    }

    private static func relativeComponents(from root: URL, to file: URL) -> [String]? {
        let rootPath = root.path.hasSuffix("/") ? root.path : root.path + "/"
        guard file.path.hasPrefix(rootPath) else { return nil }
        let relative = String(file.path.dropFirst(rootPath.count))
        guard let components = safeRelativeComponents(relative) else {
            return nil
        }
        return components
    }

    private static func safeRelativeComponents(_ path: String) -> [String]? {
        guard !path.isEmpty, !path.hasPrefix("/"), !path.contains("\\") else {
            return nil
        }
        let components = path.split(separator: "/", omittingEmptySubsequences: false)
            .map(String.init)
        guard components.count >= 2,
              managedOfflineDirectoryNames.contains(components[0]),
              components.allSatisfy({ !$0.isEmpty && $0 != "." && $0 != ".." })
        else { return nil }
        return components
    }

    private static func isSafeFileName(_ name: String) -> Bool {
        !name.isEmpty
            && name == URL(fileURLWithPath: name).lastPathComponent
            && !name.contains("/")
            && !name.contains("\\")
    }
}
