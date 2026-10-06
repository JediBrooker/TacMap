package com.tacmap.app

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapScreenLaunchGuardTest {
    private val launcher = setOf(Intent.CATEGORY_LAUNCHER)

    private fun yields(
        restoring: Boolean = false,
        root: Boolean = false,
        action: String? = Intent.ACTION_MAIN,
        categories: Set<String>? = launcher,
        mapBelow: Boolean = true,
    ) = MapScreenLaunchGuard.shouldYield(restoring, root, action, categories) { mapBelow }

    @Test
    fun launcherOrNotificationTapOnTopOfARunningMapFinishes() {
        assertTrue(yields())
    }

    @Test
    fun theTaskRootAlwaysOpens() {
        assertFalse(yields(root = true))
    }

    @Test
    fun aRestoreIsNeverFinished() {
        // process death or a config change recreates the same screen, it has to come back
        assertFalse(yields(restoring = true))
    }

    @Test
    fun onlyLauncherIntentsYield() {
        // am start -n, debug hook intents, tests: explicit component, no MAIN/LAUNCHER
        assertFalse(yields(action = null, categories = null))
        assertFalse(yields(action = Intent.ACTION_VIEW))
        assertFalse(yields(categories = null))
        assertFalse(yields(categories = setOf(Intent.CATEGORY_DEFAULT)))
    }

    @Test
    fun launcherIntentIntoSomeoneElsesTaskStillOpens() {
        // another app started our launch intent without NEW_TASK, the root under us is theirs
        assertFalse(yields(mapBelow = false))
    }

    @Test
    fun taskLookupOnlyRunsWhenItCouldMatter() {
        var asked = 0
        val probe = { asked++; true }
        MapScreenLaunchGuard.shouldYield(false, true, Intent.ACTION_MAIN, launcher, probe)
        MapScreenLaunchGuard.shouldYield(true, false, Intent.ACTION_MAIN, launcher, probe)
        MapScreenLaunchGuard.shouldYield(false, false, null, null, probe)
        assertTrue("binder call on an ordinary launch", asked == 0)
    }
}
