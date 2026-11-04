package com.tacmap.app

import com.tacmap.app.ReviewPromptPolicy.Event
import com.tacmap.app.ReviewPromptPolicy.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class ReviewPromptPolicyTest {
    private class MemoryStore : ReviewPromptStore {
        var state = State()
        var saves = 0
        override fun load() = state
        override fun save(state: State) { this.state = state; saves++ }
    }

    // 2023-11-14T22:13:20Z
    private var clock = 1_700_000_000_000L
    private val day = 86_400_000L

    private fun policy(zone: String = "UTC") =
        ReviewPromptPolicy(zone = { ZoneId.of(zone) }, nowMs = { clock })

    @Test
    fun freshInstallDoesNotPrompt() {
        assertFalse(policy().shouldPrompt(State()))
    }

    @Test
    fun needsTwoDistinctEventsAndThreeDays() {
        val p = policy()
        var s = State()
        s = p.recordingLaunch(s)
        clock += day; s = p.recordingLaunch(s)
        clock += day; s = p.recordingLaunch(s)
        assertEquals(3, s.launchDays.size)
        assertFalse("three days but no wins yet", p.shouldPrompt(s))

        s = p.recording(Event.MAP_IMPORTED, s)
        s = p.recording(Event.MAP_IMPORTED, s)
        assertFalse("the same win twice is still one kind of win", p.shouldPrompt(s))

        s = p.recording(Event.OFFLINE_BAKE_COMPLETED, s)
        assertTrue(p.shouldPrompt(s))
    }

    @Test
    fun twoEventsOnFewerThanThreeDaysWaits() {
        val p = policy()
        var s = State()
        s = p.recording(Event.MAP_IMPORTED, s)
        s = p.recording(Event.MISSION_EXPORTED, s)
        s = p.recordingLaunch(s)
        clock += day; s = p.recordingLaunch(s)
        assertFalse(p.shouldPrompt(s))
        clock += 5 * day; s = p.recordingLaunch(s)
        assertTrue(p.shouldPrompt(s))
    }

    @Test
    fun sameDayLaunchesCountOnce() {
        val p = policy()
        var s = p.recordingLaunch(State())
        clock += 3_600_000L
        s = p.recordingLaunch(s)
        assertEquals(1, s.launchDays.size)
    }

    @Test
    fun dayKeyFollowsTheDeviceZone() {
        // 22:13 UTC is already the 15th in Sydney
        assertEquals("2023-11-14", policy().dayKey(clock))
        assertEquals("2023-11-15", policy("Australia/Sydney").dayKey(clock))
    }

    @Test
    fun askedOnceNeverAgain() {
        val p = policy()
        var s = State(events = setOf(Event.MAP_IMPORTED, Event.MISSION_EXPORTED), launchDays = setOf("a", "b", "c"))
        assertTrue(p.shouldPrompt(s))
        s = p.markingAsked(s)
        assertFalse(p.shouldPrompt(s))
        s = p.recording(Event.OFFLINE_BAKE_COMPLETED, s)
        clock += 10 * day; s = p.recordingLaunch(s)
        assertFalse("more wins after the ask dont reopen it", p.shouldPrompt(s))
    }

    @Test
    fun unknownEventKeysAreDropped() {
        assertEquals(Event.MAP_IMPORTED, Event.fromKey("mapImported"))
        assertEquals(null, Event.fromKey("somethingFromTheFuture"))
    }

    @Test
    fun coordinatorBecomesEligibleAndTakesTheRequestOnce() {
        val store = MemoryStore()
        val c = ReviewPromptCoordinator(store, policy())
        assertFalse(c.eligible.value)
        assertFalse(c.takeRequest())

        c.noteLaunch()
        clock += day; c.noteLaunch()
        clock += day; c.noteLaunch()
        c.record(Event.MAP_IMPORTED)
        assertFalse(c.eligible.value)
        c.record(Event.MISSION_EXPORTED)
        assertTrue(c.eligible.value)

        assertTrue(c.takeRequest())
        assertFalse(c.eligible.value)
        assertTrue("the once flag hits the store before anything is shown", store.state.asked)
        assertFalse(c.takeRequest())

        // a fresh coordinator over the same store remembers
        assertFalse(ReviewPromptCoordinator(store, policy()).eligible.value)
    }

    @Test
    fun suppressedCoordinatorStillRecordsButNeverAsks() {
        val store = MemoryStore()
        val c = ReviewPromptCoordinator(store, policy())
        c.suppress()
        c.record(Event.MAP_IMPORTED)
        c.record(Event.OFFLINE_BAKE_COMPLETED)
        repeat(3) { c.noteLaunch(); clock += day }
        assertEquals(2, store.state.events.size)
        assertFalse(c.eligible.value)
        assertFalse(c.takeRequest())
    }

    @Test
    fun suppressingAfterEligibilityPullsItBack() {
        val store = MemoryStore()
        store.state = State(events = setOf(Event.MAP_IMPORTED, Event.MISSION_EXPORTED), launchDays = setOf("a", "b", "c"))
        val c = ReviewPromptCoordinator(store, policy())
        assertTrue(c.eligible.value)
        c.suppress()
        assertFalse(c.eligible.value)
    }

    @Test
    fun repeatedNoOpsDontTouchTheStore() {
        val store = MemoryStore()
        val c = ReviewPromptCoordinator(store, policy())
        c.noteLaunch()
        c.noteLaunch()
        c.record(Event.MAP_IMPORTED)
        c.record(Event.MAP_IMPORTED)
        assertEquals(2, store.saves)
    }
}
