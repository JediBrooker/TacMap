import Foundation
import Combine

/// App-wide operational-security / privacy settings, persisted in UserDefaults
/// and observable by SwiftUI. Defaults are OPSEC-first for a field tool:
///  - `privacyScreen` ON — an opaque cover redacts the map (with live position)
///    in the app-switcher snapshot whenever the app isn't active.
///  - `onlineLookups` OFF — elevation / weather / terrain lookups transmit the
///    queried coordinate to a third party (Open-Meteo), so they are opt-in.
final class OpsecSettings: ObservableObject {
    static let shared = OpsecSettings()

    @Published var privacyScreen: Bool { didSet { defaults.set(privacyScreen, forKey: Keys.privacy) } }
    @Published var onlineLookups: Bool { didSet { defaults.set(onlineLookups, forKey: Keys.online) } }
    @Published var relayURL: String { didSet { defaults.set(relayURL, forKey: Keys.relay) } }

    static let defaultRelay = "wss://tacmap-sync.christianbrooker.workers.dev/room/"

    private let defaults = UserDefaults.standard

    private init() {
        privacyScreen = defaults.object(forKey: Keys.privacy) as? Bool ?? true
        onlineLookups = defaults.object(forKey: Keys.online) as? Bool ?? false
        relayURL = defaults.string(forKey: Keys.relay) ?? Self.defaultRelay
    }

    private enum Keys {
        static let privacy = "opsec.privacyScreen"
        static let online = "opsec.onlineLookups"
        static let relay = "opsec.relayURL"
    }
}
