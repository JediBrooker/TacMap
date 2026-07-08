import SwiftUI

/// Privacy / operational-security settings: privacy screen, opt-in online
/// lookups, and the (self-hostable) sync relay URL.
struct OpsecSettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var opsec = OpsecSettings.shared

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
}
