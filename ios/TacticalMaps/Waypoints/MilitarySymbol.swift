import SwiftUI
import UIKit

/// NATO APP-6C symbology, drawn from primitives so we don't need a
/// third-party APP-6 font or SVG library.
///
/// A symbol is the product of three orthogonal dimensions:
///
///   1. **Affiliation** — the frame shape and fill colour (friend,
///      hostile, neutral, unknown).
///   2. **Echelon** — the indicator above the frame (●, ●●, ●●●,
///      I, II, III, X, XX, XXX).
///   3. **Function** — the glyph drawn inside the frame (infantry X,
///      armour oval, recce slash, artillery dot, engineer E, etc.).
///
/// `MilitarySymbolSpec` carries one selection per axis. `MilitarySymbolView`
/// composes the three into a single SwiftUI Canvas draw.

// MARK: - Affiliation

enum SymbolAffiliation: String, Codable, Hashable, CaseIterable {
    case friend     // filled cyan rectangle
    case hostile    // filled red diamond
    case neutral    // filled green square (taller, axis-aligned)
    case unknown    // filled yellow quatrefoil

    var displayName: String {
        switch self {
        case .friend:  return "Friendly"
        case .hostile: return "Hostile"
        case .neutral: return "Neutral"
        case .unknown: return "Unknown"
        }
    }

    /// APP-6C medium-intensity fill colour for the frame.
    var fillColor: Color {
        switch self {
        case .friend:  return Color(red: 0x80/255, green: 0xE0/255, blue: 1.0)        // #80E0FF
        case .hostile: return Color(red: 1.0,       green: 0x80/255, blue: 0x80/255)  // #FF8080
        case .neutral: return Color(red: 0xAA/255, green: 0xFF/255, blue: 0xAA/255)   // #AAFFAA
        case .unknown: return Color(red: 1.0,       green: 1.0,       blue: 0x80/255)  // #FFFF80
        }
    }

    /// Hex used by GeoJSON simplestyle export.
    var fillHex: String {
        switch self {
        case .friend:  return "#80E0FF"
        case .hostile: return "#FF8080"
        case .neutral: return "#AAFFAA"
        case .unknown: return "#FFFF80"
        }
    }
}

// MARK: - Echelon

enum SymbolEchelon: String, Codable, Hashable, CaseIterable {
    case team, squad, section, platoon
    case company, battalion, regiment
    case brigade, division, corps

    var displayName: String {
        switch self {
        case .team:      return "Team / Crew"
        case .squad:     return "Squad"
        case .section:   return "Section"
        case .platoon:   return "Platoon"
        case .company:   return "Company"
        case .battalion: return "Battalion"
        case .regiment:  return "Regiment"
        case .brigade:   return "Brigade"
        case .division:  return "Division"
        case .corps:     return "Corps"
        }
    }

    /// Compact glyph label (used as a fallback / debugging aid).
    var glyph: String {
        switch self {
        case .team:      return "Ø"
        case .squad:     return "●"
        case .section:   return "●●"
        case .platoon:   return "●●●"
        case .company:   return "I"
        case .battalion: return "II"
        case .regiment:  return "III"
        case .brigade:   return "X"
        case .division:  return "XX"
        case .corps:     return "XXX"
        }
    }
}

// MARK: - Function (branch / role)

enum SymbolFunction: String, Codable, Hashable, CaseIterable {
    case infantry       // crossed lines (X within frame's local coords)
    case armour         // horizontal oval
    case mechInfantry   // oval + infantry X (mechanised infantry)
    case recce          // single diagonal slash, top-left to bottom-right
    case artillery      // filled dot in centre
    case engineer       // letter E
    case medical        // black equilateral cross
    case signal         // lightning bolt
    case logistics      // letter S (supply)
    case antiTank       // upward arrowhead
    case hq             // headquarters: vertical line on the left of the frame extending down
    case unspecified    // no function glyph (just the affiliation frame)

    var displayName: String {
        switch self {
        case .infantry:     return "Infantry"
        case .armour:       return "Armour"
        case .mechInfantry: return "Mechanised Infantry"
        case .recce:        return "Reconnaissance"
        case .artillery:    return "Artillery"
        case .engineer:     return "Engineer"
        case .medical:      return "Medical"
        case .signal:       return "Signal"
        case .logistics:    return "Logistics / Supply"
        case .antiTank:     return "Anti-Tank"
        case .hq:           return "Headquarters"
        case .unspecified:  return "— (no branch)"
        }
    }
}

