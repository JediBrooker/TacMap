import Foundation
import CoreGraphics

/// why a legacy read is uncertain (contract s13.1 L3, libraryLoad.legacyCodes).
/// mbtilesUnconvertible is ours, the fixture folds it into the PDF one
enum LegacyMigrationCause: String, Sendable {
    case corrupt, quarantinedOnly, sessionInvalid, pdfHashMismatch, pdfUnconvertible, v1PointsUnrebuildable
    case mbtilesUnconvertible
}

/// One time move from the two old stores (ActiveMapSelectionStore's
/// active/retained descriptor + PDFSessionStore's sealed session and per-file
/// calibration library) into the one sealed ImportedMapLibrary (contract s8.2).
///
/// Fail closed and idempotent (s13.1 L6):
/// 1. files are hard linked to their opaque names (copied if that fails),
/// 2. every migration draft is saved, each one has to land,
/// 3. the library is written (the single commit point),
/// 4. only then are the legacy stores cleared and the old names unlinked.
/// A failure in 2 or 3 unlinks this attempt's links and clears nothing, unless
/// 3 threw after the library already hit the disk: then the links it names stay
/// and the restore takes it from there like after a crash past 3. A crash
/// before 3 just redoes it (drafts are keyed by file + page so they get
/// overwritten), a crash after 3 leaves stale legacy bytes the next load clears.
/// Not clean though: links are made as files get inspected, before 3. A salvage
/// killed in there leaves map-<uuid> links behind. The redo's adopt keeps one
/// name per inode (3.0.3), so those aren't listed again as "Recovered map"
/// rows, and once 3 lands 4 unlinks them with the old names. Only a copy (link
/// failed) still gets adopted twice. A kill between 3 and 4 leaves the 2.x name
/// as a second hard link nothing lists (a flagged library never reconciles it
/// away). Nothing is lost either way.
/// A locked key migrates nothing and deletes nothing. Legacy state the key can
/// open but we can't fully read or convert gets salvaged (L5): what converts is
/// written, every other map file adopted, the old stores left frozen, and the
/// library flagged so nothing ever cleans up around it. Locked forever isn't an
/// outcome any more (wp4-ios-1).
enum ImportedMapLibraryMigration {

    enum Result: Equatable {
        /// the library already exists (or there was never anything to move)
        case notNeeded
        /// key locked, or a draft / library write failed: nothing cleared or
        /// deleted, Retry and unlock run it again. Nothing written either, except a
        /// library write that threw after landing (its links stay, see commit)
        case blocked
        case migrated(uncalibratedName: String?)
        /// L5: uncertain legacy read, written with recoveryPreservesOrphans, old
        /// stores and every .corrupt-* copy left alone. Show the recovered notice
        case salvaged
        /// L7: no legacy at all but map files sitting in the managed dirs, adopted
        /// the same way. Same notice
        case adoptedOrphans
    }

