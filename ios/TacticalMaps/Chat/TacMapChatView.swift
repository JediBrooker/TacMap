import SwiftUI

struct TacMapChatView: View {
    @ObservedObject var manager: SyncManager
    @ObservedObject var store: TacMapChatStore
    @Environment(\.dismiss) private var dismiss

    @State private var scope: TacMapChatScope
    /// Immutable actor + session + chat-key tuple captured at selection time.
    /// A reconnect invalidates it and forces an explicit re-selection instead
    /// of silently redirecting a draft to a replacement session.
    @State private var selectedRecipientSnapshot: TacMapChatRecipient?
    @State private var bodyText = ""
    @State private var pendingRoomBroadcast = false
    @State private var sendIssue: String?

    init(manager: SyncManager,
         store: TacMapChatStore,
         initialRoute: TacMapChatRoute) {
        self.manager = manager
        self.store = store
        switch initialRoute {
        case .room:
            _scope = State(initialValue: .room)
            _selectedRecipientSnapshot = State(initialValue: nil)
        case .direct(let recipient):
            _scope = State(initialValue: .direct)
            _selectedRecipientSnapshot = State(initialValue: recipient)
        }
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                recipientControls
                Divider()
                history
                Divider()
                composer
            }
            .navigationTitle("TacMap Chat")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
            .confirmationDialog(
                "Send to the entire room?",
                isPresented: $pendingRoomBroadcast,
                titleVisibility: .visible
            ) {
                Button(roomBroadcastConfirmationTitle) { performSend(scope: .room) }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("This routes the message to every currently chat-ready unit. It will not be private to one selected unit.")
            }
            .alert("TacMap Chat", isPresented: Binding(
                get: { sendIssue != nil },
                set: { if !$0 { sendIssue = nil } }
            )) {
                Button("OK", role: .cancel) { sendIssue = nil }
            } message: {
                Text(sendIssue ?? "The message was not sent.")
            }
            .onChange(of: store.isLocked) { locked in
                if locked { clearPlaintextAndDismiss() }
            }
            .onAppear { acknowledgeVisibleConversation() }
            .onChange(of: scope) { _ in acknowledgeVisibleConversation() }
            .onChange(of: selectedRecipientSnapshot) { _ in
                acknowledgeVisibleConversation()
            }
            .onDisappear {
                bodyText = ""
                selectedRecipientSnapshot = nil
                sendIssue = nil
                pendingRoomBroadcast = false
            }
        }
    }

    private var liveRecipients: [TacMapChatRecipient] {
        manager.chatRecipients.values.sorted {
            if $0.displayName.localizedCaseInsensitiveCompare($1.displayName) != .orderedSame {
                return $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending
            }
            return $0.actorId < $1.actorId
        }
    }

    /// Keep encrypted direct history reachable after a peer goes offline. A
    /// history-only choice deliberately has no usable session/key tuple, so
    /// `selectedRecipient` remains nil and sending stays disabled until the
    /// user selects a current authenticated endpoint.
    private var historicalDirectRecipients: [TacMapChatRecipient] {
        var displayNames: [String: String] = [:]
        for message in store.messages where message.scope == .direct {
            guard let actorId = message.conversationActorId,
                  manager.chatRecipients[actorId] == nil else { continue }
            let candidate = message.isOutgoing
                ? (message.recipientName ?? "") : message.senderName
            if !candidate.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                || displayNames[actorId] == nil {
                displayNames[actorId] = candidate
            }
        }
        return displayNames.map { actorId, displayName in
            TacMapChatRecipient(
                actorId: actorId,
                displayName: displayName,
                sessionDomain: "",
                keyId: ""
            )
        }
    }

    private var recipientChoices: [TacMapChatRecipient] {
        (liveRecipients + historicalDirectRecipients).sorted {
            if $0.displayName.localizedCaseInsensitiveCompare($1.displayName) != .orderedSame {
                return $0.displayName.localizedCaseInsensitiveCompare($1.displayName)
                    == .orderedAscending
            }
            return $0.actorId < $1.actorId
        }
    }

    private var selectedRecipient: TacMapChatRecipient? {
        guard let selectedRecipientSnapshot,
              let current = manager.chatRecipients[selectedRecipientSnapshot.actorId],
              current.actorId == selectedRecipientSnapshot.actorId,
              current.sessionDomain == selectedRecipientSnapshot.sessionDomain,
              current.keyId == selectedRecipientSnapshot.keyId else {
            return nil
        }
        // Retain the name that was visible when the immutable endpoint was
        // selected. A callsign rename does not silently replace the target.
        return selectedRecipientSnapshot
    }

    private var visibleMessages: [TacMapChatMessage] {
        store.history(scope: scope, directActorId: selectedRecipientSnapshot?.actorId)
    }

    @ViewBuilder
    private var recipientControls: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("RECIPIENT")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)

            Picker("Recipient scope", selection: $scope) {
                Label("Entire room", systemImage: "dot.radiowaves.left.and.right")
                    .tag(TacMapChatScope.room)
                Label("Selected unit", systemImage: "person.crop.circle")
                    .tag(TacMapChatScope.direct)
            }
            .pickerStyle(.segmented)
            .accessibilityHint("Choose whether this message is routed to the whole room or only one unit")

            if scope == .room {
                Label(
                    "Broadcast to every chat-ready unit",
                    systemImage: "exclamationmark.triangle.fill"
                )
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.orange)
            } else if recipientChoices.isEmpty {
                Label("No chat-ready units or direct history are available",
                      systemImage: "person.crop.circle.badge.xmark")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            } else {
                Picker("Selected unit", selection: $selectedRecipientSnapshot) {
                    Text("Choose a unit").tag(TacMapChatRecipient?.none)
                    ForEach(recipientChoices) { recipient in
                        Text(recipientChoiceLabel(recipient)).tag(Optional(recipient))
                    }
                }
                .pickerStyle(.menu)
                .accessibilityValue(selectedRecipientSnapshot.map(recipientChoiceLabel)
                    ?? "No current unit session selected")
                if let selectedRecipientSnapshot, selectedRecipient == nil {
                    Label(manager.chatRecipients[selectedRecipientSnapshot.actorId] == nil
                          ? "This unit is offline. You can read its encrypted history, but sending requires a live secure session."
                          : "That unit's secure session changed. Select it again; this draft was not redirected.",
                          systemImage: manager.chatRecipients[selectedRecipientSnapshot.actorId] == nil
                            ? "clock.arrow.circlepath" : "arrow.triangle.2.circlepath")
                        .font(.caption)
                        .foregroundStyle(.orange)
                }
            }

            if let availability = manager.chatAvailabilityMessage {
                Label(availability, systemImage: "lock.trianglebadge.exclamationmark")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(scope == .room ? Color.orange.opacity(0.08) : Color.clear)
    }

    @ViewBuilder
    private var history: some View {
        if visibleMessages.isEmpty {
            VStack(spacing: 10) {
                Image(systemName: scope == .room ? "bubble.left.and.bubble.right" : "person.line.dotted.person")
                    .font(.system(size: 32))
                    .foregroundStyle(.secondary)
                Text(scope == .room ? "No room messages yet" : "No messages with this unit yet")
                    .font(.headline)
                Text("Chat is live-only. Messages missed while a unit is offline are not recovered by the relay.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 28)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 10) {
                        ForEach(visibleMessages) { message in
                            messageRow(message)
                                .id(message.id)
                        }
                    }
                    .padding(14)
                }
                .onAppear { scrollToLatest(proxy) }
                .onChange(of: visibleMessages.last?.id) { _ in
                    scrollToLatest(proxy)
                    acknowledgeVisibleConversation()
                }
            }
        }
    }

    private var composer: some View {
        VStack(spacing: 10) {
            HStack(alignment: .bottom, spacing: 10) {
                TextField(
                    "Message",
                    text: Binding(
                        get: { bodyText },
                        set: { bodyText = TacMapChatPayload.boundedBody($0) }
                    ),
                    axis: .vertical
                )
                .lineLimit(1...5)
                .textFieldStyle(.roundedBorder)
                .accessibilityHint("Type an encrypted chat message")

                Button(action: reviewOrSend) {
                    Image(systemName: scope == .room ? "dot.radiowaves.left.and.right" : "arrow.up.circle.fill")
                        .font(.system(size: 30))
                        .foregroundStyle(scope == .room ? .orange : .blue)
                }
                .buttonStyle(.plain)
                .disabled(!canSend)
                .opacity(canSend ? 1 : 0.4)
                .accessibilityLabel(scope == .room ? "Review room send" : "Send to selected unit")
            }

            HStack {
                Spacer()
                Text("Routed means accepted by the relay, not delivered or read.")
            }
            .font(.caption2)
            .foregroundStyle(.secondary)
        }
        .padding(12)
        .background(.thinMaterial)
    }

    private var canSend: Bool {
        guard manager.chatSessionReady,
              !bodyText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return false }
        switch scope {
        case .room:
            return !liveRecipients.isEmpty
        case .direct:
            return selectedRecipient != nil
        }
    }

    private var roomBroadcastConfirmationTitle: String {
        let count = liveRecipients.count
        return "Send to \(count) unit\(count == 1 ? "" : "s")"
    }

    private func recipientChoiceLabel(_ recipient: TacMapChatRecipient) -> String {
        let historyOnly = manager.chatRecipients[recipient.actorId] == nil
            ? " · history only" : ""
        let unread = store.unreadCount(scope: .direct, directActorId: recipient.actorId)
        let unreadLabel = unread > 0
            ? " · \(unread) unread message\(unread == 1 ? "" : "s")" : ""
        return recipient.displayLabel + historyOnly + unreadLabel
    }

    private func acknowledgeVisibleConversation() {
        _ = try? store.markRead(
            scope: scope,
            directActorId: selectedRecipientSnapshot?.actorId
        )
    }

    private func reviewOrSend() {
        if scope == .room {
            pendingRoomBroadcast = true
        } else {
            performSend(scope: .direct)
        }
    }

    private func performSend(scope: TacMapChatScope) {
        let trimmed = bodyText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        do {
            try manager.sendChat(
                body: trimmed,
                kind: .text,
                scope: scope,
                recipient: scope == .direct ? selectedRecipient : nil
            )
            bodyText = ""
        } catch {
            sendIssue = error.localizedDescription
        }
    }

    private func messageRow(_ message: TacMapChatMessage) -> some View {
        HStack {
            if message.isOutgoing { Spacer(minLength: 42) }
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Text("\(message.senderName.isEmpty ? "Unknown unit" : message.senderName) · \(String(message.senderActorId.suffix(6)).uppercased())")
                        .font(.caption.weight(.semibold))
                    if message.kind == .report {
                        Label("Report", systemImage: "doc.text.fill")
                            .font(.caption2.weight(.semibold))
                            .foregroundStyle(.orange)
                    }
                }
                Text(message.body)
                    .font(.body)
                    .textSelection(.enabled)
                HStack(spacing: 5) {
                    Text(message.sentAt, format: .dateTime.hour().minute())
                    if message.isOutgoing {
                        Text(deliveryLabel(message))
                    }
                }
                .font(.caption2)
                .foregroundStyle(message.deliveryState == .failed ? .red : .secondary)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 9)
            .background(
                message.isOutgoing ? Color.blue.opacity(0.15) : Color.secondary.opacity(0.12),
                in: RoundedRectangle(cornerRadius: 12)
            )
            if !message.isOutgoing { Spacer(minLength: 42) }
        }
    }

    private func deliveryLabel(_ message: TacMapChatMessage) -> String {
        switch message.deliveryState {
        case .sending: return "Sending…"
        case .routed: return message.scope == .room ? "Sent to room" : "Routed"
        case .received: return "Received"
        case .failed: return "Not routed"
        }
    }

    private func scrollToLatest(_ proxy: ScrollViewProxy) {
        guard let last = visibleMessages.last else { return }
        DispatchQueue.main.async {
            withAnimation { proxy.scrollTo(last.id, anchor: .bottom) }
        }
    }

    private func clearPlaintextAndDismiss() {
        bodyText = ""
        selectedRecipientSnapshot = nil
        sendIssue = nil
        pendingRoomBroadcast = false
        dismiss()
    }
}
