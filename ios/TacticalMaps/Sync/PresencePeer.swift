import Foundation

/// Remote unit member broadcasting location via sync relay. Ephemeral: recent
/// updates expire quickly, while a v3 location may remain as a bounded
/// last-known position when its matching authenticated session is still active.
struct PresencePeer: Identifiable {
    let clientId: String
    var callsign: String
    var affiliation: String
    var echelon: String
    var function: String
    var isHQ: Bool
    var lat: Double
    var lon: Double
    var heading: Double
    var speed: Double
    var ts: TimeInterval
    /// Authenticated v3 session that supplied this map location. Nil for v2.
    /// Relay leave/replacement frames may only remove the matching session.
    var sessionDomain: String? = nil
    /// Sender-signed lifetime for this last-known position. Legacy and
    /// foreground-only senders stay on the short live-update window.
    var retentionWindow: TimeInterval = PresenceExpiryPolicy.liveUpdateWindow
    /// Signed horizontal accuracy when advertised by an updated v3 sender.
    var horizontalAccuracyMetres: Double? = nil
    var receivedAt: Date = Date()
    /// Process-local monotonic receipt time used for security-sensitive expiry.
    /// Wall-clock changes must not extend an ephemeral marker.
    var receivedAtUptime: TimeInterval = ProcessInfo.processInfo.systemUptime
    /// Monotonic instant when the coordinate aged beyond the live-update window.
    var staleSinceUptime: TimeInterval? = nil
    /// Start of the current transport-loss/session-rotation grace. This is
    /// separate from fix age so repeated reconnect failures cannot extend it.
    var reconnectGraceStartedAtUptime: TimeInterval? = nil

    var id: String { clientId }
    var isStale: Bool {
        staleSinceUptime != nil || reconnectGraceStartedAtUptime != nil
    }
}

struct PresenceMarkerPresentation: Equatable {
    let visibleLabel: String
    let accessibilityLabel: String
}

struct PresenceMarkerAppearance: Equatable {
    let callsign: String
    let affiliation: String
    let echelon: String
    let function: String
    let isHQ: Bool
    let isStale: Bool
    let presentation: PresenceMarkerPresentation
}

/// Opacity is only a secondary stale cue. The visible label and VoiceOver
/// description both state that this is a last-known coordinate and its age.
func presenceMarkerPresentation(
    _ peer: PresencePeer,
    nowUptime: TimeInterval = ProcessInfo.processInfo.systemUptime
) -> PresenceMarkerPresentation {
    let unitName = peer.callsign.isEmpty ? L10n.text("Unit") : peer.callsign
    guard peer.isStale else {
        return PresenceMarkerPresentation(
            visibleLabel: peer.callsign,
            accessibilityLabel: unitName
        )
    }
    let age = max(0, nowUptime - peer.receivedAtUptime)
    let shortAge: String
    let spokenAge: String
    if age < 60 {
        shortAge = "<1m"
        spokenAge = L10n.text("less than 1 minute ago")
    } else if age < 60 * 60 {
        let minutes = Int(age / 60)
        shortAge = "\(minutes)m"
        spokenAge = L10n.quantity("minute_ago", minutes)
    } else {
        let hours = Int(age / (60 * 60))
        shortAge = "\(hours)h"
        spokenAge = L10n.quantity("hour_ago", hours)
    }
    return PresenceMarkerPresentation(
        visibleLabel: peer.callsign.isEmpty
            ? L10n.text("Last known · %1$@", shortAge)
            : L10n.text("%1$@ · Last known %2$@", peer.callsign, shortAge),
        accessibilityLabel: L10n.text("%1$@. Last known %2$@.", unitName, spokenAge)
    )
}

func presenceMarkerAppearance(
    _ peer: PresencePeer,
    nowUptime: TimeInterval = ProcessInfo.processInfo.systemUptime
) -> PresenceMarkerAppearance {
    PresenceMarkerAppearance(
        callsign: peer.callsign,
        affiliation: peer.affiliation,
        echelon: peer.echelon,
        function: peer.function,
        isHQ: peer.isHQ,
        isStale: peer.isStale,
        presentation: presenceMarkerPresentation(peer, nowUptime: nowUptime)
    )
}

