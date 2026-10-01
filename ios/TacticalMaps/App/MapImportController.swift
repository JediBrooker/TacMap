import Foundation
import Combine
import SwiftUI
import PDFKit

/// A PDF waiting on the page picker (s9.6): the prepared copy stays registered
/// in flight until the user picks or cancels.
struct PagePickerRequest: Identifiable {
    let id = UUID()
    var prepared: PreparedPDFImport
    /// 0-based pages carrying a declared but unusable georef
    var badges: [Int]
    /// set when the picker changes the page of an existing entry (Choose page...)
    var existingEntryID: UUID?
}

/// A just imported PDF whose georef we won't use: Calibrate now / Later.
struct RejectedImportPrompt: Identifiable {
    let id = UUID()
    var entryID: UUID
    var message: String
}

/// E1: a Choose page pick that would wipe this map's hand calibration, asked first
struct ChangePageConfirm: Identifiable {
    let id = UUID()
    var request: PagePickerRequest
    var page: Int
}

/// Choose page on a library entry (E1, E4), pure. import_limits.json lifecycle.choosePage
enum ChoosePageDecision {
    enum Then: String { case none, calibrate, activate }

    struct Outcome: Equatable {
        /// catalogue key of the confirm to ask first, nil = straight on
        var confirm: String?
        var write: Bool
        var then: Then
        /// the change page write also sets active = online(preferred)
        var activeOnline: Bool
    }

    static func decide(currentPage: Int, pickedPage: Int, hasManual: Bool, pickedPageHasValidGeoref: Bool,
                       entryIsActive: Bool) -> Outcome {
        // picking the page it's already on does nothing at all
        guard pickedPage != currentPage else { return Outcome(confirm: nil, write: false, then: .none, activeOnline: false) }
        return Outcome(confirm: hasManual ? "map_change_page_confirm" : nil, write: true,
                       then: pickedPageHasValidGeoref ? .activate : .calibrate,
                       activeOnline: entryIsActive && !pickedPageHasValidGeoref)
    }
}

/// hops background progress callbacks onto main
private final class ProgressSink: @unchecked Sendable {
    private let deliver: (ImportProgress) -> Void
    init(_ deliver: @escaping (ImportProgress) -> Void) { self.deliver = deliver }
    func post(_ p: ImportProgress) { DispatchQueue.main.async { self.deliver(p) } }
}

private final class CancelFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var value = false
    func set(_ v: Bool) { lock.lock(); value = v; lock.unlock() }
    func get() -> Bool { lock.lock(); defer { lock.unlock() }; return value }
}

/// Runs map imports for ContentView (contract s9): pre-checks, copy + hash,
/// dedupe, inspect, decide, probe, write (E5). The slow parts are off main, a
/// progress card after 300 ms with Cancel while copying or reading. Import
/// menu items are disabled while one runs, a second import never silently
/// cancels the first (D5-09).
final class MapImportController: ObservableObject {
    @Published private(set) var progress: ImportProgress?
    @Published private(set) var showHUD = false
    @Published var pagePicker: PagePickerRequest?
    @Published var rejectedPrompt: RejectedImportPrompt?
    @Published var error: MapImportError?
    @Published var changePageConfirm: ChangePageConfirm?

    let toasts = PassthroughSubject<String, Never>()

    weak var mapVM: MapViewModel?
    /// E1: calibration on an entry that's in the library
    var startCalibration: ((UUID) -> Void)?

    private var task: Task<Void, Never>?
    private var hudTask: Task<Void, Never>?
    /// fresh per import: an abandoned inspector keeps its own (set) flag and
    /// can't see the next import reset it
    private var cancelFlag = CancelFlag()
    /// E6: which import the card belongs to. A late callback from an abandoned
    /// worker carries an older id and gets dropped
    private var runID = UUID()

    var isRunning: Bool { task != nil }

    /// E2: Cancel while copying or reading only. Saving is the library write,
    /// too late to back out of
    var canCancel: Bool { Self.cancelVisible(progress) }

    static func cancelVisible(_ p: ImportProgress?) -> Bool {
        switch p {
        case .copying?, .reading?: return true
        case .saving?, nil: return false
        }
    }

