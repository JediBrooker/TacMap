import Foundation
import CoreLocation
import CryptoKit

/// Persists the currently-active PDF map source across app launches. The PDF
/// file is already copied to protected app storage on import so it
/// survives a relaunch. What we add here is a small JSON sidecar in
/// UserDefaults that captures the non-bitmap state: file name, GeoPDF
/// bounds (sw/ne lat/lng), PDF crop rect, plus (when calibrated) the
/// affine + fiduciaries. That way we can reconstruct the same PDFMapSource
/// on startup without re-parsing or asking user to re-import.
enum PDFSessionStore {
    private static let key = "active_pdf_v1"

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
        guard let bounds = source.bounds else {
            /// No bounds at all, nothing to anchor page to.
            return false
        }
        /// Plain PDFs use the camera-centred fallback bounds resolved at import.
        /// Persist those bounds too: they are the exact placement the user saw,
        /// and active-map restoration must not silently discard that map just
        /// because it has not been fiduciary-calibrated yet.
        let cropRect = source.pdfRenderRect
        let cal: PersistedCalibration?
        if case .fiduciaries(let fids, let transform) = source.calibration {
            cal = PersistedCalibration(fids: fids, transform: transform)
        } else {
            cal = nil
        }
        guard let contentKey = source.contentKey ?? Self.contentKey(for: source.url),
              validContentKey(contentKey),
              cal.map(calibrationIsValid) ?? true else {
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
            placementAffine: bounds.placementAffine
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

    static func load() -> PDFMapSource? {
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
            let centre = CLLocationCoordinate2D(
                latitude: (dto.swLat + dto.neLat) / 2,
                longitude: (dto.swLng + dto.neLng) / 2
            )
            let parsedBounds = GeoPDFReader.bounds(from: url)
            let safeBounds = parsedBounds ?? GeoPDFReader.fallbackBounds(centeredOn: centre)
            let uncalibrated = PDFMapSource(
                url: url,
                bounds: safeBounds,
                fromGeoPDF: parsedBounds != nil,
                contentKey: actualContentKey
            )
            guard save(uncalibrated) else {
                defaults.removeObject(forKey: key)
                return nil
            }
            NSLog("[PDFSessionStore] legacy PDF identity was unverified; calibration must be repeated")
            return uncalibrated
        }
        let bounds = GeoPDFReader.Bounds(
            southWest: CLLocationCoordinate2D(latitude: dto.swLat, longitude: dto.swLng),
            northEast: CLLocationCoordinate2D(latitude: dto.neLat, longitude: dto.neLng),
            pdfCropRect: CGRect(
                x: dto.cropX, y: dto.cropY,
                width: dto.cropW, height: dto.cropH
            ),
            placementAffine: dto.placementAffine
        )
        let source = PDFMapSource(
            url: url,
            bounds: bounds,
            fromGeoPDF: dto.kind == MapSourceKind.geoPDF.rawValue,
            contentKey: actualContentKey
        )
        if let cal = dto.calibration {
            source.applyCalibration(transform: cal.transform, fiduciaries: cal.fids)
        }
        // Corroborated legacy copies can now be rewritten with their byte
        // identity so future renames restore without filename trust.
        if dto.contentKey == nil {
            _ = save(source)
        }
        return source
    }

    static func snapshotActiveSession() -> ActiveSessionSnapshot {
        ActiveSessionSnapshot(stored: defaultsProvider().data(forKey: key))
    }

    /// Restore the exact pre-transition PDF session after selector persistence
    /// fails. UserDefaults has no throwing write API, so verify the resulting
    /// bytes before reporting success.
    @discardableResult
    static func restoreActiveSession(_ snapshot: ActiveSessionSnapshot) -> Bool {
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
    }

    // MARK: - Per-PDF calibration library
    //
    // Beyond the single active source above, keep every PDF's calibration
    // keyed by content hash so renamed copies restore while different sheets
    // with a colliding filename never inherit one another's affine.

    private static let libraryKey = "pdf_calibrations_v1"

    /// Apply a previously-saved calibration for this source's file if any.
    /// Called on import so re-imported PDF lands already calibrated.
    static func applyCalibrationIfKnown(to source: PDFMapSource) {
        let library = loadLibrary()
        guard let key = source.contentKey ?? contentKey(for: source.url) else { return }
        let cal = library.byContentHash[key]
        guard let cal, calibrationIsValid(cal) else { return }
        source.applyCalibration(transform: cal.transform, fiduciaries: cal.fids)
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
    /// displayName intentionally not stored: PDFMapSource derives it from
    /// the file URL on init, persisting it would just be dead data that
    /// could drift out of sync with the filename.
    let fileName: String
    /// Optional for v1 compatibility; all new writes bind the descriptor to bytes.
    let contentKey: String?
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
}

private struct PersistedCalibration: Codable, Equatable {
    let fids: [Fiduciary]
    let transform: AffineTransform2D
}

private struct PersistedCalibrationLibrary: Codable {
    static let currentVersion = 2

    var version = currentVersion
    var byContentHash: [String: PersistedCalibration] = [:]
    var legacyByFileName: [String: PersistedCalibration] = [:]
}
