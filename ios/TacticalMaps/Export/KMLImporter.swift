import Foundation
import CoreLocation
import Compression

/// Parses a KML / KMZ document (Google Earth, ATAK, Caltopo, …) into our
/// domain objects.
///
/// Placemark `Point` → generic waypoint; `LineString` / `Polygon` (outer
/// ring) → drawing. KML `Folder` / `Document` names become drawing layers
/// so an exported folder structure survives the round-trip. KMZ is a zip
/// wrapper — we read the first `.kml` entry (typically `doc.kml`).
///
/// Reuses `GeoJSONImporter.Result` so the import flow handles GeoJSON and
/// KML identically. Shared `<styleUrl>` styles aren't resolved in this
/// first cut; shapes use the standard amber defaults, like foreign GeoJSON.
enum KMLImporter {

    enum ImportError: Error, LocalizedError {
        case notKML
        case kmzHadNoKML

        var errorDescription: String? {
            switch self {
            case .notKML:      return "This file isn't valid KML."
            case .kmzHadNoKML: return "No .kml entry was found inside this KMZ."
            }
        }
    }

    /// Parse a `.kml` or `.kmz` file. Sniffs the zip magic to unwrap KMZ.
    static func parse(_ data: Data,
                      existingLayers: [DrawingLayer],
                      fallbackLayerID: UUID) throws -> GeoJSONImporter.Result {
        let kmlData: Data
        if data.count >= 2, data[data.startIndex] == 0x50, data[data.startIndex + 1] == 0x4B {
            guard let extracted = MiniZip.extractFirstKML(from: data) else {
                throw ImportError.kmzHadNoKML
            }
            kmlData = extracted
        } else {
            kmlData = data
        }

        let delegate = KMLParserDelegate(existingLayers: existingLayers,
                                         fallbackLayerID: fallbackLayerID)
        let parser = XMLParser(data: kmlData)
        parser.delegate = delegate
        guard parser.parse(), delegate.sawKMLContent else {
            throw ImportError.notKML
        }
        return delegate.result
    }
}

// MARK: - SAX delegate

private final class KMLParserDelegate: NSObject, XMLParserDelegate {

    private(set) var result = GeoJSONImporter.Result()
    private(set) var sawKMLContent = false

    private var layersByName: [String: DrawingLayer]
    private let fallbackLayerID: UUID

    /// Layer id for the current folder scope (inherits down the tree).
    private var layerStack: [UUID]
    /// Element name path, used to disambiguate `<name>` (folder vs placemark).
    private var elementPath: [String] = []
    private var charBuffer = ""

    // Current placemark accumulation.
    private var inPlacemark = false
    private var placemarkName: String?
    private var placemarkNotes: String?
    private enum Geom { case point, line, polygon }
    private var geom: Geom?
    private var coords: [Coordinate2D] = []
    private var firstAltitude: Double?
    /// Polygon outer ring only: ignore coordinates after the first set.
    private var capturedCoords = false

    init(existingLayers: [DrawingLayer], fallbackLayerID: UUID) {
        self.layersByName = Dictionary(existingLayers.map { ($0.name, $0) },
                                       uniquingKeysWith: { a, _ in a })
        self.fallbackLayerID = fallbackLayerID
        self.layerStack = [fallbackLayerID]
    }

    private var currentLayerID: UUID { layerStack.last ?? fallbackLayerID }

    private func localName(_ name: String) -> String {
        guard let colon = name.lastIndex(of: ":") else { return name }
        return String(name[name.index(after: colon)...])
    }

    func parser(_ parser: XMLParser, didStartElement elementName: String,
                namespaceURI: String?, qualifiedName qName: String?,
                attributes attributeDict: [String: String]) {
        let name = localName(elementName)
        elementPath.append(name)
        charBuffer = ""

        switch name {
        case "kml", "Document", "Placemark":
            sawKMLContent = true
        default:
            break
        }

        switch name {
        case "Folder", "Document":
            // Inherit the enclosing layer until a <name> refines it.
            layerStack.append(currentLayerID)
        case "Placemark":
            inPlacemark = true
            placemarkName = nil
            placemarkNotes = nil
            geom = nil
            coords = []
            firstAltitude = nil
            capturedCoords = false
        case "Point":   if inPlacemark { geom = .point }
        case "LineString": if inPlacemark { geom = .line }
        case "Polygon": if inPlacemark { geom = .polygon }
        default:
            break
        }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        charBuffer += string
    }

