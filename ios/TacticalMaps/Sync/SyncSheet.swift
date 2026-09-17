import SwiftUI
import UIKit
import UniformTypeIdentifiers

enum UnitSyncJoinGate {
    static func requiresConsent(
        roomCode: String,
        shareLocation: Bool,
        backgroundLocation: Bool
    ) -> Bool {
        roomCode.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix("3:")
            && (!shareLocation || !backgroundLocation)
    }
}

private struct PendingUnitSyncJoin: Identifiable {
    let id = UUID()
    let code: String
    let roomName: String
}

/// Join / create a unit sync room and show connection status.
struct SyncSheet: View {
    @ObservedObject private var appLanguage = AppLanguage.shared
    @ObservedObject var manager: SyncManager
    let onOpenChat: (TacMapChatRoute) -> Void
    @ObservedObject private var opsec = OpsecSettings.shared
    @Environment(\.dismiss) private var dismiss
    @State private var code = ""
    @State private var roomName = ""
    @State private var codeError: LocalizedMessage?
    @State private var legacyConfirmed = false
    @State private var roomCodeCopied = false
    @State private var copyFeedbackToken = UUID()
    @State private var pendingJoin: PendingUnitSyncJoin?

    init(manager: SyncManager,
         onOpenChat: @escaping (TacMapChatRoute) -> Void = { _ in }) {
        self.manager = manager
        self.onOpenChat = onOpenChat
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    HStack(spacing: 10) {
                        Circle().fill(statusColor).frame(width: 10, height: 10)
                        Text(statusText)
                    }
                }

                if let error = manager.lastError {
                    Section(L10n.text("Sync needs attention")) {
                        Text(error)
                            .font(.caption)
                            .foregroundStyle(.red)
                        Button(L10n.text("Dismiss error")) { manager.acknowledgeLastError() }
                    }
                }

