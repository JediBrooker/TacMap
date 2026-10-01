import XCTest
@testable import TacticalMaps

/// S3-04 / S4-06 (fence table never pruned) and S4-07 (history only pruned by
/// count) against the real sealed chat store.
@MainActor
final class TacMapChatRetentionTests: XCTestCase {
    private let testKey = Data((0..<32).map { UInt8(truncatingIfNeeded: $0 &+ 9) })
    private var directory: URL!
    private var roomId: String!

    override func setUp() {
        super.setUp()
        SafeStore.keyProvider = { [testKey] in testKey }
        SealedMigrationPolicy.resetForTests(key: testKey)
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("TacMapChatRetentionTests-\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        roomId = canonical(0x21, 0)
    }

    override func tearDown() {
        if let directory { try? FileManager.default.removeItem(at: directory) }
        SafeStore.keyProvider = { try DataKey.key() }
        SealedMigrationPolicy.resetForTests(key: testKey)
        super.tearDown()
    }

    private func canonical(_ byte: UInt8, _ index: Int) -> String {
        var raw = Data(repeating: byte, count: 32)
        raw[0] = UInt8(truncatingIfNeeded: index)
        raw[1] = UInt8(truncatingIfNeeded: index >> 8)
        return SyncIdentity.urlB64Encode(raw)
    }

    private func inbound(sender: String, body: String) throws -> TacMapChatMessage {
        TacMapChatMessage(
            id: try TacMapChatMessage.makeMessageID(),
            roomId: roomId, scope: .room, senderActorId: sender, senderName: "Remote",
            recipientActorId: nil, recipientName: nil, kind: .text, body: body,
            sentAtMilliseconds: 1_790_000_000_000, isOutgoing: false,
            deliveryState: .received, failureCode: nil)
    }

    func testSupersededFencesArePrunedSoANewSessionStillGetsThrough() throws {
        let store = TacMapChatStore(containerURL: directory)
        try store.open(roomId: roomId)
        let actor = canonical(0x31, 0)
        var current = canonical(0x41, 0)
        store.durableSessionDomain = { $0 == actor ? current : nil }
        // one actor reconnecting 300 times, each session sends one message
        for session in 0..<300 {
            current = canonical(0x41, session)
            let result = try store.acceptInbound(
                try inbound(sender: actor, body: "msg \(session)"),
                actorId: actor, sessionDomain: current, keyId: canonical(0x51, session),
                counter: 1, fingerprint: canonical(0x61, session))
            XCTAssertEqual(result, .accepted, "session \(session) must still be accepted")
        }
    }

    func testFullTableOfLiveSessionsIsReportedAsReplayFull() throws {
        let store = TacMapChatStore(containerURL: directory)
        try store.open(roomId: roomId)
        store.durableSessionDomain = { _ in nil }
        for index in 0..<256 {
            let actor = canonical(0x71, index)
            XCTAssertEqual(try store.acceptInbound(
                try inbound(sender: actor, body: "hi"), actorId: actor,
                sessionDomain: canonical(0x72, index), keyId: canonical(0x73, index),
                counter: 1, fingerprint: canonical(0x74, index)), .accepted)
        }
        let extra = canonical(0x75, 0)
        XCTAssertEqual(try store.acceptInbound(
            try inbound(sender: extra, body: "one too many"), actorId: extra,
            sessionDomain: canonical(0x76, 0), keyId: canonical(0x77, 0),
            counter: 1, fingerprint: canonical(0x78, 0)), .rejectedReplayFull)
    }

    func testHistoryIsPrunedByEncodedBytesInsteadOfFailingForGood() throws {
        let store = TacMapChatStore(containerURL: directory)
        try store.open(roomId: roomId)
        let actor = canonical(0x31, 1)
        let session = canonical(0x41, 1)
        let key = canonical(0x51, 1)
        // 4,000 quotes escape to ~8 KB each, so ~260 of these used to brick chat
        let body = String(repeating: "\"", count: 4_000)
        for index in 0..<300 {
            let result = try store.acceptInbound(
                try inbound(sender: actor, body: body), actorId: actor, sessionDomain: session,
                keyId: key, counter: Int64(index + 1), fingerprint: canonical(0x61, index))
            XCTAssertEqual(result, .accepted, "message \(index)")
        }
        XCTAssertLessThan(store.messages.count, 300)
        XCTAssertGreaterThan(store.messages.count, 100)
        let reopened = TacMapChatStore(containerURL: directory)
        try reopened.open(roomId: roomId)
        XCTAssertEqual(reopened.messages.count, store.messages.count)
        XCTAssertEqual(reopened.messages.last?.body, body)
    }
}
