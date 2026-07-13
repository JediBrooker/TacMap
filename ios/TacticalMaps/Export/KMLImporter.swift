import Foundation
import CoreLocation
import Compression

/// Parses KML / KMZ (Google Earth, ATAK, Caltopo, etc.) into domain objects.
///
/// Points become generic waypoints, lines/polygons become drawings.
/// KML Folder/Document names map to drawing layers so folder structure
/// survives the round-trip. KMZ is just a zip wrapper, we pull the first
/// `.kml` entry (usually `doc.kml`).
///
/// Reuses `GeoJSONImporter.Result` so the import path is the same for both
/// formats. `<styleUrl>` styles aren't resolved yet - shapes just get the
/// default amber styling, same as foreign GeoJSON.
enum KMLImporter {

    static let maxInputBytes = 16 * 1024 * 1024
    fileprivate static let maxFeatures = 10_000
    fileprivate static let maxCoordinates = 100_000

    enum ImportError: Error, LocalizedError {
        case notKML
        case kmzHadNoKML
        case limitExceeded(String)
        case cancelled

        var errorDescription: String? {
            switch self {
            case .notKML:      return "This file isn't valid KML."
            case .kmzHadNoKML: return "No .kml entry was found inside this KMZ."
            case .limitExceeded(let reason): return "Import safety limit exceeded: \(reason)."
            case .cancelled: return "Import cancelled."
            }
        }
    }

    /// Parse a `.kml` or `.kmz` file. Checks zip magic bytes to unwrap KMZ.
    static func parse(_ data: Data,
                      existingLayers: [DrawingLayer],
                      fallbackLayerID: UUID) throws -> GeoJSONImporter.Result {
        guard data.count <= maxInputBytes else { throw ImportError.limitExceeded("file is over 16 MB") }
        let kmlData: Data
        if data.count >= 2, data[data.startIndex] == 0x50, data[data.startIndex + 1] == 0x4B {
            guard let extracted = try MiniZip.extractFirstKML(from: data) else {
                throw ImportError.kmzHadNoKML
            }
            kmlData = extracted
        } else {
            kmlData = data
        }

        let delegate = KMLParserDelegate(existingLayers: existingLayers,
                                         fallbackLayerID: fallbackLayerID,
                                         deadline: Date().addingTimeInterval(4))
        let parser = XMLParser(data: kmlData)
        parser.delegate = delegate
        parser.shouldResolveExternalEntities = false
        parser.externalEntityResolvingPolicy = .never
        guard parser.parse(), delegate.sawKMLContent else {
            if let failure = delegate.failure { throw failure }
            throw ImportError.notKML
        }
        if let failure = delegate.failure { throw failure }
        return delegate.result
    }
}

// MARK: - SAX delegate

private final class KMLParserDelegate: NSObject, XMLParserDelegate {

    private(set) var result = GeoJSONImporter.Result()
    private(set) var sawKMLContent = false

    private var layersByName: [String: DrawingLayer]
    private let fallbackLayerID: UUID

    /// Layer id for current folder scope, inherits down the tree.
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
    private var featureCount = 0
    private var coordinateCount = 0
    private let deadline: Date
    private(set) var failure: Error?

    init(existingLayers: [DrawingLayer], fallbackLayerID: UUID, deadline: Date) {
        self.layersByName = Dictionary(existingLayers.map { ($0.name, $0) },
                                       uniquingKeysWith: { a, _ in a })
        self.fallbackLayerID = fallbackLayerID
        self.layerStack = [fallbackLayerID]
        self.deadline = deadline
    }

    private var currentLayerID: UUID { layerStack.last ?? fallbackLayerID }

    private func localName(_ name: String) -> String {
        guard let colon = name.lastIndex(of: ":") else { return name }
        return String(name[name.index(after: colon)...])
    }

    func parser(_ parser: XMLParser, didStartElement elementName: String,
                namespaceURI: String?, qualifiedName qName: String?,
                attributes attributeDict: [String: String]) {
        guard checkBudget(parser) else { return }
        let name = localName(elementName)
        elementPath.append(name)
        if elementPath.count > 64 {
            fail(.limitExceeded("XML nesting is too deep"), parser)
            return
        }
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
            featureCount += 1
            if featureCount > KMLImporter.maxFeatures {
                fail(.limitExceeded("more than 10,000 placemarks"), parser)
                return
            }
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
        guard checkBudget(parser) else { return }
        guard charBuffer.utf8.count + string.utf8.count <= 4 * 1024 * 1024 else {
            fail(.limitExceeded("an XML text field is over 4 MB"), parser)
            return
        }
        charBuffer += string
    }

