package com.tacmap.sync

import com.tacmap.localization.DisplayFormat

import com.tacmap.localization.Messages

import com.tacmap.localization.L10n

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import com.tacmap.ui.AlertDialog
import androidx.compose.material3.Button
import com.tacmap.ui.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tacmap.ui.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.Date

@Composable
fun TacMapChatDialog(
    manager: SyncManager,
    initialTarget: TacMapChatTarget,
    onDismiss: () -> Unit,
) {
    val allMessages by manager.chatMessages.collectAsState()
    val recipients by manager.chatRecipients.collectAsState()
    val unreadTotal by manager.unreadChatMessageCount.collectAsState()
    val sessionReady by manager.chatSessionReady.collectAsState()
    val availabilityMessage by manager.chatAvailabilityMessage.collectAsState()
    val availability = availabilityMessage?.text
    val historyAvailability by manager.chatHistoryAvailability.collectAsState()
    var roomScope by remember(initialTarget) {
        mutableStateOf(initialTarget === TacMapChatTarget.EntireRoom)
    }
    var selectedSnapshot by remember(initialTarget) {
        mutableStateOf(initialTarget as? TacMapChatTarget.SelectedUnit)
    }
    var recipientMenuOpen by remember { mutableStateOf(false) }
    var body by remember { mutableStateOf("") }
    var sendIssue by remember { mutableStateOf<com.tacmap.localization.LocalizedMessage?>(null) }
    var confirmRoomSend by remember { mutableStateOf(false) }

    // App/DataKey lock must not leave plaintext drafts or route snapshots in remember state.
    LaunchedEffect(historyAvailability) {
        if (historyAvailability == TacMapChatHistoryAvailability.LOCKED) {
            body = ""
            selectedSnapshot = null
            onDismiss()
        }
    }

    val currentTarget: TacMapChatTarget = if (roomScope) {
        TacMapChatTarget.EntireRoom
    } else {
        selectedSnapshot ?: TacMapChatTarget.SelectedUnit("", "", "", "")
    }
    val visibleMessages = allMessages.filter { message ->
        if (roomScope) {
            message.scope == TacMapChatScope.ROOM
        } else {
            message.scope == TacMapChatScope.DIRECT &&
                message.conversationActorId == selectedSnapshot?.actorId
        }
    }
    val conversationTargets = chatConversationTargets(allMessages, recipients)
    val roomUnread = manager.unreadChatMessageCount(TacMapChatTarget.EntireRoom)
    val directUnread = (unreadTotal - roomUnread).coerceAtLeast(0)

    // Only acknowledge the room or direct thread that is actually visible. The
    // store persists this before its aggregate badge count is allowed to fall.
    LaunchedEffect(
        roomScope,
        selectedSnapshot?.actorId,
        visibleMessages.map(TacMapChatMessage::id),
        unreadTotal,
    ) {
        if (roomScope) {
            manager.markChatMessagesRead(TacMapChatTarget.EntireRoom)
        } else {
            selectedSnapshot?.let(manager::markChatMessagesRead)
        }
    }
    val blockReason = if (!roomScope && selectedSnapshot == null) {
        L10n.text("Choose a unit")
    } else {
        manager.chatSendBlockReason(currentTarget)?.text
    }
    val canSend = sessionReady && blockReason == null && body.isNotBlank()

    // Follow effects live inside the Dialog below, see followNewestChatRow
    val historyState = rememberLazyListState()
    val newestIndex = visibleMessages.lastIndex
    val newestId = visibleMessages.lastOrNull()?.id
    // At the largest text sizes the header and composer would squeeze the
    // history out, so the whole dialog scrolls and the history keeps a height.
    val largeText = LocalDensity.current.fontScale >= LARGE_TEXT_FONT_SCALE

    fun performSend() {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return
        when (val result = manager.sendChat(
            currentTarget,
            TacMapChatContentKind.TEXT,
            trimmed,
        )) {
            is TacMapChatSendResult.Sent -> body = ""
            is TacMapChatSendResult.Blocked -> sendIssue = result.pendingReason
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .padding(TACMAP_CHAT_OUTER_MARGIN_DP.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                tonalElevation = 6.dp,
                modifier = Modifier.fillMaxSize(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (largeText) Modifier.verticalScroll(rememberScrollState()) else Modifier),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("TacMap Chat", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onDismiss) { Text(L10n.text("Done")) }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (roomScope) Color(0x1AFF9800) else Color.Transparent)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(Messages.chatRecipientHeading(), fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (roomScope) {
                                Button(onClick = { roomScope = true }, modifier = Modifier.weight(1f)) {
                                    Text(L10n.text("Entire room%1\$s", unreadSuffix(roomUnread)))
                                }
                            } else {
                                OutlinedButton(onClick = { roomScope = true }, modifier = Modifier.weight(1f)) {
                                    Text(L10n.text("Entire room%1\$s", unreadSuffix(roomUnread)))
                                }
                            }
                            if (!roomScope) {
                                Button(onClick = { roomScope = false }, modifier = Modifier.weight(1f)) {
                                    Text(L10n.text("Selected unit%1\$s", unreadSuffix(directUnread)))
                                }
                            } else {
                                OutlinedButton(onClick = { roomScope = false }, modifier = Modifier.weight(1f)) {
                                    Text(L10n.text("Selected unit%1\$s", unreadSuffix(directUnread)))
                                }
                            }
                        }
                        if (roomScope) {
                            Text(
                                L10n.text("Broadcast to every chat-ready unit"),
                                color = Color(0xFFEF6C00),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                            )
                        } else {
                            Box {
                                OutlinedButton(
                                    onClick = { recipientMenuOpen = true },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(selectedSnapshot?.displayLabel() ?: L10n.text("Choose a unit"))
                                }
                                DropdownMenu(
                                    expanded = recipientMenuOpen,
                                    onDismissRequest = { recipientMenuOpen = false },
                                ) {
                                    conversationTargets.forEach { recipient ->
                                        val unread = manager.unreadChatMessageCount(recipient)
                                        val isLive = recipient.actorId in recipients
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text(recipient.displayLabel())
                                                    if (unread > 0 || !isLive) {
                                                        Text(
                                                            listOfNotNull(
                                                                unread.takeIf { it > 0 }?.let {
                                                                    L10n.text("%1\$s unread", it)
                                                                },
                                                                L10n.text("Offline").takeUnless { isLive },
                                                            ).joinToString(" · "),
                                                            color = Color.Gray,
                                                            fontSize = 11.sp,
                                                        )
                                                    }
                                                }
                                            },
                                            onClick = {
                                                // Capture the exact actor + session + key tuple now.
                                                selectedSnapshot = recipient
                                                recipientMenuOpen = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        (blockReason ?: availability)?.let {
                            Text(it, color = Color.Gray, fontSize = 11.sp)
                        }
                    }

                    HorizontalDivider()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (largeText) Modifier.height(LARGE_TEXT_HISTORY_HEIGHT_DP.dp) else Modifier.weight(1f)),
                    ) {
                        // Open on, and follow, the newest message. Has to sit in the Dialog's own
                        // composition (same one as the LazyColumn) so the list already has the new
                        // row when this runs. From the outer composition it raced the list.
                        LaunchedEffect(roomScope, selectedSnapshot?.actorId, newestId) {
                            followNewestChatRow(
                                newestKey = newestId,
                                newestIndex = newestIndex,
                                lastFullyVisibleKey = snapshotFlow { historyState.layoutInfo }
                                    .map { it.lastFullyVisibleKey() },
                            ) { historyState.scrollToItem(it) }
                        }
                        // Keyboard up/down resizes the history. Stay on the newest msg if thats
                        // where the user was, leave them alone if theyd scrolled back to read.
                        LaunchedEffect(historyState) {
                            val pin = ChatBottomPin()
                            snapshotFlow { historyState.layoutInfo }.collect { info ->
                                val newest = info.totalItemsCount - 1
                                val newestShown = info.lastFullyVisibleIndex() == newest
                                if (pin.onLayout(info.viewportSize.height, newestShown) && newest >= 0) {
                                    historyState.scrollToItem(newest)
                                }
                            }
                        }
                        if (visibleMessages.isEmpty()) {
                            Column(
                                modifier = Modifier.fillMaxSize().padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Text(
                                    if (roomScope) L10n.text("No room messages yet") else L10n.text("No messages with this unit yet"),
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    L10n.text("Chat is live-only. Messages missed while a unit is offline are not recovered by the relay."),
                                    color = Color.Gray,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                        } else {
                            LazyColumn(
                                state = historyState,
                                modifier = Modifier.fillMaxSize().padding(vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(visibleMessages, key = { it.id }) { message ->
                                    TacMapChatMessageRow(message)
                                }
                            }
                        }
                    }

                    HorizontalDivider()
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = body,
                            onValueChange = { body = TacMapChatPayload.boundedBody(it) },
                            label = { Text(L10n.text("Message")) },
                            minLines = 1,
                            maxLines = 5,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                L10n.text("Plain text only"),
                                color = Color.Gray,
                                fontSize = 10.sp,
                            )
                            Spacer(Modifier.weight(1f))
                            Button(
                                enabled = canSend,
                                onClick = {
                                    if (roomScope) confirmRoomSend = true else performSend()
                                },
                            ) { Text(L10n.text("Send")) }
                        }
                        Text(
                            L10n.text("Routed means accepted by the relay, not delivered or read."),
                            color = Color.Gray,
                            fontSize = 10.sp,
                        )
                    }
                }
            }
        }
    }

    if (confirmRoomSend) {
        AlertDialog(
            onDismissRequest = { confirmRoomSend = false },
            title = { Text(L10n.text("Send to the entire room?")) },
            text = { Text(L10n.text("This routes the message to every currently chat-ready unit.")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRoomSend = false
                    performSend()
                }) { Text(Messages.sendUnitsCount(recipients.size)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRoomSend = false }) { Text(L10n.text("Cancel")) }
            },
        )
    }

    sendIssue?.let { issue ->
        AlertDialog(
            onDismissRequest = { sendIssue = null },
            title = { Text("TacMap Chat") },
            text = { Text(issue.text) },
            confirmButton = {
                TextButton(onClick = { sendIssue = null }) { Text(Messages.acknowledge()) }
            },
        )
    }
}

