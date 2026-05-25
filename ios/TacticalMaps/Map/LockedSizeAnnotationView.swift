import MapKit
import UIKit

/// `MKAnnotationView` for tactical-symbol images that scales with the
/// map's zoom level — i.e. the symbol represents a fixed *geographic*
/// footprint, so it grows visually when the user zooms in and shrinks
/// when they zoom out. (This is the opposite of MKAnnotation's default
/// behaviour, which keeps annotations at fixed pixel size on screen.)
///
/// The native image size baked by `TacticalControlMeasureSymbolView`
/// is the symbol's appearance at `1.0×` user scale and at the
/// reference map zoom (1 metre ≈ 1 point). The owning coordinator
/// applies a per-frame zoom-derived scale via `applyZoomScale(_:)`
/// during `mapViewDidChangeVisibleRegion`, multiplied by the
/// per-waypoint `scale` field.
final class LockedSizeAnnotationView: MKAnnotationView {

    private let symbolImageView: UIImageView = {
        let iv = UIImageView()
        iv.contentMode = .center
        iv.autoresizingMask = []
        iv.clipsToBounds = false
        return iv
    }()

    /// Native point size of the underlying image (before zoom scaling).
    private(set) var nativeSize: CGSize?

    override init(annotation: MKAnnotation?, reuseIdentifier: String?) {
        super.init(annotation: annotation, reuseIdentifier: reuseIdentifier)
        clipsToBounds = false
        layer.masksToBounds = false
        addSubview(symbolImageView)
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        clipsToBounds = false
        layer.masksToBounds = false
        addSubview(symbolImageView)
    }

    /// Install the symbol image. Sets the view's bounds to the image's
    /// native point size so the inner UIImageView lays out at 1.0×;
    /// later `applyZoomScale` will scale the whole view via transform.
    func setSymbolImage(_ img: UIImage?) {
        self.image = nil
        symbolImageView.image = img
        if let size = img?.size {
            nativeSize = size
            self.bounds = CGRect(origin: bounds.origin, size: size)
            symbolImageView.frame = CGRect(origin: .zero, size: size)
        } else {
            nativeSize = nil
            symbolImageView.frame = .zero
        }
        // Reset transform so a recycled view from the dequeue pool
        // doesn't carry a stale zoom scale from a previous use.
        self.transform = .identity
    }

    /// Apply a uniform scale to the view via its `transform`. The
    /// coordinator calls this on every map-camera change so the symbol
    /// tracks the map's current zoom level.
    func applyZoomScale(_ scale: CGFloat) {
        self.transform = CGAffineTransform(scaleX: max(scale, 0.01),
                                            y: max(scale, 0.01))
    }
}
