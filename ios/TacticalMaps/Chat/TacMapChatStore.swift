import Foundation
import Combine

@MainActor
final class TacMapChatStore: ObservableObject {
    enum StoreError: LocalizedError, Equatable {
        case inactive
        case locked
        case corrupt
        case unsealed
        case invalidRecord
        case historyTooLarge
        case persistenceFailed

        var errorDescription: String? {
            switch self {
            case .inactive: return "TacMap Chat is not attached to a secure room."
            case .locked: return "Unlock mission data to use TacMap Chat."
            case .corrupt: return "Encrypted chat history could not be authenticated."
            case .unsealed: return "Unencrypted chat history was rejected."
            case .invalidRecord: return "Chat history contains an invalid record."
            case .historyTooLarge: return "Chat history reached its protected storage limit."
            case .persistenceFailed: return "Encrypted chat history could not be updated."
            }
        }
    }

    enum InboundAcceptance: Equatable {
        case accepted
        case duplicate
        case rejected
    }

    struct ReplayFence: Codable, Equatable {
        var actorId: String
        var sessionDomain: String
        var keyId: String
        var counter: Int64
        /// Recent exact signed-frame fingerprints distinguish an idempotent
        /// relay retry from a conflicting equal-counter frame.
        var recentFingerprints: [String]

        var isValid: Bool {
            TacMapChatMessage.isCanonical32(actorId)
                && TacMapChatMessage.isCanonical32(sessionDomain)
                && TacMapChatMessage.isCanonical32(keyId)
                && counter > 0
                && recentFingerprints.count <= Document.maximumRecentFingerprints
                && recentFingerprints.allSatisfy(TacMapChatMessage.isCanonical32)
                && Set(recentFingerprints).count == recentFingerprints.count
        }
    }

    private struct Document: Codable {
        static let schemaVersion = 2
        static let maximumReplayFences = 256
        static let maximumRecentFingerprints = 16

        var version: Int = schemaVersion
        var messages: [TacMapChatMessage] = []
        var replay: [String: ReplayFence] = [:]
        /// Canonical IDs only. The notification state remains inside the same
        /// sealed room document as history and never duplicates message text.
        var unreadMessageIds: [String] = []

        var isValid: Bool {
            guard version == Self.schemaVersion,
                  Self.contentIsValid(messages: messages, replay: replay),
                  unreadMessageIds.count <= messages.count,
                  Set(unreadMessageIds).count == unreadMessageIds.count else {
                return false
            }
            let messagesById = Dictionary(uniqueKeysWithValues: messages.map { ($0.id, $0) })
            return unreadMessageIds.allSatisfy { id in
                messagesById[id].map { !$0.isOutgoing } == true
            }
        }

        static func contentIsValid(messages: [TacMapChatMessage],
                                   replay: [String: ReplayFence]) -> Bool {
            messages.count <= TacMapChatStore.maximumMessagesPerRoom
                && Set(messages.map(\.id)).count == messages.count
                && messages.allSatisfy(\.isValid)
                && replay.count <= Self.maximumReplayFences
                && replay.allSatisfy { key, fence in
                    key == Self.replayKey(
                        actorId: fence.actorId,
                        sessionDomain: fence.sessionDomain,
                        keyId: fence.keyId
                    ) && fence.isValid
                }
        }

        static func replayKey(actorId: String, sessionDomain: String, keyId: String) -> String {
            "\(actorId):\(sessionDomain):\(keyId)"
        }
    }

    /// Version 1 had no unread state. Migrating it treats all existing history
    /// as read, then rewrites the authenticated document as version 2.
    private struct LegacyDocumentV1: Codable {
        static let schemaVersion = 1
        var version: Int
        var messages: [TacMapChatMessage]
        var replay: [String: ReplayFence]

        var isValid: Bool {
            version == Self.schemaVersion
                && Document.contentIsValid(messages: messages, replay: replay)
        }
    }

    private struct LoadedDocument {
        let document: Document
        let needsRewrite: Bool
    }

    nonisolated static let maximumMessagesPerRoom = 500
    nonisolated static let maximumEncodedHistoryBytes = 2_097_152
    nonisolated static let replayAdvanceWindow: Int64 = 10_000

