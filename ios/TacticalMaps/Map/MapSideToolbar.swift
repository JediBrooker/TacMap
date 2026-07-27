import SwiftUI

/// Top-left hamburger. Custom popover instead of SwiftUI's system Menu
/// b/c we need full-width 54pt buttons with 28pt icons - system Menu
/// rows were too hard to tap on a real phone.
struct HamburgerMenu: View {
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
    let isRecordingTrack: Bool
    let trackPointCount: Int
    let onToggleTrackRecording: () -> Void
    let onExportGPX:     () -> Void
    let onExportAll:     () -> Void
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
        .accessibilityLabel("Menu")
        /// Large detent only - medium sheet clips the bottom rows on
        /// shorter iPhones. ScrollView so everything's reachable.
        .sheet(isPresented: $isOpen, onDismiss: runPendingAction) {
            NavigationStack {
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        if !isPurchased {
                            trialBanner
                            row("Unlock Full Version", systemImage: "cart") { close(onUnlock) }
                            divider
                        }
                        row("Search…",         systemImage: "magnifyingglass")     { close(onSearch) }
                        divider
                        row("Symbology",       systemImage: "mappin.and.ellipse")  { close(onWaypoints) }
                        row("Drawings",        systemImage: "scribble.variable")   { close(onDrawings) }
                        row("Layers and Labels", systemImage: "square.3.stack.3d") { close(onLayers) }
                        row("Measure",         systemImage: "ruler")               { close(onMeasure) }
                        row("Weather & UAV Safety", systemImage: "wind")            { close(onWeather) }
                        divider
                        // All file import/export lives behind one row so the
                        // main menu stays short. Pushes a sub-page within the
                        // sheet's NavigationStack (no sheet-over-sheet races).
                        navRow("Import / Export…", systemImage: "square.and.arrow.up.on.square")
                        divider
                        row(isRecordingTrack
                            ? "Stop Track Recording (\(trackPointCount) pts)"
                            : "Start Track Recording",
                            systemImage: isRecordingTrack ? "stop.circle.fill" : "record.circle")
                            { close(onToggleTrackRecording) }
                        divider
                        row("Unit Sync…", systemImage: "antenna.radiowaves.left.and.right") { close(onSync) }
                        row("App Lock…", systemImage: "lock.shield")               { close(onAppLock) }
                        row("Settings, Privacy & OPSEC", systemImage: "eye.slash.fill") { close(onOpsec) }
                        row("About & Credits", systemImage: "info.circle")         { close(onAbout) }
                    }
                }
                .navigationTitle("Menu")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("Close") { isOpen = false }
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
                sectionHeader("Import")
                row("PDF Map…", systemImage: "doc.badge.plus")               { close(onImport) }
                row("Offline Tiles…", systemImage: "square.stack.3d.up.fill") { close(onImportTiles) }
                row("GeoJSON…", systemImage: "square.and.arrow.down")         { close(onImportGeoJSON) }
                row("KML / KMZ…", systemImage: "globe.desk")                  { close(onImportKML) }
                divider
                sectionHeader("Export")
                row("GeoJSON…", systemImage: "square.and.arrow.up")           { close(onExport) }
                row("Export All Data…", systemImage: "square.and.arrow.up.on.square") { close(onExportAll) }
                row("GPX Track…", systemImage: "point.topleft.down.curvedto.point.bottomright.up")
                    { close(onExportGPX) }
            }
        }
        .navigationTitle("Import / Export")
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
                 ? "Free trial — \(trialDaysRemaining) day\(trialDaysRemaining == 1 ? "" : "s") left"
                 : "Free trial ended")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(.secondary)
            Spacer()
        }
        .padding(.horizontal, 16)
        .frame(height: 40)
    }
}

/// Red "REC" pill while GPX track is recording. Dot pulses, tapping
/// stops the recording. Menu can also start/stop it.
struct RecordingIndicator: View {
    let pointCount: Int
    let onStop: () -> Void
    @State private var pulse = false

    var body: some View {
        Button(action: onStop) {
            HStack(spacing: 7) {
                Circle()
                    .fill(.white)
                    .frame(width: 9, height: 9)
                    .opacity(pulse ? 0.25 : 1.0)
                Text("REC")
                    .font(.system(size: 12, weight: .bold))
                    .tracking(1)
                    .foregroundStyle(.white)
                Text("· \(pointCount) pt\(pointCount == 1 ? "" : "s")")
                    .font(.system(size: 12))
                    .foregroundStyle(.white.opacity(0.85))
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(Color(red: 0.84, green: 0.18, blue: 0.18).opacity(0.95), in: Capsule())
            .overlay(Capsule().stroke(.white.opacity(0.15), lineWidth: 1))
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Recording track — \(pointCount) points. Tap to stop.")
        .onAppear {
            withAnimation(.easeInOut(duration: 0.7).repeatForever(autoreverses: true)) {
                pulse = true
            }
        }
    }
}

/// Lock toggle. Freezes all graphics when on - no select, drag, or
/// vertex-edit. Sits under the undo/redo buttons in right rail.
struct LockButton: View {
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
        .accessibilityLabel(locked ? "Graphics locked — tap to unlock"
                                   : "Lock graphics in place")
    }
}

struct UnitLabelsToggle: View {
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
        .accessibilityLabel(active ? "Hide unit labels" : "Show unit labels")
    }
}

/// One-tap entry to the symbol builder at the current map crosshair. Lives in
/// the top-left HUD rail so adding a symbol no longer requires opening the
/// hamburger menu and then the full symbology list first.
struct QuickAddSymbolButton: View {
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
        .accessibilityLabel("Add symbol at crosshair")
        .accessibilityHint("Opens the symbol builder at the centre of the map")
    }
}

/// Top-right compass chip. N marker rotates live with map heading,
/// lower half shows NATO mils (6400/circle). Tap to reset to north.
struct CompassChip: View {
    /// Map heading in degrees (0 = north-up, 90 = east-up).
    let heading: Double
    /// Triggered when the user taps the chip.
    let onTap: () -> Void

    private let size: CGFloat = 56

    /// NATO mils, 6400 per circle. N=0000 E=1600 S=3200 W=4800.
    /// Wraps via modulo so 6400 displays as 0000.
    private var milsString: String {
        MapHeading.milsString(for: heading)
    }

    var body: some View {
        Button(action: onTap) {
            ZStack {
                Circle().fill(.black.opacity(0.82))
                    .frame(width: size, height: size)
                Circle().stroke(.white.opacity(0.14), lineWidth: 1)
                    .frame(width: size, height: size)

                // ----- Rotating N marker (orbits the compass centre) -----
                // Triangle tick at the top edge.
                VStack(spacing: 0) {
                    Image(systemName: "triangle.fill")
                        .font(.system(size: 7))
                        .foregroundStyle(.red)
                        .padding(.top, 3)
                    Spacer()
                }
                .frame(width: size, height: size)
                .rotationEffect(.degrees(-heading))

                // Letter N below the triangle, also rotates.
                VStack(spacing: 0) {
                    Spacer().frame(height: 11)
                    Text("N")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(.white)
                    Spacer()
                }
                .frame(width: size, height: size)
                .rotationEffect(.degrees(-heading))

                // ----- Static mils readout (always upright, easy to read) -----
                VStack(spacing: 0) {
                    Spacer()
                    Text(milsString)
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
        .accessibilityLabel("Map heading \(milsString) mils")
        .accessibilityHint(heading == 0
            ? "Map already north-up"
            : "Tap to reset to north (currently \(Int(heading))°)")
    }
}
