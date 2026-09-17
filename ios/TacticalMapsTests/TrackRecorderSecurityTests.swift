import XCTest
import CoreLocation
import CryptoKit
@testable import TacticalMaps

final class TrackRecorderSecurityTests: XCTestCase {
    private enum FakeKeychainError: Error { case forced }

    private final class FakeAppLockKeychain: AppLockKeychainClient {
        var storage: [String: Data] = [:]
        var failingWrites: Set<String> = []
        var failingDeletes: Set<String> = []
        var corruptReadAfterWrite: Set<String> = []
        var writtenSinceReset: Set<String> = []
        var operations: [String] = []

        func read(account: String) throws -> Data? {
            operations.append("read:\(account)")
            if corruptReadAfterWrite.contains(account), writtenSinceReset.contains(account) {
                return Data("corrupt-readback".utf8)
            }
            return storage[account]
        }

        func updateOrAdd(_ data: Data, account: String) throws {
            operations.append("write:\(account)")
            guard !failingWrites.contains(account) else { throw FakeKeychainError.forced }
            storage[account] = data
            writtenSinceReset.insert(account)
        }

        func delete(account: String) throws {
            operations.append("delete:\(account)")
            guard !failingDeletes.contains(account) else { throw FakeKeychainError.forced }
            storage.removeValue(forKey: account)
        }
    }

    private let testKey = Data((0..<32).map { UInt8($0 &+ 64) })

    override func setUp() {
        super.setUp()
        SafeStore.keyProvider = { [testKey] in testKey }
        SealedMigrationPolicy.resetForTests(key: testKey)
    }

    override func tearDown() {
        SafeStore.keyProvider = { try DataKey.key() }
        SealedMigrationPolicy.resetForTests(key: testKey)
        super.tearDown()
    }

    private func temporaryFile() -> URL {
        FileManager.default.temporaryDirectory
            .appendingPathComponent("track-security-\(UUID().uuidString)")
            .appendingPathComponent("recording.ndjson")
    }

    private func testDefaults() -> (UserDefaults, String) {
        let suite = "TrackRecorderSecurityTests.\(UUID().uuidString)"
        return (UserDefaults(suiteName: suite)!, suite)
    }

    private func oneRoundHash(pin: String, salt: Data) -> Data {
        Data(SHA256.hash(data: salt + Data(pin.utf8) + salt))
    }

