import Foundation

/// User-configurable identity for real-time presence broadcast on the sync relay.
/// Persisted to UserDefaults so the callsign + symbol choices survive app restarts.
struct PresenceConfig: Codable, Equatable {
    var callsign: String = ""
    var shareLocation: Bool = false
    var affiliation: String = "friend"
    var echelon: String = "team"
    var function: String = "infantry"
    var isHQ: Bool = false
}
