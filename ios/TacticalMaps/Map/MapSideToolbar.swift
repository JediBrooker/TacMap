import SwiftUI

/// Top-left hamburger. Custom popover instead of SwiftUI's system Menu
/// b/c we need full-width 54pt buttons with 28pt icons - system Menu
/// rows were too hard to tap on a real phone.
struct HamburgerMenu: View {
    @ObservedObject private var appLanguage = AppLanguage.shared
    /// Billing: show trial status + an unlock entry point (hidden once bought).
    let isPurchased: Bool
    let trialDaysRemaining: Int
    let onUnlock:        () -> Void
    let onSearch:        () -> Void
    let onWaypoints:     () -> Void
    let onDrawings:      () -> Void
    let onLayers:        () -> Void
    let onMeasure:       () -> Void
    let onWeather:       () -> Void
    let onImport:        () -> Void
    let onImportTiles:   () -> Void
    let onImportGeoJSON: () -> Void
    let onImportKML:     () -> Void
    let onExport:        () -> Void
    /// GPX track recording state + actions.
    let recordingState: RecordingCoordinator.State
    let trackPointCount: Int
    let onToggleTrackRecording: () -> Void
    let onExportGPX:     () -> Void
    let onExportAll:     () -> Void
    let onChat:          () -> Void
    let onSync:          () -> Void
    let onAppLock:       () -> Void
    let onOpsec:         () -> Void
    let onAbout:         () -> Void

    @State private var isOpen = false
    /// Stashed action from menu row. Fires from onDismiss so it
    /// doesn't race the dismiss animation.
    @State private var pendingAction: (() -> Void)?

