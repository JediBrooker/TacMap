package com.tacmap.sync

import org.json.JSONObject

private const val MAX_LEGACY_SYNC_VERSION = 1_000_000_000_000L

internal fun strictJsonInteger(value: Any?, minimum: Long, maximum: Long): Long? {
    val parsed = when (value) {
        is Int -> value.toLong()
        is Long -> value
        else -> return null
    }
    return parsed.takeIf { it in minimum..maximum }
}

internal fun shouldResendRecoverableDelete(
    wireObjectId: String,
    stamp: VersionStamp,
    confirmedSnapshotDeletes: Map<String, String>,
): Boolean = confirmedSnapshotDeletes[wireObjectId] != stamp.encode()

/** Exact metadata for one currently desired outbound object generation.
 * Queue acceptance is deliberately absent: only [acknowledge] retires it. */
internal data class PendingOutboundDelivery(
    val localId: String,
    val requestId: String,
    val connectionGeneration: Long,
    val actorId: String,
    val sessionDomain: String?,
    val wireObjectId: String,
    val version: String,
    val kind: String,
    val ciphertextHash: String,
    val desiredContentHash: String?,
    val desiredContent: String?,
    val frame: String,
    val attempts: Int = 1,
    val rejectionCode: String? = null,
    val rejectionRetryable: Boolean? = null,
)

internal data class DeliveryAck(
    val version: Int,
    val requestId: String,
    val actorId: String,
    val sessionDomain: String?,
    val wireObjectId: String,
    val objectVersion: String,
    val kind: String,
    val ciphertextHash: String,
)

internal data class DeliveryNack(
    val version: Int,
    val requestId: String,
    val actorId: String,
    val sessionDomain: String?,
    val code: String,
    val retryable: Boolean,
)

internal fun parseDeliveryAckFrame(message: JSONObject, v3: Boolean): DeliveryAck? {
    val version = strictJsonInteger(message.opt("av"), 1, 1)?.toInt() ?: return null
    val requestId = message.optString("rid").takeIf { it.matches(Regex("^[A-Za-z0-9_-]{16,64}$")) }
        ?: return null
    val actor = message.optString("by").ifEmpty { return null }
    val session = if (v3) message.optString("sd").ifEmpty { return null } else null
    val wireId = message.optString("id").ifEmpty { return null }
    val objectVersion = if (v3) {
        message.optString("vs").ifEmpty { return null }
    } else {
        strictJsonInteger(message.opt("v"), 0, MAX_LEGACY_SYNC_VERSION)?.toString() ?: return null
    }
    val kind = message.optString("kind").ifEmpty { return null }
    val ciphertextHash = message.optString("cth")
        .takeIf { SyncIdentity.urlB64Decode32(it) != null } ?: return null
    return DeliveryAck(
        version, requestId, actor, session, wireId, objectVersion, kind, ciphertextHash,
    )
}

internal fun parseDeliveryNackFrame(message: JSONObject, v3: Boolean): DeliveryNack? {
    val version = strictJsonInteger(message.opt("av"), 1, 1)?.toInt() ?: return null
    val requestId = message.optString("rid").takeIf { it.matches(Regex("^[A-Za-z0-9_-]{16,64}$")) }
        ?: return null
    val actor = message.optString("by").ifEmpty { return null }
    val session = if (v3) message.optString("sd").ifEmpty { return null } else null
    val code = message.optString("code").takeIf { it.matches(Regex("^[a-z-]{1,32}$")) }
        ?: return null
    val retryable = message.opt("retry") as? Boolean ?: return null
    return DeliveryNack(version, requestId, actor, session, code, retryable)
}