    /// what the stages of one import get to see
    struct RunContext: Sendable {
        let entryCount: Int
        let isCancelled: @Sendable () -> Bool
        let progress: @Sendable (ImportProgress) -> Void
    }

    // MARK: - entry points

    func importPDF(_ result: Result<[URL], Error>) {
        guard let url = pick(result) else { return }
        importPDF(url: url)
    }

    /// a picked file, or the DEBUG import hook's (docs/DEBUG_HOOKS.md)
    func importPDF(url: URL, completion: (() -> Void)? = nil) {
        run(initial: .copying(done: 0, total: 0), done: completion) { [weak self] ctx in
            let copy = try await MapImportPipeline.copyPDF(url: url, entryCount: ctx.entryCount, libraryLoaded: true,
                                                           isCancelled: ctx.isCancelled, progress: ctx.progress)
            guard let self else { return Self.drop(copy.url) }
            try self.stopIfCancelled(ctx, copy.url)
            let modified = ImportedMapStorage.modifiedAtMs(copy.url)
            // E5: dedupe straight after the copy, a duplicate is never parsed
            if let existing = self.duplicate(copy.contentKey) {
                self.finishDuplicate(existing, copy: copy, modifiedAtMs: modified)
                return
            }
            let inspection = try await MapImportPipeline.inspectCopiedPDF(copy, isCancelled: ctx.isCancelled) { p, n in
                ctx.progress(.reading(page: p, pages: n))
            }
            try self.stopIfCancelled(ctx, copy.url)
            let prepared = PreparedPDFImport(copy: copy, displayName: MapImportPipeline.displayName(url),
                                             inspection: inspection, modifiedAtMs: modified, performedWorkOffMainThread: true)
            try await self.finishPDF(prepared, ctx)
        }
    }

    func importMBTiles(_ result: Result<[URL], Error>) {
        guard let url = pick(result) else { return }
        run(initial: .copying(done: 0, total: 0)) { [weak self] ctx in
            let prepared = try await MapImportPipeline.prepareMBTiles(url: url, entryCount: ctx.entryCount, libraryLoaded: true,
                                                                      isCancelled: ctx.isCancelled, progress: ctx.progress)
            guard let self else { return Self.drop(prepared.copy.url) }
            try self.stopIfCancelled(ctx, prepared.copy.url)
            self.finishMBTiles(prepared)
        }
    }

    /// test seam for the WP2 import probe, swapped to fail on purpose
    var probe: @Sendable (URL, String, Int, GeoPDFReader.PageGeometry, PdfGeoreference?, String) throws -> PDFPageRaster = {
        try MapImportPipeline.probe(url: $0, contentKey: $1, pageIndex: $2, page: $3, georef: $4, token: $5)
    }

    func cancel() {
        guard canCancel else { return }
        cancelFlag.set(true)
        task?.cancel()
    }

    private func pick(_ result: Result<[URL], Error>) -> URL? {
        switch result {
        case .failure(let e):
            error = .failed(detail: e.localizedDescription)
            return nil
        case .success(let urls):
            guard !isRunning else { return nil }
            return urls.first
        }
    }