    /// Run with the library still Empty. Off the main thread: the v1 session
    /// path can re-parse a PDF and salvage / adoption hash and inspect every file.
    static func migrateIfNeeded(now: Date = Date(),
                                drafts draftStore: CalibrationDraftStoring = CalibrationDraftStore.shared,
                                write: (LibraryState) throws -> Void = ImportedMapLibrary.write,
                                inFlight: Set<URL> = InFlightImportFiles.snapshot,
                                inspect: (URL) -> PDFInspection? = ImportedMapLibraryRecovery.inspectForRecovery) -> Result {
        switch ImportedMapLibrary.load() {
        case .empty: break
        // no key, can't even tell whether there's a library: nothing moves yet
        case .locked: return .blocked
        case .loaded, .corrupt: return .notNeeded
        }
        let snapshot = ActiveMapSelectionStore.legacySnapshot()
        let sessionPresent = PDFSessionStore.hasStoredSession
        if snapshot == .none && !sessionPresent {
            // L7: nothing to migrate, but an Empty library next to map files can't
            // be the authoritative first launch, the reconcile would eat them
            guard ImportedMapLibraryRecovery.managedFilesPresent(inFlight: inFlight) else { return .notNeeded }
            return adoptOrphans(now: now, draftStore: draftStore, write: write, inFlight: inFlight, inspect: inspect)
        }
        // no key, no migration: the legacy bytes stay exactly as they are
        guard (try? SafeStore.keyProvider()) != nil else { return .blocked }

        var causes: [LegacyMigrationCause] = []
        var active: ActiveMapSelectionStore.LegacySelection?
        var retained: ActiveMapSelectionStore.LegacySelection?
        switch snapshot {
        case .locked: return .blocked
        case .uncertain(let cause): causes.append(cause)
        case let .loaded(a, r): active = a; retained = r
        case .none: break
        }

        var state = LibraryState()
        if case .online(let style)? = active {
            state.preferredOnlineStyle = style.rawValue
            state.active = .online(style)
        } else if let style = ActiveMapSelectionStore.preferredOnlineStyle() {
            // a WP2 build kept the last online pick next to an active imported map (H1)
            state.preferredOnlineStyle = style.rawValue
        }
        var links: [(old: URL, new: URL)] = []
        var uncalibratedName: String?
        let nowMs = Int64(now.timeIntervalSince1970 * 1000)

        // the PDF session, whichever descriptor pointed at it (or none did, v1).
        // Only a file that's really gone is dropped. Anything on disk we can't
        // read, hash, link or open makes the read uncertain: it isn't converted,
        // the record stays frozen and its file gets adopted by the salvage below
        var pdfEntry: ImportedMapEntry?
        var drafts: [CalibrationDraft] = []
        if sessionPresent {
            switch PDFSessionStore.migrationRead() {
            case .none, .gone:
                break
            case .locked:
                return .blocked
            case .uncertain(let cause):
                causes.append(cause)
            case let .source(source, parked):
                // wp4-ios-8: the map still converts, but its v1 points are only in
                // pdf_calibrations_v1 now, which a plain run would clear
                if parked { causes.append(.v1PointsUnrebuildable) }
                guard FileManager.default.fileExists(atPath: source.url.path) else { break }
                if let made = Self.pdfEntry(from: source, nowMs: nowMs) {
                    pdfEntry = made.entry
                    links.append(made.link)
                    if let draft = made.draft { drafts.append(draft) }
                } else {
                    causes.append(.pdfUnconvertible)
                }
            }
        }
        if let e = pdfEntry {
            state.entries.append(e)
            let wasActive = active == .pdf
            let st = ImportedMapStates.state(e, file: .ok)
            if wasActive {
                if ImportedMapStates.canBeDurableActive(st) {
                    state.active = .entry(e.id)
                } else {
                    // never a durable basemap without a georef, say so once
                    state.active = .online(state.preferredStyle)
                    uncalibratedName = e.displayName
                }
            }
        }

        // the retained/active MBTiles
        let tilesURL: URL? = {
            for s in [retained, active] {
                if case .offlineTiles(let url?)? = s { return url }
            }
            return nil
        }()
        if let url = tilesURL {
            // resolved means it's there. a link/copy that fails leaves it to the
            // salvage, which adopts it where it is
            if let made = Self.mbtilesEntry(url, nowMs: nowMs) {
                state.entries.append(made.entry)
                if let link = made.link { links.append(link) }
                if case .offlineTiles? = active { state.active = .entry(made.entry.id) }
            } else {
                causes.append(.mbtilesUnconvertible)
            }
        }
        if state.active == nil { state.active = .online(state.preferredStyle) }
        guard causes.isEmpty else {
            NSLog("[LibraryMigration] legacy read uncertain (\(causes.map(\.rawValue).joined(separator: ","))), salvaging")
            // L5: keep what converted, adopt every other map file, flag it, clear nothing.
            // the recovered notice replaces the uncalibrated one
            state.recoveryPreservesOrphans = true
            // not twice: what a converted entry points at (a bake-dir MBTiles stays
            // where it is, no link) and the old names of converted links
            let converted = Set(links.flatMap { [$0.old, $0.new] } + state.entries.compactMap(ImportedMapLibrary.fileURL))
            let adopted = ImportedMapLibraryRecovery.adopt(excluding: converted, inFlight: inFlight, now: now, inspect: inspect)
            state.entries += adopted.entries
            return commit(state, links: links + adopted.links, extraNames: adopted.extraNames, drafts: drafts,
                          draftStore: draftStore, write: write, clearLegacyStores: false) ? .salvaged : .blocked
        }
        return commit(state, links: links, drafts: drafts, draftStore: draftStore, write: write, clearLegacyStores: true)
            ? .migrated(uncalibratedName: uncalibratedName) : .blocked
    }

