import XCTest
@testable import TacticalMaps

final class TrackRecorderSecurityTests: XCTestCase {
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
}
