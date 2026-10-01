import Foundation
import CoreGraphics
import CoreLocation
import CryptoKit
import PDFKit

/// Persists the currently-active PDF map source across app launches. The PDF
/// file is already copied to protected app storage on import so it
/// survives a relaunch. What we add here is a small sealed JSON sidecar in
/// UserDefaults: file name + content key, the PdfGeoreference (version 2),
/// plus the fiduciaries when hand calibrated.
///
/// v1 entries (a lat/lon box and affine, no georef) get migrated on load. With
/// no fiduciaries the content-hash verified bytes get re-parsed for a GeoPDF
/// georef. Fiduciaries get moved from the old PDFKit display space back to raw
/// page space (see legacyDisplayToRaw) and refit in UTM of the first point. If
/// neither works, which is what the old camera-centred fallback looks like, the
/// map comes back uncalibrated and the UI asks for a calibration instead of
/// trusting the made-up box. Points whose refit got refused stay on the source
/// as pendingFiduciaries so that calibration starts with them placed.
enum PDFSessionStore {
    private static let key = "active_pdf_v1"

    /// The v1 migration runs off main (migrateStoredSession) while the UI can
    /// still save/clear/restore on main. One lock round every entry point that
    /// reads-then-writes, so a late migration save can't clobber a newer
    /// session or bring back a cleared one. Recursive, load() calls save().
    private static let lock = NSRecursiveLock()

    /// Exact encrypted preference bytes captured before a cross-store map
    /// transition. Keeping the sealed bytes opaque avoids decrypt/re-encrypt
    /// drift while rolling a failed selector commit back.
    struct ActiveSessionSnapshot {
        fileprivate let stored: Data?
    }

    /// Mutable providers keep persistence tests isolated from the developer's
    /// simulator data. Production always uses the defaults below.
    static var defaultsProvider: () -> UserDefaults = { .standard }
    static var importedMapsDirectoryProvider: () throws -> URL = {
        try ImportedMapFileCopier.importedMapsDirectory()
    }
    static var legacyDocumentsDirectoryProvider: () -> URL? = {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
    }
    static var requiresSealedPolicy: (String, Data) throws -> Bool = {
        try SealedMigrationPolicy.requiresSealed($0, key: $1)
    }
    static var markSealedPolicy: (String, Data) throws -> Void = {
        try SealedMigrationPolicy.markSealed($0, key: $1)
    }

    // The affine, fiduciaries and bounds in here pin down exactly which sheet is
    // loaded and what ground it covers, so this is area-of-interest data and
    // gets the same at-rest treatment as waypoints. UserDefaults only ever sees
    // ciphertext. Labels are bound in as AEAD associated data so one blob can't
    // be opened as the other.
    private static let labelActive = "pdf_session/active_pdf"
    private static let labelLibrary = "pdf_session/pdf_calibrations"

    private static func seal(_ data: Data, _ label: String) -> Data? {
        guard let key = try? SafeStore.keyProvider() else { return nil }
        guard let sealed = try? SealedEnvelope.sealFile(key: key, plaintext: data, label: label),
              (try? markSealedPolicy("defaults:\(label)", key)) != nil else { return nil }
        return sealed
    }

    /// Returns plaintext, or nil when locked / tampered. Pre-encryption builds
    /// stored bare JSON, which has no magic, so it passes straight through.
    private static func unseal(_ stored: Data, _ label: String) -> Data? {
        guard let key = try? SafeStore.keyProvider() else { return nil }
        if !SealedEnvelope.isSealedFile(stored) {
            guard (try? requiresSealedPolicy("defaults:\(label)", key)) == false else { return nil }
            return stored
        }
        guard (try? markSealedPolicy("defaults:\(label)", key)) != nil else { return nil }
        return SealedEnvelope.openFile(key: key, blob: stored, label: label)
    }

    private static func commitSealedDefaults(
        _ sealed: Data,
        key: String,
        defaults: UserDefaults
    ) -> Bool {
        DurableDefaultsDataStore.userDefaults(defaults, key: key).commit(sealed)
    }

