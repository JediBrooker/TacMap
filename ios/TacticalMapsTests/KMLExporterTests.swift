import XCTest
@testable import TacticalMaps

/// Pins KML export to testdata/kml_export.json, the same fixture the Android
/// suite reads.
@MainActor
final class KMLExporterTests: XCTestCase {
    private var fixture: [String: Any]!

    override func setUpWithError() throws {
        try super.setUpWithError()
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        for _ in 0..<8 {
            let candidate = dir.appendingPathComponent("testdata/kml_export.json")
            if FileManager.default.fileExists(atPath: candidate.path) {
                fixture = try XCTUnwrap(
                    JSONSerialization.jsonObject(with: Data(contentsOf: candidate)) as? [String: Any]
                )
                return
            }
            dir = dir.deletingLastPathComponent()
        }
        XCTFail("Could not locate testdata/kml_export.json")
    }

    private func number(_ value: Any?) -> Double { (value as! NSNumber).doubleValue }

    private var layers: [DrawingLayer] {
        (fixture["layers"] as! [[String: Any]]).map { json in
            DrawingLayer(id: UUID(uuidString: json["id"] as! String)!,
                         name: json["name"] as! String,
                         visible: json["visible"] as! Bool,
                         defaultColorHex: json["color"] as! String)
        }
    }

    private var waypoints: [Waypoint] {
        (fixture["waypoints"] as! [[String: Any]]).map { json in
            let kind: WaypointKind
            switch json["kind"] as! String {
            case "military":
                let affiliation = SymbolAffiliation(rawValue: json["affiliation"] as! String)!
                kind = .military(MilitarySymbolSpec(affiliation: affiliation, echelon: .platoon))
            case "controlMeasure":
                kind = .controlMeasure(.block)
            default:
                kind = .generic
            }
            return Waypoint(
                id: UUID(uuidString: json["id"] as! String)!,
                name: json["name"] as! String,
                notes: json["notes"] as? String,
                latitude: number(json["lat"]),
                longitude: number(json["lon"]),
                elevation: (json["elevation"] as? NSNumber)?.doubleValue,
                kind: kind,
                taskColor: (json["taskColor"] as? String).flatMap(TaskColor.init(rawValue:)) ?? .black,
                layerID: UUID(uuidString: json["layerID"] as! String)!
            )
        }
    }

    private var drawings: [DrawingShape] {
        (fixture["drawings"] as! [[String: Any]]).map { json in
            let kinds: [String: DrawingKind] = ["point": .point, "polyline": .polyline, "polygon": .polygon]
            let coordinates = (json["coordinates"] as! [[NSNumber]]).map {
                Coordinate2D(latitude: $0[0].doubleValue, longitude: $0[1].doubleValue)
            }
            let style = DrawingStyle(strokeColorHex: json["stroke"] as! String,
                                     fillColorHex: json["fill"] as? String,
                                     strokeWidth: number(json["strokeWidth"]),
                                     fillOpacity: number(json["fillOpacity"]))
            return DrawingShape(id: UUID(uuidString: json["id"] as! String)!,
                                name: json["name"] as? String,
                                notes: json["notes"] as? String,
                                kind: kinds[json["kind"] as! String]!,
                                coordinates: coordinates,
                                style: style,
                                layerID: UUID(uuidString: json["layerID"] as! String)!)
        }
    }

    func testKMLMatchesSharedFixture() {
        let kml = KMLExporter.kml(waypoints: waypoints, drawings: drawings, layers: layers)
        XCTAssertEqual(kml, fixture["expectedKML"] as? String)
    }

    func testFixedPointFormattingMatchesAndroid() {
        XCTAssertEqual(KMLExporter.fixed(42.25, 1), "42.2")
        XCTAssertEqual(KMLExporter.fixed(-0.000_000_01, 7), "0.0000000")
        XCTAssertEqual(KMLExporter.fixed(-0.1278, 7), "-0.1278000")
        XCTAssertEqual(KMLExporter.kmlColor(hex: "#FF0000", alpha: 255), "ff0000ff")
    }

    func testKMZHoldsDocumentFirstAndOneImagePerDistinctSymbol() throws {
        let archive = KMZExporter.kmz(waypoints: waypoints, drawings: drawings, layers: layers)
        let entries = storedEntries(archive)
        XCTAssertEqual(entries.first?.name, "doc.kml")
        let kml = try XCTUnwrap(String(data: entries[0].contents, encoding: .utf8))
        let icons = entries.dropFirst().map(\.name)
        XCTAssertFalse(icons.isEmpty)
        XCTAssertEqual(Set(icons).count, icons.count)
        for icon in icons {
            XCTAssertTrue(icon.hasPrefix("files/icons/") && icon.hasSuffix(".png"), icon)
            XCTAssertTrue(kml.contains("<href>\(icon)</href>"), icon)
        }
        XCTAssertEqual(kml.components(separatedBy: "<Placemark ").count - 1, 8)
    }

    /// Reads a stored (uncompressed) ZIP's local entries in order.
    private func storedEntries(_ data: Data) -> [(name: String, contents: Data)] {
        let bytes = [UInt8](data)
        func u16(_ at: Int) -> Int { Int(bytes[at]) | Int(bytes[at + 1]) << 8 }
        func u32(_ at: Int) -> Int { u16(at) | u16(at + 2) << 16 }
        var entries: [(String, Data)] = []
        var offset = 0
        while offset + 30 <= bytes.count, u32(offset) == 0x0403_4B50 {
            let size = u32(offset + 18)
            let nameLength = u16(offset + 26)
            let extraLength = u16(offset + 28)
            let nameStart = offset + 30
            let dataStart = nameStart + nameLength + extraLength
            let name = String(decoding: bytes[nameStart..<(nameStart + nameLength)], as: UTF8.self)
            entries.append((name, Data(bytes[dataStart..<(dataStart + size)])))
            offset = dataStart + size
        }
        return entries
    }
}