// MARK: - Spec

struct MilitarySymbolSpec: Hashable, Codable {
    var affiliation: SymbolAffiliation
    var echelon:     SymbolEchelon
    var function:    SymbolFunction

    init(affiliation: SymbolAffiliation,
         echelon:     SymbolEchelon,
         function:    SymbolFunction = .infantry) {
        self.affiliation = affiliation
        self.echelon     = echelon
        self.function    = function
    }
}

// MARK: - Rendering

/// SwiftUI view that draws the APP-6C symbol. Use directly in lists / pickers,
/// or hand to `MilitarySymbolRenderer.image(for:)` to bake into a UIImage for
/// a MapKit annotation.
struct MilitarySymbolView: View {
    let spec: MilitarySymbolSpec
    var size: CGFloat = 56

    var body: some View {
        Canvas { ctx, canvasSize in
            let w = canvasSize.width
            let h = canvasSize.height
            let echelonH: CGFloat = h * 0.22
            let gap: CGFloat      = h * 0.06

            let frameTop = echelonH + gap
            let frameBottom = h - 2
            let frameH = frameBottom - frameTop

            // Frame geometry per affiliation
            let frameRect: CGRect
            switch spec.affiliation {
            case .friend, .neutral:
                // Axis-aligned: friend is a wider rectangle, neutral is square-ish
                let frameW: CGFloat
                if spec.affiliation == .friend {
                    frameW = min(w - 4, frameH * 1.5)
                } else {
                    frameW = min(w - 4, frameH * 1.1)
                }
                frameRect = CGRect(x: (w - frameW) / 2, y: frameTop,
                                   width: frameW, height: frameH)
            case .hostile, .unknown:
                // Rotated / lobed shape inscribed in a square
                let side = min(w - 6, frameH)
                frameRect = CGRect(x: (w - side) / 2,
                                   y: frameTop + (frameH - side) / 2,
                                   width: side, height: side)
            }

            // Draw the frame
            switch spec.affiliation {
            case .friend:
                drawAxisAligned(ctx: ctx, in: frameRect)
            case .neutral:
                drawAxisAligned(ctx: ctx, in: frameRect)
            case .hostile:
                drawDiamond(ctx: ctx, in: frameRect)
            case .unknown:
                drawQuatrefoil(ctx: ctx, in: frameRect)
            }

            // Draw the function glyph inside the frame.
            drawFunction(ctx: ctx, function: spec.function,
                         affiliation: spec.affiliation, in: frameRect)

            // Echelon centred above the frame
            let echelonRect = CGRect(x: 0, y: 0, width: w, height: echelonH)
            drawEchelon(ctx: ctx, echelon: spec.echelon, in: echelonRect)
        }
        .frame(width: size, height: size)
        .shadow(color: .black.opacity(0.35), radius: 1.5, x: 0, y: 1)
    }

    // MARK: Frame shapes

    private func drawAxisAligned(ctx: GraphicsContext, in rect: CGRect) {
        let path = Path(rect)
        ctx.fill(path,   with: .color(spec.affiliation.fillColor))
        ctx.stroke(path, with: .color(.black), lineWidth: 1.5)
    }

    private func drawDiamond(ctx: GraphicsContext, in rect: CGRect) {
        let cx = rect.midX, cy = rect.midY
        let r  = rect.width / 2
        var path = Path()
        path.move(to:    CGPoint(x: cx,     y: cy - r))
        path.addLine(to: CGPoint(x: cx + r, y: cy))
        path.addLine(to: CGPoint(x: cx,     y: cy + r))
        path.addLine(to: CGPoint(x: cx - r, y: cy))
        path.closeSubpath()
        ctx.fill(path,   with: .color(spec.affiliation.fillColor))
        ctx.stroke(path, with: .color(.black), lineWidth: 1.5)
    }

