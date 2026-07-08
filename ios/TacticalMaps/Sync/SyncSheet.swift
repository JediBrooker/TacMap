import SwiftUI

/// Join / create a unit sync room and show connection status.
struct SyncSheet: View {
    @ObservedObject var manager: SyncManager
    @Environment(\.dismiss) private var dismiss
    @State private var code = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    HStack(spacing: 10) {
                        Circle().fill(statusColor).frame(width: 10, height: 10)
                        Text(statusText)
                    }
                }

                if let room = manager.room {
                    Section("Room") {
                        Text(room).font(.system(.body, design: .monospaced))
                        Button(role: .destructive) {
                            manager.leave()
                        } label: {
                            Label("Leave room", systemImage: "xmark.circle")
                        }
                    }

                    // Identity section — visible only while connected to a room.
                    Section("Your Identity") {
                        TextField("Callsign", text: $manager.presenceConfig.callsign)
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.characters)

                        Picker("Affiliation", selection: $manager.presenceConfig.affiliation) {
                            Text("Friendly").tag("friend")
                            Text("Hostile").tag("hostile")
                            Text("Neutral").tag("neutral")
                            Text("Unknown").tag("unknown")
                        }
                        .pickerStyle(.menu)

                        Picker("Echelon", selection: $manager.presenceConfig.echelon) {
                            Text("Team / Crew").tag("team")
                            Text("Section").tag("section")
                            Text("Platoon").tag("platoon")
                            Text("Company").tag("company")
                            Text("Battalion / Regiment").tag("battalionRegiment")
                            Text("Brigade").tag("brigade")
                            Text("Division").tag("division")
                        }
                        .pickerStyle(.menu)

                        Picker("Function", selection: $manager.presenceConfig.function) {
                            Text("Infantry").tag("infantry")
                            Text("Armour").tag("armour")
                            Text("Artillery").tag("artillery")
                            Text("Cavalry").tag("cavalry")
                            Text("Engineer").tag("engineer")
                            Text("Signals").tag("signal")
                            Text("Medical").tag("medical")
                            Text("Reconnaissance").tag("recce")
                            Text("Mechanised Infantry").tag("mechInfantry")
                            Text("Motorised Infantry").tag("motorisedInfantry")
                            Text("Anti-Tank").tag("antiTank")
                            Text("Air Defence").tag("airDefence")
                            Text("Aviation (Rotary)").tag("aviation")
                            Text("Aviation (Fixed-Wing)").tag("aviationFixed")
                            Text("Mortar").tag("mortar")
                            Text("CBRN Defence").tag("cbrn")
                            Text("Electronic Warfare").tag("electronicWarfare")
                            Text("Special Forces").tag("specialForces")
                            Text("Military Police").tag("militaryPolice")
                            Text("Supply").tag("logistics")
                            Text("Maintenance").tag("maintenance")
                            Text("Transportation").tag("transportation")
                            Text("Combat Service Support").tag("css")
                        }
                        .pickerStyle(.menu)

                        Toggle("Headquarters", isOn: $manager.presenceConfig.isHQ)

                        Toggle("Share my location", isOn: $manager.presenceConfig.shareLocation)
                    }

                    if !manager.peers.isEmpty {
                        Section("Members Online (\(manager.peers.count))") {
                            ForEach(Array(manager.peers.values).sorted(by: { $0.callsign < $1.callsign })) { peer in
                                HStack {
                                    Text(peer.callsign.isEmpty ? peer.clientId.prefix(8) + "..." : peer.callsign)
                                        .font(.body)
                                    Spacer()
                                    Text(peer.affiliation.capitalized)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                } else {
                    Section("Join a unit room") {
                        TextField("Unit join code", text: $code)
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                        Button {
                            manager.join(code)
                        } label: {
                            Label("Join / create room", systemImage: "antenna.radiowaves.left.and.right")
                        }
                        .disabled(code.trimmingCharacters(in: .whitespaces).isEmpty)
                    }
                }

                Section {
                    Text("Everyone who enters the same code shares a live, end-to-end-encrypted map. The relay only ever sees ciphertext.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Unit Sync")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } } }
        }
    }

    private var statusText: String {
        switch manager.status {
        case .connected:  return "Connected"
        case .connecting: return "Connecting..."
        case .offline:    return "Offline"
        }
    }

    private var statusColor: Color {
        switch manager.status {
        case .connected:  return .green
        case .connecting: return .orange
        case .offline:    return .gray
        }
    }
}
