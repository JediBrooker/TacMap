import MapKit
import UIKit

/// A full-coverage opaque overlay that stands in for Apple's basemap while
/// online basemaps are gated off.
///
/// MapKit has no equivalent of Android's `MapType.NONE`, so the only way to
/// stop the base map from being drawn is to cover it with an `MKTileOverlay`
/// that declares `canReplaceMapContent`. Same mechanism the offline MBTiles
/// path already uses.
///
/// MEASURED, DO NOT ASSUME OTHERWISE: `canReplaceMapContent` suppresses
/// *drawing*, not *fetching*. On a freshly erased iPhone 17 Pro simulator,
/// launching this app and sitting on the map for 35s grew geod's tile store
/// (Caches/com.apple.geod/Vault/MapTiles) by 457,320 bytes with the gate off
/// and 453,200 bytes with it on, over an identical no-app baseline. Same tiles,
/// either way. Apple's basemap is fetched by the geod system daemon, which is
/// outside our sandbox and which we cannot gate.
///
/// So this class buys two real things: no basemap imagery on screen (which is
/// what a shoulder-surfer or a screenshot sees), and no Esri/OpenTopoMap
/// request, since those go through overlays we simply don't install. It does
/// NOT buy zero egress to Apple. THREAT_MODEL section 6 says so out loud.
/// Genuinely closing that needs MKMapView replaced with a renderer we own.
///
/// Tiles are synthesised in memory. `loadTile` never touches the network, and
/// there is no `urlTemplate`, so there is nothing for this class to request.
final class BlankBasemapOverlay: MKTileOverlay {

    /// Stable id so the coordinator doesn't tear down and rebuild the overlay
    /// on every model change. It isn't tied to a real MapSource.
    static let sourceID = UUID(uuidString: "00000000-0000-0000-0000-0000B1A11C00")!

    /// One 256px tile, rendered once and handed to every path.
    private static let tile: Data? = {
        let size = CGSize(width: 256, height: 256)
        let renderer = UIGraphicsImageRenderer(size: size)
        let image = renderer.image { ctx in
            UIColor(white: 0.07, alpha: 1.0).setFill()
            ctx.fill(CGRect(origin: .zero, size: size))
        }
        return image.pngData()
    }()

    override init(urlTemplate: String?) {
        super.init(urlTemplate: nil)
        canReplaceMapContent = true
        tileSize = CGSize(width: 256, height: 256)
    }

    convenience init() { self.init(urlTemplate: nil) }

    override func loadTile(at path: MKTileOverlayPath,
                           result: @escaping (Data?, Error?) -> Void) {
        result(Self.tile, nil)
    }
}
