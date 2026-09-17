package com.tacmap.map

import com.tacmap.localization.DecimalInput

import com.tacmap.localization.L10n
import com.tacmap.localization.Messages
import com.tacmap.localization.LocalizedMessage

import com.tacmap.drawings.DrawingDocument
import com.tacmap.drawings.DrawingLayer
import com.tacmap.waypoints.ReinforcementStatus
import com.tacmap.waypoints.TaskColor
import com.tacmap.waypoints.Waypoint
import com.tacmap.waypoints.WaypointKind
import com.tacmap.waypoints.normalizedUnitAmplifiersForKind

internal val ELEVATION_VALIDATION_MESSAGE: LocalizedMessage get() = Messages.displayEnterAValidElevationInMetresMessage()
internal val MGRS_MOVE_VALIDATION_MESSAGE: LocalizedMessage get() =
    Messages.displayEnterAOrFigureGridOrFullMgrsAtMessage()

internal sealed interface SymbolDraftResult {
    data class Valid(val waypoint: Waypoint) : SymbolDraftResult
    data class Invalid(val pendingMessage: LocalizedMessage) : SymbolDraftResult {
        val message: String get() = pendingMessage.text
    }
}

/** UI-only selected-symbol draft. Nothing here owns a store or publishes a
 * mutation: Save normalizes once, then the caller performs one durable update. */
internal data class SymbolEditDraft(
    val original: Waypoint,
    val name: String = original.name,
    val kind: WaypointKind = original.kind,
    val kindDisplayName: String = kind.displayName,
    val notes: String = original.notes.orEmpty(),
    val elevationText: String = original.elevationMetres?.toString().orEmpty(),
    val layerId: String = original.layerId,
    val taskColor: TaskColor = original.taskColor,
    val rotationDegrees: Double = original.rotation,
    val scaleX: Double = original.scaleX,
    val scaleY: Double = original.scaleY,
    val latitude: Double = original.latitude,
    val longitude: Double = original.longitude,
    val mgrsInput: String = "",
    val higherFormation: String = original.higherFormation.orEmpty(),
    val uniqueIdentifier: String = original.uniqueIdentifier.orEmpty(),
    val reinforcementStatus: ReinforcementStatus = original.reinforcementStatus,
) {
    fun changingKind(newKind: WaypointKind): SymbolEditDraft =
        if (newKind is WaypointKind.ControlMeasure) {
            copy(
                kind = newKind,
                kindDisplayName = newKind.displayName,
                higherFormation = "",
                uniqueIdentifier = "",
                reinforcementStatus = ReinforcementStatus.NONE,
            )
        } else {
            copy(
                kind = newKind,
                kindDisplayName = newKind.displayName,
                taskColor = TaskColor.BLACK,
                rotationDegrees = 0.0,
                scaleX = 1.0,
                scaleY = 1.0,
                higherFormation = if (newKind is WaypointKind.Military) higherFormation else "",
                uniqueIdentifier = if (newKind is WaypointKind.Military) uniqueIdentifier else "",
                reinforcementStatus = if (newKind is WaypointKind.Military) {
                    reinforcementStatus
                } else {
                    ReinforcementStatus.NONE
                },
            )
        }

    fun movingTo(latitude: Double, longitude: Double): SymbolEditDraft =
        copy(latitude = latitude, longitude = longitude)

    fun normalized(layers: List<DrawingLayer>): SymbolDraftResult {
        val resolvedLocation = if (mgrsInput.isBlank()) {
            latitude to longitude
        } else {
            resolveMgrsCoordinate(mgrsInput, latitude, longitude)?.let {
                it.latitude to it.longitude
            } ?: return SymbolDraftResult.Invalid(MGRS_MOVE_VALIDATION_MESSAGE)
        }
        val cleanElevation = elevationText.trim()
        val elevation = if (cleanElevation.isBlank()) {
            null
        } else {
            DecimalInput.parse(cleanElevation)
                ?: return SymbolDraftResult.Invalid(ELEVATION_VALIDATION_MESSAGE)
        }
        val fallbackLayerId = layers.firstOrNull {
            it.id == DrawingDocument.DEFAULT_LAYER_ID
        }?.id ?: layers.firstOrNull()?.id ?: DrawingDocument.DEFAULT_LAYER_ID
        val resolvedLayer = layerId.takeIf { selected -> layers.any { it.id == selected } }
            ?: fallbackLayerId
        val isControl = kind is WaypointKind.ControlMeasure
        val amplifiers = normalizedUnitAmplifiersForKind(
            kind = kind,
            higherFormation = higherFormation,
            uniqueIdentifier = uniqueIdentifier,
            reinforcementStatus = reinforcementStatus,
        )
        return SymbolDraftResult.Valid(
            original.copy(
                name = name.trim().ifBlank { kindDisplayName },
                kind = kind,
                notes = notes.trim().ifBlank { null },
                elevationMetres = elevation,
                layerId = resolvedLayer,
                taskColor = if (isControl) taskColor else TaskColor.BLACK,
                rotation = if (isControl) normalizedDegrees(rotationDegrees) else 0.0,
                scaleX = if (isControl) scaleX.coerceIn(MIN_SYMBOL_SCALE, MAX_SYMBOL_SCALE) else 1.0,
                scaleY = if (isControl) scaleY.coerceIn(MIN_SYMBOL_SCALE, MAX_SYMBOL_SCALE) else 1.0,
                latitude = resolvedLocation.first,
                longitude = resolvedLocation.second,
                higherFormation = amplifiers.higherFormation,
                uniqueIdentifier = amplifiers.uniqueIdentifier,
                reinforcementStatus = amplifiers.reinforcementStatus,
            )
        )
    }
}

internal const val MIN_SYMBOL_SCALE = 0.1
internal const val MAX_SYMBOL_SCALE = 20.0

internal val ELEVATION_VALIDATION_ERROR: String get() = ELEVATION_VALIDATION_MESSAGE.text

internal val MGRS_MOVE_VALIDATION_ERROR: String get() = MGRS_MOVE_VALIDATION_MESSAGE.text
