import MapKit
import UIKit

/// `MKAnnotationView` for tactical-symbol images that scales with the
/// map's zoom level — i.e. the symbol represents a fixed *geographic*
/// footprint, so it grows visually when the user zooms in and shrinks
/// when they zoom out.
///
/// The white outer halo is rendered as a `CALayer` shadow on the
/// inner image view at runtime, with the shadow radius **counter-
/// scaled** so the on-screen halo width stays roughly constant
/// (a few points) regardless of how much the symbol has been
/// transform-scaled. Without this, the halo would scale 1:1 with
/// the symbol and bloat to 15–20pt at high scales.
///
/// To keep the shadow from being clipped by MapKit's internal
/// annotation container, the view's `bounds` are enlarged by
/// `shadowSlack` points around the image. The image view itself sits
/// in the centre.
final class LockedSizeAnnotationView: MKAnnotationView {

    /// Extra room around the symbol image inside which the shadow can
    /// render before it would be clipped by MapKit. Enough headroom
    /// for the largest counter-scaled shadow we ever produce.
    private static let shadowSlack: CGFloat = 24

    private let symbolImageView: UIImageView = {
        let iv = UIImageView()
        iv.contentMode = .center
        iv.autoresizingMask = []
        iv.clipsToBounds = false
        iv.layer.shadowColor = UIColor.white.cgColor
        iv.layer.shadowOpacity = 1.0
        iv.layer.shadowOffset = .zero
        iv.layer.masksToBounds = false
        return iv
    }()

    /// Native point size of the underlying image (before any zoom
    /// scaling). nil until `setSymbolImage` has run.
    private(set) var nativeImageSize: CGSize?

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

    /// Install the symbol image. Sets the view's bounds to
    /// `imageSize + 2 * shadowSlack` so the CALayer shadow has room
    /// to render inside the annotation view's clip area. The image
    /// view itself is sized to the image and centred.
    func setSymbolImage(_ img: UIImage?) {
        self.image = nil
        symbolImageView.image = img
        if let size = img?.size {
            nativeImageSize = size
            let slack = Self.shadowSlack
            let outerSide = CGSize(width: size.width + 2 * slack,
                                    height: size.height + 2 * slack)
            self.bounds = CGRect(origin: bounds.origin, size: outerSide)
            symbolImageView.frame = CGRect(
                x: slack, y: slack,
                width: size.width, height: size.height
            )
        } else {
            nativeImageSize = nil
            symbolImageView.frame = .zero
        }
        // Reset transform so a recycled view from the dequeue pool
        // doesn't carry a stale zoom scale from a previous use.
        self.transform = .identity
    }

    /// Apply a uniform scale to the view via its `transform`. Called
    /// on every map-camera change so the symbol tracks zoom.
    ///
    /// Also counter-scales the shadow radius so the visible halo width
    /// stays at ~`onScreenHaloPt` regardless of `scale`. Without this
    /// the halo would scale 1:1 with the symbol and look like a thick
    /// fuzzy blob at high zoom.
    func applyZoomScale(_ scale: CGFloat) {
        let safe = max(scale, 0.01)
        self.transform = CGAffineTransform(scaleX: safe, y: safe)

        // Target visible halo: ~3pt wide, with a tiny growth at very
        // large scales so it doesn't disappear into the symbol mass.
        // Layer-space radius = on-screen / scale.
        let onScreenHaloPt: CGFloat = 3.0
        let layerRadius = max(0.5, onScreenHaloPt / safe)
        symbolImageView.layer.shadowRadius = layerRadius
    }
}
