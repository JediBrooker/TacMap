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

    /// No shadow slack — the annotation view's bounds match the image
    /// exactly so MapKit's hit-test uses the actual symbol area as
    /// the tap target. The halo renders outside bounds via
    /// `masksToBounds = false` on every layer in the chain.
    private static let shadowSlack: CGFloat = 0

    /// We render the symbol image *four* times, stacked. The bottom
    /// three carry the white halo (a single CALayer shadow is too
    /// soft to read on light satellite imagery — stacking accumulates
    /// alpha into a solid white outline). The top one has no shadow
    /// and is the actual visible black symbol.
    private let haloLayer1: UIImageView = makeShadowImageView()
    private let haloLayer2: UIImageView = makeShadowImageView()
    private let haloLayer3: UIImageView = makeShadowImageView()
    private let symbolImageView: UIImageView = {
        let iv = UIImageView()
        iv.contentMode = .center
        iv.autoresizingMask = []
        iv.clipsToBounds = false
        return iv
    }()

    private static func makeShadowImageView() -> UIImageView {
        let iv = UIImageView()
        iv.contentMode = .center
        iv.autoresizingMask = []
        iv.clipsToBounds = false
        iv.layer.shadowColor = UIColor.white.cgColor
        iv.layer.shadowOpacity = 1.0
        iv.layer.shadowOffset = .zero
        iv.layer.masksToBounds = false
        return iv
    }

    /// Native point size of the underlying image (before any zoom
    /// scaling). nil until `setSymbolImage` has run.
    private(set) var nativeImageSize: CGSize?

    override init(annotation: MKAnnotation?, reuseIdentifier: String?) {
        super.init(annotation: annotation, reuseIdentifier: reuseIdentifier)
        clipsToBounds = false
        layer.masksToBounds = false
        // Halo layers underneath the visible symbol.
        addSubview(haloLayer1)
        addSubview(haloLayer2)
        addSubview(haloLayer3)
        addSubview(symbolImageView)
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        clipsToBounds = false
        layer.masksToBounds = false
        addSubview(haloLayer1)
        addSubview(haloLayer2)
        addSubview(haloLayer3)
        addSubview(symbolImageView)
    }

    /// Install the symbol image. Sets the view's bounds to
    /// `imageSize + 2 * shadowSlack` so the CALayer shadow has room
    /// to render inside the annotation view's clip area. The image
    /// view itself is sized to the image and centred.
    func setSymbolImage(_ img: UIImage?) {
        self.image = nil
        symbolImageView.image = img
        haloLayer1.image = img
        haloLayer2.image = img
        haloLayer3.image = img
        if let size = img?.size {
            nativeImageSize = size
            let slack = Self.shadowSlack
            let outerSide = CGSize(width: size.width + 2 * slack,
                                    height: size.height + 2 * slack)
            self.bounds = CGRect(origin: bounds.origin, size: outerSide)
            let centerFrame = CGRect(
                x: slack, y: slack,
                width: size.width, height: size.height
            )
            symbolImageView.frame = centerFrame
            haloLayer1.frame = centerFrame
            haloLayer2.frame = centerFrame
            haloLayer3.frame = centerFrame
        } else {
            nativeImageSize = nil
            symbolImageView.frame = .zero
            haloLayer1.frame = .zero
            haloLayer2.frame = .zero
            haloLayer3.frame = .zero
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
        // Halo shrinks as the symbol grows. Visible on-screen halo
        // width = baseHalo / sqrt(scale) with a 1pt floor — so the
        // halo never quite disappears, but a 10× symbol gets a ~2pt
        // halo while a 1× symbol gets ~6pt. (Earlier we kept halo
        // CONSTANT on screen, which made large symbols look like
        // they had thick fuzzy outlines.)
        //
        // Layer-space radius = visible / scale, because the layer is
        // about to be transform-scaled by `scale`.
        let baseHalo: CGFloat = 6.0
        let onScreenHaloPt = max(1.5, baseHalo / sqrt(safe))
        let layerRadius = max(1.0, onScreenHaloPt / safe)
        haloLayer1.layer.shadowRadius = layerRadius
        haloLayer2.layer.shadowRadius = layerRadius
        haloLayer3.layer.shadowRadius = layerRadius
    }

    // MARK: Render outside bounds

    /// Whenever MapKit attaches us to its annotation container, walk
    /// up the parent chain and set `masksToBounds = false` so our
    /// halo (which extends past our own bounds) doesn't get clipped.
    override func didMoveToSuperview() {
        super.didMoveToSuperview()
        var v: UIView? = superview
        var hops = 0
        while let s = v, hops < 4 {
            s.clipsToBounds = false
            s.layer.masksToBounds = false
            v = s.superview
            hops += 1
        }
    }
}
