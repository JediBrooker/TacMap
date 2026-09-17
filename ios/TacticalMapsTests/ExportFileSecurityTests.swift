import XCTest
@testable import TacticalMaps

final class ExportFileSecurityTests: XCTestCase {
    private var scratch: URL!

    override func setUpWithError() throws {
        try super.setUpWithError()
        scratch = FileManager.default.temporaryDirectory
            .appendingPathComponent("ExportFileSecurityTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: scratch, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        if let scratch {
            try? FileManager.default.removeItem(at: scratch)
        }
        scratch = nil
        try super.tearDownWithError()
    }

    func testColdLaunchPurgeDoesNotCreateAbsentDirectory() {
        let absent = scratch.appendingPathComponent("missing", isDirectory: true)

        XCTAssertTrue(ExportFileSecurity.purgeStaleArtifactsOnLaunch(in: absent))
        XCTAssertFalse(FileManager.default.fileExists(atPath: absent.path))
    }

    func testColdLaunchPurgeRemovesStaleDirectArtifact() throws {
        let artifact = try makeFile(named: "stale.geojson")
        let now = Date(timeIntervalSince1970: 10_000)
        try FileManager.default.setAttributes(
            [.modificationDate: now.addingTimeInterval(-3_601)],
            ofItemAtPath: artifact.path
        )

        XCTAssertTrue(ExportFileSecurity.purgeStaleArtifactsOnLaunch(in: scratch, now: now))
        XCTAssertFalse(FileManager.default.fileExists(atPath: artifact.path))
    }

    func testColdLaunchPurgePreservesFreshArtifact() throws {
        let artifact = try makeFile(named: "fresh.gpx")
        let now = Date(timeIntervalSince1970: 10_000)
        try FileManager.default.setAttributes(
            [.modificationDate: now.addingTimeInterval(-60)],
            ofItemAtPath: artifact.path
        )

        XCTAssertTrue(ExportFileSecurity.purgeStaleArtifactsOnLaunch(in: scratch, now: now))
        XCTAssertTrue(FileManager.default.fileExists(atPath: artifact.path))
    }

    func testColdLaunchPurgeUnlinksChildSymlinkWithoutTouchingTarget() throws {
        let outside = FileManager.default.temporaryDirectory
            .appendingPathComponent("ExportFileSecurityOutside-\(UUID().uuidString)")
        try Data("sensitive".utf8).write(to: outside, options: .atomic)
        defer { try? FileManager.default.removeItem(at: outside) }
        let link = scratch.appendingPathComponent("linked.geojson")
        try FileManager.default.createSymbolicLink(at: link, withDestinationURL: outside)

        XCTAssertTrue(ExportFileSecurity.purgeStaleArtifactsOnLaunch(in: scratch))
        XCTAssertFalse(FileManager.default.fileExists(atPath: link.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: outside.path))
    }

    func testColdLaunchPurgeRefusesSymlinkedRoot() throws {
        let outside = scratch.appendingPathComponent("outside", isDirectory: true)
        try FileManager.default.createDirectory(at: outside, withIntermediateDirectories: true)
        let target = try makeFile(named: "target.geojson", in: outside)
        let linkedRoot = scratch.appendingPathComponent("linked-root", isDirectory: true)
        try FileManager.default.createSymbolicLink(at: linkedRoot, withDestinationURL: outside)

        XCTAssertFalse(ExportFileSecurity.purgeStaleArtifactsOnLaunch(in: linkedRoot))
        XCTAssertTrue(FileManager.default.fileExists(atPath: target.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: linkedRoot.path))
    }

    private func makeFile(named name: String, in directory: URL? = nil) throws -> URL {
        let url = (directory ?? scratch).appendingPathComponent(name)
        try Data("sensitive".utf8).write(to: url, options: .atomic)
        return url
    }
}
