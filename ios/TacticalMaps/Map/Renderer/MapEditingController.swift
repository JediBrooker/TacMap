import UIKit
import CoreLocation

/// Candidate state for a direct-manipulation waypoint drag. Gesture changes
/// remain here (and in the transient screen-position cache) until `commit`
/// performs the single durable store mutation at the end of the gesture.
struct WaypointGesturePreview {
    let original: Waypoint
    private(set) var candidate: Waypoint

    init(_ waypoint: Waypoint) {
        original = waypoint
        candidate = waypoint
    }

    mutating func move(to coordinate: CLLocationCoordinate2D) {
        candidate.latitude = coordinate.latitude
        candidate.longitude = coordinate.longitude
    }

    @discardableResult
    func commit(to store: WaypointStore) throws -> Bool {
        try store.commitEdit(candidate, actionName: L10n.text("Move Waypoint"))
    }
}

/// In-memory candidate for a whole-drawing drag. Repeated `.changed` events
/// update only this preview; the store sees at most one write when the gesture
/// ends successfully.
struct DrawingGesturePreview {
    let original: DrawingShape
    private(set) var candidate: DrawingShape

    init(_ shape: DrawingShape) {
        original = shape
        candidate = shape
    }

    mutating func translate(latitudeDelta: Double, longitudeDelta: Double) {
        candidate.coordinates = candidate.coordinates.map {
            Coordinate2D(
                latitude: $0.latitude + latitudeDelta,
                longitude: $0.longitude + longitudeDelta
            )
        }
    }

