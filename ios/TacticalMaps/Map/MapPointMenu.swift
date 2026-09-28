import SwiftUI
import CoreLocation
import UIKit
import UniformTypeIdentifiers

/// A long-pressed point on the map and its coordinate in the user's chosen
/// readout format. Drives the point menu (place, measure, rings, sun and
/// moon, copy).
struct MapPressPoint: Identifiable {
    let id = UUID()
    let coordinate: CLLocationCoordinate2D
    let coordinateText: CoordinateDisplayFormat.Resolved

    init(coordinate: CLLocationCoordinate2D, format: CoordinateDisplayFormat) {
        self.coordinate = coordinate
        self.coordinateText = format.resolve(
            mgrs: MGRSFormatter.string(from: coordinate),
            wgs84: MapViewModel.wgs84Text(for: coordinate),
            utm: MGRSFormatter.utm(from: coordinate)
        )
    }

    /// Copies the coordinate for two minutes, on this device only.
    func copyToPasteboard() {
        UIPasteboard.general.setItems(
            [[UTType.plainText.identifier: coordinateText.text]],
            options: [
                .expirationDate: Date().addingTimeInterval(120),
                .localOnly: true
            ]
        )
    }
}

/// Offline sun and moon times for a long-pressed point.
struct PointSunMoonSheet: View {
    @ObservedObject private var appLanguage = AppLanguage.shared
    let point: MapPressPoint
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Text(point.coordinateText.text)
                        .font(.subheadline.monospaced())
                        .foregroundStyle(.secondary)
                        .textSelection(.enabled)
                    SunMoonSection(coordinate: point.coordinate)
                }
                .padding()
            }
            .navigationTitle(Messages.sunMoonTitle())
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(L10n.text("Done")) { dismiss() }
                }
            }
        }
    }
}

/// The long-press point menu and the sheets it opens. Actions that create
/// mission objects are hidden while graphics are locked.
struct MapPointMenuModifier: ViewModifier {
    @Binding var point: MapPressPoint?
    @ObservedObject var drawingStore: DrawingStore
    let canEdit: Bool
    let onPlaceSymbol: (CLLocationCoordinate2D) -> Void
    let onMeasure: (CLLocationCoordinate2D) -> Void
    let onCopied: (CoordinateDisplayFormat) -> Void

    @State private var sunMoonPoint: MapPressPoint?
    @State private var ringCentre: Waypoint?

    func body(content: Content) -> some View {
        content
            .confirmationDialog(
                point?.coordinateText.text ?? "",
                isPresented: Binding(get: { point != nil }, set: { if !$0 { point = nil } }),
                titleVisibility: .visible,
                presenting: point
            ) { pressed in
                if canEdit {
                    Button(Messages.mapPointPlaceSymbol()) { onPlaceSymbol(pressed.coordinate) }
                }
                Button(Messages.mapPointMeasure()) { onMeasure(pressed.coordinate) }
                if canEdit {
                    Button(Messages.mapPointRangeRings()) { ringCentre = centre(for: pressed) }
                }
                Button(Messages.mapPointSunMoon()) { sunMoonPoint = pressed }
                Button(Messages.mapPointCopy()) {
                    pressed.copyToPasteboard()
                    UIImpactFeedbackGenerator(style: .light).impactOccurred()
                    onCopied(pressed.coordinateText.format)
                }
                Button(L10n.text("Cancel"), role: .cancel) {}
            }
            .nightSheet(item: $sunMoonPoint) { pressed in
                PointSunMoonSheet(point: pressed)
                    .presentationDetents([.medium, .large])
            }
            .nightSheet(item: $ringCentre) { centre in
                RangeRingsSheet(drawingStore: drawingStore, waypoint: centre, anchored: false)
            }
    }

    /// A stand-in symbol that only supplies the rings' centre, name and layer.
    private func centre(for pressed: MapPressPoint) -> Waypoint {
        let layerID = drawingStore.activeLayerID
            ?? drawingStore.layers.first?.id
            ?? DrawingLayer.legacyFallbackID
        return Waypoint(name: pressed.coordinateText.text,
                        latitude: pressed.coordinate.latitude,
                        longitude: pressed.coordinate.longitude,
                        layerID: layerID)
    }
}
