package com.tacmap.map

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.tacmap.app.MainActivity
import com.tacmap.calibration.PdfPageRenderer
import org.junit.Assert.assertEquals
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
        val source = importPdfMapSource(
            context = context,
            sourceUri = Uri.fromFile(selectedPdf),
            cameraLat = latitude,
            cameraLng = longitude,
        )

        assertTrue(File(requireNotNull(source.uri.path)).isFile)
        assertEquals(200, source.pageInfo?.pageWidth)
        assertEquals(300, source.pageInfo?.pageHeight)
        assertTrue(requireNotNull(source.coverage).contains(latitude, longitude))

        val rendered = PdfPageRenderer.renderFirstPageRegion(
            context = context,
            uri = source.uri,
            pageRect = RectF(0f, 0f, 200f, 300f),
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

            assertTrue(
                "The selected PDF never became the rendered map",
                device.wait(
                    Until.hasObject(By.desc("PDF map rendered: $displayName")),
                    IMPORT_TIMEOUT_MS,
                ),
            )
            assertTrue(
                "The imported PDF was not activated as the offline basemap",
                device.hasObject(By.text("Offline basemap")),
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
