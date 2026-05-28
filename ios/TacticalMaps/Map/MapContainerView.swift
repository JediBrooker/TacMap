import SwiftUI
import MapKit
import Combine
import Grid
import MGRS

/// `UIViewRepresentable` wrapper around `MKMapView`. Hosts the satellite map,
/// waypoint annotations, and drawing overlays (polyline/polygon/point).
///
/// Gesture model:
/// - Pan/pinch flip the VM into browse mode (header reads map centre).
/// - Single tap, *only while drawing mode is active*, adds a vertex to the
///   in-progress shape.
struct MapContainerView: UIViewRepresentable {
    @ObservedObject var mapVM: MapViewModel
    @ObservedObject var locationService: LocationService
    @ObservedObject var waypointStore: WaypointStore
    @ObservedObject var drawingStore: DrawingStore
    @ObservedObject var drawingSession: DrawingSessionViewModel
    @ObservedObject var measureSession: MeasureSession
    @ObservedObject var visibility: LayerVisibility
    @ObservedObject var calibration: CalibrationSession

    func makeUIView(context: Context) -> MKMapView {
        let mv = MKMapView()
        mv.delegate = context.coordinator
        mv.showsUserLocation = visibility.userLocationVisible
        mv.showsCompass = false   // we render our own
        mv.showsScale = false
        // Back to satellite — PDF now renders via MKTileOverlay path which is
        // independent of base-map type.
        mv.mapType = .satellite
        mv.pointOfInterestFilter = .excludingAll
        // Lock the camera flat. MapKit can apply 3D tilt at deep zoom on
        // satellite imagery, which visually distorts annotation views —
        // making fixed-pixel symbols *look* like they're growing or
        // shrinking as the user zooms. Locking pitch keeps the camera
        // straight-down so annotations render at their canonical size.
        mv.isPitchEnabled = false

        // Pan/pinch → browse mode signal.
        let pan   = UIPanGestureRecognizer(target: context.coordinator,
                                           action: #selector(Coordinator.userTouchedMap))
        let pinch = UIPinchGestureRecognizer(target: context.coordinator,
                                             action: #selector(Coordinator.userTouchedMap))
        pan.delegate = context.coordinator
        pinch.delegate = context.coordinator
        mv.addGestureRecognizer(pan)
        mv.addGestureRecognizer(pinch)

        // Disable MKMapView's built-in rotation (which pivots around the
        // midpoint of the two fingers and drags the map sideways), and add
        // our own that pivots around the camera's current centre coordinate
        // — so the map spins in place around the screen centre.
        mv.isRotateEnabled = false
        let rotation = UIRotationGestureRecognizer(
            target: context.coordinator,
            action: #selector(Coordinator.handleRotation(_:))
        )
        rotation.delegate = context.coordinator
        mv.addGestureRecognizer(rotation)

        // Single-tap → add vertex while drawing.
        let tap = UITapGestureRecognizer(target: context.coordinator,
                                         action: #selector(Coordinator.handleTap(_:)))
        tap.delegate = context.coordinator
        // Don't fight the map's built-in double-tap-to-zoom gesture.
        if let dt = mv.gestureRecognizers?.first(where: {
            ($0 as? UITapGestureRecognizer)?.numberOfTapsRequired == 2
        }) {
            tap.require(toFail: dt)
        }
        // Don't swallow the touch — MKMapView's internal annotation tap
        // recognizer needs it too so `didSelect` fires when the user
        // taps a control-measure symbol.
        tap.cancelsTouchesInView = false
        mv.addGestureRecognizer(tap)
        context.coordinator.tapGesture = tap

        // Long-press-drag → reposition a drawing under the finger. We
        // disable the map's scroll while a drag is active so the user is
        // actually dragging the shape, not panning the basemap.
        let press = UILongPressGestureRecognizer(target: context.coordinator,
                                                 action: #selector(Coordinator.handleDrawingDrag(_:)))
        press.minimumPressDuration = 0.35
        press.allowableMovement = .greatestFiniteMagnitude
        press.delegate = context.coordinator
        mv.addGestureRecognizer(press)
        context.coordinator.drawingDragPress = press
        context.coordinator.attachedMapView = mv

        // Programmatic camera moves.
        context.coordinator.cameraRequestSink = mapVM.cameraRequests.sink { region in
            mv.setRegion(region, animated: true)
        }
        // Compass tap → smooth animate camera heading back to 0° (north up),
        // keeping the current centre, altitude, and pitch.
        context.coordinator.resetNorthSink = mapVM.resetNorthRequests.sink { [weak mv] _ in
            guard let mv else { return }
            let cam = MKMapCamera(
                lookingAtCenter:    mv.camera.centerCoordinate,
                fromDistance:       mv.camera.centerCoordinateDistance,
                pitch:              mv.camera.pitch,
                heading:            0
            )
            mv.setCamera(cam, animated: true)
        }

        context.coordinator.syncPDFOverlay(on: mv,
                                           source: mapVM.mapSource,
                                           visible: visibility.pdfOverlayVisible)
        context.coordinator.refresh(on: mv,
                                    waypoints: waypointStore.waypoints,
                                    drawings:  drawingStore.visibleShapes,
                                    session:   drawingSession,
                                    visibility: visibility)
        return mv
    }

    func updateUIView(_ mv: MKMapView, context: Context) {
        mv.showsUserLocation = visibility.userLocationVisible
        context.coordinator.calibration = calibration
        context.coordinator.syncPDFOverlay(on: mv,
                                           source: mapVM.mapSource,
                                           visible: visibility.pdfOverlayVisible)
        // Sync the MGRS-grid toggle through to the coordinator and
        // rebuild — flipping the switch must take effect without
        // waiting for the next pan/zoom.
        let mgrsChanged = context.coordinator.mgrsGridVisibleFlag != visibility.mgrsGridVisible
        context.coordinator.mgrsGridVisibleFlag = visibility.mgrsGridVisible
        if mgrsChanged { context.coordinator.refreshMGRSGrid(on: mv) }
        context.coordinator.refresh(on: mv,
                                    waypoints: waypointStore.waypoints,
                                    drawings:  drawingStore.visibleShapes,
                                    session:   drawingSession,
                                    visibility: visibility)
        // Sync calibration markers + clear when not calibrating.
        context.coordinator.syncCalibrationMarkers()
        // Mirror MapVM's selection state onto MKMapView. When ContentView
        // dismisses the floating controls card (sets the ID to nil), we
        // tell MapKit to deselect the annotation so the user can re-tap
        // it later to bring the card back.
        if mapVM.selectedWaypointID == nil
            && !mv.selectedAnnotations.isEmpty {
            context.coordinator.deselectAll(on: mv)
        }
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(mapVM: mapVM,
                    waypointStore: waypointStore,
                    drawingStore: drawingStore,
                    drawingSession: drawingSession,
                    measureSession: measureSession,
                    calibration: calibration)
    }

    // MARK: - Coordinator

    final class Coordinator: NSObject, MKMapViewDelegate, UIGestureRecognizerDelegate {
        let mapVM: MapViewModel
        let waypointStore: WaypointStore
        let drawingStore: DrawingStore
        let drawingSession: DrawingSessionViewModel
        let measureSession: MeasureSession
        var calibration: CalibrationSession   // mutable so updateUIView can refresh

        var cameraRequestSink: AnyCancellable?
        var resetNorthSink:    AnyCancellable?
        weak var tapGesture: UITapGestureRecognizer?
        weak var drawingDragPress: UILongPressGestureRecognizer?
        weak var attachedMapView: MKMapView?

        /// Style lookup keyed by overlay identity (MKPolyline/MKPolygon don't
        /// carry style metadata themselves).
        private var styleByOverlay: [ObjectIdentifier: DrawingStyle] = [:]
        private var inProgressOverlayIDs: Set<ObjectIdentifier> = []
        /// Drawing-shape id keyed by overlay identity. Lets the renderer
        /// thicken the stroke for whichever shape is currently selected.
        private var shapeIDByOverlay: [ObjectIdentifier: UUID] = [:]
        /// MGRS-grid polyline lookup: which grid-type each registered
        /// overlay represents. Used by the renderer to pick stroke
        /// colour/width per level, and by the refresh routine to remove
        /// only grid polylines when toggling the overlay or panning.
        private var mgrsGridTypeByOverlay: [ObjectIdentifier: GridType] = [:]
        private var mgrsOverlayIDs: Set<ObjectIdentifier> = []
        private var lastMGRSFingerprint: String = ""
        /// Active MGRS label annotations — tracked separately so the
        /// refresh routine can yank just the grid labels without
        /// touching drawing or waypoint annotations.
        private var mgrsLabelAnnotations: [MGRSGridLabelAnnotation] = []

        /// PDF overlay rendered as a UIImageView subview (bypasses MKOverlay
        /// because iOS 26 MapKit silently refuses to draw custom overlays on
        /// satellite imagery). Keyed by the source's UUID.
        private var pdfImageView: PDFImageOverlayView?
        private var pdfSourceID: UUID?

        /// Dark UIView covering the satellite while a PDF is loaded so the
        /// imported map is the only visible content. Removed when the PDF is
        /// hidden or unloaded.
        private var basemapMask: UIView?

        private var nextRegionChangeIsUserDriven = false

        /// Light haptic fired when the user selects a control-measure
        /// waypoint to open the rotate / resize controls card.
        private let selectionHaptic = UIImpactFeedbackGenerator(style: .light)

        /// Fingerprint of the last (waypoints, drawings, in-progress
        /// session, visibility) tuple we rendered. `refresh()` is a no-op
        /// when the fingerprint hasn't changed — this matters because
        /// `updateUIView` fires whenever ANY published value on the VM
        /// changes (incl. the symbol-selection state), and otherwise we
        /// would tear down + re-add every annotation on every selection,
        /// which immediately deselects the just-tapped annotation and
        /// closes the rotate / resize card.
        private var lastRefreshFingerprint: String = ""

        /// True while refresh() is tearing down + re-adding annotations.
        /// MapKit fires `didDeselect` when an annotation is removed; we
        /// suppress the selection-state clear during a refresh so the
        /// controls card stays open while the user drags a slider (which
        /// publishes a new waypoint rotation/scale and triggers refresh).
        private var isRebuildingAnnotations = false

        init(mapVM: MapViewModel,
             waypointStore: WaypointStore,
             drawingStore: DrawingStore,
             drawingSession: DrawingSessionViewModel,
             measureSession: MeasureSession,
             calibration: CalibrationSession) {
            self.mapVM = mapVM
            self.waypointStore = waypointStore
            self.drawingStore = drawingStore
            self.drawingSession = drawingSession
            self.measureSession = measureSession
            self.calibration = calibration
        }

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

        func mapView(_ mv: MKMapView, regionDidChangeAnimated animated: Bool) {
            let byUser = nextRegionChangeIsUserDriven
            nextRegionChangeIsUserDriven = false
            mapVM.mapRegionDidChange(mv.region, animated: animated, byUser: byUser)
            mapVM.mapCameraDidChange(heading: mv.camera.heading)
            mapVM.currentMetresPerPoint = metresPerPoint(in: mv)
            pdfImageView?.updateFrame(in: mv)
            publishOverlayState(in: mv)
            refreshMGRSGrid(on: mv)
        }

        /// Fires on every render frame during pan/zoom/rotate — the only delegate
        /// callback that captures rotation gestures. We also use this to keep
        /// the PDF image view glued to its geographic bounds in real time.
        func mapViewDidChangeVisibleRegion(_ mv: MKMapView) {
            mapVM.mapCameraDidChange(heading: mv.camera.heading)
            mapVM.currentMetresPerPoint = metresPerPoint(in: mv)
            pdfImageView?.updateFrame(in: mv)
            publishOverlayState(in: mv)
        }

        /// Cached waypoint list captured on each `refresh()` so the
        /// camera-change callbacks can recompute screen positions
        /// without going back through updateUIView.
        private var currentWaypoints: [Waypoint] = []

        /// Republish per-waypoint screen positions + the current zoom
        /// scale factor for `TacticalSymbolOverlay` to consume. Runs
        /// on every camera change so the SwiftUI overlay's symbols
        /// track the map as it pans and zooms.
        ///
        /// The actual mutation of `@Published` properties is deferred
        /// to the next runloop tick because some call paths reach
        /// this from inside `updateUIView` (via `refresh()`), and
        /// SwiftUI forbids mutating observable state synchronously
        /// during a view-update pass — it triggers the "Publishing
        /// changes from within view updates is not allowed" warning
        /// and an infinite re-render loop.
        private func publishOverlayState(in mv: MKMapView) {
            // Publish screen positions for EVERY waypoint kind —
            // tactical control measures, military units, and generic
            // pins. The SwiftUI overlay renders them all.
            var positions: [UUID: CGPoint] = [:]
            for wp in currentWaypoints {
                positions[wp.id] = mv.convert(wp.coordinate, toPointTo: mv)
            }
            let zoom = currentZoomScaleFactor(for: mv)

            DispatchQueue.main.async { [weak self, weak mv] in
                guard let self else { return }
                self.mapVM.waypointScreenPositions = positions
                self.mapVM.zoomScaleFactor = zoom
                if self.mapVM.screenToCoordinate == nil {
                    self.mapVM.screenToCoordinate = { [weak mv] pt in
                        guard let mv else {
                            return CLLocationCoordinate2D(latitude: 0, longitude: 0)
                        }
                        return mv.convert(pt, toCoordinateFrom: mv)
                    }
                }
            }
        }

        /// Convert the current map region into a unit scale where
        /// `1.0` corresponds to the reference zoom (1 metre per point).
        /// Halve metresPerPoint (zoom in) → scale 2.0. Double it (zoom
        /// out) → scale 0.5. Clamped to [0.005, 50] so symbols stay
        /// visible from "single building" zoom all the way out to a
        /// continental view.
        func currentZoomScaleFactor(for mv: MKMapView) -> CGFloat {
            let mpp = metresPerPoint(in: mv)
            let raw = referenceMetresPerPoint / mpp
            return CGFloat(max(0.005, min(raw, 50.0)))
        }

        /// Pure metres-per-point at the current camera, no clamping.
        /// Used by `defaultScaleForNewSymbol` to size new symbols
        /// relative to the screen at placement time.
        func metresPerPoint(in mv: MKMapView) -> Double {
            // 111_000 m / degree latitude — close enough for sizing.
            let latDeltaMetres = mv.region.span.latitudeDelta * 111_000
            let viewHeight = max(Double(mv.bounds.height), 1)
            return latDeltaMetres / viewHeight
        }

        /// Reference zoom where `waypoint.scale = 1.0` renders at the
        /// symbol's base point size. Lower = bigger symbols at all
        /// zooms; higher = smaller.
        private let referenceMetresPerPoint: Double = 1.0

        // MARK: Drawing tap

        // MARK: Annotation selection → floating controls

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

        // MARK: Drag-to-move

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

        /// Long-press a real vertex handle → delete the vertex if the
        /// shape still meets its kind's minimum. The drawing snaps back
        /// to a baked, transform-free state (rotation/scale reset).
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
            guard g.state == .began,
                  let view = g.view as? MKAnnotationView,
                  let h = view.annotation as? DrawingVertexHandleAnnotation,
                  !h.isMidpoint,
                  var shape = drawingStore.shapes.first(where: { $0.id == h.shapeID })
            else { return }
            if shape.removeEffectiveVertex(at: h.vertexIndex) {
                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                drawingStore.update(shape)
            } else {
                // Tell the user why nothing happened.
                UINotificationFeedbackGenerator().notificationOccurred(.warning)
            }
        }

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

        /// Hit-test the tactical-symbol overlay using the published
        /// screen positions and per-kind sizes. The overlay itself is
        /// non-interactive (touches pass through to MKMapView so pinch
        /// works), so selection has to happen from here.
        private func waypointHitTest(at pt: CGPoint) -> UUID? {
            let positions = mapVM.waypointScreenPositions
            let zoom = mapVM.zoomScaleFactor
            // Most-recent-on-top: walk the waypoints in reverse to
            // match the overlay's draw order.
            for wp in waypointStore.waypoints.reversed() {
                guard let centre = positions[wp.id] else { continue }
                let size = waypointBubbleSize(for: wp, zoomScale: zoom)
                let frame = CGRect(
                    x: centre.x - size.width  / 2,
                    y: centre.y - size.height / 2,
                    width:  size.width,
                    height: size.height
                )
                guard frame.contains(pt) else { continue }
                // Control measures: extra alpha-mask test so taps in
                // the transparent corners of a hexagonal/triangle
                // graphic fall through. Military / generic glyphs fill
                // their frame solidly so a rect check is enough.
                if case .controlMeasure(let measure) = wp.kind {
                    let local = CGPoint(x: pt.x - frame.minX, y: pt.y - frame.minY)
                    let normalized = CGPoint(
                        x: local.x / max(frame.width,  1),
                        y: local.y / max(frame.height, 1)
                    )
                    if !TacticalControlMeasureAlphaMask.containsInVisibleBounds(
                        measure: measure,
                        rotation: wp.rotation,
                        normalizedPoint: normalized
                    ) { continue }
                }
                return wp.id
            }
            return nil
        }

        /// Mirror of TacticalSymbolOverlay.bubbleSize so the tap
        /// hit-test sees the same bubble geometry the overlay draws.
        private func waypointBubbleSize(for wp: Waypoint, zoomScale: CGFloat) -> CGSize {
            switch wp.kind {
            case .controlMeasure:
                let w = max(8, 64 * CGFloat(wp.scaleX) * zoomScale)
                let h = max(8, 64 * CGFloat(wp.scaleY) * zoomScale)
                return CGSize(width: w, height: h)
            case .military:
                return CGSize(width: 44, height: 44)
            case .generic:
                return CGSize(width: 34, height: 34)
            }
        }

        // MARK: Drag-to-move drawings

        /// ID of the drawing currently being dragged via long-press, plus
        /// the last touch coordinate (so each .changed event applies an
        /// incremental delta).
        private var draggingDrawingID: UUID?
        private var lastDragCoord: CLLocationCoordinate2D?

        // MARK: MGRS grid overlay

        /// Snapshot of the toggle so refreshMGRSGrid can read it without
        /// taking the LayerVisibility object as a parameter on every
        /// region-change callback.
        var mgrsGridVisibleFlag: Bool = false

        /// Rebuild the visible MGRS-grid polylines. Cheap: bounded by
        /// what's actually on screen, and skipped entirely when the
        /// toggle is off. We bucket by a coarse fingerprint so panning
        /// inside a stable cell doesn't re-tessellate the same lines.
        func refreshMGRSGrid(on mv: MKMapView) {
            // Always drop the existing overlay set + label annotations
            // first — if the toggle is off, this leaves the map clean.
            if !mgrsOverlayIDs.isEmpty {
                let toRemove = mv.overlays.filter { mgrsOverlayIDs.contains(ObjectIdentifier($0)) }
                mv.removeOverlays(toRemove)
                mgrsOverlayIDs.removeAll()
                mgrsGridTypeByOverlay.removeAll()
            }
            if !mgrsLabelAnnotations.isEmpty {
                mv.removeAnnotations(mgrsLabelAnnotations)
                mgrsLabelAnnotations.removeAll()
            }
            guard mgrsGridVisibleFlag else {
                lastMGRSFingerprint = ""
                return
            }

            // Skip the heavy work when the rounded region hasn't moved
            // enough to change which 100km / 10km / 1km cells are visible.
            let region = mv.region
            let widthPts = mv.bounds.width
            let fp = String(format: "%.3f,%.3f,%.3f,%.3f,%.0f",
                            region.center.latitude,
                            region.center.longitude,
                            region.span.latitudeDelta,
                            region.span.longitudeDelta,
                            widthPts)
            if fp == lastMGRSFingerprint { return }
            lastMGRSFingerprint = fp

            let built = MGRSGridRenderer.build(for: region, mapWidthPoints: widthPts)
            for seg in built.lines {
                mgrsGridTypeByOverlay[ObjectIdentifier(seg.polyline)] = seg.gridType
                mgrsOverlayIDs.insert(ObjectIdentifier(seg.polyline))
                mv.addOverlay(seg.polyline, level: .aboveLabels)
            }
            for label in built.labels {
                let ann = MGRSGridLabelAnnotation(text: label.text,
                                                  coordinate: label.coordinate,
                                                  gridType: label.gridType,
                                                  isVertical: label.isVertical)
                mgrsLabelAnnotations.append(ann)
            }
            if !mgrsLabelAnnotations.isEmpty {
                mv.addAnnotations(mgrsLabelAnnotations)
            }
        }

        /// True if the press began on (or close to) any vertex-edit
        /// handle annotation. Used by the whole-shape drag gesture to
        /// step aside and let the handle's own drag run instead.
        private func pressIsOnVertexHandle(at pt: CGPoint, on mv: MKMapView) -> Bool {
            let tol: CGFloat = 22
            for ann in mv.annotations {
                guard let h = ann as? DrawingVertexHandleAnnotation else { continue }
                let p = mv.convert(h.coordinate, toPointTo: mv)
                if hypot(p.x - pt.x, p.y - pt.y) <= tol { return true }
            }
            return false
        }

        /// Return the midpoint ("+" insertion) handle nearest to the
        /// tap point, or nil if the tap missed all of them. Skips real
        /// vertices so tap-to-insert and tap-on-vertex don't collide.
        private func midpointHandleHitTest(at pt: CGPoint, on mv: MKMapView)
            -> DrawingVertexHandleAnnotation?
        {
            let tol: CGFloat = 22
            var best: DrawingVertexHandleAnnotation?
            var bestDist: CGFloat = .infinity
            for ann in mv.annotations {
                guard let h = ann as? DrawingVertexHandleAnnotation, h.isMidpoint else { continue }
                let p = mv.convert(h.coordinate, toPointTo: mv)
                let d = hypot(p.x - pt.x, p.y - pt.y)
                if d <= tol && d < bestDist {
                    best = h
                    bestDist = d
                }
            }
            return best
        }

        /// ID of the waypoint currently being dragged via long-press.
        /// Only one of (waypoint, drawing) drags at a time.
        private var draggingWaypointID: UUID?

        @objc func handleDrawingDrag(_ press: UILongPressGestureRecognizer) {
            guard let mv = press.view as? MKMapView else { return }
            let pt = press.location(in: mv)

            switch press.state {
            case .began:
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

        /// Hit-test the visible drawings against a screen-space tap. Returns
        /// the topmost shape within the tap tolerance, or nil. Uses a 20pt
        /// screen-space tolerance so thin strokes still feel tappable.
        private func drawingHitTest(at tap: CGPoint, on mv: MKMapView) -> DrawingShape? {
            let tolerance: CGFloat = 20
            for shape in drawingStore.visibleShapes.reversed() {
                let screen = shape.effectiveCoordinates.map {
                    mv.convert(CLLocationCoordinate2D(latitude: $0.latitude,
                                                     longitude: $0.longitude),
                               toPointTo: mv)
                }
                switch shape.kind {
                case .point:
                    if let p = screen.first,
                       hypot(p.x - tap.x, p.y - tap.y) <= tolerance {
                        return shape
                    }
                case .polyline where screen.count >= 2:
                    for i in 0 ..< screen.count - 1 {
                        if Self.distance(from: tap, to: screen[i], screen[i+1]) <= tolerance {
                            return shape
                        }
                    }
                case .polygon where screen.count >= 3:
                    if Self.pointInPolygon(tap, vertices: screen) {
                        return shape
                    }
                    for i in 0 ..< screen.count {
                        let next = screen[(i + 1) % screen.count]
                        if Self.distance(from: tap, to: screen[i], next) <= tolerance {
                            return shape
                        }
                    }
                default:
                    continue
                }
            }
            return nil
        }

        /// Shortest distance from point p to segment a-b (CGPoint screen coords).
        private static func distance(from p: CGPoint, to a: CGPoint, _ b: CGPoint) -> CGFloat {
            let dx = b.x - a.x, dy = b.y - a.y
            let l2 = dx * dx + dy * dy
            if l2 == 0 { return hypot(p.x - a.x, p.y - a.y) }
            var t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / l2
            t = max(0, min(1, t))
            let projX = a.x + t * dx, projY = a.y + t * dy
            return hypot(p.x - projX, p.y - projY)
        }

        /// Ray-casting point-in-polygon test on screen coordinates.
        private static func pointInPolygon(_ p: CGPoint, vertices: [CGPoint]) -> Bool {
            guard vertices.count >= 3 else { return false }
            var inside = false
            var j = vertices.count - 1
            for i in 0 ..< vertices.count {
                let vi = vertices[i], vj = vertices[j]
                if ((vi.y > p.y) != (vj.y > p.y)) &&
                   (p.x < (vj.x - vi.x) * (p.y - vi.y) / (vj.y - vi.y) + vi.x) {
                    inside.toggle()
                }
                j = i
            }
            return inside
        }

        // MARK: PDF overlay sync

        /// Attach/detach the PDF image view so its presence matches
        /// `(source is PDFMapSource) && visible`. Resizes its frame on every
        /// camera change to stay anchored to the PDF’s geographic bounds.
        ///
        /// When the PDF is active we also drop a dark UIView between the
        /// satellite tiles and the PDF so the imported map is the only
        /// visible content (no satellite trying to align underneath).
        func syncPDFOverlay(on mv: MKMapView,
                            source: MapSource,
                            visible: Bool) {
            let pdfSource = source as? PDFMapSource
            let newID = pdfSource?.id
            let pdfActive = (pdfSource != nil) && visible

            // Manage basemap mask.
            if pdfActive && basemapMask == nil {
                let mask = UIView(frame: mv.bounds)
                mask.backgroundColor = UIColor(white: 0.10, alpha: 1.0)  // near-black
                mask.autoresizingMask = [.flexibleWidth, .flexibleHeight]
                mask.isUserInteractionEnabled = false
                mv.addSubview(mask)
                basemapMask = mask
            } else if !pdfActive, let mask = basemapMask {
                mask.removeFromSuperview()
                basemapMask = nil
            }

            // Remove if source changed, became non-PDF, or visibility flipped off.
            if let existing = pdfImageView,
               newID != pdfSourceID || !visible || pdfSource == nil {
                NSLog("[PDF] removing image view")
                existing.removeFromSuperview()
                pdfImageView = nil
                pdfSourceID = nil
            }

            // Attach if needed.
            if pdfImageView == nil, visible,
               let src = pdfSource,
               let bounds = src.bounds,
               let image = src.renderedImage() {
                NSLog("[PDF] attaching image view for \(src.displayName) (\(Int(image.size.width))x\(Int(image.size.height)))")
                let view = PDFImageOverlayView(
                    image: image,
                    southWest: bounds.southWest,
                    northEast: bounds.northEast,
                    pdfRenderRect: src.pdfRenderRect
                )
                // Insert near the top of the subview hierarchy so it sits
                // above the satellite tiles. MKMapView's annotations live in
                // separate sibling views above ours — waypoints and the user
                // dot remain visible.
                // PDF goes on top of the dark mask (which was added just above).
                mv.addSubview(view)
                view.updateFrame(in: mv)
                pdfImageView = view
                pdfSourceID = newID

                // Fly to the PDF if it's not in the current viewport.
                let visibleRect = mv.visibleMapRect
                let pdfMapRect = MKMapRect(
                    origin: MKMapPoint(bounds.northEast).x < MKMapPoint(bounds.southWest).x
                        ? MKMapPoint(bounds.northEast)
                        : MKMapPoint(x: MKMapPoint(bounds.southWest).x,
                                      y: MKMapPoint(bounds.northEast).y),
                    size: MKMapSize(
                        width:  abs(MKMapPoint(bounds.northEast).x - MKMapPoint(bounds.southWest).x),
                        height: abs(MKMapPoint(bounds.northEast).y - MKMapPoint(bounds.southWest).y)
                    )
                )
                if !pdfMapRect.intersects(visibleRect) {
                    let span = MKCoordinateSpan(
                        latitudeDelta:  abs(bounds.northEast.latitude  - bounds.southWest.latitude)  * 1.2,
                        longitudeDelta: abs(bounds.northEast.longitude - bounds.southWest.longitude) * 1.2
                    )
                    NSLog("[PDF] off-screen — flying camera to \(bounds.centre.latitude),\(bounds.centre.longitude)")
                    mv.setRegion(MKCoordinateRegion(center: bounds.centre, span: span), animated: true)
                }
            }

            // Keep the existing view's frame fresh against current camera.
            pdfImageView?.updateFrame(in: mv)
        }

        // MARK: Refresh

        /// Rebuilds all annotations + overlays from the current model. Cheap
        /// enough for prototype scale; for a production app, diff instead.
        ///
        /// Short-circuits when the (waypoints, drawings, session, visibility)
        /// fingerprint hasn't changed since the last call. This matters
        /// because `updateUIView` fires on every `MapViewModel` publication,
        /// including pure UI state like `selectedControlMeasureWaypointID`,
        /// and rebuilding all annotations during the same runloop tick as
        /// `didSelect` would immediately deselect the just-tapped one.
        func refresh(on mv: MKMapView,
                     waypoints: [Waypoint],
                     drawings:  [DrawingShape],
                     session:   DrawingSessionViewModel,
                     visibility: LayerVisibility?) {
            let fingerprint = makeRefreshFingerprint(
                waypoints: waypoints,
                drawings:  drawings,
                session:   session,
                measureSession: measureSession,
                visibility: visibility
            )
            // Always cache so the per-frame overlay-position publisher
            // has the latest list, even when the fingerprint is the
            // same and we early-out below.
            currentWaypoints = waypoints
            // Also republish so SwiftUI overlay catches new waypoints
            // / removals immediately (camera-change publisher won't
            // fire until the next interaction).
            publishOverlayState(in: mv)

            if fingerprint == lastRefreshFingerprint { return }
            lastRefreshFingerprint = fingerprint

            // Capture the currently-selected waypoint annotation so we
            // can re-select it after we tear annotations down — the
            // user might be in the middle of dragging the rotate slider.
            let selectedID = mapVM.selectedWaypointID

            isRebuildingAnnotations = true
            defer { isRebuildingAnnotations = false }

            // --- Waypoint annotations ---
            let existingWaypointAnns = mv.annotations.compactMap { $0 as? WaypointAnnotation }
            mv.removeAnnotations(existingWaypointAnns)

            // --- Drawing point annotations ---
            let existingDrawingAnns = mv.annotations.compactMap { $0 as? DrawingPointAnnotation }
            mv.removeAnnotations(existingDrawingAnns)

            // --- Drawing label annotations (cleared then re-added per refresh) ---
            let existingLabelAnns = mv.annotations.compactMap { $0 as? DrawingLabelAnnotation }
            mv.removeAnnotations(existingLabelAnns)

            // --- In-progress vertex dots ---
            let existingVertexAnns = mv.annotations.compactMap { $0 as? DrawingVertexAnnotation }
            mv.removeAnnotations(existingVertexAnns)

            // --- Vertex-edit handles (rebuilt whenever selection changes) ---
            let existingHandleAnns = mv.annotations.compactMap { $0 as? DrawingVertexHandleAnnotation }
            mv.removeAnnotations(existingHandleAnns)

            self.labelsVisible = visibility?.drawingLabelsVisible ?? true

            // --- Overlays ---
            mv.removeOverlays(mv.overlays)
            styleByOverlay.removeAll()
            inProgressOverlayIDs.removeAll()
            shapeIDByOverlay.removeAll()

            // ALL waypoint kinds are rendered by TacticalSymbolOverlay
            // (a SwiftUI overlay above the map) — keep them all out
            // of the MKAnnotation pipeline so MapKit doesn't manage
            // their views and gesture handling is consistent across
            // kinds. Selection / drag are handled by the overlay
            // itself.
            _ = selectedID  // No re-selection needed (no MKAnnotations).

            // Add finished drawings if visible.
            if visibility?.drawingsVisible ?? true {
                for shape in drawings {
                    addShape(shape, to: mv, inProgress: false)
                }
            }

            // Add in-progress overlay (always visible while drawing).
            if session.isDrawing && !session.inProgressCoordinates.isEmpty {
                let pseudo = DrawingShape(
                    kind: session.activeKind ?? .polyline,
                    coordinates: session.inProgressCoordinates,
                    style: DrawingStyle()
                )
                addShape(pseudo, to: mv, inProgress: true)
            }

            // Measure-tool polyline. Drawn dashed in the tactical-orange
            // accent so it reads as a "tool" overlay distinct from saved
            // drawings.
            if measureSession.isActive && measureSession.points.count >= 2 {
                let coords = measureSession.points
                let line = MKPolyline(coordinates: coords, count: coords.count)
                let style = DrawingStyle(
                    strokeColorHex: "#FFA500",
                    fillColorHex:   nil,
                    strokeWidth:    3.0,
                    fillOpacity:    0,
                    dashPattern:    [6, 4]
                )
                styleByOverlay[ObjectIdentifier(line)] = style
                inProgressOverlayIDs.insert(ObjectIdentifier(line))
                mv.addOverlay(line)
            }

            // Vertex dots: every tapped point during drawing/measuring
            // gets a small marker so the user can see where their taps
            // landed even before the polyline connects two of them.
            let drawColor = UIColor(hex: drawingSession.strokeColorHex)
            let measureColor = UIColor(red: 1, green: 0.65, blue: 0.18, alpha: 1)
            if drawingSession.isDrawing {
                for c in drawingSession.inProgressCoordinates {
                    let ann = DrawingVertexAnnotation(color: drawColor)
                    ann.coordinate = CLLocationCoordinate2D(latitude: c.latitude, longitude: c.longitude)
                    mv.addAnnotation(ann)
                }
            }
            if measureSession.isActive {
                for c in measureSession.points {
                    let ann = DrawingVertexAnnotation(color: measureColor)
                    ann.coordinate = c
                    mv.addAnnotation(ann)
                }
            }

            // Vertex-edit handles for the currently selected polyline /
            // polygon. We render them at the EFFECTIVE coordinates so the
            // handles sit on top of the rendered shape. Mutations bake the
            // rotation/scale transform before persisting (see
            // `DrawingShape.setEffectiveVertex`).
            if let selectedID = mapVM.selectedDrawingID,
               let shape = drawings.first(where: { $0.id == selectedID }),
               shape.kind == .polyline || shape.kind == .polygon {
                let coords = shape.clEffectiveCoordinates
                // Real vertex handles — draggable, long-press to delete.
                for (i, c) in coords.enumerated() {
                    let h = DrawingVertexHandleAnnotation(
                        shapeID: shape.id,
                        vertexIndex: i,
                        isMidpoint: false,
                        coordinate: c
                    )
                    mv.addAnnotation(h)
                }
                // Midpoint insertion handles. Polylines: between each
                // adjacent pair. Polygons: also between last and first
                // so the user can split the closing segment.
                let segmentCount = shape.kind == .polygon ? coords.count : coords.count - 1
                for i in 0..<max(segmentCount, 0) {
                    let a = coords[i]
                    let b = coords[(i + 1) % coords.count]
                    let mid = CLLocationCoordinate2D(
                        latitude:  (a.latitude  + b.latitude)  / 2,
                        longitude: (a.longitude + b.longitude) / 2
                    )
                    let h = DrawingVertexHandleAnnotation(
                        shapeID: shape.id,
                        vertexIndex: i + 1,
                        isMidpoint: true,
                        coordinate: mid
                    )
                    mv.addAnnotation(h)
                }
            }
        }

        /// Compact identity string used to decide whether `refresh()`
        /// has work to do. Includes every field that affects what we
        /// render — coords, kind, rotation, scale, name, notes-presence,
        /// elevation-presence — so any meaningful mutation produces a
        /// new string and triggers a rebuild.
        private func makeRefreshFingerprint(waypoints: [Waypoint],
                                                   drawings:  [DrawingShape],
                                                   session:   DrawingSessionViewModel,
                                                   measureSession: MeasureSession,
                                                   visibility: LayerVisibility?) -> String {
            var parts: [String] = []
            parts.reserveCapacity(waypoints.count + drawings.count + 3)
            for w in waypoints {
                let elev = w.elevation.map { String($0) } ?? ""
                let notes = w.notes ?? ""
                parts.append("w|\(w.id.uuidString)|\(w.latitude)|\(w.longitude)|\(w.kindFingerprint)|\(w.rotation)|\(w.scaleX)|\(w.scaleY)|\(w.name)|\(notes)|\(elev)")
            }
            for d in drawings {
                // Hash every vertex so mid-shape edits (drag a single
                // handle, insert/delete a midpoint) invalidate the
                // cached fingerprint. Cheap — drawing counts are tiny.
                var coordsHash = Hasher()
                for c in d.coordinates {
                    coordsHash.combine(c.latitude)
                    coordsHash.combine(c.longitude)
                }
                let selected = mapVM.selectedDrawingID == d.id
                parts.append("d|\(d.id.uuidString)|\(d.kind.rawValue)|\(d.coordinates.count)|\(coordsHash.finalize())|\(d.style.strokeColorHex)|\(d.layerID.uuidString)|\(d.rotation)|\(d.scaleX)|\(d.scaleY)|\(d.style.dashPattern != nil)|\(d.name ?? "")|\(selected)")
            }
            parts.append("s|\(session.isDrawing)|\(session.inProgressCoordinates.count)|\(session.activeKind?.rawValue ?? "-")")
            parts.append("m|\(measureSession.isActive)|\(measureSession.points.count)")
            parts.append("v|\(visibility?.waypointsVisible ?? true)|\(visibility?.drawingsVisible ?? true)")
            return parts.joined(separator: ";")
        }

        // MARK: Calibration marker sync

        /// Forwarded into the PDFImageOverlayView; safe to call whenever —
        /// clears markers when no calibration is active.
        func syncCalibrationMarkers() {
            guard let img = pdfImageView else { return }
            if calibration.isCalibrating {
                img.syncFiduciaryMarkers(
                    calibration.fiduciaries,
                    pendingPDFPoint: calibration.pendingTap?.pdfPoint
                )
            } else {
                img.syncFiduciaryMarkers([], pendingPDFPoint: nil)
            }
        }

        /// Snapshot of the current label-visibility toggle. Captured in
        /// `refresh()` so addShape's label-add branch can read it.
        private var labelsVisible: Bool = true

        private func addShape(_ shape: DrawingShape, to mv: MKMapView, inProgress: Bool) {
            // In-progress shapes are drawn as-typed; finished shapes use
            // their effective coordinates (rotation + W/H applied).
            let coords = inProgress ? shape.clCoordinates : shape.clEffectiveCoordinates

            // Drop a label annotation if the user named the shape (only
            // for finished shapes; in-progress drawings have no name yet)
            // and the user hasn't hidden drawing labels via the Layers sheet.
            if !inProgress,
               labelsVisible,
               let name = shape.name?.trimmingCharacters(in: .whitespaces),
               !name.isEmpty,
               let anchor = shape.labelAnchor {
                let labelAnn = DrawingLabelAnnotation(shape: shape, text: name)
                labelAnn.coordinate = anchor
                mv.addAnnotation(labelAnn)
            }

            switch shape.kind {
            case .point:
                guard let c = coords.first else { return }
                let ann = DrawingPointAnnotation(shape: shape)
                ann.coordinate = c
                mv.addAnnotation(ann)

            case .polyline:
                guard coords.count >= 2 else { return }
                let line = MKPolyline(coordinates: coords, count: coords.count)
                styleByOverlay[ObjectIdentifier(line)] = shape.style
                if !inProgress { shapeIDByOverlay[ObjectIdentifier(line)] = shape.id }
                if inProgress { inProgressOverlayIDs.insert(ObjectIdentifier(line)) }
                mv.addOverlay(line)

            case .polygon:
                guard coords.count >= 2 else { return }
                let poly = MKPolygon(coordinates: coords, count: coords.count)
                styleByOverlay[ObjectIdentifier(poly)] = shape.style
                if !inProgress { shapeIDByOverlay[ObjectIdentifier(poly)] = shape.id }
                if inProgress { inProgressOverlayIDs.insert(ObjectIdentifier(poly)) }
                mv.addOverlay(poly)
                // For in-progress polygon, also draw the open edge as a dashed polyline
                // so the user can see what they're tracing before closing the ring.
                if inProgress {
                    let line = MKPolyline(coordinates: coords, count: coords.count)
                    styleByOverlay[ObjectIdentifier(line)] = shape.style
                    inProgressOverlayIDs.insert(ObjectIdentifier(line))
                    mv.addOverlay(line)
                }
            }
        }

        // MARK: Renderers / annotation views

        func mapView(_ mv: MKMapView, rendererFor overlay: MKOverlay) -> MKOverlayRenderer {
            // PDF basemap is no longer an MKOverlay — it's a UIImageView
            // subview (see syncPDFOverlay). This delegate handles drawings only.

            let key = ObjectIdentifier(overlay)
            // MGRS grid line — pick stroke width from grid type, colour
            // is a shared neutral dark-grey ink so the grid matches
            // across iOS / Android and stays readable on any basemap.
            if let gridType = mgrsGridTypeByOverlay[key],
               let line = overlay as? MKPolyline {
                let r = MKPolylineRenderer(polyline: line)
                r.strokeColor = MGRSGridRenderer.inkColor
                r.lineWidth = MGRSGridRenderer.lineWidth(for: gridType)
                return r
            }
            let style = styleByOverlay[key] ?? .default
            let inProgress = inProgressOverlayIDs.contains(key)
            // Selection glow: when this overlay's shape is the one whose
            // controls card is open, bump the stroke width so the shape
            // visibly "lifts" off the map.
            let isSelected = shapeIDByOverlay[key]
                .map { $0 == mapVM.selectedDrawingID } ?? false
            let selectionBoost: CGFloat = isSelected ? 3.0 : 0.0

            if let line = overlay as? MKPolyline {
                let r = MKPolylineRenderer(polyline: line)
                r.strokeColor = UIColor(hex: style.strokeColorHex)
                r.lineWidth   = CGFloat(style.strokeWidth) + selectionBoost
                r.lineDashPattern = effectiveDashPattern(for: style,
                                                         inProgress: inProgress)
                return r
            }
            if let poly = overlay as? MKPolygon {
                let r = MKPolygonRenderer(polygon: poly)
                r.strokeColor = UIColor(hex: style.strokeColorHex)
                r.lineWidth   = CGFloat(style.strokeWidth) + selectionBoost
                let fillHex   = style.fillColorHex ?? style.strokeColorHex
                // Slightly brighter fill when selected.
                let fillAlpha = style.fillOpacity * (isSelected ? 1.6 : 1.0)
                r.fillColor   = UIColor(hex: fillHex, alpha: min(fillAlpha, 0.6))
                r.lineDashPattern = effectiveDashPattern(for: style,
                                                         inProgress: inProgress)
                return r
            }
            return MKOverlayRenderer(overlay: overlay)
        }

        /// Resolve the dash pattern for an overlay's renderer.
        /// - In-progress shapes always render dashed (preview convention),
        ///   regardless of the user's solid/dashed toggle.
        /// - Finalized shapes honour `style.dashPattern` — nil means solid.
        private func effectiveDashPattern(for style: DrawingStyle,
                                          inProgress: Bool) -> [NSNumber]? {
            if inProgress {
                return [6, 4]
            }
            return style.dashPattern.map { $0.map { NSNumber(value: $0) } }
        }

        func mapView(_ mv: MKMapView, viewFor annotation: MKAnnotation) -> MKAnnotationView? {
            if let wp = annotation as? WaypointAnnotation {
                // Military kinds get a custom APP-6 image so the frame /
                // function / echelon are drawn properly. Everything else
                // (generic waypoint, tactical control measures) keeps the
                // teardrop MKMarker pin with an SF Symbol glyph.
                if let spec = wp.waypoint.kind.militarySpec {
                    let id = "waypoint-military"
                    // Military symbols use a plain MKAnnotationView (no
                    // halo, no enlarged bounds). The user explicitly
                    // didn't want the entire unit graphic to glow —
                    // adding a CALayer shadow to the whole image was
                    // too much. Future work could halo only the
                    // echelon indicator above the frame.
                    let view = mv.dequeueReusableAnnotationView(withIdentifier: id)
                        ?? MKAnnotationView(annotation: wp, reuseIdentifier: id)
                    view.annotation = wp
                    view.image = MilitarySymbolRenderer.image(for: spec)
                    view.centerOffset = .zero
                    view.canShowCallout = false
                    view.isDraggable = true
                    return view
                }
                // Tactical control measures are rendered by
                // `TacticalSymbolOverlay` (a SwiftUI overlay above
                // the map view), not by MKMapView's annotation
                // pipeline. They're filtered out before they ever
                // become annotations — see `refresh()`.
                let id = "waypoint"
                let view = mv.dequeueReusableAnnotationView(withIdentifier: id) as? MKMarkerAnnotationView
                    ?? MKMarkerAnnotationView(annotation: wp, reuseIdentifier: id)
                view.annotation = wp
                view.glyphImage  = UIImage(systemName: wp.waypoint.kind.sfSymbol)
                view.markerTintColor = UIColor(wp.waypoint.kind.tint)
                view.canShowCallout  = false
                view.isDraggable = true
                return view
            }
            if let dp = annotation as? DrawingPointAnnotation {
                let id = "drawing-point"
                let view = mv.dequeueReusableAnnotationView(withIdentifier: id) as? MKMarkerAnnotationView
                    ?? MKMarkerAnnotationView(annotation: dp, reuseIdentifier: id)
                view.annotation = dp
                view.glyphImage = UIImage(systemName: "mappin")
                view.markerTintColor = UIColor(hex: dp.shape.style.strokeColorHex)
                view.canShowCallout = true
                return view
            }
            if let lbl = annotation as? DrawingLabelAnnotation {
                let id = "drawing-label"
                let view = mv.dequeueReusableAnnotationView(withIdentifier: id)
                    ?? MKAnnotationView(annotation: lbl, reuseIdentifier: id)
                view.annotation = lbl
                view.canShowCallout = false
                view.isUserInteractionEnabled = false
                view.displayPriority = .required
                // Render the pill as a single UIImage — much more reliable
                // than building subviews, which MapKit sometimes drops on
                // annotation reuse.
                view.image = Self.renderLabelPill(text: lbl.text)
                if let img = view.image {
                    view.bounds = CGRect(origin: .zero, size: img.size)
                }
                // Hang the pill below the shape's anchor.
                view.centerOffset = CGPoint(x: 0, y: (view.bounds.height / 2) + 8)
                return view
            }
            if let pv = annotation as? DrawingVertexAnnotation {
                let id = "drawing-vertex"
                let view = mv.dequeueReusableAnnotationView(withIdentifier: id)
                    ?? MKAnnotationView(annotation: pv, reuseIdentifier: id)
                view.annotation = pv
                view.canShowCallout = false
                view.isUserInteractionEnabled = false
                view.displayPriority = .required
                view.image = Self.renderVertexDot(color: pv.color)
                if let img = view.image {
                    view.bounds = CGRect(origin: .zero, size: img.size)
                }
                view.centerOffset = .zero
                return view
            }
            if let g = annotation as? MGRSGridLabelAnnotation {
                let id = "mgrs-grid-label"
                let view = mv.dequeueReusableAnnotationView(withIdentifier: id)
                    ?? MKAnnotationView(annotation: g, reuseIdentifier: id)
                view.annotation = g
                view.canShowCallout = false
                view.isUserInteractionEnabled = false
                // .required so MapKit never declutters grid labels away
                // — a sparse grid with hidden numbers is worse than a
                // dense one where the user can still read the values.
                view.displayPriority = .required
                view.collisionMode = .none
                view.image = Self.renderMGRSLabel(text: g.text,
                                                  fontSize: MGRSGridRenderer.labelFontSize(for: g.gridType),
                                                  rotated: g.isVertical)
                if let img = view.image {
                    view.bounds = CGRect(origin: .zero, size: img.size)
                }
                view.centerOffset = .zero
                return view
            }
            if let h = annotation as? DrawingVertexHandleAnnotation {
                let id = h.isMidpoint ? "drawing-vertex-mid" : "drawing-vertex-handle"
                let view = mv.dequeueReusableAnnotationView(withIdentifier: id)
                    ?? MKAnnotationView(annotation: h, reuseIdentifier: id)
                view.annotation = h
                view.canShowCallout = false
                view.isUserInteractionEnabled = true
                // MapKit's built-in drag is a long-press-then-drag that
                // never fires reliably on small custom annotation
                // views, so we drive the drag ourselves via a
                // UIPanGestureRecognizer below. Keep isDraggable off
                // so the system doesn't install its own competing
                // recogniser.
                view.isDraggable = false
                view.displayPriority = .required
                view.image = h.isMidpoint
                    ? Self.renderVertexHandle(midpoint: true)
                    : Self.renderVertexHandle(midpoint: false)
                if let img = view.image {
                    view.bounds = CGRect(origin: .zero, size: img.size)
                }
                view.centerOffset = .zero

                // Strip any recogniser left over from a recycled view
                // so handlers don't stack on reuse.
                view.gestureRecognizers?.forEach { view.removeGestureRecognizer($0) }

                // Pan = instant drag. Fires on the very first movement
                // (no long-press warmup) so the handle behaves like a
                // standard drawing-app vertex.
                let pan = UIPanGestureRecognizer(target: self, action: #selector(handleVertexPan(_:)))
                view.addGestureRecognizer(pan)

                // Long-press = delete (real vertices only — midpoint
                // handles don't represent a stored vertex so there's
                // nothing to remove). 0.6s lets it fire reliably while
                // still giving a quick tap-to-insert / drag enough
                // headroom.
                if !h.isMidpoint {
                    let lp = UILongPressGestureRecognizer(target: self, action: #selector(handleVertexLongPress(_:)))
                    lp.minimumPressDuration = 0.6
                    lp.allowableMovement = 8
                    view.addGestureRecognizer(lp)
                }
                return view
            }
            return nil
        }

        /// Render the drawing-name pill as a UIImage so it survives MapKit's
        /// annotation-view reuse cycle.
        private static func renderLabelPill(text: String) -> UIImage {
            let font = UIFont.systemFont(ofSize: 11, weight: .semibold)
            let attrs: [NSAttributedString.Key: Any] = [.font: font]
            let textSize = (text as NSString).size(withAttributes: attrs)
            let padH: CGFloat = 6, padV: CGFloat = 3
            let size = CGSize(width: ceil(textSize.width) + padH * 2,
                              height: ceil(textSize.height) + padV * 2)
            let renderer = UIGraphicsImageRenderer(size: size)
            return renderer.image { ctx in
                let cg = ctx.cgContext
                // Pill background.
                let rect = CGRect(origin: .zero, size: size).insetBy(dx: 0.5, dy: 0.5)
                let path = UIBezierPath(roundedRect: rect, cornerRadius: 4).cgPath
                cg.addPath(path)
                cg.setFillColor(UIColor.black.withAlphaComponent(0.62).cgColor)
                cg.fillPath()
                cg.addPath(path)
                cg.setStrokeColor(UIColor.white.withAlphaComponent(0.18).cgColor)
                cg.setLineWidth(0.5)
                cg.strokePath()
                // Text — slight shadow for legibility.
                let textAttrs: [NSAttributedString.Key: Any] = [
                    .font: font,
                    .foregroundColor: UIColor.white,
                    .shadow: {
                        let s = NSShadow()
                        s.shadowColor = UIColor.black.withAlphaComponent(0.8)
                        s.shadowBlurRadius = 1.5
                        return s
                    }()
                ]
                (text as NSString).draw(at: CGPoint(x: padH, y: padV), withAttributes: textAttrs)
            }
        }

        /// Filled dot rendered at each tapped vertex during a measure or
        /// draw session, so the user can see exactly where their taps
        /// landed before the polyline closes the gap.
        private static func renderVertexDot(color: UIColor) -> UIImage {
            let size = CGSize(width: 12, height: 12)
            let renderer = UIGraphicsImageRenderer(size: size)
            return renderer.image { ctx in
                let cg = ctx.cgContext
                cg.setFillColor(color.cgColor)
                cg.fillEllipse(in: CGRect(x: 1, y: 1, width: 10, height: 10))
                cg.setStrokeColor(UIColor.white.cgColor)
                cg.setLineWidth(1.5)
                cg.strokeEllipse(in: CGRect(x: 1, y: 1, width: 10, height: 10))
            }
        }

        /// MGRS grid label drawn as bare dark-grey bold text with a
        /// subtle white "drop shadow" halo for legibility on busy
        /// basemaps. No pill background. When `rotated` is true the
        /// text is drawn sideways so it lines up with vertical
        /// (easting) grid lines.
        private static func renderMGRSLabel(text: String, fontSize: CGFloat, rotated: Bool) -> UIImage {
            let font = UIFont.systemFont(ofSize: fontSize, weight: .bold)
            let baseAttrs: [NSAttributedString.Key: Any] = [
                .font: font,
                .foregroundColor: MGRSGridRenderer.labelTextColor
            ]
            let textSize = (text as NSString).size(withAttributes: baseAttrs)
            let pad: CGFloat = 3
            let drawW = textSize.width  + pad * 2
            let drawH = textSize.height + pad * 2
            // Rotated labels need the canvas swapped to fit the
            // rotated text — width becomes the original height + pad,
            // height becomes the original width.
            let canvasSize = rotated
                ? CGSize(width: drawH, height: drawW)
                : CGSize(width: drawW, height: drawH)
            let r = UIGraphicsImageRenderer(size: canvasSize)
            return r.image { ctx in
                let cg = ctx.cgContext
                if rotated {
                    // Rotate -90° around centre so easting labels run
                    // along the line (text reads bottom→top).
                    cg.translateBy(x: canvasSize.width / 2, y: canvasSize.height / 2)
                    cg.rotate(by: -.pi / 2)
                    cg.translateBy(x: -drawW / 2, y: -drawH / 2)
                }
                // Soft white halo via four offset white passes — keeps
                // dark-grey digits readable on dark satellite tiles
                // without adding a visible pill.
                let haloAttrs: [NSAttributedString.Key: Any] = [
                    .font: font,
                    .foregroundColor: UIColor(white: 1, alpha: 0.9)
                ]
                let offset: CGFloat = 1
                for dx in [-offset, offset] {
                    for dy in [-offset, offset] {
                        (text as NSString).draw(at: CGPoint(x: pad + dx, y: pad + dy),
                                                withAttributes: haloAttrs)
                    }
                }
                (text as NSString).draw(at: CGPoint(x: pad, y: pad),
                                        withAttributes: baseAttrs)
            }
        }

        /// Bigger, fatter vertex-edit handle. Solid orange for real
        /// vertices; outlined white "+" for midpoint insertion handles.
        private static func renderVertexHandle(midpoint: Bool) -> UIImage {
            let size = CGSize(width: 26, height: 26)
            let renderer = UIGraphicsImageRenderer(size: size)
            let orange = UIColor(red: 1, green: 0.65, blue: 0.18, alpha: 1)
            return renderer.image { ctx in
                let cg = ctx.cgContext
                let rect = CGRect(x: 3, y: 3, width: 20, height: 20)
                if midpoint {
                    // Hollow disc with a "+" so the user knows tapping
                    // / dragging inserts a new vertex.
                    cg.setFillColor(UIColor.white.withAlphaComponent(0.85).cgColor)
                    cg.fillEllipse(in: rect)
                    cg.setStrokeColor(orange.cgColor)
                    cg.setLineWidth(2)
                    cg.strokeEllipse(in: rect)
                    cg.setStrokeColor(orange.cgColor)
                    cg.setLineWidth(2.5)
                    cg.move(to: CGPoint(x: 13, y: 8));  cg.addLine(to: CGPoint(x: 13, y: 18))
                    cg.move(to: CGPoint(x:  8, y: 13)); cg.addLine(to: CGPoint(x: 18, y: 13))
                    cg.strokePath()
                } else {
                    cg.setFillColor(orange.cgColor)
                    cg.fillEllipse(in: rect)
                    cg.setStrokeColor(UIColor.white.cgColor)
                    cg.setLineWidth(2)
                    cg.strokeEllipse(in: rect)
                }
            }
        }

    }
}

// MARK: - Annotations

final class WaypointAnnotation: NSObject, MKAnnotation {
    let waypoint: Waypoint
    /// Stored coordinate (KVO-compliant) so MKMapView can mutate it
    /// during a drag (`isDraggable = true` on the annotation view).
    /// On drag end the coordinator persists the new value back to
    /// the store and the regular refresh path picks it up.
    @objc dynamic var coordinate: CLLocationCoordinate2D
    init(_ wp: Waypoint) {
        self.waypoint = wp
        self.coordinate = wp.coordinate
    }
    var title: String? { waypoint.name }
    var subtitle: String? { waypoint.subtitle }
}

/// Small filled-circle annotation rendered at each tapped vertex while
/// the user is drawing or measuring. Provides instant visual feedback for
/// the tap landing point and matches the Android-side dot affordance.
final class DrawingVertexAnnotation: NSObject, MKAnnotation {
    let color: UIColor
    @objc dynamic var coordinate: CLLocationCoordinate2D = .init()
    init(color: UIColor) { self.color = color }
}

/// Interactive vertex-edit handle rendered alongside the currently
/// selected polyline / polygon. Two flavours: a solid orange disc at
/// each existing vertex (drag to move, long-press to delete), and a
/// hollow "+" disc at each segment midpoint (drag to insert a new
/// vertex at that position).
final class DrawingVertexHandleAnnotation: NSObject, MKAnnotation {
    let shapeID: UUID
    /// For real vertices: the index in `shape.coordinates`. For
    /// midpoint handles: the index where a NEW vertex would be
    /// inserted (i.e. between coords[index-1] and coords[index]).
    let vertexIndex: Int
    let isMidpoint: Bool
    @objc dynamic var coordinate: CLLocationCoordinate2D = .init()
    init(shapeID: UUID, vertexIndex: Int, isMidpoint: Bool, coordinate: CLLocationCoordinate2D) {
        self.shapeID = shapeID
        self.vertexIndex = vertexIndex
        self.isMidpoint = isMidpoint
        self.coordinate = coordinate
    }
}

/// Floating text label rendered alongside a finished drawing whose
/// `shape.name` is non-empty. Anchored at `shape.labelAnchor` so it sits
/// near the centroid (polygons), mid-segment (polylines), or the point
/// itself. Non-interactive — taps pass through to the underlying shape.
final class DrawingLabelAnnotation: NSObject, MKAnnotation {
    let shapeID: UUID
    let text: String
    @objc dynamic var coordinate: CLLocationCoordinate2D = .init()
    init(shape: DrawingShape, text: String) {
        self.shapeID = shape.id
        self.text = text
    }
}

/// Static label rendered alongside an MGRS grid line — typically the
/// 100km square ID ("LH") or a 10km / 1km easting-northing pair. Never
/// interactive: taps pass straight through to the underlying overlay
/// or basemap.
final class MGRSGridLabelAnnotation: NSObject, MKAnnotation {
    let text: String
    @objc dynamic var coordinate: CLLocationCoordinate2D
    let gridType: GridType
    /// True when the label belongs to a north-south line (easting
    /// label); false when it belongs to an east-west line (northing
    /// label). Drives the on-screen orientation of the rendered text.
    let isVertical: Bool
    init(text: String, coordinate: CLLocationCoordinate2D, gridType: GridType, isVertical: Bool) {
        self.text = text
        self.coordinate = coordinate
        self.gridType = gridType
        self.isVertical = isVertical
    }
}

final class DrawingPointAnnotation: NSObject, MKAnnotation {
    let shape: DrawingShape
    @objc dynamic var coordinate: CLLocationCoordinate2D = .init()
    init(shape: DrawingShape) { self.shape = shape }
    var title: String? { shape.name ?? shape.kind.displayName }
    var subtitle: String? { shape.notes }
}