    /// Persist the source before callers mark it as the active basemap. Returning
    /// false lets them keep the previous active descriptor rather than creating
    /// a `.pdf` selection that points at stale or missing session data.
    @discardableResult
    static func save(_ source: PDFMapSource) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard let bounds = source.bounds else {
            /// No bounds at all, nothing to anchor page to.
            return false
        }
        /// Uncalibrated plain PDFs persist too (their provisional placement is
        /// in the georef and flagged as such), so a relaunch mid-calibration
        /// doesn't silently lose the map.
        let cropRect = source.pdfRenderRect
        let cal: PersistedCalibration?
        if case .fiduciaries(let fids, let transform) = source.calibration {
            cal = PersistedCalibration(fids: fids, transform: transform,
                                       georef: source.georef.origin == .fiduciaries ? source.georef : nil,
                                       rawPageSpace: true)
        } else {
            cal = nil
        }
        // points a refit refused stay with the session so the next calibration starts from them
        let pending = cal == nil && !source.pendingFiduciaries.isEmpty ? source.pendingFiduciaries : nil
        guard let contentKey = source.contentKey ?? Self.contentKey(for: source.url),
              validContentKey(contentKey),
              cal.map(calibrationIsValid) ?? true,
              source.georef.isStructurallyValid else {
            return false
        }
        let dto = PersistedPDF(
            fileName: source.url.lastPathComponent,
            contentKey: contentKey,
            swLat: bounds.southWest.latitude,
            swLng: bounds.southWest.longitude,
            neLat: bounds.northEast.latitude,
            neLng: bounds.northEast.longitude,
            cropX: Double(cropRect.origin.x),
            cropY: Double(cropRect.origin.y),
            cropW: Double(cropRect.size.width),
            cropH: Double(cropRect.size.height),
            kind: source.kind.rawValue,
            calibration: cal,
            placementAffine: bounds.placementAffine,
            version: PersistedPDF.currentVersion,
            georef: source.georef,
            pendingFiduciaries: pending
        )
        guard valid(dto) else { return false }
        // Remember this PDF's calibration in the per-file library too, so it can
        // be restored even after the user switches to a different PDF and back.
        if let cal {
            saveToLibrary(
                fileName: source.url.lastPathComponent,
                contentKey: contentKey,
                cal
            )
        }
        do {
            let data = try JSONEncoder().encode(dto)
            guard let sealed = seal(data, Self.labelActive) else {
                NSLog("[PDFSessionStore] could not seal active PDF, not persisting")
                return false
            }
            let defaults = defaultsProvider()
            guard commitSealedDefaults(sealed, key: key, defaults: defaults) else {
                NSLog("[PDFSessionStore] active PDF write could not be verified")
                return false
            }
            return true
        } catch {
            /// Don't silently drop the write. A stale entry would then
            /// get restored on next launch with no clue why.
            NSLog("[PDFSessionStore] failed to encode active PDF")
            return false
        }
    }

    /// True when the saved session is a v1 (or filename-only) record, so the
    /// next load() re-parses the PDF. Cheap, it only opens the small sealed JSON.
    /// No lock on purpose, main must not wait on a migration that's mid parse.
    static var needsMigration: Bool {
        guard let stored = defaultsProvider().data(forKey: key),
              let data = unseal(stored, Self.labelActive),
              let dto = try? JSONDecoder().decode(PersistedPDF.self, from: data) else { return false }
        return dto.georef == nil || dto.contentKey == nil
    }

    /// The v1 -> v2 migration on its own: load() re-saves the record as v2 so
    /// the main thread load() after it is the fast path. Call this OFF main,
    /// re-parsing a 38 MB USGS sheet stalls for seconds (plan s5).
    static func migrateStoredSession() {
        _ = load()
    }

    static func load() -> PDFMapSource? {
        lock.lock()
        defer { lock.unlock() }
        let defaults = defaultsProvider()
        guard let stored = defaults.data(forKey: key) else { return nil }
        // Locked key returns nil. Do NOT clear the entry: the user would lose
        // their calibrated sheet just for opening the app before authenticating.
        guard let data = unseal(stored, Self.labelActive) else { return nil }
        guard let dto = try? JSONDecoder().decode(PersistedPDF.self, from: data) else {
            defaults.removeObject(forKey: key)
            return nil
        }
        guard valid(dto) else {
            defaults.removeObject(forKey: key)
            return nil
        }
        // Written by a pre-encryption build, seal it in place.
        if !SealedEnvelope.isSealedFile(stored) {
            guard let sealed = seal(data, Self.labelActive),
                  commitSealedDefaults(sealed, key: key, defaults: defaults) else {
                // The legacy descriptor remains byte-for-byte intact, but the
                // marker-first attempt fences it from a future plaintext
                // downgrade. Do not publish area-of-interest metadata until
                // its authenticated migration is durable.
                return nil
            }
        }
        guard let resolved = resolveImportedMap(
            named: dto.fileName,
            expectedContentKey: dto.contentKey
        ) else {
            NSLog("[PDFSessionStore] active PDF file vanished; clearing session")
            defaults.removeObject(forKey: key)
            return nil
        }
        let url = resolved.url
        guard let actualContentKey = Self.contentKey(for: url) else {
            NSLog("[PDFSessionStore] active PDF could not be fingerprinted; clearing stale session")
            defaults.removeObject(forKey: key)
            return nil
        }
        if let expectedContentKey = dto.contentKey {
            guard actualContentKey == expectedContentKey else {
                NSLog("[PDFSessionStore] active PDF content changed; clearing stale session")
                defaults.removeObject(forKey: key)
                return nil
            }
        }

        // Filename-only descriptors cannot authenticate which bytes their
        // embedded calibration belonged to. Two independently stored copies
        // with the same hash corroborate the legacy identity; otherwise retain
        // the map bytes but rebuild an uncalibrated, content-bound descriptor.
        // This prevents a same-name App Support collision from inheriting the
        // historical sheet's affine during an upgrade.
        if dto.contentKey == nil && resolved.legacyCorroboratedContentKey != actualContentKey {
            let rebuilt = rebuildFromBytes(url: url, contentKey: actualContentKey, legacy: dto)
            guard save(rebuilt) else {
                defaults.removeObject(forKey: key)
                return nil
            }
            NSLog("[PDFSessionStore] legacy PDF identity was unverified; calibration must be repeated")
            return rebuilt
        }

        let source: PDFMapSource
        if let georef = dto.georef {
            source = PDFMapSource(url: url, georef: georef, contentKey: actualContentKey)
            if let cal = dto.calibration {
                if georef.origin == .fiduciaries {
                    source.adoptCalibration(georef: georef, fiduciaries: cal.fids, transform: cal.transform)
                } else if !refit(source, fiduciaries: cal.fids) {
                    source.keepPendingFiduciaries(cal.fids)
                }
            }
            if source.calibration == nil, let pending = dto.pendingFiduciaries {
                source.keepPendingFiduciaries(pending)
            }
        } else {
            // no georef: a real v1 entry, or a v2 one whose georef didn't decode
            // (its points are already raw, only v1 ones need the PDFKit undo)
            source = migrateV1(dto, url: url, contentKey: actualContentKey,
                               pointsAreRaw: (dto.version ?? 1) >= 2 || dto.calibration?.pointsAreRaw == true)
        }
        // v1 and corroborated legacy copies get rewritten as content-bound v2
        // so the next launch doesn't redo the migration (or trust a filename)
        if dto.contentKey == nil || dto.georef == nil {
            _ = save(source)
        }
        return source
    }

    /// v1 -> v2. See the type doc for the cases.
    private static func migrateV1(_ dto: PersistedPDF, url: URL, contentKey: String,
                                  pointsAreRaw: Bool) -> PDFMapSource {
        if let cal = dto.calibration {
            let crop = CGRect(x: dto.cropX, y: dto.cropY, width: dto.cropW, height: dto.cropH)
            let source = uncalibratedSource(url: url, contentKey: contentKey, legacy: dto,
                                            pageBox: crop, rotation: pageRotation(url) ?? 0)
            // stored lat/lon are WGS84 (datum shifted at entry), refit in UTM.
            // if the old page space can't be rebuilt, or the refit is refused
            // (collinear etc), the map just stays uncalibrated. never refit
            // display-space points as if they were raw, that lands the sheet
            // a box-origin away (or 90 deg off) and still shows it as calibrated
            guard let fids = pointsAreRaw ? cal.fids : rawFiduciaries(cal.fids, legacyDisplaySpaceOf: url) else {
                // park the untouched v1 points in the library under the byte hash
                // (still flagged as display space) so they aren't lost, a later
                // applyCalibrationIfKnown gets another go at the undo
                saveToLibrary(fileName: url.lastPathComponent, contentKey: contentKey, cal)
                NSLog("[PDFSessionStore] v1 fiduciary page space unknown; map is uncalibrated, points kept pending")
                return source
            }
            if !refit(source, fiduciaries: fids) {
                // keep them (raw now) so calibration opens with them already placed,
                // the user fixes the bad one instead of starting from scratch
                source.keepPendingFiduciaries(fids)
                NSLog("[PDFSessionStore] v1 fiduciaries could not be refit; map is uncalibrated, points kept pending")
            }
            return source
        }
        return rebuildFromBytes(url: url, contentKey: contentKey, legacy: dto)
    }

    /// Refit saved fiduciaries (raw page space, WGS84 lat/lon) onto the source
    /// over its current crop, with the persisted lon/lat affine rebuilt from the
    /// new georef so the record stays in one page space. false when refused.
    @discardableResult
    private static func refit(_ source: PDFMapSource, fiduciaries: [Fiduciary]) -> Bool {
        let r = source.pdfRenderRect
        guard r.width > 0, r.height > 0,
              [r.minX, r.minY, r.maxX, r.maxY].allSatisfy({ $0.isFinite && abs($0) <= maximumSafePDFCoordinateMagnitude }),
              let g = FiduciaryFitter.georeference(fromWGS84: fiduciaries, crop: r, page: source.georef.page),
              let t = g.bestFitLatLonAffine() else { return false }
        source.adoptCalibration(georef: g, fiduciaries: fiduciaries, transform: t)
        return source.calibration != nil
    }

    /// The page space pre-v2 builds stored fiduciaries in. The old rasteriser
    /// set up its own CTM for pdfRenderRect and then called PDFKit
    /// draw(with: .mediaBox), which applies transform(for: .mediaBox) on top
    /// (box origin shift plus /Rotate), and taps were mapped back through the
    /// same rect. So stored = T(raw) whatever the rect was, and raw = T^-1(stored).
    /// v1 only ever showed page 0. nil when the page can't be opened.
    static func legacyDisplayToRaw(url: URL) -> CGAffineTransform? {
        guard let page = PDFDocument(url: url)?.page(at: 0) else { return nil }
        let t = page.transform(for: .mediaBox)
        let det = t.a * t.d - t.b * t.c
        guard [t.a, t.b, t.c, t.d, t.tx, t.ty].allSatisfy(\.isFinite), abs(det) > 1e-9 else { return nil }
        return t.inverted()
    }

    static func rawFiduciaries(_ fids: [Fiduciary], legacyDisplaySpaceOf url: URL) -> [Fiduciary]? {
        guard let inv = legacyDisplayToRaw(url: url) else { return nil }
        return fids.map { f in
            let p = CGPoint(x: f.pdfX, y: f.pdfY).applying(inv)
            var raw = f
            raw.pdfX = Double(p.x)
            raw.pdfY = Double(p.y)
            return raw
        }
    }

    private static func pageRotation(_ url: URL) -> Int? {
        guard let page = CGPDFDocument(url as CFURL)?.page(at: 1) else { return nil }
        return GeoPDFReader.pageGeometry(page).rotation
    }

    /// Re-parse the bytes for a georef. Anything else (plain PDF, now
    /// rejected metadata, the old camera box) comes back uncalibrated so the
    /// user gets asked to calibrate instead of trusting a made-up placement.
    private static func rebuildFromBytes(url: URL, contentKey: String, legacy dto: PersistedPDF) -> PDFMapSource {
        let readout = GeoPDFReader.read(url: url)
        if let g = readout?.georef {
            return PDFMapSource(url: url, georef: g, contentKey: contentKey)
        }
        let pageBox = readout?.page.cropBox ?? CGRect(x: dto.cropX, y: dto.cropY, width: dto.cropW, height: dto.cropH)
        return uncalibratedSource(url: url, contentKey: contentKey, legacy: dto,
                                  pageBox: pageBox, rotation: readout?.page.rotation ?? 0)
    }

    private static func uncalibratedSource(url: URL, contentKey: String, legacy dto: PersistedPDF,
                                           pageBox: CGRect, rotation: Int) -> PDFMapSource {
        let centre = CLLocationCoordinate2D(
            latitude: (dto.swLat + dto.neLat) / 2,
            longitude: (dto.swLng + dto.neLng) / 2
        )
        if let g = PdfGeoreference.provisional(pageBox: pageBox, rotation: rotation, centredOn: centre) {
            return PDFMapSource(url: url, georef: g, contentKey: contentKey)
        }
        return PDFMapSource(url: url, bounds: GeoPDFReader.Bounds(
            southWest: CLLocationCoordinate2D(latitude: dto.swLat, longitude: dto.swLng),
            northEast: CLLocationCoordinate2D(latitude: dto.neLat, longitude: dto.neLng),
            pdfCropRect: pageBox), contentKey: contentKey)
    }

    static func snapshotActiveSession() -> ActiveSessionSnapshot {
        lock.lock()
        defer { lock.unlock() }
        return ActiveSessionSnapshot(stored: defaultsProvider().data(forKey: key))
    }

    /// Restore the exact pre-transition PDF session after selector persistence
    /// fails. UserDefaults has no throwing write API, so verify the resulting
    /// bytes before reporting success.
    @discardableResult
    static func restoreActiveSession(_ snapshot: ActiveSessionSnapshot) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        let defaults = defaultsProvider()
        if let stored = snapshot.stored {
            defaults.set(stored, forKey: key)
        } else {
            defaults.removeObject(forKey: key)
        }
        return defaults.data(forKey: key) == snapshot.stored
    }

    @discardableResult
    static func clear() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        let defaults = defaultsProvider()
        defaults.removeObject(forKey: key)
        return defaults.data(forKey: key) == nil
    }

    private struct ResolvedImportedMap {
        let url: URL
        /// Present only when the historical Documents copy and private copy
        /// independently hashed to these exact bytes during resolution.
        let legacyCorroboratedContentKey: String?
    }

    /// Resolve an imported map by file name. Legacy Documents bytes are moved
    /// into protected App Support. A same-name collision never proves identity:
    /// only matching hashes across both locations permit calibration migration.
    private static func resolveImportedMap(
        named fileName: String,
        expectedContentKey: String?
    ) -> ResolvedImportedMap? {
        guard !fileName.isEmpty, fileName == URL(fileURLWithPath: fileName).lastPathComponent,
              !fileName.contains("/"), !fileName.contains("\\") else { return nil }
        let fm = FileManager.default
        guard let directory = try? importedMapsDirectoryProvider() else { return nil }
        let privateURL = directory.appendingPathComponent(fileName)
        let privateCandidate = safeRegularFile(privateURL) ? privateURL : nil
        let legacyCandidate = legacyDocumentsDirectoryProvider()
            .map { $0.appendingPathComponent(fileName) }
            .flatMap { safeRegularFile($0) ? $0 : nil }

        if let expectedContentKey {
            if let privateCandidate,
               contentKey(for: privateCandidate) == expectedContentKey {
                return ResolvedImportedMap(url: privateCandidate, legacyCorroboratedContentKey: nil)
            }
            if let legacyCandidate,
               contentKey(for: legacyCandidate) == expectedContentKey,
               let migrated = migrateLegacyDocument(
                    legacyCandidate,
                    preferredName: fileName,
                    into: directory,
                    fileManager: fm
               ) {
                return ResolvedImportedMap(url: migrated, legacyCorroboratedContentKey: nil)
            }
            return nil
        }

        if let privateCandidate, let legacyCandidate {
            if let privateKey = contentKey(for: privateCandidate),
               let legacyKey = contentKey(for: legacyCandidate),
               privateKey == legacyKey {
                return ResolvedImportedMap(
                    url: privateCandidate,
                    legacyCorroboratedContentKey: privateKey
                )
            }
            // The Documents location is the historical storage contract. Keep
            // those bytes, but a collision means their old calibration cannot
            // be authenticated and must not follow them.
            if let migrated = migrateLegacyDocument(
                legacyCandidate,
                preferredName: fileName,
                into: directory,
                fileManager: fm
            ) {
                return ResolvedImportedMap(url: migrated, legacyCorroboratedContentKey: nil)
            }
            return ResolvedImportedMap(url: privateCandidate, legacyCorroboratedContentKey: nil)
        }

        if let privateCandidate {
            return ResolvedImportedMap(url: privateCandidate, legacyCorroboratedContentKey: nil)
        }
        guard let legacyCandidate,
              let migrated = migrateLegacyDocument(
                legacyCandidate,
                preferredName: fileName,
                into: directory,
                fileManager: fm
              ) else { return nil }
        return ResolvedImportedMap(url: migrated, legacyCorroboratedContentKey: nil)
    }

    private static func safeRegularFile(_ url: URL) -> Bool {
        guard let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey])
        else { return false }
        return values.isRegularFile == true && values.isSymbolicLink != true
    }

    private static func migrateLegacyDocument(
        _ legacyURL: URL,
        preferredName: String,
        into directory: URL,
        fileManager: FileManager
    ) -> URL? {
        var destination = directory.appendingPathComponent(preferredName)
        if fileManager.fileExists(atPath: destination.path) {
            let sourceName = URL(fileURLWithPath: preferredName)
            let stem = sourceName.deletingPathExtension().lastPathComponent
            let ext = sourceName.pathExtension
            let uniqueName = "\(stem)-legacy-\(UUID().uuidString)"
            destination = directory.appendingPathComponent(uniqueName)
            if !ext.isEmpty { destination.appendPathExtension(ext) }
        }
        do {
            try fileManager.moveItem(at: legacyURL, to: destination)
            try? fileManager.setAttributes(
                [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication],
                ofItemAtPath: destination.path
            )
            return destination
        } catch {
            return nil
        }
    }

    private static func valid(_ dto: PersistedPDF) -> Bool {
        let numbers = [dto.swLat, dto.swLng, dto.neLat, dto.neLng,
                       dto.cropX, dto.cropY, dto.cropW, dto.cropH]
        return numbers.allSatisfy(\.isFinite)
            && abs(dto.swLat) <= 90 && abs(dto.neLat) <= 90
            && abs(dto.swLng) <= 180 && abs(dto.neLng) <= 180
            && dto.swLat < dto.neLat && dto.swLng < dto.neLng
            && dto.cropW > 0 && dto.cropH > 0
            && !dto.fileName.isEmpty
            && dto.fileName == URL(fileURLWithPath: dto.fileName).lastPathComponent
            && !dto.fileName.contains("/") && !dto.fileName.contains("\\")
            && (dto.contentKey == nil || validContentKey(dto.contentKey!))
            && (dto.calibration.map(calibrationIsValid) ?? true)
            && (dto.placementAffine.map(affineIsValid) ?? true)
            && (dto.georef?.isStructurallyValid ?? true)
    }

    // MARK: - Per-PDF calibration library
    //
    // Beyond the single active source above, keep every PDF's calibration
    // keyed by content hash so renamed copies restore while different sheets
    // with a colliding filename never inherit one another's affine.

    private static let libraryKey = "pdf_calibrations_v1"

    /// Apply a previously-saved calibration for this source's file if any.
    /// Called on import so re-imported PDF lands already calibrated. A stored
    /// v2 georef is used as is, older records get refit in UTM.
    static func applyCalibrationIfKnown(to source: PDFMapSource) {
        lock.lock()
        defer { lock.unlock() }
        let library = loadLibrary()
        guard let key = source.contentKey ?? contentKey(for: source.url) else { return }
        let cal = library.byContentHash[key]
        guard let cal, calibrationIsValid(cal) else { return }
        if let g = cal.georef, g.origin == .fiduciaries {
            source.adoptCalibration(georef: g, fiduciaries: cal.fids, transform: cal.transform)
            if source.calibration != nil { return }
        }
        // older records keep their points in the old PDFKit display space, same
        // bytes (content hash) so the same page transform undoes it
        guard let fids = cal.pointsAreRaw ? cal.fids : rawFiduciaries(cal.fids, legacyDisplaySpaceOf: source.url) else {
            NSLog("[PDFSessionStore] saved calibration page space unknown; not applied")
            return
        }
        if !refit(source, fiduciaries: fids) {
            source.keepPendingFiduciaries(fids)
        }
    }

    private static func saveToLibrary(
        fileName: String,
        contentKey: String,
        _ cal: PersistedCalibration
    ) {
        guard validContentKey(contentKey), calibrationIsValid(cal) else { return }
        var lib = loadLibrary()
        lib.byContentHash[contentKey] = cal
        lib.legacyByFileName.removeValue(forKey: fileName)
        if let data = try? JSONEncoder().encode(lib),
           let sealed = seal(data, Self.labelLibrary) {
            let defaults = defaultsProvider()
            _ = commitSealedDefaults(sealed, key: libraryKey, defaults: defaults)
        }
    }

    private static func loadLibrary() -> PersistedCalibrationLibrary {
        guard let stored = defaultsProvider().data(forKey: libraryKey),
              let data = unseal(stored, Self.labelLibrary)
        else { return PersistedCalibrationLibrary() }
        let wasLegacyPlaintext = !SealedEnvelope.isSealedFile(stored)
        if let library = try? JSONDecoder().decode(PersistedCalibrationLibrary.self, from: data),
           library.version == PersistedCalibrationLibrary.currentVersion {
            if wasLegacyPlaintext {
                guard let sealed = seal(data, Self.labelLibrary),
                      commitSealedDefaults(
                        sealed,
                        key: libraryKey,
                        defaults: defaultsProvider()
                      ) else { return PersistedCalibrationLibrary() }
            }
            return library
        }
        // v1 was a raw filename -> calibration dictionary. Preserve it only as
        // untrusted legacy material; a byte hash or active descriptor must bind
        // it before application.
        if let legacy = try? JSONDecoder().decode([String: PersistedCalibration].self, from: data) {
            if wasLegacyPlaintext {
                guard let sealed = seal(data, Self.labelLibrary),
                      commitSealedDefaults(
                        sealed,
                        key: libraryKey,
                        defaults: defaultsProvider()
                      ) else { return PersistedCalibrationLibrary() }
            }
            return PersistedCalibrationLibrary(
                legacyByFileName: legacy.filter { calibrationIsValid($0.value) }
            )
        }
        return PersistedCalibrationLibrary()
    }

    static func contentKey(for url: URL) -> String? {
        guard url.isFileURL, let handle = try? FileHandle(forReadingFrom: url) else { return nil }
        defer { try? handle.close() }
        var hasher = SHA256()
        do {
            while true {
                let data = try handle.read(upToCount: 64 * 1024) ?? Data()
                if data.isEmpty { break }
                hasher.update(data: data)
            }
        } catch {
            return nil
        }
        return "sha256:" + hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }

    private static func validContentKey(_ value: String) -> Bool {
        guard value.hasPrefix("sha256:"), value.count == 71 else { return false }
        return value.dropFirst(7).allSatisfy { $0.isHexDigit && !$0.isUppercase }
    }

    private static func calibrationIsValid(_ cal: PersistedCalibration) -> Bool {
        let t = cal.transform
        guard affineIsValid(t),
              cal.fids.count >= 3 else { return false }
        guard cal.fids.allSatisfy({ fid in
            [fid.pdfX, fid.pdfY, fid.latitude, fid.longitude].allSatisfy(\.isFinite)
                && abs(fid.latitude) <= 90
                && abs(fid.longitude) <= 180
        }) else { return false }
        // An invertible stored affine does not make coincident/collinear
        // control points trustworthy. Re-run the same rank check used when
        // calibration is created so malformed legacy state fails closed.
        return (try? AffineFitter.fit(cal.fids)) != nil
    }

    private static func affineIsValid(_ transform: AffineTransform2D) -> Bool {
        [transform.a, transform.b, transform.c,
         transform.d, transform.e, transform.f].allSatisfy(\.isFinite)
            && transform.inverted() != nil
    }
}

