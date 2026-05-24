import SwiftUI

/// App-Store-compliant credits screen. Lists every third-party library or
/// data source we depend on with its license + project link.
struct AcknowledgementsView: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section("Map data") {
                    LinkRow(
                        title: "Apple MapKit Satellite Imagery",
                        subtitle: "Apple Inc. — Use governed by the Apple Maps Service",
                        url: URL(string: "https://www.apple.com/legal/internet-services/maps/terms-en.html")
                    )
                    LinkRow(
                        title: "Open-Meteo Elevation API",
                        subtitle: "Copernicus DEM (≈30m). Free for non-commercial & commercial use under CC BY 4.0.",
                        url: URL(string: "https://open-meteo.com/en/license")
                    )
                }

                Section("Open source libraries") {
                    LinkRow(
                        title: "NGA mgrs-ios (vendored, MIT)",
                        subtitle: "MGRS ↔ lat/lon conversions. Includes a Snyder UTM patch to compile under Xcode 26.",
                        url: URL(string: "https://github.com/ngageoint/mgrs-ios")
                    )
                    LinkRow(
                        title: "NGA grid-ios (MIT)",
                        subtitle: "Grid primitives used by mgrs-ios.",
                        url: URL(string: "https://github.com/ngageoint/grid-ios")
                    )
                    LinkRow(
                        title: "NGA simple-features-ios (MIT)",
                        subtitle: "Geometric primitives.",
                        url: URL(string: "https://github.com/ngageoint/simple-features-ios")
                    )
                    LinkRow(
                        title: "NGA color-ios (MIT)",
                        subtitle: "Colour utilities.",
                        url: URL(string: "https://github.com/ngageoint/color-ios")
                    )
                }

                Section("Standards & specifications") {
                    LinkRow(
                        title: "OGC GeoPDF Encoding Best Practice",
                        subtitle: "OGC 08-139r3. Used for reading LGIDict georeferencing.",
                        url: URL(string: "https://www.ogc.org/standards/geopdf")
                    )
                    LinkRow(
                        title: "GeoJSON (RFC 7946)",
                        subtitle: "Export format for waypoints + drawings.",
                        url: URL(string: "https://datatracker.ietf.org/doc/html/rfc7946")
                    )
                    LinkRow(
                        title: "Mapbox simplestyle-spec",
                        subtitle: "GeoJSON styling keys (stroke, fill, marker-color, marker-symbol).",
                        url: URL(string: "https://github.com/mapbox/simplestyle-spec")
                    )
                    LinkRow(
                        title: "Mapbox Maki Icon Set",
                        subtitle: "Marker icon names referenced in GeoJSON output (campsite, drinking-water, etc.).",
                        url: URL(string: "https://github.com/mapbox/maki")
                    )
                }

                Section {
                    Text("TacticalMaps respects your privacy. We collect no telemetry. Location and elevation lookups stay on your device or are anonymised in flight.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("About & Credits")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } }
            }
        }
    }
}

private struct LinkRow: View {
    let title: String
    let subtitle: String
    let url: URL?

    var body: some View {
        if let url = url {
            Link(destination: url) { content }
        } else {
            content
        }
    }

    private var content: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).foregroundStyle(.primary)
            Text(subtitle).font(.caption).foregroundStyle(.secondary)
        }
    }
}
