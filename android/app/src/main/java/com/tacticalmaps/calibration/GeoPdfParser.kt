package com.tacticalmaps.calibration

import android.content.Context
import android.net.Uri
import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import java.io.File
import java.util.UUID

private const val TAG = "GeoPdfParser"

/**
 * Pulls embedded georeferencing out of a GeoPDF without asking the user to drop
 * fiduciaries by hand. Two flavours are supported:
 *
 *  - **OGC GeoPDF / Adobe GeoPDF** (the modern format, ~2009 onward): the page
 *    or document catalog has a `/VP` array of Viewport dictionaries; each
 *    viewport carries a `/Measure` dictionary with `/Subtype /GEO`, a
 *    `/GPTS` array of `[lat lon ...]` pairs, and an `/LPTS` array of
 *    `[x y ...]` pairs normalised to the viewport's `/BBox`.
 *  - **TerraGo / legacy LGIDict**: the page's `/LGIDict` dictionary holds
 *    `Neatline` (the polygon enclosing the map face) and `CTM`/`Registration`
 *    (point pairs). Many older GeoPDFs use only this form.
 *
 * Both formats yield (PDF user-space point) → (WGS84 lat/lon) correspondences,
 * which we hand to [AffineFitter] to derive the same six-coefficient affine
 * the manual fiduciary flow uses.
 */
object GeoPdfParser {
    private var initialised = false

    private fun ensureInit(context: Context) {
        if (!initialised) {
            PDFBoxResourceLoader.init(context.applicationContext)
            initialised = true
        }
    }

    /**
     * Attempts to extract georeferencing from the first page of [uri].
     * Returns `null` if the PDF has no recognisable GeoPDF metadata, in
     * which case the import flow falls back to the user-driven
     * fiduciary calibration UI.
     */
    fun parse(context: Context, uri: Uri): GeoPdfResult? {
        ensureInit(context)
        val file = uriToFile(uri) ?: return null
        if (!file.exists()) return null
        return runCatching {
            PDDocument.load(file).use { doc ->
                val page = doc.getPage(0) ?: return@use null
                val pageW = page.mediaBox.width.toDouble()
                val pageH = page.mediaBox.height.toDouble()
                val correspondences =
                    extractAdobeViewports(page)
                        ?: extractAdobeViewports(doc.documentCatalog.cosObject)
                        ?: extractLegacyLgiDict(page)
                if (correspondences == null || correspondences.size < 3) return@use null
                Log.i(TAG, "GeoPDF parsed: ${correspondences.size} correspondences")
                GeoPdfResult(
                    pageWidth = pageW,
                    pageHeight = pageH,
                    correspondences = correspondences
                )
            }
        }.onFailure {
            Log.w(TAG, "GeoPDF parse failed: ${it.message}")
        }.getOrNull()
    }

    /// Adobe / OGC viewports — searches a page or catalog dictionary.
    ///
    /// A page often carries SEVERAL viewports: the map neatline PLUS small
    /// marginalia insets (adjoining-sheets index, state locator). We must NOT
    /// take the first usable one — QTopo sheets list the adjoining-sheets inset
    /// first, and that inset is georeferenced against a 145°E prime meridian, so
    /// trusting it drops the import off the coast of West Africa. The map body
    /// is always the LARGEST viewport by BBox area, so keep the candidate with
    /// the greatest area.
    private fun extractAdobeViewports(parent: COSDictionary): List<GeoCorrespondence>? {
        val vp = parent.getDictionaryObject(COSName.getPDFName("VP")) as? COSArray ?: return null
        var best: List<GeoCorrespondence>? = null
        var bestArea = -1.0
        for (i in 0 until vp.size()) {
            val viewport = vp.getObject(i) as? COSDictionary ?: continue
            val measure = viewport
                .getDictionaryObject(COSName.getPDFName("Measure")) as? COSDictionary
                ?: continue
            if (measure.getNameAsString("Subtype") != "GEO") continue
            val gpts = measure.getDictionaryObject(COSName.getPDFName("GPTS")) as? COSArray ?: continue
            val lpts = measure.getDictionaryObject(COSName.getPDFName("LPTS")) as? COSArray ?: continue
            val bbox = (viewport.getDictionaryObject(COSName.getPDFName("BBox")) as? COSArray)
                ?: continue
            val bx0 = bbox.numAt(0) ?: continue
            val by0 = bbox.numAt(1) ?: continue
            val bx1 = bbox.numAt(2) ?: continue
            val by1 = bbox.numAt(3) ?: continue
            /// The two BBox numbers are diagonal corners specified
            /// in the SAME ORDER they pair with LPTS — i.e.
            /// LPTS(0, 0) → first corner, LPTS(1, 1) → second
            /// corner. This handles both the standard
            /// `[llx lly urx ury]` form (corners are lower-left and
            /// upper-right, Y deltas positive) AND the TerraGo /
            /// raster-style form where the second corner has a
            /// smaller Y (negative delta, page rendered "Y-down").
            /// Treating the two corners as just "endpoints of the
            /// LPTS axis" gets the right answer in both cases.
            val dx = bx1 - bx0
            val dy = by1 - by0
            if (kotlin.math.abs(dx) < 1e-9 || kotlin.math.abs(dy) < 1e-9) continue

            // GPTS longitudes are relative to the GCS prime meridian (Greenwich
            // for the map body, but 145°E on some QTopo insets).
            val primeMeridian = primeMeridianOffset(measure)

            val list = mutableListOf<GeoCorrespondence>()
            val pairs = minOf(gpts.size() / 2, lpts.size() / 2)
            for (j in 0 until pairs) {
                val lat = gpts.numAt(j * 2) ?: continue
                val lon = gpts.numAt(j * 2 + 1) ?: continue
                val nx = lpts.numAt(j * 2) ?: continue
                val ny = lpts.numAt(j * 2 + 1) ?: continue
                val pdfX = bx0 + nx * dx
                val pdfY = by0 + ny * dy
                list += GeoCorrespondence(
                    pdfX = pdfX, pdfY = pdfY,
                    latitude = lat, longitude = lon + primeMeridian
                )
            }
            val area = kotlin.math.abs(dx * dy)
            if (list.size >= 3 && area > bestArea) {
                best = list
                bestArea = area
            }
        }
        return best
    }

