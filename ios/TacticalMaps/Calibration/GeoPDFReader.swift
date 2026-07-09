import Foundation
import PDFKit
import CoreGraphics
import CoreLocation
import MGRS
import Grid

/// Reads georeferencing metadata from a PDF.
///
/// Handles the common case for topo GeoPDFs: an OGC LGIDict with a CTM
/// (PDF-user-space -> projection) and a Neatline polygon. The Neatline
/// bounding rect (PDF user space) is always returned when present, even
/// when we can't decode the projection for geographic bounds. That lets
/// callers crop title-block / legend marginalia from the rasterised page
/// even when geo bounds come from a hardcoded known-sheet entry.
enum GeoPDFReader {

    struct Bounds: Hashable {
        let southWest: CLLocationCoordinate2D
        let northEast: CLLocationCoordinate2D
        /// PDF-page crop rect (PDF user space, y-up, origin bottom-left)
        /// covering just the map content (LGIDict Neatline bounding box).
        let pdfCropRect: CGRect?
        /// Affine mapping PDF user-space points to WGS84, fitted from the
        /// GeoPDF control points (GPTS/LPTS). When present, overlay places
        /// the page with this transform (captures grid-convergence rotation
        /// and true scale) instead of stretching to the lat/lon box (which
        /// leaves the sheet's grid ~1 deg off true). nil for LGIDict /
        /// known-sheet / fallback paths.
        let placementAffine: AffineTransform2D?

