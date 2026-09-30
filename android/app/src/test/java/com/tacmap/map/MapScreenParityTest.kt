package com.tacmap.map

import com.tacmap.mgrs.MgrsFormatter
import com.tacmap.models.TrackRecordingPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Recording-pill taps and the fiduciary GPS fill, brought in line with iOS. */
class MapScreenParityTest {
    @Test
    fun recordingPillTapMatchesIosExceptWhileStarting() {
        assertEquals(RecordingPillAction.STOP, recordingPillAction(TrackRecordingPhase.Recording))
        assertEquals(RecordingPillAction.DISMISS, recordingPillAction(TrackRecordingPhase.AwaitingPermission))
        assertEquals(RecordingPillAction.DISMISS, recordingPillAction(TrackRecordingPhase.Interrupted))
        assertEquals(RecordingPillAction.NONE, recordingPillAction(TrackRecordingPhase.Starting))
        assertEquals(RecordingPillAction.NONE, recordingPillAction(TrackRecordingPhase.Idle))
    }

    @Test
    fun currentLocationFillsAnMgrsTheFiduciaryParserAccepts() {
        val mgrs = calibrationMgrsForFix(-35.2809, 149.1300)
        assertNotNull(mgrs)
        val (lat, lng) = MgrsFormatter.parse(mgrs!!)!!
        assertEquals(-35.2809, lat, 1e-4)
        assertEquals(149.1300, lng, 1e-4)
        // No MGRS beyond the UTM band limits, so the button stays disabled.
        assertNull(calibrationMgrsForFix(85.0, 10.0))
        assertNull(calibrationMgrsForFix(-81.0, 10.0))
    }
}