    private func run(initial: ImportProgress, done: (() -> Void)? = nil,
                     _ body: @escaping @MainActor (RunContext) async throws -> Void) {
        guard let mapVM else {
            // OD-F2: never drop an import without a word, the debug hook hit this
            NSLog("[MapImport] import dropped: no map view model wired yet")
            done?()
            return
        }
        guard !isRunning else {
            NSLog("[MapImport] import dropped: another one is still running")
            done?()
            return
        }
        // locked library is the first precheck, before any byte is copied
        guard mapVM.libraryStatus == .loaded else {
            error = .locked
            done?()
            return
        }
        let flag = CancelFlag()
        cancelFlag = flag
        let id = UUID()
        runID = id
        progress = initial
        showHUD = false
        let sink = ProgressSink { [weak self] p in
            guard let self, self.runID == id, self.task != nil, self.canCancel else { return }
            self.progress = p
        }
        let ctx = RunContext(entryCount: mapVM.library?.entries.count ?? 0,
                             isCancelled: { flag.get() }, progress: { sink.post($0) })
        hudTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(ImportLimits.progressHudDelayMs) * 1_000_000)
            guard let self, !Task.isCancelled, self.task != nil, self.runID == id else { return }
            self.showHUD = true
        }
        task = Task { @MainActor [weak self] in
            do {
                try await body(ctx)
            } catch let e as MapImportError {
                if e == .cancelled { self?.toasts.send(Messages.mapImportCancelled()) } else { self?.error = e }
                // an abandoned inspector stops at its next page instead of running on
                flag.set(true)
            } catch is CancellationError {
                flag.set(true)
                self?.toasts.send(Messages.mapImportCancelled())
            } catch {
                flag.set(true)
                self?.error = .failed(detail: error.localizedDescription)
            }
            if let self, self.runID == id {
                self.task = nil
                self.hudTask?.cancel()
                self.progress = nil
                self.showHUD = false
            }
            done?()
        }
    }

    /// E2: Cancel re-checked after the copy, the inspection and the probe. The
    /// copy goes, nothing is written, the cancelled toast shows
    private func stopIfCancelled(_ ctx: RunContext, _ copy: URL) throws {
        guard ctx.isCancelled() || Task.isCancelled else { return }
        Self.drop(copy)
        throw MapImportError.cancelled
    }

    // MARK: - outcomes (main thread)

    private func duplicate(_ key: String) -> ImportedMapEntry? {
        mapVM?.library?.entry(contentKey: key)
    }

    private static func drop(_ url: URL) {
        ImportedMapStorage.unlink(url)
        InFlightImportFiles.unregister(url)
    }

    private func discard(_ url: URL) { Self.drop(url) }

    /// s9.4 + E9. The existing entry wins, unless its own file is missing or
    /// changed: then this copy is exactly its bytes, so it's kept and the entry
    /// re-linked to it (one write) before the usual activate / calibrate
    private func finishDuplicate(_ e: ImportedMapEntry, copy: ImportedMapStorage.CopyResult, modifiedAtMs: Int64) {
        guard let mapVM else { return discard(copy.url) }
        let dup = ImportDecision.Duplicate(existingHasGeoref: e.pdf?.effectiveGeoref != nil || e.kind == .mbtiles,
                                           name: e.displayName,
                                           existingUnavailable: mapVM.fileStatus(e) != .ok)
        let decision = ImportDecision.decide(pageCount: 1, pages: [], duplicate: dup)
        if decision.relink {
            progress = .saving
            guard mapVM.relinkLibraryEntry(e.id, to: copy.url, byteCount: copy.byteCount, modifiedAtMs: modifiedAtMs) else {
                return
            }
        } else {
            discard(copy.url)
        }
        switch decision.action {
        case .calibrateExisting: startCalibration?(e.id)
        default: mapVM.activateLibraryEntry(e.id)
        }
        if let t = decision.toast { toasts.send(t.text) }
    }

    /// WP2 import probe (contract M) on the page that's about to be committed,
    /// before the library write: arm the import guard, draw the base raster +
    /// blank check off main, complete. false = it couldnt be drawn, the copy is
    /// gone and the error is up. The raster is kept so the first paint is instant
    @MainActor
    private func probed(_ prepared: PreparedPDFImport, pageIndex: Int, token: String) async -> Bool {
        let url = prepared.copy.url, key = prepared.copy.contentKey
        let page = prepared.inspection.page(pageIndex)
        let georef = page?.georef
        let known = page?.geometry
        let probe = self.probe
        do {
            let raster = try await Task.detached(priority: .userInitiated) { () throws -> PDFPageRaster in
                guard let geometry = known ?? PDFInspector.geometry(url, pageIndex: pageIndex) else {
                    throw MapImportError.invalidPdf
                }
                return try probe(url, key, pageIndex, geometry, georef, token)
            }.value
            mapVM?.pdfRuntime.adoptBaseRaster(raster, identity: PDFDocumentIdentity(contentKey: key, pageIndex: pageIndex),
                                              url: url)
            return true
        } catch let e as MapImportError {
            error = e
        } catch {
            self.error = .failed(detail: error.localizedDescription)
        }
        discard(prepared.copy.url)
        return false
    }

    /// probe, the last cancel check, then the one library write (E2, M7). The
    /// copy belongs to the library from here (or waits on the write's Retry, S7)
    @MainActor
    private func commit(_ prepared: PreparedPDFImport, _ entry: ImportedMapEntry, pageIndex: Int, token: String,
                        activate: Bool, _ ctx: RunContext) async throws -> Bool {
        guard await probed(prepared, pageIndex: pageIndex, token: token) else { return false }
        try stopIfCancelled(ctx, prepared.copy.url)
        progress = .saving
        return mapVM?.addImportedEntry(entry, activate: activate, ownsCopy: prepared.copy.url) ?? false
    }

    @MainActor
    private func finishPDF(_ prepared: PreparedPDFImport, _ ctx: RunContext) async throws {
        let token = UUID().uuidString
        let decision = ImportDecision.decide(pageCount: prepared.inspection.pageCount,
                                             pages: prepared.inspection.scanned.map(\.decisionState), duplicate: nil)
        switch decision.action {
        case .activateExisting, .calibrateExisting:
            discard(prepared.copy.url)
        case .addAndActivate:
            let pageIndex = decision.page ?? 0
            guard let entry = prepared.entry(pageIndex: pageIndex, renderGuardToken: token) else {
                discard(prepared.copy.url)
                throw MapImportError.invalidPdf
            }
            guard try await commit(prepared, entry, pageIndex: pageIndex, token: token, activate: true, ctx) else { return }
            // E7: only once it's really in the library
            if let t = decision.toast { toasts.send(t.text) }
        case .addRejected:
            guard let entry = prepared.entry(pageIndex: 0, renderGuardToken: token) else {
                discard(prepared.copy.url)
                throw MapImportError.invalidPdf
            }
            guard try await commit(prepared, entry, pageIndex: 0, token: token, activate: false, ctx) else { return }
            rejectedPrompt = RejectedImportPrompt(entryID: entry.id, message: decision.alert?.message.text ?? "")
        case .addAndCalibrate:
            guard let entry = prepared.entry(pageIndex: 0, renderGuardToken: token) else {
                discard(prepared.copy.url)
                throw MapImportError.invalidPdf
            }
            guard try await commit(prepared, entry, pageIndex: 0, token: token, activate: false, ctx) else { return }
            startCalibration?(entry.id)
        case .pagePicker:
            pagePicker = PagePickerRequest(prepared: prepared, badges: decision.pickerBadges ?? [])
        }
    }

    private func finishMBTiles(_ prepared: PreparedMBTilesImport) {
        guard let mapVM else { return discard(prepared.copy.url) }
        if let e = duplicate(prepared.copy.contentKey) {
            finishDuplicate(e, copy: prepared.copy, modifiedAtMs: prepared.modifiedAtMs)
            return
        }
        guard let entry = prepared.entry() else {
            discard(prepared.copy.url)
            error = .invalidMbtiles
            return
        }
        progress = .saving
        mapVM.addImportedEntry(entry, activate: true, ownsCopy: prepared.copy.url)
    }

    // MARK: - page picker

    func choosePage(_ index: Int) {
        guard let req = pagePicker, let mapVM else { return }
        pagePicker = nil
        if let id = req.existingEntryID {
            // Choose page... on a library entry, the file is already the entry's
            guard let e = mapVM.entry(id), let pdf = e.pdf else { return }
            let d = ChoosePageDecision.decide(currentPage: pdf.pageIndex, pickedPage: index, hasManual: pdf.manual != nil,
                                              pickedPageHasValidGeoref: req.prepared.inspection.page(index)?.georef != nil,
                                              entryIsActive: mapVM.library?.activeEntryID == id)
            guard d.write else { return }
            if d.confirm != nil {
                // E1: the calibration only goes after the user says so
                changePageConfirm = ChangePageConfirm(request: req, page: index)
                return
            }
            applyChangePage(req, page: index)
            return
        }
        let token = UUID().uuidString
        guard let entry = req.prepared.entry(pageIndex: index, renderGuardToken: token) else {
            discard(req.prepared.copy.url)
            error = .invalidPdf
            return
        }
        let hasGeoref = entry.pdf?.embedded != nil
        // the picked page gets the same probe as a single page import, then the commit
        run(initial: .reading(page: index + 1, pages: req.prepared.inspection.pageCount)) { [weak self] ctx in
            guard let self else { return Self.drop(req.prepared.copy.url) }
            guard try await self.commit(req.prepared, entry, pageIndex: index, token: token, activate: hasGeoref, ctx) else { return }
            if !hasGeoref { self.startCalibration?(entry.id) }
        }
    }

    /// Change page confirmed (or nothing to confirm)
    func confirmChangePage() {
        guard let c = changePageConfirm else { return }
        changePageConfirm = nil
        applyChangePage(c.request, page: c.page)
    }

    /// E4: a page with a valid georef is activated (framed), any other starts
    /// calibrating it. The change page write already took an active entry
    /// online when the new page has no georef
    private func applyChangePage(_ req: PagePickerRequest, page index: Int) {
        guard let mapVM, let id = req.existingEntryID else { return }
        let page = req.prepared.inspection.page(index)
        guard let geometry = page?.geometry ?? PDFInspector.geometry(req.prepared.copy.url, pageIndex: index) else {
            error = .invalidPdf
            return
        }
        guard mapVM.changePage(id, pageIndex: index, rotate: geometry.rotation, pageBox: CalibrationTarget.box(geometry.cropBox),
                               embedded: page?.georef, embeddedIssue: page?.issue?.rawValue) else { return }
        if page?.georef != nil {
            if mapVM.activeEntryID != id { mapVM.activateLibraryEntry(id) }
        } else {
            startCalibration?(id)
        }
    }

    func cancelPagePicker() {
        guard let req = pagePicker else { return }
        pagePicker = nil
        // a fresh import's copy goes, an existing entry's file obviously stays
        if req.existingEntryID == nil { discard(req.prepared.copy.url) }
    }

    /// "Choose page..." from Layers: inspect the entry's own file again, off main,
    /// with the progress card and Cancel. A failure is an alert (E3)
    func choosePage(for entry: ImportedMapEntry) {
        guard let mapVM, let url = mapVM.fileURL(entry), let key = entry.contentKey else {
            NSLog("[MapImport] choose page dropped: entry has no file")
            return
        }
        run(initial: .reading(page: 0, pages: entry.pdf?.pageCount ?? 0)) { [weak self] ctx in
            let inspection = try await MapImportPipeline.inspectStoredPDF(url, isCancelled: ctx.isCancelled) { p, n in
                ctx.progress(.reading(page: p, pages: n))
            }
            if ctx.isCancelled() || Task.isCancelled { throw MapImportError.cancelled }
            guard let self else { return }
            let copy = ImportedMapStorage.CopyResult(url: url, byteCount: entry.byteCount, contentKey: key)
            let prepared = PreparedPDFImport(copy: copy, displayName: entry.displayName, inspection: inspection,
                                             modifiedAtMs: entry.fileModifiedAtMs, performedWorkOffMainThread: true)
            let badges = inspection.scanned.filter { $0.issue != nil }.map(\.index)
            self.pagePicker = PagePickerRequest(prepared: prepared, badges: badges, existingEntryID: entry.id)
        }
    }
}

