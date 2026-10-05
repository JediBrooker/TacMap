import Foundation
import CoreGraphics

/// One imported map in the library (contract s8.2). Everything that names or
/// places the user's AO lives in here, sealed: the file names on disk are opaque.
struct ImportedMapEntry: Codable, Hashable, Identifiable, Sendable {
    enum Kind: String, Codable, Sendable { case pdf, mbtiles }

    struct PDFInfo: Codable, Hashable, Sendable {
        var pageCount: Int
        var pageIndex: Int
        var rotate: Int
        /// CropBox n MediaBox of the chosen page, 4 raw user-space corners
        var pageBox: [PdfPagePoint]
        var embedded: PdfGeoreference?
        /// PdfGeorefRejectReason raw value when the PDF declared a georef we couldn't use
        var embeddedIssue: String?
        var manual: ManualCalibration?
        /// reserved for WP2's first-render crash breaker (D5-07)
        var firstRenderPending: Bool?
        /// WP2 offline tiles baked from exactly the effective georef. Any georef
        /// change drops it (reducer), then the reconcile + bake sweep reap the file
        var bake: PDFBakeRecord? = nil
        /// random UUID the crash guard knows this map by (WP2 s.I), minted with the
        /// entry. entries from before it fall back to the entry id, see below
        var renderGuardToken: String? = nil

        var effectiveGeoref: PdfGeoreference? { manual?.georef ?? embedded }

        /// the bake record only if it's one we'd have written
        var validBake: PDFBakeRecord? { bake.flatMap { $0.isValidRecord ? $0 : nil } }
    }

    var id: UUID
    var kind: Kind
    /// opaque path relative to Application Support, e.g. ImportedMaps/map-<uuid>.pdf
    var fileName: String
    /// source file stem, only ever stored sealed in here
    var displayName: String
    /// sha256:<hex>, optional only for migrated MBTiles
    var contentKey: String?
    var byteCount: Int64
    var fileModifiedAtMs: Int64
    var importedAtMs: Int64
    var derivedFromId: UUID?
    var pdf: PDFInfo?

    /// crash guard token. the entry id is a fine stand in for entries that
    /// never got one: random, sealed, nothing to do with the file or its name
    var renderGuardToken: String {
        pdf?.renderGuardToken.flatMap { UUID(uuidString: $0)?.uuidString } ?? id.uuidString
    }
}

extension PDFBakeRecord {
    /// a record we'd have written: plain tacmap-bake-*.mbtiles name, 64 hex key, sane numbers
    var isValidRecord: Bool {
        let name = fileName
        return !name.isEmpty && name == URL(fileURLWithPath: name).lastPathComponent
            && !name.contains("/") && !name.contains("\\") && name.lowercased().hasSuffix(".mbtiles")
            && bakeKey.count == 64 && bakeKey.allSatisfy { $0.isHexDigit }
            && (16...1024).contains(tilePx)
            && minZoom >= 0 && minZoom <= maxZoom && maxZoom <= PDFTileConstants.maxZoomCap
            && bytes >= 0
    }
}

enum MapSelection: Hashable, Sendable {
    case online(BasemapStyle)
    case entry(UUID)
}

extension MapSelection: Codable {
    private enum Keys: String, CodingKey { case kind, style, id }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: Keys.self)
        switch try c.decode(String.self, forKey: .kind) {
        case "online":
            let raw = try c.decode(String.self, forKey: .style)
            self = .online(BasemapStyle(rawValue: raw) ?? OnlineRasterBasemapSource.defaultStyle)
        case "entry":
            self = .entry(try c.decode(UUID.self, forKey: .id))
        default:
            throw DecodingError.dataCorruptedError(forKey: .kind, in: c, debugDescription: "unknown selection")
        }
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: Keys.self)
        switch self {
        case .online(let style):
            try c.encode("online", forKey: .kind)
            try c.encode(style.rawValue, forKey: .style)
        case .entry(let id):
            try c.encode("entry", forKey: .kind)
            try c.encode(id, forKey: .id)
        }
    }
}

