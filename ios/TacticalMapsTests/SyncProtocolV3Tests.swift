import XCTest
import CryptoKit
import Security
@testable import TacticalMaps

/// Validates iOS v3 protocol implementation against the shared fixture
/// (testdata/sync_protocol_v3.json). If these pass, iOS interoperates
/// byte-for-byte with the relay and Android.
final class SyncProtocolV3Tests: XCTestCase {

    private final class MutablePresenceSlot {
        var value: Data?
        var failWrites = false

        init(_ value: Data? = nil) { self.value = value }

        var store: DurableDefaultsDataStore {
            DurableDefaultsDataStore(
                read: { self.value },
                writeCandidate: { candidate in
                    self.value = candidate
                    return !self.failWrites
                },
                restore: { self.value = $0 }
            )
        }
    }

    /// The hosted XCTest process shares the app's real Application Support and
    /// standard defaults. Preserve only the exact artifacts touched by the live
    /// manager test, remove them while it runs, and restore them byte-for-byte.
    /// This keeps the opt-in test from leaving its fixed-key identity/replay data
    /// behind or overwriting a developer's local simulator state.
    private final class HostedAppSyncStateSnapshot {
        private enum SnapshotError: LocalizedError {
            case missingOriginalAndBackup(original: String, backup: String)
            case setupAndRestoreFailed(setup: String, restore: String, backup: String)
            case defaultsNotDurable
            case restoredDefaultMismatch(key: String)

            var errorDescription: String? {
                switch self {
                case .missingOriginalAndBackup(let original, let backup):
                    return "Could not restore \(original): its backup is missing at \(backup)."
                case .setupAndRestoreFailed(let setup, let restore, let backup):
                    return "Live-test setup failed (\(setup)) and restoration also failed "
                        + "(\(restore)). Recovery backup retained at \(backup)."
                case .defaultsNotDurable:
                    return "Restored UserDefaults could not be synchronized to durable storage."
                case .restoredDefaultMismatch(let key):
                    return "Restored UserDefaults value does not match the saved value for \(key)."
                }
            }
        }

        private let defaults = UserDefaults.standard
        private let defaultKeys = [
            "sync.clientId", "sync.deviceSeed", "sync.presenceConfig", "opsec.relayURL"
        ]
        private var savedDefaults: [String: Any] = [:]
        private let files: [URL]
        private let replayDirectory: URL
        private let replayDirectoryExisted: Bool
        private let backupDirectory: URL
        private let preexistingFilePaths: Set<String>
        private var movedFiles: [(original: URL, backup: URL)] = []
        private var restoredFilePaths: Set<String> = []
        private var restored = false

        var recoveryBackupPath: String { backupDirectory.path }

        init(roomId: String) throws {
            let support = try XCTUnwrap(
                FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first)
            replayDirectory = support.appendingPathComponent("sync_replay", isDirectory: true)
            replayDirectoryExisted = FileManager.default.fileExists(atPath: replayDirectory.path)
            let chatDirectory = support.appendingPathComponent("tacmap_chat", isDirectory: true)
            files = [
                support.appendingPathComponent("waypoints.json"),
                support.appendingPathComponent("drawings.json"),
                support.appendingPathComponent("sync_model_revisions.json"),
                // Opaque replay filenames are DEK-derived. Move the complete
                // directory so the test neither needs nor leaks the host key.
                replayDirectory,
                // Move the complete directory, not just this run's room file.
                // That keeps every pre-existing Chat history and any legacy or
                // quarantine companion byte-for-byte intact while the live test
                // uses its fixed at-rest key.
                chatDirectory
            ]
            backupDirectory = FileManager.default.temporaryDirectory
                .appendingPathComponent("tacmap-live-sync-backup-\(UUID().uuidString)", isDirectory: true)
            preexistingFilePaths = Set(files.compactMap { file in
                FileManager.default.fileExists(atPath: file.path)
                    ? file.standardizedFileURL.path : nil
            })
            for key in defaultKeys {
                if let value = defaults.object(forKey: key) { savedDefaults[key] = value }
            }

            do {
                try FileManager.default.createDirectory(at: backupDirectory, withIntermediateDirectories: true)
                for key in defaultKeys {
                    defaults.removeObject(forKey: key)
                }
                defaults.synchronize()
                for (index, original) in files.enumerated()
                    where FileManager.default.fileExists(atPath: original.path) {
                    let backup = backupDirectory.appendingPathComponent(String(index))
                    try FileManager.default.moveItem(at: original, to: backup)
                    movedFiles.append((original, backup))
                }
            } catch {
                let setupError = error
                do {
                    try restore()
                } catch {
                    throw SnapshotError.setupAndRestoreFailed(
                        setup: setupError.localizedDescription,
                        restore: error.localizedDescription,
                        backup: backupDirectory.path
                    )
                }
                throw error
            }
        }

        func restore() throws {
            guard !restored else { return }
            let fm = FileManager.default

            // Remove only artifacts created by the live test. A pre-existing
            // artifact whose initial move failed must never be mistaken for
            // test output during the initializer's rollback path.
            for file in files
                where !preexistingFilePaths.contains(file.standardizedFileURL.path)
                    && fm.fileExists(atPath: file.path) {
                try fm.removeItem(at: file)
            }

            // Restoration is retry-safe. Entries already moved back during a
            // partial attempt are verified but never deleted on the retry.
            for entry in movedFiles {
                let originalPath = entry.original.standardizedFileURL.path
                if fm.fileExists(atPath: entry.backup.path) {
                    if fm.fileExists(atPath: entry.original.path) {
                        try fm.removeItem(at: entry.original)
                    }
                    try fm.createDirectory(
                        at: entry.original.deletingLastPathComponent(),
                        withIntermediateDirectories: true
                    )
                    try fm.moveItem(at: entry.backup, to: entry.original)
                    restoredFilePaths.insert(originalPath)
                } else if restoredFilePaths.contains(originalPath),
                          fm.fileExists(atPath: entry.original.path) {
                    continue
                } else {
                    throw SnapshotError.missingOriginalAndBackup(
                        original: entry.original.path,
                        backup: entry.backup.path
                    )
                }
            }
            for file in files where preexistingFilePaths.contains(file.standardizedFileURL.path) {
                guard fm.fileExists(atPath: file.path) else {
                    let backup = movedFiles.first {
                        $0.original.standardizedFileURL.path == file.standardizedFileURL.path
                    }?.backup.path ?? backupDirectory.path
                    throw SnapshotError.missingOriginalAndBackup(
                        original: file.path,
                        backup: backup
                    )
                }
            }
            for key in defaultKeys { defaults.removeObject(forKey: key) }
            for (key, value) in savedDefaults { defaults.set(value, forKey: key) }
            guard defaults.synchronize() else { throw SnapshotError.defaultsNotDurable }
            for key in defaultKeys {
                let actual = defaults.object(forKey: key)
                if let expected = savedDefaults[key] {
                    guard let actual,
                          (actual as? NSObject)?.isEqual(expected) == true else {
                        throw SnapshotError.restoredDefaultMismatch(key: key)
                    }
                } else if actual != nil {
                    throw SnapshotError.restoredDefaultMismatch(key: key)
                }
            }
            if !replayDirectoryExisted, fm.fileExists(atPath: replayDirectory.path),
               try fm.contentsOfDirectory(atPath: replayDirectory.path).isEmpty {
                try fm.removeItem(at: replayDirectory)
            }
            if fm.fileExists(atPath: backupDirectory.path) {
                try fm.removeItem(at: backupDirectory)
            }
            restored = true
        }

        deinit { try? restore() }
    }

    private lazy var fixture: [String: Any] = {
        var dir = URL(fileURLWithPath: #file).deletingLastPathComponent()
        for _ in 0..<8 {
            let f = dir.appendingPathComponent("testdata/sync_protocol_v3.json")
            if FileManager.default.fileExists(atPath: f.path),
               let data = try? Data(contentsOf: f),
               let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                return obj
            }
            dir = dir.deletingLastPathComponent()
        }
        XCTFail("Could not locate testdata/sync_protocol_v3.json")
        return [:]
    }()

    private lazy var localStoreFixture: [String: Any] = {
        var dir = URL(fileURLWithPath: #file).deletingLastPathComponent()
        for _ in 0..<8 {
            let file = dir.appendingPathComponent("testdata/local_store_name_v1.json")
            if FileManager.default.fileExists(atPath: file.path),
               let data = try? Data(contentsOf: file),
               let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                return object
            }
            dir = dir.deletingLastPathComponent()
        }
        XCTFail("Could not locate testdata/local_store_name_v1.json")
        return [:]
    }()

    // MARK: - Key derivation

    func testV3KeyDerivationMatchesFixture() {
        let kd = fixture["key_derivation"] as! [String: Any]
        let joinCode = kd["join_code"] as! String
        let keys = SyncCrypto.deriveRoomV3(joinCode)

        XCTAssertEqual(keys.roomId, kd["room_id"] as? String)
        XCTAssertEqual(hex(keys.roomIdRaw), kd["room_id_raw_hex"] as? String)
        XCTAssertEqual(hex(keys.roomKey.withUnsafeBytes { Data($0) }), kd["room_key_hex"] as? String)
        XCTAssertEqual(hex(keys.metadataKey), kd["metadata_key_hex"] as? String)
        XCTAssertEqual(keys.authToken, kd["auth_token_base64url"] as? String)
    }

    // MARK: - Actor ID

    func testActorIdMatchesFixture() {
        let kd = fixture["key_derivation"] as! [String: Any]
        let identity = fixture["identity"] as! [String: Any]
        let roomIdRaw = SyncIdentity.hexToBytes(kd["room_id_raw_hex"] as! String)

        let devA = identity["device_a"] as! [String: Any]
        let pubA = SyncIdentity.hexToBytes(devA["pubkey_raw_hex"] as! String)
        XCTAssertEqual(SyncIdentity.actorId(roomIdRaw: roomIdRaw, pubkeyRaw: pubA),
                       devA["actor_id"] as? String)

        let devB = identity["device_b"] as! [String: Any]
        let pubB = SyncIdentity.hexToBytes(devB["pubkey_raw_hex"] as! String)
        XCTAssertEqual(SyncIdentity.actorId(roomIdRaw: roomIdRaw, pubkeyRaw: pubB),
                       devB["actor_id"] as? String)
    }

    func testCrossRoomCorrelationPrevented() {
        let identity = fixture["identity"] as! [String: Any]
        let devA = identity["device_a"] as! [String: Any]
        let cross = identity["cross_room_correlation"] as! [String: Any]

        let pubA = SyncIdentity.hexToBytes(devA["pubkey_raw_hex"] as! String)
        let altKeys = SyncCrypto.deriveRoomV3(cross["alt_join_code"] as! String)
        let altActorId = SyncIdentity.actorId(roomIdRaw: altKeys.roomIdRaw, pubkeyRaw: pubA)

        XCTAssertEqual(altActorId, cross["device_a_actor_id_in_alt_room"] as? String)
        XCTAssertNotEqual(altActorId, devA["actor_id"] as? String)
    }

    func testOpaqueLocalStoreNamesMatchSharedFixtureAndArePathSafe() throws {
        let key = SyncIdentity.hexToBytes(localStoreFixture["data_key_hex"] as! String)
        let room = localStoreFixture["room_id"] as! String
        let chat = try SyncLocalStore.fileName(dataKey: key, roomId: room, domain: .chat)
        let replay = try SyncLocalStore.fileName(dataKey: key, roomId: room, domain: .replay)

        XCTAssertEqual(chat, localStoreFixture["chat_file"] as? String)
        XCTAssertEqual(replay, localStoreFixture["replay_file"] as? String)
        XCTAssertNotEqual(chat, replay, "the store purpose must be domain separated")
        XCTAssertFalse(chat.contains(room))
        XCTAssertNotEqual(
            chat,
            try SyncLocalStore.fileName(dataKey: key, roomId: room + "-other", domain: .chat)
        )
        XCTAssertNotEqual(
            chat,
            try SyncLocalStore.fileName(
                dataKey: Data(repeating: 0xff, count: 32), roomId: room, domain: .chat
            )
        )
        XCTAssertNotNil(chat.range(of: #"^v1_[A-Za-z0-9_-]{43}\.json$"#,
                                   options: .regularExpression))

        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("opaque-store-path-\(UUID().uuidString)", isDirectory: true)
        let directory = root.appendingPathComponent("inside", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let maliciousRoom = "../../outside-room"
        let resolved = try SyncLocalStore.resolveFile(
            directory: directory,
            roomId: maliciousRoom,
            domain: .replay,
            dataKey: key
        )
        XCTAssertEqual(resolved.deletingLastPathComponent().standardizedFileURL,
                       directory.standardizedFileURL)
        XCTAssertFalse(resolved.lastPathComponent.contains("outside-room"))
        XCTAssertThrowsError(try SyncLocalStore.fileName(
            dataKey: Data(repeating: 1, count: 31), roomId: room, domain: .chat
        ))
        XCTAssertThrowsError(try SyncLocalStore.fileName(
            dataKey: key, roomId: "", domain: .chat
        ))
    }

    func testColdUpgradeMigratesEveryInactiveLegacyRoomAcrossBothStores() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("opaque-store-cold-upgrade-\(UUID().uuidString)", isDirectory: true)
        let chatDirectory = root.appendingPathComponent("tacmap_chat", isDirectory: true)
        let replayDirectory = root.appendingPathComponent("sync_replay", isDirectory: true)
        try FileManager.default.createDirectory(at: chatDirectory, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: replayDirectory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }

        let previousKeyProvider = SafeStore.keyProvider
        let key = Data(repeating: 0x6a, count: 32)
        SafeStore.keyProvider = { key }
        SealedMigrationPolicy.resetForTests(key: key)
        defer {
            SafeStore.keyProvider = previousKeyProvider
            SealedMigrationPolicy.resetForTests(key: key)
        }

        let rooms = [
            SyncIdentity.urlB64Encode(Data(repeating: 0x31, count: 32)),
            SyncIdentity.urlB64Encode(Data(repeating: 0x32, count: 32)),
        ]
        for room in rooms {
            try SafeStore.write(
                Data("chat-\(room)".utf8),
                to: chatDirectory.appendingPathComponent("\(room).json"),
                label: "sync/chat/\(room)"
            )
            try SafeStore.write(
                Data("replay-\(room)".utf8),
                to: replayDirectory.appendingPathComponent("\(room).json"),
                label: "sync/room/\(room)"
            )
        }

        XCTAssertEqual(
            try SyncLocalStore.migrateAllLegacyStores(
                applicationSupportDirectory: root
            ),
            4
        )
        for room in rooms {
            for (directory, domain) in [
                (chatDirectory, SyncLocalStore.Domain.chat),
                (replayDirectory, SyncLocalStore.Domain.replay),
            ] {
                let opaque = directory.appendingPathComponent(
                    try SyncLocalStore.fileName(dataKey: key, roomId: room, domain: domain)
                )
                XCTAssertTrue(FileManager.default.fileExists(atPath: opaque.path))
                XCTAssertFalse(FileManager.default.fileExists(
                    atPath: directory.appendingPathComponent("\(room).json").path
                ))
            }
        }
        let remainingNames = try [chatDirectory, replayDirectory]
            .flatMap { try FileManager.default.contentsOfDirectory(atPath: $0.path) }
        for room in rooms {
            XCTAssertFalse(remainingNames.contains(where: { $0.contains(room) }))
        }
    }

    func testLockedColdUpgradeMakesNoChangesAndMalformedNamesStayUntouched() throws {
        enum Expected: Error { case locked }

        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("opaque-store-locked-upgrade-\(UUID().uuidString)", isDirectory: true)
        let chatDirectory = root.appendingPathComponent("tacmap_chat", isDirectory: true)
        try FileManager.default.createDirectory(at: chatDirectory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }

        let room = SyncIdentity.urlB64Encode(Data(repeating: 0x41, count: 32))
        let legacy = chatDirectory.appendingPathComponent("\(room).json")
        let legacyBytes = Data("locked-history".utf8)
        try legacyBytes.write(to: legacy)
        let malformed = [
            "not-a-room.json",
            "\(room).json.backup",
            "\(room).json.corrupt-",
            "\(room).json.corrupt-not-a-timestamp",
            "\(room).json.sealed-only.backup",
            "v1_\(room).json",
            "\(room).txt",
        ]
        for name in malformed {
            try Data(name.utf8).write(to: chatDirectory.appendingPathComponent(name))
        }
        let namesBefore = try FileManager.default.contentsOfDirectory(atPath: chatDirectory.path).sorted()

        let previousKeyProvider = SafeStore.keyProvider
        SafeStore.keyProvider = { throw Expected.locked }
        defer { SafeStore.keyProvider = previousKeyProvider }
        XCTAssertThrowsError(try SyncLocalStore.migrateAllLegacyStores(
            applicationSupportDirectory: root
        ))
        XCTAssertEqual(
            try FileManager.default.contentsOfDirectory(atPath: chatDirectory.path).sorted(),
            namesBefore
        )
        XCTAssertEqual(try Data(contentsOf: legacy), legacyBytes)

        let key = Data(repeating: 0x42, count: 32)
        SafeStore.keyProvider = { key }
        XCTAssertEqual(try SyncLocalStore.migrateAllLegacyStores(
            applicationSupportDirectory: root
        ), 1)
        for name in malformed {
            XCTAssertTrue(FileManager.default.fileExists(
                atPath: chatDirectory.appendingPathComponent(name).path
            ))
        }
        XCTAssertFalse(FileManager.default.fileExists(atPath: legacy.path))
    }

    func testInterruptedLegacyRenameResumesMarkerAndCompanionAdoption() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("opaque-store-resume-\(UUID().uuidString)", isDirectory: true)
        let directory = root.appendingPathComponent("tacmap_chat", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }

