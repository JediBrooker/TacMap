import SwiftUI
import UIKit

/// Renders each `TacticalControlMeasure` as a milsymbol-style point symbol:
/// a black outline shape with the abbreviation as a label. Used by the
/// MapKit annotation view and the picker icon.
///
/// Reference geometry follows APP-6 / FM 1-02 conventions as implemented
/// in milsymbol.js (MIT-licensed).
struct TacticalControlMeasureSymbolView: View {
    let measure: TacticalControlMeasure
    var size: CGFloat = 56

    var body: some View {
        Canvas { ctx, canvasSize in
            let w = canvasSize.width
            let h = canvasSize.height
            // Reserve the bottom strip for the label so the shape sits on top
            // and the abbreviation doesn't clash with the geometry.
            let labelH: CGFloat = h * 0.26
            let shapeRect = CGRect(x: 0, y: 0, width: w, height: h - labelH)

            switch measure {
            case .axisOfAssault: drawAxisOfAssault(ctx: ctx, in: shapeRect)
            case .supportByFire: drawSupportByFire(ctx: ctx, in: shapeRect)
            case .attackByFire:  drawAttackByFire(ctx: ctx, in: shapeRect)
            case .formUpPoint:   drawFormUpPoint(ctx: ctx, in: shapeRect)
            case .rvPoint:       drawRendezvous(ctx: ctx, in: shapeRect)
            case .axp:           drawAXP(ctx: ctx, in: shapeRect)
            case .lz:            drawLZ(ctx: ctx, in: shapeRect)
            }

            // Bottom-anchored abbreviation label.
            let label = Text(measure.abbreviation)
                .font(.system(size: labelH * 0.78, weight: .heavy, design: .monospaced))
                .foregroundColor(.black)
            ctx.draw(label,
                     at: CGPoint(x: w / 2, y: h - labelH * 0.5),
                     anchor: .center)
        }
        .frame(width: size, height: size)
        .shadow(color: .black.opacity(0.30), radius: 1.2, x: 0, y: 1)
    }

    // MARK: Shape helpers

    /// Filled chevron pointing right — axis-of-advance arrow.
    private func drawAxisOfAssault(ctx: GraphicsContext, in rect: CGRect) {
        let inset = rect.height * 0.12
        let leftX  = rect.minX + inset
        let rightX = rect.maxX - inset
        let topY   = rect.minY + inset
        let botY   = rect.maxY - inset
        let notch  = rect.width * 0.30
        // Filled arrowhead pointing right.
        var p = Path()
        p.move(to:    CGPoint(x: leftX,        y: topY))
        p.addLine(to: CGPoint(x: rightX,       y: rect.midY))
        p.addLine(to: CGPoint(x: leftX,        y: botY))
        p.addLine(to: CGPoint(x: leftX + notch, y: rect.midY))
        p.closeSubpath()
        ctx.fill(p, with: .color(.black))
    }

    /// Hollow bracket facing right — Support by Fire position. Two short
    /// vertical line segments at left, two short horizontal arms reaching
    /// right (the "fire" lines).
    private func drawSupportByFire(ctx: GraphicsContext, in rect: CGRect) {
        drawFiresBracket(ctx: ctx, in: rect, filled: false)
    }

    /// Same bracket shape as SBF but FILLED — Attack by Fire is the
    /// "executing" counterpart per APP-6.
    private func drawAttackByFire(ctx: GraphicsContext, in rect: CGRect) {
        drawFiresBracket(ctx: ctx, in: rect, filled: true)
    }

    private func drawFiresBracket(ctx: GraphicsContext, in rect: CGRect, filled: Bool) {
        let pad = rect.width * 0.18
        let leftX  = rect.minX + pad
        let rightX = rect.maxX - pad
        let topY   = rect.minY + rect.height * 0.20
        let botY   = rect.maxY - rect.height * 0.10
        let cy     = (topY + botY) / 2
        let mast   = leftX + rect.width * 0.12
        // Vertical mast on the left.
        var p = Path()
        p.move(to:    CGPoint(x: mast, y: topY))
        p.addLine(to: CGPoint(x: mast, y: botY))
        // Two arms reaching out to the right, ending in arrowheads.
        let arm1Y = cy - rect.height * 0.18
        let arm2Y = cy + rect.height * 0.18
        let tipX  = rightX
        p.move(to:    CGPoint(x: mast, y: arm1Y))
        p.addLine(to: CGPoint(x: tipX, y: arm1Y))
        p.move(to:    CGPoint(x: mast, y: arm2Y))
        p.addLine(to: CGPoint(x: tipX, y: arm2Y))
        ctx.stroke(p, with: .color(.black), lineWidth: 2)

        // Arrowheads at each tip.
        let head: CGFloat = rect.width * 0.07
        for y in [arm1Y, arm2Y] {
            var h = Path()
            h.move(to:    CGPoint(x: tipX - head, y: y - head))
            h.addLine(to: CGPoint(x: tipX,        y: y))
            h.addLine(to: CGPoint(x: tipX - head, y: y + head))
            if filled {
                h.closeSubpath()
                ctx.fill(h, with: .color(.black))
            } else {
                ctx.stroke(h, with: .color(.black), lineWidth: 2)
            }
        }
    }

