package com.tacmap.sync

import com.tacmap.localization.L10n
import com.tacmap.localization.Messages
import com.tacmap.localization.LocalizedMessage

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.util.Base64

/** Explicit user-visible routing choice. Direct messages never fall back to a room broadcast. */
sealed interface TacMapChatTarget {
    data object EntireRoom : TacMapChatTarget

    /**
     * An immutable snapshot of one authenticated live endpoint. All three routing
     * identifiers must still match at send time or the send fails closed.
     */
    data class SelectedUnit(
        val actorId: String,
        val sessionDomain: String,
        val chatKeyId: String,
        val displayName: String,
    ) : TacMapChatTarget
}

internal fun TacMapChatTarget.SelectedUnit.displayLabel(): String {
    val name = displayName.trim().ifBlank { L10n.text("Unknown unit") }
    return L10n.text("%1\$s · %2\$s", name, actorId.takeLast(6).uppercase())
}

sealed interface TacMapChatSendResult {
    data class Sent(val messageId: String) : TacMapChatSendResult
    data class Blocked(val pendingReason: LocalizedMessage) : TacMapChatSendResult {
        val reason: String get() = pendingReason.text
    }
}

@Serializable
enum class TacMapChatScope {
    @SerialName("room") ROOM,
    @SerialName("direct") DIRECT,
}

@Serializable
enum class TacMapChatContentKind {
    @SerialName("text") TEXT,
    @SerialName("report") REPORT,
}

/** A relay acknowledgement means routed, not delivered or read. */
@Serializable
enum class TacMapChatDeliveryState {
    @SerialName("sent") SENT,
    @SerialName("routed") ROUTED,
    @SerialName("received") RECEIVED,
    @SerialName("failed") FAILED,
}

@Serializable
data class TacMapChatPayload(
    val pv: Int,
    val kind: TacMapChatContentKind,
    val body: String,
    val createdAt: Long,
    val replyTo: String? = null,
) {
    val isValid: Boolean
        get() = pv == VERSION &&
            body.isNotBlank() &&
            body.toByteArray(Charsets.UTF_8).size <= MAX_BODY_UTF8_BYTES &&
            createdAt >= 0 &&
            (replyTo == null || TacMapChatIds.isCanonicalMessageId(replyTo))

    companion object {
        const val VERSION = 1
        const val MAX_BODY_UTF8_BYTES = 4_096
        const val MAX_PLAINTEXT_BYTES = 8_192
        const val MAX_DISPLAY_NAME_CODE_POINTS = 64

        /** Truncate only at a Unicode code-point boundary. */
        fun boundedBody(value: String): String {
            var end = 0
            var bytes = 0
            while (end < value.length) {
                val codePoint = value.codePointAt(end)
                val chars = Character.charCount(codePoint)
                val encodedBytes = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
                if (bytes + encodedBytes > MAX_BODY_UTF8_BYTES) break
                bytes += encodedBytes
                end += chars
            }
            return value.substring(0, end)
        }

        fun boundedDisplayName(value: String): String {
            val cleaned = value.filterNot(::isUnsafeDisplayCharacter).trim()
            val count = cleaned.codePointCount(0, cleaned.length)
            return if (count <= MAX_DISPLAY_NAME_CODE_POINTS) cleaned else {
                cleaned.substring(0, cleaned.offsetByCodePoints(0, MAX_DISPLAY_NAME_CODE_POINTS))
            }
        }

        private fun isUnsafeDisplayCharacter(value: Char): Boolean =
            value.isISOControl() || Character.getType(value) == Character.FORMAT.toInt()
    }
}

/** Encrypted-at-rest local history entry. */
@Serializable
data class TacMapChatMessage(
    val id: String,
    val roomId: String,
    val scope: TacMapChatScope,
    val senderActorId: String,
    val senderName: String,
    val recipientActorId: String? = null,
    val recipientName: String? = null,
    val kind: TacMapChatContentKind,
    val body: String,
    val sentAtMilliseconds: Long,
    val isOutgoing: Boolean,
    val deliveryState: TacMapChatDeliveryState,
    val failureCode: String? = null,
) {
    val conversationActorId: String?
        get() = if (scope != TacMapChatScope.DIRECT) null
        else if (isOutgoing) recipientActorId else senderActorId

    val isValid: Boolean
        get() = TacMapChatIds.isCanonicalMessageId(id) &&
            TacMapChatIds.isCanonical32(roomId) &&
            TacMapChatIds.isCanonical32(senderActorId) &&
            senderName.codePointCount(0, senderName.length) <=
                TacMapChatPayload.MAX_DISPLAY_NAME_CODE_POINTS &&
            body.isNotBlank() &&
            body.toByteArray(Charsets.UTF_8).size <= TacMapChatPayload.MAX_BODY_UTF8_BYTES &&
            sentAtMilliseconds >= 0 &&
            when (scope) {
                TacMapChatScope.ROOM -> recipientActorId == null && recipientName == null
                TacMapChatScope.DIRECT -> recipientActorId != senderActorId &&
                    recipientActorId?.let(TacMapChatIds::isCanonical32) == true &&
                    (recipientName?.codePointCount(0, recipientName.length) ?: 0) <=
                    TacMapChatPayload.MAX_DISPLAY_NAME_CODE_POINTS
            } &&
            (failureCode?.toByteArray(Charsets.UTF_8)?.size ?: 0) <= MAX_FAILURE_CODE_BYTES

    companion object {
        private const val MAX_FAILURE_CODE_BYTES = 64
    }
}

