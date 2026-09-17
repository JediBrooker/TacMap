package com.tacmap.sync

import android.content.Context
import com.tacmap.util.DataKey
import com.tacmap.util.SafeStore
import com.tacmap.util.SealedEnvelope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

internal enum class TacMapChatHistoryAvailability { CLOSED, READY, LOCKED, CORRUPT, UNAVAILABLE }

internal enum class TacMapChatInboundResult { ACCEPTED, DUPLICATE, REPLAY_REJECTED, STORE_UNAVAILABLE }

/**
 * Encrypted, bounded chat history. Chat has never had a plaintext format, so a
 * bare JSON file is rejected rather than passed through SafeStore migration.
 */
@OptIn(ExperimentalSerializationApi::class)
internal class TacMapChatHistoryStore private constructor(private val directory: File) {
    constructor(context: Context) : this(File(context.filesDir, DIRECTORY_NAME))

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        explicitNulls = true
    }
    private val _messages = MutableStateFlow<List<TacMapChatMessage>>(emptyList())
    val messages: StateFlow<List<TacMapChatMessage>> = _messages.asStateFlow()
    private val _unreadCount = MutableStateFlow(0)
    /** Aggregate metadata for UI badges. Message bodies never leave the sealed history flow. */
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()
    private val _availability = MutableStateFlow(TacMapChatHistoryAvailability.CLOSED)
    val availability: StateFlow<TacMapChatHistoryAvailability> = _availability.asStateFlow()
    private val _issue = MutableStateFlow<String?>(null)
    val issue: StateFlow<String?> = _issue.asStateFlow()

    private var activeRoomId: String? = null
    private var activeFile: File? = null
    private var activeLabel: String? = null
    private var replay: List<TacMapChatReplayRecord> = emptyList()
    private var unreadInboundMessageIds: Set<String> = emptySet()

    @Synchronized
    fun open(roomId: String): Boolean {
        close()
        if (!TacMapChatIds.isCanonical32(roomId)) return failCorrupt("Invalid chat room identity")

        val namingKey = try {
            SafeStore.keyProvider.key()
        } catch (_: DataKey.LockedException) {
            return failLocked()
        } catch (_: DataKey.UnrecoverableException) {
            return failLocked()
        } catch (_: Throwable) {
            return failLocked()
        }

        val file = try {
            // Resolve only the requested room. The unlock-time cold-upgrade
            // scan reports inactive-room failures without taking an unrelated
            // active chat room offline.
            SyncLocalStore.resolveFile(
                directory = directory,
                roomId = roomId,
                domain = SyncIdentity.LocalStoreDomain.CHAT,
                dataKey = namingKey,
            )
        } catch (_: Throwable) {
            return failUnavailable("Encrypted chat history could not be migrated")
        } finally {
            namingKey.fill(0)
        }
        val label = "sync/chat/$roomId"
        if (file.exists()) {
            val raw = runCatching { file.readBytes() }.getOrElse {
                return failCorrupt("Encrypted chat history could not be read")
            }
            if (!SealedEnvelope.isSealedFile(raw)) {
                return failCorrupt("Unencrypted chat history was rejected")
            }
            if (raw.size > MAX_ENCODED_HISTORY_BYTES + SEALED_FILE_OVERHEAD_ALLOWANCE) {
                return failCorrupt("Encrypted chat history exceeded its storage limit")
            }
        }

        return when (val loaded = SafeStore.readOrQuarantine(file, label) {
            decodeHistory(it, expectedRoomId = roomId)
        }) {
            SafeStore.LoadResult.Empty -> activate(
                roomId, file, label,
                TacMapChatHistoryEnvelope(STORE_VERSION, emptyList(), emptyList()),
            )
            is SafeStore.LoadResult.Loaded -> activate(roomId, file, label, loaded.value)
            is SafeStore.LoadResult.Locked -> failLocked()
            is SafeStore.LoadResult.Corrupt -> failCorrupt(
                "Encrypted chat history could not be authenticated"
            )
        }
    }

    @Synchronized
    fun close() {
        _messages.value = emptyList()
        publishUnread(emptySet())
        activeRoomId = null
        activeFile = null
        activeLabel = null
        replay = emptyList()
        _availability.value = TacMapChatHistoryAvailability.CLOSED
        _issue.value = null
    }

    @Synchronized
    fun lock() {
        _messages.value = emptyList()
        publishUnread(emptySet())
        activeRoomId = null
        activeFile = null
        activeLabel = null
        replay = emptyList()
        _availability.value = TacMapChatHistoryAvailability.LOCKED
        _issue.value = "Unlock mission data to use TacMap Chat"
    }

    /** Persists before publishing; a storage failure cannot create phantom history. */
    @Synchronized
    fun append(message: TacMapChatMessage): Boolean {
        val roomId = activeRoomId ?: return false
        if (_availability.value != TacMapChatHistoryAvailability.READY ||
            message.roomId != roomId || !message.isValid
        ) return false
        if (_messages.value.any { it.id == message.id }) return false
        // Retention follows authenticated local acceptance order. The sender's
        // createdAt value is display-only and must not control eviction.
        val candidate = (_messages.value + message).takeLast(MAX_MESSAGES_PER_ROOM)
        return persistAndPublish(candidate, replay, unreadInboundMessageIds)
    }

    @Synchronized
    fun updateDelivery(id: String, state: TacMapChatDeliveryState, failureCode: String? = null): Boolean {
        if (_availability.value != TacMapChatHistoryAvailability.READY) return false
        val index = _messages.value.indexOfFirst { it.id == id }
        if (index < 0) return false
        val candidate = _messages.value.toMutableList()
        candidate[index] = candidate[index].copy(
            deliveryState = state,
            failureCode = failureCode?.take(MAX_FAILURE_CODE_CHARS),
        )
        if (!candidate[index].isValid) return false
        return persistAndPublish(candidate, replay, unreadInboundMessageIds)
    }

    /**
     * Advances replay state and appends the authenticated plaintext in one
     * sealed atomic write. Call only after signature verification and AEAD open.
     */
    @Synchronized
    fun acceptInbound(
        message: TacMapChatMessage,
        actorId: String,
        sessionDomain: String,
        chatKeyId: String,
        counterHex: String,
        fingerprint: String,
    ): TacMapChatInboundResult {
        val roomId = activeRoomId ?: return TacMapChatInboundResult.STORE_UNAVAILABLE
        if (_availability.value != TacMapChatHistoryAvailability.READY ||
            message.roomId != roomId || !message.isValid || message.isOutgoing ||
            message.senderActorId != actorId ||
            !TacMapChatIds.isCanonical32(actorId) ||
            !TacMapChatIds.isCanonical32(sessionDomain) ||
            !TacMapChatIds.isCanonical32(chatKeyId) ||
            !TacMapChatIds.isCanonical32(fingerprint) ||
            !counterHex.matches(Regex("^[0-7][0-9a-f]{15}$")) ||
            counterHex == ZERO_COUNTER
        ) return TacMapChatInboundResult.REPLAY_REJECTED

        val identityIndex = replay.indexOfFirst {
            it.actorId == actorId && it.sessionDomain == sessionDomain && it.chatKeyId == chatKeyId
        }
        val prior = replay.getOrNull(identityIndex)
        val incomingCounter = counterHex.toLong(16)
        val priorCounter = prior?.highCounterHex?.toLong(16) ?: 0L
        if (incomingCounter <= priorCounter) {
            return if (prior?.recentFingerprints?.contains(fingerprint) == true) {
                TacMapChatInboundResult.DUPLICATE
            } else {
                TacMapChatInboundResult.REPLAY_REJECTED
            }
        }
        if (incomingCounter - priorCounter > MAX_COUNTER_ADVANCE) {
            return TacMapChatInboundResult.REPLAY_REJECTED
        }
        if (_messages.value.any { it.id == message.id }) {
            // An exact retry was handled above by its persisted fingerprint.
            // Reusing a message ID under a newer header is a protocol violation.
            return TacMapChatInboundResult.REPLAY_REJECTED
        }
        if (prior == null && replay.size >= MAX_REPLAY_IDENTITIES) {
            // Replay fences are security state, not a cache. Never evict an old
            // high-water mark merely to admit a 257th endpoint identity.
            return TacMapChatInboundResult.REPLAY_REJECTED
        }

        val nextRecord = TacMapChatReplayRecord(
            actorId = actorId,
            sessionDomain = sessionDomain,
            chatKeyId = chatKeyId,
            highCounterHex = counterHex,
            recentFingerprints = ((prior?.recentFingerprints ?: emptyList()) + fingerprint)
                .takeLast(TacMapChatReplayRecord.MAX_RECENT_FINGERPRINTS),
        )
        val nextReplay = replay.toMutableList().apply {
            if (identityIndex >= 0) set(identityIndex, nextRecord) else add(nextRecord)
        }
        // Retention follows authenticated local acceptance order. The sender's
        // createdAt value is display-only and must not control eviction.
        val nextMessages = (_messages.value + message).takeLast(MAX_MESSAGES_PER_ROOM)
        val nextUnread = unreadInboundMessageIds + message.id
        return if (persistAndPublish(nextMessages, nextReplay, nextUnread)) {
            TacMapChatInboundResult.ACCEPTED
        } else {
            TacMapChatInboundResult.STORE_UNAVAILABLE
        }
    }

    fun history(target: TacMapChatTarget): List<TacMapChatMessage> = _messages.value.filter { message ->
        when (target) {
            TacMapChatTarget.EntireRoom -> message.scope == TacMapChatScope.ROOM
            is TacMapChatTarget.SelectedUnit -> message.scope == TacMapChatScope.DIRECT &&
                message.conversationActorId == target.actorId
        }
    }

    /**
     * Acknowledges only the conversation the user actually opened. This is
     * metadata-only and uses the same sealed atomic write as history, so a failed
     * write leaves the badge set.
     */
    @Synchronized
    fun markRead(target: TacMapChatTarget): Boolean {
        if (_availability.value != TacMapChatHistoryAvailability.READY) return false
        val readIds = _messages.value.asSequence()
            .filterNot(TacMapChatMessage::isOutgoing)
            .filter { message ->
                when (target) {
                    TacMapChatTarget.EntireRoom -> message.scope == TacMapChatScope.ROOM
                    is TacMapChatTarget.SelectedUnit ->
                        message.scope == TacMapChatScope.DIRECT &&
                            message.conversationActorId == target.actorId
                }
            }
            .map(TacMapChatMessage::id)
            .toSet()
        val remaining = unreadInboundMessageIds - readIds
        if (remaining.size == unreadInboundMessageIds.size) return true
        return persistAndPublish(_messages.value, replay, remaining)
    }

    @Synchronized
    fun unreadCount(target: TacMapChatTarget): Int = _messages.value.count { message ->
        message.id in unreadInboundMessageIds && when (target) {
            TacMapChatTarget.EntireRoom -> message.scope == TacMapChatScope.ROOM
            is TacMapChatTarget.SelectedUnit ->
                message.scope == TacMapChatScope.DIRECT &&
                    message.conversationActorId == target.actorId
        }
    }

    private fun decodeHistory(raw: String, expectedRoomId: String): TacMapChatHistoryEnvelope {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_ENCODED_HISTORY_BYTES)
        val root = json.parseToJsonElement(raw).jsonObject
        val versionPrimitive = root["version"]?.jsonPrimitive
            ?: throw IllegalArgumentException("Missing chat history version")
        require(!versionPrimitive.isString)
        val probedVersion = versionPrimitive.intOrNull
            ?: throw IllegalArgumentException("Invalid chat history version")
        val expectedKeys = when (probedVersion) {
            LEGACY_STORE_VERSION -> LEGACY_TOP_LEVEL_KEYS
            STORE_VERSION -> CURRENT_TOP_LEVEL_KEYS
            else -> throw IllegalArgumentException("Unsupported chat history version")
        }
        require(root.keys == expectedKeys)
        val decoded = json.decodeFromString<TacMapChatHistoryEnvelope>(raw)
        require(decoded.version == probedVersion)
        require(decoded.messages.size <= MAX_MESSAGES_PER_ROOM)
        require(decoded.messages.all { it.roomId == expectedRoomId && it.isValid })
        require(decoded.messages.map { it.id }.toSet().size == decoded.messages.size)
        require(decoded.replay.size <= MAX_REPLAY_IDENTITIES)
        require(decoded.replay.all { it.isValid })
        require(decoded.replay.map { Triple(it.actorId, it.sessionDomain, it.chatKeyId) }.distinct().size ==
            decoded.replay.size)
        val unreadIds = if (decoded.version == LEGACY_STORE_VERSION) {
            // v1 had no unread field: an upgrade must not badge old history.
            require(decoded.unreadInboundMessageIds.isEmpty())
            emptyList()
        } else {
            decoded.unreadInboundMessageIds
        }
        require(unreadIds.distinct().size == unreadIds.size)
        require(unreadIds.all(TacMapChatIds::isCanonicalMessageId))
        val inboundIds = decoded.messages.asSequence()
            .filterNot(TacMapChatMessage::isOutgoing)
            .map(TacMapChatMessage::id)
            .toSet()
        require(unreadIds.all(inboundIds::contains))
        return decoded.copy(unreadInboundMessageIds = unreadIds)
    }

    private fun activate(
        roomId: String,
        file: File,
        label: String,
        value: TacMapChatHistoryEnvelope,
    ): Boolean {
        activeRoomId = roomId
        activeFile = file
        activeLabel = label
        _availability.value = TacMapChatHistoryAvailability.READY
        _issue.value = null
        val normalizedMessages = value.messages.map { message ->
            if (message.isOutgoing && message.deliveryState == TacMapChatDeliveryState.SENT) {
                message.copy(
                    deliveryState = TacMapChatDeliveryState.FAILED,
                    failureCode = INTERRUPTED_FAILURE_CODE,
                )
            } else {
                message
            }
        }
        if (normalizedMessages != value.messages || value.version != STORE_VERSION) {
            // Pending frames are memory-only and cannot resume after a restart.
            // Persist that failure and the v1 metadata migration before exposing
            // history so stale state can never reopen looking successful.
            return persistAndPublish(
                normalizedMessages,
                value.replay,
                value.unreadInboundMessageIds.toSet(),
            )
        }
        _messages.value = normalizedMessages
        replay = value.replay
        publishUnread(value.unreadInboundMessageIds.toSet())
        return true
    }

    private fun persistAndPublish(
        candidateMessages: List<TacMapChatMessage>,
        candidateReplay: List<TacMapChatReplayRecord>,
        candidateUnreadInboundMessageIds: Set<String>,
    ): Boolean {
        val file = activeFile ?: return false
        val label = activeLabel ?: return false
        val normalizedUnread = retainedUnreadInboundMessageIds(
            candidateMessages,
            candidateUnreadInboundMessageIds,
        )
        val envelope = TacMapChatHistoryEnvelope(
            version = STORE_VERSION,
            messages = candidateMessages,
            replay = candidateReplay,
            unreadInboundMessageIds = normalizedUnread,
        )
        val encoded = runCatching { json.encodeToString(envelope) }.getOrElse {
            _issue.value = "Chat history could not be encoded"
            return false
        }
        if (encoded.toByteArray(Charsets.UTF_8).size > MAX_ENCODED_HISTORY_BYTES) {
            _issue.value = "Chat history reached its protected storage limit"
            return false
        }
        return runCatching { SafeStore.writeAtomically(file, label, encoded) }
            .fold(
                onSuccess = {
                    _messages.value = candidateMessages
                    replay = candidateReplay
                    publishUnread(normalizedUnread.toSet())
                    _issue.value = null
                    true
                },
                onFailure = { error ->
                    if (error is DataKey.LockedException || error is DataKey.UnrecoverableException) {
                        lock()
                    } else {
                        _issue.value = "Chat history could not be saved"
                        _availability.value = TacMapChatHistoryAvailability.UNAVAILABLE
                    }
                    false
                },
            )
    }

    private fun failLocked(): Boolean {
        lock()
        return false
    }

    private fun failCorrupt(message: String): Boolean {
        _messages.value = emptyList()
        publishUnread(emptySet())
        activeRoomId = null
        activeFile = null
        activeLabel = null
        replay = emptyList()
        _availability.value = TacMapChatHistoryAvailability.CORRUPT
        _issue.value = message
        return false
    }

    private fun failUnavailable(message: String): Boolean {
        _messages.value = emptyList()
        publishUnread(emptySet())
        activeRoomId = null
        activeFile = null
        activeLabel = null
        replay = emptyList()
        _availability.value = TacMapChatHistoryAvailability.UNAVAILABLE
        _issue.value = message
        return false
    }

    private fun publishUnread(ids: Set<String>) {
        unreadInboundMessageIds = ids
        _unreadCount.value = ids.size
    }

    companion object {
        const val MAX_MESSAGES_PER_ROOM = 500
        const val MAX_ENCODED_HISTORY_BYTES = 2_097_152
        private const val LEGACY_STORE_VERSION = 1
        private const val STORE_VERSION = 2
        private val LEGACY_TOP_LEVEL_KEYS = setOf("version", "messages", "replay")
        private val CURRENT_TOP_LEVEL_KEYS = LEGACY_TOP_LEVEL_KEYS + "unreadInboundMessageIds"
        private const val ZERO_COUNTER = "0000000000000000"
        private const val MAX_COUNTER_ADVANCE = 10_000L
        private const val MAX_REPLAY_IDENTITIES = 256
        private const val SEALED_FILE_OVERHEAD_ALLOWANCE = 128
        private const val MAX_FAILURE_CODE_CHARS = 64
        private const val INTERRUPTED_FAILURE_CODE = "interrupted"
        private const val DIRECTORY_NAME = "tacmap_chat"

        internal fun forTests(directory: File): TacMapChatHistoryStore =
            TacMapChatHistoryStore(directory)
    }
}

/** Retention pruning can never leave a badge pointing at an evicted/outgoing message. */
internal fun retainedUnreadInboundMessageIds(
    retainedMessages: List<TacMapChatMessage>,
    candidateIds: Set<String>,
): List<String> = retainedMessages.asSequence()
    .filterNot(TacMapChatMessage::isOutgoing)
    .map(TacMapChatMessage::id)
    .filter(candidateIds::contains)
    .toList()
