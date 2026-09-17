import Foundation
import CryptoKit
import Security

/// v3 room-scoped identity derivation and binary preimage construction.
/// Pure + deterministic so it unit-tests against the shared fixture.
enum SyncIdentity {

    enum SessionRandomError: Error, Equatable {
        case generationFailed(OSStatus)
    }

    static let domainPut: UInt8 = 0x01
    static let domainDelete: UInt8 = 0x02
    static let domainPresence: UInt8 = 0x03
    static let domainHello: UInt8 = 0x04
    static let protocolVersion: UInt8 = 0x03
    static let explicitLeaveVersion = 1
    static let explicitLeaveKind = "leave-v1"

    /// Room-scoped actor ID: SHA-256("tacmap-actor-v3\0" || roomIdRaw || pubkeyRaw)
    /// -> base64url no pad. Same device in different rooms has different actorIds.
    static func actorId(roomIdRaw: Data, pubkeyRaw: Data) -> String {
        var hasher = SHA256()
        hasher.update(data: Data("tacmap-actor-v3\0".utf8))
        hasher.update(data: roomIdRaw)
        hasher.update(data: pubkeyRaw)
        return Data(hasher.finalize()).base64URLEncodedStringNoPad()
    }

    /// Wire object ID: HMAC-SHA256(metadataKey, "tacmap-wire-obj-v3\0" || localUUID_bytes)
    /// -> base64url no pad. The relay never sees the local UUID.
    static func wireObjectId(metadataKey: Data, localUuidBytes: Data) -> String {
        let key = SymmetricKey(data: metadataKey)
        var hmac = HMAC<SHA256>(key: key)
        hmac.update(data: Data("tacmap-wire-obj-v3\0".utf8))
        hmac.update(data: localUuidBytes)
        return Data(hmac.finalize()).base64URLEncodedStringNoPad()
    }

    /// Deterministic binary preimage for Ed25519 signing (ADR-001 section 5).
    static func buildPreimage(
        domain: UInt8,
        roomIdRaw: Data,
        actorId: String,
        sessionDomain: Data,
        counterHex16: String,
        objectId: String,
        kind: String,
        payloadHash: Data
    ) -> Data {
        let actorIdBytes = Data(actorId.utf8)
        let objectIdBytes = Data(objectId.utf8)
        let kindBytes = Data(kind.utf8)
        let counterBytes = Data(counterHex16.utf8)

        var buf = Data()
        buf.reserveCapacity(1 + 1 + 32 + 2 + actorIdBytes.count + 32 + 16 +
                            2 + objectIdBytes.count + 1 + kindBytes.count + 32)
        buf.append(domain)
        buf.append(protocolVersion)
        buf.append(roomIdRaw)
        buf.appendUInt16LE(UInt16(actorIdBytes.count))
        buf.append(actorIdBytes)
        buf.append(sessionDomain)
        buf.append(counterBytes)
        buf.appendUInt16LE(UInt16(objectIdBytes.count))
        buf.append(objectIdBytes)
        buf.append(UInt8(kindBytes.count))
        buf.append(kindBytes)
        buf.append(payloadHash)

        return buf
    }

    static func sha256(_ data: Data) -> Data {
        Data(SHA256.hash(data: data))
    }

    typealias SessionRandomFill = (UnsafeMutableRawBufferPointer) -> OSStatus

    static func generateSessionDomain(
        randomFill: SessionRandomFill = { buffer in
            guard let baseAddress = buffer.baseAddress else { return errSecParam }
            return SecRandomCopyBytes(kSecRandomDefault, buffer.count, baseAddress)
        }
    ) throws -> Data {
        var raw = Data(count: 32)
        let status = raw.withUnsafeMutableBytes(randomFill)
        guard status == errSecSuccess else {
            raw.resetBytes(in: raw.startIndex..<raw.endIndex)
            throw SessionRandomError.generationFailed(status)
        }
        let sessionDomain = sha256(raw)
        raw.resetBytes(in: raw.startIndex..<raw.endIndex)
        return sessionDomain
    }

    /// Convert a UUID to its 16 raw bytes (big-endian).
    static func uuidToBytes(_ uuid: UUID) -> Data {
        withUnsafeBytes(of: uuid.uuid) { Data($0) }
    }

