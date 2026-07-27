package com.tacmap.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainMenuActionsTest {
    @Test fun privacyAndOpsecMenuEntryOpensProductionDialogRoute() {
        var menuOpen = true
        var dialogVisible = false

        openPrivacyAndOpsecFromMenu(
            setMenuOpen = { menuOpen = it },
            setDialogVisible = { dialogVisible = it },
        )

        assertEquals("Settings, Privacy & OPSEC", PRIVACY_OPSEC_LABEL)
        assertFalse(menuOpen)
        assertTrue(dialogVisible)
    }
}
