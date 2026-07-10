import XCTest
@testable import TacticalMaps

final class SafeStoreTests: XCTestCase {

    private let testKey = Data((0..<32).map { UInt8($0) })
    private let label = "waypoints.json"

    override func setUp() {
        super.setUp()
        // Real key lives in the Keychain, which needs entitlements the test host
        // doesn't always have. Swap in a fixed one; the envelope code is the same.
        SafeStore.keyProvider = { [testKey] in testKey }
    }

    override func tearDown() {
        SafeStore.keyProvider = { try DataKey.key() }
        super.tearDown()
    }

    private func tempDir() -> URL {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    func testWriteThenReadRoundTrips() throws {
        let url = tempDir().appendingPathComponent("d.json")
        try SafeStore.write(Data("hello world".utf8), to: url, label: label)
        let result = SafeStore.read(url, label: label) { String(decoding: $0, as: UTF8.self) }
        guard case .loaded(let s) = result else { return XCTFail("expected loaded") }
        XCTAssertEqual(s, "hello world")
    }

    func testBytesOnDiskAreCiphertextNotPlaintext() throws {
        let url = tempDir().appendingPathComponent("d.json")
        try SafeStore.write(Data(#"[{"callsign":"ZERO","grid":"30UXC1234567890"}]"#.utf8), to: url, label: label)
        let onDisk = try Data(contentsOf: url)
        XCTAssertTrue(SealedEnvelope.isSealedFile(onDisk), "sealed files carry the magic")
        let text = String(decoding: onDisk, as: UTF8.self)
        XCTAssertFalse(text.contains("ZERO"), "callsign must not survive in the clear")
        XCTAssertFalse(text.contains("30UXC"), "grid must not survive in the clear")
    }

    func testMissingFileIsEmptyNotCorrupt() {
        let url = tempDir().appendingPathComponent("absent.json")
        if case .empty = SafeStore.read(url, label: label, decode: { $0 }) { } else {
            XCTFail("a missing file must be .empty (fresh install), not .corrupt")
        }
    }

    func testCorruptFileIsQuarantinedAndPreserved_notOverwritten() throws {
        let dir = tempDir()
        let url = dir.appendingPathComponent("d.json")
        try Data("{ not valid json".utf8).write(to: url)
        struct Boom: Error {}
        let result = SafeStore.read(url, label: label) { _ -> Int in throw Boom() }
        guard case .corrupt(let quarantine, _) = result else {
            return XCTFail("a present-but-unreadable file must be .corrupt")
        }
        let q = try XCTUnwrap(quarantine, "the original bytes must be preserved aside")
        XCTAssertTrue(FileManager.default.fileExists(atPath: q.path))
        XCTAssertEqual(try String(contentsOf: q, encoding: .utf8), "{ not valid json")
        // Primary path is freed so a fresh seed doesn't clobber the recovery copy.
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path))
    }

    func testTamperedSealedFileIsQuarantinedNotSilentlyEmpty() throws {
        let dir = tempDir()
        let url = dir.appendingPathComponent("d.json")
        try SafeStore.write(Data(#"{"ok":true}"#.utf8), to: url, label: label)
        var bytes = try Data(contentsOf: url)
        bytes[bytes.count - 1] ^= 0x01
        try bytes.write(to: url)

        let result = SafeStore.read(url, label: label) { $0 }
        guard case .corrupt(let quarantine, _) = result else {
            return XCTFail("a failed tag check is corruption, not emptiness")
        }
        XCTAssertNotNil(quarantine)
    }

    func testLegacyPlaintextFileIsReadAndSealedInPlace() throws {
        let dir = tempDir()
        let url = dir.appendingPathComponent("d.json")
        try Data(#"{"legacy":true}"#.utf8).write(to: url) // pre-encryption build

        let result = SafeStore.read(url, label: label) { String(decoding: $0, as: UTF8.self) }
        guard case .loaded(let s) = result else { return XCTFail("expected loaded") }
        XCTAssertEqual(s, #"{"legacy":true}"#)

        // Migrated in place: same path, now ciphertext, no orphaned plaintext.
        let onDisk = try Data(contentsOf: url)
        XCTAssertTrue(SealedEnvelope.isSealedFile(onDisk))
        XCTAssertFalse(String(decoding: onDisk, as: UTF8.self).contains("legacy"))

        // and it still reads back
        let again = SafeStore.read(url, label: label) { String(decoding: $0, as: UTF8.self) }
        guard case .loaded(let s2) = again else { return XCTFail("expected loaded") }
        XCTAssertEqual(s2, #"{"legacy":true}"#)
    }

    func testLockedKeyLeavesTheFileAloneAndDoesNotQuarantine() throws {
        let dir = tempDir()
        let url = dir.appendingPathComponent("d.json")
        try SafeStore.write(Data(#"{"mission":"real"}"#.utf8), to: url, label: label)
        let before = try Data(contentsOf: url)

        SafeStore.keyProvider = { throw DataKey.LockedError() }
        let result = SafeStore.read(url, label: label) { $0 }

        guard case .locked = result else { return XCTFail("locked is not corrupt") }
        XCTAssertTrue(FileManager.default.fileExists(atPath: url.path), "file must still be there")
        XCTAssertEqual(try Data(contentsOf: url), before, "bytes untouched")
        let siblings = try FileManager.default.contentsOfDirectory(atPath: dir.path)
        XCTAssertFalse(siblings.contains { $0.contains(".corrupt-") }, "nothing quarantined")
    }

    func testBlobFromAnotherStoreIsQuarantinedNotLoaded() throws {
        let dir = tempDir()
        let url = dir.appendingPathComponent("waypoints.json")
        // Seal under drawings.json, then try to read it as waypoints.json.
        let alien = try SealedEnvelope.sealFile(key: testKey,
                                                plaintext: Data(#"{"shapes":[]}"#.utf8),
                                                label: "drawings.json")
        try alien.write(to: url)
        let result = SafeStore.read(url, label: "waypoints.json") { $0 }
        guard case .corrupt = result else { return XCTFail("cross-store blob must not load") }
    }
}
