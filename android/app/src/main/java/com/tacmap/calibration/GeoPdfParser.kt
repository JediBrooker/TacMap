package com.tacmap.calibration

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
import kotlin.math.abs

private const val TAG = "GeoPdfParser"

/**
 * Pulls embedded georeferencing out of a GeoPDF so the user doesn't have
 * to drop fiduciaries by hand. Two flavours supported:
 *
 *  - OGC / Adobe GeoPDF (modern, ~2009+): page or catalog has a `/VP`
 *    array of Viewport dicts; each carries a `/Measure` with `/Subtype /GEO`,
 *    `/GPTS` (lat lon pairs), and `/LPTS` (x y pairs normalised to `/BBox`).
 *  - TerraGo / legacy LGIDict: page's `/LGIDict` has `Neatline` (polygon
 *    around the map face) and `CTM`/`Registration` (point pairs). Lots of
 *    older GeoPDFs only have this.
 *
 * Both formats give us (PDF user-space point) -> (WGS84 lat/lon)
 * correspondences that we feed to [AffineFitter] for the same six-coeff
 * affine the manual fiduciary flow produces.
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
     * Try to extract georeferencing from first page of [uri].
     * Returns null if there's no recognisable GeoPDF metadata, in which
     * case we fall back to user-driven fiduciary calibration.
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

    /**
     * First-page rotation in degrees (0/90/180/270), or 0 if unreadable.
     * Nothing in calibration/tiling handles /Rotate, so the import flow
     * rejects non-zero rather than silently misregistering the sheet.
     */
    fun pageRotation(context: Context, uri: Uri): Int {
        ensureInit(context)
        val file = uriToFile(uri) ?: return 0
        if (!file.exists()) return 0
        return runCatching {
            PDDocument.load(file).use { doc -> doc.getPage(0)?.rotation ?: 0 }
        }.getOrDefault(0)
    }

    // Adobe / OGC viewports - searches a page or catalog dictionary.
    //
    // A page often has SEVERAL viewports: the map neatline plus small
    // marginalia insets (adjoining-sheets index, state locator). We can't
    // just take the first usable one b/c QTopo sheets list the adjoining-sheets
    // inset first, and that inset is georeferenced against 145 deg E prime
    // meridian so trusting it drops the import off the coast of West Africa.
    // The map body is always the LARGEST viewport by BBox area, so we pick
    // the candidate with greatest area.
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
            // BBox corners are diagonal, specified in same order they pair
            // with LPTS - i.e. LPTS(0,0) -> first corner, LPTS(1,1) -> second.
            // Works for both the standard [llx lly urx ury] form AND the
            // TerraGo / raster-style form where second corner has smaller Y
            // (negative delta, Y-down). Just treating them as "endpoints of
            // the LPTS axis" gets the right answer either way.
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

    // GPTS longitudes are relative to the GCS prime meridian, almost always
    // Greenwich (0) but some QTopo insets declare e.g. PRIMEM["...",145.0].
    // Without adding that offset the longitudes come out ~145 deg too small.
    // Parses the offset from the Measure's /GCS /WKT string.
    private fun primeMeridianOffset(measure: COSDictionary): Double {
        val gcs = measure.getDictionaryObject(COSName.getPDFName("GCS")) as? COSDictionary ?: return 0.0
        val wkt = (gcs.getDictionaryObject(COSName.getPDFName("WKT")) as? COSString)?.string ?: return 0.0
        val match = Regex("""PRIMEM\["[^"]*",\s*(-?\d+(?:\.\d+)?)""").find(wkt) ?: return 0.0
        return match.groupValues[1].toDoubleOrNull() ?: 0.0
    }

    // convenience overload for page-level extraction
    private fun extractAdobeViewports(page: PDPage): List<GeoCorrespondence>? =
        extractAdobeViewports(page.cosObject)

    // Legacy TerraGo LGIDict (older GeoPDFs).
    //
    // LGIDict has `Neatline` (polygon around the map face) and
    // `Registration` (point pairs as [PDFx PDFy lat lon]).
    // We just pull the registration list directly, same shape of
    // (PDF point, geographic point) as the modern format.
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
            var unsupported = false
            for (i in 0 until reg.size()) {
                val pair = reg.getObject(i) as? COSArray ?: continue
                if (pair.size() < 4) continue
                val pdfX = pair.numAt(0) ?: continue
                val pdfY = pair.numAt(1) ?: continue
                // LGIDict Registration map coords are [mapX mapY] in the CRS
                // from the sibling /Projection (OGC order = [lon lat]). Old code
                // read them as [lat lon] (swapped) and treated projected
                // easting/northing as raw lat/lon. Now we interpret by geographic
                // range instead, and bail on projected CRS we can't invert on
                // Android rather than emitting wrong georeferencing.
                val mapX = pair.numAt(2) ?: continue
                val mapY = pair.numAt(3) ?: continue
                val latLon = geographicLatLon(mapX, mapY)
                if (latLon == null) { unsupported = true; break }
                list += GeoCorrespondence(pdfX, pdfY, latLon.first, latLon.second)
            }
            if (!unsupported && list.size >= 3) return list
        }
        return null
    }

    /**
     * Interpret an LGIDict Registration map-coordinate pair as (lat, lon),
     * or null if it's a projected easting/northing (values outside +/-180)
     * that we can't invert on Android. OGC order is [lon lat]; range
     * checks also auto-correct a lat/lon-swapped sheet (lat must be +/-90).
     */
    private fun geographicLatLon(x: Double, y: Double): Pair<Double, Double>? {
        if (abs(x) > 180.0 || abs(y) > 180.0) return null // projected coords, not supported here
        return when {
            abs(x) <= 90.0 && abs(y) <= 90.0 -> y to x     // OGC order: x=lon, y=lat
            abs(x) > 90.0 && abs(y) <= 90.0 -> y to x       // x can only be lon
            abs(x) <= 90.0 && abs(y) > 90.0 -> x to y       // y can only be lon (swapped sheet)
            else -> null                                    // both > 90: not valid lat/lon
        }
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
