import XCTest
@testable import TacticalMaps

final class SafeStoreTests: XCTestCase {

    private func tempDir() -> URL {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    func testWriteThenReadRoundTrips() throws {
        let url = tempDir().appendingPathComponent("d.json")
        try SafeStore.write(Data("hello world".utf8), to: url)
        let result = SafeStore.read(url) { String(decoding: $0, as: UTF8.self) }
        guard case .loaded(let s) = result else { return XCTFail("expected loaded") }
        XCTAssertEqual(s, "hello world")
    }

    func testMissingFileIsEmptyNotCorrupt() {
        let url = tempDir().appendingPathComponent("absent.json")
        if case .empty = SafeStore.read(url, decode: { $0 }) { } else {
            XCTFail("a missing file must be .empty (fresh install), not .corrupt")
        }
    }

    func testCorruptFileIsQuarantinedAndPreserved_notOverwritten() throws {
        let dir = tempDir()
        let url = dir.appendingPathComponent("d.json")
        try Data("{ not valid json".utf8).write(to: url)
        struct Boom: Error {}
        let result = SafeStore.read(url) { _ -> Int in throw Boom() }
        guard case .corrupt(let quarantine, _) = result else {
            return XCTFail("a present-but-unreadable file must be .corrupt")
        }
        let q = try XCTUnwrap(quarantine, "the original bytes must be preserved aside")
        XCTAssertTrue(FileManager.default.fileExists(atPath: q.path))
        XCTAssertEqual(try String(contentsOf: q, encoding: .utf8), "{ not valid json")
        // Primary path is freed so a fresh seed doesn't clobber the recovery copy.
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path))
    }
}