/// The ONE authority for the active map and the library (s8.2). Every
/// transition is exactly one sealed write of this, then publication.
struct LibraryState: Codable, Hashable, Sendable {
    static let currentSchema = 1

    var schemaVersion = currentSchema
    var active: MapSelection?
    var preferredOnlineStyle: String = OnlineRasterBasemapSource.defaultStyle.rawValue
    var entries: [ImportedMapEntry] = []
    // A rebuilt index cannot prove which unreferenced files or drafts are disposable.
    var recoveryPreservesOrphans: Bool? = nil

    var permitsCleanup: Bool { recoveryPreservesOrphans != true }

    var preferredStyle: BasemapStyle {
        BasemapStyle(rawValue: preferredOnlineStyle) ?? OnlineRasterBasemapSource.defaultStyle
    }

    func entry(_ id: UUID) -> ImportedMapEntry? { entries.first { $0.id == id } }

    func entry(contentKey: String) -> ImportedMapEntry? {
        entries.first { $0.contentKey == contentKey && $0.derivedFromId == nil }
    }

    var activeEntryID: UUID? {
        if case .entry(let id)? = active { return id }
        return nil
    }
}

// MARK: - derived state (pure, import_limits.json entryStates)

enum ImportedMapFileStatus: Equatable, Sendable {
    case ok
    case missing
    case sizeOrMtimeMismatch
}

enum ImportedMapState: String, Sendable {
    case geoPDF, calibrated, rejected, needsCalibration, offlineTiles, derived, unavailable
}

enum ImportedMapRowTap: String, Sendable { case activate, calibrate, none }

enum ImportedMapMenuAction: String, Sendable {
    case calibrate = "map_action_calibrate"
    case choosePage = "map_action_choose_page"
    case generateOfflineTiles = "generateOfflineTiles"
    case useEmbedded = "map_action_use_embedded"
    case delete = "map_action_delete"
}

struct ImportedMapPresentation: Sendable {
    var state: ImportedMapState
    var effectiveGeoref: String?
    var canBeDurableActive: Bool
    var subtitle: CalibrationMessage
    var rowTap: ImportedMapRowTap
    var menu: [ImportedMapMenuAction]
}

enum ImportedMapStates {

    static func state(_ e: ImportedMapEntry, file: ImportedMapFileStatus) -> ImportedMapState {
        if file != .ok { return .unavailable }
        if e.kind == .mbtiles { return e.derivedFromId == nil ? .offlineTiles : .derived }
        guard let pdf = e.pdf else { return .needsCalibration }
        // S2: a rebuild adopted it but couldnt read it, pageCount 0. Delete only
        if pdf.pageCount <= 0 { return .unavailable }
        if pdf.manual != nil { return .calibrated }
        if pdf.embedded != nil { return .geoPDF }
        if pdf.embeddedIssue != nil { return .rejected }
        return .needsCalibration
    }

    static func canBeDurableActive(_ s: ImportedMapState) -> Bool {
        [.geoPDF, .calibrated, .offlineTiles, .derived].contains(s)
    }

