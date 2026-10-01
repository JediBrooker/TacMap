package com.tacmap.map

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tacmap.calibration.Calibration
import com.tacmap.calibration.GeoCrs
import com.tacmap.calibration.GeoDatums
import com.tacmap.calibration.GeoPdfGeorefResult
import com.tacmap.calibration.GeorefOrigin
import com.tacmap.calibration.GeorefRejectReason
import com.tacmap.calibration.MapSourceKind
import com.tacmap.calibration.PagePoint
import com.tacmap.calibration.PdfBox
import com.tacmap.calibration.PdfDocumentInspector
import com.tacmap.calibration.PdfGeoreference
import com.tacmap.calibration.PdfMapSource
import com.tacmap.calibration.PdfPageGeometry
import com.tacmap.calibration.PdfPageRenderer
import com.tacmap.calibration.PdfTiler
import com.tacmap.calibration.PlaneAffine
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import mil.nga.grid.Hemisphere
import mil.nga.mgrs.utm.UTM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class PdfImportHardeningInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun plainPdfPreflightsAndPreviewDoesNotUpscale() {
        val file = createPdf("plain.pdf")
        val (width, height) = preflightPdfImport(context, file)
        val rendered = PdfPageRenderer.renderFirstPage(context, Uri.fromFile(file))
        try {
            assertTrue(width > 0 && height > 0)
            assertTrue(rendered.bitmap.width <= width)
            assertTrue(rendered.bitmap.height <= height)
            assertTrue(rendered.bitmap.allocationByteCount <= 32 * 1024 * 1024)
            assertEquals(GeoPdfGeorefResult.NoGeoreference, PdfDocumentInspector.inspect(context, file).georeference())
        } finally {
            rendered.bitmap.recycle()
        }
    }

    @Test
    fun rotatedPagesImportWithTheirRotationInsteadOfBeingRejected() {
        // plan 02 s1: georefs live in raw user space and the renderer maps through
        // /Rotate, so a turned page is supported (it used to be refused here)
        listOf(90, 180, 270).forEach { rotation ->
            val file = createPdf("rotated-$rotation.pdf", rotation)
            val (width, height) = preflightPdfImport(context, file)
            // pdfium reports the displayed (turned) size
            if (rotation % 180 == 0) assertEquals(600 to 400, width to height) else assertEquals(400 to 600, width to height)
            val geometry = PdfDocumentInspector.inspect(context, file).geometry
            assertEquals(rotation, geometry.rotation)
            assertEquals(PdfBox(0.0, 0.0, 600.0, 400.0), geometry.mediaBox)
        }
    }

    @Test
    fun invalidAndPasswordProtectedPdfAreRejected() {
        val invalid = File(context.cacheDir, "invalid-${System.nanoTime()}.pdf").apply {
            writeText("not a pdf")
        }
        val protected = createPdf("protected.pdf", password = "secret")

        listOf(invalid, protected).forEach { file ->
            val result = runCatching { preflightPdfImport(context, file) }
            assertTrue(result.exceptionOrNull() is PdfImportRejectedException)
            assertTrue(result.exceptionOrNull()!!.message!!.contains("password-protected"))
        }
    }

    private fun georef(file: File): GeoPdfGeorefResult = PdfDocumentInspector.inspect(context, file).georeference()

    @Test
    fun adobeViewportAndLegacyLgiDictBothGeoreference() {
        val adobe = georef(createPdf("adobe-vp.pdf", metadata = MetadataKind.ADOBE_VIEWPORT))
        val legacy = georef(createPdf("legacy-lgi.pdf", metadata = MetadataKind.LGI_DICT))

        // no /GCS: local TM fallback on the GPTS, datum assumed; and the biggest viewport wins over the inset
        val a = adobe as GeoPdfGeorefResult.Georeferenced
        assertEquals(GeorefOrigin.ADOBE_VP, a.georef.origin)
        assertEquals(1, a.selection.index)
        assertTrue(a.georef.datumAssumed)
        // a 1 deg lon/lat box isn't affine in any plane, the local TM fit leaves a few
        // hundred metres at the corners, still under the RMS gate for a 145 km sheet
        val corner = requireNotNull(a.georef.toWGS84(600.0, 400.0))
        assertEquals(-33.0, corner.latitude, 5e-3)
        assertEquals(151.0, corner.longitude, 5e-3)
        val l = legacy as GeoPdfGeorefResult.Georeferenced
        assertEquals(GeorefOrigin.LGI_DICT, l.georef.origin)
        assertEquals(GeoCrs.Geographic, l.georef.crs)
    }

    @Test
    fun offEarthAdobeViewportIsALoudRejection() {
        val malformed = createPdf("invalid-adobe-vp.pdf", metadata = MetadataKind.INVALID_ADOBE_VIEWPORT)

        assertEquals(GeoPdfGeorefResult.Rejected(GeorefRejectReason.GPTS_OFF_EARTH), georef(malformed))
    }

    @Test
    fun brokenMainViewportFallsBackToTheLargestValidOneAsThePlanSays() {
        // LPTS 1.1 is fine now (D1-01) but this body's GPTS don't fit an affine, so it
        // fails the RMS gate and plan s1's "largest valid viewport" picks the inset.
        // used to be a hard reject, the shared fixture flags this rule for review
        val result = georef(createPdf("dominant-invalid-adobe-vp.pdf", metadata = MetadataKind.ADOBE_MALFORMED_LARGER_VIEWPORT))
        val ok = result as GeoPdfGeorefResult.Georeferenced
        assertEquals(0, ok.selection.index)
    }

    @Test
    fun oversizedEmbeddedGeospatialMetadataIsRejected() {
        listOf(
            MetadataKind.OVERSIZED_ADOBE_VIEWPORTS,
            MetadataKind.OVERSIZED_LGI_ENTRIES,
        ).forEach { metadata ->
            val file = createPdf("oversized-${metadata.name}.pdf", metadata = metadata)
            assertEquals(metadata.name, GeoPdfGeorefResult.Rejected(GeorefRejectReason.MALFORMED), georef(file))
        }
    }

    @Test
    fun projectedLgiRegistrationUtmProducesRealWgs84ControlPoints() {
        val result = georef(createPdf("lgi-utm.pdf", metadata = MetadataKind.LGI_UTM_REGISTRATION))
        val g = (result as GeoPdfGeorefResult.Georeferenced).georef
        assertEquals(56, (g.crs as GeoCrs.TransverseMercator).utmZone)
        assertProjected56South(g, projectedGridPoints())
    }

    @Test
    fun projectedLgiCtmTransverseMercatorProducesRealWgs84ControlPoints() {
        val result = georef(createPdf("lgi-tc.pdf", metadata = MetadataKind.LGI_TC_CTM))
        val g = (result as GeoPdfGeorefResult.Georeferenced).georef
        assertEquals("ctm", result.selection.source)
        assertProjected56South(g, projectedGridPoints())
    }

    @Test
    fun pureUtmAndTransverseMercatorInverseAgreeOnWgs84() {
        val datum = requireNotNull(GeoDatums.forLgiCode("WE"))
        val utm = GeoCrs.utm(56, true)
        val tc = GeoCrs.TransverseMercator(lat0 = 0.0, lon0 = 153.0, k0 = 0.9996, fe = 500_000.0, fn = 10_000_000.0)
        val easting = 334_368.6336
        val northing = 6_250_945.575
        val a = requireNotNull(utm.inverse(easting, northing, datum.ellipsoid))
        val b = requireNotNull(tc.inverse(easting, northing, datum.ellipsoid))
        // Cross-checked with NGA UTM 2.1.3's independent inverse.
        assertEquals(-33.8688251, a.latitude, 1e-6)
        assertEquals(151.2092995, a.longitude, 1e-6)
        assertEquals(a.latitude, b.latitude, 1e-10)
        assertEquals(a.longitude, b.longitude, 1e-10)
    }

    @Test
    fun unsupportedOrInsufficientProjectedLgiMetadataFailsClosedWithAReason() {
        mapOf(
            MetadataKind.LGI_PROJECTED_WITHOUT_PROJECTION to GeorefRejectReason.MALFORMED,
            MetadataKind.LGI_UNSUPPORTED_PROJECTION to GeorefRejectReason.UNSUPPORTED_PROJECTION,
            MetadataKind.LGI_UTM_WITHOUT_DATUM to GeorefRejectReason.UNKNOWN_DATUM,
            MetadataKind.LGI_TC_INCOMPLETE to GeorefRejectReason.MALFORMED,
        ).forEach { (metadata, reason) ->
            val file = createPdf("rejected-$metadata.pdf", metadata = metadata)
            assertEquals(metadata.name, GeoPdfGeorefResult.Rejected(reason), georef(file))
        }
    }

    @Test
    fun malformedNamedLayersEntryNeverFallsBackToGeoreferencedInset() {
        val file = createPdf("lgi-malformed-layers.pdf", metadata = MetadataKind.LGI_MALFORMED_LAYERS)

        assertTrue(georef(file) is GeoPdfGeorefResult.Rejected)
    }

    @Test
    fun generatedMbtilesContainsEveryExpectedOnPageTile() = runBlocking {
        val source = calibratedSource(createPdf("tiler-success.pdf"))
        var finalProgress: PdfTiler.Progress? = null

        val output = PdfTiler.generate(context, source) { finalProgress = it }

        val path = requireNotNull(output)
        try {
            val db = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)
            val tileCount = try {
                db.rawQuery("SELECT COUNT(*) FROM tiles", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    cursor.getInt(0)
                }
            } finally {
                db.close()
            }
            val progress = requireNotNull(finalProgress)
            assertEquals(progress.total, progress.done)
            assertEquals(progress.total, tileCount)
            assertTrue(tileCount > 0)
        } finally {
            File(path).delete()
        }
    }

    @Test
    fun tileRenderFailurePublishesNoMbtilesArtifact() = runBlocking {
        val offlineDirectory = File(context.filesDir, "offline_tiles").apply { mkdirs() }
        val before = offlineDirectory.list()?.toSet().orEmpty()
        val missing = File(context.cacheDir, "missing-${System.nanoTime()}.pdf")

        val output = PdfTiler.generate(context, calibratedSource(missing)) { }

        assertNull(output)
        assertEquals(before, offlineDirectory.list()?.toSet().orEmpty())
    }

    private enum class MetadataKind {
        NONE,
        ADOBE_VIEWPORT,
        INVALID_ADOBE_VIEWPORT,
        ADOBE_MALFORMED_LARGER_VIEWPORT,
        OVERSIZED_ADOBE_VIEWPORTS,
        OVERSIZED_LGI_ENTRIES,
        LGI_DICT,
        LGI_UTM_REGISTRATION,
        LGI_TC_CTM,
        LGI_PROJECTED_WITHOUT_PROJECTION,
        LGI_UNSUPPORTED_PROJECTION,
        LGI_UTM_WITHOUT_DATUM,
        LGI_TC_INCOMPLETE,
        LGI_MALFORMED_LAYERS,
    }

    private fun createPdf(
        name: String,
        rotation: Int = 0,
        password: String? = null,
        metadata: MetadataKind = MetadataKind.NONE,
    ): File {
        val file = File(context.cacheDir, "${System.nanoTime()}-$name")
        PDDocument().use { document ->
            val page = PDPage(PDRectangle(600f, 400f)).apply { this.rotation = rotation }
            document.addPage(page)
            when (metadata) {
                MetadataKind.NONE -> Unit
                MetadataKind.ADOBE_VIEWPORT -> addAdobeViewport(page)
                MetadataKind.INVALID_ADOBE_VIEWPORT -> addInvalidAdobeViewport(page)
                MetadataKind.ADOBE_MALFORMED_LARGER_VIEWPORT ->
                    addMalformedLargerAdobeViewport(page)
                MetadataKind.OVERSIZED_ADOBE_VIEWPORTS -> addOversizedAdobeViewports(page)
                MetadataKind.OVERSIZED_LGI_ENTRIES -> addOversizedLgiEntries(page)
                MetadataKind.LGI_DICT -> addLegacyLgiDict(page)
                MetadataKind.LGI_UTM_REGISTRATION -> addProjectedRegistration(page, "UT", includeDatum = true)
                MetadataKind.LGI_TC_CTM -> addTransverseMercatorCtm(page, complete = true)
                MetadataKind.LGI_PROJECTED_WITHOUT_PROJECTION -> addProjectedRegistration(
                    page,
                    projectionType = null,
                    includeDatum = false,
                )
                MetadataKind.LGI_UNSUPPORTED_PROJECTION -> addProjectedRegistration(
                    page,
                    projectionType = "XX",
                    includeDatum = true,
                )
                MetadataKind.LGI_UTM_WITHOUT_DATUM -> addProjectedRegistration(
                    page,
                    projectionType = "UT",
                    includeDatum = false,
                )
                MetadataKind.LGI_TC_INCOMPLETE -> addTransverseMercatorCtm(page, complete = false)
                MetadataKind.LGI_MALFORMED_LAYERS -> addMalformedLayersWithValidInset(page)
            }
            if (password != null) {
                val policy = StandardProtectionPolicy("owner-$password", password, AccessPermission())
                policy.encryptionKeyLength = 128
                document.protect(policy)
            }
            document.save(file)
        }
        return file
    }

    private fun addAdobeViewport(page: PDPage) {
        val viewports = COSArray()
        // A small, wrong inset first proves the parser selects the largest map body.
        viewports.add(adobeViewport(0.0, 0.0, 50.0, 50.0, 10.0, 10.0, 11.0, 11.0))
        viewports.add(adobeViewport(0.0, 0.0, 600.0, 400.0, -34.0, 150.0, -33.0, 151.0))
        page.cosObject.setItem(COSName.getPDFName("VP"), viewports)
    }

    private fun addInvalidAdobeViewport(page: PDPage) {
        val viewports = COSArray().apply {
            add(adobeViewport(0.0, 0.0, 600.0, 400.0, 1000.0, 1000.0, 1001.0, 1001.0))
        }
        page.cosObject.setItem(COSName.getPDFName("VP"), viewports)
    }

    private fun addMalformedLargerAdobeViewport(page: PDPage) {
        val validInset = adobeViewport(0.0, 0.0, 50.0, 50.0, 10.0, 10.0, 11.0, 11.0)
        val malformedMapBody = adobeViewport(
            0.0,
            0.0,
            600.0,
            400.0,
            -34.0,
            150.0,
            -33.0,
            151.0,
        )
        val measure = malformedMapBody
            .getDictionaryObject(COSName.getPDFName("Measure")) as COSDictionary
        measure.setItem(
            COSName.getPDFName("LPTS"),
            numbers(0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.1),
        )
        page.cosObject.setItem(
            COSName.getPDFName("VP"),
            COSArray().apply {
                add(validInset)
                add(malformedMapBody)
            },
        )
    }

    private fun addOversizedAdobeViewports(page: PDPage) {
        val viewport = adobeViewport(0.0, 0.0, 600.0, 400.0, -34.0, 150.0, -33.0, 151.0)
        page.cosObject.setItem(
            COSName.getPDFName("VP"),
            COSArray().apply { repeat(65) { add(viewport) } },
        )
    }

    private fun addOversizedLgiEntries(page: PDPage) {
        addLegacyLgiDict(page)
        val entry = page.cosObject
            .getDictionaryObject(COSName.getPDFName("LGIDict")) as COSDictionary
        page.cosObject.setItem(
            COSName.getPDFName("LGIDict"),
            COSArray().apply { repeat(65) { add(entry) } },
        )
    }

    private fun adobeViewport(
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
        south: Double,
        west: Double,
        north: Double,
        east: Double,
    ): COSDictionary {
        val measure = COSDictionary().apply {
            setName(COSName.getPDFName("Subtype"), "GEO")
            setItem(COSName.getPDFName("GPTS"), numbers(
                south, west, south, east, north, east, north, west
            ))
            setItem(COSName.getPDFName("LPTS"), numbers(
                0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0
            ))
        }
        return COSDictionary().apply {
            setItem(COSName.getPDFName("BBox"), numbers(x0, y0, x1, y1))
            setItem(COSName.getPDFName("Measure"), measure)
        }
    }

    private fun addLegacyLgiDict(page: PDPage) {
        val registrations = COSArray()
        listOf(
            doubleArrayOf(0.0, 0.0, 150.0, -34.0),
            doubleArrayOf(600.0, 0.0, 151.0, -34.0),
            doubleArrayOf(600.0, 400.0, 151.0, -33.0),
            doubleArrayOf(0.0, 400.0, 150.0, -33.0),
        ).forEach { registrations.add(numbers(*it)) }
        page.cosObject.setItem(
            COSName.getPDFName("LGIDict"),
            COSDictionary().apply {
                setItem(COSName.getPDFName("Registration"), registrations)
                setItem(
                    COSName.getPDFName("Projection"),
                    COSDictionary().apply {
                        setName(COSName.getPDFName("ProjectionType"), "LL")
                    },
                )
            }
        )
    }

    private fun projectedGridPoints(): List<DoubleArray> = listOf(
        doubleArrayOf(334_000.0, 6_250_000.0),
        doubleArrayOf(334_600.0, 6_250_000.0),
        doubleArrayOf(334_600.0, 6_250_400.0),
        doubleArrayOf(334_000.0, 6_250_400.0),
    )

    private fun addProjectedRegistration(
        page: PDPage,
        projectionType: String?,
        includeDatum: Boolean,
    ) {
        val pdfPoints = listOf(
            0.0 to 0.0,
            600.0 to 0.0,
            600.0 to 400.0,
            0.0 to 400.0,
        )
        val registrations = COSArray().apply {
            pdfPoints.zip(projectedGridPoints()).forEach { (pdf, projected) ->
                add(numbers(pdf.first, pdf.second, projected[0], projected[1]))
            }
        }
        page.cosObject.setItem(
            COSName.getPDFName("LGIDict"),
            COSDictionary().apply {
                setItem(COSName.getPDFName("Registration"), registrations)
                if (projectionType != null) {
                    setItem(
                        COSName.getPDFName("Projection"),
                        COSDictionary().apply {
                            setName(COSName.getPDFName("ProjectionType"), projectionType)
                            setInt(COSName.getPDFName("Zone"), 56)
                            setName(COSName.getPDFName("Hemisphere"), "S")
                            if (includeDatum) setName(COSName.getPDFName("Datum"), "WE")
                        },
                    )
                }
            },
        )
    }

    private fun addTransverseMercatorCtm(page: PDPage, complete: Boolean) {
        page.cosObject.setItem(
            COSName.getPDFName("LGIDict"),
            COSDictionary().apply {
                // ADF/AUSLIG-style LGIDicts commonly encode every enum and
                // numeric field as a PDF string rather than a name/number.
                setItem(COSName.getPDFName("CTM"), stringNumbers(1.0, 0.0, 0.0, 1.0, 334_000.0, 6_250_000.0))
                setItem(COSName.getPDFName("Neatline"), stringNumbers(
                    0.0, 0.0,
                    600.0, 0.0,
                    600.0, 400.0,
                    0.0, 400.0,
                ))
                setItem(
                    COSName.getPDFName("Projection"),
                    COSDictionary().apply {
                        setString(COSName.getPDFName("ProjectionType"), "TC")
                        setString(COSName.getPDFName("Datum"), "WE")
                        if (complete) setString(COSName.getPDFName("CentralMeridian"), "153")
                        setString(COSName.getPDFName("OriginLatitude"), "0")
                        setString(COSName.getPDFName("FalseEasting"), "500000")
                        setString(COSName.getPDFName("FalseNorthing"), "10000000")
                        setString(COSName.getPDFName("ScaleFactor"), "0.9996")
                    },
                )
            },
        )
    }

    private fun addMalformedLayersWithValidInset(page: PDPage) {
        val entries = COSArray().apply {
            add(COSDictionary().apply {
                setString(COSName.getPDFName("Description"), "Layers")
                setItem(COSName.getPDFName("Registration"), COSArray())
            })
            add(COSDictionary().apply {
                setString(COSName.getPDFName("Description"), "Adjoining Sheet Guide")
                val registrations = COSArray().apply {
                    listOf(
                        doubleArrayOf(0.0, 0.0, 150.0, -34.0),
                        doubleArrayOf(100.0, 0.0, 151.0, -34.0),
                        doubleArrayOf(100.0, 100.0, 151.0, -33.0),
                    ).forEach { add(numbers(*it)) }
                }
                setItem(COSName.getPDFName("Registration"), registrations)
                setItem(
                    COSName.getPDFName("Projection"),
                    COSDictionary().apply {
                        setName(COSName.getPDFName("ProjectionType"), "LL")
                    },
                )
            })
        }
        page.cosObject.setItem(COSName.getPDFName("LGIDict"), entries)
    }

    private fun assertProjected56South(
        georef: PdfGeoreference,
        projected: List<DoubleArray>,
    ) {
        val page = listOf(0.0 to 0.0, 600.0 to 0.0, 600.0 to 400.0, 0.0 to 400.0)
        page.zip(projected).forEach { (p, mapPoint) ->
            val expected = UTM.create(56, Hemisphere.SOUTH, mapPoint[0], mapPoint[1]).toPoint()
            val actual = requireNotNull(georef.toWGS84(p.first, p.second))
            assertEquals(expected.latitude, actual.latitude, 2e-5)
            assertEquals(expected.longitude, actual.longitude, 2e-5)
        }
    }

    private fun numbers(vararg values: Double): COSArray = COSArray().apply {
        values.forEach { add(COSFloat(it.toFloat())) }
    }

    private fun stringNumbers(vararg values: Double): COSArray = COSArray().apply {
        values.forEach { add(COSString(it.toString())) }
    }

    private fun calibratedSource(file: File): PdfMapSource {
        // 600 x 400 pt page laid on a 1 x 1 deg lon/lat box
        val georef = PdfGeoreference(
            page = 0,
            crs = GeoCrs.Geographic,
            datum = GeoDatums.WGS84,
            affine = PlaneAffine(1.0 / 600.0, 0.0, 150.0, 0.0, 1.0 / 400.0, -34.0),
            crop = listOf(PagePoint(0.0, 0.0), PagePoint(600.0, 0.0), PagePoint(600.0, 400.0), PagePoint(0.0, 400.0)),
            origin = GeorefOrigin.FIDUCIARIES,
        )
        return PdfMapSource(
            uri = Uri.fromFile(file),
            displayName = file.nameWithoutExtension,
            kind = MapSourceKind.CALIBRATED_PDF,
            calibration = Calibration.Parsed(georef),
            geometry = PdfPageGeometry(PdfBox(0.0, 0.0, 600.0, 400.0), null, 0, 600, 400),
        )
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun initialisePdfBox() {
            PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext())
        }
    }
}