internal const val TACMAP_CHAT_OUTER_MARGIN_DP = 12

/** Font scale from which the chat scrolls as a whole (Android's largest text sizes). */
private const val LARGE_TEXT_FONT_SCALE = 1.5f
private const val LARGE_TEXT_HISTORY_HEIGHT_DP = 320

/**
 * Scroll history until the newest message is actually on screen. This used to run from the outer
 * composition while the LazyColumn lives in the Dialog's own one, so it could fire before the list
 * had the new row. The scroll got clamped to the old last row, the list anchored on it, and the new
 * msg sat just below the fold untill the next one arrived. Looked exactly like chat lagging a
 * message behind. Now its called from inside the Dialog, and this double checks by row key (not
 * row count, at the 500 msg cap the count never changes). Bounded so a row taller than the
 * viewport cant spin forever.
 */
internal suspend fun followNewestChatRow(
    newestKey: Any?,
    newestIndex: Int,
    lastFullyVisibleKey: Flow<Any?>,
    scrollTo: suspend (Int) -> Unit,
) {
    if (newestKey == null || newestIndex < 0) return
    var attempts = 0
    lastFullyVisibleKey.first { key ->
        if (key == newestKey || attempts >= MAX_FOLLOW_ATTEMPTS) return@first true
        attempts += 1
        scrollTo(newestIndex)
        false
    }
}