    static func present(_ e: ImportedMapEntry, file: ImportedMapFileStatus, draftPoints: Int?,
                        parentName: String?) -> ImportedMapPresentation {
        let s = state(e, file: file)
        let pdf = e.pdf
        let effective: String? = pdf.flatMap { $0.manual != nil ? "manual" : ($0.embedded != nil ? "embedded" : nil) }
        let can = canBeDurableActive(s)
        let subtitle: CalibrationMessage
        if s == .unavailable {
            subtitle = CalibrationMessage(key: "map_state_unavailable")
        } else if let draftPoints, e.kind == .pdf {
            subtitle = CalibrationMessage(key: "map_state_draft", args: ["points": .number(draftPoints)])
        } else {
            switch s {
            case .geoPDF: subtitle = CalibrationMessage(key: "map_state_geopdf")
            case .calibrated:
                let m = pdf?.manual
                if let rms = m?.rmsM {
                    subtitle = CalibrationMessage(key: "map_state_calibrated", args: ["points": .number(m?.n ?? 0), "rms": .metres(rms)])
                } else {
                    subtitle = CalibrationMessage(key: "map_state_calibrated_exact", args: ["points": .number(m?.n ?? 0)])
                }
            case .rejected: subtitle = CalibrationMessage(key: "map_state_rejected")
            case .needsCalibration: subtitle = CalibrationMessage(key: "map_state_needs_calibration")
            case .offlineTiles: subtitle = CalibrationMessage(key: "map_state_offline_tiles")
            case .derived: subtitle = CalibrationMessage(key: "map_state_derived", args: ["name": .text(parentName ?? "")])
            case .unavailable: subtitle = CalibrationMessage(key: "map_state_unavailable")
            }
        }
        let tap: ImportedMapRowTap = s == .unavailable ? .none : (can ? .activate : .calibrate)
        var menu: [ImportedMapMenuAction] = []
        if e.kind == .pdf, s != .unavailable, let pdf {
            menu.append(.calibrate)
            if pdf.pageCount > 1 { menu.append(.choosePage) }
            if effective != nil { menu.append(.generateOfflineTiles) }
            if pdf.manual != nil && pdf.embedded != nil { menu.append(.useEmbedded) }
        }
        menu.append(.delete)
        return ImportedMapPresentation(state: s, effectiveGeoref: effective, canBeDurableActive: can,
                                       subtitle: subtitle, rowTap: tap, menu: menu)
    }
}

// MARK: - pure transitions

enum LibraryTransition {
    case selectOnline(BasemapStyle)
    case activateEntry(UUID)
    case addEntry(ImportedMapEntry, activate: Bool)
    case commitCalibration(UUID, ManualCalibration, contentKey: String, pageIndex: Int)
    case revertToEmbedded(UUID)
    /// P1 "Choose page...": new page, its embedded georef/issue, manual dropped
    case changePage(UUID, pageIndex: Int, rotate: Int, pageBox: [PdfPagePoint],
                    embedded: PdfGeoreference?, embeddedIssue: String?)
    /// WP2 bake publish: the record lands on its PDF only if that entry is still
    /// the same bytes + token + georef the bake was made from (S1). The active
    /// map doesn't change
    case attachBake(UUID, PDFBakeRecord, contentKey: String?, renderGuardToken: String)
    /// Remove Offline Tiles: clear the record, only if it's still this one
    case removeBake(UUID, PDFBakeRecord)
    /// OD-F4: the file came back and hashed to its content key, take its new
    /// size + mtime so the row stops saying unavailable
    case refreshFileStamp(UUID, byteCount: Int64, modifiedAtMs: Int64)
    /// E9: a re-import of an unavailable entry has its exact bytes, point the
    /// entry at the new copy. calibration + bake record stay, reconcile reaps the old file
    case relink(UUID, fileName: String, byteCount: Int64, modifiedAtMs: Int64)
    case deleteEntry(UUID)
}

enum LibraryTransitionError: Error, Equatable {
    case unknownEntry
    case notActivatable
    case targetMismatch
    case libraryFull
    /// the entry isn't the map the bake was made from any more
    case bakeSourceChanged
    /// a bake record we wouldn't have written
    case invalidBake
}

