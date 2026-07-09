import SwiftUI

/// Exports the recorded GPX track: writes a temp `.gpx` and offers `ShareLink`
/// to Files / AirDrop / Mail, etc.
struct GPXExportSheet: View {
    let points: [TrackPoint]
    @Environment(\.dismiss) private var dismiss

    @State private var generatedURL: URL? = nil
    @State private var error: String? = nil

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 16) {
                Label("\(points.count) track point\(points.count == 1 ? "" : "s") recorded",
                      systemImage: "point.topleft.down.curvedto.point.bottomright.up")
                    .font(.headline)

                if let url = generatedURL {
                    ShareLink(
                        item: url,
                        preview: SharePreview("TacMap GPX track", image: Image(systemName: "map"))
                    ) {
                        Label("Share GPX file", systemImage: "square.and.arrow.up")
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 10)
                            .background(Color.accentColor, in: RoundedRectangle(cornerRadius: 12))
                            .foregroundStyle(.white)
                    }
                } else if points.isEmpty {
                    Text("No track recorded yet. Start recording from the menu, move, then export.")
                        .foregroundStyle(.secondary)
                }

                if let error {
                    Text(error).foregroundStyle(.red)
                }

                Text("Format: GPX 1.1 - opens in Garmin, Strava, Gaia GPS, QGIS, Google Earth, and most GPS tools.")
                    .font(.caption)
                    .foregroundStyle(.secondary)

                Spacer()
            }
            .padding()
            .navigationTitle("Export GPX")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } }
            }
            .task { generate() }
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
            self.error = "Export failed: \(error.localizedDescription)"
        }
    }
}