/// The small progress card (s9.1), shown 300 ms into an import.
struct ImportProgressHUD: View {
    @ObservedObject private var appLanguage = AppLanguage.shared
    @ObservedObject var controller: MapImportController

    var body: some View {
        if controller.showHUD, let p = controller.progress {
            VStack(alignment: .leading, spacing: 8) {
                Text(p.text)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.white)
                    .accessibilityIdentifier("import.progress")
                if let f = p.fraction {
                    ProgressView(value: f).tint(Color(red: 1, green: 0.65, blue: 0.18))
                } else {
                    ProgressView().tint(.white)
                }
                // E2: gone once it's saving, like Android
                if controller.canCancel {
                    Button(L10n.text("Cancel")) { controller.cancel() }
                        .font(.subheadline.weight(.semibold))
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .background(.white.opacity(0.12), in: RoundedRectangle(cornerRadius: 10))
                        .foregroundStyle(.white)
                        .accessibilityIdentifier("import.cancel")
                }
            }
            .padding(14)
            .frame(maxWidth: 360)
            .background(.black.opacity(0.9), in: RoundedRectangle(cornerRadius: 16))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(.white.opacity(0.14)))
            .padding(.horizontal, 24)
            .transition(.opacity)
        }
    }
}

/// Thumbnails for the page picker: one serial queue, LRU of 24.
private final class PageThumbnailCache: ObservableObject {
    private let cache = NSCache<NSNumber, UIImage>()
    private let queue = DispatchQueue(label: "com.tacmap.page-thumbnails", qos: .userInitiated)
    private var document: PDFDocument?
    @Published private(set) var ready = Set<Int>()

