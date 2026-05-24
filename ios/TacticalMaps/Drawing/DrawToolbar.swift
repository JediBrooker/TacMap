import SwiftUI

/// Floating bottom HUD shown while a `DrawingSessionViewModel` is active.
/// Replaces the centre-on-location button during drawing mode.
struct DrawToolbar: View {
    @ObservedObject var session: DrawingSessionViewModel
    let onFinish: () -> Void

    var body: some View {
        if let kind = session.activeKind {
            HStack(spacing: 10) {
                Label(kind.displayName, systemImage: kind.sfSymbol)
                    .font(.caption.weight(.bold))
                    .lineLimit(1)
                    .fixedSize(horizontal: true, vertical: false)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(Color(red: 1, green: 0.65, blue: 0.18), in: Capsule())
                    .foregroundStyle(.black)

                colorSwatchMenu

                Text("\(session.inProgressCoordinates.count) pt\(session.inProgressCoordinates.count == 1 ? "" : "s")")
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.white.opacity(0.8))
                    .fixedSize(horizontal: true, vertical: false)

                Spacer()

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
                .disabled(session.inProgressCoordinates.isEmpty)
                .opacity(session.inProgressCoordinates.isEmpty ? 0.4 : 1)

                Button("Cancel") { session.cancel() }
                    .font(.subheadline)
                    .foregroundStyle(.white)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(.white.opacity(0.10), in: Capsule())
                    .buttonStyle(.plain)

                Button("Finish", action: onFinish)
                    .font(.subheadline.weight(.bold))
                    .foregroundStyle(.black)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
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
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(.black.opacity(0.85), in: RoundedRectangle(cornerRadius: 18))
            .overlay(RoundedRectangle(cornerRadius: 18).stroke(.white.opacity(0.12)))
        }
    }

    /// Tappable circle showing the current stroke colour. Tapping opens a
    /// menu of the 12 palette swatches.
    private var colorSwatchMenu: some View {
        Menu {
            ForEach(DrawingPalette.swatches) { swatch in
                Button {
                    session.strokeColorHex = swatch.hex
                } label: {
                    Label {
                        Text(swatch.name)
                    } icon: {
                        // Filled tinted circle so the menu reads as a palette
                        Image(systemName: session.strokeColorHex.caseInsensitiveCompare(swatch.hex) == .orderedSame
                              ? "largecircle.fill.circle"
                              : "circle.fill")
                            .foregroundStyle(swatch.color)
                    }
                }
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