/// Filesystem-only lifecycle policy for plaintext imported PDF/GeoPDF and
/// MBTiles bytes. Kept independent of PDFKit, SQLite, and encrypted preferences
/// so containment, symlink, and failed-deletion behavior can be exercised with
/// ordinary temporary-directory tests.
enum ManagedImportedMapFileLifecycle {
    private static let sqliteSidecarSuffixes = ["-wal", "-shm", "-journal"]

    @discardableResult
    static func reconcile(
        directories: [URL],
        keeping: Set<URL>,
        fileManager: FileManager = .default
    ) -> Bool {
        var complete = true
        var managedRoots: [(original: URL, canonical: URL)] = []
        for directory in directories {
            guard directory.isFileURL else { complete = false; continue }
            var isDirectory: ObjCBool = false
            guard fileManager.fileExists(atPath: directory.path, isDirectory: &isDirectory) else {
                continue
            }
            guard isDirectory.boolValue,
                  let values = try? directory.resourceValues(
                    forKeys: [.isDirectoryKey, .isSymbolicLinkKey]
                  ),
                  values.isDirectory == true,
                  values.isSymbolicLink != true else {
                complete = false
                continue
            }
            let canonical = directory.resolvingSymlinksInPath().standardizedFileURL
            if !managedRoots.contains(where: { $0.canonical == canonical }) {
                managedRoots.append((directory, canonical))
            }
        }

        var canonicalKeep = Set<URL>()
        for retained in keeping {
            guard let validated = validatedManagedMap(
                retained,
                canonicalDirectories: managedRoots.map(\.canonical),
                authoritativeOnly: true
            ) else { return false }
            canonicalKeep.insert(validated)
        }

        for root in managedRoots {
            guard let children = try? fileManager.contentsOfDirectory(
                at: root.original,
                includingPropertiesForKeys: [.isRegularFileKey, .isSymbolicLinkKey],
                options: [.skipsSubdirectoryDescendants, .skipsPackageDescendants]
            ) else {
                complete = false
                continue
            }
            for child in children where isManagedCandidateName(child.lastPathComponent) {
                guard let candidate = validatedManagedMap(
                    child,
                    canonicalDirectories: [root.canonical],
                    authoritativeOnly: false
                ) else {
                    // An unexpected directory or symlink named like a map is
                    // never followed or recursively removed.
                    complete = false
                    continue
                }
                if canonicalKeep.contains(candidate) { continue }
                do {
                    try fileManager.removeItem(at: child)
                } catch let error as CocoaError where error.code == .fileNoSuchFile {
                    // A concurrent successful cleanup is equivalent to success.
                } catch {
                    complete = false
                }
            }
        }
        return complete
    }