    func parser(_ parser: XMLParser, didEndElement elementName: String,
                namespaceURI: String?, qualifiedName qName: String?) {
        let name = localName(elementName)
        let text = charBuffer.trimmingCharacters(in: .whitespacesAndNewlines)
        // Parent is the element just below this one on the path.
        let parent = elementPath.count >= 2 ? elementPath[elementPath.count - 2] : ""

        switch name {
        case "name":
            if inPlacemark {
                placemarkName = text
            } else if parent == "Folder" || parent == "Document", !text.isEmpty {
                // Refine the current folder scope to a named layer.
                if !layerStack.isEmpty { layerStack[layerStack.count - 1] = resolveLayer(named: text) }
            }
        case "description":
            if inPlacemark { placemarkNotes = text }
        case "coordinates":
            if inPlacemark, !capturedCoords {
                coords = Self.parseCoordinates(text)
                firstAltitude = Self.firstAltitude(text)
                capturedCoords = true
            }
        case "Placemark":
            finishPlacemark()
            inPlacemark = false
        case "Folder", "Document":
            if layerStack.count > 1 { layerStack.removeLast() }
        default:
            break
        }

        if !elementPath.isEmpty { elementPath.removeLast() }
        charBuffer = ""
    }

    // MARK: building

    private func resolveLayer(named name: String) -> UUID {
        if let existing = layersByName[name] { return existing.id }
        let layer = DrawingLayer(id: UUID(), name: name, defaultColorHex: "#1E88E5")
        layersByName[name] = layer
        result.newLayers.append(layer)
        return layer.id
    }

    private func finishPlacemark() {
        guard let geom else { return }
        let layerID = currentLayerID
        switch geom {
        case .point:
            guard let first = coords.first else { return }
            let wp = Waypoint(
                id: UUID(),
                name: (placemarkName?.isEmpty == false ? placemarkName! : "Imported"),
                notes: placemarkNotes,
                coordinate: CLLocationCoordinate2D(latitude: first.latitude,
                                                   longitude: first.longitude),
                elevation: firstAltitude,
                kind: .generic,
                rotation: 0,
                scaleX: 1,
                scaleY: 1,
                layerID: layerID
            )
            result.waypoints.append(wp)
        case .line:
            guard coords.count >= 2 else { return }
            result.drawings.append(makeShape(kind: .polyline, coords: coords, layerID: layerID))
        case .polygon:
            var pts = coords
            if pts.count > 1, let f = pts.first, let l = pts.last,
               f.latitude == l.latitude, f.longitude == l.longitude {
                pts.removeLast()
            }
            guard pts.count >= 3 else { return }
            result.drawings.append(makeShape(kind: .polygon, coords: pts, layerID: layerID))
        }
    }

    private func makeShape(kind: DrawingKind, coords: [Coordinate2D], layerID: UUID) -> DrawingShape {
        DrawingShape(
            id: UUID(),
            name: placemarkName,
            notes: placemarkNotes,
            kind: kind,
            coordinates: coords,
            style: DrawingStyle(),
            layerID: layerID
        )
    }

    // MARK: coordinate parsing

    /// KML coordinates: whitespace-separated `lon,lat[,alt]` tuples.
    static func parseCoordinates(_ text: String) -> [Coordinate2D] {
        text.split(whereSeparator: { $0 == " " || $0 == "\n" || $0 == "\t" || $0 == "\r" })
            .compactMap { tuple in
                let parts = tuple.split(separator: ",", omittingEmptySubsequences: false)
                guard parts.count >= 2,
                      let lon = Double(parts[0]), let lat = Double(parts[1]) else { return nil }
                return Coordinate2D(latitude: lat, longitude: lon)
            }
    }

    static func firstAltitude(_ text: String) -> Double? {
        guard let first = text.split(whereSeparator: {
            $0 == " " || $0 == "\n" || $0 == "\t" || $0 == "\r"
        }).first else { return nil }
        let parts = first.split(separator: ",", omittingEmptySubsequences: false)
        guard parts.count >= 3 else { return nil }
        return Double(parts[2])
    }
}

// MARK: - Minimal ZIP reader (KMZ)

