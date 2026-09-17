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
            unitAmplifiersVisible: visibility.unitAmplifiersVisible,
            taskLabelsVisible: visibility.taskLabelsVisible,
            selectedID: mapVM.selectedWaypointID
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
    private var amplifierViews: [UUID: UnitAmplifierLabelsView] = [:]

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
                unitAmplifiersVisible: Bool,
                taskLabelsVisible: Bool,
                selectedID: UUID?) {
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
        let amplifierIDs: Set<UUID> = visible
            ? Set(waypoints.compactMap { waypoint in
                UnitAmplifierPresentation(
                    waypoint: waypoint, visible: unitAmplifiersVisible) == nil
                    ? nil : waypoint.id
            })
            : []
        for (id, view) in amplifierViews where !amplifierIDs.contains(id) {
            view.removeFromSuperview()
            amplifierViews.removeValue(forKey: id)
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
                existing.frame = frame
                existing.update(waypoint: wp)
                existing.setSelected(isSelected)
            } else {
                let bub = BubbleView(waypoint: wp)
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

            if let presentation = UnitAmplifierPresentation(
                waypoint: wp, visible: unitAmplifiersVisible) {
                let amplifierView = amplifierViews[wp.id] ?? UnitAmplifierLabelsView()
                amplifierView.configure(
                    presentation: presentation,
                    symbolSize: size
                )
                amplifierView.bounds = CGRect(
                    origin: .zero,
                    size: UnitAmplifierLabelsView.canvasSize
                )
                amplifierView.center = pos
                if amplifierView.superview == nil { addSubview(amplifierView) }
                bringSubviewToFront(amplifierView)
                amplifierViews[wp.id] = amplifierView
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

/// FM 1-02.2 unit amplifier layout, kept separate from the existing waypoint
/// name pill. Field F is upper-right, T bottom-left, and M bottom-right.
final class UnitAmplifierLabelsView: UIView {
    static let canvasSize = CGSize(width: 350, height: 100)

    private let fieldF = UnitAmplifierLabelsView.makeLabel(alignment: .left)
    private let fieldT = UnitAmplifierLabelsView.makeLabel(alignment: .right)
    private let fieldM = UnitAmplifierLabelsView.makeLabel(alignment: .left)
    private var symbolSize = CGSize(width: 44, height: 44)

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        isOpaque = false
        isUserInteractionEnabled = false
        clipsToBounds = false
        addSubview(fieldF)
        addSubview(fieldT)
        addSubview(fieldM)
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    func configure(presentation: UnitAmplifierPresentation, symbolSize: CGSize) {
        self.symbolSize = symbolSize
        Self.set(presentation.reinforcementText, on: fieldF)
        Self.set(presentation.uniqueIdentifier, on: fieldT)
        Self.set(presentation.higherFormation, on: fieldM)
        setNeedsLayout()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let centreX = bounds.midX
        let centreY = bounds.midY
        let symbolHalfWidth = symbolSize.width / 2
        let symbolHalfHeight = symbolSize.height / 2
        let sideWidth: CGFloat = 145
        let gap: CGFloat = 5
        let fieldFSize = fittedSize(for: fieldF, maximumWidth: 55)
        let fieldTSize = fittedSize(for: fieldT, maximumWidth: sideWidth)
        let fieldMSize = fittedSize(for: fieldM, maximumWidth: sideWidth)

        fieldF.frame = CGRect(
            x: centreX + symbolHalfWidth + gap,
            y: centreY - symbolHalfHeight - 3,
            width: fieldFSize.width,
            height: fieldFSize.height
        )
        fieldT.frame = CGRect(
            x: centreX - symbolHalfWidth - gap - fieldTSize.width,
            y: centreY + symbolHalfHeight - fieldTSize.height + 3,
            width: fieldTSize.width,
            height: fieldTSize.height
        )
        fieldM.frame = CGRect(
            x: centreX + symbolHalfWidth + gap,
            y: centreY + symbolHalfHeight - fieldMSize.height + 3,
            width: fieldMSize.width,
            height: fieldMSize.height
        )
    }

    private func fittedSize(for label: UILabel, maximumWidth: CGFloat) -> CGSize {
        guard !label.isHidden else { return .zero }
        let size = label.sizeThatFits(
            CGSize(width: maximumWidth, height: .greatestFiniteMagnitude))
        return CGSize(width: min(ceil(size.width), maximumWidth),
                      height: ceil(size.height))
    }

    private static func makeLabel(alignment: NSTextAlignment) -> CompactAmplifierLabel {
        // Match Android's high-contrast chip treatment, but deliberately omit
        // its extra vertical inset so the background hugs the glyphs more
        // tightly on the smaller iOS unit symbol.
        let label = CompactAmplifierLabel(
            contentInsets: UIEdgeInsets(top: 0, left: 3, bottom: 0, right: 3))
        label.backgroundColor = UIColor.black.withAlphaComponent(0.72)
        label.textColor = .white
        label.font = .systemFont(ofSize: 10, weight: .bold)
        label.textAlignment = alignment
        label.numberOfLines = 1
        label.adjustsFontSizeToFitWidth = true
        label.minimumScaleFactor = 0.65
        label.layer.cornerRadius = 3
        label.layer.cornerCurve = .continuous
        label.layer.masksToBounds = true
        label.isUserInteractionEnabled = false
        return label
    }

    private static func set(_ text: String?, on label: UILabel) {
        guard let text, !text.isEmpty else {
            label.text = nil
            label.isHidden = true
            return
        }
        label.text = text
        label.isHidden = false
    }
}

/// UILabel with explicit content insets. UIKit's stock label has no padding,
/// while placing it in a separate container would complicate the three edge
/// anchors above.
private final class CompactAmplifierLabel: UILabel {
    private let contentInsets: UIEdgeInsets

    init(contentInsets: UIEdgeInsets) {
        self.contentInsets = contentInsets
        super.init(frame: .zero)
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    override func drawText(in rect: CGRect) {
        super.drawText(in: rect.inset(by: contentInsets))
    }

    override func sizeThatFits(_ size: CGSize) -> CGSize {
        let contentSize = CGSize(
            width: max(0, size.width - contentInsets.left - contentInsets.right),
            height: max(0, size.height - contentInsets.top - contentInsets.bottom))
        let fitted = super.sizeThatFits(contentSize)
        return CGSize(width: fitted.width + contentInsets.left + contentInsets.right,
                      height: fitted.height + contentInsets.top + contentInsets.bottom)
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

    private let imageView = UIImageView()

    init(waypoint: Waypoint) {
        self.waypoint = waypoint
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

    func update(waypoint: Waypoint) {
        let kindChanged = waypoint.kind != self.waypoint.kind
        let rotationChanged = waypoint.rotation != self.waypoint.rotation
        let colorChanged = waypoint.taskColor != self.waypoint.taskColor
        self.waypoint = waypoint
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

}

/// Per-symbol visible-bounds cache. For each (measure, rotation) we
/// render into a small alpha-only bitmap and compute the tight bbox of
/// visible pixels (normalized 0..1). MapEditingController uses this for hit
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
