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
import com.tacmap.calibration.GeorefOrigin
import com.tacmap.calibration.GeorefRejectReason
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.tacmap.calibration.PdfGeorefIssue
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

        val latitude = -35.2809
        val longitude = 149.1300
        val copyJournalDir = File(context.cacheDir, "pdf-import-smoke-journal").apply { mkdirs() }
        val result = importPdfMapSource(
            context = context,
            sourceUri = Uri.fromFile(selectedPdf),
            cameraLat = latitude,
            cameraLng = longitude,
            operationKey = "pdf-import-smoke",
            copyJournal = DocumentImportCopyJournal.forTests(copyJournalDir),
        )
        val source = result.source

        assertTrue(File(requireNotNull(source.uri.path)).isFile)
        assertEquals(200, source.geometry.rendererWidth)
        assertEquals(300, source.geometry.rendererHeight)
        // a plain PDF is never passed off as georeferenced: its own outcome, a
        // provisional placement around the camera, and a reason the UI can show
        assertEquals(PdfImportOutcome.NoGeoreference, result.outcome)
        assertFalse(source.isGeoreferenced)
        assertEquals(PdfGeorefIssue.NoMetadata, source.georefIssue)
        assertEquals(GeorefOrigin.PROVISIONAL, source.placement?.origin)
        assertTrue(requireNotNull(source.coverage).contains(latitude, longitude))

        val rendered = PdfPageRenderer.renderRawRegion(
            context = context,
            uri = source.uri,
            geometry = source.geometry,
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
    fun refusedGeoPdfIsParkedOffTheMapAndCancelDropsTheCopy() {
        // same flow as iOS: a declared-but-unusable georef comes back as its own outcome
        // with the reason, the UI parks it behind the alert, Cancel deletes the private copy
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
        val copyJournalDir = File(context.cacheDir, "pdf-import-refused-journal").apply { mkdirs() }
        val result = importPdfMapSource(
            context = context,
            sourceUri = Uri.fromFile(selectedPdf),
            cameraLat = -35.28,
            cameraLng = 149.13,
            operationKey = "pdf-import-refused-${System.nanoTime()}",
            copyJournal = DocumentImportCopyJournal.forTests(copyJournalDir),
        )
        assertEquals(PdfImportOutcome.Rejected(GeorefRejectReason.LPTS_OUT_OF_RANGE), result.outcome)
        assertFalse(result.source.isGeoreferenced)
        assertEquals(GeorefOrigin.PROVISIONAL, result.source.placement?.origin)
        val copy = File(requireNotNull(result.source.uri.path))
        assertTrue(copy.isFile)
        assertTrue(
            com.tacmap.localization.Messages.pdfGeorefRejectedMessage(pdfGeorefRejectionReason(GeorefRejectReason.LPTS_OUT_OF_RANGE))
                .isNotBlank()
        )
        discardRejectedPdfImport(result.source)
        assertFalse("Cancel should drop the refused copy", copy.exists())
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

            // no georef in this PDF (plan 02 s1): same flow as iOS, it goes straight into
            // calibration on a provisional placement that's labelled uncalibrated, never a
            // quiet camera box passed off as a basemap
            assertTrue(
                "A plain PDF import didn't drop into calibration",
                device.wait(Until.hasObject(By.text("Calibrating PDF")), IMPORT_TIMEOUT_MS),
            )
            assertTrue(
                "The selected PDF never became the rendered map",
                device.wait(
                    Until.hasObject(By.desc("PDF map rendered: $displayName")),
                    IMPORT_TIMEOUT_MS,
                ),
            )
            assertTrue(
                "The imported PDF wasn't labelled uncalibrated",
                device.hasObject(By.text("Uncalibrated map")),
            )
            assertFalse(
                "A plain PDF isn't a refused GeoPDF",
                device.hasObject(By.text("Georeference not usable")),
            )
            waitFor(device, By.text("Cancel")).click()
            assertTrue(
                "Backing out of calibration should keep it labelled uncalibrated",
                device.wait(Until.hasObject(By.text("Uncalibrated map")), UI_TIMEOUT_MS),
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
