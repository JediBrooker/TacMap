import SwiftUI
import MapKit
import Combine
import Grid
import MGRS

/// UIViewRepresentable wrapper for MKMapView. Satellite map + waypoint
/// annotations + drawing overlays (polyline/polygon/point).
///
/// Pan/pinch flips VM into browse mode (header reads map centre).
/// Single tap while drawing adds a vertex to the in-progress shape.
struct MapContainerView: UIViewRepresentable {
    @ObservedObject var mapVM: MapViewModel
    @ObservedObject var locationService: LocationService
    @ObservedObject var waypointStore: WaypointStore
    @ObservedObject var drawingStore: DrawingStore
    @ObservedObject var drawingSession: DrawingSessionViewModel
    @ObservedObject var measureSession: MeasureSession
    @ObservedObject var visibility: LayerVisibility
    @ObservedObject var calibration: CalibrationSession
    /// When true, ALL graphic interaction is frozen - no select/tap-to-open,
    /// no drag, no vertex insert/move/delete. Synced to Coordinator each updateUIView.
    var graphicsLocked: Bool
    /// Remote peers broadcasting position via sync relay. Shown as presence annotations.
    var peers: [String: PresencePeer] = [:]

    func makeUIView(context: Context) -> MKMapView {
        let mv = MKMapView()
        mv.delegate = context.coordinator
        mv.showsUserLocation = visibility.userLocationVisible
        mv.showsCompass = false   // we render our own
        mv.showsScale = false
        // Back to satellite, PDF now renders via MKTileOverlay path which is
        // independent of base-map type.
        mv.mapType = .satellite
        mv.pointOfInterestFilter = .excludingAll
        // Lock camera flat - MapKit applies 3D tilt at deep zoom on satellite
        // which makes fixed-pixel symbols look like they grow/shrink. Keeping
        // pitch locked = straight-down camera, annotations stay canonical size.
        mv.isPitchEnabled = false

        // Pan/pinch -> browse mode signal.
        let pan   = UIPanGestureRecognizer(target: context.coordinator,
                                           action: #selector(Coordinator.userTouchedMap))
        let pinch = UIPinchGestureRecognizer(target: context.coordinator,
                                             action: #selector(Coordinator.userTouchedMap))
        pan.delegate = context.coordinator
        pinch.delegate = context.coordinator
        mv.addGestureRecognizer(pan)
        mv.addGestureRecognizer(pinch)

        // Kill MapKit's built-in rotation (pivots around finger midpoint,
        // drags map sideways) and use our own that pivots around camera
        // centre so the map spins in place.
        mv.isRotateEnabled = false
        let rotation = UIRotationGestureRecognizer(
            target: context.coordinator,
            action: #selector(Coordinator.handleRotation(_:))
        )
        rotation.delegate = context.coordinator
        mv.addGestureRecognizer(rotation)

        // Single-tap -> add vertex while drawing.
        let tap = UITapGestureRecognizer(target: context.coordinator,
                                         action: #selector(Coordinator.handleTap(_:)))
        tap.delegate = context.coordinator
        // Don't fight the map's built-in double-tap-to-zoom gesture.
        if let dt = mv.gestureRecognizers?.first(where: {
            ($0 as? UITapGestureRecognizer)?.numberOfTapsRequired == 2
        }) {
            tap.require(toFail: dt)
        }
        // Don't swallow the touch - MKMapView's annotation tap recognizer
        // needs it too so didSelect fires when user taps a control-measure.
        tap.cancelsTouchesInView = false
        mv.addGestureRecognizer(tap)
        context.coordinator.tapGesture = tap

        // Long-press-drag to reposition a drawing. Disables map scroll
        // while dragging so the shape moves, not the basemap.
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
        // Compass tap - smoothly animate heading back to 0 (north up),
        // keeps current centre, altitude, pitch.
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
        context.coordinator.graphicsLocked = graphicsLocked
        context.coordinator.syncPDFOverlay(on: mv,
                                           source: mapVM.mapSource,
                                           visible: visibility.pdfOverlayVisible)
        context.coordinator.syncTileOverlay(on: mv, source: mapVM.mapSource)
        context.coordinator.setHeatmapEnabled(visibility.terrainHeatmapVisible, on: mv)
        // Sync MGRS-grid toggle to coordinator and rebuild. Flipping
        // the switch needs to take effect immediately, can't wait for
        // next pan/zoom.
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
        // Mirror VM selection state onto MKMapView. When ContentView
        // dismisses the controls card (sets ID to nil), deselect on
        // MapKit side so user can re-tap to bring it back.
        if mapVM.selectedWaypointID == nil
            && !mv.selectedAnnotations.isEmpty {
            context.coordinator.deselectAll(on: mv)
        }
        // Sync presence annotations from remote peers.
        context.coordinator.syncPresenceAnnotations(on: mv, peers: peers)
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
        /// Mirrors MapContainerView.graphicsLocked. Gesture handlers bail when set.
        var graphicsLocked = false

        var cameraRequestSink: AnyCancellable?
        var resetNorthSink:    AnyCancellable?
        weak var tapGesture: UITapGestureRecognizer?
        weak var drawingDragPress: UILongPressGestureRecognizer?
        weak var attachedMapView: MKMapView?

        /// Style lookup by overlay identity - MKPolyline/MKPolygon don't
        /// carry style metadata themselves so we stash it here.
        var styleByOverlay: [ObjectIdentifier: DrawingStyle] = [:]
        var inProgressOverlayIDs: Set<ObjectIdentifier> = []
        /// Drawing-shape id by overlay identity so renderer can
        /// thicken stroke for the selected shape.
        var shapeIDByOverlay: [ObjectIdentifier: UUID] = [:]
        /// Which grid-type each overlay represents. Renderer uses this
        /// to pick stroke colour/width, and refresh uses it to nuke
        /// just grid polylines when toggling or panning.
        var mgrsGridTypeByOverlay: [ObjectIdentifier: GridType] = [:]
        var mgrsOverlayIDs: Set<ObjectIdentifier> = []
        var lastMGRSFingerprint: String = ""
        /// Active MGRS label annotations, tracked separately so we can
        /// yank just the grid labels without clobbering drawing or
        /// waypoint annotations.
        var mgrsLabelAnnotations: [MGRSGridLabelAnnotation] = []
        /// Subview-based grid renderer, only used while PDF basemap is active
        /// b/c MKOverlay grid lines render beneath the PDF image subview
        /// and would be hidden. nil on plain-basemap path.
        var mgrsGridOverlayView: MGRSGridOverlayView?
        /// Redraws drawing/measure/in-progress shapes ABOVE the PDF image
        /// since MKOverlay shapes render beneath it. nil without a PDF.
        var pdfDrawingsView: DrawingsOverlayView?

        /// PDF overlay as UIImageView subview. Bypasses MKOverlay b/c iOS 26
        /// MapKit silently refuses to draw custom overlays on satellite.
        /// Keyed by source UUID.
        var pdfImageView: PDFImageOverlayView?
        var pdfSourceID: UUID?
        /// Source being rasterised off main thread so repeated
        /// syncPDFOverlay calls don't kick off duplicates.
        var pdfRasterizingSourceID: UUID?

        /// Raster basemap overlay (offline MBTiles or online OSM) + source id.
        /// Persists across refresh(), see MapContainerCoordinator+TileSync.
        var tileOverlay: MKTileOverlay?
        var tileSourceID: UUID?

        /// Terrain heat-map overlay, refreshed (debounced) on region change
        /// while enabled. Persists across refresh().
        var heatmapOverlay: TerrainHeatmapOverlay?
        var heatmapEnabled = false
        let heatmapService = TerrainHeatmapService()
        var heatmapTask: Task<Void, Never>?

        /// Dark mask over satellite while PDF is loaded so only the
        /// imported map is visible. Removed when PDF hidden or unloaded.
        var basemapMask: UIView?

        var nextRegionChangeIsUserDriven = false

        /// Light haptic fired when the user selects a control-measure
        /// waypoint to open the rotate / resize controls card.
        let selectionHaptic = UIImpactFeedbackGenerator(style: .light)

        /// Fingerprint of the last (waypoints, drawings, in-progress
        /// session, visibility) tuple we rendered. refresh() no-ops when
        /// unchanged - this matters b/c updateUIView fires on ANY VM
        /// publish (incl selection state), and tearing down + re-adding
        /// every annotation on each selection would immediately deselect
        /// the just-tapped one and close the controls card.
        private var lastRefreshFingerprint: String = ""

        /// True while refresh() is tearing down + re-adding annotations.
        /// MapKit fires didDeselect when an annotation is removed, so we
        /// suppress the selection clear during refresh. Otherwise the
        /// controls card closes while user drags a slider (which triggers
        /// refresh via the published rotation/scale).
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
            if heatmapEnabled { scheduleHeatmapRefresh(on: mv) }
        }

        /// Fires every render frame during pan/zoom/rotate - only delegate
        /// callback that catches rotation. Also keeps PDF image view glued
        /// to its geo bounds in real time.
        func mapViewDidChangeVisibleRegion(_ mv: MKMapView) {
            mapVM.mapCameraDidChange(heading: mv.camera.heading)
            mapVM.currentMetresPerPoint = metresPerPoint(in: mv)
            pdfImageView?.updateFrame(in: mv)
            // Keep subview grid + drawings (PDF path) glued to map each frame.
            // No-op on MKOverlay path.
            reprojectMGRSGridOverlay()
            reprojectPDFDrawings()
            publishOverlayState(in: mv)
        }

        /// Cached waypoints from last refresh() so camera-change
        /// callbacks can recompute screen positions without going
        /// back through updateUIView.
        private var currentWaypoints: [Waypoint] = []

        /// Republish per-waypoint screen positions + zoom scale factor
        /// for TacticalSymbolOverlay. Runs on every camera change so
        /// the SwiftUI overlay tracks pan/zoom.
        ///
        /// Deferred to next runloop tick b/c some paths hit this from
        /// inside updateUIView (via refresh()), and SwiftUI doesn't
        /// let you mutate observable state during a view-update pass.
        /// Triggers the "Publishing changes from within view updates"
        /// warning + infinite re-render loop if you dont defer.
        private func publishOverlayState(in mv: MKMapView) {
            // Publish screen positions for all waypoint kinds -
            // control measures, military units, generic pins.
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

        /// Convert map region to unit scale: 1.0 = reference zoom
        /// (1m/pt). Halving metresPerPoint (zoom in) gives 2.0, doubling
        /// (zoom out) gives 0.5. Clamped to [0.005, 50] so symbols are
        /// visible from building-level all the way out to continental.
        func currentZoomScaleFactor(for mv: MKMapView) -> CGFloat {
            MapGeometry.zoomScaleFactor(metresPerPoint: metresPerPoint(in: mv),
                                        reference: referenceMetresPerPoint)
        }

        /// Raw metres-per-point at current camera, no clamping. Used to
        /// size new symbols relative to screen at placement time.
        func metresPerPoint(in mv: MKMapView) -> Double {
            MapGeometry.metresPerPoint(latitudeDelta: mv.region.span.latitudeDelta,
                                       viewHeightPoints: Double(mv.bounds.height))
        }

        /// Reference zoom where scale 1.0 = symbol's base point size.
        /// Lower = bigger symbols, higher = smaller.
        private let referenceMetresPerPoint: Double = 1.0

        // MARK: Drawing tap


        /// Tracks whether each vertex-handle long-press has seen movement.
        /// Handler defers delete to lift-time and skips if finger moved
        /// (pan recogniser is also active = user is dragging not deleting).
        var vertexLongPressMoved: [ObjectIdentifier: Bool] = [:]



        // MARK: Drag-to-move drawings

        /// Drawing being dragged via long-press + last touch coord
        /// so each .changed applies an incremental delta.
        var draggingDrawingID: UUID?
        var lastDragCoord: CLLocationCoordinate2D?

        // MARK: MGRS grid overlay

        /// Snapshot of the toggle so refreshMGRSGrid can read it
        /// without needing LayerVisibility on every region-change.
        var mgrsGridVisibleFlag: Bool = false

        /// Waypoint being dragged via long-press. Only one drag at a time
        /// (either waypoint or drawing, not both).
        var draggingWaypointID: UUID?

        /// Currently-rendered presence annotations, keyed by clientId.
        var presenceAnnotations: [String: PresenceAnnotation] = [:]

        /// Diff presence annotations against peers dict, add/update/remove.
        func syncPresenceAnnotations(on mv: MKMapView, peers: [String: PresencePeer]) {
            let currentIDs = Set(presenceAnnotations.keys)
            let newIDs = Set(peers.keys)

            // Remove annotations for peers that left.
            for id in currentIDs.subtracting(newIDs) {
                if let ann = presenceAnnotations.removeValue(forKey: id) {
                    mv.removeAnnotation(ann)
                }
            }

            // Add or update annotations for current peers.
            for (id, peer) in peers {
                if let existing = presenceAnnotations[id] {
                    // Update coordinate in place (KVO-compliant).
                    let newCoord = CLLocationCoordinate2D(latitude: peer.lat, longitude: peer.lon)
                    if abs(existing.coordinate.latitude - newCoord.latitude) > 1e-8
                        || abs(existing.coordinate.longitude - newCoord.longitude) > 1e-8 {
                        existing.coordinate = newCoord
                    }
                } else {
                    // new peer, add annotation
                    let ann = PresenceAnnotation(peer)
                    presenceAnnotations[id] = ann
                    mv.addAnnotation(ann)
                }
            }
        }


        // MARK: Refresh

        /// Rebuild all annotations + overlays from current model. Cheap
        /// enough for now, a real app would diff instead.
        ///
        /// Short-circuits when the fingerprint hasn't changed. This matters
        /// b/c updateUIView fires on every VM publication, including pure
        /// UI state like selectedControlMeasureWaypointID, and rebuilding
        /// during the same tick as didSelect would deselect the tapped one.
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
            // Always cache so overlay-position publisher has the latest
            // list even when we bail out early below.
            currentWaypoints = waypoints
            // Also republish so SwiftUI overlay catches new/removed
            // waypoints immediately - camera-change won't fire til
            // next interaction.
            publishOverlayState(in: mv)

            if fingerprint == lastRefreshFingerprint { return }
            lastRefreshFingerprint = fingerprint

            // Capture selected waypoint so we can re-select after
            // tearing annotations down, user might be mid-slider drag.
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

            // --- Overlays --- keep tile basemap + terrain heatmap, they
            // persist across refreshes so they dont reload every change
            mv.removeOverlays(mv.overlays.filter {
                !($0 is MKTileOverlay) && !($0 is TerrainHeatmapOverlay)
            })
            styleByOverlay.removeAll()
            inProgressOverlayIDs.removeAll()
            shapeIDByOverlay.removeAll()

            // All waypoint kinds rendered by TacticalSymbolOverlay
            // (SwiftUI overlay above map) - keep out of MKAnnotation
            // pipeline so MapKit doesnt manage their views. Selection
            // and drag handled by the overlay itself.
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

            // Measure-tool polyline, dashed in tactical-orange to look
            // like a tool overlay not a saved drawing.
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

            // PDF basemap is a UIImageView ON TOP of the map, so MKOverlay
            // shapes (drawings, in-progress, measure) render beneath it and
            // vanish. While PDF active, redraw vectors into a subview above
            // the PDF. Symbols/labels are annotations so they're fine.
            if pdfImageView != nil {
                var vectors: [PDFVectorShape] = []
                if visibility?.drawingsVisible ?? true {
                    for shape in drawings
                    where shape.kind == .polyline || shape.kind == .polygon || shape.kind == .freedraw {
                        vectors.append(PDFVectorShape(
                            coords: shape.clEffectiveCoordinates,
                            isPolygon: shape.kind == .polygon,
                            style: shape.style,
                            isSelected: shape.id == mapVM.selectedDrawingID,
                            inProgress: false))
                    }
                }
                if session.isDrawing, !session.inProgressCoordinates.isEmpty {
                    let kind = session.activeKind ?? .polyline
                    vectors.append(PDFVectorShape(
                        coords: session.inProgressCoordinates.map {
                            CLLocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude)
                        },
                        isPolygon: kind == .polygon,
                        style: DrawingStyle(),
                        isSelected: false,
                        inProgress: true))
                }
                if measureSession.isActive, measureSession.points.count >= 2 {
                    vectors.append(PDFVectorShape(
                        coords: measureSession.points,
                        isPolygon: false,
                        style: DrawingStyle(strokeColorHex: "#FFA500", fillColorHex: nil,
                                            strokeWidth: 3.0, fillOpacity: 0, dashPattern: [6, 4]),
                        isSelected: false,
                        inProgress: true))
                }
                ensurePDFDrawingsView(on: mv).update(shapes: vectors)
            } else {
                removePDFDrawingsView()
            }

            // Vertex dots - small marker at each tap point so user can
            // see where taps landed before the polyline connects them.
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

            // Vertex-edit handles for selected polyline/polygon. Rendered
            // at EFFECTIVE coords so handles sit on the actual shape.
            // Mutations bake the transform before persisting
            // (see DrawingShape.setEffectiveVertex).
            if let selectedID = mapVM.selectedDrawingID,
               let shape = drawings.first(where: { $0.id == selectedID }) {
                let coords = shape.clEffectiveCoordinates
                // Freehand strokes have way too many vertices for meaningful
                // editing so they get no vertex handles. Still selectable /
                // movable / deletable via controls card, just not vertex-editable.
                // Detect by point count (matches Android's >20 heuristic).
                let isFreehand = shape.kind == .freedraw
                    || (shape.kind == .polyline && coords.count > 20)
                if !isFreehand && (shape.kind == .polyline || shape.kind == .polygon) {
                    // real vertex handles - draggable, long-press to delete
                    for (i, c) in coords.enumerated() {
                        let h = DrawingVertexHandleAnnotation(
                            shapeID: shape.id,
                            vertexIndex: i,
                            isMidpoint: false,
                            coordinate: c
                        )
                        mv.addAnnotation(h)
                    }
                    // Midpoint handles for inserting new verts. Polylines get
                    // them between adjacent pairs, polygons also between
                    // last->first so you can split the closing segment.
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
        }

        /// Compact fingerprint for refresh() short-circuit. Includes
        /// everything that affects rendering: coords, kind, rotation,
        /// scale, name, notes, elevation. Any real mutation produces a
        /// new string and triggers rebuild.
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
                parts.append("w|\(w.id.uuidString)|\(w.latitude)|\(w.longitude)|\(w.kindFingerprint)|\(w.rotation)|\(w.scaleX)|\(w.scaleY)|\(w.taskColor.rawValue)|\(w.name)|\(notes)|\(elev)")
            }
            for d in drawings {
                // Hash every vertex so single-handle drags and midpoint
                // insert/delete invalidate the fingerprint. Cheap since
                // drawing counts are always tiny.
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

        /// Current label-visibility toggle, captured in refresh() so
        /// addShape can check it.
        private var labelsVisible: Bool = true

        private func addShape(_ shape: DrawingShape, to mv: MKMapView, inProgress: Bool) {
            // In-progress = raw coords; finished = effective coords
            // (rotation + W/H baked in).
            let coords = inProgress ? shape.clCoordinates : shape.clEffectiveCoordinates

            // Add label if shape has a name (finished only, in-progress
            // has no name yet) and labels arent hidden via Layers sheet.
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
                // For in-progress polygon also draw open edge as dashed polyline
                // so user sees what they're tracing before closing the ring.
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


