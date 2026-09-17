package com.tacmap.map

import com.tacmap.calibration.ActiveMapSelectionPersistence
import com.tacmap.calibration.BasemapStyle

/** How a successful durable selection changes the retained-map publication. */
internal sealed interface RetainedMapPublication<out Source> {
    data object Keep : RetainedMapPublication<Nothing>
    data object Clear : RetainedMapPublication<Nothing>
    data class Set<Source>(val source: Source) : RetainedMapPublication<Source>
}

/** State that is safe to publish because its selector is already durable. */
internal data class DurableMapPublication<Source>(
    val active: Source,
    val retained: RetainedMapPublication<Source>,
    val preferredOnlineStyle: BasemapStyle,
)

internal enum class MapSelectionCommitFailure {
    PDF_SESSION,
    SELECTOR,
    PDF_SESSION_ROLLBACK,
}

internal sealed interface MapSelectionCommitResult {
    data object Succeeded : MapSelectionCommitResult
    data class Failed(val reason: MapSelectionCommitFailure) : MapSelectionCommitResult
}

/**
 * Enforces the persistence-before-publication boundary used by [MapViewModel].
 *
 * Active and retained selectors are committed by one atomic store operation.
 * PDF metadata has its own encrypted store, so it is prepared first and rolled
 * back if the selector commit fails. No failed transition reaches [publish].
 */
internal class ActiveMapSelectionCommitCoordinator<Source>(
    private val persistence: ActiveMapSelectionPersistence,
    private val publish: (DurableMapPublication<Source>) -> Unit,
) {
    fun selectOnline(
        source: Source,
        style: BasemapStyle,
    ): MapSelectionCommitResult = commitSelector(
        persist = { persistence.saveOnline(style) },
        publication = DurableMapPublication(
            active = source,
            retained = RetainedMapPublication.Keep,
            preferredOnlineStyle = style,
        ),
    )

    fun activatePdf(
        source: Source,
        preferredOnlineStyle: BasemapStyle,
        persistPdfSession: () -> Boolean,
        rollbackPdfSession: () -> Boolean,
    ): MapSelectionCommitResult {
        if (!persistPdfSession()) {
            val rollbackSucceeded = rollbackPdfSession()
            return MapSelectionCommitResult.Failed(
                if (rollbackSucceeded) MapSelectionCommitFailure.PDF_SESSION
                else MapSelectionCommitFailure.PDF_SESSION_ROLLBACK
            )
        }
        if (!persistence.saveActiveAndRetainedPdf(preferredOnlineStyle)) {
            val rollbackSucceeded = rollbackPdfSession()
            return MapSelectionCommitResult.Failed(
                if (rollbackSucceeded) MapSelectionCommitFailure.SELECTOR
                else MapSelectionCommitFailure.PDF_SESSION_ROLLBACK
            )
        }
        publish(
            DurableMapPublication(
                active = source,
                retained = RetainedMapPublication.Set(source),
                preferredOnlineStyle = preferredOnlineStyle,
            )
        )
        return MapSelectionCommitResult.Succeeded
    }

    fun activatePersistedPdf(
        source: Source,
        preferredOnlineStyle: BasemapStyle,
    ): MapSelectionCommitResult = commitSelector(
        persist = { persistence.saveActiveAndRetainedPdf(preferredOnlineStyle) },
        publication = DurableMapPublication(
            active = source,
            retained = RetainedMapPublication.Set(source),
            preferredOnlineStyle = preferredOnlineStyle,
        ),
    )

    fun activateOffline(
        source: Source,
        path: String,
        preferredOnlineStyle: BasemapStyle,
    ): MapSelectionCommitResult = commitSelector(
        persist = { persistence.saveActiveAndRetainedOffline(path, preferredOnlineStyle) },
        publication = DurableMapPublication(
            active = source,
            retained = RetainedMapPublication.Set(source),
            preferredOnlineStyle = preferredOnlineStyle,
        ),
    )

    fun unloadImportedMap(
        onlineSource: Source,
        style: BasemapStyle,
        clearRetained: Boolean,
    ): MapSelectionCommitResult = commitSelector(
        persist = {
            if (clearRetained) persistence.saveOnlineAndClearRetained(style)
            else persistence.saveOnline(style)
        },
        publication = DurableMapPublication(
            active = onlineSource,
            retained = if (clearRetained) {
                RetainedMapPublication.Clear
            } else {
                RetainedMapPublication.Keep
            },
            preferredOnlineStyle = style,
        ),
    )

    private fun commitSelector(
        persist: () -> Boolean,
        publication: DurableMapPublication<Source>,
    ): MapSelectionCommitResult {
        if (!persist()) {
            return MapSelectionCommitResult.Failed(MapSelectionCommitFailure.SELECTOR)
        }
        publish(publication)
        return MapSelectionCommitResult.Succeeded
    }
}
