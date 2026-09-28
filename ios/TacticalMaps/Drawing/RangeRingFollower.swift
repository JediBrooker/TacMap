import Combine
import Foundation

/// Keeps range rings centred on their symbol. It observes the symbol list
/// synchronously, so when a local move (or its undo) changes a symbol's
/// position the ring edits join the same undo group as the move. Remote Unit
/// Sync writes are skipped: the peer that moved the symbol sends its own
/// ring updates.
@MainActor
final class RangeRingFollower {
    private var positions: [UUID: Coordinate2D] = [:]
    private var subscription: AnyCancellable?

    nonisolated init() {}

    func attach(waypointStore: WaypointStore, drawingStore: DrawingStore) {
        guard subscription == nil else { return }
        positions = Self.positions(of: waypointStore.waypoints)
        subscription = waypointStore.$waypoints.sink { [weak self, weak drawingStore] waypoints in
            guard let self, let drawingStore else { return }
            self.follow(waypoints, drawingStore: drawingStore)
        }
    }

    private func follow(_ waypoints: [Waypoint], drawingStore: DrawingStore) {
        let previous = positions
        positions = Self.positions(of: waypoints)
        guard !SyncRemoteModelApplier.isApplying, !drawingStore.locked else { return }
        var moved: [UUID: Waypoint] = [:]
        for waypoint in waypoints {
            guard let old = previous[waypoint.id], old != positions[waypoint.id] else { continue }
            moved[waypoint.id] = waypoint
        }
        guard !moved.isEmpty else { return }
        let edits = drawingStore.shapes.compactMap { shape -> DrawingShape? in
            guard let anchor = shape.anchorWaypointID, let waypoint = moved[anchor] else { return nil }
            return RangeRings.followed(shape, waypoint: waypoint)
        }
        guard !edits.isEmpty else { return }
        // A failed write keeps the rings where they were; the store surfaces
        // its own save error.
        _ = try? drawingStore.commitEdits(edits, actionName: Messages.ringsUndoAction())
    }

    private static func positions(of waypoints: [Waypoint]) -> [UUID: Coordinate2D] {
        var out: [UUID: Coordinate2D] = [:]
        for waypoint in waypoints {
            out[waypoint.id] = Coordinate2D(latitude: waypoint.latitude, longitude: waypoint.longitude)
        }
        return out
    }
}
