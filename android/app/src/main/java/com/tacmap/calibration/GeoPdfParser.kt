package com.tacmap.calibration

import android.content.Context
import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNull
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File

private const val TAG = "GeoPdfParser"

/**
 * Reads page 0's geo metadata (ISO 32000 /VP, OGC LGIDict) and page boxes with
 * PDFBox and hands the raw numbers to [GeoPdfGeoreferencer], which does all the
 * actual geo work in pure Kotlin. Nothing here decides anything, it just reads.
 *
 * Numbers stay doubles: pdfbox-android 2.0.x keeps COSFloat as a BigDecimal, so
 * doubleValue() is what the file says (PDFBox 3 / MuPDF go via float32 and move
 * GPTS by ~1 m). Overflowing reals get clamped to Float.MAX_VALUE by PDFBox, we
 * turn that back into infinity so it's rejected as nonFinite, not off earth.
 */
object GeoPdfParser {
    private const val MAX_METADATA_ENTRIES = 64
    private const val MAX_GEO_CONTROL_VALUES = 8_192
    internal const val MAX_REGISTRATION_ROWS = 4_096
    // every number we pull off one page across /VP and LGIDict. Real files use a few
    // hundred at most; indirect refs let a 60 KB file point thousands of keys at one
    // 8k array, so without a page cap that's GBs of boxed doubles (OOM)
    internal const val MAX_PAGE_VALUES = 65_536
    private const val MAX_PARENT_DEPTH = 32
    private val LETTER = listOf(0.0, 0.0, 612.0, 792.0)

    @Volatile
    private var initialised = false

    internal fun ensureInit(context: Context) {
        if (!initialised) {
            PDFBoxResourceLoader.init(context.applicationContext)
            initialised = true
        }
    }

    /** page 0 metadata + boxes, or null when PDFBox can't open the file at all */
    fun readFirstPage(context: Context, file: File): GeoPdfPageData? {
        ensureInit(context)
        if (!file.isFile) return null
        return try {
            PDDocument.load(file).use { doc -> pageData(doc, 0) }
        } catch (oom: OutOfMemoryError) {
            // don't swallow an OOM into "plain PDF", let the import fail loudly
            throw oom
        } catch (_: Exception) {
            Log.w(TAG, "GeoPDF metadata read failed")
            null
        }
    }