        let previousKeyProvider = SafeStore.keyProvider
        let key = Data(repeating: 0x51, count: 32)
        SafeStore.keyProvider = { key }
        SealedMigrationPolicy.resetForTests(key: key)
        defer {
            SafeStore.keyProvider = previousKeyProvider
            SealedMigrationPolicy.resetForTests(key: key)
        }
        let room = SyncIdentity.urlB64Encode(Data(repeating: 0x52, count: 32))
        let legacy = directory.appendingPathComponent("\(room).json")
        try SafeStore.write(Data("history".utf8), to: legacy, label: "sync/chat/\(room)")
        let target = directory.appendingPathComponent(
            try SyncLocalStore.fileName(dataKey: key, roomId: room, domain: .chat)
        )
        try FileManager.default.moveItem(at: legacy, to: target)

        let legacyMarker = legacy.appendingPathExtension("sealed-only")
        let legacyCompanion = legacy.appendingPathExtension("corrupt-123")
        try Data([1]).write(to: legacyMarker)
        try Data("quarantine".utf8).write(to: legacyCompanion)

        XCTAssertEqual(try SyncLocalStore.migrateAllLegacyStores(
            applicationSupportDirectory: root
        ), 1)
        XCTAssertTrue(FileManager.default.fileExists(atPath: target.path))
        XCTAssertTrue(FileManager.default.fileExists(
            atPath: target.appendingPathExtension("sealed-only").path
        ))
        XCTAssertEqual(
            try Data(contentsOf: target.appendingPathExtension("corrupt-123")),
            Data("quarantine".utf8)
        )
        XCTAssertFalse(try FileManager.default.contentsOfDirectory(atPath: directory.path)
            .contains(where: { $0.contains(room) }))
    }

    func testInterruptedConflictKeepsMarkerAndCompanionsBoundToLegacyCopy() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("opaque-store-conflict-resume-\(UUID().uuidString)")
        let directory = root.appendingPathComponent("tacmap_chat", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }

        let key = Data(repeating: 0x61, count: 32)
        let room = SyncIdentity.urlB64Encode(Data(repeating: 0x62, count: 32))
        let target = directory.appendingPathComponent(
            try SyncLocalStore.fileName(dataKey: key, roomId: room, domain: .chat)
        )
        try Data("current".utf8).write(to: target)
        let legacy = directory.appendingPathComponent("\(room).json")
        try Data("legacy".utf8).write(to: legacy)
        let companion = legacy.appendingPathExtension("corrupt-123")
        try Data("legacy quarantine".utf8).write(to: companion)

        // Marker-first interruption: the marker reached an opaque conflict
        // slot while legacy data remained at the historical path.
        let conflict = target.appendingPathExtension("legacy-conflict-\(UUID().uuidString)")
        let conflictMarker = conflict.appendingPathExtension("sealed-only")
        try Data([1]).write(to: conflictMarker)
        _ = try SyncLocalStore.resolveFile(
            directory: directory,
            roomId: room,
            domain: .chat,
            dataKey: key
        )
        XCTAssertEqual(try Data(contentsOf: conflict), Data("legacy".utf8))
        XCTAssertTrue(FileManager.default.fileExists(atPath: conflictMarker.path))
        XCTAssertEqual(
            try Data(contentsOf: conflict.appendingPathExtension("corrupt-123")),
            Data("legacy quarantine".utf8)
        )
        XCTAssertFalse(FileManager.default.fileExists(
            atPath: target.appendingPathExtension("corrupt-123").path
        ))

        // A crash after data+marker but before companion adoption still
        // resumes against the unique conflict copy.
        try Data("late quarantine".utf8).write(
            to: legacy.appendingPathExtension("corrupt-456")
        )
        _ = try SyncLocalStore.resolveFile(
            directory: directory,
            roomId: room,
            domain: .chat,
            dataKey: key
        )
        XCTAssertEqual(
            try Data(contentsOf: conflict.appendingPathExtension("corrupt-456")),
            Data("late quarantine".utf8)
        )

        // Former data-first interruption: adopt the raw marker into the one
        // unmarked conflict copy, never onto the winning current file.
        let olderConflict = target.appendingPathExtension(
            "legacy-conflict-\(UUID().uuidString)"
        )
        try Data("older legacy".utf8).write(to: olderConflict)
        let rawMarker = legacy.appendingPathExtension("sealed-only")
        try Data([1]).write(to: rawMarker)
        _ = try SyncLocalStore.resolveFile(
            directory: directory,
            roomId: room,
            domain: .chat,
            dataKey: key
        )
        XCTAssertTrue(FileManager.default.fileExists(
            atPath: olderConflict.appendingPathExtension("sealed-only").path
        ))
        XCTAssertFalse(FileManager.default.fileExists(
            atPath: target.appendingPathExtension("sealed-only").path
        ))
        XCTAssertFalse(FileManager.default.fileExists(atPath: rawMarker.path))
    }

    func testInactivePlaintextReplayIsValidatedAndSealedBeforeOpaqueRename() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("opaque-store-plaintext-replay-\(UUID().uuidString)")
        let scratch = FileManager.default.temporaryDirectory
            .appendingPathComponent("opaque-store-replay-scratch-\(UUID().uuidString)")
        let replayDirectory = root.appendingPathComponent("sync_replay", isDirectory: true)
        try FileManager.default.createDirectory(at: replayDirectory, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: scratch, withIntermediateDirectories: true)
        defer {
            try? FileManager.default.removeItem(at: root)
            try? FileManager.default.removeItem(at: scratch)
        }

        let previousKeyProvider = SafeStore.keyProvider
        let key = Data(repeating: 0x66, count: 32)
        SafeStore.keyProvider = { key }
        SealedMigrationPolicy.resetForTests(key: key)
        defer {
            SafeStore.keyProvider = previousKeyProvider
            SealedMigrationPolicy.resetForTests(key: key)
        }
        let room = SyncIdentity.urlB64Encode(Data(repeating: 0x67, count: 32))
        try SyncReplayState(roomId: room, containerURL: scratch).save()
        let scratchCurrent = try currentReplayURL(directory: scratch, roomId: room, key: key)
        let sealed = try Data(contentsOf: scratchCurrent)
        let plaintext = try XCTUnwrap(SealedEnvelope.openFile(
            key: key,
            blob: sealed,
            label: "sync/room/\(room)"
        ))
        let legacy = replayDirectory.appendingPathComponent("\(room).json")
        try plaintext.write(to: legacy)

        XCTAssertEqual(
            try SyncLocalStore.migrateAllLegacyStores(applicationSupportDirectory: root),
            1
        )
        let current = try currentReplayURL(directory: root, roomId: room, key: key)
        XCTAssertTrue(SealedEnvelope.isSealedFile(try Data(contentsOf: current)))
        XCTAssertFalse(FileManager.default.fileExists(atPath: legacy.path))
        XCTAssertFalse(try FileManager.default.contentsOfDirectory(atPath: replayDirectory.path)
            .contains(where: { $0.contains(room) }))
        XCTAssertTrue(SyncReplayState(roomId: room, containerURL: root).load())
    }

    func testInvalidPlaintextReplayIsPreservedAndNotRenamed() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("opaque-store-invalid-replay-\(UUID().uuidString)")
        let replayDirectory = root.appendingPathComponent("sync_replay", isDirectory: true)
        try FileManager.default.createDirectory(at: replayDirectory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let previousKeyProvider = SafeStore.keyProvider
        let key = Data(repeating: 0x68, count: 32)
        SafeStore.keyProvider = { key }
        SealedMigrationPolicy.resetForTests(key: key)
        defer {
            SafeStore.keyProvider = previousKeyProvider
            SealedMigrationPolicy.resetForTests(key: key)
        }
        let room = SyncIdentity.urlB64Encode(Data(repeating: 0x69, count: 32))
        let legacy = replayDirectory.appendingPathComponent("\(room).json")
        let invalid = Data(#"{"schemaVersion":3,"localCounter":"bad"}"#.utf8)
        try invalid.write(to: legacy)

        XCTAssertThrowsError(try SyncLocalStore.migrateAllLegacyStores(
            applicationSupportDirectory: root
        ))
        XCTAssertEqual(try Data(contentsOf: legacy), invalid)
        let current = try currentReplayURL(directory: root, roomId: room, key: key)
        XCTAssertFalse(FileManager.default.fileExists(atPath: current.path))
    }

    func testSymbolicLinkStoresAreRejectedWithoutTouchingExternalTargets() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("opaque-store-symlink-\(UUID().uuidString)")
        let external = FileManager.default.temporaryDirectory
            .appendingPathComponent("opaque-store-external-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: external, withIntermediateDirectories: true)
        defer {
            try? FileManager.default.removeItem(at: root)
            try? FileManager.default.removeItem(at: external)
        }
        let linkedRoot = root.appendingPathComponent("linked")
        try FileManager.default.createSymbolicLink(
            at: linkedRoot,
            withDestinationURL: external
        )
        XCTAssertThrowsError(try SyncLocalStore.migrateAllLegacyStores(
            applicationSupportDirectory: linkedRoot
        ))

        let replayDirectory = root.appendingPathComponent("sync_replay", isDirectory: true)
        try FileManager.default.createDirectory(at: replayDirectory, withIntermediateDirectories: true)
        let room = SyncIdentity.urlB64Encode(Data(repeating: 0x6a, count: 32))
        let outside = external.appendingPathComponent("outside.json")
        try Data("outside".utf8).write(to: outside)
        try FileManager.default.createSymbolicLink(
            at: replayDirectory.appendingPathComponent("\(room).json"),
            withDestinationURL: outside
        )
        let key = Data(repeating: 0x6b, count: 32)
        XCTAssertThrowsError(try SyncLocalStore.migrateLegacyFiles(
            directory: replayDirectory,
            domain: .replay,
            dataKey: key
        ))
        XCTAssertEqual(try Data(contentsOf: outside), Data("outside".utf8))
        XCTAssertFalse(FileManager.default.fileExists(
            atPath: replayDirectory.appendingPathComponent(
                try SyncLocalStore.fileName(dataKey: key, roomId: room, domain: .replay)
            ).path
        ))
    }

    func testInactiveReplayMigrationFailureDoesNotBlockRequestedRoom() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("opaque-store-active-replay-\(UUID().uuidString)")
        let replayDirectory = root.appendingPathComponent("sync_replay", isDirectory: true)
        try FileManager.default.createDirectory(
            at: replayDirectory,
            withIntermediateDirectories: true
        )
        defer { try? FileManager.default.removeItem(at: root) }
        let external = FileManager.default.temporaryDirectory
            .appendingPathComponent("inactive-replay-\(UUID().uuidString).json")
        try Data("outside".utf8).write(to: external)
        defer { try? FileManager.default.removeItem(at: external) }
        let activeRoom = SyncIdentity.urlB64Encode(Data(repeating: 0x6c, count: 32))
        let inactiveRoom = SyncIdentity.urlB64Encode(Data(repeating: 0x6d, count: 32))
        try FileManager.default.createSymbolicLink(
            at: replayDirectory.appendingPathComponent("\(inactiveRoom).json"),
            withDestinationURL: external
        )
        let key = Data(repeating: 0x6e, count: 32)
        let previousKeyProvider = SafeStore.keyProvider
        SafeStore.keyProvider = { key }
        SealedMigrationPolicy.resetForTests(key: key)
        defer {
            SafeStore.keyProvider = previousKeyProvider
            SealedMigrationPolicy.resetForTests(key: key)
        }

        XCTAssertTrue(SyncReplayState(roomId: activeRoom, containerURL: root).load())
        XCTAssertThrowsError(try SyncLocalStore.migrateLegacyFiles(
            directory: replayDirectory,
            domain: .replay,
            dataKey: key
        ))
        XCTAssertEqual(try Data(contentsOf: external), Data("outside".utf8))
    }

    func testOpaquePathMigrationRejectsPlaintextDowngradeBeforeRename() throws {
        let key = SyncIdentity.hexToBytes(localStoreFixture["data_key_hex"] as! String)
        let room = localStoreFixture["room_id"] as! String
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("opaque-store-policy-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        let previousKeyProvider = SafeStore.keyProvider
        SafeStore.keyProvider = { key }
        SealedMigrationPolicy.resetForTests(key: key)
        defer {
            SafeStore.keyProvider = previousKeyProvider
            SealedMigrationPolicy.resetForTests(key: key)
        }
        let label = "sync/room/\(room)"
        let legacy = directory.appendingPathComponent("\(room).json")
        let legacyPolicyID = "file:\(legacy.standardizedFileURL.path):\(label)"
        try SealedMigrationPolicy.markSealed(legacyPolicyID, key: key)
        // Model an attacker replacing an already-migrated legacy store with
        // syntactically valid plaintext immediately before the filename move.
        try Data("{}".utf8).write(to: legacy)

        let current = directory.appendingPathComponent(
            try SyncLocalStore.fileName(dataKey: key, roomId: room, domain: .replay)
        )
        XCTAssertThrowsError(try SyncLocalStore.resolveFile(
            directory: directory,
            roomId: room,
            domain: .replay,
            dataKey: key
        ))
        XCTAssertEqual(try Data(contentsOf: legacy), Data("{}".utf8))
        XCTAssertFalse(FileManager.default.fileExists(atPath: current.path))
    }

    func testSessionDomainGenerationChecksSecureRandomStatus() throws {
        XCTAssertThrowsError(try SyncIdentity.generateSessionDomain { _ in errSecIO }) { error in
            XCTAssertEqual(
                error as? SyncIdentity.SessionRandomError,
                .generationFailed(errSecIO)
            )
        }
        let generated = try SyncIdentity.generateSessionDomain { buffer in
            buffer.initializeMemory(as: UInt8.self, repeating: 0xa5)
            return errSecSuccess
        }
        XCTAssertEqual(generated, SyncIdentity.sha256(Data(repeating: 0xa5, count: 32)))
    }

    // MARK: - Wire object IDs

    func testWireObjectIdMatchesFixture() {
        let wids = fixture["wire_object_ids"] as! [String: Any]
        let metadataKey = SyncIdentity.hexToBytes(wids["metadata_key_hex"] as! String)
        let cases = wids["cases"] as! [[String: Any]]

        for c in cases {
            let localUuid = SyncIdentity.hexToBytes(c["local_uuid_hex"] as! String)
            XCTAssertEqual(
                SyncIdentity.wireObjectId(metadataKey: metadataKey, localUuidBytes: localUuid),
                c["wire_object_id"] as? String)
        }
    }

    // MARK: - VersionStamp

    func testVersionStampComparisonMatchesFixture() {
        let vs = fixture["version_stamp"] as! [String: Any]
        let cases = vs["comparison_cases"] as! [[String: Any]]

        for c in cases {
            let a = VersionStamp.parse(c["a"] as! String)!
            let b = VersionStamp.parse(c["b"] as! String)!
            let winner = c["winner"] as! String
            if winner == "a" {
                XCTAssertTrue(a > b, "\(c["name"]!): a should win")
            } else {
                XCTAssertTrue(b > a, "\(c["name"]!): b should win")
            }
        }
    }

    func testVersionStampRoundTrip() {
        let vs = VersionStamp(counter: 42, actorId: actorA)
        XCTAssertEqual(vs.encode(), "000000000000002a:\(actorA)")
        let parsed = VersionStamp.parse(vs.encode())!
        XCTAssertEqual(vs, parsed)
    }

    func testVersionStampMaxCounter() {
        let vs = VersionStamp(counter: VersionStamp.maxCounter, actorId: actorA)
        XCTAssertEqual(vs.encode(), "7fffffffffffffff:\(actorA)")
        let parsed = VersionStamp.parse(vs.encode())!
        XCTAssertEqual(parsed.counter, VersionStamp.maxCounter)
    }

    func testVersionStampParseBadInput() {
        XCTAssertNil(VersionStamp.parse(""))
        XCTAssertNil(VersionStamp.parse("not-a-stamp"))
        XCTAssertNil(VersionStamp.parse("0000000000000001"))
        XCTAssertNil(VersionStamp.parse("000000000000001:x"))
        XCTAssertNil(VersionStamp.parse("00000000000000001:x"))
        XCTAssertNil(VersionStamp.parse("000000000000000g:x"))
    }

    // MARK: - Signed preimage

    func testSignedPreimageMatchesFixture() {
        let sp = fixture["signed_preimage"] as! [String: Any]
        let kd = fixture["key_derivation"] as! [String: Any]
        let roomIdRaw = SyncIdentity.hexToBytes(kd["room_id_raw_hex"] as! String)
        let sessionDomain = SyncIdentity.hexToBytes(sp["session_domain_hex"] as! String)
        let cases = sp["cases"] as! [[String: Any]]

        for c in cases {
            let domainStr = c["domain_byte"] as! String
            let domainByte = UInt8(domainStr.dropFirst(2), radix: 16)!
            let actorId = c["actor_id"] as! String
            let counter = (c["counter"] as! NSNumber).int64Value
            let objectId = c["object_id"] as! String
            let kind = c["kind"] as! String
            let payloadHash = SyncIdentity.hexToBytes(c["payload_hash_hex"] as! String)

            let preimage = SyncIdentity.buildPreimage(
                domain: domainByte, roomIdRaw: roomIdRaw,
                actorId: actorId, sessionDomain: sessionDomain,
                counterHex16: VersionStamp.counterHex16(counter),
                objectId: objectId, kind: kind, payloadHash: payloadHash)

            XCTAssertEqual(hex(preimage), c["preimage_hex"] as? String,
                           "\(c["name"]!): preimage mismatch")
        }
    }

    func testSignatureVerifiesAgainstFixture() {
        let sp = fixture["signed_preimage"] as! [String: Any]
        let identity = fixture["identity"] as! [String: Any]
        let devA = identity["device_a"] as! [String: Any]
        let pubKeyB64 = devA["pubkey_base64url"] as! String
        let cases = sp["cases"] as! [[String: Any]]

        for c in cases {
            let preimageBytes = SyncIdentity.hexToBytes(c["preimage_hex"] as! String)
            let sigB64 = c["signature_base64url"] as! String
            XCTAssertTrue(SyncSigning.verify(pubKeyB64, preimageBytes, sigB64),
                          "\(c["name"]!): signature should verify")
        }
    }

    // MARK: - Auth verification

    func testAuthVerificationMatchesFixture() {
        let av = fixture["auth_verification"] as! [String: Any]
        let authTokenB64 = av["authTokenBase64url"] as! String
        let expectedRoomId = av["roomId"] as! String
        let authTokenRaw = SyncIdentity.urlB64Decode(authTokenB64)!
        var hasher = SHA256()
        hasher.update(data: Data("tacmap-room-id-v3\0".utf8))
        hasher.update(data: authTokenRaw)
        let roomIdRaw = Data(hasher.finalize())
        XCTAssertEqual(roomIdRaw.base64URLEncodedStringNoPad(), expectedRoomId)
    }

    // MARK: - Replay state

    func testReplayAcceptNewer() {
        let state = SyncReplayState(roomId: "test-room")
        XCTAssertTrue(state.advance("obj1", stamp(3, actorA)))
        XCTAssertTrue(state.advance("obj1", stamp(5, actorA)))
    }

    func testReplayRejectOlder() {
        let state = SyncReplayState(roomId: "test-room")
        XCTAssertTrue(state.advance("obj1", stamp(5, actorA)))
        XCTAssertFalse(state.advance("obj1", stamp(3, actorA)))
    }

    func testReplayRejectEqualSameActor() {
        let state = SyncReplayState(roomId: "test-room")
        XCTAssertTrue(state.advance("obj1", stamp(5, actorA)))
        XCTAssertFalse(state.advance("obj1", stamp(5, actorA)))
    }

    func testReplayAcceptEqualCounterHigherActor() {
        let state = SyncReplayState(roomId: "test-room")
        let low = min(actorA, actorB), high = max(actorA, actorB)
        XCTAssertTrue(state.advance("obj1", stamp(5, low)))
        XCTAssertTrue(state.advance("obj1", stamp(5, high)))
    }

    func testReplayTombstonePersists() {
        let state = SyncReplayState(roomId: "test-room")
        XCTAssertTrue(state.tombstone("obj1", stamp(5, actorA)))
        XCTAssertTrue(state.isTombstoned("obj1"))
        XCTAssertFalse(state.advance("obj1", stamp(3, actorB)))
        XCTAssertTrue(state.advance("obj1", stamp(7, actorB)))
        XCTAssertFalse(state.isTombstoned("obj1"))
    }

    func testCounterAdvanceWindowEnforced() {
        let state = SyncReplayState(roomId: "test-room")
        XCTAssertTrue(state.advance("obj1", stamp(100, actorA)))
        XCTAssertTrue(state.advance("obj2", stamp(10099, actorA)))
        XCTAssertFalse(state.advance("obj3", stamp(20100, actorA)))
        XCTAssertTrue(state.advance("obj3", stamp(20099, actorA)))
    }

    func testActorRegistrationRejectsKeySwap() {
        let state = SyncReplayState(roomId: "test-room")
        XCTAssertTrue(state.registerActor(actorA, pubkey: pubA))
        XCTAssertTrue(state.registerActor(actorA, pubkey: pubA))
        XCTAssertFalse(state.registerActor(actorA, pubkey: pubB))
    }

    func testPresenceCounterIsBoundToSignedSession() throws {
        let state = SyncReplayState(roomId: "test-room")
        XCTAssertTrue(try state.acceptHello(actorId: actorA, pubkey: pubA, sessionDomain: sessionDomain, epochHex: "0000000000000001"))
        XCTAssertTrue(try state.acceptPresence(actorId: actorA, sessionDomain: sessionDomain, counter: 1))
        XCTAssertTrue(try state.acceptPresence(actorId: actorA, sessionDomain: sessionDomain, counter: 5))
        XCTAssertFalse(try state.acceptPresence(actorId: actorA, sessionDomain: sessionDomain, counter: 3))
        XCTAssertFalse(try state.acceptPresence(actorId: actorA, sessionDomain: sessionDomain, counter: 5))
        let otherSession = Data(repeating: 7, count: 32).base64URLEncodedStringNoPad()
        XCTAssertTrue(try state.acceptHello(actorId: actorA, pubkey: pubA, sessionDomain: otherSession, epochHex: "0000000000000002"))
        XCTAssertTrue(try state.acceptPresence(actorId: actorA, sessionDomain: otherSession, counter: 1))
        XCTAssertFalse(try state.acceptPresence(actorId: actorA, sessionDomain: sessionDomain, counter: 6))
    }

    func testPresenceEnvelopeCarriesExactAwkwardPayloadBytes() throws {
        let payload = SyncManager.PresencePayload(
            lat: -35.281982, lon: 149.131032,
            heading: 12.3456789012345, speed: 0.0000004,
            callsign: "A/1 🛰️", affiliation: "friend",
            echelon: "team", function: "infantry", isHQ: true)
        let signed = try XCTUnwrap(SyncManager.buildPresencePayloadBytes(payload))
        var inner = SyncManager.makePresenceEnvelope(
            payload: payload, signedPayload: signed,
            publicKey: pubA, signature: "test-signature")

        XCTAssertEqual(inner["pv"] as? Int, 1)
        XCTAssertEqual(inner["p"] as? String, signed.base64EncodedString())

        // Simulate the actual sealed-JSON round trip. Even if compatibility
        // fields are independently changed, a v1 receiver must parse the exact
        // authenticated `p` bytes, not reconstruct numbers from those fields.
        inner["lat"] = 0.0
        let wire = try JSONSerialization.data(withJSONObject: inner)
        let decodedObject = try XCTUnwrap(
            JSONSerialization.jsonObject(with: wire) as? [String: Any])
        let decoded = try XCTUnwrap(SyncManager.decodePresenceEnvelope(decodedObject))
        XCTAssertEqual(decoded.signedPayload, signed)
        XCTAssertEqual(decoded.payload, payload)

        var unknownVersion = decodedObject
        unknownVersion["pv"] = 2
        XCTAssertNil(SyncManager.decodePresenceEnvelope(unknownVersion))
        var nonCanonicalBase64 = decodedObject
        nonCanonicalBase64["p"] = signed.base64EncodedString() + "\n"
        XCTAssertNil(SyncManager.decodePresenceEnvelope(nonCanonicalBase64))
    }

    func testExactPresenceEnvelopeVerifiesSharedFixtureWithoutReserializing() throws {
        let signedPreimage = fixture["signed_preimage"] as! [String: Any]
        let value = try XCTUnwrap(
            (signedPreimage["cases"] as! [[String: Any]])
                .first { $0["name"] as? String == "presence_update" })
        let identity = fixture["identity"] as! [String: Any]
        let device = identity["device_a"] as! [String: Any]
        let publicKey = device["pubkey_base64url"] as! String
        let plaintext = Data((value["plaintext"] as! String).utf8)
        let inner: [String: Any] = [
            "pv": 1,
            "p": plaintext.base64EncodedString(),
            "pub": publicKey,
            "sig": value["signature_base64url"] as! String
        ]
        let decoded = try XCTUnwrap(SyncManager.decodePresenceEnvelope(inner))
        XCTAssertEqual(decoded.signedPayload, plaintext)
        XCTAssertEqual(hex(SyncIdentity.sha256(decoded.signedPayload)),
                       value["payload_hash_hex"] as? String)
        XCTAssertNotEqual(SyncManager.buildPresencePayloadBytes(decoded.payload), plaintext,
                          "verification must use embedded bytes, not Foundation reserialization")

        let keys = fixture["key_derivation"] as! [String: Any]
        let roomIdRaw = SyncIdentity.hexToBytes(keys["room_id_raw_hex"] as! String)
        let session = SyncIdentity.hexToBytes(signedPreimage["session_domain_hex"] as! String)
        let actor = value["actor_id"] as! String
        let counter = (value["counter"] as! NSNumber).int64Value
        let preimage = SyncIdentity.buildPreimage(
            domain: SyncIdentity.domainPresence, roomIdRaw: roomIdRaw,
            actorId: actor, sessionDomain: session,
            counterHex16: VersionStamp.counterHex16(counter),
            objectId: "", kind: "loc",
            payloadHash: SyncIdentity.sha256(decoded.signedPayload))
        XCTAssertEqual(hex(preimage), value["preimage_hex"] as? String)
        XCTAssertTrue(SyncSigning.verify(
            publicKey, preimage, decoded.signature))
    }

    func testEqualHelloReconnectRestoresSessionAndRejectsPresenceReplay() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("sync-presence-reconnect-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let previousKeyProvider = SafeStore.keyProvider
        let key = Data(repeating: 0x75, count: 32)
        SafeStore.keyProvider = { key }
        SealedMigrationPolicy.resetForTests(key: key)
        defer {
            SafeStore.keyProvider = previousKeyProvider
            try? FileManager.default.removeItem(at: directory)
        }

        let room = "presence-reconnect"
        let first = SyncReplayState(roomId: room, containerURL: directory)
        XCTAssertTrue(first.load())
        XCTAssertTrue(try first.acceptHello(
            actorId: actorA, pubkey: pubA, sessionDomain: sessionDomain,
            epochHex: "0000000000000001"))
        XCTAssertTrue(try first.acceptPresence(
            actorId: actorA, sessionDomain: sessionDomain, counter: 5))

        let restarted = SyncReplayState(roomId: room, containerURL: directory)
        XCTAssertTrue(restarted.load())
        XCTAssertEqual(restarted.activeSessionDomain(actorA), sessionDomain)
        XCTAssertTrue(try restarted.acceptHello(
            actorId: actorA, pubkey: pubA, sessionDomain: sessionDomain,
            epochHex: "0000000000000001"),
            "the relay may replay the current signed hello after this client reconnects")
        XCTAssertFalse(try restarted.acceptPresence(
            actorId: actorA, sessionDomain: sessionDomain, counter: 5))
        XCTAssertTrue(try restarted.acceptPresence(
            actorId: actorA, sessionDomain: sessionDomain, counter: 6))

        let differentSession = Data(repeating: 0x33, count: 32).base64URLEncodedStringNoPad()
        XCTAssertFalse(try restarted.acceptHello(
            actorId: actorA, pubkey: pubA, sessionDomain: differentSession,
            epochHex: "0000000000000001"),
            "an equal epoch must never activate a different session domain")

        let restartedAgain = SyncReplayState(roomId: room, containerURL: directory)
        XCTAssertTrue(restartedAgain.load())
        XCTAssertFalse(try restartedAgain.acceptPresence(
            actorId: actorA, sessionDomain: sessionDomain, counter: 6))
        XCTAssertTrue(try restartedAgain.acceptPresence(
            actorId: actorA, sessionDomain: sessionDomain, counter: 7))
    }

    func testLegacyReplayFilenameMigratesWithoutLosingRollbackState() throws {
        try withReplayPersistenceSandbox(keyByte: 0x76) { directory, key in
            let room = fixtureRoomId
            let state = SyncReplayState(roomId: room, containerURL: directory)
            XCTAssertTrue(state.load())
            XCTAssertTrue(try state.acceptHello(
                actorId: actorA,
                pubkey: pubA,
                sessionDomain: sessionDomain,
                epochHex: "0000000000000001"
            ))
            XCTAssertTrue(try state.acceptPresence(
                actorId: actorA,
                sessionDomain: sessionDomain,
                counter: 4
            ))

            let current = try currentReplayURL(directory: directory, roomId: room, key: key)
            let legacy = current.deletingLastPathComponent()
                .appendingPathComponent("\(room).json")
            try FileManager.default.moveItem(at: current, to: legacy)

            let reloaded = SyncReplayState(roomId: room, containerURL: directory)
            XCTAssertTrue(reloaded.load())
            XCTAssertEqual(reloaded.activeSessionDomain(actorA), sessionDomain)
            XCTAssertFalse(try reloaded.acceptPresence(
                actorId: actorA,
                sessionDomain: sessionDomain,
                counter: 4
            ))
            XCTAssertTrue(FileManager.default.fileExists(atPath: current.path))
            XCTAssertFalse(FileManager.default.fileExists(atPath: legacy.path))
            XCTAssertFalse(current.lastPathComponent.contains(room))
        }
    }

    func testLockedNamingKeyNeverMovesLegacyReplayState() throws {
        enum Expected: Error { case unavailable }

        try withReplayPersistenceSandbox(keyByte: 0x77) { directory, key in
            let room = fixtureRoomId
            let state = SyncReplayState(roomId: room, containerURL: directory)
            try state.save()
            let current = try currentReplayURL(directory: directory, roomId: room, key: key)
            let legacy = current.deletingLastPathComponent()
                .appendingPathComponent("\(room).json")
            try FileManager.default.moveItem(at: current, to: legacy)
            let before = try Data(contentsOf: legacy)

            SafeStore.keyProvider = { throw Expected.unavailable }
            XCTAssertFalse(SyncReplayState(roomId: room, containerURL: directory).load())
            XCTAssertEqual(try Data(contentsOf: legacy), before)
            XCTAssertFalse(FileManager.default.fileExists(atPath: current.path))
            SafeStore.keyProvider = { key }
        }
    }

    func testSignedHelloProductionVerifierMatchesFixtureAndRejectsTampering() {
        let hello = (fixture["signed_preimage"] as! [String: Any])["cases"] as! [[String: Any]]
        let value = hello.first { $0["name"] as? String == "hello_announcement" }!
        let roomRaw = SyncIdentity.hexToBytes((fixture["key_derivation"] as! [String: Any])["room_id_raw_hex"] as! String)
        let sig = value["signature_base64url"] as! String
        XCTAssertTrue(SyncIdentity.verifyHello(
            actorId: actorA, publicKey: pubA, sessionDomain: sessionDomain,
            versionStamp: stamp(1, actorA).encode(), signature: sig, roomIdRaw: roomRaw))
        XCTAssertFalse(SyncIdentity.verifyHello(
            actorId: actorB, publicKey: pubA, sessionDomain: sessionDomain,
            versionStamp: stamp(1, actorB).encode(), signature: sig, roomIdRaw: roomRaw))
        let sdRaw = SyncIdentity.urlB64Decode(sessionDomain)!
        let helloVS = stamp(1, actorA).encode()
        XCTAssertTrue(SyncIdentity.helloAckMatches(actorId: actorA, sessionDomain: sdRaw, expectedVersion: helloVS,
                                                   frameActorId: actorA, frameSessionDomain: sessionDomain, frameVersion: helloVS))
        XCTAssertFalse(SyncIdentity.helloAckMatches(actorId: actorA, sessionDomain: sdRaw, expectedVersion: helloVS,
                                                    frameActorId: actorB, frameSessionDomain: sessionDomain, frameVersion: helloVS))
    }

    func testAuthenticatedSnapshotRaisesCounterAndNeverLowersFence() throws {
        let state = SyncReplayState(roomId: "test-room")
        let mutation = SyncReplayState.DurableMutation(
            wireObjectId: wireId, stamp: stamp(50_000, actorA), publicKey: pubA,
            kind: .put(contentHash: String(repeating: "a", count: 64)))
        XCTAssertEqual(try state.commitSnapshot([mutation], seq: 10), [.newlyPersisted])
        XCTAssertEqual(state.localCounter, 50_000)
        XCTAssertEqual(try state.reserveNextCounter(), 50_001)
        XCTAssertEqual(try state.commitSnapshot([], seq: 3), [])
        XCTAssertEqual(state.lastSnapshotSeq, 10)
    }

    func testPersistenceFailureRollsBackReservedCounter() throws {
        enum Expected: Error { case unavailable }
        // The persistence path derives encrypted store names before invoking the
        // injected writer, so this test needs its own key like the other store tests.
        try withReplayPersistenceSandbox(keyByte: 0x30) { directory, _ in
            var writes = 0
            let state = SyncReplayState(
                roomId: "persistence-test",
                containerURL: directory,
                persistenceWriter: { _, _, _ in
                    writes += 1
                    if writes > 1 { throw Expected.unavailable }
                })
            XCTAssertEqual(try state.reserveNextCounter(), 1)
            XCTAssertThrowsError(try state.reserveNextCounter())
            XCTAssertEqual(state.localCounter, 1, "an unsaved counter must not become sendable")
        }
    }

    func testLocalHelloEpochPinsActorAtomicallyAndSurvivesColdReload() throws {
        try withReplayPersistenceSandbox(keyByte: 0x31) { directory, _ in
            let first = SyncReplayState(roomId: fixtureRoomId, containerURL: directory)
            XCTAssertEqual(
                try first.reserveHelloEpoch(actorId: actorA, pubkey: pubA),
                "0000000000000001"
            )
            XCTAssertEqual(first.getPinnedPubkey(actorA), pubA)

            let restarted = SyncReplayState(roomId: fixtureRoomId, containerURL: directory)
            XCTAssertTrue(restarted.load(localActorId: actorA, publicKey: pubA))
            XCTAssertEqual(restarted.getPinnedPubkey(actorA), pubA)
            XCTAssertEqual(restarted.getHelloEpoch(actorA), "0000000000000001")
            XCTAssertEqual(
                try restarted.reserveHelloEpoch(actorId: actorA, pubkey: pubA),
                "0000000000000002"
            )

            let restartedAgain = SyncReplayState(roomId: fixtureRoomId, containerURL: directory)
            XCTAssertTrue(restartedAgain.load())
            XCTAssertEqual(restartedAgain.getPinnedPubkey(actorA), pubA)
            XCTAssertEqual(restartedAgain.getHelloEpoch(actorA), "0000000000000002")
        }
    }

    func testLoadSafelyRepairsLegacyLocalOrphanHelloEpochAndPersistsRepair() throws {
        try withReplayPersistenceSandbox(keyByte: 0x32) { directory, key in
            let original = SyncReplayState(roomId: fixtureRoomId, containerURL: directory)
            _ = try original.reserveHelloEpoch(actorId: actorA, pubkey: pubA)
            try removeReplayActorPin(
                actorA,
                roomId: fixtureRoomId,
                directory: directory,
                key: key
            )

            let strict = SyncReplayState(roomId: fixtureRoomId, containerURL: directory)
            XCTAssertFalse(strict.load(), "an orphan epoch is invalid without the local repair tuple")

            let repaired = SyncReplayState(roomId: fixtureRoomId, containerURL: directory)
            XCTAssertTrue(repaired.load(localActorId: actorA, publicKey: pubA))
            XCTAssertEqual(repaired.getPinnedPubkey(actorA), pubA)
            XCTAssertEqual(repaired.getHelloEpoch(actorA), "0000000000000001")

            let coldReload = SyncReplayState(roomId: fixtureRoomId, containerURL: directory)
            XCTAssertTrue(coldReload.load(), "repair must be durable before load returns success")
            XCTAssertEqual(coldReload.getPinnedPubkey(actorA), pubA)
        }
    }

    func testLoadRejectsRemoteAndMismatchedOrphanHelloEpoch() throws {
        try withReplayPersistenceSandbox(keyByte: 0x33) { directory, key in
            let remoteDirectory = directory.appendingPathComponent("remote", isDirectory: true)
            let remote = SyncReplayState(roomId: fixtureRoomId, containerURL: remoteDirectory)
            _ = try remote.reserveHelloEpoch(actorId: actorB, pubkey: pubB)
            try removeReplayActorPin(
                actorB,
                roomId: fixtureRoomId,
                directory: remoteDirectory,
                key: key
            )
            let remoteOrphan = SyncReplayState(
                roomId: fixtureRoomId,
                containerURL: remoteDirectory
            )
            XCTAssertFalse(remoteOrphan.load(localActorId: actorA, publicKey: pubA))
            XCTAssertNil(remoteOrphan.getPinnedPubkey(actorB))

            let mismatchDirectory = directory.appendingPathComponent("mismatch", isDirectory: true)
            let mismatch = SyncReplayState(roomId: fixtureRoomId, containerURL: mismatchDirectory)
            _ = try mismatch.reserveHelloEpoch(actorId: actorA, pubkey: pubA)
            try removeReplayActorPin(
                actorA,
                roomId: fixtureRoomId,
                directory: mismatchDirectory,
                key: key
            )
            let mismatchedKey = SyncReplayState(
                roomId: fixtureRoomId,
                containerURL: mismatchDirectory
            )
            XCTAssertFalse(mismatchedKey.load(localActorId: actorA, publicKey: pubB))
            XCTAssertNil(mismatchedKey.getPinnedPubkey(actorA))
        }
    }

    func testLegacyLocalOrphanRepairFailsClosedWhenPersistenceFails() throws {
        enum Expected: Error { case unavailable }

        try withReplayPersistenceSandbox(keyByte: 0x34) { directory, key in
            let original = SyncReplayState(roomId: fixtureRoomId, containerURL: directory)
            _ = try original.reserveHelloEpoch(actorId: actorA, pubkey: pubA)
            try removeReplayActorPin(
                actorA,
                roomId: fixtureRoomId,
                directory: directory,
                key: key
            )

            var repairWrites = 0
            let repair = SyncReplayState(
                roomId: fixtureRoomId,
                containerURL: directory,
                persistenceWriter: { _, _, _ in
                    repairWrites += 1
                    throw Expected.unavailable
                }
            )
            XCTAssertFalse(repair.load(localActorId: actorA, publicKey: pubA))
            XCTAssertEqual(repairWrites, 1)
            XCTAssertNil(repair.getPinnedPubkey(actorA))
            XCTAssertNil(repair.getHelloEpoch(actorA))

            let stillOrphaned = SyncReplayState(roomId: fixtureRoomId, containerURL: directory)
            XCTAssertFalse(stillOrphaned.load(), "a failed repair must not publish or persist state")
        }
    }

    func testLegacyLocalOrphanRepairRejectsSessionAndPresenceReferences() throws {
        try withReplayPersistenceSandbox(keyByte: 0x35) { directory, key in
            let original = SyncReplayState(roomId: fixtureRoomId, containerURL: directory)
            XCTAssertTrue(try original.acceptHello(
                actorId: actorA,
                pubkey: pubA,
                sessionDomain: sessionDomain,
                epochHex: "0000000000000001"
            ))
            XCTAssertTrue(try original.acceptPresence(
                actorId: actorA,
                sessionDomain: sessionDomain,
                counter: 1
            ))
            try removeReplayActorPin(
                actorA,
                roomId: fixtureRoomId,
                directory: directory,
                key: key
            )

            let repair = SyncReplayState(roomId: fixtureRoomId, containerURL: directory)
            XCTAssertFalse(repair.load(localActorId: actorA, publicKey: pubA))
            XCTAssertNil(repair.getPinnedPubkey(actorA))
            XCTAssertNil(repair.getHelloEpoch(actorA))

            let unchanged = SyncReplayState(roomId: fixtureRoomId, containerURL: directory)
            XCTAssertFalse(unchanged.load(), "rejected dependent state must not be rewritten")
        }
    }

    func testLegacyLocalOrphanRepairRejectsMutationAndPendingReferences() throws {
        try withReplayPersistenceSandbox(keyByte: 0x36) { directory, key in
            let contentHash = String(repeating: "d", count: 64)
            let durable = SyncReplayState.DurableMutation(
                wireObjectId: wireId,
                stamp: stamp(1, actorA),
                publicKey: pubA,
                kind: .put(contentHash: contentHash)
            )
            let remote = SyncReplayState.RemoteMutation(
                mutation: durable,
                priorModelHash: nil,
                localModelId: UUID().uuidString,
                acceptedGeneration: 1,
                expectedModelHash: contentHash
            )
            let original = SyncReplayState(roomId: fixtureRoomId, containerURL: directory)
            _ = try original.reserveHelloEpoch(actorId: actorA, pubkey: pubA)
            XCTAssertTrue(try original.commitRemote(remote))
            try removeReplayActorPin(
                actorA,
                roomId: fixtureRoomId,
                directory: directory,
                key: key
            )

            let repair = SyncReplayState(roomId: fixtureRoomId, containerURL: directory)
            XCTAssertFalse(repair.load(localActorId: actorA, publicKey: pubA))
            XCTAssertNil(repair.getPinnedPubkey(actorA))
            XCTAssertNil(repair.getHelloEpoch(actorA))
            XCTAssertTrue(repair.pendingRemoteMutations().isEmpty)

            let unchanged = SyncReplayState(roomId: fixtureRoomId, containerURL: directory)
            XCTAssertFalse(unchanged.load(), "rejected mutation state must not be rewritten")
        }
    }

    func testHelloEpochAndCrashRecoveryRequireExactMutationKindAndHash() throws {
        let state = SyncReplayState(roomId: fixtureRoomId)
        XCTAssertEqual(
            try state.reserveHelloEpoch(actorId: actorA, pubkey: pubA),
            "0000000000000001"
        )
        XCTAssertEqual(
            try state.reserveHelloEpoch(actorId: actorA, pubkey: pubA),
            "0000000000000002"
        )
        let hash = String(repeating: "a", count: 64)
        let put = SyncReplayState.DurableMutation(
            wireObjectId: wireId, stamp: stamp(1, actorA), publicKey: pubA,
            kind: .put(contentHash: hash))
        XCTAssertTrue(try state.commit(put))
        XCTAssertEqual(state.recoverableLocalPut(
            wireObjectId: wireId, actorId: actorA, pubkey: pubA, contentHash: hash), stamp(1, actorA))
        XCTAssertNil(state.recoverableLocalPut(
            wireObjectId: wireId, actorId: actorA, pubkey: pubA, contentHash: String(repeating: "b", count: 64)))
        let deletion = SyncReplayState.DurableMutation(
            wireObjectId: wireId, stamp: stamp(2, actorA), publicKey: pubA, kind: .delete)
        XCTAssertTrue(try state.commit(deletion))
        XCTAssertNil(state.recoverableLocalPut(
            wireObjectId: wireId, actorId: actorA, pubkey: pubA, contentHash: hash))
        XCTAssertEqual(state.recoverableLocalDeletes(actorId: actorA, pubkey: pubA).first?.1, stamp(2, actorA))
    }

    func testSnapshotReappliesOnlyExactMutationAfterPersistBeforeModelCrash() throws {
        let state = SyncReplayState(roomId: "snapshot-crash-recovery")
        let putHash = String(repeating: "1", count: 64)
        let put = SyncReplayState.DurableMutation(
            wireObjectId: wireId, stamp: stamp(1, actorA), publicKey: pubA,
            kind: .put(contentHash: putHash))

        // Simulate persistence succeeding and the process dying before model apply.
        XCTAssertTrue(try state.commit(put))
        let putResult = try state.commitSnapshot([put], seq: 1)
        XCTAssertEqual(putResult, [.exactAlreadyPersisted])
        XCTAssertTrue(putResult[0].shouldApplyToModel)

        let conflictingHash = SyncReplayState.DurableMutation(
            wireObjectId: wireId, stamp: stamp(1, actorA), publicKey: pubA,
            kind: .put(contentHash: String(repeating: "2", count: 64)))
        let conflictingKind = SyncReplayState.DurableMutation(
            wireObjectId: wireId, stamp: stamp(1, actorA), publicKey: pubA, kind: .delete)
        XCTAssertEqual(try state.commitSnapshot([conflictingHash], seq: 2), [.conflictOrStale])
        XCTAssertEqual(try state.commitSnapshot([conflictingKind], seq: 3), [.conflictOrStale])

        let delete = SyncReplayState.DurableMutation(
            wireObjectId: wireId, stamp: stamp(2, actorA), publicKey: pubA, kind: .delete)
        XCTAssertTrue(try state.commit(delete))
        let deleteResult = try state.commitSnapshot([delete], seq: 4)
        XCTAssertEqual(deleteResult, [.exactAlreadyPersisted])
        XCTAssertTrue(deleteResult[0].shouldApplyToModel)

        let putAtDeleteStamp = SyncReplayState.DurableMutation(
            wireObjectId: wireId, stamp: stamp(2, actorA), publicKey: pubA,
            kind: .put(contentHash: putHash))
        XCTAssertEqual(try state.commitSnapshot([putAtDeleteStamp], seq: 5), [.conflictOrStale])
    }

    func testPendingModelMarkerProtectsOfflinePutDeleteAndRecreate() throws {
        let prior = String(repeating: "1", count: 64)
        let incoming = String(repeating: "2", count: 64)
        let offline = String(repeating: "3", count: 64)

        func putState() throws -> (SyncReplayState, SyncReplayState.DurableMutation) {
            let state = SyncReplayState(roomId: "pending-put")
            let mutation = SyncReplayState.DurableMutation(
                wireObjectId: wireId, stamp: stamp(1, actorA), publicKey: pubA,
                kind: .put(contentHash: incoming))
            XCTAssertTrue(try state.commitRemote(.init(mutation: mutation, priorModelHash: prior)))
            return (state, mutation)
        }

        do {
            let (state, mutation) = try putState()
            XCTAssertEqual(state.pendingModelDecision(mutation, currentModelHash: prior), .applyIncoming)
            XCTAssertTrue(try state.clearPendingModelApplication(mutation))
        }
        do {
            let (state, mutation) = try putState()
            XCTAssertEqual(state.pendingModelDecision(mutation, currentModelHash: incoming), .alreadyApplied)
            XCTAssertTrue(try state.clearPendingModelApplication(mutation))
        }
        do {
            let (state, mutation) = try putState()
            XCTAssertEqual(state.pendingModelDecision(mutation, currentModelHash: offline), .localDiverged)
            XCTAssertTrue(try state.clearPendingModelApplication(mutation))
            let local = SyncReplayState.DurableMutation(
                wireObjectId: wireId, stamp: stamp(2, actorA), publicKey: pubA,
                kind: .put(contentHash: offline))
            XCTAssertTrue(try state.commit(local))
            XCTAssertGreaterThan(local.stamp.counter, mutation.stamp.counter)
        }
        do {
            let (state, mutation) = try putState()
            XCTAssertEqual(state.pendingModelDecision(mutation, currentModelHash: nil), .localDiverged)
            XCTAssertTrue(try state.clearPendingModelApplication(mutation))
            let localDelete = SyncReplayState.DurableMutation(
                wireObjectId: wireId, stamp: stamp(2, actorA), publicKey: pubA, kind: .delete)
            XCTAssertTrue(try state.commit(localDelete))
            XCTAssertGreaterThan(localDelete.stamp.counter, mutation.stamp.counter)
        }

        func deleteState() throws -> (SyncReplayState, SyncReplayState.DurableMutation) {
            let state = SyncReplayState(roomId: "pending-delete")
            let mutation = SyncReplayState.DurableMutation(
                wireObjectId: wireId, stamp: stamp(1, actorA), publicKey: pubA, kind: .delete)
            XCTAssertTrue(try state.commitRemote(.init(mutation: mutation, priorModelHash: prior)))
            return (state, mutation)
        }
        do {
            let (state, mutation) = try deleteState()
            XCTAssertEqual(state.pendingModelDecision(mutation, currentModelHash: prior), .applyIncoming)
            XCTAssertTrue(try state.clearPendingModelApplication(mutation))
        }
        do {
            let (state, mutation) = try deleteState()
            XCTAssertEqual(state.pendingModelDecision(mutation, currentModelHash: nil), .alreadyApplied)
            XCTAssertTrue(try state.clearPendingModelApplication(mutation))
        }
        do {
            let (state, mutation) = try deleteState()
            XCTAssertEqual(state.pendingModelDecision(mutation, currentModelHash: offline), .localDiverged)
            XCTAssertTrue(try state.clearPendingModelApplication(mutation))
            let recreated = SyncReplayState.DurableMutation(
                wireObjectId: wireId, stamp: stamp(2, actorA), publicKey: pubA,
                kind: .put(contentHash: offline))
            XCTAssertTrue(try state.commit(recreated))
            XCTAssertGreaterThan(recreated.stamp.counter, mutation.stamp.counter)
        }
    }

    func testPendingModelMarkerSeparatesSenderPayloadHashFromReceiverModelHash() throws {
        let senderPayloadHash = String(repeating: "41", count: 32)
        let receiverModelHash = String(repeating: "42", count: 32)
        let mutation = SyncReplayState.DurableMutation(
            wireObjectId: wireId, stamp: stamp(1, actorA), publicKey: pubA,
            kind: .put(contentHash: senderPayloadHash))
        let state = SyncReplayState(roomId: "cross-export-hash")
        XCTAssertTrue(try state.commitRemote(.init(
            mutation: mutation, priorModelHash: nil, expectedModelHash: receiverModelHash)))
        XCTAssertEqual(state.pendingModelDecision(
            mutation, currentModelHash: receiverModelHash), .alreadyApplied)
        XCTAssertEqual(state.pendingModelDecision(
            mutation, currentModelHash: senderPayloadHash), .localDiverged)
    }

    func testSealedReplayReloadPreservesDistinctReceiverModelHash() throws {
        let rawHash = String(repeating: "51", count: 32)
        let expectedHash = String(repeating: "52", count: 32)
        let mutation = SyncReplayState.DurableMutation(
            wireObjectId: wireId, stamp: stamp(1, actorA), publicKey: pubA,
            kind: .put(contentHash: rawHash))
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("sync-replay-expected-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let previousKeyProvider = SafeStore.keyProvider
        let key = Data(repeating: 0x73, count: 32)
        SafeStore.keyProvider = { key }
        SealedMigrationPolicy.resetForTests(key: key)
        defer {
            SafeStore.keyProvider = previousKeyProvider
            try? FileManager.default.removeItem(at: dir)
        }
        let state = SyncReplayState(roomId: "sealed-expected", containerURL: dir)
        XCTAssertTrue(state.load())
        XCTAssertTrue(try state.commitRemote(.init(
            mutation: mutation, priorModelHash: nil, expectedModelHash: expectedHash)))
        let file = try currentReplayURL(directory: dir, roomId: "sealed-expected", key: key)
        XCTAssertTrue(SealedEnvelope.isSealedFile(try Data(contentsOf: file)))

        let reloaded = SyncReplayState(roomId: "sealed-expected", containerURL: dir)
        XCTAssertTrue(reloaded.load())
        XCTAssertEqual(reloaded.pendingModelDecision(
            mutation, currentModelHash: expectedHash), .alreadyApplied)
        XCTAssertEqual(reloaded.pendingModelDecision(
            mutation, currentModelHash: rawHash), .localDiverged)
    }

    func testLegacySealedPendingWithoutExpectedHashFallsBackToRawHash() throws {
        let rawHash = String(repeating: "61", count: 32)
        let expectedHash = String(repeating: "62", count: 32)
        let mutation = SyncReplayState.DurableMutation(
            wireObjectId: wireId, stamp: stamp(1, actorA), publicKey: pubA,
            kind: .put(contentHash: rawHash))
        let room = "legacy-pending"
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("sync-replay-legacy-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let previousKeyProvider = SafeStore.keyProvider
        let key = Data(repeating: 0x74, count: 32)
        SafeStore.keyProvider = { key }
        SealedMigrationPolicy.resetForTests(key: key)
        defer {
            SafeStore.keyProvider = previousKeyProvider
            try? FileManager.default.removeItem(at: dir)
        }
        let state = SyncReplayState(roomId: room, containerURL: dir)
        XCTAssertTrue(state.load())
        XCTAssertTrue(try state.commitRemote(.init(
            mutation: mutation, priorModelHash: nil, expectedModelHash: expectedHash)))
        let file = try currentReplayURL(directory: dir, roomId: room, key: key)
        let label = "sync/room/\(room)"
        let sealed = try Data(contentsOf: file)
        let plain = try XCTUnwrap(SealedEnvelope.openFile(key: key, blob: sealed, label: label))
        var root = try XCTUnwrap(JSONSerialization.jsonObject(with: plain) as? [String: Any])
        var pending = try XCTUnwrap(root["pendingModelApplications"] as? [String: [String: Any]])
        var record = try XCTUnwrap(pending[wireId])
        record.removeValue(forKey: "expectedHash")
        pending[wireId] = record
        root["pendingModelApplications"] = pending
        try SafeStore.write(
            JSONSerialization.data(withJSONObject: root, options: [.sortedKeys]),
            to: file, label: label)

        let reloaded = SyncReplayState(roomId: room, containerURL: dir)
        XCTAssertTrue(reloaded.load())
        XCTAssertEqual(reloaded.pendingModelDecision(
            mutation, currentModelHash: rawHash), .alreadyApplied)
        XCTAssertEqual(reloaded.pendingModelDecision(
            mutation, currentModelHash: expectedHash), .localDiverged)
    }

    func testGlobalGenerationClosesAbaAcrossLeaveAndRestart() throws {
        let localId = "71d0f3d2-7d33-4af4-a593-d4cb70fb808d"
        let prior = String(repeating: "4", count: 64)
        let incoming = String(repeating: "5", count: 64)
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("model-generation-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let key = Data(repeating: 0x6a, count: 32)
        let journal = LocalModelRevisionJournal(containerURL: directory, testKey: key)
        XCTAssertTrue(journal.load())

        func pending() throws -> (SyncReplayState, SyncReplayState.DurableMutation) {
            let state = SyncReplayState(roomId: "aba-room")
            let mutation = SyncReplayState.DurableMutation(
                wireObjectId: wireId, stamp: stamp(1, actorA), publicKey: pubA,
                kind: .put(contentHash: incoming))
            XCTAssertTrue(try state.commitRemote(.init(
                mutation: mutation, priorModelHash: prior, localModelId: localId,
                acceptedGeneration: journal.generation(localId))))
            return (state, mutation)
        }

        do {
            let (state, mutation) = try pending()
            XCTAssertEqual(state.pendingModelDecision(
                mutation, currentModelHash: prior, currentGeneration: journal.generation(localId)), .applyIncoming)
            XCTAssertEqual(state.pendingModelDecision(
                mutation, currentModelHash: incoming, currentGeneration: journal.generation(localId)), .alreadyApplied)
        }

        // Journal survives independently of replay/room lifetime: Leave/restart,
        // then an offline delete of the applied remote PUT cannot look like prior nil.
        do {
            let (state, mutation) = try pending()
            try journal.bump(localId)
            XCTAssertEqual(state.pendingModelDecision(
                mutation, currentModelHash: nil, currentGeneration: journal.generation(localId)), .localDiverged)
        }

        do {
            let (state, mutation) = try pending()
            let accepted = journal.generation(localId)
            try journal.bump(localId); try journal.bump(localId) // edit then exact revert
            XCTAssertEqual(state.pendingModelDecision(
                mutation, currentModelHash: prior, currentGeneration: journal.generation(localId)), .localDiverged)
            XCTAssertGreaterThan(journal.generation(localId), accepted)
        }

        do {
            let (state, mutation) = try pending()
            try journal.bump(localId); try journal.bump(localId) // recreate/delete cycle
            XCTAssertEqual(state.pendingModelDecision(
                mutation, currentModelHash: incoming, currentGeneration: journal.generation(localId)), .alreadyApplied)
        }

        let restarted = LocalModelRevisionJournal(containerURL: directory, testKey: key)
        XCTAssertTrue(restarted.load())
        XCTAssertEqual(restarted.generation(localId), journal.generation(localId),
                       "sealed generation must survive Leave/process restart")
    }

    @MainActor func testCallsignBoundUsesUnicodeScalars() {
        let manager = SyncManager()
        let value = String(repeating: "🛰️", count: 40)
        XCTAssertEqual(manager.boundedCallsign(value).unicodeScalars.count, 64)
    }

    func testOnlineMemberLifecycleIsIndependentOfLocationAndSessionScoped() throws {
        let tracker = OnlineMemberTracker(staleMetadataAfter: 60)
        let firstSession = sessionDomain
        let replacementSession = SyncIdentity.urlB64Encode(Data(repeating: 0x5d, count: 32))
        let firstSeen = Date(timeIntervalSince1970: 100)
        let replacementSeen = Date(timeIntervalSince1970: 110)

        var members = tracker.authenticatedHello(
            clientId: actorA, sessionDomain: firstSession, now: firstSeen)
        XCTAssertEqual(members.count, 1,
                       "an authenticated hello is a member even without a loc payload")
        XCTAssertNil(members[actorA]?.callsign)

        members = tracker.updatePresenceMetadata(
            clientId: actorA,
            sessionDomain: firstSession,
            callsign: " Alpha 1-1 ",
            affiliation: "friend",
            echelon: "platoon",
            function: "infantry",
            isHQ: false,
            now: Date(timeIntervalSince1970: 105)
        )
        XCTAssertEqual(members[actorA]?.displayName, "Alpha 1-1")
        members = tracker.expireStaleMetadata(now: Date(timeIntervalSince1970: 166))
        XCTAssertEqual(members.count, 1,
                       "aging location metadata must not expire the authenticated session")
        XCTAssertNil(members[actorA]?.callsign)

        members = tracker.authenticatedHello(
            clientId: actorA, sessionDomain: replacementSession, now: replacementSeen)
        XCTAssertEqual(members.count, 1)
        XCTAssertNil(members[actorA]?.callsign,
                     "a replacement session must not inherit stale location metadata")
        XCTAssertEqual(members[actorA]?.joinedAt, replacementSeen)

        let active = [actorA: V3ActiveSession(publicKey: pubA, sessionDomain: replacementSession)]
        XCTAssertNil(acceptedV3Departure(
            ["t": "leave", "by": actorA, "sd": firstSession],
            activeSessions: active,
            ownActorId: actorB
        ), "a delayed leave from the replaced socket must not erase its successor")
        let accepted = try XCTUnwrap(acceptedV3Departure(
            ["t": "leave", "by": actorA, "sd": replacementSession],
            activeSessions: active,
            ownActorId: actorB
        ))
        XCTAssertEqual(accepted, V3Departure(actorId: actorA, sessionDomain: replacementSession))
        XCTAssertEqual(accepted.kind, .transient)
        XCTAssertTrue(tracker.remove(
            clientId: accepted.actorId,
            sessionDomain: accepted.sessionDomain
        ).isEmpty)

        let replacementDeparture = try XCTUnwrap(acceptedV3Departure(
            ["t": "leave", "by": actorA, "sd": replacementSession, "replaced": true],
            activeSessions: active,
            ownActorId: actorB
        ))
        XCTAssertEqual(replacementDeparture.kind, .replacement)
        XCTAssertEqual(acceptedV3Departure(
            ["t": "leave", "by": actorA, "sd": replacementSession, "explicit": true],
            activeSessions: active,
            ownActorId: actorB
        )?.kind, .explicit)
        XCTAssertEqual(acceptedV3Departure(
            ["t": "leave", "by": actorA, "sd": replacementSession, "transient": true],
            activeSessions: active,
            ownActorId: actorB
        )?.kind, .transient)
        XCTAssertNil(acceptedV3Departure(
            ["t": "leave", "by": actorA, "sd": replacementSession, "replaced": "true"],
            activeSessions: active,
            ownActorId: actorB
        ))
        XCTAssertNil(acceptedV3Departure(
            ["t": "leave", "by": actorA, "sd": replacementSession,
             "explicit": true, "transient": true],
            activeSessions: active,
            ownActorId: actorB
        ))
    }

    func testV3DepartureRejectsSelfMalformedAndUnknownSessions() {
        let active = [actorA: V3ActiveSession(publicKey: pubA, sessionDomain: sessionDomain)]
        XCTAssertNil(acceptedV3Departure(
            ["t": "leave", "by": actorA, "sd": sessionDomain],
            activeSessions: active,
            ownActorId: actorA
        ))
        XCTAssertNil(acceptedV3Departure(
            ["t": "leave", "by": "not-canonical", "sd": sessionDomain],
            activeSessions: active,
            ownActorId: actorB
        ))
        XCTAssertNil(acceptedV3Departure(
            ["t": "leave", "by": actorA],
            activeSessions: active,
            ownActorId: actorB
        ))
        XCTAssertNil(acceptedV3Departure(
            ["t": "leave", "by": actorB, "sd": sessionDomain],
            activeSessions: active,
            ownActorId: nil
        ))
    }

    func testReplacementLeaveRetainsStaleFixWhileExplicitLeaveClearsIt() {
        let laterSession = String(repeating: "Q", count: 43)
        let peer = PresencePeer(
            clientId: actorA, callsign: "11A", affiliation: "friend",
            echelon: "team", function: "infantry", isHQ: false,
            lat: -35, lon: 149, heading: 0, speed: 0, ts: 1,
            sessionDomain: sessionDomain,
            receivedAtUptime: 1
        )
        let replacement = presencePeerAfterV3Departure(
            peer,
            departure: V3Departure(
                actorId: actorA,
                sessionDomain: sessionDomain,
                kind: .replacement
            ),
            nowUptime: 2
        )
        XCTAssertEqual(replacement?.reconnectGraceStartedAtUptime, 2)
        XCTAssertNil(presencePeerAfterV3Departure(
            peer,
            departure: V3Departure(
                actorId: actorA,
                sessionDomain: sessionDomain,
                kind: .explicit
            ),
            nowUptime: 2
        ))
        XCTAssertEqual(presencePeerAfterV3Departure(
            peer,
            departure: V3Departure(
                actorId: actorA,
                sessionDomain: laterSession,
                kind: .explicit
            ),
            nowUptime: 2
        )?.sessionDomain, sessionDomain)
        XCTAssertEqual(presencePeerAfterV3Departure(
            peer,
            departure: V3Departure(
                actorId: actorA,
                sessionDomain: laterSession,
                kind: .replacement
            ),
            nowUptime: 2
        )?.sessionDomain, sessionDomain)
        let transient = presencePeerAfterV3Departure(
            peer,
            departure: V3Departure(
                actorId: actorA,
                sessionDomain: sessionDomain,
                kind: .transient
            ),
            nowUptime: 2
        )
        XCTAssertEqual(transient?.reconnectGraceStartedAtUptime, 2)
        XCTAssertEqual(presencePeerAfterV3Departure(
            transient,
            departure: V3Departure(
                actorId: actorA,
                sessionDomain: sessionDomain,
                kind: .transient
            ),
            nowUptime: 20
        )?.reconnectGraceStartedAtUptime, 2)
    }

    func testBackgroundPresenceCadenceDefaultsToFifteenMinutesAndUsesMonotonicTime() {
        var cadence = UnitSyncPresenceCadence()
        let firstFix = Date(timeIntervalSince1970: 1_000)
        XCTAssertTrue(cadence.foregroundReady)
        XCTAssertTrue(cadence.isDue(at: 100))
        XCTAssertEqual(cadence.timerInterval, 5)
        XCTAssertEqual(cadence.advertisedRetentionSeconds, 45)
        XCTAssertTrue(cadence.canBroadcast(locationTimestamp: firstFix, at: 100))
        cadence.markBroadcast(locationTimestamp: firstFix, at: 100)
        XCTAssertFalse(cadence.isDue(at: 104))
        XCTAssertTrue(cadence.isDue(at: 105))
        XCTAssertFalse(cadence.canBroadcast(
            locationTimestamp: firstFix,
            at: 105
        ), "one cached fix must not be re-stamped as a new presence")
        XCTAssertTrue(cadence.canBroadcast(
            locationTimestamp: firstFix.addingTimeInterval(1),
            at: 105
        ))
        cadence.startAuthenticatedSession()
        XCTAssertTrue(cadence.canBroadcast(
            locationTimestamp: firstFix,
            at: 105
        ), "a replacement authenticated socket must seed its own presence")
        cadence.markBroadcast(locationTimestamp: firstFix, at: 105)

        cadence.configure(
            foregroundReady: false,
            backgroundEnabled: true,
            backgroundInterval: 15 * 60
        )
        XCTAssertTrue(cadence.isDue(at: 100))
        cadence.markBroadcast(
            locationTimestamp: firstFix.addingTimeInterval(2),
            at: 100
        )
        XCTAssertFalse(cadence.isDue(at: 999))
        XCTAssertTrue(cadence.isDue(at: 1_000))
        XCTAssertTrue(cadence.isDue(at: 99), "a clock-source reset permits one fresh update")
        XCTAssertEqual(cadence.timerInterval, 30)
        XCTAssertEqual(cadence.advertisedRetentionSeconds, 20 * 60)

        cadence.configure(
            foregroundReady: false,
            backgroundEnabled: true,
            backgroundInterval: 30 * 60
        )
        XCTAssertEqual(cadence.advertisedRetentionSeconds, 35 * 60)

        cadence.configure(
            foregroundReady: false,
            backgroundEnabled: true,
            backgroundInterval: 60 * 60
        )
        XCTAssertEqual(cadence.advertisedRetentionSeconds, 65 * 60)

        cadence.configure(
            foregroundReady: false,
            backgroundEnabled: true,
            backgroundInterval: 5 * 60
        )
        XCTAssertTrue(cadence.isDue(at: 101), "changing cadence reschedules immediately")

        cadence.configure(
            foregroundReady: false,
            backgroundEnabled: false,
            backgroundInterval: 5 * 60
        )
        XCTAssertFalse(cadence.canBroadcast)
        XCTAssertFalse(cadence.isDue(at: 10_000))
        XCTAssertEqual(cadence.advertisedRetentionSeconds, 45)

        let now = Date(timeIntervalSince1970: 10_000)
        XCTAssertTrue(UnitSyncPresenceCadence.locationIsFresh(
            timestamp: now.addingTimeInterval(-30), now: now))
        XCTAssertFalse(UnitSyncPresenceCadence.locationIsFresh(
            timestamp: now.addingTimeInterval(-31), now: now))
        XCTAssertFalse(UnitSyncPresenceCadence.locationIsFresh(
            timestamp: now.addingTimeInterval(31), now: now))
    }

    func testPresenceRefreshAndTransportWatchdogBeatForegroundExpiry() {
        XCTAssertLessThan(
            UnitSyncPresenceCadence.preferredLocationAge,
            UnitSyncPresenceCadence.maximumLocationAge
        )
        XCTAssertLessThan(
            UnitSyncPresenceCadence.maximumLocationAge,
            PresenceExpiryPolicy.liveUpdateWindow
        )
        XCTAssertLessThan(
            SyncConnectionWatchdogPolicy.heartbeatInterval
                + SyncConnectionWatchdogPolicy.heartbeatTimeout
                + SyncReconnectBackoff.baseDelay * (1 + SyncReconnectBackoff.jitterFraction),
            PresenceExpiryPolicy.liveUpdateWindow
        )

        XCTAssertTrue(SyncConnectionWatchdogPolicy.isCurrent(
            scheduledGeneration: 7,
            currentGeneration: 7,
            wantsConnection: true,
            hasCurrentSocket: true
        ))
        XCTAssertFalse(SyncConnectionWatchdogPolicy.isCurrent(
            scheduledGeneration: 7,
            currentGeneration: 8,
            wantsConnection: true,
            hasCurrentSocket: true
        ))
        XCTAssertFalse(SyncConnectionWatchdogPolicy.isCurrent(
            scheduledGeneration: 7,
            currentGeneration: 7,
            wantsConnection: false,
            hasCurrentSocket: true
        ))
        XCTAssertFalse(SyncConnectionWatchdogPolicy.isCurrent(
            scheduledGeneration: 7,
            currentGeneration: 7,
            wantsConnection: true,
            hasCurrentSocket: false
        ))
    }

    func testDelayedSendFailureCannotDisconnectReplacementSocketGeneration() {
        XCTAssertFalse(SyncConnectionWatchdogPolicy.isCurrent(
            scheduledGeneration: 41,
            currentGeneration: 42,
            wantsConnection: true,
            hasCurrentSocket: true
        ), "an old hello/send callback must not tear down its replacement session")
        XCTAssertFalse(SyncConnectionWatchdogPolicy.isCurrent(
            scheduledGeneration: 42,
            currentGeneration: 42,
            wantsConnection: true,
            hasCurrentSocket: false
        ), "identity mismatch must reject a callback even within one generation")
    }

    func testVerifiedHelloAckActivatesLiveSessionBeforeMissionReconciliation() {
        var events: [String] = []
        V3HelloAckWorkSequencer.run {
            events.append("presence-and-heartbeat")
        } reconcileMissionState: {
            events.append("mission-reconciliation")
        }
        XCTAssertEqual(events, ["presence-and-heartbeat", "mission-reconciliation"])
    }

    func testChatInboundCanOpenBeforeLocalKeyAckWhileOutboundRemainsGated() {
        XCTAssertTrue(TacMapChatReadinessPolicy.permitsInbound(
            hasLocalSession: true
        ), "a routed frame for the current advertised key must not race its local ACK callback")
        XCTAssertFalse(TacMapChatReadinessPolicy.permitsInbound(
            hasLocalSession: false
        ), "no inbound frame is eligible after local session key material is cleared")

        XCTAssertFalse(TacMapChatReadinessPolicy.permitsOutbound(
            hasLocalSession: true,
            relayAcknowledged: false
        ), "the UI and sender must remain disabled until the relay acknowledges the key")
        XCTAssertTrue(TacMapChatReadinessPolicy.permitsOutbound(
            hasLocalSession: true,
            relayAcknowledged: true
        ))
        XCTAssertFalse(TacMapChatReadinessPolicy.permitsOutbound(
            hasLocalSession: false,
            relayAcknowledged: true
        ))
    }

    func testPresenceExpiryBridgesBackgroundCadenceOnlyForMatchingV3Session() {
        let now = Date(timeIntervalSince1970: 2_000_000)
        let nowUptime: TimeInterval = 2_000_000
        func peer(
            age: TimeInterval,
            session: String?,
            retention: TimeInterval = PresenceExpiryPolicy.liveUpdateWindow
        ) -> PresencePeer {
            PresencePeer(
                clientId: actorA,
                callsign: "A11",
                affiliation: "friend",
                echelon: "team",
                function: "infantry",
                isHQ: false,
                lat: -33.86,
                lon: 151.21,
                heading: 0,
                speed: 0,
                ts: 0,
                sessionDomain: session,
                retentionWindow: retention,
                receivedAt: now.addingTimeInterval(-age),
                receivedAtUptime: nowUptime - age
            )
        }
        let active = V3ActiveSession(publicKey: pubA, sessionDomain: sessionDomain)
        let otherSession = SyncIdentity.urlB64Encode(Data(repeating: 0x44, count: 32))

        XCTAssertTrue(PresenceExpiryPolicy.shouldRetain(
            peer(age: 45, session: nil), activeSession: nil, nowUptime: nowUptime))
        XCTAssertFalse(PresenceExpiryPolicy.shouldRetain(
            peer(age: 46, session: nil), activeSession: nil, nowUptime: nowUptime))
        XCTAssertFalse(PresenceExpiryPolicy.shouldRetain(
            peer(age: 60, session: sessionDomain), activeSession: active, nowUptime: nowUptime))
        XCTAssertTrue(PresenceExpiryPolicy.shouldRetain(
            peer(age: 15 * 60, session: sessionDomain, retention: 20 * 60),
            activeSession: active,
            nowUptime: nowUptime))
        XCTAssertFalse(PresenceExpiryPolicy.shouldRetain(
            peer(age: 60, session: sessionDomain, retention: 20 * 60),
            activeSession: V3ActiveSession(publicKey: pubA, sessionDomain: otherSession),
            nowUptime: nowUptime))
        XCTAssertFalse(PresenceExpiryPolicy.shouldRetain(
            peer(age: 65 * 60 + 1, session: sessionDomain, retention: 90 * 60),
            activeSession: active,
            nowUptime: nowUptime))
        XCTAssertFalse(PresenceExpiryPolicy.shouldRetain(
            peer(age: 0, session: nil),
            activeSession: nil,
            nowUptime: nowUptime - 1
        ), "a monotonic clock rollback must fail closed")
    }

    func testPresenceRetentionAdvertisementIsCanonicalAndFailClosed() throws {
        let payload = try XCTUnwrap(PresenceRetentionAdvertisement.encodePayload(seconds: 65 * 60))
        let envelope: [String: Any] = [
            PresenceRetentionAdvertisement.versionField:
                PresenceRetentionAdvertisement.envelopeVersion,
            PresenceRetentionAdvertisement.payloadField: payload.base64EncodedString(),
            PresenceRetentionAdvertisement.signatureField: "signature"
        ]
        guard case .valid(let decoded) = PresenceRetentionAdvertisement.decode(from: envelope) else {
            return XCTFail("valid retention envelope was rejected")
        }
        XCTAssertEqual(decoded.seconds, 65 * 60)
        XCTAssertEqual(decoded.signedPayload, payload)
        XCTAssertEqual(decoded.signature, "signature")

        XCTAssertNil(PresenceRetentionAdvertisement.encodePayload(seconds: 65 * 60 + 1))
        guard case .invalid = PresenceRetentionAdvertisement.decode(from: [
            PresenceRetentionAdvertisement.versionField: 1,
            PresenceRetentionAdvertisement.payloadField: payload.base64EncodedString()
        ]) else {
            return XCTFail("partial retention envelope must fail closed")
        }
    }

    func testSyncFunctionChoicesUseCompleteSymbolFunctionSet() {
        let choices = Set(PresenceConfig.functionChoices.map(\.rawValue))
        XCTAssertTrue([
            "ammunition", "bridging", "eod", "radar", "uav", "unspecified",
            "airDefence", "aviationFixed", "militaryPolice", "transportation",
        ].allSatisfy(choices.contains))
        XCTAssertEqual(choices.count, SymbolFunction.allCases.count)
    }

    @MainActor
    func testRoomNamesPersistOnlyInsideSealedLocalPresenceConfig() throws {
        let defaults = UserDefaults.standard
        let priorBlob = defaults.data(forKey: "sync.presenceConfig")
        let previousKeyProvider = SafeStore.keyProvider
        let testKey = Data(repeating: 0x4d, count: 32)
        defer {
            SafeStore.keyProvider = previousKeyProvider
            if let priorBlob {
                defaults.set(priorBlob, forKey: "sync.presenceConfig")
            } else {
                defaults.removeObject(forKey: "sync.presenceConfig")
            }
        }

        defaults.removeObject(forKey: "sync.presenceConfig")
        SafeStore.keyProvider = { testKey }
        SealedMigrationPolicy.resetForTests(key: testKey)
        XCTAssertEqual(PresenceConfig.boundedRoomName("1 "), "1 ",
                       "live editing must retain a space before the next word")
        let multiScalarGrapheme = "🇦🇺"
        let boundedUnicode = PresenceConfig.boundedRoomName(
            String(repeating: multiScalarGrapheme,
                   count: PresenceConfig.roomNameMaxLength + 1))
        XCTAssertEqual(boundedUnicode.unicodeScalars.count,
                       PresenceConfig.roomNameMaxLength)
        let manager = SyncManager()
        var updatedPresence = manager.presenceConfig
        updatedPresence.setRoomName(
            "  BG Waratah Operations Room  ", for: "derived-room-id")
        XCTAssertTrue(manager.updatePresenceConfig(updatedPresence))

        let stored = try XCTUnwrap(defaults.data(forKey: "sync.presenceConfig"))
        XCTAssertTrue(SealedEnvelope.isSealedFile(stored))
        XCTAssertFalse(String(decoding: stored, as: UTF8.self)
            .contains("BG Waratah Operations Room"))
        let plaintext = try XCTUnwrap(SealedEnvelope.openFile(
            key: testKey, blob: stored, label: "sync/presenceConfig"))
        let restored = try JSONDecoder().decode(PresenceConfig.self, from: plaintext)
        XCTAssertEqual(restored.roomName(for: "derived-room-id"),
                       "BG Waratah Operations Room")

        let legacy = try JSONDecoder().decode(
            PresenceConfig.self,
            from: Data(#"{"callsign":"I11","shareLocation":false}"#.utf8))
        XCTAssertTrue(legacy.roomNamesById.isEmpty)
    }

    @MainActor
    func testPresenceConfigFailedCandidateRestoresPriorBytesBeforePublication() throws {
        let suite = "SyncProtocolV3Tests.presence.rollback.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defaults.removePersistentDomain(forName: suite)
        let previousKeyProvider = SafeStore.keyProvider
        let testKey = Data(repeating: 0x61, count: 32)
        defer {
            defaults.removePersistentDomain(forName: suite)
            SealedMigrationPolicy.resetForTests(key: testKey)
            SafeStore.keyProvider = previousKeyProvider
        }
        SafeStore.keyProvider = { testKey }
        SealedMigrationPolicy.resetForTests(key: testKey)

        var original = PresenceConfig()
        original.callsign = "DURABLE"
        original.shareLocation = false
        let originalPlain = try JSONEncoder().encode(original)
        let originalSealed = try SealedEnvelope.sealFile(
            key: testKey,
            plaintext: originalPlain,
            label: "sync/presenceConfig"
        )
        let slot = MutablePresenceSlot(originalSealed)
        let manager = SyncManager(defaults: defaults, presenceStore: slot.store)
        XCTAssertEqual(manager.presenceConfig, original)

        slot.failWrites = true
        var candidate = original
        candidate.callsign = "NOT DURABLE"
        candidate.shareLocation = true
        XCTAssertFalse(manager.updatePresenceConfig(candidate))
        XCTAssertEqual(manager.presenceConfig, original)
        XCTAssertEqual(slot.value, originalSealed)
        XCTAssertNotNil(manager.lastError)

        // Model a later successful flush/restart: the failed candidate cannot
        // leak back out of the defaults cache.
        slot.failWrites = false
        let restarted = SyncManager(defaults: defaults, presenceStore: slot.store)
        XCTAssertEqual(restarted.presenceConfig, original)
    }

    @MainActor
    func testPresenceConfigSealedPolicyRejectsPlaintextDowngrade() throws {
        let suite = "SyncProtocolV3Tests.presence.downgrade.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defaults.removePersistentDomain(forName: suite)
        let previousKeyProvider = SafeStore.keyProvider
        let testKey = Data(repeating: 0x62, count: 32)
        defer {
            defaults.removePersistentDomain(forName: suite)
            SealedMigrationPolicy.resetForTests(key: testKey)
            SafeStore.keyProvider = previousKeyProvider
        }
        SafeStore.keyProvider = { testKey }
        SealedMigrationPolicy.resetForTests(key: testKey)
        try SealedMigrationPolicy.markSealed(
            "defaults:sync.presenceConfig",
            key: testKey
        )

        var downgraded = PresenceConfig()
        downgraded.callsign = "PLAINTEXT"
        downgraded.shareLocation = true
        let slot = MutablePresenceSlot(try JSONEncoder().encode(downgraded))
        let manager = SyncManager(defaults: defaults, presenceStore: slot.store)

        XCTAssertEqual(manager.presenceConfig, PresenceConfig())
        XCTAssertFalse(manager.presenceConfig.shareLocation)
        XCTAssertNotNil(manager.lastError)
        XCTAssertFalse(SealedEnvelope.isSealedFile(try XCTUnwrap(slot.value)))
    }

    @MainActor
    func testPresenceConfigFailedLegacyMigrationFencesAndDoesNotPublish() throws {
        let suite = "SyncProtocolV3Tests.presence.legacy-failure.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defaults.removePersistentDomain(forName: suite)
        let previousKeyProvider = SafeStore.keyProvider
        let testKey = Data(repeating: 0x63, count: 32)
        defer {
            defaults.removePersistentDomain(forName: suite)
            SealedMigrationPolicy.resetForTests(key: testKey)
            SafeStore.keyProvider = previousKeyProvider
        }
        SafeStore.keyProvider = { testKey }
        SealedMigrationPolicy.resetForTests(key: testKey)

        var legacy = PresenceConfig()
        legacy.callsign = "LEGACY"
        legacy.shareLocation = true
        let legacyBytes = try JSONEncoder().encode(legacy)
        let slot = MutablePresenceSlot(legacyBytes)
        slot.failWrites = true
        let manager = SyncManager(defaults: defaults, presenceStore: slot.store)

        XCTAssertEqual(manager.presenceConfig, PresenceConfig())
        XCTAssertEqual(slot.value, legacyBytes)
        XCTAssertTrue(try SealedMigrationPolicy.requiresSealed(
            "defaults:sync.presenceConfig",
            key: testKey
        ))
        XCTAssertNotNil(manager.lastError)

        slot.failWrites = false
        let restarted = SyncManager(defaults: defaults, presenceStore: slot.store)
        XCTAssertEqual(restarted.presenceConfig, PresenceConfig())
        XCTAssertNotNil(restarted.lastError)
    }

    @MainActor
    func testPresenceConfigLegacyMigrationSealsBeforePublication() throws {
        let suite = "SyncProtocolV3Tests.presence.legacy-success.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defaults.removePersistentDomain(forName: suite)
        let previousKeyProvider = SafeStore.keyProvider
        let testKey = Data(repeating: 0x64, count: 32)
        defer {
            defaults.removePersistentDomain(forName: suite)
            SealedMigrationPolicy.resetForTests(key: testKey)
            SafeStore.keyProvider = previousKeyProvider
        }
        SafeStore.keyProvider = { testKey }
        SealedMigrationPolicy.resetForTests(key: testKey)

        var legacy = PresenceConfig()
        legacy.callsign = "MIGRATED"
        legacy.shareLocation = true
        let slot = MutablePresenceSlot(try JSONEncoder().encode(legacy))
        let manager = SyncManager(defaults: defaults, presenceStore: slot.store)

        XCTAssertEqual(manager.presenceConfig, legacy)
        let sealed = try XCTUnwrap(slot.value)
        XCTAssertTrue(SealedEnvelope.isSealedFile(sealed))
        XCTAssertTrue(try SealedMigrationPolicy.requiresSealed(
            "defaults:sync.presenceConfig",
            key: testKey
        ))
    }

    @MainActor
    func testPresenceConfigInvalidSealedRecordNeverPublishes() throws {
        let suite = "SyncProtocolV3Tests.presence.invalid-sealed.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defaults.removePersistentDomain(forName: suite)
        let previousKeyProvider = SafeStore.keyProvider
        let testKey = Data(repeating: 0x65, count: 32)
        defer {
            defaults.removePersistentDomain(forName: suite)
            SealedMigrationPolicy.resetForTests(key: testKey)
            SafeStore.keyProvider = previousKeyProvider
        }
        SafeStore.keyProvider = { testKey }
        SealedMigrationPolicy.resetForTests(key: testKey)

        var exposed = PresenceConfig()
        exposed.shareLocation = true
        var corrupt = try SealedEnvelope.sealFile(
            key: testKey,
            plaintext: JSONEncoder().encode(exposed),
            label: "sync/presenceConfig"
        )
        corrupt[corrupt.index(before: corrupt.endIndex)] ^= 0x01
        let manager = SyncManager(
            defaults: defaults,
            presenceStore: MutablePresenceSlot(corrupt).store
        )

        XCTAssertEqual(manager.presenceConfig, PresenceConfig())
        XCTAssertFalse(manager.presenceConfig.shareLocation)
        XCTAssertNotNil(manager.lastError)
    }

    func testRoomNameHistoryStaysBoundedAndPreservesMostRecentEdit() {
        var config = PresenceConfig()
        for index in 0...PresenceConfig.roomNameHistoryLimit {
            config.setRoomName("Room \(index)", for: String(format: "room-%03d", index))
        }

        let newestID = String(format: "room-%03d", PresenceConfig.roomNameHistoryLimit)
        XCTAssertEqual(config.roomNamesById.count, PresenceConfig.roomNameHistoryLimit)
        XCTAssertEqual(config.roomName(for: newestID),
                       "Room \(PresenceConfig.roomNameHistoryLimit)")
        XCTAssertNil(config.roomName(for: "room-000"))
    }

    func testProductionJSONIntegerParserAcceptsFreshSnapshotSequence() throws {
        func decoded(_ json: String) throws -> Any? {
            let data = try XCTUnwrap(json.data(using: .utf8))
            let object = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
            return object["seq"]
        }

        // Foundation's NSNumber bridge makes numeric 0 and 1 satisfy `is Bool`.
        // Exercise the exact JSON-decoded values used by SyncManager rather than
        // constructing convenient Swift Ints that would miss the live regression.
        XCTAssertEqual(SyncManager.strictJSONInteger(try decoded("{\"seq\":0}"), minimum: 0, maximum: Int64.max), 0)
        XCTAssertEqual(SyncManager.strictJSONInteger(try decoded("{\"seq\":1}"), minimum: 0, maximum: Int64.max), 1)
        XCTAssertEqual(SyncManager.strictJSONInteger(try decoded("{\"seq\":2}"), minimum: 0, maximum: Int64.max), 2)
        XCTAssertEqual(SyncManager.strictJSONInteger(try decoded("{\"seq\":9223372036854775807}"), minimum: 0, maximum: Int64.max), Int64.max)
        XCTAssertEqual(SyncManager.strictJSONInteger(try decoded("{\"seq\":-9223372036854775808}"), minimum: Int64.min, maximum: Int64.max), Int64.min)
        XCTAssertNil(SyncManager.strictJSONInteger(try decoded("{\"seq\":false}"), minimum: 0, maximum: Int64.max))
        XCTAssertNil(SyncManager.strictJSONInteger(try decoded("{\"seq\":1.0}"), minimum: 0, maximum: Int64.max))
        XCTAssertNil(SyncManager.strictJSONInteger(try decoded("{\"seq\":999999999999.000001}"), minimum: 0, maximum: Int64.max))
        XCTAssertNil(SyncManager.strictJSONInteger(try decoded("{\"seq\":1.5}"), minimum: 0, maximum: Int64.max))
        XCTAssertNil(SyncManager.strictJSONInteger(try decoded("{\"seq\":-1}"), minimum: 0, maximum: Int64.max))
        XCTAssertNil(SyncManager.strictJSONInteger(try decoded("{\"seq\":18446744073709551615}"), minimum: Int64.min, maximum: Int64.max))
    }

    @MainActor func testLockedSigningKeyRefusesJoinWithoutCreatingIdentity() throws {
        let joinCode = "3:identity-locked-regression-20260712"
        let roomId = SyncCrypto.deriveRoomV3(String(joinCode.dropFirst(2))).roomId
        let hostedState = try HostedAppSyncStateSnapshot(roomId: roomId)
        let previousKeyProvider = SafeStore.keyProvider
        let manager = SyncManager()
        defer {
            manager.leave()
            SafeStore.keyProvider = previousKeyProvider
            do {
                try hostedState.restore()
            } catch {
                XCTFail(
                    "Test state restoration failed; recovery backup retained at "
                        + "\(hostedState.recoveryBackupPath): \(error.localizedDescription)"
                )
            }
        }

        SafeStore.keyProvider = { throw DataKey.LockedError() }
        manager.join(joinCode)

        XCTAssertNil(manager.room)
        XCTAssertEqual(manager.lastError,
                       "Signing identity is locked or unavailable. Unlock the device and try again.")
        XCTAssertNil(UserDefaults.standard.data(forKey: "sync.deviceSeed"),
                     "a locked key must not create identity material it cannot protect")
    }

    @MainActor func testCorruptSigningSeedRefusesJoinWithoutSilentRotation() throws {
        let joinCode = "3:identity-corrupt-regression-20260712"
        let roomId = SyncCrypto.deriveRoomV3(String(joinCode.dropFirst(2))).roomId
        let hostedState = try HostedAppSyncStateSnapshot(roomId: roomId)
        let previousKeyProvider = SafeStore.keyProvider
        let testKey = Data(repeating: 0x6b, count: 32)
        let invalidSeed = Data(repeating: 0x41, count: 31)
        let corruptBlob = try SealedEnvelope.sealFile(
            key: testKey, plaintext: invalidSeed, label: "sync/deviceSeed")
        let manager = SyncManager()
        defer {
            manager.leave()
            SafeStore.keyProvider = previousKeyProvider
            do {
                try hostedState.restore()
            } catch {
                XCTFail(
                    "Test state restoration failed; recovery backup retained at "
                        + "\(hostedState.recoveryBackupPath): \(error.localizedDescription)"
                )
            }
        }

        SafeStore.keyProvider = { testKey }
        UserDefaults.standard.set(corruptBlob, forKey: "sync.deviceSeed")
        XCTAssertTrue(UserDefaults.standard.synchronize())
        manager.join(joinCode)

        XCTAssertNil(manager.room)
        XCTAssertEqual(manager.lastError,
                       "Signing identity is locked or unavailable. Unlock the device and try again.")
        XCTAssertEqual(UserDefaults.standard.data(forKey: "sync.deviceSeed"), corruptBlob,
                       "invalid identity material must remain untouched rather than rotate silently")
    }

    @MainActor func testSessionRandomFailureStopsBeforeSocketOrFrameCreation() throws {
        enum Expected: Error { case unavailable }

        let joinCode = "3:session-rng-failure-regression-20260828"
        let roomId = SyncCrypto.deriveRoomV3(String(joinCode.dropFirst(2))).roomId
        let hostedState = try HostedAppSyncStateSnapshot(roomId: roomId)
        let previousKeyProvider = SafeStore.keyProvider
        let previousRelay = OpsecSettings.shared.relayURL
        let testKey = Data(repeating: 0x6d, count: 32)
        var generatorCalls = 0
        let manager = SyncManager(sessionDomainGenerator: {
            generatorCalls += 1
            throw Expected.unavailable
        })
        defer {
            manager.leave()
            SafeStore.keyProvider = previousKeyProvider
            _ = OpsecSettings.shared.setRelayURL(previousRelay)
            do {
                try hostedState.restore()
            } catch {
                XCTFail(
                    "Test state restoration failed; recovery backup retained at "
                        + "\(hostedState.recoveryBackupPath): \(error.localizedDescription)"
                )
            }
        }

        SafeStore.keyProvider = { testKey }
        SealedMigrationPolicy.resetForTests(key: testKey)
        XCTAssertTrue(OpsecSettings.shared.setRelayURL("ws://127.0.0.1:9"))
        manager.configure(waypointStore: WaypointStore(), drawingStore: DrawingStore())
        manager.join(joinCode)

        XCTAssertEqual(generatorCalls, 1)
        XCTAssertEqual(manager.status, .offline)
        XCTAssertEqual(
            manager.lastError,
            "Secure session randomness is unavailable. Unit Sync was not started."
        )
        XCTAssertFalse(manager.chatSessionReady)

        // Foreground lifecycle churn must not retry a connection after the
        // cryptographic prerequisite failed.
        manager.updateLifecycle(
            foregroundReady: false,
            backgroundPresenceEnabled: false,
            backgroundInterval: 900
        )
        manager.updateLifecycle(
            foregroundReady: true,
            backgroundPresenceEnabled: false,
            backgroundInterval: 900
        )
        XCTAssertEqual(generatorCalls, 1)
        XCTAssertEqual(manager.status, .offline)
    }

    @MainActor func testSigningSeedPersistsAndReloadsWithoutRotation() throws {
        let joinCode = "3:identity-reload-regression-20260712"
        let roomId = SyncCrypto.deriveRoomV3(String(joinCode.dropFirst(2))).roomId
        let hostedState = try HostedAppSyncStateSnapshot(roomId: roomId)
        let previousKeyProvider = SafeStore.keyProvider
        let previousRelay = OpsecSettings.shared.relayURL
        let testKey = Data(repeating: 0x7c, count: 32)
        var managers: [SyncManager] = []
        defer {
            managers.forEach { $0.leave() }
            SafeStore.keyProvider = previousKeyProvider
            _ = OpsecSettings.shared.setRelayURL(previousRelay)
            do {
                try hostedState.restore()
            } catch {
                XCTFail(
                    "Test state restoration failed; recovery backup retained at "
                        + "\(hostedState.recoveryBackupPath): \(error.localizedDescription)"
                )
            }
        }

        SafeStore.keyProvider = { testKey }
        SealedMigrationPolicy.resetForTests(key: testKey)
        XCTAssertTrue(OpsecSettings.shared.setRelayURL("ws://127.0.0.1:9"))

        let first = SyncManager()
        managers.append(first)
        first.configure(waypointStore: WaypointStore(), drawingStore: DrawingStore())
        first.join(joinCode)
        XCTAssertEqual(first.room, joinCode)
        let persisted = try XCTUnwrap(UserDefaults.standard.data(forKey: "sync.deviceSeed"))
        let seed = try XCTUnwrap(SealedEnvelope.openFile(
            key: testKey, blob: persisted, label: "sync/deviceSeed"))
        XCTAssertEqual(seed.count, 32)
        first.leave()

        // A new manager models process-level cache loss. It must reopen the
        // existing seed and retain the same durable actor identity.
        let second = SyncManager()
        managers.append(second)
        second.configure(waypointStore: WaypointStore(), drawingStore: DrawingStore())
        second.join(joinCode)
        XCTAssertEqual(second.room, joinCode)
        XCTAssertEqual(UserDefaults.standard.data(forKey: "sync.deviceSeed"), persisted)
        second.leave()
    }

#if DEBUG && targetEnvironment(simulator)
    @MainActor func testSimulatorUITestSigningIdentityResetIsExplicitAndNarrow() throws {
        let suiteName = "SyncProtocolV3Tests.identityReset.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }

        let identity = Data(repeating: 0x41, count: 32)
        defaults.set(identity, forKey: "sync.deviceSeed")
        defaults.set("preserve", forKey: "unrelated.preference")

        XCTAssertFalse(SyncManager.resetSigningIdentityForSimulatorUITestIfRequested(
            environment: [:], defaults: defaults
        ))
        XCTAssertEqual(defaults.data(forKey: "sync.deviceSeed"), identity)
        XCTAssertFalse(SyncManager.resetSigningIdentityForSimulatorUITestIfRequested(
            environment: [
                SyncManager.simulatorUITestSigningIdentityResetEnvironmentKey: "true"
            ],
            defaults: defaults
        ))
        XCTAssertEqual(defaults.data(forKey: "sync.deviceSeed"), identity)

        XCTAssertTrue(SyncManager.resetSigningIdentityForSimulatorUITestIfRequested(
            environment: [
                SyncManager.simulatorUITestSigningIdentityResetEnvironmentKey: "1"
            ],
            defaults: defaults
        ))
        XCTAssertNil(defaults.data(forKey: "sync.deviceSeed"))
        XCTAssertEqual(defaults.string(forKey: "unrelated.preference"), "preserve")
    }
#endif

    @MainActor
    func testV2VerifiedRecordMetadataFinalizesOnlyAfterDurableModelWrite() throws {
        enum Expected: Error { case unavailable }
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("sync-v2-model-write-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        var waypointWriteShouldFail = true
        var waypointWriteAttempts = 0
        let waypointStore = WaypointStore(
            storageURL: directory.appendingPathComponent("waypoints.json"),
            persistenceWriter: { _, _, _ in
                waypointWriteAttempts += 1
                if waypointWriteShouldFail { throw Expected.unavailable }
            }
        )
        let drawingStore = DrawingStore(
            storageURL: directory.appendingPathComponent("drawings.json"),
            persistenceWriter: { _, _, _ in }
        )
        let waypoint = Waypoint(
            name: "Verified v2",
            latitude: -33.86,
            longitude: 151.20,
            layerID: DrawingLayer.legacyFallbackID
        )
        let parsed = GeoJSONImporter.Result(
            waypoints: [waypoint], drawings: [], newLayers: [], invalidSkipped: 0)
        var metadataFinalizations = 0

        XCTAssertThrowsError(try SyncVerifiedV2RecordCommitter.apply(
            parsed,
            waypointStore: waypointStore,
            drawingStore: drawingStore
        ) {
            metadataFinalizations += 1
        })
        XCTAssertTrue(waypointStore.waypoints.isEmpty)
        XCTAssertEqual(metadataFinalizations, 0,
                       "v2 clock/version/hash metadata cannot acknowledge a failed model write")

        waypointWriteShouldFail = false
        try SyncVerifiedV2RecordCommitter.apply(
            parsed,
            waypointStore: waypointStore,
            drawingStore: drawingStore
        ) {
            metadataFinalizations += 1
        }
        XCTAssertEqual(waypointStore.waypoints, [waypoint])
        XCTAssertEqual(metadataFinalizations, 1)
        XCTAssertEqual(waypointWriteAttempts, 2)
    }

    @MainActor
    func testV3FailedModelWriteRetainsDurablePendingMarkerUntilRetrySucceeds() throws {
        enum Expected: Error { case unavailable }
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("sync-v3-model-write-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        var waypointWriteShouldFail = true
        let waypointStore = WaypointStore(
            storageURL: directory.appendingPathComponent("waypoints.json"),
            persistenceWriter: { _, _, _ in
                if waypointWriteShouldFail { throw Expected.unavailable }
            }
        )
        let drawingStore = DrawingStore(
            storageURL: directory.appendingPathComponent("drawings.json"),
            persistenceWriter: { _, _, _ in }
        )
        let waypoint = Waypoint(
            name: "Pending v3",
            latitude: -33.86,
            longitude: 151.20,
            layerID: DrawingLayer.legacyFallbackID
        )
        let parsed = GeoJSONImporter.Result(
            waypoints: [waypoint], drawings: [], newLayers: [], invalidSkipped: 0)
        let mutation = SyncReplayState.DurableMutation(
            wireObjectId: wireId,
            stamp: stamp(1, actorA),
            publicKey: pubA,
            kind: .put(contentHash: String(repeating: "a", count: 64))
        )
        let replay = SyncReplayState(roomId: "pending-model-write")
        XCTAssertTrue(try replay.commitRemote(.init(
            mutation: mutation,
            priorModelHash: nil,
            localModelId: waypoint.id.uuidString,
            expectedModelHash: String(repeating: "b", count: 64)
        )))

        XCTAssertThrowsError(try SyncRemoteModelApplier.apply(
            parsed,
            waypointStore: waypointStore,
            drawingStore: drawingStore
        ))
        XCTAssertTrue(waypointStore.waypoints.isEmpty)
        XCTAssertTrue(replay.hasPendingModelApplications())
        XCTAssertEqual(replay.pendingModelDecision(
            mutation, currentModelHash: nil), .applyIncoming)

        waypointWriteShouldFail = false
        try SyncRemoteModelApplier.apply(
            parsed,
            waypointStore: waypointStore,
            drawingStore: drawingStore
        )
        XCTAssertEqual(waypointStore.waypoints, [waypoint])
        XCTAssertTrue(replay.hasPendingModelApplications(),
                      "the manager clears this marker only after its post-write hash check")
        XCTAssertTrue(try replay.clearPendingModelApplication(mutation))
        XCTAssertFalse(replay.hasPendingModelApplications())
    }

    /// Opt-in integration regression for the complete production manager path.
    /// Run with TACMAP_LIVE_RELAY=ws://127.0.0.1:8791 and
    /// TACMAP_LIVE_JOIN_CODE=3:<code> while a local Wrangler relay is active.
    /// Adding TACMAP_CHAT_INTEROP_RUN_ID=<unique-id> turns the handshake check
    /// into the iOS half of the Android-first bidirectional Chat exchange.
    @MainActor func testLiveProductionHandshakeWhenRelayIsProvided() async throws {
        let environment = ProcessInfo.processInfo.environment
        guard let relay = environment["TACMAP_LIVE_RELAY"],
              let joinCode = environment["TACMAP_LIVE_JOIN_CODE"] else {
            throw XCTSkip("Set TACMAP_LIVE_RELAY and TACMAP_LIVE_JOIN_CODE to run the live handshake regression.")
        }
        guard joinCode.hasPrefix("3:") else {
            XCTFail("The live production regression requires a v3 join code.")
            return
        }
        let roomId = SyncCrypto.deriveRoomV3(String(joinCode.dropFirst(2))).roomId
        let hostedState = try HostedAppSyncStateSnapshot(roomId: roomId)

        let previousRelay = OpsecSettings.shared.relayURL
        let previousKeyProvider = SafeStore.keyProvider
        XCTAssertTrue(OpsecSettings.shared.setRelayURL(relay))
        // Keep this opt-in test isolated from the app's real Keychain-backed
        // at-rest key. The manager, WebSocket, snapshot parser, signed hello,
        // replay state and relay ack remain the production implementations.
        let liveTestKey = Data(repeating: 0x5a, count: 32)
        SafeStore.keyProvider = { liveTestKey }
        SealedMigrationPolicy.resetForTests(key: liveTestKey)
        let waypointStore = WaypointStore()
        let manager = SyncManager()
        manager.configure(waypointStore: waypointStore, drawingStore: DrawingStore())
        var livePresence = manager.presenceConfig
        livePresence.callsign = "iOS Interop"
        XCTAssertTrue(manager.updatePresenceConfig(livePresence))
        defer {
            manager.leave()
            SealedMigrationPolicy.resetForTests(key: liveTestKey)
            SafeStore.keyProvider = previousKeyProvider
            _ = OpsecSettings.shared.setRelayURL(previousRelay)
            do {
                try hostedState.restore()
            } catch {
                XCTFail(
                    "Live-test state restoration failed; recovery backup retained at "
                        + "\(hostedState.recoveryBackupPath): \(error.localizedDescription)"
                )
            }
        }

        manager.join(joinCode)
        let deadline = Date().addingTimeInterval(10)
        while Date() < deadline {
            if case .connected = manager.status {
                break
            }
            if manager.lastError != nil { break }
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        guard case .connected = manager.status else {
            XCTFail("Production v3 handshake did not connect: \(manager.lastError ?? "no protocol error")")
            return
        }

        if environment["TACMAP_LIVE_EMIT_OBJECT"] == "1" {
            _ = try waypointStore.addDurably(Waypoint(
                name: "iOS live interop marker", notes: "TACMAP-IOS-ANDROID-INTEROP",
                latitude: -33.8688, longitude: 151.2093))
            try await Task.sleep(nanoseconds: 1_000_000_000)
        }

        guard let rawRunID = environment["TACMAP_CHAT_INTEROP_RUN_ID"] else { return }
        let runID = rawRunID.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !runID.isEmpty, runID.utf8.count <= 256 else {
            XCTFail("TACMAP_CHAT_INTEROP_RUN_ID must contain 1...256 UTF-8 bytes.")
            return
        }

        let androidRoomBody = "ANDROID_TO_IOS_ROOM::\(runID)"
        let androidDirectBody = "ANDROID_TO_IOS_DIRECT::\(runID)"
        let iosRoomBody = "IOS_TO_ANDROID_ROOM::\(runID)"
        let iosDirectBody = "IOS_TO_ANDROID_DIRECT::\(runID)"

        // Android is the deterministic initiator. Requiring one acknowledged
        // peer key before inspecting history proves the manager completed the
        // signed hello + ephemeral chat-key exchange, not merely the socket
        // handshake, before either routed message is accepted.
        let inboundDeadline = Date().addingTimeInterval(30)
        var androidPeer: TacMapChatRecipient?
        while Date() < inboundDeadline {
            let messages = manager.chatStore.messages
            if manager.chatSessionReady,
               manager.chatRecipients.count == 1,
               let peer = manager.chatRecipients.values.first,
               messages.contains(where: {
                   !$0.isOutgoing && $0.scope == .room && $0.kind == .report
                       && $0.body == androidRoomBody && $0.senderActorId == peer.actorId
               }),
               messages.contains(where: {
                   !$0.isOutgoing && $0.scope == .direct && $0.kind == .text
                       && $0.body == androidDirectBody && $0.senderActorId == peer.actorId
               }) {
                androidPeer = peer
                break
            }
            if manager.lastError != nil { break }
            try await Task.sleep(nanoseconds: 100_000_000)
        }

        guard let peer = androidPeer else {
            XCTFail(
                "iOS did not authenticate and decrypt both Android Chat frames: "
                    + (manager.chatSessionIssue ?? manager.lastError ?? "timed out")
            )
            return
        }
        let inboundRoom = try XCTUnwrap(manager.chatStore.messages.first(where: {
            !$0.isOutgoing && $0.body == androidRoomBody
        }))
        let inboundDirect = try XCTUnwrap(manager.chatStore.messages.first(where: {
            !$0.isOutgoing && $0.body == androidDirectBody
        }))
        XCTAssertEqual(inboundRoom.scope, .room)
        XCTAssertEqual(inboundRoom.kind, .report)
        XCTAssertEqual(inboundRoom.senderActorId, peer.actorId)
        XCTAssertNil(inboundRoom.recipientActorId)
        XCTAssertEqual(inboundRoom.deliveryState, .received)
        XCTAssertEqual(inboundDirect.scope, .direct)
        XCTAssertEqual(inboundDirect.kind, .text)
        XCTAssertEqual(inboundDirect.senderActorId, peer.actorId)
        XCTAssertNotNil(inboundDirect.recipientActorId)
        XCTAssertEqual(inboundDirect.deliveryState, .received)

        // The response order is the Android test's barrier: it does not finish
        // until its production manager has independently opened both frames.
        let roomMessageID = try manager.sendChat(
            body: iosRoomBody,
            kind: .report,
            scope: .room,
            recipient: nil
        )
        let directMessageID = try manager.sendChat(
            body: iosDirectBody,
            kind: .text,
            scope: .direct,
            recipient: peer
        )
        let localActorId = try XCTUnwrap(manager.chatStore.messages.first(where: {
            $0.id == roomMessageID && $0.isOutgoing
        })?.senderActorId)
        XCTAssertEqual(inboundDirect.recipientActorId, localActorId)

        let acknowledgementDeadline = Date().addingTimeInterval(30)
        while Date() < acknowledgementDeadline {
            let outgoing = Dictionary(
                uniqueKeysWithValues: manager.chatStore.messages
                    .filter { $0.isOutgoing && ($0.id == roomMessageID || $0.id == directMessageID) }
                    .map { ($0.id, $0) }
            )
            if outgoing[roomMessageID]?.deliveryState == .routed,
               outgoing[directMessageID]?.deliveryState == .routed {
                XCTAssertEqual(outgoing[roomMessageID]?.body, iosRoomBody)
                XCTAssertEqual(outgoing[roomMessageID]?.scope, .room)
                XCTAssertEqual(outgoing[roomMessageID]?.kind, .report)
                XCTAssertNil(outgoing[roomMessageID]?.recipientActorId)
                XCTAssertEqual(outgoing[roomMessageID]?.senderActorId, localActorId)
                XCTAssertEqual(outgoing[directMessageID]?.body, iosDirectBody)
                XCTAssertEqual(outgoing[directMessageID]?.scope, .direct)
                XCTAssertEqual(outgoing[directMessageID]?.kind, .text)
                XCTAssertEqual(outgoing[directMessageID]?.recipientActorId, peer.actorId)
                XCTAssertEqual(outgoing[directMessageID]?.senderActorId, localActorId)
                return
            }
            if outgoing.values.contains(where: { $0.deliveryState == .failed }) { break }
            if manager.lastError != nil { break }
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        let states = manager.chatStore.messages
            .filter { $0.id == roomMessageID || $0.id == directMessageID }
            .map { "\($0.id)=\($0.deliveryState.rawValue):\($0.failureCode ?? "none")" }
            .joined(separator: ", ")
        XCTFail(
            "Relay did not acknowledge both iOS Chat frames: "
                + (manager.chatSessionIssue ?? manager.lastError ?? states)
        )
    }

    func testOutboundDeliveryRequiresExactAckAndIgnoresDuplicateDelayedOrReplacementSessionReplies() {
        let tracker = OutboundDeliveryTracker()
        let first = pendingDelivery()
        tracker.register(first)
        XCTAssertNil(tracker.acknowledge(DeliveryAck(
            version: 1, requestId: first.requestId, actorId: first.actorId,
            sessionDomain: "replacement", wireObjectId: first.wireObjectId,
            objectVersion: first.objectVersion, kind: first.kind,
            ciphertextHash: first.ciphertextHash)))

        let replacement = pendingDelivery(
            requestId: "request_delivery_b", objectVersion: "v2", contentHash: "content-b")
        tracker.register(replacement)
        XCTAssertNil(tracker.acknowledge(ack(for: first)), "a superseded generation's delayed ack must be ignored")
        XCTAssertEqual(tracker.pending(localId: first.localId)?.requestId, replacement.requestId)
        XCTAssertEqual(tracker.acknowledge(ack(for: replacement)), replacement)
        XCTAssertNil(tracker.acknowledge(ack(for: replacement)), "duplicate ack is idempotent")
    }

    func testOutboundDeliveryLostSendRetryRejectionAndReconnectPolicy() {
        let tracker = OutboundDeliveryTracker(maxAttempts: 3)
        let delivery = pendingDelivery()
        tracker.register(delivery)
        XCTAssertNotNil(tracker.nextAttempt(
            requestId: delivery.requestId, generation: 1, sessionDomain: "session-a"))
        XCTAssertNotNil(tracker.nextAttempt(
            requestId: delivery.requestId, generation: 1, sessionDomain: "session-a"))
        XCTAssertNil(tracker.nextAttempt(
            requestId: delivery.requestId, generation: 1, sessionDomain: "session-a"))

        let rejectedTracker = OutboundDeliveryTracker()
        rejectedTracker.register(delivery)
        let rejected = rejectedTracker.reject(DeliveryNack(
            version: 1, requestId: delivery.requestId, actorId: delivery.actorId,
            sessionDomain: delivery.sessionDomain, code: "quota", retryable: false))
        XCTAssertEqual(rejected?.rejectionCode, "quota")
        XCTAssertNil(rejectedTracker.nextAttempt(
            requestId: delivery.requestId, generation: 1, sessionDomain: "session-a"))
        XCTAssertEqual(rejectedTracker.resetForReconnect(), Set([delivery.localId]))
        XCTAssertTrue(rejectedTracker.all().isEmpty)

        let storageTracker = OutboundDeliveryTracker()
        storageTracker.register(delivery)
        _ = storageTracker.reject(DeliveryNack(
            version: 1, requestId: delivery.requestId, actorId: delivery.actorId,
            sessionDomain: delivery.sessionDomain, code: "storage", retryable: true))
        XCTAssertNotNil(storageTracker.nextAttempt(
            requestId: delivery.requestId, generation: 1, sessionDomain: "session-a"))
    }

    func testRollbackWarningSurvivesSameGenerationHelloAckAndOnlyLaterCleanSnapshotClearsIt() {
        let lifecycle = SyncIssueLifecycle()
        let warned = lifecycle.beginConnection()
        lifecycle.report(.literal("rollback"), kind: .security, generation: warned)
        XCTAssertEqual(
            lifecycle.connectionSucceeded(generation: warned, verifiedCleanSnapshot: true)?.message,
            "rollback")

        let later = lifecycle.beginConnection()
        lifecycle.report(.literal("temporary disconnect"), kind: .connection, generation: later)
        XCTAssertEqual(lifecycle.issue?.message, "rollback")
        XCTAssertNil(lifecycle.connectionSucceeded(generation: later, verifiedCleanSnapshot: true))
    }

    func testLocalMigrationWarningSurvivesSnapshotsAndDismissalUntilMigrationSucceeds() {
        let lifecycle = SyncIssueLifecycle()
        let generation = lifecycle.beginConnection()
        lifecycle.reportPersistentSecurity(.literal("storage migration"), generation: generation)

        let retryGeneration = lifecycle.beginConnection()
        XCTAssertEqual(
            lifecycle.connectionSucceeded(
                generation: retryGeneration,
                verifiedCleanSnapshot: true
            )?.message,
            "storage migration"
        )
        lifecycle.report(.literal("rollback"), kind: .security, generation: retryGeneration)
        XCTAssertEqual(lifecycle.issue?.message, "rollback")
        XCTAssertEqual(lifecycle.dismiss()?.message, "storage migration")
        XCTAssertNil(lifecycle.clearPersistentSecurity())
    }

    func testReconnectDoesNotResendATombstoneAlreadyConfirmedBySnapshot() {
        let value = VersionStamp(counter: 7, actorId: actorA)
        XCTAssertFalse(shouldResendRecoverableDelete(
            wireObjectId: wireId,
            stamp: value,
            confirmedSnapshotDeletes: [wireId: value.encode()]))
        XCTAssertTrue(shouldResendRecoverableDelete(
            wireObjectId: wireId,
            stamp: value,
            confirmedSnapshotDeletes: [:]))
    }

    func testV2SnapshotGateBuffersEmptyAndMultipageSnapshotsUntilExactEnd() {
        let socket = NSObject()
        let gate = V2SnapshotGate(maxItems: 10, maxAggregateBytes: 10_000)
        gate.start(socketIdentity: socket, generation: 7)

        guard case .began = gate.accept(
            socketIdentity: socket,
            generation: 7,
            message: ["t": "snapshot-begin", "seq": 3],
            frameBytes: 20
        ) else { return XCTFail("valid begin must open the snapshot fence") }
        guard case .pageAccepted = gate.accept(
            socketIdentity: socket,
            generation: 7,
            message: [
                "t": "snapshot",
                "items": [["id": "one"]],
                "members": [["clientId": "peer"]],
                "more": true
            ],
            frameBytes: 30
        ) else { return XCTFail("a non-final page must remain outbound-gated") }
        guard case .pageAccepted = gate.accept(
            socketIdentity: socket,
            generation: 7,
            message: ["t": "snapshot", "items": [], "more": false],
            frameBytes: 20
        ) else { return XCTFail("an empty final page must be accepted") }
        guard case .completed(let batch) = gate.accept(
            socketIdentity: socket,
            generation: 7,
            message: ["t": "snapshot-end", "seq": 3],
            frameBytes: 20
        ) else { return XCTFail("only the matching end fence may release outbound") }
        XCTAssertEqual(batch.sequence, 3)
        XCTAssertEqual(batch.records.first?["id"] as? String, "one")
        XCTAssertEqual(batch.members.first?["clientId"] as? String, "peer")
        guard case .ignored = gate.accept(
            socketIdentity: socket,
            generation: 7,
            message: ["t": "snapshot-end", "seq": 3],
            frameBytes: 20
        ) else { return XCTFail("a completed fence must release at most once") }
    }

    func testV2SnapshotGateRejectsOrderTimeoutAndStaleSocketCompletion() {
        let oldSocket = NSObject()
        let newSocket = NSObject()
        let gate = V2SnapshotGate(maxItems: 10, maxAggregateBytes: 10_000)
        gate.start(socketIdentity: oldSocket, generation: 4)
        guard case .rejected = gate.accept(
            socketIdentity: oldSocket,
            generation: 4,
            message: ["t": "snapshot-end", "seq": 1],
            frameBytes: 10
        ) else { return XCTFail("end-before-begin must fail closed") }

        gate.start(socketIdentity: oldSocket, generation: 5)
        _ = gate.accept(
            socketIdentity: oldSocket,
            generation: 5,
            message: ["t": "snapshot-begin", "seq": 1],
            frameBytes: 10
        )
        gate.start(socketIdentity: newSocket, generation: 6)
        guard case .ignored = gate.accept(
            socketIdentity: oldSocket,
            generation: 5,
            message: ["t": "snapshot-end", "seq": 1],
            frameBytes: 10
        ) else { return XCTFail("a stale socket must not release its replacement") }
        guard case .rejected = gate.timeout(socketIdentity: newSocket, generation: 6) else {
            return XCTFail("an incomplete current snapshot must time out fail-closed")
        }
    }

    func testLegacyDeleteRecoveryRequiresExactVerifiedCurrentSnapshotTombstone() {
        let delivery = PendingOutboundDelivery(
            localId: "local-a", requestId: "request_delivery_a", connectionGeneration: 4,
            actorId: "actor-a", sessionDomain: nil, wireObjectId: "wire-a",
            objectVersion: "7", kind: "del", ciphertextHash: "cipher-a",
            desiredContentHash: nil, desiredContent: nil, frame: "frame"
        )
        let recovery = LegacyDeleteRecovery(delivery: delivery, snapshotGeneration: 5)
        XCTAssertTrue(recovery.matchesVerifiedTombstone(
            localId: "local-a", wireObjectId: "wire-a", actorId: "actor-a",
            objectVersion: "7", kind: "del", ciphertextHash: "cipher-a",
            snapshotGeneration: 5
        ))
        XCTAssertFalse(recovery.matchesVerifiedTombstone(
            localId: "local-a", wireObjectId: "wire-a", actorId: "actor-a",
            objectVersion: "6", kind: "del", ciphertextHash: "cipher-a",
            snapshotGeneration: 5
        ))
        XCTAssertFalse(recovery.matchesVerifiedTombstone(
            localId: "local-a", wireObjectId: "wire-a", actorId: "actor-a",
            objectVersion: "7", kind: "waypoint", ciphertextHash: "cipher-a",
            snapshotGeneration: 5
        ))
        XCTAssertFalse(recovery.matchesVerifiedTombstone(
            localId: "local-a", wireObjectId: "wire-a", actorId: "actor-a",
            objectVersion: "7", kind: "del", ciphertextHash: "cipher-a",
            snapshotGeneration: 4
        ))
    }

    func testDelayedReconnectGuardRejectsLeaveRejoinAndReplacementSocket() {
        XCTAssertTrue(SyncReconnectAttemptGuard.shouldRun(
            scheduledGeneration: 4, currentGeneration: 4,
            wantsConnection: true, hasActiveSocket: false
        ))
        XCTAssertFalse(SyncReconnectAttemptGuard.shouldRun(
            scheduledGeneration: 4, currentGeneration: 4,
            wantsConnection: false, hasActiveSocket: false
        ))
        XCTAssertFalse(SyncReconnectAttemptGuard.shouldRun(
            scheduledGeneration: 4, currentGeneration: 5,
            wantsConnection: true, hasActiveSocket: false
        ))
        XCTAssertFalse(SyncReconnectAttemptGuard.shouldRun(
            scheduledGeneration: 4, currentGeneration: 4,
            wantsConnection: true, hasActiveSocket: true
        ))
    }

    func testDeliveryAckVersionRejectsFractionalExponentBooleanAndString() throws {
        func av(_ json: String) throws -> Any? {
            (try JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any])?["av"]
        }
        XCTAssertEqual(SyncManager.strictJSONInteger(
            try av("{\"av\":1}"), minimum: 1, maximum: 1), 1)
        for json in ["{\"av\":1.5}", "{\"av\":1e0}", "{\"av\":true}", "{\"av\":\"1\"}"] {
            XCTAssertNil(SyncManager.strictJSONInteger(
                try av(json), minimum: 1, maximum: 1), json)
        }
    }

    // MARK: - Helpers

    private func pendingDelivery(
        requestId: String = "request_delivery_a",
        objectVersion: String = "v1",
        contentHash: String = "content-a"
    ) -> PendingOutboundDelivery {
        PendingOutboundDelivery(
            localId: "local-a", requestId: requestId, connectionGeneration: 1,
            actorId: "actor-a", sessionDomain: "session-a", wireObjectId: "wire-a",
            objectVersion: objectVersion, kind: "waypoint", ciphertextHash: "cipher-a",
            desiredContentHash: contentHash, desiredContent: "content", frame: "frame")
    }

    private func ack(for delivery: PendingOutboundDelivery) -> DeliveryAck {
        DeliveryAck(
            version: 1, requestId: delivery.requestId, actorId: delivery.actorId,
            sessionDomain: delivery.sessionDomain, wireObjectId: delivery.wireObjectId,
            objectVersion: delivery.objectVersion, kind: delivery.kind,
            ciphertextHash: delivery.ciphertextHash)
    }

    private func hex(_ data: Data) -> String {
        data.map { String(format: "%02x", $0) }.joined()
    }

    private func withReplayPersistenceSandbox<T>(
        keyByte: UInt8,
        _ body: (URL, Data) throws -> T
    ) throws -> T {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("sync-replay-local-actor-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let previousKeyProvider = SafeStore.keyProvider
        let key = Data(repeating: keyByte, count: 32)
        SafeStore.keyProvider = { key }
        SealedMigrationPolicy.resetForTests(key: key)
        defer {
            SafeStore.keyProvider = previousKeyProvider
            try? FileManager.default.removeItem(at: directory)
        }
        return try body(directory, key)
    }

    private func removeReplayActorPin(
        _ actorId: String,
        roomId: String,
        directory: URL,
        key: Data
    ) throws {
        let url = try currentReplayURL(directory: directory, roomId: roomId, key: key)
        let label = "sync/room/\(roomId)"
        let sealed = try Data(contentsOf: url)
        let plaintext = try XCTUnwrap(
            SealedEnvelope.openFile(key: key, blob: sealed, label: label)
        )
        var dictionary = try XCTUnwrap(
            JSONSerialization.jsonObject(with: plaintext) as? [String: Any]
        )
        var actors = try XCTUnwrap(dictionary["actors"] as? [String: [String: Any]])
        XCTAssertNotNil(actors.removeValue(forKey: actorId))
        dictionary["actors"] = actors
        let orphaned = try JSONSerialization.data(withJSONObject: dictionary, options: [.sortedKeys])
        try SafeStore.write(orphaned, to: url, label: label)
    }

    private func currentReplayURL(directory: URL, roomId: String, key: Data) throws -> URL {
        directory
            .appendingPathComponent("sync_replay", isDirectory: true)
            .appendingPathComponent(try SyncLocalStore.fileName(
                dataKey: key,
                roomId: roomId,
                domain: .replay
            ))
    }

    private var identity: [String: Any] { fixture["identity"] as! [String: Any] }
    private var deviceA: [String: Any] { identity["device_a"] as! [String: Any] }
    private var deviceB: [String: Any] { identity["device_b"] as! [String: Any] }
    private var actorA: String { deviceA["actor_id"] as! String }
    private var actorB: String { deviceB["actor_id"] as! String }
    private var pubA: String { deviceA["pubkey_base64url"] as! String }
    private var pubB: String { deviceB["pubkey_base64url"] as! String }
    private var fixtureRoomId: String {
        (fixture["key_derivation"] as! [String: Any])["room_id"] as! String
    }
    private var sessionDomain: String {
        let hex = (fixture["signed_preimage"] as! [String: Any])["session_domain_hex"] as! String
        return SyncIdentity.hexToBytes(hex).base64URLEncodedStringNoPad()
    }
    private var wireId: String {
        let cases = (fixture["wire_object_ids"] as! [String: Any])["cases"] as! [[String: Any]]
        return cases[0]["wire_object_id"] as! String
    }
    private func stamp(_ counter: Int64, _ actor: String) -> VersionStamp {
        VersionStamp(counter: counter, actorId: actor)
    }
}
