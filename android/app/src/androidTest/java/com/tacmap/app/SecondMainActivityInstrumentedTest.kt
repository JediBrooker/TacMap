package com.tacmap.app

import android.Manifest
import android.app.Activity
import android.app.ActivityOptions
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.tacmap.calibration.ActiveRef
import com.tacmap.calibration.BasemapStyle
import com.tacmap.calibration.ImportedMapLibraryStore
import com.tacmap.calibration.LibraryLoad
import com.tacmap.calibration.LibraryState
import com.tacmap.calibration.PdfBaker
import com.tacmap.localization.Messages
import com.tacmap.map.FirstRunTips
import com.tacmap.map.MapViewModel
import com.tacmap.sync.BackgroundUnitSyncLocationService
import com.tacmap.util.DataKey
import com.tacmap.util.MissionKeyUnlockRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A1 (3.0.1) on a real device, the way it was found: TacMap open, Home, tap the Unit Sync
 * notification. That used to stack a second MainActivity (with its own MapViewModel and its
 * own copy of the library) on top of the first, and the first one's next library write
 * deleted whatever the second had imported. Debug build only (the import hook)
 */
@RunWith(AndroidJUnit4::class)
class SecondMainActivityInstrumentedTest {
    @get:Rule val missionKey = MissionKeyUnlockRule()
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val app: Context = instr.targetContext
    private val device = UiDevice.getInstance(instr)
    private val files = app.filesDir
    private val library = ImportedMapLibraryStore(files)
    private val backup = File(app.cacheDir, "secondactivity-backup-${System.nanoTime()}")
    private val roots = listOf("pdf_maps", "mbtiles", "offline_tiles", PdfBaker.WORK_DIR)

    @Before
    fun setUp() {
        backup.mkdirs()
        files.listFiles().orEmpty().filter { it.isFile }.forEach { it.copyTo(File(backup, it.name), overwrite = true) }
        for (r in roots) File(files, r).listFiles().orEmpty().filter { it.isFile }.forEach {
            it.copyTo(File(backup, "$r/${it.name}"), overwrite = true)
        }
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms += Manifest.permission.POST_NOTIFICATIONS
        perms.forEach { instr.uiAutomation.grantRuntimePermission(app.packageName, it) }
        FirstRunTips.markSeen(app)
        // start every run on a known library, nothing imported
        assertTrue(library.write(LibraryState(active = ActiveRef.online(BasemapStyle.OSM_TOPO.name), preferredOnlineStyle = BasemapStyle.OSM_TOPO.name)))
    }

    @After
    fun tearDown() {
        instr.runOnMainSync { mainActivitiesNow().forEach { it.finish() } }
        waitUntil("activities gone") { mainActivities().isEmpty() }
        BackgroundUnitSyncLocationService.cancelPausedNotification(app)
        device.pressHome()
        // finishing them relocked the mission key, the restore below writes the library
        DataKey.unlock()
        val lib = ImportedMapLibraryStore.FILE_NAME
        files.listFiles().orEmpty().filter { it.name.startsWith("$lib.corrupt-") && !File(backup, it.name).exists() }.forEach { it.delete() }
        File(files, "dbg").deleteRecursively()
        for (r in roots) {
            File(files, r).listFiles().orEmpty().filter { it.isFile }.forEach { it.delete() }
            File(backup, r).listFiles().orEmpty().forEach { it.copyTo(File(files, "$r/${it.name}"), overwrite = true) }
        }
        backup.listFiles().orEmpty().filter { it.isFile }.forEach { it.copyTo(File(files, it.name), overwrite = true) }
        // none before this ran: leave an empty one, a deleted library reads as corrupt from then on (WP4 r1 S1)
        if (!File(backup, lib).exists()) {
            library.write(LibraryState(active = ActiveRef.online(BasemapStyle.OSM_TOPO.name), preferredOnlineStyle = BasemapStyle.OSM_TOPO.name))
        }
        backup.deleteRecursively()
    }