    static func hexToBytes(_ hex: String) -> Data {
        var data = Data(capacity: hex.count / 2)
        var index = hex.startIndex
        while index < hex.endIndex {
            let nextIndex = hex.index(index, offsetBy: 2)
            guard let byte = UInt8(hex[index..<nextIndex], radix: 16) else { break }
            data.append(byte)
            index = nextIndex
        }
        return data
    }

    static func bytesToHex(_ data: Data) -> String {
        data.map { String(format: "%02x", $0) }.joined()
    }

    static func urlB64Encode(_ data: Data) -> String {
        data.base64URLEncodedStringNoPad()
    }

    static func urlB64Decode(_ s: String) -> Data? {
        var base64 = s.replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        let remainder = base64.count % 4
        if remainder > 0 { base64 += String(repeating: "=", count: 4 - remainder) }
        return Data(base64Encoded: base64)
    }

    /// Strict canonical decoding for the 32-byte base64url values used by v3.
    /// Foundation's decoder is intentionally permissive, which is useful for
    /// general data but inappropriate for self-certifying wire identities.
    static func decodeCanonical32(_ value: String) -> Data? {
        guard value.count == 43,
              value.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-" || $0 == "_") }),
              let raw = urlB64Decode(value), raw.count == 32,
              urlB64Encode(raw) == value else { return nil }
        return raw
    }

    static func actorBindingIsValid(actorId: String, publicKey: String, roomIdRaw: Data) -> Bool {
        guard let pubRaw = decodeCanonical32(publicKey),
              decodeCanonical32(actorId) != nil else { return false }
        return self.actorId(roomIdRaw: roomIdRaw, pubkeyRaw: pubRaw) == actorId
    }

    /// Verify the authenticated per-WebSocket actor announcement from ADR-001.
    /// This exact helper is used by the manager and by production-path tests.
    static func verifyHello(
        actorId: String,
        publicKey: String,
        sessionDomain: String,
        versionStamp: String,
        signature: String,
        roomIdRaw: Data
    ) -> Bool {
        guard actorBindingIsValid(actorId: actorId, publicKey: publicKey, roomIdRaw: roomIdRaw),
              let pubRaw = decodeCanonical32(publicKey),
              let sdRaw = decodeCanonical32(sessionDomain),
              versionStamp.count == 60,
              versionStamp.dropFirst(16) == ":\(actorId)",
              let epoch = parseHelloEpoch(String(versionStamp.prefix(16))) else { return false }
        let preimage = buildPreimage(
            domain: domainHello,
            roomIdRaw: roomIdRaw,
            actorId: actorId,
            sessionDomain: sdRaw,
            counterHex16: epoch,
            objectId: "",
            kind: "hello",
            payloadHash: sha256(pubRaw)
        )
        return SyncSigning.verify(publicKey, preimage, signature)
    }

    static func parseHelloEpoch(_ value: String) -> String? {
        guard value.count == 16, value != "0000000000000000",
              value.range(of: "^[0-9a-f]{16}$", options: .regularExpression) != nil,
              UInt64(value, radix: 16) != nil else { return nil }
        return value
    }

    /// Session-bound proof emitted only for a user's deliberate Leave action.
    /// An abnormal transport close cannot forge this distinction.
    static func explicitLeavePreimage(
        roomIdRaw: Data,
        actorId: String,
        sessionDomain: Data,
        helloVersion: String
    ) -> Data? {
        guard helloVersion.count == 60,
              helloVersion.dropFirst(16) == ":\(actorId)",
              let epoch = parseHelloEpoch(String(helloVersion.prefix(16))) else { return nil }
        return buildPreimage(
            domain: domainHello,
            roomIdRaw: roomIdRaw,
            actorId: actorId,
            sessionDomain: sessionDomain,
            counterHex16: epoch,
            objectId: "",
            kind: explicitLeaveKind,
            payloadHash: sha256(Data())
        )
    }

    static func helloAckMatches(actorId: String, sessionDomain: Data, expectedVersion: String,
                                frameActorId: String, frameSessionDomain: String, frameVersion: String) -> Bool {
        frameActorId == actorId && frameSessionDomain == urlB64Encode(sessionDomain) && frameVersion == expectedVersion
    }
}