private const val MAX_FOLLOW_ATTEMPTS = 8

/** True = viewport just resized (keyboard) while the user was on the newest row, scroll back to it. */
internal class ChatBottomPin {
    private var viewportHeight = -1
    private var atBottom = true

    fun onLayout(viewportHeight: Int, newestFullyVisible: Boolean): Boolean {
        val resized = this.viewportHeight >= 0 && viewportHeight != this.viewportHeight
        this.viewportHeight = viewportHeight
        // a resize says nothing about where the user wants to be, only scrolls / new rows do
        if (!resized) {
            atBottom = newestFullyVisible
            return false
        }
        return atBottom && !newestFullyVisible
    }
}

private fun LazyListLayoutInfo.lastFullyVisible() =
    visibleItemsInfo.lastOrNull { it.offset + it.size <= viewportEndOffset }

private fun LazyListLayoutInfo.lastFullyVisibleKey(): Any? = lastFullyVisible()?.key

private fun LazyListLayoutInfo.lastFullyVisibleIndex(): Int = lastFullyVisible()?.index ?: -1

/**
 * Keep encrypted historical direct threads readable after their sender leaves.
 * Historical targets deliberately have no live session/key tuple, so the
 * existing send gate disables replies until an authenticated live target exists.
 */
internal fun chatConversationTargets(
    messages: List<TacMapChatMessage>,
    liveRecipients: Map<String, TacMapChatTarget.SelectedUnit>,
): List<TacMapChatTarget.SelectedUnit> {
    val byActor = linkedMapOf<String, TacMapChatTarget.SelectedUnit>()
    messages.forEach { message ->
        if (message.scope != TacMapChatScope.DIRECT) return@forEach
        val actorId = message.conversationActorId ?: return@forEach
        val name = if (message.isOutgoing) message.recipientName.orEmpty() else message.senderName
        val retainedName = name.takeIf(String::isNotBlank)
            ?: byActor[actorId]?.displayName.orEmpty()
        byActor[actorId] = TacMapChatTarget.SelectedUnit(
            actorId = actorId,
            sessionDomain = "",
            chatKeyId = "",
            displayName = retainedName,
        )
    }
    liveRecipients.forEach { (actorId, liveTarget) ->
        val historicalName = byActor[actorId]?.displayName.orEmpty()
        byActor[actorId] = if (liveTarget.displayName.isBlank() && historicalName.isNotBlank()) {
            liveTarget.copy(displayName = historicalName)
        } else {
            liveTarget
        }
    }
    return byActor.values.sortedBy { it.displayLabel().lowercase() }
}

