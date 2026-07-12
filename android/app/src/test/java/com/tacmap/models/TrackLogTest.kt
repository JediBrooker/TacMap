package com.tacmap.models

import com.tacmap.util.DataKey
import com.tacmap.util.SafeStore
import com.tacmap.util.SealedEnvelope
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TrackLogTest {

    private val testKey = ByteArray(32) { it.toByte() }
    private val sealedLabels = mutableSetOf<String>()

    @Before fun installTestKey() {
        SafeStore.keyProvider = SafeStore.KeyProvider { testKey }
        SafeStore.migrationPolicy = object : SafeStore.MigrationPolicy {
            override fun isSealedOnly(label: String) = label in sealedLabels
            override fun markSealedOnly(label: String) { sealedLabels += label }
        }
    }

    @After fun restoreKeyProvider() {
        SafeStore.keyProvider = SafeStore.KeyProvider { DataKey.key() }
        SafeStore.migrationPolicy = object : SafeStore.MigrationPolicy {
            override fun isSealedOnly(label: String) = DataKey.isStoreSealedOnly(label)
            override fun markSealedOnly(label: String) = DataKey.markStoreSealedOnly(label)
        }
    }

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
        assertEquals(points, recovered.points)
        assertFalse(recovered.hadLegacyLines)
    }

    @Test fun everyLineOnDiskIsSealed() {
        val f = tempFile()
        (0 until 3).forEach { TrackLog.append(f, it.let(::pt)) }
        val lines = f.readLines().filter { it.isNotBlank() }
        assertEquals(3, lines.size)
        assertTrue("every line sealed", lines.all { SealedEnvelope.isSealedLine(it) })
        assertFalse("no coordinate in the clear", f.readText().contains("50.0"))
    }

    @Test fun readMissingFileIsEmpty() {
        assertTrue(TrackLog.read(File("/nonexistent/does/not/exist.ndjson")).points.isEmpty())
    }

    @Test fun truncateStartsFresh() {
        val f = tempFile()
        (0 until 5).forEach { TrackLog.append(f, pt(it)) }
        TrackLog.truncate(f)
        assertTrue(TrackLog.read(f).points.isEmpty())
        TrackLog.append(f, pt(99))
        assertEquals(listOf(pt(99)), TrackLog.read(f).points)
    }

    @Test fun tornFinalLineIsSkippedAndEarlierPointsSurvive() {
        val f = tempFile()
        TrackLog.append(f, pt(1))
        TrackLog.append(f, pt(2))
        // Interrupted append: half a sealed line, no newline.
        f.appendText("v1:DAwMDAwMDAwMDAwMj5F3mCpw")
        val recovered = TrackLog.read(f)
        assertEquals("one bad line costs one fix, not the track", listOf(pt(1), pt(2)), recovered.points)
    }

    @Test fun corruptedMiddleLineDoesNotInvalidateTheTail() {
        // This is why we don't bind the line index into the AAD. Flip a byte in
        // line 2 and lines 3..5 must still open.
        val f = tempFile()
        (1..5).forEach { TrackLog.append(f, pt(it)) }
        val lines = f.readLines().toMutableList()
        lines[1] = lines[1].dropLast(4) + "AAAA"
        f.writeText(lines.joinToString("\n") + "\n")

        val recovered = TrackLog.read(f)
        assertEquals(listOf(pt(1), pt(3), pt(4), pt(5)), recovered.points)
    }

    @Test fun legacyPlaintextLogIsReadAndFlaggedForReseal() {
        val f = tempFile()
        f.parentFile.mkdirs()
        val json = Json { ignoreUnknownKeys = true }
        val legacy = (1..3).map { pt(it) }
        f.writeText(legacy.joinToString("\n") { json.encodeToString(TrackPoint.serializer(), it) } + "\n")

        val recovered = TrackLog.read(f)
        assertEquals(legacy, recovered.points)
        assertTrue("caller must know to re-seal", recovered.hadLegacyLines)

        TrackLog.reseal(f, recovered.points)
        val after = TrackLog.read(f)
        assertEquals(legacy, after.points)
        assertFalse(after.hadLegacyLines)
        assertTrue(f.readLines().filter { it.isNotBlank() }.all { SealedEnvelope.isSealedLine(it) })
    }

    @Test fun plaintextDowngradeIsRejectedAfterReseal() {
        val f = tempFile()
        val json = Json { ignoreUnknownKeys = true }
        f.writeText(json.encodeToString(TrackPoint.serializer(), pt(1)) + "\n")
        val recovered = TrackLog.read(f)
        TrackLog.reseal(f, recovered.points)
        f.writeText(json.encodeToString(TrackPoint.serializer(), pt(2)) + "\n")
        val failure = runCatching { TrackLog.read(f) }.exceptionOrNull()
        assertTrue(failure is java.io.IOException)
    }

    @Test fun lineFromAnotherStoreDoesNotOpen() {
        val f = tempFile()
        f.parentFile.mkdirs()
        // A sealed line whose AAD says it belongs to waypoints, dropped into the track log.
        val alien = SealedEnvelope.sealLine(testKey, """{"latitude":1.0}""".toByteArray(), "waypoints.json")
        f.writeText(alien + "\n")
        assertTrue(TrackLog.read(f).points.isEmpty())
    }
}
