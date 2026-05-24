import SwiftUI

/// Top-left hamburger button. Opens a Menu containing every secondary tool:
/// Search, Waypoints, Drawings, Layers, Import PDF Map, Export GeoJSON.
struct HamburgerMenu: View {
    let onSearch:    () -> Void
    let onWaypoints: () -> Void
    let onDrawings:  () -> Void
    let onLayers:    () -> Void
    let onImport:    () -> Void
    let onExport:    () -> Void
    let onAbout:     () -> Void

    var body: some View {
        Menu {
            Section {
                Button { onSearch() } label: {
                    Label("Search…", systemImage: "magnifyingglass")
                }
            }
            Section("Layers & data") {
                Button { onWaypoints() } label: { Label("Symbology", systemImage: "mappin.and.ellipse") }
                Button { onDrawings()  } label: { Label("Drawings",  systemImage: "scribble.variable") }
                Button { onLayers()    } label: { Label("Layers",    systemImage: "square.3.stack.3d") }
            }
            Section("Maps") {
                Button { onImport() } label: { Label("Import PDF Map…", systemImage: "doc.badge.plus") }
            }
            Section {
                Button { onExport() } label: { Label("Export GeoJSON…", systemImage: "square.and.arrow.up") }
            }
            Section {
                Button { onAbout() } label: { Label("About & Credits", systemImage: "info.circle") }
            }
        } label: {
            Image(systemName: "line.3.horizontal")
                .font(.system(size: 17, weight: .medium))
                .frame(width: 42, height: 42)
                .background(.black.opacity(0.78), in: Circle())
                .overlay(Circle().stroke(.white.opacity(0.08)))
                .foregroundStyle(.white)
        }
    }
}

/// Top-right compass chip. Rotates the N marker live with the map's heading
/// (so N always points to real-world north) and shows the heading as a
/// NATO-mil reading (6400 mils per full circle) in the lower half.
/// Tap the chip to smooth-animate the map back to heading = 0°.
struct CompassChip: View {
    /// Map heading in degrees (0 = north-up, 90 = east-up).
    let heading: Double
    /// Triggered when the user taps the chip.
    let onTap: () -> Void

    private let size: CGFloat = 56

    /// NATO mils: 6400 per full circle (1° ≈ 17.78 mils). N=0000, E=1600,
    /// S=3200, W=4800. Wraps via modulo so a brief reading of 6400 displays 0000.
    private var milsString: String {
        let positive = ((heading.truncatingRemainder(dividingBy: 360.0)) + 360.0)
            .truncatingRemainder(dividingBy: 360.0)
        let mils = positive * (6400.0 / 360.0)
        let rounded = Int(round(mils)) % 6400
        return String(format: "%04d", rounded)
    }

    var body: some View {
        Button(action: onTap) {
            ZStack {
                Circle().fill(.black.opacity(0.82))
                    .frame(width: size, height: size)
                Circle().stroke(.white.opacity(0.14), lineWidth: 1)
                    .frame(width: size, height: size)

                // ----- Rotating N marker (orbits the compass centre) -----
                // Triangle tick at the top edge.
                VStack(spacing: 0) {
                    Image(systemName: "triangle.fill")
                        .font(.system(size: 7))
                        .foregroundStyle(.red)
                        .padding(.top, 3)
                    Spacer()
                }
                .frame(width: size, height: size)
                .rotationEffect(.degrees(-heading))
                .animation(.linear(duration: 0.05), value: heading)

                // Letter N below the triangle, also rotates.
                VStack(spacing: 0) {
                    Spacer().frame(height: 11)
                    Text("N")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(.white)
                    Spacer()
                }
                .frame(width: size, height: size)
                .rotationEffect(.degrees(-heading))
                .animation(.linear(duration: 0.05), value: heading)

                // ----- Static mils readout (always upright, easy to read) -----
                VStack(spacing: 0) {
                    Spacer()
                    Text(milsString)
                        .font(.system(size: 11, weight: .bold, design: .monospaced))
                        .foregroundStyle(Color(red: 0.55, green: 0.95, blue: 0.55))
                        .padding(.bottom, 5)
                }
                .frame(width: size, height: size)

                // Thin separator between the rotating face and the digit panel.
                Rectangle()
                    .fill(.white.opacity(0.10))
                    .frame(width: size * 0.55, height: 0.5)
                    .offset(y: 4)
            }
            .frame(width: size, height: size)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Map heading \(milsString) mils")
        .accessibilityHint(heading == 0
            ? "Map already north-up"
            : "Tap to reset to north (currently \(Int(heading))°)")
    }
}
