import UIKit
import CoreLocation

/// Renders sync presence peers (military symbol + callsign) as subviews pinned
/// to their coordinates, projected through a `project` closure. Replaces the
/// MKAnnotation presence path so peers show on the MapKit-free renderer.
final class PresenceOverlayView: UIView {

    /// Projects a WGS84 coordinate to a point in THIS view. Set by the host.
    var project: ((CLLocationCoordinate2D) -> CGPoint)?

    private struct Marker { let container: UIView; var coord: CLLocationCoordinate2D }
    private var markers: [String: Marker] = [:]

    init() {
        super.init(frame: .zero)
        backgroundColor = .clear
        isOpaque = false
        isUserInteractionEnabled = false // taps fall through to the map
        autoresizingMask = [.flexibleWidth, .flexibleHeight]
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { nil }

    func update(peers: [String: PresencePeer]) {
        // Drop peers that left.
        for (id, m) in markers where peers[id] == nil {
            m.container.removeFromSuperview()
            markers[id] = nil
        }
        // Add / move current peers.
        for (id, peer) in peers {
            let coord = CLLocationCoordinate2D(latitude: peer.lat, longitude: peer.lon)
            if var existing = markers[id] {
                existing.coord = coord
                markers[id] = existing
            } else {
                let container = makeMarker(for: peer)
                addSubview(container)
                markers[id] = Marker(container: container, coord: coord)
            }
        }
        reproject()
    }

    func reproject() {
        guard let project else { return }
        for (_, m) in markers { m.container.center = project(m.coord) }
    }

    func clear() {
        markers.values.forEach { $0.container.removeFromSuperview() }
        markers.removeAll()
    }

    /// One peer: 40pt symbol centred on the coord, callsign pill just below.
    private func makeMarker(for peer: PresencePeer) -> UIView {
        // rawValues are lowercase; lowercase the incoming token so an Android
        // peer (uppercase enum names on the wire) maps correctly, and fall back
        // to UNKNOWN - never .friend - for a missing/garbled affiliation.
        let spec = MilitarySymbolSpec(
            affiliation: SymbolAffiliation(rawValue: peer.affiliation.lowercased()) ?? .unknown,
            echelon: SymbolEchelon(rawValue: peer.echelon.lowercased()) ?? .team,
            function: SymbolFunction(rawValue: peer.function.lowercased()) ?? .infantry,
            isHeadquarters: peer.isHQ)

        let container = UIView(frame: CGRect(x: 0, y: 0, width: 40, height: 40))
        container.isUserInteractionEnabled = false
        container.clipsToBounds = false

        let symbol = UIImageView(image: MilitarySymbolRenderer.image(for: spec, size: 40))
        symbol.frame = CGRect(x: 0, y: 0, width: 40, height: 40)
        container.addSubview(symbol)

        let callsign = peer.callsign
        if !callsign.isEmpty {
            let label = UILabel()
            label.text = callsign
            label.font = .systemFont(ofSize: 10, weight: .bold)
            label.textColor = .white
            label.textAlignment = .center
            label.backgroundColor = UIColor.black.withAlphaComponent(0.6)
            label.layer.cornerRadius = 3
            label.clipsToBounds = true
            label.sizeToFit()
            let w = label.frame.width + 8
            label.frame = CGRect(x: (40 - w) / 2, y: 40, width: w, height: label.frame.height + 2)
            container.addSubview(label)
        }
        return container
    }
}