    /// L7: the S2 rebuild over the managed dirs, written once with the flag
    private static func adoptOrphans(now: Date, draftStore: CalibrationDraftStoring,
                                     write: (LibraryState) throws -> Void, inFlight: Set<URL>,
                                     inspect: (URL) -> PDFInspection?) -> Result {
        var state = LibraryState()
        state.recoveryPreservesOrphans = true
        state.active = .online(OnlineRasterBasemapSource.defaultStyle)
        let adopted = ImportedMapLibraryRecovery.adopt(excluding: [], inFlight: inFlight, now: now, inspect: inspect)
        state.entries = adopted.entries
        return commit(state, links: adopted.links, extraNames: adopted.extraNames, drafts: [], draftStore: draftStore,
                      write: write, clearLegacyStores: false) ? .adoptedOrphans : .blocked
    }

    /// L6: drafts, then the one library write, then (plain runs only) clear the
    /// old stores, then unlink the old names. false = blocked, nothing was
    /// cleared and no old name unlinked. This attempt's links go again unless
    /// the library landed anyway (see below). extraNames are second hard links
    /// to bytes the state lists under another name (adopt), dropped with the
    /// old names and only after the write
    private static func commit(_ state: LibraryState, links: [(old: URL, new: URL)], extraNames: [URL] = [],
                               drafts: [CalibrationDraft],
                               draftStore: CalibrationDraftStoring, write: (LibraryState) throws -> Void,
                               clearLegacyStores: Bool) -> Bool {
        let moved = links.filter { $0.old.standardizedFileURL != $0.new.standardizedFileURL }
        do {
            // wp4-android-8 on iOS too: a draft that didnt save must not be followed
            // by a clear. keyed by file + page, so a retry just overwrites them
            for d in drafts { try draftStore.save(d) }
            try write(state)
        } catch {
            // F1: SafeStore.write puts the sealed bytes down before the keychain
            // sealed-only record, so a throw from there leaves the library on disk.
            // it names the new links, so they stay. pull them and the restore right
            // after loads it, finds no entry files and reconciles away the 2.x
            // names, the last copy. load() said empty before, so a file now is ours
            if ImportedMapLibrary.exists() {
                NSLog("[LibraryMigration] library write threw after it landed, links kept, nothing cleared")
                return false
            }
            NSLog("[LibraryMigration] migration write failed, nothing cleared")
            for l in moved { ImportedMapStorage.unlink(l.new) }
            return false
        }
        if clearLegacyStores { clearLegacy() }
        for l in moved { ImportedMapStorage.unlink(l.old) }
        for u in extraNames { ImportedMapStorage.unlink(u) }
        return true
    }

    /// after the library is durable the old stores are dead weight
    static func clearLegacy() {
        ActiveMapSelectionStore.removeLegacyStore()
        PDFSessionStore.clear()
        PDFSessionStore.clearLibrary()
    }

    /// true while any of the old stores still has bytes (a crash after the write),
    /// or the old selector got quarantined: that's never "no legacy left" (S1)
    static var legacyPresent: Bool {
        clearableLegacyPresent || ActiveMapSelectionStore.legacyStoreQuarantined
    }

