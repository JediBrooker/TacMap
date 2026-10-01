import Foundation
import CoreGraphics

/// One time move from the two old stores (ActiveMapSelectionStore's
/// active/retained descriptor + PDFSessionStore's sealed session and per-file
/// calibration library) into the one sealed ImportedMapLibrary (contract s8.2).
///
/// Fail closed and idempotent:
/// 1. files are hard linked to their opaque names (copied if that fails),
/// 2. the library is written (the single commit point),
/// 3. only then are the legacy stores cleared and the old names unlinked.
/// A crash before 2 just redoes it (the half made links are orphans reconcile
/// removes), a crash after 2 leaves stale legacy bytes that the next load clears.
/// Locked or unreadable legacy state migrates nothing and deletes nothing.
enum ImportedMapLibraryMigration {

    enum Result: Equatable {
        /// the library already exists (or there was never anything to move)
        case notNeeded
        /// key locked / legacy unreadable: try again after unlock, nothing touched
        case blocked
        case migrated(uncalibratedName: String?)
        case failed
    }

    /// Run with the library still Empty. Off the main thread: the v1 session
    /// path can re-parse a PDF.
    static func migrateIfNeeded(now: Date = Date()) -> Result {
        switch ImportedMapLibrary.load() {
        case .empty: break
        // no key, can't even tell whether there's a library: nothing moves yet
        case .locked: return .blocked
        case .loaded, .corrupt: return .notNeeded
        }
        let snapshot = ActiveMapSelectionStore.legacySnapshot()
        let sessionPresent = PDFSessionStore.hasStoredSession
        if snapshot == .none && !sessionPresent { return .notNeeded }
        // no key, no migration: the legacy bytes stay exactly as they are
        guard (try? SafeStore.keyProvider()) != nil else { return .blocked }
        if snapshot == .unreadable { return .blocked }

        var active: ActiveMapSelectionStore.LegacySelection?
        var retained: ActiveMapSelectionStore.LegacySelection?
        if case let .loaded(a, r) = snapshot { active = a; retained = r }

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
        // S3: fail closed. Only a file that's really gone is dropped, anything on
        // disk we can't read, hash, link or open blocks the whole thing
        var pdfEntry: ImportedMapEntry?
        var drafts: [CalibrationDraft] = []
        if sessionPresent {
            switch PDFSessionStore.migrationRead() {
            case .none, .gone:
                break
            case .blocked:
                return .blocked
            case .source(let source):
                guard FileManager.default.fileExists(atPath: source.url.path) else { break }
                guard let made = Self.pdfEntry(from: source, nowMs: nowMs) else { return .blocked }
                pdfEntry = made.entry
                links.append(made.link)
                if let draft = made.draft { drafts.append(draft) }
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
            // resolved means it's there, so a link/copy that fails blocks (S3)
            guard let made = Self.mbtilesEntry(url, nowMs: nowMs) else {
                for l in links where l.old.standardizedFileURL != l.new.standardizedFileURL { ImportedMapStorage.unlink(l.new) }
                return .blocked
            }
            state.entries.append(made.entry)
            if let link = made.link { links.append(link) }
            if case .offlineTiles? = active { state.active = .entry(made.entry.id) }
        }
        if state.active == nil { state.active = .online(state.preferredStyle) }
        return finish(state: state, links: links, drafts: drafts, uncalibrated: uncalibratedName)
    }

    private static func finish(state: LibraryState, links: [(old: URL, new: URL)], drafts: [CalibrationDraft] = [],
                               uncalibrated: String?) -> Result {
        do {
            try ImportedMapLibrary.write(state)
        } catch {
            // the links are orphans now, the next reconcile on a loaded library drops them
            return .failed
        }
        // D8: drafts only once the library they belong to is durable
        for d in drafts { try? CalibrationDraftStore.shared.save(d) }
        clearLegacy()
        for l in links where l.old.standardizedFileURL != l.new.standardizedFileURL {
            ImportedMapStorage.unlink(l.old)
        }
        return .migrated(uncalibratedName: uncalibrated)
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
        ActiveMapSelectionStore.legacyStoreExists || PDFSessionStore.hasStoredSession
            || ActiveMapSelectionStore.legacyStoreQuarantined
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
        var name = url.deletingPathExtension().lastPathComponent
        if let store = MBTilesStore(url: target) {
            name = store.metadata.name ?? name
            store.closeForDeletion()
        }
        let stats = fileStats(target)
        return (ImportedMapEntry(id: UUID(), kind: .mbtiles, fileName: rel, displayName: name, contentKey: nil,
                                 byteCount: stats.bytes, fileModifiedAtMs: stats.mtime, importedAtMs: nowMs,
                                 derivedFromId: nil, pdf: nil), link)
    }
}

/// S2: Retry on a corrupt library rebuilds the list from the map files that
/// are actually on disk. It never deletes a user file: every opaque map file
/// nothing in flight owns gets an entry again (bakes excepted, they're derived
/// and the sweep has them once the new library is loaded). Names and
/// calibrations were only in the lost library, so they can't come back.
/// Off the main thread, it hashes and inspects.
enum ImportedMapLibraryRecovery {
    /// the rebuild's own crash breaker. Unlike the import marker it never
    /// deletes the file it names, that one just gets adopted unread next time
    static let markerName = ".rebuild-inspecting"

