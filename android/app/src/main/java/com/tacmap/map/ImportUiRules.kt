package com.tacmap.map

import com.tacmap.calibration.ImportError
import com.tacmap.calibration.ImportFailure
import com.tacmap.calibration.LibraryState
import com.tacmap.calibration.ManualCalibration
import com.tacmap.calibration.fiducial.StatusMessage
import com.tacmap.localization.DisplayFormat
import com.tacmap.map.render.pdf.PdfBakeFormat
import java.util.Locale

/**
 * The little decisions the import + Layers UI makes, kept out of the composables so the
 * shared fixture (import_limits.json) can pin them on the JVM. Same rules as iOS.
 */
internal object ImportUiRules {
    private fun uiLocale(): Locale = Locale.forLanguageTag(DisplayFormat.uiLanguage())

    /**
     * OD-F6: every byte count the import / Layers copy shows goes through WP2's bake size
     * rule, so 512 MiB reads "537 MB" on both apps (and 4 GiB "4.3 GB", de "4,3 GB")
     */
    fun byteText(bytes: Long, locale: Locale = uiLocale()): String = PdfBakeFormat.size(bytes, locale)

    /** the {limit} / {size} arg text of a byte-carrying import error, nothing for the others */
    fun failureArgText(f: ImportFailure, locale: Locale): Map<String, String> = when (f.error) {
        ImportError.TOO_LARGE -> mapOf("limit" to byteText((f.args.getValue("limit") as Number).toLong(), locale))
        ImportError.NO_SPACE -> mapOf("size" to byteText((f.args.getValue("size") as Number).toLong(), locale))
        else -> emptyMap()
    }

    /** OD-F14: the Layers footer {size} counts each entry's file plus its bake file */
    fun footerBytes(state: LibraryState): Long =
        state.entries.sumOf { maxOf(0L, it.byteCount) + (it.pdf?.validBake?.bytes ?: 0L) }

    /**
     * OD-F10: the WP2 "Imported Map" block's subtitle for a hand calibrated PDF is the s10
     * state label, same words as its Layers row. null = not ours, the georef origin label stays
     */
    fun calibratedBlockLabel(manual: ManualCalibration?): StatusMessage? {
        val m = manual ?: return null
        val rms = m.rmsM
        return if (rms != null) StatusMessage("map_state_calibrated", mapOf("points" to m.n, "rms" to rms))
        else StatusMessage("map_state_calibrated_exact", mapOf("points" to m.n))
    }

    /** OD-F9: the bake confirm for a map that isn't the one on screen says the screen won't change */
    fun bakeConfirmKey(onScreen: Boolean): String =
        if (onScreen) "pdf_bake_confirm_message" else "pdf_bake_confirm_message_inactive"

    enum class GenerateMenu { CANCEL, ENABLED, DISABLED }

    /**
     * E13 row menu Generate offline tiles, like iOS: Cancel while this entry's bake runs,
     * greyed out while any bake is busy or while the on-screen copy of this map failed to draw
     */
    fun generateMenu(bakeBusy: Boolean, bakeRunningForEntry: Boolean, onScreenAndFailed: Boolean): GenerateMenu = when {
        bakeRunningForEntry -> GenerateMenu.CANCEL
        bakeBusy || onScreenAndFailed -> GenerateMenu.DISABLED
        else -> GenerateMenu.ENABLED
    }
}

/** what picking a page in the Layers Choose page picker does (E1, E4, lifecycle.choosePage) */
internal data class ChoosePageDecision(
    /** the message key to confirm with first, null = no question */
    val confirm: String?,
    val write: Boolean,
    val then: Then,
    /** the change-page write also drops the durable selection back to online */
    val activeOnline: Boolean,
) {
    enum class Then(val code: String) { NONE("none"), ACTIVATE("activate"), CALIBRATE("calibrate") }
}

internal object ChoosePageRules {
    /** the Layers picker's message (E14), the import one says none of the pages has a georef */
    const val PICKER_MESSAGE_KEY = "map_choose_page_message"

    fun decide(
        currentPage: Int,
        pickedPage: Int,
        hasManual: Boolean,
        pickedHasValidGeoref: Boolean,
        entryIsActive: Boolean,
    ): ChoosePageDecision {
        // same page again: nothing to change, and certainly no calibration to throw away
        if (pickedPage == currentPage) return ChoosePageDecision(null, write = false, then = ChoosePageDecision.Then.NONE, activeOnline = false)
        return ChoosePageDecision(
            confirm = if (hasManual) "map_change_page_confirm" else null,
            write = true,
            then = if (pickedHasValidGeoref) ChoosePageDecision.Then.ACTIVATE else ChoosePageDecision.Then.CALIBRATE,
            activeOnline = entryIsActive && !pickedHasValidGeoref,
        )
    }
}
