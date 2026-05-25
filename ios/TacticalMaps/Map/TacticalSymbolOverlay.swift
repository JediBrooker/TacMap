import SwiftUI

/// A transparent SwiftUI overlay layered above `MapContainerView`.
/// Renders **every** waypoint (military, generic, and tactical
/// control measure) as a live SwiftUI view, positioned by the screen
/// coordinate that `MapContainerView.Coordinator` publishes on every
/// camera change.
///
/// Why not MKAnnotationView? Every halo / click-hijack / scale issue
/// was caused by MKMapView's annotation pipeline. Plus, with the
/// overlay sitting above the map, MKMapView's `isDraggable` was no
/// longer firing reliably — gestures were getting intercepted by the
/// overlay even where the overlay had no content. Routing everything
/// through the overlay gives us consistent tap + long-press-drag
/// across all symbol kinds.
struct TacticalSymbolOverlay: View {
    @ObservedObject var waypointStore: WaypointStore
    @ObservedObject var mapVM: MapViewModel
    @ObservedObject var visibility: LayerVisibility

    var body: some View {
        ZStack(alignment: .topLeading) {
            Color.clear.allowsHitTesting(false)

            if visibility.waypointsVisible {
                ForEach(waypointStore.waypoints, id: \.id) { wp in
                    if let pos = mapVM.waypointScreenPositions[wp.id] {
                        SymbolBubble(
                            waypoint: wp,
                            zoomScale: mapVM.zoomScaleFactor,
                            store: waypointStore,
                            mapVM: mapVM
                        )
                        .position(x: pos.x, y: pos.y)
                    }
                }
            }
        }
    }
}

/// One waypoint with its halo, tap, and long-press-drag gesture.
/// Kind-specific rendering happens inside `glyph` — everything else
/// (gestures, halo, drag offset) is shared.
private struct SymbolBubble: View {
    let waypoint: Waypoint
    let zoomScale: CGFloat
    @ObservedObject var store: WaypointStore
    @ObservedObject var mapVM: MapViewModel

    @State private var dragOffset: CGSize = .zero
    @State private var isDragging: Bool = false

    var body: some View {
        glyph
            // Real SwiftUI .shadow — works at any size, vector-crisp,
            // not subject to MKMapView's layer clipping.
            .shadow(color: .white, radius: 2)
            .shadow(color: .white, radius: 2)
            .shadow(color: .white, radius: 1.5)
            .scaleEffect(isDragging ? 1.08 : 1.0)
            .offset(dragOffset)
            .animation(.easeOut(duration: 0.12), value: isDragging)
            .onTapGesture {
                mapVM.selectedWaypointID = waypoint.id
            }
            // Long-press + drag (iOS-native draggable-annotation
            // pattern). A regular tap or a pinch fails the long-press
            // immediately so the touch passes through to MKMapView
            // for pan/zoom. Only an intentional 0.35s hold engages
            // drag.
            .gesture(
                LongPressGesture(minimumDuration: 0.35)
                    .sequenced(before: DragGesture(minimumDistance: 0))
                    .onChanged { value in
                        switch value {
                        case .first(true):
                            if !isDragging {
                                isDragging = true
                                UIImpactFeedbackGenerator(style: .medium)
                                    .impactOccurred()
                            }
                        case .second(true, let drag):
                            if let drag = drag {
                                dragOffset = drag.translation
                            }
                        default:
                            break
                        }
                    }
                    .onEnded { value in
                        defer {
                            isDragging = false
                            dragOffset = .zero
                        }
                        guard case .second(true, let drag?) = value,
                              let originalPos = mapVM.waypointScreenPositions[waypoint.id],
                              let convert = mapVM.screenToCoordinate
                        else { return }
                        let newScreenPoint = CGPoint(
                            x: originalPos.x + drag.translation.width,
                            y: originalPos.y + drag.translation.height
                        )
                        let newCoord = convert(newScreenPoint)
                        var updated = waypoint
                        updated.latitude  = newCoord.latitude
                        updated.longitude = newCoord.longitude
                        store.update(updated)
                    }
            )
    }

    /// Per-kind glyph. Tactical control measures scale with zoom (the
    /// `scale × zoomScale` formula). Military symbols are fixed-size
    /// on screen — they identify a unit, they're not geographic
    /// footprints. Generic waypoints are SF Symbol pins.
    @ViewBuilder
    private var glyph: some View {
        switch waypoint.kind {
        case .controlMeasure(let measure):
            let baseSize: CGFloat = 64
            let displaySize = max(
                8,
                baseSize * CGFloat(waypoint.scale) * zoomScale
            )
            TacticalControlMeasureSymbolView(
                measure: measure,
                rotation: waypoint.rotation,
                size: displaySize
            )

        case .military(let spec):
            // Render at a fixed pixel size on screen — unit symbols
            // identify a unit, not a geographic footprint.
            MilitarySymbolView(spec: spec, size: 44)

        case .generic:
            // Teardrop pin replacement: a simple coloured circle
            // with an SF Symbol glyph. Good enough for v1.
            ZStack {
                Circle()
                    .fill(waypoint.kind.tint)
                    .frame(width: 34, height: 34)
                Image(systemName: waypoint.kind.sfSymbol)
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(.white)
            }
        }
    }
}
