import MapKit
import UIKit

// MARK: - Gesture handling
//
// All the gesture-recogniser targets installed in makeUIView: browse-mode
// pan/pinch, centre-pivot rotation, drawing/measure taps, whole-shape drag,
// and vertex-edit handle drag/delete. Extracted verbatim from
// MapContainerView.swift; the recognisers' stored state lives on the
// Coordinator (extensions can't add stored properties).
extension MapContainerView.Coordinator {

    // MARK: Browse-mode gestures

    @objc func userTouchedMap() {
        nextRegionChangeIsUserDriven = true
    }

    /// Centre-pivot rotation. We keep MKMapView's `centerCoordinate`
    /// pinned to the current screen-centre point and only mutate heading.
    /// `g.rotation` is reset every change so we apply frame-to-frame deltas.
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

    /// Refuse to begin our long-press-drag recogniser when the
    /// press lands on a vertex-edit handle. Otherwise our gesture
    /// claims the touches and MapKit's annotation drag can never
    /// fire — meaning the user can't actually move a vertex.
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

    /// Direct pan-driven drag for a vertex-edit handle. Bypasses
    /// MapKit's built-in (and unreliable for small custom views)
    /// long-press-then-drag, so the user can pick up and move a
    /// vertex with a single fluid gesture. While a drag is in
    /// flight we disable the map's own scroll so the basemap
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
            // Update the annotation's coordinate live so the
            // handle visibly follows the finger.
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
            // Subtle "you're holding it" haptic so the user knows
            // the hold has been registered — they can either lift
            // (delete) or drag (move) from here.
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
        case .changed:
            vertexLongPressMoved[key] = true
        case .ended:
            let moved = vertexLongPressMoved[key] ?? false
            vertexLongPressMoved.removeValue(forKey: key)
            // Movement during the hold means the user was dragging —
            // the pan recogniser handled the move; skip delete.
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

        // Calibration mode wins — user is placing fiduciaries.
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
                    waypoints: Array(mv.annotations.compactMap { ($0 as? WaypointAnnotation)?.waypoint }),
                    drawings:  drawingStore.visibleShapes,
                    session:   drawingSession,
                    visibility: nil)
            return
        }

        // Drawing-mode taps add a vertex — never select existing shapes.
        if drawingSession.isDrawing {
            let coord = mv.convert(pt, toCoordinateFrom: mv)
            let autoCommit = drawingSession.addPoint(coord)
            if autoCommit, let shape = drawingSession.finish() {
                drawingStore.add(shape)
            }
            refresh(on: mv,
                    waypoints: Array(mv.annotations.compactMap { ($0 as? WaypointAnnotation)?.waypoint }),
                    drawings:  drawingStore.visibleShapes,
                    session:   drawingSession,
                    visibility: nil)
            return
        }

        // Locked → ignore all graphic taps (no vertex insert, no select);
        // still let an empty-area tap dismiss a stray controls card.
        if graphicsLocked {
            if mapVM.selectedWaypointID != nil { mapVM.selectedWaypointID = nil }
            if mapVM.selectedDrawingID  != nil { mapVM.selectedDrawingID  = nil }
            return
        }

        // Vertex-edit "+" midpoint handles: a single tap inserts
        // a new vertex at the handle's current coordinate (a more
        // discoverable affordance than the drag-the-plus path).
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

        // Hit-test against tactical symbols FIRST (drawn on top of
        // drawings in the SwiftUI overlay), then against drawings.
        // Bubbles are non-interactive so the tap arrives here even
        // when the user taps directly on a symbol.
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
            // If the user pressed a vertex-edit handle for the
            // currently selected drawing, defer to per-handle
            // gestures (drag, long-press-to-delete) instead of
            // grabbing the whole shape.
            if pressIsOnVertexHandle(at: pt, on: mv) {
                return
            }
            guard !drawingSession.isDrawing, !calibration.isCalibrating else { return }
            // Waypoints sit on top of drawings — try them first.
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
