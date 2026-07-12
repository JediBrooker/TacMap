import Foundation
import Combine

/// App-wide OPSEC / privacy settings. Persisted in UserDefaults, observable
/// by SwiftUI. Defaults are OPSEC-first for a field tool:
/// privacyScreen ON - opaque cover redacts map (with live position)
/// in app-switcher snapshot whenever app isn't active.
/// onlineLookups OFF - elevation/weather/terrain lookups transmit the
/// queried coordinate to a third party (Open-Meteo) so they're opt-in.
final class OpsecSettings: ObservableObject {
    static let shared = OpsecSettings()

    @Published var privacyScreen: Bool { didSet { defaults.set(privacyScreen, forKey: Keys.privacy) } }
    @Published var onlineLookups: Bool { didSet { defaults.set(onlineLookups, forKey: Keys.online) } }
    /// Off by default. Requesting tiles hands your area of interest to the tile
    /// provider, so a fresh install fetches nothing until you say so.
    @Published var onlineBasemaps: Bool { didSet { defaults.set(onlineBasemaps, forKey: Keys.basemaps) } }
    @Published var relayURL: String { didSet { defaults.set(relayURL, forKey: Keys.relay) } }

    // Host only, no "/room/" - SyncManager appends the full "/room/<id>" path
    // itself. (Matches SyncManager.relayBase; a trailing "/room/" here would
    // double up the path and the relay would 404 the socket.)
    static let defaultRelay = "wss://tacmap-sync.christianbrooker.workers.dev"

    private let defaults = UserDefaults.standard

    private init() {
        privacyScreen = defaults.object(forKey: Keys.privacy) as? Bool ?? true
        onlineLookups = defaults.object(forKey: Keys.online) as? Bool ?? false
        onlineBasemaps = defaults.object(forKey: Keys.basemaps) as? Bool ?? false
        relayURL = defaults.string(forKey: Keys.relay) ?? Self.defaultRelay
    }

    // The "require auth to decrypt" toggle deliberately isn't here. It has to
    // stay in lockstep with the access control on the Keychain item, so it lives
    // in DataKey and UserDefaults would only be a second, drifting copy.

    private enum Keys {
        static let privacy = "opsec.privacyScreen"
        static let online = "opsec.onlineLookups"
        static let basemaps = "opsec.onlineBasemaps"
        static let relay = "opsec.relayURL"
    }
}