/// A signature-verified v3 hello/session currently reported by the relay.
/// This remains separate from PresencePeer because room membership does not
/// require a user to share a location. Relay-attested liveness is metadata,
/// not cryptographic proof that the connection is still live.
struct OnlineMember: Identifiable, Equatable {
    let clientId: String
    var displayName: String
    var callsign: String?
    var affiliation: String?
    var echelon: String?
    var function: String?
    var isHQ: Bool?
    let joinedAt: Date
    var lastSeenAt: Date
    var metadataUpdatedAt: Date?

    var id: String { clientId }
}

struct V3ActiveSession: Equatable {
    let publicKey: String
    let sessionDomain: String
}

enum PresenceExpiryPolicy {
    static let liveUpdateWindow: TimeInterval = 45
    /// Five minutes of delivery grace beyond the longest selectable 60-minute
    /// background cadence. Relay-attested sessions still remain bounded.
    static let activeSessionLastKnownWindow: TimeInterval = 65 * 60
    static let reconnectLastKnownWindow: TimeInterval = 2 * 60

    static func markedStale(
        _ peer: PresencePeer,
        nowUptime: TimeInterval = ProcessInfo.processInfo.systemUptime
    ) -> PresencePeer {
        guard peer.reconnectGraceStartedAtUptime == nil else { return peer }
        var updated = peer
        updated.reconnectGraceStartedAtUptime = nowUptime
        return updated
    }

    /// A verified hello for the same signed session restores transport
    /// liveness without making an old coordinate look fresh.
    static func transportRestored(
        _ peer: PresencePeer,
        nowUptime: TimeInterval = ProcessInfo.processInfo.systemUptime
    ) -> PresencePeer {
        var updated = peer
        updated.reconnectGraceStartedAtUptime = nil
        return withFreshness(updated, nowUptime: nowUptime)
    }

    static func withFreshness(
        _ peer: PresencePeer,
        nowUptime: TimeInterval = ProcessInfo.processInfo.systemUptime
    ) -> PresencePeer {
        guard peer.staleSinceUptime == nil, nowUptime >= peer.receivedAtUptime,
              nowUptime - peer.receivedAtUptime > liveUpdateWindow else { return peer }
        var updated = peer
        updated.staleSinceUptime = peer.receivedAtUptime + liveUpdateWindow
        return updated
    }

    static func shouldRetain(
        _ peer: PresencePeer,
        activeSession: V3ActiveSession?,
        nowUptime: TimeInterval = ProcessInfo.processInfo.systemUptime
    ) -> Bool {
        let age = nowUptime - peer.receivedAtUptime
        guard age >= 0 else { return false }
        if age <= liveUpdateWindow { return true }
        let reconnectGrace = peer.reconnectGraceStartedAtUptime.map {
            nowUptime >= $0 && nowUptime - $0 <= reconnectLastKnownWindow
        } ?? false
        let activeRetention = peer.retentionWindow.isFinite &&
            peer.retentionWindow > liveUpdateWindow &&
            age <= min(peer.retentionWindow, activeSessionLastKnownWindow) &&
            peer.sessionDomain != nil &&
            activeSession?.sessionDomain == peer.sessionDomain
        return reconnectGrace || activeRetention
    }
}

/// Optional, independently signed extension to the backwards-compatible v1
/// presence envelope. Older clients ignore these fields and keep their 45s
/// behavior; updated clients extend a marker only when the exact bytes verify.
struct PresenceRetentionAdvertisement: Equatable {
    static let envelopeVersion: Int64 = 1
    static let versionField = "prv"
    static let payloadField = "pr"
    static let signatureField = "prsig"
    static let payloadTTLField = "ttl"
    static let signatureKind = "loc-retention"

    enum DecodeResult {
        case absent
        case valid(PresenceRetentionAdvertisement)
        case invalid
    }

    let seconds: Int
    let signedPayload: Data
    let signature: String

    static func retentionSeconds(
        backgroundEnabled: Bool,
        backgroundInterval: TimeInterval
    ) -> Int {
        guard backgroundEnabled else { return Int(PresenceExpiryPolicy.liveUpdateWindow) }
        let withDeliveryGrace = backgroundInterval + (5 * 60)
        return Int(min(PresenceExpiryPolicy.activeSessionLastKnownWindow, withDeliveryGrace))
    }

    static func encodePayload(seconds: Int) -> Data? {
        guard seconds >= Int(PresenceExpiryPolicy.liveUpdateWindow),
              seconds <= Int(PresenceExpiryPolicy.activeSessionLastKnownWindow) else { return nil }
        return try? JSONSerialization.data(
            withJSONObject: [payloadTTLField: seconds],
            options: .sortedKeys
        )
    }

