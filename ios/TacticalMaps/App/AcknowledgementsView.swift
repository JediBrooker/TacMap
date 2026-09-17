import SwiftUI

/// Credits screen (App Store compliant). Lists every third-party lib or
/// data source we use with its license + project link.
struct AcknowledgementsView: View {
    @Environment(\.dismiss) private var dismiss
    /// Captured once on appear so the "Clear" button can hide the section
    /// without a re-render fight.
    @State private var crashURL: URL? = CrashReporter.exportURL()

    var body: some View {
        NavigationStack {
            List {
                Section(L10n.text("Map data")) {
                    LinkRow(
                        title: L10n.text("Esri World Imagery (Satellite basemap)"),
                        subtitle: L10n.text("Esri, Maxar, Earthstar Geographics, and the GIS User Community."),
                        url: URL(string: "https://www.arcgis.com/home/item.html?id=10df2279f9684e4a9f6a7f08febac2a9")
                    )
                    LinkRow(
                        title: L10n.text("Esri Basemap Styles (Topographic + Street)"),
                        subtitle: L10n.text("Esri, TomTom, Garmin, FAO, NOAA, USGS · map data © OpenStreetMap contributors."),
                        url: URL(string: "https://developers.arcgis.com/documentation/mapping-and-location-services/mapping/basemap-styles-service/")
                    )
                    LinkRow(
                        title: L10n.text("OpenTopoMap (Topographic basemap)"),
                        subtitle: L10n.text("© OpenTopoMap (CC-BY-SA) · map data © OpenStreetMap contributors (ODbL)."),
                        url: URL(string: "https://opentopomap.org/about")
                    )
                    LinkRow(
                        title: "OpenStreetMap",
                        subtitle: L10n.text("Map data © OpenStreetMap contributors, ODbL."),
                        url: URL(string: "https://www.openstreetmap.org/copyright")
                    )
                    LinkRow(
                        title: L10n.text("Open-Meteo Elevation API"),
                        subtitle: L10n.text("Copernicus DEM (≈30m). Free for non-commercial & commercial use under CC BY 4.0."),
                        url: URL(string: "https://open-meteo.com/en/license")
                    )
                }

                Section(L10n.text("Open source libraries")) {
                    LinkRow(
                        title: L10n.text("NGA mgrs-ios (vendored, MIT)"),
                        subtitle: L10n.text("MGRS ↔ lat/lon conversions. Includes a Snyder UTM patch to compile under Xcode 26."),
                        url: URL(string: "https://github.com/ngageoint/mgrs-ios")
                    )
                    LinkRow(
                        title: "NGA grid-ios (MIT)",
                        subtitle: L10n.text("Grid primitives used by mgrs-ios."),
                        url: URL(string: "https://github.com/ngageoint/grid-ios")
                    )
                    LinkRow(
                        title: "NGA simple-features-ios (MIT)",
                        subtitle: L10n.text("Geometric primitives."),
                        url: URL(string: "https://github.com/ngageoint/simple-features-ios")
                    )
                    LinkRow(
                        title: "NGA color-ios (MIT)",
                        subtitle: L10n.text("Colour utilities."),
                        url: URL(string: "https://github.com/ngageoint/color-ios")
                    )
                }

                Section(L10n.text("Standards & specifications")) {
                    LinkRow(
                        title: "OGC GeoPDF Encoding Best Practice",
                        subtitle: L10n.text("OGC 08-139r3. Used for reading LGIDict georeferencing."),
                        url: URL(string: "https://www.ogc.org/standards/geopdf")
                    )
                    LinkRow(
                        title: "GeoJSON (RFC 7946)",
                        subtitle: L10n.text("Export format for waypoints + drawings."),
                        url: URL(string: "https://datatracker.ietf.org/doc/html/rfc7946")
                    )
                    LinkRow(
                        title: "Mapbox simplestyle-spec",
                        subtitle: L10n.text("GeoJSON styling keys (stroke, fill, marker-color, marker-symbol)."),
                        url: URL(string: "https://github.com/mapbox/simplestyle-spec")
                    )
                    LinkRow(
                        title: "Mapbox Maki Icon Set",
                        subtitle: L10n.text("Marker icon names referenced in GeoJSON output (campsite, drinking-water, etc.)."),
                        url: URL(string: "https://github.com/mapbox/maki")
                    )
                }

                Section {
                    Text(L10n.text("TacMap adds no analytics or remote crash telemetry. Optional online basemaps and lookups are off on a fresh install; if you enable them, the provider receives your IP plus the requested tile area, place query, or lookup coordinate. Unit Sync payload content is end-to-end encrypted, while its relay still sees routing and traffic metadata."))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                if let crashURL {
                    Section(L10n.text("Diagnostics")) {
                        ShareLink(item: crashURL) {
                            Label(L10n.text("Export last crash log"), systemImage: "ladybug")
                        }
                        Button(role: .destructive) {
                            CrashReporter.clear()
                            ExportFileSecurity.remove(crashURL)
                            self.crashURL = nil
                        } label: {
                            Label(L10n.text("Clear crash log"), systemImage: "trash")
                        }
                        Text(L10n.text("A crash was recorded on a previous run. Nothing is sent anywhere — you choose whether to share this file."))
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .navigationTitle(L10n.text("About & Credits"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button(L10n.text("Done")) { dismiss() } }
            }
        }
        .onDisappear { ExportFileSecurity.remove(crashURL) }
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