    private static func validatedManagedMap(
        _ candidate: URL,
        canonicalDirectories: [URL],
        authoritativeOnly: Bool
    ) -> URL? {
        let validName = authoritativeOnly
            ? isAuthoritativeMapName(candidate.lastPathComponent)
            : isManagedCandidateName(candidate.lastPathComponent)
        guard candidate.isFileURL, validName,
              let values = try? candidate.resourceValues(
                forKeys: [.isRegularFileKey, .isSymbolicLinkKey]
              ),
              values.isRegularFile == true,
              values.isSymbolicLink != true else { return nil }
        let canonical = candidate.resolvingSymlinksInPath().standardizedFileURL
        guard canonicalDirectories.contains(canonical.deletingLastPathComponent()) else { return nil }
        return canonical
    }

    private static func isManagedCandidateName(_ name: String) -> Bool {
        isAuthoritativeMapName(name) || isCrashResidueName(name)
    }

    private static func isAuthoritativeMapName(_ name: String) -> Bool {
        let lower = name.lowercased()
        return lower.hasSuffix(".pdf") || lower.hasSuffix(".mbtiles")
    }

    private static func isCrashResidueName(_ name: String) -> Bool {
        let lower = name.lowercased()
        if lower.hasSuffix(".pdf.partial") || lower.hasSuffix(".mbtiles.partial") {
            return true
        }
        guard let suffix = sqliteSidecarSuffixes.first(where: lower.hasSuffix) else {
            return false
        }
        let base = String(lower.dropLast(suffix.count))
        return base.hasSuffix(".mbtiles") || base.hasSuffix(".mbtiles.partial")
    }
}

