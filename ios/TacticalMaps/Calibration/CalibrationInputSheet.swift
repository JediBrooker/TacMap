import SwiftUI
import CoreLocation

/// Modal that asks the user for the MGRS of a tapped fiduciary point.
/// Shown automatically when `session.pendingTap != nil`.
struct CalibrationInputSheet: View {
    @ObservedObject var session: CalibrationSession
    /// Closure called when the user dismisses without confirming, so we can
    /// clear the pending tap.
    let onCancel: () -> Void
    /// User's current GPS, if available, so we can offer a one-tap "use here".
    let currentLocation: CLLocationCoordinate2D?

    @State private var mgrs: String = ""
    @State private var label: String = ""
    @State private var errorMessage: String? = nil
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section("Tapped point") {
                    if let p = session.pendingTap?.pdfPoint {
                        Text("PDF coord: (\(Int(p.x)), \(Int(p.y)))")
                            .font(.caption.monospaced())
                            .foregroundStyle(.secondary)
                    }
                    Text("Look at the PDF's printed grid labels or local knowledge to find the MGRS of this feature.")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                Section("MGRS grid reference") {
                    TextField("56HLH 12345 67890", text: $mgrs)
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                    if let loc = currentLocation {
                        Button {
                            mgrs = MGRSFormatter.string(from: loc)
                        } label: {
                            Label("Use my current location (\(MGRSFormatter.string(from: loc)))",
                                  systemImage: "location.fill")
                                .font(.callout)
                        }
                    }
                }
                Section("Optional label") {
                    TextField("e.g. “Church spire”, “Grid intersection NE”", text: $label)
                }
                if let error = errorMessage {
                    Section { Text(error).foregroundStyle(.red).font(.caption) }
                }
            }
            .navigationTitle("Add fiduciary #\(session.fiduciaries.count + 1)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Cancel") {
                        onCancel()
                        dismiss()
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Save") { save() }
                        .bold()
                        .disabled(mgrs.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
    }

    private func save() {
        let cleaned = mgrs.uppercased().trimmingCharacters(in: .whitespacesAndNewlines)
        let labelOrNil = label.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? nil
            : label.trimmingCharacters(in: .whitespacesAndNewlines)
        if session.confirmFiduciary(mgrs: cleaned, label: labelOrNil) {
            dismiss()
        } else {
            errorMessage = "Couldn't parse MGRS. Format: <zone><band><square> <easting> <northing> — e.g. 56HLH 12345 67890"
        }
    }
}
