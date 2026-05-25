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
        mv.addGestureRecognizer(tap)
        context.coordinator.tapGesture = tap

        // Long-press over a tactical-control-measure waypoint enters
        // "drag-to-rotate" mode: the annotation's image follows the
        // finger angle relative to the symbol centre. See
        // Coordinator.handleSymbolLongPress.
        let longPress = UILongPressGestureRecognizer(
            target: context.coordinator,
            action: #selector(Coordinator.handleSymbolLongPress(_:))
        )
        longPress.minimumPressDuration = 0.4
        // Once the press fires, allow the finger to wander as far as
        // needed — the user is dragging an angle, not holding still.
        longPress.allowableMovement = .greatestFiniteMagnitude
        longPress.delegate = context.coordinator
        mv.addGestureRecognizer(longPress)

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

        // MARK: Long-press rotation gesture state

        /// Set on long-press .began when the user grabs a control-measure
        /// waypoint; nil otherwise. Carries the bits we need for live
        /// rotation without having to look the annotation back up by ID
        /// on every .changed frame.
        private var rotatingAnnotation: WaypointAnnotation?
        private var rotatingView: MKAnnotationView?
        private var rotatingMeasure: TacticalControlMeasure?
        private let rotationHaptic = UIImpactFeedbackGenerator(style: .medium)

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
            pdfImageView?.updateFrame(in: mv)
        }

        /// Fires on every render frame during pan/zoom/rotate — the only delegate
        /// callback that captures rotation gestures. We also use this to keep
        /// the PDF image view glued to its geographic bounds in real time.
        func mapViewDidChangeVisibleRegion(_ mv: MKMapView) {
            mapVM.mapCameraDidChange(heading: mv.camera.heading)
            pdfImageView?.updateFrame(in: mv)
        }

        // MARK: Drawing tap

        // MARK: Long-press to rotate a tactical control measure

        /// Long-press grabs the symbol; subsequent finger movement rotates
        /// it in place around its centre. 0° = north (up), increasing
        /// clockwise so it matches `WaypointEditSheet`'s slider semantics
        /// and the rendered SwiftUI `rotationEffect`.
        @objc func handleSymbolLongPress(_ g: UILongPressGestureRecognizer) {
            guard let mv = g.view as? MKMapView else { return }
            let touch = g.location(in: mv)

            switch g.state {
            case .began:
                // Find a control-measure annotation whose rendered view
                // contains the touch. Iterate in reverse so symbols drawn
                // on top win when they overlap.
                let candidates = mv.annotations.compactMap { $0 as? WaypointAnnotation }
                for ann in candidates.reversed() {
                    guard case .controlMeasure(let measure) = ann.waypoint.kind,
                          let view = mv.view(for: ann) else { continue }
                    // view.frame is in the annotation container's coords;
                    // convert the touch into the same space.
                    let local = view.superview.map { mv.convert(touch, to: $0) } ?? touch
                    guard view.frame.contains(local) else { continue }
                    rotatingAnnotation = ann
                    rotatingView = view
                    rotatingMeasure = measure
                    rotationHaptic.prepare()
                    rotationHaptic.impactOccurred()
                    // Visual cue: nudge the symbol up slightly so it feels
                    // "lifted" off the map while the user rotates it.
                    UIView.animate(withDuration: 0.12) {
                        view.transform = CGAffineTransform(scaleX: 1.12, y: 1.12)
                    }
                    // Stop the map from panning under the finger while
                    // we're rotating; restored on .ended.
                    mv.isScrollEnabled = false
                    break
                }

            case .changed:
                applyLiveRotation(touchInMap: touch, mapView: mv)

            case .ended, .cancelled, .failed:
                defer { endRotationGesture(on: mv) }
                guard let ann = rotatingAnnotation else { return }
                let finalDeg = angleFromCentre(touchInMap: touch,
                                               annotation: ann,
                                               mapView: mv)
                // Persist via the store. The published change triggers
                // updateUIView → refresh, which removes / re-adds this
                // annotation with the new rotation baked in.
                if let wp = waypointStore.waypoints.first(where: { $0.id == ann.waypoint.id }) {
                    var updated = wp
                    updated.rotation = finalDeg
                    waypointStore.update(updated)
                }

            default:
                break
            }
        }

        /// Update the annotation view's image to reflect the current
        /// finger angle. No store write — that happens once on .ended.
        private func applyLiveRotation(touchInMap touch: CGPoint, mapView mv: MKMapView) {
            guard let ann = rotatingAnnotation,
                  let view = rotatingView,
                  let measure = rotatingMeasure else { return }
            let deg = angleFromCentre(touchInMap: touch, annotation: ann, mapView: mv)
            view.image = TacticalControlMeasureRenderer.image(for: measure, rotation: deg)
        }

        /// Angle in degrees from the annotation's centre to `touch`,
        /// normalised to [0, 360) and oriented so 0° points north (up).
        private func angleFromCentre(touchInMap touch: CGPoint,
                                     annotation ann: WaypointAnnotation,
                                     mapView mv: MKMapView) -> Double {
            let centre = mv.convert(ann.coordinate, toPointTo: mv)
            let dx = Double(touch.x - centre.x)
            let dy = Double(touch.y - centre.y)
            // atan2 gives angle from +x axis; UIKit y grows downward, so
            // adding 90° rotates the reference axis so 0° = up (north).
            var deg = atan2(dy, dx) * 180 / .pi + 90
            deg = deg.truncatingRemainder(dividingBy: 360)
            if deg < 0 { deg += 360 }
            return deg
        }

        private func endRotationGesture(on mv: MKMapView) {
            if let view = rotatingView {
                UIView.animate(withDuration: 0.12) {
                    view.transform = .identity
                }
            }
            mv.isScrollEnabled = true
            rotatingAnnotation = nil
            rotatingView = nil
            rotatingMeasure = nil
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
        func refresh(on mv: MKMapView,
                     waypoints: [Waypoint],
                     drawings:  [DrawingShape],
                     session:   DrawingSessionViewModel,
                     visibility: LayerVisibility?) {
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

            // Add waypoints if visible.
            if visibility?.waypointsVisible ?? true {
                mv.addAnnotations(waypoints.map(WaypointAnnotation.init))
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
                if inProgress { r.lineDashPattern = [6, 4] }
                return r
            }
            if let poly = overlay as? MKPolygon {
                let r = MKPolygonRenderer(polygon: poly)
                r.strokeColor = UIColor(hex: style.strokeColorHex)
                r.lineWidth   = CGFloat(style.strokeWidth)
                let fillHex   = style.fillColorHex ?? style.strokeColorHex
                r.fillColor   = UIColor(hex: fillHex, alpha: style.fillOpacity)
                if inProgress { r.lineDashPattern = [6, 4] }
                return r
            }
            return MKOverlayRenderer(overlay: overlay)
        }

        func mapView(_ mv: MKMapView, viewFor annotation: MKAnnotation) -> MKAnnotationView? {
            if let wp = annotation as? WaypointAnnotation {
                // Military kinds get a custom APP-6 image so the frame /
                // function / echelon are drawn properly. Everything else
                // (generic waypoint, tactical control measures) keeps the
                // teardrop MKMarker pin with an SF Symbol glyph.
                if let spec = wp.waypoint.kind.militarySpec {
                    let id = "waypoint-military"
                    let view = mv.dequeueReusableAnnotationView(withIdentifier: id)
                        ?? MKAnnotationView(annotation: wp, reuseIdentifier: id)
                    view.annotation = wp
                    view.image = MilitarySymbolRenderer.image(for: spec)
                    view.centerOffset = .zero
                    view.canShowCallout = true
                    return view
                }
                if let measure = wp.waypoint.kind.controlMeasure {
                    let id = "waypoint-control-measure"
                    let view = mv.dequeueReusableAnnotationView(withIdentifier: id)
                        ?? MKAnnotationView(annotation: wp, reuseIdentifier: id)
                    view.annotation = wp
                    view.image = TacticalControlMeasureRenderer.image(
                        for: measure,
                        rotation: wp.waypoint.rotation)
                    view.centerOffset = .zero
                    view.canShowCallout = true
                    return view
                }
                let id = "waypoint"
                let view = mv.dequeueReusableAnnotationView(withIdentifier: id) as? MKMarkerAnnotationView
                    ?? MKMarkerAnnotationView(annotation: wp, reuseIdentifier: id)
                view.annotation = wp
                view.glyphImage  = UIImage(systemName: wp.waypoint.kind.sfSymbol)
                view.markerTintColor = UIColor(wp.waypoint.kind.tint)
                view.canShowCallout  = true
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
    init(_ wp: Waypoint) { self.waypoint = wp }
    var coordinate: CLLocationCoordinate2D { waypoint.coordinate }
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