    func parser(_ parser: XMLParser, didEndElement elementName: String,
                namespaceURI: String?, qualifiedName qName: String?) {
        guard checkBudget(parser) else { return }
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
                let remaining = max(0, KMLImporter.maxCoordinates - coordinateCount)
                let parsed = Self.parseCoordinates(text, limit: remaining)
                guard !parsed.exceeded else {
                    fail(.limitExceeded("more than 100,000 coordinates"), parser)
                    return
                }
                coords = parsed.coordinates
                coordinateCount += coords.count
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
    static func parseCoordinates(_ text: String, limit: Int = KMLImporter.maxCoordinates)
        -> (coordinates: [Coordinate2D], exceeded: Bool) {
        var result: [Coordinate2D] = []
        for tuple in text.split(whereSeparator: { $0 == " " || $0 == "\n" || $0 == "\t" || $0 == "\r" }) {
            if result.count >= limit { return (result, true) }
            let parts = tuple.split(separator: ",", omittingEmptySubsequences: false)
            guard parts.count >= 2,
                  let lon = Double(parts[0]), let lat = Double(parts[1]),
                  lon.isFinite, lat.isFinite,
                  abs(lat) <= 90, abs(lon) <= 180 else { continue }
            result.append(Coordinate2D(latitude: lat, longitude: lon))
        }
        return (result, false)
    }

    static func firstAltitude(_ text: String) -> Double? {
        guard let first = text.split(whereSeparator: {
            $0 == " " || $0 == "\n" || $0 == "\t" || $0 == "\r"
        }).first else { return nil }
        let parts = first.split(separator: ",", omittingEmptySubsequences: false)
        guard parts.count >= 3 else { return nil }
        guard let altitude = Double(parts[2]), altitude.isFinite else { return nil }
        return altitude
    }

    private func checkBudget(_ parser: XMLParser) -> Bool {
        if failure != nil { return false }
        if Date() > deadline {
            fail(.limitExceeded("parsing took too long"), parser)
            return false
        }
        if withUnsafeCurrentTask(body: { $0?.isCancelled ?? false }) {
            fail(.cancelled, parser)
            return false
        }
        return true
    }

    private func fail(_ error: KMLImporter.ImportError, _ parser: XMLParser) {
        failure = error
        parser.abortParsing()
    }
}

// MARK: - Minimal ZIP reader (KMZ)

/// Bare-minimum ZIP reader to yank a .kml out of a KMZ. Finds the EOCD,
/// walks the central directory for the .kml entry (prefers `doc.kml`),
/// then inflates via `Compression` (COMPRESSION_ZLIB == raw DEFLATE, which
/// is what ZIP uses). No zip64 or encryption, but thats fine for KMZ.
private enum MiniZip {

    static func extractFirstKML(from data: Data) throws -> Data? {
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
        guard cdCount <= 256 else { throw KMLImporter.ImportError.limitExceeded("KMZ has too many entries") }
        guard cdOffset >= 0, cdOffset <= n else { return nil }

        var p = cdOffset
        var best: (offset: Int, comp: Int, uncomp: Int, method: Int, isDoc: Bool)?
        var entry = 0
        while entry < cdCount, p + 46 <= n,
              b[p] == 0x50, b[p+1] == 0x4B, b[p+2] == 0x01, b[p+3] == 0x02 {
            let method = readU16(b, p + 10)
            let flags = readU16(b, p + 8)
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
                guard flags & 0x1 == 0 else { return nil }
                guard uncomp <= KMLImporter.maxInputBytes,
                      comp <= KMLImporter.maxInputBytes,
                      uncomp <= max(1, comp) * 100 else {
                    throw KMLImporter.ImportError.limitExceeded("KMZ expansion is too large")
                }
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
        guard dataStart <= n, e.comp <= n - dataStart else { return nil }
        let compressed = Data(b[dataStart..<dataStart + e.comp])

        if e.method == 0 { return e.comp == e.uncomp ? compressed : nil } // stored
        guard e.method == 8 else { return nil }
        return inflate(compressed, expected: e.uncomp)          // deflate
    }

    private static func inflate(_ data: Data, expected: Int) -> Data? {
        guard expected > 0, expected <= KMLImporter.maxInputBytes else { return nil }
        var dst = Data(count: expected)
        let written = dst.withUnsafeMutableBytes { (dstRaw: UnsafeMutableRawBufferPointer) -> Int in
            data.withUnsafeBytes { (srcRaw: UnsafeRawBufferPointer) -> Int in
                guard let dstPtr = dstRaw.bindMemory(to: UInt8.self).baseAddress,
                      let srcPtr = srcRaw.bindMemory(to: UInt8.self).baseAddress else { return 0 }
                return compression_decode_buffer(dstPtr, expected, srcPtr, data.count, nil, COMPRESSION_ZLIB)
            }
        }
        guard written == expected else { return nil }
        return dst
    }

    private static func readU16(_ b: [UInt8], _ o: Int) -> Int {
        Int(b[o]) | (Int(b[o+1]) << 8)
    }
    private static func readU32(_ b: [UInt8], _ o: Int) -> UInt32 {
        UInt32(b[o]) | (UInt32(b[o+1]) << 8) | (UInt32(b[o+2]) << 16) | (UInt32(b[o+3]) << 24)
    }
}