/// Opaque, DEK-bound local filenames for per-room sealed state. Room IDs are
/// intentionally retained inside the authenticated store label, but never in
/// filesystem metadata where another local process or backup index could list
/// them without opening mission ciphertext.
enum SyncLocalStore {
    enum Domain: String {
        case chat = "tacmap-chat"
        case replay = "sync-replay"
    }

    enum StoreNameError: Error {
        case invalidKey
        case invalidRoomIdentity
    }

    private static let prefix = Data("tacmap-local-room-store-v1\0".utf8)
    private static let maximumRoomIdentityBytes = 4_096
    private static let migrationLock = NSLock()

    /// Proactively removes room identifiers left in filenames by pre-2.0
    /// builds. This runs after the mission DEK is available so histories for
    /// rooms that are not rejoined still receive the same opaque naming as the
    /// active room.
    @discardableResult
    static func migrateAllLegacyStores(
        applicationSupportDirectory: URL,
        fileManager: FileManager = .default
    ) throws -> Int {
        guard pathExistsWithoutFollowingLinks(
            applicationSupportDirectory,
            fileManager: fileManager
        ) else { return 0 }
        try requireSafeStoreDirectory(
            applicationSupportDirectory,
            create: false,
            fileManager: fileManager
        )
        var dataKey = try SafeStore.keyProvider()
        defer { dataKey.resetBytes(in: dataKey.startIndex..<dataKey.endIndex) }
        let stores: [(String, Domain)] = [
            ("tacmap_chat", .chat),
            ("sync_replay", .replay),
        ]
        var migrated = 0
        var firstFailure: Error?
        for (directoryName, domain) in stores {
            do {
                migrated += try migrateLegacyFiles(
                    directory: applicationSupportDirectory.appendingPathComponent(
                        directoryName,
                        isDirectory: true
                    ),
                    domain: domain,
                    dataKey: dataKey,
                    fileManager: fileManager
                )
            } catch {
                if firstFailure == nil { firstFailure = error }
            }
        }
        if let firstFailure { throw firstFailure }
        return migrated
    }

    /// Scan one legacy store directory without trusting arbitrary filenames.
    /// Only canonical 32-byte base64url room IDs and the exact historical data,
    /// marker, temporary, and quarantine suffixes are eligible. Each room is
    /// resolved independently so an interrupted prior pass is naturally
    /// resumable.
    @discardableResult
    static func migrateLegacyFiles(
        directory: URL,
        domain: Domain,
        dataKey: Data,
        fileManager: FileManager = .default
    ) throws -> Int {
        guard pathExistsWithoutFollowingLinks(directory, fileManager: fileManager) else {
            return 0
        }
        try requireSafeStoreDirectory(directory, create: false, fileManager: fileManager)

        let roomIds = Set(
            try fileManager.contentsOfDirectory(atPath: directory.path)
                .compactMap { legacyRoomId(from: $0) }
        ).sorted()
        var migrated = 0
        var firstFailure: Error?
        for roomId in roomIds {
            do {
                _ = try resolveFile(
                    directory: directory,
                    roomId: roomId,
                    domain: domain,
                    dataKey: dataKey,
                    fileManager: fileManager
                )
                migrated += 1
            } catch {
                if firstFailure == nil { firstFailure = error }
            }
        }
        if let firstFailure { throw firstFailure }
        return migrated
    }

    static func fileName(dataKey: Data, roomId: String, domain: Domain) throws -> String {
        let roomBytes = Data(roomId.utf8)
        guard dataKey.count == 32 else { throw StoreNameError.invalidKey }
        guard !roomBytes.isEmpty, roomBytes.count <= maximumRoomIdentityBytes else {
            throw StoreNameError.invalidRoomIdentity
        }
        var input = Data(capacity: prefix.count + domain.rawValue.utf8.count + 1 + roomBytes.count)
        input.append(prefix)
        input.append(Data(domain.rawValue.utf8))
        input.append(0)
        input.append(roomBytes)
        let digest = HMAC<SHA256>.authenticationCode(
            for: input,
            using: SymmetricKey(data: dataKey)
        )
        return "v1_\(Data(digest).base64URLEncodedStringNoPad()).json"
    }

