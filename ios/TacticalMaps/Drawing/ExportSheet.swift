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

    /// Per-type counts (only non-empty types are listed). Free-draws are stored
    /// as many-point polylines, so they're split out from plain lines by point
    /// count (matching the > 20 heuristic used elsewhere).
    private var summaryItems: [(text: String, icon: String)] {
        let wps = waypointStore.waypoints
        let units = wps.filter { if case .military = $0.kind { return true }; return false }.count
        let tasks = wps.filter { if case .controlMeasure = $0.kind { return true }; return false }.count
        let markers = wps.filter { if case .generic = $0.kind { return true }; return false }.count

        let shapes = drawingStore.shapes
        let lineish = shapes.filter { $0.kind == .polyline || $0.kind == .freedraw }
        let freeDraws = lineish.filter { $0.coordinates.count > 20 }.count
        let lines = lineish.count - freeDraws
        let areas = shapes.filter { $0.kind == .polygon }.count
        let points = shapes.filter { $0.kind == .point }.count

        var items: [(String, String)] = []
        func add(_ n: Int, _ noun: String, _ icon: String) {
            if n > 0 { items.append(("\(n) \(noun)\(n == 1 ? "" : "s")", icon)) }
        }
        add(units,    "unit",      "shield.lefthalf.filled")
        add(tasks,    "task",      "scope")
        add(markers,  "marker",    "mappin")
        add(lines,    "line",      "line.diagonal")
        add(freeDraws,"free-draw", "scribble.variable")
        add(areas,    "area",      "hexagon")
        add(points,   "point",     "smallcircle.filled.circle")
        return items
    }

    private var summary: some View {
        VStack(alignment: .leading, spacing: 6) {
            let items = summaryItems
            if items.isEmpty {
                Label("Nothing to export", systemImage: "tray")
                    .foregroundStyle(.secondary)
            } else {
                ForEach(items.indices, id: \.self) { i in
                    Label(items[i].text, systemImage: items[i].icon)
                }
            }
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