    @Published private(set) var messages: [TacMapChatMessage] = []
    @Published private(set) var activeRoomId: String?
    @Published private(set) var issue: String?
    @Published private(set) var isLocked = false
    /// Aggregate only; message IDs and conversation membership remain private
    /// to the sealed store.
    @Published private(set) var unreadMessageCount = 0

    private let containerURL: URL
    private var fileURL: URL?
    private var label: String?
    private var document = Document()

    init(containerURL: URL? = nil) {
        let support = containerURL ?? FileManager.default.urls(
            for: .applicationSupportDirectory,
            in: .userDomainMask
        )[0]
        self.containerURL = support.appendingPathComponent("tacmap_chat", isDirectory: true)
    }

    func open(roomId: String) throws {
        guard TacMapChatMessage.isCanonical32(roomId) else {
            fail(.invalidRecord)
            throw StoreError.invalidRecord
        }
        let dataKey: Data
        do {
            dataKey = try SafeStore.keyProvider()
        } catch {
            fail(.locked)
            throw StoreError.locked
        }
        let url: URL
        do {
            // Resolve only the requested room. The unlock-time cold-upgrade
            // scan reports inactive-room failures without taking an unrelated
            // active chat room offline.
            url = try SyncLocalStore.resolveFile(
                directory: containerURL,
                roomId: roomId,
                domain: .chat,
                dataKey: dataKey
            )
        } catch {
            fail(.persistenceFailed)
            throw StoreError.persistenceFailed
        }
        let storeLabel = "sync/chat/\(roomId)"

        // Chat did not exist in a plaintext release, so unlike legacy mission
        // stores there is nothing legitimate to migrate. Reject bare JSON
        // before SafeStore's generic migration path can accept it.
        if FileManager.default.fileExists(atPath: url.path) {
            do {
                let bytes = try Data(contentsOf: url, options: [.mappedIfSafe])
                guard bytes.count <= Self.maximumEncodedHistoryBytes + 256,
                      SealedEnvelope.isSealedFile(bytes) else {
                    fail(.unsealed)
                    throw StoreError.unsealed
                }
            } catch let error as StoreError {
                throw error
            } catch {
                fail(.corrupt)
                throw StoreError.corrupt
            }
        }

        let loaded = SafeStore.read(url, label: storeLabel) { data -> LoadedDocument in
            guard data.count <= Self.maximumEncodedHistoryBytes else {
                throw StoreError.historyTooLarge
            }
            guard let raw = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                throw StoreError.invalidRecord
            }
            let decoder = JSONDecoder()
            switch raw["version"] as? Int {
            case LegacyDocumentV1.schemaVersion:
                guard Set(raw.keys) == Set(["version", "messages", "replay"]) else {
                    throw StoreError.invalidRecord
                }
                let legacy = try decoder.decode(LegacyDocumentV1.self, from: data)
                guard legacy.isValid,
                      legacy.messages.allSatisfy({ $0.roomId == roomId }) else {
                    throw StoreError.invalidRecord
                }
                return LoadedDocument(
                    document: Document(
                        version: Document.schemaVersion,
                        messages: legacy.messages,
                        replay: legacy.replay,
                        unreadMessageIds: []
                    ),
                    needsRewrite: true
                )
            case Document.schemaVersion:
                guard Set(raw.keys) == Set([
                    "version", "messages", "replay", "unreadMessageIds"
                ]) else {
                    throw StoreError.invalidRecord
                }
                let decoded = try decoder.decode(Document.self, from: data)
                guard decoded.isValid,
                      decoded.messages.allSatisfy({ $0.roomId == roomId }) else {
                    throw StoreError.invalidRecord
                }
                return LoadedDocument(document: decoded, needsRewrite: false)
            default:
                throw StoreError.invalidRecord
            }
        }

