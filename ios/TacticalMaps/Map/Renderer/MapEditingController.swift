import UIKit
import CoreLocation

/// The drawing / editing gesture layer for the MapKit-free renderer. Attaches
/// tap + long-press + pan recognisers to a `TileMapView` and reproduces what the
/// MKMapView coordinator's gestures did: tap to select / add a draw or measure
/// vertex / insert a midpoint / place a calibration fiduciary / dismiss; long-press
/// to drag a whole shape or waypoint (or delete a vertex); pan to drag a vertex
/// handle. All coordinate work goes through `view.camera`, and waypoint hit-tests
/// read the same published screen positions the SwiftUI symbol overlay uses.
final class MapEditingController: NSObject, UIGestureRecognizerDelegate {

    // Wired once by the container.
    weak var mapVM: MapViewModel?
    weak var waypointStore: WaypointStore?
    weak var drawingStore: DrawingStore?
    weak var drawingSession: DrawingSessionViewModel?
    weak var measureSession: MeasureSession?
    weak var calibration: CalibrationSession?
    weak var handlesView: VertexHandlesOverlayView?

    /// Screen tap -> PDF user point, for placing calibration fiduciaries.
    var pdfScreenTapToPDFPoint: ((CGPoint) -> CGPoint?)?
    /// Push fiduciary markers into the PDF overlay after a calibration tap.
    var refreshCalibrationMarkers: (() -> Void)?

    // Refreshed every updateUIView.
    var graphicsLocked = false
    var handles: [EditHandle] = []

    private weak var view: TileMapView?
    private weak var vertexPan: UIPanGestureRecognizer?

    // Drag state.
    private var draggingHandleIndex: Int?
    private var draggingWaypointID: UUID?
    private var draggingDrawingID: UUID?
    private var lastDragCoord: CLLocationCoordinate2D?
    private var pressedRealHandleIndex: Int?
    private var pressMoved = false

    private let handleHitTolerance: CGFloat = 22

