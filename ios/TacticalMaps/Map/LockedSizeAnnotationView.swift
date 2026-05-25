import MapKit
import UIKit

/// An `MKAnnotationView` whose bounds size is permanently pinned to the
/// dimensions of the image it was given. `bounds.origin` is still
/// honoured (MapKit uses it for positioning), but any attempt — by
/// MapKit's internal layout passes or by view reuse — to change the
/// `bounds.size` is ignored.
///
/// Background: the default `MKAnnotationView` derives its frame from
/// the image's point size, but on iOS 18+ satellite maps that frame
/// can drift during pinch-zoom / camera changes. The result is that
/// fixed-pixel symbols visually scale with zoom. Locking bounds here
/// — rather than re-applying them in `mapViewDidChangeVisibleRegion`
/// — costs nothing per render and survives reuse from the dequeue
/// pool.
final class LockedSizeAnnotationView: MKAnnotationView {

    /// The size we've committed to. nil until `setSymbolImage` has run.
    private var lockedSize: CGSize?

    /// Set the image and pin the view's bounds size to it. Pass nil to
    /// release the lock (useful when the view is being recycled for a
    /// different annotation type).
    func setSymbolImage(_ img: UIImage?) {
        self.image = img
        if let size = img?.size {
            lockedSize = size
            super.bounds = CGRect(origin: bounds.origin, size: size)
            // Layer-level safety net: even if anything mutates bounds
            // through a path that bypasses our setter, contents are
            // drawn at native pixel size from the centre, not stretched.
            layer.contentsGravity = .center
        } else {
            lockedSize = nil
        }
    }

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
}
