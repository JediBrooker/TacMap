import Foundation
import CoreGraphics
import CoreLocation

/// Pulls georeferencing metadata out of a PDF page and hands it to
/// PdfGeorefBuilder. ISO 32000 / Adobe /VP goes first (page, then catalog:
/// Viewport /Measure /Subtype /GEO with GPTS/LPTS/GCS/Bounds, what USGS US
/// Topo, Esri, Avenza and ADF/AUSLIG write), then OGC LGIDict (/CTM or
/// /Registration, /Neatline, /Projection).
///
/// Everything comes back in raw page user space (box origin included,
/// /Rotate ignored). The three outcomes are all distinct on purpose, a
/// declared-but-broken georef must never look like a plain PDF.
enum GeoPDFReader {

    /// The bits of the page the provisional placement + renderers need.
    struct PageGeometry: Equatable, Sendable {
        /// raw MediaBox, origin included
        var mediaBox: CGRect
        /// effective CropBox (clipped to the MediaBox, defaults to it)
        var cropBox: CGRect
        /// /Rotate, normalised to 0/90/180/270. Only renderers use it.
        var rotation: Int
    }

    enum Outcome: Equatable, Sendable {
        case georeferenced(PdfGeoreference)
        /// no geo metadata at all, off to calibration
        case notGeoreferenced
        /// geo metadata was declared but we won't trust it, say why
        case rejected(PdfGeorefRejectReason)
    }

    struct Readout: Equatable {
        var outcome: Outcome
        var page: PageGeometry
        var selection: PdfGeorefSelection?
        var fitStats: PdfGeorefFitStats?

        var georef: PdfGeoreference? {
            if case .georeferenced(let g) = outcome { return g }
            return nil
        }
    }

    /// nil only when the file isn't an openable PDF with that page.
    static func read(url: URL, pageIndex: Int = 0) -> Readout? {
        guard let doc = CGPDFDocument(url as CFURL) else { return nil }
        return read(document: doc, pageIndex: pageIndex)
    }

    static func read(document doc: CGPDFDocument, pageIndex: Int = 0) -> Readout? {
        guard !doc.isEncrypted || doc.isUnlocked,
              pageIndex >= 0, pageIndex < doc.numberOfPages,
              let page = doc.page(at: pageIndex + 1),
              let pageDict = page.dictionary else { return nil }
        let geometry = pageGeometry(page)
        let pageBox = rectPolygon(geometry.cropBox)

        var rejection: PdfGeorefRejectReason?
        // one budget for every number we pull off this page, see ReadBudget
        let budget = ReadBudget()

        // Adobe /VP on the page, else on the catalog
        var viewports = extractViewports(in: pageDict, budget: budget)
        if viewports == nil, let catalog = doc.catalog {
            viewports = extractViewports(in: catalog, budget: budget)
        }
        // ran out of budget somewhere in the /VP: the whole /VP is junk, not just the
        // arrays after the cut (an earlier viewport winning would depend on read order).
        // nothing's left for the LGIDict either. same rule as Android (lgiRules.pageBudget)
        let vpExhausted = budget.exhausted
        if let viewports {
            switch viewports {
            case _ where vpExhausted, .tooMany, .malformed:
                rejection = .malformed
            case .list(let vps):
                switch PdfGeorefBuilder.build(viewports: vps, page: pageIndex) {
                case .georef(let g, let sel, let stats)?:
                    NSLog("[GeoPDF] Adobe viewport \(sel.index) of \(vps.count) accepted")
                    return Readout(outcome: .georeferenced(g), page: geometry, selection: sel, fitStats: stats)
                case .rejected(let reason)?:
                    NSLog("[GeoPDF] Adobe georef rejected: \(reason.rawValue)")
                    rejection = reason
                case nil:
                    break
                }
            }
        }

        // then LGIDict
        let lgi = vpExhausted ? nil : extractLgiEntries(in: pageDict, budget: budget)
        switch lgi {
        case _ where budget.exhausted, .tooMany?, .malformed?:
            rejection = rejection ?? .malformed
        case .list(let entries)?:
            switch PdfGeorefBuilder.build(lgiEntries: entries, pageBox: pageBox, page: pageIndex) {
            case .georef(let g, let sel, let stats)?:
                NSLog("[GeoPDF] LGIDict entry \(sel.index) of \(entries.count) accepted (\(sel.source?.rawValue ?? "?"))")
                return Readout(outcome: .georeferenced(g), page: geometry, selection: sel, fitStats: stats)
            case .rejected(let reason)?:
                NSLog("[GeoPDF] LGIDict georef rejected: \(reason.rawValue)")
                rejection = rejection ?? reason
            case nil:
                break
            }
        case nil:
            break
        }

        if let rejection {
            return Readout(outcome: .rejected(rejection), page: geometry)
        }
        return Readout(outcome: .notGeoreferenced, page: geometry)
    }