    /// L2: what D8 may clear, the live stores only. a .corrupt-* copy never counts
    static var clearableLegacyPresent: Bool {
        ActiveMapSelectionStore.legacyStoreExists || PDFSessionStore.hasStoredSession
    }

    /// the launch hop has work: legacy to move, or map files with no library (L7)
    static var pending: Bool {
        legacyPresent || ImportedMapLibraryRecovery.managedFilesPresent()
    }

    // MARK: - entries

    /// hard link to the opaque name, copy when the link isn't possible
    static func linkOpaque(_ old: URL, ext: String) -> URL? {
        guard let new = try? ImportedMapStorage.opaqueDestination(id: UUID(), ext: ext) else { return nil }
        do {
            try FileManager.default.linkItem(at: old, to: new)
        } catch {
            do { try FileManager.default.copyItem(at: old, to: new) } catch { return nil }
        }
        ImportedMapStorage.protectAndExclude(new)
        return new
    }

    private static func fileStats(_ url: URL) -> (bytes: Int64, mtime: Int64) {
        let v = try? url.resourceValues(forKeys: [.fileSizeKey])
        return (Int64(v?.fileSize ?? 0), ImportedMapStorage.modifiedAtMs(url))
    }

    static func pdfEntry(from source: PDFMapSource, nowMs: Int64)
        -> (entry: ImportedMapEntry, link: (old: URL, new: URL), draft: CalibrationDraft?)? {
        guard let contentKey = PDFSessionStore.contentKey(for: source.url),
              source.contentKey == nil || source.contentKey == contentKey,
              let new = linkOpaque(source.url, ext: "pdf") else { return nil }
        let pageIndex = source.georef.page
        // blocked means we come back next launch, dont leave a copy per attempt
        guard let rel = ImportedMapStorage.relativePath(for: new),
              let doc = CGPDFDocument(new as CFURL), let page = doc.page(at: pageIndex + 1) else {
            ImportedMapStorage.unlink(new)
            return nil
        }
        let geometry = GeoPDFReader.pageGeometry(page)
        let pageBox = CalibrationTarget.box(geometry.cropBox)
        var info = ImportedMapEntry.PDFInfo(pageCount: doc.numberOfPages, pageIndex: pageIndex, rotate: geometry.rotation,
                                            pageBox: pageBox, embedded: nil, embeddedIssue: nil, manual: nil)
        // a WP2 session's crash guard token comes along (a standing suspect stays
        // one), its bake record doesn't: tiles can be made again, the sweep reaps them
        info.renderGuardToken = source.renderGuardToken
        switch source.georef.origin {
        case .adobeVP, .lgiDict:
            info.embedded = source.georef
        case .fiduciaries, .provisional:
            // what the PDF itself says, the session only kept the fit
            if let readout = GeoPDFReader.read(document: doc, pageIndex: pageIndex) {
                info.embedded = readout.georef
                if case .rejected(let r) = readout.outcome { info.embeddedIssue = r.rawValue }
            }
        }
        let fids = source.fiduciaries ?? (source.pendingFiduciaries.isEmpty ? nil : source.pendingFiduciaries)
        var draft: CalibrationDraft?
        let entryID = UUID()
        if let fids, !fids.isEmpty {
            // stored lat/lon are WGS84 (datum shifted at entry), typed text kept as input
            let points = fids.enumerated().map { i, f in
                CalibrationPoint(number: i + 1, page: PdfPagePoint(x: f.pdfX, y: f.pdfY), input: f.mgrs,
                                 reference: .geographic(latitude: f.latitude, longitude: f.longitude),
                                 kind: .intersection, datumOverride: "WGS84", label: f.label)
            }
            let state = CalibrationState(datumID: "WGS84", points: points)
            let report = CalibrationFitEvaluator.evaluate(state, pageBox: pageBox, rotate: geometry.rotation, pageIndex: pageIndex)
            if source.georef.origin == .fiduciaries, let g = report.georef {
                info.manual = ManualCalibration(datumId: "WGS84", points: state.points, nextNumber: state.nextNumber,
                                                georef: g, n: report.n, rmsM: report.n >= 4 ? report.rmsM : nil,
                                                grade: report.grade, savedAtMs: nowMs)
            } else {
                // refused (collinear etc) or never a calibration: park them so the
                // next calibration opens with them placed
                draft = CalibrationDraft(contentKey: contentKey, pageIndex: pageIndex, entryId: entryID, datumId: "WGS84",
                                         points: state.points, nextNumber: state.nextNumber, pending: nil,
                                         active: false, updatedAtMs: nowMs)
            }
        }
        let stats = fileStats(new)
        let name = source.url.deletingPathExtension().lastPathComponent
        let entry = ImportedMapEntry(id: entryID, kind: .pdf, fileName: rel, displayName: name.isEmpty ? Messages.mapLibrarySection() : name,
                                     contentKey: contentKey, byteCount: stats.bytes, fileModifiedAtMs: stats.mtime,
                                     importedAtMs: nowMs, derivedFromId: nil, pdf: info)
        return (entry, (source.url, new), draft)
    }