    /**
     * @param allowCatalogVp the catalog /VP is the document's, it only stands in for
     *   page 0's (contract s9.5: pages 0-49 plus the catalog /VP)
     */
    internal fun pageData(doc: PDDocument, pageIndex: Int, allowCatalogVp: Boolean = true): GeoPdfPageData? {
        if (pageIndex !in 0 until doc.numberOfPages) return null
        val page = doc.getPage(pageIndex).cosObject
        val reader = Reader()
        val media = inherited(page, "MediaBox")?.let(reader::boxNumbers)
            ?.takeIf { it.size == 4 && it.all { v -> v != null && v.isFinite() } }?.map { it!! }
            ?: LETTER
        val crop = inherited(page, "CropBox")?.let(reader::boxNumbers)
            ?.takeIf { it.size == 4 && it.all { v -> v != null && v.isFinite() } }?.map { it!! }
        val rotate = (inherited(page, "Rotate") as? COSNumber)?.intValue() ?: 0

        // page /VP when it has a GEO viewport, else the (non standard) catalog one. Same as iOS.
        // a page /VP that's there but isn't an array (null included) is declared junk, and
        // then the catalog isn't asked (lgiRules.nullValues, iOS extractViewports)
        val pageVpRaw = page.raw("VP")
        val pageVpJunk = pageVpRaw != null && pageVpRaw !is COSArray
        val pageVp = pageVpRaw as? COSArray
        val catalogVp = if (!allowCatalogVp || pageVpJunk) null else
            doc.documentCatalog.cosObject.raw("VP") as? COSArray
        val vpArray = pageVp?.takeIf { it.size() > MAX_METADATA_ENTRIES || hasGeoViewport(it) }
            ?: catalogVp?.takeIf { it.size() > MAX_METADATA_ENTRIES || hasGeoViewport(it) }
        // an oversized array is still declared, build() rejects it rather than calling it plain
        var viewportsOversized = pageVpJunk || (vpArray?.size() ?: 0) > MAX_METADATA_ENTRIES
        val viewports = if (vpArray == null || viewportsOversized) emptyList() else {
            // only GEO viewports are candidates, and the selected index counts just those
            (0 until vpArray.size()).mapNotNull { i ->
                (vpArray.getObject(i) as? COSDictionary)?.takeIf(::isGeoViewport)?.let(reader::viewport)
            }
        }
        if (reader.exhausted) viewportsOversized = true

        // /LGIDict null is declared-but-junk too, PDFBox's getDictionaryObject would hide it
        val lgi = page.raw("LGIDict")
        val lgiOversized = lgi is COSArray && lgi.size() > MAX_METADATA_ENTRIES
        // a dictionary or an array of them; other members are skipped, anything else is declared-but-junk
        val lgiDicts: List<COSDictionary> = when {
            lgi is COSDictionary -> listOf(lgi)
            lgi is COSArray && !lgiOversized -> (0 until lgi.size()).mapNotNull { lgi.getObject(it) as? COSDictionary }
            else -> emptyList()
        }
        val lgiEntries = lgiDicts.map(reader::lgiEntry)
        return GeoPdfPageData(
            pageIndex = pageIndex,
            mediaBox = media,
            cropBox = crop,
            rotate = rotate,
            viewports = viewports,
            viewportsOversized = viewportsOversized,
            lgiDeclared = lgi != null,
            lgiEntries = lgiEntries,
            lgiOversized = lgiOversized || reader.exhausted,
        )
    }

    /**
     * Just a page's boxes + /Rotate, for the pages past the georef scan (E12). Same
     * inheritance and fallbacks as [pageData] so page 51 can't get away with a CropBox it
     * only inherits. (mediaBox, cropBox or null, rotate)
     */
    internal fun pageBoxes(doc: PDDocument, pageIndex: Int): Triple<List<Double>, List<Double>?, Int>? {
        if (pageIndex !in 0 until doc.numberOfPages) return null
        val page = doc.getPage(pageIndex).cosObject
        val reader = Reader()
        val media = inherited(page, "MediaBox")?.let(reader::boxNumbers)
            ?.takeIf { it.size == 4 && it.all { v -> v != null && v.isFinite() } }?.map { it!! }
            ?: LETTER
        val crop = inherited(page, "CropBox")?.let(reader::boxNumbers)
            ?.takeIf { it.size == 4 && it.all { v -> v != null && v.isFinite() } }?.map { it!! }
        val rotate = (inherited(page, "Rotate") as? COSNumber)?.intValue() ?: 0
        return Triple(media, crop, rotate)
    }

    // MediaBox / CropBox / Rotate are inheritable from the page tree
    private fun inherited(page: COSDictionary, key: String): COSBase? {
        var node: COSDictionary? = page
        var depth = 0
        val name = COSName.getPDFName(key)
        while (node != null && depth < MAX_PARENT_DEPTH) {
            node.getDictionaryObject(name)?.let { return it }
            node = node.getDictionaryObject(COSName.PARENT) as? COSDictionary
            depth++
        }
        return null
    }

    private fun hasGeoViewport(vp: COSArray): Boolean =
        (0 until vp.size()).any { (vp.getObject(it) as? COSDictionary)?.let(::isGeoViewport) == true }

    private fun isGeoViewport(dict: COSDictionary): Boolean {
        val measure = dict.getDictionaryObject(COSName.getPDFName("Measure")) as? COSDictionary
        return measure?.text("Subtype") == "GEO"
    }

    /** one page's worth of reading, with the shared value budget */
    private class Reader {
        var remaining = MAX_PAGE_VALUES
        var exhausted = false
            private set

