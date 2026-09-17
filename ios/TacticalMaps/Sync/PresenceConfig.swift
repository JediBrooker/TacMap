import Foundation

/// Identity settings for presence broadcast on the sync relay. Persisted as a
/// sealed blob so callsign, symbol choices, and local room labels survive restarts.
struct PresenceConfig: Codable, Equatable {
    var callsign: String = ""
    var shareLocation: Bool = false
    var affiliation: String = "friend"
    var echelon: String = "team"
    var function: String = "infantry"
    var isHQ: Bool = false
    /// Human-readable names are local metadata keyed by the relay-safe derived
    /// room ID. This dictionary lives only in the sealed presence-config blob;
    /// it is never part of presence, routing, authentication, or a URL.
    var roomNamesById: [String: String] = [:]

    static let roomNameMaxLength = 64
    static let roomNameHistoryLimit = 64

    /// Shared with the symbol editor so Unit Sync cannot silently expose a
    /// reduced branch/function subset on either platform.
    static let functionChoices: [SymbolFunction] = SymbolFunction.allCases

    /// Length-only bound suitable for a live TextField binding. Do not trim
    /// here: removing the trailing space after every keystroke makes it
    /// impossible to type multi-word names.
    static func boundedRoomName(_ value: String) -> String {
        String(value.unicodeScalars.prefix(roomNameMaxLength))
    }

    static func normalizedRoomName(_ value: String) -> String {
        boundedRoomName(value.trimmingCharacters(in: .whitespacesAndNewlines))
    }

    func roomName(for roomId: String) -> String? {
        guard let stored = roomNamesById[roomId] else { return nil }
        let value = Self.normalizedRoomName(stored)
        return value.isEmpty ? nil : value
    }

    mutating func setRoomName(_ value: String, for roomId: String) {
        let id = String(roomId.prefix(128))
        guard !id.isEmpty else { return }
        let bounded = Self.normalizedRoomName(value)
        if bounded.isEmpty {
            roomNamesById.removeValue(forKey: id)
        } else {
            roomNamesById[id] = bounded
            // Bound runtime growth as well as decoded data. There is no durable
            // recency metadata, so evict deterministically while retaining the
            // room the user just named.
            let excess = roomNamesById.count - Self.roomNameHistoryLimit
            if excess > 0 {
                let evictions = roomNamesById.keys
                    .filter { $0 != id }
                    .sorted()
                    .prefix(excess)
                for key in evictions { roomNamesById.removeValue(forKey: key) }
            }
        }
    }

    private enum CodingKeys: String, CodingKey {
        case callsign, shareLocation, affiliation, echelon, function, isHQ, roomNamesById
    }

    init() {}

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        callsign = try c.decodeIfPresent(String.self, forKey: .callsign) ?? ""
        shareLocation = try c.decodeIfPresent(Bool.self, forKey: .shareLocation) ?? false
        affiliation = try c.decodeIfPresent(String.self, forKey: .affiliation) ?? "friend"
        echelon = try c.decodeIfPresent(String.self, forKey: .echelon) ?? "team"
        function = try c.decodeIfPresent(String.self, forKey: .function) ?? "infantry"
        isHQ = try c.decodeIfPresent(Bool.self, forKey: .isHQ) ?? false
        let decoded = try c.decodeIfPresent([String: String].self, forKey: .roomNamesById) ?? [:]
        roomNamesById = [:]
        for (id, name) in decoded.sorted(by: { $0.key < $1.key })
            .prefix(Self.roomNameHistoryLimit) {
            setRoomName(name, for: id)
        }
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(callsign, forKey: .callsign)
        try c.encode(shareLocation, forKey: .shareLocation)
        try c.encode(affiliation, forKey: .affiliation)
        try c.encode(echelon, forKey: .echelon)
        try c.encode(function, forKey: .function)
        try c.encode(isHQ, forKey: .isHQ)
        try c.encode(roomNamesById, forKey: .roomNamesById)
    }
}