    static var markerURL: URL? {
        try? ImportedMapStorage.importedMapsDirectory().appendingPathComponent(markerName)
    }

    /// every map file a reconcile would have deleted, oldest first
    static func candidates(inFlight: Set<URL>, fileManager fm: FileManager = .default) -> [URL] {
        var dirs: [URL] = []
        if let d = try? ImportedMapStorage.importedMapsDirectory() { dirs.append(d) }
        dirs.append(ImportedMapLibrary.offlineTilesDirectory)
        let busy = Set(inFlight.map { $0.standardizedFileURL.resolvingSymlinksInPath().path })
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

    /// the candidate library, not written. The caller does the one write
    static func rebuild(inFlight: Set<URL> = InFlightImportFiles.snapshot, now: Date = Date(),
                        inspect: (URL) -> PDFInspection? = { url in
                            try? MapImportPipeline.inspectWithWatchdog(url, isCancelled: { false }, progress: { _, _ in })
                        }) -> LibraryState {
        let style = OnlineRasterBasemapSource.defaultStyle
        var s = LibraryState()
        s.recoveryPreservesOrphans = true
        s.preferredOnlineStyle = style.rawValue
        s.active = .online(style)
        let nowMs = Int64(now.timeIntervalSince1970 * 1000)
        let marker = markerURL
        // the last rebuild died in the parser on this file, dont open it again
        let crashedOn = marker.flatMap { try? String(contentsOf: $0, encoding: .utf8) }?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        defer { if let marker { try? FileManager.default.removeItem(at: marker) } }
        var n = 0
        for url in candidates(inFlight: inFlight) {
            guard let rel = ImportedMapStorage.relativePath(for: url) else { continue }
            n += 1
            let name = Messages.mapRecoveredName(DisplayFormat.number(Double(n), decimals: 0))
            // a hash that won't come is still adopted (Delete only), never left for reconcile
            let key = PDFSessionStore.contentKey(for: url)
            let bytes = Int64((try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0)
            var mtime = ImportedMapStorage.modifiedAtMs(url)
            var entry = ImportedMapEntry(id: UUID(), kind: .pdf, fileName: rel, displayName: name, contentKey: key,
                                         byteCount: bytes, fileModifiedAtMs: mtime, importedAtMs: nowMs,
                                         derivedFromId: nil, pdf: nil)
            if url.pathExtension.lowercased() == "pdf" {
                // pageCount 0 = couldnt read it, always unavailable
                var info = ImportedMapEntry.PDFInfo(pageCount: 0, pageIndex: 0, rotate: 0, pageBox: [], embedded: nil,
                                                    embeddedIssue: nil, manual: nil)
                info.renderGuardToken = UUID().uuidString
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
                            info.pageCount = inspection.pageCount
                            info.pageIndex = page
                            info.rotate = p.geometry.rotation
                            info.pageBox = CalibrationTarget.box(p.geometry.cropBox)
                            info.embedded = p.georef
                            info.embeddedIssue = p.issue?.rawValue
                        }
                    }
                }
                entry.pdf = info
            } else {
                entry.kind = .mbtiles
                // validated. One that won't open is still adopted, as unavailable: a
                // stamp that can never match the file
                if let store = MBTilesStore(url: url) { store.closeForDeletion() } else { mtime = -1 }
                entry.fileModifiedAtMs = mtime
            }
            s.entries.append(entry)
        }
        return s
    }
}
