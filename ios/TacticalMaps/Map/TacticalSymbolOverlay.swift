import SwiftUI
import UIKit

/// A transparent overlay layered above `MapContainerView`. Renders
/// **every** waypoint (military, generic, and tactical control measure)
/// at the screen coordinate that `MapContainerView.Coordinator`
/// publishes on every camera change.
///
/// Implementation is pure UIKit (no SwiftUI / no `UIHostingController`)
/// for two reasons:
///
/// 1. **Bulletproof hit-testing.** `UIView.hitTest` returns the topmost
///    subview whose frame contains the point. Combined with our custom
///    `point(inside:)` that returns false for empty taps, touches that
///    miss every bubble pass straight through to the map below.
///
/// 2. **No gesture leakage.** An orphaned `UIHostingController` (one
///    whose view is in the hierarchy but isn't a proper child VC of
///    the containing view controller) installs its SwiftUI gestures on
///    the wrong responder chain and can intercept touches meant for
///    modals presented above it — that was the cause of the "can't
///    click Tasks segment / can't scroll picker" bug after the first
///    UIView rewrite. Native UIKit gesture recognizers don't leak.
struct TacticalSymbolOverlay: UIViewRepresentable {
    @ObservedObject var waypointStore: WaypointStore
    @ObservedObject var drawingStore: DrawingStore
    @ObservedObject var mapVM: MapViewModel
    @ObservedObject var visibility: LayerVisibility

    func makeUIView(context: Context) -> OverlayContainerView {
        let view = OverlayContainerView()
        view.backgroundColor = .clear
        view.isOpaque = false
        view.isUserInteractionEnabled = true
        return view
    }

    func updateUIView(_ view: OverlayContainerView, context: Context) {
        // Waypoints respect both the master toggle AND their assigned
        // layer's visibility. Hidden waypoints are filtered out before
        // the overlay sees them so their bubble views are torn down and
        // taps pass through the now-empty region.
        let visibleLayerIDs = Set(drawingStore.layers.filter { $0.visible }.map(\.id))
        let visibleWaypoints = waypointStore.waypoints.filter {
            visibleLayerIDs.contains($0.layerID)
        }
        view.update(
            waypoints: visibleWaypoints,
            positions: mapVM.waypointScreenPositions,
            zoomScale: mapVM.zoomScaleFactor,
            visible: visibility.waypointsVisible,
            unitLabelsVisible: visibility.unitLabelsVisible,
            taskLabelsVisible: visibility.taskLabelsVisible,
            selectedID: mapVM.selectedWaypointID,
            store: waypointStore,
            mapVM: mapVM
        )
    }
}

/// Container UIView that lays out one `BubbleView` per waypoint. Its
/// `hitTest` falls through to the map for taps that miss every
/// bubble's frame — this is what kills the click-hijack bug.
final class OverlayContainerView: UIView {
    private var bubbleViews: [UUID: BubbleView] = [:]
    /// Optional name labels rendered as a translucent pill under each
    /// bubble. Disabled via the Layers sheet's "Unit Labels" toggle.
    private var labelViews: [UUID: UILabel] = [:]

