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
        /// leaves the sheet's grid ~1 deg off true). nil only for known-sheet
        /// and ungeoreferenced fallback paths.
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
        // (1) Adobe Geospatial - the modern, common path. A malformed
        // declared-GEO map body must not silently downgrade to a smaller valid
        // inset or to unrelated fallback metadata.
        switch parseAdobeGeospatial(url: url) {
        case .valid(let adobe):
            guard let sw = adobe.southWest, let ne = adobe.northEast else {
                return nil
            }
            return Bounds(southWest: sw, northEast: ne,
                          pdfCropRect: adobe.pdfCropRect,
                          placementAffine: adobe.placementAffine)
        case .rejected:
            return nil
        case .absent:
            break
        }

        // (2) OGC LGIDict fallback.
        let raw = parseLGIDictRaw(url: url)
        if let p = raw,
           let sw = p.southWest,
           let ne = p.northEast,
           let affine = p.placementAffine {
            return Bounds(
                southWest: sw,
                northEast: ne,
                pdfCropRect: p.pdfCropRect,
                placementAffine: affine
            )
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

    private enum AdobeGeospatialParseResult {
        case absent
        case valid(ParsedLGI)
        case rejected
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

    private static func parseAdobeGeospatial(url: URL) -> AdobeGeospatialParseResult {
        guard let doc  = PDFDocument(url: url),
              let page = doc.page(at: 0),
              let cg   = page.pageRef,
              let pageDict = cg.dictionary else { return .absent }

        let pageResult = selectAdobeViewports(in: pageDict)
        guard case .absent = pageResult else { return pageResult }
        guard let catalog = cg.document?.catalog else { return .absent }
        return selectAdobeViewports(in: catalog)
    }

    private static func selectAdobeViewports(
        in parent: CGPDFDictionaryRef
    ) -> AdobeGeospatialParseResult {

        var vpArr: CGPDFArrayRef?
        guard CGPDFDictionaryGetArray(parent, "VP", &vpArr),
              let vpRef = vpArr else { return .absent }
        let count = CGPDFArrayGetCount(vpRef)
        guard count > 0 else { return .absent }
        guard count <= maximumMetadataEntries else { return .rejected }

        // A georeferenced topo page usually has SEVERAL viewports: the map
        // neatline plus small marginalia insets (adjoining-sheets index, state
        // locator). Can't assume viewport[0] is the map - QTopo sheets list
        // the adjoining-sheets inset FIRST, and that thing is georeferenced
        // against a 145 deg E prime meridian, so trusting it drops the import
        // off the coast of West Africa. The map body is always the LARGEST
        // viewport by BBox area, so pick that one.
        var bestGeo: (area: Double, sw: CLLocationCoordinate2D, ne: CLLocationCoordinate2D, crop: CGRect, affine: AffineTransform2D)?
        var largestMalformedDeclaredGeoArea = -1.0
        var hasUnrankableMalformedDeclaredGeo = false
        var sawDeclaredGeo = false

        for idx in 0..<count {
            var vpDict: CGPDFDictionaryRef?
            guard CGPDFArrayGetDictionary(vpRef, idx, &vpDict),
                  let viewport = vpDict else { continue }

            guard geoMeasure(in: viewport) != nil else { continue }
            sawDeclaredGeo = true

            guard let crop = viewportCrop(viewport) else {
                // Without a safe BBox this declared GEO viewport cannot be
                // ranked against another candidate. Selecting an inset would
                // therefore be guesswork, so fail closed.
                hasUnrankableMalformedDeclaredGeo = true
                continue
            }
            let area = Double(crop.width) * Double(crop.height)

            if let bounds = viewportGeoBounds(viewport),
               let affine = viewportAffine(viewport, crop: crop) {
                if area > (bestGeo?.area ?? -1) {
                    bestGeo = (area, bounds.sw, bounds.ne, crop, affine)
                }
            } else {
                largestMalformedDeclaredGeoArea = max(largestMalformedDeclaredGeoArea, area)
            }
        }

        if let g = bestGeo {
            guard !hasUnrankableMalformedDeclaredGeo,
                  largestMalformedDeclaredGeoArea <= g.area else {
                NSLog("[GeoPDF] Adobe Geospatial: rejected malformed declared-GEO viewport that could supersede selected inset")
                return .rejected
            }
            NSLog("[GeoPDF] Adobe Geospatial: \(count) viewport(s); selected valid geospatial viewport")
            return .valid(ParsedLGI(
                southWest: g.sw,
                northEast: g.ne,
                pdfCropRect: g.crop,
                placementAffine: g.affine
            ))
        }
        return sawDeclaredGeo ? .rejected : .absent
    }

    /// /BBox -> a well-formed crop rect in PDF user space (y-up, origin
    /// bottom-left). USGS US Topo sometimes writes lly > ury, so take
    /// min/max to avoid a negative-height rect.
    private static func viewportCrop(_ viewport: CGPDFDictionaryRef) -> CGRect? {
        var bboxArr: CGPDFArrayRef?
        guard CGPDFDictionaryGetArray(viewport, "BBox", &bboxArr),
              let bbox = bboxArr,
              CGPDFArrayGetCount(bbox) == 4 else { return nil }
        guard let nums = pdfRectangleValues(bbox) else { return nil }
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

        guard let measure = geoMeasure(in: viewport) else { return nil }

        var gptsArr: CGPDFArrayRef?
        guard CGPDFDictionaryGetArray(measure, "GPTS", &gptsArr),
              let gpts = gptsArr else { return nil }

        // GPTS longitudes are relative to GCS prime meridian (Greenwich for
        // the map body, but 145 deg E on some QTopo insets).
        guard let primeMeridian = measurePrimeMeridian(measure) else { return nil }

        let count = CGPDFArrayGetCount(gpts)
        guard count >= 6,
              count <= maximumGeoControlValues,
              count.isMultiple(of: 2) else { return nil }
        var lats: [Double] = []
        var lons: [Double] = []
        var i = 0
        while i + 1 < count {
            var lat: CGPDFReal = 0, lon: CGPDFReal = 0
            guard CGPDFArrayGetNumber(gpts, i,     &lat),
                  CGPDFArrayGetNumber(gpts, i + 1, &lon) else { return nil }
            let latitude = Double(lat)
            let longitude = Double(lon) + primeMeridian
            guard isEarthCoordinate(latitude: latitude, longitude: longitude) else {
                return nil
            }
            lats.append(latitude)
            lons.append(longitude)
            i += 2
        }

        guard let minLat = lats.min(), let maxLat = lats.max(),
              let minLon = lons.min(), let maxLon = lons.max(),
              minLat != maxLat, minLon != maxLon else { return nil }

        return (CLLocationCoordinate2D(latitude: minLat, longitude: minLon),
                CLLocationCoordinate2D(latitude: maxLat, longitude: maxLon))
    }

    /// /Measure -> least-squares affine (PDF user-space -> WGS84) fitted from
    /// GPTS/LPTS control points. LPTS are normalised (0-1) within viewport BBox
    /// so each PDF-space control point is crop.origin + lpts * crop.size - same
    /// crop the page is rasterised against, keeps the fit and render consistent.
    /// Returns nil if LPTS is absent or points are degenerate.
    private static func viewportAffine(_ viewport: CGPDFDictionaryRef, crop: CGRect) -> AffineTransform2D? {
        guard let measure = geoMeasure(in: viewport) else { return nil }

        var gptsArr: CGPDFArrayRef?
        var lptsArr: CGPDFArrayRef?
        guard CGPDFDictionaryGetArray(measure, "GPTS", &gptsArr), let gpts = gptsArr,
              CGPDFDictionaryGetArray(measure, "LPTS", &lptsArr), let lpts = lptsArr
        else { return nil }

        guard let primeMeridian = measurePrimeMeridian(measure) else { return nil }
        let gptsCount = CGPDFArrayGetCount(gpts)
        let lptsCount = CGPDFArrayGetCount(lpts)
        guard gptsCount == lptsCount,
              gptsCount >= 6,
              gptsCount <= maximumGeoControlValues,
              gptsCount.isMultiple(of: 2) else { return nil }
        let pairs = gptsCount / 2

        // LPTS are axes within the *ordered* BBox endpoints. Preserve a negative
        // Y (or X) delta used by raster-style producers instead of normalising
        // it away through CGRect, while retaining the normalised crop for render.
        var bboxArr: CGPDFArrayRef?
        guard CGPDFDictionaryGetArray(viewport, "BBox", &bboxArr),
              let bbox = bboxArr,
              let bboxValues = pdfRectangleValues(bbox) else { return nil }
        let originX = bboxValues[0]
        let originY = bboxValues[1]
        let deltaX = bboxValues[2] - bboxValues[0]
        let deltaY = bboxValues[3] - bboxValues[1]
        guard deltaX.isFinite, deltaY.isFinite,
              abs(deltaX) > 1e-9, abs(deltaY) > 1e-9,
              Double(crop.width).isFinite, Double(crop.height).isFinite,
              crop.width > 0, crop.height > 0 else { return nil }
        var fiducials: [Fiduciary] = []
        for j in 0..<pairs {
            var lat: CGPDFReal = 0, lon: CGPDFReal = 0
            var nx: CGPDFReal = 0, ny: CGPDFReal = 0
            guard CGPDFArrayGetNumber(gpts, j * 2,     &lat),
                  CGPDFArrayGetNumber(gpts, j * 2 + 1, &lon),
                  CGPDFArrayGetNumber(lpts, j * 2,     &nx),
                  CGPDFArrayGetNumber(lpts, j * 2 + 1, &ny) else { return nil }
            let latitude = Double(lat)
            let longitude = Double(lon) + primeMeridian
            let normalX = Double(nx)
            let normalY = Double(ny)
            guard isEarthCoordinate(latitude: latitude, longitude: longitude),
                  normalX.isFinite, normalY.isFinite,
                  (0.0...1.0).contains(normalX),
                  (0.0...1.0).contains(normalY) else { return nil }
            fiducials.append(Fiduciary(
                pdfX: originX + normalX * deltaX,
                pdfY: originY + normalY * deltaY,
                mgrs: "",
                latitude: latitude,
                longitude: longitude
            ))
        }
        return try? AffineFitter.fit(fiducials).transform
    }

    private static let maximumGeoControlValues = 8_192
    private static let maximumMetadataEntries = 64

    private static func pdfRectangleValues(_ array: CGPDFArrayRef) -> [Double]? {
        guard CGPDFArrayGetCount(array) == 4 else { return nil }
        var values: [Double] = []
        values.reserveCapacity(4)
        for index in 0..<4 {
            var number: CGPDFReal = 0
            guard CGPDFArrayGetNumber(array, index, &number) else { return nil }
            let value = Double(number)
            guard value.isFinite,
                  abs(value) <= maximumSafePDFCoordinateMagnitude else { return nil }
            values.append(value)
        }
        return values
    }

    private static func geoMeasure(in viewport: CGPDFDictionaryRef) -> CGPDFDictionaryRef? {
        var measureDict: CGPDFDictionaryRef?
        guard CGPDFDictionaryGetDictionary(viewport, "Measure", &measureDict),
              let measure = measureDict else { return nil }
        var subtypePtr: UnsafePointer<Int8>?
        guard CGPDFDictionaryGetName(measure, "Subtype", &subtypePtr),
              let subtypePtr,
              String(cString: subtypePtr) == "GEO" else { return nil }
        return measure
    }

    /// GPTS longitudes are measured from the GCS prime meridian, almost always
    /// Greenwich (0) but some QTopo insets declare e.g. PRIMEM["...",145.0].
    /// Without adding that offset the longitudes come out ~145 deg too small.
    /// Parses the offset from Measure's /GCS /WKT string.
    private static func measurePrimeMeridian(_ measure: CGPDFDictionaryRef) -> Double? {
        var gcsDict: CGPDFDictionaryRef?
        guard dictionaryHasValue(measure, key: "GCS") else { return 0 }
        guard CGPDFDictionaryGetDictionary(measure, "GCS", &gcsDict),
              let gcs = gcsDict else { return nil }
        var wktRef: CGPDFStringRef?
        guard dictionaryHasValue(gcs, key: "WKT") else { return 0 }
        guard CGPDFDictionaryGetString(gcs, "WKT", &wktRef), let s = wktRef,
              let wkt = CGPDFStringCopyTextString(s) as String? else { return nil }
        guard wkt.range(of: "PRIMEM", options: .caseInsensitive) != nil else { return 0 }
        guard let re = try? NSRegularExpression(
            pattern: #"PRIMEM\s*\[\s*"[^"]*"\s*,\s*([-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[Ee][-+]?\d+)?)"#,
            options: .caseInsensitive
        ),
        let m = re.firstMatch(in: wkt, range: NSRange(wkt.startIndex..., in: wkt)),
        m.numberOfRanges > 1,
        let gr = Range(m.range(at: 1), in: wkt),
        let value = Double(wkt[gr]),
        value.isFinite,
        (-180.0...180.0).contains(value) else { return nil }
        return value
    }

    private static func dictionaryHasValue(_ dictionary: CGPDFDictionaryRef, key: String) -> Bool {
        var object: CGPDFObjectRef?
        return CGPDFDictionaryGetObject(dictionary, key, &object)
    }

    private static func isEarthCoordinate(latitude: Double, longitude: Double) -> Bool {
        latitude.isFinite && longitude.isFinite &&
            (-90.0...90.0).contains(latitude) &&
            (-180.0...180.0).contains(longitude)
    }

    // MARK: - LGIDict parsing

    /// PDF spec lets LGIDict CTM/Neatline values be encoded as either PDF
    /// numbers OR PDF strings wrapped in parens. ADF and AUSLIG topo sheets
    /// use the string encoding (e.g. (135.8274208613)). CGPDFArrayGetNumber
    /// fails on strings so we fall back to CGPDFArrayGetString + parse.
    private static func arrayReal(_ arr: CGPDFArrayRef, _ idx: Int) -> Double? {
        var num: CGPDFReal = 0
        if CGPDFArrayGetNumber(arr, idx, &num) {
            let value = Double(num)
            return value.isFinite ? value : nil
        }
        var sRef: CGPDFStringRef?
        if CGPDFArrayGetString(arr, idx, &sRef), let s = sRef,
           let cf = CGPDFStringCopyTextString(s) as String?,
           let value = Double(cf.trimmingCharacters(in: .whitespacesAndNewlines)),
           value.isFinite {
            return value
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
        guard let value = dictReal(dict, key),
              value.rounded(.towardZero) == value,
              value >= Double(Int.min),
              value <= Double(Int.max) else { return nil }
        return Int(value)
    }

    private struct ParsedDatum {
        let ellipsoid: Ellipsoid
        let dx: Double
        let dy: Double
        let dz: Double
    }

    private static let knownDatumCodes: Set<String> = [
        "WE", "WD", "GD", "NA", "OB", "OG", "OS", "EU", "NS", "TC", "CH", "NT", "NF", "KK"
    ]

    private static func dictReal(_ dictionary: CGPDFDictionaryRef, _ key: String) -> Double? {
        var number: CGPDFReal = 0
        if CGPDFDictionaryGetNumber(dictionary, key, &number) {
            let value = Double(number)
            return value.isFinite ? value : nil
        }
        guard let string = dictString(dictionary, key),
              let value = Double(string.trimmingCharacters(in: .whitespacesAndNewlines)),
              value.isFinite else { return nil }
        return value
    }

    private static func parseDatum(_ projection: CGPDFDictionaryRef,
                                   requireExplicit: Bool) -> ParsedDatum? {
        guard dictionaryHasValue(projection, key: "Datum") else {
            return requireExplicit
                ? nil
                : ParsedDatum(ellipsoid: .wgs84, dx: 0, dy: 0, dz: 0)
        }

        var datumDictionary: CGPDFDictionaryRef?
        if CGPDFDictionaryGetDictionary(projection, "Datum", &datumDictionary),
           let datum = datumDictionary {
            var ellipsoidDictionary: CGPDFDictionaryRef?
            guard CGPDFDictionaryGetDictionary(datum, "Ellipsoid", &ellipsoidDictionary),
                  let ellipsoid = ellipsoidDictionary,
                  let semiMajorAxis = dictReal(ellipsoid, "SemiMajorAxis"),
                  let inverseFlattening = dictReal(ellipsoid, "InvFlattening"),
                  inverseFlattening > 0 else { return nil }
            let flattening = 1.0 / inverseFlattening
            guard semiMajorAxis >= 6_000_000,
                  semiMajorAxis <= 7_000_000,
                  flattening.isFinite,
                  flattening > 0,
                  flattening < 0.01 else { return nil }
            let sourceEllipsoid = Ellipsoid(a: semiMajorAxis, f: flattening)

            guard dictionaryHasValue(datum, key: "ToWGS84") else {
                // A non-modern ellipsoid with no translation cannot be placed
                // safely onto WGS84.
                guard abs(semiMajorAxis - Ellipsoid.wgs84.a) < 1,
                      abs(inverseFlattening - 298.257223563) < 0.01 else { return nil }
                return ParsedDatum(ellipsoid: sourceEllipsoid, dx: 0, dy: 0, dz: 0)
            }

            var translationDictionary: CGPDFDictionaryRef?
            guard CGPDFDictionaryGetDictionary(datum, "ToWGS84", &translationDictionary),
                  let translation = translationDictionary,
                  let dx = dictReal(translation, "dx"),
                  let dy = dictReal(translation, "dy"),
                  let dz = dictReal(translation, "dz") else { return nil }
            return ParsedDatum(ellipsoid: sourceEllipsoid, dx: dx, dy: dy, dz: dz)
        }

        guard let rawCode = dictName(projection, "Datum") else { return nil }
        let code = rawCode.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        guard knownDatumCodes.contains(code) else { return nil }
        let parameters = DatumShift.params(for: code)
        return ParsedDatum(
            ellipsoid: parameters.ellipsoid,
            dx: parameters.dx,
            dy: parameters.dy,
            dz: parameters.dz
        )
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
            guard count > 0, count <= maximumMetadataEntries else { return nil }
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
        NSLog("[GeoPDF] LGIDict: \(entries.count) entries; selected supported entry")
        let entryDict = chosen

        // CTM (PDF user space -> projection coords). Exactly 6 finite numbers,
        // optionally encoded as strings by older ADF/AUSLIG producers.
        var ctm: [Double]?
        var ctmArr: CGPDFArrayRef?
        if CGPDFDictionaryGetArray(entryDict, "CTM", &ctmArr),
           let ctmRef = ctmArr,
           CGPDFArrayGetCount(ctmRef) == 6 {
            let values = (0..<6).compactMap { arrayReal(ctmRef, $0) }
            if values.count == 6 { ctm = values }
        }

        // Neatline: complete x,y pairs in PDF user space (may be strings).
        // An explicitly malformed value invalidates the metadata; never use a
        // valid prefix or silently replace a bad declared neatline with MediaBox.
        var neatlinePts: [CGPoint] = []
        var nlArr: CGPDFArrayRef?
        if dictionaryHasValue(entryDict, key: "Neatline") {
            guard CGPDFDictionaryGetArray(entryDict, "Neatline", &nlArr),
                  let nlRef = nlArr else { return nil }
            let count = CGPDFArrayGetCount(nlRef)
            guard count >= 6,
                  count <= maximumGeoControlValues,
                  count.isMultiple(of: 2) else { return nil }
            for i in stride(from: 0, to: count, by: 2) {
                guard let x = arrayReal(nlRef, i),
                      let y = arrayReal(nlRef, i + 1),
                      abs(x) <= maximumSafePDFCoordinateMagnitude,
                      abs(y) <= maximumSafePDFCoordinateMagnitude else { return nil }
                neatlinePts.append(CGPoint(x: x, y: y))
            }
        } else {
            let mediaValues = [
                Double(mediaBox.minX), Double(mediaBox.minY),
                Double(mediaBox.maxX), Double(mediaBox.maxY)
            ]
            guard mediaValues.allSatisfy({
                $0.isFinite && abs($0) <= maximumSafePDFCoordinateMagnitude
            }), mediaBox.width > 0, mediaBox.height > 0 else { return nil }
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
                  [minPX, maxPX, minPY, maxPY].allSatisfy(\.isFinite),
                  maxPX > minPX, maxPY > minPY,
                  (maxPX - minPX).isFinite,
                  (maxPY - minPY).isFinite else { return nil }
            let crop = CGRect(x: minPX, y: minPY,
                              width: maxPX - minPX,
                              height: maxPY - minPY)
            return crop.width.isFinite && crop.height.isFinite ? crop : nil
        }()

        // Projection. We handle: LL/LongLat (geographic), UT (UTM),
        // TC (Transverse Mercator), LC (Lambert Conformal Conic).
        // Missing/malformed projection metadata is crop-only. Projected CRSs
        // additionally require an explicit, understood source datum.
        var projDict: CGPDFDictionaryRef?
        guard CGPDFDictionaryGetDictionary(entryDict, "Projection", &projDict),
              let projectionDictionary = projDict,
              let rawProjectionType = dictName(projectionDictionary, "ProjectionType") else {
            NSLog("[GeoPDF] LGIDict has no complete projection - returning crop only")
            return ParsedLGI(southWest: nil, northEast: nil, pdfCropRect: pdfCrop)
        }
        let projectionType = rawProjectionType
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .uppercased()
        let isProjected = projectionType != "LL" && projectionType != "LONGLAT"
        guard let datum = parseDatum(projectionDictionary, requireExplicit: isProjected) else {
            NSLog("[GeoPDF] LGIDict datum is absent, unknown, or incomplete - returning crop only")
            return ParsedLGI(southWest: nil, northEast: nil, pdfCropRect: pdfCrop)
        }

        // /Display may carry the zone/hemisphere for a UTM projection.
        var displayDict: CGPDFDictionaryRef?
        _ = CGPDFDictionaryGetDictionary(entryDict, "Display", &displayDict)

        guard let ctm else {
            NSLog("[GeoPDF] LGIDict has no CTM - returning crop only")
            return ParsedLGI(southWest: nil, northEast: nil, pdfCropRect: pdfCrop)
        }

        let a = ctm[0], b = ctm[1], c = ctm[2], d = ctm[3], e = ctm[4], f = ctm[5]

        // Apply the affine to each Neatline point.
        let projected = neatlinePts.map { p -> (x: Double, y: Double) in
            let xx = Double(p.x), yy = Double(p.y)
            return (a * xx + c * yy + e, b * xx + d * yy + f)
        }
        guard projected.allSatisfy({ $0.x.isFinite && $0.y.isFinite }) else {
            NSLog("[GeoPDF] LGIDict CTM overflowed - returning crop only")
            return ParsedLGI(southWest: nil, northEast: nil, pdfCropRect: pdfCrop)
        }

        var lats: [Double] = []
        var lons: [Double] = []
        var placementControls: [Fiduciary] = []

        // Build a fully specified Projection. Inverse runs on the source datum's
        // ellipsoid; DatumShift then removes its offset to land on WGS84.
        let ellipsoid = datum.ellipsoid
        let projection: Projection? = {
            switch projectionType {
            case "LL", "LONGLAT":
                return .longLat

            case "UT", "UTM":
                guard let zone = dictInt(projectionDictionary, "Zone")
                        ?? displayDict.flatMap({ dictInt($0, "Zone") }),
                      (1...60).contains(zone),
                      let rawHemisphere = dictName(projectionDictionary, "Hemisphere")
                        ?? displayDict.flatMap({ dictName($0, "Hemisphere") }) else { return nil }
                let hemisphere = rawHemisphere
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                    .uppercased()
                let isSouthern: Bool
                switch hemisphere {
                case "N", "NORTH": isSouthern = false
                case "S", "SOUTH": isSouthern = true
                default: return nil
                }
                // Do not route through the NGA UTM helper here: it is fixed to
                // WGS84 and would discard a valid legacy/custom source ellipsoid.
                return .transverseMercator(
                    centralMeridian: Double(zone) * 6 - 183,
                    originLatitude: 0,
                    falseEasting: 500_000,
                    falseNorthing: isSouthern ? 10_000_000 : 0,
                    scaleFactor: 0.9996,
                    ellipsoid: ellipsoid
                )

            case "TC":
                guard let centralMeridian = dictReal(projectionDictionary, "CentralMeridian"),
                      (-180.0...180.0).contains(centralMeridian),
                      let originLatitude = dictReal(projectionDictionary, "OriginLatitude"),
                      (-90.0...90.0).contains(originLatitude),
                      let falseEasting = dictReal(projectionDictionary, "FalseEasting"),
                      let falseNorthing = dictReal(projectionDictionary, "FalseNorthing"),
                      let scaleFactor = dictReal(projectionDictionary, "ScaleFactor"),
                      scaleFactor > 0,
                      scaleFactor <= 10 else { return nil }
                return .transverseMercator(
                    centralMeridian: centralMeridian,
                    originLatitude:  originLatitude,
                    falseEasting:    falseEasting,
                    falseNorthing:   falseNorthing,
                    scaleFactor:     scaleFactor,
                    ellipsoid:       ellipsoid
                )

            case "LC":
                guard let parallelOne = dictReal(projectionDictionary, "StandardParallelOne"),
                      (-89.999...89.999).contains(parallelOne) else { return nil }
                let parallelTwo = dictReal(projectionDictionary, "StandardParallelTwo") ?? parallelOne
                guard (-89.999...89.999).contains(parallelTwo),
                      let originLatitude = dictReal(projectionDictionary, "OriginLatitude"),
                      (-89.999...89.999).contains(originLatitude),
                      let centralMeridian = dictReal(projectionDictionary, "CentralMeridian"),
                      (-180.0...180.0).contains(centralMeridian),
                      let falseEasting = dictReal(projectionDictionary, "FalseEasting"),
                      let falseNorthing = dictReal(projectionDictionary, "FalseNorthing") else { return nil }
                return .lambertConformalConic(
                    stdParallel1:    parallelOne,
                    stdParallel2:    parallelTwo,
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
            NSLog("[GeoPDF] LGIDict projection not supported - crop only")
            return ParsedLGI(southWest: nil, northEast: nil, pdfCropRect: pdfCrop)
        }

        NSLog("[GeoPDF] decoding \(projected.count) projected corners")
        for (idx, pt) in projected.enumerated() {
            guard let g = proj.inverse(easting: pt.x, northing: pt.y) else {
            NSLog("[GeoPDF] projected corner \(idx) inverse failed")
                return ParsedLGI(southWest: nil, northEast: nil, pdfCropRect: pdfCrop)
            }
            // Shift off source datum onto WGS84 (identity for modern datums;
            // removes 50-250m offset for legacy datums, whether named by a
            // 2-letter code or carried as an inline /Datum ToWGS84 dict).
            let w = DatumShift.toWGS84(lat: g.lat, lon: g.lon,
                                       sourceEllipsoid: datum.ellipsoid,
                                       dx: datum.dx, dy: datum.dy, dz: datum.dz)
            guard w.lat.isFinite, w.lon.isFinite,
                  (-90.0...90.0).contains(w.lat),
                  (-180.0...180.0).contains(w.lon) else {
                NSLog("[GeoPDF] projected corner \(idx) produced an invalid WGS84 coordinate")
                return ParsedLGI(southWest: nil, northEast: nil, pdfCropRect: pdfCrop)
            }
            NSLog("[GeoPDF] projected corner \(idx) decoded")
            lats.append(w.lat)
            lons.append(w.lon)
            let pdfPoint = neatlinePts[idx]
            placementControls.append(Fiduciary(
                pdfX: Double(pdfPoint.x),
                pdfY: Double(pdfPoint.y),
                mgrs: "",
                latitude: w.lat,
                longitude: w.lon
            ))
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
            NSLog("[GeoPDF] decoded bounds rejected")
            return ParsedLGI(southWest: nil, northEast: nil, pdfCropRect: pdfCrop)
        }

        // The overlay must use the actual CTM/projection placement, never an
        // axis-aligned stretch of projected bounds. Fit the same PDF→WGS84
        // affine used by manual and Adobe control points. Over a single topo
        // sheet projection curvature is tiny; a large residual means metadata
        // is inconsistent and automatic placement must fail closed.
        guard let placement = try? AffineFitter.fit(placementControls),
              placement.crossValidated,
              placement.rmsMetres.isFinite,
              placement.rmsMetres <= 50 else {
            NSLog("[GeoPDF] LGIDict placement fit rejected - crop only")
            return ParsedLGI(southWest: nil, northEast: nil, pdfCropRect: pdfCrop)
        }

        NSLog("[GeoPDF] LGIDict bounds decoded")
        return ParsedLGI(
            southWest: CLLocationCoordinate2D(latitude: minLat, longitude: minLon),
            northEast: CLLocationCoordinate2D(latitude: maxLat, longitude: maxLon),
            pdfCropRect: pdfCrop,
            placementAffine: placement.transform
        )
    }

    /// Centre-on-camera fallback: 10km square. Used when no metadata exists
    /// and the PDF isn't a known sheet. Better than not rendering at all.
    static func fallbackBounds(centeredOn camera: CLLocationCoordinate2D,
                                halfWidthMetres: Double = 5000) -> Bounds {
        let safeHalfWidth = halfWidthMetres.isFinite && halfWidthMetres > 0
            ? min(halfWidthMetres, 1_000_000)
            : 5_000
        // Web-Mercator has no finite representation at the poles. Clamp an
        // unavailable/corrupt camera to a safe terrestrial centre rather than
        // publishing Inf/NaN fallback bounds into the renderer and persistence.
        let latitude = camera.latitude.isFinite
            ? min(85, max(-85, camera.latitude))
            : 0
        let rawLongitude = camera.longitude.isFinite ? camera.longitude : 0
        let normalizedLongitude = ((rawLongitude + 180).truncatingRemainder(dividingBy: 360) + 360)
            .truncatingRemainder(dividingBy: 360) - 180
        let metresPerDegLat = 111_320.0
        let metresPerDegLon = 111_320.0 * cos(latitude * .pi / 180)
        let dLat = safeHalfWidth / metresPerDegLat
        let dLon = safeHalfWidth / metresPerDegLon
        let mercatorLatitudeLimit = 85.05112878
        let centreLatitude = min(
            mercatorLatitudeLimit - dLat,
            max(-mercatorLatitudeLimit + dLat, latitude)
        )
        // This Bounds representation is non-wrapping, so nudge an antimeridian
        // fallback just enough to keep both longitudes Earth-valid.
        let centreLongitude = min(180 - dLon, max(-180 + dLon, normalizedLongitude))
        return Bounds(
            southWest: CLLocationCoordinate2D(
                latitude: centreLatitude - dLat,
                longitude: centreLongitude - dLon
            ),
            northEast: CLLocationCoordinate2D(
                latitude: centreLatitude + dLat,
                longitude: centreLongitude + dLon
            ),
            pdfCropRect: nil
        )
    }
}