    var body: some View {
        Button {
            isOpen.toggle()
        } label: {
            Image(systemName: "line.3.horizontal")
                .font(.system(size: 19, weight: .medium))
                /// 48pt clears Apple's 44pt min tap target, matches the
                /// surrounding HUD chips (compass, centre button).
                .frame(width: 48, height: 48)
                .background(.black.opacity(0.78), in: Circle())
                .overlay(Circle().stroke(.white.opacity(0.08)))
                .foregroundStyle(.white)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(L10n.text("Menu"))
        .accessibilityIdentifier("map.menu")
        /// Large detent only - medium sheet clips the bottom rows on
        /// shorter iPhones. ScrollView so everything's reachable.
        .sheet(isPresented: $isOpen, onDismiss: runPendingAction) {
            NavigationStack {
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        if !isPurchased {
                            trialBanner
                            row(L10n.text("Unlock Full Version"), systemImage: "cart") { close(onUnlock) }
                            divider
                        }
                        row(L10n.text("Search…"),         systemImage: "magnifyingglass")     { close(onSearch) }
                        divider
                        row(L10n.text("Symbology"),       systemImage: "mappin.and.ellipse")  { close(onWaypoints) }
                        row(L10n.text("Drawings"),        systemImage: "scribble.variable")   { close(onDrawings) }
                        row(L10n.text("Layers and Labels"), systemImage: "square.3.stack.3d") { close(onLayers) }
                        row(L10n.text("Measure"),         systemImage: "ruler")               { close(onMeasure) }
                        row(L10n.text("Weather & UAV Safety"), systemImage: "wind")            { close(onWeather) }
                        divider
                        // All file import/export lives behind one row so the
                        // main menu stays short. Pushes a sub-page within the
                        // sheet's NavigationStack (no sheet-over-sheet races).
                        navRow(L10n.text("Import / Export…"), systemImage: "square.and.arrow.up.on.square")
                        divider
                        row(recordingMenuTitle,
                            systemImage: recordingMenuIcon)
                            { close(onToggleTrackRecording) }
                        divider
                        row(L10n.text("TacMap Chat…"), systemImage: "bubble.left.and.bubble.right.fill") { close(onChat) }
                        row(L10n.text("Unit Sync…"), systemImage: "antenna.radiowaves.left.and.right") { close(onSync) }
                        row(L10n.text("App Lock…"), systemImage: "lock.shield")               { close(onAppLock) }
                        row(L10n.text("Settings, Privacy & OPSEC"), systemImage: "eye.slash.fill") { close(onOpsec) }
                            .accessibilityIdentifier("menu.settings")
                        row(L10n.text("About & Credits"), systemImage: "info.circle")         { close(onAbout) }
                    }
                }
                .navigationTitle(L10n.text("Menu"))
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button(L10n.text("Close")) { isOpen = false }
                    }
                }
            }
            .presentationDetents([.large])
            .presentationDragIndicator(.visible)
            .padSheetSizing()
        }
    }

    /// Stash action and dismiss. Action fires from onDismiss after
    /// sheet is fully gone so file-importer sheets dont race it.
    private func close(_ action: @escaping () -> Void) {
        pendingAction = action
        isOpen = false
    }

    private func runPendingAction() {
        let action = pendingAction
        pendingAction = nil
        action?()
    }

    private var recordingMenuTitle: String {
        switch recordingState {
        case .idle:
            return L10n.text("Start Track Recording")
        case .awaitingPermission:
            return L10n.text("Cancel Track Recording Start")
        case .starting:
            return L10n.text("Starting Track Recording…")
        case .recording:
            return L10n.text("Stop Track Recording (%1$@ pts)", trackPointCount)
        case .interrupted:
            return L10n.text("Retry Track Recording")
        }
    }

    private var recordingMenuIcon: String {
        switch recordingState {
        case .recording:
            return "stop.circle.fill"
        case .awaitingPermission, .starting:
            return "hourglass.circle"
        case .interrupted:
            return "exclamationmark.circle"
        case .idle:
            return "record.circle"
        }
    }

    @ViewBuilder
    private func row(
        _ label: String,
        systemImage: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 14) {
                Image(systemName: systemImage)
                    .font(.system(size: 18, weight: .medium))
                    .foregroundStyle(.primary)
                    .frame(width: 28, alignment: .center)
                Text(label)
                    .font(.system(size: 16))
                    .foregroundStyle(.primary)
                Spacer()
            }
            .padding(.horizontal, 16)
            /// 54pt tall, clears Apple's 44pt min with room to spare.
            .frame(height: 54)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    /// Like row() but pushes the Import/Export sub-page instead of
    /// running an action. Has trailing chevron.
    @ViewBuilder
    private func navRow(_ label: String, systemImage: String) -> some View {
        NavigationLink {
            importExportPage
        } label: {
            HStack(spacing: 14) {
                Image(systemName: systemImage)
                    .font(.system(size: 18, weight: .medium))
                    .foregroundStyle(.primary)
                    .frame(width: 28, alignment: .center)
                Text(label)
                    .font(.system(size: 16))
                    .foregroundStyle(.primary)
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(.tertiary)
            }
            .padding(.horizontal, 16)
            .frame(height: 54)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    /// Import/Export sub-page. Rows reuse close() so picking one
    /// dismisses the whole sheet, same as top-level rows.
    @ViewBuilder
    private var importExportPage: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                sectionHeader(L10n.text("Import"))
                row(L10n.text("PDF Map…"), systemImage: "doc.badge.plus")               { close(onImport) }
                row(L10n.text("Offline Tiles…"), systemImage: "square.stack.3d.up.fill") { close(onImportTiles) }
                row(L10n.text("GeoJSON…"), systemImage: "square.and.arrow.down")         { close(onImportGeoJSON) }
                row("KML / KMZ…", systemImage: "globe.desk")                  { close(onImportKML) }
                divider
                sectionHeader(L10n.text("Export"))
                row(L10n.text("GeoJSON…"), systemImage: "square.and.arrow.up")           { close(onExport) }
                row("\(MissionObjectExport.actionTitle)…", systemImage: "square.and.arrow.up.on.square") { close(onExportAll) }
                row(L10n.text("GPX Track…"), systemImage: "point.topleft.down.curvedto.point.bottomright.up")
                    { close(onExportGPX) }
            }
        }
        .navigationTitle(L10n.text("Import / Export"))
        .navigationBarTitleDisplayMode(.inline)
    }

    private func sectionHeader(_ title: String) -> some View {
        Text(title.uppercased())
            .font(.system(size: 12, weight: .semibold))
            .foregroundStyle(.secondary)
            .padding(.horizontal, 16)
            .padding(.top, 18)
            .padding(.bottom, 6)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var divider: some View {
        Rectangle()
            .fill(Color.gray.opacity(0.25))
            .frame(height: 0.5)
    }

    /// Non-interactive status line above the Unlock row.
    private var trialBanner: some View {
        HStack(spacing: 14) {
            Image(systemName: "clock.badge.checkmark")
                .font(.system(size: 18, weight: .medium))
                .foregroundStyle(.secondary)
                .frame(width: 28, alignment: .center)
            Text(trialDaysRemaining > 0
                 ? L10n.quantity("trial_remaining", trialDaysRemaining)
                 : L10n.text("Free trial ended"))
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(.secondary)
            Spacer()
        }
        .padding(.horizontal, 16)
        .frame(height: 40)
    }
}

