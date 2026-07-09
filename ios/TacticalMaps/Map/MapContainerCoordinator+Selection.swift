import MapKit
import UIKit

// MARK: - Annotation selection + annotation drag-to-move
//
// Hooks MapKit's select/deselect callbacks up to the VM's selected-waypoint
// state (which drives the floating controls card) and persists drags back
// to the stores. Pulled out of MapContainerView.swift.
extension MapContainerView.Coordinator {

    /// Tap a waypoint -> publish its ID on the VM so the controls card
    /// shows up. Other annotation types don't need this.
    ///
    /// We implement both the iOS 17+ annotation-flavored selector and
    /// the older view-flavored one b/c MapKit picks whichever it wants
    /// depending on OS version.
    func mapView(_ mv: MKMapView, didSelect view: MKAnnotationView) {
        handleSelection(of: view.annotation)
    }

    func mapView(_ mv: MKMapView, didSelect annotation: MKAnnotation) {
        handleSelection(of: annotation)
    }

    func mapView(_ mv: MKMapView, didDeselect view: MKAnnotationView) {
        handleDeselection(of: view.annotation)
    }

    func mapView(_ mv: MKMapView, didDeselect annotation: MKAnnotation) {
        handleDeselection(of: annotation)
    }

    private func handleSelection(of annotation: MKAnnotation?) {
        guard let wp = annotation as? WaypointAnnotation else { return }
        // Skip the haptic on refresh-driven re-selection (same waypoint
        // already on the model) - user didn't tap anything new.
        let isReselection = mapVM.selectedWaypointID == wp.waypoint.id
        if !isReselection {
            selectionHaptic.prepare()
            selectionHaptic.impactOccurred()
        }
        DispatchQueue.main.async { [weak self] in
            self?.mapVM.selectedWaypointID = wp.waypoint.id
        }
    }

    private func handleDeselection(of annotation: MKAnnotation?) {
        // MapKit fires didDeselect when an annotation is removed.
        // If thats part of a refresh, controls card should stay open -
        // the annotation gets re-added and re-selected in `refresh()`.
        if isRebuildingAnnotations { return }
        guard let wp = annotation as? WaypointAnnotation else { return }
        DispatchQueue.main.async { [weak self] in
            if self?.mapVM.selectedWaypointID == wp.waypoint.id {
                self?.mapVM.selectedWaypointID = nil
            }
        }
    }

    /// Deselects everything. Called when controls card is dismissed.
    func deselectAll(on mv: MKMapView) {
        for ann in mv.selectedAnnotations {
            mv.deselectAnnotation(ann, animated: false)
        }
    }

    /// Fires when user long-presses and drags an annotation. Persist
    /// the new coordinate to the store on .ending so it survives
    /// the next refresh.
    func mapView(_ mv: MKMapView,
                 annotationView view: MKAnnotationView,
                 didChange newState: MKAnnotationView.DragState,
                 fromOldState oldState: MKAnnotationView.DragState) {
        guard newState == .ending else { return }
        if let ann = view.annotation as? WaypointAnnotation {
            if let wp = waypointStore.waypoints.first(where: { $0.id == ann.waypoint.id }) {
                var updated = wp
                updated.latitude  = ann.coordinate.latitude
                updated.longitude = ann.coordinate.longitude
                waypointStore.update(updated)
            }
            return
        }
        if let h = view.annotation as? DrawingVertexHandleAnnotation,
           var shape = drawingStore.shapes.first(where: { $0.id == h.shapeID }) {
            let newCoord = Coordinate2D(latitude: h.coordinate.latitude,
                                        longitude: h.coordinate.longitude)
            if h.isMidpoint {
                shape.insertEffectiveVertex(newCoord, at: h.vertexIndex)
            } else {
                shape.setEffectiveVertex(h.vertexIndex, to: newCoord)
            }
            drawingStore.update(shape)
            return
        }
    }
}