    static func decode(from envelope: [String: Any]) -> DecodeResult {
        let fields = [versionField, payloadField, signatureField]
        guard fields.contains(where: envelope.keys.contains) else { return .absent }
        guard let version = strictInteger(envelope[versionField]),
              version == envelopeVersion,
              let encoded = envelope[payloadField] as? String,
              !encoded.isEmpty,
              encoded.utf8.count <= 256,
              let bytes = Data(base64Encoded: encoded),
              bytes.base64EncodedString() == encoded,
              bytes.count <= 128,
              let object = try? JSONSerialization.jsonObject(with: bytes) as? [String: Any],
              Set(object.keys) == [payloadTTLField],
              let ttl = strictInteger(object[payloadTTLField]),
              ttl >= Int64(PresenceExpiryPolicy.liveUpdateWindow),
              ttl <= Int64(PresenceExpiryPolicy.activeSessionLastKnownWindow),
              let signature = envelope[signatureField] as? String,
              !signature.isEmpty else { return .invalid }
        return .valid(Self(seconds: Int(ttl), signedPayload: bytes, signature: signature))
    }

    private static func strictInteger(_ value: Any?) -> Int64? {
        guard let number = value as? NSNumber,
              CFGetTypeID(number) != CFBooleanGetTypeID() else { return nil }
        let double = number.doubleValue
        guard double.isFinite, double.rounded(.towardZero) == double,
              double >= Double(Int64.min), double <= Double(Int64.max) else { return nil }
        return number.int64Value
    }
}

enum V3DepartureKind: Equatable {
    case explicit
    case transient
    case replacement
}

struct V3Departure: Equatable {
    let actorId: String
    let sessionDomain: String
    let kind: V3DepartureKind

    init(actorId: String,
         sessionDomain: String,
         kind: V3DepartureKind = .transient) {
        self.actorId = actorId
        self.sessionDomain = sessionDomain
        self.kind = kind
    }
}

/// A v3 leave is relay-attested liveness metadata rather than a peer-signed
/// assertion. Accept only the exact authenticated actor/session tuple so a
/// delayed close from a replaced socket cannot erase its successor.
func acceptedV3Departure(_ message: [String: Any],
                         activeSessions: [String: V3ActiveSession],
                         ownActorId: String?) -> V3Departure? {
    var kinds: [V3DepartureKind] = []
    for (field, kind) in [
        ("explicit", V3DepartureKind.explicit),
        ("transient", V3DepartureKind.transient),
        ("replaced", V3DepartureKind.replacement),
    ] where message[field] != nil {
        guard let flag = message[field] as? NSNumber,
              CFGetTypeID(flag) == CFBooleanGetTypeID(),
              flag.boolValue else { return nil }
        kinds.append(kind)
    }
    guard kinds.count <= 1 else { return nil }
    guard let actor = message["by"] as? String,
          let session = message["sd"] as? String,
          actor != ownActorId,
          SyncIdentity.decodeCanonical32(actor) != nil,
          SyncIdentity.decodeCanonical32(session) != nil,
          activeSessions[actor]?.sessionDomain == session else { return nil }
    return V3Departure(
        actorId: actor,
        sessionDomain: session,
        // Legacy relays have no discriminator. Preserve the marker as bounded
        // last-known truth rather than silently treating a flap as user leave.
        kind: kinds.first ?? .transient
    )
}

/// Replacement/transient close is transport loss, not a deliberate departure.
/// Preserve the exact last authenticated fix as visibly stale; only an
/// authenticated explicit leave clears it immediately.
func presencePeerAfterV3Departure(
    _ peer: PresencePeer?,
    departure: V3Departure,
    nowUptime: TimeInterval = ProcessInfo.processInfo.systemUptime
) -> PresencePeer? {
    guard let peer else { return nil }
    guard peer.sessionDomain == departure.sessionDomain else { return peer }
    return departure.kind == .explicit
        ? nil
        : PresenceExpiryPolicy.markedStale(peer, nowUptime: nowUptime)
}

/// Pure lifecycle reducer. SyncManager calls it only after authenticating a v3
/// hello or signed presence payload, which keeps membership independent of map
/// location and makes replacement/leave behavior directly regression-testable.
final class OnlineMemberTracker {
    private struct Tracked {
        let sessionDomain: String
        var member: OnlineMember
    }

