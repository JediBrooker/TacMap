import SwiftUI
import CoreLocation

/// General, privacy and OPSEC settings: map coordinate display, privacy screen,
/// independently switchable online lookups and basemaps, and at-rest key binding.
struct OpsecSettingsView: View {
    @ObservedObject private var appLanguage = AppLanguage.shared
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var opsec = OpsecSettings.shared

    @State private var authBound = DataKey.isAuthBound
    @State private var keyError: String?

    var body: some View {
        NavigationStack {
            Form {
                Section(L10n.text("Language")) {
                    Picker(L10n.text("Language"), selection: Binding(
                        get: { appLanguage.selection },
                        set: { appLanguage.select($0) }
                    )) {
                        ForEach(AppLanguage.Choice.allCases) { choice in
                            Text(choice.label).tag(choice)
                        }
                    }
                    .accessibilityIdentifier("settings.language")
                }

                if let issue = opsec.persistenceIssue {
                    Section(L10n.text("Settings need attention")) {
                        Text(issue)
                            .font(.caption)
                            .foregroundStyle(.red)
                    }
                }

                Section {
                    Picker(
                        L10n.text("Primary map coordinate"),
                        selection: settingBinding(
                            \.coordinateDisplayFormat,
                            set: opsec.setCoordinateDisplayFormat
                        )
                    ) {
                        ForEach(CoordinateDisplayFormat.allCases) { format in
                            Text(format.label).tag(format)
                        }
                    }
                    .pickerStyle(.segmented)
                } header: {
                    Text(L10n.text("Coordinate display"))
                } footer: {
                    Text(L10n.text("Chooses the coordinate shown in the large green map readout. MGRS is the default."))
                }

                Section {
                    ForEach(MapOrientationMode.allCases) { mode in
                        Button {
                            _ = opsec.setMapOrientationMode(mode)
                        } label: {
                            HStack {
                                Text(mode.label)
                                    .foregroundStyle(.primary)
                                Spacer()
                                if opsec.mapOrientationMode == mode {
                                    Image(systemName: "checkmark")
                                        .fontWeight(.semibold)
                                        .foregroundStyle(.tint)
                                        .accessibilityHidden(true)
                                }
                            }
                            .contentShape(Rectangle())
                        }
                        .disabled(mode == .headingUp && !CLLocationManager.headingAvailable())
                        .accessibilityLabel(mode.label)
                        .accessibilityValue(opsec.mapOrientationMode == mode ? L10n.text("Selected") : L10n.text("Not selected"))
                        .accessibilityAddTraits(opsec.mapOrientationMode == mode ? .isSelected : [])
                    }
                } header: {
                    Text(L10n.text("Map orientation"))
                } footer: {
                    if CLLocationManager.headingAvailable() {
                        Text(L10n.text("North Up starts north-facing and keeps two-finger rotation available. Heading Up uses the phone compass to keep the direction you are pointing at the top of the map. The compass marks bearings T for true north, M when it falls back to magnetic north, and ? while waiting for a valid reading."))
                    } else {
                        Text(L10n.text("Heading Up is unavailable because this device does not report compass headings."))
                    }
                }

                Section {
                    Toggle(
                        L10n.text("Privacy screen in app switcher"),
                        isOn: settingBinding(\.privacyScreen, set: opsec.setPrivacyScreen)
                    )
                } footer: {
                    Text(L10n.text("Covers the map (with your position) whenever the app isn't active, so it isn't captured in the app-switcher thumbnail."))
                }

                Section {
                    Toggle(
                        L10n.text("Background Unit Sync location"),
                        isOn: settingBinding(
                            \.backgroundUnitSyncLocation,
                            set: opsec.setBackgroundUnitSyncLocation
                        )
                    )
                    Picker(
                        L10n.text("Screen-off update interval"),
                        selection: settingBinding(
                            \.backgroundUnitSyncInterval,
                            set: opsec.setBackgroundUnitSyncInterval
                        )
                    ) {
                        ForEach(BackgroundUnitSyncInterval.allCases) { interval in
                            Text(interval.label).tag(interval)
                        }
                    }
                    .disabled(!opsec.backgroundUnitSyncLocation)

                } header: {
                    Text(L10n.text("Unit Sync"))
                } footer: {
                    Text(L10n.text("Off by default. While the app is active, Unit Sync location remains near-real-time (about every 5 seconds); the selected interval affects only screen-off background updates. When enabled, a joined v3 room can continue sharing your encrypted position after the screen locks—but only while Share my location is also on. Background updates are best-effort, iOS shows its background-location indicator, and the room reconnects for a verified snapshot when you return. Track recording is controlled separately."))
                }

                Section {
                    Toggle(
                        L10n.text("Online place, terrain & weather lookups"),
                        isOn: settingBinding(\.onlineLookups, set: opsec.setOnlineLookups)
                    )
                } footer: {
                    Text(L10n.text("Off by default. Elevation and weather send the map-centre coordinate (coarsened to ~110 m) to Open-Meteo. The terrain heat-map sends a 24 × 24 coordinate grid (coarsened to ~11 m) covering the visible map area. Place-name search uses Apple's MKLocalSearch and may send the typed place-name or address query and camera region to Apple. Leave this off for a fully offline/OPSEC posture."))
                }

                Section {
                    Toggle(
                        L10n.text("Online basemap tiles"),
                        isOn: settingBinding(\.onlineBasemaps, set: opsec.setOnlineBasemaps)
                    )
                } footer: {
                    Text(L10n.text("Off by default. While off, no Esri or OpenTopoMap tile is requested.\n\nTacMap renders basemap imagery without Apple Maps or MKMapView network tiles.\n\nWhen this is on, the tile coordinates you view go to Esri / OpenTopoMap from your IP, which reveals your area of interest — the red banner shows while that's happening. For a fully dark posture, turn this off and use an imported offline pack, or fly with the radio off."))
                }

                Section {
                    Toggle(L10n.text("Require unlock to decrypt mission data"), isOn: Binding(
                        get: { authBound },
                        set: { setAuthBound($0) }
                    ))
                    if let keyError {
                        Text(keyError).font(.caption).foregroundStyle(.red)
                    }
                } footer: {
                    Text(L10n.text("Off: waypoints, drawings and tracks are encrypted with a key the Keychain releases to this app automatically after the first device unlock. The key does not migrate to another device, but a protected backup can restore it to the same device. Code running as this app on a compromised device may still ask the Keychain to decrypt.\n\nOn: the Keychain requires Face ID, Touch ID or your passcode before key use. This raises the bar after process death, but a fully compromised device remains outside the guarantee. After the app is killed nothing can read or write mission data until you unlock, including background track recording."))
                }
            }
            .navigationTitle(L10n.text("Settings, Privacy & OPSEC"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button(L10n.text("Done")) { dismiss() } } }
        }
    }

    /// Re-wraps the data key, it doesn't re-encrypt any files. Turning the
    /// toggle ON needs no prompt (the current key is unbound); turning it OFF
    /// reads the auth-bound key first, which is what raises Face ID.
    ///
    /// Reads the truth back off DataKey rather than trusting the Toggle, so a
    /// cancelled Face ID prompt snaps the switch back instead of lying.
    private func setAuthBound(_ enabled: Bool) {
        keyError = nil
        do {
            try DataKey.setAuthBound(enabled)
        } catch {
            keyError = error.localizedDescription
        }
        authBound = DataKey.isAuthBound
    }

    private func settingBinding<Value>(
        _ keyPath: KeyPath<OpsecSettings, Value>,
        set: @escaping (Value) -> Bool
    ) -> Binding<Value> {
        Binding(
            get: { opsec[keyPath: keyPath] },
            set: { _ = set($0) }
        )
    }
}
