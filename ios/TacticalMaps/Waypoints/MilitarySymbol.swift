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
    // Sorted alphabetically by displayName so the picker reads naturally.
    case airDefence            // Air Defence
    case ammunition            // Ammunition
    case antiTank              // Anti-Tank
    case armour                // Armour
    case artillery             // Artillery
    case aviationFixed         // Aviation (Fixed-Wing)
    case aviation              // Aviation (Rotary)
    case bridging              // Bridging
    case cavalry               // Cavalry
    case cbrn                  // CBRN Defence
    case css                   // Combat Service Support
    case electronicWarfare     // Electronic Warfare
    case engineer              // Engineer
    case eod                   // Explosive Ordnance Disposal
    case fuel                  // Fuel / POL
    case infantry              // Infantry
    case maintenance           // Maintenance
    case mechInfantry          // Mechanised Infantry
    case medical               // Medical
    case militaryPolice        // Military Police
    case mortar                // Mortar
    case motorisedInfantry     // Motorised Infantry
    case ordnance              // Ordnance
    case radar                 // Radar
    case recce                 // Reconnaissance
    case signal                // Signals
    case specialForces         // Special Forces
    case logistics             // Supply  (rawValue kept for back-compat)
    case transportation        // Transportation
    case uav                   // Unmanned Air Vehicle
    case unspecified           // — (no branch), always shown last

    var displayName: String {
        switch self {
        case .airDefence:         return "Air Defence"
        case .ammunition:         return "Ammunition"
        case .antiTank:           return "Anti-Tank"
        case .armour:             return "Armour"
        case .artillery:          return "Artillery"
        case .aviationFixed:      return "Aviation (Fixed-Wing)"
        case .aviation:           return "Aviation (Rotary)"
        case .bridging:           return "Bridging"
        case .cavalry:            return "Cavalry"
        case .cbrn:               return "CBRN Defence"
        case .css:                return "Combat Service Support"
        case .electronicWarfare:  return "Electronic Warfare"
        case .engineer:           return "Engineer"
        case .eod:                return "Explosive Ordnance Disposal"
        case .fuel:               return "Fuel / POL"
        case .infantry:           return "Infantry"
        case .maintenance:        return "Maintenance"
        case .mechInfantry:       return "Mechanised Infantry"
        case .medical:            return "Medical"
        case .militaryPolice:     return "Military Police"
        case .mortar:             return "Mortar"
        case .motorisedInfantry:  return "Motorised Infantry"
        case .ordnance:           return "Ordnance"
        case .radar:              return "Radar"
        case .recce:              return "Reconnaissance"
        case .signal:             return "Signals"
        case .specialForces:      return "Special Forces"
        case .logistics:          return "Supply"
        case .transportation:     return "Transportation"
        case .uav:                return "Unmanned Air Vehicle"
        case .unspecified:        return "— (no branch)"
        }
    }
}

// MARK: - Spec

struct MilitarySymbolSpec: Hashable, Codable {
    var affiliation: SymbolAffiliation
    var echelon:     SymbolEchelon
    var function:    SymbolFunction
    /// When true, the flagpole modifier is drawn from the bottom-left
    /// corner of the frame extending downward — marking this symbol as
    /// a Headquarters.
    var isHeadquarters: Bool

    init(affiliation: SymbolAffiliation,
         echelon:     SymbolEchelon,
         function:    SymbolFunction = .infantry,
         isHeadquarters: Bool = false) {
        self.affiliation    = affiliation
        self.echelon        = echelon
        self.function       = function
        self.isHeadquarters = isHeadquarters
    }

    // Custom Codable so isHeadquarters defaults to false when decoding
    // older waypoint data that doesn't carry the field.
    private enum CodingKeys: String, CodingKey {
        case affiliation, echelon, function, isHeadquarters
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        affiliation    = try c.decode(SymbolAffiliation.self, forKey: .affiliation)
        echelon        = try c.decode(SymbolEchelon.self,     forKey: .echelon)
        function       = try c.decode(SymbolFunction.self,    forKey: .function)
        isHeadquarters = (try? c.decode(Bool.self, forKey: .isHeadquarters)) ?? false
    }
    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(affiliation, forKey: .affiliation)
        try c.encode(echelon,     forKey: .echelon)
        try c.encode(function,    forKey: .function)
        if isHeadquarters {
            try c.encode(true, forKey: .isHeadquarters)
        }
    }
}

// MARK: - Rendering

