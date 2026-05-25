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
    case team, section, platoon
    case company, battalion, regiment
    case brigade, division, corps

    var displayName: String {
        switch self {
        case .team:      return "Team / Crew"
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
        case .section:   return "●"
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
    case infantry          // crossed diagonal lines (saltire — "crossed bandoliers")
    case armour            // horizontal capsule (tank-tread cross-section)
    case mechInfantry      // capsule + infantry X (mechanised infantry)
    case recce             // single diagonal line, lower-left to upper-right ("sabre belt")
    case cavalry           // recce slash + armour capsule (mounted reconnaissance)
    case artillery         // filled circle in centre ("cannonball")
    case airDefence        // dome arc (concave-down, "protective dome")
    case antiTank          // outlined inverted-V chevron ("concentrated piercing action")
    case mortar            // vertical arrow pointing up with a baseline
    case engineer          // letter E (stylised bridge)
    case medical           // black equilateral cross (red-cross style)
    case signal            // lightning bolt
    case logistics         // Supply — flat horizontal line (side view of a road)
    case aviation          // two facing crescents (helicopter rotor blades)
    case maintenance       // wrench-style horizontal line with a notch
    case hq                // headquarters: vertical line extending down from bottom-left of frame
    case unspecified       // no function glyph (just the affiliation frame)

    var displayName: String {
        switch self {
        case .infantry:       return "Infantry"
        case .armour:         return "Armour"
        case .mechInfantry:   return "Mechanised Infantry"
        case .recce:          return "Reconnaissance"
        case .cavalry:        return "Cavalry"
        case .artillery:      return "Artillery"
        case .airDefence:     return "Air Defence"
        case .antiTank:       return "Anti-Tank"
        case .mortar:         return "Mortar"
        case .engineer:       return "Engineer"
        case .medical:        return "Medical"
        case .signal:         return "Signal"
        case .logistics:      return "Supply"
        case .aviation:       return "Aviation (Rotary)"
        case .maintenance:    return "Maintenance"
        case .hq:             return "Headquarters"
        case .unspecified:    return "— (no branch)"
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
            drawArmourCapsule(ctx: ctx, in: glyphRect)

        case .mechInfantry:
            drawArmourCapsule(ctx: ctx, in: glyphRect)
            drawInfantryX(ctx: ctx, affiliation: affiliation, frame: glyphRect)

        case .recce:
            // Single slash from lower-left to upper-right ("sabre belt").
            drawRecceSlash(ctx: ctx, in: glyphRect)

        case .cavalry:
            // Mounted reconnaissance = armour capsule + recce slash.
            drawArmourCapsule(ctx: ctx, in: glyphRect)
            drawRecceSlash(ctx: ctx, in: glyphRect)

        case .artillery:
            let r = min(glyphRect.width, glyphRect.height) * 0.18
            let dot = Path(ellipseIn: CGRect(x: glyphRect.midX - r,
                                             y: glyphRect.midY - r,
                                             width: r*2, height: r*2))
            ctx.fill(dot, with: .color(.black))

        case .airDefence:
            drawAirDefenceDome(ctx: ctx, in: glyphRect)

        case .antiTank:
            drawAntiTankVee(ctx: ctx, in: glyphRect)

        case .mortar:
            drawMortarArrow(ctx: ctx, in: glyphRect)

        case .engineer:
            drawEngineerBridge(ctx: ctx, in: glyphRect)

        case .medical:
            drawMedicalCross(ctx: ctx, in: glyphRect)

        case .signal:
            drawSignalLightning(ctx: ctx, in: glyphRect)

        case .logistics:
            drawSupplyLine(ctx: ctx, in: glyphRect)

        case .aviation:
            drawRotorBlades(ctx: ctx, in: glyphRect)

        case .maintenance:
            drawMaintenanceDumbbell(ctx: ctx, in: glyphRect)

        case .hq:
            // HQ "flagpole" — a vertical line running from the TOP-LEFT
            // corner straight down past the BOTTOM-LEFT corner, ending
            // some way below the frame. It's a frame modifier (drawn
            // outside the glyph box), per APP-6.
            var pole = Path()
            pole.move(to:    CGPoint(x: frame.minX, y: frame.minY))
            pole.addLine(to: CGPoint(x: frame.minX, y: frame.maxY + frame.height * 0.60))
            ctx.stroke(pole, with: .color(.black), lineWidth: 2)
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
            // Diagonal X drawn in screen coords, with endpoints at the four
            // mid-edge points of the diamond so the X stays inside the frame.
            let cx = frame.midX, cy = frame.midY
            let h  = frame.width / 4   // half of inscribed-square side
            path.move(to:    CGPoint(x: cx - h, y: cy - h))
            path.addLine(to: CGPoint(x: cx + h, y: cy + h))
            path.move(to:    CGPoint(x: cx + h, y: cy - h))
            path.addLine(to: CGPoint(x: cx - h, y: cy + h))
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

    /// Tank-tread capsule (horizontal stadium shape, flatter than an oval).
    /// Drawn outlined, no fill — matches the APP-6 armour basic icon.
    private func drawArmourCapsule(ctx: GraphicsContext, in rect: CGRect) {
        let capW = rect.width * 0.68
        let capH = rect.height * 0.42
        let capRect = CGRect(x: rect.midX - capW/2,
                             y: rect.midY - capH/2,
                             width: capW, height: capH)
        let path = Path(roundedRect: capRect, cornerRadius: capH / 2)
        ctx.stroke(path, with: .color(.black), lineWidth: 2)
    }

    /// Diagonal slash from lower-left to upper-right ("sabre belt").
    /// Used by Reconnaissance and Cavalry.
    private func drawRecceSlash(ctx: GraphicsContext, in rect: CGRect) {
        var path = Path()
        path.move(to:    CGPoint(x: rect.minX, y: rect.maxY))
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.minY))
        ctx.stroke(path, with: .color(.black), lineWidth: 2)
    }

    /// Inverted V (apex at the top-centre, legs at the bottom-left and
    /// bottom-right corners). APP-6 anti-tank — "concentrated piercing
    /// action".
    private func drawAntiTankVee(ctx: GraphicsContext, in rect: CGRect) {
        var p = Path()
        p.move(to:    CGPoint(x: rect.minX, y: rect.maxY))   // bottom-left
        p.addLine(to: CGPoint(x: rect.midX, y: rect.minY))   // top-centre (apex)
        p.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY))   // bottom-right
        ctx.stroke(p, with: .color(.black), lineWidth: 2.5)
    }

    /// "Letter E on its side" — horizontal baseline with three short
    /// vertical legs rising from it (left, centre, right). APP-6 engineer.
    private func drawEngineerBridge(ctx: GraphicsContext, in rect: CGRect) {
        let inset   = rect.width * 0.12
        let baseY   = rect.maxY - rect.height * 0.22
        let topY    = rect.minY + rect.height * 0.22
        let leftX   = rect.minX + inset
        let rightX  = rect.maxX - inset
        let midX    = rect.midX
        var p = Path()
        // Baseline.
        p.move(to:    CGPoint(x: leftX,  y: baseY))
        p.addLine(to: CGPoint(x: rightX, y: baseY))
        // Three verticals.
        p.move(to:    CGPoint(x: leftX,  y: baseY)); p.addLine(to: CGPoint(x: leftX,  y: topY))
        p.move(to:    CGPoint(x: midX,   y: baseY)); p.addLine(to: CGPoint(x: midX,   y: topY))
        p.move(to:    CGPoint(x: rightX, y: baseY)); p.addLine(to: CGPoint(x: rightX, y: topY))
        ctx.stroke(p, with: .color(.black), lineWidth: 2)
    }

    /// Stylised lightning flash — four points zigzagging from upper-right
    /// to lower-left, evocative of radio waves. APP-6 signals glyph.
    private func drawSignalLightning(ctx: GraphicsContext, in rect: CGRect) {
        let x1 = rect.minX + rect.width * 0.70
        let y1 = rect.minY + rect.height * 0.15
        let x2 = rect.minX + rect.width * 0.40
        let y2 = rect.minY + rect.height * 0.45
        let x3 = rect.minX + rect.width * 0.60
        let y3 = rect.minY + rect.height * 0.55
        let x4 = rect.minX + rect.width * 0.30
        let y4 = rect.minY + rect.height * 0.85
        var p = Path()
        p.move(to:    CGPoint(x: x1, y: y1))
        p.addLine(to: CGPoint(x: x2, y: y2))
        p.addLine(to: CGPoint(x: x3, y: y3))
        p.addLine(to: CGPoint(x: x4, y: y4))
        ctx.stroke(p, with: .color(.black), lineWidth: 2)
    }

    /// Single flat horizontal line through the centre — APP-6 Supply
    /// glyph ("side view of a road").
    private func drawSupplyLine(ctx: GraphicsContext, in rect: CGRect) {
        let inset = rect.width * 0.18
        var p = Path()
        p.move(to:    CGPoint(x: rect.minX + inset, y: rect.midY))
        p.addLine(to: CGPoint(x: rect.maxX - inset, y: rect.midY))
        ctx.stroke(p, with: .color(.black), lineWidth: 2)
    }

    /// Bowtie (▶◀) — two filled triangles meeting apex-to-apex at the
    /// centre. APP-6 rotary-wing aviation ("blurred spinning helicopter
    /// blades").
    private func drawRotorBlades(ctx: GraphicsContext, in rect: CGRect) {
        let cx     = rect.midX
        let cy     = rect.midY
        let halfH  = rect.height * 0.28
        let halfW  = rect.width  * 0.36
        // Left triangle: vertical base on the left, apex at centre.
        var left = Path()
        left.move(to:    CGPoint(x: cx - halfW, y: cy - halfH))
        left.addLine(to: CGPoint(x: cx - halfW, y: cy + halfH))
        left.addLine(to: CGPoint(x: cx,         y: cy))
        left.closeSubpath()
        // Right triangle: vertical base on the right, apex at centre.
        var right = Path()
        right.move(to:    CGPoint(x: cx + halfW, y: cy - halfH))
        right.addLine(to: CGPoint(x: cx + halfW, y: cy + halfH))
        right.addLine(to: CGPoint(x: cx,         y: cy))
        right.closeSubpath()
        ctx.fill(left,  with: .color(.black))
        ctx.fill(right, with: .color(.black))
    }

    /// Protective dome — single arc spanning the frame width, concave down.
    private func drawAirDefenceDome(ctx: GraphicsContext, in rect: CGRect) {
        let domeW = rect.width * 0.78
        let domeH = rect.height * 0.65
        let cx = rect.midX
        let baseY = rect.midY + domeH * 0.25
        var p = Path()
        p.move(to: CGPoint(x: cx - domeW / 2, y: baseY))
        p.addQuadCurve(to: CGPoint(x: cx + domeW / 2, y: baseY),
                       control: CGPoint(x: cx, y: baseY - domeH))
        ctx.stroke(p, with: .color(.black), lineWidth: 2)
    }

    /// Small open circle at the bottom-centre, with a vertical arrow
    /// rising from it — APP-6 mortar (projectile + high-arc trajectory).
    private func drawMortarArrow(ctx: GraphicsContext, in rect: CGRect) {
        let cx       = rect.midX
        let circleR  = rect.height * 0.12
        let circleCy = rect.maxY - rect.height * 0.20
        let topY     = rect.minY + rect.height * 0.18

        // Open circle at the base.
        let circle = Path(ellipseIn: CGRect(x: cx - circleR,
                                            y: circleCy - circleR,
                                            width:  circleR * 2,
                                            height: circleR * 2))
        ctx.stroke(circle, with: .color(.black), lineWidth: 1.5)

        // Shaft from top of circle up.
        var shaft = Path()
        shaft.move(to:    CGPoint(x: cx, y: circleCy - circleR))
        shaft.addLine(to: CGPoint(x: cx, y: topY))
        ctx.stroke(shaft, with: .color(.black), lineWidth: 2)

        // Arrowhead.
        let hw = rect.width * 0.13
        var head = Path()
        head.move(to:    CGPoint(x: cx - hw, y: topY + rect.height * 0.10))
        head.addLine(to: CGPoint(x: cx,      y: topY))
        head.addLine(to: CGPoint(x: cx + hw, y: topY + rect.height * 0.10))
        ctx.stroke(head, with: .color(.black), lineWidth: 2)
    }

    /// Dumbbell — two small open circles at left and right, connected by a
    /// short horizontal line. APP-6 maintenance ("stylised wrench").
    private func drawMaintenanceDumbbell(ctx: GraphicsContext, in rect: CGRect) {
        let cy      = rect.midY
        let r       = rect.height * 0.13
        let leftCx  = rect.minX + rect.width * 0.22
        let rightCx = rect.maxX - rect.width * 0.22

        let leftCircle = Path(ellipseIn: CGRect(x: leftCx - r, y: cy - r,
                                                width: r*2, height: r*2))
        let rightCircle = Path(ellipseIn: CGRect(x: rightCx - r, y: cy - r,
                                                 width: r*2, height: r*2))
        ctx.stroke(leftCircle,  with: .color(.black), lineWidth: 1.5)
        ctx.stroke(rightCircle, with: .color(.black), lineWidth: 1.5)

        var bar = Path()
        bar.move(to:    CGPoint(x: leftCx + r,  y: cy))
        bar.addLine(to: CGPoint(x: rightCx - r, y: cy))
        ctx.stroke(bar, with: .color(.black), lineWidth: 2)
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

    // MARK: Echelon

    private func drawEchelon(ctx: GraphicsContext, echelon: SymbolEchelon, in rect: CGRect) {
        let cx = rect.midX
        let cy = rect.midY
        let ink: GraphicsContext.Shading = .color(.black)

        switch echelon {
        case .team:
            // APP-6 Team/Crew (Ø): small open circle with a diagonal slash
            // passing through it, centred above the frame.
            let r = rect.height * 0.35
            let ring = Path(ellipseIn: CGRect(x: cx - r, y: cy - r,
                                              width: r * 2, height: r * 2))
            ctx.stroke(ring, with: ink, lineWidth: 1.5)
            var slash = Path()
            let s = r * 1.2
            slash.move(to:    CGPoint(x: cx - s, y: cy + s))
            slash.addLine(to: CGPoint(x: cx + s, y: cy - s))
            ctx.stroke(slash, with: ink, lineWidth: 1.5)
        case .section:
            drawDots(count: 1, ctx: ctx, cx: cx, cy: cy, radius: 2.2, spacing: 0, ink: ink)
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
