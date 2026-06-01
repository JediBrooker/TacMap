import MapKit
import UIKit
import Grid
import MGRS

// MARK: - Overlay renderers + annotation views
//
// How the map draws each overlay (drawing strokes/fills, MGRS grid lines) and
// each annotation (waypoints, drawing points/labels, vertex dots + edit
// handles, grid labels), plus the UIImage renderers backing the custom
// annotation views. Extracted verbatim from MapContainerView.swift.
extension MapContainerView.Coordinator {

    func mapView(_ mv: MKMapView, rendererFor overlay: MKOverlay) -> MKOverlayRenderer {
        // Offline MBTiles raster basemap.
        if let tile = overlay as? MKTileOverlay {
            return MKTileOverlayRenderer(tileOverlay: tile)
        }
        // PDF basemap is no longer an MKOverlay — it's a UIImageView
        // subview (see syncPDFOverlay). This delegate handles drawings only.

        let key = ObjectIdentifier(overlay)
        // MGRS grid line — pick stroke width from grid type, colour
        // is a shared neutral dark-grey ink so the grid matches
        // across iOS / Android and stays readable on any basemap.
        if let gridType = mgrsGridTypeByOverlay[key],
           let line = overlay as? MKPolyline {
            let r = MKPolylineRenderer(polyline: line)
            r.strokeColor = MGRSGridRenderer.inkColor
            r.lineWidth = MGRSGridRenderer.lineWidth(for: gridType)
            return r
        }
        let style = styleByOverlay[key] ?? .default
        let inProgress = inProgressOverlayIDs.contains(key)
        // Selection glow: when this overlay's shape is the one whose
        // controls card is open, bump the stroke width so the shape
        // visibly "lifts" off the map.
        let isSelected = shapeIDByOverlay[key]
            .map { $0 == mapVM.selectedDrawingID } ?? false
        let selectionBoost: CGFloat = isSelected ? 3.0 : 0.0

        if let line = overlay as? MKPolyline {
            let r = MKPolylineRenderer(polyline: line)
            r.strokeColor = UIColor(hex: style.strokeColorHex)
            r.lineWidth   = CGFloat(style.strokeWidth) + selectionBoost
            r.lineDashPattern = effectiveDashPattern(for: style,
                                                     inProgress: inProgress)
            return r
        }
        if let poly = overlay as? MKPolygon {
            let r = MKPolygonRenderer(polygon: poly)
            r.strokeColor = UIColor(hex: style.strokeColorHex)
            r.lineWidth   = CGFloat(style.strokeWidth) + selectionBoost
            let fillHex   = style.fillColorHex ?? style.strokeColorHex
            // Slightly brighter fill when selected.
            let fillAlpha = style.fillOpacity * (isSelected ? 1.6 : 1.0)
            r.fillColor   = UIColor(hex: fillHex, alpha: min(fillAlpha, 0.6))
            r.lineDashPattern = effectiveDashPattern(for: style,
                                                     inProgress: inProgress)
            return r
        }
        return MKOverlayRenderer(overlay: overlay)
    }

    /// Resolve the dash pattern for an overlay's renderer.
    /// - In-progress shapes always render dashed (preview convention),
    ///   regardless of the user's solid/dashed toggle.
    /// - Finalized shapes honour `style.dashPattern` — nil means solid.
    private func effectiveDashPattern(for style: DrawingStyle,
                                      inProgress: Bool) -> [NSNumber]? {
        if inProgress {
            return [6, 4]
        }
        return style.dashPattern.map { $0.map { NSNumber(value: $0) } }
    }

