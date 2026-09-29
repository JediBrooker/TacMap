package com.tacmap.map

import android.content.Context
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import com.tacmap.localization.Messages

/**
 * The guided map tour: shown once after the first unlock and replayable from
 * About or Settings. Each step highlights one real map control. Bump
 * [CURRENT_VERSION] when the tour changes enough to show again. iOS mirrors
 * this in `FirstRunTips.swift`.
 */
object FirstRunTips {
    const val CURRENT_VERSION = 2
    private const val PREFS = "first_run_tips"
    private const val KEY_SEEN_VERSION = "seen_version"

    /** [target] is the control to highlight; null shows the card in the middle. */
    class Step(val target: TourTarget?, val title: String, val body: String)

    val steps: List<Step>
        get() = listOf(
            Step(TourTarget.CROSSHAIR, Messages.tourWelcomeTitle(), Messages.tourWelcomeBody()),
            Step(TourTarget.HEADER, Messages.tourHeaderTitle(), Messages.tourHeaderBody()),
            Step(TourTarget.ADD, Messages.tourAddTitle(), Messages.tourAddBody()),
            Step(TourTarget.MAP_HOLD, Messages.tourHoldTitle(), Messages.tourHoldBody()),
            Step(TourTarget.MENU, Messages.tourMenuTitle(), Messages.tourMenuBody()),
            Step(TourTarget.LABELS, Messages.tourLabelsTitle(), Messages.tourLabelsBody()),
            Step(TourTarget.NIGHT, Messages.tourNightTitle(), Messages.tourNightBody()),
            Step(TourTarget.COMPASS, Messages.tourCompassTitle(), Messages.tourCompassBody()),
            Step(TourTarget.LOCK, Messages.tourLockTitle(), Messages.tourLockBody()),
            Step(null, Messages.tourEditTitle(), Messages.tourEditBody()),
        )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun shouldShow(context: Context): Boolean =
        prefs(context).getInt(KEY_SEEN_VERSION, 0) < CURRENT_VERSION

    /** Also used by instrumented tests before launching MainActivity, so the
     * tour never covers the map they drive. */
    fun markSeen(context: Context) {
        prefs(context).edit().putInt(KEY_SEEN_VERSION, CURRENT_VERSION).apply()
    }

    fun reset(context: Context) {
        prefs(context).edit().remove(KEY_SEEN_VERSION).apply()
    }
}

/** Map controls the tour can point at. [CROSSHAIR] and [MAP_HOLD] are places
 * on the map rather than controls, so nothing is tagged for them. */
enum class TourTarget { CROSSHAIR, HEADER, ADD, MAP_HOLD, MENU, LABELS, NIGHT, COMPASS, LOCK }

/** Where each tagged control is, in root coordinates, for the tour overlay. */
@Stable
class TourTargets {
    val bounds = mutableStateMapOf<TourTarget, Rect>()
}

/** Marks this node as the control a tour step highlights. A control that
 * leaves the screen (the + button while graphics are locked) drops out, and
 * its step shows the card without a spotlight. */
fun Modifier.tourTarget(targets: TourTargets, target: TourTarget): Modifier = composed {
    DisposableEffect(targets, target) {
        onDispose { targets.bounds.remove(target) }
    }
    onGloballyPositioned { coordinates ->
        val rect = coordinates.boundsInRoot()
        if (targets.bounds[target] != rect) targets.bounds[target] = rect
    }
}
