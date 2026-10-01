import SwiftUI

/// The crosshair while calibrating (s7.6): thin white lines with a black halo
/// and a 6 pt open gap in the middle, no ring or glow, so the printed grid
/// intersection underneath stays visible.
struct CalibrationReticle: View {
    var arm: CGFloat = 26
    var gap: CGFloat = 3

    var body: some View {
        Canvas { ctx, size in
            let c = CGPoint(x: size.width / 2, y: size.height / 2)
            var path = Path()
            for (dx, dy) in [(1.0, 0.0), (-1.0, 0.0), (0.0, 1.0), (0.0, -1.0)] {
                path.move(to: CGPoint(x: c.x + dx * gap, y: c.y + dy * gap))
                path.addLine(to: CGPoint(x: c.x + dx * (gap + arm), y: c.y + dy * (gap + arm)))
            }
            ctx.stroke(path, with: .color(.black), lineWidth: 3)
            ctx.stroke(path, with: .color(.white), lineWidth: 1)
        }
        .frame(width: (arm + gap) * 2 + 4, height: (arm + gap) * 2 + 4)
        .accessibilityHidden(true)
    }
}
