import SwiftUI
import MapKit
import Combine
import CoreLocation

/// Small MapKit-typed helpers the container needs (MKCoordinateRegion is just a
/// center+span struct; keeping this out of the MapKit-free MapCamera core).
enum MapProjectionMath {
    /// The lat/lon bounding box the camera currently shows, as a region - what
    /// MGRSGridRenderer.build wants.
    static func visibleRegion(_ camera: MapCamera) -> MKCoordinateRegion {
        let w = camera.viewportSize.width, h = camera.viewportSize.height
        let coords = [CGPoint(x: 0, y: 0), CGPoint(x: w, y: 0),
                      CGPoint(x: 0, y: h), CGPoint(x: w, y: h)].map { camera.coordinate(for: $0) }
        let lats = coords.map(\.latitude), lons = coords.map(\.longitude)
        let minLat = lats.min() ?? 0, maxLat = lats.max() ?? 0
        let minLon = lons.min() ?? 0, maxLon = lons.max() ?? 0
        return MKCoordinateRegion(
            center: CLLocationCoordinate2D(latitude: (minLat + maxLat) / 2,
                                           longitude: (minLon + maxLon) / 2),
            span: MKCoordinateSpan(latitudeDelta: max(maxLat - minLat, 0.0001),
                                   longitudeDelta: max(maxLon - minLon, 0.0001)))
    }
}

/// SwiftUI host for the MapKit-free `TileMapView`. Drives the basemap tile
/// source from the app's map state and publishes the projection contract
/// (`waypointScreenPositions`, `screenToCoordinate`, `cameraCentre`, `heading`,
/// `currentMetresPerPoint`, `zoomScaleFactor`) into `MapViewModel` exactly like
/// the old MKMapView coordinator did - so the SwiftUI overlays that read those
/// (symbols, crosshair, labels) keep working with no changes.
///
/// This is the drop-in replacement for MKMapView's role as the projection +
/// gesture engine. Overlays that used to render *inside* MKMapView (drawings,
/// PDF, MGRS grid, presence) get ported on top of this in following steps.
struct TileMapContainer: UIViewRepresentable {
    @ObservedObject var mapVM: MapViewModel
    @ObservedObject var waypointStore: WaypointStore
    @ObservedObject var drawingStore: DrawingStore
    @ObservedObject var drawingSession: DrawingSessionViewModel
    @ObservedObject var measureSession: MeasureSession
    @ObservedObject var visibility: LayerVisibility
    @ObservedObject var locationService: LocationService
    @ObservedObject var calibration: CalibrationSession
    @ObservedObject var opsec = OpsecSettings.shared
    var graphicsLocked: Bool = false
    var peers: [String: PresencePeer] = [:]

    func makeUIView(context: Context) -> TileMapView {
        let start = locationService.lastLocation?.coordinate
            ?? (mapVM.cameraCentre.latitude == 0 && mapVM.cameraCentre.longitude == 0
                ? CLLocationCoordinate2D(latitude: 20, longitude: 0)
                : mapVM.cameraCentre)
        let camera = MapCamera(center: start, zoom: 4, headingDegrees: 0, viewportSize: .zero)
        let view = TileMapView(camera: camera)
        view.onCameraChange = { [weak coordinator = context.coordinator] cam in
            coordinator?.publish(cam)
            coordinator?.reprojectOverlays()
        }
        view.onGestureBegan = { [weak mapVM] in mapVM?.isBrowsing = true }
        context.coordinator.attach(view: view, mapVM: mapVM)
        context.coordinator.wireEditing(
            mapVM: mapVM, waypointStore: waypointStore, drawingStore: drawingStore,
            drawingSession: drawingSession, measureSession: measureSession,
            calibration: calibration)
        return view
    }

