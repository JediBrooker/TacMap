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
        case unavailable(String)
    }

    enum RetainedMapRemovalError: LocalizedError {
        case lockedOrUnreadable
        case active
        case unmanagedFile
        case persistenceFailed(Error)
        case rollbackFailed(Error)

        var errorDescription: String? {
            switch self {
            case .lockedOrUnreadable:
                return "The saved map library is locked or unreadable. Unlock mission data, then try again."
            case .active:
                return "Switch to an online basemap before deleting the imported map."
            case .unmanagedFile:
                return "The imported map is outside app-managed storage, so it was not deleted."
            case .persistenceFailed(let error):
                return "The imported map library could not be updated: \(error.localizedDescription)"
            case .rollbackFailed(let error):
                return "The imported map could not be deleted and its saved entry could not be restored. Keep the app open and retry: \(error.localizedDescription)"
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
    }

    private struct DecodedState {
        var state: PersistedState
        let migrated: Bool
    }

    private static let label = "map_source/active_selection"
    private static let currentSchema = 2
    private static var pendingUnavailableIssue: (path: String, message: String)?
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
            return .unavailable("Saved imported-map details are locked. Unlock mission data to restore them.")
        case .corrupt:
            return .unavailable("Saved imported-map details could not be authenticated. The recovery copy was preserved; reset the saved map selection or import the map again.")
        case .loaded(let decoded):
            guard let selection = decoded.state.retained else { return .noRetainedMap }
            guard let source = source(for: selection) else {
                return .unavailable("The saved imported map is missing or unreadable. Remove its saved entry, then import the source file again.")
            }
            if decoded.migrated, !write(decoded.state) {
                return .unavailable("The saved imported-map entry could not be upgraded securely. Unlock mission data or free storage, then try again.")
            }
            return .restored(source)
        }
    }

    /// Remove plaintext PDF/GeoPDF/MBTiles copies that are no longer reachable
    /// from the authenticated v2 map-library snapshot. Missing, legacy, locked,
    /// corrupt, or internally inconsistent state performs no deletion: without
    /// durable authority we cannot know which operational map is recoverable.
    @discardableResult
    static func reconcileManagedImportedMapFiles() -> Bool {
        guard case .loaded(let decoded) = loadState(), !decoded.migrated else {
            return false
        }
        let active = decoded.state.active
        let retained = decoded.state.retained
        let keep: URL?
        if retained?.kind == .pdf {
            guard active?.kind != .offlineTiles else { return false }
            guard let source = source(for: retained!) as? PDFMapSource else {
                return false
            }
            keep = source.url
        } else if retained?.kind == .offlineTiles {
            guard active?.kind != .pdf,
                  active?.kind != .offlineTiles || active == retained else { return false }
            guard let path = retained?.value,
                  let file = restoredOfflineFile(for: path) else { return false }
            guard PDFSessionStore.clear() else { return false }
            keep = file
        } else {
            // A current snapshot can only activate an imported map while
            // retaining that same map. Refuse cleanup rather than guessing
            // through a semantically inconsistent or future snapshot.
            guard active?.kind != .pdf, active?.kind != .offlineTiles else { return false }
            guard PDFSessionStore.clear() else { return false }
            keep = nil
        }

        guard let imported = try? importedMapsDirectoryProvider() else { return false }
        let generated = applicationSupportDirectoryProvider()
            .appendingPathComponent("offline_tiles", isDirectory: true)
        return ManagedImportedMapFileLifecycle.reconcile(
            directories: [imported, generated],
            keeping: keep.map { Set([$0]) } ?? []
        )
    }

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
            guard write(decoded.state) else {
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
            return PDFSessionStore.load()?.url
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
                "Saved imported-map details could not be authenticated. The recovery copy was preserved; reset the saved map selection or import the map again."
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
            retained = PDFSessionStore.load() == nil
                ? nil
                : PersistedSelection(kind: .pdf, value: nil)
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
