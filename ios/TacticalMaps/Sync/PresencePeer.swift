import Foundation

/// A remote unit member whose location is being broadcast via the sync relay.
/// Ephemeral — peers are removed when they send a `leave` message or go stale
/// (no `loc` update for 45 seconds).
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