    func attach(to view: TileMapView) {
        self.view = view
        let tap = UITapGestureRecognizer(target: self, action: #selector(onTap))
        let longPress = UILongPressGestureRecognizer(target: self, action: #selector(onLongPress))
        longPress.minimumPressDuration = 0.35
        longPress.allowableMovement = .greatestFiniteMagnitude
        let pan = UIPanGestureRecognizer(target: self, action: #selector(onVertexPan))
        for g in [tap, longPress, pan] as [UIGestureRecognizer] {
            g.delegate = self
            view.addGestureRecognizer(g)
        }
        vertexPan = pan
    }

    // MARK: - Projection helpers

    private func coord(_ pt: CGPoint) -> CLLocationCoordinate2D? { view?.camera.coordinate(for: pt) }
    private func screen(_ c: CLLocationCoordinate2D) -> CGPoint? { view?.camera.screenPoint(for: c) }

    // MARK: - Tap

    @objc private func onTap(_ tap: UITapGestureRecognizer) {
        guard let view else { return }
        let pt = tap.location(in: view)

        // Calibration mode wins - user is placing fiduciaries on the PDF.
        if calibration?.isCalibrating == true {
            if let pdfPoint = pdfScreenTapToPDFPoint?(pt) {
                calibration?.recordTap(pdfPoint: pdfPoint, screenPoint: pt)
                refreshCalibrationMarkers?()
            }
            return
        }

        // Measure-mode taps add a vertex to the running measurement.
        if measureSession?.isActive == true, let c = coord(pt) {
            measureSession?.addPoint(c)
            return
        }

        // Drawing-mode taps add a vertex, never select existing shapes.
        if drawingSession?.isDrawing == true, let c = coord(pt) {
            if let autoCommit = drawingSession?.addPoint(c), autoCommit,
               let shape = drawingSession?.finish() {
                drawingStore?.add(shape)
            }
            return
        }

        // Locked - ignore graphic taps, but still dismiss a stray controls card.
        if graphicsLocked {
            if mapVM?.selectedWaypointID != nil { mapVM?.selectedWaypointID = nil }
            if mapVM?.selectedDrawingID  != nil { mapVM?.selectedDrawingID  = nil }
            return
        }

        // Midpoint "+" tap inserts a new vertex there.
        if let mid = midpointHandleHitTest(at: pt),
           var shape = drawingStore?.shapes.first(where: { $0.id == mid.shapeID }) {
            shape.insertEffectiveVertex(Coordinate2D(latitude: mid.lat, longitude: mid.lon),
                                        at: mid.vertexIndex)
            drawingStore?.update(shape)
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            return
        }

        // Symbols draw on top of drawings, so hit-test them first.
        if let wpID = waypointHitTest(at: pt) {
            mapVM?.selectedDrawingID = nil
            mapVM?.selectedWaypointID = wpID
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            return
        }
        if let hit = drawingHitTest(at: pt) {
            mapVM?.selectedWaypointID = nil
            mapVM?.selectedDrawingID  = hit.id
            return
        }

        // Empty map -> dismiss any floating controls card.
        if mapVM?.selectedWaypointID != nil { mapVM?.selectedWaypointID = nil }
        if mapVM?.selectedDrawingID  != nil { mapVM?.selectedDrawingID  = nil }
    }

    // MARK: - Long-press: whole-shape / waypoint drag, or vertex delete

    @objc private func onLongPress(_ g: UILongPressGestureRecognizer) {
        guard let view else { return }
        let pt = g.location(in: view)

        switch g.state {
        case .began:
            if graphicsLocked { return }
            pressMoved = false
            // A press on a vertex handle belongs to the pan (drag) / delete
            // path, not whole-shape drag. Remember a real handle so a hold
            // with no movement deletes it.
            if let idx = handleIndex(at: pt) {
                pressedRealHandleIndex = handles[idx].isMidpoint ? nil : idx
                UIImpactFeedbackGenerator(style: .light).impactOccurred()
                return
            }
            guard drawingSession?.isDrawing != true,
                  calibration?.isCalibrating != true else { return }
            if let wpID = waypointHitTest(at: pt) {
                draggingWaypointID = wpID
                view.setBrowseGesturesEnabled(false)
                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                return
            }
            if let hit = drawingHitTest(at: pt) {
                draggingDrawingID = hit.id
                lastDragCoord = coord(pt)
                view.setBrowseGesturesEnabled(false)
                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            }

        case .changed:
            pressMoved = true
            if pressedRealHandleIndex != nil { return } // pan drives handle move
            if let wpID = draggingWaypointID,
               let wp = waypointStore?.waypoints.first(where: { $0.id == wpID }),
               let c = coord(pt) {
                // Move the bubble instantly by publishing its new screen pos
                // synchronously, then persist - same trick the bubble's own
                // drag uses so it doesn't snap back on the async re-publish.
                mapVM?.waypointScreenPositions[wpID] = pt
                var updated = wp
                updated.latitude = c.latitude
                updated.longitude = c.longitude
                waypointStore?.update(updated)
                return
            }
            if let id = draggingDrawingID, let start = lastDragCoord,
               var shape = drawingStore?.shapes.first(where: { $0.id == id }),
               let current = coord(pt) {
                let dLat = current.latitude - start.latitude
                let dLon = current.longitude - start.longitude
                shape.coordinates = shape.coordinates.map {
                    Coordinate2D(latitude: $0.latitude + dLat, longitude: $0.longitude + dLon)
                }
                drawingStore?.update(shape)
                lastDragCoord = current
            }

        case .ended, .cancelled, .failed:
            defer {
                pressedRealHandleIndex = nil
                if draggingDrawingID != nil || draggingWaypointID != nil {
                    UIImpactFeedbackGenerator(style: .light).impactOccurred()
                }
                draggingDrawingID = nil
                draggingWaypointID = nil
                lastDragCoord = nil
                view.setBrowseGesturesEnabled(true)
            }
            // Hold-with-no-movement on a real vertex handle deletes it.
            if let idx = pressedRealHandleIndex, g.state == .ended, !pressMoved {
                let h = handles[idx]
                if var shape = drawingStore?.shapes.first(where: { $0.id == h.shapeID }) {
                    if shape.removeEffectiveVertex(at: h.vertexIndex) {
                        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                        drawingStore?.update(shape)
                    } else {
                        UINotificationFeedbackGenerator().notificationOccurred(.warning)
                    }
                }
            }

        default:
            break
        }
    }

    // MARK: - Pan: vertex-handle drag (immediate, no hold)

    @objc private func onVertexPan(_ pan: UIPanGestureRecognizer) {
        guard let view else { return }
        let pt = pan.location(in: view)

        switch pan.state {
        case .began:
            if graphicsLocked { pan.state = .failed; return }
            guard let idx = handleIndex(at: pt) else { pan.state = .failed; return }
            draggingHandleIndex = idx
            view.setBrowseGesturesEnabled(false)
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        case .changed:
            if let idx = draggingHandleIndex {
                handlesView?.liveMove(handleIndex: idx, to: pt)
            }
        case .ended, .cancelled, .failed:
            defer {
                draggingHandleIndex = nil
                view.setBrowseGesturesEnabled(true)
            }
            guard pan.state == .ended, let idx = draggingHandleIndex,
                  handles.indices.contains(idx), let c = coord(pt) else { return }
            let h = handles[idx]
            guard var shape = drawingStore?.shapes.first(where: { $0.id == h.shapeID }) else { return }
            let newCoord = Coordinate2D(latitude: c.latitude, longitude: c.longitude)
            if h.isMidpoint {
                shape.insertEffectiveVertex(newCoord, at: h.vertexIndex)
            } else {
                shape.setEffectiveVertex(h.vertexIndex, to: newCoord)
            }
            drawingStore?.update(shape)
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
        default:
            break
        }
    }

    // MARK: - Gesture delegate

    func gestureRecognizer(_ g: UIGestureRecognizer,
                           shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
        true
    }

    /// The vertex pan only begins on a handle, so the map's browse pan keeps
    /// working everywhere else.
    func gestureRecognizerShouldBegin(_ g: UIGestureRecognizer) -> Bool {
        guard g === vertexPan, let view else { return true }
        if graphicsLocked { return false }
        return handleIndex(at: g.location(in: view)) != nil
    }

    // MARK: - Hit testing (screen space, via the camera)

    private func handleIndex(at pt: CGPoint) -> Int? {
        var best: Int?
        var bestDist = handleHitTolerance
        for (i, h) in handles.enumerated() {
            guard let p = screen(h.coord) else { continue }
            let d = hypot(p.x - pt.x, p.y - pt.y)
            if d <= bestDist { best = i; bestDist = d }
        }
        return best
    }

    private func midpointHandleHitTest(at pt: CGPoint) -> EditHandle? {
        var best: EditHandle?
        var bestDist = handleHitTolerance
        for h in handles where h.isMidpoint {
            guard let p = screen(h.coord) else { continue }
            let d = hypot(p.x - pt.x, p.y - pt.y)
            if d <= bestDist { best = h; bestDist = d }
        }
        return best
    }

    private func waypointHitTest(at pt: CGPoint) -> UUID? {
        guard let mapVM, let waypointStore else { return nil }
        let positions = mapVM.waypointScreenPositions
        let zoom = mapVM.zoomScaleFactor
        for wp in waypointStore.waypoints.reversed() {
            guard let centre = positions[wp.id] else { continue }
            let size = bubbleSize(for: wp, zoomScale: zoom)
            let frame = CGRect(x: centre.x - size.width / 2, y: centre.y - size.height / 2,
                               width: size.width, height: size.height)
            guard frame.contains(pt) else { continue }
            if case .controlMeasure(let measure) = wp.kind {
                let local = CGPoint(x: pt.x - frame.minX, y: pt.y - frame.minY)
                let normalized = CGPoint(x: local.x / max(frame.width, 1),
                                         y: local.y / max(frame.height, 1))
                if !TacticalControlMeasureAlphaMask.containsInVisibleBounds(
                    measure: measure, rotation: wp.rotation, normalizedPoint: normalized) { continue }
            }
            return wp.id
        }
        return nil
    }

    /// Mirror of TacticalSymbolOverlay.bubbleSize - must match or taps miss.
    private func bubbleSize(for wp: Waypoint, zoomScale: CGFloat) -> CGSize {
        switch wp.kind {
        case .controlMeasure:
            return CGSize(width: max(8, 64 * CGFloat(wp.scaleX) * zoomScale),
                          height: max(8, 64 * CGFloat(wp.scaleY) * zoomScale))
        case .military:
            return CGSize(width: 44, height: 44)
        case .generic:
            return CGSize(width: 34, height: 34)
        case .marker:
            return CGSize(width: 34, height: 34)
        }
    }

    private func drawingHitTest(at tap: CGPoint) -> DrawingShape? {
        guard let drawingStore else { return nil }
        let tol: CGFloat = 20
        for shape in drawingStore.visibleShapes.reversed() {
            let pts = shape.clEffectiveCoordinates.compactMap { screen($0) }
            switch shape.kind {
            case .point:
                if let p = pts.first, hypot(p.x - tap.x, p.y - tap.y) <= tol { return shape }
            case .polyline where pts.count >= 2:
                for i in 0 ..< pts.count - 1 {
                    if MapGeometry.distance(from: tap, toSegment: pts[i], pts[i+1]) <= tol { return shape }
                }
            case .polygon where pts.count >= 3:
                if MapGeometry.pointInPolygon(tap, vertices: pts) { return shape }
                for i in 0 ..< pts.count {
                    if MapGeometry.distance(from: tap, toSegment: pts[i], pts[(i+1) % pts.count]) <= tol { return shape }
                }
            default:
                continue
            }
        }
        return nil
    }
}
