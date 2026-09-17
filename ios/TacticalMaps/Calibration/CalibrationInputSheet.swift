import SwiftUI
import CoreLocation

/// Modal that asks user for the MGRS of a tapped fiduciary point.
/// Pops up automatically when session.pendingTap != nil.
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
                Section(L10n.text("Tapped point")) {
                    if let p = session.pendingTap?.pdfPoint {
                        Text(L10n.text("PDF coord: (%1$@, %2$@)", Int(p.x), Int(p.y)))
                            .font(.caption.monospaced())
                            .foregroundStyle(.secondary)
                    }
                    Text(L10n.text("Look at the PDF's printed grid labels or local knowledge to find the MGRS of this feature."))
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                Section(L10n.text("Map datum")) {
                    Picker(L10n.text("Datum"), selection: $session.datum) {
                        ForEach(Datum.allCases, id: \.self) { d in
                            Text(d.displayName).tag(d)
                        }
                    }
                    if session.datum != .wgs84 {
                        Text(L10n.text("MGRS you enter is read as %1$@ and shifted to WGS84 (~1–2 m) so overlays line up.", session.datum.displayName))
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }
                Section(L10n.text("MGRS grid reference")) {
                    TextField("56HLH 12345 67890", text: $mgrs)
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                    if let loc = currentLocation {
                        Button {
                            mgrs = MGRSFormatter.string(from: loc)
                        } label: {
                            Label(L10n.text("Use my current location (%1$@)", MGRSFormatter.string(from: loc)),
                                  systemImage: "location.fill")
                                .font(.callout)
                        }
                    }
                }
                Section(L10n.text("Optional label")) {
                    TextField(L10n.text("e.g. “Church spire”, “Grid intersection NE”"), text: $label)
                }
                if let error = errorMessage {
                    Section { Text(error).foregroundStyle(.red).font(.caption) }
                }
            }
            .navigationTitle(L10n.text("Add fiduciary #%1$@", session.fiduciaries.count + 1))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(L10n.text("Cancel")) {
                        onCancel()
                        dismiss()
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button(L10n.text("Save")) { save() }
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
            errorMessage = L10n.text("Couldn't parse MGRS. Format: <zone><band><square> <easting> <northing>, e.g. 56HLH 12345 67890")
        }
    }
}
