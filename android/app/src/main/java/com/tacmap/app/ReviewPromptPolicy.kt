package com.tacmap.app

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * When to ask for a Play Store review. Pure rules, no Play Core in here so it
 * unit tests on the JVM. iOS mirrors this in ReviewPrompt.swift so both
 * platforms ask under the same conditions.
 *
 * The rule: the user has had at least two different kinds of win (a map landed
 * in the library, an offline bake finished, a mission export went out through
 * the share sheet) AND has opened the app on three separate days. Then we ask,
 * once per install, and only on a quiet moment back on the main map. Whether
 * Play actually shows the card is its business, the API never tells us.
 */
class ReviewPromptPolicy(
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    enum class Event(val key: String) {
        MAP_IMPORTED("mapImported"),
        OFFLINE_BAKE_COMPLETED("offlineBakeCompleted"),
        MISSION_EXPORTED("missionExported");

        companion object {
            /** null for a key from some build we dont know about yet */
            fun fromKey(key: String): Event? = entries.firstOrNull { it.key == key }
        }
    }

    data class State(
        val events: Set<Event> = emptySet(),
        /** "yyyy-MM-dd" in the device zone, a session over midnight counts as two days. Close enough */
        val launchDays: Set<String> = emptySet(),
        val asked: Boolean = false,
    )

    fun dayKey(epochMs: Long): String =
        DAY.format(Instant.ofEpochMilli(epochMs).atZone(zone()))

    fun recordingLaunch(state: State): State =
        state.copy(launchDays = state.launchDays + dayKey(nowMs()))

    fun recording(event: Event, state: State): State =
        state.copy(events = state.events + event)

    fun shouldPrompt(state: State): Boolean =
        !state.asked &&
            state.events.size >= REQUIRED_DISTINCT_EVENTS &&
            state.launchDays.size >= REQUIRED_LAUNCH_DAYS

    fun markingAsked(state: State): State = state.copy(asked = true)

    companion object {
        const val REQUIRED_DISTINCT_EVENTS = 2
        const val REQUIRED_LAUNCH_DAYS = 3
        private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }
}

/** Where the policy state lives. SharedPreferences in the app, a var in tests. */
interface ReviewPromptStore {
    fun load(): ReviewPromptPolicy.State
    fun save(state: ReviewPromptPolicy.State)
}

class SharedPreferencesReviewPromptStore(private val prefs: SharedPreferences) : ReviewPromptStore {
    override fun load(): ReviewPromptPolicy.State = ReviewPromptPolicy.State(
        events = (prefs.getStringSet(KEY_EVENTS, null) ?: emptySet())
            .mapNotNull(ReviewPromptPolicy.Event::fromKey)
            .toSet(),
        launchDays = prefs.getStringSet(KEY_LAUNCH_DAYS, null)?.toSet() ?: emptySet(),
        asked = prefs.getBoolean(KEY_ASKED, false),
    )

    override fun save(state: ReviewPromptPolicy.State) {
        // fresh sets on purpose, getStringSet hands back its own instance and
        // mutating that is the classic SharedPreferences footgun
        prefs.edit()
            .putStringSet(KEY_EVENTS, state.events.map { it.key }.toSet())
            .putStringSet(KEY_LAUNCH_DAYS, state.launchDays.toSet())
            .putBoolean(KEY_ASKED, state.asked)
            .apply()
    }

    companion object {
        const val PREFS = "review_prompt"
        const val KEY_EVENTS = "events"
        const val KEY_LAUNCH_DAYS = "launch_days"
        const val KEY_ASKED = "asked"

        fun open(context: Context) = SharedPreferencesReviewPromptStore(
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        )
    }
}

/**
 * Glue between the places that know a win happened (import commit, bake
 * manager, export share) and the one screen allowed to ask (MapScreen).
 * Lives on the Application so it outlives the Activity. Main thread only.
 */
class ReviewPromptCoordinator(
    private val store: ReviewPromptStore,
    private val policy: ReviewPromptPolicy = ReviewPromptPolicy(),
) {
    private var state = store.load()
    private var suppressed = false
    private val _eligible = MutableStateFlow(false)

    /** true once the rule is met and nobody has asked yet. MapScreen watches
     * this and waits for a quiet moment before calling [takeRequest]. */
    val eligible: StateFlow<Boolean> = _eligible.asStateFlow()

    init { refresh() }

    /** Scripted runs (debug launch hooks, instrumented tests) never get the
     * card, it would land on top of whatever the test is looking at. Sticky
     * for the process. */
    fun suppress() {
        suppressed = true
        refresh()
    }

    /** Call whenever the user actually reaches the map (launch, foreground).
     * Same day twice is a no-op thanks to the set. */
    fun noteLaunch() = update(policy.recordingLaunch(state))

    fun record(event: ReviewPromptPolicy.Event) = update(policy.recording(event, state))

    /** The screen is about to ask. Flips the once-per-install flag before the
     * flow launches so a crash or a silently swallowed card never retries.
     * false when there is nothing to ask for. */
    fun takeRequest(): Boolean {
        if (!_eligible.value) return false
        update(policy.markingAsked(state))
        return true
    }

    private fun update(next: ReviewPromptPolicy.State) {
        if (next == state) return
        state = next
        store.save(next)
        refresh()
    }

    private fun refresh() {
        _eligible.value = !suppressed && policy.shouldPrompt(state)
    }

    companion object {
        fun from(context: Context): ReviewPromptCoordinator =
            (context.applicationContext as TacticalApp).reviewPrompt
    }
}
