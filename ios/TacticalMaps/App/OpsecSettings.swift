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

/// How the map's upward direction is controlled. Raw values are stable because
/// they are persisted in UserDefaults.
enum MapOrientationMode: String, CaseIterable, Identifiable {
    case northUp
    case headingUp

    static let defaultsKey = "map.orientationMode"

    var id: String { rawValue }

    var label: String {
        switch self {
        case .northUp: "North Up"
        case .headingUp: "Heading Up"
        }
    }

    static func stored(in defaults: UserDefaults = .standard) -> Self {
        guard let rawValue = defaults.string(forKey: defaultsKey),
              let stored = Self(rawValue: rawValue) else {
            // Preserve the existing gesture-controlled, north-up starting state.
            return .northUp
        }
        return stored
    }

    func persist(in defaults: UserDefaults = .standard) {
        defaults.set(rawValue, forKey: Self.defaultsKey)
    }
}

/// User-selectable cadence for screen-off Unit Sync location sharing.
/// Raw minute values are stable because they are persisted in UserDefaults.
enum BackgroundUnitSyncInterval: Int, CaseIterable, Identifiable {
    case oneMinute = 1
    case fiveMinutes = 5
    case fifteenMinutes = 15
    case thirtyMinutes = 30
    case sixtyMinutes = 60

    static let defaultsKey = "opsec.backgroundUnitSyncIntervalMinutes"
    static let defaultValue: Self = .fifteenMinutes

    var id: Int { rawValue }
    var seconds: TimeInterval { TimeInterval(rawValue * 60) }

    var label: String {
        rawValue == 1 ? "Every minute" : "Every \(rawValue) minutes"
    }

    static func stored(in defaults: UserDefaults = .standard) -> Self {
        guard let stored = Self(rawValue: defaults.integer(forKey: defaultsKey)) else {
            return defaultValue
        }
        return stored
    }

    func persist(in defaults: UserDefaults = .standard) {
        defaults.set(rawValue, forKey: Self.defaultsKey)
    }
}

/// App-wide OPSEC / privacy settings. Persisted in UserDefaults and observable
/// by SwiftUI. Fresh installs favour a dark-by-default network posture:
/// privacyScreen ON - opaque cover redacts map (with live position)
/// in app-switcher snapshot whenever app isn't active.
/// onlineLookups OFF - providers receive no query or coordinate until opt-in.
/// onlineBasemaps OFF - providers receive no viewed tile until opt-in.
/// backgroundUnitSyncLocation OFF - screen-off position egress requires opt-in.
/// Every network feature remains independently switchable. Existing UserDefaults
/// values take precedence, so an update preserves each user's explicit choices.
final class OpsecSettings: ObservableObject {
    static let shared = OpsecSettings()
    static let defaultOnlineLookups = false
    static let defaultOnlineBasemaps = false
    /// Existing users must explicitly opt in before an update begins sharing
    /// their position after the app leaves the foreground.
    static let defaultBackgroundUnitSyncLocation = false

    @Published private(set) var privacyScreen: Bool
    @Published private(set) var onlineLookups: Bool
    @Published private(set) var onlineBasemaps: Bool
    @Published private(set) var backgroundUnitSyncLocation: Bool
    @Published private(set) var backgroundUnitSyncInterval: BackgroundUnitSyncInterval
    @Published private(set) var relayURL: String
    @Published private(set) var coordinateDisplayFormat: CoordinateDisplayFormat
    @Published private(set) var mapOrientationMode: MapOrientationMode
    @Published private(set) var persistenceIssue: String?
    @Published private(set) var relayValidationIssue: String?

    // Host only, no "/room/" - SyncManager appends the full "/room/<id>" path
    // itself. (Matches SyncManager.relayBase; a trailing "/room/" here would
    // double up the path and the relay would 404 the socket.)
    static let defaultRelay = "wss://tacmap-sync.christianbrooker.workers.dev"

    private let defaults: UserDefaults
    private let synchronize: (UserDefaults) -> Bool

    init(
        defaults: UserDefaults = .standard,
        environment: [String: String] = ProcessInfo.processInfo.environment,
        synchronize: @escaping (UserDefaults) -> Bool = { $0.synchronize() }
    ) {
        self.defaults = defaults
        self.synchronize = synchronize
        persistenceIssue = nil
        relayValidationIssue = nil
        privacyScreen = defaults.object(forKey: Keys.privacy) as? Bool ?? true
        onlineLookups = defaults.object(forKey: Keys.online) as? Bool ?? Self.defaultOnlineLookups
        onlineBasemaps = defaults.object(forKey: Keys.basemaps) as? Bool ?? Self.defaultOnlineBasemaps
        backgroundUnitSyncLocation = defaults.object(forKey: Keys.backgroundUnitSyncLocation) as? Bool
            ?? Self.defaultBackgroundUnitSyncLocation
        backgroundUnitSyncInterval = BackgroundUnitSyncInterval.stored(in: defaults)
        let storedRelay = defaults.string(forKey: Keys.relay)
        let relayResolution = RelayEndpointPolicy.resolvePersisted(
            storedRelay,
            defaultEndpoint: Self.defaultRelay
        )
        relayURL = relayResolution.endpoint
        coordinateDisplayFormat = CoordinateDisplayFormat.stored(in: defaults)
        mapOrientationMode = MapOrientationMode.stored(in: defaults)
        // Marketing-screenshot mode: the store XCUITest sets this env var so the
        // shots explicitly request online tiles/lookups regardless of saved state.
        // Never set in production (env vars can't be injected into a
        // shipped app), so this is inert outside the screenshot harness.
        if environment["TACMAP_UITEST_ONLINE"] == "1" {
            onlineBasemaps = true
            onlineLookups = true
        } else if environment["TACMAP_UITEST_OFFLINE_BASEMAP"] == "1" {
            // GeoPDF slide: force the online basemap OFF (authoritatively, over any
            // value a prior run persisted) so the imported PDF sheet is what shows.
            onlineBasemaps = false
        }
        if relayResolution.needsRepair,
           !persist(relayResolution.endpoint, key: Keys.relay, verify: {
               self.defaults.string(forKey: Keys.relay) == relayResolution.endpoint
           }) {
            persistenceIssue = "The saved Unit Sync relay was unsafe or obsolete. TacMap is using its secure default for this run, but could not repair the saved setting."
        }
    }

