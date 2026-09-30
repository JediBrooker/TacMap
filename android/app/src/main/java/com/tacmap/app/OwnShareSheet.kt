package com.tacmap.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent

/**
 * Our own share chooser is a translucent activity. It pauses MainActivity but the map stays
 * on screen behind it, so locking on that pause flashed the "Mission data locked" screen behind
 * the sheet (and rebuilt MapScreen once it closed). For that one pause we hold the lock untill
 * onStop instead, which still fires as soon as the user actually leaves: share target opens,
 * home, recents, screen off. iOS never locks for its share sheet either, scene stays active.
 */
internal class ShareSheetLockDeferral {
    private var shareSheetRequested = false
    private var lockDeferred = false

    fun shareSheetRequested() {
        shareSheetRequested = true
    }

    fun shareSheetLaunchFailed() {
        shareSheetRequested = false
    }

    /** True = lock now, like any other pause. */
    fun onPause(): Boolean {
        val defer = shareSheetRequested
        shareSheetRequested = false
        lockDeferred = defer
        return !defer
    }

    /** True = run the lock we skipped in onPause. */
    fun onStop(): Boolean {
        val run = lockDeferred
        lockDeferred = false
        return run
    }

    /** Sheet closed w/o leaving the app, nothing left to lock. */
    fun onResume() {
        shareSheetRequested = false
        lockDeferred = false
    }
}

/** Activity that knows its about to cover itself with its own share sheet. */
internal interface OwnShareSheetHost {
    fun willShowOwnShareSheet()
    fun ownShareSheetLaunchFailed()
}

/** Start a chooser we built ourselves. Anything else should keep using startActivity. */
internal fun Context.startOwnShareSheet(chooser: Intent) {
    val host = findActivity() as? OwnShareSheetHost
    host?.willShowOwnShareSheet()
    try {
        startActivity(chooser)
    } catch (e: RuntimeException) {
        host?.ownShareSheetLaunchFailed()
        throw e
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
