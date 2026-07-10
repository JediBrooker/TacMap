import SwiftUI
import MapKit
import Combine
import CoreLocation

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
    @ObservedObject var locationService: LocationService
    @ObservedObject var opsec = OpsecSettings.shared

    func makeUIView(context: Context) -> TileMapView {
        let start = locationService.lastLocation?.coordinate
            ?? (mapVM.cameraCentre.latitude == 0 && mapVM.cameraCentre.longitude == 0
                ? CLLocationCoordinate2D(latitude: 20, longitude: 0)
                : mapVM.cameraCentre)
        let camera = MapCamera(center: start, zoom: 4, headingDegrees: 0, viewportSize: .zero)
        let view = TileMapView(camera: camera)
        view.onCameraChange = { [weak coordinator = context.coordinator] cam in
            coordinator?.publish(cam)
        }
        view.onGestureBegan = { [weak mapVM] in mapVM?.isBrowsing = true }
        context.coordinator.attach(view: view, mapVM: mapVM)
        return view
    }

    func updateUIView(_ view: TileMapView, context: Context) {
        // Only swap the source on an actual style change (assigning it clears the
        // tile cache), and only republish when the waypoint set changes - else
        // publish() mutates mapVM, re-runs updateUIView, and loops.
        context.coordinator.syncSource(view: view, mapSource: mapVM.mapSource,
                                       onlineBasemaps: opsec.onlineBasemaps)
        context.coordinator.syncWaypoints(waypointStore.waypoints, view: view)
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

        func attach(view: TileMapView, mapVM: MapViewModel) {
            self.view = view
            self.mapVM = mapVM
            cameraSink = mapVM.cameraRequests.sink { [weak self] region in self?.flyTo(region) }
            resetNorthSink = mapVM.resetNorthRequests.sink { [weak view] _ in
                view?.camera.headingDegrees = 0
            }
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