/// Builds the candidate state for one transition. Unit tested, no IO.
enum LibraryReducer {
    static func apply(_ t: LibraryTransition, to s: LibraryState) throws -> (state: LibraryState, removed: [ImportedMapEntry]) {
        var out = s
        switch t {
        case .selectOnline(let style):
            out.active = .online(style)
            out.preferredOnlineStyle = style.rawValue
            return (out, [])
        case .activateEntry(let id):
            guard let e = s.entry(id) else { throw LibraryTransitionError.unknownEntry }
            guard ImportedMapStates.canBeDurableActive(ImportedMapStates.state(e, file: .ok)) else {
                throw LibraryTransitionError.notActivatable
            }
            out.active = .entry(id)
            return (out, [])
        case let .addEntry(e, activate):
            guard s.entries.count < ImportLimits.maxLibraryEntries || e.derivedFromId != nil else {
                throw LibraryTransitionError.libraryFull
            }
            out.entries.append(e)
            if activate { out.active = .entry(e.id) }
            return (out, [])
        case let .commitCalibration(id, manual, contentKey, pageIndex):
            guard let i = out.entries.firstIndex(where: { $0.id == id }) else { throw LibraryTransitionError.unknownEntry }
            // never apply a calibration to another file or page (D5-10)
            guard out.entries[i].contentKey == contentKey, out.entries[i].pdf?.pageIndex == pageIndex else {
                throw LibraryTransitionError.targetMismatch
            }
            out.entries[i].pdf?.manual = manual
            // baked tiles belong to the old georef
            out.entries[i].pdf?.bake = nil
            out.active = .entry(id)
            return (out, [])
        case .revertToEmbedded(let id):
            guard let i = out.entries.firstIndex(where: { $0.id == id }) else { throw LibraryTransitionError.unknownEntry }
            out.entries[i].pdf?.manual = nil
            out.entries[i].pdf?.bake = nil
            if out.activeEntryID == id, out.entries[i].pdf?.embedded == nil {
                out.active = .online(s.preferredStyle)
            }
            return (out, [])
        case let .changePage(id, pageIndex, rotate, pageBox, embedded, issue):
            guard let i = out.entries.firstIndex(where: { $0.id == id }), out.entries[i].pdf != nil else {
                throw LibraryTransitionError.unknownEntry
            }
            out.entries[i].pdf?.pageIndex = pageIndex
            out.entries[i].pdf?.rotate = rotate
            out.entries[i].pdf?.pageBox = pageBox
            out.entries[i].pdf?.embedded = embedded
            out.entries[i].pdf?.embeddedIssue = issue
            out.entries[i].pdf?.manual = nil
            out.entries[i].pdf?.bake = nil
            if out.activeEntryID == id, embedded == nil { out.active = .online(s.preferredStyle) }
            return (out, [])
        case let .attachBake(id, record, contentKey, token):
            // D9, the M3 order: entry, then bytes + token, then the record itself,
            // then the georef it was baked from
            guard let i = out.entries.firstIndex(where: { $0.id == id }), let pdf = out.entries[i].pdf,
                  out.entries[i].contentKey == contentKey, out.entries[i].renderGuardToken == token else {
                throw LibraryTransitionError.bakeSourceChanged
            }
            guard record.isValidRecord, ManagedImportedMapFileLifecycle.isGeneratedBakeName(record.fileName) else {
                throw LibraryTransitionError.invalidBake
            }
            guard let g = pdf.effectiveGeoref,
                  PDFRenderContext.bakeKey(georef: g, tilePx: record.tilePx) == record.bakeKey else {
                throw LibraryTransitionError.bakeSourceChanged
            }
            out.entries[i].pdf?.bake = record
            return (out, [])
        case let .removeBake(id, record):
            // D9: no entry, or its record isnt this file any more = source changed, nothing written
            guard let i = out.entries.firstIndex(where: { $0.id == id }),
                  let current = out.entries[i].pdf?.bake, current.fileName == record.fileName else {
                throw LibraryTransitionError.bakeSourceChanged
            }
            out.entries[i].pdf?.bake = nil
            return (out, [])
        case let .refreshFileStamp(id, bytes, mtime):
            guard let i = out.entries.firstIndex(where: { $0.id == id }) else { throw LibraryTransitionError.unknownEntry }
            out.entries[i].byteCount = bytes
            out.entries[i].fileModifiedAtMs = mtime
            return (out, [])
        case let .relink(id, fileName, bytes, mtime):
            guard let i = out.entries.firstIndex(where: { $0.id == id }),
                  ImportedMapStorage.resolveManaged(relativePath: fileName) != nil else {
                throw LibraryTransitionError.unknownEntry
            }
            out.entries[i].fileName = fileName
            out.entries[i].byteCount = bytes
            out.entries[i].fileModifiedAtMs = mtime
            return (out, [])
        case .deleteEntry(let id):
            guard s.entry(id) != nil else { throw LibraryTransitionError.unknownEntry }
            let removed = s.entries.filter { $0.id == id || $0.derivedFromId == id }
            let ids = Set(removed.map(\.id))
            out.entries.removeAll { ids.contains($0.id) }
            if let a = s.activeEntryID, ids.contains(a) { out.active = .online(s.preferredStyle) }
            return (out, removed)
        }
    }
}

