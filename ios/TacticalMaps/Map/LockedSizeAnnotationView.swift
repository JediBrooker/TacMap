import MapKit
import UIKit

/// An `MKAnnotationView` for tactical-symbol images that resists every
/// known mechanism MapKit uses to scale annotations during zoom.
///
/// Why a subclass and not just `view.image = …` ? On iOS 18 satellite
/// maps, several things can make a fixed-pixel symbol visually scale
/// during pinch-zoom:
///   1. MapKit's internal layout pass resizes the view's `bounds` and
///      the layer's default `contentsGravity = .resize` stretches the
///      image to fill.
///   2. A `transform` with a non-identity scale is applied to the
///      view as part of a camera-distance based render hint.
///   3. The layer's `contents` are re-rasterised at a different
///      backing scale.
///
/// We close all three holes:
///   1. The image is hosted in a child `UIImageView` whose
///      `autoresizingMask` is empty and whose `frame` is reset every
///      `layoutSubviews()` to the locked size, so the parent's bounds
///      can wobble freely without dragging the image with them.
///   2. The `transform` setter strips any scale/rotation, keeping only
///      translation.
///   3. `contentMode = .center` on the inner image view means even if
///      something bypasses our `frame` reset, the image renders at
///      native pixel size from the centre rather than stretched.
final class LockedSizeAnnotationView: MKAnnotationView {

    private let symbolImageView: UIImageView = {
        let iv = UIImageView()
        // .center = draw the image at its natural pixel size, centred
        // in the view's bounds; never stretch.
        iv.contentMode = .center
        // Empty = don't auto-resize when the parent's bounds change.
        iv.autoresizingMask = []
        // Halo bleed (the white outer glow) lives inside the image's
        // own pixel data — no clipping needed at the view level.
        iv.clipsToBounds = false
        return iv
    }()

    /// The locked point size for both this view AND the inner image
    /// view. nil until `setSymbolImage` has run.
    private var lockedSize: CGSize?

    override init(annotation: MKAnnotation?, reuseIdentifier: String?) {
        super.init(annotation: annotation, reuseIdentifier: reuseIdentifier)
        addSubview(symbolImageView)
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        addSubview(symbolImageView)
    }

    /// Set the symbol image and pin both this view and the inner image
    /// view to its point size. Pass nil to release the lock.
    func setSymbolImage(_ img: UIImage?) {
        // We render via the child image view, not via `self.image`,
        // so that MapKit's image-driven layout heuristics can't
        // resize us.
        self.image = nil
        symbolImageView.image = img

        if let size = img?.size {
            lockedSize = size
            super.bounds = CGRect(origin: bounds.origin, size: size)
            symbolImageView.frame = CGRect(origin: .zero, size: size)
        } else {
            lockedSize = nil
            symbolImageView.frame = .zero
        }
    }

    // MARK: Defences

    /// Refuse any attempt to change `bounds.size` once the lock is set.
    /// `bounds.origin` is still honoured (MapKit uses it for layout
    /// arithmetic against the annotation's geographic coordinate).
    override var bounds: CGRect {
        get { super.bounds }
        set {
            guard let lockedSize else {
                super.bounds = newValue
                return
            }
            super.bounds = CGRect(origin: newValue.origin, size: lockedSize)
        }
    }

    /// Strip any scale or rotation that MapKit attempts to apply via
    /// the view's transform — keep only the translation component.
    /// Translation is what MapKit uses for sub-pixel positioning; the
    /// scale component is what was making the symbol grow with zoom.
    override var transform: CGAffineTransform {
        get { super.transform }
        set {
            super.transform = CGAffineTransform(
                translationX: newValue.tx,
                y: newValue.ty
            )
        }
    }

    /// Re-pin the inner image view to the locked size on every layout
    /// pass — catches any code path that mutated bounds via a route
    /// the setter doesn't see (layer-level KVO bypasses).
    override func layoutSubviews() {
        super.layoutSubviews()
        guard let lockedSize else { return }
        symbolImageView.frame = CGRect(origin: .zero, size: lockedSize)
        symbolImageView.center = CGPoint(x: bounds.midX, y: bounds.midY)
        // Same defence at the CALayer level — any 3D transform with
        // scale gets reset to identity.
        let t = layer.transform
        if abs(t.m11 - 1) > 0.001 || abs(t.m22 - 1) > 0.001 || abs(t.m33 - 1) > 0.001 {
            layer.transform = CATransform3DIdentity
        }
    }
}
