import UIKit
import QuartzCore

/// Puts a TileDrawPlan on screen as one CALayer per item under tileRoot.
/// Replaces the old draw(_:) that CPU composited the whole backing store on
/// every camera tick. Frames are exact floats off one grid origin, edge AA
/// off and linear filtering, no inflate and no rounding (D4-16), so
/// neighbours share their edges and nothing shimmers during a pinch.
final class TileLayerCompositor {
    let tileRoot = CALayer()

    private struct Key: Hashable {
        let col: Int
        let row: Int
        let source: TileIndex
        let sub: Int   // child quadrant, -1 for own/ancestor
    }

    private var live: [Key: CALayer] = [:]
    private var pool: [CALayer] = []

    #if DEBUG
    private(set) var deviceAuditPaintedItems: [[String: Any]] = []
    private(set) var deviceAuditPaintedCount = 0
    #endif

    init() {
        tileRoot.masksToBounds = false
        tileRoot.actions = Self.noActions
        // the view's own background shows through gaps, never black
        tileRoot.backgroundColor = nil
    }

    private static let noActions: [String: CAAction] = [
        "contents": NSNull(), "contentsRect": NSNull(), "frame": NSNull(), "bounds": NSNull(),
        "position": NSNull(), "zPosition": NSNull(), "hidden": NSNull(), "transform": NSNull(),
        "sublayers": NSNull(), "onOrderIn": NSNull(), "onOrderOut": NSNull(),
    ]

    var layerCount: Int { live.count }

    /// lay out the root over the view, rotated by heading about its centre
    func layoutRoot(bounds: CGRect, headingDegrees: Double) {
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        tileRoot.setAffineTransform(.identity)
        tileRoot.bounds = CGRect(origin: .zero, size: bounds.size)
        tileRoot.position = CGPoint(x: bounds.midX, y: bounds.midY)
        tileRoot.setAffineTransform(CGAffineTransform(rotationAngle: -headingDegrees * .pi / 180))
        CATransaction.commit()
    }

    func apply(items: [TileDrawItem], grid: TileGrid, image: (TileIndex) -> CGImage?) {
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        #if DEBUG
        let auditing = ProcessInfo.processInfo.environment["TACMAP_DEBUG_DEVICE_AUDIT"] == "1"
        deviceAuditPaintedItems = []
        deviceAuditPaintedCount = 0
        #endif
        var next: [Key: CALayer] = [:]
        next.reserveCapacity(items.count)
        for item in items {
            guard let img = image(item.source) else { continue }
            let sub = item.kind == .child ? Int(item.destRect.minX * 2 + item.destRect.minY * 4) : -1
            let key = Key(col: item.dest.col, row: item.dest.row, source: item.source, sub: sub)
            let layer = live.removeValue(forKey: key) ?? dequeue()
            let f = grid.frame(item.dest)
            let dest = CGRect(x: f.minX + item.destRect.minX * f.width,
                              y: f.minY + item.destRect.minY * f.height,
                              width: item.destRect.width * f.width,
                              height: item.destRect.height * f.height)
            if (layer.contents as AnyObject?) !== img { layer.contents = img }
            if layer.contentsRect != item.unitRect { layer.contentsRect = item.unitRect }
            layer.frame = dest
            layer.zPosition = CGFloat(item.order)
            if layer.superlayer == nil { tileRoot.addSublayer(layer) }
            next[key] = layer
            #if DEBUG
            if auditing {
                deviceAuditPaintedCount += 1
                if deviceAuditPaintedItems.count < 64 {
                    deviceAuditPaintedItems.append([
                        "quality": String(describing: item.kind),
                        "sourceTile": [item.source.z, item.source.x, item.source.y],
                        "destination": [item.dest.col, item.dest.row],
                        "frame": [Double(layer.frame.minX), Double(layer.frame.minY), Double(layer.frame.width), Double(layer.frame.height)],
                        "contentsRect": [Double(layer.contentsRect.minX), Double(layer.contentsRect.minY), Double(layer.contentsRect.width), Double(layer.contentsRect.height)],
                        "bitmap": [img.width, img.height], "order": item.order,
                        "imageID": String(describing: ObjectIdentifier(img))
                    ])
                }
            }
            #endif
        }
        for (_, layer) in live { recycle(layer) }
        live = next
        CATransaction.commit()
    }

    func removeAll() {
        #if DEBUG
        deviceAuditPaintedItems = []
        deviceAuditPaintedCount = 0
        #endif
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        for (_, layer) in live { recycle(layer) }
        live.removeAll()
        CATransaction.commit()
    }

    private func dequeue() -> CALayer {
        if let l = pool.popLast() { return l }
        let l = CALayer()
        l.actions = Self.noActions
        l.contentsGravity = .resize
        l.allowsEdgeAntialiasing = false
        l.edgeAntialiasingMask = []
        l.magnificationFilter = .linear
        l.minificationFilter = .linear
        l.isOpaque = false
        return l
    }

    private func recycle(_ l: CALayer) {
        l.contents = nil
        l.removeFromSuperlayer()
        if pool.count < 64 { pool.append(l) }
    }
}