    static func mbtilesEntry(_ url: URL, nowMs: Int64) -> (entry: ImportedMapEntry, link: (old: URL, new: URL)?)? {
        let inTiles = url.deletingLastPathComponent().lastPathComponent == ImportedMapStorage.tilesDirectoryName
        // name first, on the 2.x file itself. Linking before it meant dying in
        // the open left a map-<uuid> link to the same bytes, which the salvage
        // then adopted and opened again
        let name = legacyPackName(url)
        let target: URL
        var link: (old: URL, new: URL)?
        if inTiles {
            // bakes already have opaque names (tacmap-<uuid>.mbtiles), leave them be
            target = url
        } else {
            guard let new = linkOpaque(url, ext: "mbtiles") else { return nil }
            target = new
            link = (url, new)
        }
        guard let rel = ImportedMapStorage.relativePath(for: target) else {
            if link != nil { ImportedMapStorage.unlink(target) }
            return nil
        }
        let stats = fileStats(target)
        return (ImportedMapEntry(id: UUID(), kind: .mbtiles, fileName: rel, displayName: name, contentKey: nil,
                                 byteCount: stats.bytes, fileModifiedAtMs: stats.mtime, importedAtMs: nowMs,
                                 derivedFromId: nil, pdf: nil), link)
    }

    /// 3.0.2: the pack's own name, read under the rebuild marker keyed on the
    /// 2.x name. Any marker already down means some pass died opening a file:
    /// this one last time (dont open it again), or a pack the salvage's adopt
    /// still has to skip. Either way the stem it is and the marker stays put,
    /// adopt reads it and takes it off. Overwriting it here used to wipe that
    /// record and the salvage reopened the pack it died on, every launch
    private static func legacyPackName(_ url: URL) -> String {
        let stem = url.deletingPathExtension().lastPathComponent
        let marker = ImportedMapLibraryRecovery.markerURL
        if let marker, FileManager.default.fileExists(atPath: marker.path) { return stem }
        if let marker {
            try? Data(url.lastPathComponent.utf8).write(
                to: marker, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        }
        defer { if let marker { try? FileManager.default.removeItem(at: marker) } }
        guard let store = MBTilesStore(url: url) else { return stem }
        defer { store.closeForDeletion() }
        return store.metadata.name ?? stem
    }
}

/// S2: Retry on a corrupt library rebuilds the list from the map files that
/// are actually on disk. It never deletes a user file: every opaque map file
/// nothing in flight owns gets an entry again (bakes excepted, they're derived
/// and the sweep has them once the new library is loaded). Names and
/// calibrations were only in the lost library, so they can't come back.
/// The 3.0.1 migration salvage and orphan adoption use the same rules (adopt).
/// Off the main thread, it hashes and inspects.
enum ImportedMapLibraryRecovery {
    /// the rebuild's own crash breaker. Unlike the import marker it never
    /// deletes the file it names, that one just gets adopted unread next time
    static let markerName = ".rebuild-inspecting"