/// Truthful GPX workflow pill. Only the `.recording` state renders the red,
/// pulsing `REC`; permission and durable-start states use distinct copy.
struct RecordingIndicator: View {
    @ObservedObject private var appLanguage = AppLanguage.shared
    let state: RecordingCoordinator.State
    let pointCount: Int
    let onStop: () -> Void
    @State private var pulse = false

    var body: some View {
        Button(action: onStop) {
            HStack(spacing: 7) {
                Image(systemName: statusIcon)
                    .font(.system(size: 11, weight: .bold))
                    .opacity(isRecording && pulse ? 0.25 : 1.0)
                Text(statusTitle)
                    .font(.system(size: 12, weight: .bold))
                    .tracking(1)
                    .foregroundStyle(.white)
                if isRecording {
                    Text("· " + L10n.quantity("point", pointCount))
                        .font(.system(size: 12))
                        .foregroundStyle(.white.opacity(0.85))
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(statusColor.opacity(0.95), in: Capsule())
            .overlay(Capsule().stroke(.white.opacity(0.15), lineWidth: 1))
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(accessibilityText)
        .onAppear {
            updatePulse(isRecording)
        }
        .onChange(of: isRecording) { active in
            updatePulse(active)
        }
    }

    private func updatePulse(_ active: Bool) {
        guard active else {
            pulse = false
            return
        }
        withAnimation(.easeInOut(duration: 0.7).repeatForever(autoreverses: true)) {
            pulse = true
        }
    }

    private var isRecording: Bool { state == .recording }

    private var statusTitle: String {
        switch state {
        case .awaitingPermission: return L10n.text("AWAITING LOCATION")
        case .starting: return Messages.recordingStatusStarting()
        case .recording: return L10n.text("REC")
        case .interrupted: return Messages.recordingStatusInterrupted()
        case .idle: return Messages.recordingStatusIdle()
        }
    }

    private var statusIcon: String {
        switch state {
        case .awaitingPermission, .starting: return "hourglass"
        case .recording: return "circle.fill"
        case .interrupted: return "exclamationmark.triangle.fill"
        case .idle: return "circle"
        }
    }

    private var statusColor: Color {
        isRecording
            ? Color(red: 0.84, green: 0.18, blue: 0.18)
            : Color(red: 0.82, green: 0.45, blue: 0.08)
    }

    private var accessibilityText: String {
        switch state {
        case .awaitingPermission:
            return L10n.text("Track recording awaiting Location permission. Tap to cancel.")
        case .starting:
            return L10n.text("Track recording is starting.")
        case .recording:
            return L10n.text("Recording track — %1$@ points. Tap to stop.", pointCount)
        case .interrupted:
            return L10n.text("Track recording interrupted. Tap to dismiss.")
        case .idle:
            return L10n.text("Track recording idle.")
        }
    }
}

/// Lock toggle. Freezes all graphics when on - no select, drag, or
/// vertex-edit. Sits under the undo/redo buttons in right rail.
struct LockButton: View {
    @ObservedObject private var appLanguage = AppLanguage.shared
    let locked: Bool
    let onToggle: () -> Void

    var body: some View {
        Button(action: onToggle) {
            Image(systemName: locked ? "lock.fill" : "lock.open")
                .font(.system(size: 17, weight: .semibold))
                .frame(width: 40, height: 40)
                .background(
                    (locked ? Color(red: 239/255, green: 108/255, blue: 0).opacity(0.88)
                            : Color.black.opacity(0.80)),
                    in: Circle()
                )
                .overlay(Circle().stroke(.white.opacity(0.12), lineWidth: 1))
                .foregroundStyle(.white)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(locked ? L10n.text("Graphics locked — tap to unlock")
                                   : L10n.text("Lock graphics in place"))
    }
}

struct UnitLabelsToggle: View {
    @ObservedObject private var appLanguage = AppLanguage.shared
    let active: Bool
    let onToggle: () -> Void

    var body: some View {
        Button(action: onToggle) {
            Image(systemName: "tag.fill")
                .font(.system(size: 17, weight: .semibold))
                .frame(width: 40, height: 40)
                .background(
                    (active ? Color.blue.opacity(0.88) : Color.black.opacity(0.80)),
                    in: Circle()
                )
                .overlay(Circle().stroke(.white.opacity(0.12), lineWidth: 1))
                .foregroundStyle(.white)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(active ? L10n.text("Hide unit labels") : L10n.text("Show unit labels"))
    }
}

/// Direct map entry to TacMap Chat while a secure Unit Sync room is active.
/// Only the aggregate unread count leaves the sealed chat store; message IDs,
/// conversation metadata and plaintext remain private to that store.
struct TacMapChatShortcutButton: View {
    @ObservedObject private var appLanguage = AppLanguage.shared
    @ObservedObject var store: TacMapChatStore
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: "bubble.left.and.bubble.right.fill")
                .font(.system(size: 17, weight: .semibold))
                .frame(width: 40, height: 40)
                .background(Color.black.opacity(0.80), in: Circle())
                .overlay(Circle().stroke(.white.opacity(0.12), lineWidth: 1))
                .foregroundStyle(.white)
                .contentShape(Circle())
                .overlay(alignment: .topTrailing) {
                    if store.unreadMessageCount > 0 {
                        Text(store.unreadMessageCount > 99
                             ? "99+" : "\(store.unreadMessageCount)")
                            .font(.system(size: 10, weight: .bold, design: .rounded))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 4)
                            .frame(minWidth: 18, minHeight: 18)
                            .background(Color.red, in: Capsule())
                            .overlay(Capsule().stroke(.white, lineWidth: 1.5))
                            .offset(x: 7, y: -7)
                            .accessibilityHidden(true)
                    }
                }
                // Preserve the 40-point visual used by the Android HUD while
                // meeting Apple's 44-point minimum interactive target.
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("TacMap Chat")
        .accessibilityValue(unreadAccessibilityValue)
        .accessibilityHint(L10n.text("Opens encrypted chat for this Unit Sync room"))
        .animation(.easeInOut(duration: 0.16), value: store.unreadMessageCount)
    }

    private var unreadAccessibilityValue: String {
        let count = store.unreadMessageCount
        guard count > 0 else { return L10n.text("No unread messages") }
        return L10n.quantity("unread", count)
    }
}

/// One-tap entry to the symbol builder at the current map crosshair. Lives in
/// the top-left HUD rail so adding a symbol no longer requires opening the
/// hamburger menu and then the full symbology list first.
struct QuickAddSymbolButton: View {
    @ObservedObject private var appLanguage = AppLanguage.shared
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: "plus")
                .font(.system(size: 19, weight: .bold))
                .frame(width: 44, height: 44)
                .background(Color.orange.opacity(0.90), in: Circle())
                .overlay(Circle().stroke(.white.opacity(0.18), lineWidth: 1))
                .foregroundStyle(.black)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(L10n.text("Add symbol at crosshair"))
        .accessibilityHint(L10n.text("Opens the symbol builder at the centre of the map"))
    }
}

