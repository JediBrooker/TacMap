import Foundation
import Security

/// User-visible recipient scope. Direct never falls back to room broadcast.
enum TacMapChatScope: String, Codable, CaseIterable, Identifiable {
    case room
    case direct

    var id: String { rawValue }
    var label: String {
        switch self {
        case .room: return L10n.text("Entire room")
        case .direct: return L10n.text("Selected unit")
        }
    }
}

enum TacMapChatContentKind: String, Codable, CaseIterable, Identifiable {
    case text
    case report

    var id: String { rawValue }
    var label: String { rawValue.capitalized }
}

/// A relay acknowledgement means only that the live frame was routed. TacMap
/// deliberately does not call that state "delivered" or "read".
enum TacMapChatDeliveryState: String, Codable {
    case sending
    case routed
    case received
    case failed
}

struct TacMapChatRecipient: Identifiable, Equatable, Hashable {
    let actorId: String
    let displayName: String
    let sessionDomain: String
    let keyId: String

    var id: String { actorId }
    var shortFingerprint: String { String(actorId.suffix(6)).uppercased() }
    var displayLabel: String {
        let name = displayName.trimmingCharacters(in: .whitespacesAndNewlines)
        return L10n.text("%1$@ · %2$@", name.isEmpty ? "Unknown unit" : name, shortFingerprint)
    }

    /// Callsigns are cosmetic and may be renamed without changing the secure
    /// endpoint. Selection is bound only to the authenticated actor, websocket
    /// session and ephemeral chat key tuple.
    static func == (lhs: Self, rhs: Self) -> Bool {
        lhs.actorId == rhs.actorId
            && lhs.sessionDomain == rhs.sessionDomain
            && lhs.keyId == rhs.keyId
    }

    func hash(into hasher: inout Hasher) {
        hasher.combine(actorId)
        hasher.combine(sessionDomain)
        hasher.combine(keyId)
    }
}

enum TacMapChatRoute: Identifiable, Equatable {
    case room
    case direct(TacMapChatRecipient)

    var id: String {
        switch self {
        case .room: return "room"
        case .direct(let recipient): return "direct:\(recipient.actorId):\(recipient.sessionDomain):\(recipient.keyId)"
        }
    }
}

/// Plaintext carried inside the authenticated chat AEAD envelope.
///
/// The outer frame owns sender, recipient and replay identity. Keeping those
/// values out of this payload avoids two sources of truth.
struct TacMapChatPayload: Codable, Equatable {
    static let version = 1
    static let maximumBodyBytes = 4_096
    static let maximumPlaintextBytes = 8_192
    static let maximumDisplayNameScalars = 64

    let pv: Int
    let kind: TacMapChatContentKind
    let body: String
    let createdAt: Int64
    let replyTo: String?

    init(kind: TacMapChatContentKind,
         body: String,
         createdAt: Int64 = Int64(Date().timeIntervalSince1970 * 1_000),
         replyTo: String? = nil) {
        self.pv = Self.version
        self.kind = kind
        self.body = body
        self.createdAt = createdAt
        self.replyTo = replyTo
    }

    var isValid: Bool {
        pv == Self.version
            && !body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && body.utf8.count <= Self.maximumBodyBytes
            && createdAt >= 0
            && (replyTo == nil || replyTo.map(TacMapChatMessage.isCanonicalMessageID) == true)
    }

    static func boundedBody(_ value: String) -> String {
        var result = ""
        result.reserveCapacity(min(value.count, maximumBodyBytes))
        var bytes = 0
        for scalar in value.unicodeScalars {
            let scalarText = String(scalar)
            let count = scalarText.utf8.count
            guard bytes + count <= maximumBodyBytes else { break }
            result.unicodeScalars.append(scalar)
            bytes += count
        }
        return result
    }
}

/// Local, encrypted-at-rest history entry. `id` is the canonical 16-byte
/// base64url message ID from the wire frame.
struct TacMapChatMessage: Identifiable, Codable, Equatable {
    struct RandomIdentifierError: Error {}
    let id: String
    let roomId: String
    let scope: TacMapChatScope
    let senderActorId: String
    let senderName: String
    let recipientActorId: String?
    let recipientName: String?
    let kind: TacMapChatContentKind
    let body: String
    let sentAtMilliseconds: Int64
    let isOutgoing: Bool
    var deliveryState: TacMapChatDeliveryState
    var failureCode: String?

    var sentAt: Date {
        Date(timeIntervalSince1970: TimeInterval(sentAtMilliseconds) / 1_000)
    }

    var conversationActorId: String? {
        guard scope == .direct else { return nil }
        return isOutgoing ? recipientActorId : senderActorId
    }

    var isValid: Bool {
        Self.isCanonicalMessageID(id)
            && Self.isCanonical32(roomId)
            && Self.isCanonical32(senderActorId)
            && senderName.unicodeScalars.count <= TacMapChatPayload.maximumDisplayNameScalars
            && body.utf8.count <= TacMapChatPayload.maximumBodyBytes
            && !body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && sentAtMilliseconds >= 0
            && ((scope == .room && recipientActorId == nil)
                || (scope == .direct
                    && recipientActorId.map(Self.isCanonical32) == true
                    && recipientActorId != senderActorId))
            && (recipientName?.unicodeScalars.count ?? 0) <= TacMapChatPayload.maximumDisplayNameScalars
            && (failureCode?.utf8.count ?? 0) <= 64
    }

    static func isCanonical32(_ value: String) -> Bool {
        SyncIdentity.decodeCanonical32(value) != nil
    }

    static func isCanonicalMessageID(_ value: String) -> Bool {
        guard value.count == 22,
              value.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-" || $0 == "_") }),
              let raw = SyncIdentity.urlB64Decode(value), raw.count == 16,
              SyncIdentity.urlB64Encode(raw) == value else { return false }
        return true
    }

    static func makeMessageID() throws -> String {
        var bytes = Data(count: 16)
        let status = bytes.withUnsafeMutableBytes { buffer in
            SecRandomCopyBytes(kSecRandomDefault, 16, buffer.baseAddress!)
        }
        guard status == errSecSuccess else { throw RandomIdentifierError() }
        return SyncIdentity.urlB64Encode(bytes)
    }
}