private fun unreadSuffix(count: Int): String = if (count > 0) " ($count)" else ""

@Composable
private fun TacMapChatMessageRow(message: TacMapChatMessage) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        if (message.isOutgoing) Spacer(Modifier.width(36.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .background(
                    if (message.isOutgoing) Color(0x1A1976D2) else Color(0x14000000),
                    RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 11.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    L10n.text("%1\$s · %2\$s", message.senderName.ifBlank { "Unknown unit" }, message.senderActorId.takeLast(6).uppercase()),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (message.kind == TacMapChatContentKind.REPORT) {
                    Text(L10n.text("  REPORT"), color = Color(0xFFEF6C00), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
            SelectionContainer {
                Text(message.body, modifier = Modifier.padding(vertical = 4.dp))
            }
            val delivery = when (message.deliveryState) {
                TacMapChatDeliveryState.SENT -> L10n.text("Sending…")
                TacMapChatDeliveryState.ROUTED -> if (message.scope == TacMapChatScope.ROOM) {
                    L10n.text("Sent to room")
                } else {
                    L10n.text("Routed")
                }
                TacMapChatDeliveryState.RECEIVED -> L10n.text("Received")
                TacMapChatDeliveryState.FAILED -> L10n.text("Not routed")
            }
            Text(
                "${DisplayFormat.time(Date(message.sentAtMilliseconds))}" +
                    if (message.isOutgoing) " · $delivery" else "",
                fontSize = 9.sp,
                color = if (message.deliveryState == TacMapChatDeliveryState.FAILED) Color.Red else Color.Gray,
            )
        }
        if (!message.isOutgoing) Spacer(Modifier.width(36.dp))
    }
}
