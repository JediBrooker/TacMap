import Foundation

/// Remote unit member broadcasting location via sync relay. Ephemeral -
/// peers get removed on `leave` or when they go stale (no `loc` for 45s).
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
    var receivedAt: Date = Date()

    var id: String { clientId }
}