    /// Resolve the current opaque path and atomically adopt the former
    /// `<roomId>.json` file when it is a safe single legacy path component.
    /// A pre-existing current file always wins; a distinct legacy file is moved
    /// to an opaque conflict backup so neither copy is overwritten or leaked by
    /// its name.
    static func resolveFile(
        directory: URL,
        roomId: String,
        domain: Domain,
        dataKey: Data,
        fileManager: FileManager = .default
    ) throws -> URL {
        migrationLock.lock()
        defer { migrationLock.unlock() }
        try requireSafeStoreDirectory(directory, create: true, fileManager: fileManager)
        let target = directory.appendingPathComponent(
            try fileName(dataKey: dataKey, roomId: roomId, domain: domain),
            isDirectory: false
        )
        try requireSafeChild(target, of: directory, fileManager: fileManager)
        try rejectSymbolicLink(target, fileManager: fileManager)
        guard let legacyName = safeLegacyFileName(roomId) else { return target }
        let legacy = directory.appendingPathComponent(legacyName, isDirectory: false)
        let legacyMarker = legacy.appendingPathExtension("sealed-only")
        let targetMarker = target.appendingPathExtension("sealed-only")
        for path in [legacy, legacyMarker, targetMarker] {
            try requireSafeChild(path, of: directory, fileManager: fileManager)
            try rejectSymbolicLink(path, fileManager: fileManager)
        }

        if domain == .replay {
            for path in [target, legacy]
                where fileManager.fileExists(atPath: path.path) {
                _ = try SafeStore.resealValidatedLegacyPlaintext(
                    at: path,
                    label: storeLabel(roomId: roomId, domain: domain),
                    key: dataKey,
                    validate: {
                        try SyncReplayState.validateLegacyPlaintextForMigration(
                            $0,
                            roomId: roomId
                        )
                    }
                )
            }
        }

        var adoptedBase = target

        if fileManager.fileExists(atPath: legacy.path) {
            if fileManager.fileExists(atPath: target.path) {
                // Marker-first makes interruption recoverable: on retry the
                // marker-only conflict slot is reused for the still-legacy data.
                let conflict = try pendingConflictBase(
                    directory: directory,
                    target: target,
                    fileManager: fileManager
                ) ?? target.appendingPathExtension("legacy-conflict-\(UUID().uuidString)")
                try requireSafeChild(conflict, of: directory, fileManager: fileManager)
                try rejectSymbolicLink(conflict, fileManager: fileManager)
                try SafeStore.prepareSealedPathMigration(
                    from: legacy,
                    to: conflict,
                    label: storeLabel(roomId: roomId, domain: domain),
                    key: dataKey
                )
                if fileManager.fileExists(atPath: legacyMarker.path) {
                    try fileManager.moveItem(
                        at: legacyMarker,
                        to: conflict.appendingPathExtension("sealed-only")
                    )
                }
                try fileManager.moveItem(at: legacy, to: conflict)
                adoptedBase = conflict
            } else {
                try SafeStore.prepareSealedPathMigration(
                    from: legacy,
                    to: target,
                    label: storeLabel(roomId: roomId, domain: domain),
                    key: dataKey
                )
                try fileManager.moveItem(at: legacy, to: target)
            }
        } else if fileManager.fileExists(atPath: legacyMarker.path),
                  !fileManager.fileExists(atPath: target.path) {
            try SafeStore.prepareSealedPathMigration(
                from: legacy,
                to: target,
                label: storeLabel(roomId: roomId, domain: domain),
                key: dataKey
            )
        }

        // Retry marker adoption independently: a crash after the data rename
        // cannot strand the old room ID in a marker filename forever. Older
        // builds moved data first, so bind to the unique unmarked conflict.
        if fileManager.fileExists(atPath: legacyMarker.path) {
            let recoveryBase = try uniqueConflictDataWithoutMarker(
                directory: directory,
                target: target,
                fileManager: fileManager
            )
            let destinationMarker = (recoveryBase ?? target)
                .appendingPathExtension("sealed-only")
            adoptedBase = recoveryBase ?? adoptedBase
            try rejectSymbolicLink(destinationMarker, fileManager: fileManager)
            if fileManager.fileExists(atPath: destinationMarker.path) {
                try fileManager.removeItem(at: legacyMarker)
            } else {
                try fileManager.moveItem(at: legacyMarker, to: destinationMarker)
            }
        }
        if adoptedBase == target,
           !fileManager.fileExists(atPath: legacy.path),
           try hasLegacyCompanions(
                directory: directory,
                legacyName: legacyName,
                fileManager: fileManager
           ) {
            adoptedBase = try uniqueConflictData(
                directory: directory,
                target: target,
                fileManager: fileManager
            ) ?? target
        }
        try adoptLegacyCompanions(
            directory: directory,
            legacyName: legacyName,
            targetName: adoptedBase.lastPathComponent,
            fileManager: fileManager
        )
        return target
    }

