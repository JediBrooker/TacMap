package com.tacmap.sync

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.text.DateFormat
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
    val availability by manager.chatAvailabilityMessage.collectAsState()
    val historyAvailability by manager.chatHistoryAvailability.collectAsState()
    var roomScope by remember(initialTarget) {
        mutableStateOf(initialTarget === TacMapChatTarget.EntireRoom)
    }
    var selectedSnapshot by remember(initialTarget) {
        mutableStateOf(initialTarget as? TacMapChatTarget.SelectedUnit)
    }
    var recipientMenuOpen by remember { mutableStateOf(false) }
    var body by remember { mutableStateOf("") }
    var sendIssue by remember { mutableStateOf<String?>(null) }
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
        "Choose a unit"
    } else {
        manager.chatSendBlockReason(currentTarget)
    }
    val canSend = sessionReady && blockReason == null && body.isNotBlank()

    fun performSend() {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return
        when (val result = manager.sendChat(
            currentTarget,
            TacMapChatContentKind.TEXT,
            trimmed,
        )) {
            is TacMapChatSendResult.Sent -> body = ""
            is TacMapChatSendResult.Blocked -> sendIssue = result.reason
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
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("TacMap Chat", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onDismiss) { Text("Done") }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (roomScope) Color(0x1AFF9800) else Color.Transparent)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("RECIPIENT", fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (roomScope) {
                                Button(onClick = { roomScope = true }, modifier = Modifier.weight(1f)) {
                                    Text("Entire room${unreadSuffix(roomUnread)}")
                                }
                            } else {
                                OutlinedButton(onClick = { roomScope = true }, modifier = Modifier.weight(1f)) {
                                    Text("Entire room${unreadSuffix(roomUnread)}")
                                }
                            }
                            if (!roomScope) {
                                Button(onClick = { roomScope = false }, modifier = Modifier.weight(1f)) {
                                    Text("Selected unit${unreadSuffix(directUnread)}")
                                }
                            } else {
                                OutlinedButton(onClick = { roomScope = false }, modifier = Modifier.weight(1f)) {
                                    Text("Selected unit${unreadSuffix(directUnread)}")
                                }
                            }
                        }
                        if (roomScope) {
                            Text(
                                "Broadcast to every chat-ready unit",
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
                                    Text(selectedSnapshot?.displayLabel() ?: "Choose a unit")
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
                                                                    "$it unread"
                                                                },
                                                                "Offline".takeUnless { isLive },
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
                            .weight(1f),
                    ) {
                        if (visibleMessages.isEmpty()) {
                            Column(
                                modifier = Modifier.fillMaxSize().padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Text(
                                    if (roomScope) "No room messages yet" else "No messages with this unit yet",
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "Chat is live-only. Messages missed while a unit is offline are not recovered by the relay.",
                                    color = Color.Gray,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                        } else {
                            LazyColumn(
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
                            label = { Text("Message") },
                            minLines = 1,
                            maxLines = 5,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Plain text only",
                                color = Color.Gray,
                                fontSize = 10.sp,
                            )
                            Spacer(Modifier.weight(1f))
                            Button(
                                enabled = canSend,
                                onClick = {
                                    if (roomScope) confirmRoomSend = true else performSend()
                                },
                            ) { Text("Send") }
                        }
                        Text(
                            "Routed means accepted by the relay, not delivered or read.",
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
            title = { Text("Send to the entire room?") },
            text = { Text("This routes the message to every currently chat-ready unit.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRoomSend = false
                    performSend()
                }) { Text("Send to ${recipients.size} unit${if (recipients.size == 1) "" else "s"}") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRoomSend = false }) { Text("Cancel") }
            },
        )
    }

    sendIssue?.let { issue ->
        AlertDialog(
            onDismissRequest = { sendIssue = null },
            title = { Text("TacMap Chat") },
            text = { Text(issue) },
            confirmButton = {
                TextButton(onClick = { sendIssue = null }) { Text("OK") }
            },
        )
    }
}

internal const val TACMAP_CHAT_OUTER_MARGIN_DP = 12

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
                    "${message.senderName.ifBlank { "Unknown unit" }} · ${message.senderActorId.takeLast(6).uppercase()}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (message.kind == TacMapChatContentKind.REPORT) {
                    Text("  REPORT", color = Color(0xFFEF6C00), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
            Text(message.body, modifier = Modifier.padding(vertical = 4.dp))
            val delivery = when (message.deliveryState) {
                TacMapChatDeliveryState.SENT -> "Sending…"
                TacMapChatDeliveryState.ROUTED -> if (message.scope == TacMapChatScope.ROOM) {
                    "Sent to room"
                } else {
                    "Routed"
                }
                TacMapChatDeliveryState.RECEIVED -> "Received"
                TacMapChatDeliveryState.FAILED -> "Not routed"
            }
            Text(
                "${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.sentAtMilliseconds))}" +
                    if (message.isOutgoing) " · $delivery" else "",
                fontSize = 9.sp,
                color = if (message.deliveryState == TacMapChatDeliveryState.FAILED) Color.Red else Color.Gray,
            )
        }
        if (!message.isOutgoing) Spacer(Modifier.width(36.dp))
    }
}
