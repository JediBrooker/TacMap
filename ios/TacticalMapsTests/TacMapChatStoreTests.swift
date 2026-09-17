import XCTest
import SwiftUI
@testable import TacticalMaps

@MainActor
final class TacMapChatStoreTests: XCTestCase {
    private let testKey = Data((0..<32).map(UInt8.init))
    private var directory: URL!
    private var roomId: String!

    override func setUp() {
        super.setUp()
        SafeStore.keyProvider = { [testKey] in testKey }
        SealedMigrationPolicy.resetForTests(key: testKey)
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("TacMapChatStoreTests-\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        roomId = canonical32(0x21)
    }

    override func tearDown() {
        if let directory { try? FileManager.default.removeItem(at: directory) }
        SafeStore.keyProvider = { try DataKey.key() }
        SealedMigrationPolicy.resetForTests(key: testKey)
        super.tearDown()
    }

    func testHistoryIsSealedAndRoundTrips() throws {
        let store = TacMapChatStore(containerURL: directory)
        try store.open(roomId: roomId)
        let outgoing = message(
            idByte: 1,
            sender: canonical32(1),
            outgoing: true,
            body: "CHECKPOINT AMBER"
        )
        XCTAssertTrue(try store.appendOutgoing(outgoing))

        let file = historyURL()
        let onDisk = try Data(contentsOf: file)
        XCTAssertTrue(SealedEnvelope.isSealedFile(onDisk))
        XCTAssertFalse(String(decoding: onDisk, as: UTF8.self).contains("CHECKPOINT AMBER"))

        let reopened = TacMapChatStore(containerURL: directory)
        try reopened.open(roomId: roomId)
        let recovered = try XCTUnwrap(reopened.messages.first)
        XCTAssertEqual(recovered.id, outgoing.id)
        XCTAssertEqual(recovered.body, outgoing.body)
        XCTAssertEqual(recovered.deliveryState, .failed)
        XCTAssertEqual(recovered.failureCode, "session-ended")

        let reopenedAgain = TacMapChatStore(containerURL: directory)
        try reopenedAgain.open(roomId: roomId)
        XCTAssertEqual(reopenedAgain.messages, reopened.messages,
                       "crash normalization must itself be durable and idempotent")
    }

    func testLegacyRoomIdFilenameMigratesWithoutDataLossOrIdentifierLeak() throws {
        let original = TacMapChatStore(containerURL: directory)
        try original.open(roomId: roomId)
        let outgoing = message(
            idByte: 0x51,
            sender: canonical32(0x52),
            outgoing: true,
            body: "LEGACY CHAT HISTORY"
        )
        XCTAssertTrue(try original.appendOutgoing(outgoing))

        let current = historyURL()
        let legacy = legacyHistoryURL()
        try FileManager.default.moveItem(at: current, to: legacy)
        let legacyQuarantine = legacy.appendingPathExtension("corrupt-123")
        let quarantinedBytes = Data("preserve-quarantine".utf8)
        try quarantinedBytes.write(to: legacyQuarantine)
        XCTAssertFalse(FileManager.default.fileExists(atPath: current.path))

        let migrated = TacMapChatStore(containerURL: directory)
        try migrated.open(roomId: roomId)
        XCTAssertEqual(migrated.messages.first?.body, outgoing.body)
        XCTAssertTrue(FileManager.default.fileExists(atPath: current.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: legacy.path))
        XCTAssertFalse(current.lastPathComponent.contains(roomId))
        let currentQuarantine = current.appendingPathExtension("corrupt-123")
        XCTAssertEqual(try Data(contentsOf: currentQuarantine), quarantinedBytes)
        XCTAssertFalse(FileManager.default.fileExists(atPath: legacyQuarantine.path))
    }

    func testLockedNamingKeyLeavesLegacyHistoryUntouched() throws {
        enum Expected: Error { case unavailable }

        let original = TacMapChatStore(containerURL: directory)
        try original.open(roomId: roomId)
        let outgoing = message(
            idByte: 0x53,
            sender: canonical32(0x54),
            outgoing: true,
            body: "PRESERVE ON LOCK"
        )
        XCTAssertTrue(try original.appendOutgoing(outgoing))
        let current = historyURL()
        let legacy = legacyHistoryURL()
        try FileManager.default.moveItem(at: current, to: legacy)
        let before = try Data(contentsOf: legacy)

        SafeStore.keyProvider = { throw Expected.unavailable }
        let locked = TacMapChatStore(containerURL: directory)
        XCTAssertThrowsError(try locked.open(roomId: roomId)) { error in
            XCTAssertEqual(error as? TacMapChatStore.StoreError, .locked)
        }
        XCTAssertEqual(try Data(contentsOf: legacy), before)
        XCTAssertFalse(FileManager.default.fileExists(atPath: current.path))
        SafeStore.keyProvider = { [testKey] in testKey }
    }

    func testInactiveMigrationFailureDoesNotBlockRequestedRoom() throws {
        let activeRoom = canonical32(0x71)
        let inactiveRoom = canonical32(0x72)
        let chatDirectory = directory.appendingPathComponent("tacmap_chat", isDirectory: true)
        try FileManager.default.createDirectory(
            at: chatDirectory,
            withIntermediateDirectories: true
        )
        let external = FileManager.default.temporaryDirectory
            .appendingPathComponent("inactive-chat-symlink-\(UUID().uuidString).json")
        try Data("outside".utf8).write(to: external)
        defer { try? FileManager.default.removeItem(at: external) }
        try FileManager.default.createSymbolicLink(
            at: chatDirectory.appendingPathComponent("\(inactiveRoom).json"),
            withDestinationURL: external
        )

        let store = TacMapChatStore(containerURL: directory)
        XCTAssertNoThrow(try store.open(roomId: activeRoom))
        XCTAssertThrowsError(try SyncLocalStore.migrateLegacyFiles(
            directory: chatDirectory,
            domain: .chat,
            dataKey: testKey
        ))
        XCTAssertEqual(try Data(contentsOf: external), Data("outside".utf8))
    }

    func testCurrentFileWinsWhileDistinctLegacyCopyIsPreservedOpaque() throws {
        let original = TacMapChatStore(containerURL: directory)
        try original.open(roomId: roomId)
        XCTAssertTrue(try original.appendOutgoing(message(
            idByte: 0x55,
            sender: canonical32(0x56),
            outgoing: true,
            body: "CURRENT HISTORY"
        )))
        let current = historyURL()
        let legacy = legacyHistoryURL()
        let legacyBytes = try Data(contentsOf: current)
        try legacyBytes.write(to: legacy)

        let reopened = TacMapChatStore(containerURL: directory)
        try reopened.open(roomId: roomId)
        XCTAssertTrue(FileManager.default.fileExists(atPath: current.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: legacy.path))
        let names = try FileManager.default.contentsOfDirectory(atPath: current.deletingLastPathComponent().path)
        let conflict = try XCTUnwrap(names.first(where: { $0.contains(".legacy-conflict-") }))
        XCTAssertFalse(conflict.contains(roomId))
        XCTAssertEqual(
            try Data(contentsOf: current.deletingLastPathComponent().appendingPathComponent(conflict)),
            legacyBytes
        )
    }

    func testInboundMessageAndReplayFenceCommitAtomically() throws {
        let actor = canonical32(2)
        let session = canonical32(3)
        let keyId = canonical32(4)
        let fingerprint = canonical32(5)
        let store = TacMapChatStore(containerURL: directory)
        try store.open(roomId: roomId)
        let incoming = message(idByte: 2, sender: actor, outgoing: false, body: "SITREP GREEN")

        XCTAssertEqual(
            try store.acceptInbound(
                incoming,
                actorId: actor,
                sessionDomain: session,
                keyId: keyId,
                counter: 1,
                fingerprint: fingerprint
            ),
            .accepted
        )
        XCTAssertEqual(store.unreadMessageCount, 1)
        XCTAssertEqual(store.unreadCount(scope: .room, directActorId: nil), 1)

        let decoded = SafeStore.read(historyURL(), label: "sync/chat/\(roomId!)") { data in
            try JSONSerialization.jsonObject(with: data) as! [String: Any]
        }
        guard case .loaded(let document) = decoded else {
            return XCTFail("Expected one authenticated sealed document")
        }
        XCTAssertEqual(document["version"] as? Int, 2)
        XCTAssertEqual((document["messages"] as? [Any])?.count, 1)
        XCTAssertEqual((document["replay"] as? [String: Any])?.count, 1)
        XCTAssertEqual((document["unreadMessageIds"] as? [String])?.count, 1)

        let reopened = TacMapChatStore(containerURL: directory)
        try reopened.open(roomId: roomId)
        XCTAssertEqual(reopened.unreadMessageCount, 1)
        XCTAssertEqual(
            try reopened.acceptInbound(
                incoming,
                actorId: actor,
                sessionDomain: session,
                keyId: keyId,
                counter: 1,
                fingerprint: fingerprint
            ),
            .duplicate,
            "the replay fence must survive in the same sealed commit as history"
        )
        XCTAssertEqual(reopened.unreadMessageCount, 1,
                       "an idempotent relay retry must not create another unread entry")

        let conflict = message(idByte: 3, sender: actor, outgoing: false, body: "CONFLICT")
        XCTAssertEqual(
            try reopened.acceptInbound(
                conflict,
                actorId: actor,
                sessionDomain: session,
                keyId: keyId,
                counter: 1,
                fingerprint: canonical32(6)
            ),
            .rejected
        )
        XCTAssertEqual(reopened.messages, [incoming])
    }

    func testUnreadStateIsDurableScopedAndClearsOnlyAfterPersist() throws {
        enum Expected: Error { case unavailable }

        let actor = canonical32(0x31)
        let directActor = canonical32(0x37)
        let localActor = canonical32(0x32)
        let session = canonical32(0x33)
        let keyId = canonical32(0x34)
        let roomFingerprint = canonical32(0x35)
        let directFingerprint = canonical32(0x36)
        let roomMessage = message(
            idByte: 31,
            sender: actor,
            outgoing: false,
            body: "ROOM UPDATE"
        )
        let directMessage = message(
            idByte: 32,
            sender: directActor,
            outgoing: false,
            body: "DIRECT UPDATE",
            scope: .direct,
            recipient: localActor
        )
        let store = TacMapChatStore(containerURL: directory)
        try store.open(roomId: roomId)

        XCTAssertEqual(try store.acceptInbound(
            roomMessage,
            actorId: actor,
            sessionDomain: session,
            keyId: keyId,
            counter: 1,
            fingerprint: roomFingerprint
        ), .accepted)
        XCTAssertEqual(try store.acceptInbound(
            directMessage,
            actorId: directActor,
            sessionDomain: session,
            keyId: keyId,
            counter: 1,
            fingerprint: directFingerprint
        ), .accepted)
        XCTAssertEqual(store.unreadMessageCount, 2)
        XCTAssertEqual(store.unreadCount(scope: .room, directActorId: nil), 1)
        XCTAssertEqual(store.unreadCount(scope: .direct, directActorId: directActor), 1)

        let reopened = TacMapChatStore(containerURL: directory)
        try reopened.open(roomId: roomId)
        XCTAssertEqual(reopened.unreadMessageCount, 2,
                       "unread IDs must stay inside the sealed per-room history")

        XCTAssertTrue(try reopened.markRead(scope: .room, directActorId: nil))
        XCTAssertEqual(reopened.unreadMessageCount, 1)
        XCTAssertEqual(reopened.unreadCount(scope: .room, directActorId: nil), 0)
        XCTAssertEqual(reopened.unreadCount(scope: .direct, directActorId: directActor), 1)
        XCTAssertEqual(try reopened.acceptInbound(
            roomMessage,
            actorId: actor,
            sessionDomain: session,
            keyId: keyId,
            counter: 1,
            fingerprint: roomFingerprint
        ), .duplicate)
        XCTAssertEqual(reopened.unreadCount(scope: .room, directActorId: nil), 0,
                       "a duplicate must not recreate an acknowledged unread entry")

        let key = testKey
        SafeStore.keyProvider = { throw Expected.unavailable }
        XCTAssertThrowsError(try reopened.markRead(scope: .direct, directActorId: directActor))
        XCTAssertEqual(reopened.unreadMessageCount, 1,
                       "the badge must not clear before its sealed acknowledgement persists")
        SafeStore.keyProvider = { key }

        XCTAssertTrue(try reopened.markRead(scope: .direct, directActorId: directActor))
        XCTAssertEqual(reopened.unreadMessageCount, 0)
        let coldReload = TacMapChatStore(containerURL: directory)
        try coldReload.open(roomId: roomId)
        XCTAssertEqual(coldReload.unreadMessageCount, 0)

        let outgoing = message(
            idByte: 33,
            sender: localActor,
            outgoing: true,
            body: "OUTBOUND"
        )
        XCTAssertTrue(try coldReload.appendOutgoing(outgoing))
        XCTAssertEqual(coldReload.unreadMessageCount, 0,
                       "outgoing messages must never affect unread state")
    }

    func testVersionOneHistoryMigratesAsReadInsideSealedStore() throws {
        struct LegacyDocument: Encodable {
            let version: Int
            let messages: [TacMapChatMessage]
            let replay: [String: TacMapChatStore.ReplayFence]
        }

        let legacyMessage = message(
            idByte: 41,
            sender: canonical32(0x41),
            outgoing: false,
            body: "ALREADY SEEN"
        )
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let legacy = try encoder.encode(LegacyDocument(
            version: 1,
            messages: [legacyMessage],
            replay: [:]
        ))
        try FileManager.default.createDirectory(
            at: historyURL().deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        try SafeStore.write(legacy, to: historyURL(), label: "sync/chat/\(roomId!)")

        let store = TacMapChatStore(containerURL: directory)
        try store.open(roomId: roomId)
        XCTAssertEqual(store.messages, [legacyMessage])
        XCTAssertEqual(store.unreadMessageCount, 0,
                       "pre-unread-schema history must migrate as already read")

        let migrated = SafeStore.read(historyURL(), label: "sync/chat/\(roomId!)") { data in
            try JSONSerialization.jsonObject(with: data) as! [String: Any]
        }
        guard case .loaded(let document) = migrated else {
            return XCTFail("Expected the sealed history migration to persist")
        }
        XCTAssertEqual(document["version"] as? Int, 2)
        XCTAssertEqual(document["unreadMessageIds"] as? [String], [])
    }

    func testLockClearsPlaintextAndCannotOverwriteHistory() throws {
        let store = TacMapChatStore(containerURL: directory)
        try store.open(roomId: roomId)
        let first = message(idByte: 7, sender: canonical32(7), outgoing: true, body: "SECRET ONE")
        try store.appendOutgoing(first)
        let before = try Data(contentsOf: historyURL())

        store.lock()
        XCTAssertTrue(store.messages.isEmpty)
        XCTAssertTrue(store.isLocked)
        XCTAssertThrowsError(try store.appendOutgoing(
            message(idByte: 8, sender: canonical32(7), outgoing: true, body: "SECRET TWO")
        ))
        XCTAssertEqual(try Data(contentsOf: historyURL()), before)
    }

    func testChatStoreRejectsPlaintextWithoutMigration() throws {
        let file = historyURL()
        try FileManager.default.createDirectory(
            at: file.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        try Data(#"{"version":1,"messages":[],"replay":{}}"#.utf8).write(to: file)
        let original = try Data(contentsOf: file)
        let store = TacMapChatStore(containerURL: directory)

        XCTAssertThrowsError(try store.open(roomId: roomId)) { error in
            XCTAssertEqual(error as? TacMapChatStore.StoreError, .unsealed)
        }
        XCTAssertEqual(try Data(contentsOf: file), original)
    }

    func testSenderCreatedAtDoesNotControlLocalHistoryOrder() throws {
        let store = TacMapChatStore(containerURL: directory)
        try store.open(roomId: roomId)
        let actor = canonical32(9)
        let future = message(
            idByte: 9,
            sender: actor,
            outgoing: true,
            body: "FUTURE",
            sentAt: Int64.max
        )
        let current = message(
            idByte: 10,
            sender: actor,
            outgoing: true,
            body: "CURRENT",
            sentAt: 1_720_000_000_000
        )
        try store.appendOutgoing(future)
        try store.appendOutgoing(current)
        XCTAssertEqual(store.messages.map(\.id), [future.id, current.id],
                       "createdAt is display-only; retention follows local acceptance")
    }

    func testChatShortcutMeetsMinimumTouchTarget() {
        let host = UIHostingController(
            rootView: TacMapChatShortcutButton(store: TacMapChatStore(), action: {})
        )

        let size = host.sizeThatFits(in: CGSize(width: 100, height: 100))
        XCTAssertGreaterThanOrEqual(size.width, 44)
        XCTAssertGreaterThanOrEqual(size.height, 44)
        XCTAssertLessThan(size.width, 45)
        XCTAssertLessThan(size.height, 45)
    }

    private func historyURL() -> URL {
        directory
            .appendingPathComponent("tacmap_chat", isDirectory: true)
            .appendingPathComponent(try! SyncLocalStore.fileName(
                dataKey: testKey,
                roomId: roomId,
                domain: .chat
            ))
    }

    private func legacyHistoryURL() -> URL {
        directory
            .appendingPathComponent("tacmap_chat", isDirectory: true)
            .appendingPathComponent("\(roomId!).json")
    }

    private func message(idByte: UInt8,
                         sender: String,
                         outgoing: Bool,
                         body: String,
                         sentAt: Int64? = nil,
                         scope: TacMapChatScope = .room,
                         recipient: String? = nil) -> TacMapChatMessage {
        TacMapChatMessage(
            id: SyncIdentity.urlB64Encode(Data(repeating: idByte, count: 16)),
            roomId: roomId,
            scope: scope,
            senderActorId: sender,
            senderName: outgoing ? "Own unit" : "Remote unit",
            recipientActorId: scope == .direct ? recipient : nil,
            recipientName: scope == .direct ? "Own unit" : nil,
            kind: .text,
            body: body,
            sentAtMilliseconds: sentAt ?? (1_720_000_000_000 + Int64(idByte)),
            isOutgoing: outgoing,
            deliveryState: outgoing ? .sending : .received,
            failureCode: nil
        )
    }

    private func canonical32(_ byte: UInt8) -> String {
        SyncIdentity.urlB64Encode(Data(repeating: byte, count: 32))
    }
}
