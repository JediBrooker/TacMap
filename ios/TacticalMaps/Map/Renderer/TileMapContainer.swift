import SwiftUI
import MapKit
import Combine
import CoreLocation

/// Small MapKit-typed helpers the container needs (MKCoordinateRegion is just a
/// center+span struct; keeping this out of the MapKit-free MapCamera core).
enum MapProjectionMath {
    /// The lat/lon bounding box the camera currently shows, as a region.
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

    /// A north-up square around the viewport's half-diagonal. Every rotation of
    /// the real viewport fits inside it, so Heading Up can reuse one heatmap
    /// sample instead of either exposing blank wedges or starving its debounce.
    static func orientationInvariantRegion(_ camera: MapCamera) -> MKCoordinateRegion {
        var northUp = camera
        northUp.headingDegrees = 0
        let centre = CGPoint(
            x: camera.viewportSize.width / 2,
            y: camera.viewportSize.height / 2
        )
        let radius = hypot(camera.viewportSize.width, camera.viewportSize.height) / 2
        let coords = [
            CGPoint(x: centre.x - radius, y: centre.y - radius),
            CGPoint(x: centre.x + radius, y: centre.y - radius),
            CGPoint(x: centre.x - radius, y: centre.y + radius),
            CGPoint(x: centre.x + radius, y: centre.y + radius),
        ].map { northUp.coordinate(for: $0) }
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
    var drawingControlsPreview: DrawingShape?
    var peers: [String: PresencePeer] = [:]
    var onPeerTap: (String) -> Void
    var onMutationError: (LocalizedMessage) -> Void
    var onEmptyMapLongPress: ((CLLocationCoordinate2D) -> Void)? = nil

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
            calibration: calibration, onMutationError: onMutationError)
        context.coordinator.editing.onPresenceTap = onPeerTap
        context.coordinator.editing.onEmptyMapLongPress = onEmptyMapLongPress
        return view
    }

    func updateUIView(_ view: TileMapView, context: Context) {
        // SwiftUI may replace the state-capturing closure between updates.
        context.coordinator.editing.onMutationError = onMutationError
        context.coordinator.editing.onPresenceTap = onPeerTap
        context.coordinator.editing.onEmptyMapLongPress = onEmptyMapLongPress
        view.isRotationGestureEnabled = opsec.mapOrientationMode == .northUp
        // Only swap the source on an actual style change (assigning it clears the
        // tile cache), and only republish when the waypoint set changes - else
        // publish() mutates mapVM, re-runs updateUIView, and loops.
        // calibrating always shows the sheet, you cant place points on a hidden map
        context.coordinator.syncSource(view: view, mapSource: mapVM.mapSource,
                                       onlineBasemaps: opsec.onlineBasemaps,
                                       importedMapVisible: visibility.importedMapVisible || calibration.isCalibrating)
        context.coordinator.syncWaypoints(waypointStore.waypoints, view: view)
        context.coordinator.syncDrawingControlsPreview(drawingControlsPreview)
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
        context.coordinator.syncHeatmap(
            visible: visibility.terrainHeatmapVisible,
            onlineLookups: opsec.onlineLookups
        )
        context.coordinator.syncUserLocation(
            coordinate: locationService.lastLocation?.coordinate,
            accuracy: locationService.lastLocation?.horizontalAccuracy ?? 0,
            visible: visibility.userLocationVisible)
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator {
        private weak var view: TileMapView?
        private weak var mapVM: MapViewModel?
        var waypoints: [Waypoint] = []
        private var cameraSink: AnyCancellable?
        private var resetNorthSink: AnyCancellable?
        private var headingSink: AnyCancellable?
        private var exactCameraSink: AnyCancellable?

        /// Identity of the currently-installed source, so we only reassign (and
        /// clear the tile cache) when it actually changes. nil = never synced.
        private var currentSourceKey: String?
        private struct WaypointProjectionKey: Equatable {
            let id: UUID
            let latitude: Double
            let longitude: Double
        }
        private var lastWaypointProjectionKeys: [WaypointProjectionKey] = []

        /// Vector overlays drawn on top of the tiles, projected via the camera.
        private var drawingsView: DrawingsOverlayView?
        private var durableDrawingVectors: [PDFVectorShape] = []
        private var gestureDrawingPreview: DrawingShape?
        private var controlsDrawingPreview: DrawingShape?
        private var gridView: MGRSGridOverlayView?
        private var gridVisible = false
        /// What the installed (or in-flight) grid was built for. nil = rebuild.
        private var gridRequest: MGRSGridRenderer.BuildRequest?
        private var gridGeneration: UInt64 = 0
        private let gridQueue = DispatchQueue(label: "com.tacmap.mgrs-grid", qos: .userInitiated)

        /// Calibration markers on the imported PDF (page space -> georef -> camera).
        private var markersView: PDFPageMarkersView?
        /// georef the markers + calibration taps go through (the active PDF's)
        private var markerGeoref: PdfGeoreference?
        /// grid build in flight + the newest request that arrived meanwhile.
        /// latest wins: a pinch never queues a pile of full rebuilds
        private var gridBuildInFlight = false
        private var gridQueuedRequest: MGRSGridRenderer.BuildRequest?
        private var gridCancel: MGRSGridRenderer.CancelToken?

        /// Sync presence peers, on top of everything.
        private var presenceView: PresenceOverlayView?

        /// The blue "you are here" dot (MKMapView drew this for free).
        private var userLocationView: UserLocationOverlayView?

        /// Drawing decorations (tap dots, name pills, point pins) + interactive
        /// vertex-edit handles + the gesture layer that drives editing.
        private var decorationsView: DrawingDecorationsOverlayView?
        private var durableDrawingDecorations = DrawingDecorationsOverlayView.Model()
        private var handlesView: VertexHandlesOverlayView?
        private var durableDrawingHandles: [EditHandle] = []
        let editing = MapEditingController()

        /// Auto terrain heatmap (opt-in, samples Open-Meteo behind the online
        /// lookups gate). Fetched debounced when the map settles.
        private var heatmapView: HeatmapOverlayView?
        private var heatmapVisible = false
        private var heatmapOnlineLookups = false
        private var heatmapTask: Task<Void, Never>?
        private var heatmapGeneration: UInt64 = 0
        private let heatmapService = TerrainHeatmapService()
        private struct HeatmapMotionKey: Equatable {
            let latitude: Double
            let longitude: Double
            let zoom: Double
            /// The orientation-invariant fetch region depends on the viewport's
            /// half-diagonal, but deliberately not on live camera heading.
            let viewportRadius: CGFloat

            init(camera: MapCamera) {
                latitude = camera.center.latitude
                longitude = camera.center.longitude
                zoom = camera.zoom
                viewportRadius = hypot(
                    camera.viewportSize.width,
                    camera.viewportSize.height
                ) / 2
            }
        }
        private var lastHeatmapMotionKey: HeatmapMotionKey?

        func attach(view: TileMapView, mapVM: MapViewModel) {
            self.view = view
            self.mapVM = mapVM
            cameraSink = mapVM.cameraRequests.sink { [weak self] region in self?.flyTo(region) }
            resetNorthSink = mapVM.resetNorthRequests.sink { [weak view] _ in
                guard let view else { return }
                var next = view.camera
                next.headingDegrees = 0
                view.camera = next
            }
            exactCameraSink = mapVM.exactCameraRequests.sink { [weak view] target in
                guard let view else { return }
                var next = view.camera
                next.center = target.center
                next.zoom = target.zoom
                next.headingDegrees = MapHeading.normalized(target.heading)
                view.camera = next
            }
            headingSink = mapVM.headingRequests.sink { [weak view] heading in
                guard let view else { return }
                var next = view.camera
                next.headingDegrees = MapHeading.normalized(heading)
                view.camera = next
            }

            // Host the vector renderers as subviews, projected via the live
            // camera. Bottom-to-top: grid, drawings, decorations, edit handles,
            // presence. Taps fall through to the tile view / editing layer.
            let project: (CLLocationCoordinate2D) -> CGPoint = { [weak view] coord in
                view?.camera.screenPoint(for: coord) ?? .zero
            }
            let heatmap = HeatmapOverlayView()
            let grid = MGRSGridOverlayView()
            let drawings = DrawingsOverlayView()
            let markers = PDFPageMarkersView()
            let decorations = DrawingDecorationsOverlayView()
            let handles = VertexHandlesOverlayView()
            let presence = PresenceOverlayView()
            let userLocation = UserLocationOverlayView()
            // Heatmap is a ground layer (just above the basemap), so it goes
            // first; the user dot sits on top of everything.
            for v in [heatmap, grid, drawings, markers, decorations, handles, presence, userLocation] as [UIView] {
                v.frame = view.bounds
                v.autoresizingMask = [.flexibleWidth, .flexibleHeight]
                view.addSubview(v)
            }
            heatmap.project = project
            grid.camera = { [weak view] in view?.camera }
            drawings.project = project
            markers.project = project
            decorations.project = project
            handles.project = project
            presence.project = project
            userLocation.project = project
            heatmapView = heatmap
            gridView = grid
            drawingsView = drawings
            markersView = markers
            decorationsView = decorations
            handlesView = handles
            presenceView = presence
            userLocationView = userLocation

            // The editing gesture layer. Its refs are wired here; per-frame
            // state (handles, graphicsLocked) is pushed in updateOverlays.
            editing.handlesView = handles
            editing.presenceView = presence
            editing.attach(to: view)

            // base raster landed, bake attached, render failed: lay tiles out again
            mapVM.pdfRuntime.onNeedsLayout = { [weak view] in view?.layoutTiles() }
        }

        /// Wire the editing controller's store/session refs + calibration hooks.
        /// Called from makeUIView after the coordinator is built.
        func wireEditing(mapVM: MapViewModel, waypointStore: WaypointStore,
                         drawingStore: DrawingStore, drawingSession: DrawingSessionViewModel,
                         measureSession: MeasureSession, calibration: CalibrationSession,
                         onMutationError: @escaping (LocalizedMessage) -> Void) {
            editing.mapVM = mapVM
            editing.waypointStore = waypointStore
            editing.drawingStore = drawingStore
            editing.drawingSession = drawingSession
            editing.measureSession = measureSession
            editing.calibration = calibration
            editing.onMutationError = onMutationError
            editing.showDrawingPreview = { [weak self] candidate in
                self?.gestureDrawingPreview = candidate
                self?.renderDrawingVectors()
                self?.renderDrawingDecorations()
                self?.renderDrawingHandles()
            }
            // a tap goes screen -> camera -> georef.toPage, the same georef that
            // draws the tiles, so it lands exactly where you tapped at any zoom
            editing.pdfScreenTapToPDFPoint = { [weak self] pt in
                guard let self, let view = self.view, let g = self.markerGeoref,
                      let p = g.toPage(view.camera.coordinate(for: pt)) else { return nil }
                let clip = mapVM.pdfRuntime.currentContext?.footprint.clipPolygon ?? g.crop
                guard PDFClip.contains(clip.map { (x: $0.x, y: $0.y) }, Double(p.x), Double(p.y)) else { return nil }
                return p
            }
            editing.refreshCalibrationMarkers = { [weak self] in
                self?.syncCalibrationMarkers(calibration)
            }
        }

        /// Redraw overlays after a camera move (positions move; grid re-tessellates
        /// only when the visible cells change).
        func reprojectOverlays() {
            drawingsView?.reproject()
            markersView?.reproject()
            gridView?.reproject()
            presenceView?.reproject()
            decorationsView?.reproject()
            handlesView?.reproject()
            heatmapView?.reproject()
            userLocationView?.reproject(metresPerPoint: view?.camera.metresPerPoint ?? 1)
            refreshGrid()
            // Heading Up changes only orientation. Re-starting the debounce on
            // every compass sample would prevent a heatmap fetch from finishing.
            if heatmapVisible, let camera = view?.camera {
                let key = HeatmapMotionKey(camera: camera)
                if key != lastHeatmapMotionKey {
                    lastHeatmapMotionKey = key
                    scheduleHeatmapFetch()
                }
            }
        }

        /// Turn the terrain heatmap on/off; kicks a debounced fetch when on.
        func syncHeatmap(visible: Bool, onlineLookups: Bool) {
            guard visible != heatmapVisible || onlineLookups != heatmapOnlineLookups else { return }
            heatmapVisible = visible
            heatmapOnlineLookups = onlineLookups
            if visible && onlineLookups {
                if let camera = view?.camera {
                    lastHeatmapMotionKey = HeatmapMotionKey(camera: camera)
                }
                scheduleHeatmapFetch()
            } else {
                lastHeatmapMotionKey = nil
                heatmapGeneration &+= 1
                heatmapTask?.cancel(); heatmapTask = nil
                heatmapView?.clear()
            }
        }

        /// Sample the DEM for the current visible region 0.4s after the last
        /// camera move (so panning doesn't fire a request per frame). The
        /// service itself no-ops unless the online-lookups gate is on.
        private func scheduleHeatmapFetch() {
            guard heatmapVisible, heatmapOnlineLookups, let view else { return }
            heatmapTask?.cancel()
            heatmapGeneration &+= 1
            let generation = heatmapGeneration
            let region = MapProjectionMath.orientationInvariantRegion(view.camera)
            heatmapTask = Task { @MainActor [weak self] in
                try? await Task.sleep(nanoseconds: 400_000_000)
                if Task.isCancelled { return }
                let image = await self?.heatmapService.generate(region: region)
                guard !Task.isCancelled, let self,
                      generation == self.heatmapGeneration,
                      self.heatmapVisible, self.heatmapOnlineLookups,
                      OpsecSettings.shared.onlineLookups, let image else { return }
                self.heatmapView?.update(image: image, region: (
                    center: region.center,
                    latDelta: region.span.latitudeDelta,
                    lonDelta: region.span.longitudeDelta))
            }
        }

        /// Position/toggle the blue user-location dot.
        func syncUserLocation(coordinate: CLLocationCoordinate2D?, accuracy: Double, visible: Bool) {
            userLocationView?.update(coordinate: coordinate, accuracyMetres: accuracy, visible: visible)
            userLocationView?.reproject(metresPerPoint: view?.camera.metresPerPoint ?? 1)
        }

        /// Fiduciary markers on the sheet while calibrating, nothing otherwise.
        func syncCalibrationMarkers(_ calibration: CalibrationSession) {
            markerGeoref = (mapVM?.mapSource as? PDFMapSource)?.georef
            if calibration.isCalibrating, let g = markerGeoref {
                markersView?.update(georef: g, fiduciaries: calibration.fiduciaries,
                                    pendingPagePoint: calibration.pendingTap?.pdfPoint)
            } else {
                markersView?.update(georef: nil, fiduciaries: [], pendingPagePoint: nil)
            }
        }

        /// Push new overlay geometry (drawings changed / selection changed).
        func updateOverlays(drawings: [PDFVectorShape], gridVisible: Bool,
                            peers: [String: PresencePeer],
                            decorations: DrawingDecorationsOverlayView.Model,
                            handles: [EditHandle], graphicsLocked: Bool) {
            durableDrawingVectors = drawings
            renderDrawingVectors()
            presenceView?.update(peers: peers)
            durableDrawingDecorations = decorations
            renderDrawingDecorations()
            durableDrawingHandles = handles
            renderDrawingHandles()
            editing.graphicsLocked = graphicsLocked
            if gridVisible != self.gridVisible {
                self.gridVisible = gridVisible
                if !gridVisible {
                    // bump the generation so a build still in flight can't land
                    gridGeneration &+= 1
                    gridRequest = nil
                    gridQueuedRequest = nil
                    gridCancel?.cancel()
                    gridView?.clear()
                } else {
                    refreshGrid()
                }
            }
        }

        func syncDrawingControlsPreview(_ preview: DrawingShape?) {
            guard preview != controlsDrawingPreview else { return }
            controlsDrawingPreview = preview
            renderDrawingVectors()
            renderDrawingDecorations()
            renderDrawingHandles()
        }

        private var activeDrawingPreview: DrawingShape? {
            gestureDrawingPreview ?? controlsDrawingPreview
        }

        private func renderDrawingVectors() {
            let vectors = activeDrawingPreview.map {
                DrawingVectorShapes.replacingDrawingPreview($0, in: durableDrawingVectors)
            } ?? durableDrawingVectors
            drawingsView?.update(shapes: vectors)
        }

        private func renderDrawingDecorations() {
            let model = activeDrawingPreview.map {
                durableDrawingDecorations.replacingDrawingPreview($0)
            } ?? durableDrawingDecorations
            decorationsView?.update(model: model)
        }

        private func renderDrawingHandles() {
            let handles = activeDrawingPreview.map(Self.buildEditHandles(for:))
                ?? durableDrawingHandles
            handlesView?.update(handles: handles)
            editing.handles = handles
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
                    m.pins.append(.init(sourceID: shape.id,
                                        lat: c.latitude, lon: c.longitude,
                                        colorHex: shape.style.strokeColorHex))
                }
                if labelsOn, let name = shape.name?.trimmingCharacters(in: .whitespaces),
                   !name.isEmpty, let anchor = shape.labelAnchor {
                    m.labels.append(.init(sourceID: shape.id,
                                          lat: anchor.latitude, lon: anchor.longitude, text: name))
                }
            }
            return m
        }

        static func buildEditHandles(selectedID: UUID?, drawingStore: DrawingStore) -> [EditHandle] {
            guard let selectedID,
                  let shape = drawingStore.visibleShapes.first(where: { $0.id == selectedID })
            else { return [] }
            return buildEditHandles(for: shape)
        }

        private static func buildEditHandles(for shape: DrawingShape) -> [EditHandle] {
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

        /// Rebuild MGRS geometry when the cache policy says the current build
        /// no longer covers the camera (see MGRSGridRenderer.needsRebuild). The
        /// build square is heading independent and bigger than anything a
        /// 64 dp pan or 0.5 zoom-out can expose, so compass samples and small
        /// pans only reproject. The build itself runs off the main queue; the
        /// old geometry stays up until the new one lands.
        private func refreshGrid() {
            guard gridVisible, let view, let gridView else { return }
            let camera = view.camera
            guard camera.viewportSize.width > 0, camera.viewportSize.height > 0 else { return }
            let pxPerDp = max(Double(gridView.traitCollection.displayScale),
                              Double(gridView.contentScaleFactor), 1)
            guard MGRSGridRenderer.needsRebuild(gridRequest, camera: camera, pxPerDp: pxPerDp) else { return }
            let request = MGRSGridRenderer.BuildRequest(camera: camera, pxPerDp: pxPerDp)
            gridRequest = request
            gridGeneration &+= 1
            // zoomed right out nothing is drawn, no need to hop queues for that
            guard !request.lod.drawn.isEmpty else {
                gridQueuedRequest = nil
                gridCancel?.cancel()
                gridView.update(grid: MGRSGridRenderer.Grid(lod: request.lod, pieces: [], squares: []))
                return
            }
            // latest wins: a build already running is now outdated, stop it and
            // park this one, it starts the moment the old one lets go
            if gridBuildInFlight {
                gridQueuedRequest = request
                gridCancel?.cancel()
                return
            }
            startGridBuild(request)
        }

        private func startGridBuild(_ request: MGRSGridRenderer.BuildRequest) {
            gridBuildInFlight = true
            let generation = gridGeneration
            let token = MGRSGridRenderer.CancelToken()
            gridCancel = token
            gridQueue.async { [weak self] in
                let grid = MGRSGridRenderer.build(request, isCancelled: { token.isCancelled })
                DispatchQueue.main.async {
                    guard let self else { return }
                    self.gridBuildInFlight = false
                    if let grid, !token.isCancelled, generation == self.gridGeneration, self.gridVisible {
                        self.gridView?.update(grid: grid)
                    }
                    if let next = self.gridQueuedRequest {
                        self.gridQueuedRequest = nil
                        if self.gridVisible { self.startGridBuild(next) }
                    }
                }
            }
        }

        /// test hooks
        var isGridBuildInFlight: Bool { gridBuildInFlight }
        var hasQueuedGridBuild: Bool { gridQueuedRequest != nil }

        /// Set the view's tile source, but only when it actually changes -
        /// assigning `source` clears the tile cache, so doing it every
        /// updateUIView would wipe tiles before they render.
        ///
        /// Online raster (gated on) -> fetch. Offline MBTiles -> local read.
        /// PDF -> warped tiles off the page (PDFTileSource), cached in the
        /// runtime per georef + tile size. Otherwise nil = dark background.
        ///
        /// Hidden imported map: same source and cache, just not drawn, and
        /// never an online map in its place.
        func syncSource(view: TileMapView, mapSource: MapSource, onlineBasemaps: Bool,
                        importedMapVisible: Bool = true) {
            let key: String
            let make: () -> RasterTileSource?
            var hidden = false
            var deferMake = false
            switch mapSource {
            case let online as OnlineRasterBasemapSource where onlineBasemaps:
                key = "online:\(online.style.rawValue)"
                make = { OnlineRasterTileSource(online.style) }
            case let offline as OfflineTileMapSource:
                key = "offline:\(offline.id)"
                make = { OfflineRasterTileSource(offline) }
            case let pdf as PDFMapSource:
                guard let runtime = mapVM?.pdfRuntime else { key = "blank"; make = { nil }; break }
                let scale = view.traitCollection.displayScale > 0 ? view.traitCollection.displayScale : UIScreen.main.scale
                hidden = !importedMapVisible
                // a failed source stays put (G1): cached tiles and fallbacks keep
                // drawing under the red header. Try Again bumps the generation so
                // the new source gets swapped in
                key = runtime.sourceKey(for: pdf, screenScale: scale) + "#\(runtime.retryGeneration)"
                make = { runtime.tileSource(for: pdf, screenScale: scale) }
                // building it publishes the render status, swiftui hates that mid update
                deferMake = true
            default:
                key = "blank"
                make = { nil }
            }
            if currentSourceKey != key {
                currentSourceKey = key
                if deferMake {
                    // one runloop turn later, and only if nothing newer came along meanwhile
                    DispatchQueue.main.async { [weak self, weak view] in
                        guard let self, let view, self.currentSourceKey == key else { return }
                        view.source = make()
                    }
                } else {
                    view.source = make()
                }
            }
            view.tilesHidden = hidden
        }

        /// Republish projection when waypoint membership OR coordinates change
        /// (camera moves already republish via onCameraChange), else we'd loop.
        ///
        /// The old ID-only comparison made "Move to Crosshair" persist the new
        /// coordinate but leave the symbol drawn at its old screen position,
        /// which looked exactly like the button did nothing until the next pan.
        func syncWaypoints(_ wps: [Waypoint], view: TileMapView) {
            let keys = wps.map {
                WaypointProjectionKey(
                    id: $0.id,
                    latitude: $0.latitude,
                    longitude: $0.longitude
                )
            }
            waypoints = wps
            if keys != lastWaypointProjectionKeys {
                lastWaypointProjectionKeys = keys
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
            let heading = MapHeading.normalized(cam.headingDegrees)
            let centre = cam.center
            DispatchQueue.main.async { [weak self] in
                mapVM.waypointScreenPositions = positions
                mapVM.currentMetresPerPoint = mpp
                mapVM.zoomScaleFactor = zsf
                mapVM.cameraCentre = centre
                mapVM.mapCameraDidChange(heading: heading)
                if mapVM.screenToCoordinate == nil {
                    mapVM.screenToCoordinate = { [weak self] pt in
                        self?.view?.camera.coordinate(for: pt)
                            ?? CLLocationCoordinate2D(latitude: 0, longitude: 0)
                    }
                }
            }
        }

        /// Fly to a region: centre on it and pick the zoom that fits the whole
        /// of it, both ways (OD-IMPORT, Android's fitExtent). It used to fit the
        /// latitude span only, so a wide sheet on a portrait phone got cropped
        func flyTo(_ region: MKCoordinateRegion) {
            guard let view else { return }
            // a cold restore frames the saved map before the first layout, bounds
            // are still zero then and the fit came out ~z4 (sheet = one dark pixel).
            // the map is full screen, so fit against the screen till we have a size
            let screen = view.window?.windowScene?.screen.bounds.size ?? UIScreen.main.bounds.size
            let size = view.bounds.width > 1 && view.bounds.height > 1 ? view.bounds.size : screen
            guard let fit = MapExtentFit.fit(region, viewport: size) else { return }
            var cam = view.camera
            cam.center = fit.centre
            cam.zoom = fit.zoom
            view.camera = cam
        }
    }
}
