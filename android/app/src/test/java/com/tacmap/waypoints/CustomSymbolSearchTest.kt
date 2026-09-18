package com.tacmap.waypoints
import org.junit.Assert.*
import org.junit.Test
class CustomSymbolSearchTest {
    @Test fun categoryTermsCaseAndUmlautsMatchWithoutNetwork() {
        assertTrue(CustomSymbolSearch.matches("Deutsche BOS / Feuerwehr / Führungsstelle", "feuerwehr fuhr"))
        assertTrue(CustomSymbolSearch.matches("THW / Zugführer", "ZUGFUHRER"))
        assertTrue(CustomSymbolSearch.matches("Any symbol", "  "))
        assertFalse(CustomSymbolSearch.matches("Feuerwehr", "polizei"))
    }
}
