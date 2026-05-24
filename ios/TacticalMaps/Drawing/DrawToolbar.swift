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
}
