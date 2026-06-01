import MapKit
import UIKit

// MARK: - Annotation selection + annotation drag-to-move
//
// Bridges MapKit's annotation selection/deselection callbacks to the view
// model's selected-waypoint state (driving the floating controls card), and
// persists annotation drags back to the stores. Extracted verbatim from
// MapContainerView.swift.
extension MapContainerView.Coordinator {

    /// When the user taps a tactical-control-measure waypoint, publish
    /// its ID on the VM so `ContentView` can show the rotate / resize
    /// controls card. Tapping other annotation kinds does nothing
    /// special (they have no per-symbol transforms to tune).
    ///
    /// Implements both the iOS 17+ annotation-flavored selector and
    /// the older view-flavored one so the callback fires regardless
    /// of which MapKit prefers on the running system.
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
        // Suppress the haptic when this is a refresh-driven
        // re-selection (same waypoint already on the model) — the
        // user didn't tap anything new.
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
        // If that removal is part of a refresh, the controls card
        // should stay open — the annotation will be re-added and
        // re-selected on the next line of `refresh()`.
        if isRebuildingAnnotations { return }
        guard let wp = annotation as? WaypointAnnotation else { return }
        DispatchQueue.main.async { [weak self] in
            if self?.mapVM.selectedWaypointID == wp.waypoint.id {
                self?.mapVM.selectedWaypointID = nil
            }
        }
    }

    /// Programmatic deselection used when the controls card is dismissed.
    func deselectAll(on mv: MKMapView) {
        for ann in mv.selectedAnnotations {
            mv.deselectAnnotation(ann, animated: false)
        }
    }

    /// MKMapView fires this when the user long-presses an annotation
    /// (`isDraggable = true`) and drags it. We persist the new
    /// coordinate to the store on .ending so the change survives
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
