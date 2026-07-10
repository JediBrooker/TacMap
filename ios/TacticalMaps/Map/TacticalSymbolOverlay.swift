import SwiftUI
import UIKit

/// Transparent overlay above `MapContainerView`. Renders all waypoints
/// (military, generic, control measures) at screen coords published by
/// the coordinator on every camera change.
///
/// Pure UIKit, no SwiftUI hosting, b/c:
/// - UIView.hitTest gives us proper hit-testing. Taps that miss every
///   bubble fall through to the map.
/// - No gesture leakage. UIHostingController that isn't a proper child VC
///   installs gestures on the wrong responder chain and intercepts touches
///   meant for modals above it - that was the culprit behind the "can't
///   click Tasks segment / can't scroll picker" bug. Native UIKit
///   gesture recognizers don't have this problem.
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
        // Filter waypoints by master toggle + per-layer visibility.
        // Hidden ones get their bubble views torn down so taps fall
        // through the empty region.
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

/// Container UIView, one `BubbleView` per waypoint. hitTest falls
/// through to map for taps that miss every bubble - this is what
/// killed the click-hijack bug.
final class OverlayContainerView: UIView {
    private var bubbleViews: [UUID: BubbleView] = [:]
    /// Name labels (translucent pill under each bubble). Toggled off
    /// via the Layers sheet "Unit Labels" switch.
    private var labelViews: [UUID: UILabel] = [:]