    static func pageGeometry(_ page: CGPDFPage) -> PageGeometry {
        let media = page.getBoxRect(.mediaBox).standardized
        var crop = page.getBoxRect(.cropBox).standardized.intersection(media)
        if crop.isNull || crop.width <= 0 || crop.height <= 0 { crop = media }
        let r = ((Int(page.rotationAngle) % 360) + 360) % 360
        return PageGeometry(mediaBox: media, cropBox: crop, rotation: r - r % 90)
    }

    private static func rectPolygon(_ r: CGRect) -> [PdfPagePoint] {
        [PdfPagePoint(x: Double(r.minX), y: Double(r.minY)), PdfPagePoint(x: Double(r.maxX), y: Double(r.minY)),
         PdfPagePoint(x: Double(r.maxX), y: Double(r.maxY)), PdfPagePoint(x: Double(r.minX), y: Double(r.maxY))]
    }

    // MARK: - Adobe /VP extraction

    enum Extracted<T> {
        case list([T])
        case tooMany
        /// declared but not something we can read at all (wrong type, empty)
        case malformed
    }

    /// Hostile files can point every row of an array at one shared 8192 number
    /// array, so per-array caps alone still let one page ask for gigabytes.
    /// Every number read off a page comes out of this.
    final class ReadBudget {
        static let maximumValuesPerPage = 65_536
        private(set) var remaining: Int

        init(_ values: Int = ReadBudget.maximumValuesPerPage) { remaining = values }

        /// once set the page is junk, see read(document:)
        private(set) var exhausted = false

        func take(_ n: Int) -> Bool {
            guard n >= 0, n <= remaining else {
                remaining = 0
                exhausted = true
                return false
            }
            remaining -= n
            return true
        }
    }

    /// nil when there's no /VP with at least one GEO viewport.
    static func extractViewports(in parent: CGPDFDictionaryRef,
                                 budget: ReadBudget = ReadBudget()) -> Extracted<AdobeViewportInput>? {
        var arrRef: CGPDFArrayRef?
        guard CGPDFDictionaryGetArray(parent, "VP", &arrRef), let arr = arrRef else {
            // /VP null or some other non-array is present junk, not "no /VP" (lgiRules.nullValues)
            return has(parent, "VP") ? .malformed : nil
        }
        let count = CGPDFArrayGetCount(arr)
        guard count > 0 else { return nil }
        guard count <= PdfGeorefBuilder.maximumEntries else { return .tooMany }
        var out: [AdobeViewportInput] = []
        for i in 0..<count {
            var vpRef: CGPDFDictionaryRef?
            guard CGPDFArrayGetDictionary(arr, i, &vpRef), let vp = vpRef else { continue }
            var mRef: CGPDFDictionaryRef?
            guard CGPDFDictionaryGetDictionary(vp, "Measure", &mRef), let measure = mRef,
                  name(measure, "Subtype") == "GEO" else { continue }
            out.append(AdobeViewportInput(
                name: text(vp, "Name"),
                bbox: numbers(vp, "BBox", budget),
                lpts: numbers(measure, "LPTS", budget),
                gpts: numbers(measure, "GPTS", budget),
                bounds: numbers(measure, "Bounds", budget),
                gcs: gcs(measure)
            ))
        }
        return out.isEmpty ? nil : .list(out)
    }

    /// /GCS that isn't a dict, /WKT that isn't a string or /EPSG that isn't a PDF
    /// integer is malformed (rejections gcsTypes). No /GCS at all is the fallback.
    private static func gcs(_ measure: CGPDFDictionaryRef) -> PdfGcsInput {
        guard has(measure, "GCS") else { return .missing }
        var gRef: CGPDFDictionaryRef?
        guard CGPDFDictionaryGetDictionary(measure, "GCS", &gRef), let g = gRef else { return .malformed }
        var wkt: String?
        if has(g, "WKT") {
            var s: CGPDFStringRef?
            guard CGPDFDictionaryGetString(g, "WKT", &s), let s,
                  let str = CGPDFStringCopyTextString(s) as String? else { return .malformed }
            wkt = str
        }
        var epsg: Int?
        if has(g, "EPSG") {
            var n: CGPDFInteger = 0
            guard CGPDFDictionaryGetInteger(g, "EPSG", &n) else { return .malformed }
            epsg = Int(n)
        }
        return .described(wkt: wkt, epsg: epsg)
    }