        var needsRewrite = false
        switch loaded {
        case .empty:
            document = Document()
        case .loaded(let value):
            document = value.document
            needsRewrite = value.needsRewrite
        case .locked:
            fail(.locked)
            throw StoreError.locked
        case .corrupt:
            fail(.corrupt)
            throw StoreError.corrupt
        }
        activeRoomId = roomId
        fileURL = url
        label = storeLabel
        isLocked = false
        var recovered = document
        if document.messages.contains(where: { $0.isOutgoing && $0.deliveryState == .sending }) {
            // Pending relay frames and session keys are deliberately
            // memory-only. After a process death there is nothing safe to
            // resend, so atomically resolve durable "Sending…" entries rather
            // than displaying an impossible in-flight state forever.
            for index in recovered.messages.indices
                where recovered.messages[index].isOutgoing
                    && recovered.messages[index].deliveryState == .sending {
                recovered.messages[index].deliveryState = .failed
                recovered.messages[index].failureCode = "session-ended"
            }
            needsRewrite = true
        }
        if needsRewrite {
            do {
                try persist(recovered)
            } catch {
                fail(.persistenceFailed)
                throw StoreError.persistenceFailed
            }
        }
        publish(recovered)
        issue = nil
    }

    /// Removes plaintext history and replay metadata from memory. The sealed
    /// file remains available after the user unlocks or rejoins.
    func close() {
        document = Document()
        messages.removeAll(keepingCapacity: false)
        activeRoomId = nil
        fileURL = nil
        label = nil
        issue = nil
        isLocked = false
        unreadMessageCount = 0
    }

    func lock() {
        document = Document()
        messages.removeAll(keepingCapacity: false)
        fileURL = nil
        label = nil
        issue = StoreError.locked.localizedDescription
        isLocked = true
        unreadMessageCount = 0
    }

    @discardableResult
    func appendOutgoing(_ message: TacMapChatMessage) throws -> Bool {
        guard message.isOutgoing else { throw StoreError.invalidRecord }
        guard let roomId = activeRoomId, message.roomId == roomId,
              message.isValid else { throw StoreError.invalidRecord }
        if document.messages.contains(where: { $0.id == message.id }) { return false }
        var candidate = document
        candidate.messages.append(message)
        pruneByAcceptanceOrder(&candidate)
        try persist(candidate)
        publish(candidate)
        return true
    }

    /// Atomically commits the displayed inbound message and its replay fence.
    /// A crash can therefore never advance acceptance without also retaining
    /// the message, or retain a message without advancing acceptance.
    func acceptInbound(_ message: TacMapChatMessage,
                       actorId: String,
                       sessionDomain: String,
                       keyId: String,
                       counter: Int64,
                       fingerprint: String) throws -> InboundAcceptance {
        guard !message.isOutgoing,
              let roomId = activeRoomId,
              message.roomId == roomId,
              message.senderActorId == actorId,
              message.isValid,
              TacMapChatMessage.isCanonical32(actorId),
              TacMapChatMessage.isCanonical32(sessionDomain),
              TacMapChatMessage.isCanonical32(keyId),
              TacMapChatMessage.isCanonical32(fingerprint),
              counter > 0 else { return .rejected }

        let fenceKey = Document.replayKey(
            actorId: actorId,
            sessionDomain: sessionDomain,
            keyId: keyId
        )
        if let existing = document.replay[fenceKey] {
            if counter <= existing.counter {
                return counter == existing.counter
                    && existing.recentFingerprints.contains(fingerprint)
                    ? .duplicate : .rejected
            }
            guard counter - existing.counter <= Self.replayAdvanceWindow else { return .rejected }
        } else if counter > Self.replayAdvanceWindow {
            return .rejected
        }
        if document.messages.contains(where: { $0.id == message.id }) { return .duplicate }

        // Never evict a durable high-water mark to admit another sender: that
        // would make an older frame from the evicted endpoint acceptable after
        // restart. A room beyond this defensive ceiling fails closed instead.
        if document.replay[fenceKey] == nil,
           document.replay.count >= Document.maximumReplayFences {
            return .rejected
        }

        var candidate = document
        var fingerprints = candidate.replay[fenceKey]?.recentFingerprints ?? []
        fingerprints.append(fingerprint)
        if fingerprints.count > Document.maximumRecentFingerprints {
            fingerprints.removeFirst(fingerprints.count - Document.maximumRecentFingerprints)
        }
        candidate.replay[fenceKey] = ReplayFence(
            actorId: actorId,
            sessionDomain: sessionDomain,
            keyId: keyId,
            counter: counter,
            recentFingerprints: fingerprints
        )
        candidate.messages.append(message)
        candidate.unreadMessageIds.append(message.id)
        pruneByAcceptanceOrder(&candidate)
        try persist(candidate)
        publish(candidate)
        return .accepted
    }

    @discardableResult
    func updateDelivery(id: String,
                        state: TacMapChatDeliveryState,
                        failureCode: String? = nil) throws -> Bool {
        guard let index = document.messages.firstIndex(where: { $0.id == id }) else { return false }
        var candidate = document
        candidate.messages[index].deliveryState = state
        candidate.messages[index].failureCode = failureCode.map { String($0.prefix(64)) }
        guard candidate.messages[index].isValid else { throw StoreError.invalidRecord }
        try persist(candidate)
        publish(candidate)
        return true
    }

    func history(scope: TacMapChatScope, directActorId: String?) -> [TacMapChatMessage] {
        messages.filter {
            belongsToConversation($0, scope: scope, directActorId: directActorId)
        }
    }

    func unreadCount(scope: TacMapChatScope, directActorId: String?) -> Int {
        let messagesById = Dictionary(uniqueKeysWithValues: document.messages.map { ($0.id, $0) })
        return document.unreadMessageIds.reduce(into: 0) { count, id in
            guard let message = messagesById[id],
                  belongsToConversation(
                    message,
                    scope: scope,
                    directActorId: directActorId
                  ) else { return }
            count += 1
        }
    }

    /// Persist the read acknowledgement before publishing a smaller badge.
    /// A storage failure therefore leaves the previous unread state visible.
    @discardableResult
    func markRead(scope: TacMapChatScope, directActorId: String?) throws -> Bool {
        guard activeRoomId != nil, !isLocked else { return false }
        let messagesById = Dictionary(uniqueKeysWithValues: document.messages.map { ($0.id, $0) })
        var candidate = document
        candidate.unreadMessageIds.removeAll { id in
            guard let message = messagesById[id] else { return false }
            return belongsToConversation(
                message,
                scope: scope,
                directActorId: directActorId
            )
        }
        guard candidate.unreadMessageIds != document.unreadMessageIds else { return false }
        try persist(candidate)
        publish(candidate)
        return true
    }

    private func persist(_ value: Document) throws {
        guard !isLocked, let fileURL, let label else { throw StoreError.locked }
        guard value.isValid else { throw StoreError.invalidRecord }
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let data = try encoder.encode(value)
        guard data.count <= Self.maximumEncodedHistoryBytes else {
            throw StoreError.historyTooLarge
        }
        try FileManager.default.createDirectory(
            at: containerURL,
            withIntermediateDirectories: true,
            attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]
        )
        try SafeStore.write(data, to: fileURL, label: label)
        issue = nil
    }

    private func publish(_ value: Document) {
        document = value
        messages = value.messages
        unreadMessageCount = value.unreadMessageIds.count
    }

    /// `createdAt` is authenticated sender input but remains display-only. It
    /// must not control retention: a room member could otherwise submit future
    /// timestamps and cause later legitimate messages to be pruned. Array order
    /// is the local durable append/accept order.
    private func pruneByAcceptanceOrder(_ value: inout Document) {
        if value.messages.count > Self.maximumMessagesPerRoom {
            value.messages.removeFirst(value.messages.count - Self.maximumMessagesPerRoom)
        }
        let retainedMessageIds = Set(value.messages.map(\.id))
        value.unreadMessageIds.removeAll { !retainedMessageIds.contains($0) }
    }

    private func belongsToConversation(_ message: TacMapChatMessage,
                                       scope: TacMapChatScope,
                                       directActorId: String?) -> Bool {
        switch scope {
        case .room:
            return message.scope == .room
        case .direct:
            return message.scope == .direct
                && message.conversationActorId == directActorId
        }
    }

    private func fail(_ error: StoreError) {
        document = Document()
        messages.removeAll(keepingCapacity: false)
        activeRoomId = nil
        fileURL = nil
        label = nil
        issue = error.localizedDescription
        isLocked = error == .locked
        unreadMessageCount = 0
    }

}
