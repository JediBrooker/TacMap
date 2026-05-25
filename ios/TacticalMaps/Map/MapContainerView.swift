import SwiftUI
import MapKit
import Combine

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
                                    drawings:  drawingStore.shapes,
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
        context.coordinator.refresh(on: mv,
                                    waypoints: waypointStore.waypoints,
                                    drawings:  drawingStore.shapes,
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
                    calibration: calibration)
    }

    // MARK: - Coordinator

    final class Coordinator: NSObject, MKMapViewDelegate, UIGestureRecognizerDelegate {
        let mapVM: MapViewModel
        let waypointStore: WaypointStore
        let drawingStore: DrawingStore
        let drawingSession: DrawingSessionViewModel
        var calibration: CalibrationSession   // mutable so updateUIView can refresh

        var cameraRequestSink: AnyCancellable?
        var resetNorthSink:    AnyCancellable?
        weak var tapGesture: UITapGestureRecognizer?
        weak var attachedMapView: MKMapView?

        /// Style lookup keyed by overlay identity (MKPolyline/MKPolygon don't
        /// carry style metadata themselves).
        private var styleByOverlay: [ObjectIdentifier: DrawingStyle] = [:]
        private var inProgressOverlayIDs: Set<ObjectIdentifier> = []

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
             calibration: CalibrationSession) {
            self.mapVM = mapVM
            self.waypointStore = waypointStore
            self.drawingStore = drawingStore
            self.drawingSession = drawingSession
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

        func mapView(_ mv: MKMapView, regionDidChangeAnimated animated: Bool) {
            let byUser = nextRegionChangeIsUserDriven
            nextRegionChangeIsUserDriven = false
            mapVM.mapRegionDidChange(mv.region, animated: animated, byUser: byUser)
            mapVM.mapCameraDidChange(heading: mv.camera.heading)
            mapVM.currentMetresPerPoint = metresPerPoint(in: mv)
            pdfImageView?.updateFrame(in: mv)
            publishOverlayState(in: mv)
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
            var positions: [UUID: CGPoint] = [:]
            for wp in currentWaypoints {
                guard case .controlMeasure = wp.kind else { continue }
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
            guard newState == .ending,
                  let ann = view.annotation as? WaypointAnnotation
            else { return }
            // The annotation's `coordinate` was updated live by MapKit
            // during the drag; commit it.
            if let wp = waypointStore.waypoints.first(where: { $0.id == ann.waypoint.id }) {
                var updated = wp
                updated.latitude  = ann.coordinate.latitude
                updated.longitude = ann.coordinate.longitude
                waypointStore.update(updated)
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

            // Tap on empty map dismisses the floating waypoint controls
            // card. Tapping on an annotation goes through MapKit's own
            // selection path (didSelect fires there), so this branch
            // only sees taps that DIDN'T hit a waypoint.
            if !drawingSession.isDrawing,
               mapVM.selectedWaypointID != nil,
               mv.selectedAnnotations.isEmpty {
                mapVM.selectedWaypointID = nil
                return
            }

            guard drawingSession.isDrawing else { return }
            let coord = mv.convert(pt, toCoordinateFrom: mv)
            let autoCommit = drawingSession.addPoint(coord)
            if autoCommit, let shape = drawingSession.finish() {
                drawingStore.add(shape)
            }
            // Re-render so the in-progress polyline grows visually.
            refresh(on: mv,
                    waypoints: Array(mv.annotations.compactMap { ($0 as? WaypointAnnotation)?.waypoint }),
                    drawings:  drawingStore.shapes,
                    session:   drawingSession,
                    visibility: nil)
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
            let fingerprint = Self.makeRefreshFingerprint(
                waypoints: waypoints,
                drawings:  drawings,
                session:   session,
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

            // --- Overlays ---
            mv.removeOverlays(mv.overlays)
            styleByOverlay.removeAll()
            inProgressOverlayIDs.removeAll()

            // Add waypoints if visible. Tactical control measures are
            // rendered by `TacticalSymbolOverlay` (a SwiftUI overlay
            // above the map) — keep them out of the MKAnnotation
            // pipeline entirely so MapKit doesn't try to manage their
            // views.
            if visibility?.waypointsVisible ?? true {
                let mapKitAnnotations = waypoints.filter { wp in
                    if case .controlMeasure = wp.kind { return false }
                    return true
                }
                mv.addAnnotations(mapKitAnnotations.map(WaypointAnnotation.init))
            }

            // Restore selection so the controls card keeps tracking the
            // same waypoint after a rebuild. We do this after re-adding
            // so the new annotation instance gets selected.
            if let selectedID,
               let restored = mv.annotations
                    .compactMap({ $0 as? WaypointAnnotation })
                    .first(where: { $0.waypoint.id == selectedID }) {
                mv.selectAnnotation(restored, animated: false)
            }

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
        }

        /// Compact identity string used to decide whether `refresh()`
        /// has work to do. Includes every field that affects what we
        /// render — coords, kind, rotation, scale, name, notes-presence,
        /// elevation-presence — so any meaningful mutation produces a
        /// new string and triggers a rebuild.
        private static func makeRefreshFingerprint(waypoints: [Waypoint],
                                                   drawings:  [DrawingShape],
                                                   session:   DrawingSessionViewModel,
                                                   visibility: LayerVisibility?) -> String {
            var parts: [String] = []
            parts.reserveCapacity(waypoints.count + drawings.count + 3)
            for w in waypoints {
                let elev = w.elevation.map { String($0) } ?? ""
                let notes = w.notes ?? ""
                parts.append("w|\(w.id.uuidString)|\(w.latitude)|\(w.longitude)|\(w.kindFingerprint)|\(w.rotation)|\(w.scale)|\(w.name)|\(notes)|\(elev)")
            }
            for d in drawings {
                parts.append("d|\(d.id.uuidString)|\(d.kind.rawValue)|\(d.coordinates.count)|\(d.style.strokeColorHex)")
            }
            parts.append("s|\(session.isDrawing)|\(session.inProgressCoordinates.count)|\(session.activeKind?.rawValue ?? "-")")
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

        private func addShape(_ shape: DrawingShape, to mv: MKMapView, inProgress: Bool) {
            let coords = shape.clCoordinates
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
                if inProgress { inProgressOverlayIDs.insert(ObjectIdentifier(line)) }
                mv.addOverlay(line)

            case .polygon:
                guard coords.count >= 2 else { return }
                let poly = MKPolygon(coordinates: coords, count: coords.count)
                styleByOverlay[ObjectIdentifier(poly)] = shape.style
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
            let style = styleByOverlay[key] ?? .default
            let inProgress = inProgressOverlayIDs.contains(key)

            if let line = overlay as? MKPolyline {
                let r = MKPolylineRenderer(polyline: line)
                r.strokeColor = UIColor(hex: style.strokeColorHex)
                r.lineWidth   = CGFloat(style.strokeWidth)
                r.lineDashPattern = effectiveDashPattern(for: style,
                                                         inProgress: inProgress)
                return r
            }
            if let poly = overlay as? MKPolygon {
                let r = MKPolygonRenderer(polygon: poly)
                r.strokeColor = UIColor(hex: style.strokeColorHex)
                r.lineWidth   = CGFloat(style.strokeWidth)
                let fillHex   = style.fillColorHex ?? style.strokeColorHex
                r.fillColor   = UIColor(hex: fillHex, alpha: style.fillOpacity)
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
            return nil
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

final class DrawingPointAnnotation: NSObject, MKAnnotation {
    let shape: DrawingShape
    @objc dynamic var coordinate: CLLocationCoordinate2D = .init()
    init(shape: DrawingShape) { self.shape = shape }
    var title: String? { shape.name ?? shape.kind.displayName }
    var subtitle: String? { shape.notes }
}
