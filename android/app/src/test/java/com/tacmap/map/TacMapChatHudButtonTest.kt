package com.tacmap.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TacMapChatHudButtonTest {
    @Test
    fun unreadBadgeCapsVisualCountAndKeepsAccessibleActualCount() {
        assertNull(tacMapChatUnreadBadgeText(0))
        assertEquals("1", tacMapChatUnreadBadgeText(1))
        assertEquals("99", tacMapChatUnreadBadgeText(99))
        assertEquals("99+", tacMapChatUnreadBadgeText(100))
        assertEquals("TacMap Chat", tacMapChatContentDescription(0))
        assertEquals("TacMap Chat, 1 unread message", tacMapChatContentDescription(1))
        assertEquals("TacMap Chat, 124 unread messages", tacMapChatContentDescription(124))
    }

    @Test
    fun launcherIsVisibleForAnyActiveSecureV3RoomOnly() {
        assertEquals(false, tacMapChatHudVisible(null))
        assertEquals(false, tacMapChatHudVisible(""))
        assertEquals(false, tacMapChatHudVisible("2:legacy-room"))
        assertEquals(true, tacMapChatHudVisible("3:secure-room"))
    }
}
