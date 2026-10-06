import Foundation
import CoreGraphics
import CoreLocation

/// What an import is doing right now, for the progress card (contract s9.1).
enum ImportProgress: Equatable, Sendable {
    case copying(done: Int64, total: Int64)
    case reading(page: Int, pages: Int)
    case saving

    var text: String {
        switch self {
        case let .copying(done, total):
            let pct = total > 0 ? Double(done) / Double(total) : 0
            let f = NumberFormatter()
            f.numberStyle = .percent
            f.maximumFractionDigits = 0
            f.locale = DisplayFormat.currentLocale
            return Messages.mapImportCopying(f.string(from: NSNumber(value: pct)) ?? "")
        case let .reading(page, pages):
            return Messages.mapImportReading(DisplayFormat.number(Double(page), decimals: 0),
                                             DisplayFormat.number(Double(pages), decimals: 0))
        case .saving:
            return Messages.mapImportSaving()
        }
    }

    var fraction: Double? {
        switch self {
        case let .copying(done, total): return total > 0 ? Double(done) / Double(total) : nil
        case let .reading(page, pages): return pages > 0 ? Double(page) / Double(pages) : nil
        case .saving: return nil
        }
    }
}

/// One scanned page of an imported PDF.
struct PDFInspectedPage: Sendable {
    var index: Int
    var geometry: GeoPDFReader.PageGeometry
    var georef: PdfGeoreference?
    var issue: PdfGeorefRejectReason?

    var decisionState: ImportDecision.PageState {
        if georef != nil { return .valid }
        if let issue { return .rejected(reason: issue.rawValue) }
        return .none
    }
}

struct PDFInspection: Sendable {
    var pageCount: Int
    /// pages 0..<min(pageCount, georefScanPages), WP1 georef read for each
    var scanned: [PDFInspectedPage]

    /// geometry for any page (picker after the scan window), opened on demand
    func page(_ i: Int) -> PDFInspectedPage? { scanned.first { $0.index == i } }
}

/// The untrusted PDF is opened ONCE here, off the main thread (D5-08, D5-09):
/// structural limits, then the georef of the first 50 pages + catalog /VP.
enum PDFInspector {

    /// test seam, counts documents opened per inspect
    static var documentOpened: (() -> Void)?

    static func inspect(_ url: URL, isCancelled: () -> Bool = { false },
                        progress: (Int, Int) -> Void = { _, _ in }) throws -> PDFInspection {
        guard let doc = CGPDFDocument(url as CFURL) else { throw MapImportError.invalidPdf }
        documentOpened?()
        let encrypted = doc.isEncrypted
        var opens = !encrypted || doc.isUnlocked
        if encrypted && !opens { opens = doc.unlockWithPassword("") }
        let count = doc.numberOfPages
        var boxes: [PDFInspectionRules.PageBoxes] = []
        if opens && count <= ImportLimits.maxPages {
            boxes.reserveCapacity(count)
            for i in 0..<count {
                if isCancelled() { throw MapImportError.cancelled }
                guard let page = doc.page(at: i + 1) else { throw MapImportError.invalidPdf }
                boxes.append(.init(mediaBox: page.getBoxRect(.mediaBox), cropBox: page.getBoxRect(.cropBox)))
            }
        }
        if let error = PDFInspectionRules.check(openable: count > 0, encrypted: encrypted, emptyPasswordOpens: opens,
                                                pageCount: count, pages: boxes) {
            throw error
        }
        let scan = min(count, ImportLimits.georefScanPages)
        var pages: [PDFInspectedPage] = []
        for i in 0..<scan {
            if isCancelled() { throw MapImportError.cancelled }
            progress(i + 1, scan)
            guard let readout = GeoPDFReader.read(document: doc, pageIndex: i) else { throw MapImportError.invalidPdf }
            var issue: PdfGeorefRejectReason?
            if case .rejected(let r) = readout.outcome { issue = r }
            pages.append(PDFInspectedPage(index: i, geometry: readout.page, georef: readout.georef, issue: issue))
        }
        return PDFInspection(pageCount: count, scanned: pages)
    }

