import UIKit
import CoreLocation

/// Numbered fiduciary markers + the pending tap crosshair while calibrating.
/// Points live in PDF page space; they go page -> georef.toWGS84 -> camera,
/// the same path that draws the tiles, so a marker sits on its page point at
/// any zoom and heading. Stopgap until the WP4 calibration UX lands.
final class PDFPageMarkersView: UIView {
    var project: ((CLLocationCoordinate2D) -> CGPoint)?

    private var georef: PdfGeoreference?
    private var fiduciaries: [Fiduciary] = []
    private var pending: CGPoint?
    private var markers: [UUID: UIView] = [:]
    private var pendingMarker: UIView?

    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false
        backgroundColor = .clear
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) unused") }

    func update(georef: PdfGeoreference?, fiduciaries: [Fiduciary], pendingPagePoint: CGPoint?) {
        self.georef = georef
        self.fiduciaries = georef == nil ? [] : fiduciaries
        self.pending = georef == nil ? nil : pendingPagePoint
        let live = Set(self.fiduciaries.map(\.id))
        for (id, v) in markers where !live.contains(id) {
            v.removeFromSuperview()
            markers[id] = nil
        }
        for (i, f) in self.fiduciaries.enumerated() {
            if let m = markers[f.id] {
                (m.subviews.first as? UILabel)?.text = "\(i + 1)"
            } else {
                let m = Self.makeMarker(number: i + 1)
                addSubview(m)
                markers[f.id] = m
            }
        }
        if self.pending == nil {
            pendingMarker?.removeFromSuperview()
            pendingMarker = nil
        } else if pendingMarker == nil {
            let m = Self.makePendingMarker()
            addSubview(m)
            pendingMarker = m
        }
        reproject()
    }

    func reproject() {
        guard let georef, let project else { return }
        for f in fiduciaries {
            guard let m = markers[f.id] else { continue }
            place(m, georef.toWGS84(x: f.pdfX, y: f.pdfY), project)
        }
        if let p = pending, let m = pendingMarker {
            place(m, georef.toWGS84(p), project)
        }
    }

    private func place(_ v: UIView, _ c: CLLocationCoordinate2D?, _ project: (CLLocationCoordinate2D) -> CGPoint) {
        guard let c else { v.isHidden = true; return }
        let p = project(c)
        guard p.x.isFinite, p.y.isFinite else { v.isHidden = true; return }
        v.isHidden = false
        v.center = p
    }

    var markerCount: Int { markers.count }

    private static func makeMarker(number: Int) -> UIView {
        let size: CGFloat = 32
        let v = UIView(frame: CGRect(x: 0, y: 0, width: size, height: size))
        v.backgroundColor = .systemOrange
        v.layer.cornerRadius = size / 2
        v.layer.borderWidth = 2
        v.layer.borderColor = UIColor.white.cgColor
        v.layer.shadowColor = UIColor.black.cgColor
        v.layer.shadowOpacity = 0.4
        v.layer.shadowRadius = 2
        v.layer.shadowOffset = .zero
        let label = UILabel(frame: v.bounds)
        label.text = "\(number)"
        label.textAlignment = .center
        label.textColor = .black
        label.font = .systemFont(ofSize: 15, weight: .bold)
        v.addSubview(label)
        return v
    }

    private static func makePendingMarker() -> UIView {
        let size: CGFloat = 28
        let v = UIView(frame: CGRect(x: 0, y: 0, width: size, height: size))
        v.backgroundColor = .clear
        let cross = CAShapeLayer()
        let path = UIBezierPath()
        path.move(to: CGPoint(x: size / 2, y: 0)); path.addLine(to: CGPoint(x: size / 2, y: size))
        path.move(to: CGPoint(x: 0, y: size / 2)); path.addLine(to: CGPoint(x: size, y: size / 2))
        cross.path = path.cgPath
        cross.strokeColor = UIColor.systemRed.cgColor
        cross.lineWidth = 2
        v.layer.addSublayer(cross)
        let ring = CAShapeLayer()
        ring.path = UIBezierPath(ovalIn: CGRect(x: 4, y: 4, width: size - 8, height: size - 8)).cgPath
        ring.strokeColor = UIColor.systemRed.cgColor
        ring.fillColor = UIColor.white.withAlphaComponent(0.3).cgColor
        ring.lineWidth = 2
        v.layer.addSublayer(ring)
        return v
    }
}
