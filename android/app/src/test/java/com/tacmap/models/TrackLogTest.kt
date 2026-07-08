package com.tacmap.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TrackLogTest {

    private fun tempFile(): File = File(Files.createTempDirectory("tracklog").toFile(), "recording.ndjson")

    private fun pt(i: Int) = TrackPoint(
        latitude = 50.0 + i * 0.001,
        longitude = -1.0 - i * 0.001,
        elevationMetres = if (i % 2 == 0) 100.0 + i else null,
        timeEpochMs = 1_700_000_000_000L + i * 1000
    )

    @Test fun appendThenReadRecoversEveryPoint() {
        val f = tempFile()
        val points = (0 until 25).map { pt(it) }
        points.forEach { TrackLog.append(f, it) }
        // Simulate a fresh process reading the log after a crash.
        val recovered = TrackLog.read(f)
        assertEquals(points, recovered)
    }

    @Test fun readMissingFileIsEmpty() {
        assertTrue(TrackLog.read(File("/nonexistent/does/not/exist.ndjson")).isEmpty())
    }

    @Test fun truncateStartsFresh() {
        val f = tempFile()
        (0 until 5).forEach { TrackLog.append(f, pt(it)) }
        TrackLog.truncate(f)
        assertTrue(TrackLog.read(f).isEmpty())
        TrackLog.append(f, pt(99))
        assertEquals(listOf(pt(99)), TrackLog.read(f))
    }

    @Test fun trailingGarbageLineIsSkippedNotThrown() {
        val f = tempFile()
        TrackLog.append(f, pt(1))
        // Simulate a torn final line from an interrupted append.
        f.appendText("{\"latitude\":50.0,\"longitude\":") // incomplete JSON, no newline
        val recovered = TrackLog.read(f)
        assertEquals(listOf(pt(1)), recovered)
    }
}