    static var markerURL: URL? {
        try? ImportedMapStorage.importedMapsDirectory().appendingPathComponent(markerName)
    }

    /// every map file a reconcile would have deleted, oldest first. Not the one
    /// a pending s9.8 marker names, recoverInterruptedImport removes that
    static func candidates(inFlight: Set<URL>, fileManager fm: FileManager = .default) -> [URL] {
        var dirs: [URL] = []
        if let d = try? ImportedMapStorage.importedMapsDirectory() { dirs.append(d) }
        dirs.append(ImportedMapLibrary.offlineTilesDirectory)
        let busy = Set(inFlight.union(MapImportPipeline.interruptedImportFiles())
            .map { $0.standardizedFileURL.resolvingSymlinksInPath().path })
        let keys: [URLResourceKey] = [.isRegularFileKey, .isSymbolicLinkKey, .contentModificationDateKey]
        var found: [(url: URL, mtime: Date)] = []
        for dir in dirs {
            let kids = (try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: keys,
                                                    options: [.skipsSubdirectoryDescendants, .skipsHiddenFiles])) ?? []
            for u in kids {
                let name = u.lastPathComponent, lower = name.lowercased()
                guard lower.hasSuffix(".pdf") || lower.hasSuffix(".mbtiles"),
                      !ManagedImportedMapFileLifecycle.isGeneratedBakeName(name),
                      !busy.contains(u.standardizedFileURL.resolvingSymlinksInPath().path),
                      let v = try? u.resourceValues(forKeys: Set(keys)), v.isRegularFile == true, v.isSymbolicLink != true,
                      ImportedMapStorage.relativePath(for: u) != nil else { continue }
                found.append((u, v.contentModificationDate ?? .distantPast))
            }
        }
        return found.sorted { $0.mtime != $1.mtime ? $0.mtime < $1.mtime : $0.url.lastPathComponent < $1.url.lastPathComponent }
            .map(\.url)
    }

    /// s9.5 inspection with the import watchdog, what rebuild and salvage use
    static let inspectForRecovery: (URL) -> PDFInspection? = { url in
        try? MapImportPipeline.inspectWithWatchdog(url, isCancelled: { false }, progress: { _, _ in })
    }

    /// the candidate library, not written. The caller does the one write
    static func rebuild(inFlight: Set<URL> = InFlightImportFiles.snapshot, now: Date = Date(),
                        inspect: (URL) -> PDFInspection? = inspectForRecovery) -> LibraryState {
        let style = OnlineRasterBasemapSource.defaultStyle
        var s = LibraryState()
        s.recoveryPreservesOrphans = true
        s.preferredOnlineStyle = style.rawValue
        s.active = .online(style)
        s.entries = entries(for: candidates(inFlight: inFlight), now: now, inspect: inspect, linkLegacyNames: false).entries
        return s
    }

    /// 3.0.1 L5 step 3-4 and L7: the same adoption for salvage and orphans,
    /// minus the files a converted entry already owns. A file still under a 2.x
    /// name keeps its stem as the name and gets linked to an opaque one like a
    /// migrated file (adopted where it is if neither link nor copy works). Not
    /// written: the caller saves, writes once, then unlinks the old names and
    /// extraNames
    static func adopt(excluding: Set<URL>, inFlight: Set<URL> = InFlightImportFiles.snapshot, now: Date = Date(),
                      inspect: (URL) -> PDFInspection? = inspectForRecovery)
        -> (entries: [ImportedMapEntry], links: [(old: URL, new: URL)], extraNames: [URL]) {
        let skip = Set(excluding.map { $0.standardizedFileURL.resolvingSymlinksInPath().path })
        let urls = candidates(inFlight: inFlight).filter { !skip.contains($0.standardizedFileURL.resolvingSymlinksInPath().path) }
        let split = oneNamePerFile(urls, taken: Set(excluding.compactMap(fileIdentity)))
        let made = entries(for: split.kept, now: now, inspect: inspect, linkLegacyNames: true)
        return (made.entries, made.links, split.extra)
    }

