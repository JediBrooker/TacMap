import UIKit
import CoreLocation

/// The blue "you are here" dot on the MapKit-free renderer. MKMapView drew this
/// for free via `showsUserLocation`; the custom view has to render it. A white-
/// ringed blue dot at the GPS fix, with a translucent accuracy circle sized to
/// the reported horizontal accuracy. Gated by the User Location layers toggle.
final class UserLocationOverlayView: UIView {

    var project: ((CLLocationCoordinate2D) -> CGPoint)?

    private var coordinate: CLLocationCoordinate2D?
    private var accuracyMetres: Double = 0
    private var metresPerPoint: Double = 1
    private var isShown = false

    private let accuracyRing = CAShapeLayer()
    private let dot = UIView()

    init() {
        super.init(frame: .zero)
        backgroundColor = .clear
        isOpaque = false
        isUserInteractionEnabled = false
        autoresizingMask = [.flexibleWidth, .flexibleHeight]

        accuracyRing.fillColor = UIColor.systemBlue.withAlphaComponent(0.15).cgColor
        accuracyRing.strokeColor = UIColor.systemBlue.withAlphaComponent(0.35).cgColor
        accuracyRing.lineWidth = 1
        layer.addSublayer(accuracyRing)

        dot.bounds = CGRect(x: 0, y: 0, width: 18, height: 18)
        dot.backgroundColor = .systemBlue
        dot.layer.cornerRadius = 9
        dot.layer.borderColor = UIColor.white.cgColor
        dot.layer.borderWidth = 3
        dot.layer.shadowColor = UIColor.black.cgColor
        dot.layer.shadowOpacity = 0.4
        dot.layer.shadowRadius = 2
        dot.layer.shadowOffset = CGSize(width: 0, height: 1)
        addSubview(dot)

        dot.isHidden = true
        accuracyRing.isHidden = true
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { nil }

    func update(coordinate: CLLocationCoordinate2D?, accuracyMetres: Double, visible: Bool) {
        self.coordinate = coordinate
        self.accuracyMetres = max(accuracyMetres, 0)
        self.isShown = visible && coordinate != nil
        layoutMarker()
    }

    /// Called on camera change so the dot tracks the map and the accuracy ring
    /// stays sized to real ground metres.
    func reproject(metresPerPoint: Double) {
        self.metresPerPoint = max(metresPerPoint, 0.0001)
        layoutMarker()
    }

    private func layoutMarker() {
        dot.isHidden = !isShown
        accuracyRing.isHidden = !isShown
        guard isShown, let coordinate, let project else { return }
        let centre = project(coordinate)
        dot.center = centre

        // Accuracy circle: only draw it when it's meaningfully bigger than the
        // dot, else it's just noise.
        let radiusPt = accuracyMetres / metresPerPoint
        if radiusPt > 14 {
            let rect = CGRect(x: centre.x - radiusPt, y: centre.y - radiusPt,
                              width: radiusPt * 2, height: radiusPt * 2)
            accuracyRing.path = UIBezierPath(ovalIn: rect).cgPath
            accuracyRing.isHidden = false
        } else {
            accuracyRing.isHidden = true
        }
    }
}
