package com.tacmap.sync

import org.json.JSONObject
import java.util.UUID

/**
 * Pre-send size check (plans/04 section 11.4, S1-09). Measure the exact frame
 * before reserving a stamp so an object the relay will never take doesn't burn
 * a counter and then loop on `invalid`.
 */
internal object OutboundSizeCheck {
    const val CT_MAX_CHARS = 700_000L
    const val MAX_FRAME_BYTES = 1_048_576L
    const val SIGNATURE_PLACEHOLDER_CHARS = 86
    private const val AEAD_OVERHEAD_BYTES = 28 // 12 nonce + 16 tag

    /** Padded standard base64 length of the sealed inner JSON. */
    fun ctChars(innerUtf8Bytes: Long): Long = 4L * ((innerUtf8Bytes + AEAD_OVERHEAD_BYTES + 2) / 3)

    fun fits(innerUtf8Bytes: Long, frameBytes: Long): Boolean =
        ctChars(innerUtf8Bytes) <= CT_MAX_CHARS && frameBytes <= MAX_FRAME_BYTES

    /** Builds the real v3 put shapes with placeholders of the real lengths. */
    fun v3PutFits(
        wireId: String,
        actorId: String,
        pub: String,
        sessionDomain: String,
        kind: String,
        content: String,
    ): Boolean {
        val inner = JSONObject().apply {
            put("c", content)
            put("sig", "A".repeat(SIGNATURE_PLACEHOLDER_CHARS))
        }.toString()
        val innerBytes = inner.toByteArray(Charsets.UTF_8).size.toLong()
        val ct = ctChars(innerBytes)
        if (ct > CT_MAX_CHARS) return false
        val frame = JSONObject().apply {
            put("t", "put"); put("id", wireId); put("vs", "0".repeat(16) + ":" + actorId)
            put("by", actorId); put("kind", kind); put("ct", "A".repeat(ct.toInt())); put("pub", pub)
            put("sd", sessionDomain); put("rid", "0".repeat(32))
        }.toString()
        return frame.toByteArray(Charsets.UTF_8).size <= MAX_FRAME_BYTES
    }
}

/**
 * v2 legacy ids (plans/04 section 16, S3-01 / S3-14). Accept either case on
 * the way in, verify with the raw id, then key everything by lowercase. Ties
 * on `v` go to the larger `by` compared as ASCII bytes, same as the relay.
 * Outbound reuses the casing a 2.x iOS sender used for the object (3.0.1
 * amendment), since 2.x iOS echo-deletes an edit that comes back lowercase.
 */
internal object LegacyV2Ids {
    private val PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    fun stateKey(raw: String): String? {
        if (!PATTERN.matches(raw)) return null
        val lower = raw.lowercase()
        return runCatching { UUID.fromString(lower).toString() }.getOrNull()?.takeIf { it == lower }
    }

    /** Frame id for a v2 put or del. The remembered raw id only counts when it folds to the same object. */
    fun outboundId(localId: String, remembered: String?): String =
        remembered?.takeIf { stateKey(it) != null && stateKey(it) == stateKey(localId) } ?: localId

    /**
     * What's remembered after an accepted inbound record carrying [raw]. The
     * first raw id with an uppercase letter sticks, lowercase never replaces it.
     */
    fun remember(current: String?, raw: String): String? = when {
        current != null -> current
        stateKey(raw) == null -> null
        raw.any { it in 'A'..'F' } -> raw
        else -> null
    }

    fun embeddedMatches(recordId: String, embeddedId: String): Boolean =
        stateKey(recordId) != null && stateKey(embeddedId) == stateKey(recordId)

    fun beats(v: Long, by: String, lastV: Long?, lastBy: String?): Boolean {
        if (lastV == null) return true
        if (v != lastV) return v > lastV
        return compareAscii(by, lastBy ?: return true) > 0
    }

    fun compareAscii(a: String, b: String): Int {
        val x = a.toByteArray(Charsets.UTF_8)
        val y = b.toByteArray(Charsets.UTF_8)
        for (i in 0 until minOf(x.size, y.size)) {
            val d = (x[i].toInt() and 0xff) - (y[i].toInt() and 0xff)
            if (d != 0) return d
        }
        return x.size - y.size
    }
}

/**
 * What an Activity pause does to a joined room (plans/04 section 13, S2-01).
 * The session ends because the DataKey locks, but the room, keys and replay
 * state stay so foreground return reconnects without the user retyping the code.
 */
internal object SyncLifecyclePolicy {
    enum class PauseAction { ENTER_BACKGROUND_PRESENCE, SUSPEND_KEEP_ROOM, DISPOSE }

    fun onActivityPausing(roomJoined: Boolean, backgroundPresenceEligible: Boolean): PauseAction = when {
        !roomJoined -> PauseAction.DISPOSE
        backgroundPresenceEligible -> PauseAction.ENTER_BACKGROUND_PRESENCE
        else -> PauseAction.SUSPEND_KEEP_ROOM
    }
}

/**
 * Chat fence pruning (plans/04 section 15.1, S3-04 / S4-06). A fence whose
 * actor now has a different durable session domain can never validate again,
 * because chat only comes from the actor's current authenticated session and a
 * session only becomes current through a strictly newer hello.
 */
internal object ChatReplayPruner {
    const val MAX_FENCES = 256
    const val FINGERPRINTS_KEPT_ON_WRITE = 16
    const val FINGERPRINTS_ACCEPTED_ON_LOAD = 64

    fun isSuperseded(actorId: String, sessionDomain: String, durableSessionDomain: (String) -> String?): Boolean {
        val current = durableSessionDomain(actorId) ?: return false
        return current != sessionDomain
    }

    fun <T> prune(
        fences: List<T>,
        identity: (T) -> Pair<String, String>,
        durableSessionDomain: (String) -> String?,
    ): List<T> = fences.filterNot { fence ->
        val (actor, sd) = identity(fence)
        isSuperseded(actor, sd, durableSessionDomain)
    }

    /** Room for one more identity after pruning? */
    fun canAdmitNewIdentity(currentFences: Int): Boolean = currentFences < MAX_FENCES
}

/** Chat history byte cap with hysteresis (plans/04 section 15.2, S4-07). */
internal object ChatHistoryBudget {
    const val MAX_MESSAGES = 500
    const val MAX_ENCODED_BYTES = 2_097_152L
    const val PRUNE_TARGET_BYTES = 1_572_864L

    fun encodedSize(fixedOverheadBytes: Long, separatorBytes: Int, messageBytes: List<Int>): Long =
        fixedOverheadBytes + messageBytes.sumOf { it.toLong() } +
            separatorBytes.toLong() * (messageBytes.size - 1).coerceAtLeast(0)

    /** How many of the oldest messages to drop. Zero when the document fits. */
    fun dropCount(fixedOverheadBytes: Long, separatorBytes: Int, messageBytes: List<Int>): Int {
        if (encodedSize(fixedOverheadBytes, separatorBytes, messageBytes) <= MAX_ENCODED_BYTES) return 0
        var total = encodedSize(fixedOverheadBytes, separatorBytes, messageBytes)
        var dropped = 0
        while (dropped < messageBytes.size && total > PRUNE_TARGET_BYTES) {
            total -= messageBytes[dropped]
            if (messageBytes.size - dropped > 1) total -= separatorBytes
            dropped += 1
        }
        return dropped
    }
}
