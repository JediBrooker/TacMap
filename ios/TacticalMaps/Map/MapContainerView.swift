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
        context.coordinator.syncTileOverlay(on: mv, source: mapVM.mapSource)
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
        context.coordinator.syncTileOverlay(on: mv, source: mapVM.mapSource)
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
        var styleByOverlay: [ObjectIdentifier: DrawingStyle] = [:]
        var inProgressOverlayIDs: Set<ObjectIdentifier> = []
        /// Drawing-shape id keyed by overlay identity. Lets the renderer
        /// thicken the stroke for whichever shape is currently selected.
        var shapeIDByOverlay: [ObjectIdentifier: UUID] = [:]
        /// MGRS-grid polyline lookup: which grid-type each registered
        /// overlay represents. Used by the renderer to pick stroke
        /// colour/width per level, and by the refresh routine to remove
        /// only grid polylines when toggling the overlay or panning.
        var mgrsGridTypeByOverlay: [ObjectIdentifier: GridType] = [:]
        var mgrsOverlayIDs: Set<ObjectIdentifier> = []
        var lastMGRSFingerprint: String = ""
        /// Active MGRS label annotations — tracked separately so the
        /// refresh routine can yank just the grid labels without
        /// touching drawing or waypoint annotations.
        var mgrsLabelAnnotations: [MGRSGridLabelAnnotation] = []
        /// Subview-based grid renderer, used ONLY while a PDF basemap is
        /// active (MKOverlay grid lines render beneath the PDF image subview
        /// and would be hidden). nil on the plain-basemap path.
        var mgrsGridOverlayView: MGRSGridOverlayView?

        /// PDF overlay rendered as a UIImageView subview (bypasses MKOverlay
        /// because iOS 26 MapKit silently refuses to draw custom overlays on
        /// satellite imagery). Keyed by the source's UUID.
        var pdfImageView: PDFImageOverlayView?
        var pdfSourceID: UUID?
        /// Source whose page bitmap is currently being rasterised off the main
        /// thread, so repeated `syncPDFOverlay` calls don't kick off duplicates.
        var pdfRasterizingSourceID: UUID?

        /// Offline MBTiles raster basemap overlay + the id of the source it
        /// belongs to. Persists across refresh() (see MapContainerCoordinator+TileSync).
        var tileOverlay: MBTilesTileOverlay?
        var tileSourceID: UUID?

        /// Dark UIView covering the satellite while a PDF is loaded so the
        /// imported map is the only visible content. Removed when the PDF is
        /// hidden or unloaded.
        var basemapMask: UIView?

        var nextRegionChangeIsUserDriven = false

        /// Light haptic fired when the user selects a control-measure
        /// waypoint to open the rotate / resize controls card.
        let selectionHaptic = UIImpactFeedbackGenerator(style: .light)

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
        var isRebuildingAnnotations = false

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
            // Keep the subview grid (PDF basemap path) glued to the map every
            // frame; no-op on the MKOverlay path.
            reprojectMGRSGridOverlay()
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
            MapGeometry.zoomScaleFactor(metresPerPoint: metresPerPoint(in: mv),
                                        reference: referenceMetresPerPoint)
        }

        /// Pure metres-per-point at the current camera, no clamping.
        /// Used by `defaultScaleForNewSymbol` to size new symbols
        /// relative to the screen at placement time.
        func metresPerPoint(in mv: MKMapView) -> Double {
            MapGeometry.metresPerPoint(latitudeDelta: mv.region.span.latitudeDelta,
                                       viewHeightPoints: Double(mv.bounds.height))
        }

        /// Reference zoom where `waypoint.scale = 1.0` renders at the
        /// symbol's base point size. Lower = bigger symbols at all
        /// zooms; higher = smaller.
        private let referenceMetresPerPoint: Double = 1.0

        // MARK: Drawing tap


        /// Tracks whether each in-flight vertex-handle long-press has
        /// seen any movement. Lets the handler defer the delete action
        /// to lift-time and skip it if the user's finger moved (which
        /// means the pan recogniser is also active — the user is
        /// dragging, not deleting).
        var vertexLongPressMoved: [ObjectIdentifier: Bool] = [:]



        // MARK: Drag-to-move drawings

        /// ID of the drawing currently being dragged via long-press, plus
        /// the last touch coordinate (so each .changed event applies an
        /// incremental delta).
        var draggingDrawingID: UUID?
        var lastDragCoord: CLLocationCoordinate2D?

        // MARK: MGRS grid overlay

        /// Snapshot of the toggle so refreshMGRSGrid can read it without
        /// taking the LayerVisibility object as a parameter on every
        /// region-change callback.
        var mgrsGridVisibleFlag: Bool = false

        /// ID of the waypoint currently being dragged via long-press.
        /// Only one of (waypoint, drawing) drags at a time.
        var draggingWaypointID: UUID?


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

            // --- Overlays --- (keep the offline-tile basemap; it persists
            // across refreshes so the tiles don't reload on every model change)
            mv.removeOverlays(mv.overlays.filter { !($0 is MKTileOverlay) })
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

            case .polyline, .freedraw:
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


    }
}


