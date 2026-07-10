import MapKit
import UIKit

/// A full-coverage opaque overlay that stands in for Apple's basemap while
/// online basemaps are gated off.
///
/// MapKit has no equivalent of Android's `MapType.NONE`, so the only way to
/// stop the base map is to cover it with an `MKTileOverlay` that declares
/// `canReplaceMapContent`, which tells MapKit it doesn't need to draw (or
/// fetch) anything underneath. The offline MBTiles path in this app already
/// relies on that, so it's a proven mechanism here rather than a guess.
///
/// Tiles are synthesised in memory. `loadTile` never touches the network, and
/// there is no `urlTemplate`, so there is nothing for this class to request
/// even if MapKit asked it to.
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