    init(url: URL) {
        cache.countLimit = ImportLimits.thumbnailCacheEntries
        // S6: each one is at most the 2x frame, 24 of them fit in here easily
        cache.totalCostLimit = ImportLimits.thumbnailCacheEntries * Self.maxCost(width: CGFloat(ImportLimits.thumbnailWidthPt))
        queue.async { [weak self] in self?.document = PDFDocument(url: url) }
    }

    func image(_ i: Int) -> UIImage? { cache.object(forKey: NSNumber(value: i)) }

    /// bytes of one thumbnail at most: the 2x frame (width x 1.3 width), RGBA
    static func maxCost(width: CGFloat) -> Int { Int(width * 2 * width * 1.3 * 2 * 4) }

    func request(_ i: Int, width: CGFloat) {
        guard cache.object(forKey: NSNumber(value: i)) == nil else { return }
        queue.async { [weak self] in
            guard let self, let page = self.document?.page(at: i) else { return }
            // S6: fit the page into the frame the grid draws, never its own aspect.
            // a 36 x 14400 pt page used to ask for a ~90 MB bitmap here
            let img = page.thumbnail(of: CGSize(width: width * 2, height: width * 1.3 * 2), for: .cropBox)
            let cost = Int(img.size.width * img.scale * img.size.height * img.scale * 4)
            self.cache.setObject(img, forKey: NSNumber(value: i), cost: cost)
            DispatchQueue.main.async { self.ready.insert(i) }
        }
    }
}

