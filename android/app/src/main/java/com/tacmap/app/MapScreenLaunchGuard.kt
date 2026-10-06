package com.tacmap.app

import android.content.Intent

/**
 * The launcher icon and the Unit Sync notice both send MAIN/LAUNCHER, and that only brings the
 * running task forward when it matches the task's root intent. A task started some other way
 * (am start -n, Play's Open on 26-28 which sets the package) doesn't match, so the platform
 * stacks a fresh MainActivity with its own MapViewModel on top of the map that's already there.
 * That fresh one should just finish so the one underneath shows.
 *
 * Only when the task's root really is a map screen: a launcher intent some other app fired
 * into its own task without NEW_TASK isn't root either and has to open normally. Never on a
 * restore, a recreated activity is the same screen coming back
 */
internal object MapScreenLaunchGuard {
    fun shouldYield(
        restoring: Boolean,
        isTaskRoot: Boolean,
        action: String?,
        categories: Set<String>?,
        taskRootIsMapScreen: () -> Boolean,
    ): Boolean {
        if (restoring || isTaskRoot) return false
        if (action != Intent.ACTION_MAIN || categories?.contains(Intent.CATEGORY_LAUNCHER) != true) return false
        return taskRootIsMapScreen()
    }
}