    private var tracked: [String: Tracked] = [:]
    private let staleMetadataAfter: TimeInterval

    init(staleMetadataAfter: TimeInterval = 5 * 60) {
        precondition(staleMetadataAfter > 0)
        self.staleMetadataAfter = staleMetadataAfter
    }

    func authenticatedHello(clientId: String,
                            sessionDomain: String,
                            now: Date = Date()) -> [String: OnlineMember] {
        guard !clientId.isEmpty, !sessionDomain.isEmpty else { return snapshot() }
        if var current = tracked[clientId], current.sessionDomain == sessionDomain {
            current.member.lastSeenAt = now
            tracked[clientId] = current
        } else {
            tracked[clientId] = Tracked(
                sessionDomain: sessionDomain,
                member: OnlineMember(
                    clientId: clientId,
                    displayName: Self.anonymousName(clientId),
                    callsign: nil,
                    affiliation: nil,
                    echelon: nil,
                    function: nil,
                    isHQ: nil,
                    joinedAt: now,
                    lastSeenAt: now,
                    metadataUpdatedAt: nil
                )
            )
        }
        return snapshot()
    }

    func authenticatedActivity(clientId: String,
                               sessionDomain: String,
                               now: Date = Date()) -> [String: OnlineMember] {
        guard var current = tracked[clientId], current.sessionDomain == sessionDomain else {
            return authenticatedHello(clientId: clientId, sessionDomain: sessionDomain, now: now)
        }
        current.member.lastSeenAt = now
        tracked[clientId] = current
        return snapshot()
    }

    func updatePresenceMetadata(clientId: String,
                                sessionDomain: String,
                                callsign: String,
                                affiliation: String,
                                echelon: String,
                                function: String,
                                isHQ: Bool,
                                now: Date = Date()) -> [String: OnlineMember] {
        guard var current = tracked[clientId], current.sessionDomain == sessionDomain else {
            return snapshot()
        }
        let safeCallsign = Self.safeDisplayText(callsign, limit: 64)
        current.member.displayName = safeCallsign ?? Self.anonymousName(clientId)
        current.member.callsign = safeCallsign
        current.member.affiliation = Self.safeDisplayText(affiliation, limit: 64)
        current.member.echelon = Self.safeDisplayText(echelon, limit: 64)
        current.member.function = Self.safeDisplayText(function, limit: 64)
        current.member.isHQ = isHQ
        current.member.lastSeenAt = now
        current.member.metadataUpdatedAt = now
        tracked[clientId] = current
        return snapshot()
    }

    func remove(clientId: String,
                sessionDomain: String? = nil) -> [String: OnlineMember] {
        if let sessionDomain {
            if tracked[clientId]?.sessionDomain == sessionDomain { tracked.removeValue(forKey: clientId) }
        } else {
            tracked.removeValue(forKey: clientId)
        }
        return snapshot()
    }

    func expireStaleMetadata(now: Date = Date()) -> [String: OnlineMember] {
        for (clientId, var current) in tracked {
            guard let updated = current.member.metadataUpdatedAt,
                  now.timeIntervalSince(updated) > staleMetadataAfter else { continue }
            current.member.displayName = Self.anonymousName(clientId)
            current.member.callsign = nil
            current.member.affiliation = nil
            current.member.echelon = nil
            current.member.function = nil
            current.member.isHQ = nil
            current.member.metadataUpdatedAt = nil
            tracked[clientId] = current
        }
        return snapshot()
    }

    func clear() -> [String: OnlineMember] {
        tracked.removeAll()
        return [:]
    }

    func snapshot() -> [String: OnlineMember] {
        tracked.mapValues(\.member)
    }

    private static func anonymousName(_ clientId: String) -> String {
        let safe = clientId.unicodeScalars.filter {
            CharacterSet.alphanumerics.contains($0) || $0 == "-" || $0 == "_"
        }
        let suffix = String(String.UnicodeScalarView(safe).suffix(8))
        return L10n.text("Member %1$@", suffix.isEmpty ? "unknown" : suffix)
    }

    private static func safeDisplayText(_ value: String, limit: Int) -> String? {
        let cleaned = value.unicodeScalars.filter {
            !CharacterSet.controlCharacters.contains($0) && $0.properties.generalCategory != .format
        }
        let trimmed = String(String.UnicodeScalarView(cleaned))
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        return String(trimmed.unicodeScalars.prefix(limit))
    }
}