/// Page picker (s9.6) for multi-page PDFs without a usable georef.
struct PDFPagePickerSheet: View {
    @ObservedObject private var appLanguage = AppLanguage.shared
    let request: PagePickerRequest
    var onPick: (Int) -> Void
    var onCancel: () -> Void
    @StateObject private var thumbs: PageThumbnailCache

    init(request: PagePickerRequest, onPick: @escaping (Int) -> Void, onCancel: @escaping () -> Void) {
        self.request = request
        self.onPick = onPick
        self.onCancel = onCancel
        _thumbs = StateObject(wrappedValue: PageThumbnailCache(url: request.prepared.copy.url))
    }

    private let width = CGFloat(ImportLimits.thumbnailWidthPt)

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    // E14: Layers' Choose page isn't about missing georeferencing
                    Text(request.existingEntryID != nil
                         ? Messages.mapChoosePageMessage(DisplayFormat.number(Double(request.prepared.inspection.pageCount), decimals: 0))
                         : Messages.mapImportChoosePageMessage(DisplayFormat.number(Double(request.prepared.inspection.pageCount), decimals: 0)))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: width + 16), spacing: 12)], spacing: 16) {
                        ForEach(0..<request.prepared.inspection.pageCount, id: \.self) { i in
                            Button { onPick(i) } label: {
                                VStack(spacing: 6) {
                                    Group {
                                        if let img = thumbs.image(i) {
                                            Image(uiImage: img).resizable().scaledToFit()
                                        } else {
                                            Rectangle().fill(.secondary.opacity(0.15))
                                                .overlay(ProgressView())
                                        }
                                    }
                                    .frame(width: width, height: width * 1.3)
                                    .background(Color.white)
                                    .clipShape(RoundedRectangle(cornerRadius: 6))
                                    Text(Messages.mapImportPageLabel(DisplayFormat.number(Double(i + 1), decimals: 0)))
                                        .font(.caption.weight(.semibold))
                                    if request.badges.contains(i) {
                                        Label(Messages.mapImportPageBadgeRejected(), systemImage: "exclamationmark.triangle.fill")
                                            .font(.caption2)
                                            .foregroundStyle(.orange)
                                    }
                                }
                            }
                            .buttonStyle(.plain)
                            .onAppear { thumbs.request(i, width: width) }
                            .accessibilityIdentifier("import.page.\(i + 1)")
                        }
                    }
                }
                .padding()
            }
            .navigationTitle(Messages.mapImportChoosePageTitle())
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L10n.text("Cancel"), action: onCancel)
                }
            }
        }
    }
}