    /// Purely visual - never claim any touch. Tap/long-press selection
    /// is dispatched from MapContainerView's own gesture recognisers
    /// which hit-test against waypoint screen positions. Returning nil
    /// means all gestures fall through to MKMapView, so pinch on a
    /// symbol still zooms the map.
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
        // Per-waypoint label visibility. A waypoint gets a label only
        // if its kind's toggle is on AND the waypoint is visible, so
        // toggling "Task Labels" off doesn't nuke "Unit Labels" too.
        let labelIDs: Set<UUID> = visible
            ? Set(waypoints.filter { wp in
                switch wp.kind {
                case .controlMeasure: return taskLabelsVisible
                case .military, .generic, .marker: return unitLabelsVisible
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
                // CRITICAL: don't update frame while dragging. Otherwise
                // unrelated re-renders (location ticks etc.) reset the
                // bubble to its pre-drag screen point mid-drag and the
                // user sees it snap back to the origin.
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

            // Name label goes INSIDE the bubble for task graphics (control
            // measures) so it sits within the symbol shape, and BELOW the
            // bubble for military / generic waypoints.
            let wantsLabel: Bool = {
                switch wp.kind {
                case .controlMeasure: return taskLabelsVisible
                case .military, .generic, .marker: return unitLabelsVisible
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
                    // Cap at ~110pt, wraps to 2nd line so long unit names
                    // don't sprawl across neighbouring icons.
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
                    case .military, .generic, .marker:
                        label.center = CGPoint(x: pos.x,
                                               y: frame.maxY + labelH / 2 + 2)
                    }
                    if label.superview == nil {
                        addSubview(label)
                    }
                    // Raise label above bubble so task labels (inside the
                    // graphic) aren't hidden by the bubble stroke. No-op
                    // for unit labels since they sit below anyway.
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

    /// Bubble size per waypoint kind. Control measures stretch
    /// independently on each axis via scaleX/scaleY; military and
    /// generic glyphs are always square (proportions matter in APP-6C).
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
        case .marker:
            return CGSize(width: 34, height: 34)
        }
    }
}

/// Single waypoint view. Pure UIKit - UIImageView for glyph, CALayer
/// shadow for white halo, tap to select, long-press to drag.
///
/// Two-stage hit testing: container filters taps outside our frame,
/// then we filter taps in the SVG's transparent padding (so corners
/// of e.g. Assembly Area bbox pass through to map).
final class BubbleView: UIView {
    private(set) var waypoint: Waypoint
    private weak var store: WaypointStore?
    private weak var mapVM: MapViewModel?

    private let imageView = UIImageView()
    private var dragStartScreenPoint: CGPoint?
    /// True while user is dragging. Container skips frame updates
    /// when this is set, otherwise unrelated @Published re-renders
    /// would snap us back to the pre-drag screen point and basically
    /// cancel the drag mid-gesture.
    private(set) var isDragging: Bool = false

    init(waypoint: Waypoint, store: WaypointStore, mapVM: MapViewModel) {
        self.waypoint = waypoint
        self.store = store
        self.mapVM = mapVM
        super.init(frame: .zero)

        backgroundColor = .clear
        isOpaque = false
        clipsToBounds = false
        // Purely visual, no interaction. All tap/long-press handling
        // lives in MapContainerView so MKMapView's gesture recognisers
        // own the chain - pinches starting on a symbol still zoom.
        isUserInteractionEnabled = false

        // scaleToFill, NOT scaleAspectFit - non-uniform bubble frames
        // need to actually stretch the symbol. A control measure with
        // scaleX=2 scaleY=1 should look 2x wide, not letterboxed.
        // Military/generic have square frames so doesn't matter there.
        imageView.contentMode = .scaleToFill
        imageView.isUserInteractionEnabled = false
        addSubview(imageView)

        // Shadow approximates the white halo from the old SwiftUI
        // version (three .shadow layers). Lives on the image view's
        // layer so it follows the glyph during animations.
        let shadow = imageView.layer
        shadow.shadowColor   = UIColor.white.cgColor
        shadow.shadowOpacity = 1.0
        shadow.shadowRadius  = 2.0
        shadow.shadowOffset  = .zero
        shadow.masksToBounds = false

        // No gesture recognisers here - tap/long-press dispatched
        // from MapContainerView which hit-tests against waypoint
        // screen positions.

        refreshImage()
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    func update(waypoint: Waypoint, store: WaypointStore, mapVM: MapViewModel) {
        let kindChanged = waypoint.kind != self.waypoint.kind
        let rotationChanged = waypoint.rotation != self.waypoint.rotation
        let colorChanged = waypoint.taskColor != self.waypoint.taskColor
        self.waypoint = waypoint
        self.store = store
        self.mapVM = mapVM
        if kindChanged || rotationChanged || colorChanged {
            refreshImage()
        }
    }

    /// Orange halo when controls card is open. Toggles the imageView's
    /// CALayer shadow instead of a seperate subview so the glow follows
    /// the symbol's outline exactly.
    func setSelected(_ selected: Bool) {
        let layer = imageView.layer
        if selected {
            // Orange glow via CALayer shadow - follows the symbol's alpha
            // outline. (We used to have a pre-blurred "glow image" behind
            // the icon but it was baked at a different scale and the glyph
            // peeked through, looked like a second symbol on thin graphics
            // like Form-Up Point.)
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
                rotation: waypoint.rotation,
                color: waypoint.taskColor
            )
            // Black line art needs a strong white halo to stay legible
            // on dark satellite imagery.
            imageView.layer.shadowColor = UIColor.white.cgColor
            imageView.layer.shadowRadius = 5.0
            imageView.layer.shadowOpacity = 1.0
        case .military(let spec):
            imageView.image = MilitarySymbolRenderer.image(for: spec, size: 44)
        case .generic:
            imageView.image = Self.genericImage()
        case .marker(let mk):
            imageView.image = MarkerSymbolRenderer.image(for: mk, size: 34)
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

    /// Used by MapContainerView tap handler to check if a tap hits the
    /// visible symbol (alpha mask for control measures) or just the
    /// frame rect (military/generic always return true).
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

        // Recognizer location is in our coord space, convert to
        // screen-space for mapVM.screenToCoordinate.
        let local = recognizer.location(in: self)
        let containerSpace = convert(local, to: superview)

        switch recognizer.state {
        case .began:
            isDragging = true
            dragStartScreenPoint = containerSpace
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            // "I'm holding this" feedback - scale up slightly.
            UIView.animate(withDuration: 0.12) {
                self.imageView.transform = CGAffineTransform(scaleX: 1.08, y: 1.08)
            }
        case .changed:
            // Move bubble live during drag. Container skips our frame
            // updates while isDragging so we don't get reset.
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
            // Set cached screen pos SYNCHRONOUSLY before store.update
            // fires @Published. Without this the SwiftUI re-render
            // reads stale waypointScreenPositions (async publish hasn't
            // run yet) and snaps the bubble back to pre-drag point,
            // then jumps to the correct spot a frame later. Ugly.
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

/// Per-symbol visible-bounds cache. For each (measure, rotation) we
/// render into a small alpha-only bitmap and compute the tight bbox of
/// visible pixels (normalized 0..1). BubbleView uses this for hit
/// testing so taps match the visible shape, not the SVG's square viewBox.
///
/// For outline-only shapes (e.g. AA's empty circle) the bbox of the
/// stroke = the enclosing square, so tapping the empty interior still
/// counts as a hit. Thats what the user expects.
@MainActor
enum TacticalControlMeasureAlphaMask {
    /// Bitmap resolution for computing bounding rect. 64 cells per
    /// side is plenty of accuracy at canonical 64pt size.
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
