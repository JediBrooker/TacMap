import Foundation
import PDFKit
import CoreGraphics
import CoreLocation
import MGRS
import Grid

/// Reads georeferencing metadata from a PDF.
///
/// Supports the most common case for topographic GeoPDFs: an **OGC LGIDict**
/// containing a `CTM` (PDF-user-space → projection) and a `Neatline`
/// polygon. The Neatline bounding rect (in PDF user space) is *always*
/// returned when present — even when the projection is one we can't decode
/// for geographic bounds (UTM etc.). That lets callers crop title-block /
/// legend marginalia from the rasterised page even when geographic bounds
/// come from a hardcoded known-sheet entry.
enum GeoPDFReader {

    struct Bounds: Hashable {
        let southWest: CLLocationCoordinate2D
        let northEast: CLLocationCoordinate2D
        /// PDF-page crop rect (PDF user space, y-up, origin bottom-left)
        /// covering just the map content (LGIDict Neatline bounding box).
        let pdfCropRect: CGRect?

        var centre: CLLocationCoordinate2D {
            CLLocationCoordinate2D(
                latitude:  (southWest.latitude  + northEast.latitude)  / 2,
                longitude: (southWest.longitude + northEast.longitude) / 2
            )
        }

        static func == (a: Bounds, b: Bounds) -> Bool {
            a.southWest.latitude  == b.southWest.latitude &&
            a.southWest.longitude == b.southWest.longitude &&
            a.northEast.latitude  == b.northEast.latitude &&
            a.northEast.longitude == b.northEast.longitude &&
            a.pdfCropRect == b.pdfCropRect
        }

        func hash(into hasher: inout Hasher) {
            hasher.combine(southWest.latitude)
            hasher.combine(southWest.longitude)
            hasher.combine(northEast.latitude)
            hasher.combine(northEast.longitude)
        }
    }

    /// Try GeoPDF metadata first, then known-filename overrides.
    /// Tries TWO georeferencing conventions in order:
    ///   1. Adobe Geospatial (ISO 32000-2): page /VP → /Measure /Subtype GEO
    ///      with /GPTS (geographic corners) and /BBox (viewport crop). This is
    ///      what most modern topo PDFs (ADF/AUSLIG, USGS quads, Avenza imports)
    ///      use. Gives pixel-accurate bounds + crop in one shot.
    ///   2. OGC LGIDict (older): /LGIDict on page dict with /CTM + /Neatline.
    static func bounds(from url: URL) -> Bounds? {
        // (1) Adobe Geospatial — the modern, common path. If present, trust it.
        if let adobe = parseAdobeGeospatial(url: url),
           let sw = adobe.southWest, let ne = adobe.northEast {
            return Bounds(southWest: sw, northEast: ne, pdfCropRect: adobe.pdfCropRect)
        }

        // (2) OGC LGIDict fallback.
        let raw = parseLGIDictRaw(url: url)
        if let p = raw, let sw = p.southWest, let ne = p.northEast {
            return Bounds(southWest: sw, northEast: ne, pdfCropRect: p.pdfCropRect)
        }

        // (3) Last-resort known-sheet table for PDFs without metadata.
        let stem = url.deletingPathExtension().lastPathComponent
        if let known = knownSheets[stem] {
            return Bounds(
                southWest: known.southWest,
                northEast: known.northEast,
                pdfCropRect: known.pdfCropRect ?? raw?.pdfCropRect
            )
        }

        return nil
    }

    /// Hardcoded sheet bounds for known demo PDFs. 1:25,000 NSW topo sheet
    /// Holsworthy North — the PDF lacks a CTM in its LGIDict so we can’t
    /// pixel-align it. We render the FULL page across the approximate
    /// graticule bounds; the user gets the whole sheet visible (legend +
    /// title block included). Pixel-accurate registration needs fiduciary
    /// calibration — see `AffineFitter` in this directory; UI TBD.
    private static let knownSheets: [String: Bounds] = [
        "Holsworthy_North_1-25000": Bounds(
            southWest: CLLocationCoordinate2D(latitude: -34.0625, longitude: 150.9375),
            northEast: CLLocationCoordinate2D(latitude: -33.9375, longitude: 151.0625),
            pdfCropRect: nil   // render whole page — nothing cut off
        )
    ]

