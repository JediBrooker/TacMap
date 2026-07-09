import MapKit
import UIKit

/// MKAnnotationView for tactical symbols that scales with map zoom
/// via applyZoomScale(_:), called from MapContainerView.Coordinator
/// on every camera change.
///
/// White outline is baked in by TacticalControlMeasureSymbolView,
/// this view just hosts the image + applies transform. Bounds match
/// image exactly so MapKit hit-test target = visible symbol, no
/// enlarged frames clobbering taps on neighbouring annotations.
final class LockedSizeAnnotationView: MKAnnotationView {

    /// Native point size before zoom scaling. nil untill setSymbolImage runs.
    private(set) var nativeImageSize: CGSize?

    /// Set the symbol image, pins bounds to its point size.
    func setSymbolImage(_ img: UIImage?) {
        self.image = img
        if let size = img?.size {
            nativeImageSize = size
            self.bounds = CGRect(origin: bounds.origin, size: size)
        } else {
            nativeImageSize = nil
        }
        // reset transform b/c recycled views from dequeue pool
        // can carry a stale zoom scale
        self.transform = .identity
    }

    /// Uniform scale via transform, fires on every camera change
    /// so symbol tracks zoom.
    func applyZoomScale(_ scale: CGFloat) {
        self.transform = CGAffineTransform(scaleX: max(scale, 0.01),
                                            y: max(scale, 0.01))
    }
}