private struct PersistedPDF: Codable {
    static let currentVersion = 2

    /// displayName intentionally not stored: PDFMapSource derives it from
    /// the file URL on init, persisting it would just be dead data that
    /// could drift out of sync with the filename.
    let fileName: String
    /// Optional for v1 compatibility; all new writes bind the descriptor to bytes.
    let contentKey: String?
    // v1 display box + crop, still written so a downgrade reads something sane
    let swLat: Double
    let swLng: Double
    let neLat: Double
    let neLng: Double
    let cropX: Double
    let cropY: Double
    let cropW: Double
    let cropH: Double
    let kind: String
    let calibration: PersistedCalibration?
    /// GeoPDF auto-fit placement affine (rotation/scale-correct). Optional so
    /// older persisted entries (which predate it) still decode to bbox fallback.
    let placementAffine: AffineTransform2D?
    /// nil = v1
    var version: Int? = nil
    /// v2: the real placement
    var georef: PdfGeoreference? = nil
    /// v2: raw page space points that aren't a calibration (refit refused),
    /// only written when there's no calibration
    var pendingFiduciaries: [Fiduciary]? = nil
}

extension PersistedPDF {
    /// Same keys as the synthesized one, but a georef that won't decode (or
    /// isn't structurally valid any more) drops to nil instead of failing the
    /// whole entry, so load() re-parses / refits rather than deleting the session.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        fileName = try c.decode(String.self, forKey: .fileName)
        contentKey = try c.decodeIfPresent(String.self, forKey: .contentKey)
        swLat = try c.decode(Double.self, forKey: .swLat)
        swLng = try c.decode(Double.self, forKey: .swLng)
        neLat = try c.decode(Double.self, forKey: .neLat)
        neLng = try c.decode(Double.self, forKey: .neLng)
        cropX = try c.decode(Double.self, forKey: .cropX)
        cropY = try c.decode(Double.self, forKey: .cropY)
        cropW = try c.decode(Double.self, forKey: .cropW)
        cropH = try c.decode(Double.self, forKey: .cropH)
        kind = try c.decode(String.self, forKey: .kind)
        calibration = try c.decodeIfPresent(PersistedCalibration.self, forKey: .calibration)
        placementAffine = try c.decodeIfPresent(AffineTransform2D.self, forKey: .placementAffine)
        version = try c.decodeIfPresent(Int.self, forKey: .version)
        georef = try? c.decodeIfPresent(PdfGeoreference.self, forKey: .georef)
        // junk here only loses the hint, never the session
        pendingFiduciaries = (try? c.decodeIfPresent([Fiduciary].self, forKey: .pendingFiduciaries)) ?? nil
    }
}

