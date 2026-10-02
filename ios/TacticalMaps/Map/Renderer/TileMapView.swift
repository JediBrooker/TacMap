import UIKit
import CoreLocation

/// A self-contained slippy-map view that draws raster tiles for a `MapCamera`
/// and drives that camera from pan/pinch/rotate gestures. No MapKit. Overlays
/// (symbols, drawings, grid) sit on top as subviews projected through the
/// same camera.
///
/// Tiles are CALayers under tileRoot (TileLayerCompositor), laid out from a
/// pure TileDrawPlanner plan: missing tiles show their nearest cached parent
/// or children so zooming and panning never flash the background (D3-11).
final class TileMapView: UIView {

    /// Current view state. Setting it relays out tiles and kicks off loads.
    /// Zoom is clamped to MapCamera.zoomLimits here, the one sink every
    /// gesture and programmatic move goes through.
    var camera: MapCamera {
        didSet {
            // assigning inside didSet doesnt re-enter it, so just carry on
            let clamped = PDFTileMath.clampCameraZoom(camera.zoom)
            if clamped != camera.zoom { camera.zoom = clamped }
            guard camera != oldValue else { return }
            layoutTiles()
            onCameraChange?(camera)
        }
    }

    /// The basemap tile source. Swapping it clears the cache and relays out.
    var source: RasterTileSource? {
        didSet {
            sourceGeneration &+= 1
            cache.removeAll()
            cancelAllLoads()
            compositor.removeAll()
            layoutTiles()
        }
    }

    /// Imported map switched off: draw nothing (dark background), keep the
    /// source and its cache so switching back is instant.
    var tilesHidden = false {
        didSet {
            guard tilesHidden != oldValue else { return }
            compositor.tileRoot.isHidden = tilesHidden
            // hidden: cancel, but dont tell the source anything (E3)
            if tilesHidden { cancelAllLoads() }
            layoutTiles()
        }
    }

    /// Fired whenever a gesture (or a programmatic set) changes the camera.
    var onCameraChange: ((MapCamera) -> Void)?

    /// Fired when the user starts a pan/pinch/rotate - the app flips into
    /// browse mode (header reads map centre, not user location).
    var onGestureBegan: (() -> Void)?

    /// Heading Up owns camera rotation; pan and pinch remain available while
    /// the two-finger rotation recognizer is temporarily ignored.
    var isRotationGestureEnabled = true

    /// what shows through gaps and a hidden imported map (contract H, iosWhite)
    static let backgroundWhite: CGFloat = 0.07

    // MARK: tiles

    private let compositor = TileLayerCompositor()
    private let cache: TileImageCache
    private var inFlight: [TileIndex: PendingTileRequest] = [:]
    private var sourceGeneration: UInt64 = 0
    private var relayoutQueued = false
    private var lastPlanSources = Set<TileIndex>()
    private(set) var lastTileZoom: Int?
    private var memoryObserver: NSObjectProtocol?

    /// what's on screen right now, for tests and the memory trim
    private(set) var lastPlan = TileDrawPlan(items: [], requests: [])

    /// placeholder registered before the source is called, so a source that
    /// completes synchronously can't leave a stale entry behind
    private final class PendingTileRequest {
        var inner: RasterTileRequest?
        func cancel() { inner?.cancel() }
    }

    // MARK: init

