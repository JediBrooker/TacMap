package com.tacmap.models

import com.tacmap.util.DataKey
import com.tacmap.util.SafeStore
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TrackRecorderLifecycleTest {
    private val key = ByteArray(32) { (it + 1).toByte() }
    private val sealed = mutableSetOf<String>()

    @Before fun installTestKey() {
        SafeStore.keyProvider = SafeStore.KeyProvider { key }
        SafeStore.migrationPolicy = object : SafeStore.MigrationPolicy {
            override fun isSealedOnly(label: String) = label in sealed
            override fun markSealedOnly(label: String) { sealed += label }
        }
    }

    @After fun restoreKey() {
        SafeStore.keyProvider = SafeStore.KeyProvider { DataKey.key() }
        SafeStore.migrationPolicy = object : SafeStore.MigrationPolicy {
            override fun isSealedOnly(label: String) = DataKey.isStoreSealedOnly(label)
            override fun markSealedOnly(label: String) = DataKey.markStoreSealedOnly(label)
        }
    }

    @Test fun stoppedTrackBlocksRestartUntilExplicitDiscardThenSecondStartSucceeds() {
        val file = File(Files.createTempDirectory("recorder-lifecycle").toFile(), "recording.ndjson")
        val recorder = TrackRecorder(file)
        assertTrue(recorder.start())
        recorder.recordPoint(TrackPoint(-33.86, 151.21, 10.0, 1_700_000_000_000L))
        recorder.stop()

        assertFalse("saved points must not be truncated by a new start", recorder.start())
        assertTrue(file.exists())
        assertTrue(recorder.points.value.isNotEmpty())

        assertTrue(recorder.discard())
        assertFalse("discard removes the encrypted log", file.exists())
        assertTrue(recorder.points.value.isEmpty())
        assertTrue("a new recording can begin after explicit discard", recorder.start())
    }

    @Test fun activeTrackCannotBeDiscardedWithoutStop() {
        val file = File(Files.createTempDirectory("recorder-active").toFile(), "recording.ndjson")
        val recorder = TrackRecorder(file)
        assertTrue(recorder.start())
        recorder.recordPoint(TrackPoint(-33.86, 151.21, null, 1_700_000_000_000L))

        assertFalse(recorder.discard())
        assertTrue(recorder.isRecording.value)
        assertTrue(file.exists())
        assertTrue(recorder.points.value.isNotEmpty())
    }
}
