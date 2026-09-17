package com.tacmap.models

import com.tacmap.util.DataKey
import com.tacmap.util.SafeStore
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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

    private fun beginRecording(recorder: TrackRecorder) {
        assertTrue(recorder.requestStart(LocationAccess.Precise, gpsEnabled = true))
        assertTrue(recorder.prepareStart())
        assertFalse("REC waits for foreground-service activation", recorder.isRecording.value)
        val generation = requireNotNull(recorder.preparedServiceGeneration())
        assertTrue(recorder.onServiceActivated(generation))
        assertTrue(recorder.isRecording.value)
    }

    @Test fun duplicateStartIsIgnoredWhileStartingAndRecording() {
        val file = File(Files.createTempDirectory("recorder-idempotent").toFile(), "recording.ndjson")
        val scheduledTimeouts = mutableListOf<() -> Unit>()
        val recorder = TrackRecorder(
            logFile = file,
            recordingKeyProvider = { key.copyOf() },
            scheduleActivationTimeout = { _, action -> scheduledTimeouts += action },
        )

        assertTrue(recorder.requestStart(LocationAccess.Precise, gpsEnabled = true))
        assertTrue(recorder.prepareStart())
        val generation = requireNotNull(recorder.preparedServiceGeneration())
        assertFalse("a second Activity start must be a no-op", recorder.requestStart(LocationAccess.Precise, true))
        assertFalse("durable preparation must not run twice", recorder.prepareStart())
        assertEquals(1, scheduledTimeouts.size)
        assertEquals(generation, recorder.preparedServiceGeneration())

        assertTrue(recorder.onServiceActivated(generation))
        assertFalse("an active recording must ignore another start", recorder.requestStart(LocationAccess.Precise, true))
        assertFalse(recorder.prepareStart())
        assertTrue(recorder.isRecording.value)
    }

    @Test fun unexpectedServiceDestroyInterruptsAndClearsKeyWithoutRecursiveStop() {
        val file = File(Files.createTempDirectory("recorder-destroyed").toFile(), "recording.ndjson")
        var stopRequests = 0
        val recorder = TrackRecorder(
            logFile = file,
            recordingKeyProvider = { key.copyOf() },
            stopRecordingService = { stopRequests += 1 },
        )
        assertTrue(recorder.requestStart(LocationAccess.Precise, true))
        assertTrue(recorder.prepareStart())
        val generation = requireNotNull(recorder.preparedServiceGeneration())
        assertTrue(recorder.onServiceActivated(generation))

        recorder.onServiceDestroyed(generation)

        assertEquals(TrackRecordingPhase.Interrupted, recorder.uiState.value.phase)
        assertFalse(recorder.isRecording.value)
        assertFalse(recorder.hasRetainedRecordingKey())
        assertEquals("onDestroy must not call stopService recursively", 0, stopRequests)
    }

    @Test fun activationTimeoutIsGenerationBoundAndExpectedDestroyIsIgnored() {
        val file = File(Files.createTempDirectory("recorder-timeout").toFile(), "recording.ndjson")
        val scheduledTimeouts = mutableListOf<() -> Unit>()
        var stopRequests = 0
        val recorder = TrackRecorder(
            logFile = file,
            recordingKeyProvider = { key.copyOf() },
            stopRecordingService = { stopRequests += 1 },
            scheduleActivationTimeout = { _, action -> scheduledTimeouts += action },
        )
        assertTrue(recorder.requestStart(LocationAccess.Precise, true))
        assertTrue(recorder.prepareStart())
        val firstGeneration = requireNotNull(recorder.preparedServiceGeneration())
        assertTrue(recorder.onServiceActivated(firstGeneration))
        scheduledTimeouts.single().invoke()
        assertTrue("activated sessions ignore their stale watchdog", recorder.isRecording.value)
        assertEquals(0, stopRequests)

        recorder.stop()
        recorder.onServiceDestroyed(firstGeneration)
        assertEquals(TrackRecordingPhase.Idle, recorder.uiState.value.phase)
        assertEquals(0, stopRequests)

        assertTrue(recorder.discard())
        assertTrue(recorder.requestStart(LocationAccess.Precise, true))
        assertTrue(recorder.prepareStart())
        assertEquals(2, scheduledTimeouts.size)
        scheduledTimeouts.last().invoke()

        assertEquals(TrackRecordingPhase.Interrupted, recorder.uiState.value.phase)
        assertFalse(recorder.hasRetainedRecordingKey())
        assertEquals(1, stopRequests)
    }

    @Test fun stoppedTrackBlocksRestartUntilExplicitDiscardThenSecondStartSucceeds() {
        val file = File(Files.createTempDirectory("recorder-lifecycle").toFile(), "recording.ndjson")
        val recorder = TrackRecorder(file)
        beginRecording(recorder)
        recorder.recordPoint(TrackPoint(-33.86, 151.21, 10.0, 1_700_000_000_000L))
        recorder.stop()

        assertTrue(recorder.requestStart(LocationAccess.Precise, gpsEnabled = true))
        assertFalse("saved points must not be truncated by a new start", recorder.prepareStart())
        assertTrue(file.exists())
        assertTrue(recorder.points.value.isNotEmpty())

        assertTrue(recorder.discard())
        assertFalse("discard removes the encrypted log", file.exists())
        assertTrue(recorder.points.value.isEmpty())
        assertTrue(recorder.requestStart(LocationAccess.Precise, gpsEnabled = true))
        assertTrue("a new recording can begin after explicit discard", recorder.prepareStart())
    }

    @Test fun activeTrackCannotBeDiscardedWithoutStop() {
        val file = File(Files.createTempDirectory("recorder-active").toFile(), "recording.ndjson")
        val recorder = TrackRecorder(file)
        beginRecording(recorder)
        recorder.recordPoint(TrackPoint(-33.86, 151.21, null, 1_700_000_000_000L))

        assertFalse(recorder.discard())
        assertEquals(
            com.tacmap.localization.Messages.trackStopBeforeDiscardMessage(),
            recorder.persistError.value,
        )
        recorder.acknowledgePersistError()
        assertEquals(null, recorder.persistError.value)
        assertTrue(recorder.isRecording.value)
        assertTrue(file.exists())
        assertTrue(recorder.points.value.isNotEmpty())
    }

    @Test fun missionKeyLockKeepsAuthorizedSessionAndAppendNeverReacquiresGlobalKey() {
        val file = File(Files.createTempDirectory("recorder-background").toFile(), "recording.ndjson")
        val recorder = TrackRecorder(file) { key.copyOf() }
        beginRecording(recorder)

        SafeStore.keyProvider = SafeStore.KeyProvider {
            throw AssertionError("global key must stay locked during background append")
        }
        recorder.onMissionKeyLock()
        recorder.recordPoint(TrackPoint(-33.86, 151.21, null, 1_700_000_000_000L))

        assertTrue(recorder.isRecording.value)
        assertEquals(1, recorder.points.value.size)
        assertTrue(recorder.hasRetainedRecordingKey())
    }

    @Test fun retainedKeyClearsOnStopFailureDiscardAndPermissionLoss() {
        fun prepared(name: String) = TrackRecorder(
            File(Files.createTempDirectory(name).toFile(), "recording.ndjson")
        ) { key.copyOf() }

        val stopped = prepared("recorder-stop")
        beginRecording(stopped)
        stopped.stop()
        assertFalse(stopped.hasRetainedRecordingKey())

        val failed = prepared("recorder-fail")
        beginRecording(failed)
        val failure = com.tacmap.localization.Messages.recordingStartFailedMessage("100% {1}")
        failed.failRecording(failure, TrackRecordingSettingsTarget.AppPermissions)
        org.junit.Assert.assertSame(failure, failed.persistError.value)
        org.junit.Assert.assertSame(failure, failed.uiState.value.pendingMessage)
        assertEquals(TrackRecordingSettingsTarget.AppPermissions, failed.uiState.value.settingsTarget)
        assertFalse(failed.hasRetainedRecordingKey())

        val discarded = prepared("recorder-discard")
        beginRecording(discarded)
        discarded.stop()
        assertTrue(discarded.discard())
        assertFalse(discarded.hasRetainedRecordingKey())

        val revoked = prepared("recorder-revoked")
        beginRecording(revoked)
        revoked.onLocationAccessChanged(LocationAccess.ApproximateOnly)
        assertFalse(revoked.hasRetainedRecordingKey())
        assertFalse(revoked.isRecording.value)
    }

    @Test fun keyIsNotCapturedWhenDurableStartPreparationIsRejected() {
        val file = File(Files.createTempDirectory("recorder-no-key").toFile(), "recording.ndjson")
        TrackLog.truncate(file)
        TrackLog.append(
            file,
            TrackPoint(-33.86, 151.21, null, 1_700_000_000_000L),
            key,
        )
        var keyRequests = 0
        val recorder = TrackRecorder(file) {
            keyRequests += 1
            key.copyOf()
        }

        assertTrue(recorder.requestStart(LocationAccess.Precise, gpsEnabled = true))
        assertFalse(recorder.prepareStart())
        assertEquals(0, keyRequests)
        assertFalse(recorder.hasRetainedRecordingKey())
    }
}