    // MARK: - LGIDict extraction

    /// nil = no /LGIDict key. Present but empty / wrong type / no dict entries is
    /// .malformed, a declared georef we can't read isn't a plain PDF. Non-dict array
    /// members get skipped, so the entry index counts dicts only (lgiRules.entries).
    static func extractLgiEntries(in pageDict: CGPDFDictionaryRef,
                                  budget: ReadBudget = ReadBudget()) -> Extracted<LgiEntryInput>? {
        guard has(pageDict, "LGIDict") else { return nil }
        var dicts: [CGPDFDictionaryRef] = []
        var arrRef: CGPDFArrayRef?
        var single: CGPDFDictionaryRef?
        if CGPDFDictionaryGetArray(pageDict, "LGIDict", &arrRef), let arr = arrRef {
            let count = CGPDFArrayGetCount(arr)
            guard count <= PdfGeorefBuilder.maximumEntries else { return .tooMany }
            for i in 0..<count {
                var e: CGPDFDictionaryRef?
                if CGPDFArrayGetDictionary(arr, i, &e), let e { dicts.append(e) }
            }
        } else if CGPDFDictionaryGetDictionary(pageDict, "LGIDict", &single), let single {
            dicts.append(single)
        }
        guard !dicts.isEmpty else { return .malformed }
        return .list(dicts.map { d in
            var projection: [String: LgiValue]?
            var pRef: CGPDFDictionaryRef?
            if CGPDFDictionaryGetDictionary(d, "Projection", &pRef), let p = pRef {
                projection = values(of: p, depth: 0)
            }
            var display: [String: LgiValue]?
            var dRef: CGPDFDictionaryRef?
            if CGPDFDictionaryGetDictionary(d, "Display", &dRef), let dd = dRef {
                display = values(of: dd, depth: 0)
            }
            return LgiEntryInput(
                // Description turns up as a string or a name depending on the producer
                description: text(d, "Description") ?? name(d, "Description"),
                ctm: numbers(d, "CTM", budget),
                registration: registration(d, budget),
                neatline: numbers(d, "Neatline", budget),
                projection: projection,
                display: display
            )
        })
    }

    private static func registration(_ d: CGPDFDictionaryRef, _ budget: ReadBudget) -> PdfRegistration {
        guard has(d, "Registration") else { return .missing }
        var arrRef: CGPDFArrayRef?
        guard CGPDFDictionaryGetArray(d, "Registration", &arrRef), let arr = arrRef else { return .malformed }
        let count = CGPDFArrayGetCount(arr)
        guard count <= PdfGeorefBuilder.maximumRegistrationRows else { return .malformed }
        var rows: [[Double]] = []
        rows.reserveCapacity(count)
        for i in 0..<count {
            var rowRef: CGPDFArrayRef?
            // length check BEFORE reading anything, a row is [x y X Y] and nothing else.
            // otherwise every row can point at one shared huge array and we'd copy it each time
            guard CGPDFArrayGetArray(arr, i, &rowRef), let row = rowRef,
                  CGPDFArrayGetCount(row) == 4 else { return .malformed }
            switch numbers(row, budget) {
            case .values(let v): rows.append(v)
            case .nonFinite: return .nonFinite
            default: return .malformed
            }
        }
        return .rows(rows)
    }

    private static func values(of dict: CGPDFDictionaryRef, depth: Int) -> [String: LgiValue] {
        final class Box { var out: [String: LgiValue] = [:] }
        let box = Box()
        CGPDFDictionaryApplyBlock(dict, { key, obj, _ in
            let k = String(cString: key)
            box.out[k] = lgiValue(obj, depth: depth)
            return box.out.count < 256
        }, nil)
        return box.out
    }

