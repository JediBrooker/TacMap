package com.tacmap.sync

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
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
    fun followNewestKeepsScrollingUntilTheNewMessageIsReallyOnScreen() = runBlocking {
        // First layouts after "m3" lands still show the old list (the Dialog composition hasnt
        // caught up). Scrolling there gets clamped, so keep going until m3 is actually visible.
        val scrolledTo = mutableListOf<Int>()
        followNewestChatRow(
            newestKey = "m3",
            newestIndex = 2,
            lastFullyVisibleKey = flowOf("m2", "m2", "m3"),
        ) { scrolledTo += it }
        assertEquals(listOf(2, 2), scrolledTo)
    }

    @Test
    fun followNewestWorksAtTheRetentionCapWhereTheRowCountNeverChanges() = runBlocking {
        // 500 rows before and after: oldest evicted, newest appended. Only the key tells them apart.
        val scrolledTo = mutableListOf<Int>()
        followNewestChatRow(
            newestKey = "m501",
            newestIndex = 499,
            lastFullyVisibleKey = flowOf("m500", "m501"),
        ) { scrolledTo += it }
        assertEquals(listOf(499), scrolledTo)
    }

    @Test
    fun followNewestLeavesAVisibleOrEmptyThreadAloneAndIsBounded() = runBlocking {
        val scrolledTo = mutableListOf<Int>()
        followNewestChatRow("m1", 0, flowOf("m1")) { scrolledTo += it }
        followNewestChatRow(null, -1, flowOf(null)) { scrolledTo += it }
        assertTrue(scrolledTo.isEmpty())

        // a row taller than the viewport is never "fully visible", dont spin on it
        followNewestChatRow("huge", 4, flowOf(*Array(50) { "m3" })) { scrolledTo += it }
        assertTrue(scrolledTo.size in 1..10)
    }

    @Test
    fun keyboardKeepsYouOnTheNewestMessageOnlyIfYouWereThere() {
        val atBottom = ChatBottomPin()
        assertFalse(atBottom.onLayout(viewportHeight = 1200, newestFullyVisible = true))
        assertTrue("keyboard shrank it and buried the newest msg", atBottom.onLayout(700, newestFullyVisible = false))
        assertTrue("still animating", atBottom.onLayout(600, newestFullyVisible = false))
        assertFalse(atBottom.onLayout(600, newestFullyVisible = true))

        val readingBack = ChatBottomPin()
        assertFalse(readingBack.onLayout(1200, newestFullyVisible = true))
        assertFalse("user scrolled up", readingBack.onLayout(1200, newestFullyVisible = false))
        assertFalse("keyboard must not yank them down", readingBack.onLayout(700, newestFullyVisible = false))
    }

    @Test
    fun dialogFollowsTheNewestMessageFromInsideTheDialogCompositionByKey() {
        val source = sourceText("android/app/src/main/java/com/tacmap/sync/TacMapChatDialog.kt")
        // outside the Dialog the effect raced the LazyColumn's own composition
        val outer = source.substringAfter("fun TacMapChatDialog(").substringBefore("    Dialog(\n")
        val dialog = source.substringAfter("    Dialog(\n").substringBefore("internal const val TACMAP_CHAT_OUTER_MARGIN_DP")
        assertFalse(outer.contains("followNewestChatRow("))
        assertTrue(dialog.contains("followNewestChatRow("))
        assertTrue(dialog.contains(".map { it.lastFullyVisibleKey() }"))
        assertTrue(dialog.contains("val pin = ChatBottomPin()"))
        assertFalse(source.contains("totalItemsCount }"))
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