    /// geometry of a page outside the scanned window (page picker pick past 50)
    static func geometry(_ url: URL, pageIndex: Int) -> GeoPDFReader.PageGeometry? {
        guard let doc = CGPDFDocument(url as CFURL), let page = doc.page(at: pageIndex + 1) else { return nil }
        return GeoPDFReader.pageGeometry(page)
    }
}

struct PreparedPDFImport: Identifiable, Sendable {
    let id = UUID()
    var copy: ImportedMapStorage.CopyResult
    var displayName: String
    var inspection: PDFInspection
    var modifiedAtMs: Int64
    var performedWorkOffMainThread: Bool

    /// library entry for one page, georef (or its rejection) of that page.
    /// renderGuardToken is the one the import probe armed the guard with
    func entry(pageIndex: Int, id entryID: UUID = UUID(), now: Date = Date(),
               renderGuardToken: String = UUID().uuidString) -> ImportedMapEntry? {
        let page = inspection.page(pageIndex)
        guard let geometry = page?.geometry ?? PDFInspector.geometry(copy.url, pageIndex: pageIndex),
              let rel = ImportedMapStorage.relativePath(for: copy.url) else { return nil }
        let info = ImportedMapEntry.PDFInfo(pageCount: inspection.pageCount, pageIndex: pageIndex,
                                            rotate: geometry.rotation,
                                            pageBox: CalibrationTarget.box(geometry.cropBox),
                                            embedded: page?.georef, embeddedIssue: page?.issue?.rawValue,
                                            manual: nil, firstRenderPending: nil,
                                            renderGuardToken: renderGuardToken)
        return ImportedMapEntry(id: entryID, kind: .pdf, fileName: rel, displayName: displayName,
                                contentKey: copy.contentKey, byteCount: copy.byteCount, fileModifiedAtMs: modifiedAtMs,
                                importedAtMs: Int64(now.timeIntervalSince1970 * 1000), derivedFromId: nil, pdf: info)
    }
}

struct PreparedMBTilesImport: Sendable {
    var copy: ImportedMapStorage.CopyResult
    var displayName: String
    var metadata: MBTilesStore.Metadata
    var modifiedAtMs: Int64
    var performedWorkOffMainThread: Bool

    func entry(now: Date = Date()) -> ImportedMapEntry? {
        guard let rel = ImportedMapStorage.relativePath(for: copy.url) else { return nil }
        // E8: the source file stem like every other entry, not whatever the
        // pack's metadata calls itself
        return ImportedMapEntry(id: UUID(), kind: .mbtiles, fileName: rel,
                                displayName: displayName, contentKey: copy.contentKey,
                                byteCount: copy.byteCount, fileModifiedAtMs: modifiedAtMs,
                                importedAtMs: Int64(now.timeIntervalSince1970 * 1000), derivedFromId: nil, pdf: nil)
    }
}

/// sync on purpose, Thread.isMainThread isn't allowed straight from async code
private func runningOffMain() -> Bool { !Thread.isMainThread }

/// Thread safe yes/no that only flips once (watchdog vs worker).
private final class OnceFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var done = false
    func claim() -> Bool {
        lock.lock(); defer { lock.unlock() }
        if done { return false }
        done = true
        return true
    }
}

/// Copy, hash and inspect an import off the main thread (contract s9).
/// Replaces the old ImportedMapWorker + PDFMapImporter.
enum MapImportPipeline {

    // MARK: - crash breaker (s9.8)

    static var markerURL: URL? {
        try? ImportedMapStorage.importedMapsDirectory().appendingPathComponent(ImportedMapStorage.inspectingMarkerName)
    }

