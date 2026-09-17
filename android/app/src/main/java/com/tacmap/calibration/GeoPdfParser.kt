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
import java.util.Locale
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
    private const val MAX_METADATA_ENTRIES = 64
    private const val MAX_GEO_CONTROL_VALUES = 8_192

    private sealed class AdobeViewportSelection {
        data object Absent : AdobeViewportSelection()
        data object Rejected : AdobeViewportSelection()
        data class Accepted(val correspondences: List<GeoCorrespondence>) : AdobeViewportSelection()
    }

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
                val pageAdobe = selectAdobeViewports(page)
                val adobe = if (pageAdobe == AdobeViewportSelection.Absent) {
                    selectAdobeViewports(doc.documentCatalog.cosObject)
                } else {
                    pageAdobe
                }
                val correspondences = when (adobe) {
                    is AdobeViewportSelection.Accepted -> adobe.correspondences
                    AdobeViewportSelection.Rejected -> return@use null
                    AdobeViewportSelection.Absent -> extractLegacyLgiDict(page)
                }
                if (!pageW.isFinite() || !pageH.isFinite() || pageW <= 0.0 || pageH <= 0.0 ||
                    correspondences == null || correspondences.size < 3 ||
                    correspondences.any { !it.isValid() }
                ) return@use null
                Log.i(TAG, "GeoPDF parsed: ${correspondences.size} correspondences")
                GeoPdfResult(
                    pageWidth = pageW,
                    pageHeight = pageH,
                    correspondences = correspondences
                )
            }
        }.onFailure { Log.w(TAG, "GeoPDF parse failed") }.getOrNull()
    }

    /**
     * First-page rotation in normalized degrees, or null when it cannot be
     * inspected. Nothing in calibration/tiling handles /Rotate, so callers
     * must reject both non-zero and unknown rotation rather than silently
     * misregistering a sheet.
     */
    fun pageRotation(context: Context, uri: Uri): Int? {
        ensureInit(context)
        val file = uriToFile(uri) ?: return null
        if (!file.exists()) return null
        return runCatching {
            PDDocument.load(file).use { doc ->
                val raw = doc.getPage(0)?.rotation ?: 0
                ((raw % 360) + 360) % 360
            }
        }.getOrNull()
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
    private fun selectAdobeViewports(parent: COSDictionary): AdobeViewportSelection {
        val vp = parent.getDictionaryObject(COSName.getPDFName("VP")) as? COSArray
            ?: return AdobeViewportSelection.Absent
        if (vp.size() == 0) return AdobeViewportSelection.Absent
        if (vp.size() > MAX_METADATA_ENTRIES) return AdobeViewportSelection.Rejected

        var best: List<GeoCorrespondence>? = null
        var bestArea = -1.0
        var largestMalformedDeclaredGeoArea = -1.0
        var hasUnrankableMalformedDeclaredGeo = false
        var sawDeclaredGeo = false
        for (i in 0 until vp.size()) {
            val viewport = vp.getObject(i) as? COSDictionary ?: continue
            val measure = viewport
                .getDictionaryObject(COSName.getPDFName("Measure")) as? COSDictionary
                ?: continue
            if (measure.getNameAsString("Subtype") != "GEO") continue
            sawDeclaredGeo = true

            val bbox = (viewport.getDictionaryObject(COSName.getPDFName("BBox")) as? COSArray)
            if (bbox == null || bbox.size() != 4) {
                hasUnrankableMalformedDeclaredGeo = true
                continue
            }
            val bx0 = bbox.numAt(0)
            val by0 = bbox.numAt(1)
            val bx1 = bbox.numAt(2)
            val by1 = bbox.numAt(3)
            if (bx0 == null || by0 == null || bx1 == null || by1 == null ||
                !bx0.isFinite() || abs(bx0) > MAX_SAFE_PDF_COORDINATE ||
                !by0.isFinite() || abs(by0) > MAX_SAFE_PDF_COORDINATE ||
                !bx1.isFinite() || abs(bx1) > MAX_SAFE_PDF_COORDINATE ||
                !by1.isFinite() || abs(by1) > MAX_SAFE_PDF_COORDINATE
            ) {
                hasUnrankableMalformedDeclaredGeo = true
                continue
            }
            // BBox corners are diagonal, specified in same order they pair
            // with LPTS - i.e. LPTS(0,0) -> first corner, LPTS(1,1) -> second.
            // Works for both the standard [llx lly urx ury] form AND the
            // TerraGo / raster-style form where second corner has smaller Y
            // (negative delta, Y-down). Just treating them as "endpoints of
            // the LPTS axis" gets the right answer either way.
            val dx = bx1 - bx0
            val dy = by1 - by0
            if (!dx.isFinite() || !dy.isFinite() ||
                kotlin.math.abs(dx) < 1e-9 || kotlin.math.abs(dy) < 1e-9
            ) {
                hasUnrankableMalformedDeclaredGeo = true
                continue
            }
            val area = kotlin.math.abs(dx * dy)
            if (!area.isFinite()) {
                hasUnrankableMalformedDeclaredGeo = true
                continue
            }

            val gpts = measure.getDictionaryObject(COSName.getPDFName("GPTS")) as? COSArray
            val lpts = measure.getDictionaryObject(COSName.getPDFName("LPTS")) as? COSArray
            if (gpts == null || lpts == null ||
                gpts.size() < 6 || gpts.size() > MAX_GEO_CONTROL_VALUES ||
                gpts.size() != lpts.size() || gpts.size() % 2 != 0
            ) {
                largestMalformedDeclaredGeoArea = maxOf(largestMalformedDeclaredGeoArea, area)
                continue
            }

            // GPTS longitudes are relative to the GCS prime meridian (Greenwich
            // for the map body, but 145°E on some QTopo insets).
            val primeMeridian = primeMeridianOffset(measure)
            if (primeMeridian == null) {
                largestMalformedDeclaredGeoArea = maxOf(largestMalformedDeclaredGeoArea, area)
                continue
            }

            val list = mutableListOf<GeoCorrespondence>()
            val pairs = minOf(gpts.size() / 2, lpts.size() / 2)
            var malformed = false
            for (j in 0 until pairs) {
                val lat = gpts.numAt(j * 2)
                val lon = gpts.numAt(j * 2 + 1)
                val nx = lpts.numAt(j * 2)
                val ny = lpts.numAt(j * 2 + 1)
                if (lat == null || lon == null || nx == null || ny == null ||
                    lat !in -90.0..90.0 || nx !in 0.0..1.0 || ny !in 0.0..1.0
                ) {
                    malformed = true
                    break
                }
                val pdfX = bx0 + nx * dx
                val pdfY = by0 + ny * dy
                val correspondence = GeoCorrespondence(
                    pdfX = pdfX, pdfY = pdfY,
                    latitude = lat, longitude = lon + primeMeridian
                )
                if (!correspondence.isValid()) {
                    malformed = true
                    break
                }
                list += correspondence
            }
            if (malformed || list.size < 3) {
                largestMalformedDeclaredGeoArea = maxOf(largestMalformedDeclaredGeoArea, area)
                continue
            }
            if (area > bestArea) {
                best = list
                bestArea = area
            }
        }
        if (best != null) {
            return if (hasUnrankableMalformedDeclaredGeo ||
                largestMalformedDeclaredGeoArea > bestArea
            ) {
                AdobeViewportSelection.Rejected
            } else {
                AdobeViewportSelection.Accepted(best)
            }
        }
        return if (sawDeclaredGeo) {
            AdobeViewportSelection.Rejected
        } else {
            AdobeViewportSelection.Absent
        }
    }

    // GPTS longitudes are relative to the GCS prime meridian, almost always
    // Greenwich (0) but some QTopo insets declare e.g. PRIMEM["...",145.0].
    // Without adding that offset the longitudes come out ~145 deg too small.
    // Parses the offset from the Measure's /GCS /WKT string.
    private fun primeMeridianOffset(measure: COSDictionary): Double? {
        val gcs = measure.getDictionaryObject(COSName.getPDFName("GCS")) as? COSDictionary ?: return 0.0
        val wkt = (gcs.getDictionaryObject(COSName.getPDFName("WKT")) as? COSString)?.string ?: return 0.0
        if (!wkt.contains("PRIMEM", ignoreCase = true)) return 0.0
        val match = Regex(
            """PRIMEM\["[^"]*",\s*([-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[Ee][-+]?\d+)?)""",
            RegexOption.IGNORE_CASE,
        ).find(wkt) ?: return null
        return match.groupValues[1].toDoubleOrNull()
            ?.takeIf { it.isFinite() && it in -180.0..180.0 }
    }

    // convenience overload for page-level extraction
    private fun selectAdobeViewports(page: PDPage): AdobeViewportSelection =
        selectAdobeViewports(page.cosObject)

    // Legacy TerraGo LGIDict (older GeoPDFs). Registration map coordinates and
    // CTM output are expressed in the sibling Projection's CRS, not necessarily
    // degrees. Decode that CRS and its source datum before creating any WGS84
    // correspondence; unsupported or incomplete projected metadata fails closed.
    private fun extractLegacyLgiDict(page: PDPage): List<GeoCorrespondence>? {
        val lgi: COSBase = page.cosObject.getDictionaryObject(COSName.getPDFName("LGIDict"))
            ?: return null
        val dicts = when (lgi) {
            is COSDictionary -> listOf(lgi)
            is COSArray -> {
                if (lgi.size() !in 1..MAX_METADATA_ENTRIES) return null
                (0 until lgi.size()).mapNotNull { lgi.getObject(it) as? COSDictionary }
            }
            else -> return null
        }
        // A multi-entry topo sheet can also contain georeferenced legends,
        // boundary guides, and adjoining-sheet insets. Once the producer names
        // a main `Layers` entry, never fall back to a different valid inset if
        // that main entry is malformed or unsupported.
        val layerEntries = dicts.filter { it.text("Description") == "Layers" }
        val candidates = layerEntries.ifEmpty { dicts }
        for (dict in candidates) {
            val reg = dict.getDictionaryObject(COSName.getPDFName("Registration")) as? COSArray
            val projectionDictionary =
                dict.getDictionaryObject(COSName.getPDFName("Projection")) as? COSDictionary
            val converter = projectionDictionary?.let { projectionConverter(dict, it) }

            if (reg != null) {
                // Some very old geographic-only LGIDicts omit /Projection. Keep
                // that narrow compatibility case only when every Registration
                // coordinate proves to be a valid longitude/latitude pair.
                val registrations = if (projectionDictionary == null) {
                    registrationCorrespondences(reg, converter = null)
                } else if (converter != null) {
                    registrationCorrespondences(reg, converter)
                } else {
                    null
                }
                if (registrations != null) return registrations
                // Conflicting/malformed Registration metadata invalidates this
                // entry; never silently downgrade to a sibling CTM.
                continue
            }

            if (converter != null) {
                ctmCorrespondences(page, dict, converter)?.let { return it }
            }
        }
        return null
    }

    private fun registrationCorrespondences(
        registrations: COSArray,
        converter: LgiCoordinateConverter?,
    ): List<GeoCorrespondence>? {
        if (registrations.size() !in 3..(MAX_GEO_CONTROL_VALUES / 2)) return null
        val result = ArrayList<GeoCorrespondence>(registrations.size())
        for (index in 0 until registrations.size()) {
            val pair = registrations.getObject(index) as? COSArray ?: return null
            if (pair.size() != 4) return null
            val pdfX = pair.realAt(0) ?: return null
            val pdfY = pair.realAt(1) ?: return null
            val mapX = pair.realAt(2) ?: return null
            val mapY = pair.realAt(3) ?: return null
            val wgs84: Pair<Double, Double> = (
                converter?.toWgs84(mapX, mapY)
                    ?: if (converter == null) legacyGeographicLatLon(mapX, mapY) else null
                ) ?: return null
            val correspondence = GeoCorrespondence(pdfX, pdfY, wgs84.first, wgs84.second)
            if (!correspondence.isValid()) return null
            result += correspondence
        }
        return result.takeIf { it.size >= 3 }
    }

    private fun ctmCorrespondences(
        page: PDPage,
        dictionary: COSDictionary,
        converter: LgiCoordinateConverter,
    ): List<GeoCorrespondence>? {
        val ctm = dictionary.getDictionaryObject(COSName.getPDFName("CTM")) as? COSArray
            ?: return null
        if (ctm.size() != 6) return null
        val values = (0 until 6).map { ctm.realAt(it) ?: return null }
        val a = values[0]
        val b = values[1]
        val c = values[2]
        val d = values[3]
        val e = values[4]
        val f = values[5]

        val neatlineValue = dictionary.getDictionaryObject(COSName.getPDFName("Neatline"))
        val pdfPoints: List<Pair<Double, Double>> = when (neatlineValue) {
            null -> {
                val box = page.mediaBox
                listOf(
                    box.lowerLeftX.toDouble() to box.lowerLeftY.toDouble(),
                    box.upperRightX.toDouble() to box.lowerLeftY.toDouble(),
                    box.upperRightX.toDouble() to box.upperRightY.toDouble(),
                    box.lowerLeftX.toDouble() to box.upperRightY.toDouble(),
                )
            }
            is COSArray -> {
                if (neatlineValue.size() < 6 ||
                    neatlineValue.size() > MAX_GEO_CONTROL_VALUES ||
                    neatlineValue.size() % 2 != 0
                ) return null
                (0 until neatlineValue.size() step 2).map { index ->
                    val x = neatlineValue.realAt(index) ?: return null
                    val y = neatlineValue.realAt(index + 1) ?: return null
                    x to y
                }
            }
            else -> return null
        }

        val result = ArrayList<GeoCorrespondence>(pdfPoints.size)
        for ((pdfX, pdfY) in pdfPoints) {
            val mapX = a * pdfX + c * pdfY + e
            val mapY = b * pdfX + d * pdfY + f
            if (!mapX.isFinite() || !mapY.isFinite()) return null
            val wgs84 = converter.toWgs84(mapX, mapY) ?: return null
            val correspondence = GeoCorrespondence(pdfX, pdfY, wgs84.first, wgs84.second)
            if (!correspondence.isValid()) return null
            result += correspondence
        }
        return result.takeIf { it.size >= 3 }
    }

    private fun projectionConverter(
        entry: COSDictionary,
        projection: COSDictionary,
    ): LgiCoordinateConverter? {
        val type = projection.text("ProjectionType")?.trim()?.uppercase(Locale.US) ?: return null
        val projected = type !in setOf("LL", "LONGLAT")
        val datum = parseDatum(projection, requireExplicit = projected) ?: return null
        val display = entry.getDictionaryObject(COSName.getPDFName("Display")) as? COSDictionary
        val decodedProjection = when (type) {
            "LL", "LONGLAT" -> LgiProjectionFactory.longLat()
            "UT", "UTM" -> {
                val zone = projection.int("Zone") ?: display?.int("Zone") ?: return null
                val hemisphere = projection.text("Hemisphere")
                    ?: display?.text("Hemisphere")
                    ?: return null
                val southern = when (hemisphere.trim().uppercase(Locale.US)) {
                    "N", "NORTH" -> false
                    "S", "SOUTH" -> true
                    else -> return null
                }
                LgiProjectionFactory.utm(zone, southern, datum.ellipsoid) ?: return null
            }
            "TC" -> LgiProjectionFactory.transverseMercator(
                centralMeridian = projection.real("CentralMeridian") ?: return null,
                originLatitude = projection.real("OriginLatitude") ?: return null,
                falseEasting = projection.real("FalseEasting") ?: return null,
                falseNorthing = projection.real("FalseNorthing") ?: return null,
                scaleFactor = projection.real("ScaleFactor") ?: return null,
                ellipsoid = datum.ellipsoid,
            ) ?: return null
            "LC" -> {
                val parallelOne = projection.real("StandardParallelOne") ?: return null
                LgiProjectionFactory.lambertConformalConic(
                    standardParallelOne = parallelOne,
                    standardParallelTwo = projection.real("StandardParallelTwo") ?: parallelOne,
                    originLatitude = projection.real("OriginLatitude") ?: return null,
                    centralMeridian = projection.real("CentralMeridian") ?: return null,
                    falseEasting = projection.real("FalseEasting") ?: return null,
                    falseNorthing = projection.real("FalseNorthing") ?: return null,
                    ellipsoid = datum.ellipsoid,
                ) ?: return null
            }
            else -> return null
        }
        return LgiCoordinateConverter(decodedProjection, datum)
    }

    private fun parseDatum(projection: COSDictionary, requireExplicit: Boolean): LgiDatum? {
        return when (val value = projection.getDictionaryObject(COSName.getPDFName("Datum"))) {
            null -> if (requireExplicit) null else LgiDatum.WGS84
            is COSName -> LgiDatum.fromCode(value.name)
            is COSString -> LgiDatum.fromCode(value.string)
            is COSDictionary -> {
                val ellipsoid = value.getDictionaryObject(COSName.getPDFName("Ellipsoid"))
                    as? COSDictionary ?: return null
                val semiMajorAxis = ellipsoid.real("SemiMajorAxis") ?: return null
                val inverseFlattening = ellipsoid.real("InvFlattening") ?: return null
                val toWgs84 = value.getDictionaryObject(COSName.getPDFName("ToWGS84"))
                    as? COSDictionary
                if (toWgs84 == null) {
                    // A non-modern ellipsoid without a translation cannot be
                    // placed safely on WGS84.
                    val modern = kotlin.math.abs(semiMajorAxis - LgiEllipsoid.WGS84.semiMajorAxis) < 1.0 &&
                        kotlin.math.abs(inverseFlattening - 298.257223563) < 0.01
                    if (!modern) return null
                    LgiDatum.inline(semiMajorAxis, inverseFlattening, 0.0, 0.0, 0.0)
                } else {
                    LgiDatum.inline(
                        semiMajorAxis,
                        inverseFlattening,
                        toWgs84.real("dx") ?: return null,
                        toWgs84.real("dy") ?: return null,
                        toWgs84.real("dz") ?: return null,
                    )
                }
            }
            else -> null
        }
    }

    /** Compatibility for pre-spec geographic Registration arrays only. */
    private fun legacyGeographicLatLon(x: Double, y: Double): Pair<Double, Double>? {
        if (!x.isFinite() || !y.isFinite()) return null
        return when {
            abs(x) <= 180.0 && abs(y) <= 90.0 -> y to x
            abs(x) <= 90.0 && abs(y) in 90.0..180.0 -> x to y
            else -> null
        }
    }

    private fun COSDictionary.text(key: String): String? = when (
        val value = getDictionaryObject(COSName.getPDFName(key))
    ) {
        is COSName -> value.name
        is COSString -> value.string
        else -> null
    }

    private fun COSDictionary.real(key: String): Double? =
        getDictionaryObject(COSName.getPDFName(key)).realValue()

    private fun COSDictionary.int(key: String): Int? {
        val value = real(key) ?: return null
        if (value % 1.0 != 0.0 || value !in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) return null
        return value.toInt()
    }

    private fun COSArray.numAt(index: Int): Double? = realAt(index)

    private fun COSArray.realAt(index: Int): Double? = getObject(index).realValue()

    @Suppress("DEPRECATION") // PDFBox's replacement-free accessor preserves COSDouble precision.
    private fun COSBase?.realValue(): Double? = when (this) {
        is COSNumber -> doubleValue().takeIf(Double::isFinite)
        is COSString -> string.trim().toDoubleOrNull()?.takeIf(Double::isFinite)
        else -> null
    }

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

private fun GeoCorrespondence.isValid(): Boolean =
    pdfX.isFinite() && pdfY.isFinite() &&
        latitude.isFinite() && longitude.isFinite() &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0
