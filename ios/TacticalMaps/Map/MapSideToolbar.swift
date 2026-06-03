import SwiftUI

/// Top-left hamburger button. Opens a CUSTOM popover (not SwiftUI's
/// system `Menu`) so each row is a full-width 54pt button with a 28pt
/// icon — wide enough to hit reliably with a finger on a real phone.
/// The system Menu's compact rows were hard to tap consistently per
/// user feedback.
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
    let onImport:        () -> Void
    let onImportTiles:   () -> Void
    let onImportGeoJSON: () -> Void
    let onExport:        () -> Void
    let onAbout:         () -> Void

    @State private var isOpen = false
    /// Action chosen from a menu row. Run from the sheet's `onDismiss` so
    /// it never races the dismiss animation (see `close`/`runPendingAction`).
    @State private var pendingAction: (() -> Void)?

    var body: some View {
        Button {
            isOpen.toggle()
        } label: {
            Image(systemName: "line.3.horizontal")
                .font(.system(size: 19, weight: .medium))
                /// 48pt sits comfortably above Apple's 44pt minimum
                /// tap target and matches the surrounding HUD chips
                /// (compass, "Centre on My Location"), so the user
                /// doesn't have to aim for a small button to open
                /// the menu.
                .frame(width: 48, height: 48)
                .background(.black.opacity(0.78), in: Circle())
                .overlay(Circle().stroke(.white.opacity(0.08)))
                .foregroundStyle(.white)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Menu")
        /// Single `.large` detent: nine 54pt rows + dividers + nav bar
        /// don't fit a medium sheet on shorter iPhones (medium clipped
        /// "About & Credits" off the bottom), so the sheet opens
        /// full-height. Rows live in a ScrollView so every entry stays
        /// reachable regardless.
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
                        row("Layers",          systemImage: "square.3.stack.3d")   { close(onLayers) }
                        row("Measure",         systemImage: "ruler")               { close(onMeasure) }
                        divider
                        row("Import PDF Map…", systemImage: "doc.badge.plus")      { close(onImport) }
                        row("Import Offline Tiles…", systemImage: "square.stack.3d.up.fill") { close(onImportTiles) }
                        row("Import GeoJSON…", systemImage: "square.and.arrow.down") { close(onImportGeoJSON) }
                        row("Export GeoJSON…", systemImage: "square.and.arrow.up")   { close(onExport) }
                        divider
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

    /// Stash the row's action and dismiss the sheet. The action fires from
    /// `onDismiss` once the sheet has fully gone, so actions that present
    /// another sheet (file importer, etc.) don't race the dismissal.
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
            /// 54pt tall: clears Apple's 44pt minimum with breathing
            /// room and gives each finger-sized icon room to read.
            .frame(height: 54)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
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

/// Top-right compass chip. Rotates the N marker live with the map's heading
/// (so N always points to real-world north) and shows the heading as a
/// NATO-mil reading (6400 mils per full circle) in the lower half.
/// Tap the chip to smooth-animate the map back to heading = 0°.
struct CompassChip: View {
    /// Map heading in degrees (0 = north-up, 90 = east-up).
    let heading: Double
    /// Triggered when the user taps the chip.
    let onTap: () -> Void

    private let size: CGFloat = 56

    /// NATO mils: 6400 per full circle (1° ≈ 17.78 mils). N=0000, E=1600,
    /// S=3200, W=4800. Wraps via modulo so a brief reading of 6400 displays 0000.
    private var milsString: String {
        let positive = ((heading.truncatingRemainder(dividingBy: 360.0)) + 360.0)
            .truncatingRemainder(dividingBy: 360.0)
        let mils = positive * (6400.0 / 360.0)
        let rounded = Int(round(mils)) % 6400
        return String(format: "%04d", rounded)
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
                .animation(.linear(duration: 0.05), value: heading)

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
                .animation(.linear(duration: 0.05), value: heading)

                // ----- Static mils readout (always upright, easy to read) -----
                VStack(spacing: 0) {
                    Spacer()
                    Text(milsString)
                        .font(.system(size: 11, weight: .bold, design: .monospaced))
                        .foregroundStyle(Color(red: 0.55, green: 0.95, blue: 0.55))
                        .padding(.bottom, 5)
                }
                .frame(width: size, height: size)

                // Thin separator between the rotating face and the digit panel.
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