    private fun waitUntil(what: String, ms: Long = 30_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out: $what")
            Thread.sleep(50)
        }
    }

    /** main thread only */
    private fun mainActivitiesNow(): List<MainActivity> {
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        return Stage.values().filter { it != Stage.DESTROYED }
            .flatMap { monitor.getActivitiesInStage(it) }
            .filterIsInstance<MainActivity>()
            .distinct()
    }

    private fun mainActivities(): List<MainActivity> {
        var out = emptyList<MainActivity>()
        instr.runOnMainSync { out = mainActivitiesNow() }
        return out
    }

    private fun stageOf(activity: Activity): Stage? {
        var stage: Stage? = null
        instr.runOnMainSync { stage = ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(activity) }
        return stage
    }

    private fun vmOf(activity: MainActivity): MapViewModel {
        var vm: MapViewModel? = null
        instr.runOnMainSync { vm = ViewModelProvider(activity)[MapViewModel::class.java] }
        return vm!!
    }

    /** what the home screen sends. debug hooks on so nothing pops up over the map */
    private fun launcherIntent(): Intent = Intent.makeMainActivity(ComponentName(app, MainActivity::class.java))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        .putExtra(DebugLaunchHooks.EXTRA_ALLOW_SCREENSHOTS, "1")

    private fun fixture(name: String): File {
        val file = File(files, "dbg/$name").apply { parentFile!!.mkdirs() }
        instr.context.assets.open("geopdf/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    /** pull the shade down and tap it like a person would, else fire its content intent */
    private fun tapPausedNotification() {
        device.openNotification()
        val row = device.wait(Until.findObject(By.text(Messages.syncBackgroundPausedTitle())), 10_000)
        if (row != null) {
            row.click()
            return
        }
        device.pressBack()
        val manager = app.getSystemService(NotificationManager::class.java)
        val posted = manager.activeNotifications.first { it.id == PAUSED_NOTIFICATION_ID }
        @Suppress("DEPRECATION")
        val options = if (Build.VERSION.SDK_INT >= 34) {
            ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle()
        } else {
            null
        }
        posted.notification.contentIntent.send(app, 0, null, null, null, null, options)
    }

    @Test
    fun tappingTheUnitSyncNotificationBringsTheRunningMapBack() {
        val first = instr.startActivitySync(launcherIntent()) as MainActivity
        device.pressHome()
        waitUntil("backgrounded") { stageOf(first) == Stage.STOPPED }

        BackgroundUnitSyncLocationService.postPausedNotification(app, "12:00")
        tapPausedNotification()

        waitUntil("something in front again") { mainActivities().any { stageOf(it) == Stage.RESUMED } }
        // give a stacked second instance the time to show up if one's coming
        Thread.sleep(1_500)
        assertEquals("the tap stacked another MainActivity", listOf(first), mainActivities())
        assertEquals(Stage.RESUMED, stageOf(first))
    }

    @Test
    fun aSecondMainActivityCantMakeTheFirstDeleteWhatItImported() {
        val sheetA = fixture("tacmap_grid_sf_iso.pdf")
        val sheetB = fixture("tacmap_grid_syd_iso.pdf")
        val a = instr.startActivitySync(launcherIntent().putExtra(DebugLaunchHooks.EXTRA_IMPORT_PDF, sheetA.absolutePath)) as MainActivity
        val vmA = vmOf(a)
        waitUntil("first import", ms = 60_000) { vmA.libraryState.value?.entries?.size == 1 }

        // a non launcher intent (what the notification used to send, and what am start -n still
        // does) doesn't match the task's root, so the platform stacks a second instance
        val second = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(DebugLaunchHooks.EXTRA_ALLOW_SCREENSHOTS, "1")
            .putExtra(DebugLaunchHooks.EXTRA_IMPORT_PDF, sheetB.absolutePath)
        val b = instr.startActivitySync(second) as MainActivity
        assertNotSame(a, b)
        val vmB = vmOf(b)
        assertNotSame(vmA, vmB)
        waitUntil("second import", ms = 60_000) { vmB.libraryState.value?.entries?.size == 2 }
        val imported = vmB.libraryState.value!!.entries.single { it.displayName == sheetB.nameWithoutExtension }
        assertTrue(File(files, imported.fileName).isFile)

        // Back out of B, A comes to the front again with the library it had before B's import
        instr.runOnMainSync { b.finish() }
        waitUntil("A back in front") { stageOf(a) == Stage.RESUMED && b !in mainActivities() }
        var picked = false
        instr.runOnMainSync { picked = vmA.selectBaseMap(BasemapStyle.OSM_STREET) }
        assertTrue(picked)

        val durable = requireNotNull((library.load() as? LibraryLoad.Loaded)?.state)
        assertNotNull("B's import dropped from the library", durable.entry(imported.id))
        assertTrue("B's import deleted: ${File(files, "pdf_maps").list()?.toList()}", File(files, imported.fileName).isFile)
        assertEquals(2, durable.entries.size)
        assertEquals(ActiveRef.online(BasemapStyle.OSM_STREET.name), durable.active)
    }

    private companion object {
        // BackgroundUnitSyncLocationService's paused notice
        const val PAUSED_NOTIFICATION_ID = 4203
    }
}
