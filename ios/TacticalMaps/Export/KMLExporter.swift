import Foundation
import CoreLocation

/// KML export of mission objects for Google Earth, ATAK and GIS tools. Layers
/// become folders (hidden layers export with visibility 0), symbols and
/// drawings become styled placemarks, and each symbol carries its MGRS grid in
/// ExtendedData. Placemark ids are `tacmap-` plus the object id (valid XML
/// IDs cannot start with a digit). Android mirrors this in `KmlExporter.kt`;
/// `testdata/kml_export.json` pins the exact KML text on both platforms.
enum KMLExporter {
    /// KML text. `iconPaths` maps symbol IDs to KMZ-relative PNG paths; symbols
    /// without an icon use an affiliation-coloured default pin.
    static func kml(waypoints: [Waypoint],
                    drawings: [DrawingShape],
                    layers: [DrawingLayer],
                    iconPaths: [UUID: String] = [:]) -> String {
        var writer = Writer(iconPaths: iconPaths)
        let layerIDs = Set(layers.map(\.id))
        var body: [String] = []
        for layer in layers {
            var placemarks: [String] = []
            for waypoint in waypoints where waypoint.layerID == layer.id {
                placemarks += writer.placemark(for: waypoint, indent: 6)
            }
            for shape in drawings where shape.layerID == layer.id {
                placemarks += writer.placemark(for: shape, indent: 6)
            }
            guard !placemarks.isEmpty else { continue }
            body.append("    <Folder>")
            body.append("      <name>\(escape(layer.name))</name>")
            if !layer.visible { body.append("      <visibility>0</visibility>") }
            body += placemarks
            body.append("    </Folder>")
        }
        for waypoint in waypoints where !layerIDs.contains(waypoint.layerID) {
            body += writer.placemark(for: waypoint, indent: 4)
        }
        for shape in drawings where !layerIDs.contains(shape.layerID) {
            body += writer.placemark(for: shape, indent: 4)
        }

        var lines = [
            #"<?xml version="1.0" encoding="UTF-8"?>"#,
            #"<kml xmlns="http://www.opengis.net/kml/2.2">"#,
            "  <Document>",
            "    <name>TacMap</name>",
        ]
        for style in writer.styles {
            lines.append(#"    <Style id="\#(style.id)">"#)
            lines += style.lines.map { "      " + $0 }
            lines.append("    </Style>")
        }
        lines += body
        lines += ["  </Document>", "</kml>"]
        return lines.joined(separator: "\n") + "\n"
    }

    /// KML `aabbggrr` colour for a `#RRGGBB` hex string.
    static func kmlColor(hex: String, alpha: Int) -> String {
        var digits = hex.trimmingCharacters(in: .whitespaces).lowercased()
        if digits.hasPrefix("#") { digits.removeFirst() }
        if digits.count != 6 || !digits.allSatisfy(\.isHexDigit) { digits = "ffffff" }
        let chars = Array(digits)
        let clamped = min(max(alpha, 0), 255)
        return String(format: "%02x", clamped) + String(chars[4...5]) + String(chars[2...3]) + String(chars[0...1])
    }

    /// XML-escapes text and drops control characters XML 1.0 does not allow.
    static func escape(_ text: String) -> String {
        var out = ""
        out.reserveCapacity(text.count)
        for scalar in text.unicodeScalars {
            switch scalar {
            case "&": out += "&amp;"
            case "<": out += "&lt;"
            case ">": out += "&gt;"
            case "\"": out += "&quot;"
            case "'": out += "&apos;"
            case "\t", "\n", "\r": out.unicodeScalars.append(scalar)
            default:
                if scalar.value >= 0x20 { out.unicodeScalars.append(scalar) }
            }
        }
        return out
    }

    static func coordinate(latitude: Double, longitude: Double, altitude: Double? = nil) -> String {
        var text = fixed(longitude, 7) + "," + fixed(latitude, 7)
        if let altitude, altitude.isFinite { text += "," + fixed(altitude, 1) }
        return text
    }

    /// Locale-independent fixed-point text; "-0.0" becomes "0.0" to match Android.
    static func fixed(_ value: Double, _ decimals: Int) -> String {
        let text = String(format: "%.\(decimals)f", value)
        if text.hasPrefix("-"), text.dropFirst().allSatisfy({ $0 == "0" || $0 == "." }) {
            return String(text.dropFirst())
        }
        return text
    }

    // MARK: - Writer

    private struct Style {
        let id: String
        let lines: [String]
    }

    private struct Writer {
        let iconPaths: [UUID: String]
        private(set) var styles: [Style] = []
        private var styleIDs = Set<String>()

        init(iconPaths: [UUID: String]) {
            self.iconPaths = iconPaths
        }

        mutating func use(_ id: String, _ lines: [String]) -> String {
            if styleIDs.insert(id).inserted { styles.append(Style(id: id, lines: lines)) }
            return id
        }

        mutating func placemark(for waypoint: Waypoint, indent: Int) -> [String] {
            let styleID: String
            if let path = iconPaths[waypoint.id] {
                let stem = (path as NSString).lastPathComponent.replacingOccurrences(of: ".png", with: "")
                styleID = use("icon-\(stem)", [
                    "<IconStyle>",
                    "  <Icon>",
                    "    <href>\(KMLExporter.escape(path))</href>",
                    "  </Icon>",
                    #"  <hotSpot x="0.5" y="0.5" xunits="fraction" yunits="fraction"/>"#,
                    "</IconStyle>",
                ])
            } else {
                let affiliation = SymbolListAffiliation(waypoint)
                styleID = use("sym-\(affiliation.rawValue)", [
                    "<IconStyle>",
                    "  <color>\(KMLExporter.affiliationColor(affiliation))</color>",
                    "</IconStyle>",
                ])
            }
            var lines = [
                #"<Placemark id="tacmap-\#(waypoint.id.uuidString.lowercased())">"#,
                "  <name>\(KMLExporter.escape(waypoint.name))</name>",
            ]
            if let notes = waypoint.notes, !notes.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                lines.append("  <description>\(KMLExporter.escape(notes))</description>")
            }
            let grid = MGRSFormatter.string(from: waypoint.coordinate, spaced: true)
            lines += [
                "  <styleUrl>#\(styleID)</styleUrl>",
                "  <ExtendedData>",
                #"    <Data name="MGRS">"#,
                "      <value>\(KMLExporter.escape(grid))</value>",
                "    </Data>",
                "  </ExtendedData>",
                "  <Point>",
                "    <coordinates>\(KMLExporter.coordinate(latitude: waypoint.latitude, longitude: waypoint.longitude, altitude: waypoint.elevation))</coordinates>",
                "  </Point>",
                "</Placemark>",
            ]
            let pad = String(repeating: " ", count: indent)
            return lines.map { pad + $0 }
        }

        mutating func placemark(for shape: DrawingShape, indent: Int) -> [String] {
            let points = shape.effectiveCoordinates
            let stroke = KMLExporter.kmlColor(hex: shape.style.strokeColorHex, alpha: 255)
            let width = KMLExporter.fixed(shape.style.strokeWidth, 1)
            let styleID: String
            let geometry: [String]
            switch shape.kind {
            case .point:
                guard let point = points.first else { return [] }
                styleID = use("point-\(stroke)", ["<IconStyle>", "  <color>\(stroke)</color>", "</IconStyle>"])
                geometry = [
                    "<Point>",
                    "  <coordinates>\(KMLExporter.coordinate(latitude: point.latitude, longitude: point.longitude))</coordinates>",
                    "</Point>",
                ]
            case .polyline, .freedraw:
                guard points.count >= 2 else { return [] }
                styleID = use("line-\(stroke)-\(width)", [
                    "<LineStyle>", "  <color>\(stroke)</color>", "  <width>\(width)</width>", "</LineStyle>",
                ])
                geometry = [
                    "<LineString>",
                    "  <tessellate>1</tessellate>",
                    "  <coordinates>\(KMLExporter.coordinateList(points))</coordinates>",
                    "</LineString>",
                ]
            case .polygon:
                guard points.count >= 3 else { return [] }
                let alpha = Int((shape.style.fillOpacity * 255).rounded())
                let fill = KMLExporter.kmlColor(hex: shape.style.fillColorHex ?? shape.style.strokeColorHex, alpha: alpha)
                styleID = use("poly-\(stroke)-\(width)-\(fill)", [
                    "<LineStyle>", "  <color>\(stroke)</color>", "  <width>\(width)</width>", "</LineStyle>",
                    "<PolyStyle>", "  <color>\(fill)</color>", "</PolyStyle>",
                ])
                var ring = points
                if let first = ring.first, first != ring.last { ring.append(first) }
                geometry = [
                    "<Polygon>",
                    "  <tessellate>1</tessellate>",
                    "  <outerBoundaryIs>",
                    "    <LinearRing>",
                    "      <coordinates>\(KMLExporter.coordinateList(ring))</coordinates>",
                    "    </LinearRing>",
                    "  </outerBoundaryIs>",
                    "</Polygon>",
                ]
            }
            var lines = [#"<Placemark id="tacmap-\#(shape.id.uuidString.lowercased())">"#]
            if let name = shape.name, !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                lines.append("  <name>\(KMLExporter.escape(name))</name>")
            }
            if let notes = shape.notes, !notes.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                lines.append("  <description>\(KMLExporter.escape(notes))</description>")
            }
            lines.append("  <styleUrl>#\(styleID)</styleUrl>")
            lines += geometry.map { "  " + $0 }
            lines.append("</Placemark>")
            let pad = String(repeating: " ", count: indent)
            return lines.map { pad + $0 }
        }
    }

    static func coordinateList(_ points: [Coordinate2D]) -> String {
        points.map { coordinate(latitude: $0.latitude, longitude: $0.longitude) }.joined(separator: " ")
    }

    static func affiliationColor(_ affiliation: SymbolListAffiliation) -> String {
        switch affiliation {
        case .friend: return "ffd85f0e"
        case .hostile: return "ff1f28d8"
        case .neutral: return "ff348a1e"
        case .unknown: return "ff00a4e2"
        case .other: return "ffffffff"
        }
    }
}
