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
    case company, battalionRegiment
    case brigade, division

    var displayName: String {
        switch self {
        case .team:      return "Team / Crew"
        case .section:   return "Section"
        case .platoon:   return "Platoon"
        case .company:   return "Company"
        case .battalionRegiment: return "Battalion / Regiment"
        case .brigade:   return "Brigade"
        case .division:  return "Division"
        }
    }

    /// Compact glyph label (used as a fallback / debugging aid).
    var glyph: String {
        switch self {
        case .team:      return "Ø"
        case .section:   return "●"
        case .platoon:   return "●●●"
        case .company:   return "I"
        case .battalionRegiment: return "II"
        case .brigade:   return "X"
        case .division:  return "XX"
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
    case infantry              // Infantry
    case maintenance           // Maintenance
    case mechInfantry          // Mechanised Infantry
    case medical               // Medical
    case militaryPolice        // Military Police
    case mortar                // Mortar
    case motorisedInfantry     // Motorised Infantry
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
        case .infantry:           return "Infantry"
        case .maintenance:        return "Maintenance"
        case .mechInfantry:       return "Mechanised Infantry"
        case .medical:            return "Medical"
        case .militaryPolice:     return "Military Police"
        case .mortar:             return "Mortar"
        case .motorisedInfantry:  return "Motorised Infantry"
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
    /// Hostile / neutral / unknown frames are square with side equal to the
    /// friendly frame WIDTH (not height), so the diamond extends above and
    /// below the friendly frame's vertical extent. Reserve extra space so
    /// the bottom vertex isn't clipped.
    private var diamondReserve: CGFloat {
        spec.affiliation == .friend ? 0 : size * 0.22
    }
    /// Total canvas height — symbol frame + (optional) flagpole + diamond overflow.
    private var canvasHeight: CGFloat { size + poleReserve + diamondReserve }

    var body: some View {
        Canvas { ctx, canvasSize in
            let w = canvasSize.width
            let h = canvasSize.height
            let poleH = poleReserve
            // Echelon indicator (dots / bars / X's above the frame).
            // Bumped from 0.22 → 0.28 of the available height so the
            // marks are easier to read against satellite imagery,
            // since adding a halo around just the indicator would
            // require restructuring the renderer entirely.
            //
            // Gap (between echelon band and frame top) reduced from
            // 0.06 → 0.02 so the echelon sits closer to the frame —
            // user feedback that the platoon dots were floating too
            // far above the rest of the graphic.
            let echelonH: CGFloat = (h - poleH) * 0.28
            let gap: CGFloat      = (h - poleH) * 0.02

            let frameTop = echelonH + gap
            let frameBottom = h - poleH - 2
            let frameH = frameBottom - frameTop

            // Frame geometry per affiliation
            let frameRect: CGRect
            switch spec.affiliation {
            case .friend:
                let frameW = min(w - 4, frameH * 1.5)
                frameRect = CGRect(x: (w - frameW) / 2, y: frameTop,
                                   width: frameW, height: frameH)
            case .hostile, .neutral, .unknown:
                // Square bounding box whose side equals the friendly frame
                // WIDTH (same min(w-4, frameH*1.5) formula). This makes the
                // diamond tip-to-tip the same as the friendly rectangle width,
                // matching APP-6C proportions. The diamond is centred on the
                // same y-midpoint as the friendly frame; the diamondReserve
                // canvas extension absorbs the bottom-vertex overflow.
                let side = min(w - 4, frameH * 1.5)
                frameRect = CGRect(x: (w - side) / 2,
                                   y: frameTop + (frameH - side) / 2,
                                   width: side, height: side)
            }

            // Draw the frame
            switch spec.affiliation {
            case .friend:
                drawAxisAligned(ctx: ctx, in: frameRect)
            case .hostile, .neutral:
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
        case .friend:                    inset = 0
        case .hostile, .neutral:         inset = frame.width * (1 - sqrt(2)/2) / 2
        case .unknown:                   inset = frame.width * 0.18
        }
        let glyphRect = frame.insetBy(dx: inset, dy: inset)

        switch function {
        case .unspecified:           break

        case .infantry:              drawAsset(ctx: ctx, named: "AppSymbols/infantry", in: glyphRect)
        case .armour:                drawAsset(ctx: ctx, named: "AppSymbols/armour", in: glyphRect)
        case .mechInfantry:          drawAsset(ctx: ctx, named: "AppSymbols/mechInfantry", in: glyphRect)
        case .motorisedInfantry:     drawAsset(ctx: ctx, named: "AppSymbols/motorisedInfantry", in: glyphRect)
        case .recce:                 drawAsset(ctx: ctx, named: "AppSymbols/recce", in: glyphRect)
        case .cavalry:               drawAsset(ctx: ctx, named: "AppSymbols/cavalry", in: glyphRect)
        case .artillery:             drawAsset(ctx: ctx, named: "AppSymbols/artillery", in: glyphRect)
        case .airDefence:            drawAsset(ctx: ctx, named: "AppSymbols/airDefence", in: glyphRect)
        case .antiTank:              drawAsset(ctx: ctx, named: "AppSymbols/antiTank", in: glyphRect)
        case .mortar:                drawAsset(ctx: ctx, named: "AppSymbols/mortar", in: glyphRect)

        case .engineer:              drawAsset(ctx: ctx, named: "AppSymbols/engineer", in: glyphRect)
        case .bridging:              drawAsset(ctx: ctx, named: "AppSymbols/bridging", in: glyphRect)
        case .signal:                drawAsset(ctx: ctx, named: "AppSymbols/signal", in: glyphRect)
        case .electronicWarfare:     drawAsset(ctx: ctx, named: "AppSymbols/electronicWarfare", in: glyphRect)
        case .radar:                 drawAsset(ctx: ctx, named: "AppSymbols/radar", in: glyphRect)
        case .cbrn:                  drawAsset(ctx: ctx, named: "AppSymbols/cbrn", in: glyphRect)
        case .aviation:              drawAsset(ctx: ctx, named: "AppSymbols/aviation", in: glyphRect)
        case .aviationFixed:         drawAsset(ctx: ctx, named: "AppSymbols/aviationFixed", in: glyphRect)
        case .uav:                   drawAsset(ctx: ctx, named: "AppSymbols/uav", in: glyphRect)

        case .medical:               drawAsset(ctx: ctx, named: "AppSymbols/medical", in: glyphRect)
        case .logistics:             drawAsset(ctx: ctx, named: "AppSymbols/logistics", in: glyphRect)
        case .maintenance:           drawAsset(ctx: ctx, named: "AppSymbols/maintenance", in: glyphRect)
        case .ammunition:            drawAsset(ctx: ctx, named: "AppSymbols/ammunition", in: glyphRect)
        case .transportation:        drawAsset(ctx: ctx, named: "AppSymbols/transportation", in: glyphRect)

        case .militaryPolice:        drawAsset(ctx: ctx, named: "AppSymbols/militaryPolice", in: glyphRect)
        case .eod:                   drawAsset(ctx: ctx, named: "AppSymbols/eod", in: glyphRect)
        case .css:                   drawAsset(ctx: ctx, named: "AppSymbols/css", in: glyphRect)
        case .specialForces:         drawAsset(ctx: ctx, named: "AppSymbols/specialForces", in: glyphRect)
        }
    }

    private func drawInfantryX(ctx: GraphicsContext, affiliation: SymbolAffiliation, frame: CGRect) {
        var path = Path()
        switch affiliation {
        case .friend:
            // Diagonals corner-to-corner.
            path.move(to:    CGPoint(x: frame.minX, y: frame.minY))
            path.addLine(to: CGPoint(x: frame.maxX, y: frame.maxY))
            path.move(to:    CGPoint(x: frame.maxX, y: frame.minY))
            path.addLine(to: CGPoint(x: frame.minX, y: frame.maxY))
        case .hostile, .neutral:
            // Diamond frame — diagonal X clipped to the inscribed square
            // so it stays inside the rotated frame.
            let cx = frame.midX, cy = frame.midY
            let h  = frame.width / 4
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
    /// end OPENING OUTWARD (curves bulge away from the centre, flat sides
    /// face the centre). Shape:  (━━━━)
    private func drawMaintenance(ctx: GraphicsContext, in rect: CGRect) {
        let cy     = rect.midY
        let capR   = rect.height * 0.18
        let leftCx  = rect.minX + rect.width * 0.18 + capR
        let rightCx = rect.maxX - rect.width * 0.18 - capR
        // Shaft between the two caps.
        var shaft = Path()
        shaft.move(to:    CGPoint(x: leftCx,  y: cy))
        shaft.addLine(to: CGPoint(x: rightCx, y: cy))
        ctx.stroke(shaft, with: .color(.black), lineWidth: 2)
        // Left cap — semicircle bulging LEFT (away from centre).
        var lcap = Path()
        lcap.addArc(center: CGPoint(x: leftCx, y: cy),
                    radius: capR,
                    startAngle: .degrees(90),
                    endAngle:   .degrees(270),
                    clockwise: false)
        ctx.stroke(lcap, with: .color(.black), lineWidth: 2)
        // Right cap — semicircle bulging RIGHT (away from centre).
        var rcap = Path()
        rcap.addArc(center: CGPoint(x: rightCx, y: cy),
                    radius: capR,
                    startAngle: .degrees(-90),
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

    /// Fixed-wing aviation — air-screw: two FAT pointed lenses (filled)
    /// joined at the centre. Each lens has a sharp tip pointing outward
    /// and bulges through the vertical centreline.
    private func drawFixedWing(ctx: GraphicsContext, in rect: CGRect) {
        let cx = rect.midX, cy = rect.midY
        let halfW = rect.width  * 0.42
        let halfH = rect.height * 0.30
        // Left lens — outer tip at far-left, inner tip at centre.
        var left = Path()
        left.move(to: CGPoint(x: cx - halfW, y: cy))
        left.addQuadCurve(to: CGPoint(x: cx, y: cy),
                          control: CGPoint(x: cx - halfW * 0.5, y: cy - halfH))
        left.addQuadCurve(to: CGPoint(x: cx - halfW, y: cy),
                          control: CGPoint(x: cx - halfW * 0.5, y: cy + halfH))
        left.closeSubpath()
        ctx.fill(left, with: .color(.black))
        // Right lens — mirror of left.
        var right = Path()
        right.move(to: CGPoint(x: cx + halfW, y: cy))
        right.addQuadCurve(to: CGPoint(x: cx, y: cy),
                           control: CGPoint(x: cx + halfW * 0.5, y: cy - halfH))
        right.addQuadCurve(to: CGPoint(x: cx + halfW, y: cy),
                           control: CGPoint(x: cx + halfW * 0.5, y: cy + halfH))
        right.closeSubpath()
        ctx.fill(right, with: .color(.black))
    }

    /// Bridging — bowtie ━━━ bowtie: horizontal line through the centre
    /// with a small filled bowtie / hourglass shape at each end.
    private func drawBridging(ctx: GraphicsContext, in rect: CGRect) {
        let cy = rect.midY
        let bowW = rect.width  * 0.18
        let bowH = rect.height * 0.50
        let inset = rect.width * 0.08
        let leftBowCx  = rect.minX + inset + bowW / 2
        let rightBowCx = rect.maxX - inset - bowW / 2

        // Build an outlined hourglass (bowtie) centred at (cx, cy).
        func bowtie(_ cx: CGFloat) {
            let tl = CGPoint(x: cx - bowW/2, y: cy - bowH/2)
            let tr = CGPoint(x: cx + bowW/2, y: cy - bowH/2)
            let bl = CGPoint(x: cx - bowW/2, y: cy + bowH/2)
            let br = CGPoint(x: cx + bowW/2, y: cy + bowH/2)
            var p = Path()
            p.move(to: tl)
            p.addLine(to: br)
            p.addLine(to: bl)
            p.addLine(to: tr)
            p.closeSubpath()
            ctx.stroke(p, with: .color(.black), lineWidth: 1.5)
        }
        bowtie(leftBowCx)
        bowtie(rightBowCx)

        // Horizontal connecting line between the two bowtie waists.
        var line = Path()
        line.move(to:    CGPoint(x: leftBowCx,  y: cy))
        line.addLine(to: CGPoint(x: rightBowCx, y: cy))
        ctx.stroke(line, with: .color(.black), lineWidth: 2)
    }

    /// Radar — single continuous stroke: a small curved "hook" at the
    /// bottom (the dish) flowing into a lightning-bolt zigzag rising up
    /// and across the frame. One stylised shape, not two disconnected
    /// pieces.
    private func drawRadar(ctx: GraphicsContext, in rect: CGRect) {
        let bottomY = rect.maxY - rect.height * 0.18
        let topY    = rect.minY + rect.height * 0.18
        let leftX   = rect.minX + rect.width * 0.20
        var p = Path()
        // Start at the bottom of the dish hook.
        p.move(to: CGPoint(x: leftX + rect.width * 0.15, y: bottomY))
        // Curve up and to the LEFT, then around — the dish opens upward.
        p.addQuadCurve(to: CGPoint(x: leftX, y: rect.midY + rect.height * 0.05),
                       control: CGPoint(x: leftX, y: bottomY))
        // Continue with the lightning zigzag: up-right, sharp angle, up-right.
        p.addLine(to: CGPoint(x: rect.midX,                  y: rect.midY - rect.height * 0.05))
        p.addLine(to: CGPoint(x: rect.midX - rect.width * 0.08, y: rect.midY - rect.height * 0.18))
        p.addLine(to: CGPoint(x: rect.maxX - rect.width * 0.15, y: topY))
        ctx.stroke(p, with: .color(.black), lineWidth: 2)
    }

    /// CBRN defence — two crossed retorts: each is a FAT FILLED lens at
    /// ±45° with explicitly closed sub-paths so the fill actually renders.
    private func drawCBRN(ctx: GraphicsContext, in rect: CGRect) {
        let cx = rect.midX, cy = rect.midY
        let L = min(rect.width, rect.height) * 0.40   // half-length along axis
        let T = L * 0.55                              // half-thickness — much fatter

        func filledLens(axisDx: Double, axisDy: Double,
                        perpDx: Double, perpDy: Double) {
            let p0 = CGPoint(x: cx - axisDx * L, y: cy - axisDy * L)
            let p1 = CGPoint(x: cx + axisDx * L, y: cy + axisDy * L)
            // Pull the bezier control points well past the perpendicular
            // midpoint so the visible curve actually bulges out (a quad
            // bezier passes through only ¼ of its control offset).
            let ctrlA = CGPoint(x: cx + perpDx * T * 2, y: cy + perpDy * T * 2)
            let ctrlB = CGPoint(x: cx - perpDx * T * 2, y: cy - perpDy * T * 2)
            var p = Path()
            p.move(to: p0)
            p.addQuadCurve(to: p1, control: ctrlA)
            p.addQuadCurve(to: p0, control: ctrlB)
            p.closeSubpath()
            ctx.fill(p, with: .color(.black))
        }
        let s2 = 1.0 / 2.0.squareRoot()
        filledLens(axisDx:  s2, axisDy:  s2, perpDx:  s2, perpDy: -s2)
        filledLens(axisDx:  s2, axisDy: -s2, perpDx:  s2, perpDy:  s2)
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

    /// Render a bundled SVG asset (from AppSymbols) into the glyph rect.
    /// Asset is template-rendering so the black silhouette is preserved
    /// against the affiliation frame fill.
    private func drawAsset(ctx: GraphicsContext, named: String, in rect: CGRect) {
        let img = ctx.resolve(Image(named).renderingMode(.original))
        // Letterbox the asset inside the glyph rect, preserving aspect.
        let src = img.size                       // intrinsic size (from SVG)
        guard src.width > 0, src.height > 0 else { return }
        let scale = min(rect.width / src.width, rect.height / src.height)
        let w = src.width * scale
        let h = src.height * scale
        let dst = CGRect(x: rect.midX - w / 2,
                         y: rect.midY - h / 2,
                         width: w, height: h)
        ctx.draw(img, in: dst)
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
            let r = rect.height * 0.42   // was 0.35
            let ring = Path(ellipseIn: CGRect(x: cx - r, y: cy - r,
                                              width: r * 2, height: r * 2))
            ctx.stroke(ring, with: ink, lineWidth: 2)   // was 1.5
            var slash = Path()
            let s = r * 1.2
            slash.move(to:    CGPoint(x: cx - s, y: cy + s))
            slash.addLine(to: CGPoint(x: cx + s, y: cy - s))
            ctx.stroke(slash, with: ink, lineWidth: 2)  // was 1.5
        case .section:
            drawDots(count: 1, ctx: ctx, cx: cx, cy: cy,
                     radius: 3.2,
                     spacing: 0, ink: ink)
        case .platoon:
            drawDots(count: 3, ctx: ctx, cx: cx, cy: cy,
                     radius: 3.2,
                     spacing: 9,     // 2*r + breathing room
                     ink: ink)
        case .company:
            drawBars(count: 1, ctx: ctx, cx: cx, top: rect.minY + 1,
                     height: rect.height - 2,
                     barW: 3.2,      // was 2.5
                     spacing: 0, ink: ink)
        case .battalionRegiment:
            drawBars(count: 2, ctx: ctx, cx: cx, top: rect.minY + 1,
                     height: rect.height - 2,
                     barW: 3.2,      // was 2.5
                     spacing: 8,     // was 7
                     ink: ink)
        case .brigade:
            drawXs(count: 1, ctx: ctx, cx: cx, top: rect.minY + 2,
                   size: (rect.height - 4) * 0.85,   // was 0.70 with -6
                   spacing: 0, ink: ink)
        case .division:
            drawXs(count: 2, ctx: ctx, cx: cx, top: rect.minY + 2,
                   size: (rect.height - 4) * 0.85,   // was 0.70 with -6
                   spacing: 9,       // was 7
                   ink: ink)
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