// MARK: - storage

/// Files of an import or bake that are in flight (.partial, not yet committed).
/// Reconcile never touches these.
enum InFlightImportFiles {
    private static let lock = NSLock()
    private static var urls = Set<String>()

    static func register(_ url: URL) {
        lock.lock(); defer { lock.unlock() }
        urls.insert(url.standardizedFileURL.path)
    }

    static func unregister(_ url: URL) {
        lock.lock(); defer { lock.unlock() }
        urls.remove(url.standardizedFileURL.path)
    }

    static var snapshot: Set<URL> {
        lock.lock(); defer { lock.unlock() }
        return Set(urls.map { URL(fileURLWithPath: $0) })
    }
}

enum ImportedMapLibrary {
    static let label = "map_library/v1"

    static var storageURLProvider: () -> URL = {
        ImportedMapStorage.applicationSupportDirectory().appendingPathComponent("imported-map-library.json")
    }

    /// S1/D1: a library that was quarantined, or that this device wrote before
    /// and is gone now, is corrupt, never empty. Empty means never written, so
    /// a fresh LibraryState (and the reconcile it drives) only ever follows a
    /// genuine first launch
    static func load() -> SafeStore.Load<LibraryState> {
        let url = storageURLProvider()
        let r = SafeStore.read(url, label: label) { data in
            let s = try JSONDecoder().decode(LibraryState.self, from: data)
            guard s.schemaVersion == LibraryState.currentSchema else {
                throw DecodingError.dataCorrupted(.init(codingPath: [], debugDescription: "newer library schema"))
            }
            return s
        }
        guard case .empty = r else { return r }
        return SafeStore.absentStoreStatus(url, label: label) ?? .empty
    }

    static func write(_ s: LibraryState) throws {
        let url = storageURLProvider()
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try SafeStore.write(JSONEncoder().encode(s), to: url, label: label)
    }

    /// S2 rebuild write. If unreadable bytes are still sitting at the path (the
    /// quarantine move failed) they go aside first, never overwritten
    static func writeRebuilt(_ s: LibraryState) throws {
        let url = storageURLProvider()
        let fm = FileManager.default
        if fm.fileExists(atPath: url.path) {
            let aside = url.deletingLastPathComponent()
                .appendingPathComponent(url.lastPathComponent + ".corrupt-\(Int(Date().timeIntervalSince1970))-r")
            try fm.moveItem(at: url, to: aside)
        }
        try write(s)
    }

    static func exists() -> Bool {
        FileManager.default.fileExists(atPath: storageURLProvider().path)
    }

    /// Absolute URL of an entry's file, nil for anything that tries to leave
    /// the two managed directories.
    static func fileURL(_ e: ImportedMapEntry) -> URL? {
        ImportedMapStorage.resolveManaged(relativePath: e.fileName)
    }

