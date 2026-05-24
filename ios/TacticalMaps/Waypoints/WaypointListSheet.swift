import SwiftUI
import MapKit

/// Sheet listing all saved waypoints. Tapping one flies the map to it. The
/// unified export now lives in `ExportSheet` (reachable from the hamburger menu).
struct WaypointListSheet: View {
    @ObservedObject var waypointStore: WaypointStore
    @ObservedObject var mapVM: MapViewModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section("Waypoints") {
                    ForEach(waypointStore.waypoints) { wp in
                        Button {
                            mapVM.cameraRequests.send(
                                MKCoordinateRegion(center: wp.coordinate,
                                                   latitudinalMeters: 1500,
                                                   longitudinalMeters: 1500)
                            )
                            dismiss()
                        } label: {
                            HStack {
                                Image(systemName: wp.kind.sfSymbol)
                                    .foregroundStyle(wp.kind.tint)
                                VStack(alignment: .leading) {
                                    Text(wp.name).foregroundStyle(.primary)
                                    Text(MGRSFormatter.string(from: wp.coordinate))
                                        .font(.caption.monospaced())
                                        .foregroundStyle(.secondary)
                                }
                                Spacer()
                                if let el = wp.elevation {
                                    Text("\(Int(el)) m")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                    .onDelete { idx in
                        idx.forEach { waypointStore.remove(waypointStore.waypoints[$0]) }
                    }
                }

                Section {
                    Button {
                        let wp = Waypoint(name: "New Waypoint", coordinate: mapVM.cameraCentre)
                        waypointStore.add(wp)
                    } label: {
                        Label("Add at Crosshair", systemImage: "plus.circle.fill")
                    }
                }
            }
            .navigationTitle("Waypoints")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } }
            }
        }
    }
}
