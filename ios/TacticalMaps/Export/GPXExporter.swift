import Foundation

/// Serialises a recorded track into GPX 1.1 (the universal GPS-exchange format
/// read by Garmin, Strava, Gaia, QGIS, Google Earth, etc.).
enum GPXExporter {

    private static let isoFormatter: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f
    }()

    static func export(points: [TrackPoint], name: String = "TacMap Track") -> String {
        var xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1" creator="TacMap" xmlns="http://www.topografix.com/GPX/1/1">
          <trk>
            <name>\(escape(name))</name>
            <trkseg>

        """
        for p in points {
            xml += "      <trkpt lat=\"\(p.coordinate.latitude)\" lon=\"\(p.coordinate.longitude)\">\n"
            if let ele = p.elevation {
                xml += "        <ele>\(ele)</ele>\n"
            }
            xml += "        <time>\(isoFormatter.string(from: p.time))</time>\n"
            xml += "      </trkpt>\n"
        }
        xml += """
            </trkseg>
          </trk>
        </gpx>

        """
        return xml
    }

    /// Write the GPX to a temp file for `ShareLink` / Files export.
    static func exportToFile(points: [TrackPoint],
                             name: String = "TacMap Track",
                             timestamp: Int) throws -> URL {
        let gpx = export(points: points, name: name)
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("TacMap-track-\(timestamp).gpx")
        try gpx.data(using: .utf8)!.write(to: url)
        return url
    }

    private static func escape(_ s: String) -> String {
        s.replacingOccurrences(of: "&", with: "&amp;")
            .replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;")
    }
}
