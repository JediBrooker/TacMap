import MapKit
import UIKit

/// Geo-anchored terrain heatmap image pinned to a region. Rows go north
/// to south, columns west to east (matching TerrainHeatmapService) so
/// it draws upright over the map.
final class TerrainHeatmapOverlay: NSObject, MKOverlay {
    let image: UIImage
    let coordinate: CLLocationCoordinate2D
    let boundingMapRect: MKMapRect

    init(image: UIImage, region: MKCoordinateRegion) {
        self.image = image
        self.coordinate = region.center
        let ne = MKMapPoint(CLLocationCoordinate2D(
            latitude: region.center.latitude + region.span.latitudeDelta / 2,
            longitude: region.center.longitude + region.span.longitudeDelta / 2))
        let sw = MKMapPoint(CLLocationCoordinate2D(
            latitude: region.center.latitude - region.span.latitudeDelta / 2,
            longitude: region.center.longitude - region.span.longitudeDelta / 2))
        self.boundingMapRect = MKMapRect(
            x: min(ne.x, sw.x), y: min(ne.y, sw.y),
            width: abs(ne.x - sw.x), height: abs(ne.y - sw.y))
        super.init()
    }
}

/// Draws heatmap image into its geographic rect. MKOverlayRenderer context
/// is y-flipped vs UIKit images so we flip before drawing.
final class TerrainHeatmapRenderer: MKOverlayRenderer {
    private let image: UIImage

    init(heatmap: TerrainHeatmapOverlay) {
        self.image = heatmap.image
        super.init(overlay: heatmap)
    }

    override func draw(_ mapRect: MKMapRect, zoomScale: MKZoomScale, in ctx: CGContext) {
        guard let cg = image.cgImage else { return }
        let rect = self.rect(for: overlay.boundingMapRect)
        ctx.saveGState()
        ctx.translateBy(x: rect.minX, y: rect.maxY)
        ctx.scaleBy(x: 1, y: -1)
        ctx.interpolationQuality = .high
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: rect.width, height: rect.height))
        ctx.restoreGState()
    }
}