    /// Mutable container so the parser can fill in whatever it can extract,
    /// independent of whether geographic projection is supported.
    private struct ParsedLGI {
        var southWest: CLLocationCoordinate2D?
        var northEast: CLLocationCoordinate2D?
        var pdfCropRect: CGRect?
    }

    // MARK: - Adobe Geospatial (/VP /Measure) parsing
    //
    // PDF page can have a /VP entry: an array of Viewport dictionaries.
    // Each viewport has:
    //   /BBox        — [x_min y_min x_max y_max] in PDF user space (the page
    //                  region this viewport applies to)
    //   /Measure     — a Measure dictionary
    //     /Subtype /GEO          — geographic measurement
    //     /GPTS [lat0 lon0 lat1 lon1 …]  — geographic corners (WGS84)
    //     /LPTS [x0 y0 x1 y1 …]          — corresponding viewport-space corners
    //                                       (typically 0–1 normalised)
    //     /GCS << … >>                    — Geographic Coordinate System
    //
    // For our purposes: bounds = bbox of GPTS values, crop = BBox.

    private static func parseAdobeGeospatial(url: URL) -> ParsedLGI? {
        guard let doc  = PDFDocument(url: url),
              let page = doc.page(at: 0),
              let cg   = page.pageRef else { return nil }
        let pageDict = cg.dictionary

        var vpArr: CGPDFArrayRef?
        guard CGPDFDictionaryGetArray(pageDict!, "VP", &vpArr),
              let vpRef = vpArr,
              CGPDFArrayGetCount(vpRef) > 0 else {
            return nil
        }

        // First viewport is typically the whole map. Take it.
        var vpDict: CGPDFDictionaryRef?
        guard CGPDFArrayGetDictionary(vpRef, 0, &vpDict),
              let viewport = vpDict else { return nil }

        // /BBox — four numbers [x_min y_min x_max y_max] in PDF user space.
        var bboxArr: CGPDFArrayRef?
        var crop: CGRect?
        if CGPDFDictionaryGetArray(viewport, "BBox", &bboxArr),
           let bbox = bboxArr,
           CGPDFArrayGetCount(bbox) >= 4 {
            var nums = [Double](repeating: 0, count: 4)
            var ok = true
            for i in 0..<4 {
                var n: CGPDFReal = 0
                if CGPDFArrayGetNumber(bbox, i, &n) { nums[i] = Double(n) } else { ok = false; break }
            }
            if ok {
                crop = CGRect(x: nums[0], y: nums[1],
                              width:  nums[2] - nums[0],
                              height: nums[3] - nums[1])
            }
        }

        // /Measure dict.
        var measureDict: CGPDFDictionaryRef?
        guard CGPDFDictionaryGetDictionary(viewport, "Measure", &measureDict),
              let measure = measureDict else {
            // Viewport without Measure — useless for georeferencing.
            return crop != nil ? ParsedLGI(southWest: nil, northEast: nil, pdfCropRect: crop) : nil
        }

        // /Subtype must be /GEO for geographic measurement.
        var subtypePtr: UnsafePointer<Int8>?
        if CGPDFDictionaryGetName(measure, "Subtype", &subtypePtr),
           let sp = subtypePtr {
            let subtype = String(cString: sp)
            guard subtype == "GEO" else {
                NSLog("[GeoPDF] Adobe /Measure /Subtype=\(subtype) (not GEO) — skipping")
                return crop != nil ? ParsedLGI(southWest: nil, northEast: nil, pdfCropRect: crop) : nil
            }
        }

        // /GPTS — array of lat/lon pairs.
        var gptsArr: CGPDFArrayRef?
        guard CGPDFDictionaryGetArray(measure, "GPTS", &gptsArr),
              let gpts = gptsArr else {
            NSLog("[GeoPDF] Adobe /Measure missing /GPTS")
            return crop != nil ? ParsedLGI(southWest: nil, northEast: nil, pdfCropRect: crop) : nil
        }

        let count = CGPDFArrayGetCount(gpts)
        var lats: [Double] = []
        var lons: [Double] = []
        var i = 0
        while i + 1 < count {
            var lat: CGPDFReal = 0, lon: CGPDFReal = 0
            guard CGPDFArrayGetNumber(gpts, i,     &lat),
                  CGPDFArrayGetNumber(gpts, i + 1, &lon) else { break }
            lats.append(Double(lat))
            lons.append(Double(lon))
            i += 2
        }

        guard let minLat = lats.min(), let maxLat = lats.max(),
              let minLon = lons.min(), let maxLon = lons.max(),
              minLat != maxLat, minLon != maxLon else {
            NSLog("[GeoPDF] Adobe /GPTS empty or degenerate")
            return crop != nil ? ParsedLGI(southWest: nil, northEast: nil, pdfCropRect: crop) : nil
        }

        // Sanity: real-Earth values.
        let lonRange: ClosedRange<Double> = -180.0...180.0
        let latRange: ClosedRange<Double> = -90.0...90.0
        guard lonRange.contains(minLon), lonRange.contains(maxLon),
              latRange.contains(minLat), latRange.contains(maxLat) else {
            NSLog("[GeoPDF] Adobe /GPTS values out of Earth range — ignoring")
            return crop != nil ? ParsedLGI(southWest: nil, northEast: nil, pdfCropRect: crop) : nil
        }

        NSLog("[GeoPDF] Adobe Geospatial decoded: \(lats.count) GPTS, SW=\(minLat),\(minLon) NE=\(maxLat),\(maxLon) BBox=\(String(describing: crop))")

        return ParsedLGI(
            southWest: CLLocationCoordinate2D(latitude: minLat, longitude: minLon),
            northEast: CLLocationCoordinate2D(latitude: maxLat, longitude: maxLon),
            pdfCropRect: crop
        )
    }

