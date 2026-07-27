import Foundation
import Combine

/// Coordinate representation used for the map header's primary readout.
/// Raw values are stable because they are persisted in UserDefaults.
enum CoordinateDisplayFormat: String, CaseIterable, Identifiable {
    case mgrs
    case wgs84
    case utm

    struct Resolved: Equatable {
        let format: CoordinateDisplayFormat
        let text: String
    }

    static let defaultsKey = "map.coordinateDisplayFormat"

    var id: String { rawValue }

    var label: String {
        switch self {
        case .mgrs: "MGRS"
        case .wgs84: "WGS84"
        case .utm: "UTM"
        }
    }

    static func stored(in defaults: UserDefaults = .standard) -> Self {
        guard let rawValue = defaults.string(forKey: defaultsKey),
              let stored = Self(rawValue: rawValue) else {
            // Preserve the header users already know when upgrading.
            return .mgrs
        }
        return stored
    }

    func persist(in defaults: UserDefaults = .standard) {
        defaults.set(rawValue, forKey: Self.defaultsKey)
    }

    /// Resolves the selected format to visible text. UTM is unavailable in the
    /// polar caps; falling back to WGS84 keeps the primary readout useful there.
    func resolve(mgrs: String, wgs84: String, utm: String?) -> Resolved {
        switch self {
        case .mgrs:
            return Resolved(format: .mgrs, text: mgrs)
        case .wgs84:
            return Resolved(format: .wgs84, text: wgs84)
        case .utm:
            guard let utm else {
                return Resolved(format: .wgs84, text: wgs84)
            }
            let trimmed = utm.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty, !trimmed.hasPrefix("N/A") else {
                return Resolved(format: .wgs84, text: wgs84)
            }
            return Resolved(format: .utm, text: trimmed)
        }
    }
}

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
    @Published var coordinateDisplayFormat: CoordinateDisplayFormat {
        didSet { coordinateDisplayFormat.persist(in: defaults) }
    }

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
        coordinateDisplayFormat = CoordinateDisplayFormat.stored(in: defaults)
        // Marketing-screenshot mode: the store XCUITest sets this env var so the
        // shots show real online tiles/lookups instead of the OPSEC-default dark
        // basemap. Never set in production (env vars can't be injected into a
        // shipped app), so this is inert outside the screenshot harness.
        let env = ProcessInfo.processInfo.environment
        if env["TACMAP_UITEST_ONLINE"] == "1" {
            onlineBasemaps = true
            onlineLookups = true
        } else if env["TACMAP_UITEST_OFFLINE_BASEMAP"] == "1" {
            // GeoPDF slide: force the online basemap OFF (authoritatively, over any
            // value a prior run persisted) so the imported PDF sheet is what shows.
            onlineBasemaps = false
        }
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