private struct PersistedCalibration: Codable, Equatable {
    let fids: [Fiduciary]
    let transform: AffineTransform2D
    /// v2 fiduciary georef (UTM fit); nil in older libraries -> refit
    var georef: PdfGeoreference? = nil
    /// true = fids are raw page user space. nil on records from before the
    /// raw-space renderer, their points sit in the old PDFKit display space
    var rawPageSpace: Bool? = nil

    var pointsAreRaw: Bool { rawPageSpace == true }
}

extension PersistedCalibration {
    /// One unusable georef only drops that georef (the record gets refit), it
    /// must not take the whole library or the active session down with it.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        fids = try c.decode([Fiduciary].self, forKey: .fids)
        transform = try c.decode(AffineTransform2D.self, forKey: .transform)
        georef = try? c.decodeIfPresent(PdfGeoreference.self, forKey: .georef)
        // records written with a georef key predate the flag but are raw already
        rawPageSpace = try c.decodeIfPresent(Bool.self, forKey: .rawPageSpace)
            ?? (c.contains(.georef) ? true : nil)
    }
}

private struct PersistedCalibrationLibrary: Codable {
    static let currentVersion = 2

    var version = currentVersion
    var byContentHash: [String: PersistedCalibration] = [:]
    var legacyByFileName: [String: PersistedCalibration] = [:]
}
