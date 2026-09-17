package com.tacmap.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawingMutationUiCoordinatorTest {

    @Test
    fun linePolygonAndPointCreationKeepDraftOnFailureThenRetry() {
        listOf("line", "polygon", "point").forEach { surface ->
            val probe = FailureThenSuccess()

            val failed = DrawingMutationUiCoordinator.attempt(DrawingMutationIntent.CREATE, probe::persist)
            assertFalse(surface, failed.saved)
            assertFalse(surface, failed.shouldCloseTransientUi)
            assertTrue((failed as DrawingMutationUiResult.Failed).message.contains("draft is still open"))

            val retried = DrawingMutationUiCoordinator.attempt(DrawingMutationIntent.CREATE, probe::persist)
            assertTrue(surface, retried.saved)
            assertTrue(surface, retried.shouldCloseTransientUi)
            assertEquals(surface, 2, probe.attempts)
        }
    }

    @Test
    fun vertexAndShapeEditsRestoreKnownGoodUiUntilRetry() {
        listOf("vertex move", "vertex insert", "vertex delete", "shape move").forEach { surface ->
            val probe = FailureThenSuccess()

            val failed = DrawingMutationUiCoordinator.attempt(DrawingMutationIntent.EDIT, probe::persist)
            assertFalse(surface, failed.shouldCloseTransientUi)
            assertTrue((failed as DrawingMutationUiResult.Failed).message.contains("previous drawing remains active"))
            assertTrue(DrawingMutationUiCoordinator.attempt(DrawingMutationIntent.EDIT, probe::persist).saved)
        }
    }

    @Test
    fun deleteAndVisibilityKeepSelectionOrSwitchUntilRetry() {
        val delete = FailureThenSuccess()
        val deleteFailure = DrawingMutationUiCoordinator.attempt(
            DrawingMutationIntent.DELETE,
            delete::persist,
        ) as DrawingMutationUiResult.Failed
        assertTrue(deleteFailure.message.contains("remains selected"))
        assertFalse(deleteFailure.shouldCloseTransientUi)
        assertTrue(DrawingMutationUiCoordinator.attempt(DrawingMutationIntent.DELETE, delete::persist).saved)

        val visibility = FailureThenSuccess()
        val visibilityFailure = DrawingMutationUiCoordinator.attempt(
            DrawingMutationIntent.VISIBILITY,
            visibility::persist,
        ) as DrawingMutationUiResult.Failed
        assertTrue(visibilityFailure.message.contains("previous setting remains active"))
        assertFalse(visibilityFailure.shouldCloseTransientUi)
        assertTrue(
            DrawingMutationUiCoordinator.attempt(
                DrawingMutationIntent.VISIBILITY,
                visibility::persist,
            ).saved
        )
    }

    @Test
    fun productionControlCallbacksUseCheckedOutcomeBeforeClosingPickersOrSelection() {
        val controls = sourceText("android/app/src/main/java/com/tacmap/map/MapDrawingControls.kt")
        val selector = sourceText("android/app/src/main/java/com/tacmap/map/LayerSelectorButton.kt")
        val screen = sourceText("android/app/src/main/java/com/tacmap/map/MapScreen.kt")

        assertTrue(controls.contains("onFeatureChange: (DrawingFeature) -> DrawingMutationUiResult"))
        assertTrue(controls.contains("onDelete: () -> DrawingMutationUiResult"))
        assertTrue(controls.contains("onSaved = { colorMenuOpen = false }"))
        assertTrue(controls.contains("onSaved = { lineGraphicMenuOpen = false }"))
        assertTrue(selector.contains("if (result.shouldCloseTransientUi) expanded = false"))
        assertTrue(screen.contains("onSaved = ::stopDrawing"))
        assertTrue(screen.contains("if (result.saved) selectedDrawingId = null"))
    }

    private class FailureThenSuccess(var attempts: Int = 0) {
        fun persist(): Boolean = ++attempts > 1
    }

    private fun sourceText(relativePath: String): String {
        var dir = java.io.File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            val source = java.io.File(dir, relativePath)
            if (source.exists()) return source.readText()
            dir = dir.parentFile ?: return@repeat
        }
        error("Could not locate $relativePath")
    }
}

