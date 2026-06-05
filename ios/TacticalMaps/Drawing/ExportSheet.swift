import SwiftUI
import UniformTypeIdentifiers

/// Unified export sheet: serialises waypoints + drawings into a single GeoJSON
/// `FeatureCollection`, shows a preview, and offers `ShareLink` to write the
/// file out to Files, Mail, AirDrop, etc.
struct ExportSheet: View {
    @ObservedObject var waypointStore: WaypointStore
    @ObservedObject var drawingStore: DrawingStore
    @Environment(\.dismiss) private var dismiss

    @State private var generatedURL: URL? = nil
    @State private var preview: String = ""
    @State private var error: String? = nil

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    summary

                    if let url = generatedURL {
                        ShareLink(
                            item: url,
                            preview: SharePreview("TacMap export", image: Image(systemName: "map"))
                        ) {
                            Label("Share GeoJSON file", systemImage: "square.and.arrow.up")
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 10)
                                .background(Color.accentColor, in: RoundedRectangle(cornerRadius: 12))
                                .foregroundStyle(.white)
                        }
                        .padding(.horizontal)
                    }

                    if let error {
                        Text(error).foregroundStyle(.red).padding(.horizontal)
                    }

                    Text("Preview")
                        .font(.headline)
                        .padding(.horizontal)
                    Text(preview.isEmpty ? "—" : preview)
                        .font(.system(.caption2, design: .monospaced))
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(10)
                        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 8))
                        .textSelection(.enabled)
                        .padding(.horizontal)
                }
                .padding(.top)
            }
            .navigationTitle("Export")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } }
            }
            .task { await generate() }
        }
    }

    private var summary: some View {
        VStack(alignment: .leading, spacing: 6) {
            Label("\(waypointStore.waypoints.count) waypoint\(waypointStore.waypoints.count == 1 ? "" : "s")",
                  systemImage: "mappin.and.ellipse")
            Label("\(drawingStore.shapes.count) drawing\(drawingStore.shapes.count == 1 ? "" : "s")",
                  systemImage: "scribble.variable")
            Text("Format: GeoJSON FeatureCollection (RFC 7946) with simplestyle-spec styling. Opens in geojson.io, GitHub, Mapbox, Felt, QGIS, ArcGIS, Google Earth (via the GeoJSON-to-KML converter).")
                .font(.caption)
                .foregroundStyle(.secondary)
                .padding(.top, 2)
        }
        .padding(.horizontal)
    }

    private func generate() async {
        do {
            let url = try GeoJSONExporter.exportToFile(
                waypoints: waypointStore.waypoints,
                drawings:  drawingStore.shapes,
                layers:    drawingStore.layers
            )
            let str = try String(contentsOf: url, encoding: .utf8)
            generatedURL = url
            // Show a snippet — full file might be hundreds of KB.
            preview = str.count > 4000
                ? String(str.prefix(4000)) + "\n… (truncated, full file in Share)"
                : str
        } catch {
            self.error = "Export failed: \(error.localizedDescription)"
        }
    }
}