        init(southWest: CLLocationCoordinate2D,
             northEast: CLLocationCoordinate2D,
             pdfCropRect: CGRect?,
             placementAffine: AffineTransform2D? = nil) {
            self.southWest = southWest
            self.northEast = northEast
            self.pdfCropRect = pdfCropRect
            self.placementAffine = placementAffine
        }

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
    /// Two georeferencing conventions tried in order:
    /// 1. Adobe Geospatial (ISO 32000-2): page /VP -> /Measure /Subtype GEO
    ///    with /GPTS (geographic corners) and /BBox (viewport crop). This is
    ///    what most modern topo PDFs use (ADF/AUSLIG, USGS quads, Avenza).
    ///    Gives pixel-accurate bounds + crop in one shot.
    /// 2. OGC LGIDict (older): /LGIDict on page dict with /CTM + /Neatline.
    static func bounds(from url: URL) -> Bounds? {
        // (1) Adobe Geospatial - the modern, common path. If present just trust it.
        if let adobe = parseAdobeGeospatial(url: url),
           let sw = adobe.southWest, let ne = adobe.northEast {
            return Bounds(southWest: sw, northEast: ne,
                          pdfCropRect: adobe.pdfCropRect,
                          placementAffine: adobe.placementAffine)
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

    /// Hardcoded sheet bounds for known demo PDFs. 1:25k NSW topo sheet
    /// Holsworthy North - the PDF lacks a CTM in its LGIDict so we can’t
    /// pixel-align it. Just render the full page across approximate graticule
    /// bounds so the user gets the whole sheet (legend + title block included).
    /// Pixel-accurate registration needs fiduciary calibration, see AffineFitter.
    private static let knownSheets: [String: Bounds] = [
        "Holsworthy_North_1-25000": Bounds(
            southWest: CLLocationCoordinate2D(latitude: -34.0625, longitude: 150.9375),
            northEast: CLLocationCoordinate2D(latitude: -33.9375, longitude: 151.0625),
            pdfCropRect: nil   // render whole page, nothing cut off
        )
    ]

    /// Mutable container so parser can fill in whatever it manages to extract,
    /// regardless of whether we support the geographic projection.
    private struct ParsedLGI {
        var southWest: CLLocationCoordinate2D?
        var northEast: CLLocationCoordinate2D?
        var pdfCropRect: CGRect?
        var placementAffine: AffineTransform2D?
    }

    // MARK: - Adobe Geospatial (/VP /Measure) parsing
    //
    // PDF page can have a /VP entry: array of Viewport dicts.
    // Each viewport has:
    //   /BBox    - [x_min y_min x_max y_max] in PDF user space
    //   /Measure - a Measure dict
    //     /Subtype /GEO       - geographic measurement
    //     /GPTS [lat0 lon0 ...] - geographic corners (WGS84)
    //     /LPTS [x0 y0 ...]     - viewport-space corners (0-1 normalised)
    //     /GCS << ... >>        - Geographic Coordinate System
    //
    // For our purposes: bounds = bbox of GPTS, crop = BBox.

    private static func parseAdobeGeospatial(url: URL) -> ParsedLGI? {
        guard let doc  = PDFDocument(url: url),
              let page = doc.page(at: 0),
              let cg   = page.pageRef,
              let pageDict = cg.dictionary else { return nil }

        var vpArr: CGPDFArrayRef?
        guard CGPDFDictionaryGetArray(pageDict, "VP", &vpArr),
              let vpRef = vpArr,
              CGPDFArrayGetCount(vpRef) > 0 else {
            return nil
        }

        // A georeferenced topo page usually has SEVERAL viewports: the map
        // neatline plus small marginalia insets (adjoining-sheets index, state
        // locator). Can't assume viewport[0] is the map - QTopo sheets list
        // the adjoining-sheets inset FIRST, and that thing is georeferenced
        // against a 145 deg E prime meridian, so trusting it drops the import
        // off the coast of West Africa. The map body is always the LARGEST
        // viewport by BBox area, so pick that one. Keep a crop-only fallback
        // for viewports that give us a BBox but no usable geo bounds.
        let count = CGPDFArrayGetCount(vpRef)
        var bestGeo: (area: Double, sw: CLLocationCoordinate2D, ne: CLLocationCoordinate2D, crop: CGRect?, viewport: CGPDFDictionaryRef)?
        var bestCrop: (area: Double, crop: CGRect)?

        for idx in 0..<count {
            var vpDict: CGPDFDictionaryRef?
            guard CGPDFArrayGetDictionary(vpRef, idx, &vpDict),
                  let viewport = vpDict else { continue }

            let crop = viewportCrop(viewport)
            let area = crop.map { Double($0.width) * Double($0.height) } ?? 0

            if let bounds = viewportGeoBounds(viewport) {
                if area > (bestGeo?.area ?? -1) {
                    bestGeo = (area, bounds.sw, bounds.ne, crop, viewport)
                }
            } else if let crop, area > (bestCrop?.area ?? -1) {
                bestCrop = (area, crop)
            }
        }

        if let g = bestGeo {
            // Fit an affine from the chosen viewport's GPTS/LPTS control points
            // so page gets placed with true rotation/scale instead of being
            // stretched to the lat/lon box. Falls back to nil (bbox placement)
            // if viewport lacks LPTS or the fit is degenerate.
            let affine = g.crop.flatMap { viewportAffine(g.viewport, crop: $0) }
            NSLog("[GeoPDF] Adobe Geospatial: \(count) viewport(s); chose largest geo (area=\(Int(g.area))) SW=\(g.sw.latitude),\(g.sw.longitude) NE=\(g.ne.latitude),\(g.ne.longitude) crop=\(String(describing: g.crop)) affine=\(affine != nil ? "yes" : "no")")
            return ParsedLGI(southWest: g.sw, northEast: g.ne, pdfCropRect: g.crop, placementAffine: affine)
        }
        if let c = bestCrop {
            NSLog("[GeoPDF] Adobe Geospatial: no decodable GPTS in \(count) viewport(s) - returning largest crop only")
            return ParsedLGI(southWest: nil, northEast: nil, pdfCropRect: c.crop)
        }
        return nil
    }

    /// /BBox -> a well-formed crop rect in PDF user space (y-up, origin
    /// bottom-left). USGS US Topo sometimes writes lly > ury, so take
    /// min/max to avoid a negative-height rect.
    private static func viewportCrop(_ viewport: CGPDFDictionaryRef) -> CGRect? {
        var bboxArr: CGPDFArrayRef?
        guard CGPDFDictionaryGetArray(viewport, "BBox", &bboxArr),
              let bbox = bboxArr,
              CGPDFArrayGetCount(bbox) >= 4 else { return nil }
        var nums = [Double](repeating: 0, count: 4)
        for i in 0..<4 {
            var n: CGPDFReal = 0
            guard CGPDFArrayGetNumber(bbox, i, &n) else { return nil }
            nums[i] = Double(n)
        }
        let x0 = min(nums[0], nums[2]), x1 = max(nums[0], nums[2])
        let y0 = min(nums[1], nums[3]), y1 = max(nums[1], nums[3])
        guard x1 > x0, y1 > y0 else { return nil }
        return CGRect(x: x0, y: y0, width: x1 - x0, height: y1 - y0)
    }

    /// /Measure (/Subtype GEO) -> SW/NE geographic corners from /GPTS, folding
    /// GCS prime-meridian offset into longitude. Returns nil when viewport
    /// has no usable geographic measurement.
    private static func viewportGeoBounds(_ viewport: CGPDFDictionaryRef)
        -> (sw: CLLocationCoordinate2D, ne: CLLocationCoordinate2D)? {

        var measureDict: CGPDFDictionaryRef?
        guard CGPDFDictionaryGetDictionary(viewport, "Measure", &measureDict),
              let measure = measureDict else { return nil }

        // /Subtype must be /GEO for geographic measurement.
        var subtypePtr: UnsafePointer<Int8>?
        if CGPDFDictionaryGetName(measure, "Subtype", &subtypePtr), let sp = subtypePtr {
            guard String(cString: sp) == "GEO" else { return nil }
        }

        var gptsArr: CGPDFArrayRef?
        guard CGPDFDictionaryGetArray(measure, "GPTS", &gptsArr),
              let gpts = gptsArr else { return nil }

        // GPTS longitudes are relative to GCS prime meridian (Greenwich for
        // the map body, but 145 deg E on some QTopo insets).
        let primeMeridian = measurePrimeMeridian(measure)

        let count = CGPDFArrayGetCount(gpts)
        var lats: [Double] = []
        var lons: [Double] = []
        var i = 0
        while i + 1 < count {
            var lat: CGPDFReal = 0, lon: CGPDFReal = 0
            guard CGPDFArrayGetNumber(gpts, i,     &lat),
                  CGPDFArrayGetNumber(gpts, i + 1, &lon) else { break }
            lats.append(Double(lat))
            lons.append(Double(lon) + primeMeridian)
            i += 2
        }

        guard let minLat = lats.min(), let maxLat = lats.max(),
              let minLon = lons.min(), let maxLon = lons.max(),
              minLat != maxLat, minLon != maxLon else { return nil }

        // Sanity: real-Earth values.
        let lonRange: ClosedRange<Double> = -180.0...180.0
        let latRange: ClosedRange<Double> = -90.0...90.0
        guard lonRange.contains(minLon), lonRange.contains(maxLon),
              latRange.contains(minLat), latRange.contains(maxLat) else { return nil }

        return (CLLocationCoordinate2D(latitude: minLat, longitude: minLon),
                CLLocationCoordinate2D(latitude: maxLat, longitude: maxLon))
    }

    /// /Measure -> least-squares affine (PDF user-space -> WGS84) fitted from
    /// GPTS/LPTS control points. LPTS are normalised (0-1) within viewport BBox
    /// so each PDF-space control point is crop.origin + lpts * crop.size - same
    /// crop the page is rasterised against, keeps the fit and render consistent.
    /// Returns nil if LPTS is absent or points are degenerate.
    private static func viewportAffine(_ viewport: CGPDFDictionaryRef, crop: CGRect) -> AffineTransform2D? {
        var measureDict: CGPDFDictionaryRef?
        guard CGPDFDictionaryGetDictionary(viewport, "Measure", &measureDict),
              let measure = measureDict else { return nil }

        var gptsArr: CGPDFArrayRef?
        var lptsArr: CGPDFArrayRef?
        guard CGPDFDictionaryGetArray(measure, "GPTS", &gptsArr), let gpts = gptsArr,
              CGPDFDictionaryGetArray(measure, "LPTS", &lptsArr), let lpts = lptsArr
        else { return nil }

        let primeMeridian = measurePrimeMeridian(measure)
        let pairs = min(CGPDFArrayGetCount(gpts), CGPDFArrayGetCount(lpts)) / 2
        guard pairs >= 3 else { return nil }

        let ox = Double(crop.minX), oy = Double(crop.minY)
        let cw = Double(crop.width), ch = Double(crop.height)
        var fiducials: [Fiduciary] = []
        for j in 0..<pairs {
            var lat: CGPDFReal = 0, lon: CGPDFReal = 0
            var nx: CGPDFReal = 0, ny: CGPDFReal = 0
            guard CGPDFArrayGetNumber(gpts, j * 2,     &lat),
                  CGPDFArrayGetNumber(gpts, j * 2 + 1, &lon),
                  CGPDFArrayGetNumber(lpts, j * 2,     &nx),
                  CGPDFArrayGetNumber(lpts, j * 2 + 1, &ny) else { return nil }
            fiducials.append(Fiduciary(
                pdfX: ox + Double(nx) * cw,
                pdfY: oy + Double(ny) * ch,
                mgrs: "",
                latitude:  Double(lat),
                longitude: Double(lon) + primeMeridian
            ))
        }
        return try? AffineFitter.fit(fiducials).transform
    }

    /// GPTS longitudes are measured from the GCS prime meridian, almost always
    /// Greenwich (0) but some QTopo insets declare e.g. PRIMEM["...",145.0].
    /// Without adding that offset the longitudes come out ~145 deg too small.
    /// Parses the offset from Measure's /GCS /WKT string.
    private static func measurePrimeMeridian(_ measure: CGPDFDictionaryRef) -> Double {
        var gcsDict: CGPDFDictionaryRef?
        guard CGPDFDictionaryGetDictionary(measure, "GCS", &gcsDict),
              let gcs = gcsDict else { return 0 }
        var wktRef: CGPDFStringRef?
        guard CGPDFDictionaryGetString(gcs, "WKT", &wktRef), let s = wktRef,
              let wkt = CGPDFStringCopyTextString(s) as String? else { return 0 }
        guard let re = try? NSRegularExpression(pattern: #"PRIMEM\["[^"]*",\s*(-?\d+(?:\.\d+)?)"#),
              let m = re.firstMatch(in: wkt, range: NSRange(wkt.startIndex..., in: wkt)),
              m.numberOfRanges > 1,
              let gr = Range(m.range(at: 1), in: wkt) else { return 0 }
        return Double(wkt[gr]) ?? 0
    }

    // MARK: - LGIDict parsing

    /// PDF spec lets LGIDict CTM/Neatline values be encoded as either PDF
    /// numbers OR PDF strings wrapped in parens. ADF and AUSLIG topo sheets
    /// use the string encoding (e.g. (135.8274208613)). CGPDFArrayGetNumber
    /// fails on strings so we fall back to CGPDFArrayGetString + parse.
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

    /// Read a dict value that may be encoded as either a PDF Name (/TC)
    /// or a PDF String ((TC)). ADF/AUSLIG topo PDFs store all LGIDict enum
    /// values as STRINGS - this was the culprit when our earlier reads
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
        // BoundaryGuide, Elevation, Adjoining Sheet Guide, Layers. We want
        // "Layers" (main map content). Older single-dict format also OK.
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

        // CTM (PDF user space -> projection coords). 6 numbers, may be strings.
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

        // Neatline crop in PDF user space, always available when Neatline is.
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

        // Projection. We handle: LL/LongLat (geographic), UT (UTM),
        // TC (Transverse Mercator), LC (Lambert Conformal Conic).
        // Params read from /Projection; /Display is consulted for the UTM
        // shortcut (lets TC PDFs whose CentralMeridian matches a standard
        // UTM zone reuse the NGA UTM helper).
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
        // Resolved source datum: ellipsoid + geocentric translation to WGS84.
        // Populated from either an inline /Datum dict (USGS US Topo and many
        // OGC GeoPDFs) or a 2-letter /Datum name. datumCode is just for logging.
        var datumCode:       String = "WE"
        var srcEllipsoid = Ellipsoid.wgs84
        var datumDx = 0.0, datumDy = 0.0, datumDz = 0.0

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

            // Datum: inline /Datum dict carries the source /Ellipsoid
            // (SemiMajorAxis + InvFlattening) and /ToWGS84 dx/dy/dz translation
            // directly, which is what USGS US Topo and many OGC GeoPDFs use.
            // Only when /Datum is a bare 2-letter OGC name do we look it up in
            // the DatumShift table. Reading embedded params means a legacy-datum
            // sheet in dict form gets its true 50-250m shift (previously just
            // fell through to WGS84 identity).
            var datumDict: CGPDFDictionaryRef?
            if CGPDFDictionaryGetDictionary(pDict, "Datum", &datumDict), let dDict = datumDict {
                datumCode = dictString(dDict, "Description") ?? "inline"
                var ellDict: CGPDFDictionaryRef?
                if CGPDFDictionaryGetDictionary(dDict, "Ellipsoid", &ellDict), let eDict = ellDict,
                   let a = dictReal(eDict, "SemiMajorAxis"), a > 0,
                   let invF = dictReal(eDict, "InvFlattening"), invF > 0 {
                    srcEllipsoid = Ellipsoid(a: a, f: 1.0 / invF)
                }
                var t2w: CGPDFDictionaryRef?
                if CGPDFDictionaryGetDictionary(dDict, "ToWGS84", &t2w), let tDict = t2w {
                    datumDx = dictReal(tDict, "dx") ?? 0
                    datumDy = dictReal(tDict, "dy") ?? 0
                    datumDz = dictReal(tDict, "dz") ?? 0
                }
            } else if let d = dictName(pDict, "Datum") {
                let p = DatumShift.params(for: d.uppercased())
                srcEllipsoid = p.ellipsoid
                datumDx = p.dx; datumDy = p.dy; datumDz = p.dz
                datumCode = d.uppercased()
            }
        }

        // /Display dict often has the easy UTM mapping (Zone, Hemi) even when
        // main /Projection is TC. Prefer Display values when set.
        var displayDict: CGPDFDictionaryRef?
        if CGPDFDictionaryGetDictionary(entryDict, "Display", &displayDict),
           let dDict = displayDict {
            if let dt = dictName(dDict, "ProjectionType"), dt == "UT" {
                if let z = dictInt(dDict, "Zone")  { utmZone = z }
                if let h = dictName(dDict, "Hemisphere")  { utmHemiName = h.uppercased() }
            }
        }

        guard haveCTM else {
            NSLog("[GeoPDF] LGIDict has no CTM - returning crop only")
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

        // Build a Projection from what we parsed. Inverse runs on the source
        // datum's ellipsoid (resolved above); DatumShift then removes the datum
        // offset to land on WGS84.
        let ellipsoid = srcEllipsoid
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
                // StandardParallelTwo for the "1SP" variant, just treat as p2==p1.
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
            NSLog("[GeoPDF] LGIDict projection='\(projectionType)' not supported - crop only")
            return ParsedLGI(southWest: nil, northEast: nil, pdfCropRect: pdfCrop)
        }

        NSLog("[GeoPDF] dispatching projection=\(proj) datum=\(datumCode) (\(projected.count) corners)")
        for (idx, pt) in projected.enumerated() {
            guard let g = proj.inverse(easting: pt.x, northing: pt.y) else {
                NSLog("[GeoPDF] corner \(idx) inverse failed (E=\(pt.x), N=\(pt.y))")
                return ParsedLGI(southWest: nil, northEast: nil, pdfCropRect: pdfCrop)
            }
            // Shift off source datum onto WGS84 (identity for modern datums;
            // removes 50-250m offset for legacy datums, whether named by a
            // 2-letter code or carried as an inline /Datum ToWGS84 dict).
            let w = DatumShift.toWGS84(lat: g.lat, lon: g.lon,
                                       sourceEllipsoid: srcEllipsoid,
                                       dx: datumDx, dy: datumDy, dz: datumDz)
            NSLog("[GeoPDF] corner \(idx): E=\(pt.x) N=\(pt.y) -> lat=\(w.lat) lon=\(w.lon) (datum \(datumCode))")
            lats.append(w.lat)
            lons.append(w.lon)
        }

        guard let minLon = lons.min(), let maxLon = lons.max(),
              let minLat = lats.min(), let maxLat = lats.max() else {
            NSLog("[GeoPDF] decoded lats/lons empty - crop only")
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
    /// and the PDF isn't a known sheet. Better than not rendering at all.
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