    func testFreshInstallTreatsActuallyMissingRecordingAsAbsent() throws {
        let url = temporaryFile()
        let directory = url.deletingLastPathComponent()
        defer { try? FileManager.default.removeItem(at: directory) }

        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path))

        // Exercise the production FileManager and String readers. This is the
        // actual first-launch path, not an injected approximation of its error.
        let recorder = TrackRecorder(fileURL: url)

        XCTAssertFalse(recorder.requiresUnlock)
        XCTAssertNil(recorder.persistError)
        XCTAssertTrue(recorder.start())
        XCTAssertTrue(recorder.isRecording)
        XCTAssertTrue(FileManager.default.fileExists(atPath: url.path))
    }

    func testStartDoesNotOverwriteWhenExistingFileAttributesAreUnavailable() throws {
        let url = temporaryFile()
        try FileManager.default.createDirectory(
            at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        let original = Data("existing mission track".utf8)
        try original.write(to: url)
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }

        let recorder = TrackRecorder(
            fileURL: url,
            attributeReader: { _ in throw CocoaError(.fileReadNoPermission) }
        )

        XCTAssertFalse(recorder.start())
        XCTAssertFalse(recorder.isRecording)
        XCTAssertEqual(try Data(contentsOf: url), original)
    }

    func testNonCocoaErrorCode260DoesNotAuthorizeOverwrite() throws {
        let url = temporaryFile()
        try FileManager.default.createDirectory(
            at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        let original = Data("existing mission track".utf8)
        try original.write(to: url)
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }

        let recorder = TrackRecorder(
            fileURL: url,
            attributeReader: { _ in
                throw NSError(domain: "UntrustedErrorDomain", code: 260)
            }
        )

        XCTAssertFalse(recorder.start())
        XCTAssertFalse(recorder.isRecording)
        XCTAssertEqual(try Data(contentsOf: url), original)
    }

    func testProtectedRecoveryCanRetryAfterUnlock() throws {
        final class ReadGate { var available = false }

        let url = temporaryFile()
        try FileManager.default.createDirectory(
            at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let pointJSON = try JSONSerialization.data(withJSONObject: [
            "lat": -33.86, "lon": 151.21, "ele": 12.0, "t": 1_700_000_000.0,
        ])
        let line = try SealedEnvelope.sealLine(
            key: testKey, plaintext: pointJSON, label: "tracks/recording.ndjson") + "\n"
        try Data(line.utf8).write(to: url)
        let gate = ReadGate()

        let recorder = TrackRecorder(
            fileURL: url,
            textReader: { url in
                guard gate.available else { throw CocoaError(.fileReadNoPermission) }
                return try String(contentsOf: url, encoding: .utf8)
            }
        )

        XCTAssertTrue(recorder.requiresUnlock)
        XCTAssertTrue(recorder.points.isEmpty)

        gate.available = true
        recorder.retryRecoveryAfterUnlock()

        XCTAssertFalse(recorder.requiresUnlock)
        XCTAssertTrue(recorder.recovered)
        XCTAssertEqual(recorder.points.count, 1)
        XCTAssertEqual(recorder.points[0].coordinate.latitude, -33.86, accuracy: 0.000_001)
        XCTAssertEqual(recorder.points[0].coordinate.longitude, 151.21, accuracy: 0.000_001)
    }

    func testSealedTrackRequiresDurablePolicyBeforeRecoveryAndRejectsDowngrade() throws {
        let url = temporaryFile()
        try FileManager.default.createDirectory(
            at: url.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let pointJSON = try JSONSerialization.data(withJSONObject: [
            "lat": -33.86, "lon": 151.21, "ele": 12.0, "t": 1_700_000_000.0,
        ])
        let sealedLine = try SealedEnvelope.sealLine(
            key: testKey,
            plaintext: pointJSON,
            label: "tracks/recording.ndjson"
        ) + "\n"
        let sealedBytes = Data(sealedLine.utf8)
        try sealedBytes.write(to: url)
        SealedMigrationPolicy.resetForTests(key: testKey)

        let unavailable = TrackRecorder(
            fileURL: url,
            markSealedPolicy: { _, _ in throw FakeKeychainError.forced }
        )
        XCTAssertTrue(unavailable.requiresUnlock)
        XCTAssertFalse(unavailable.recovered)
        XCTAssertTrue(unavailable.points.isEmpty)
        XCTAssertEqual(try Data(contentsOf: url), sealedBytes)

        let recovered = TrackRecorder(fileURL: url)
        XCTAssertTrue(recovered.recovered)
        XCTAssertEqual(recovered.points.count, 1)

        // The successful recovery established the authenticated fence. A
        // later plaintext replacement must never be accepted as legacy input.
        try (pointJSON + Data("\n".utf8)).write(to: url, options: .atomic)
        let downgraded = TrackRecorder(fileURL: url)
        XCTAssertFalse(downgraded.recovered)
        XCTAssertTrue(downgraded.points.isEmpty)
        XCTAssertTrue(downgraded.persistError?.contains("sealed-only") == true)
    }

    func testPlaintextTrackMigrationDoesNotPublishUntilPolicyAndCiphertextAreDurable() throws {
        let url = temporaryFile()
        try FileManager.default.createDirectory(
            at: url.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let pointJSON = try JSONSerialization.data(withJSONObject: [
            "lat": -33.86, "lon": 151.21, "ele": 12.0, "t": 1_700_000_000.0,
        ])
        let plaintextBytes = pointJSON + Data("\n".utf8)
        try plaintextBytes.write(to: url)
        var failPolicyWrite = true

        let recorder = TrackRecorder(
            fileURL: url,
            markSealedPolicy: { identifier, key in
                if failPolicyWrite { throw FakeKeychainError.forced }
                try SealedMigrationPolicy.markSealed(identifier, key: key)
            }
        )
        XCTAssertTrue(recorder.requiresUnlock)
        XCTAssertFalse(recorder.recovered)
        XCTAssertTrue(recorder.points.isEmpty)
        XCTAssertEqual(try Data(contentsOf: url), plaintextBytes)

        failPolicyWrite = false
        recorder.retryRecoveryAfterUnlock()
        XCTAssertFalse(recorder.requiresUnlock)
        XCTAssertTrue(recorder.recovered)
        XCTAssertEqual(recorder.points.count, 1)
        let migrated = try String(contentsOf: url, encoding: .utf8)
        XCTAssertTrue(migrated.split(separator: "\n").allSatisfy {
            SealedEnvelope.isSealedLine(String($0))
        })
    }

    func testStopClearsScopedRecordingKey() throws {
        let url = temporaryFile()
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let recorder = TrackRecorder(fileURL: url)

        XCTAssertTrue(recorder.start())
        XCTAssertTrue(recorder.hasScopedRecordingKeyForTesting)

        recorder.stop()

        XCTAssertFalse(recorder.hasScopedRecordingKeyForTesting)
    }

    func testStartFailureClearsPreviouslyScopedRecordingKey() throws {
        let url = temporaryFile()
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        var keyAvailable = true
        SafeStore.keyProvider = { [testKey] in
            guard keyAvailable else { throw FakeKeychainError.forced }
            return testKey
        }
        let recorder = TrackRecorder(fileURL: url)

        XCTAssertTrue(recorder.start())
        XCTAssertTrue(recorder.hasScopedRecordingKeyForTesting)
        keyAvailable = false

        XCTAssertFalse(recorder.start())
        XCTAssertFalse(recorder.hasScopedRecordingKeyForTesting)
        XCTAssertFalse(recorder.isRecording)
    }

    func testAppendFailureClearsScopedRecordingKey() throws {
        let url = temporaryFile()
        let directory = url.deletingLastPathComponent()
        defer { try? FileManager.default.removeItem(at: directory) }
        let recorder = TrackRecorder(fileURL: url)

        XCTAssertTrue(recorder.start())
        XCTAssertTrue(recorder.hasScopedRecordingKeyForTesting)

        try FileManager.default.removeItem(at: url)
        try FileManager.default.removeItem(at: directory)
        try Data("not-a-directory".utf8).write(to: directory)
        recorder.ingest(CLLocation(latitude: -33.86, longitude: 151.21))

        XCTAssertFalse(recorder.isRecording)
        XCTAssertFalse(recorder.hasScopedRecordingKeyForTesting)
        XCTAssertNotNil(recorder.persistError)
        let originalLanguage = AppLanguage.shared.selection
        defer { AppLanguage.shared.select(originalLanguage) }
        AppLanguage.shared.select(.de)
        XCTAssertEqual(recorder.persistError, "Die Trackaufzeichnung wurde gestoppt, weil eine Position nicht gespeichert werden konnte.")
        AppLanguage.shared.select(.en)
        XCTAssertEqual(recorder.persistError, "Track recording stopped because a fix could not be saved.")
        recorder.persistError = nil
        XCTAssertNil(recorder.persistError)
    }

    func testRecordingWaitsForPermissionThenStartsExactlyOnceAfterDurableSetup() {
        var authorizationRequests = 0
        var durableStarts = 0
        var backgroundUpdates: [Bool] = []
        let coordinator = RecordingCoordinator(
            requestAuthorization: { authorizationRequests += 1 },
            initializeDurableRecording: {
                durableStarts += 1
                return true
            },
            stopRecording: {},
            setBackgroundUpdates: { backgroundUpdates.append($0) },
            recordingError: { nil }
        )

        coordinator.start(authorization: .notDetermined)
        XCTAssertEqual(coordinator.state, .awaitingPermission)
        XCTAssertEqual(authorizationRequests, 1)
        XCTAssertEqual(durableStarts, 0)
        XCTAssertTrue(backgroundUpdates.isEmpty)

        coordinator.start(authorization: .notDetermined)
        XCTAssertEqual(authorizationRequests, 1, "an in-flight permission request must not repeat")

        coordinator.authorizationChanged(.authorizedWhenInUse)
        XCTAssertEqual(coordinator.state, .recording)
        XCTAssertEqual(durableStarts, 1)
        XCTAssertEqual(backgroundUpdates, [true])

        coordinator.authorizationChanged(.authorizedWhenInUse)
        XCTAssertEqual(durableStarts, 1, "duplicate authorization callbacks must not restart the log")
    }

    func testDeniedAndRestrictedRecordingRemainIdleWithSettingsGuidance() {
        for status in [CLAuthorizationStatus.denied, .restricted] {
            var durableStarts = 0
            let coordinator = RecordingCoordinator(
                requestAuthorization: {},
                initializeDurableRecording: {
                    durableStarts += 1
                    return true
                },
                stopRecording: {},
                setBackgroundUpdates: { _ in },
                recordingError: { nil }
            )

            coordinator.start(authorization: status)

            XCTAssertEqual(coordinator.state, .idle)
            XCTAssertEqual(durableStarts, 0)
            XCTAssertEqual(coordinator.guidance?.offersSettings, true)
            XCTAssertTrue(coordinator.guidance?.message.contains("Settings") == true)
        }
    }

    func testDurableLogFailureNeverPublishesRecording() {
        var backgroundUpdates: [Bool] = []
        let coordinator = RecordingCoordinator(
            requestAuthorization: {},
            initializeDurableRecording: { false },
            stopRecording: {},
            setBackgroundUpdates: { backgroundUpdates.append($0) },
            recordingError: { .literal("Disk unavailable") }
        )

        coordinator.start(authorization: .authorizedWhenInUse)

        XCTAssertEqual(coordinator.state, .interrupted(.literal("Disk unavailable")))
        XCTAssertEqual(backgroundUpdates, [false])
    }

    func testMidRecordingRevocationStopsBackgroundModeAndPreservesDurableLog() throws {
        let url = temporaryFile()
        let directory = url.deletingLastPathComponent()
        defer { try? FileManager.default.removeItem(at: directory) }
        let recorder = TrackRecorder(fileURL: url)
        var backgroundUpdates: [Bool] = []
        let coordinator = RecordingCoordinator(
            requestAuthorization: {},
            initializeDurableRecording: { recorder.start() },
            stopRecording: { recorder.stop() },
            setBackgroundUpdates: { backgroundUpdates.append($0) },
            recordingError: { recorder.pendingPersistError }
        )

        coordinator.start(authorization: .authorizedWhenInUse)
        XCTAssertEqual(coordinator.state, .recording)
        XCTAssertTrue(recorder.isRecording)
        XCTAssertTrue(FileManager.default.fileExists(atPath: url.path))

        coordinator.authorizationChanged(.denied)

        XCTAssertFalse(recorder.isRecording)
        XCTAssertEqual(backgroundUpdates, [true, false])
        XCTAssertTrue(FileManager.default.fileExists(atPath: url.path), "revocation must preserve the log")
        guard case .interrupted(let message) = coordinator.state else {
            return XCTFail("revocation must publish an interrupted state")
        }
        XCTAssertTrue(message.text.contains("preserved"))
        XCTAssertEqual(coordinator.guidance?.offersSettings, true)
    }

    func testAppLockUsesOneCheckedCredentialRecord() throws {
        let keychain = FakeAppLockKeychain()
        let (defaults, suite) = testDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = AppLockStore(
            keychain: keychain,
            defaults: defaults,
            iterations: 1,
            saltProvider: { Data(repeating: 7, count: 16) }
        )

        try store.setPIN("1234")

        XCTAssertNotNil(keychain.storage[AppLockStore.credentialAccount])
        XCTAssertNil(keychain.storage[AppLockStore.legacySaltAccount])
        XCTAssertNil(keychain.storage[AppLockStore.legacyHashAccount])
        XCTAssertTrue(store.verify("1234"))
        XCTAssertFalse(store.verify("9999"))
        let object = try XCTUnwrap(
            JSONSerialization.jsonObject(
                with: XCTUnwrap(keychain.storage[AppLockStore.credentialAccount])
            ) as? [String: Any]
        )
        XCTAssertEqual(object["version"] as? Int, 1)
    }

    func testFailedCredentialUpdatePreservesPreviousWorkingPIN() throws {
        let keychain = FakeAppLockKeychain()
        let (defaults, suite) = testDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = AppLockStore(
            keychain: keychain,
            defaults: defaults,
            iterations: 1,
            saltProvider: { Data(repeating: 9, count: 16) }
        )
        try store.setPIN("1234")
        let previous = keychain.storage[AppLockStore.credentialAccount]
        keychain.failingWrites.insert(AppLockStore.credentialAccount)

        XCTAssertThrowsError(try store.setPIN("5678"))

        XCTAssertEqual(keychain.storage[AppLockStore.credentialAccount], previous)
        XCTAssertTrue(store.verify("1234"))
        XCTAssertFalse(store.verify("5678"))
    }

    func testFailedCredentialReadBackRollsBackPreviousWorkingPIN() throws {
        let keychain = FakeAppLockKeychain()
        let (defaults, suite) = testDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = AppLockStore(
            keychain: keychain,
            defaults: defaults,
            iterations: 1,
            saltProvider: { Data(repeating: 11, count: 16) }
        )
        try store.setPIN("1234")
        let previous = keychain.storage[AppLockStore.credentialAccount]
        keychain.writtenSinceReset.removeAll()
        keychain.corruptReadAfterWrite.insert(AppLockStore.credentialAccount)

        XCTAssertThrowsError(try store.setPIN("5678"))

        keychain.corruptReadAfterWrite.removeAll()
        XCTAssertEqual(keychain.storage[AppLockStore.credentialAccount], previous)
        XCTAssertTrue(store.verify("1234"))
        XCTAssertFalse(store.verify("5678"))
    }

    func testFailedLegacyMigrationKeepsWorkingSplitCredential() throws {
        let keychain = FakeAppLockKeychain()
        let (defaults, suite) = testDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        let salt = Data(repeating: 13, count: 16)
        keychain.storage[AppLockStore.legacySaltAccount] = salt
        keychain.storage[AppLockStore.legacyHashAccount] = oneRoundHash(pin: "1234", salt: salt)
        keychain.failingWrites.insert(AppLockStore.credentialAccount)
        let store = AppLockStore(keychain: keychain, defaults: defaults, iterations: 1)

        XCTAssertThrowsError(try store.migrateIfNeeded())

        XCTAssertNil(keychain.storage[AppLockStore.credentialAccount])
        XCTAssertNotNil(keychain.storage[AppLockStore.legacySaltAccount])
        XCTAssertNotNil(keychain.storage[AppLockStore.legacyHashAccount])
        XCTAssertTrue(store.verify("1234"), "failed migration must keep the legacy PIN usable")
    }

    func testDisableRequiresCombinedAnchorBeforePartialLegacyCleanupAndCanRetry() throws {
        let keychain = FakeAppLockKeychain()
        let (defaults, suite) = testDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        let salt = Data(repeating: 23, count: 16)
        keychain.storage[AppLockStore.legacySaltAccount] = salt
        keychain.storage[AppLockStore.legacyHashAccount] = oneRoundHash(
            pin: "1234",
            salt: salt
        )
        keychain.failingWrites.insert(AppLockStore.credentialAccount)
        let store = AppLockStore(keychain: keychain, defaults: defaults, iterations: 1)

        XCTAssertThrowsError(try store.migrateIfNeeded())
        XCTAssertTrue(store.verify("1234"), "legacy fallback must remain usable")
        keychain.operations.removeAll()

        XCTAssertThrowsError(try store.disable(currentPIN: "1234"))

        XCTAssertNil(keychain.storage[AppLockStore.credentialAccount])
        XCTAssertNotNil(keychain.storage[AppLockStore.legacySaltAccount])
        XCTAssertNotNil(keychain.storage[AppLockStore.legacyHashAccount])
        XCTAssertFalse(keychain.operations.contains("delete:\(AppLockStore.legacySaltAccount)"))
        XCTAssertFalse(keychain.operations.contains("delete:\(AppLockStore.legacyHashAccount)"))
        XCTAssertTrue(store.isEnabled)
        XCTAssertTrue(store.verify("1234"))

        // Once the checked combined write can succeed, a later legacy-delete
        // failure is safe because the combined credential anchors the same PIN.
        keychain.failingWrites.remove(AppLockStore.credentialAccount)
        keychain.failingDeletes.insert(AppLockStore.legacyHashAccount)
        keychain.operations.removeAll()

        XCTAssertThrowsError(try store.disable(currentPIN: "1234"))

        XCTAssertNotNil(keychain.storage[AppLockStore.credentialAccount])
        XCTAssertNil(keychain.storage[AppLockStore.legacySaltAccount])
        XCTAssertNotNil(keychain.storage[AppLockStore.legacyHashAccount])
        XCTAssertTrue(store.isEnabled)
        XCTAssertTrue(store.verify("1234"))

        keychain.failingDeletes.remove(AppLockStore.legacyHashAccount)
        XCTAssertTrue(try store.disable(currentPIN: "1234"))
        XCTAssertFalse(store.isEnabled)
        XCTAssertFalse(store.verify("1234"))
    }

    func testDisableLegacyCleanupFailureKeepsCombinedCredentialUsable() throws {
        let keychain = FakeAppLockKeychain()
        let (defaults, suite) = testDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = AppLockStore(
            keychain: keychain,
            defaults: defaults,
            iterations: 1,
            saltProvider: { Data(repeating: 17, count: 16) }
        )
        try store.setPIN("1234")
        let combined = keychain.storage[AppLockStore.credentialAccount]
        keychain.operations.removeAll()
        keychain.failingDeletes.insert(AppLockStore.legacySaltAccount)

        XCTAssertThrowsError(try store.disable(currentPIN: "1234"))

        XCTAssertEqual(keychain.storage[AppLockStore.credentialAccount], combined)
        XCTAssertFalse(keychain.operations.contains("delete:\(AppLockStore.credentialAccount)"))
        for account in [
            AppLockStore.legacySaltAccount,
            AppLockStore.legacyHashAccount,
            AppLockStore.failureAccount,
            AppLockStore.lockUntilAccount,
        ] {
            XCTAssertTrue(keychain.operations.contains("delete:\(account)"))
        }
        XCTAssertTrue(store.verify("1234"))
    }

    func testDisableThrottleCleanupFailureKeepsCombinedCredentialUsable() throws {
        let keychain = FakeAppLockKeychain()
        let (defaults, suite) = testDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = AppLockStore(
            keychain: keychain,
            defaults: defaults,
            iterations: 1,
            saltProvider: { Data(repeating: 19, count: 16) }
        )
        try store.setPIN("1234")
        let combined = keychain.storage[AppLockStore.credentialAccount]
        keychain.operations.removeAll()
        keychain.failingDeletes.insert(AppLockStore.lockUntilAccount)

        XCTAssertThrowsError(try store.disable(currentPIN: "1234"))

        XCTAssertEqual(keychain.storage[AppLockStore.credentialAccount], combined)
        XCTAssertFalse(keychain.operations.contains("delete:\(AppLockStore.credentialAccount)"))
        XCTAssertTrue(store.verify("1234"))
    }

    func testValidCombinedCredentialRetriesFailedLegacyCleanupWithoutRewrite() throws {
        let keychain = FakeAppLockKeychain()
        let (defaults, suite) = testDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        let salt = Data(repeating: 21, count: 16)
        let storedHash = oneRoundHash(pin: "1234", salt: salt)
        keychain.storage[AppLockStore.legacySaltAccount] = salt
        keychain.storage[AppLockStore.legacyHashAccount] = storedHash
        defaults.set(salt, forKey: AppLockStore.legacyDefaultsSaltKey)
        defaults.set(storedHash, forKey: AppLockStore.legacyDefaultsHashKey)
        keychain.failingDeletes.insert(AppLockStore.legacyHashAccount)
        let store = AppLockStore(keychain: keychain, defaults: defaults, iterations: 1)

        XCTAssertThrowsError(try store.migrateIfNeeded())

        let combined = try XCTUnwrap(keychain.storage[AppLockStore.credentialAccount])
        XCTAssertNil(keychain.storage[AppLockStore.legacySaltAccount])
        XCTAssertNotNil(keychain.storage[AppLockStore.legacyHashAccount])
        XCTAssertNotNil(defaults.data(forKey: AppLockStore.legacyDefaultsSaltKey))
        let credentialWritesBeforeRetry = keychain.operations.filter {
            $0 == "write:\(AppLockStore.credentialAccount)"
        }.count
        let credentialWrite = try XCTUnwrap(
            keychain.operations.firstIndex(of: "write:\(AppLockStore.credentialAccount)")
        )
        let firstLegacyDelete = try XCTUnwrap(
            keychain.operations.firstIndex(of: "delete:\(AppLockStore.legacySaltAccount)")
        )
        XCTAssertTrue(keychain.operations.indices.contains { index in
            index > credentialWrite
                && index < firstLegacyDelete
                && keychain.operations[index] == "read:\(AppLockStore.credentialAccount)"
        })

        keychain.failingDeletes.remove(AppLockStore.legacyHashAccount)
        try store.migrateIfNeeded()

        XCTAssertEqual(keychain.storage[AppLockStore.credentialAccount], combined)
        XCTAssertNil(keychain.storage[AppLockStore.legacyHashAccount])
        XCTAssertNil(defaults.data(forKey: AppLockStore.legacyDefaultsSaltKey))
        XCTAssertNil(defaults.data(forKey: AppLockStore.legacyDefaultsHashKey))
        XCTAssertEqual(keychain.operations.filter {
            $0 == "write:\(AppLockStore.credentialAccount)"
        }.count, credentialWritesBeforeRetry, "cleanup retry must not rewrite a valid credential")
        XCTAssertTrue(store.verify("1234"))
    }

    func testSuccessfulLegacyMigrationVerifiesBeforeDeletingSplitCredential() throws {
        let keychain = FakeAppLockKeychain()
        let (defaults, suite) = testDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        let salt = Data(repeating: 15, count: 16)
        keychain.storage[AppLockStore.legacySaltAccount] = salt
        keychain.storage[AppLockStore.legacyHashAccount] = oneRoundHash(pin: "1234", salt: salt)
        let store = AppLockStore(keychain: keychain, defaults: defaults, iterations: 1)

        try store.migrateIfNeeded()

        XCTAssertNotNil(keychain.storage[AppLockStore.credentialAccount])
        XCTAssertNil(keychain.storage[AppLockStore.legacySaltAccount])
        XCTAssertNil(keychain.storage[AppLockStore.legacyHashAccount])
        XCTAssertTrue(store.verify("1234"))
        let credentialWrite = try XCTUnwrap(
            keychain.operations.firstIndex(of: "write:\(AppLockStore.credentialAccount)")
        )
        let firstLegacyDelete = try XCTUnwrap(
            keychain.operations.firstIndex(of: "delete:\(AppLockStore.legacySaltAccount)")
        )
        XCTAssertTrue(keychain.operations.indices.contains { index in
            index > credentialWrite
                && index < firstLegacyDelete
                && keychain.operations[index] == "read:\(AppLockStore.credentialAccount)"
        }, "the combined credential must be read back before legacy deletion")
    }

    func testInvalidCombinedCredentialFailsClosed() {
        let keychain = FakeAppLockKeychain()
        let (defaults, suite) = testDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        keychain.storage[AppLockStore.credentialAccount] = Data("not-a-credential".utf8)
        let store = AppLockStore(keychain: keychain, defaults: defaults, iterations: 1)

        XCTAssertTrue(store.isEnabled, "corrupt credential data must not silently disable the lock")
        XCTAssertFalse(store.verify("1234"))
    }

    func testLiveLocationPolicyRequestsFirstPromptOnInitialAppearance() {
        XCTAssertFalse(LiveLocationPermissionPolicy.shouldStartUpdates(for: .notDetermined))
        XCTAssertTrue(LiveLocationPermissionPolicy.shouldRequestOnInitialAppearance(for: .notDetermined))
        XCTAssertFalse(LiveLocationPermissionPolicy.shouldRequestOnInitialAppearance(for: .denied))
        XCTAssertFalse(LiveLocationPermissionPolicy.shouldRequestOnInitialAppearance(for: .authorizedWhenInUse))
        let control = LiveLocationPermissionPolicy.control(for: .notDetermined)
        XCTAssertEqual(control.action, .requestPermission)
        XCTAssertEqual(control.title, "Enable Live Location")
        XCTAssertTrue(control.guidance.contains("first launch"))
    }

    func testLiveLocationPolicyStartsOnlyWhenAuthorisedAndGuidesUnavailableStates() {
        for status in [CLAuthorizationStatus.authorizedWhenInUse, .authorizedAlways] {
            XCTAssertTrue(LiveLocationPermissionPolicy.shouldStartUpdates(for: status))
            XCTAssertEqual(LiveLocationPermissionPolicy.control(for: status).action,
                           .centreOnLocation)
        }
        for status in [CLAuthorizationStatus.denied, .restricted] {
            XCTAssertFalse(LiveLocationPermissionPolicy.shouldStartUpdates(for: status))
            let control = LiveLocationPermissionPolicy.control(for: status)
            XCTAssertEqual(control.action, .openSettings)
            XCTAssertTrue(control.guidance.localizedCaseInsensitiveContains("settings"))
        }
    }

    func testBackgroundLocationOwnersCannotDisableEachOther() {
        let syncOnly = BackgroundLocationPolicy.resolve([.unitSync])
        XCTAssertTrue(syncOnly.enabled)
        XCTAssertEqual(syncOnly.desiredAccuracy, kCLLocationAccuracyHundredMeters)
        XCTAssertEqual(syncOnly.distanceFilter, 50)

        let both = BackgroundLocationPolicy.resolve([.trackRecording, .unitSync])
        XCTAssertTrue(both.enabled)
        XCTAssertEqual(both.desiredAccuracy, kCLLocationAccuracyBest)
        XCTAssertEqual(both.distanceFilter, kCLDistanceFilterNone)

        let recordingAfterSyncStops = BackgroundLocationPolicy.resolve([.trackRecording])
        XCTAssertTrue(recordingAfterSyncStops.enabled)
        XCTAssertEqual(recordingAfterSyncStops.desiredAccuracy, kCLLocationAccuracyBest)

        XCTAssertFalse(BackgroundLocationPolicy.resolve([]).enabled)
    }
}