    // MARK: - LGIDict parsing

    /// PDF spec lets LGIDict CTM/Neatline values be encoded as either PDF
    /// numbers OR PDF strings wrapped in parens. ADF and AUSLIG topo sheets
    /// use the string encoding (e.g. `(135.8274208613)`). CGPDFArrayGetNumber
    /// fails on strings, so we fall back to CGPDFArrayGetString + parse.
    private static func arrayReal(_ arr: CGPDFArrayRef, _ idx: Int) -> Double? {
        var num: CGPDFReal = 0
        if CGPDFArrayGetNumber(arr, idx, &num) { return Double(num) }
        var sRef: CGPDFStringRef?
        if CGPDFArrayGetString(arr, idx, &sRef), let s = sRef,
           let cf = CGPDFStringCopyTextString(s) as String? {
            return Double(cf)
        }
        return nil
    }

    private static func dictString(_ dict: CGPDFDictionaryRef, _ key: String) -> String? {
        var sRef: CGPDFStringRef?
        guard CGPDFDictionaryGetString(dict, key, &sRef), let s = sRef else { return nil }
        return CGPDFStringCopyTextString(s) as String?
    }

    /// Read a dictionary value that may be encoded as either a PDF Name
    /// (`/TC`) or a PDF String (`(TC)`). ADF/AUSLIG topo PDFs store all
    /// LGIDict enum values as STRINGS, which is why our earlier reads
    /// silently returned nil and the parser defaulted to LL projection.
    private static func dictName(_ dict: CGPDFDictionaryRef, _ key: String) -> String? {
        var pPtr: UnsafePointer<Int8>?
        if CGPDFDictionaryGetName(dict, key, &pPtr), let p = pPtr {
            return String(cString: p)
        }
        return dictString(dict, key)
    }

    private static func dictInt(_ dict: CGPDFDictionaryRef, _ key: String) -> Int? {
        var n: CGPDFInteger = 0
        // PDF integers can also be encoded as strings in LGIDict.
        if CGPDFDictionaryGetInteger(dict, key, &n) { return Int(n) }
        if let s = dictString(dict, key) { return Int(s) }
        return nil
    }