    private static func lgiValue(_ obj: CGPDFObjectRef, depth: Int) -> LgiValue {
        switch CGPDFObjectGetType(obj) {
        case .name:
            var p: UnsafePointer<Int8>?
            return CGPDFObjectGetValue(obj, .name, &p) && p != nil ? .name(String(cString: p!)) : .other
        case .string:
            var s: CGPDFStringRef?
            guard CGPDFObjectGetValue(obj, .string, &s), let s,
                  let str = CGPDFStringCopyTextString(s) as String? else { return .other }
            return .text(str)
        case .integer:
            var n: CGPDFInteger = 0
            return CGPDFObjectGetValue(obj, .integer, &n) ? .integer(Int64(n)) : .other
        case .null:
            return .null
        case .real:
            var r: CGPDFReal = 0
            guard CGPDFObjectGetValue(obj, .real, &r) else { return .other }
            let v = Double(r)
            return v.isFinite && abs(v) < coreGraphicsRealCeiling ? .number(v) : .other
        case .dictionary:
            guard depth < 3 else { return .other }
            var d: CGPDFDictionaryRef?
            guard CGPDFObjectGetValue(obj, .dictionary, &d), let d else { return .other }
            return .dictionary(values(of: d, depth: depth + 1))
        default:
            return .other
        }
    }

    // MARK: - little readers

    /// CoreGraphics doesn't hand back inf for a real too big for a double, it
    /// saturates at 1e75. Nothing legit is anywhere near that, so treat the
    /// ceiling as the overflow it really is.
    static let coreGraphicsRealCeiling = 1e75

    private static func has(_ d: CGPDFDictionaryRef, _ key: String) -> Bool {
        var o: CGPDFObjectRef?
        return CGPDFDictionaryGetObject(d, key, &o)
    }

    private static func numbers(_ d: CGPDFDictionaryRef, _ key: String, _ budget: ReadBudget) -> PdfNumbers {
        guard has(d, key) else { return .missing }
        var arrRef: CGPDFArrayRef?
        guard CGPDFDictionaryGetArray(d, key, &arrRef), let arr = arrRef else { return .malformed }
        return numbers(arr, budget)
    }

    /// numbers, or numeric strings (ADF/AUSLIG write LGIDict reals as (135.82...))
    private static func numbers(_ arr: CGPDFArrayRef, _ budget: ReadBudget) -> PdfNumbers {
        let count = CGPDFArrayGetCount(arr)
        guard count <= PdfGeorefBuilder.maximumControlValues, budget.take(count) else { return .malformed }
        var out: [Double] = []
        out.reserveCapacity(count)
        var overflow = false
        for i in 0..<count {
            var r: CGPDFReal = 0
            if CGPDFArrayGetNumber(arr, i, &r) {
                let v = Double(r)
                if !v.isFinite || abs(v) >= coreGraphicsRealCeiling { overflow = true }
                out.append(v)
                continue
            }
            var s: CGPDFStringRef?
            guard CGPDFArrayGetString(arr, i, &s), let s,
                  let str = CGPDFStringCopyTextString(s) as String?,
                  let v = PdfNumericString.parse(str) else { return .malformed }
            if !v.isFinite || abs(v) >= coreGraphicsRealCeiling { overflow = true }
            out.append(v)
        }
        return overflow ? .nonFinite : .values(out)
    }

    private static func text(_ d: CGPDFDictionaryRef, _ key: String) -> String? {
        var s: CGPDFStringRef?
        guard CGPDFDictionaryGetString(d, key, &s), let s else { return nil }
        return CGPDFStringCopyTextString(s) as String?
    }

    private static func name(_ d: CGPDFDictionaryRef, _ key: String) -> String? {
        var p: UnsafePointer<Int8>?
        guard CGPDFDictionaryGetName(d, key, &p), let p else { return nil }
        return String(cString: p)
    }
}

extension GeoPDFReader {

    /// Lat/lon box + crop rect + one best-fit lon/lat affine, derived from a
    /// PdfGeoreference for the overlay/tiler that still place the page with a
    /// single linear map. Display only, the georef is the source of truth.
    struct Bounds: Hashable {
        let southWest: CLLocationCoordinate2D
        let northEast: CLLocationCoordinate2D
        /// bounding rect of the georef crop, raw page user space (y up)
        let pdfCropRect: CGRect?
        /// PDF user space -> WGS84 lon/lat, least squares over the crop
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

        init?(georef: PdfGeoreference) {
            guard let box = georef.wgs84Bounds(), let affine = georef.bestFitLatLonAffine() else { return nil }
            self.init(southWest: box.southWest, northEast: box.northEast,
                      pdfCropRect: georef.cropBoundingRect, placementAffine: affine)
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
}