        /** reserve [n] values off the page budget, false (and the page is junk) once it's gone */
        private fun take(n: Int): Boolean {
            if (n > remaining) {
                exhausted = true
                remaining = 0
                return false
            }
            remaining -= n
            return true
        }

        // boxes don't come off the budget, they're 4 numbers and inherited
        fun boxNumbers(base: COSBase): List<Double?>? {
            val array = base as? COSArray ?: return null
            if (array.size() != 4) return null
            return (0 until 4).map { array.getObject(it)?.realValue() }
        }

        fun viewport(dict: COSDictionary): GeoPdfViewportData {
            val measure = dict.getDictionaryObject(COSName.getPDFName("Measure")) as? COSDictionary
            var gcsMalformed = false
            var wkt: String? = null
            var epsg: Int? = null
            when (val gcs = measure?.raw("GCS")) {
                null -> Unit
                is COSDictionary -> {
                    when (val w = gcs.raw("WKT")) {
                        null -> Unit
                        is COSString -> wkt = w.string
                        else -> gcsMalformed = true
                    }
                    when (val e = gcs.raw("EPSG")) {
                        null -> Unit
                        // has to be a pdf integer, 32610.0 or (32610) don't count
                        is COSInteger -> e.longValue().takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }
                            ?.let { epsg = it.toInt() } ?: run { gcsMalformed = true }
                        else -> gcsMalformed = true
                    }
                }
                else -> gcsMalformed = true
            }
            return GeoPdfViewportData(
                name = dict.text("Name"),
                isGeo = true,
                bbox = dict.numbersAt("BBox"),
                gpts = measure?.numbersAt("GPTS"),
                lpts = measure?.numbersAt("LPTS"),
                bounds = measure?.numbersAt("Bounds"),
                gcsWkt = wkt,
                gcsEpsg = epsg,
                gcsMalformed = gcsMalformed,
            )
        }

        fun lgiEntry(dict: COSDictionary): GeoPdfLgiEntryData = GeoPdfLgiEntryData(
            description = dict.text("Description"),
            ctm = dict.numbersAt("CTM"),
            registration = registration(dict),
            neatline = dict.numbersAt("Neatline"),
            // a null (or non dict) /Projection or /Display reads as not there: no /Projection is
            // malformed anyway, /Display only gets looked at when /Projection lacks a zone
            projection = (dict.raw("Projection") as? COSDictionary)?.let(::projection),
            display = (dict.raw("Display") as? COSDictionary)?.let(::projection),
        )

        /**
         * Rows of [x y X Y]. Each row's length is checked BEFORE any value is read:
         * every row can be an indirect ref to the same 8k array, and reading those
         * blew up to hundreds of MB from a 60 KB file. A bad row -> listOf(null), malformed.
         */
        private fun registration(dict: COSDictionary): List<List<Double?>?>? {
            val reg = dict.raw("Registration") ?: return null
            if (reg !is COSArray || reg.size() > MAX_REGISTRATION_ROWS) return listOf(null)
            val rows = ArrayList<List<Double?>?>(reg.size())
            for (i in 0 until reg.size()) {
                val row = reg.getObject(i) as? COSArray
                if (row == null || row.size() != 4 || !take(4)) return listOf(null)
                rows += (0 until 4).map { row.getObject(it)?.realValue() }
            }
            return rows
        }

