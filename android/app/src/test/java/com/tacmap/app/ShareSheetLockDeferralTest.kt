package com.tacmap.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ShareSheetLockDeferralTest {
    @Test
    fun ordinaryPauseStillLocksImmediately() {
        val gate = ShareSheetLockDeferral()
        assertTrue(gate.onPause())
        assertFalse("already locked, nothing deferred", gate.onStop())
    }

    @Test
    fun ownShareSheetKeepsTheMapUpUntilTheUserActuallyLeaves() {
        val gate = ShareSheetLockDeferral()
        gate.shareSheetRequested()
        assertFalse("chooser is translucent, map stays visible", gate.onPause())
        assertTrue("picked a target / went home / screen off", gate.onStop())
        assertFalse(gate.onStop())
    }

    @Test
    fun dismissingTheSheetClearsTheDeferral() {
        val gate = ShareSheetLockDeferral()
        gate.shareSheetRequested()
        assertFalse(gate.onPause())
        gate.onResume()
        assertFalse(gate.onStop())
        assertTrue("next pause is ordinary again", gate.onPause())
    }

    @Test
    fun deferralIsOneShot() {
        val gate = ShareSheetLockDeferral()
        gate.shareSheetRequested()
        assertFalse(gate.onPause())
        // multi window or similar: resumed focus never came back before another pause
        assertTrue(gate.onPause())
    }

    @Test
    fun failedLaunchDoesNotLeaveADeferralArmed() {
        val gate = ShareSheetLockDeferral()
        gate.shareSheetRequested()
        gate.shareSheetLaunchFailed()
        assertTrue(gate.onPause())
    }

    @Test
    fun appChoosersGoThroughTheOwnShareSheetPath() {
        val root = sourceRoot()
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("startActivity(Intent.createChooser(") }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .toList()
        assertTrue("use startOwnShareSheet: $offenders", offenders.isEmpty())

        val activity = File(root, "com/tacmap/app/MainActivity.kt").readText()
        assertTrue(activity.contains("if (shareSheetLock.onPause()) lockForBackground()"))
        // compose is paused by the time onStop runs, so the deferred path has to drop MapScreen itself
        val onStop = activity.substringAfter("override fun onStop()").substringBefore("\n    }\n")
        assertTrue(onStop.contains("if (shareSheetLock.onStop()) {"))
        assertTrue(onStop.contains("lockForBackground()"))
        assertTrue(onStop.contains("?.disposeComposition()"))
        assertTrue(activity.contains("shareSheetLock.onResume()"))
    }

    private fun sourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            listOf("src/main/java", "app/src/main/java", "android/app/src/main/java").forEach { relative ->
                val candidate = File(dir, relative)
                if (File(candidate, "com/tacmap").isDirectory) return candidate
            }
            dir = dir?.parentFile
        }
        error("Could not locate the app sources")
    }
}
