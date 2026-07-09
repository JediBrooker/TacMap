import Foundation

/// Identity settings for presence broadcast on the sync relay. Saved to
/// UserDefaults so callsign + symbol choices persist accross restarts.
struct PresenceConfig: Codable, Equatable {
    var callsign: String = ""
    var shareLocation: Bool = false
    var affiliation: String = "friend"
    var echelon: String = "team"
    var function: String = "infantry"
    var isHQ: Bool = false
}
