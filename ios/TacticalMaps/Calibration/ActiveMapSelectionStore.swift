import Foundation

/// Remembers which basemap was actually visible when the app closed.
///
/// PDFSessionStore deliberately keeps the most recently imported/calibrated
/// PDF available as a library entry even after the user switches back to an
/// online basemap. That is useful, but it is not the same thing as "active".
/// This small sealed descriptor records that missing piece:
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

    private enum Kind: String, Codable {
        case online
        case pdf
        case offlineTiles
    }

    private struct PersistedSelection: Codable {
        let kind: Kind
        let value: String?
    }

    private static let label = "map_source/active_selection"
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

    static func save(_ source: MapSource) {
        let selection: PersistedSelection
        switch source {
        case let online as OnlineRasterBasemapSource:
            selection = PersistedSelection(kind: .online, value: online.style.rawValue)
        case is PDFMapSource:
            // PDFSessionStore already holds the sensitive filename, bounds,
            // calibration and placement in its own sealed payload.
            selection = PersistedSelection(kind: .pdf, value: nil)
        case let offline as OfflineTileMapSource:
            guard let relativePath = managedRelativePath(for: offline.url) else {
                // Only app-managed MBTiles may become a persistent selection.
                // Refuse external, traversing, or symlink-escaped files without
                // replacing the last known-good descriptor.
                return
            }
            selection = PersistedSelection(
                kind: .offlineTiles,
                value: relativePath
            )
        default:
            return
        }

        do {
            let url = storageURLProvider()
            try FileManager.default.createDirectory(
                at: url.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )
            try SafeStore.write(
                JSONEncoder().encode(selection),
                to: url,
                label: label
            )
        } catch {
            // A locked auth-bound key is expected before the user unlocks
            // mission data. Do not clear or replace the previous selection.
            NSLog("[ActiveMapSelectionStore] could not persist active basemap")
        }
    }

    static func restore() -> RestoreResult {
        let loaded = SafeStore.read(
            storageURLProvider(),
            label: label,
            decode: { try JSONDecoder().decode(PersistedSelection.self, from: $0) }
        )
        switch loaded {
        case .empty:
            return .noSelection
        case .locked, .corrupt:
            return .unavailable
        case .loaded(let selection):
            guard let source = source(for: selection) else { return .unavailable }
            return .restored(source)
        }
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

    /// Validate after resolving symlinks so neither a stored `..` component nor
    /// an intermediate symlink can escape Application Support. The first
    /// relative component is also restricted to the two directories managed by
    /// the app's import and PDF-tiling flows.
    private static func validatedManagedOfflineFile(_ file: URL) -> URL? {
        guard file.isFileURL,
              file.pathExtension.caseInsensitiveCompare("mbtiles") == .orderedSame,
              let originalValues = try? file.resourceValues(
                forKeys: [.isRegularFileKey, .isSymbolicLinkKey]
              ),
              originalValues.isRegularFile == true,
              originalValues.isSymbolicLink != true
        else { return nil }

        let resolvedFile = file.standardizedFileURL
            .resolvingSymlinksInPath()
            .standardizedFileURL
        let support = resolvedApplicationSupportDirectory()
        guard relativeComponents(from: support, to: resolvedFile) != nil,
              let resolvedValues = try? resolvedFile.resourceValues(
                forKeys: [.isRegularFileKey, .isSymbolicLinkKey]
              ),
              resolvedValues.isRegularFile == true,
              resolvedValues.isSymbolicLink != true
        else { return nil }
        return resolvedFile
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
