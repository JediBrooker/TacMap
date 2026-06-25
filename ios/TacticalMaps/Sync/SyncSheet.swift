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
        case .connecting: return "Connecting…"
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