    /// device + inode, what every hard link to the same bytes shares
    struct FileIdentity: Hashable {
        let device: Int
        let inode: UInt64
    }

    static func fileIdentity(_ url: URL) -> FileIdentity? {
        // lstat, a symlink is never the file it points at
        guard let a = try? FileManager.default.attributesOfItem(atPath: url.path),
              let dev = (a[.systemNumber] as? NSNumber)?.intValue,
              let ino = (a[.systemFileNumber] as? NSNumber)?.uint64Value else { return nil }
        return FileIdentity(device: dev, inode: ino)
    }

    /// 3.0.3 A2: a salvage killed before its write leaves its map-<uuid> links
    /// behind, same inode as a file this pass converts (excluded, so taken) or
    /// a 2.x name it's about to link again. Adopting those listed the map twice.
    /// One name per inode survives, the 2.x one if there is one (it has the
    /// stem), the rest come back as extra names. No identity = adopted as before
    static func oneNamePerFile(_ urls: [URL], taken: Set<FileIdentity>) -> (kept: [URL], extra: [URL]) {
        let ids = urls.map(fileIdentity)
        var owner: [FileIdentity: Int] = [:]
        let legacyFirst = urls.indices.filter { legacyStem(urls[$0]) != nil } + urls.indices.filter { legacyStem(urls[$0]) == nil }
        for i in legacyFirst {
            guard let id = ids[i], !taken.contains(id), owner[id] == nil else { continue }
            owner[id] = i
        }
        var kept: [URL] = [], extra: [URL] = []
        for (i, url) in urls.enumerated() {
            if let id = ids[i], owner[id] != i { extra.append(url) } else { kept.append(url) }
        }
        return (kept, extra)
    }

