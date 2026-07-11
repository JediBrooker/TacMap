import UIKit
import CoreLocation

/// Geo-anchored terrain heatmap image on the MapKit-free renderer. The image
/// covers a lat/lon region (row 0 = north, col 0 = west, per TerrainHeatmapService)
/// and is placed by projecting the region corners through the camera, so it rides
/// the map through pan/zoom/rotate. Replaces the TerrainHeatmapOverlay MKOverlay.
final class HeatmapOverlayView: UIView {

    var project: ((CLLocationCoordinate2D) -> CGPoint)?

    private let imageView = UIImageView()
    private var nw = CLLocationCoordinate2D()
    private var ne = CLLocationCoordinate2D()
    private var sw = CLLocationCoordinate2D()
    private var centre = CLLocationCoordinate2D()
    private var hasImage = false

    init() {
        super.init(frame: .zero)
        backgroundColor = .clear
        isOpaque = false
        isUserInteractionEnabled = false
        autoresizingMask = [.flexibleWidth, .flexibleHeight]
        imageView.contentMode = .scaleToFill
        imageView.layer.magnificationFilter = .linear
        addSubview(imageView)
        imageView.isHidden = true
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { nil }

    func update(image: UIImage, region: (center: CLLocationCoordinate2D,
                                        latDelta: Double, lonDelta: Double)) {
        let c = region.center
        centre = c
        nw = .init(latitude: c.latitude + region.latDelta / 2, longitude: c.longitude - region.lonDelta / 2)
        ne = .init(latitude: c.latitude + region.latDelta / 2, longitude: c.longitude + region.lonDelta / 2)
        sw = .init(latitude: c.latitude - region.latDelta / 2, longitude: c.longitude - region.lonDelta / 2)
        imageView.image = image
        imageView.isHidden = false
        hasImage = true
        reproject()
    }

    func reproject() {
        guard hasImage, let project else { return }
        let pNW = project(nw), pNE = project(ne), pSW = project(sw)
        // East edge (NW->NE) gives width + screen rotation; south edge (NW->SW)
        // gives height. Conformal at this scale so the two stay perpendicular.
        let w = hypot(pNE.x - pNW.x, pNE.y - pNW.y)
        let h = hypot(pSW.x - pNW.x, pSW.y - pNW.y)
        let angle = atan2(pNE.y - pNW.y, pNE.x - pNW.x)
        imageView.transform = .identity
        imageView.bounds = CGRect(x: 0, y: 0, width: max(w, 1), height: max(h, 1))
        imageView.transform = CGAffineTransform(rotationAngle: angle)
        imageView.center = project(centre)
    }

    func clear() {
        imageView.image = nil
        imageView.isHidden = true
        hasImage = false
    }
}
