import UIKit
import CryptoKit

/// KML and KMZ files for sharing. KMZ packs each symbol's rendered PNG next to
/// the KML so Google Earth, ATAK and GIS tools show the actual APP-6, task,
/// marker and custom symbols instead of generic pins.
@MainActor
enum KMZExporter {
    enum Format {
        case kml
        case kmz

        var fileExtension: String {
            switch self {
            case .kml: "kml"
            case .kmz: "kmz"
            }
        }

        var shareTitle: String {
            switch self {
            case .kml: Messages.exportKmlTitle()
            case .kmz: Messages.exportKmzTitle()
            }
        }
    }

    static let fileNamePrefix = "TacMap-MissionObjects"

    /// The same image the map shows for this symbol.
    static func icon(for waypoint: Waypoint) -> UIImage? {
        switch waypoint.kind {
        case .military(let spec):
            return MilitarySymbolRenderer.image(for: spec, size: 56)
        case .controlMeasure(let measure):
            return TacticalControlMeasureRenderer.image(for: measure,
                                                        rotation: waypoint.rotation,
                                                        color: waypoint.taskColor)
        case .marker(let marker):
            return MarkerSymbolRenderer.image(for: marker, size: 34)
        case .generic:
            return BubbleView.genericImage()
        }
    }

    /// KMZ archive: `doc.kml` first, then one PNG per distinct symbol image.
    static func kmz(waypoints: [Waypoint], drawings: [DrawingShape], layers: [DrawingLayer]) -> Data {
        var iconPaths: [UUID: String] = [:]
        var files: [(path: String, png: Data)] = []
        var seen = Set<String>()
        for waypoint in waypoints {
            guard let png = icon(for: waypoint)?.pngData() else { continue }
            let path = "files/icons/\(iconName(for: png)).png"
            iconPaths[waypoint.id] = path
            if seen.insert(path).inserted { files.append((path, png)) }
        }
        let kml = KMLExporter.kml(waypoints: waypoints, drawings: drawings,
                                  layers: layers, iconPaths: iconPaths)
        var zip = ZipArchiveWriter()
        zip.add(path: "doc.kml", contents: Data(kml.utf8))
        for file in files { zip.add(path: file.path, contents: file.png) }
        return zip.archive()
    }

    /// Stable short name from the image bytes, so identical symbols share one file.
    static func iconName(for png: Data) -> String {
        SHA256.hash(data: png).prefix(8).map { String(format: "%02x", $0) }.joined()
    }

    static func exportToFile(format: Format,
                             waypoints: [Waypoint],
                             drawings: [DrawingShape],
                             layers: [DrawingLayer]) throws -> URL {
        let data: Data
        switch format {
        case .kml:
            data = Data(KMLExporter.kml(waypoints: waypoints, drawings: drawings, layers: layers).utf8)
        case .kmz:
            data = kmz(waypoints: waypoints, drawings: drawings, layers: layers)
        }
        let stamp = ISO8601DateFormatter().string(from: .now)
            .replacingOccurrences(of: ":", with: "-")
        let url = try ExportFileSecurity.freshURL(
            fileName: "\(fileNamePrefix)-\(stamp).\(format.fileExtension)"
        )
        try data.write(to: url, options: [.atomic, .completeFileProtection])
        try ExportFileSecurity.protect(url)
        return url
    }
}
