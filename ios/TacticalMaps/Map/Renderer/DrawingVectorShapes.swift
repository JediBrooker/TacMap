import CoreLocation

/// Builds the flat list of vector shapes (saved drawings + in-progress sketch +
/// measure line) that the drawings renderer strokes. Shared by the old MKMapView
/// PDF path and the new TileMapView so there's one source of truth.
enum DrawingVectorShapes {
    static func build(drawings: [DrawingShape],
                      drawingsVisible: Bool,
                      selectedDrawingID: UUID?,
                      session: DrawingSessionViewModel,
                      measure: MeasureSession) -> [PDFVectorShape] {
        var vectors: [PDFVectorShape] = []
        if drawingsVisible {
            for shape in drawings
            where shape.kind == .polyline || shape.kind == .polygon || shape.kind == .freedraw {
                vectors.append(PDFVectorShape(
                    sourceID: shape.id,
                    coords: shape.clEffectiveCoordinates,
                    isPolygon: shape.kind == .polygon,
                    style: shape.style,
                    isSelected: shape.id == selectedDrawingID,
                    inProgress: false))
            }
        }
        if session.isDrawing, !session.inProgressCoordinates.isEmpty {
            let kind = session.activeKind ?? .polyline
            vectors.append(PDFVectorShape(
                coords: session.inProgressCoordinates.map {
                    CLLocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude)
                },
                isPolygon: kind == .polygon,
                style: DrawingStyle(),
                isSelected: false,
                inProgress: true))
        }
        if measure.isActive, measure.points.count >= 2 {
            vectors.append(PDFVectorShape(
                coords: measure.points,
                isPolygon: false,
                style: DrawingStyle(strokeColorHex: "#FFA500", fillColorHex: nil,
                                    strokeWidth: 3.0, fillOpacity: 0, dashPattern: [6, 4]),
                isSelected: false,
                inProgress: true))
        }
        return vectors
    }

    /// Replaces one durable vector with an in-memory gesture candidate while
    /// retaining the renderer's current visibility/session/measurement set.
    static func replacingDrawingPreview(_ preview: DrawingShape,
                                        in vectors: [PDFVectorShape]) -> [PDFVectorShape] {
        guard preview.kind == .polyline || preview.kind == .polygon || preview.kind == .freedraw,
              vectors.contains(where: { $0.sourceID == preview.id }) else {
            return vectors
        }
        return vectors.map { vector in
            guard vector.sourceID == preview.id else { return vector }
            return PDFVectorShape(
                sourceID: preview.id,
                coords: preview.clEffectiveCoordinates,
                isPolygon: preview.kind == .polygon,
                style: preview.style,
                isSelected: vector.isSelected,
                inProgress: false
            )
        }
    }
}