    func updateUIView(_ view: TileMapView, context: Context) {
        // Only swap the source on an actual style change (assigning it clears the
        // tile cache), and only republish when the waypoint set changes - else
        // publish() mutates mapVM, re-runs updateUIView, and loops.
        context.coordinator.syncSource(view: view, mapSource: mapVM.mapSource,
                                       onlineBasemaps: opsec.onlineBasemaps)
        context.coordinator.syncPDF(source: mapVM.mapSource, view: view)
        context.coordinator.syncWaypoints(waypointStore.waypoints, view: view)
        context.coordinator.updateOverlays(
            drawings: DrawingVectorShapes.build(
                drawings: drawingStore.visibleShapes,
                drawingsVisible: visibility.drawingsVisible,
                selectedDrawingID: mapVM.selectedDrawingID,
                session: drawingSession,
                measure: measureSession),
            gridVisible: visibility.mgrsGridVisible,
            peers: peers,
            decorations: Coordinator.buildDecorations(
                drawingStore: drawingStore, drawingSession: drawingSession,
                measureSession: measureSession, visibility: visibility),
            handles: Coordinator.buildEditHandles(
                selectedID: mapVM.selectedDrawingID, drawingStore: drawingStore),
            graphicsLocked: graphicsLocked)
        context.coordinator.syncCalibrationMarkers(calibration)
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator {
        private weak var view: TileMapView?
        private weak var mapVM: MapViewModel?
        var waypoints: [Waypoint] = []
        private var cameraSink: AnyCancellable?
        private var resetNorthSink: AnyCancellable?

        /// Identity of the currently-installed source, so we only reassign (and
        /// clear the tile cache) when it actually changes. nil = never synced.
        private var currentSourceKey: String?
        private var lastWaypointIDs: [UUID] = []

        /// Vector overlays drawn on top of the tiles, projected via the camera.
        private var drawingsView: DrawingsOverlayView?
        private var gridView: MGRSGridOverlayView?
        private var gridVisible = false
        private var lastGridFingerprint = ""

        /// Imported PDF/GeoPDF image + the dark mask beneath it, below the grid.
        private var pdfView: PDFImageOverlayView?
        private var pdfMask: UIView?
        private var pdfSourceID: UUID?

        /// Sync presence peers, on top of everything.
        private var presenceView: PresenceOverlayView?

        /// Drawing decorations (tap dots, name pills, point pins) + interactive
        /// vertex-edit handles + the gesture layer that drives editing.
        private var decorationsView: DrawingDecorationsOverlayView?
        private var handlesView: VertexHandlesOverlayView?
        let editing = MapEditingController()

        func attach(view: TileMapView, mapVM: MapViewModel) {
            self.view = view
            self.mapVM = mapVM
            cameraSink = mapVM.cameraRequests.sink { [weak self] region in self?.flyTo(region) }
            resetNorthSink = mapVM.resetNorthRequests.sink { [weak view] _ in
                view?.camera.headingDegrees = 0
            }

            // Host the vector renderers as subviews, projected via the live
            // camera. Bottom-to-top: grid, drawings, decorations, edit handles,
            // presence. Taps fall through to the tile view / editing layer.
            let project: (CLLocationCoordinate2D) -> CGPoint = { [weak view] coord in
                view?.camera.screenPoint(for: coord) ?? .zero
            }
            let grid = MGRSGridOverlayView()
            let drawings = DrawingsOverlayView()
            let decorations = DrawingDecorationsOverlayView()
            let handles = VertexHandlesOverlayView()
            let presence = PresenceOverlayView()
            for v in [grid, drawings, decorations, handles, presence] as [UIView] {
                v.frame = view.bounds
                v.autoresizingMask = [.flexibleWidth, .flexibleHeight]
                view.addSubview(v)
            }
            grid.project = project
            drawings.project = project
            decorations.project = project
            handles.project = project
            presence.project = project
            gridView = grid
            drawingsView = drawings
            decorationsView = decorations
            handlesView = handles
            presenceView = presence

            // The editing gesture layer. Its refs are wired here; per-frame
            // state (handles, graphicsLocked) is pushed in updateOverlays.
            editing.handlesView = handles
            editing.attach(to: view)
        }

        /// Wire the editing controller's store/session refs + calibration hooks.
        /// Called from makeUIView after the coordinator is built.
        func wireEditing(mapVM: MapViewModel, waypointStore: WaypointStore,
                         drawingStore: DrawingStore, drawingSession: DrawingSessionViewModel,
                         measureSession: MeasureSession, calibration: CalibrationSession) {
            editing.mapVM = mapVM
            editing.waypointStore = waypointStore
            editing.drawingStore = drawingStore
            editing.drawingSession = drawingSession
            editing.measureSession = measureSession
            editing.calibration = calibration
            editing.pdfScreenTapToPDFPoint = { [weak self] pt in
                guard let self, let pdf = self.pdfView, let view = self.view else { return nil }
                return pdf.pdfPoint(forScreenTap: pt, inView: view)
            }
            editing.refreshCalibrationMarkers = { [weak self] in
                guard let self else { return }
                self.pdfView?.syncFiduciaryMarkers(
                    calibration.isCalibrating ? calibration.fiduciaries : [],
                    pendingPDFPoint: calibration.isCalibrating ? calibration.pendingTap?.pdfPoint : nil)
            }
        }

        /// Redraw overlays after a camera move (positions move; grid re-tessellates
        /// only when the visible cells change).
        func reprojectOverlays() {
            if let pdfView, let view {
                pdfView.updateFrame(project: { view.camera.screenPoint(for: $0) },
                                    headingDegrees: view.camera.headingDegrees)
            }
            drawingsView?.reproject()
            gridView?.reproject()
            presenceView?.reproject()
            decorationsView?.reproject()
            handlesView?.reproject()
            refreshGrid()
        }

        /// Attach/detach the imported-PDF image (+ dark mask) beneath the grid.
        func syncPDF(source: MapSource, view: TileMapView) {
            guard let pdf = source as? PDFMapSource, let bounds = pdf.bounds,
                  let image = pdf.renderedImage() else {
                pdfView?.removeFromSuperview(); pdfView = nil
                pdfMask?.removeFromSuperview(); pdfMask = nil
                pdfSourceID = nil
                return
            }
            guard pdf.id != pdfSourceID else { return }
            pdfSourceID = pdf.id
            pdfView?.removeFromSuperview()
            pdfMask?.removeFromSuperview()

            // Dark mask so tiles don't show through the imported sheet.
            let mask = UIView(frame: view.bounds)
            mask.backgroundColor = UIColor(white: 0.10, alpha: 1.0)
            mask.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            mask.isUserInteractionEnabled = false
            view.insertSubview(mask, at: 0)
            pdfMask = mask

            let pv = PDFImageOverlayView(image: image, southWest: bounds.southWest,
                                         northEast: bounds.northEast,
                                         pdfRenderRect: pdf.pdfRenderRect,
                                         placementTransform: pdf.placementTransform)
            view.insertSubview(pv, aboveSubview: mask)
            pv.updateFrame(project: { view.camera.screenPoint(for: $0) },
                           headingDegrees: view.camera.headingDegrees)
            pdfView = pv
        }

        /// Forward fiduciary markers into the PDF overlay (no-op when there's
        /// no PDF or calibration is off) - matches the MKMapView coordinator.
        func syncCalibrationMarkers(_ calibration: CalibrationSession) {
            guard let pdfView else { return }
            if calibration.isCalibrating {
                pdfView.syncFiduciaryMarkers(calibration.fiduciaries,
                                             pendingPDFPoint: calibration.pendingTap?.pdfPoint)
            } else {
                pdfView.syncFiduciaryMarkers([], pendingPDFPoint: nil)
            }
        }

        /// Push new overlay geometry (drawings changed / selection changed).
        func updateOverlays(drawings: [PDFVectorShape], gridVisible: Bool,
                            peers: [String: PresencePeer],
                            decorations: DrawingDecorationsOverlayView.Model,
                            handles: [EditHandle], graphicsLocked: Bool) {
            drawingsView?.update(shapes: drawings)
            presenceView?.update(peers: peers)
            decorationsView?.update(model: decorations)
            handlesView?.update(handles: handles)
            editing.handles = handles
            editing.graphicsLocked = graphicsLocked
            if gridVisible != self.gridVisible {
                self.gridVisible = gridVisible
                if !gridVisible { gridView?.clear(); lastGridFingerprint = "" }
                else { refreshGrid() }
            }
        }

        // MARK: - Model builders (drawing decorations + vertex-edit handles)

        static let measureDotHex = "#FFA62E"

        static func buildDecorations(drawingStore: DrawingStore,
                                     drawingSession: DrawingSessionViewModel,
                                     measureSession: MeasureSession,
                                     visibility: LayerVisibility)
            -> DrawingDecorationsOverlayView.Model {
            var m = DrawingDecorationsOverlayView.Model()
            // Tap dots while drawing / measuring.
            if drawingSession.isDrawing {
                for c in drawingSession.inProgressCoordinates {
                    m.dots.append(.init(lat: c.latitude, lon: c.longitude,
                                        colorHex: drawingSession.strokeColorHex))
                }
            }
            if measureSession.isActive {
                for c in measureSession.points {
                    m.dots.append(.init(lat: c.latitude, lon: c.longitude, colorHex: measureDotHex))
                }
            }
            // Point pins + name pills for finished shapes.
            guard visibility.drawingsVisible else { return m }
            let labelsOn = visibility.drawingLabelsVisible
            for shape in drawingStore.visibleShapes {
                if shape.kind == .point, let c = shape.clEffectiveCoordinates.first {
                    m.pins.append(.init(lat: c.latitude, lon: c.longitude,
                                        colorHex: shape.style.strokeColorHex))
                }
                if labelsOn, let name = shape.name?.trimmingCharacters(in: .whitespaces),
                   !name.isEmpty, let anchor = shape.labelAnchor {
                    m.labels.append(.init(lat: anchor.latitude, lon: anchor.longitude, text: name))
                }
            }
            return m
        }

        static func buildEditHandles(selectedID: UUID?, drawingStore: DrawingStore) -> [EditHandle] {
            guard let selectedID,
                  let shape = drawingStore.visibleShapes.first(where: { $0.id == selectedID })
            else { return [] }
            let coords = shape.clEffectiveCoordinates
            let isFreehand = shape.kind == .freedraw || (shape.kind == .polyline && coords.count > 20)
            guard !isFreehand, shape.kind == .polyline || shape.kind == .polygon else { return [] }
            var out: [EditHandle] = []
            for (i, c) in coords.enumerated() {
                out.append(EditHandle(shapeID: shape.id, vertexIndex: i, isMidpoint: false,
                                      lat: c.latitude, lon: c.longitude))
            }
            let segmentCount = shape.kind == .polygon ? coords.count : coords.count - 1
            for i in 0 ..< max(segmentCount, 0) {
                let a = coords[i], b = coords[(i + 1) % coords.count]
                out.append(EditHandle(shapeID: shape.id, vertexIndex: i + 1, isMidpoint: true,
                                      lat: (a.latitude + b.latitude) / 2,
                                      lon: (a.longitude + b.longitude) / 2))
            }
            return out
        }

        /// Rebuild the MGRS grid for the current visible region, deduped by a
        /// coarse fingerprint so panning inside a cell doesn't re-tessellate.
        private func refreshGrid() {
            guard gridVisible, let view, let gridView else { return }
            let region = MapProjectionMath.visibleRegion(view.camera)
            let fp = String(format: "%.3f,%.3f,%.3f,%.3f,%.0f",
                            region.center.latitude, region.center.longitude,
                            region.span.latitudeDelta, region.span.longitudeDelta,
                            view.bounds.width)
            guard fp != lastGridFingerprint else { return }
            lastGridFingerprint = fp
            let built = MGRSGridRenderer.build(for: region, mapWidthPoints: view.bounds.width)
            gridView.update(lines: built.lines, labels: built.labels)
        }

        /// Set the view's tile source, but only when it actually changes -
        /// assigning `source` clears the tile cache, so doing it every
        /// updateUIView would wipe tiles before they render.
        ///
        /// Online raster (gated on) -> fetch. Offline MBTiles -> local read.
        /// Otherwise (gated-off online, or a PDF source whose image overlay draws
        /// on top) -> nil = dark background.
        func syncSource(view: TileMapView, mapSource: MapSource, onlineBasemaps: Bool) {
            let key: String
            let make: () -> RasterTileSource?
            switch mapSource {
            case let online as OnlineRasterBasemapSource where onlineBasemaps:
                key = "online:\(online.style.rawValue)"
                make = { OnlineRasterTileSource(online.style) }
            case let offline as OfflineTileMapSource:
                key = "offline:\(offline.id)"
                make = { OfflineRasterTileSource(offline) }
            default:
                key = "blank"
                make = { nil }
            }
            if currentSourceKey != key {
                currentSourceKey = key
                view.source = make()
            }
        }

        /// Republish projection only when the waypoint set changes (camera moves
        /// already republish via onCameraChange), else we'd loop.
        func syncWaypoints(_ wps: [Waypoint], view: TileMapView) {
            let ids = wps.map(\.id)
            waypoints = wps
            if ids != lastWaypointIDs {
                lastWaypointIDs = ids
                publish(view.camera)
            }
        }

        /// Publish the projection state MapViewModel exposes to the overlays.
        func publish(_ cam: MapCamera) {
            guard let mapVM else { return }
            var positions: [UUID: CGPoint] = [:]
            for wp in waypoints { positions[wp.id] = cam.screenPoint(for: wp.coordinate) }
            let mpp = cam.metresPerPoint
            let zsf = MapGeometry.zoomScaleFactor(metresPerPoint: mpp, reference: 1.0)
            let heading = cam.headingDegrees
            let centre = cam.center
            DispatchQueue.main.async { [weak self] in
                mapVM.waypointScreenPositions = positions
                mapVM.currentMetresPerPoint = mpp
                mapVM.zoomScaleFactor = zsf
                mapVM.cameraCentre = centre
                mapVM.heading = heading
                if mapVM.screenToCoordinate == nil {
                    mapVM.screenToCoordinate = { [weak self] pt in
                        self?.view?.camera.coordinate(for: pt)
                            ?? CLLocationCoordinate2D(latitude: 0, longitude: 0)
                    }
                }
            }
        }

        /// Fly to a region: centre on it and pick the zoom that fits its
        /// latitude span in the viewport height.
        func flyTo(_ region: MKCoordinateRegion) {
            guard let view else { return }
            var cam = view.camera
            cam.center = region.center
            let metresForSpan = region.span.latitudeDelta * 111_320
            let mpp = metresForSpan / Double(max(view.bounds.height, 1))
            cam.zoom = min(max(WebMercator.zoom(latitude: region.center.latitude,
                                                groundResolution: mpp), 2), 19)
            view.camera = cam
        }
    }
}
