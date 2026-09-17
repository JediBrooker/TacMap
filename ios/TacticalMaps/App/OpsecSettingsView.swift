import SwiftUI
import CoreLocation

/// General, privacy and OPSEC settings: map coordinate display, privacy screen,
/// independently switchable online lookups and basemaps, and at-rest key binding.
struct OpsecSettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var opsec = OpsecSettings.shared

    @State private var authBound = DataKey.isAuthBound
    @State private var keyError: String?
    @State private var relayDraft = OpsecSettings.shared.relayURL

    var body: some View {
        NavigationStack {
            Form {
                if let issue = opsec.persistenceIssue {
                    Section("Settings need attention") {
                        Text(issue)
                            .font(.caption)
                            .foregroundStyle(.red)
                    }
                }

                Section {
                    Picker(
                        "Primary map coordinate",
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
                    Text("Coordinate display")
                } footer: {
                    Text("Chooses the coordinate shown in the large green map readout. MGRS is the default.")
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
                        .accessibilityValue(opsec.mapOrientationMode == mode ? "Selected" : "Not selected")
                        .accessibilityAddTraits(opsec.mapOrientationMode == mode ? .isSelected : [])
                    }
                } header: {
                    Text("Map orientation")
                } footer: {
                    if CLLocationManager.headingAvailable() {
                        Text("North Up starts north-facing and keeps two-finger rotation available. Heading Up uses the phone compass to keep the direction you are pointing at the top of the map. The compass marks bearings T for true north, M when it falls back to magnetic north, and ? while waiting for a valid reading.")
                    } else {
                        Text("Heading Up is unavailable because this device does not report compass headings.")
                    }
                }

                Section {
                    Toggle(
                        "Privacy screen in app switcher",
                        isOn: settingBinding(\.privacyScreen, set: opsec.setPrivacyScreen)
                    )
                } footer: {
                    Text("Covers the map (with your position) whenever the app isn't active, so it isn't captured in the app-switcher thumbnail.")
                }

                Section {
                    Toggle(
                        "Background Unit Sync location",
                        isOn: settingBinding(
                            \.backgroundUnitSyncLocation,
                            set: opsec.setBackgroundUnitSyncLocation
                        )
                    )
                    Picker(
                        "Screen-off update interval",
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

                    TextField("Unit Sync relay", text: $relayDraft)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                        .submitLabel(.done)
                        .onSubmit(saveRelay)
                        .onChange(of: relayDraft) { _ in
                            opsec.clearRelayValidationIssue()
                        }
                    HStack {
                        Button("Save relay", action: saveRelay)
                            .disabled(relayDraft == opsec.relayURL)
                        Spacer()
                        Button("Use default", action: resetRelay)
                            .disabled(opsec.relayURL == OpsecSettings.defaultRelay &&
                                      relayDraft == OpsecSettings.defaultRelay)
                    }
                    if let issue = opsec.relayValidationIssue {
                        Text(issue)
                            .font(.caption)
                            .foregroundStyle(.red)
                    }
                } header: {
                    Text("Unit Sync")
                } footer: {
                    Text("Off by default. While the app is active, Unit Sync location remains near-real-time (about every 5 seconds); the selected interval affects only screen-off background updates. When enabled, a joined v3 room can continue sharing your encrypted position after the screen locks—but only while Share my location is also on. Background updates are best-effort, iOS shows its background-location indicator, and the room reconnects for a verified snapshot when you return. Track recording is controlled separately. Custom relays must use secure wss://. Debug builds also permit ws:// only on this device's loopback address.")
                }

                Section {
                    Toggle(
                        "Online place, terrain & weather lookups",
                        isOn: settingBinding(\.onlineLookups, set: opsec.setOnlineLookups)
                    )
                } footer: {
                    Text("Off by default. Elevation and weather send the map-centre coordinate (coarsened to ~110 m) to Open-Meteo. The terrain heat-map sends a 24 × 24 coordinate grid (coarsened to ~11 m) covering the visible map area. Place-name search uses Apple's MKLocalSearch and may send the typed place-name or address query and camera region to Apple. Leave this off for a fully offline/OPSEC posture.")
                }

                Section {
                    Toggle(
                        "Online basemap tiles",
                        isOn: settingBinding(\.onlineBasemaps, set: opsec.setOnlineBasemaps)
                    )
                } footer: {
                    Text("""
                    Off by default. While off, no Esri or OpenTopoMap tile is requested.

                    TacMap renders basemap imagery without Apple Maps or MKMapView network tiles.

                    When this is on, the tile coordinates you view go to Esri / OpenTopoMap from your IP, which reveals your area of interest — the red banner shows while that's happening. For a fully dark posture, turn this off and use an imported offline pack, or fly with the radio off.
                    """)
                }

                Section {
                    Toggle("Require unlock to decrypt mission data", isOn: Binding(
                        get: { authBound },
                        set: { setAuthBound($0) }
                    ))
                    if let keyError {
                        Text(keyError).font(.caption).foregroundStyle(.red)
                    }
                } footer: {
                    Text("""
                    Off: waypoints, drawings and tracks are encrypted with a key the Keychain releases to this app automatically after the first device unlock. The key does not migrate to another device, but a protected backup can restore it to the same device. Code running as this app on a compromised device may still ask the Keychain to decrypt.

                    On: the Keychain requires Face ID, Touch ID or your passcode before key use. This raises the bar after process death, but a fully compromised device remains outside the guarantee. After the app is killed nothing can read or write mission data until you unlock, including background track recording.
                    """)
                }
            }
            .navigationTitle("Settings, Privacy & OPSEC")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } } }
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

    private func saveRelay() {
        if opsec.setRelayURL(relayDraft) {
            relayDraft = opsec.relayURL
        }
    }

    private func resetRelay() {
        if opsec.resetRelayURL() {
            relayDraft = opsec.relayURL
        }
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
