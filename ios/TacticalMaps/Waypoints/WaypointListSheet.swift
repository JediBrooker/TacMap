import SwiftUI
import MapKit

/// Lists all saved waypoints. Tap a row to edit. “Add at Crosshair” drops
/// a new waypoint at map centre and opens the edit sheet so you can name
/// and symbolise it.
struct WaypointListSheet: View {
    @ObservedObject private var appLanguage = AppLanguage.shared
    @ObservedObject var waypointStore: WaypointStore
    @ObservedObject var drawingStore: DrawingStore
    @ObservedObject var mapVM: MapViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var editing: Waypoint? = nil
    @State private var creatingAt: CLLocationCoordinate2D? = nil
    @State private var pendingDelete: Waypoint? = nil
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            List {
                Section(L10n.text("Symbology (%1$@)", waypointStore.waypoints.count)) {
                    if waypointStore.waypoints.isEmpty {
                        Text(L10n.text("No symbols yet. Pan the crosshair to a feature and tap “Add at Crosshair” below."))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(waypointStore.waypoints) { wp in
                            Button {
                                editing = wp
                            } label: {
                                row(for: wp)
                            }
                            .buttonStyle(.plain)
                            .swipeActions(edge: .leading, allowsFullSwipe: true) {
                                Button {
                                    flyTo(wp)
                                } label: { Label(L10n.text("Fly to"), systemImage: "location.viewfinder") }
                                    .tint(.blue)
                            }
                            // allowsFullSwipe: false - don't let a full swipe
                            // nuke mission data outright, make them tap Delete
                            // then confirm.
                            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                                Button(role: .destructive) {
                                    pendingDelete = wp
                                } label: { Label(L10n.text("Delete"), systemImage: "trash") }
                            }
                        }
                    }
                }

                Section {
                    Button {
                        creatingAt = mapVM.cameraCentre
                    } label: {
                        Label(L10n.text("Add at Crosshair"), systemImage: "plus.circle.fill")
                    }
                } footer: {
                    Text(L10n.text("Swipe right on a symbol to fly to it; swipe left to delete."))
                        .font(.caption2)
                }
            }
            .navigationTitle(L10n.text("Symbology"))
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button(L10n.text("Done")) { dismiss() } }
            }
            .sheet(item: $editing) { wp in
                SelectedSymbolEditSheet(
                    waypointStore: waypointStore,
                    drawingStore: drawingStore,
                    waypoint: wp
                )
            }
            .sheet(item: $creatingAt) { coord in
                WaypointCreationSheet(
                    waypointStore: waypointStore,
                    defaultCoordinate: coord,
                    defaultScale: mapVM.defaultControlMeasureScale,
                    defaultLayerID: drawingStore.activeLayerID ?? DrawingLayer.legacyFallbackID
                )
            }
            .confirmationDialog(
                L10n.text("Delete “%1$@”?", pendingDelete?.name ?? ""),
                isPresented: Binding(get: { pendingDelete != nil },
                                     set: { if !$0 { pendingDelete = nil } }),
                titleVisibility: .visible,
                presenting: pendingDelete
            ) { wp in
                Button(L10n.text("Delete"), role: .destructive) {
                    do {
                        _ = try waypointStore.deleteDurably(wp)
                        pendingDelete = nil
                    } catch {
                        pendingDelete = nil
                        errorMessage = error.localizedDescription
                    }
                }
                Button(L10n.text("Cancel"), role: .cancel) { pendingDelete = nil }
            }
            .alert(L10n.text("Could Not Delete Symbol"),
                   isPresented: Binding(get: { errorMessage != nil },
                                        set: { if !$0 { errorMessage = nil } }),
                   presenting: errorMessage) { _ in
                Button(L10n.text("OK"), role: .cancel) { errorMessage = nil }
            } message: { Text($0) }
        }
    }

    @ViewBuilder
    private func row(for wp: Waypoint) -> some View {
        HStack {
            WaypointKindIcon(kind: wp.kind, size: 32)
                .frame(width: 36)
            VStack(alignment: .leading, spacing: 2) {
                Text(wp.name).foregroundStyle(.primary)
                HStack(spacing: 6) {
                    Text(MGRSFormatter.string(from: wp.coordinate))
                        .font(.caption.monospaced())
                        .foregroundStyle(.secondary)
                    if let el = wp.elevation {
                        Text("• \(Int(el)) m")
                            .font(.caption.monospaced())
                            .foregroundStyle(.secondary)
                    }
                }
            }
            Spacer()
            Image(systemName: "chevron.right")
                .font(.caption)
                .foregroundStyle(.tertiary)
        }
    }

    private func flyTo(_ wp: Waypoint) {
        mapVM.cameraRequests.send(
            MKCoordinateRegion(center: wp.coordinate,
                               latitudinalMeters: 1500,
                               longitudinalMeters: 1500)
        )
        dismiss()
    }
}

// Allow CLLocationCoordinate2D to drive a `.sheet(item:)` for "create at" flows.
extension CLLocationCoordinate2D: @retroactive Identifiable {
    public var id: String { "\(latitude),\(longitude)" }
}