/// Top-right compass chip. N marker rotates live with map heading,
/// lower half shows NATO mils (6400/circle). Tap to reset to north.
struct CompassChip: View {
    @ObservedObject private var appLanguage = AppLanguage.shared
    /// Map heading in degrees (0 = north-up, 90 = east-up).
    let heading: Double
    let orientationMode: MapOrientationMode
    let headingAvailable: Bool
    let northReference: HeadingNorthReference?
    /// Triggered when the user taps the chip.
    let onTap: () -> Void

    private let size: CGFloat = 56
    private let northDialSize: CGFloat = 34

    /// NATO mils, 6400 per circle. N=0000 E=1600 S=3200 W=4800.
    /// Wraps via modulo so 6400 displays as 0000.
    private var milsString: String {
        MapHeading.milsString(for: heading)
    }

    private var referenceSuffix: String {
        guard orientationMode == .headingUp else { return "T" }
        return northReference?.displaySuffix ?? "?"
    }

    private var referenceAccessibilityLabel: String {
        guard orientationMode == .headingUp else { return L10n.text("true north") }
        return northReference?.accessibilityLabel ?? L10n.text("north reference pending")
    }

    private var activeStrokeColor: Color {
        northReference == .magneticNorth ? .orange : .blue
    }

    private var tapHint: String {
        switch MapHeading.compassTapAction(
            headingUpEnabled: orientationMode == .headingUp,
            currentHeading: heading,
            headingAvailable: headingAvailable
        ) {
        case .resetNorth:
            return L10n.text("Resets the map to north")
        case .enableHeadingUp:
            return L10n.text("Switches to Heading Up using the phone compass")
        case .disableHeadingUp:
            return L10n.text("Switches to North Up and resets the map to north")
        case .headingUnavailable:
            return L10n.text("Heading Up is unavailable on this device")
        }
    }

