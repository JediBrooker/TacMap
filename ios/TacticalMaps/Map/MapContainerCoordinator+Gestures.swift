import MapKit
import UIKit

// MARK: - Gesture handling
//
// All the gesture recogniser targets from makeUIView: browse-mode
// pan/pinch, centre-pivot rotation, drawing/measure taps, whole-shape drag,
// vertex-edit handle drag/delete. Pulled out of MapContainerView.swift;
// recogniser stored state lives on Coordinator (extensions can't add
// stored properties).
extension MapContainerView.Coordinator {

    // MARK: Browse-mode gestures

    @objc func userTouchedMap() {
        nextRegionChangeIsUserDriven = true
    }

    /// Centre-pivot rotation. Keep MKMapView's centerCoordinate pinned
    /// to current screen centre, only mutate heading. g.rotation is
    /// reset every change so we apply frame-to-frame deltas.
    @objc func handleRotation(_ g: UIRotationGestureRecognizer) {
        guard let mv = g.view as? MKMapView else { return }
        switch g.state {
        case .began:
            nextRegionChangeIsUserDriven = true
        case .changed:
            let deltaRad = g.rotation
            g.rotation = 0
            guard abs(deltaRad) > 0.0001 else { return }
            let camera = mv.camera
            var newHeading = camera.heading + deltaRad * 180 / .pi
            newHeading = newHeading.truncatingRemainder(dividingBy: 360)
            if newHeading < 0 { newHeading += 360 }
            let newCamera = MKMapCamera(
                lookingAtCenter:    camera.centerCoordinate,
                fromDistance:       camera.centerCoordinateDistance,
                pitch:              camera.pitch,
                heading:            newHeading
            )
            nextRegionChangeIsUserDriven = true
            mv.setCamera(newCamera, animated: false)
        default:
            break
        }
    }

    func gestureRecognizer(_ g: UIGestureRecognizer,
                           shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
        true
    }

    /// Don't begin our long-press-drag recogniser when the press
    /// lands on a vertex-edit handle. Otherwise our gesture clobbers
    /// the touches and MapKit's annotation drag can never fire, so
    /// user can't actually move a vertex.
    func gestureRecognizerShouldBegin(_ g: UIGestureRecognizer) -> Bool {
        if g === drawingDragPress,
           let mv = g.view as? MKMapView {
            let pt = g.location(in: mv)
            if pressIsOnVertexHandle(at: pt, on: mv) {
                return false
            }
        }
        return true
    }

    // MARK: Vertex-edit handle drag / delete

    /// Direct pan-driven drag for vertex-edit handles. Bypasses
    /// MapKit's built-in long-press-then-drag which is unreliable
    /// for small custom views. User can just grab and move a vertex
    /// in one gesture. We kill map scroll while dragging so basemap
    /// doesn't slide under the finger.
    @objc func handleVertexPan(_ pan: UIPanGestureRecognizer) {
        guard let view = pan.view as? MKAnnotationView,
              let h = view.annotation as? DrawingVertexHandleAnnotation,
              let mv = attachedMapView
        else { return }

        let pt = pan.location(in: mv)
        let coord = mv.convert(pt, toCoordinateFrom: mv)

        switch pan.state {
        case .began:
            if graphicsLocked { return }
            mv.isScrollEnabled = false
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        case .changed:
            // Update coordinate live so handle follows the finger.
            h.coordinate = coord
        case .ended, .cancelled, .failed:
            mv.isScrollEnabled = true
            guard pan.state == .ended,
                  var shape = drawingStore.shapes.first(where: { $0.id == h.shapeID })
            else { return }
            let newCoord = Coordinate2D(
                latitude: coord.latitude,
                longitude: coord.longitude
            )
            if h.isMidpoint {
                shape.insertEffectiveVertex(newCoord, at: h.vertexIndex)
            } else {
                shape.setEffectiveVertex(h.vertexIndex, to: newCoord)
            }
            drawingStore.update(shape)
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
        default:
            break
        }
    }

    @objc func handleVertexLongPress(_ g: UILongPressGestureRecognizer) {
        let key = ObjectIdentifier(g)
        switch g.state {
        case .began:
            if graphicsLocked { return }
            vertexLongPressMoved[key] = false
            // Subtle "you're holding it" haptic. From here they can
            // either lift (delete) or drag (move).
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
        case .changed:
            vertexLongPressMoved[key] = true
        case .ended:
            let moved = vertexLongPressMoved[key] ?? false
            vertexLongPressMoved.removeValue(forKey: key)
            // Movement during hold = user was dragging, pan recogniser
            // handled it already. Skip delete.
            if moved { return }
            guard let view = g.view as? MKAnnotationView,
                  let h = view.annotation as? DrawingVertexHandleAnnotation,
                  !h.isMidpoint,
                  var shape = drawingStore.shapes.first(where: { $0.id == h.shapeID })
            else { return }
            if shape.removeEffectiveVertex(at: h.vertexIndex) {
                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                drawingStore.update(shape)
            } else {
                UINotificationFeedbackGenerator().notificationOccurred(.warning)
            }
        case .cancelled, .failed:
            vertexLongPressMoved.removeValue(forKey: key)
        default:
            break
        }
    }

    // MARK: Drawing / measure taps