    /// written before the PDF parse (or the MBTiles admission) starts, holds
    /// only the copy's opaque name
    static func writeMarker(_ copy: URL) {
        guard let m = markerURL else { return }
        let token = copy.deletingPathExtension().lastPathComponent
        try? Data(token.utf8).write(to: m, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    static func removeMarker() {
        guard let m = markerURL else { return }
        try? FileManager.default.removeItem(at: m)
    }

    /// test seam, runs in the sweep right after it read the marker
    static var sweepReadMarker: (() -> Void)?

    /// A marker means the app died inside the PDF parser or the MBTiles
    /// admission. Remove the copy it names and the marker, don't retry.
    /// true = tell the user once. Runs at launch but also after every unlock
    /// and locked library Retry, so a marker whose copy is still in flight in
    /// this process is a live import mid parse/admission, not a crash: leave
    /// the copy and the marker to that import (3.0.3, Android does the same)
    @discardableResult
    static func recoverInterruptedImport() -> Bool {
        // read the marker once, a live import can take it off under us
        guard let m = markerURL, let data = try? Data(contentsOf: m) else { return false }
        sweepReadMarker?()
        let files = interruptedImportFiles(marker: data)
        // the copy is registered before the marker goes on and stays so till
        // the import commits or drops it, so checking after the read can't
        // miss a live one
        let live = Set(InFlightImportFiles.snapshot.map(comparablePath))
        if files.contains(where: { live.contains(comparablePath($0)) }) { return false }
        // a failed parse/admission takes its marker off before it unregisters
        // the copy (off main), so a copy that left flight since the read shows
        // up here as a gone or different marker. that import already said why
        // it failed, it wasn't a crash
        guard (try? Data(contentsOf: m)) == data else { return false }
        for url in files { try? FileManager.default.removeItem(at: url) }
        try? FileManager.default.removeItem(at: m)
        return true
    }

    private static func comparablePath(_ url: URL) -> String {
        url.standardizedFileURL.resolvingSymlinksInPath().path
    }

    /// what a pending marker names (the copy and its partial), read only. The
    /// library adoption leaves these to recoverInterruptedImport
    static func interruptedImportFiles() -> Set<URL> {
        guard let m = markerURL, let data = try? Data(contentsOf: m) else { return [] }
        return interruptedImportFiles(marker: data)
    }

    private static func interruptedImportFiles(marker data: Data) -> Set<URL> {
        let token = String(decoding: data, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)
        guard token.hasPrefix("map-"), !token.contains("/"), let dir = try? ImportedMapStorage.importedMapsDirectory() else {
            return []
        }
        // 3.0.2: an MBTiles pack that killed its own admission goes the same way
        return Set(["pdf", "pdf.partial", "mbtiles", "mbtiles.partial"].map {
            dir.appendingPathComponent("\(token).\($0)")
        })
    }

    // MARK: - PDF

    /// copy + inspect in one go (tests, and anything that doesn't dedupe in between)
    static func preparePDF(url: URL, entryCount: Int, libraryLoaded: Bool,
                           isCancelled: @escaping @Sendable () -> Bool,
                           progress: @escaping @Sendable (ImportProgress) -> Void) async throws -> PreparedPDFImport {
        let copy = try await copyPDF(url: url, entryCount: entryCount, libraryLoaded: libraryLoaded,
                                     isCancelled: isCancelled, progress: progress)
        let inspection = try await inspectCopiedPDF(copy, isCancelled: isCancelled) { p, n in
            progress(.reading(page: p, pages: n))
        }
        return PreparedPDFImport(copy: copy, displayName: displayName(url), inspection: inspection,
                                 modifiedAtMs: ImportedMapStorage.modifiedAtMs(copy.url),
                                 performedWorkOffMainThread: true)
    }

    /// s9.2 + s9.3: pre-checks, then the one pass copy + hash into an opaque,
    /// in-flight name. Off main. The caller dedupes before anything parses it (E5)
    static func copyPDF(url: URL, entryCount: Int, libraryLoaded: Bool,
                        isCancelled: @escaping @Sendable () -> Bool,
                        progress: @escaping @Sendable (ImportProgress) -> Void) async throws -> ImportedMapStorage.CopyResult {
        let worker = Task.detached(priority: .userInitiated) { () throws -> ImportedMapStorage.CopyResult in
            try SecurityScopedImportAccess.withCoordinatedRead(of: url) { src -> ImportedMapStorage.CopyResult in
                try precheck(src, kind: .pdf, entryCount: entryCount, libraryLoaded: libraryLoaded)
                let dest = try ImportedMapStorage.opaqueDestination(id: UUID(), ext: "pdf")
                return try ImportedMapStorage.copyHashing(src, to: dest, maximumBytes: ImportLimits.pdfMaxBytes,
                                                          isCancelled: isCancelled,
                                                          progress: { progress(.copying(done: $0, total: $1)) })
            }
        }
        return try await withTaskCancellationHandler(operation: { try await worker.value },
                                                     onCancel: { worker.cancel() })
    }

    /// s9.5 on the copy, marker + watchdog, off main. Any failure unlinks the copy
    static func inspectCopiedPDF(_ copy: ImportedMapStorage.CopyResult,
                                 isCancelled: @escaping @Sendable () -> Bool,
                                 progress: @escaping @Sendable (Int, Int) -> Void) async throws -> PDFInspection {
        let worker = Task.detached(priority: .userInitiated) { () throws -> PDFInspection in
            do {
                if isCancelled() { throw MapImportError.cancelled }
                writeMarker(copy.url)
                defer { removeMarker() }
                return try inspectWithWatchdog(copy.url, isCancelled: isCancelled, progress: progress)
            } catch {
                ImportedMapStorage.unlink(copy.url)
                InFlightImportFiles.unregister(copy.url)
                throw error
            }
        }
        return try await withTaskCancellationHandler(operation: { try await worker.value },
                                                     onCancel: { worker.cancel() })
    }

    /// Choose page on a library entry: its own file, read again off main.
    /// No s9.8 marker, a crash here must never cost the entry its file
    static func inspectStoredPDF(_ url: URL, isCancelled: @escaping @Sendable () -> Bool,
                                 progress: @escaping @Sendable (Int, Int) -> Void) async throws -> PDFInspection {
        try await Task.detached(priority: .userInitiated) {
            try inspectWithWatchdog(url, isCancelled: isCancelled, progress: progress)
        }.value
    }

    /// 30 s watchdog. CGPDF can't be interrupted, so on expiry the worker is
    /// abandoned and its result dropped; the caller unlinks the copy (fine while open)
    static func inspectWithWatchdog(_ url: URL, timeout: TimeInterval = Double(ImportLimits.parseTimeoutMs) / 1000,
                                    isCancelled: @escaping @Sendable () -> Bool,
                                    progress: @escaping @Sendable (Int, Int) -> Void) throws -> PDFInspection {
        let once = OnceFlag()
        let done = DispatchSemaphore(value: 0)
        var result: Result<PDFInspection, Error> = .failure(MapImportError.tooComplex)
        let lock = NSLock()
        DispatchQueue.global(qos: .userInitiated).async {
            let r = Result { try PDFInspector.inspect(url, isCancelled: isCancelled, progress: progress) }
            if once.claim() {
                lock.lock(); result = r; lock.unlock()
                done.signal()
            }
        }
        if done.wait(timeout: .now() + timeout) == .timedOut, once.claim() {
            throw MapImportError.tooComplex
        }
        lock.lock(); defer { lock.unlock() }
        return try result.get()
    }

    // MARK: - render probe (WP2 contract M)

    /// Draw the page once before it's saved anywhere: a PDF that crashes the
    /// renderer dies in the import, not on every launch. Arms the import guard
    /// round the base raster + blank check. A clean failure is cannotDraw(reason)
    /// and nothing gets committed. Plain and refused pages get a stand in
    /// placement, the base raster only depends on the page box. Off main
    static func probe(url: URL, contentKey: String, pageIndex: Int, page: GeoPDFReader.PageGeometry,
                      georef: PdfGeoreference?, token: String,
                      guardStore: PDFRenderGuard = .shared) throws -> PDFPageRaster {
        var g: PdfGeoreference
        if let georef {
            g = georef
        } else if let p = PdfGeoreference.provisional(pageBox: page.cropBox, rotation: page.rotation,
                                                      centredOn: CLLocationCoordinate2D(latitude: 0, longitude: 0)) {
            g = p
        } else {
            throw MapImportError.invalidPdf
        }
        g.page = pageIndex
        guardStore.arm(kind: .import, token: token)
        // a clean failure isnt a crash, so the marker comes off either way
        defer { guardStore.complete(kind: .import, token: token) }
        do {
            let (_, pdfPage) = try PDFTileRenderer.openPage(url: url, pageIndex: pageIndex)
            let ctx = try PDFRenderContext(
                url: url, identity: PDFDocumentIdentity(contentKey: contentKey, pageIndex: pageIndex),
                georef: g, pageBox: PDFTileRenderer.pageBox(pdfPage), tilePx: 512, guardToken: token,
                baseBudgetPx: PDFMemoryTier.baseBudgetPx(physicalMemory: ProcessInfo.processInfo.physicalMemory))
            let raster = try autoreleasepool {
                try PDFTileRenderer.renderBaseRaster(page: pdfPage, plan: ctx.basePlan, footprint: ctx.footprint)
            }
            if raster.blank { throw PDFRenderFailure.blank }
            return raster
        } catch let f as PDFRenderFailure {
            throw MapImportError.cannotDraw(f)
        }
    }

    // MARK: - MBTiles

    static func prepareMBTiles(url: URL, entryCount: Int, libraryLoaded: Bool,
                               isCancelled: @escaping @Sendable () -> Bool,
                               progress: @escaping @Sendable (ImportProgress) -> Void) async throws -> PreparedMBTilesImport {
        let worker = Task.detached(priority: .userInitiated) { () throws -> PreparedMBTilesImport in
            let offMain = runningOffMain()
            let copy = try SecurityScopedImportAccess.withCoordinatedRead(of: url) { src -> ImportedMapStorage.CopyResult in
                try precheck(src, kind: .mbtiles, entryCount: entryCount, libraryLoaded: libraryLoaded)
                let dest = try ImportedMapStorage.opaqueDestination(id: UUID(), ext: "mbtiles")
                return try ImportedMapStorage.copyHashing(src, to: dest, maximumBytes: ImportLimits.mbtilesMaxBytes,
                                                          isCancelled: isCancelled,
                                                          progress: { progress(.copying(done: $0, total: $1)) })
            }
            do {
                if isCancelled() { throw MapImportError.cancelled }
                // s9.8 marker round the admission (3.0.2): a pack that kills it
                // is removed and reported next launch, never adopted. A clean
                // refusal takes the marker off too
                writeMarker(copy.url)
                let opened = MBTilesStore(url: copy.url)
                let metadata = opened?.metadata
                opened?.closeForDeletion()
                removeMarker()
                guard let metadata else { throw MapImportError.invalidMbtiles }
                return PreparedMBTilesImport(copy: copy, displayName: displayName(url), metadata: metadata,
                                             modifiedAtMs: ImportedMapStorage.modifiedAtMs(copy.url),
                                             performedWorkOffMainThread: offMain)
            } catch {
                ImportedMapStorage.unlink(copy.url)
                InFlightImportFiles.unregister(copy.url)
                throw error
            }
        }
        return try await withTaskCancellationHandler(operation: { try await worker.value },
                                                     onCancel: { worker.cancel() })
    }

    // MARK: - bits

    private static func precheck(_ src: URL, kind: ImportPrecheck.Kind, entryCount: Int, libraryLoaded: Bool) throws {
        let size = Int64((try? src.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0)
        if let e = ImportPrecheck.check(library: libraryLoaded ? .loaded : .locked, entryCount: entryCount, kind: kind,
                                        sizeBytes: size, freeBytes: ImportedMapStorage.freeBytes()) {
            throw e
        }
    }

    /// the source file stem, only ever stored sealed in the library
    static func displayName(_ url: URL) -> String {
        let stem = url.deletingPathExtension().lastPathComponent.trimmingCharacters(in: .whitespacesAndNewlines)
        return stem.isEmpty ? Messages.mapLibrarySection() : String(stem.prefix(120))
    }
}