                if let room = manager.room {
                    Section {
                        TextField(L10n.text("Room name"), text: Binding(
                            get: { roomName },
                            set: {
                                roomName = manager.boundedRoomName($0)
                                manager.updateRoomName(roomName)
                            }
                        ))
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.words)
                        Button {
                            copyRoomCode(room)
                        } label: {
                            HStack {
                                Text(room)
                                    .font(.system(.body, design: .monospaced))
                                    .foregroundStyle(.primary)
                                Spacer()
                                Label(
                                    roomCodeCopied ? L10n.text("Copied") : L10n.text("Copy"),
                                    systemImage: roomCodeCopied ? "checkmark.circle.fill" : "doc.on.doc"
                                )
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(roomCodeCopied ? .green : .secondary)
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(L10n.text("Unit room code %1$@", room))
                        .accessibilityHint(L10n.text("Copies the unit room code to the clipboard"))
                        .accessibilityValue(roomCodeCopied ? L10n.text("Copied") : "")
                        if room.hasPrefix("2:") {
                            Text(L10n.text("LEGACY ROOM: weaker replay, identity, and metadata protections."))
                                .font(.caption.bold()).foregroundStyle(.red)
                        }
                        Button(role: .destructive) {
                            manager.leave()
                        } label: {
                            Label(L10n.text("Leave room"), systemImage: "xmark.circle")
                        }
                    } header: {
                        Text(manager.roomName ?? L10n.text("Room"))
                    } footer: {
                        Text(L10n.text("The room name is encrypted on this device only. It is never sent to the relay or included in the join code."))
                            .font(.caption2)
                    }

                    // Identity section, only visible while connected to a room.
                    Section(L10n.text("Your Identity")) {
                        TextField(L10n.text("Callsign"), text: Binding(
                            get: { manager.presenceConfig.callsign },
                            set: {
                                var updated = manager.presenceConfig
                                updated.callsign = manager.boundedCallsign($0)
                                _ = manager.updatePresenceConfig(updated)
                            }
                        ))
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.characters)

                        Picker(L10n.text("Affiliation"), selection: presenceBinding(\.affiliation)) {
                            Text(L10n.text("Friendly")).tag("friend")
                            Text(L10n.text("Hostile")).tag("hostile")
                            Text(L10n.text("Neutral")).tag("neutral")
                            Text(L10n.text("Unknown")).tag("unknown")
                        }
                        .pickerStyle(.menu)

                        Picker(L10n.text("Echelon"), selection: presenceBinding(\.echelon)) {
                            Text(L10n.text("Team / Crew")).tag("team")
                            Text(L10n.text("Section")).tag("section")
                            Text(L10n.text("Platoon")).tag("platoon")
                            Text(L10n.text("Company")).tag("company")
                            Text(L10n.text("Battalion / Regiment")).tag("battalionRegiment")
                            Text(L10n.text("Brigade")).tag("brigade")
                            Text(L10n.text("Division")).tag("division")
                        }
                        .pickerStyle(.menu)

                        Picker(L10n.text("Function"), selection: presenceBinding(\.function)) {
                            ForEach(PresenceConfig.functionChoices, id: \.self) { function in
                                Text(function.displayName).tag(function.rawValue)
                            }
                        }
                        .pickerStyle(.menu)

                        Toggle(L10n.text("Headquarters"), isOn: presenceBinding(\.isHQ))

                        Toggle(L10n.text("Share my location"), isOn: presenceBinding(\.shareLocation))
                        Text(L10n.text("Screen-off sharing is controlled separately in Settings, Privacy & OPSEC."))
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }

                } else {
                    Section {
                        TextField(L10n.text("Room name (this device)"), text: Binding(
                            get: { roomName },
                            set: { roomName = manager.boundedRoomName($0) }
                        ))
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.words)
                        TextField(L10n.text("Unit join code"), text: $code)
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                            .onChange(of: code) { _ in codeError = nil; legacyConfirmed = false }
                        Button {
                            code = SyncCrypto.generateJoinCode()
                            codeError = nil
                            legacyConfirmed = false
                        } label: {
                            Label(L10n.text("Generate strong code"), systemImage: "wand.and.stars")
                        }
                        Button {
                            attemptJoin()
                        } label: {
                            Label(L10n.text("Join / create room"), systemImage: "antenna.radiowaves.left.and.right")
                        }
                        .disabled(code.trimmingCharacters(in: .whitespaces).isEmpty)
                        if let codeError {
                            Text(codeError.text).font(.caption).foregroundStyle(.red)
                        }
                        if code.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix("2:") {
                            Text(L10n.text("LEGACY ROOM: weaker replay, identity, and metadata protections."))
                                .font(.caption.bold()).foregroundStyle(.red)
                        }
                    } header: {
                        Text(L10n.text("Join or create a unit room"))
                    } footer: {
                        Text(L10n.text("Room names are local, encrypted display labels. Other members can use their own name for the same code."))
                            .font(.caption2)
                    }
                }

                if manager.room?.hasPrefix("2:") == true {
                    Section(L10n.text("Legacy v2 room membership")) {
                        Text(L10n.text("Authenticated online membership is unavailable in legacy v2 rooms. Upgrade every device to a v3 room for relay-reported signed sessions."))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                } else {
                    Section(L10n.text("Relay-reported sessions (%1$@)", manager.onlineMembers.count)) {
                        if manager.onlineMembers.isEmpty {
                            Text(manager.status == .connected
                                 ? L10n.text("No other sessions currently reported by the relay")
                                 : L10n.text("Join a v3 room to see relay-reported signed sessions"))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        } else {
                            ForEach(manager.onlineMembers.values.sorted {
                                if $0.displayName.localizedCaseInsensitiveCompare($1.displayName) != .orderedSame {
                                    return $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending
                                }
                                return $0.clientId < $1.clientId
                            }) { member in
                                memberEntry(member)
                            }
                        }
                        Text(L10n.text("Identity and session signatures are verified, but connection liveness is relay-attested; it is not cryptographic proof that a peer is currently online and remains subject to the replay/rollback caveat below."))
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }

                Section(L10n.text("Shared map locations (%1$@)", manager.peers.count)) {
                    if manager.peers.isEmpty {
                        Text(manager.status == .connected
                             ? L10n.text("No map locations received")
                             : L10n.text("Map locations appear while connected"))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(manager.peers.values.sorted {
                            let left = $0.callsign.isEmpty ? L10n.text("Unnamed location") : $0.callsign
                            let right = $1.callsign.isEmpty ? L10n.text("Unnamed location") : $1.callsign
                            if left.localizedCaseInsensitiveCompare(right) != .orderedSame {
                                return left.localizedCaseInsensitiveCompare(right) == .orderedAscending
                            }
                            return $0.clientId < $1.clientId
                        }) { peer in
                            peerEntry(peer)
                        }
                    }
                }

                Section {
                    Text(L10n.text("Mission payload content is end-to-end encrypted. The relay still sees connection, routing, session, timing, size, and traffic metadata."))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Text(L10n.text("Replay protection only rejects signed sessions at or below epochs this device has already stored. Detecting an obsolete but previously unseen higher session requires external verification."))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle(L10n.text("Unit Sync"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button(L10n.text("Done")) { dismiss() } } }
            .onAppear {
                if manager.room != nil { roomName = manager.roomName ?? "" }
            }
            .onChange(of: manager.room) { activeRoom in
                if activeRoom != nil { roomName = manager.roomName ?? roomName }
            }
            .alert(item: $pendingJoin) { pending in
                Alert(
                    title: Text(L10n.text("Enable location sharing?")),
                    message: Text(joinConsentMessage),
                    primaryButton: .default(Text(L10n.text("Enable & Join"))) {
                        guard opsec.setBackgroundUnitSyncLocation(true) else {
                            codeError = opsec.persistenceMessage
                                ?? Messages.syncFormCouldNotSaveBackgroundUnitSyncConsentMessage()
                            pendingJoin = nil
                            return
                        }
                        var updated = manager.presenceConfig
                        updated.shareLocation = true
                        guard manager.updatePresenceConfig(updated) else {
                            _ = opsec.setBackgroundUnitSyncLocation(false)
                            codeError = manager.lastErrorMessage
                                ?? Messages.syncFormCouldNotSaveLocationSharingConsentMessage()
                            pendingJoin = nil
                            return
                        }
                        manager.join(pending.code, roomName: pending.roomName)
                        pendingJoin = nil
                    },
                    secondaryButton: .cancel {
                        pendingJoin = nil
                    }
                )
            }
        }
    }

    private func attemptJoin() {
        let trimmed = code.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.hasPrefix("3:") && !trimmed.hasPrefix("2:") {
            codeError = Messages.syncFormCodesMustStartWith3Enter2OnlyForAnMessage()
        } else if trimmed.hasPrefix("2:") && !legacyConfirmed {
            legacyConfirmed = true
            codeError = Messages.syncFormLegacyV2HasWeakerRollbackAndIdentityProtectionTapAgainMessage()
        } else if SyncCrypto.isJoinCodeTooWeak(code) {
            codeError = Messages.syncFormTooShortToBeSafeUseAtLeast1CharactersMessage(String(SyncCrypto.minJoinCodeLength))
        } else if UnitSyncJoinGate.requiresConsent(
            roomCode: trimmed,
            shareLocation: manager.presenceConfig.shareLocation,
            backgroundLocation: opsec.backgroundUnitSyncLocation
        ) {
            codeError = nil
            pendingJoin = PendingUnitSyncJoin(code: trimmed, roomName: roomName)
        } else {
            manager.join(trimmed, roomName: roomName)
        }
    }

    private var joinConsentMessage: String {
        let cadence = opsec.backgroundUnitSyncInterval.label.lowercased()
        return L10n.text("To join, TacMap will enable Share my location and Background Unit Sync location. When Location access is allowed, your encrypted position will be sent while the app is open and approximately %1$@ while the screen is off.", cadence)
    }

    private func presenceBinding<Value>(
        _ keyPath: WritableKeyPath<PresenceConfig, Value>
    ) -> Binding<Value> {
        Binding(
            get: { manager.presenceConfig[keyPath: keyPath] },
            set: { value in
                var updated = manager.presenceConfig
                updated[keyPath: keyPath] = value
                _ = manager.updatePresenceConfig(updated)
            }
        )
    }

    private var statusText: String {
        switch manager.status {
        case .connected:  return L10n.text("Connected")
        case .snapshotting: return L10n.text("Authenticating snapshot...")
        case .connecting: return L10n.text("Connecting...")
        case .offline:    return L10n.text("Offline")
        }
    }

    private var statusColor: Color {
        switch manager.status {
        case .connected:  return .green
        case .snapshotting: return .orange
        case .connecting: return .orange
        case .offline:    return .gray
        }
    }

    private func memberDetail(_ member: OnlineMember) -> String {
        let function = member.function.flatMap { SymbolFunction(rawValue: $0)?.displayName } ?? member.function
        let values = [member.affiliation?.capitalized, function, member.echelon?.capitalized]
            .compactMap { $0 }
            .filter { !$0.isEmpty }
        return values.isEmpty ? L10n.text("Location sharing off") : values.joined(separator: " • ")
    }

    private func peerDetail(_ peer: PresencePeer) -> String {
        let function = SymbolFunction(rawValue: peer.function)?.displayName ?? peer.function
        return [peer.affiliation.capitalized, function, peer.echelon.capitalized]
            .filter { !$0.isEmpty }
            .joined(separator: " • ")
    }

    @ViewBuilder
    private func memberEntry(_ member: OnlineMember) -> some View {
        if let recipient = manager.chatRecipients[member.clientId] {
            Button {
                onOpenChat(.direct(recipient))
            } label: {
                HStack {
                    memberLabel(member, fingerprint: recipient.shortFingerprint)
                    Spacer()
                    Image(systemName: "bubble.left.fill")
                        .foregroundStyle(.blue)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(L10n.text("Message %1$@", recipient.displayLabel))
            .accessibilityHint(L10n.text("Opens an end-to-end encrypted selected-unit chat"))
        } else {
            memberLabel(member, fingerprint: String(member.clientId.suffix(6)).uppercased())
        }
    }

    private func memberLabel(_ member: OnlineMember, fingerprint: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(L10n.text("%1$@ · %2$@", member.displayName, fingerprint))
                .font(.body.weight(.semibold))
            Text(memberDetail(member))
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    @ViewBuilder
    private func peerEntry(_ peer: PresencePeer) -> some View {
        if let recipient = manager.chatRecipients[peer.clientId] {
            Button {
                onOpenChat(.direct(recipient))
            } label: {
                HStack {
                    peerLabel(peer, fingerprint: recipient.shortFingerprint)
                    Spacer()
                    Image(systemName: "bubble.left.fill")
                        .foregroundStyle(.blue)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(L10n.text("Message %1$@", recipient.displayLabel))
            .accessibilityHint(L10n.text("Opens an end-to-end encrypted selected-unit chat"))
        } else {
            peerLabel(peer, fingerprint: String(peer.clientId.suffix(6)).uppercased())
        }
    }

    private func peerLabel(_ peer: PresencePeer, fingerprint: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(L10n.text("%1$@ · %2$@", peer.callsign.isEmpty ? "Unnamed location" : peer.callsign, fingerprint))
                .font(.body.weight(.semibold))
            Text(L10n.text("Last map position • %1$@", peerDetail(peer)))
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    private func copyRoomCode(_ room: String) {
        UIPasteboard.general.setItems(
            [[UTType.plainText.identifier: room]],
            options: [
                .expirationDate: Date().addingTimeInterval(120),
                .localOnly: true
            ]
        )
        UINotificationFeedbackGenerator().notificationOccurred(.success)

        let token = UUID()
        copyFeedbackToken = token
        withAnimation { roomCodeCopied = true }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
            guard copyFeedbackToken == token else { return }
            withAnimation { roomCodeCopied = false }
        }
    }
}