    private static func parseLGIDictRaw(url: URL) -> ParsedLGI? {
        guard let doc  = PDFDocument(url: url),
              let page = doc.page(at: 0),
              let cg   = page.pageRef else { return nil }
        let pageDict = cg.dictionary
        let mediaBox = page.bounds(for: .mediaBox)

        // Collect ALL LGIDict entries. ADF/AUSLIG sheets have multiple:
        // BoundaryGuide, Elevation, Adjoining Sheet Guide, Layers — we want
        // "Layers" (the main map content). Older single-dict format also OK.
        var entries: [CGPDFDictionaryRef] = []
        var arr: CGPDFArrayRef?
        var single: CGPDFDictionaryRef?

        if CGPDFDictionaryGetArray(pageDict!, "LGIDict", &arr),
           let arrRef = arr {
            let count = CGPDFArrayGetCount(arrRef)
            for i in 0..<count {
                var e: CGPDFDictionaryRef?
                if CGPDFArrayGetDictionary(arrRef, i, &e), let entry = e {
                    entries.append(entry)
                }
            }
        } else if CGPDFDictionaryGetDictionary(pageDict!, "LGIDict", &single),
                  let s = single {
            entries.append(s)
        }

        guard !entries.isEmpty else { return nil }

        // Pick the "Layers" entry (main map) if present; else fall back to first.
        let chosen = entries.first { dict in
            dictString(dict, "Description") == "Layers"
        } ?? entries[0]
        let chosenDesc = dictString(chosen, "Description") ?? "<none>"
        NSLog("[GeoPDF] LGIDict: \(entries.count) entries; using '\(chosenDesc)'")
        let entryDict = chosen

        // CTM (PDF user space → projection coords). 6 numbers, may be strings.
        var ctm = [Double](repeating: 0, count: 6)
        var ctmArr: CGPDFArrayRef?
        var haveCTM = false
        if CGPDFDictionaryGetArray(entryDict, "CTM", &ctmArr),
           let ctmRef = ctmArr,
           CGPDFArrayGetCount(ctmRef) >= 6 {
            haveCTM = true
            for i in 0..<6 {
                if let v = arrayReal(ctmRef, i) { ctm[i] = v }
                else { haveCTM = false; break }
            }
        }

        // Neatline: array of x,y pairs in PDF user space (may be strings).
        var neatlinePts: [CGPoint] = []
        var nlArr: CGPDFArrayRef?
        if CGPDFDictionaryGetArray(entryDict, "Neatline", &nlArr),
           let nlRef = nlArr {
            let count = CGPDFArrayGetCount(nlRef)
            var i = 0
            while i + 1 < count {
                guard let x = arrayReal(nlRef, i),
                      let y = arrayReal(nlRef, i + 1) else { break }
                neatlinePts.append(CGPoint(x: x, y: y))
                i += 2
            }
        }
        if neatlinePts.isEmpty {
            neatlinePts = [
                CGPoint(x: mediaBox.minX, y: mediaBox.minY),
                CGPoint(x: mediaBox.maxX, y: mediaBox.minY),
                CGPoint(x: mediaBox.maxX, y: mediaBox.maxY),
                CGPoint(x: mediaBox.minX, y: mediaBox.maxY)
            ]
        }

        // Neatline crop in PDF user space — always available when Neatline is.
        let pdfXs = neatlinePts.map { Double($0.x) }
        let pdfYs = neatlinePts.map { Double($0.y) }
        let pdfCrop: CGRect? = {
            guard let minPX = pdfXs.min(), let maxPX = pdfXs.max(),
                  let minPY = pdfYs.min(), let maxPY = pdfYs.max(),
                  maxPX > minPX, maxPY > minPY else { return nil }
            return CGRect(x: minPX, y: minPY,
                          width: maxPX - minPX,
                          height: maxPY - minPY)
        }()

        // Projection. We now handle the full set:
        //   LL / LongLat — geographic.
        //   UT          — UTM (zone + hemisphere).
        //   TC          — Transverse Mercator (arbitrary central meridian).
        //   LC          — Lambert Conformal Conic (two standard parallels).
        // Parameters are read directly from /Projection; /Display is consulted
        // for the UTM shortcut (lets TC PDFs whose CentralMeridian matches a
        // standard UTM zone reuse the NGA UTM helper).
        var projectionType = "LL"
        var utmZone: Int = 0
        var utmHemiName = "N"
        var centralMeridian: Double = 0
        var originLatitude:  Double = 0
        var falseEasting:    Double = 0
        var falseNorthing:   Double = 0
        var scaleFactor:     Double = 1.0
        var stdParallel1:    Double = 0
        var stdParallel2:    Double = 0
        var datumCode:       String = "WE"

        // Read a Double that may be encoded as PDF number, PDF string, or via
        // dictName fallback.
        func dictReal(_ dict: CGPDFDictionaryRef, _ key: String) -> Double? {
            var n: CGPDFReal = 0
            if CGPDFDictionaryGetNumber(dict, key, &n) { return Double(n) }
            if let s = dictString(dict, key) { return Double(s) }
            return nil
        }

        var projDict: CGPDFDictionaryRef?
        if CGPDFDictionaryGetDictionary(entryDict, "Projection", &projDict),
           let pDict = projDict {
            if let t = dictName(pDict, "ProjectionType") { projectionType = t }
            if let z = dictInt(pDict,  "Zone")                { utmZone        = z }
            if let h = dictName(pDict, "Hemisphere")          { utmHemiName    = h.uppercased() }
            if let v = dictReal(pDict, "CentralMeridian")     { centralMeridian = v }
            if let v = dictReal(pDict, "OriginLatitude")      { originLatitude  = v }
            if let v = dictReal(pDict, "FalseEasting")        { falseEasting    = v }
            if let v = dictReal(pDict, "FalseNorthing")       { falseNorthing   = v }
            if let v = dictReal(pDict, "ScaleFactor")         { scaleFactor     = v }
            if let v = dictReal(pDict, "StandardParallelOne") { stdParallel1    = v }
            if let v = dictReal(pDict, "StandardParallelTwo") { stdParallel2    = v }
            if let d = dictName(pDict, "Datum")               { datumCode       = d.uppercased() }
        }

        // /Display dict often carries the easy-to-use UTM mapping (Zone, Hemi)
        // even when the main /Projection is TC. Prefer Display values when set.
        var displayDict: CGPDFDictionaryRef?
        if CGPDFDictionaryGetDictionary(entryDict, "Display", &displayDict),
           let dDict = displayDict {
            if let dt = dictName(dDict, "ProjectionType"), dt == "UT" {
                if let z = dictInt(dDict, "Zone")  { utmZone = z }
                if let h = dictName(dDict, "Hemisphere")  { utmHemiName = h.uppercased() }
            }
        }

        guard haveCTM else {
            NSLog("[GeoPDF] LGIDict has no CTM — returning crop only")
            return ParsedLGI(southWest: nil, northEast: nil, pdfCropRect: pdfCrop)
        }

        let a = ctm[0], b = ctm[1], c = ctm[2], d = ctm[3], e = ctm[4], f = ctm[5]

        // Apply the affine to each Neatline point.
        let projected = neatlinePts.map { p -> (x: Double, y: Double) in
            let xx = Double(p.x), yy = Double(p.y)
            return (a * xx + c * yy + e, b * xx + d * yy + f)
        }

        var lats: [Double] = []
        var lons: [Double] = []

        // Build a Projection from what we parsed and dispatch.
        let ellipsoid = Ellipsoid.forDatumCode(datumCode)
        let projection: Projection? = {
            switch projectionType {
            case "LL", "LongLat":
                return .longLat

            case "UT":
                guard utmZone > 0 else { return nil }
                let h: Hemisphere = (utmHemiName == "S") ? .SOUTH : .NORTH
                return .utm(zone: utmZone, hemisphere: h, ellipsoid: ellipsoid)

            case "TC":
                // Prefer the UTM shortcut when /Display gave us a zone AND the
                // TC parameters match a UTM zone (FE 500000, FN 10000000 for
                // south or 0 for north, k0 0.9996, central meridian = zone
                // central). For ADF/AUSLIG PDFs this hits.
                let cmMatchesZone = utmZone > 0 &&
                    abs(centralMeridian - (Double(utmZone) * 6 - 183)) < 0.01
                let isStandardUTM = cmMatchesZone &&
                    abs(falseEasting - 500_000) < 0.5 &&
                    abs(scaleFactor - 0.9996) < 1e-4
                if isStandardUTM {
                    let h: Hemisphere = (utmHemiName == "S") ? .SOUTH : .NORTH
                    return .utm(zone: utmZone, hemisphere: h, ellipsoid: ellipsoid)
                }
                // Otherwise solve the general TM directly with the LGIDict params.
                return .transverseMercator(
                    centralMeridian: centralMeridian,
                    originLatitude:  originLatitude,
                    falseEasting:    falseEasting,
                    falseNorthing:   falseNorthing,
                    scaleFactor:     scaleFactor == 0 ? 1.0 : scaleFactor,
                    ellipsoid:       ellipsoid
                )

            case "LC":
                // LCC needs two standard parallels. Some encodings omit
                // StandardParallelTwo for the "1SP" variant — treat as p2==p1.
                let p2 = stdParallel2 != 0 ? stdParallel2 : stdParallel1
                return .lambertConformalConic(
                    stdParallel1:    stdParallel1,
                    stdParallel2:    p2,
                    originLatitude:  originLatitude,
                    centralMeridian: centralMeridian,
                    falseEasting:    falseEasting,
                    falseNorthing:   falseNorthing,
                    ellipsoid:       ellipsoid
                )

            default:
                return nil
            }
        }()

        guard let proj = projection else {
            NSLog("[GeoPDF] LGIDict projection='\(projectionType)' not supported — crop only")
            return ParsedLGI(southWest: nil, northEast: nil, pdfCropRect: pdfCrop)
        }

        NSLog("[GeoPDF] dispatching projection=\(proj) datum=\(datumCode) (\(projected.count) corners)")
        for (idx, pt) in projected.enumerated() {
            guard let g = proj.inverse(easting: pt.x, northing: pt.y) else {
                NSLog("[GeoPDF] corner \(idx) inverse failed (E=\(pt.x), N=\(pt.y))")
                return ParsedLGI(southWest: nil, northEast: nil, pdfCropRect: pdfCrop)
            }
            NSLog("[GeoPDF] corner \(idx): E=\(pt.x) N=\(pt.y) -> lat=\(g.lat) lon=\(g.lon)")
            lats.append(g.lat)
            lons.append(g.lon)
        }

        guard let minLon = lons.min(), let maxLon = lons.max(),
              let minLat = lats.min(), let maxLat = lats.max() else {
            NSLog("[GeoPDF] decoded lats/lons empty — crop only")
            return ParsedLGI(southWest: nil, northEast: nil, pdfCropRect: pdfCrop)
        }

        let lonRange: ClosedRange<Double> = -180.0...180.0
        let latRange: ClosedRange<Double> = -90.0...90.0
        guard lonRange.contains(minLon),
              lonRange.contains(maxLon),
              latRange.contains(minLat),
              latRange.contains(maxLat),
              minLon != maxLon, minLat != maxLat else {
            NSLog("[GeoPDF] decoded bounds rejected: lat=\(minLat)..\(maxLat) lon=\(minLon)..\(maxLon)")
            return ParsedLGI(southWest: nil, northEast: nil, pdfCropRect: pdfCrop)
        }

        NSLog("[GeoPDF] LGIDict decoded bounds SW=\(minLat),\(minLon) NE=\(maxLat),\(maxLon)")
        return ParsedLGI(
            southWest: CLLocationCoordinate2D(latitude: minLat, longitude: minLon),
            northEast: CLLocationCoordinate2D(latitude: maxLat, longitude: maxLon),
            pdfCropRect: pdfCrop
        )
    }

    /// Centre-on-camera fallback: 10km square. Used when no metadata exists
    /// and the PDF isn't a known sheet — better than not rendering at all.
    static func fallbackBounds(centeredOn camera: CLLocationCoordinate2D,
                                halfWidthMetres: Double = 5000) -> Bounds {
        let metresPerDegLat = 111_320.0
        let metresPerDegLon = 111_320.0 * cos(camera.latitude * .pi / 180)
        let dLat = halfWidthMetres / metresPerDegLat
        let dLon = halfWidthMetres / metresPerDegLon
        return Bounds(
            southWest: CLLocationCoordinate2D(latitude:  camera.latitude - dLat, longitude: camera.longitude - dLon),
            northEast: CLLocationCoordinate2D(latitude:  camera.latitude + dLat, longitude: camera.longitude + dLon),
            pdfCropRect: nil
        )
    }
}