internal data class LegacyDeleteRecovery(
    val localId: String,
    val requestId: String,
    val actorId: String,
    val wireObjectId: String,
    val objectVersion: String,
    val kind: String,
    val ciphertextHash: String,
    val snapshotGeneration: Long,
) {
    fun matchesVerifiedTombstone(
        localId: String,
        wireObjectId: String,
        actorId: String,
        objectVersion: String,
        kind: String,
        ciphertextHash: String,
        snapshotGeneration: Long,
    ): Boolean = this.localId == localId && this.wireObjectId == wireObjectId &&
        this.actorId == actorId && this.objectVersion == objectVersion &&
        this.kind == kind && this.ciphertextHash == ciphertextHash &&
        this.snapshotGeneration == snapshotGeneration

    companion object {
        fun from(delivery: PendingOutboundDelivery, snapshotGeneration: Long) = LegacyDeleteRecovery(
            localId = delivery.localId,
            requestId = delivery.requestId,
            actorId = delivery.actorId,
            wireObjectId = delivery.wireObjectId,
            objectVersion = delivery.version,
            kind = delivery.kind,
            ciphertextHash = delivery.ciphertextHash,
            snapshotGeneration = snapshotGeneration,
        )
    }
}

/** One pending generation per local object. Delayed/duplicate/other-session
 * replies cannot affect a replacement operation because every field is exact. */
internal class OutboundDeliveryTracker(private val maxAttempts: Int = 5) {
    private val byLocalId = LinkedHashMap<String, PendingOutboundDelivery>()
    private val localIdByRequest = HashMap<String, String>()

    @Synchronized
    fun register(delivery: PendingOutboundDelivery): PendingOutboundDelivery? {
        val prior = byLocalId.put(delivery.localId, delivery)
        prior?.let { localIdByRequest.remove(it.requestId) }
        localIdByRequest[delivery.requestId] = delivery.localId
        return prior
    }

    @Synchronized fun pending(localId: String): PendingOutboundDelivery? = byLocalId[localId]
    @Synchronized fun all(): List<PendingOutboundDelivery> = byLocalId.values.toList()

    @Synchronized
    fun acknowledge(ack: DeliveryAck): PendingOutboundDelivery? {
        if (ack.version != 1) return null
        val localId = localIdByRequest[ack.requestId] ?: return null
        val pending = byLocalId[localId] ?: return null
        if (pending.requestId != ack.requestId || pending.actorId != ack.actorId ||
            pending.sessionDomain != ack.sessionDomain || pending.wireObjectId != ack.wireObjectId ||
            pending.version != ack.objectVersion || pending.kind != ack.kind ||
            pending.ciphertextHash != ack.ciphertextHash) return null
        byLocalId.remove(localId)
        localIdByRequest.remove(ack.requestId)
        return pending
    }

    @Synchronized
    fun reject(nack: DeliveryNack): PendingOutboundDelivery? {
        if (nack.version != 1) return null
        val localId = localIdByRequest[nack.requestId] ?: return null
        val pending = byLocalId[localId] ?: return null
        if (pending.actorId != nack.actorId || pending.sessionDomain != nack.sessionDomain) return null
        val updated = pending.copy(rejectionCode = nack.code, rejectionRetryable = nack.retryable)
        byLocalId[localId] = updated
        return updated
    }

    /** Returns a replacement attempt, or null once the bounded watchdog is exhausted. */
    @Synchronized
    fun nextAttempt(requestId: String, generation: Long, sessionDomain: String?): PendingOutboundDelivery? {
        val localId = localIdByRequest[requestId] ?: return null
        val pending = byLocalId[localId] ?: return null
        if (pending.connectionGeneration != generation || pending.sessionDomain != sessionDomain ||
            pending.attempts >= maxAttempts || pending.rejectionRetryable == false ||
            pending.rejectionCode?.let { it !in RETRYABLE_CODES } == true) return null
        return pending.copy(
            attempts = pending.attempts + 1,
            rejectionCode = null,
            rejectionRetryable = null,
        ).also {
            byLocalId[localId] = it
        }
    }

    @Synchronized
    fun resetForReconnect(): Set<String> {
        val ids = byLocalId.keys.toSet()
        byLocalId.clear()
        localIdByRequest.clear()
        return ids
    }

    companion object {
        private val RETRYABLE_CODES = setOf("storage", "hello-required")
    }
}
