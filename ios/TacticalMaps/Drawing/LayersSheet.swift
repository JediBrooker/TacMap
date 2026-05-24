import SwiftUI

/// Modal listing toggleable overlay layers.
struct LayersSheet: View {
    @ObservedObject var visibility: LayerVisibility
    @ObservedObject var mapVM: MapViewModel
    /// Closure invoked when the user requests fiduciary calibration for the
    /// currently-loaded PDF. ContentView dismisses this sheet and starts the
    /// CalibrationSession.
    var onCalibrate: () -> Void = {}
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section("Overlays") {
                    Toggle("Symbology",     isOn: $visibility.waypointsVisible)
                    Toggle("Drawings",      isOn: $visibility.drawingsVisible)
                    Toggle("User Location", isOn: $visibility.userLocationVisible)
                }

                Section("Imported Map") {
                    if let pdfSource = mapVM.mapSource as? PDFMapSource {
                        Toggle(isOn: $visibility.pdfOverlayVisible) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(pdfSource.displayName).font(.callout)
                                Text(pdfSource.bounds == nil
                                     ? "No georeferencing — using map-centre fallback"
                                     : (pdfSource.kind == .geoPDF
                                        ? "Georeferenced (GeoPDF LGIDict)"
                                        : "Manually placed bounds"))
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                            }
                        }
                        Button {
                            dismiss()
                            onCalibrate()
                        } label: {
                            Label("Calibrate with fiduciaries…", systemImage: "scope")
                        }
                        if let fids = pdfSource.fiduciaries, !fids.isEmpty {
                            Text("Currently calibrated with \(fids.count) fiduciaries")
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                        Button(role: .destructive) {
                            mapVM.mapSource = AppleSatelliteMapSource()
                        } label: {
                            Label("Unload PDF", systemImage: "xmark.circle")
                        }
                    } else {
                        Label("None loaded", systemImage: "doc")
                            .foregroundStyle(.secondary)
                        Text("Import a PDF or GeoPDF via ☰ → Import PDF Map.")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }

                Section("Basemap") {
                    HStack {
                        Image(systemName: "globe.americas.fill")
                        Text("Apple Satellite")
                        Spacer()
                        Text(mapVM.mapSource is PDFMapSource
                             ? "Hidden while PDF is loaded"
                             : "Active")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .navigationTitle("Layers")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}