    /// The overlay is purely VISUAL — never claim any touch. Tap and
    /// long-press selection for symbols is dispatched from
    /// MapContainerView's own gesture recognisers, which hit-test
    /// against the published waypoint screen positions. Returning nil
    /// here means every gesture (tap, pan, pinch, rotation) falls
    /// straight through to MKMapView underneath, so a pinch that
    /// starts on a symbol still zooms the map.
    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        return nil
    }

    override func point(inside point: CGPoint, with event: UIEvent?) -> Bool {
        return false
    }

    func update(waypoints: [Waypoint],
                positions: [UUID: CGPoint],
                zoomScale: CGFloat,
                visible: Bool,
                unitLabelsVisible: Bool,
                taskLabelsVisible: Bool,
                selectedID: UUID?,
                store: WaypointStore,
                mapVM: MapViewModel) {
        let liveIDs: Set<UUID> = visible
            ? Set(waypoints.map { $0.id })
            : []
        // Drop bubbles for waypoints that disappeared.
        for (id, bub) in bubbleViews where !liveIDs.contains(id) {
            bub.removeFromSuperview()
            bubbleViews.removeValue(forKey: id)
        }
        // Decide per-waypoint whether a label should currently exist. A
        // waypoint earns a label slot only if its kind's toggle is on AND
        // the waypoint is otherwise visible — so toggling "Task Labels"
        // off doesn't yank "Unit Labels" along with it.
        let labelIDs: Set<UUID> = visible
            ? Set(waypoints.filter { wp in
                switch wp.kind {
                case .controlMeasure: return taskLabelsVisible
                case .military, .generic: return unitLabelsVisible
                }
            }.map(\.id))
            : []
        for (id, lbl) in labelViews where !labelIDs.contains(id) {
            lbl.removeFromSuperview()
            labelViews.removeValue(forKey: id)
        }
        guard visible else { return }

        for wp in waypoints {
            guard let pos = positions[wp.id] else { continue }
            let size = Self.bubbleSize(for: wp, zoomScale: zoomScale)
            let frame = CGRect(
                x: pos.x - size.width  / 2,
                y: pos.y - size.height / 2,
                width:  size.width,
                height: size.height
            )
            let isSelected = (wp.id == selectedID)
            if let existing = bubbleViews[wp.id] {
                // CRITICAL: skip frame updates while a bubble is being
                // dragged. Otherwise an unrelated re-render (location
                // ticks, etc.) would reset the bubble's frame to the
                // cached pre-drag screen point in the middle of every
                // drag — and the user sees the bubble snap back to its
                // origin while they're still holding it.
                if !existing.isDragging {
                    existing.frame = frame
                }
                existing.update(waypoint: wp, store: store, mapVM: mapVM)
                existing.setSelected(isSelected)
            } else {
                let bub = BubbleView(waypoint: wp,
                                     store: store,
                                     mapVM: mapVM)
                bub.frame = frame
                bub.setSelected(isSelected)
                addSubview(bub)
                bubbleViews[wp.id] = bub
            }

            // Position the optional name label — INSIDE the bubble for
            // tactical control measures (task graphics) so it sits within
            // the symbol's shape, BELOW the bubble for military / generic
            // waypoints.
            let wantsLabel: Bool = {
                switch wp.kind {
                case .controlMeasure: return taskLabelsVisible
                case .military, .generic: return unitLabelsVisible
                }
            }()
            if wantsLabel {
                let name = wp.name.trimmingCharacters(in: .whitespaces)
                if name.isEmpty {
                    if let stale = labelViews.removeValue(forKey: wp.id) {
                        stale.removeFromSuperview()
                    }
                } else {
                    let label = labelViews[wp.id] ?? Self.makeUnitLabel()
                    label.text = name
                    label.numberOfLines = 2
                    // Cap label content at ~110pt; wraps to a second line
                    // if needed so unit names that overshoot don't sprawl
                    // across neighbouring icons.
                    let maxContentWidth: CGFloat = 110
                    let fitted = label.sizeThatFits(
                        CGSize(width: maxContentWidth,
                               height: .greatestFiniteMagnitude)
                    )
                    let labelW = min(fitted.width,  maxContentWidth) + 10
                    let labelH = fitted.height + 4
                    label.bounds = CGRect(x: 0, y: 0, width: labelW, height: labelH)
                    switch wp.kind {
                    case .controlMeasure:
                        label.center = CGPoint(x: pos.x, y: pos.y)
                    case .military, .generic:
                        label.center = CGPoint(x: pos.x,
                                               y: frame.maxY + labelH / 2 + 2)
                    }
                    if label.superview == nil {
                        addSubview(label)
                    }
                    // Always raise the label above the bubble so task
                    // labels (which sit inside the graphic) aren't
                    // obscured by the bubble's stroke. Unit labels sit
                    // below the bubble anyway so this is a no-op there.
                    bringSubviewToFront(label)
                    labelViews[wp.id] = label
                }
            }
        }
    }

    private static func makeUnitLabel() -> UILabel {
        let label = UILabel()
        label.font = .systemFont(ofSize: 11, weight: .semibold)
        label.textColor = .white
        label.textAlignment = .center
        label.numberOfLines = 1
        label.backgroundColor = UIColor.black.withAlphaComponent(0.62)
        label.layer.cornerRadius = 4
        label.layer.cornerCurve = .continuous
        label.layer.masksToBounds = true
        label.isUserInteractionEnabled = false
        return label
    }

    /// Per-kind intrinsic bubble size. Tactical control measures can
    /// be stretched independently on each axis via `scaleX/scaleY`;
    /// military and generic glyphs are always square (their proportions
    /// carry meaning in APP-6C).
    static func bubbleSize(for wp: Waypoint, zoomScale: CGFloat) -> CGSize {
        switch wp.kind {
        case .controlMeasure:
            let w = max(8, 64 * CGFloat(wp.scaleX) * zoomScale)
            let h = max(8, 64 * CGFloat(wp.scaleY) * zoomScale)
            return CGSize(width: w, height: h)
        case .military(let spec):
            // Non-friend symbols use a taller canvas (diamondReserve = size * 0.22)
            // so the diamond/quatrefoil bottom vertex isn't clipped.
            let h: CGFloat = spec.affiliation == .friend ? 44 : 54
            return CGSize(width: 44, height: h)
        case .generic:
            return CGSize(width: 34, height: 34)
        }
    }
}

