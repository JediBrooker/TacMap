import Foundation
import CoreGraphics

enum TileCacheEntry {
    case image(CGImage)
    /// loaded, nothing to draw (off the sheet, or the source has no tile there)
    case empty

    var cost: Int {
        switch self {
        case .image(let img): return max(img.bytesPerRow * img.height, PDFTileConstants.emptyEntryCostBytes)
        case .empty: return PDFTileConstants.emptyEntryCostBytes
        }
    }
}

/// Memory only byte LRU for decoded tiles. Never touches disk, rendered PDF
/// tiles must not end up in a plaintext cache. Main thread only.
final class TileImageCache {
    private final class Node {
        let key: TileIndex
        var entry: TileCacheEntry
        var prev: Node?
        var next: Node?
        init(key: TileIndex, entry: TileCacheEntry) { self.key = key; self.entry = entry }
    }

    let byteLimit: Int
    private(set) var totalCost = 0
    private var nodes: [TileIndex: Node] = [:]
    // head = most recent
    private var head: Node?
    private var tail: Node?

    init(byteLimit: Int) { self.byteLimit = max(1, byteLimit) }

    /// 64/128/192 MiB by physical RAM, contract B
    static func byteLimit(physicalMemory: UInt64 = ProcessInfo.processInfo.physicalMemory) -> Int {
        PDFMemoryTier.tileCacheBytes(physicalMemory: physicalMemory)
    }

    var count: Int { nodes.count }

    func entry(for key: TileIndex) -> TileCacheEntry? {
        guard let n = nodes[key] else { return nil }
        moveToFront(n)
        return n.entry
    }

    /// peek without bumping recency (draw plans ask a lot)
    func peek(_ key: TileIndex) -> TileCacheEntry? { nodes[key]?.entry }

    func insert(_ entry: TileCacheEntry, for key: TileIndex) {
        if let n = nodes[key] {
            totalCost -= n.entry.cost
            n.entry = entry
            totalCost += entry.cost
            moveToFront(n)
        } else {
            let n = Node(key: key, entry: entry)
            nodes[key] = n
            totalCost += entry.cost
            pushFront(n)
        }
        evict(keeping: key)
    }

    func remove(_ key: TileIndex) {
        guard let n = nodes.removeValue(forKey: key) else { return }
        unlink(n)
        totalCost -= n.entry.cost
    }

    func removeAll() {
        nodes.removeAll()
        head = nil
        tail = nil
        totalCost = 0
    }

    /// memory warning: keep only what the current frame draws
    func trim(keeping keep: Set<TileIndex>) {
        for key in Array(nodes.keys) where !keep.contains(key) { remove(key) }
    }

    private func evict(keeping fresh: TileIndex) {
        while totalCost > byteLimit, let last = tail, last.key != fresh {
            remove(last.key)
        }
    }

    private func pushFront(_ n: Node) {
        n.prev = nil
        n.next = head
        head?.prev = n
        head = n
        if tail == nil { tail = n }
    }

    private func unlink(_ n: Node) {
        if let p = n.prev { p.next = n.next } else { head = n.next }
        if let nx = n.next { nx.prev = n.prev } else { tail = n.prev }
        n.prev = nil
        n.next = nil
    }

    private func moveToFront(_ n: Node) {
        guard head !== n else { return }
        unlink(n)
        pushFront(n)
    }
}