    @objc func handleTap(_ tap: UITapGestureRecognizer) {
        guard let mv = tap.view as? MKMapView else { return }
        let pt = tap.location(in: mv)

        // Calibration mode wins, user is placing fiduciaries.
        if calibration.isCalibrating, let img = pdfImageView {
            if let pdfPoint = img.pdfPoint(forScreenTap: pt, in: mv) {
                calibration.recordTap(pdfPoint: pdfPoint, screenPoint: pt)
                syncCalibrationMarkers()
            }
            return
        }

        // Measure-mode taps add a vertex to the running measurement.
        if measureSession.isActive {
            let coord = mv.convert(pt, toCoordinateFrom: mv)
            measureSession.addPoint(coord)
            refresh(on: mv,
                    // Waypoints render via SwiftUI overlay not MKMapView
                    // annotations, so mv.annotations is empty here - just
                    // grab them from the store.
                    waypoints: waypointStore.waypoints,
                    drawings:  drawingStore.visibleShapes,
                    session:   drawingSession,
                    visibility: nil)
            return
        }

        // Drawing-mode taps add a vertex, never select existing shapes.
        if drawingSession.isDrawing {
            let coord = mv.convert(pt, toCoordinateFrom: mv)
            let autoCommit = drawingSession.addPoint(coord)
            if autoCommit, let shape = drawingSession.finish() {
                drawingStore.add(shape)
            }
            refresh(on: mv,
                    // Waypoints render via SwiftUI overlay not MKMapView
                    // annotations, so mv.annotations is empty here - just
                    // grab them from the store.
                    waypoints: waypointStore.waypoints,
                    drawings:  drawingStore.visibleShapes,
                    session:   drawingSession,
                    visibility: nil)
            return
        }

        // Locked - ignore all graphic taps (no vertex insert, no select).
        // Still let empty-area tap dismiss a stray controls card tho.
        if graphicsLocked {
            if mapVM.selectedWaypointID != nil { mapVM.selectedWaypointID = nil }
            if mapVM.selectedDrawingID  != nil { mapVM.selectedDrawingID  = nil }
            return
        }

        // Midpoint "+" handle tap inserts a new vertex at that
        // coordinate. More discoverable than drag-the-plus.
        if let mid = midpointHandleHitTest(at: pt, on: mv),
           var shape = drawingStore.shapes.first(where: { $0.id == mid.shapeID }) {
            let coord = Coordinate2D(
                latitude: mid.coordinate.latitude,
                longitude: mid.coordinate.longitude
            )
            shape.insertEffectiveVertex(coord, at: mid.vertexIndex)
            drawingStore.update(shape)
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            return
        }

        // Hit-test tactical symbols FIRST (they're drawn on top of
        // drawings in the SwiftUI overlay), then drawings. Bubbles
        // are non-interactive so tap arrives here even when user
        // taps directly on a symbol.
        if let wpID = waypointHitTest(at: pt) {
            mapVM.selectedDrawingID = nil
            mapVM.selectedWaypointID = wpID
            selectionHaptic.prepare()
            selectionHaptic.impactOccurred()
            return
        }
        if let hit = drawingHitTest(at: pt, on: mv) {
            mapVM.selectedWaypointID = nil
            mapVM.selectedDrawingID  = hit.id
            return
        }

        // Tap on empty map dismisses any floating controls card.
        if mapVM.selectedWaypointID != nil {
            mapVM.selectedWaypointID = nil
        }
        if mapVM.selectedDrawingID != nil {
            mapVM.selectedDrawingID = nil
        }
    }

    // MARK: Whole-shape / waypoint drag

    @objc func handleDrawingDrag(_ press: UILongPressGestureRecognizer) {
        guard let mv = press.view as? MKMapView else { return }
        let pt = press.location(in: mv)

        switch press.state {
        case .began:
            if graphicsLocked { return }
            // If user pressed a vertex-edit handle for the selected
            // drawing, bail out and let per-handle gestures (drag,
            // long-press-to-delete) handle it instead.
            if pressIsOnVertexHandle(at: pt, on: mv) {
                return
            }
            guard !drawingSession.isDrawing, !calibration.isCalibrating else { return }
            // Waypoints sit on top of drawings, try them first.
            if let wpID = waypointHitTest(at: pt) {
                draggingWaypointID = wpID
                mv.isScrollEnabled = false
                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                return
            }
            if let hit = drawingHitTest(at: pt, on: mv) {
                draggingDrawingID = hit.id
                lastDragCoord = mv.convert(pt, toCoordinateFrom: mv)
                mv.isScrollEnabled = false
                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                return
            }

        case .changed:
            if let wpID = draggingWaypointID,
               let wp = waypointStore.waypoints.first(where: { $0.id == wpID }) {
                let coord = mv.convert(pt, toCoordinateFrom: mv)
                var updated = wp
                updated.latitude  = coord.latitude
                updated.longitude = coord.longitude
                waypointStore.update(updated)
                return
            }
            guard let id = draggingDrawingID,
                  let start = lastDragCoord,
                  var shape = drawingStore.shapes.first(where: { $0.id == id })
            else { return }
            let current = mv.convert(pt, toCoordinateFrom: mv)
            let dLat = current.latitude  - start.latitude
            let dLon = current.longitude - start.longitude
            shape.coordinates = shape.coordinates.map {
                Coordinate2D(latitude:  $0.latitude  + dLat,
                             longitude: $0.longitude + dLon)
            }
            drawingStore.update(shape)
            lastDragCoord = current

        case .ended, .cancelled, .failed:
            if draggingDrawingID != nil || draggingWaypointID != nil {
                UIImpactFeedbackGenerator(style: .light).impactOccurred()
            }
            draggingDrawingID = nil
            draggingWaypointID = nil
            lastDragCoord = nil
            mv.isScrollEnabled = true

        default:
            break
        }
    }
}
