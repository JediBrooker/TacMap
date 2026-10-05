package com.tacmap.util

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.UiDevice
import com.tacmap.app.DebugLaunchHooks
import com.tacmap.app.MainActivity
import com.tacmap.app.TacticalApp
import com.tacmap.map.FirstRunTips
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The real DataKey on a device: once the Activity pause locks it, nothing gets the DEK back
 * into the general cache until the explicit unlock. Device-bound mode only, auth mode would
 * need a person at the credential prompt.
 */
@RunWith(AndroidJUnit4::class)
class DataKeyRelockInstrumentedTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val app = instr.targetContext
    private val device = UiDevice.getInstance(instr)
    private val scratch = File(app.cacheDir, "relock-${System.nanoTime()}")

    @get:Rule val missionKey = MissionKeyUnlockRule()

    @Before fun setUp() {
        assumeFalse(DataKey.isAuthBound)
        scratch.mkdirs()
    }

    @After fun tearDown() {
        instr.runOnMainSync { mainActivitiesNow().forEach { it.finish() } }
        waitUntil("activities gone") { mainActivities().isEmpty() }
        device.pressHome()
        runCatching { DataKey.unlock() }
        scratch.deleteRecursively()
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

    private fun stageOf(activity: MainActivity): Stage? {
        var stage: Stage? = null
        instr.runOnMainSync { stage = ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(activity) }
        return stage
    }

    private fun launcherIntent(): Intent = Intent.makeMainActivity(ComponentName(app, MainActivity::class.java))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        .putExtra(DebugLaunchHooks.EXTRA_ALLOW_SCREENSHOTS, "1")

    @Test fun lockedKeyIsNeverUnwrappedAgainUntilTheExplicitUnlock() {
        assertTrue(DataKey.isKeyCachedForTests)
        DataKey.lock()
        assertFalse(DataKey.isKeyCachedForTests)
        assertFalse(DataKey.isUnlocked)
        // what a sealed write that was still running at the pause runs into
        val late = File(scratch, "late.json")
        assertThrows(DataKey.LockedException::class.java) { SafeStore.writeAtomically(late, "relock-probe", "{}") }
        assertFalse(late.exists())
        assertThrows(DataKey.LockedException::class.java) { DataKey.key() }
        assertFalse("nothing refilled the general cache", DataKey.isKeyCachedForTests)

        DataKey.unlock()
        assertTrue(DataKey.isKeyCachedForTests)
        assertTrue(DataKey.isUnlocked)
        DataKey.key().fill(0)
    }

    @Test fun homeRelocksTheKeyAndOnlyTheResumeUnlocksItAgain() {
        assumeFalse("App Lock wants the PIN screen first", (app.applicationContext as TacticalApp).appLock.isEnabled)
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms += Manifest.permission.POST_NOTIFICATIONS
        // a permission dialog on top would pause (and lock) the map before we look
        perms.forEach { instr.uiAutomation.grantRuntimePermission(app.packageName, it) }
        FirstRunTips.markSeen(app)

        val activity = instr.startActivitySync(launcherIntent()) as MainActivity
        waitUntil("in front and unlocked") { stageOf(activity) == Stage.RESUMED && DataKey.isKeyCachedForTests }

        device.pressHome()
        waitUntil("backgrounded") { stageOf(activity) == Stage.STOPPED }
        assertFalse("the pause dropped the general key", DataKey.isKeyCachedForTests)
        assertThrows(DataKey.LockedException::class.java) { DataKey.key() }
        // whatever the retained view model or a late callback does in the background, no refill
        Thread.sleep(1_500)
        assertFalse("nothing re-cached it behind the lock", DataKey.isKeyCachedForTests)

        app.startActivity(launcherIntent())
        waitUntil("back in front") { stageOf(activity) == Stage.RESUMED }
        waitUntil("the resume unlocked it again") { DataKey.isKeyCachedForTests }
    }
}