    /// Four-lobed cloud shape used by APP-6 for Unknown affiliation.
    private func drawQuatrefoil(ctx: GraphicsContext, in rect: CGRect) {
        let cx = rect.midX, cy = rect.midY
        let r  = rect.width / 2
        let lobeR = r * 0.55
        // Build path from four semicircular lobes pointing N/E/S/W.
        var path = Path()
        // Top lobe
        path.move(to: CGPoint(x: cx - lobeR, y: cy - r + lobeR))
        path.addArc(center: CGPoint(x: cx, y: cy - r + lobeR),
                    radius: lobeR, startAngle: .degrees(180),
                    endAngle: .degrees(0), clockwise: false)
        // Right lobe
        path.addArc(center: CGPoint(x: cx + r - lobeR, y: cy),
                    radius: lobeR, startAngle: .degrees(270),
                    endAngle: .degrees(90), clockwise: false)
        // Bottom lobe
        path.addArc(center: CGPoint(x: cx, y: cy + r - lobeR),
                    radius: lobeR, startAngle: .degrees(0),
                    endAngle: .degrees(180), clockwise: false)
        // Left lobe
        path.addArc(center: CGPoint(x: cx - r + lobeR, y: cy),
                    radius: lobeR, startAngle: .degrees(90),
                    endAngle: .degrees(270), clockwise: false)
        path.closeSubpath()
        ctx.fill(path,   with: .color(spec.affiliation.fillColor))
        ctx.stroke(path, with: .color(.black), lineWidth: 1.5)
    }

    // MARK: Function glyphs

    private func drawFunction(ctx: GraphicsContext, function: SymbolFunction,
                              affiliation: SymbolAffiliation, in frame: CGRect) {
        // Hostile diamond and unknown quatrefoil need an inscribed-square
        // bounding box for any glyph that's not the canonical infantry X.
        let inset: CGFloat
        switch affiliation {
        case .friend, .neutral: inset = 0
        case .hostile:          inset = frame.width * (1 - sqrt(2)/2) / 2
        case .unknown:          inset = frame.width * 0.18
        }
        let glyphRect = frame.insetBy(dx: inset, dy: inset)

        switch function {
        case .unspecified:
            break

        case .infantry:
            drawInfantryX(ctx: ctx, affiliation: affiliation, frame: frame)

        case .armour:
            drawArmourOval(ctx: ctx, in: glyphRect)

        case .mechInfantry:
            drawArmourOval(ctx: ctx, in: glyphRect)
            drawInfantryX(ctx: ctx, affiliation: affiliation, frame: glyphRect)

        case .recce:
            // Single slash from top-left to bottom-right of the glyph box
            var path = Path()
            path.move(to:    CGPoint(x: glyphRect.minX, y: glyphRect.minY))
            path.addLine(to: CGPoint(x: glyphRect.maxX, y: glyphRect.maxY))
            ctx.stroke(path, with: .color(.black), lineWidth: 2)

        case .artillery:
            let r = min(glyphRect.width, glyphRect.height) * 0.18
            let dot = Path(ellipseIn: CGRect(x: glyphRect.midX - r,
                                             y: glyphRect.midY - r,
                                             width: r*2, height: r*2))
            ctx.fill(dot, with: .color(.black))

        case .engineer:
            drawLetter(ctx: ctx, letter: "E", in: glyphRect)

        case .medical:
            drawMedicalCross(ctx: ctx, in: glyphRect)

        case .signal:
            drawLightningBolt(ctx: ctx, in: glyphRect)

        case .logistics:
            drawLetter(ctx: ctx, letter: "S", in: glyphRect)

        case .antiTank:
            drawAntiTankArrow(ctx: ctx, in: glyphRect)

        case .hq:
            // HQ is drawn as a vertical line attached to the bottom-left
            // corner of the frame, dropping down a short distance — it's
            // a frame modifier rather than a glyph inside.
            var path = Path()
            path.move(to:    CGPoint(x: frame.minX, y: frame.maxY))
            path.addLine(to: CGPoint(x: frame.minX, y: frame.maxY + frame.height * 0.45))
            ctx.stroke(path, with: .color(.black), lineWidth: 2)
        }
    }

