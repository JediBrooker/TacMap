package com.tacmap.map

import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.tacmap.app.MainActivity
import com.tacmap.calibration.PdfMapSource
import com.tacmap.calibration.canonicalJson
import com.tacmap.calibration.PdfBakeManager
import com.tacmap.localization.Messages
import com.tacmap.map.render.pdf.PdfRenderStatus
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

// Opt-in actual activity verification. Camera moves never supply calibration points.
@RunWith(AndroidJUnit4::class)
class MergedPdfDeviceVerificationTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val app = instr.targetContext
    private val device = UiDevice.getInstance(instr)
    private lateinit var vm: MapViewModel
    private lateinit var evidence: File
    private lateinit var sourceName: String

    private fun until(label: String, timeout: Long = 60_000, predicate: () -> Boolean) {
        val end = System.currentTimeMillis() + timeout
        while (!predicate()) {
            check(System.currentTimeMillis() < end) { "Timed out: $label" }
            Thread.sleep(50)
        }
    }

    private fun text(value: String) = requireNotNull(device.wait(Until.findObject(By.text(value)), 15_000)) {
        "Missing UI control: $value"
    }

    private fun capture(label: String) {
        device.waitForIdle()
        Thread.sleep(2_000)
        val path = File(evidence, "$label.png")
        assertTrue(device.takeScreenshot(path))
        device.dumpWindowHierarchy(File(evidence, "$label.xml"))
        val ready = device.findObject(By.desc("PDF map rendered: $sourceName"))
        val viewport = vm.cameraViewportState.value
        val source = vm.mapSource.value as? PdfMapSource
        File(evidence, "$label.json").writeText(buildJsonObject {
            put("label", label)
            put("sourceFile", args.getString("task4File"))
            put("density", app.resources.displayMetrics.density)
            put("screenshotWidth", device.displayWidth)
            put("screenshotHeight", device.displayHeight)
            put("renderStatus", vm.pdfRuntime.status.value.toString())
            put("calibrating", vm.calibration.isActive)
            put("bakeState", vm.bakeManager.state.value.toString())
            put("expectedImportOutcome", args.getString("task4Outcome"))
            vm.libraryState.value?.let { library ->
                put("activeEntryId", library.active.entryId)
                library.entries.find { it.displayName == sourceName }?.let { entry ->
                    put("importedEntry", buildJsonObject {
                        put("id", entry.id); put("contentKey", entry.contentKey)
                        put("embeddedIssue", entry.pdf?.embeddedIssue)
                        put("manualPointCount", entry.pdf?.manual?.points?.size ?: 0)
                    })
                }
            }
            ready?.visibleBounds?.let { b ->
                put("mapBoundsPixels", buildJsonArray { add(b.left); add(b.top); add(b.right); add(b.bottom) })
            }
            viewport?.let {
                put("actualCamera", buildJsonObject {
                    put("latitude", it.latitude); put("longitude", it.longitude)
                    put("zoom", it.zoom); put("heading", it.bearingDegrees)
                })
            }
            source?.let {
                val activeRender = vm.pdfRuntime.activeSource
                put("sourceId", activeRender?.auditSourceId ?: it.id)
                put("modelSourceId", it.id)
                put("renderSourceId", activeRender?.auditInstanceId)
                activeRender?.let { render ->
                    put("renderGeoref", Json.parseToJsonElement(render.georef.canonicalJson()))
                    put("renderEwmaMs", render.ewmaMs?.let(::JsonPrimitive) ?: JsonNull)
                    put("renderHeavy", render.heavy); put("renderTilePx", render.tilePx)
                    put("renderBaseMaxZoom", render.baseMaxZoom); put("renderDetailZoom", render.policy.detailZoom)
                    put("runtimeBakeMaxZoom", render.bakeMaxZoom?.let(::JsonPrimitive) ?: JsonNull)
                }
                put("entryId", it.entryId); put("contentKey", it.contentKey)
                put("page", it.pageIndex); put("kind", it.kind.toString())
                put("baked", it.render.bake != null)
                it.placement?.let { g -> put("georef", Json.parseToJsonElement(g.canonicalJson())) }
            }
        }.toString())
    }

    private fun fly(lat: Double, lon: Double, zoom: Double) {
        instr.runOnMainSync { vm.applyDebugCamera(com.tacmap.app.DebugLaunchHooks.Camera(lat, lon, zoom, 0.0)) }
        until("camera applied") {
            val c = vm.cameraViewportState.value
            vm.pendingCameraTarget.value == null && c != null &&
                abs(c.latitude - lat) < 1e-7 && abs(c.longitude - lon) < 1e-7 && abs(c.zoom - zoom) < 0.001
        }
        device.waitForIdle()
    }

    private fun calibrate(id: String) {
        text("WGS84").click()
        until("calibration placing") { vm.calibration.state.value?.phase == CalibrationPhase.Placing }
        val fixture = Json.parseToJsonElement(instr.context.assets.open("pdf_georef.json").bufferedReader().use { it.readText() }).jsonObject
        val points = when (id) {
            "sf_plain" -> fixture["sheets"]!!.jsonArray.first { it.jsonObject["id"]!!.jsonPrimitive.content == id }
                .jsonObject["truth"]!!.jsonObject["fiducialTargets"]!!.jsonArray
            else -> fixture["fiduciaryFits"]!!.jsonObject["sets"]!!.jsonArray.first {
                it.jsonObject["name"]!!.jsonPrimitive.content == if (id == "rot5_plain") "rot5_plain_4_grid_fids" else "cbr50k_plain_latlon_first"
            }.jsonObject["points"]!!.jsonArray
        }
        points.take(4).forEachIndexed { index, value ->
            val p = value.jsonObject
            val xy = p["page"]!!.jsonArray.map { it.jsonPrimitive.double }
            val target = requireNotNull(vm.calibration.state.value!!.display.georef.toWGS84(xy[0], xy[1]))
            fly(target.latitude, target.longitude, 18.0)
            text("Add point").click()
            until("actual crosshair captured") { vm.calibration.state.value?.phase is CalibrationPhase.Entering }
            val captured = requireNotNull(vm.calibration.state.value!!.pendingPage)
            assertEquals(xy[0], captured.x, 0.001)
            assertEquals(xy[1], captured.y, 0.001)
            val field = requireNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), 10_000))
            field.text = (p["input"] ?: p["label"])!!.jsonPrimitive.content
            device.pressBack()
            requireNotNull(device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("Save point|Save anyway"))), 10_000)).click()
            until("point saved") { vm.calibration.state.value?.state?.points?.size == index + 1 }
            capture("calibration-point-${index + 1}")
        }
        text("Finish").click()
        until("four-point calibration committed") { !vm.calibration.isActive }
        assertEquals(4, vm.libraryState.value!!.entries.last { it.displayName == sourceName }.pdf!!.manual!!.points.size)
        capture("calibration-finished")
    }

    @Test fun verifyImportedSheetThroughActivity() {
        assumeTrue("Provide isolated task4File to run the device verifier", args.containsKey("task4File"))
        val name = requireNotNull(args.getString("task4File"))
        val id = requireNotNull(args.getString("task4Id"))
        sourceName = File(name).nameWithoutExtension
        evidence = File(app.filesDir, "task4/$id").apply { mkdirs() }
        val file = File(app.filesDir, "dbg/${File(name).name}").apply { parentFile!!.mkdirs() }
        if (!name.startsWith("/")) instr.context.assets.open("geopdf/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
        else File(name).copyTo(file, overwrite = true)
        FirstRunTips.markSeen(app)
        val intent = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("TACMAP_DEBUG_IMPORT_PDF", file.absolutePath)
            .putExtra("TACMAP_DEBUG_GRID", "1").putExtra("TACMAP_DEBUG_OPSEC_ALLOW_SCREENSHOTS", "1")
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { vm = ViewModelProvider(it)[MapViewModel::class.java] }
            try {
                val expected = args.getString("task4Outcome") ?: if (name.contains("budget_") || name.contains("render_blank")) "renderFailure:blank" else "addAndActivate"
                if (expected == "renderFailure:blank") {
                    text(Messages.pdfRenderReasonBlank())
                    text(Messages.acknowledge())
                    File(evidence, "blank-reason-proof.json").writeText(buildJsonObject {
                        put("actualVisibleReason", text(Messages.pdfRenderReasonBlank()).text)
                        put("expectedLocalizedReason", Messages.pdfRenderReasonBlank())
                    }.toString())
                    capture("import-outcome")
                    assertTrue("negative fixture must not become a library entry", vm.libraryState.value!!.entries.none { it.displayName == sourceName })
                    return
                }
                until("library import") { vm.libraryState.value?.entries?.any { it.displayName == sourceName } == true }
                if (expected == "addRejected") {
                    until("visible georef rejection") { vm.rejectedPrompt.value != null }
                    val entry = vm.libraryState.value!!.entries.single { it.displayName == sourceName }
                    assertEquals(args.getString("task4Reason"), entry.pdf!!.embeddedIssue)
                    assertNotEquals(entry.id, vm.libraryState.value!!.active.entryId)
                    text("Calibrate now")
                    capture("import-outcome")
                    return
                }
                if (id.endsWith("plain")) {
                    val manual = vm.libraryState.value!!.entries.single { it.displayName == sourceName }.pdf!!.manual
                    if (manual == null) calibrate(id) else assertEquals(4, manual.points.size)
                }
                until("actual PDF rendered", 90_000) { device.hasObject(By.desc("PDF map rendered: $sourceName")) }
                assertEquals(PdfRenderStatus.Ready, vm.pdfRuntime.status.value)
                instr.runOnMainSync { vm.centreOnMap() }
                until("sheet fit applied") { vm.pendingCameraTarget.value == null }
                capture("sheet-fit")
                val source = vm.mapSource.value as PdfMapSource
                val centre = requireNotNull(source.placement!!.toWGS84(source.placement!!.cropCentroid.x, source.placement!!.cropCentroid.y))
                val lat = args.getString("task4Lat")?.toDouble() ?: centre.latitude
                val lon = args.getString("task4Lon")?.toDouble() ?: centre.longitude
                for (z in listOf(14.0, 16.0, 18.0, 20.0, 18.0, 16.0, 14.0)) {
                    fly(lat, lon, z)
                    capture(if (File(evidence, "z${z.toInt()}.png").exists()) "return-z${z.toInt()}" else "z${z.toInt()}")
                    assertEquals(PdfRenderStatus.Ready, vm.pdfRuntime.status.value)
                }
                device.swipe(device.displayWidth * 3 / 4, device.displayHeight / 2, device.displayWidth / 4, device.displayHeight / 2, 30)
                capture("pan-seams")
                assertEquals(PdfRenderStatus.Ready, vm.pdfRuntime.status.value)
                val measureLat = args.getString("task4MeasureLat")?.toDouble()
                val measureLon = args.getString("task4MeasureLon")?.toDouble()
                if (measureLat != null && measureLon != null) {
                    for (z in listOf(18.0, 20.0)) {
                        fly(measureLat, measureLon, z)
                        capture("measurement-z${z.toInt()}")
                        assertEquals(PdfRenderStatus.Ready, vm.pdfRuntime.status.value)
                    }
                }
            } catch (failure: Throwable) {
                capture("verification-failure")
                throw failure
            }
        }
    }

    @Test fun verifyUnobstructedLowZoomMeasurementViews() {
        assumeTrue("Provide independent low-zoom anchor", args.getString("task4LowZoom") == "1")
        val name = requireNotNull(args.getString("task4File"))
        val id = requireNotNull(args.getString("task4Id"))
        sourceName = File(name).nameWithoutExtension
        evidence = File(app.filesDir, "task4/$id").apply { mkdirs() }
        val file = File(app.filesDir, "dbg/${File(name).name}")
        assertTrue("normal import source retained", file.isFile)
        val intent = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("TACMAP_DEBUG_IMPORT_PDF", file.absolutePath)
            .putExtra("TACMAP_DEBUG_GRID", "1").putExtra("TACMAP_DEBUG_OPSEC_ALLOW_SCREENSHOTS", "1")
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { vm = ViewModelProvider(it)[MapViewModel::class.java] }
            until("same normal-import PDF rendered", 90_000) { device.hasObject(By.desc("PDF map rendered: $sourceName")) }
            val lat = requireNotNull(args.getString("task4MeasureLat")).toDouble()
            val lon = requireNotNull(args.getString("task4MeasureLon")).toDouble()
            for (z in listOf(14.0, 16.0)) {
                fly(lat, lon, z)
                capture("measurement-z${z.toInt()}")
                assertEquals(PdfRenderStatus.Ready, vm.pdfRuntime.status.value)
            }
            val before = requireNotNull(vm.cameraViewportState.value)
            repeat(2) {
                device.swipe(device.displayWidth * 3 / 4, device.displayHeight / 2, device.displayWidth / 4, device.displayHeight / 2, 30)
                device.waitForIdle()
            }
            capture("pan-seams-additional")
            val after = requireNotNull(vm.cameraViewportState.value)
            val tiles = Math.scalb(1.0, after.zoom.toInt())
            val beforeTileX = kotlin.math.floor((before.longitude + 180.0) / 360.0 * tiles)
            val afterTileX = kotlin.math.floor((after.longitude + 180.0) / 360.0 * tiles)
            assertNotEquals("real pan must cross a tile boundary", beforeTileX, afterTileX)
            assertEquals(before.zoom, after.zoom, 0.001)
            assertEquals(PdfRenderStatus.Ready, vm.pdfRuntime.status.value)
            File(evidence, "pan-boundary-proof.json").writeText(buildJsonObject {
                put("beforeTileX", beforeTileX); put("afterTileX", afterTileX)
                put("zoom", after.zoom); put("actualSwipes", 2)
            }.toString())
            val highLat = args.getString("task4HighMeasureLat")?.toDouble()
            val highLon = args.getString("task4HighMeasureLon")?.toDouble()
            if (highLat != null && highLon != null) for (z in listOf(18.0, 20.0)) {
                fly(highLat, highLon, z)
                capture("measurement-z${z.toInt()}")
                assertEquals(PdfRenderStatus.Ready, vm.pdfRuntime.status.value)
            }
        }
    }

    private fun setGridVisible(visible: Boolean) {
        requireNotNull(device.wait(Until.findObject(By.desc("Menu")), 10_000)).click()
        text("Layers and Labels").click()
        val toggle = requireNotNull(device.wait(Until.findObject(By.desc("MGRS Grid")), 10_000))
        if (toggle.isChecked != visible) toggle.click()
        until("actual grid switch changed") { device.findObject(By.desc("MGRS Grid"))?.isChecked == visible }
        device.pressBack()
        until("layers dismissed") { !device.hasObject(By.text("Layers and Labels")) }
    }

    @Test fun verifyPrintedRasterAndGridSeparately() {
        assumeTrue("Provide independent grid difference anchors", args.getString("task4GridDifference") == "1")
        sourceName = File(requireNotNull(args.getString("task4File"))).nameWithoutExtension
        evidence = File(app.filesDir, "task4/${requireNotNull(args.getString("task4Id"))}").apply { mkdirs() }
        val file = File(app.filesDir, "dbg/$sourceName.pdf")
        assertTrue(file.isFile)
        val intent = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("TACMAP_DEBUG_IMPORT_PDF", file.absolutePath)
            .putExtra("TACMAP_DEBUG_GRID", "1").putExtra("TACMAP_DEBUG_OPSEC_ALLOW_SCREENSHOTS", "1")
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { vm = ViewModelProvider(it)[MapViewModel::class.java] }
            until("same live PDF rendered") { device.hasObject(By.desc("PDF map rendered: $sourceName")) }
            val source = vm.mapSource.value as PdfMapSource
            assertNull("difference pairs must use the live PDF", source.render.bake)
            for (phase in listOf("5m", "8m")) {
                val suffix = if (phase == "5m") "" else "2"
                val lat = requireNotNull(args.getString("task4MeasureLat$suffix")).toDouble()
                val lon = requireNotNull(args.getString("task4MeasureLon$suffix")).toDouble()
                fly(lat, lon, 20.0)
                setGridVisible(true)
                capture("grid-difference-z20-on-$phase")
                val before = requireNotNull(vm.cameraViewportState.value)
                setGridVisible(false)
                capture("grid-difference-z20-off-$phase")
                val after = requireNotNull(vm.cameraViewportState.value)
                assertEquals(before.latitude, after.latitude, 1e-7)
                assertEquals(before.longitude, after.longitude, 1e-7)
                assertEquals(before.zoom, after.zoom, 0.001)
                assertEquals(source.id, (vm.mapSource.value as PdfMapSource).id)
                assertEquals(PdfRenderStatus.Ready, vm.pdfRuntime.status.value)
                File(evidence, "grid-toggle-proof-$phase.json").writeText(buildJsonObject {
                    put("realUISwitchOnThenOff", true); put("sameSourceId", source.id)
                    put("latitude", after.latitude); put("longitude", after.longitude); put("zoom", after.zoom)
                }.toString())
            }
            setGridVisible(true)
        }
    }

    @Test fun verifyActualCompletedRenderGeometry() {
        assumeTrue("Provide explicit read-only job audit", args.getString("task4Audit") == "1")
        sourceName = File(requireNotNull(args.getString("task4File"))).nameWithoutExtension
        evidence = File(app.filesDir, "task4/${requireNotNull(args.getString("task4Id"))}").apply { mkdirs() }
        val file = File(app.filesDir, "dbg/$sourceName.pdf")
        assertTrue(file.isFile)
        val intent = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("TACMAP_DEBUG_IMPORT_PDF", file.absolutePath)
            .putExtra("TACMAP_DEBUG_GRID", "1").putExtra("TACMAP_DEBUG_OPSEC_ALLOW_SCREENSHOTS", "1")
            .putExtra("TACMAP_DEBUG_DEVICE_AUDIT", "1")
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { vm = ViewModelProvider(it)[MapViewModel::class.java] }
            until("actual live PDF rendered") { device.hasObject(By.desc("PDF map rendered: $sourceName")) }
            var source = vm.mapSource.value as PdfMapSource
            val legacyBake = if (args.getString("task4LegacyBake") == "1") requireNotNull(source.render.bake) else null
            if (legacyBake == null) assertNull(source.render.bake) else {
                assertNotEquals("renderer-v1 bake must no longer match", legacyBake.bakeKey,
                    com.tacmap.map.render.pdf.PdfBakePlan.bakeKey(requireNotNull(source.placement).canonicalJson(), legacyBake.tilePx))
                assertNull("stale precision tiles cannot attach", requireNotNull(vm.pdfRuntime.activeSource).bakeMaxZoom)
                val oldFile = File(app.filesDir, "offline_tiles/${legacyBake.fileName}")
                assertTrue("existing ownership preserves stale file for ordinary lifecycle", oldFile.isFile)
                val oldStore = requireNotNull(com.tacmap.calibration.MBTilesStore.open(oldFile.path))
                try {
                    assertEquals("1", oldStore.rawMetadata("tacmap_renderer"))
                    assertEquals(legacyBake.bakeKey, oldStore.rawMetadata("tacmap_bake_key"))
                } finally { oldStore.close() }
                assertTrue("source PDF survives", com.tacmap.calibration.PdfStoredFile.matches(
                    File(requireNotNull(source.uri.path)), requireNotNull(source.render.contentKey)))
            }
            val lat = requireNotNull(args.getString("task4MeasureLat")).toDouble()
            val lon = requireNotNull(args.getString("task4MeasureLon")).toDouble()
            fly(lat, lon, 20.0)
            try {
                until("actual painted frame at requested camera") {
                    val current = vm.mapSource.value as? PdfMapSource
                    val activeRender = vm.pdfRuntime.activeSource
                    val f = com.tacmap.map.render.pdf.PdfDeviceAudit.snapshot()["frame"] as? JsonObject
                    val matches = f != null && current != null && activeRender != null && current.entryId == source.entryId &&
                        current.contentKey == source.contentKey && f["sourceId"]!!.jsonPrimitive.content == activeRender.auditSourceId &&
                        f["renderSourceId"]!!.jsonPrimitive.content == activeRender.auditInstanceId &&
                        abs(f["latitude"]!!.jsonPrimitive.double - lat) < 1e-7 &&
                        abs(f["longitude"]!!.jsonPrimitive.double - lon) < 1e-7 &&
                        abs(f["zoom"]!!.jsonPrimitive.double - 20.0) < .001
                    if (matches) source = requireNotNull(current)
                    matches
                }
            } catch (failure: Throwable) {
                capture("audit-admission-failure")
                File(evidence, "audit-admission-failure-audit.json").writeText(
                    com.tacmap.map.render.pdf.PdfDeviceAudit.snapshot().toString())
                throw failure
            }
            val activeRender = requireNotNull(vm.pdfRuntime.activeSource)
            assertEquals("actual renderer uses current canonical georef", requireNotNull(source.placement).canonicalJson(), activeRender.georef.canonicalJson())
            assertTrue("actual renderer cache identity binds current file", activeRender.cacheKey.startsWith("pdf:${source.uri.path}:"))
            assertTrue("actual source bytes match current content", com.tacmap.calibration.PdfStoredFile.matches(
                File(requireNotNull(source.uri.path)), requireNotNull(source.render.contentKey)))
            capture("render-audit-z20")
            val audit = com.tacmap.map.render.pdf.PdfDeviceAudit.snapshot()
            File(evidence, "render-audit-z20-audit.json").writeText(audit.toString())
            assertEquals(0, audit["frameObservationErrors"]!!.jsonPrimitive.int)
            val f = audit["frame"]!!.jsonObject
            assertEquals(0, f["omittedDraws"]!!.jsonPrimitive.int)
            val completed = audit["completed"]!!.jsonObject
            assertEquals(0, completed["omittedRecords"]!!.jsonPrimitive.int)
            assertEquals(0, completed["failedRecords"]!!.jsonPrimitive.int)
            val actualRenderSourceId = requireNotNull(vm.pdfRuntime.activeSource?.auditInstanceId)
            assertEquals(actualRenderSourceId, f["renderSourceId"]!!.jsonPrimitive.content)
            val records = completed["records"]!!.jsonArray.map { it.jsonObject }.filter {
                it["sourceId"]!!.jsonPrimitive.content == activeRender.auditSourceId && it["renderSourceId"]!!.jsonPrimitive.content == actualRenderSourceId
            }
            val draws = f["draws"]!!.jsonArray
            assertTrue("actual painted tiles must be observed", draws.isNotEmpty())
            for (draw in draws) {
                val t = draw.jsonObject["source"]!!.jsonArray.map { it.jsonPrimitive.int }
                val matches = records.filter {
                    val j = it["job"]!!.jsonObject
                    j["z"]!!.jsonPrimitive.int == t[0] &&
                        t[1] in j["x"]!!.jsonPrimitive.int until j["x"]!!.jsonPrimitive.int + j["cols"]!!.jsonPrimitive.int &&
                        t[2] in j["y"]!!.jsonPrimitive.int until j["y"]!!.jsonPrimitive.int + j["rows"]!!.jsonPrimitive.int
                }
                assertEquals("each painted bitmap must have one unambiguous completed job", 1, matches.size)
            }
            assertEquals(PdfRenderStatus.Ready, vm.pdfRuntime.status.value)
            if (legacyBake != null) {
                assertNull(requireNotNull(vm.pdfRuntime.activeSource).bakeMaxZoom)
                assertEquals(legacyBake, vm.libraryState.value!!.entry(source.entryId!!)!!.pdf!!.bake)
                File(evidence, "legacy-bake-proof.json").writeText(buildJsonObject {
                    put("rendererVersion", com.tacmap.map.render.pdf.PdfBakePlan.RENDERER_VERSION)
                    put("storedLegacyRecordPreserved", true); put("legacyReaderAttached", false)
                    put("sourcePDFPreserved", true); put("liveOwnTilesCompletedAndPainted", true)
                }.toString())
            }
        }
    }

    @Test fun verifyColdPublishedBakeViews() {
        assumeTrue("Cold renderer2 bake acquisition is explicit", args.getString("task4ColdBake") == "1")
        sourceName = File(requireNotNull(args.getString("task4File"))).nameWithoutExtension
        evidence = File(app.filesDir, "task4/bake-cold-precision").apply { mkdirs() }
        val intent = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("TACMAP_DEBUG_GRID", "1").putExtra("TACMAP_DEBUG_OPSEC_ALLOW_SCREENSHOTS", "1")
            .putExtra("TACMAP_DEBUG_DEVICE_AUDIT", "1")
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { vm = ViewModelProvider(it)[MapViewModel::class.java] }
            until("published PDF restored") { device.hasObject(By.desc("PDF map rendered: $sourceName")) }
            val source = vm.mapSource.value as PdfMapSource
            val bake = requireNotNull(source.render.bake)
            assertEquals(2, com.tacmap.map.render.pdf.PdfBakePlan.RENDERER_VERSION)
            assertEquals(com.tacmap.map.render.pdf.PdfBakePlan.bakeKey(requireNotNull(source.placement).canonicalJson(), bake.tilePx), bake.bakeKey)
            val store = requireNotNull(com.tacmap.calibration.MBTilesStore.open(File(app.filesDir, "offline_tiles/${bake.fileName}").path))
            try { assertEquals("2", store.rawMetadata("tacmap_renderer")) } finally { store.close() }
            until("current baked reader attaches") { vm.pdfRuntime.activeSource?.bakeMaxZoom == bake.maxZoom }
            val lat = requireNotNull(args.getString("task4MeasureLat")).toDouble()
            val lon = requireNotNull(args.getString("task4MeasureLon")).toDouble()
            val lowLat = requireNotNull(args.getString("task4LowMeasureLat")).toDouble()
            val lowLon = requireNotNull(args.getString("task4LowMeasureLon")).toDouble()
            for (z in listOf(16.0, 18.0, 20.0)) {
                val targetLat = if (z <= 16.0) lowLat else lat
                val targetLon = if (z <= 16.0) lowLon else lon
                fly(targetLat, targetLon, z)
                until("cold baked own tiles painted") {
                    val frame = com.tacmap.map.render.pdf.PdfDeviceAudit.snapshot()["frame"] as? JsonObject
                    val draws = frame?.get("draws")?.jsonArray
                    frame != null && draws != null && draws.isNotEmpty() &&
                        frame["renderSourceId"]!!.jsonPrimitive.content == vm.pdfRuntime.activeSource?.auditInstanceId &&
                        abs(frame["zoom"]!!.jsonPrimitive.double - z) < .001 &&
                        abs(frame["latitude"]!!.jsonPrimitive.double - targetLat) < 1e-7 &&
                        abs(frame["longitude"]!!.jsonPrimitive.double - targetLon) < 1e-7 &&
                        draws.all { it.jsonObject["kind"]!!.jsonPrimitive.content == "OWN" &&
                            it.jsonObject["source"]!!.jsonArray[0].jsonPrimitive.int == 16 }
                }
                capture("cold-baked-z${z.toInt()}")
                val audit = com.tacmap.map.render.pdf.PdfDeviceAudit.snapshot()
                File(evidence, "cold-baked-z${z.toInt()}-audit.json").writeText(audit.toString())
                val active = requireNotNull(vm.pdfRuntime.activeSource)
                assertEquals(requireNotNull(source.placement).canonicalJson(), active.georef.canonicalJson())
                assertTrue(active.cacheKey.startsWith("pdf:${source.uri.path}:"))
                assertEquals(bake.maxZoom, active.bakeMaxZoom)
                val frame = audit["frame"]!!.jsonObject
                assertEquals(active.auditSourceId, frame["sourceId"]!!.jsonPrimitive.content)
                assertEquals(0, frame["omittedDraws"]!!.jsonPrimitive.int)
                // This process starts cold. No vector/raster D16 jobs may supply these actual D16 bitmaps.
                val records = audit["completed"]!!.jsonObject["records"]!!.jsonArray.map { it.jsonObject }
                assertTrue("displayed D16 comes from the restored baked reader", records.none {
                    it["renderSourceId"]!!.jsonPrimitive.content == active.auditInstanceId &&
                        it["job"]!!.jsonObject["z"]!!.jsonPrimitive.int == 16
                })
                assertEquals(PdfRenderStatus.Ready, vm.pdfRuntime.status.value)
            }
            assertTrue(com.tacmap.calibration.PdfStoredFile.matches(File(requireNotNull(source.uri.path)), requireNotNull(source.render.contentKey)))
        }
    }

    private fun scrollToLayerControl(label: String) {
        var scrolls = 0
        while (!device.hasObject(By.text(label)) && scrolls < 30) {
            val scrollView = requireNotNull(device.findObject(By.scrollable(true)))
            scrollView.scroll(Direction.DOWN, 0.15f, 300)
            device.waitForIdle()
            Thread.sleep(750)
            scrolls++
            device.dumpWindowHierarchy(File(evidence, "bake-scroll-$scrolls.xml"))
            device.takeScreenshot(File(evidence, "bake-scroll-$scrolls.png"))
        }
        capture("bake-sheet-control")
        text(label).click()
    }

    @Test fun verifyRealBakeSurvivesConfigurationRotation() {
        assumeTrue("Provide task4Bake for the real bake lifecycle", args.getString("task4Bake") == "1")
        val name = args.getString("task4File") ?: "tacmap_grid_sf_iso.pdf"
        sourceName = File(name).nameWithoutExtension
        evidence = File(app.filesDir, "task4/bake-rotation").apply { mkdirs() }
        val file = File(app.filesDir, "dbg/${File(name).name}").apply { parentFile!!.mkdirs() }
        if (!file.isFile) instr.context.assets.open("geopdf/${File(name).name}").use { input -> file.outputStream().use { input.copyTo(it) } }
        val intent = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("TACMAP_DEBUG_IMPORT_PDF", file.absolutePath)
            .putExtra("TACMAP_DEBUG_GRID", "1").putExtra("TACMAP_DEBUG_OPSEC_ALLOW_SCREENSHOTS", "1")
        try {
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            lateinit var beforeActivity: MainActivity
            scenario.onActivity {
                beforeActivity = it
                vm = ViewModelProvider(it)[MapViewModel::class.java]
            }
            until("live PDF") { device.hasObject(By.desc("PDF map rendered: $sourceName")) }
            if ((vm.mapSource.value as PdfMapSource).render.bake != null) {
                requireNotNull(device.wait(Until.findObject(By.desc("Menu")), 10_000)).click()
                text("Layers and Labels").click()
                scrollToLayerControl(Messages.pdfBakeRemove())
                until("prior real bake removed through UI") {
                    (vm.mapSource.value as? PdfMapSource)?.render?.bake == null
                }
                device.pressBack()
            }
            assertNull("live comparison starts without a stored bake", (vm.mapSource.value as PdfMapSource).render.bake)
            val entryId = (vm.mapSource.value as PdfMapSource).entryId
            val lat = args.getString("task4MeasureLat")!!.toDouble()
            val lon = args.getString("task4MeasureLon")!!.toDouble()
            val lowLat = requireNotNull(args.getString("task4LowMeasureLat")).toDouble()
            val lowLon = requireNotNull(args.getString("task4LowMeasureLon")).toDouble()
            for (z in listOf(16.0, 18.0, 20.0)) {
                fly(if (z <= 16.0) lowLat else lat, if (z <= 16.0) lowLon else lon, z)
                capture("live-z${z.toInt()}")
                assertEquals(PdfRenderStatus.Ready, vm.pdfRuntime.status.value)
            }
            requireNotNull(device.wait(Until.findObject(By.desc("Menu")), 10_000)).click()
            text("Layers and Labels").click()
            capture("bake-sheet-open")
            scrollToLayerControl(Messages.pdfBakeGenerateButton())
            until("bake confirm") { vm.bakeManager.state.value is PdfBakeManager.State.Confirming }
            val proposal = (vm.bakeManager.state.value as PdfBakeManager.State.Confirming).proposal
            val highest = proposal.options.filter { it.enoughSpace }.maxBy { it.option.maxZoom }
            val optionPrefix = "Up to zoom ${highest.option.maxZoom}"
            requireNotNull(device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(optionPrefix) + ".*"))), 10_000)).click()
            val generationClickAt = android.os.SystemClock.elapsedRealtime()
            text(Messages.pdfBakeGenerate()).click()
            until("actual running bake") { vm.bakeManager.state.value is PdfBakeManager.State.Running }
            val runningObservedAt = android.os.SystemClock.elapsedRealtime()
            val before = vm.bakeManager.state.value as PdfBakeManager.State.Running
            assertTrue(before.done < before.total)
            device.setOrientationLeft()
            until("configuration rotated") { device.displayWidth > device.displayHeight }
            scenario.onActivity {
                assertSame("orientation is handled by the actual activity", beforeActivity, it)
                assertSame(vm, ViewModelProvider(it)[MapViewModel::class.java])
            }
            assertTrue("bake survives real configuration rotation", vm.bakeManager.state.value is PdfBakeManager.State.Running)
            scenario.recreate()
            scenario.onActivity {
                assertNotSame("actual activity must be recreated", beforeActivity, it)
                assertSame(vm, ViewModelProvider(it)[MapViewModel::class.java])
            }
            val after = vm.bakeManager.state.value as? PdfBakeManager.State.Running
            assertNotNull("bake must still be running after actual recreation", after)
            capture("configuration-during-bake")
            assertTrue("configuration screenshot must also show an active bake", vm.bakeManager.state.value is PdfBakeManager.State.Running)
            File(evidence, "rotation-proof.json").writeText(buildJsonObject {
                put("beforeDone", before.done); put("beforeTotal", before.total)
                put("afterConfigurationState", after.toString())
                put("maxZoom", highest.option.maxZoom); put("plannedTiles", highest.option.tiles)
                put("actualRotationHandledBySameActivity", true)
                put("additionalActualActivityRecreation", true)
                put("sameViewModel", true); put("activeEntryId", entryId)
            }.toString())
            until("bake published", 300_000) { vm.libraryState.value?.entry(entryId!!)?.pdf?.bake != null }
            val publishedAt = android.os.SystemClock.elapsedRealtime()
            val published = requireNotNull(vm.libraryState.value!!.entry(entryId!!)!!.pdf!!.bake)
            File(evidence, "bake-cost-proof.json").writeText(buildJsonObject {
                put("rendererVersion", com.tacmap.map.render.pdf.PdfBakePlan.RENDERER_VERSION)
                put("generationClickAtElapsedMs", generationClickAt); put("runningObservedAtElapsedMs", runningObservedAt)
                put("publicationObservedAtElapsedMs", publishedAt)
                put("observedGenerateToPublicationMs", publishedAt - generationClickAt)
                put("observedRunningToPublicationMs", publishedAt - runningObservedAt)
                put("plannedTiles", highest.option.tiles); put("maxZoom", highest.option.maxZoom)
                put("bakeBytes", published.bytes); put("bakeTilePx", published.tilePx)
                put("postBakeEwmaMs", vm.pdfRuntime.activeSource?.ewmaMs?.let(::JsonPrimitive) ?: JsonNull)
                put("postBakeHeavy", vm.pdfRuntime.activeSource?.heavy ?: false)
                put("observedJavaHeapUsedBytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
                put("javaHeapMaximumBytes", Runtime.getRuntime().maxMemory())
            }.toString())
            assertEquals(entryId, vm.libraryState.value!!.active.entryId)
            // Recreation dismisses the sheet. Only dismiss it if it still obscures the map.
            if (!device.hasObject(By.desc("PDF map rendered: $sourceName"))) device.pressBack()
            device.setOrientationNatural()
            until("portrait restored") { device.displayHeight > device.displayWidth }
            until("baked source attached") { (vm.mapSource.value as? PdfMapSource)?.render?.bake != null }
            for (z in listOf(16.0, 18.0, 20.0)) {
                fly(if (z <= 16.0) lowLat else lat, if (z <= 16.0) lowLon else lon, z)
                capture("baked-z${z.toInt()}")
                assertEquals(PdfRenderStatus.Ready, vm.pdfRuntime.status.value)
            }
        }
        } finally {
            device.setOrientationNatural()
            device.unfreezeRotation()
        }
    }
}