    private static func adoptLegacyCompanions(
        directory: URL,
        legacyName: String,
        targetName: String,
        fileManager: FileManager
    ) throws {
        for name in try fileManager.contentsOfDirectory(atPath: directory.path) {
            let suffix: Substring
            let candidateSuffix = name.dropFirst(legacyName.count)
            if name.hasPrefix(legacyName), isCorruptCompanionSuffix(candidateSuffix) {
                suffix = candidateSuffix
            } else if name == legacyName + ".tmp" {
                suffix = ".tmp"
            } else {
                continue
            }
            let source = directory.appendingPathComponent(name, isDirectory: false)
            try requireSafeChild(source, of: directory, fileManager: fileManager)
            try rejectSymbolicLink(source, fileManager: fileManager)
            var destination = directory.appendingPathComponent(
                targetName + String(suffix),
                isDirectory: false
            )
            try requireSafeChild(destination, of: directory, fileManager: fileManager)
            try rejectSymbolicLink(destination, fileManager: fileManager)
            if fileManager.fileExists(atPath: destination.path) {
                destination = destination.appendingPathExtension(
                    "legacy-conflict-\(UUID().uuidString)"
                )
            }
            try fileManager.moveItem(at: source, to: destination)
        }
    }

    private static func hasLegacyCompanions(
        directory: URL,
        legacyName: String,
        fileManager: FileManager
    ) throws -> Bool {
        try fileManager.contentsOfDirectory(atPath: directory.path).contains { name in
            let suffix = name.dropFirst(legacyName.count)
            return name == legacyName + ".tmp" ||
                (name.hasPrefix(legacyName) && isCorruptCompanionSuffix(suffix))
        }
    }

    private static func requireSafeStoreDirectory(
        _ directory: URL,
        create: Bool,
        fileManager: FileManager
    ) throws {
        if isSymbolicLink(directory, fileManager: fileManager) {
            throw CocoaError(.fileReadInvalidFileName)
        }
        if !pathExistsWithoutFollowingLinks(directory, fileManager: fileManager) {
            guard create else { throw CocoaError(.fileNoSuchFile) }
            try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        }
        if isSymbolicLink(directory, fileManager: fileManager) {
            throw CocoaError(.fileReadInvalidFileName)
        }
        var isDirectory: ObjCBool = false
        guard fileManager.fileExists(atPath: directory.path, isDirectory: &isDirectory),
              isDirectory.boolValue else {
            throw CocoaError(.fileReadInvalidFileName)
        }
    }

    private static func requireSafeChild(
        _ child: URL,
        of directory: URL,
        fileManager: FileManager
    ) throws {
        guard child.deletingLastPathComponent().standardizedFileURL ==
                directory.standardizedFileURL else {
            throw CocoaError(.fileReadInvalidFileName)
        }
    }

    private static func rejectSymbolicLink(_ url: URL, fileManager: FileManager) throws {
        if isSymbolicLink(url, fileManager: fileManager) {
            throw CocoaError(.fileReadInvalidFileName)
        }
        if fileManager.fileExists(atPath: url.path) {
            let attributes = try fileManager.attributesOfItem(atPath: url.path)
            guard attributes[.type] as? FileAttributeType == .typeRegular else {
                throw CocoaError(.fileReadInvalidFileName)
            }
        }
    }

    private static func isSymbolicLink(_ url: URL, fileManager: FileManager) -> Bool {
        (try? fileManager.destinationOfSymbolicLink(atPath: url.path)) != nil
    }

    private static func pathExistsWithoutFollowingLinks(
        _ url: URL,
        fileManager: FileManager
    ) -> Bool {
        fileManager.fileExists(atPath: url.path) || isSymbolicLink(url, fileManager: fileManager)
    }