/// One waypoint's view. Pure UIKit:
///  - `UIImageView` holds the rendered glyph
///  - `CALayer` shadow gives the white halo
///  - `UITapGestureRecognizer` for select-on-tap
///  - `UILongPressGestureRecognizer` for press-and-drag-to-move
///
/// Hit-testing is two-stage: the container's `point(inside:)` filters
/// out taps outside our frame; our own `point(inside:)` further filters
/// out taps inside the SVG's transparent padding (so the corners of
/// e.g. an Assembly Area's bounding box pass through to the map).
final class BubbleView: UIView {
    private(set) var waypoint: Waypoint
    private weak var store: WaypointStore?
    private weak var mapVM: MapViewModel?

    private let imageView = UIImageView()
    private var dragStartScreenPoint: CGPoint?
    /// True between long-press recognition and release. While true the
    /// container leaves our frame alone — re-renders triggered by
    /// unrelated @Published changes (location ticks, etc.) would
    /// otherwise reset us to the cached pre-drag screen point and
    /// effectively cancel the drag mid-gesture.
    private(set) var isDragging: Bool = false

    init(waypoint: Waypoint, store: WaypointStore, mapVM: MapViewModel) {
        self.waypoint = waypoint
        self.store = store
        self.mapVM = mapVM
        super.init(frame: .zero)

        backgroundColor = .clear
        isOpaque = false
        clipsToBounds = false
        // Bubbles are PURELY VISUAL. All tap / long-press handling
        // lives in MapContainerView so MKMapView's pinch + pan + tap
        // recognisers own the entire gesture chain — pinches that
        // start on a symbol still zoom the map.
        isUserInteractionEnabled = false

        // .scaleToFill (NOT .scaleAspectFit) so non-uniform bubble
        // frames actually stretch the symbol — a control measure with
        // scaleX=2, scaleY=1 needs to look 2× as wide, not just sit
        // letterboxed inside a wider frame. Military/generic glyphs
        // have square frames so this is a no-op for them.
        imageView.contentMode = .scaleToFill
        imageView.isUserInteractionEnabled = false
        addSubview(imageView)

        // Triple-stacked shadow approximates the soft white halo the
        // SwiftUI version produced via three `.shadow(...)` layers.
        // The shadow lives on the image view's layer so it follows the
        // glyph if we ever animate (drag scale-up).
        let shadow = imageView.layer
        shadow.shadowColor   = UIColor.white.cgColor
        shadow.shadowOpacity = 1.0
        shadow.shadowRadius  = 2.0
        shadow.shadowOffset  = .zero
        shadow.masksToBounds = false

        // No gesture recognisers — tap / long-press are dispatched
        // from MapContainerView's own recognisers which hit-test
        // against waypoint screen positions.

        refreshImage()
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    func update(waypoint: Waypoint, store: WaypointStore, mapVM: MapViewModel) {
        let kindChanged = waypoint.kind != self.waypoint.kind
        let rotationChanged = waypoint.rotation != self.waypoint.rotation
        self.waypoint = waypoint
        self.store = store
        self.mapVM = mapVM
        if kindChanged || rotationChanged {
            refreshImage()
        }
    }

    /// Bright orange halo when the controls card for this waypoint is
    /// open. Toggles the imageView's CALayer shadow rather than adding
    /// a separate subview so the glow tracks the symbol's pixel-perfect
    /// outline.
    func setSelected(_ selected: Bool) {
        let layer = imageView.layer
        if selected {
            // Orange CALayer shadow on the icon — a glow that follows the
            // symbol's exact alpha outline (rectangle / hexagon / arc / …).
            // (A separate pre-blurred "glow image" used to live behind the
            // icon, but it was baked at a different scale than the crisp
            // icon, so its embedded glyph peeked out and read as a second
            // symbol on thin/open graphics like Form-Up Point.)
            layer.shadowColor   = UIColor(red: 1, green: 0.65, blue: 0.18, alpha: 1).cgColor
            layer.shadowOpacity = 1.0
            layer.shadowRadius  = 12.0
            layer.shadowOffset  = .zero
            UIView.animate(withDuration: 0.15) {
                self.imageView.transform = CGAffineTransform(scaleX: 1.10, y: 1.10)
            }
        } else {
            layer.shadowColor   = UIColor.white.cgColor
            layer.shadowOpacity = 1.0
            layer.shadowRadius  = 2.0
            layer.shadowOffset  = .zero
            UIView.animate(withDuration: 0.15) {
                self.imageView.transform = .identity
            }
        }
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        imageView.frame = bounds
    }

    // MARK: - Rendering

    @MainActor
    private func refreshImage() {
        switch waypoint.kind {
        case .controlMeasure(let measure):
            imageView.image = TacticalControlMeasureRenderer.image(
                for: measure,
                rotation: waypoint.rotation
            )
        case .military(let spec):
            imageView.image = MilitarySymbolRenderer.image(for: spec, size: 44)
        case .generic:
            imageView.image = Self.genericImage()
        }
    }

    private static var genericImageCache: UIImage?
    private static func genericImage() -> UIImage? {
        if let c = genericImageCache { return c }
        let size = CGSize(width: 34, height: 34)
        let renderer = UIGraphicsImageRenderer(size: size)
        let img = renderer.image { ctx in
            UIColor.systemYellow.setFill()
            UIBezierPath(ovalIn: CGRect(origin: .zero, size: size)).fill()
            let glyph = UIImage(systemName: "mappin",
                                withConfiguration: UIImage.SymbolConfiguration(pointSize: 18,
                                                                               weight: .semibold))?
                .withTintColor(.white, renderingMode: .alwaysOriginal)
            if let glyph {
                let r = CGRect(
                    x: (size.width  - glyph.size.width)  / 2,
                    y: (size.height - glyph.size.height) / 2,
                    width:  glyph.size.width,
                    height: glyph.size.height
                )
                glyph.draw(in: r)
            }
        }
        genericImageCache = img
        return img
    }

    // MARK: - Gestures

    @objc private func handleTap() {
        mapVM?.selectedWaypointID = waypoint.id
    }

    /// Static helper used by MapContainerView's tap handler to decide
    /// whether a tap at the given local point hits this bubble's
    /// visible symbol (control-measure alpha mask) or just its frame
    /// rectangle (military / generic).
    func containsVisiblePoint(_ point: CGPoint) -> Bool {
        guard bounds.contains(point) else { return false }
        guard case .controlMeasure(let measure) = waypoint.kind else {
            return true
        }
        let normalized = CGPoint(
            x: point.x / max(bounds.width,  1),
            y: point.y / max(bounds.height, 1)
        )
        return TacticalControlMeasureAlphaMask.containsInVisibleBounds(
            measure: measure,
            rotation: waypoint.rotation,
            normalizedPoint: normalized
        )
    }

    @objc private func handlePress(_ recognizer: UILongPressGestureRecognizer) {
        guard let mapVM = mapVM,
              let store = store,
              let originalPos = mapVM.waypointScreenPositions[waypoint.id]
        else { return }

        // The recognizer's `location(in:)` is in OUR coordinate space.
        // Compose with the bubble's frame origin to get a screen-space
        // point we can hand to mapVM.screenToCoordinate.
        let local = recognizer.location(in: self)
        let containerSpace = convert(local, to: superview)

        switch recognizer.state {
        case .began:
            isDragging = true
            dragStartScreenPoint = containerSpace
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            // Visual "I'm holding this" affordance — scale up + drop the
            // halo a touch.
            UIView.animate(withDuration: 0.12) {
                self.imageView.transform = CGAffineTransform(scaleX: 1.08, y: 1.08)
            }
        case .changed:
            // Move the bubble live as the user drags. The container
            // skips frame updates for us while isDragging is true, so
            // we don't get reset by unrelated re-renders.
            guard let start = dragStartScreenPoint else { return }
            let dx = containerSpace.x - start.x
            let dy = containerSpace.y - start.y
            let centre = CGPoint(x: originalPos.x + dx,
                                 y: originalPos.y + dy)
            let size = bounds.size
            frame = CGRect(
                x: centre.x - size.width  / 2,
                y: centre.y - size.height / 2,
                width:  size.width,
                height: size.height
            )
        case .ended, .cancelled, .failed:
            defer {
                isDragging = false
                dragStartScreenPoint = nil
                UIView.animate(withDuration: 0.12) {
                    self.imageView.transform = .identity
                }
            }
            guard recognizer.state == .ended,
                  let convert = mapVM.screenToCoordinate else { return }
            let centre = CGPoint(x: frame.midX, y: frame.midY)
            let newCoord = convert(centre)
            // Update the cached screen position SYNCHRONOUSLY before
            // store.update fires its @Published change. Without this,
            // the SwiftUI re-render triggered by store.update reads
            // the stale `mapVM.waypointScreenPositions` (the async
            // `publishOverlayState` hasn't run yet) and snaps the
            // bubble back to its pre-drag screen point — only to
            // jump to the correct point a frame later. Setting it
            // here means OverlayContainerView.update sees the right
            // value on the very first re-render, no snap-back.
            mapVM.waypointScreenPositions[waypoint.id] = centre
            var updated = waypoint
            updated.latitude  = newCoord.latitude
            updated.longitude = newCoord.longitude
            store.update(updated)
        default:
            break
        }
    }
}

/// Per-symbol visible-bounds cache. For each (measure, rotation), we
/// render the glyph once into a small alpha-only bitmap and compute
/// the tight bounding rect of its visible pixels (normalized to 0..1).
/// `BubbleView.point(inside:)` then accepts taps that land within
/// that rect — which gives the user a hit-region matching the visible
/// shape of the symbol, not the SVG's square viewBox.
///
/// For outline-only shapes (e.g. AA's empty circle), the bounding box
/// of the visible stroke equals the circle's enclosing square — so a
/// tap on the empty interior still counts as a hit, matching what the
/// user perceives as "the symbol."
@MainActor
enum TacticalControlMeasureAlphaMask {
    /// Bitmap resolution used to compute the bounding rect. 64 cells
    /// per side gives sub-pixel accuracy at the canonical 64pt size.
    static let resolution: Int = 64

