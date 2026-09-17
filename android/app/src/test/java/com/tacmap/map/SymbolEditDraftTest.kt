package com.tacmap.map

import com.tacmap.drawings.DrawingLayer
import com.tacmap.util.MissionStorePersistence
import com.tacmap.waypoints.TaskColor
import com.tacmap.waypoints.HIGHER_FORMATION_MAX_CODE_POINTS
import com.tacmap.waypoints.MilitarySymbolSpec
import com.tacmap.waypoints.ReinforcementStatus
import com.tacmap.waypoints.TacticalControlMeasure
import com.tacmap.waypoints.Waypoint
import com.tacmap.waypoints.WaypointKind
import com.tacmap.waypoints.WaypointStore
import com.tacmap.waypoints.normalizedUnitAmplifier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SymbolEditDraftTest {
    @Test
    fun compactProductionCardOwnsDurableMoveAndLaunchesSelectedSymbolEditor() {
        val card = sourceText("android/app/src/main/java/com/tacmap/map/SymbolControlsCard.kt")
        val compactBody = card.substringBefore("if (showEditor)")
        val screen = sourceText("android/app/src/main/java/com/tacmap/map/MapScreen.kt")

        assertTrue(card.contains("SelectedSymbolEditorDialog("))
        assertTrue(screen.contains("SymbolControlsCard("))
        assertTrue(compactBody.contains("store.update"))
        assertTrue(compactBody.contains("Move to crosshair"))
        assertFalse(compactBody.contains("store.remove"))
        assertFalse(card.contains("LayerSelectorButton("))
        assertFalse(
            sourceText("android/app/src/main/java/com/tacmap/map/SelectedSymbolEditorDialog.kt")
                .contains("Move symbol to crosshair")
        )
        assertFalse(screen.contains("onMovedToCrosshair ="))
    }

    @Test
    fun transformResetControlsHaveExplicitAccessibilityLabels() {
        assertEquals("Reset rotation", resetAccessibilityLabel("Rotation"))
        assertEquals("Reset width", resetAccessibilityLabel("Width scale"))
        assertEquals("Reset height", resetAccessibilityLabel("Height scale"))
    }

    @Test
    fun sharedDraftNormalizationContract() {
        val root = fixture()
        val initial = root.getValue("initialWaypoint").jsonObject
        val waypoint = Waypoint(
            id = initial.str("id"),
            name = initial.str("name"),
            notes = initial.str("notes"),
            elevationMetres = initial.d("elevationMetres"),
            kind = WaypointKind.ControlMeasure(TacticalControlMeasure.AXIS_OF_MAIN_ATTACK),
            layerId = initial.str("layerId"),
            taskColor = TaskColor.RED,
            rotation = initial.d("rotationDegrees"),
            scaleX = initial.d("scaleX"),
            scaleY = initial.d("scaleY"),
            latitude = initial.d("latitude"),
            longitude = initial.d("longitude"),
        )
        val layers = listOf(DrawingLayer(id = initial.str("layerId"), name = "Operations"))
        val base = SymbolEditDraft(waypoint).copy(kindDisplayName = initial.str("kindDisplayName"))
        val cases = root.getValue("draftCases").jsonArray.associate { element ->
            val case = element.jsonObject
            case.str("caseKey") to case
        }

        val trimCase = cases.getValue("trim_and_parse")
        val trimInput = trimCase.getValue("input").jsonObject
        val trimExpected = trimCase.getValue("expected").jsonObject
        val trimmed = valid(
            base.copy(
                name = trimInput.str("name"),
                notes = trimInput.str("notes"),
                elevationText = trimInput.str("elevationText"),
            ).normalized(layers)
        )
        assertEquals(trimExpected.str("name"), trimmed.name)
        assertEquals(trimExpected.str("notes"), trimmed.notes)
        assertEquals(trimExpected.d("elevationMetres"), trimmed.elevationMetres!!, 0.0)

        val blankCase = cases.getValue("blank_optional_fields")
        val blankInput = blankCase.getValue("input").jsonObject
        val blankExpected = blankCase.getValue("expected").jsonObject
        val blank = valid(
            base.copy(
                name = blankInput.str("name"),
                notes = blankInput.str("notes"),
                elevationText = blankInput.str("elevationText"),
                kindDisplayName = blankCase.str("selectedKindDisplayName"),
            ).normalized(layers)
        )
        assertEquals(blankExpected.str("name"), blank.name)
        assertEquals(null, blank.notes)
        assertEquals(null, blank.elevationMetres)

        val invalidCase = cases.getValue("invalid_elevation")
        val invalidInput = invalidCase.getValue("input").jsonObject
        val invalid = base.copy(elevationText = invalidInput.str("elevationText")).normalized(layers)
        assertEquals(invalidCase.str("expectedError"), (invalid as SymbolDraftResult.Invalid).message)
        assertEquals(0, invalidCase.getValue("expectedStoreMutations").jsonPrimitive.content.toInt())

        val boundsCase = cases.getValue("control_bounds")
        val boundsInput = boundsCase.getValue("input").jsonObject
        val boundsExpected = boundsCase.getValue("expected").jsonObject
        val bounded = valid(
            base.copy(
                rotationDegrees = boundsInput.d("rotationDegrees"),
                scaleX = boundsInput.d("scaleX"),
                scaleY = boundsInput.d("scaleY"),
            ).normalized(layers)
        )
        assertEquals(boundsExpected.d("rotationDegrees"), bounded.rotation, 0.0)
        assertEquals(boundsExpected.d("scaleX"), bounded.scaleX, 0.0)
        assertEquals(boundsExpected.d("scaleY"), bounded.scaleY, 0.0)

        val nonControlExpected = cases.getValue("change_to_non_control").getValue("expected").jsonObject
        val reset = base.changingKind(WaypointKind.Generic)
        assertEquals(nonControlExpected.d("rotationDegrees"), reset.rotationDegrees, 0.0)
        assertEquals(nonControlExpected.d("scaleX"), reset.scaleX, 0.0)
        assertEquals(nonControlExpected.d("scaleY"), reset.scaleY, 0.0)
        assertEquals(nonControlExpected.str("taskColor").uppercase(), reset.taskColor.name)

        val fieldOrder = root.getValue("fieldOrder").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(
            listOf("name", "kind", "notes", "elevationMetres", "layerId", "taskColor",
                "higherFormation", "uniqueIdentifier", "reinforcementStatus", "mgrs",
                "rotationDegrees", "scaleX", "scaleY", "delete"),
            fieldOrder,
        )
        val amplifierContract = root.getValue("normalization").jsonObject
            .getValue("unitAmplifiers").jsonObject
        assertEquals(
            HIGHER_FORMATION_MAX_CODE_POINTS,
            amplifierContract.getValue("higherFormationMaximum").jsonPrimitive.content.toInt(),
        )
        val nonBmp = amplifierContract.getValue("nonBmpBoundaryCase").jsonObject
        assertEquals(
            nonBmp.getValue("expectedHigherFormation").jsonPrimitive.content,
            normalizedUnitAmplifier(
                nonBmp.getValue("input").jsonPrimitive.content,
                HIGHER_FORMATION_MAX_CODE_POINTS,
            ),
        )
        assertEquals(48, root.getValue("accessibility").jsonObject.getValue("androidMinimumTargetDp").jsonPrimitive.content.toInt())
    }

    @Test
    fun manyDraftChangesPublishNothingAndSaveWritesExactlyOnce() {
        val persistence = CountingPersistence()
        val store = WaypointStore.forTests(tempDir(), persistence)
        val waypoint = Waypoint(
            id = "symbol",
            name = "Before",
            latitude = -33.8,
            longitude = 151.2,
            kind = WaypointKind.ControlMeasure(),
        )
        assertTrue(store.add(waypoint))
        persistence.writes = 0

        var draft = SymbolEditDraft(waypoint)
        repeat(30) { tick ->
            draft = draft.copy(rotationDegrees = tick.toDouble(), scaleX = 1.0 + tick / 10.0)
        }
        assertEquals(0, persistence.writes)
        val saved = valid(draft.normalized(listOf(DrawingLayer(id = "default", name = "Friendly"))))
        assertTrue(store.update(saved))
        assertEquals(1, persistence.writes)
    }

    @Test
    fun selectedUnitDraftMovesByLocalGridAndNormalizesAmplifiersOnSingleSave() {
        val waypoint = Waypoint(
            id = "unit",
            name = "Alpha",
            latitude = -34.0522,
            longitude = 150.9550,
            kind = WaypointKind.Military(MilitarySymbolSpec()),
        )
        val result = valid(
            SymbolEditDraft(waypoint).copy(
                mgrsInput = "18 85",
                higherFormation = "  ${"X".repeat(HIGHER_FORMATION_MAX_CODE_POINTS + 5)}  ",
                uniqueIdentifier = "  I11  ",
                reinforcementStatus = ReinforcementStatus.REDUCED,
            ).normalized(listOf(DrawingLayer(id = "default", name = "Friendly")))
        )

        assertTrue(result.latitude != waypoint.latitude || result.longitude != waypoint.longitude)
        assertEquals(HIGHER_FORMATION_MAX_CODE_POINTS, result.higherFormation!!.length)
        assertEquals("I11", result.uniqueIdentifier)
        assertEquals(ReinforcementStatus.REDUCED, result.reinforcementStatus)

        val invalid = SymbolEditDraft(waypoint).copy(mgrsInput = "123").normalized(emptyList())
        assertEquals(MGRS_MOVE_VALIDATION_ERROR, (invalid as SymbolDraftResult.Invalid).message)
    }

    @Test
    fun changingAwayFromMilitaryClearsUnitOnlyAmplifiers() {
        val waypoint = Waypoint(
            name = "Alpha",
            latitude = -34.0,
            longitude = 151.0,
            kind = WaypointKind.Military(),
            higherFormation = "CT-A",
            uniqueIdentifier = "I11",
            reinforcementStatus = ReinforcementStatus.REINFORCED,
        )

        val saved = valid(
            SymbolEditDraft(waypoint)
                .changingKind(WaypointKind.ControlMeasure())
                .normalized(emptyList())
        )

        assertEquals(null, saved.higherFormation)
        assertEquals(null, saved.uniqueIdentifier)
        assertEquals(ReinforcementStatus.NONE, saved.reinforcementStatus)
    }

    private fun valid(result: SymbolDraftResult): Waypoint =
        (result as SymbolDraftResult.Valid).waypoint

    private fun fixture() = Json.parseToJsonElement(fixtureText("symbol_edit_contract.json")).jsonObject
    private fun kotlinx.serialization.json.JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
    private fun kotlinx.serialization.json.JsonObject.d(key: String) = getValue(key).jsonPrimitive.double

    private fun fixtureText(name: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            val fixture = File(dir, "testdata/$name")
            if (fixture.exists()) return fixture.readText()
            dir = dir?.parentFile
        }
        error("Could not locate testdata/$name")
    }

    private fun sourceText(relativePath: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            val source = File(dir, relativePath)
            if (source.exists()) return source.readText()
            dir = dir?.parentFile
        }
        error("Could not locate $relativePath")
    }

    private fun tempDir(): File = Files.createTempDirectory("symbol-draft").toFile()

    private class CountingPersistence(var writes: Int = 0) : MissionStorePersistence {
        override fun write(file: File, label: String, text: String) {
            writes++
        }
    }
}
