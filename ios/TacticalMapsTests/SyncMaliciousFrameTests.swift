import XCTest
import Foundation
@testable import TacticalMaps

/// SEC-006 regression: every entry in testdata/malicious_frames.json is a raw
/// WebSocket text frame that must be silently dropped without mutating state.
/// Same corpus as Android SyncMaliciousFrameTest.
///
/// Because SyncManager is @MainActor and needs injected stores, we test the
/// parsing invariants directly: strict version parsing, JSON survival, and the
/// guarantee that no corpus case produces a usable version from a bad value.
final class SyncMaliciousFrameTests: XCTestCase {

    private struct Case {
        let name: String
        let frame: String
    }

    private func loadCorpus() throws -> [Case] {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        for _ in 0..<8 {
            let candidate = dir.appendingPathComponent("testdata")
                .appendingPathComponent("malicious_frames.json")
            if FileManager.default.fileExists(atPath: candidate.path) {
                let data = try Data(contentsOf: candidate)
                let root = try JSONSerialization.jsonObject(with: data) as! [String: Any]
                let cases = root["cases"] as! [[String: Any]]
                return cases.map { Case(name: $0["name"] as! String,
                                        frame: $0["frame"] as! String) }
            }
            dir = dir.deletingLastPathComponent()
        }
        XCTFail("Could not locate testdata/malicious_frames.json")
        return []
    }

    private static let maxVersion: Int64 = 1_000_000_000_000

    func testNoFrameThrowsOnJsonParse() throws {
        let corpus = try loadCorpus()
        for c in corpus {
            // must not crash — either parses or doesn't
            let _ = try? JSONSerialization.jsonObject(
                with: Data(c.frame.utf8)) as? [String: Any]
        }
    }

    func testVersionParsingRejectsAllMaliciousValues() throws {
        let corpus = try loadCorpus()
        let versionCases = corpus.filter { $0.name.hasPrefix("version_") }
        XCTAssertFalse(versionCases.isEmpty, "should have version test cases")

        for c in versionCases {
            guard let obj = try? JSONSerialization.jsonObject(
                with: Data(c.frame.utf8)) as? [String: Any] else { continue }
            let v = SyncManager.strictJSONInteger(obj["v"], minimum: 0, maximum: Self.maxVersion)
            XCTAssertNil(v, "\(c.name): malicious version should be rejected, got \(String(describing: v))")
        }
    }

    func testProductionParserRejectsIntegralFloatingPointEncodings() throws {
        for frame in [
            #"{"t":"put","v":1.0}"#,
            #"{"t":"put","v":999999999999.000001}"#
        ] {
            let object = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(frame.utf8)) as? [String: Any])
            XCTAssertNil(SyncManager.strictJSONInteger(
                object["v"], minimum: 0, maximum: Self.maxVersion), frame)
        }
    }

    func testFrameCeilingRejectsOversized() {
        let huge = String(repeating: "A", count: SyncInboundFramePolicy.maxFrameBytes + 1)
        XCTAssertEqual(SyncInboundFramePolicy.inspect(text: huge), .reject(.oversized))
    }

    func testUnicodeCeilingUsesUTF8WireBytesRatherThanCharacterCount() {
        let emoji = String(
            repeating: "😀",
            count: SyncInboundFramePolicy.maxFrameBytes / 4 + 1
        )
        XCTAssertLessThan(emoji.count, SyncInboundFramePolicy.maxFrameBytes)
        XCTAssertGreaterThan(emoji.utf8.count, SyncInboundFramePolicy.maxFrameBytes)
        XCTAssertEqual(SyncInboundFramePolicy.inspect(text: emoji), .reject(.oversized))
    }

    func testBinaryIsBoundedBeforeDecodeAndInvalidUTF8Closes() {
        let oversized = Data(repeating: 0xff, count: SyncInboundFramePolicy.maxFrameBytes + 1)
        XCTAssertEqual(SyncInboundFramePolicy.inspect(data: oversized), .reject(.oversized))
        XCTAssertEqual(
            SyncInboundFramePolicy.inspect(data: Data([0xc3, 0x28])),
            .reject(.invalidUTF8)
        )
        let valid = Data(#"{"t":"hello"}"#.utf8)
        XCTAssertEqual(
            SyncInboundFramePolicy.inspect(data: valid),
            .accept(text: #"{"t":"hello"}"#, data: valid)
        )
    }

    func testHostileFrameClaimsOnlyOneClosePerSocketGeneration() {
        let gate = SyncInboundFrameCloseGate()
        XCTAssertTrue(gate.claimClose(generation: 41))
        XCTAssertFalse(gate.claimClose(generation: 41))
        XCTAssertTrue(gate.claimClose(generation: 42))
    }

    // These two used to pin a flat 200 frames / 4 MiB live budget, the same as
    // the relay's per-sender allowance, so one member's import closed every
    // peer (S2-04, S4-02). Contract section 12 scales the room budget with
    // the number of sessions and counts our own acks separately.
    func testLiveReceiveBudgetScalesWithSessionsAndResetsByWindow() {
        let budget = SyncReceiveBudget()
        let limit = SyncReceiveBudget.roomLimits(sessions: 0).frames
        XCTAssertEqual(limit, 600)
        for _ in 0..<limit {
            XCTAssertTrue(budget.admit(generation: 7, phase: .live, frameType: "loc", byteCount: 1, activeSessions: 0, nowMs: 100))
        }
        XCTAssertFalse(budget.admit(generation: 7, phase: .live, frameType: "loc", byteCount: 1, activeSessions: 0, nowMs: 100))
        XCTAssertTrue(budget.admit(generation: 7, phase: .live, frameType: "op-ack", byteCount: 1, activeSessions: 0, nowMs: 100),
                      "our own acks have their own budget")
        XCTAssertTrue(budget.admit(generation: 7, phase: .live, frameType: "loc", byteCount: 1, activeSessions: 0, nowMs: 10_100))
        XCTAssertTrue(budget.admit(generation: 8, phase: .live, frameType: "loc", byteCount: 1, activeSessions: 0, nowMs: 10_100))
    }

    func testInitialReceiveBudgetCountsMalformedFramesThenResetsForLivePhase() {
        let budget = SyncReceiveBudget()
        XCTAssertTrue(budget.admit(generation: 9, phase: .initial, frameType: nil,
                                   byteCount: SyncReceiveBudget.initialMaxBytes, activeSessions: 0, nowMs: 1))
        XCTAssertFalse(budget.admit(generation: 9, phase: .initial, frameType: nil, byteCount: 1, activeSessions: 0, nowMs: 2))
        XCTAssertTrue(budget.admit(generation: 9, phase: .live, frameType: nil, byteCount: 1, activeSessions: 0, nowMs: 2))
    }

    func testVeryLargeAsciiIsRejectedBeforeDataDuplicationContract() {
        let huge = String(repeating: "A", count: SyncInboundFramePolicy.maxFrameBytes * 4)
        XCTAssertEqual(SyncInboundFramePolicy.inspect(text: huge), .reject(.oversized))
    }
}
