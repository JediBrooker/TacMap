import Foundation
import Combine
import UIKit
import ImageIO
import CryptoKit

struct CustomSymbol: Codable, Hashable {
    let id: String
    let name: String
    let png: String

    func image() -> UIImage? {
        guard !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, name.count <= 160,
              png.utf8.count <= 44000, let bytes = Data(base64Encoded: png),
              (24...32768).contains(bytes.count),
              Array(bytes.prefix(8)) == [137,80,78,71,13,10,26,10],
              let source = CGImageSourceCreateWithData(bytes as CFData, nil),
              let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = props[kCGImagePropertyPixelWidth] as? Int,
              let height = props[kCGImagePropertyPixelHeight] as? Int,
              (1...256).contains(width), (1...256).contains(height),
              SHA256.hash(data: bytes).map({ String(format: "%02x", $0) }).joined() == id,
              let cg = CGImageSourceCreateImageAtIndex(source, 0, nil) else { return nil }
        return UIImage(cgImage: cg)
    }
}

struct CustomSymbolPack: Codable {
    let format: Int
    let name: String
    let attribution: String
    let symbols: [CustomSymbol]
}

enum CustomSymbolPackError: Error { case invalid }

final class CustomSymbolStore: ObservableObject {
    static let shared = CustomSymbolStore()
    @Published private(set) var packs: [CustomSymbolPack] = []
    private let file: URL
    init(directory: URL? = nil) {
        let root = directory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        file = root.appendingPathComponent("custom-symbol-packs.sealed")
    }
    private static let storeLabel = "custom-symbol-packs/v1"
    func clear() { packs = [] }
    func reload() throws {
        do {
            let key = try SafeStore.keyProvider()
            guard FileManager.default.fileExists(atPath: file.path) else { packs = []; return }
            guard let size = try file.resourceValues(forKeys: [.fileSizeKey]).fileSize,
                  size <= 32 * 1024 * 1024 + 64 else { throw CustomSymbolPackError.invalid }
            let bytes = try Data(contentsOf: file)
            guard SealedEnvelope.isSealedFile(bytes),
                  let plain = SealedEnvelope.openFile(key: key, blob: bytes, label: Self.storeLabel) else { throw CustomSymbolPackError.invalid }
            let loaded = try JSONDecoder().decode([CustomSymbolPack].self, from: plain)
            guard loaded.count <= 32, loaded.allSatisfy(Self.valid) else { throw CustomSymbolPackError.invalid }
            packs = loaded
        } catch { clear(); throw error }
    }
    static func preflight(_ bytes: Data) throws {
        var depth = 0, tokens = 0
        var quoted = false, escaped = false
        for c in bytes {
            if quoted {
                if escaped { escaped = false } else if c == 92 { escaped = true } else if c == 34 { quoted = false }
            } else {
                switch c {
                case 34: quoted = true; tokens += 1
                case 123, 91: depth += 1; tokens += 1
                case 125, 93: depth -= 1
                case 44: tokens += 1
                default: break
                }
                guard (0...8).contains(depth), tokens <= 30000 else { throw CustomSymbolPackError.invalid }
            }
        }
        guard !quoted, depth == 0 else { throw CustomSymbolPackError.invalid }
    }
    static func valid(_ pack: CustomSymbolPack) -> Bool {
        pack.format == 1 && !pack.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && pack.name.count <= 100 &&
        pack.attribution.count <= 2000 && (1...1000).contains(pack.symbols.count) &&
        Set(pack.symbols.map(\.id)).count == pack.symbols.count && pack.symbols.allSatisfy { $0.image() != nil }
    }
    func symbol(_ id: String) -> CustomSymbol? { packs.lazy.flatMap(\.symbols).first { $0.id == id } }
    var entries: [MarkerCatalog.Entry] {
        var seen = Set<String>()
        return packs.flatMap { pack in pack.symbols.compactMap { symbol in
            guard seen.insert(symbol.id).inserted else { return nil }
            return MarkerCatalog.Entry(id: symbol.id, name: "\(pack.name) / \(symbol.name)", sfSymbol: "photo", defaultColorHex: "#3B7BE0")
        }}
    }
    @discardableResult func importPack(_ bytes: Data) throws -> CustomSymbolPack {
        try reload() // Never overwrite an unreadable or locked store.
        guard bytes.count <= 16 * 1024 * 1024 else { throw CustomSymbolPackError.invalid }
        try Self.preflight(bytes)
        let pack = try JSONDecoder().decode(CustomSymbolPack.self, from: bytes)
        guard Self.valid(pack) else { throw CustomSymbolPackError.invalid }
        let updated = packs.filter { $0.name != pack.name } + [pack]
        let encoded = try JSONEncoder().encode(updated)
        guard updated.count <= 32, encoded.count <= 32 * 1024 * 1024 else { throw CustomSymbolPackError.invalid }
        try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        try SafeStore.write(encoded, to: file, label: Self.storeLabel)
        packs = updated
        return pack
    }
}

/// Search user-supplied names without network lookups or changing stored spelling.
enum CustomSymbolSearch {
    static func matches(_ text: String, query: String) -> Bool {
        let folded = text.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: Locale(identifier: "en_US_POSIX"))
        return query.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: Locale(identifier: "en_US_POSIX"))
            .split(whereSeparator: { $0.isWhitespace }).allSatisfy { folded.contains($0) }
    }
}
