package com.tacmap.map

import com.tacmap.waypoints.MilitarySymbolSpec
import com.tacmap.waypoints.ReinforcementStatus
import com.tacmap.waypoints.TacticalControlMeasure
import com.tacmap.waypoints.TaskColor
import com.tacmap.waypoints.WaypointKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The new-symbol builder saves what iOS `WaypointCreationSheet.save` saves. */
class NewSymbolDraftTest {

    private fun draft(
        kind: WaypointKind,
        name: String = "",
        notes: String = "",
        elevationText: String = "",
        rotation: Double = 0.0,
        scaleX: Double = 1.0,
        scaleY: Double = 1.0,
        higherFormation: String = "",
        uniqueIdentifier: String = "",
        reinforcementStatus: ReinforcementStatus = ReinforcementStatus.NONE,
    ) = newSymbolDraft(
        name = name,
        kind = kind,
        notes = notes,
        elevationText = elevationText,
        rotation = rotation,
        scaleX = scaleX,
        scaleY = scaleY,
        higherFormation = higherFormation,
        uniqueIdentifier = uniqueIdentifier,
        reinforcementStatus = reinforcementStatus,
    )

    @Test fun blankFieldsFallBackLikeIos() {
        val point = draft(WaypointKind.Generic, name = "   ", notes = "  ")!!
        assertEquals(WaypointKind.Generic.displayName, point.name)
        assertNull(point.notes)
        assertNull(point.elevationMetres)
    }

    @Test fun notesAndElevationAreTrimmedAndParsed() {
        val point = draft(WaypointKind.Generic, name = " OP 1 ", notes = " Dead ground to the north ", elevationText = " 214 ")!!
        assertEquals("OP 1", point.name)
        assertEquals("Dead ground to the north", point.notes)
        assertEquals(214.0, point.elevationMetres!!, 0.0)
        assertEquals(-12.0, draft(WaypointKind.Generic, elevationText = "-12")!!.elevationMetres!!, 0.0)
    }

    @Test fun invalidElevationIsRejected() {
        assertNull(draft(WaypointKind.Generic, elevationText = "high"))
    }

    @Test fun tasksKeepRotationAndScaleWithinTheEditorRange() {
        val task = draft(
            WaypointKind.ControlMeasure(TacticalControlMeasure.AMBUSH),
            rotation = 450.0,
            scaleX = 25.0,
            scaleY = 0.01,
        )!!
        assertEquals(90.0, task.rotation, 0.0)
        assertEquals(MAX_SYMBOL_SCALE, task.scaleX, 0.0)
        assertEquals(MIN_SYMBOL_SCALE, task.scaleY, 0.0)
    }

    @Test fun otherKindsDropTaskTransformsAndUnitsKeepAmplifiers() {
        val unit = draft(
            WaypointKind.Military(MilitarySymbolSpec()),
            rotation = 45.0,
            scaleX = 3.0,
            scaleY = 3.0,
            higherFormation = " 1 RAR ",
            uniqueIdentifier = "A Coy",
            reinforcementStatus = ReinforcementStatus.REINFORCED,
        )!!
        assertEquals(0.0, unit.rotation, 0.0)
        assertEquals(1.0, unit.scaleX, 0.0)
        assertEquals(1.0, unit.scaleY, 0.0)
        assertEquals("1 RAR", unit.higherFormation)
        assertEquals("A Coy", unit.uniqueIdentifier)
        assertEquals(ReinforcementStatus.REINFORCED, unit.reinforcementStatus)

        val task = draft(WaypointKind.ControlMeasure(), higherFormation = "1 RAR")!!
        assertNull(task.higherFormation)
        assertEquals(ReinforcementStatus.NONE, task.reinforcementStatus)
    }

    @Test fun waypointCarriesEveryFieldToTheModel() {
        val kind = WaypointKind.ControlMeasure(TacticalControlMeasure.SUPPORT_BY_FIRE)
        val waypoint = draft(
            kind,
            name = "SBF 2",
            notes = "Gun line",
            elevationText = "88",
            rotation = 270.0,
            scaleX = 2.5,
            scaleY = 1.5,
        )!!.toWaypoint(latitude = -35.28, longitude = 149.13, layerId = "ops")
        assertEquals("SBF 2", waypoint.name)
        assertEquals("Gun line", waypoint.notes)
        assertEquals(88.0, waypoint.elevationMetres!!, 0.0)
        assertEquals(kind, waypoint.kind)
        assertEquals(270.0, waypoint.rotation, 0.0)
        assertEquals(2.5, waypoint.scaleX, 0.0)
        assertEquals(1.5, waypoint.scaleY, 0.0)
        assertEquals(TaskColor.BLACK, waypoint.taskColor)
        assertEquals(-35.28, waypoint.latitude, 0.0)
        assertEquals(149.13, waypoint.longitude, 0.0)
        assertEquals("ops", waypoint.layerId)
    }
}
