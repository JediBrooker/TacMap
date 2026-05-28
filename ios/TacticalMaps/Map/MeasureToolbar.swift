import SwiftUI

/// Bottom HUD shown while `MeasureSession.isActive`. Lays out the running
/// distance, bearing-of-last-segment in mils, and (for 3+ points) the
/// enclosed area, plus undo / done buttons.
struct MeasureToolbar: View {
    @ObservedObject var session: MeasureSession

    var body: some View {
        if session.isActive {
            HStack(spacing: 8) {
                Image(systemName: "ruler")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(.black)
                    .frame(width: 30, height: 30)
                    .background(Color(red: 1, green: 0.65, blue: 0.18), in: Circle())

                VStack(alignment: .leading, spacing: 1) {
                    Text(MeasureFormat.distance(session.totalDistanceMeters))
                        .font(.subheadline.weight(.bold).monospacedDigit())
                        .foregroundStyle(.white)
                    HStack(spacing: 6) {
                        if let mils = session.lastBearingMils {
                            Text("\(String(format: "%04d", mils)) mils")
                                .font(.caption2.monospacedDigit())
                                .foregroundStyle(.white.opacity(0.8))
                        }
                        if let area = session.enclosedAreaSquareMeters {
                            Text("· \(MeasureFormat.area(area))")
                                .font(.caption2.monospacedDigit())
                                .foregroundStyle(.white.opacity(0.7))
                        }
                    }
                }

                Spacer(minLength: 0)

                // Undo (last vertex)
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
                .disabled(session.points.isEmpty)
                .opacity(session.points.isEmpty ? 0.4 : 1)

                Button("Done") { session.cancel() }
                    .font(.subheadline.weight(.bold))
                    .lineLimit(1)
                    .fixedSize(horizontal: true, vertical: false)
                    .foregroundStyle(.black)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 7)
                    .background(Capsule().fill(Color(red: 1, green: 0.65, blue: 0.18)))
                    .buttonStyle(.plain)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 7)
            .background(.black.opacity(0.85), in: RoundedRectangle(cornerRadius: 18))
            .overlay(RoundedRectangle(cornerRadius: 18).stroke(.white.opacity(0.12)))
        }
    }
}