    func mapView(_ mv: MKMapView, viewFor annotation: MKAnnotation) -> MKAnnotationView? {
        if let wp = annotation as? WaypointAnnotation {
            // Military kinds get a custom APP-6 image so the frame /
            // function / echelon are drawn properly. Everything else
            // (generic waypoint, tactical control measures) keeps the
            // teardrop MKMarker pin with an SF Symbol glyph.
            if let spec = wp.waypoint.kind.militarySpec {
                let id = "waypoint-military"
                // Military symbols use a plain MKAnnotationView (no
                // halo, no enlarged bounds). The user explicitly
                // didn't want the entire unit graphic to glow —
                // adding a CALayer shadow to the whole image was
                // too much. Future work could halo only the
                // echelon indicator above the frame.
                let view = mv.dequeueReusableAnnotationView(withIdentifier: id)
                    ?? MKAnnotationView(annotation: wp, reuseIdentifier: id)
                view.annotation = wp
                view.image = MilitarySymbolRenderer.image(for: spec)
                view.centerOffset = .zero
                view.canShowCallout = false
                view.isDraggable = true
                return view
            }
            // Tactical control measures are rendered by
            // `TacticalSymbolOverlay` (a SwiftUI overlay above
            // the map view), not by MKMapView's annotation
            // pipeline. They're filtered out before they ever
            // become annotations — see `refresh()`.
            let id = "waypoint"
            let view = mv.dequeueReusableAnnotationView(withIdentifier: id) as? MKMarkerAnnotationView
                ?? MKMarkerAnnotationView(annotation: wp, reuseIdentifier: id)
            view.annotation = wp
            view.glyphImage  = UIImage(systemName: wp.waypoint.kind.sfSymbol)
            view.markerTintColor = UIColor(wp.waypoint.kind.tint)
            view.canShowCallout  = false
            view.isDraggable = true
            return view
        }
        if let dp = annotation as? DrawingPointAnnotation {
            let id = "drawing-point"
            let view = mv.dequeueReusableAnnotationView(withIdentifier: id) as? MKMarkerAnnotationView
                ?? MKMarkerAnnotationView(annotation: dp, reuseIdentifier: id)
            view.annotation = dp
            view.glyphImage = UIImage(systemName: "mappin")
            view.markerTintColor = UIColor(hex: dp.shape.style.strokeColorHex)
            view.canShowCallout = true
            return view
        }
        if let lbl = annotation as? DrawingLabelAnnotation {
            let id = "drawing-label"
            let view = mv.dequeueReusableAnnotationView(withIdentifier: id)
                ?? MKAnnotationView(annotation: lbl, reuseIdentifier: id)
            view.annotation = lbl
            view.canShowCallout = false
            view.isUserInteractionEnabled = false
            view.displayPriority = .required
            // Render the pill as a single UIImage — much more reliable
            // than building subviews, which MapKit sometimes drops on
            // annotation reuse.
            view.image = Self.renderLabelPill(text: lbl.text)
            if let img = view.image {
                view.bounds = CGRect(origin: .zero, size: img.size)
            }
            // Hang the pill below the shape's anchor.
            view.centerOffset = CGPoint(x: 0, y: (view.bounds.height / 2) + 8)
            return view
        }
        if let pv = annotation as? DrawingVertexAnnotation {
            let id = "drawing-vertex"
            let view = mv.dequeueReusableAnnotationView(withIdentifier: id)
                ?? MKAnnotationView(annotation: pv, reuseIdentifier: id)
            view.annotation = pv
            view.canShowCallout = false
            view.isUserInteractionEnabled = false
            view.displayPriority = .required
            view.image = Self.renderVertexDot(color: pv.color)
            if let img = view.image {
                view.bounds = CGRect(origin: .zero, size: img.size)
            }
            view.centerOffset = .zero
            return view
        }
        if let g = annotation as? MGRSGridLabelAnnotation {
            let id = "mgrs-grid-label"
            let view = mv.dequeueReusableAnnotationView(withIdentifier: id)
                ?? MKAnnotationView(annotation: g, reuseIdentifier: id)
            view.annotation = g
            view.canShowCallout = false
            view.isUserInteractionEnabled = false
            // .required so MapKit never declutters grid labels away
            // — a sparse grid with hidden numbers is worse than a
            // dense one where the user can still read the values.
            view.displayPriority = .required
            view.collisionMode = .none
            view.image = Self.renderMGRSLabel(text: g.text,
                                              fontSize: MGRSGridRenderer.labelFontSize(for: g.gridType),
                                              rotated: g.isVertical)
            if let img = view.image {
                view.bounds = CGRect(origin: .zero, size: img.size)
            }
            view.centerOffset = .zero
            return view
        }
        if let h = annotation as? DrawingVertexHandleAnnotation {
            let id = h.isMidpoint ? "drawing-vertex-mid" : "drawing-vertex-handle"
            let view = mv.dequeueReusableAnnotationView(withIdentifier: id)
                ?? MKAnnotationView(annotation: h, reuseIdentifier: id)
            view.annotation = h
            view.canShowCallout = false
            view.isUserInteractionEnabled = true
            // MapKit's built-in drag is a long-press-then-drag that
            // never fires reliably on small custom annotation
            // views, so we drive the drag ourselves via a
            // UIPanGestureRecognizer below. Keep isDraggable off
            // so the system doesn't install its own competing
            // recogniser.
            view.isDraggable = false
            view.displayPriority = .required
            view.image = h.isMidpoint
                ? Self.renderVertexHandle(midpoint: true)
                : Self.renderVertexHandle(midpoint: false)
            if let img = view.image {
                view.bounds = CGRect(origin: .zero, size: img.size)
            }
            view.centerOffset = .zero

            // Strip any recogniser left over from a recycled view
            // so handlers don't stack on reuse.
            view.gestureRecognizers?.forEach { view.removeGestureRecognizer($0) }

            // Pan = drag. Fires on the very first movement so the
            // user can pick up the handle immediately, OR continue
            // a drag that started after a hold (long-press and
            // pan recognise simultaneously below).
            let pan = UIPanGestureRecognizer(target: self, action: #selector(handleVertexPan(_:)))
            pan.delegate = self
            view.addGestureRecognizer(pan)

            // Long-press = delete (real vertices only — midpoint
            // handles don't represent a stored vertex so there's
            // nothing to remove). The handler only acts on .ended
            // and only if NO movement happened during the press,
            // so hold-then-drag is correctly treated as a drag.
            if !h.isMidpoint {
                let lp = UILongPressGestureRecognizer(target: self, action: #selector(handleVertexLongPress(_:)))
                lp.minimumPressDuration = 0.55
                lp.allowableMovement = .greatestFiniteMagnitude
                lp.delegate = self
                view.addGestureRecognizer(lp)
            }
            return view
        }
        return nil
    }

    /// Render the drawing-name pill as a UIImage so it survives MapKit's
    /// annotation-view reuse cycle.
    private static func renderLabelPill(text: String) -> UIImage {
        let font = UIFont.systemFont(ofSize: 11, weight: .semibold)
        let attrs: [NSAttributedString.Key: Any] = [.font: font]
        let textSize = (text as NSString).size(withAttributes: attrs)
        let padH: CGFloat = 6, padV: CGFloat = 3
        let size = CGSize(width: ceil(textSize.width) + padH * 2,
                          height: ceil(textSize.height) + padV * 2)
        let renderer = UIGraphicsImageRenderer(size: size)
        return renderer.image { ctx in
            let cg = ctx.cgContext
            // Pill background.
            let rect = CGRect(origin: .zero, size: size).insetBy(dx: 0.5, dy: 0.5)
            let path = UIBezierPath(roundedRect: rect, cornerRadius: 4).cgPath
            cg.addPath(path)
            cg.setFillColor(UIColor.black.withAlphaComponent(0.62).cgColor)
            cg.fillPath()
            cg.addPath(path)
            cg.setStrokeColor(UIColor.white.withAlphaComponent(0.18).cgColor)
            cg.setLineWidth(0.5)
            cg.strokePath()
            // Text — slight shadow for legibility.
            let textAttrs: [NSAttributedString.Key: Any] = [
                .font: font,
                .foregroundColor: UIColor.white,
                .shadow: {
                    let s = NSShadow()
                    s.shadowColor = UIColor.black.withAlphaComponent(0.8)
                    s.shadowBlurRadius = 1.5
                    return s
                }()
            ]
            (text as NSString).draw(at: CGPoint(x: padH, y: padV), withAttributes: textAttrs)
        }
    }

    /// Filled dot rendered at each tapped vertex during a measure or
    /// draw session, so the user can see exactly where their taps
    /// landed before the polyline closes the gap.
    private static func renderVertexDot(color: UIColor) -> UIImage {
        let size = CGSize(width: 12, height: 12)
        let renderer = UIGraphicsImageRenderer(size: size)
        return renderer.image { ctx in
            let cg = ctx.cgContext
            cg.setFillColor(color.cgColor)
            cg.fillEllipse(in: CGRect(x: 1, y: 1, width: 10, height: 10))
            cg.setStrokeColor(UIColor.white.cgColor)
            cg.setLineWidth(1.5)
            cg.strokeEllipse(in: CGRect(x: 1, y: 1, width: 10, height: 10))
        }
    }

    /// MGRS grid label drawn as bare dark-grey bold text with a
    /// subtle white "drop shadow" halo for legibility on busy
    /// basemaps. No pill background. When `rotated` is true the
    /// text is drawn sideways so it lines up with vertical
    /// (easting) grid lines.
    private static func renderMGRSLabel(text: String, fontSize: CGFloat, rotated: Bool) -> UIImage {
        let font = UIFont.systemFont(ofSize: fontSize, weight: .bold)
        let baseAttrs: [NSAttributedString.Key: Any] = [
            .font: font,
            .foregroundColor: MGRSGridRenderer.labelTextColor
        ]
        let textSize = (text as NSString).size(withAttributes: baseAttrs)
        let pad: CGFloat = 3
        let drawW = textSize.width  + pad * 2
        let drawH = textSize.height + pad * 2
        // Rotated labels need the canvas swapped to fit the
        // rotated text — width becomes the original height + pad,
        // height becomes the original width.
        let canvasSize = rotated
            ? CGSize(width: drawH, height: drawW)
            : CGSize(width: drawW, height: drawH)
        let r = UIGraphicsImageRenderer(size: canvasSize)
        return r.image { ctx in
            let cg = ctx.cgContext
            if rotated {
                // Rotate -90° around centre so easting labels run
                // along the line (text reads bottom→top).
                cg.translateBy(x: canvasSize.width / 2, y: canvasSize.height / 2)
                cg.rotate(by: -.pi / 2)
                cg.translateBy(x: -drawW / 2, y: -drawH / 2)
            }
            // Soft white halo via four offset white passes — keeps
            // dark-grey digits readable on dark satellite tiles
            // without adding a visible pill.
            let haloAttrs: [NSAttributedString.Key: Any] = [
                .font: font,
                .foregroundColor: UIColor(white: 1, alpha: 0.9)
            ]
            let offset: CGFloat = 1
            for dx in [-offset, offset] {
                for dy in [-offset, offset] {
                    (text as NSString).draw(at: CGPoint(x: pad + dx, y: pad + dy),
                                            withAttributes: haloAttrs)
                }
            }
            (text as NSString).draw(at: CGPoint(x: pad, y: pad),
                                    withAttributes: baseAttrs)
        }
    }

    /// Bigger, fatter vertex-edit handle. Solid orange for real
    /// vertices; outlined white "+" for midpoint insertion handles.
    private static func renderVertexHandle(midpoint: Bool) -> UIImage {
        let size = CGSize(width: 26, height: 26)
        let renderer = UIGraphicsImageRenderer(size: size)
        let orange = UIColor(red: 1, green: 0.65, blue: 0.18, alpha: 1)
        return renderer.image { ctx in
            let cg = ctx.cgContext
            let rect = CGRect(x: 3, y: 3, width: 20, height: 20)
            if midpoint {
                // Hollow disc with a "+" so the user knows tapping
                // / dragging inserts a new vertex.
                cg.setFillColor(UIColor.white.withAlphaComponent(0.85).cgColor)
                cg.fillEllipse(in: rect)
                cg.setStrokeColor(orange.cgColor)
                cg.setLineWidth(2)
                cg.strokeEllipse(in: rect)
                cg.setStrokeColor(orange.cgColor)
                cg.setLineWidth(2.5)
                cg.move(to: CGPoint(x: 13, y: 8));  cg.addLine(to: CGPoint(x: 13, y: 18))
                cg.move(to: CGPoint(x:  8, y: 13)); cg.addLine(to: CGPoint(x: 18, y: 13))
                cg.strokePath()
            } else {
                cg.setFillColor(orange.cgColor)
                cg.fillEllipse(in: rect)
                cg.setStrokeColor(UIColor.white.cgColor)
                cg.setLineWidth(2)
                cg.strokeEllipse(in: rect)
            }
        }
    }
}