internal object TacMapChatIds {
    private val random = SecureRandom()
    private val urlEncoder = Base64.getUrlEncoder().withoutPadding()
    private val urlDecoder = Base64.getUrlDecoder()

    fun isCanonical32(value: String): Boolean = canonicalBytes(value, 32) != null

    fun isCanonicalMessageId(value: String): Boolean = canonicalBytes(value, 16) != null

    fun canonicalBytes(value: String, expectedBytes: Int): ByteArray? {
        val expectedChars = when (expectedBytes) {
            16 -> 22
            32 -> 43
            64 -> 86
            else -> return null
        }
        if (value.length != expectedChars || value.any {
                !it.isLetterOrDigit() && it != '-' && it != '_'
            }) return null
        val decoded = runCatching { urlDecoder.decode(value) }.getOrNull() ?: return null
        return decoded.takeIf { it.size == expectedBytes && urlEncoder.encodeToString(it) == value }
    }

    fun newMessageId(): String = ByteArray(16).also(random::nextBytes).let(urlEncoder::encodeToString)
}

internal data class TacMapChatPeerKey(
    val actorId: String,
    val sessionDomain: String,
    val chatKeyId: String,
    val x25519PublicKey: ByteArray,
    val signingPublicKey: ByteArray,
    val displayName: String,
) {
    fun immutableTarget(): TacMapChatTarget.SelectedUnit = TacMapChatTarget.SelectedUnit(
        actorId = actorId,
        sessionDomain = sessionDomain,
        chatKeyId = chatKeyId,
        displayName = displayName,
    )
}

@Serializable
internal data class TacMapChatReplayRecord(
    val actorId: String,
    val sessionDomain: String,
    val chatKeyId: String,
    val highCounterHex: String,
    val recentFingerprints: List<String>,
) {
    val isValid: Boolean
        get() = TacMapChatIds.isCanonical32(actorId) &&
            TacMapChatIds.isCanonical32(sessionDomain) &&
            TacMapChatIds.isCanonical32(chatKeyId) &&
            highCounterHex.matches(Regex("^[0-7][0-9a-f]{15}$")) &&
            recentFingerprints.size <= MAX_RECENT_FINGERPRINTS &&
            recentFingerprints.distinct().size == recentFingerprints.size &&
            recentFingerprints.all(TacMapChatIds::isCanonical32)

    companion object {
        const val MAX_RECENT_FINGERPRINTS = 64
    }
}

@Serializable
internal data class TacMapChatHistoryEnvelope(
    val version: Int,
    val messages: List<TacMapChatMessage>,
    val replay: List<TacMapChatReplayRecord>,
    /** Metadata only, kept inside the same sealed history envelope as the messages. */
    val unreadInboundMessageIds: List<String> = emptyList(),
)

internal sealed interface TacMapChatSendGate {
    data class Ready(val peer: TacMapChatPeerKey? = null) : TacMapChatSendGate
    data class Blocked(val pendingReason: LocalizedMessage) : TacMapChatSendGate {
        val reason: String get() = pendingReason.text
    }
}

/** Pure send-time gate used by SyncManager and tests. */
internal object TacMapChatTargetGate {
    fun evaluate(
        target: TacMapChatTarget,
        connectedV3: Boolean,
        localChatKeyAcknowledged: Boolean,
        peerKeys: Map<String, TacMapChatPeerKey>,
    ): TacMapChatSendGate {
        if (!connectedV3) return TacMapChatSendGate.Blocked(Messages.chatJoinAConnectedVUnitSyncRoomMessage())
        if (!localChatKeyAcknowledged) return TacMapChatSendGate.Blocked(Messages.chatSecureChatIsStillStartingMessage())
        if (target === TacMapChatTarget.EntireRoom) return TacMapChatSendGate.Ready()
        val selected = target as TacMapChatTarget.SelectedUnit
        val current = peerKeys[selected.actorId]
        return if (current != null &&
            current.sessionDomain == selected.sessionDomain &&
            current.chatKeyId == selected.chatKeyId
        ) {
            TacMapChatSendGate.Ready(current)
        } else {
            // Deliberately no room fallback: the user selected one exact endpoint.
            TacMapChatSendGate.Blocked(Messages.chatSelectedUnitIsNoLongerAvailableMessage())
        }
    }
}
