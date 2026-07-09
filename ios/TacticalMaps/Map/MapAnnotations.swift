import Foundation
import MapKit
import UIKit
import Grid
import MGRS

// MARK: - Map annotations
//
// MKAnnotation model objects the coordinator places on the map.
// Pulled out of MapContainerView.swift when we broke that file up.

final class WaypointAnnotation: NSObject, MKAnnotation {
    let waypoint: Waypoint
    /// KVO-compliant coord so MKMapView can mutate it during drag
    /// (isDraggable = true on the view). On drag end coordinator
    /// persists back to store and refresh picks it up.
    @objc dynamic var coordinate: CLLocationCoordinate2D
    init(_ wp: Waypoint) {
        self.waypoint = wp
        self.coordinate = wp.coordinate
    }
    var title: String? { waypoint.name }
    var subtitle: String? { waypoint.subtitle }
}

/// Small filled circle at each tapped vertex while drawing or measuring.
/// Visual feedback for where the tap landed, matches Android's dot.
final class DrawingVertexAnnotation: NSObject, MKAnnotation {
    let color: UIColor
    @objc dynamic var coordinate: CLLocationCoordinate2D = .init()
    init(color: UIColor) { self.color = color }
}

/// Vertex-edit handle on the selected polyline/polygon. Two flavors:
/// solid orange disc on existing verts (drag to move, long-press to
/// delete) and hollow "+" disc at midpoints (drag to insert).
final class DrawingVertexHandleAnnotation: NSObject, MKAnnotation {
    let shapeID: UUID
    /// For real verts: index in shape.coordinates. For midpoints:
    /// index where a new vertex gets inserted (between index-1
    /// and index).
    let vertexIndex: Int
    let isMidpoint: Bool
    @objc dynamic var coordinate: CLLocationCoordinate2D = .init()
    init(shapeID: UUID, vertexIndex: Int, isMidpoint: Bool, coordinate: CLLocationCoordinate2D) {
        self.shapeID = shapeID
        self.vertexIndex = vertexIndex
        self.isMidpoint = isMidpoint
        self.coordinate = coordinate
    }
}

/// Text label on a finished drawing with a non-empty name. Anchored
/// at shape.labelAnchor (centroid for polygons, mid-segment for
/// polylines, point itself otherwise). Not interactive, taps pass thru.
final class DrawingLabelAnnotation: NSObject, MKAnnotation {
    let shapeID: UUID
    let text: String
    @objc dynamic var coordinate: CLLocationCoordinate2D = .init()
    init(shape: DrawingShape, text: String) {
        self.shapeID = shape.id
        self.text = text
    }
}

/// Label next to an MGRS grid line - 100km square ID ("LH") or a
/// 10km/1km easting-northing pair. Not interactive, taps pass thru.
final class MGRSGridLabelAnnotation: NSObject, MKAnnotation {
    let text: String
    @objc dynamic var coordinate: CLLocationCoordinate2D
    let gridType: GridType
    /// True for N-S line (easting label), false for E-W (northing).
    /// Drives on-screen text orientation.
    let isVertical: Bool
    init(text: String, coordinate: CLLocationCoordinate2D, gridType: GridType, isVertical: Bool) {
        self.text = text
        self.coordinate = coordinate
        self.gridType = gridType
        self.isVertical = isVertical
    }
}

final class DrawingPointAnnotation: NSObject, MKAnnotation {
    let shape: DrawingShape
    @objc dynamic var coordinate: CLLocationCoordinate2D = .init()
    init(shape: DrawingShape) { self.shape = shape }
    var title: String? { shape.name ?? shape.kind.displayName }
    var subtitle: String? { shape.notes }
}

/// Shows a remote unit member's position on the map. Small military symbol
/// with callsign label below. Read-only, not selectable or draggable.
final class PresenceAnnotation: NSObject, MKAnnotation {
    let peer: PresencePeer
    @objc dynamic var coordinate: CLLocationCoordinate2D

    init(_ peer: PresencePeer) {
        self.peer = peer
        self.coordinate = CLLocationCoordinate2D(latitude: peer.lat, longitude: peer.lon)
    }

    var title: String? { peer.callsign.isEmpty ? nil : peer.callsign }
}