    private func drawInfantryX(ctx: GraphicsContext, affiliation: SymbolAffiliation, frame: CGRect) {
        var path = Path()
        switch affiliation {
        case .friend, .neutral:
            // Diagonals corner-to-corner.
            path.move(to:    CGPoint(x: frame.minX, y: frame.minY))
            path.addLine(to: CGPoint(x: frame.maxX, y: frame.maxY))
            path.move(to:    CGPoint(x: frame.maxX, y: frame.minY))
            path.addLine(to: CGPoint(x: frame.minX, y: frame.maxY))
        case .hostile:
            // Frame is rotated 45°; X in local coords becomes + in screen coords.
            let cx = frame.midX, cy = frame.midY
            let r  = frame.width / 2
            let inset = r * sqrt(2) / 2
            path.move(to:    CGPoint(x: cx - inset, y: cy))
            path.addLine(to: CGPoint(x: cx + inset, y: cy))
            path.move(to:    CGPoint(x: cx,         y: cy - inset))
            path.addLine(to: CGPoint(x: cx,         y: cy + inset))
        case .unknown:
            // Inside a quatrefoil, draw the X across the inscribed square.
            let inscribe = frame.insetBy(dx: frame.width * 0.18, dy: frame.height * 0.18)
            path.move(to:    CGPoint(x: inscribe.minX, y: inscribe.minY))
            path.addLine(to: CGPoint(x: inscribe.maxX, y: inscribe.maxY))
            path.move(to:    CGPoint(x: inscribe.maxX, y: inscribe.minY))
            path.addLine(to: CGPoint(x: inscribe.minX, y: inscribe.maxY))
        }
        ctx.stroke(path, with: .color(.black), lineWidth: 2)
    }

    private func drawArmourOval(ctx: GraphicsContext, in rect: CGRect) {
        // Horizontal oval: ~60% of frame width, ~55% of frame height.
        let ovalW = rect.width * 0.62
        let ovalH = rect.height * 0.55
        let ovalRect = CGRect(x: rect.midX - ovalW/2,
                              y: rect.midY - ovalH/2,
                              width: ovalW, height: ovalH)
        let path = Path(ellipseIn: ovalRect)
        ctx.fill(path, with: .color(.black.opacity(0.0)))   // no fill
        ctx.stroke(path, with: .color(.black), lineWidth: 2)
    }

    private func drawLetter(ctx: GraphicsContext, letter: String, in rect: CGRect) {
        let fontSize = min(rect.width, rect.height) * 0.55
        let text = Text(letter)
            .font(.system(size: fontSize, weight: .heavy, design: .default))
            .foregroundColor(.black)
        ctx.draw(text, at: CGPoint(x: rect.midX, y: rect.midY), anchor: .center)
    }

    private func drawMedicalCross(ctx: GraphicsContext, in rect: CGRect) {
        let w = rect.width * 0.5, h = rect.height * 0.5
        let thick = min(w, h) * 0.30
        let horiz = CGRect(x: rect.midX - w/2,    y: rect.midY - thick/2,
                            width: w,             height: thick)
        let vert  = CGRect(x: rect.midX - thick/2, y: rect.midY - h/2,
                            width: thick,         height: h)
        ctx.fill(Path(horiz), with: .color(.black))
        ctx.fill(Path(vert),  with: .color(.black))
    }

    private func drawLightningBolt(ctx: GraphicsContext, in rect: CGRect) {
        var p = Path()
        let x0 = rect.minX + rect.width * 0.55
        let x1 = rect.minX + rect.width * 0.35
        let x2 = rect.minX + rect.width * 0.65
        let x3 = rect.minX + rect.width * 0.45
        p.move(to:    CGPoint(x: x0, y: rect.minY))
        p.addLine(to: CGPoint(x: x1, y: rect.midY))
        p.addLine(to: CGPoint(x: x2, y: rect.midY))
        p.addLine(to: CGPoint(x: x3, y: rect.maxY))
        ctx.stroke(p, with: .color(.black), lineWidth: 2)
    }

    private func drawAntiTankArrow(ctx: GraphicsContext, in rect: CGRect) {
        var p = Path()
        let cx = rect.midX
        p.move(to:    CGPoint(x: cx,                  y: rect.minY))
        p.addLine(to: CGPoint(x: rect.maxX - rect.width * 0.2, y: rect.maxY))
        p.addLine(to: CGPoint(x: rect.minX + rect.width * 0.2, y: rect.maxY))
        p.closeSubpath()
        ctx.fill(p,   with: .color(.black))
    }

    // MARK: Echelon