    private static func pendingConflictBase(
        directory: URL,
        target: URL,
        fileManager: FileManager
    ) throws -> URL? {
        let candidates = Set(try fileManager.contentsOfDirectory(atPath: directory.path)
            .compactMap { conflictBaseNameFromMarker($0, targetName: target.lastPathComponent) })
            .map { directory.appendingPathComponent($0, isDirectory: false) }
            .filter { !fileManager.fileExists(atPath: $0.path) }
        guard candidates.count <= 1 else { throw CocoaError(.fileReadCorruptFile) }
        if let candidate = candidates.first {
            try requireSafeChild(candidate, of: directory, fileManager: fileManager)
            try rejectSymbolicLink(
                candidate.appendingPathExtension("sealed-only"),
                fileManager: fileManager
            )
        }
        return candidates.first
    }

    private static func uniqueConflictDataWithoutMarker(
        directory: URL,
        target: URL,
        fileManager: FileManager
    ) throws -> URL? {
        let candidates = try conflictData(
            directory: directory,
            target: target,
            fileManager: fileManager
        )
            .filter {
                !fileManager.fileExists(
                    atPath: $0.appendingPathExtension("sealed-only").path
                )
            }
        guard candidates.count <= 1 else { throw CocoaError(.fileReadCorruptFile) }
        return candidates.first
    }

    private static func uniqueConflictData(
        directory: URL,
        target: URL,
        fileManager: FileManager
    ) throws -> URL? {
        let candidates = try conflictData(
            directory: directory,
            target: target,
            fileManager: fileManager
        )
        guard candidates.count <= 1 else { throw CocoaError(.fileReadCorruptFile) }
        return candidates.first
    }

    private static func conflictData(
        directory: URL,
        target: URL,
        fileManager: FileManager
    ) throws -> [URL] {
        let candidates = try fileManager.contentsOfDirectory(atPath: directory.path)
            .filter { isConflictDataName($0, targetName: target.lastPathComponent) }
            .map { directory.appendingPathComponent($0, isDirectory: false) }
        for candidate in candidates {
            try requireSafeChild(candidate, of: directory, fileManager: fileManager)
            try rejectSymbolicLink(candidate, fileManager: fileManager)
        }
        return candidates
    }

    private static func conflictBaseNameFromMarker(
        _ name: String,
        targetName: String
    ) -> String? {
        let markerSuffix = ".sealed-only"
        guard name.hasPrefix(targetName + ".legacy-conflict-"),
              name.hasSuffix(markerSuffix) else { return nil }
        let base = String(name.dropLast(markerSuffix.count))
        return isConflictDataName(base, targetName: targetName) ? base : nil
    }

    private static func isConflictDataName(_ name: String, targetName: String) -> Bool {
        let prefix = targetName + ".legacy-conflict-"
        guard name.hasPrefix(prefix) else { return false }
        return UUID(uuidString: String(name.dropFirst(prefix.count))) != nil
    }

    private static func safeLegacyFileName(_ roomId: String) -> String? {
        guard !roomId.isEmpty,
              roomId != ".", roomId != "..",
              !roomId.contains("/"), !roomId.contains("\\"),
              !roomId.contains("\0"),
              roomId.utf8.count <= 240 else { return nil }
        return "\(roomId).json"
    }

    private static func legacyRoomId(from entryName: String) -> String? {
        guard let boundary = entryName.range(of: ".json") else { return nil }
        let roomId = String(entryName[..<boundary.lowerBound])
        let suffix = String(entryName[boundary.upperBound...])
        guard suffix.isEmpty || suffix == ".tmp" || suffix == ".sealed-only" ||
              isCorruptCompanionSuffix(suffix[...]) else { return nil }
        return SyncIdentity.decodeCanonical32(roomId) == nil ? nil : roomId
    }

    private static func isCorruptCompanionSuffix(_ suffix: Substring) -> Bool {
        let corruptPrefix = ".corrupt-"
        guard suffix.hasPrefix(corruptPrefix) else { return false }
        let timestamp = suffix.dropFirst(corruptPrefix.count)
        return !timestamp.isEmpty && timestamp.allSatisfy { $0.isASCII && $0.isNumber }
    }

    private static func storeLabel(roomId: String, domain: Domain) -> String {
        switch domain {
        case .chat: return "sync/chat/\(roomId)"
        case .replay: return "sync/room/\(roomId)"
        }
    }
}

extension Data {
    mutating func appendUInt16LE(_ value: UInt16) {
        var le = value.littleEndian
        append(Data(bytes: &le, count: 2))
    }
}
