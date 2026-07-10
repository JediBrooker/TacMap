import UIKit
import CoreLocation

/// A self-contained slippy-map view that draws raster tiles for a `MapCamera`
/// and drives that camera from pan/pinch/rotate gestures. No MapKit: this is the
/// piece that lets us drop MKMapView. Overlays (symbols, drawings, grid) get
/// wired on top of it in later steps via the same `camera` projection.
final class TileMapView: UIView {

    /// Current view state. Setting it redraws and kicks off any missing loads.
    var camera: MapCamera {
        didSet {
            guard camera != oldValue else { return }
            setNeedsDisplay()
            onCameraChange?(camera)
        }
    }

    /// The basemap tile source. Swapping it clears the cache and redraws.
    var source: RasterTileSource? {
        didSet {
            cache.removeAllObjects()
            cancelAllLoads()
            setNeedsDisplay()
        }
    }

    /// Fired whenever a gesture (or a programmatic set) changes the camera.
    var onCameraChange: ((MapCamera) -> Void)?

    /// Fired when the user starts a pan/pinch/rotate - the app flips into
    /// browse mode (header reads map centre, not user location).
    var onGestureBegan: (() -> Void)?

    // MARK: tile cache + in-flight

    private let cache = NSCache<NSString, UIImage>()
    private var inFlight: [String: RasterTileRequest] = [:]

    private func key(_ t: TileIndex) -> String { "\(t.z)/\(t.x)/\(t.y)" }

    // MARK: init

    init(camera: MapCamera) {
        self.camera = camera
        super.init(frame: .zero)
        backgroundColor = UIColor(white: 0.07, alpha: 1) // dark, so gaps aren't white
        isOpaque = true
        cache.countLimit = 400
        installGestures()
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) unused") }

    override func layoutSubviews() {
        super.layoutSubviews()
        // Keep the camera's viewport in sync with the actual view size.
        if camera.viewportSize != bounds.size {
            camera.viewportSize = bounds.size
        }
    }

    // MARK: drawing

    override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext(), let source else { return }
        let tz = TileMath.tileZoom(for: camera.zoom, minZoom: source.minZoom, maxZoom: source.maxZoom)
        let tiles = TileMath.visibleTiles(camera: camera, tileZoom: tz)

        // The tile layout is computed heading-flat; rotate the whole context by
        // the camera heading around the viewport centre to apply rotation.
        ctx.saveGState()
        let c = CGPoint(x: bounds.midX, y: bounds.midY)
        ctx.translateBy(x: c.x, y: c.y)
        ctx.rotate(by: -camera.headingDegrees * .pi / 180)
        ctx.translateBy(x: -c.x, y: -c.y)

        for tile in tiles {
            let frame = TileMath.tileFrame(tile, camera: camera) // CGRect
            if let image = cache.object(forKey: key(tile) as NSString) {
                // Grow by 0.5px to hide hairline seams between adjacent tiles.
                image.draw(in: frame.insetBy(dx: -0.5, dy: -0.5))
            } else {
                loadTile(tile)
            }
        }
        ctx.restoreGState()
    }

    // MARK: loading

    private func loadTile(_ tile: TileIndex) {
        guard let source else { return }
        let k = key(tile)
        guard inFlight[k] == nil else { return }
        let req = source.loadTile(tile) { [weak self] image in
            guard let self else { return }
            self.inFlight[k] = nil
            guard let image else { return }
            self.cache.setObject(image, forKey: k as NSString)
            self.setNeedsDisplay()
        }
        if let req { inFlight[k] = req }
    }

    private func cancelAllLoads() {
        inFlight.values.forEach { $0.cancel() }
        inFlight.removeAll()
    }

    // MARK: gestures

    private func installGestures() {
        let pan = UIPanGestureRecognizer(target: self, action: #selector(handlePan))
        let pinch = UIPinchGestureRecognizer(target: self, action: #selector(handlePinch))
        let rotate = UIRotationGestureRecognizer(target: self, action: #selector(handleRotate))
        [pan, pinch, rotate].forEach { $0.delegate = self; addGestureRecognizer($0) }
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
        guard let source else { return }
        let focal = gr.location(in: self)
        let anchor = camera.coordinate(for: focal) // coord under the fingers

        var next = camera
        next.zoom = (camera.zoom + log2(Double(gr.scale)))
            .clamped(to: Double(source.minZoom)...Double(source.maxZoom))
        gr.scale = 1
        // Keep that coord under the fingers while zooming.
        let landed = next.screenPoint(for: anchor)
        next.center = next.coordinate(for: CGPoint(x: viewportCenter.x + (landed.x - focal.x),
                                                   y: viewportCenter.y + (landed.y - focal.y)))
        camera = next
    }

    @objc private func handleRotate(_ gr: UIRotationGestureRecognizer) {
        let d = gr.rotation
        gr.rotation = 0
        // UIRotation is CCW-positive; map heading is CW-positive.
        camera.headingDegrees += d * 180 / .pi
    }
}

extension TileMapView: UIGestureRecognizerDelegate {
    // Let pan + pinch + rotate run together, like a real map.
    func gestureRecognizer(_ g: UIGestureRecognizer,
                           shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
        true
    }
}

private extension Comparable {
    func clamped(to r: ClosedRange<Self>) -> Self { min(max(self, r.lowerBound), r.upperBound) }
}