/// SwiftUI view that draws the APP-6C symbol. Use directly in lists / pickers,
/// or hand to `MilitarySymbolRenderer.image(for:)` to bake into a UIImage for
/// a MapKit annotation.
struct MilitarySymbolView: View {
    let spec: MilitarySymbolSpec
    var size: CGFloat = 56

    /// Extra vertical room reserved for the HQ flagpole modifier (when set).
    private var poleReserve: CGFloat { spec.isHeadquarters ? size * 0.42 : 0 }
    /// Total canvas height — symbol frame + (optional) flagpole strip below.
    private var canvasHeight: CGFloat { size + poleReserve }

    var body: some View {
        Canvas { ctx, canvasSize in
            let w = canvasSize.width
            let h = canvasSize.height
            let poleH = poleReserve
            let echelonH: CGFloat = (h - poleH) * 0.22
            let gap: CGFloat      = (h - poleH) * 0.06

            let frameTop = echelonH + gap
            let frameBottom = h - poleH - 2
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

            // HQ flagpole modifier: flows from the bottom-left corner of
            // the frame straight down into the reserved strip below.
            if spec.isHeadquarters {
                var pole = Path()
                pole.move(to:    CGPoint(x: frameRect.minX, y: frameRect.maxY))
                pole.addLine(to: CGPoint(x: frameRect.minX, y: frameRect.maxY + poleH))
                ctx.stroke(pole, with: .color(.black), lineWidth: 2)
            }
        }
        .frame(width: size, height: canvasHeight)
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
        case .unspecified:           break

        case .infantry:              drawInfantryX(ctx: ctx, affiliation: affiliation, frame: frame)
        case .armour:                drawArmourCapsule(ctx: ctx, in: glyphRect)
        case .mechInfantry:
            drawArmourCapsule(ctx: ctx, in: glyphRect)
            drawInfantryX(ctx: ctx, affiliation: affiliation, frame: glyphRect)
        case .motorisedInfantry:
            drawArmourCapsule(ctx: ctx, in: glyphRect)
            drawInfantryX(ctx: ctx, affiliation: affiliation, frame: glyphRect)
            drawWheels(ctx: ctx, in: glyphRect)
        case .recce:                 drawRecceSlash(ctx: ctx, in: glyphRect)
        case .cavalry:
            drawArmourCapsule(ctx: ctx, in: glyphRect)
            drawRecceSlash(ctx: ctx, in: glyphRect)
        case .artillery:
            let r = min(glyphRect.width, glyphRect.height) * 0.18
            let dot = Path(ellipseIn: CGRect(x: glyphRect.midX - r,
                                             y: glyphRect.midY - r,
                                             width: r*2, height: r*2))
            ctx.fill(dot, with: .color(.black))
        case .airDefence:            drawAirDefenceDome(ctx: ctx, in: glyphRect)
        case .antiTank:              drawAntiTankVee(ctx: ctx, in: glyphRect)
        case .mortar:                drawMortarArrow(ctx: ctx, in: glyphRect)

        case .engineer:              drawEngineerBridge(ctx: ctx, in: glyphRect)
        case .bridging:              drawBridging(ctx: ctx, in: glyphRect)
        case .signal:                drawSignalLightning(ctx: ctx, in: glyphRect)
        case .electronicWarfare:     drawLetter(ctx: ctx, letter: "EW",  in: glyphRect)
        case .radar:                 drawRadar(ctx: ctx, in: glyphRect)
        case .cbrn:                  drawCBRN(ctx: ctx, in: glyphRect)
        case .aviation:              drawRotorBladesBowtie(ctx: ctx, in: glyphRect)
        case .aviationFixed:         drawFixedWing(ctx: ctx, in: glyphRect)
        case .uav:                   drawUAV(ctx: ctx, in: glyphRect)

        case .medical:               drawMedicalCross(ctx: ctx, in: glyphRect)
        case .logistics:             drawSupplyLine(ctx: ctx, in: glyphRect)
        case .fuel:                  drawFunnel(ctx: ctx, in: glyphRect)
        case .maintenance:           drawMaintenance(ctx: ctx, in: glyphRect)
        case .ordnance:              drawOrdnance(ctx: ctx, in: glyphRect)
        case .ammunition:            drawAmmunition(ctx: ctx, in: glyphRect)
        case .transportation:        drawWheel(ctx: ctx, in: glyphRect)

        case .militaryPolice:        drawLetter(ctx: ctx, letter: "MP",  in: glyphRect)
        case .eod:                   drawLetter(ctx: ctx, letter: "EOD", in: glyphRect)
        case .css:                   drawLetter(ctx: ctx, letter: "CSS", in: glyphRect)
        case .specialForces:         drawLetter(ctx: ctx, letter: "SF",  in: glyphRect)
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

    /// "Letter E on its side" — horizontal beam at TOP with three short
    /// vertical legs descending from it (left, centre, right). APP-6 engineer.
    private func drawEngineerBridge(ctx: GraphicsContext, in rect: CGRect) {
        let inset   = rect.width * 0.12
        let topY    = rect.minY + rect.height * 0.22
        let botY    = rect.maxY - rect.height * 0.22
        let leftX   = rect.minX + inset
        let rightX  = rect.maxX - inset
        let midX    = rect.midX
        var p = Path()
        // Top beam.
        p.move(to:    CGPoint(x: leftX,  y: topY))
        p.addLine(to: CGPoint(x: rightX, y: topY))
        // Three descending verticals.
        p.move(to:    CGPoint(x: leftX,  y: topY)); p.addLine(to: CGPoint(x: leftX,  y: botY))
        p.move(to:    CGPoint(x: midX,   y: topY)); p.addLine(to: CGPoint(x: midX,   y: botY))
        p.move(to:    CGPoint(x: rightX, y: topY)); p.addLine(to: CGPoint(x: rightX, y: botY))
        ctx.stroke(p, with: .color(.black), lineWidth: 2)
    }

    /// Stylised lightning flash — diagonal Z whose endpoints touch the
    /// TOP-LEFT and BOTTOM-RIGHT corners of the frame, with a sharp
    /// angular zig in the middle. APP-6 signals glyph.
    private func drawSignalLightning(ctx: GraphicsContext, in rect: CGRect) {
        let p1 = CGPoint(x: rect.minX, y: rect.minY)                    // top-left corner
        let p2 = CGPoint(x: rect.maxX - rect.width * 0.32, y: rect.midY)
        let p3 = CGPoint(x: rect.minX + rect.width * 0.32, y: rect.midY)
        let p4 = CGPoint(x: rect.maxX, y: rect.maxY)                    // bottom-right corner
        var p = Path()
        p.move(to: p1)
        p.addLine(to: p2)
        p.addLine(to: p3)
        p.addLine(to: p4)
        ctx.stroke(p, with: .color(.black), lineWidth: 2)
    }

    /// Single flat horizontal line spanning the FULL width of the frame,
    /// positioned in the LOWER portion of the frame (around 75% down).
    /// APP-6 Supply glyph — "side view of a road".
    private func drawSupplyLine(ctx: GraphicsContext, in rect: CGRect) {
        let y = rect.minY + rect.height * 0.75
        var p = Path()
        p.move(to:    CGPoint(x: rect.minX, y: y))
        p.addLine(to: CGPoint(x: rect.maxX, y: y))
        ctx.stroke(p, with: .color(.black), lineWidth: 2)
    }

    /// Bowtie ▶◀ — two filled triangles meeting apex-to-apex at the
    /// centre. APP-6 rotary-wing aviation ("blurred spinning helicopter
    /// blades").
    private func drawRotorBladesBowtie(ctx: GraphicsContext, in rect: CGRect) {
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

    /// Shallow protective dome sitting in the LOWER portion of the frame.
    /// Endpoints at the bottom corners; apex at ~60% down from the top.
    private func drawAirDefenceDome(ctx: GraphicsContext, in rect: CGRect) {
        let leftEnd  = CGPoint(x: rect.minX, y: rect.maxY)
        let rightEnd = CGPoint(x: rect.maxX, y: rect.maxY)
        // For a symmetric quad bezier the apex y is (startY + endY + 2*ctrlY)/4.
        // Putting ctrlY at minY + 0.20*h gives an apex at 60% down — well
        // inside the lower half of the frame.
        let control = CGPoint(x: rect.midX, y: rect.minY + rect.height * 0.20)
        var p = Path()
        p.move(to: leftEnd)
        p.addQuadCurve(to: rightEnd, control: control)
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

    /// APP-6 maintenance — horizontal shaft with semi-circle caps at each
    /// end, opening OUTWARD (away from centre). Shape: (━━━).
    private func drawMaintenance(ctx: GraphicsContext, in rect: CGRect) {
        let cy     = rect.midY
        let capR   = rect.height * 0.22
        let leftX  = rect.minX + rect.width * 0.18
        let rightX = rect.maxX - rect.width * 0.18
        // Horizontal shaft from the inner edge of the left cap to the
        // inner edge of the right cap.
        var shaft = Path()
        shaft.move(to:    CGPoint(x: leftX,  y: cy))
        shaft.addLine(to: CGPoint(x: rightX, y: cy))
        ctx.stroke(shaft, with: .color(.black), lineWidth: 2)
        // Left semi-circle (opens LEFT — bulges toward the left edge).
        var lcap = Path()
        lcap.addArc(center: CGPoint(x: leftX, y: cy),
                    radius: capR,
                    startAngle: .degrees(90),
                    endAngle:   .degrees(270),
                    clockwise: false)
        ctx.stroke(lcap, with: .color(.black), lineWidth: 2)
        // Right semi-circle (opens RIGHT — bulges toward the right edge).
        var rcap = Path()
        rcap.addArc(center: CGPoint(x: rightX, y: cy),
                    radius: capR,
                    startAngle: .degrees(270),
                    endAngle:   .degrees(90),
                    clockwise: false)
        ctx.stroke(rcap, with: .color(.black), lineWidth: 2)
    }

    /// Ammunition — vertical bullet / cartridge silhouette: flat bottom,
    /// vertical sides, semicircular dome on top.
    private func drawAmmunition(ctx: GraphicsContext, in rect: CGRect) {
        let bodyW = rect.width * 0.36
        let cx    = rect.midX
        let leftX  = cx - bodyW / 2
        let rightX = cx + bodyW / 2
        let r      = bodyW / 2
        let baseY  = rect.maxY - rect.height * 0.15
        let domeBaseY = rect.minY + rect.height * 0.22 + r
        var p = Path()
        p.move(to:    CGPoint(x: leftX,  y: baseY))           // bottom-left
        p.addLine(to: CGPoint(x: leftX,  y: domeBaseY))       // up left side
        // Semicircular dome from left to right at the top.
        p.addArc(center: CGPoint(x: cx, y: domeBaseY),
                 radius: r,
                 startAngle: .degrees(180),
                 endAngle:   .degrees(0),
                 clockwise:  false)
        p.addLine(to: CGPoint(x: rightX, y: baseY))           // down right side
        p.closeSubpath()
        ctx.stroke(p, with: .color(.black), lineWidth: 1.5)
    }

    /// Fixed-wing aviation — air-screw: two filled lens shapes with
    /// POINTED outer tips, pinching together at the centre to form a
    /// horizontal infinity / figure-8.
    private func drawFixedWing(ctx: GraphicsContext, in rect: CGRect) {
        let cx = rect.midX, cy = rect.midY
        let halfW = rect.width  * 0.40
        let halfH = rect.height * 0.22
        // Left lens: pointed at far-left, fat in the middle, pinched at cx.
        var left = Path()
        left.move(to: CGPoint(x: cx - halfW, y: cy))
        left.addQuadCurve(to: CGPoint(x: cx, y: cy),
                          control: CGPoint(x: cx - halfW * 0.45, y: cy - halfH))
        left.addQuadCurve(to: CGPoint(x: cx - halfW, y: cy),
                          control: CGPoint(x: cx - halfW * 0.45, y: cy + halfH))
        ctx.fill(left, with: .color(.black))
        // Right lens: mirror of left.
        var right = Path()
        right.move(to: CGPoint(x: cx + halfW, y: cy))
        right.addQuadCurve(to: CGPoint(x: cx, y: cy),
                           control: CGPoint(x: cx + halfW * 0.45, y: cy - halfH))
        right.addQuadCurve(to: CGPoint(x: cx + halfW, y: cy),
                           control: CGPoint(x: cx + halfW * 0.45, y: cy + halfH))
        ctx.fill(right, with: .color(.black))
    }

    /// Bridging — flat horizontal capsule outline (lens / eye shape) with
    /// a horizontal line through the middle. Topographical map bridge
    /// symbol.
    private func drawBridging(ctx: GraphicsContext, in rect: CGRect) {
        let capW = rect.width * 0.78
        let capH = rect.height * 0.40
        let capRect = CGRect(x: rect.midX - capW/2,
                             y: rect.midY - capH/2,
                             width: capW, height: capH)
        // Fully-rounded capsule outline.
        let cap = Path(roundedRect: capRect, cornerRadius: capH / 2)
        ctx.stroke(cap, with: .color(.black), lineWidth: 1.8)
        // Horizontal line through the centre, slightly wider than the
        // capsule so it pokes out the rounded ends a touch.
        var line = Path()
        line.move(to:    CGPoint(x: capRect.minX - capH * 0.20, y: rect.midY))
        line.addLine(to: CGPoint(x: capRect.maxX + capH * 0.20, y: rect.midY))
        ctx.stroke(line, with: .color(.black), lineWidth: 1.8)
    }

    /// Radar — small parabolic dish on the left + two short concentric
    /// radio-wave arcs radiating from its focus to the right.
    private func drawRadar(ctx: GraphicsContext, in rect: CGRect) {
        let cy = rect.midY
        let dishCx = rect.minX + rect.width * 0.30
        let dishR  = min(rect.width, rect.height) * 0.22
        // Dish: half-circle facing right (concave-right).
        var dish = Path()
        dish.addArc(center: CGPoint(x: dishCx, y: cy),
                    radius: dishR,
                    startAngle: .degrees(-90),
                    endAngle:   .degrees(90),
                    clockwise: true)
        ctx.stroke(dish, with: .color(.black), lineWidth: 2)
        // Two wave arcs to the right of the dish.
        for i in 1...2 {
            let r = dishR + CGFloat(i) * (dishR * 0.7)
            var wave = Path()
            wave.addArc(center: CGPoint(x: dishCx, y: cy),
                        radius: r,
                        startAngle: .degrees(-40),
                        endAngle:   .degrees(40),
                        clockwise: false)
            ctx.stroke(wave, with: .color(.black), lineWidth: 1.2)
        }
    }

    /// CBRN defence — two crossed filled ovals (stylised retorts) at ±45°,
    /// each with a rounded bulb at one end. Saltire pattern overall.
    private func drawCBRN(ctx: GraphicsContext, in rect: CGRect) {
        let cx = rect.midX, cy = rect.midY
        let halfLen = min(rect.width, rect.height) * 0.34
        let halfThk = halfLen * 0.32
        // Build a fatter horizontal lens (rounded), then rotate by ±45°.
        func buildLens(angle: Double) -> Path {
            let lensRect = CGRect(x: cx - halfLen, y: cy - halfThk,
                                  width: halfLen * 2, height: halfThk * 2)
            var p = Path()
            p.addEllipse(in: lensRect)
            return p.applying(CGAffineTransform(translationX: -cx, y: -cy)
                .rotated(by: angle)
                .translatedBy(x: cx, y: cy))
        }
        ctx.fill(buildLens(angle: .pi / 4),  with: .color(.black))
        ctx.fill(buildLens(angle: -.pi / 4), with: .color(.black))
    }

    /// Ordnance — outlined disc in the centre with horizontal cannon
    /// barrels extending out to the left and right edges of the frame.
    private func drawOrdnance(ctx: GraphicsContext, in rect: CGRect) {
        let cx = rect.midX, cy = rect.midY
        let r  = min(rect.width, rect.height) * 0.22
        // Horizontal barrels from the disc edge to the frame edges.
        var bars = Path()
        bars.move(to:    CGPoint(x: rect.minX, y: cy))
        bars.addLine(to: CGPoint(x: cx - r,    y: cy))
        bars.move(to:    CGPoint(x: cx + r,    y: cy))
        bars.addLine(to: CGPoint(x: rect.maxX, y: cy))
        ctx.stroke(bars, with: .color(.black), lineWidth: 2)
        // Outlined disc in the centre.
        let disc = Path(ellipseIn: CGRect(x: cx - r, y: cy - r,
                                          width: r * 2, height: r * 2))
        ctx.stroke(disc, with: .color(.black), lineWidth: 1.8)
    }

    /// Fuel / POL — funnel: wide-mouthed inverted V at the top with a
    /// short vertical stem hanging from the apex. Y silhouette.
    private func drawFunnel(ctx: GraphicsContext, in rect: CGRect) {
        let topY     = rect.minY + rect.height * 0.18
        let apexY    = rect.minY + rect.height * 0.62
        let stemBotY = rect.maxY - rect.height * 0.15
        let leftX  = rect.minX + rect.width * 0.18
        let rightX = rect.maxX - rect.width * 0.18
        var v = Path()
        v.move(to:    CGPoint(x: leftX,    y: topY))
        v.addLine(to: CGPoint(x: rect.midX, y: apexY))
        v.addLine(to: CGPoint(x: rightX,   y: topY))
        ctx.stroke(v, with: .color(.black), lineWidth: 2)
        var stem = Path()
        stem.move(to:    CGPoint(x: rect.midX, y: apexY))
        stem.addLine(to: CGPoint(x: rect.midX, y: stemBotY))
        ctx.stroke(stem, with: .color(.black), lineWidth: 2)
    }

    /// Transportation — outlined wheel with EIGHT radial spokes (wagon-wheel
    /// style). APP-6 transport glyph.
    private func drawWheel(ctx: GraphicsContext, in rect: CGRect) {
        let d = min(rect.width, rect.height) * 0.72
        let r = d / 2
        let cx = rect.midX, cy = rect.midY
        // Hub circle.
        let ring = CGRect(x: cx - r, y: cy - r, width: d, height: d)
        ctx.stroke(Path(ellipseIn: ring), with: .color(.black), lineWidth: 1.5)
        // Eight radial spokes every 45°.
        var spokes = Path()
        for i in 0..<8 {
            let theta = Double(i) * .pi / 4
            spokes.move(to:    CGPoint(x: cx, y: cy))
            spokes.addLine(to: CGPoint(x: cx + r * cos(theta),
                                       y: cy + r * sin(theta)))
        }
        ctx.stroke(spokes, with: .color(.black), lineWidth: 1.2)
    }

    /// UAV — flying-wing silhouette: a wide flat chevron with the apex
    /// pointing DOWN at the centre. Top edge is flat, bottom edge dips
    /// to a point in the middle.
    private func drawUAV(ctx: GraphicsContext, in rect: CGRect) {
        let leftX  = rect.minX + rect.width * 0.06
        let rightX = rect.maxX - rect.width * 0.06
        let topY   = rect.midY - rect.height * 0.10
        let dipY   = rect.midY + rect.height * 0.10
        let apexY  = rect.midY + rect.height * 0.30
        var p = Path()
        // Outer outline: top-left → top-right → apex → top-left.
        p.move(to:    CGPoint(x: leftX,    y: topY))
        p.addLine(to: CGPoint(x: rightX,   y: topY))
        p.addLine(to: CGPoint(x: rect.midX, y: apexY))
        p.closeSubpath()
        ctx.fill(p, with: .color(.black))
        // Suppressing the bottom "dip" because the closed triangle alone
        // reads as a flying wing.
        _ = dipY
    }

    /// Three small open circles in a row along the bottom inside edge of
    /// the frame, representing the wheels of a wheeled APC. Used for
    /// Mechanised Infantry (Wheeled APC).
    private func drawWheels(ctx: GraphicsContext, in rect: CGRect) {
        let r       = rect.height * 0.07
        let cy      = rect.maxY - r - 2
        let spacing = rect.width * 0.18
        for dx in [-spacing, 0, spacing] {
            let cx = rect.midX + dx
            let wheel = Path(ellipseIn: CGRect(x: cx - r, y: cy - r,
                                               width: r * 2, height: r * 2))
            ctx.stroke(wheel, with: .color(.black), lineWidth: 1.2)
        }
    }

    private func drawLetter(ctx: GraphicsContext, letter: String, in rect: CGRect) {
        // Scale the font down for multi-letter labels so they fit inside
        // the affiliation frame (CSS, EOD, SOF, etc.).
        let count = letter.count
        let scale: CGFloat = count <= 1 ? 0.55 : count <= 2 ? 0.42 : 0.32
        let fontSize = min(rect.width, rect.height) * scale
        let text = Text(letter)
            .font(.system(size: fontSize, weight: .heavy, design: .default))
            .foregroundColor(.black)
        ctx.draw(text, at: CGPoint(x: rect.midX, y: rect.midY), anchor: .center)
    }

    /// Medical — thin stroked + sign: a horizontal line edge-to-edge and a
    /// vertical line top-to-bottom, both drawn as 2pt strokes (not filled
    /// bars).
    private func drawMedicalCross(ctx: GraphicsContext, in rect: CGRect) {
        var p = Path()
        p.move(to:    CGPoint(x: rect.minX, y: rect.midY))
        p.addLine(to: CGPoint(x: rect.maxX, y: rect.midY))
        p.move(to:    CGPoint(x: rect.midX, y: rect.minY))
        p.addLine(to: CGPoint(x: rect.midX, y: rect.maxY))
        ctx.stroke(p, with: .color(.black), lineWidth: 2)
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
