import SwiftUI

/// Floating bottom HUD shown while a `DrawingSessionViewModel` is active.
/// Replaces the centre-on-location button during drawing mode.
struct DrawToolbar: View {
    @ObservedObject var session: DrawingSessionViewModel
    let onFinish: () -> Void

    @State private var showingNameAlert = false
    @State private var draftName: String = ""

    var body: some View {
        if let kind = session.activeKind {
            // Sized to fit iPhone portrait widths. The "active tool" pill
            // shows just the icon (label is redundant once you've tapped
            // the tool); the point counter sits alongside it. Cancel
            // collapses to an icon on narrow widths.
            HStack(spacing: 6) {
                Image(systemName: kind.sfSymbol)
                    .font(.caption.weight(.bold))
                    .foregroundStyle(.black)
                    .frame(width: 30, height: 30)
                    .background(Color(red: 1, green: 0.65, blue: 0.18), in: Circle())

                colorSwatchMenu

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
                        .frame(width: 30, height: 30)
                        .background(.white.opacity(0.10), in: Circle())
                        .foregroundStyle(.white)
                }
                .buttonStyle(.plain)
                .disabled(session.inProgressCoordinates.isEmpty)
                .opacity(session.inProgressCoordinates.isEmpty ? 0.4 : 1)

                // fixedSize + lineLimit(1) keep these as single-line pills.
                // Compact paddings let both fit alongside the tool icons
                // on a portrait phone.
                Button {
                    session.cancel()
                } label: {
                    Image(systemName: "xmark")
                        .font(.subheadline.weight(.semibold))
                        .frame(width: 30, height: 30)
                        .background(.white.opacity(0.10), in: Circle())
                        .foregroundStyle(.white)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Cancel")

                Button("Finish", action: onFinish)
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
            .alert("Name this drawing", isPresented: $showingNameAlert) {
                TextField("e.g. Patrol route, Engagement area", text: $draftName)
                    .autocorrectionDisabled()
                Button("Save") {
                    session.shapeName = draftName.trimmingCharacters(in: .whitespaces)
                }
                Button("Cancel", role: .cancel) { }
            } message: {
                Text("Leave blank to keep the default name (\(session.activeKind?.displayName ?? "")).")
            }
        }
    }

    /// "Tag" button. Outline icon when no name set, filled with a tiny
    /// label preview when set. Tap opens an alert with a TextField.
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
        .accessibilityLabel("Drawing name")
        .accessibilityValue(session.shapeName.isEmpty ? "Unset" : session.shapeName)
    }

    /// Toggle between solid and dashed stroke for the finalized line /
    /// polygon outline. While drawing, the in-progress preview is always
    /// rendered dashed — this toggle only affects the committed shape.
    /// The icon draws the actual stroke style (solid bar vs three short
    /// dashes) so the affordance reads without a label.
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
        .accessibilityLabel("Stroke style")
        .accessibilityValue(session.isDashed ? "Dashed" : "Solid")
    }

    /// Tappable circle showing the current stroke colour. Tapping opens a
    /// menu of the 12 palette swatches.
    private var colorSwatchMenu: some View {
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
            .accessibilityLabel("Drawing colour")
            .accessibilityValue(DrawingPalette.swatch(forHex: session.strokeColorHex)?.name ?? session.strokeColorHex)
        }
        .buttonStyle(.plain)
    }

}
