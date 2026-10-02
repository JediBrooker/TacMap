package com.tacmap.map

import com.tacmap.models.TrackRecordingPhase
import org.junit.Assert.assertEquals
import org.junit.Test

/** Recording-pill taps, brought in line with iOS. */
class MapScreenParityTest {
    @Test
    fun recordingPillTapMatchesIosExceptWhileStarting() {
        assertEquals(RecordingPillAction.STOP, recordingPillAction(TrackRecordingPhase.Recording))
        assertEquals(RecordingPillAction.DISMISS, recordingPillAction(TrackRecordingPhase.AwaitingPermission))
        assertEquals(RecordingPillAction.DISMISS, recordingPillAction(TrackRecordingPhase.Interrupted))
        assertEquals(RecordingPillAction.NONE, recordingPillAction(TrackRecordingPhase.Starting))
        assertEquals(RecordingPillAction.NONE, recordingPillAction(TrackRecordingPhase.Idle))
    }
}
