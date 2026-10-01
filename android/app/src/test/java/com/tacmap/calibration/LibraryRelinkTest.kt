package com.tacmap.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** E9: a re-import of an unavailable entry re-links it, one candidate state, nothing else moves */
class LibraryRelinkTest {
    private val key = "sha256:" + "d".repeat(64)
    private val bake = PersistedPdfBake("tacmap-bake-0123456789abcdef.mbtiles", "e".repeat(64), 10, 16, 256, 42)
    private val box = listOf(listOf(0.0, 0.0), listOf(10.0, 0.0), listOf(10.0, 10.0), listOf(0.0, 10.0))
    private val entry = ImportedMapEntry(
        id = "a", kind = "pdf", fileName = "pdf_maps/import-old.pdf", displayName = "Sheet",
        contentKey = key, byteCount = 10, fileModifiedAtMs = 1, importedAtMs = 1,
        pdf = PdfEntryInfo(pageCount = 3, pageIndex = 2, rotate = 90, pageBox = box, bake = bake, renderGuardToken = "tok"),
    )
    private val state = LibraryState(active = ActiveRef.entry("a"), preferredOnlineStyle = "OSM_TOPO", entries = listOf(entry))

    @Test
    fun relinkOnlyMovesTheFileFields() {
        val r = LibraryReducer.apply(LibraryTransition.Relink("a", key, "pdf_maps/import-new.pdf", 11, 2), state)
        assertTrue(r is LibraryReduction.Ok)
        val next = (r as LibraryReduction.Ok).state
        assertEquals(entry.copy(fileName = "pdf_maps/import-new.pdf", byteCount = 11, fileModifiedAtMs = 2), next.entry("a"))
        // calibration, page, bake record, token and the selection all stay
        assertEquals(state.active, next.active)
        assertEquals(bake, next.entry("a")!!.pdf!!.bake)
    }

    @Test
    fun relinkNeedsTheSameBytes() {
        assertEquals(
            LibraryReduction.Rejected(LibraryTransitionError.TARGET_MISMATCH),
            LibraryReducer.apply(LibraryTransition.Relink("a", "sha256:" + "f".repeat(64), "pdf_maps/x.pdf", 1, 1), state),
        )
        assertEquals(
            LibraryReduction.Rejected(LibraryTransitionError.UNKNOWN_ENTRY),
            LibraryReducer.apply(LibraryTransition.Relink("zz", key, "pdf_maps/x.pdf", 1, 1), state),
        )
    }
}