    private func drawEchelon(ctx: GraphicsContext, echelon: SymbolEchelon, in rect: CGRect) {
        let cx = rect.midX
        let cy = rect.midY
        let ink: GraphicsContext.Shading = .color(.black)

        switch echelon {
        case .team:
            // Small diagonal slash through the top-right corner of the frame.
            // Drawn into the echelon rect so it sits above the frame.
            var p = Path()
            let len = rect.height * 0.75
            p.move(to:    CGPoint(x: cx - len/2, y: cy + len/3))
            p.addLine(to: CGPoint(x: cx + len/2, y: cy - len/3))
            ctx.stroke(p, with: ink, lineWidth: 2)
        case .squad:
            drawDots(count: 1, ctx: ctx, cx: cx, cy: cy, radius: 2.2, spacing: 0, ink: ink)
        case .section:
            drawDots(count: 2, ctx: ctx, cx: cx, cy: cy, radius: 2, spacing: 7, ink: ink)
        case .platoon:
            drawDots(count: 3, ctx: ctx, cx: cx, cy: cy, radius: 2, spacing: 6, ink: ink)
        case .company:
            drawBars(count: 1, ctx: ctx, cx: cx, top: rect.minY + 1,
                     height: rect.height - 2, barW: 2.5, spacing: 0, ink: ink)
        case .battalion:
            drawBars(count: 2, ctx: ctx, cx: cx, top: rect.minY + 1,
                     height: rect.height - 2, barW: 2.5, spacing: 5, ink: ink)
        case .regiment:
            drawBars(count: 3, ctx: ctx, cx: cx, top: rect.minY + 1,
                     height: rect.height - 2, barW: 2.5, spacing: 5, ink: ink)
        case .brigade:
            drawXs(count: 1, ctx: ctx, cx: cx, top: rect.minY + 1,
                   size: rect.height - 2, spacing: 0, ink: ink)
        case .division:
            drawXs(count: 2, ctx: ctx, cx: cx, top: rect.minY + 1,
                   size: rect.height - 2, spacing: 9, ink: ink)
        case .corps:
            drawXs(count: 3, ctx: ctx, cx: cx, top: rect.minY + 1,
                   size: rect.height - 2, spacing: 9, ink: ink)
        }
    }

    private func drawDots(count: Int, ctx: GraphicsContext,
                          cx: CGFloat, cy: CGFloat,
                          radius: CGFloat, spacing: CGFloat,
                          ink: GraphicsContext.Shading) {
        let totalWidth = CGFloat(count - 1) * spacing
        for i in 0..<count {
            let x = cx - totalWidth / 2 + CGFloat(i) * spacing
            let dot = Path(ellipseIn: CGRect(x: x - radius, y: cy - radius,
                                             width: radius * 2, height: radius * 2))
            ctx.fill(dot, with: ink)
        }
    }

    private func drawBars(count: Int, ctx: GraphicsContext,
                          cx: CGFloat, top: CGFloat, height: CGFloat,
                          barW: CGFloat, spacing: CGFloat,
                          ink: GraphicsContext.Shading) {
        let totalWidth = CGFloat(count - 1) * spacing
        for i in 0..<count {
            let x = cx - totalWidth / 2 + CGFloat(i) * spacing
            let bar = Path(CGRect(x: x - barW / 2, y: top, width: barW, height: height))
            ctx.fill(bar, with: ink)
        }
    }

    private func drawXs(count: Int, ctx: GraphicsContext,
                        cx: CGFloat, top: CGFloat, size: CGFloat,
                        spacing: CGFloat, ink: GraphicsContext.Shading) {
        let totalWidth = CGFloat(count - 1) * spacing
        for i in 0..<count {
            let centerX = cx - totalWidth / 2 + CGFloat(i) * spacing
            var path = Path()
            path.move(to:    CGPoint(x: centerX - size / 2, y: top))
            path.addLine(to: CGPoint(x: centerX + size / 2, y: top + size))
            path.move(to:    CGPoint(x: centerX + size / 2, y: top))
            path.addLine(to: CGPoint(x: centerX - size / 2, y: top + size))
            ctx.stroke(path, with: ink, lineWidth: 2)
        }
    }
}

// MARK: - UIImage renderer for MapKit

@MainActor
enum MilitarySymbolRenderer {

    private static var cache: [MilitarySymbolSpec: UIImage] = [:]

    /// Returns a cached UIImage of the given symbol, suitable for use as
    /// `MKAnnotationView.image`.
    static func image(for spec: MilitarySymbolSpec, size: CGFloat = 56) -> UIImage? {
        if let cached = cache[spec] { return cached }
        let view = MilitarySymbolView(spec: spec, size: size)
        let renderer = ImageRenderer(content: view)
        renderer.scale = UIScreen.main.scale
        let img = renderer.uiImage
        if let img { cache[spec] = img }
        return img
    }
}