    var body: some View {
        Button(action: onTap) {
            ZStack {
                Circle().fill(.black.opacity(0.82))
                    .frame(width: size, height: size)
                Circle().stroke(
                    orientationMode == .headingUp
                        ? activeStrokeColor.opacity(0.95)
                        : Color.white.opacity(0.14),
                    lineWidth: orientationMode == .headingUp ? 2 : 1
                )
                    .frame(width: size, height: size)

                // ----- Rotating N marker (orbits the upper compass face) -----
                // Keep this dial above the separator so south-facing headings
                // cannot collide with the static mils readout.
                // Triangle tick at the top edge.
                VStack(spacing: 0) {
                    Image(systemName: "triangle.fill")
                        .font(.system(size: 7))
                        .foregroundStyle(.red)
                        .padding(.top, 3)
                    Spacer()
                }
                .frame(width: northDialSize, height: northDialSize)
                .rotationEffect(.degrees(-heading))
                .offset(y: -(size - northDialSize) / 2)

                // Letter N below the triangle, also rotates.
                VStack(spacing: 0) {
                    Spacer().frame(height: 11)
                    Text("N")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(.white)
                    Spacer()
                }
                .frame(width: northDialSize, height: northDialSize)
                .rotationEffect(.degrees(-heading))
                .offset(y: -(size - northDialSize) / 2)

                // ----- Static mils readout (always upright, easy to read) -----
                VStack(spacing: 0) {
                    Spacer()
                    Text("\(milsString)\(referenceSuffix)")
                        .font(.system(size: 11, weight: .bold, design: .monospaced))
                        .foregroundStyle(Color(red: 0.55, green: 0.95, blue: 0.55))
                        .padding(.bottom, 5)
                }
                .frame(width: size, height: size)

                // Thin separator between rotating face and mils readout.
                Rectangle()
                    .fill(.white.opacity(0.10))
                    .frame(width: size * 0.55, height: 0.5)
                    .offset(y: 4)
            }
            .frame(width: size, height: size)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(
            L10n.text("%1$@, map heading %2$@ mils, %3$@", orientationMode.label, milsString, referenceAccessibilityLabel)
        )
        .accessibilityHint(tapHint)
    }
}