    @discardableResult
    func setPrivacyScreen(_ value: Bool) -> Bool {
        guard persist(value, key: Keys.privacy, verify: {
            self.defaults.object(forKey: Keys.privacy) as? Bool == value
        }) else { return false }
        privacyScreen = value
        return true
    }

    @discardableResult
    func setOnlineLookups(_ value: Bool) -> Bool {
        guard persist(value, key: Keys.online, verify: {
            self.defaults.object(forKey: Keys.online) as? Bool == value
        }) else { return false }
        onlineLookups = value
        return true
    }

    @discardableResult
    func setOnlineBasemaps(_ value: Bool) -> Bool {
        guard persist(value, key: Keys.basemaps, verify: {
            self.defaults.object(forKey: Keys.basemaps) as? Bool == value
        }) else { return false }
        onlineBasemaps = value
        return true
    }

    @discardableResult
    func setBackgroundUnitSyncLocation(_ value: Bool) -> Bool {
        guard persist(value, key: Keys.backgroundUnitSyncLocation, verify: {
            self.defaults.object(forKey: Keys.backgroundUnitSyncLocation) as? Bool == value
        }) else { return false }
        backgroundUnitSyncLocation = value
        return true
    }

    @discardableResult
    func setBackgroundUnitSyncInterval(_ value: BackgroundUnitSyncInterval) -> Bool {
        guard persist(value.rawValue, key: BackgroundUnitSyncInterval.defaultsKey, verify: {
            self.defaults.integer(forKey: BackgroundUnitSyncInterval.defaultsKey) == value.rawValue
        }) else { return false }
        backgroundUnitSyncInterval = value
        return true
    }

    @discardableResult
    func setRelayURL(_ value: String) -> Bool {
        let normalized: String
        do {
            normalized = try RelayEndpointPolicy.normalize(value)
        } catch let error as RelayEndpointPolicy.ValidationError {
            relayValidationIssue = error.localizedDescription
            return false
        } catch {
            relayValidationIssue = "The relay address is not valid."
            return false
        }
        guard persist(normalized, key: Keys.relay, verify: {
            self.defaults.string(forKey: Keys.relay) == normalized
        }) else { return false }
        relayURL = normalized
        relayValidationIssue = nil
        return true
    }

    @discardableResult
    func resetRelayURL() -> Bool {
        setRelayURL(Self.defaultRelay)
    }

    func clearRelayValidationIssue() {
        relayValidationIssue = nil
    }

    @discardableResult
    func setCoordinateDisplayFormat(_ value: CoordinateDisplayFormat) -> Bool {
        guard persist(value.rawValue, key: CoordinateDisplayFormat.defaultsKey, verify: {
            self.defaults.string(forKey: CoordinateDisplayFormat.defaultsKey) == value.rawValue
        }) else { return false }
        coordinateDisplayFormat = value
        return true
    }

    @discardableResult
    func setMapOrientationMode(_ value: MapOrientationMode) -> Bool {
        guard persist(value.rawValue, key: MapOrientationMode.defaultsKey, verify: {
            self.defaults.string(forKey: MapOrientationMode.defaultsKey) == value.rawValue
        }) else { return false }
        mapOrientationMode = value
        return true
    }

    /// UserDefaults mutates its process cache before durability is known. Keep
    /// the prior exact object, require a synchronized readback, and roll back
    /// before publishing any setting to the rest of the app.
    private func persist(
        _ value: Any,
        key: String,
        verify: () -> Bool
    ) -> Bool {
        let previous = defaults.object(forKey: key)
        defaults.set(value, forKey: key)
        guard synchronize(defaults), verify() else {
            if let previous {
                defaults.set(previous, forKey: key)
            } else {
                defaults.removeObject(forKey: key)
            }
            _ = synchronize(defaults)
            persistenceIssue = "Could not save this privacy setting. The previous setting remains active; check available storage and try again."
            return false
        }
        persistenceIssue = nil
        return true
    }

    // The "require auth to decrypt" toggle deliberately isn't here. It has to
    // stay in lockstep with the access control on the Keychain item, so it lives
    // in DataKey and UserDefaults would only be a second, drifting copy.

    private enum Keys {
        static let privacy = "opsec.privacyScreen"
        static let online = "opsec.onlineLookups"
        static let basemaps = "opsec.onlineBasemaps"
        static let backgroundUnitSyncLocation = "opsec.backgroundUnitSyncLocation"
        static let relay = "opsec.relayURL"
    }
}
