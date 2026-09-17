package com.tacmap.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TacMapChatDialogTest {
    @Test
    fun productionDialogUsesNearFullScreenSafeImeAwarePresentation() {
        val source = sourceText(
            "android/app/src/main/java/com/tacmap/sync/TacMapChatDialog.kt"
        )

        assertEquals(12, TACMAP_CHAT_OUTER_MARGIN_DP)
        assertTrue(source.contains("usePlatformDefaultWidth = false"))
        assertTrue(source.contains("decorFitsSystemWindows = false"))
        assertTrue(source.contains(".windowInsetsPadding(WindowInsets.safeDrawing)"))
        assertTrue(source.contains(".imePadding()"))
        assertTrue(source.contains(".padding(TACMAP_CHAT_OUTER_MARGIN_DP.dp)"))
        assertTrue(source.contains(".weight(1f)"))
        assertFalse(source.contains("heightIn(min = 160.dp, max = 320.dp)"))
    }

    @Test
    fun historicalDirectConversationRemainsReadableAndLiveRouteOverridesPlaceholder() {
        val room = canonical32(1)
        val remoteActor = canonical32(2)
        val localActor = canonical32(3)
        val historical = TacMapChatMessage(
            id = canonicalMessageId(4),
            roomId = room,
            scope = TacMapChatScope.DIRECT,
            senderActorId = remoteActor,
            senderName = "Offline Alpha",
            recipientActorId = localActor,
            recipientName = "This device",
            kind = TacMapChatContentKind.TEXT,
            body = "History remains encrypted at rest",
            sentAtMilliseconds = 1_720_000_000_000,
            isOutgoing = false,
            deliveryState = TacMapChatDeliveryState.RECEIVED,
        )

        val blankLater = historical.copy(
            id = canonicalMessageId(7),
            senderName = "   ",
        )
        val offlineTarget = chatConversationTargets(
            listOf(historical, blankLater),
            emptyMap(),
        ).single()
        assertEquals(remoteActor, offlineTarget.actorId)
        assertEquals("Offline Alpha", offlineTarget.displayName)
        assertTrue(offlineTarget.sessionDomain.isEmpty())
        assertTrue(offlineTarget.chatKeyId.isEmpty())

        val liveTarget = TacMapChatTarget.SelectedUnit(
            actorId = remoteActor,
            sessionDomain = canonical32(5),
            chatKeyId = canonical32(6),
            displayName = "Live Alpha",
        )
        assertEquals(
            listOf(liveTarget),
            chatConversationTargets(
                listOf(historical, blankLater),
                mapOf(remoteActor to liveTarget),
            ),
        )

        val blankLiveTarget = liveTarget.copy(displayName = "")
        assertEquals(
            "Offline Alpha",
            chatConversationTargets(
                listOf(historical, blankLater),
                mapOf(remoteActor to blankLiveTarget),
            ).single().displayName,
        )

        val renamedLater = historical.copy(
            id = canonicalMessageId(8),
            senderName = "Renamed Alpha",
        )
        assertEquals(
            "Renamed Alpha",
            chatConversationTargets(
                listOf(historical, blankLater, renamedLater),
                emptyMap(),
            ).single().displayName,
        )
    }

    private fun canonical32(seed: Int): String =
        SyncIdentity.urlB64(ByteArray(32) { (seed + it).toByte() })

    private fun canonicalMessageId(seed: Int): String =
        SyncIdentity.urlB64(ByteArray(16) { (seed + it).toByte() })

    private fun sourceText(relativePath: String): String {
        var directory = java.io.File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            val source = java.io.File(directory, relativePath)
            if (source.exists()) return source.readText()
            directory = directory.parentFile ?: return@repeat
        }
        error("Could not locate $relativePath")
    }
}
