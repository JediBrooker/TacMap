import Foundation
import MapKit
import UIKit
import Grid
import MGRS

// MARK: - Map annotations
//
// The MKAnnotation model objects the map coordinator places on the map.
// Extracted verbatim from MapContainerView.swift as part of decomposing that
// file; behaviour is unchanged.

final class WaypointAnnotation: NSObject, MKAnnotation {
    let waypoint: Waypoint
    /// Stored coordinate (KVO-compliant) so MKMapView can mutate it
    /// during a drag (`isDraggable = true` on the annotation view).
    /// On drag end the coordinator persists the new value back to
    /// the store and the regular refresh path picks it up.
    @objc dynamic var coordinate: CLLocationCoordinate2D
    init(_ wp: Waypoint) {
        self.waypoint = wp
        self.coordinate = wp.coordinate
    }
    var title: String? { waypoint.name }
    var subtitle: String? { waypoint.subtitle }
}

/// Small filled-circle annotation rendered at each tapped vertex while
/// the user is drawing or measuring. Provides instant visual feedback for
/// the tap landing point and matches the Android-side dot affordance.
final class DrawingVertexAnnotation: NSObject, MKAnnotation {
    let color: UIColor
    @objc dynamic var coordinate: CLLocationCoordinate2D = .init()
    init(color: UIColor) { self.color = color }
}

/// Interactive vertex-edit handle rendered alongside the currently
/// selected polyline / polygon. Two flavours: a solid orange disc at
/// each existing vertex (drag to move, long-press to delete), and a
/// hollow "+" disc at each segment midpoint (drag to insert a new
/// vertex at that position).
final class DrawingVertexHandleAnnotation: NSObject, MKAnnotation {
    let shapeID: UUID
    /// For real vertices: the index in `shape.coordinates`. For
    /// midpoint handles: the index where a NEW vertex would be
    /// inserted (i.e. between coords[index-1] and coords[index]).
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

/// Floating text label rendered alongside a finished drawing whose
/// `shape.name` is non-empty. Anchored at `shape.labelAnchor` so it sits
/// near the centroid (polygons), mid-segment (polylines), or the point
/// itself. Non-interactive — taps pass through to the underlying shape.
final class DrawingLabelAnnotation: NSObject, MKAnnotation {
    let shapeID: UUID
    let text: String
    @objc dynamic var coordinate: CLLocationCoordinate2D = .init()
    init(shape: DrawingShape, text: String) {
        self.shapeID = shape.id
        self.text = text
    }
}

/// Static label rendered alongside an MGRS grid line — typically the
/// 100km square ID ("LH") or a 10km / 1km easting-northing pair. Never
/// interactive: taps pass straight through to the underlying overlay
/// or basemap.
final class MGRSGridLabelAnnotation: NSObject, MKAnnotation {
    let text: String
    @objc dynamic var coordinate: CLLocationCoordinate2D
    let gridType: GridType
    /// True when the label belongs to a north-south line (easting
    /// label); false when it belongs to an east-west line (northing
    /// label). Drives the on-screen orientation of the rendered text.
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