    /// L7 managedFiles: after s9.8 a regular .pdf/.mbtiles in ImportedMaps or
    /// offline_tiles that nothing in flight owns. An Empty library next to one of
    /// those is never the authoritative first launch. .partial copies and lone
    /// sqlite sidecars don't count (s16.3, same as Android), the first launch's
    /// reconcile just deletes them. Doesn't make the dirs, just looks
    static func managedFilesPresent(inFlight: Set<URL> = InFlightImportFiles.snapshot,
                                    fileManager fm: FileManager = .default) -> Bool {
        let support = ImportedMapStorage.applicationSupportDirectory()
        let busy = Set(inFlight.union(MapImportPipeline.interruptedImportFiles())
            .map { $0.standardizedFileURL.resolvingSymlinksInPath().path })
        for name in [ImportedMapStorage.importedDirectoryName, ImportedMapStorage.tilesDirectoryName] {
            let dir = support.appendingPathComponent(name, isDirectory: true)
            let kids = (try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: [.isRegularFileKey, .isSymbolicLinkKey],
                                                    options: [.skipsSubdirectoryDescendants])) ?? []
            for u in kids where ManagedImportedMapFileLifecycle.isAuthoritativeMapName(u.lastPathComponent) {
                guard !busy.contains(u.standardizedFileURL.resolvingSymlinksInPath().path),
                      let v = try? u.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey]),
                      v.isRegularFile == true, v.isSymbolicLink != true else { continue }
                return true
            }
        }
        return false
    }

    /// ImportedMaps/map-<uuid>.<ext>, what every import and migration writes
    static func isOpaqueImportName(_ name: String) -> Bool {
        let lower = name.lowercased()
        guard lower.hasPrefix("map-") else { return false }
        return UUID(uuidString: String((lower as NSString).deletingPathExtension.dropFirst(4))) != nil
    }

    /// the stem of a file still under its 2.x name in ImportedMaps, nil for
    /// opaque names and anything in offline_tiles (those were always ours)
    private static func legacyStem(_ url: URL) -> String? {
        guard url.deletingLastPathComponent().lastPathComponent == ImportedMapStorage.importedDirectoryName,
              !isOpaqueImportName(url.lastPathComponent) else { return nil }
        let stem = url.deletingPathExtension().lastPathComponent
        return stem.isEmpty ? nil : stem
    }

    private static func entries(for urls: [URL], now: Date, inspect: (URL) -> PDFInspection?, linkLegacyNames: Bool)
        -> (entries: [ImportedMapEntry], links: [(old: URL, new: URL)]) {
        let nowMs = Int64(now.timeIntervalSince1970 * 1000)
        let marker = markerURL
        // the last rebuild died in the parser on this file, dont open it again
        let crashedOn = marker.flatMap { try? String(contentsOf: $0, encoding: .utf8) }?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        defer { if let marker { try? FileManager.default.removeItem(at: marker) } }
        var out: [ImportedMapEntry] = []
        var links: [(old: URL, new: URL)] = []
        var n = 0
        for url in urls {
            guard ImportedMapStorage.relativePath(for: url) != nil else { continue }
            let isPDF = url.pathExtension.lowercased() == "pdf"
            // a hash that won't come is still adopted (Delete only), never left for reconcile
            let key = PDFSessionStore.contentKey(for: url)
            var info: ImportedMapEntry.PDFInfo?
            var opens = true
            if isPDF {
                // pageCount 0 = couldnt read it, always unavailable
                var pdf = ImportedMapEntry.PDFInfo(pageCount: 0, pageIndex: 0, rotate: 0, pageBox: [], embedded: nil,
                                                   embeddedIssue: nil, manual: nil)
                pdf.renderGuardToken = UUID().uuidString
                if key != nil, url.lastPathComponent != crashedOn {
                    if let marker {
                        try? Data(url.lastPathComponent.utf8).write(to: marker, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
                    }
                    let inspection = inspect(url)
                    if let marker { try? FileManager.default.removeItem(at: marker) }
                    // s9.6 first valid page, no picker. Nothing valid = page 0
                    if let inspection {
                        let page = inspection.scanned.first { $0.georef != nil }?.index ?? 0
                        if let p = inspection.page(page) {
                            pdf.pageCount = inspection.pageCount
                            pdf.pageIndex = page
                            pdf.rotate = p.geometry.rotation
                            pdf.pageBox = CalibrationTarget.box(p.geometry.cropBox)
                            pdf.embedded = p.georef
                            pdf.embeddedIssue = p.issue?.rawValue
                        }
                    }
                }
                info = pdf
            } else if url.lastPathComponent == crashedOn {
                // 3.0.2: the last pass died opening this pack, adopt it unavailable unopened
                opens = false
            } else {
                if let marker {
                    try? Data(url.lastPathComponent.utf8).write(to: marker, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
                }
                let store = MBTilesStore(url: url)
                store?.closeForDeletion()
                if let marker { try? FileManager.default.removeItem(at: marker) }
                opens = store != nil
            }
            // linked only after the parse, so a crash in there never leaves a
            // second name for the same bytes
            var file = url
            let name: String
            if linkLegacyNames, let stem = legacyStem(url) {
                name = stem
                if let new = ImportedMapLibraryMigration.linkOpaque(url, ext: isPDF ? "pdf" : "mbtiles") {
                    links.append((url, new))
                    file = new
                }
            } else {
                n += 1
                name = Messages.mapRecoveredName(DisplayFormat.number(Double(n), decimals: 0))
            }
            guard let rel = ImportedMapStorage.relativePath(for: file) else { continue }
            let bytes = Int64((try? file.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0)
            // an MBTiles that won't open is still adopted, as unavailable: a stamp
            // that can never match the file
            let mtime = opens ? ImportedMapStorage.modifiedAtMs(file) : -1
            out.append(ImportedMapEntry(id: UUID(), kind: isPDF ? .pdf : .mbtiles, fileName: rel, displayName: name,
                                        contentKey: key, byteCount: bytes, fileModifiedAtMs: mtime, importedAtMs: nowMs,
                                        derivedFromId: nil, pdf: info))
        }
        return (out, links)
    }
}
