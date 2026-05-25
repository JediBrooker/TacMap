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

    /// Room around the image inside the annotation view's bounds so
    /// the CALayer halo isn't clipped by MapKit's internal annotation
    /// container (which DOES clip child shadows regardless of how
    /// many `masksToBounds = false` calls we walk up the parent
    /// chain). Hit-testing is constrained back to just the image
    /// area via `point(inside:with:)` so the slack doesn't steal
    /// taps from neighbouring annotations.
    private static let shadowSlack: CGFloat = 40

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
        iv.isUserInteractionEnabled = false
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

        // Halo target ramp: thicker when symbol is small on screen,
        // none when symbol is big.
        let baseHalo:  CGFloat = 10.0
        let slope:     CGFloat = 1.8
        let formulaHalo = max(0, baseHalo - slope * safe)

        // Also cap the halo width to half the symbol's on-screen size
        // so we never end up with a halo bigger than the symbol it's
        // outlining (which at very small scales produced a wide, very
        // diffuse Gaussian that the eye reads as "no halo at all").
        let symbolOnScreenPt = 64.0 * safe   // 64 = TacticalControlMeasureRenderer.baseSize
        let onScreenHaloPt = min(formulaHalo, symbolOnScreenPt / 2)

        if onScreenHaloPt <= 0.01 {
            haloLayer1.layer.shadowOpacity = 0
            haloLayer2.layer.shadowOpacity = 0
            haloLayer3.layer.shadowOpacity = 0
            return
        }

        // Layer-space radius = visible / scale because the layer is
        // about to be transform-scaled by `scale`. Cap at the slack
        // value so the Gaussian doesn't bleed past what the
        // annotation view's bounds allow — beyond that it just gets
        // clipped anyway, AND a too-wide Gaussian on a small symbol
        // is too diffuse to read.
        let layerRadius = min(onScreenHaloPt / safe, CGFloat(Self.shadowSlack) - 2)
        let opacity: Float = Float(min(1.0, layerRadius / 1.0))
        let effectiveRadius = max(0.7, layerRadius)
        for iv in [haloLayer1, haloLayer2, haloLayer3] {
            iv.layer.shadowRadius = effectiveRadius
            iv.layer.shadowOpacity = opacity
        }
    }

    // MARK: Hit testing

    /// Limit the tappable area to just the symbol image — the 40pt
    /// shadow slack around the image is visual room for the halo,
    /// NOT a tap target. Without this, MapKit's annotation hit-test
    /// uses the inflated bounds and a large symbol's slack swallows
    /// taps meant for nearby smaller annotations.
    ///
    /// Both `hitTest` and `point(inside:)` are overridden because
    /// MapKit's annotation tap recognizer may use either path.
    override func point(inside point: CGPoint, with event: UIEvent?) -> Bool {
        let inside = symbolImageView.frame.contains(point)
        NSLog("[HitDbg] point(inside:) called point=\(point) imgFrame=\(symbolImageView.frame) → \(inside)")
        return inside
    }

    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        let inside = symbolImageView.frame.contains(point)
        NSLog("[HitDbg] hitTest called point=\(point) imgFrame=\(symbolImageView.frame) → \(inside)")
        guard inside else { return nil }
        return self
    }
}