    /// size + mtime only, cheap enough for the main thread (D5-08)
    static func fileStatus(_ e: ImportedMapEntry) -> ImportedMapFileStatus {
        guard let url = fileURL(e),
              let v = try? url.resourceValues(forKeys: [.fileSizeKey, .contentModificationDateKey, .isRegularFileKey]),
              v.isRegularFile == true else { return .missing }
        let size = Int64(v.fileSize ?? -1)
        let mtime = v.contentModificationDate.map { Int64(($0.timeIntervalSince1970 * 1000).rounded()) } ?? -1
        return size == e.byteCount && abs(mtime - e.fileModifiedAtMs) <= 1 ? .ok : .sizeOrMtimeMismatch
    }

    /// Every file the library vouches for, sidecars included. A PDF's valid
    /// bake rides along with it (WP2), no record = reaped.
    static func managedFiles(_ s: LibraryState) -> Set<URL> {
        var out = Set<URL>()
        for e in s.entries {
            guard let url = fileURL(e) else { continue }
            out.insert(url)
        }
        for name in bakeFileNames(s) {
            out.insert(offlineTilesDirectory.appendingPathComponent(name))
        }
        return out
    }

    static var offlineTilesDirectory: URL {
        ImportedMapStorage.applicationSupportDirectory().appendingPathComponent("offline_tiles", isDirectory: true)
    }

    /// names of every valid bake the library points at (R3-2 sweep keep set)
    static func bakeFileNames(_ s: LibraryState) -> Set<String> {
        Set(s.entries.compactMap { $0.pdf?.validBake?.fileName }
            .filter(ManagedImportedMapFileLifecycle.isGeneratedBakeName))
    }

    /// Delete map files in ImportedMaps/ and offline_tiles/ that no entry (and no
    /// in-flight import) owns. Only ever run on a Loaded library. Under the
    /// managed files lock the bake publish and Remove take (R2-S2)
    @discardableResult
    static func reconcile(_ s: LibraryState, inFlight: Set<URL> = InFlightImportFiles.snapshot) -> Bool {
        guard s.permitsCleanup else { return true }
        return ManagedImportedMapFileLifecycle.withManagedFilesLock {
            let fm = FileManager.default
            let keep = managedFiles(s).filter { fm.fileExists(atPath: $0.path) }
            guard let imported = try? ImportedMapStorage.importedMapsDirectory() else { return false }
            return ManagedImportedMapFileLifecycle.reconcile(directories: [imported, offlineTilesDirectory],
                                                             keeping: keep, inFlight: inFlight)
        }
    }

    /// OD-F4 re-check for a flagged (or retried) PDF: true once the file at its
    /// url is back inside ImportedMaps, a plain file, and hashes to its content
    /// key. Off main, it hashes (memoised on the file stamp)
    static func storedFileMatches(_ source: PDFMapSource) -> Bool {
        guard let key = source.contentKey, source.url.isFileURL,
              let dir = try? ImportedMapStorage.importedMapsDirectory(),
              source.url.deletingLastPathComponent().standardizedFileURL == dir.standardizedFileURL,
              let v = try? source.url.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey]),
              v.isRegularFile == true, v.isSymbolicLink != true else { return false }
        return PDFSessionStore.contentKey(for: source.url) == key
    }

    /// what the sweep may trust: the names of a Loaded library, nothing at all
    /// for an empty one with no legacy stores left and no map files sitting in
    /// the managed dirs (3.0.1 L7, those get adopted), else unreadable = skip
    enum BakeAuthority: Equatable {
        case read(Set<String>)
        case unreadable
    }

    static func bakeAuthority(_ load: SafeStore.Load<LibraryState>, legacyPresent: Bool,
                              managedFiles: Bool = false) -> BakeAuthority {
        switch load {
        case .loaded(let s): return s.permitsCleanup ? .read(bakeFileNames(s)) : .unreadable
        case .empty: return legacyPresent || managedFiles ? .unreadable : .read([])
        case .locked, .corrupt: return .unreadable
        }
    }
}