    @discardableResult
    func commit(to store: DrawingStore) throws -> Bool {
        try store.commitEdit(candidate, actionName: L10n.text("Move Drawing"))
    }
}

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
    weak var presenceView: PresenceOverlayView?

    /// Screen tap -> PDF user point, for placing calibration fiduciaries.
    var pdfScreenTapToPDFPoint: ((CGPoint) -> CGPoint?)?
    /// Push fiduciary markers into the PDF overlay after a calibration tap.
    var refreshCalibrationMarkers: (() -> Void)?
    /// Renders a transient whole-drawing candidate without publishing it from
    /// the store. Passing nil restores the latest durable renderer snapshot.
    var showDrawingPreview: ((DrawingShape?) -> Void)?
    /// Routed to ContentView's existing "Mission Object Not Saved" alert.
    var onMutationError: ((String) -> Void)?
    /// A remote Unit Sync marker was tapped. Kept on the parent recognizer so
    /// marker views never steal map pan/pinch gestures.
    var onPresenceTap: ((String) -> Void)?

    // Refreshed every updateUIView.
    var graphicsLocked = false
    var handles: [EditHandle] = []

    private weak var view: TileMapView?
    private weak var vertexPan: UIPanGestureRecognizer?

    // Drag state.
    private var draggingHandleIndex: Int?
    private var waypointDrag: WaypointGesturePreview?
    private var waypointDragOriginalScreenPoint: CGPoint?
    private var drawingDrag: DrawingGesturePreview?
    private var drawingDragHandles: [EditHandle]?
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
                guard let drawingStore else { return }
                do {
                    _ = try drawingStore.addDurably(shape)
                } catch {
                    reportMutationFailure(
                        L10n.text("The drawing was not added."), error: error,
                        recovery: L10n.text("Check available storage, then draw it again.")
                    )
                }
            }
            return
        }

        // Presence renders above local mission graphics, so its visible marker
        // wins an ordinary tap. Graphics lock protects edits; it does not turn
        // off communication with an authenticated remote unit.
        if let peerID = presenceView?.peerID(at: pt) {
            mapVM?.selectedWaypointID = nil
            mapVM?.selectedDrawingID = nil
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            onPresenceTap?(peerID)
            return
        }

        // Locked - ignore graphic taps, but still dismiss a stray controls card.
        if graphicsLocked {
            if mapVM?.selectedWaypointID != nil { mapVM?.selectedWaypointID = nil }
            if mapVM?.selectedDrawingID  != nil { mapVM?.selectedDrawingID  = nil }
            return
        }

        // Midpoint "+" tap inserts a new vertex there.
        if let mid = midpointHandleHitTest(at: pt), let drawingStore,
           var shape = drawingStore.shapes.first(where: { $0.id == mid.shapeID }) {
            shape.insertEffectiveVertex(Coordinate2D(latitude: mid.lat, longitude: mid.lon),
                                        at: mid.vertexIndex)
            do {
                _ = try drawingStore.commitEdit(shape, actionName: L10n.text("Insert Drawing Vertex"))
                UIImpactFeedbackGenerator(style: .light).impactOccurred()
            } catch {
                reportMutationFailure(L10n.text("The new drawing vertex was not saved."), error: error)
            }
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
            if let wpID = waypointHitTest(at: pt),
               let waypoint = waypointStore?.waypoints.first(where: { $0.id == wpID }) {
                waypointDrag = WaypointGesturePreview(waypoint)
                waypointDragOriginalScreenPoint = mapVM?.waypointScreenPositions[wpID]
                view.setBrowseGesturesEnabled(false)
                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                return
            }
            if let hit = drawingHitTest(at: pt) {
                drawingDrag = DrawingGesturePreview(hit)
                drawingDragHandles = handles
                lastDragCoord = coord(pt)
                view.setBrowseGesturesEnabled(false)
                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            }

        case .changed:
            pressMoved = true
            if pressedRealHandleIndex != nil { return } // pan drives handle move
            if waypointDrag != nil, let c = coord(pt) {
                waypointDrag?.move(to: c)
                if let id = waypointDrag?.original.id {
                    // Screen-position publication is a transient visual preview,
                    // not mission-model publication. The durable model changes
                    // exactly once in the end-state branch below.
                    mapVM?.waypointScreenPositions[id] = pt
                }
                return
            }
            if drawingDrag != nil, let start = lastDragCoord, let current = coord(pt) {
                let dLat = current.latitude - start.latitude
                let dLon = current.longitude - start.longitude
                drawingDrag?.translate(latitudeDelta: dLat, longitudeDelta: dLon)
                if let candidate = drawingDrag?.candidate {
                    showDrawingPreview?(candidate)
                    drawingDragHandles = drawingDragHandles?.map { handle in
                        guard handle.shapeID == candidate.id else { return handle }
                        return EditHandle(
                            shapeID: handle.shapeID,
                            vertexIndex: handle.vertexIndex,
                            isMidpoint: handle.isMidpoint,
                            lat: handle.lat + dLat,
                            lon: handle.lon + dLon
                        )
                    }
                    if let previewHandles = drawingDragHandles {
                        handlesView?.update(handles: previewHandles)
                    }
                }
                lastDragCoord = current
            }

        case .ended, .cancelled, .failed:
            defer {
                pressedRealHandleIndex = nil
                if drawingDrag != nil || waypointDrag != nil {
                    UIImpactFeedbackGenerator(style: .light).impactOccurred()
                }
                drawingDrag = nil
                drawingDragHandles = nil
                waypointDrag = nil
                waypointDragOriginalScreenPoint = nil
                lastDragCoord = nil
                view.setBrowseGesturesEnabled(true)
            }
            // Hold-with-no-movement on a real vertex handle deletes it.
            if let idx = pressedRealHandleIndex, g.state == .ended, !pressMoved {
                let h = handles[idx]
                if let drawingStore,
                   var shape = drawingStore.shapes.first(where: { $0.id == h.shapeID }) {
                    if shape.removeEffectiveVertex(at: h.vertexIndex) {
                        do {
                            _ = try drawingStore.commitEdit(shape, actionName: L10n.text("Delete Drawing Vertex"))
                            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                        } catch {
                            reportMutationFailure(L10n.text("The drawing vertex was not deleted."), error: error)
                        }
                    } else {
                        UINotificationFeedbackGenerator().notificationOccurred(.warning)
                    }
                }
                return
            }

            if let preview = waypointDrag {
                if g.state == .ended, preview.candidate != preview.original,
                   let waypointStore {
                    do {
                        _ = try preview.commit(to: waypointStore)
                    } catch {
                        restoreWaypointPreview(preview)
                        reportMutationFailure(L10n.text("The waypoint move was not saved."), error: error)
                    }
                } else {
                    restoreWaypointPreview(preview)
                }
            }

            if let preview = drawingDrag {
                if g.state == .ended, preview.candidate != preview.original,
                   let drawingStore {
                    do {
                        _ = try preview.commit(to: drawingStore)
                        // Keep the in-memory candidate visible until the
                        // @Published durable snapshot reaches SwiftUI.
                        DispatchQueue.main.async { [weak self] in
                            self?.showDrawingPreview?(nil)
                        }
                    } catch {
                        restoreDrawingPreview()
                        reportMutationFailure(L10n.text("The drawing move was not saved."), error: error)
                    }
                } else {
                    restoreDrawingPreview()
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
                  handles.indices.contains(idx), let c = coord(pt) else {
                handlesView?.update(handles: handles)
                return
            }
            let h = handles[idx]
            guard let drawingStore,
                  var shape = drawingStore.shapes.first(where: { $0.id == h.shapeID }) else {
                handlesView?.update(handles: handles)
                return
            }
            let newCoord = Coordinate2D(latitude: c.latitude, longitude: c.longitude)
            if h.isMidpoint {
                shape.insertEffectiveVertex(newCoord, at: h.vertexIndex)
            } else {
                shape.setEffectiveVertex(h.vertexIndex, to: newCoord)
            }
            do {
                _ = try drawingStore.commitEdit(shape, actionName: L10n.text("Move Drawing Vertex"))
                UIImpactFeedbackGenerator(style: .light).impactOccurred()
            } catch {
                handlesView?.update(handles: handles)
                reportMutationFailure(L10n.text("The drawing vertex move was not saved."), error: error)
            }
        default:
            break
        }
    }

    private func restoreWaypointPreview(_ preview: WaypointGesturePreview) {
        let point = waypointDragOriginalScreenPoint ?? screen(preview.original.coordinate)
        if let point {
            mapVM?.waypointScreenPositions[preview.original.id] = point
        }
    }

    private func restoreDrawingPreview() {
        showDrawingPreview?(nil)
        handlesView?.update(handles: handles)
    }

    private func reportMutationFailure(_ summary: String,
                                       error: Error,
                                       recovery: String = L10n.text("Check available storage, then try again.")) {
        UINotificationFeedbackGenerator().notificationOccurred(.error)
        onMutationError?("\(summary) \(error.localizedDescription) \(recovery)")
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
