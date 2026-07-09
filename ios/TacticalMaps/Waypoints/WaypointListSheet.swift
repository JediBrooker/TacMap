import SwiftUI
import MapKit

/// Lists all saved waypoints. Tap a row to edit. “Add at Crosshair” drops
/// a new waypoint at map centre and opens the edit sheet so you can name
/// and symbolise it.
struct WaypointListSheet: View {
    @ObservedObject var waypointStore: WaypointStore
    @ObservedObject var mapVM: MapViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var editing: Waypoint? = nil
    @State private var creatingAt: CLLocationCoordinate2D? = nil
    @State private var pendingDelete: Waypoint? = nil

    var body: some View {
        NavigationStack {
            List {
                Section("Symbology (\(waypointStore.waypoints.count))") {
                    if waypointStore.waypoints.isEmpty {
                        Text("No symbols yet. Pan the crosshair to a feature and tap “Add at Crosshair” below.")
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
                                } label: { Label("Fly to", systemImage: "location.viewfinder") }
                                    .tint(.blue)
                            }
                            // allowsFullSwipe: false - don't let a full swipe
                            // nuke mission data outright, make them tap Delete
                            // then confirm.
                            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                                Button(role: .destructive) {
                                    pendingDelete = wp
                                } label: { Label("Delete", systemImage: "trash") }
                            }
                        }
                    }
                }

                Section {
                    Button {
                        creatingAt = mapVM.cameraCentre
                    } label: {
                        Label("Add at Crosshair", systemImage: "plus.circle.fill")
                    }
                } footer: {
                    Text("Swipe right on a symbol to fly to it; swipe left to delete.")
                        .font(.caption2)
                }
            }
            .navigationTitle("Symbology")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } }
            }
            .sheet(item: $editing) { wp in
                WaypointEditSheet(waypointStore: waypointStore, original: wp)
            }
            .sheet(item: $creatingAt) { coord in
                WaypointEditSheet(
                    waypointStore: waypointStore,
                    original: nil,
                    defaultCoordinate: coord,
                    defaultScale: mapVM.defaultControlMeasureScale
                )
            }
            .confirmationDialog(
                "Delete “\(pendingDelete?.name ?? "")”?",
                isPresented: Binding(get: { pendingDelete != nil },
                                     set: { if !$0 { pendingDelete = nil } }),
                titleVisibility: .visible,
                presenting: pendingDelete
            ) { wp in
                Button("Delete", role: .destructive) { waypointStore.remove(wp); pendingDelete = nil }
                Button("Cancel", role: .cancel) { pendingDelete = nil }
            }
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