    /// GPTS longitudes are measured from the GCS prime meridian — almost always
    /// Greenwich (0), but some QTopo insets declare e.g. `PRIMEM["…",145.0]`.
    /// Without adding that offset the longitudes come out ~145° too small.
    /// Parses the offset from the Measure's /GCS /WKT string.
    private fun primeMeridianOffset(measure: COSDictionary): Double {
        val gcs = measure.getDictionaryObject(COSName.getPDFName("GCS")) as? COSDictionary ?: return 0.0
        val wkt = (gcs.getDictionaryObject(COSName.getPDFName("WKT")) as? COSString)?.string ?: return 0.0
        val match = Regex("""PRIMEM\["[^"]*",\s*(-?\d+(?:\.\d+)?)""").find(wkt) ?: return 0.0
        return match.groupValues[1].toDoubleOrNull() ?: 0.0
    }

    /// Convenience overload — page-level extraction.
    private fun extractAdobeViewports(page: PDPage): List<GeoCorrespondence>? =
        extractAdobeViewports(page.cosObject)

    /// Legacy TerraGo LGIDict (older GeoPDFs).
    ///
    /// LGIDict carries `Neatline` (the polygon enclosing the map
    /// face) and `Registration` (point pairs as `[PDFx PDFy lat lon]`).
    /// We pull the registration list directly — that's the same shape
    /// of (PDF point, geographic point) the modern format provides.
    private fun extractLegacyLgiDict(page: PDPage): List<GeoCorrespondence>? {
        val lgi = page.cosObject.getDictionaryObject(COSName.getPDFName("LGIDict")) as? COSBase
            ?: return null
        val dicts: List<COSDictionary> = when (lgi) {
            is COSDictionary -> listOf(lgi)
            is COSArray -> (0 until lgi.size()).mapNotNull {
                lgi.getObject(it) as? COSDictionary
            }
            else -> return null
        }
        for (dict in dicts) {
            val reg = dict.getDictionaryObject(COSName.getPDFName("Registration")) as? COSArray
                ?: continue
            val list = mutableListOf<GeoCorrespondence>()
            for (i in 0 until reg.size()) {
                val pair = reg.getObject(i) as? COSArray ?: continue
                if (pair.size() < 4) continue
                val pdfX = pair.numAt(0) ?: continue
                val pdfY = pair.numAt(1) ?: continue
                val lat = pair.numAt(2) ?: continue
                val lon = pair.numAt(3) ?: continue
                list += GeoCorrespondence(pdfX, pdfY, lat, lon)
            }
            if (list.size >= 3) return list
        }
        return null
    }

    private fun COSArray.numAt(index: Int): Double? =
        (getObject(index) as? COSNumber)?.floatValue()?.toDouble()

    private fun uriToFile(uri: Uri): File? {
        if (uri.scheme != "file") return null
        val path = uri.path ?: return null
        return File(path)
    }
}

data class GeoPdfResult(
    val pageWidth: Double,
    val pageHeight: Double,
    val correspondences: List<GeoCorrespondence>
)

data class GeoCorrespondence(
    val pdfX: Double,
    val pdfY: Double,
    val latitude: Double,
    val longitude: Double
) {
    fun toFiduciary(label: String? = null): Fiduciary = Fiduciary(
        id = UUID.randomUUID().toString(),
        pdfX = pdfX,
        pdfY = pdfY,
        mgrs = "",
        latitude = latitude,
        longitude = longitude,
        label = label
    )
}
