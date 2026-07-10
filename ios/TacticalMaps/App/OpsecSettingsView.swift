import SwiftUI

/// Privacy / OPSEC settings: privacy screen, opt-in online lookups and
/// basemaps, at-rest key binding, and the self-hostable sync relay URL.
struct OpsecSettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var opsec = OpsecSettings.shared

    @State private var authBound = DataKey.isAuthBound
    @State private var keyError: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Toggle("Privacy screen in app switcher", isOn: $opsec.privacyScreen)
                } footer: {
                    Text("Covers the map (with your position) whenever the app isn't active, so it isn't captured in the app-switcher thumbnail.")
                }

                Section {
                    Toggle("Online terrain & weather lookups", isOn: $opsec.onlineLookups)
                } footer: {
                    Text("When on, the map-centre coordinate (coarsened to ~110 m) is sent to Open-Meteo for elevation, weather and the terrain heat-map. Off by default for OPSEC.")
                }

                Section {
                    Toggle("Online basemap tiles", isOn: $opsec.onlineBasemaps)
                } footer: {
                    Text("Off by default. While off the map only draws imported offline maps, and no tile request leaves the device. Turning it on lets Apple, Esri or OpenTopoMap see the ground you are looking at, from your IP.")
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
                    Off: waypoints, drawings and tracks are encrypted with a key the keychain releases to this app automatically. A filesystem dump or a backup gets ciphertext, but an attacker who jailbreaks the device and runs code as this app can still decrypt.

                    On: the secure hardware refuses the key without Face ID, Touch ID or your passcode. A jailbreak alone gets nothing. In exchange, after the app is killed nothing can read or write mission data until you unlock, and that includes background track recording.
                    """)
                }

                Section("Sync relay") {
                    TextField("Relay URL", text: $opsec.relayURL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }
            }
            .navigationTitle("Privacy & OPSEC")
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
}
