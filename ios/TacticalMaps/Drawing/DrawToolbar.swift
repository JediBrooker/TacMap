import SwiftUI

/// Floating bottom HUD while a drawing session is active.
/// Replaces the centre-on-location button in drawing mode.
struct DrawToolbar: View {
    @ObservedObject private var appLanguage = AppLanguage.shared
    @ObservedObject var session: DrawingSessionViewModel
    let onFinish: () -> Void

    @State private var showingNameAlert = false
    @State private var draftName: String = ""
    @State private var showCancelConfirm = false

    var body: some View {
        if let kind = session.activeKind {
            // Sized for iPhone portrait. Active tool pill just shows the
            // icon (label is redundant once tapped), point counter sits
            // next to it. Cancel collapses to icon on narrow widths.
            HStack(spacing: 6) {
                Image(systemName: kind.sfSymbol)
                    .font(.caption.weight(.bold))
                    .foregroundStyle(.black)
                    .frame(width: 30, height: 30)
                    .background(Color(red: 1, green: 0.65, blue: 0.18), in: Circle())

                strokeColorSwatchMenu

                if kind == .polygon {
                    fillStyleMenu
                }

                strokeStyleToggle

                nameButton

                Text("\(session.inProgressCoordinates.count)")
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.white.opacity(0.85))
                    .fixedSize(horizontal: true, vertical: false)
                    .frame(minWidth: 16)

                Spacer(minLength: 0)

                Button {
                    session.undo()
                } label: {
                    Image(systemName: "arrow.uturn.backward")
                        .font(.subheadline.weight(.semibold))
                        .frame(width: 34, height: 34)
                        .background(.white.opacity(0.10), in: Circle())
                        .foregroundStyle(.white)
                }
                .buttonStyle(.plain)
                .contentShape(Rectangle())
                .disabled(session.inProgressCoordinates.isEmpty)
                .opacity(session.inProgressCoordinates.isEmpty ? 0.4 : 1)

                // fixedSize + lineLimit(1) keep these as single-line pills,
                // compact padding so both fit next to tool icons on phone.
                Button {
                    // don't nuke placed points on a stray tap, confirm first
                    if session.inProgressCoordinates.isEmpty { session.cancel() }
                    else { showCancelConfirm = true }
                } label: {
                    Image(systemName: "xmark")
                        .font(.subheadline.weight(.semibold))
                        .frame(width: 34, height: 34)
                        .background(.white.opacity(0.10), in: Circle())
                        .foregroundStyle(.white)
                }
                .buttonStyle(.plain)
                .contentShape(Rectangle())
                .accessibilityLabel(L10n.text("Cancel"))

                Button(L10n.text("Finish"), action: onFinish)
                    .font(.subheadline.weight(.bold))
                    .lineLimit(1)
                    .fixedSize(horizontal: true, vertical: false)
                    .foregroundStyle(.black)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 7)
                    .background(
                        Capsule().fill(
                            session.canFinish
                                ? Color(red: 1, green: 0.65, blue: 0.18)
                                : Color.gray
                        )
                    )
                    .buttonStyle(.plain)
                    .disabled(!session.canFinish)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 7)
            .background(.black.opacity(0.85), in: RoundedRectangle(cornerRadius: 18))
            .overlay(RoundedRectangle(cornerRadius: 18).stroke(.white.opacity(0.12)))
            .alert(L10n.text("Name this drawing"), isPresented: $showingNameAlert) {
                TextField(L10n.text("e.g. Patrol route, Engagement area"), text: $draftName)
                    .autocorrectionDisabled()
                Button(L10n.text("Save")) {
                    session.shapeName = draftName.trimmingCharacters(in: .whitespaces)
                }
                Button(L10n.text("Cancel"), role: .cancel) { }
            } message: {
                Text(L10n.text("Leave blank to keep the default name (%1$@).", session.activeKind?.displayName ?? ""))
            }
            .alert(L10n.text("Discard drawing?"), isPresented: $showCancelConfirm) {
                Button(L10n.text("Discard"), role: .destructive) { session.cancel() }
                Button(L10n.text("Keep drawing"), role: .cancel) { }
            } message: {
                Text(L10n.text("This will discard the %1$@ point(s) you've placed.", session.inProgressCoordinates.count))
            }
        }
    }

    /// "Tag" button - outline when no name, filled w/ tiny label preview
    /// when set. Tap opens an alert with a TextField.
    private var nameButton: some View {
        Button {
            draftName = session.shapeName
            showingNameAlert = true
        } label: {
            let hasName = !session.shapeName.isEmpty
            HStack(spacing: 4) {
                Image(systemName: hasName ? "tag.fill" : "tag")
                    .font(.caption.weight(.semibold))
                if hasName {
                    Text(session.shapeName)
                        .font(.caption2.weight(.semibold))
                        .lineLimit(1)
                        .truncationMode(.tail)
                        .frame(maxWidth: 70)
                }
            }
            .foregroundStyle(.white)
            .padding(.horizontal, hasName ? 8 : 0)
            .frame(height: 30)
            .frame(minWidth: 30)
            .background(.white.opacity(hasName ? 0.18 : 0.10),
                        in: Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(L10n.text("Drawing name"))
        .accessibilityValue(session.shapeName.isEmpty ? L10n.text("Unset") : session.shapeName)
    }

    /// Solid vs dashed stroke toggle. Only affects the committed shape,
    /// not the in-progress preview (that's always dashed). The icon
    /// shows the actual stroke style so it reads without a label.
    private var strokeStyleToggle: some View {
        Button {
            session.isDashed.toggle()
        } label: {
            ZStack {
                Circle()
                    .fill(.white.opacity(session.isDashed ? 0.22 : 0.10))
                if session.isDashed {
                    HStack(spacing: 2.5) {
                        ForEach(0..<3, id: \.self) { _ in
                            Capsule().fill(.white).frame(width: 5, height: 2.5)
                        }
                    }
                } else {
                    Capsule().fill(.white).frame(width: 20, height: 2.5)
                }
            }
            .frame(width: 34, height: 34)
            .overlay(
                Circle()
                    .stroke(.white.opacity(session.isDashed ? 0.5 : 0), lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
        .accessibilityLabel(L10n.text("Stroke style"))
        .accessibilityValue(session.isDashed ? L10n.text("Dashed") : L10n.text("Solid"))
    }

    /// Colour swatch circle, opens the 12-colour palette menu on tap.
    private var strokeColorSwatchMenu: some View {
        Menu {
            ForEach(DrawingPalette.swatches) { swatch in
                Button {
                    session.strokeColorHex = swatch.hex
                } label: {
                    Label(swatch.name,
                          systemImage: session.strokeColorHex.caseInsensitiveCompare(swatch.hex) == .orderedSame
                              ? "largecircle.fill.circle"
                              : "circle.fill")
                }
                .tint(swatch.color)
            }
        } label: {
            ZStack {
                Circle()
                    .fill(Color(hex: session.strokeColorHex))
                    .frame(width: 22, height: 22)
                Circle()
                    .stroke(.white.opacity(0.85), lineWidth: 1.5)
                    .frame(width: 22, height: 22)
            }
            .accessibilityLabel(L10n.text("Stroke colour"))
            .accessibilityValue(DrawingPalette.swatch(forHex: session.strokeColorHex)?.name ?? session.strokeColorHex)
        }
        .buttonStyle(.plain)
    }

    /// Polygon fill has its own hue and opacity. Keeping these in one compact
    /// menu avoids widening the drawing HUD while still exposing both controls.
    private var fillStyleMenu: some View {
        Menu {
            Section(L10n.text("Fill colour")) {
                ForEach(DrawingPalette.swatches) { swatch in
                    Button {
                        session.fillColorHex = swatch.hex
                    } label: {
                        Label(swatch.name,
                              systemImage: session.fillColorHex.caseInsensitiveCompare(swatch.hex) == .orderedSame
                                  ? "largecircle.fill.circle"
                                  : "circle.fill")
                    }
                    .tint(swatch.color)
                }
            }
            Section(L10n.text("Fill opacity")) {
                ForEach([0.0, 0.1, 0.2, 0.4, 0.6, 0.8, 1.0], id: \.self) { opacity in
                    Button {
                        session.fillOpacity = opacity
                    } label: {
                        Label("\(Int(opacity * 100))%",
                              systemImage: abs(session.fillOpacity - opacity) < 0.001
                                  ? "checkmark.circle.fill"
                                  : "circle")
                    }
                }
            }
        } label: {
            ZStack {
                Circle()
                    .fill(Color(hex: session.fillColorHex).opacity(session.fillOpacity))
                Circle().stroke(.white.opacity(0.85), lineWidth: 1.5)
                Image(systemName: "paintbrush.pointed.fill")
                    .font(.caption2)
                    .foregroundStyle(.white)
                    .shadow(radius: 1)
            }
            .frame(width: 34, height: 34)
            .accessibilityLabel(L10n.text("Fill style"))
            .accessibilityValue(L10n.text("%1$@, %2$@ percent", DrawingPalette.swatch(forHex: session.fillColorHex)?.name ?? session.fillColorHex, Int(session.fillOpacity * 100)))
        }
        .buttonStyle(.plain)
    }

}
