package com.tacmap.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DurablePreferenceCommitTest {
    @Test
    fun candidateThatMutatesMemoryThenFailsIsRolledBackBeforeLaterFlush() {
        val memory = linkedMapOf<String, Any>("online" to false, "unrelated" to 1)
        var disk = memory.toMap()
        var published = false

        val committed = DurablePreferenceCommit.publishAfter(
            capture = { memory.toMap() },
            commit = {
                // Mirrors SharedPreferences: commitToMemory happens before the
                // disk write reports failure.
                memory["online"] = true
                false
            },
            rollback = { before ->
                memory.clear()
                memory.putAll(before)
            },
            publish = { published = true },
        )

        assertFalse(committed)
        assertFalse(published)
        assertEquals(false, memory["online"])
        assertEquals(false, disk["online"])

        // A later unrelated successful edit flushes the whole in-memory map.
        // The rejected ENABLE must not hitchhike into the simulated restart.
        memory["unrelated"] = 2
        disk = memory.toMap()
        assertEquals(false, disk["online"])
        assertEquals(2, disk["unrelated"])
    }

    @Test
    fun successfulCommitPublishesOnlyAfterDurableValueChanges() {
        var memoryValue = true
        var diskValue = true
        var observedDiskAtPublish = true
        var publishedValue = true

        val committed = DurablePreferenceCommit.publishAfter(
            capture = { memoryValue to diskValue },
            commit = {
                memoryValue = false
                diskValue = false
                true
            },
            rollback = { (memory, disk) ->
                memoryValue = memory
                diskValue = disk
            },
            publish = {
                observedDiskAtPublish = diskValue
                publishedValue = false
            },
        )

        assertTrue(committed)
        assertFalse(observedDiskAtPublish)
        assertFalse(publishedValue)
    }

    @Test
    fun rollbackRestoresEveryRemovedLegacyEntryExactly() {
        val memory = linkedMapOf<String, Any>(
            "sealed" to "old-envelope",
            "callsign" to "11A",
            "shareLocation" to true,
            "isHQ" to false,
        )
        val before = memory.toMap()

        val committed = DurablePreferenceCommit.publishAfter(
            capture = { memory.toMap() },
            commit = {
                memory["sealed"] = "candidate-envelope"
                memory.remove("callsign")
                memory.remove("shareLocation")
                memory.remove("isHQ")
                false
            },
            rollback = { snapshot ->
                memory.clear()
                memory.putAll(snapshot)
            },
            publish = { error("failed commit must not publish") },
        )

        assertFalse(committed)
        assertEquals(before, memory)
    }
}