        private fun projection(dict: COSDictionary): GeoPdfLgiProjectionData {
            val params = HashMap<String, Double?>()
            for (key in listOf(
                "CentralMeridian", "OriginLatitude", "FalseEasting", "FalseNorthing", "ScaleFactor",
                "StandardParallelOne", "StandardParallelTwo",
            )) {
                // present but junk (null, a name, a bad string) stays in the map as null, that's malformed
                dict.raw(key)?.let { params[key] = it.realValue() }
            }
            val datum = when (val d = dict.raw("Datum")) {
                null -> null
                is COSName -> LgiDatumData.Code(d.name)
                is COSString -> LgiDatumData.Code(d.string)
                // a bare integer reads as its digits (iOS does the same), it won't match a code
                is COSInteger -> LgiDatumData.Code(d.longValue().toString())
                is COSDictionary -> {
                    val ellipsoid = d.raw("Ellipsoid") as? COSDictionary
                    val shift = when (val t = d.raw("ToWGS84")) {
                        null -> null
                        is COSDictionary -> listOf(t.real("dx"), t.real("dy"), t.real("dz"))
                        else -> listOf(null)
                    }
                    LgiDatumData.Inline(
                        semiMajorAxis = ellipsoid?.real("SemiMajorAxis"),
                        inverseFlattening = ellipsoid?.real("InvFlattening"),
                        toWgs84 = shift,
                    )
                }
                else -> LgiDatumData.Invalid
            }
            return GeoPdfLgiProjectionData(
                projectionType = dict.raw("ProjectionType")?.let(::textOf),
                // lgiRules.valueTypes: a /Zone that's there but isn't a number (a name like /10,
                // junk text, null) is NaN so it fails the zone check instead of falling back to /Display
                zone = dict.raw("Zone")?.let { it.realValue() ?: Double.NaN },
                // present but not text (null too) still has to fail the unit / hemisphere check, not read as absent
                hemisphere = dict.raw("Hemisphere")?.let { textOf(it) ?: INVALID_TEXT },
                datum = datum,
                parameters = params,
                units = dict.raw("Units")?.let { textOf(it) ?: INVALID_TEXT },
            )
        }

        /** absent -> null, present but not an array (or over budget) -> [null] so it reads as malformed */
        private fun COSDictionary.numbersAt(key: String): List<Double?>? =
            when (val v = raw(key)) {
                null -> null
                is COSArray -> numbers(v)
                else -> listOf(null)
            }

        private fun numbers(array: COSArray): List<Double?> {
            if (array.size() > MAX_GEO_CONTROL_VALUES || !take(array.size())) return listOf(null)
            return (0 until array.size()).map { array.getObject(it)?.realValue() }
        }
    }

    internal const val INVALID_TEXT = "\u0000invalid"

    private fun textOf(v: COSBase): String? = when (v) {
        is COSName -> v.name
        is COSString -> v.string
        else -> null
    }

    private fun COSDictionary.text(key: String): String? = getDictionaryObject(COSName.getPDFName(key))?.let(::textOf)

    private fun COSDictionary.real(key: String): Double? = raw(key)?.realValue()

    /**
     * The value under [key] with a pdf null kept as COSNull. getDictionaryObject turns
     * null into "no key", but lgiRules.nullValues says a null is PRESENT with the
     * wrong type on every georef key. A ref to an object that isn't there is null too
     */
    private fun COSDictionary.raw(key: String): COSBase? {
        val item = getItem(COSName.getPDFName(key)) ?: return null
        return if (item is COSObject) item.getObject() ?: COSNull.NULL else item
    }

    @Suppress("DEPRECATION") // COSFloat.doubleValue is the exact BigDecimal in pdfbox-android 2.0.x
    private fun COSBase.realValue(): Double? = when (this) {
        // PDFBox clamps an overflowing real to +-Float.MAX_VALUE and a huge int to
        // Long.MIN/MAX, undo that so it reads as non finite
        is COSFloat -> doubleValue().let { if (kotlin.math.abs(it) >= Float.MAX_VALUE.toDouble()) it * Double.POSITIVE_INFINITY else it }
        is COSInteger -> longValue().let {
            if (it == Long.MAX_VALUE) Double.POSITIVE_INFINITY
            else if (it == Long.MIN_VALUE) Double.NEGATIVE_INFINITY
            else it.toDouble()
        }
        is COSNumber -> doubleValue()
        // LGIDicts from ADF/AUSLIG tools write numbers as strings, (-122.6) etc. Only the
        // lgiRules.numericStrings grammar though, toDouble also takes 10d, hex, NaN...
        is COSString -> PdfValueRules.numericString(string)
        else -> null
    }
}
