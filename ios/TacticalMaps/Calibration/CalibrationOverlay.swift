import SwiftUI

/// HUD overlay shown while a calibration session is active. Sits across the
/// top of the screen, surfaces fiduciary count + Finish/Cancel actions.
struct CalibrationOverlay: View {
    @ObservedObject var session: CalibrationSession
    let onFinish: () -> Void
    let onCancel: () -> Void

    var body: some View {
        VStack(spacing: 6) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Calibrating PDF")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(Color(red: 1, green: 0.65, blue: 0.18))
                    Text(statusLine)
                        .font(.caption2)
                        .foregroundStyle(.white.opacity(0.85))
                }
                Spacer()
                Button("Cancel", action: onCancel)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 10).padding(.vertical, 6)
                    .background(.white.opacity(0.10), in: Capsule())
                Button("Finish", action: onFinish)
                    .font(.caption.weight(.bold))
                    .foregroundStyle(.black)
                    .padding(.horizontal, 12).padding(.vertical, 6)
                    .background(
                        Capsule().fill(session.canFinish
                            ? Color(red: 1, green: 0.65, blue: 0.18)
                            : Color.gray)
                    )
                    .disabled(!session.canFinish)
            }

            if let rms = session.lastFitRMSMetres {
                Text("Previous fit RMS: \(Int(rms))m — add more fiduciaries to refine")
                    .font(.caption2.monospacedDigit())
                    .foregroundStyle(.white.opacity(0.6))
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(.black.opacity(0.86), in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(.white.opacity(0.12)))
    }

    private var statusLine: String {
        let n = session.fiduciaries.count
        if n == 0 {
            return "Tap a known feature on the PDF to drop fiduciary #1 (need 3+)."
        }
        if n < 3 {
            return "\(n)/3 fiduciaries placed. Tap another known feature."
        }
        return "\(n) fiduciaries placed. Tap Finish to apply, or add more for accuracy."
    }
}
