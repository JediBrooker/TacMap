package com.tacmap.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** R2-S3: the writer reads back what sqlite did with journal_mode, OFF then MEMORY, else it gives up */
class MBTilesJournalModeTest {
    private fun run(answers: Map<String, String?>): Pair<Result<String>, List<String>> {
        val asked = ArrayList<String>()
        val r = runCatching { applyNoDiskJournal { mode -> asked += mode; answers[mode] } }
        return r to asked
    }

    @Test
    fun offWhenSqliteTakesIt() {
        val (r, asked) = run(mapOf("off" to "off"))
        assertEquals("off", r.getOrThrow())
        assertEquals(listOf("off"), asked)
    }

    @Test
    fun memoryWhenOffIsIgnored() {
        // defensive mode sqlite says the old mode back, case doesn't matter
        val (r, asked) = run(mapOf("off" to "delete", "memory" to "MEMORY"))
        assertEquals("memory", r.getOrThrow())
        assertEquals(listOf("off", "memory"), asked)
    }

    @Test
    fun failsClosedWhenNeitherTook() {
        for (answers in listOf(
            mapOf("off" to "truncate", "memory" to "truncate"),
            mapOf("off" to "wal", "memory" to "wal"),
            mapOf<String, String?>("off" to null, "memory" to null),
        )) {
            val (r, asked) = run(answers)
            assertTrue("$answers should fail the writer", r.isFailure)
            assertEquals(listOf("off", "memory"), asked)
        }
        // and that's a plain write failure for the bake, not noSpace
        val e = run(mapOf("off" to "delete", "memory" to "delete")).first.exceptionOrNull()
        assertEquals(PdfBakeError.WRITE_FAILED, PdfBakeErrors.classifyWrite(e))
    }
}