/// Just enough of the ZIP format to pull a single `.kml` entry out of a KMZ:
/// locate the End-Of-Central-Directory record, walk the central directory
/// for the `.kml` entry (preferring `doc.kml`), then inflate its data via
/// Apple's `Compression` framework (`COMPRESSION_ZLIB` == raw DEFLATE, which
/// is exactly what ZIP stores). No zip64 / encryption support — fine for KMZ.
private enum MiniZip {

    static func extractFirstKML(from data: Data) -> Data? {
        let b = [UInt8](data)
        let n = b.count
        guard n > 22 else { return nil }

        // Find EOCD (0x06054b50), scanning back from the end.
        var eocd = -1
        let minSearch = max(0, n - 22 - 65_536)
        var i = n - 22
        while i >= minSearch {
            if b[i] == 0x50, b[i+1] == 0x4B, b[i+2] == 0x05, b[i+3] == 0x06 { eocd = i; break }
            i -= 1
        }
        guard eocd >= 0 else { return nil }

        let cdCount = readU16(b, eocd + 10)
        let cdOffset = Int(readU32(b, eocd + 16))

        var p = cdOffset
        var best: (offset: Int, comp: Int, uncomp: Int, method: Int, isDoc: Bool)?
        var entry = 0
        while entry < cdCount, p + 46 <= n,
              b[p] == 0x50, b[p+1] == 0x4B, b[p+2] == 0x01, b[p+3] == 0x02 {
            let method = readU16(b, p + 10)
            let comp = Int(readU32(b, p + 20))
            let uncomp = Int(readU32(b, p + 24))
            let nameLen = readU16(b, p + 28)
            let extraLen = readU16(b, p + 30)
            let commentLen = readU16(b, p + 32)
            let localOffset = Int(readU32(b, p + 42))
            let nameStart = p + 46
            let nameEnd = min(nameStart + nameLen, n)
            let name = String(bytes: b[nameStart..<nameEnd], encoding: .utf8) ?? ""
            let lower = name.lowercased()
            if lower.hasSuffix(".kml") {
                let isDoc = lower == "doc.kml" || lower.hasSuffix("/doc.kml")
                if best == nil || isDoc {
                    best = (localOffset, comp, uncomp, method, isDoc)
                    if isDoc { /* keep scanning is fine; doc.kml wins */ }
                }
            }
            p = nameStart + nameLen + extraLen + commentLen
            entry += 1
        }

        guard let e = best else { return nil }

        // Local file header → data offset.
        let lh = e.offset
        guard lh + 30 <= n, b[lh] == 0x50, b[lh+1] == 0x4B, b[lh+2] == 0x03, b[lh+3] == 0x04 else { return nil }
        let lhNameLen = readU16(b, lh + 26)
        let lhExtraLen = readU16(b, lh + 28)
        let dataStart = lh + 30 + lhNameLen + lhExtraLen
        guard dataStart + e.comp <= n else { return nil }
        let compressed = Data(b[dataStart..<dataStart + e.comp])

        if e.method == 0 { return compressed }                  // stored
        return inflate(compressed, expected: e.uncomp)          // deflate
    }

    private static func inflate(_ data: Data, expected: Int) -> Data? {
        guard expected > 0 else { return nil }
        var dst = Data(count: expected)
        let written = dst.withUnsafeMutableBytes { (dstRaw: UnsafeMutableRawBufferPointer) -> Int in
            data.withUnsafeBytes { (srcRaw: UnsafeRawBufferPointer) -> Int in
                guard let dstPtr = dstRaw.bindMemory(to: UInt8.self).baseAddress,
                      let srcPtr = srcRaw.bindMemory(to: UInt8.self).baseAddress else { return 0 }
                return compression_decode_buffer(dstPtr, expected, srcPtr, data.count, nil, COMPRESSION_ZLIB)
            }
        }
        guard written > 0 else { return nil }
        return dst.prefix(written)
    }

    private static func readU16(_ b: [UInt8], _ o: Int) -> Int {
        Int(b[o]) | (Int(b[o+1]) << 8)
    }
    private static func readU32(_ b: [UInt8], _ o: Int) -> UInt32 {
        UInt32(b[o]) | (UInt32(b[o+1]) << 8) | (UInt32(b[o+2]) << 16) | (UInt32(b[o+3]) << 24)
    }
}