    /// Form Up Point — outlined oval (assembly area).
    private func drawFormUpPoint(ctx: GraphicsContext, in rect: CGRect) {
        let pad = rect.width * 0.12
        let r = rect.insetBy(dx: pad, dy: pad * 1.4)
        let oval = Path(ellipseIn: r)
        ctx.stroke(oval, with: .color(.black), lineWidth: 2)
    }

    /// Rendezvous — outlined inverted triangle (apex at bottom).
    private func drawRendezvous(ctx: GraphicsContext, in rect: CGRect) {
        let pad = rect.width * 0.14
        var p = Path()
        p.move(to:    CGPoint(x: rect.minX + pad,  y: rect.minY + pad))
        p.addLine(to: CGPoint(x: rect.maxX - pad,  y: rect.minY + pad))
        p.addLine(to: CGPoint(x: rect.midX,        y: rect.maxY - pad))
        p.closeSubpath()
        ctx.stroke(p, with: .color(.black), lineWidth: 2)
    }

    /// Ambulance Exchange Point — outlined square with a medical cross
    /// inside (geneva cross).
    private func drawAXP(ctx: GraphicsContext, in rect: CGRect) {
        let pad = rect.width * 0.14
        let sq  = rect.insetBy(dx: pad, dy: pad)
        ctx.stroke(Path(sq), with: .color(.black), lineWidth: 2)
        // Cross inside the square.
        let crossThick = sq.width * 0.18
        let horiz = CGRect(x: sq.minX + sq.width * 0.18,
                           y: sq.midY - crossThick / 2,
                           width: sq.width * 0.64, height: crossThick)
        let vert  = CGRect(x: sq.midX - crossThick / 2,
                           y: sq.minY + sq.height * 0.18,
                           width: crossThick, height: sq.height * 0.64)
        ctx.fill(Path(horiz), with: .color(.black))
        ctx.fill(Path(vert),  with: .color(.black))
    }

    /// Landing Zone — outlined square with an inscribed "H" (rotary-wing
    /// pad). Same convention as on aviation maps.
    private func drawLZ(ctx: GraphicsContext, in rect: CGRect) {
        let pad = rect.width * 0.14
        let sq  = rect.insetBy(dx: pad, dy: pad)
        ctx.stroke(Path(sq), with: .color(.black), lineWidth: 2)
        // Inscribed H.
        let legW: CGFloat = 2
        let inset = sq.width * 0.28
        var p = Path()
        p.move(to:    CGPoint(x: sq.minX + inset, y: sq.minY + sq.height * 0.20))
        p.addLine(to: CGPoint(x: sq.minX + inset, y: sq.maxY - sq.height * 0.20))
        p.move(to:    CGPoint(x: sq.maxX - inset, y: sq.minY + sq.height * 0.20))
        p.addLine(to: CGPoint(x: sq.maxX - inset, y: sq.maxY - sq.height * 0.20))
        p.move(to:    CGPoint(x: sq.minX + inset, y: sq.midY))
        p.addLine(to: CGPoint(x: sq.maxX - inset, y: sq.midY))
        _ = legW
        ctx.stroke(p, with: .color(.black), lineWidth: 2)
    }
}

extension TacticalControlMeasure {
    /// Short label drawn beneath the symbol.
    var abbreviation: String {
        switch self {
        case .axisOfAssault: return "AXIS"
        case .supportByFire: return "SBF"
        case .attackByFire:  return "ABF"
        case .formUpPoint:   return "FUP"
        case .rvPoint:       return "RV"
        case .axp:           return "AXP"
        case .lz:            return "LZ"
        }
    }
}

// MARK: - UIImage renderer for MapKit annotations

@MainActor
enum TacticalControlMeasureRenderer {
    private static var cache: [TacticalControlMeasure: UIImage] = [:]

    static func image(for measure: TacticalControlMeasure, size: CGFloat = 56) -> UIImage? {
        if let cached = cache[measure] { return cached }
        let view = TacticalControlMeasureSymbolView(measure: measure, size: size)
        let renderer = ImageRenderer(content: view)
        renderer.scale = UIScreen.main.scale
        let img = renderer.uiImage
        if let img { cache[measure] = img }
        return img
    }
}
