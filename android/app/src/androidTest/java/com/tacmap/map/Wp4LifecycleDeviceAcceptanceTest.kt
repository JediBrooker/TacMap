package com.tacmap.map

import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.tacmap.app.MainActivity
import com.tacmap.calibration.*
import com.tacmap.calibration.fiducial.CalibrationCapture
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

// Run each stage in a separate instrumentation process, force-stop between stages.
// Only the crosshair capture is supplied directly, using the shared PDF targets.
@RunWith(AndroidJUnit4::class)
class Wp4LifecycleDeviceAcceptanceTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val app = instr.targetContext
    private val device = UiDevice.getInstance(instr)
    private lateinit var vm: MapViewModel
    private val stage = InstrumentationRegistry.getArguments().getString("wp4Stage") ?: "resume"
    private val fids get() = Json.parseToJsonElement(instr.context.assets.open("pdf_georef.json").bufferedReader().use { it.readText() })
        .jsonObject["fiduciaryFits"]!!.jsonObject["sets"]!!.jsonArray.first { it.jsonObject["name"]!!.jsonPrimitive.content == "rot5_plain_4_grid_fids" }.jsonObject["points"]!!.jsonArray

    private fun waitFor(text: String) = requireNotNull(device.wait(Until.findObject(By.text(text)), 30_000)) { "No text: $text" }
    private fun waitUntil(what: String, predicate: () -> Boolean) {
        val end = System.currentTimeMillis() + 30_000
        while (!predicate()) { check(System.currentTimeMillis() < end) { "Timed out: $what" }; Thread.sleep(50) }
    }
    private fun launch(pdf: String? = null): ActivityScenario<MainActivity> {
        FirstRunTips.markSeen(app)
        val intent = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("TACMAP_DEBUG_OPSEC_ALLOW_SCREENSHOTS", "1").putExtra("TACMAP_DEBUG_GRID", "1")
        if (pdf != null) {
            val file = File(app.filesDir, "dbg/$pdf").apply { parentFile!!.mkdirs() }
            instr.context.assets.open("geopdf/$pdf").use { input -> file.outputStream().use { input.copyTo(it) } }
            intent.putExtra("TACMAP_DEBUG_IMPORT_PDF", file.absolutePath)
        }
        val scenario = ActivityScenario.launch<MainActivity>(intent)
        scenario.onActivity { vm = ViewModelProvider(it)[MapViewModel::class.java] }
        return scenario
    }
    private fun add(index: Int) {
        val point = fids[index].jsonObject
        val xy = point["page"]!!.jsonArray.map { it.jsonPrimitive.double }
        instr.runOnMainSync { assertTrue(vm.calibration.beginAdd(CalibrationCapture(PagePoint(xy[0], xy[1]), true, 2.0, false))) }
        val field = requireNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), 10_000))
        field.text = point["input"]!!.jsonPrimitive.content
        device.pressBack()
        requireNotNull(device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("Save point|Save anyway"))), 10_000)).click()
        waitUntil("saved point ${index + 1}") { vm.calibration.state.value?.state?.points?.size == index + 1 }
    }
    private fun screenshot(name: String) { device.waitForIdle(); Thread.sleep(500); device.takeScreenshot(File(app.filesDir, "wp4-$name.png")) }

    @Test fun stagedRealActivityLifecycle() {
        org.junit.Assume.assumeTrue("Run with -e wp4Stage after clean app data", InstrumentationRegistry.getArguments().containsKey("wp4Stage"))
        when (stage) {
            "seed" -> {
                launch("tacmap_grid_rot5_plain.pdf")
                waitFor("WGS84").click()
                waitUntil("calibration") { vm.calibration.state.value?.phase == CalibrationPhase.Placing }
                add(0); add(1)
                assertEquals(2, CalibrationDraftStore(app.filesDir).all().single().points.size)
                screenshot("mid-calibration")
            }
            "resume" -> {
                launch()
                waitUntil("two cold resumed points") { vm.calibration.state.value?.state?.points?.size == 2 }
                add(2); add(3)
                waitFor("Finish").click()
                waitUntil("manual commit") { vm.libraryState.value?.entries?.single()?.pdf?.manual?.points?.size == 4 }
                // the library's published before Finish ends the session, let the click handler run out
                instr.waitForIdleSync()
                assertFalse(vm.calibration.isActive)
                assertTrue(CalibrationDraftStore(app.filesDir).all().isEmpty())
                val manual = vm.libraryState.value!!.entries.single().pdf!!.manual!!
                assertTrue("fixture fit", manual.rmsM!! < 0.001)
                screenshot("four-points-finished")
            }
            "second" -> {
                launch("tacmap_grid_sf_iso.pdf")
                waitUntil("two imports") { vm.libraryState.value?.entries?.size == 2 }
                val first = vm.libraryState.value!!.entries.single { it.displayName == "tacmap_grid_rot5_plain" }
                assertEquals(4, first.pdf!!.manual!!.points.size)
                assertTrue(File(app.filesDir, first.fileName).isFile)
                screenshot("second-import-preserves-first")
            }
            "delete" -> {
                launch()
                waitUntil("loaded") { vm.libraryState.value?.entries?.size == 2 }
                val first = vm.libraryState.value!!.entries.single { it.displayName == "tacmap_grid_rot5_plain" }
                requireNotNull(device.wait(Until.findObject(By.desc("Menu")), 10_000)).click()
                waitFor("Layers and Labels").click()
                repeat(8) {
                    if (!device.hasObject(By.desc(first.displayName))) {
                        device.swipe(device.displayWidth / 2, device.displayHeight * 4 / 5, device.displayWidth / 2, device.displayHeight / 3, 30)
                        device.waitForIdle()
                    }
                }
                requireNotNull(device.wait(Until.findObject(By.desc(first.displayName)), 10_000)).click()
                waitFor(com.tacmap.localization.Messages.mapActionDelete()).click()
                waitFor("Cancel")
                assertTrue("confirmation precedes delete", File(app.filesDir, first.fileName).isFile)
                screenshot("delete-confirmation")
                waitFor("Delete").click()
                waitUntil("deleted") { vm.libraryState.value?.entry(first.id) == null }
                assertFalse(File(app.filesDir, first.fileName).exists())
                assertTrue(CalibrationDraftStore(app.filesDir).all().none { it.contentKey == first.contentKey })
            }
            "guardSeed" -> {
                launch("tacmap_grid_sf_iso.pdf")
                waitUntil("guard seed imported") { vm.libraryState.value?.entries?.any { it.displayName == "tacmap_grid_sf_iso" } == true }
                requireNotNull(device.wait(Until.findObject(By.desc("PDF map rendered: tacmap_grid_sf_iso")), 30_000))
                screenshot("guard-seed")
                val entry = vm.libraryState.value!!.entries.single { it.displayName == "tacmap_grid_sf_iso" }
                val guard = com.tacmap.map.render.pdf.PdfRenderGuardState()
                assertTrue(guard.arm(com.tacmap.map.render.pdf.GuardKind.BASE, entry.renderGuardToken))
                File(app.noBackupFilesDir, com.tacmap.map.render.pdf.PdfRenderGuard.FILE_NAME).writeText(guard.toJson().toString())
            }
            "guardCold" -> {
                launch()
                waitUntil("cold held back") { vm.pdfRecovery.value != null }
                val id = vm.pdfRecovery.value!!.entryId
                assertTrue(vm.mapSource.value is OnlineRasterMapSourceAndroid)
                assertEquals(id, vm.libraryState.value!!.active.entryId)
                assertTrue(File(app.filesDir, vm.libraryState.value!!.entry(id!!)!!.fileName).isFile)
                screenshot("guard-cold-suppressed")
                waitFor(com.tacmap.localization.Messages.pdfGuardOpenAnyway()).click()
                waitUntil("explicit reopened") { vm.pdfRecovery.value == null && vm.mapSource.value is PdfMapSource }
                screenshot("guard-explicit-reopen")
            }
            "recoverySeed" -> {
                launch()
                waitUntil("loaded for recovery seed") { vm.libraryState.value?.entries?.size == 1 }
                val entry = vm.libraryState.value!!.entries.single()
                instr.runOnMainSync { if (!vm.calibration.isActive) assertTrue(vm.startCalibration(entry.id)) else vm.calibration.cancelEntry() }
                add(0)
                instr.runOnMainSync { vm.calibration.leave(keep = true); vm.endCalibrationPreview() }
                assertEquals(1, CalibrationDraftStore(app.filesDir).all().single().points.size)
                File(app.filesDir, "offline_tiles/tacmap-bake-wp4-sentinel.mbtiles").apply { parentFile!!.mkdirs(); writeText("orphan bake preserved by corrupt recovery") }
                screenshot("recovery-seed")
            }
            "recovery" -> {
                launch()
                waitUntil("corrupt") { vm.libraryStatus.value == LibraryStatus.CORRUPT }
                assertTrue(File(app.filesDir, "offline_tiles/tacmap-bake-wp4-sentinel.mbtiles").isFile)
                assertTrue(File(app.filesDir, CalibrationDraftStore.FILE_NAME).isFile)
                screenshot("corrupt-before-retry")
                waitFor("Retry").click()
                waitUntil("recovered") { vm.libraryStatus.value == LibraryStatus.LOADED }
                assertFalse(vm.libraryState.value!!.permitsCleanup)
                assertTrue(File(app.filesDir, "offline_tiles/tacmap-bake-wp4-sentinel.mbtiles").isFile)
                assertTrue(File(app.filesDir, CalibrationDraftStore.FILE_NAME).isFile)
                screenshot("corrupt-retry-preserves-files")
            }
            "recoveryCold" -> {
                launch()
                waitUntil("cold recovered") { vm.libraryStatus.value == LibraryStatus.LOADED }
                assertFalse(vm.libraryState.value!!.permitsCleanup)
                assertTrue(File(app.filesDir, "offline_tiles/tacmap-bake-wp4-sentinel.mbtiles").isFile)
                assertTrue(File(app.filesDir, CalibrationDraftStore.FILE_NAME).isFile)
                screenshot("corrupt-cold-preserves-files")
            }
            else -> error("Unknown stage: $stage")
        }
    }
}
