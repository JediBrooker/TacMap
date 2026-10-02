package com.tacmap.map

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.tacmap.app.MainActivity
import com.tacmap.calibration.GeorefRejectReason
import com.tacmap.calibration.ImportOutcome
import com.tacmap.calibration.InFlightImportFiles
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.tacmap.calibration.PdfPageRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfImportSmokeTest {

    @Test
    fun importsAndRendersPdfMapPipeline() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val selectedPdf = File(context.cacheDir, "pdf-import-smoke.pdf")
        createPdf(selectedPdf)

        val prepared = runImport(context, selectedPdf, "pdf-import-smoke-${System.nanoTime()}")
        try {
            assertTrue(prepared.file.isFile)
            val page = requireNotNull(prepared.inspection.page(0))
            val geometry = requireNotNull(page.geometry)
            assertEquals(200, geometry.rendererWidth)
            assertEquals(300, geometry.rendererHeight)
            assertTrue(prepared.contentKey.startsWith("sha256:"))
            // a plain single page PDF is never passed off as georeferenced: it's added
            // as needsCalibration and goes straight into calibration (E1)
            assertEquals(ImportOutcome.AddAndCalibrate(0), prepared.outcome)
            assertEquals(null, page.georef?.takeIf { it !is com.tacmap.calibration.GeoPdfGeorefResult.NoGeoreference })
            renderBlack(context, prepared.file, geometry)
        } finally {
            InFlightImportFiles.release(prepared.file)
            prepared.file.delete()
        }
    }

    /** the whole s9 pipeline minus the library commit, off a file:// source */
    private fun runImport(context: Context, pdf: File, key: String): PreparedPdfImport {
        val journalDir = File(context.cacheDir, "pdf-import-journal-${System.nanoTime()}").apply { mkdirs() }
        val pipeline = MapImportPipeline(context, DocumentImportCopyJournal.forTests(journalDir))
        val snapshot = LibrarySnapshot(loaded = true, entryCount = 0, byContentKey = { null })
        val outcome = runBlocking { pipeline.runPdf(Uri.fromFile(pdf), key, snapshot) { } }
        assertTrue("import failed: $outcome", outcome is PreparedOutcome.Pdf)
        return (outcome as PreparedOutcome.Pdf).prepared
    }

    private fun renderBlack(context: Context, file: File, geometry: com.tacmap.calibration.PdfPageGeometry) {
        val rendered = PdfPageRenderer.renderRawRegion(
            context = context,
            uri = Uri.fromFile(file),
            geometry = geometry,
            x0 = 0.0,
            y0 = 0.0,
            x1 = 200.0,
            y1 = 300.0,
            outputWidth = 32,
            outputHeight = 32,
        )
        try {
            assertEquals(Color.BLACK, rendered.getPixel(16, 16))
        } finally {
            rendered.recycle()
        }
    }

    @Test
    fun refusedGeoPdfIsAddedAsRejectedWithItsReason() {
        // s9.6 (same table as iOS): a single page whose declared georef can't be used is
        // added as rejected, never active, and the alert offers Calibrate now / Later
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<Context>()
        com.tacmap.calibration.PdfGeorefFixture.readBytes = { name ->
            instrumentation.context.assets.open(name).use { it.readBytes() }
        }
        val case = com.tacmap.calibration.PdfGeorefFixture.root["rejections"]!!.jsonObject["cases"]!!.jsonArray
            .map { it.jsonObject }.first { it["id"]!!.jsonPrimitive.content == "lpts_outside_range" }
        val selectedPdf = File(context.cacheDir, "pdf-import-refused.pdf")
        com.tacmap.calibration.GeoPdfFixtureInstrumentedTest.writeOnePagePdf(
            selectedPdf,
            case["mediaBox"]!!.jsonArray.map { it.jsonPrimitive.double },
            case["pdfPageExtras"]!!.jsonPrimitive.content,
        )
        val prepared = runImport(context, selectedPdf, "pdf-import-refused-${System.nanoTime()}")
        try {
            assertEquals(ImportOutcome.AddRejected(0, GeorefRejectReason.LPTS_OUT_OF_RANGE), prepared.outcome)
            assertTrue(prepared.file.isFile)
            assertTrue(
                com.tacmap.localization.Messages.mapImportGeorefRejected(pdfGeorefRejectionReason(GeorefRejectReason.LPTS_OUT_OF_RANGE))
                    .isNotBlank()
            )
        } finally {
            InFlightImportFiles.release(prepared.file)
            prepared.file.delete()
        }
    }

    // This picker fixture uses the scoped MediaStore Downloads API (Android 10+).
    // The import/render pipeline and localisation tests also run on API 26.
    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun importsPdfThroughUserInterfaceAndSurvivesSecurityLock() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val fileName = "tacmap-picker-smoke-${System.currentTimeMillis()}.pdf"
        val displayName = fileName.removeSuffix(".pdf")
        val selectedPdf = createPdfInDownloads(context, fileName)

        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )

        // The first-run tips dialog would cover the map this test drives.
        com.tacmap.map.FirstRunTips.markSeen(InstrumentationRegistry.getInstrumentation().targetContext)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            waitFor(device, By.desc("Menu")).click()
            waitFor(device, By.text("Import / Export")).click()
            waitFor(device, By.text("PDF Map")).click()

            assertTrue(
                "The Android document picker did not open",
                device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")), UI_TIMEOUT_MS),
            )
            waitFor(device, By.text(fileName), PICKER_TIMEOUT_MS).click()

            // no georef in this PDF: same flow as iOS (WP4 E1), straight into calibration on
            // a provisional placement. A plain PDF has no datum yet, so the datum sheet's first
            assertTrue(
                "A plain PDF import didn't drop into calibration",
                device.wait(Until.hasObject(By.text("Datum printed on the map")), IMPORT_TIMEOUT_MS),
            )
            waitFor(device, By.text("WGS84")).click()
            assertTrue(
                "The selected PDF never became the rendered map",
                device.wait(
                    Until.hasObject(By.desc("PDF map rendered: $displayName")),
                    IMPORT_TIMEOUT_MS,
                ),
            )
            // the header says it isn't georeferenced, never a quiet camera box passed off as a map
            assertTrue(device.hasObject(By.text("NOT GEOREFERENCED")))
            assertFalse(
                "A plain PDF isn't a refused GeoPDF",
                device.hasObject(By.text("Georeference not usable")),
            )
            // nothing placed, so the X just leaves (no keep/discard dialog)
            waitFor(device, By.desc("Leave calibration")).click()
            assertTrue(
                "Leaving calibration should hand the map back",
                device.wait(Until.gone(By.text("NOT GEOREFERENCED")), UI_TIMEOUT_MS),
            )
        } finally {
            scenario.close()
            context.contentResolver.delete(selectedPdf, null, null)
        }
    }

    private fun waitFor(
        device: UiDevice,
        selector: androidx.test.uiautomator.BySelector,
        timeoutMs: Long = UI_TIMEOUT_MS,
    ) = requireNotNull(device.wait(Until.findObject(selector), timeoutMs)) {
        "Timed out waiting for $selector"
    }

    private fun createPdfInDownloads(context: Context, fileName: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = requireNotNull(
            context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ) { "Unable to create the PDF picker fixture" }
        try {
            context.contentResolver.openOutputStream(uri).use { output ->
                requireNotNull(output) { "Unable to write the PDF picker fixture" }
                writePdf(output)
            }
            context.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null,
            )
            return uri
        } catch (error: Throwable) {
            context.contentResolver.delete(uri, null, null)
            throw error
        }
    }

    private fun createPdf(file: File) {
        file.outputStream().use(::writePdf)
    }

    private fun writePdf(output: java.io.OutputStream) {
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(200, 300, 1).create())
            page.canvas.drawColor(Color.BLACK)
            page.canvas.drawText("TacMap PDF smoke test", 10f, 30f, Paint().apply {
                color = Color.WHITE
                textSize = 12f
            })
            document.finishPage(page)
            document.writeTo(output)
        } finally {
            document.close()
        }
    }

    private companion object {
        const val UI_TIMEOUT_MS = 10_000L
        const val PICKER_TIMEOUT_MS = 20_000L
        const val IMPORT_TIMEOUT_MS = 30_000L
    }
}