    init(camera: MapCamera, cacheBytes: Int = TileImageCache.byteLimit()) {
        self.camera = camera
        self.cache = TileImageCache(byteLimit: cacheBytes)
        super.init(frame: .zero)
        backgroundColor = UIColor(white: Self.backgroundWhite, alpha: 1) // dark, so gaps aren't white
        isOpaque = true
        layer.insertSublayer(compositor.tileRoot, at: 0)
        installGestures()
        memoryObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.didReceiveMemoryWarningNotification, object: nil, queue: .main
        ) { [weak self] _ in self?.didReceiveMemoryWarning() }
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) unused") }

    deinit {
        if let memoryObserver { NotificationCenter.default.removeObserver(memoryObserver) }
        inFlight.values.forEach { $0.cancel() }
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        // Keep the camera's viewport in sync with the actual view size.
        if camera.viewportSize != bounds.size {
            camera.viewportSize = bounds.size
        }
        layoutTiles()
    }

    // MARK: layout

    /// Plan, composite, request. Cheap enough for every camera tick: no
    /// decode, no rendering, just bookkeeping on main.
    func layoutTiles() {
        compositor.layoutRoot(bounds: bounds, headingDegrees: camera.headingDegrees)
        guard let source, !tilesHidden, camera.viewportSize.width > 0, camera.viewportSize.height > 0 else {
            compositor.removeAll()
            lastPlan = TileDrawPlan(items: [], requests: [])
            lastPlanSources = []
            return
        }
        let tz = TileMath.tileZoom(for: camera.zoom, minZoom: source.minZoom, maxZoom: source.maxZoom)
        lastTileZoom = tz
        if TileMath.isUnderzoomed(tileZoom: tz, cameraZoom: camera.zoom) {
            compositor.removeAll()
            lastPlan = TileDrawPlan(items: [], requests: [])
            lastPlanSources = []
            // E3: underzoomed counts as hidden, wanted callbacks are skipped
            cancelAllLoads()
            return
        }
        let visible = TileMath.visibleTiles(camera: camera, tileZoom: tz)
        let grid = TileGrid(camera: camera, tileZoom: tz)
        let plan = TileDrawPlanner.plan(visible: visible, state: { [cache] t in
            if let e = cache.peek(t) {
                switch e {
                case .image: return .image
                case .empty: return .empty
                }
            }
            return source.hasContent(t) ? .missing : .empty
        }, fallbackZoom: source.fallbackZoom(forTileZoom: tz))
        compositor.apply(items: plan.items, grid: grid) { [cache] t in
            if case .image(let img)? = cache.entry(for: t) { return img }
            return nil
        }
        lastPlan = plan
        lastPlanSources = Set(plan.items.map(\.source))

        let wanted = Set(plan.requests)
        for (t, req) in inFlight where !wanted.contains(t) {
            req.cancel()
            inFlight[t] = nil
        }
        source.wantedTilesDidChange(plan.requests, tileZoom: tz, centreUnit: TileMath.viewportCentreUnit(camera: camera))
        for t in plan.requests where inFlight[t] == nil {
            loadTile(t, from: source)
        }
    }

    /// one relayout per runloop turn however many tiles land
    private func scheduleRelayout() {
        guard !relayoutQueued else { return }
        relayoutQueued = true
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.relayoutQueued = false
            self.layoutTiles()
        }
    }

    // MARK: loading

    private func loadTile(_ tile: TileIndex, from source: RasterTileSource) {
        let generation = sourceGeneration
        let pending = PendingTileRequest()
        inFlight[tile] = pending
        let req = source.loadTile(tile) { [weak self] image in
            guard let self, generation == self.sourceGeneration else { return }
            if self.inFlight[tile] === pending { self.inFlight[tile] = nil }
            guard let image else { return }
            if image === RasterTileSourceEmpty.image {
                self.cache.insert(.empty, for: tile)
            } else if let cg = image.cgImage {
                self.cache.insert(.image(cg), for: tile)
            } else {
                return
            }
            self.scheduleRelayout()
        }
        if let req {
            pending.inner = req
        } else if inFlight[tile] === pending {
            // source declined without a token (eg a failed PDF), dont wedge the key
            inFlight[tile] = nil
        }
    }

    private func cancelAllLoads() {
        inFlight.values.forEach { $0.cancel() }
        inFlight.removeAll()
    }

    /// keep only what this frame draws, the rest reloads from the source
    func didReceiveMemoryWarning() {
        cache.trim(keeping: lastPlanSources)
    }

    // test hooks
    var cachedTileCount: Int { cache.count }
    var inFlightCount: Int { inFlight.count }
    var tileRootLayer: CALayer { compositor.tileRoot }
    func cacheState(_ t: TileIndex) -> TileCacheState {
        switch cache.peek(t) {
        case .image?: return .image
        case .empty?: return .empty
        case nil: return .missing
        }
    }

    // MARK: gestures

    /// The browse recognisers (pan/pinch/rotate). The editing layer flips these
    /// off while dragging a shape or vertex so the basemap doesn't slide under
    /// the finger - the MapKit path did this via `isScrollEnabled = false`.
    private(set) var browseGestures: [UIGestureRecognizer] = []

    func setBrowseGesturesEnabled(_ enabled: Bool) {
        browseGestures.forEach { $0.isEnabled = enabled }
    }

    private func installGestures() {
        let pan = UIPanGestureRecognizer(target: self, action: #selector(handlePan))
        let pinch = UIPinchGestureRecognizer(target: self, action: #selector(handlePinch))
        let rotate = UIRotationGestureRecognizer(target: self, action: #selector(handleRotate))
        [pan, pinch, rotate].forEach { $0.delegate = self; addGestureRecognizer($0) }
        browseGestures = [pan, pinch, rotate]
    }

    // Gestures apply their INCREMENTAL delta each callback and reset it to zero.
    // That composes correctly when pan + pinch + rotate run together, instead of
    // three handlers fighting over one shared start state.

    private var viewportCenter: CGPoint { CGPoint(x: bounds.midX, y: bounds.midY) }

    @objc private func handlePan(_ gr: UIPanGestureRecognizer) {
        if gr.state == .began { onGestureBegan?() }
        let t = gr.translation(in: self)
        gr.setTranslation(.zero, in: self)
        // New centre = the coord currently at (centre - delta), so the map
        // follows the finger.
        camera.center = camera.coordinate(for: CGPoint(x: viewportCenter.x - t.x,
                                                       y: viewportCenter.y - t.y))
    }

    @objc private func handlePinch(_ gr: UIPinchGestureRecognizer) {
        if gr.state == .began { onGestureBegan?() }
        applyPinch(scale: Double(gr.scale), focal: gr.location(in: self))
        gr.scale = 1
    }

    /// One pinch step about focal. Clamped only to the global camera range
    /// (D3-08), never to the source's zoom range: past a source's maxZoom the
    /// last level just gets scaled up.
    func applyPinch(scale: Double, focal: CGPoint) {
        guard scale.isFinite, scale > 0 else { return }
        let anchor = camera.coordinate(for: focal) // coord under the fingers
        var next = camera
        next.zoom = PDFTileMath.pinchZoom(camera.zoom, scale: scale)
        // Keep that coord under the fingers while zooming.
        let landed = next.screenPoint(for: anchor)
        next.center = next.coordinate(for: CGPoint(x: viewportCenter.x + (landed.x - focal.x),
                                                   y: viewportCenter.y + (landed.y - focal.y)))
        camera = next
    }

    @objc private func handleRotate(_ gr: UIRotationGestureRecognizer) {
        guard isRotationGestureEnabled else {
            gr.rotation = 0
            return
        }
        switch gr.state {
        case .began:
            // Rotation is a browse gesture too. The old custom-MKMapView path
            // marked this explicitly, but the TileMapView migration only did so
            // for pan/pinch.
            onGestureBegan?()
            consumeRotationGestureDelta(gr)
        case .changed, .ended:
            // `.ended` can carry the last movement since the previous changed
            // callback. Consuming it also preserves very short began→ended
            // rotations that never emit a changed state.
            consumeRotationGestureDelta(gr)
        default:
            break
        }
    }

    /// Consume the recognizer's incremental value exactly once. Internal so
    /// focused tests can verify the reset contract without synthesising touches.
    func consumeRotationGestureDelta(_ gr: UIRotationGestureRecognizer) {
        let delta = gr.rotation
        gr.rotation = 0
        applyRotationGestureDelta(delta)
    }

    /// Apply one incremental rotation and assign the whole camera value so its
    /// observer always fires. Kept internal for focused regression coverage.
    func applyRotationGestureDelta(_ radians: CGFloat) {
        guard isRotationGestureEnabled, radians.isFinite, radians != 0 else { return }
        var next = camera
        next.headingDegrees = MapHeading.addingGestureRotation(
            radians, to: next.headingDegrees
        )
        camera = next
    }
}

extension TileMapView: UIGestureRecognizerDelegate {
    // Let pan + pinch + rotate run together, like a real map.
    func gestureRecognizer(_ g: UIGestureRecognizer,
                           shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
        true
    }
}