    private struct Key: Hashable {
        let measure: TacticalControlMeasure
        let rotationCentideg: Int
    }
    private static var boundsCache: [Key: CGRect] = [:]

    static func containsInVisibleBounds(measure: TacticalControlMeasure,
                                        rotation: Double,
                                        normalizedPoint p: CGPoint) -> Bool {
        let normalized = ((rotation.truncatingRemainder(dividingBy: 360)) + 360)
            .truncatingRemainder(dividingBy: 360)
        let key = Key(
            measure: measure,
            rotationCentideg: Int((normalized * 100).rounded())
        )
        let rect = boundsCache[key]
            ?? Self.computeAndCache(measure: measure,
                                    rotation: normalized,
                                    key: key)
        if rect.isNull { return true }   // fail-open
        let inset = -0.03   // 3% outward forgiveness
        return rect.insetBy(dx: inset, dy: inset).contains(p)
    }

    private static func computeAndCache(measure: TacticalControlMeasure,
                                        rotation: Double,
                                        key: Key) -> CGRect {
        let view = TacticalControlMeasureSymbolView(
            measure: measure,
            rotation: rotation,
            size: CGFloat(resolution)
        )
        let renderer = ImageRenderer(content: view)
        renderer.scale = 1.0
        guard let cgImage = renderer.uiImage?.cgImage else {
            boundsCache[key] = .null
            return .null
        }
        var pixels = [UInt8](repeating: 0,
                             count: resolution * resolution)
        let colorSpace = CGColorSpaceCreateDeviceGray()
        guard let ctx = CGContext(
            data: &pixels,
            width: resolution,
            height: resolution,
            bitsPerComponent: 8,
            bytesPerRow: resolution,
            space: colorSpace,
            bitmapInfo: CGImageAlphaInfo.alphaOnly.rawValue
        ) else {
            boundsCache[key] = .null
            return .null
        }
        ctx.draw(cgImage, in: CGRect(x: 0, y: 0,
                                     width: resolution, height: resolution))
        var minX = resolution, minY = resolution, maxX = -1, maxY = -1
        for y in 0..<resolution {
            for x in 0..<resolution {
                if pixels[y * resolution + x] > 12 {
                    if x < minX { minX = x }
                    if x > maxX { maxX = x }
                    if y < minY { minY = y }
                    if y > maxY { maxY = y }
                }
            }
        }
        let rect: CGRect
        if maxX < minX || maxY < minY {
            rect = .zero
        } else {
            let r = CGFloat(resolution)
            rect = CGRect(
                x: CGFloat(minX) / r,
                y: CGFloat(minY) / r,
                width:  CGFloat(maxX - minX + 1) / r,
                height: CGFloat(maxY - minY + 1) / r
            )
        }
        boundsCache[key] = rect
        return rect
    }
}
