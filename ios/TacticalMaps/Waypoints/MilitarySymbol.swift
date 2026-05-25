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
    // ---- Combat arms ----
    case infantry, armour, mechInfantry, mechInfantryWheeled, recce, cavalry
    case artillery, airDefence, antiTank, mortar, missile
    // ---- Combat support ----
    case engineer, bridging, signal, electronicRanging, electronicWarfare
    case radar, psyOps, cbrn
    case aviation              // rotary-wing
    case aviationFixed         // fixed-wing
    case uav
    // ---- Combat service support ----
    case medical, hospital
    case logistics             // displays as "Supply" — rawValue kept for back-compat
    case fuel, maintenance, ordnance, ammunition, transportation
    case meteorological, topographical
    // ---- Specialist / admin ----
    case militaryPolice, eod, navy
    case css                   // Combat Service Support
    case specialForces, specialOps
    case unspecified

    var displayName: String {
        switch self {
        case .infantry:           return "Infantry"
        case .armour:             return "Armour"
        case .mechInfantry:       return "Mechanised Infantry"
        case .mechInfantryWheeled: return "Mechanised Infantry (Wheeled APC)"
        case .recce:              return "Reconnaissance"
        case .cavalry:            return "Cavalry"
        case .artillery:          return "Artillery"
        case .airDefence:         return "Air Defence"
        case .antiTank:           return "Anti-Tank"
        case .mortar:             return "Mortar"
        case .missile:            return "Missile"
        case .engineer:           return "Engineer"
        case .bridging:           return "Bridging"
        case .signal:             return "Signals"
        case .electronicRanging:  return "Electronic Ranging"
        case .electronicWarfare:  return "Electronic Warfare"
        case .radar:              return "Radar"
        case .psyOps:             return "Psychological Operations"
        case .cbrn:               return "CBRN Defence"
        case .aviation:           return "Aviation (Rotary)"
        case .aviationFixed:      return "Aviation (Fixed-Wing)"
        case .uav:                return "Unmanned Air Vehicle"
        case .medical:            return "Medical"
        case .hospital:           return "Hospital"
        case .logistics:          return "Supply"
        case .fuel:               return "Fuel / POL"
        case .maintenance:        return "Maintenance"
        case .ordnance:           return "Ordnance"
        case .ammunition:         return "Ammunition"
        case .transportation:     return "Transportation"
        case .meteorological:     return "Meteorological"
        case .topographical:      return "Topographical"
        case .militaryPolice:     return "Military Police"
        case .eod:                return "Explosive Ordnance Disposal"
        case .navy:               return "Navy"
        case .css:                return "Combat Service Support"
        case .specialForces:      return "Special Forces"
        case .specialOps:         return "Special Operations Forces"
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
        case .mechInfantryWheeled:
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
        case .missile:               drawMissile(ctx: ctx, in: glyphRect)

        case .engineer:              drawEngineerBridge(ctx: ctx, in: glyphRect)
        case .bridging:              drawBridging(ctx: ctx, in: glyphRect)
        case .signal:                drawSignalLightning(ctx: ctx, in: glyphRect)
        case .electronicRanging:     drawElectronicRanging(ctx: ctx, in: glyphRect)
        case .electronicWarfare:     drawLetter(ctx: ctx, letter: "EW",  in: glyphRect)
        case .radar:                 drawRadar(ctx: ctx, in: glyphRect)
        case .psyOps:                drawPsyOps(ctx: ctx, in: glyphRect)
        case .cbrn:                  drawCBRN(ctx: ctx, in: glyphRect)
        case .aviation:              drawRotorBladesBowtie(ctx: ctx, in: glyphRect)
        case .aviationFixed:         drawFixedWing(ctx: ctx, in: glyphRect)
        case .uav:                   drawUAV(ctx: ctx, in: glyphRect)

        case .medical:               drawMedicalCross(ctx: ctx, in: glyphRect)
        case .hospital:              drawHospital(ctx: ctx, in: glyphRect)
        case .logistics:             drawSupplyLine(ctx: ctx, in: glyphRect)
        case .fuel:                  drawFunnel(ctx: ctx, in: glyphRect)
        case .maintenance:           drawMaintenance(ctx: ctx, in: glyphRect)
        case .ordnance:              drawOrdnance(ctx: ctx, in: glyphRect)
        case .ammunition:            drawAmmunition(ctx: ctx, in: glyphRect)
        case .transportation:        drawWheel(ctx: ctx, in: glyphRect)
        case .meteorological:        drawLetter(ctx: ctx, letter: "MET", in: glyphRect)
        case .topographical:         drawTopographical(ctx: ctx, in: glyphRect)

        case .militaryPolice:        drawLetter(ctx: ctx, letter: "MP",  in: glyphRect)
        case .eod:                   drawLetter(ctx: ctx, letter: "EOD", in: glyphRect)
        case .navy:                  drawAnchor(ctx: ctx, in: glyphRect)
        case .css:                   drawLetter(ctx: ctx, letter: "CSS", in: glyphRect)
        case .specialForces:         drawLetter(ctx: ctx, letter: "SF",  in: glyphRect)
        case .specialOps:            drawLetter(ctx: ctx, letter: "SOF", in: glyphRect)
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

    /// Stylised lightning flash — diagonal Z with a sharp angular zig in
    /// the middle, running from the upper-right toward the lower-left.
    /// APP-6 signals glyph (also used as a building block for radar).
    private func drawSignalLightning(ctx: GraphicsContext, in rect: CGRect) {
        // 3-segment polyline: top-right → mid-left → mid-right → bottom-left.
        // The middle segment crosses back toward the right, creating the
        // characteristic sharp "knee" of a lightning bolt.
        let topX    = rect.maxX - rect.width * 0.10
        let topY    = rect.minY + rect.height * 0.12
        let midLX   = rect.minX + rect.width * 0.30
        let midUY   = rect.minY + rect.height * 0.50
        let midRX   = rect.maxX - rect.width * 0.30
        let midDY   = rect.minY + rect.height * 0.55
        let botX    = rect.minX + rect.width * 0.10
        let botY    = rect.maxY - rect.height * 0.12
        var p = Path()
        p.move(to:    CGPoint(x: topX,  y: topY))
        p.addLine(to: CGPoint(x: midLX, y: midUY))
        p.addLine(to: CGPoint(x: midRX, y: midDY))
        p.addLine(to: CGPoint(x: botX,  y: botY))
        ctx.stroke(p, with: .color(.black), lineWidth: 2)
    }

    /// Single flat horizontal line through the centre, spanning the FULL
    /// width of the frame (edge-to-edge). APP-6 Supply glyph — "side view
    /// of a road".
    private func drawSupplyLine(ctx: GraphicsContext, in rect: CGRect) {
        var p = Path()
        p.move(to:    CGPoint(x: rect.minX, y: rect.midY))
        p.addLine(to: CGPoint(x: rect.maxX, y: rect.midY))
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

    /// Protective dome — single arc whose endpoints sit at the bottom-left
    /// and bottom-right corners of the frame, peaking near the top centre.
    /// APP-6 Air Defence glyph.
    private func drawAirDefenceDome(ctx: GraphicsContext, in rect: CGRect) {
        // Base endpoints AT the corners of the frame.
        let leftEnd  = CGPoint(x: rect.minX, y: rect.maxY)
        let rightEnd = CGPoint(x: rect.maxX, y: rect.maxY)
        // Control point well above the top edge — pulls the quadratic
        // bezier high so the apex of the dome sits just below the top of
        // the frame instead of sagging in the middle.
        let control  = CGPoint(x: rect.midX,
                               y: rect.minY - rect.height * 0.35)
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

    /// APP-6 maintenance — two filled triangles with apexes pointing
    /// OUTWARD (left and right) joined by a horizontal bar through the
    /// centre. Looks like ◀━▶ (stylised open-end wrench).
    private func drawMaintenance(ctx: GraphicsContext, in rect: CGRect) {
        let cy        = rect.midY
        let triHalfH  = rect.height * 0.22
        let triW      = rect.width  * 0.20
        let leftBaseX  = rect.minX + rect.width * 0.30
        let rightBaseX = rect.maxX - rect.width * 0.30
        let leftTipX   = leftBaseX  - triW
        let rightTipX  = rightBaseX + triW

        // Left filled triangle (apex pointing left).
        var left = Path()
        left.move(to:    CGPoint(x: leftBaseX, y: cy - triHalfH))
        left.addLine(to: CGPoint(x: leftBaseX, y: cy + triHalfH))
        left.addLine(to: CGPoint(x: leftTipX,  y: cy))
        left.closeSubpath()
        ctx.fill(left, with: .color(.black))

        // Right filled triangle (apex pointing right).
        var right = Path()
        right.move(to:    CGPoint(x: rightBaseX, y: cy - triHalfH))
        right.addLine(to: CGPoint(x: rightBaseX, y: cy + triHalfH))
        right.addLine(to: CGPoint(x: rightTipX,  y: cy))
        right.closeSubpath()
        ctx.fill(right, with: .color(.black))

        // Connecting bar between the two triangles' bases.
        var bar = Path()
        bar.move(to:    CGPoint(x: leftBaseX,  y: cy))
        bar.addLine(to: CGPoint(x: rightBaseX, y: cy))
        ctx.stroke(bar, with: .color(.black), lineWidth: 2)
    }

    /// Bullet/cartridge — vertical capsule with FLAT bottom and ROUNDED top.
    private func drawAmmunition(ctx: GraphicsContext, in rect: CGRect) {
        let w = rect.width * 0.32
        let h = rect.height * 0.66
        let box = CGRect(x: rect.midX - w/2, y: rect.maxY - h - 2,
                         width: w, height: h)
        // Build path: bottom-left → up to (top-left, corner-radius) → arc over
        // → down to bottom-right → close.
        let r = w / 2
        var p = Path()
        p.move(to:    CGPoint(x: box.minX, y: box.maxY))
        p.addLine(to: CGPoint(x: box.minX, y: box.minY + r))
        p.addArc(center: CGPoint(x: box.midX, y: box.minY + r),
                 radius: r, startAngle: .degrees(180), endAngle: .degrees(0),
                 clockwise: false)
        p.addLine(to: CGPoint(x: box.maxX, y: box.maxY))
        p.closeSubpath()
        ctx.stroke(p, with: .color(.black), lineWidth: 1.5)
    }

    /// Fixed-wing aviation — air-screw: two filled lens shapes (pointed
    /// at the outer ends) touching apex-to-apex at the centre.
    private func drawFixedWing(ctx: GraphicsContext, in rect: CGRect) {
        let cx = rect.midX, cy = rect.midY
        let halfW = rect.width  * 0.36
        let halfH = rect.height * 0.18
        // Left lens: tip at leftmost x, body bulges right.
        var left = Path()
        left.move(to: CGPoint(x: cx - halfW, y: cy))
        left.addQuadCurve(to: CGPoint(x: cx, y: cy),
                          control: CGPoint(x: cx - halfW * 0.5, y: cy - halfH))
        left.addQuadCurve(to: CGPoint(x: cx - halfW, y: cy),
                          control: CGPoint(x: cx - halfW * 0.5, y: cy + halfH))
        // Right lens: tip at rightmost x, body bulges left.
        var right = Path()
        right.move(to: CGPoint(x: cx + halfW, y: cy))
        right.addQuadCurve(to: CGPoint(x: cx, y: cy),
                           control: CGPoint(x: cx + halfW * 0.5, y: cy - halfH))
        right.addQuadCurve(to: CGPoint(x: cx + halfW, y: cy),
                           control: CGPoint(x: cx + halfW * 0.5, y: cy + halfH))
        ctx.fill(left,  with: .color(.black))
        ctx.fill(right, with: .color(.black))
    }

    /// Bridging — bridge cross-section: lens/eye shape made of two
    /// opposing arcs meeting at the left and right edges of the frame.
    private func drawBridging(ctx: GraphicsContext, in rect: CGRect) {
        let leftX  = rect.minX
        let rightX = rect.maxX
        let cy     = rect.midY
        let amp    = rect.height * 0.35
        // Top arc (convex up).
        var top = Path()
        top.move(to: CGPoint(x: leftX, y: cy))
        top.addQuadCurve(to: CGPoint(x: rightX, y: cy),
                         control: CGPoint(x: rect.midX, y: cy - amp))
        ctx.stroke(top, with: .color(.black), lineWidth: 2)
        // Bottom arc (convex down).
        var bot = Path()
        bot.move(to: CGPoint(x: leftX, y: cy))
        bot.addQuadCurve(to: CGPoint(x: rightX, y: cy),
                         control: CGPoint(x: rect.midX, y: cy + amp))
        ctx.stroke(bot, with: .color(.black), lineWidth: 2)
    }

    /// Electronic ranging — single parabolic curve (no mast) opening to
    /// the RIGHT, like a sideways C with a small focus dot.
    private func drawElectronicRanging(ctx: GraphicsContext, in rect: CGRect) {
        let cx = rect.midX
        let cy = rect.midY
        let arcR = min(rect.width, rect.height) * 0.32
        // Half-circle facing RIGHT (concave-right).
        var dish = Path()
        dish.addArc(center: CGPoint(x: cx - arcR * 0.3, y: cy),
                    radius: arcR,
                    startAngle: .degrees(-90),
                    endAngle:   .degrees(90),
                    clockwise: true)
        ctx.stroke(dish, with: .color(.black), lineWidth: 2)
        // Small filled dot at the focus.
        let dotR = arcR * 0.15
        let focus = Path(ellipseIn: CGRect(x: cx + arcR * 0.4 - dotR,
                                           y: cy - dotR,
                                           width: dotR*2, height: dotR*2))
        ctx.fill(focus, with: .color(.black))
    }

    /// Radar — parabolic dish (opening right) on the LEFT side of the
    /// frame with a small lightning flash going off to the upper-right.
    private func drawRadar(ctx: GraphicsContext, in rect: CGRect) {
        let cy = rect.midY
        let dishCx = rect.minX + rect.width * 0.32
        let arcR   = min(rect.width, rect.height) * 0.28
        // Dish: half-circle facing right.
        var dish = Path()
        dish.addArc(center: CGPoint(x: dishCx, y: cy),
                    radius: arcR,
                    startAngle: .degrees(-90),
                    endAngle:   .degrees(90),
                    clockwise: true)
        ctx.stroke(dish, with: .color(.black), lineWidth: 2)
        // Lightning bolt to the upper right of the dish.
        let startX = dishCx + arcR * 0.7
        let startY = cy - arcR * 0.3
        var bolt = Path()
        bolt.move(to:    CGPoint(x: startX, y: startY))
        bolt.addLine(to: CGPoint(x: startX + rect.width * 0.18, y: cy - rect.height * 0.05))
        bolt.addLine(to: CGPoint(x: startX + rect.width * 0.05, y: cy + rect.height * 0.05))
        bolt.addLine(to: CGPoint(x: startX + rect.width * 0.22, y: cy + rect.height * 0.25))
        ctx.stroke(bolt, with: .color(.black), lineWidth: 1.8)
    }

    /// Psychological operations — a horn / megaphone shape opening to the
    /// right (apex on left, wide flat side on right).
    private func drawPsyOps(ctx: GraphicsContext, in rect: CGRect) {
        let leftX   = rect.minX + rect.width * 0.18
        let rightX  = rect.maxX - rect.width * 0.22
        let topY    = rect.midY - rect.height * 0.22
        let botY    = rect.midY + rect.height * 0.22
        var p = Path()
        p.move(to:    CGPoint(x: leftX,  y: rect.midY))
        p.addLine(to: CGPoint(x: rightX, y: topY))
        p.addLine(to: CGPoint(x: rightX, y: botY))
        p.closeSubpath()
        ctx.stroke(p, with: .color(.black), lineWidth: 2)
        // Sound waves to the right of the horn.
        var wave1 = Path()
        wave1.move(to: CGPoint(x: rightX + 3, y: topY))
        wave1.addLine(to: CGPoint(x: rightX + 3, y: botY))
        ctx.stroke(wave1, with: .color(.black), lineWidth: 1.5)
    }

    /// CBRN defence — two crossed leaf-shapes (retorts) forming an X.
    private func drawCBRN(ctx: GraphicsContext, in rect: CGRect) {
        let cx = rect.midX, cy = rect.midY
        let len = min(rect.width, rect.height) * 0.42
        let bulge = len * 0.30
        // First retort: NW to SE.
        var r1 = Path()
        r1.move(to:    CGPoint(x: cx - len, y: cy - len))
        r1.addQuadCurve(to: CGPoint(x: cx + len, y: cy + len),
                        control: CGPoint(x: cx - bulge, y: cy + bulge))
        r1.addQuadCurve(to: CGPoint(x: cx - len, y: cy - len),
                        control: CGPoint(x: cx + bulge, y: cy - bulge))
        ctx.fill(r1, with: .color(.black))
        // Second retort: NE to SW.
        var r2 = Path()
        r2.move(to:    CGPoint(x: cx + len, y: cy - len))
        r2.addQuadCurve(to: CGPoint(x: cx - len, y: cy + len),
                        control: CGPoint(x: cx + bulge, y: cy + bulge))
        r2.addQuadCurve(to: CGPoint(x: cx + len, y: cy - len),
                        control: CGPoint(x: cx - bulge, y: cy - bulge))
        ctx.fill(r2, with: .color(.black))
    }

    /// Ordnance — outlined disc in the centre with two horizontal bars
    /// extending outward to the left and right edges of the frame
    /// (cannons sticking out behind a disc).
    private func drawOrdnance(ctx: GraphicsContext, in rect: CGRect) {
        let r = rect.height * 0.22
        let cx = rect.midX, cy = rect.midY
        // Horizontal bars left and right of the disc.
        var bars = Path()
        bars.move(to:    CGPoint(x: rect.minX + rect.width * 0.10, y: cy))
        bars.addLine(to: CGPoint(x: cx - r, y: cy))
        bars.move(to:    CGPoint(x: cx + r, y: cy))
        bars.addLine(to: CGPoint(x: rect.maxX - rect.width * 0.10, y: cy))
        ctx.stroke(bars, with: .color(.black), lineWidth: 2)
        // Centre disc (outlined).
        let disc = Path(ellipseIn: CGRect(x: cx - r, y: cy - r,
                                          width: r * 2, height: r * 2))
        ctx.stroke(disc, with: .color(.black), lineWidth: 1.5)
    }

    /// Fuel / POL — simplified funnel: a wide inverted triangle (open
    /// mouth at the top, apex at lower-mid) with a short vertical stem
    /// continuing below the apex.
    private func drawFunnel(ctx: GraphicsContext, in rect: CGRect) {
        let topY     = rect.minY + rect.height * 0.18
        let apexY    = rect.minY + rect.height * 0.62
        let stemBotY = rect.maxY - rect.height * 0.12
        let leftX  = rect.minX + rect.width * 0.18
        let rightX = rect.maxX - rect.width * 0.18
        // Triangle outline.
        var funnel = Path()
        funnel.move(to:    CGPoint(x: leftX,    y: topY))
        funnel.addLine(to: CGPoint(x: rect.midX, y: apexY))
        funnel.addLine(to: CGPoint(x: rightX,   y: topY))
        ctx.stroke(funnel, with: .color(.black), lineWidth: 2)
        // Stem from apex straight down.
        var stem = Path()
        stem.move(to:    CGPoint(x: rect.midX, y: apexY))
        stem.addLine(to: CGPoint(x: rect.midX, y: stemBotY))
        ctx.stroke(stem, with: .color(.black), lineWidth: 2)
    }

    /// Missile — narrow vertical projectile silhouette (pointed top).
    private func drawMissile(ctx: GraphicsContext, in rect: CGRect) {
        let w = rect.width * 0.20
        let topY = rect.minY + rect.height * 0.10
        let noseY = topY + rect.height * 0.18
        let botY = rect.maxY - rect.height * 0.12
        let leftX  = rect.midX - w/2
        let rightX = rect.midX + w/2
        var p = Path()
        // Pointed nose.
        p.move(to:    CGPoint(x: rect.midX, y: topY))
        p.addLine(to: CGPoint(x: rightX,    y: noseY))
        p.addLine(to: CGPoint(x: rightX,    y: botY))
        p.addLine(to: CGPoint(x: leftX,     y: botY))
        p.addLine(to: CGPoint(x: leftX,     y: noseY))
        p.closeSubpath()
        ctx.stroke(p, with: .color(.black), lineWidth: 1.5)
    }

    /// Navy — anchor: small ring at the top, vertical shaft, horizontal
    /// stock near the top, two curved flukes at the bottom.
    private func drawAnchor(ctx: GraphicsContext, in rect: CGRect) {
        let cx = rect.midX
        let topY    = rect.minY + rect.height * 0.16
        let stockY  = rect.minY + rect.height * 0.32
        let botY    = rect.maxY - rect.height * 0.18
        let ringR   = rect.width  * 0.07
        let halfBar = rect.width  * 0.22
        let flukeR  = rect.width  * 0.28

        // Ring at the very top.
        let ring = Path(ellipseIn: CGRect(x: cx - ringR, y: topY - ringR*2,
                                          width: ringR*2, height: ringR*2))
        ctx.stroke(ring, with: .color(.black), lineWidth: 1.5)

        // Vertical shaft from the ring down to the crown.
        var shaft = Path()
        shaft.move(to:    CGPoint(x: cx, y: topY))
        shaft.addLine(to: CGPoint(x: cx, y: botY))
        ctx.stroke(shaft, with: .color(.black), lineWidth: 2)

        // Stock (crossbar) near the top.
        var stock = Path()
        stock.move(to:    CGPoint(x: cx - halfBar, y: stockY))
        stock.addLine(to: CGPoint(x: cx + halfBar, y: stockY))
        ctx.stroke(stock, with: .color(.black), lineWidth: 2)

        // Left fluke — arc curving down and outward from the crown to the
        // lower-left, then back up slightly (tip).
        var leftFluke = Path()
        leftFluke.addArc(center: CGPoint(x: cx, y: botY),
                         radius: flukeR,
                         startAngle: .degrees(90),
                         endAngle:   .degrees(180),
                         clockwise: false)
        ctx.stroke(leftFluke, with: .color(.black), lineWidth: 2)

        var rightFluke = Path()
        rightFluke.addArc(center: CGPoint(x: cx, y: botY),
                          radius: flukeR,
                          startAngle: .degrees(0),
                          endAngle:   .degrees(90),
                          clockwise: false)
        ctx.stroke(rightFluke, with: .color(.black), lineWidth: 2)
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

    /// UAV — wide flat boomerang/wing silhouette (a shallow inverted-V).
    private func drawUAV(ctx: GraphicsContext, in rect: CGRect) {
        let leftX   = rect.minX + rect.width * 0.10
        let rightX  = rect.maxX - rect.width * 0.10
        let tipY    = rect.midY - rect.height * 0.06
        let backY   = rect.midY + rect.height * 0.12
        let midDown = rect.midY + rect.height * 0.04
        var p = Path()
        p.move(to:    CGPoint(x: leftX,    y: backY))
        p.addQuadCurve(to: CGPoint(x: rightX, y: backY),
                       control: CGPoint(x: rect.midX, y: tipY - 8))
        p.addLine(to: CGPoint(x: rect.midX, y: midDown))
        p.closeSubpath()
        ctx.fill(p, with: .color(.black))
    }

    /// Topographical — stylised sextant: arc opening downward with a small
    /// triangular index arm pointing up from its centre.
    private func drawTopographical(ctx: GraphicsContext, in rect: CGRect) {
        let cy = rect.midY
        let arcR = rect.width * 0.32
        var arc = Path()
        arc.addArc(center: CGPoint(x: rect.midX, y: cy + rect.height * 0.05),
                   radius: arcR,
                   startAngle: .degrees(200),
                   endAngle:   .degrees(340),
                   clockwise: false)
        ctx.stroke(arc, with: .color(.black), lineWidth: 2)
        // Index arm: small upward triangle.
        var tri = Path()
        let triH = rect.height * 0.30
        tri.move(to:    CGPoint(x: rect.midX, y: cy - triH))
        tri.addLine(to: CGPoint(x: rect.midX - 5, y: cy))
        tri.addLine(to: CGPoint(x: rect.midX + 5, y: cy))
        tri.closeSubpath()
        ctx.stroke(tri, with: .color(.black), lineWidth: 1.5)
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

    /// Hospital — geometric H with horizontal crossbars at the top and
    /// bottom (medical cross + H combined).
    private func drawHospital(ctx: GraphicsContext, in rect: CGRect) {
        let leftX  = rect.minX + rect.width * 0.30
        let rightX = rect.maxX - rect.width * 0.30
        let topY   = rect.minY + rect.height * 0.20
        let botY   = rect.maxY - rect.height * 0.20
        let midY   = rect.midY
        var p = Path()
        // Two vertical legs of the H.
        p.move(to:    CGPoint(x: leftX,  y: topY))
        p.addLine(to: CGPoint(x: leftX,  y: botY))
        p.move(to:    CGPoint(x: rightX, y: topY))
        p.addLine(to: CGPoint(x: rightX, y: botY))
        // Middle crossbar.
        p.move(to:    CGPoint(x: leftX,  y: midY))
        p.addLine(to: CGPoint(x: rightX, y: midY))
        // Top and bottom crossbars (extend slightly outside the legs).
        let extra = rect.width * 0.10
        p.move(to:    CGPoint(x: leftX  - extra, y: topY))
        p.addLine(to: CGPoint(x: rightX + extra, y: topY))
        p.move(to:    CGPoint(x: leftX  - extra, y: botY))
        p.addLine(to: CGPoint(x: rightX + extra, y: botY))
        ctx.stroke(p, with: .color(.black), lineWidth: 2)
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
