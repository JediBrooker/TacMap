import SwiftUI

/// Exports the recorded GPX track: writes a temp `.gpx` and offers `ShareLink`
/// to Files / AirDrop / Mail, etc.
struct GPXExportSheet: View {
    @ObservedObject private var appLanguage = AppLanguage.shared
    let points: [TrackPoint]
    @Environment(\.dismiss) private var dismiss

    @State private var generatedURL: URL? = nil
    @State private var error: LocalizedMessage? = nil

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 16) {
                Label(L10n.quantity("track_recorded", points.count),
                      systemImage: "point.topleft.down.curvedto.point.bottomright.up")
                    .font(.headline)

                if let url = generatedURL {
                    ShareLink(
                        item: url,
                        preview: SharePreview(L10n.text("TacMap GPX track"), image: Image(systemName: "map"))
                    ) {
                        Label(L10n.text("Share GPX file"), systemImage: "square.and.arrow.up")
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 10)
                            .background(Color.accentColor, in: RoundedRectangle(cornerRadius: 12))
                            .foregroundStyle(.white)
                    }
                } else if points.isEmpty {
                    Text(L10n.text("No track recorded yet. Start recording from the menu, move, then export."))
                        .foregroundStyle(.secondary)
                }

                if let error {
                    Text(error.text).foregroundStyle(.red)
                }

                Text(L10n.text("Format: GPX 1.1 - opens in Garmin, Strava, Gaia GPS, QGIS, Google Earth, and most GPS tools."))
                    .font(.caption)
                    .foregroundStyle(.secondary)

                Spacer()
            }
            .padding()
            .navigationTitle(L10n.text("Export GPX"))
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button(L10n.text("Done")) { dismiss() } }
            }
            .task { generate() }
            .onDisappear { ExportFileSecurity.remove(generatedURL) }
        }
    }

    private func generate() {
        guard !points.isEmpty else { return }
        do {
            generatedURL = try GPXExporter.exportToFile(
                points: points,
                timestamp: Int(Date().timeIntervalSince1970)
            )
        } catch {
            self.error = Messages.displayExportFailedMessage("").withArgument(0, error.displayMessage)
        }
    }
}
