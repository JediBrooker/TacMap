package com.tacmap.sync

/** Issue keys from the client contract fixture (`issues`). Kind and scope live
 * here so the manager can't drift from the table. */
internal enum class SyncIssueCode(val kind: SyncIssueKind) {
    SKIPPED_UNSUPPORTED(SyncIssueKind.CONNECTION),
    SKIPPED_UNVERIFIED(SyncIssueKind.SECURITY),
    ROOM_RESET_SUSPECTED(SyncIssueKind.SECURITY),
    ROOM_RESET_CHANGES_PAUSED(SyncIssueKind.CONNECTION),
    OBJECT_TOO_LARGE(SyncIssueKind.CONNECTION),
    ROOM_QUOTA_NACK(SyncIssueKind.CONNECTION),
    RELAY_INVALID_NACK(SyncIssueKind.CONNECTION),
    UNCONFIRMED_RECONNECT(SyncIssueKind.CONNECTION),
    SNAPSHOT_STRUCTURAL(SyncIssueKind.SECURITY),
    RELAY_BUSY(SyncIssueKind.CONNECTION),
    RELAY_RATE_LIMITED(SyncIssueKind.CONNECTION),
    ROOM_FULL_CANNOT_JOIN(SyncIssueKind.CONNECTION),
    RELAY_REFUSED_ROOM(SyncIssueKind.CONNECTION),
    IDENTITY_REJECTED(SyncIssueKind.SECURITY),
    SESSION_CONFLICT(SyncIssueKind.CONNECTION),
    SESSION_COUNTER_BEHIND(SyncIssueKind.CONNECTION),
    SNAPSHOT_MALFORMED_STOPPED(SyncIssueKind.SECURITY),
    CHAT_REPLAY_FULL(SyncIssueKind.CONNECTION),
    BACKGROUND_PAUSED(SyncIssueKind.CONNECTION),
}

internal enum class SyncCloseAction { NONE, RECONNECT, RECONNECT_NOW, STOP, ESCALATE_EPOCH_THEN_RECONNECT }

/** Closes we start ourselves. Classified by this reason, never by whatever
 * code the transport echoes back at us. */
internal enum class SyncLocalClose(val wireName: String) {
    LEAVE("leave"),
    LIFECYCLE_PAUSE("lifecyclePause"),
    RECEIVE_BUDGET_EXCEEDED("receiveBudgetExceeded"),
    OVERSIZED_INBOUND("oversizedInbound"),
    BINARY_INBOUND("binaryInbound"),
    CONNECT_OPEN_TIMEOUT("connectOpenTimeout"),
    HANDSHAKE_STALL("handshakeStall"),
    HELLO_ACK_TIMEOUT("helloAckTimeout"),
    HANDSHAKE_ABSOLUTE("handshakeAbsolute"),
    LIVENESS_TIMEOUT("livenessTimeout"),
    ACK_EXHAUSTED("ackExhausted"),
    STRUCTURAL_SNAPSHOT("structuralSnapshot"),
    LIVE_WINDOW_RESYNC("liveWindowResync"),
    SESSION_NACK("sessionNack"),
    HELLO_REQUIRED_NACK("helloRequiredNack"),
    PERSISTENCE_FAILURE("persistenceFailure"),
}

internal data class SyncCloseDecision(
    val action: SyncCloseAction,
    val backoffClass: SyncBackoffClass? = null,
    val issue: SyncIssueCode? = null,
    /** Raise [issue] only once this many consecutive failures of this kind happened. */
    val surfaceAfterConsecutive: Int = 1,
    val retryOnForeground: Boolean = false,
    val pacerAfter4008: Boolean = false,
    val countsAsSessionConflict: Boolean = false,
    val localCloseCode: Int? = null,
)

/**
 * The static close/status tables (plans/04 section 7.2). Counting (session
 * conflicts, busy chains, structural failures, 4014 escalations) is in
 * [SyncFailureCounters] so this stays a pure lookup.
 */
internal object SyncCloseClassifier {
    private val HANDSHAKE_STATUS = Regex("Invalid status code received: (\\d{3})")

    /** Java-WebSocket only tells us the upgrade status through its failure text. */
    fun parseHandshakeStatus(reason: String?): Int? =
        reason?.let { HANDSHAKE_STATUS.find(it)?.groupValues?.get(1)?.toIntOrNull() }

    fun classifyHttp(status: Int?): SyncCloseDecision = when (status) {
        401, 403, 404, 426 -> SyncCloseDecision(
            SyncCloseAction.STOP, issue = SyncIssueCode.RELAY_REFUSED_ROOM, retryOnForeground = false,
        )
        429 -> SyncCloseDecision(
            SyncCloseAction.RECONNECT, SyncBackoffClass.SLOW_RATE,
            issue = SyncIssueCode.RELAY_RATE_LIMITED, surfaceAfterConsecutive = 1,
        )
        503 -> SyncCloseDecision(
            SyncCloseAction.RECONNECT, SyncBackoffClass.SLOW_BUSY,
            issue = SyncIssueCode.RELAY_BUSY, surfaceAfterConsecutive = BUSY_FAILURES_BEFORE_SURFACE,
        )
        null -> SyncCloseDecision(SyncCloseAction.RECONNECT, SyncBackoffClass.TRANSIENT)
        in 500..599 -> SyncCloseDecision(SyncCloseAction.RECONNECT, SyncBackoffClass.TRANSIENT)
        in 400..499 -> SyncCloseDecision(SyncCloseAction.RECONNECT, SyncBackoffClass.SLOW_UNKNOWN)
        // a 1xx/2xx/3xx that still isn't a 101 is just a broken path
        else -> SyncCloseDecision(SyncCloseAction.RECONNECT, SyncBackoffClass.TRANSIENT)
    }

    fun classifyClose(code: Int, afterOwnLeave: Boolean = false): SyncCloseDecision = when (code) {
        1000 -> if (afterOwnLeave) {
            SyncCloseDecision(SyncCloseAction.NONE)
        } else {
            SyncCloseDecision(SyncCloseAction.RECONNECT, SyncBackoffClass.TRANSIENT)
        }
        1011 -> SyncCloseDecision(
            SyncCloseAction.RECONNECT, SyncBackoffClass.SLOW_STORAGE,
            issue = SyncIssueCode.RELAY_BUSY, surfaceAfterConsecutive = BUSY_FAILURES_BEFORE_SURFACE,
        )
        1013 -> SyncCloseDecision(
            SyncCloseAction.RECONNECT, SyncBackoffClass.SLOW_BUSY,
            issue = SyncIssueCode.RELAY_BUSY, surfaceAfterConsecutive = BUSY_FAILURES_BEFORE_SURFACE,
        )
        4008 -> SyncCloseDecision(SyncCloseAction.RECONNECT, SyncBackoffClass.TRANSIENT, pacerAfter4008 = true)
        4009 -> SyncCloseDecision(SyncCloseAction.RECONNECT, SyncBackoffClass.SLOW_UNKNOWN)
        4010, 4011 -> SyncCloseDecision(
            SyncCloseAction.STOP, issue = SyncIssueCode.IDENTITY_REJECTED, retryOnForeground = false,
        )
        4012 -> SyncCloseDecision(SyncCloseAction.RECONNECT, SyncBackoffClass.TRANSIENT)
        4013 -> SyncCloseDecision(
            SyncCloseAction.STOP, issue = SyncIssueCode.ROOM_FULL_CANNOT_JOIN, retryOnForeground = true,
        )
        4014 -> SyncCloseDecision(
            SyncCloseAction.ESCALATE_EPOCH_THEN_RECONNECT, SyncBackoffClass.TRANSIENT,
            issue = SyncIssueCode.SESSION_COUNTER_BEHIND,
        )
        4015 -> SyncCloseDecision(
            SyncCloseAction.RECONNECT, SyncBackoffClass.TRANSIENT,
            issue = SyncIssueCode.SESSION_CONFLICT, retryOnForeground = true,
            countsAsSessionConflict = true,
        )
        in 1000..1999 -> SyncCloseDecision(SyncCloseAction.RECONNECT, SyncBackoffClass.TRANSIENT)
        in 4000..4999 -> SyncCloseDecision(SyncCloseAction.RECONNECT, SyncBackoffClass.SLOW_UNKNOWN)
        // -1 (never connected without a status) and anything weird: plain transport loss
        else -> SyncCloseDecision(SyncCloseAction.RECONNECT, SyncBackoffClass.TRANSIENT)
    }

    fun classifyLocal(reason: SyncLocalClose): SyncCloseDecision = when (reason) {
        SyncLocalClose.LEAVE, SyncLocalClose.LIFECYCLE_PAUSE, SyncLocalClose.PERSISTENCE_FAILURE ->
            SyncCloseDecision(SyncCloseAction.NONE)
        SyncLocalClose.RECEIVE_BUDGET_EXCEEDED -> SyncCloseDecision(
            SyncCloseAction.RECONNECT, SyncBackoffClass.TRANSIENT, localCloseCode = 1008,
        )
        SyncLocalClose.OVERSIZED_INBOUND -> SyncCloseDecision(
            SyncCloseAction.RECONNECT, SyncBackoffClass.TRANSIENT, localCloseCode = 1009,
        )
        SyncLocalClose.BINARY_INBOUND -> SyncCloseDecision(
            SyncCloseAction.RECONNECT, SyncBackoffClass.TRANSIENT, localCloseCode = 1003,
        )
        SyncLocalClose.CONNECT_OPEN_TIMEOUT, SyncLocalClose.HANDSHAKE_STALL,
        SyncLocalClose.HELLO_ACK_TIMEOUT, SyncLocalClose.HANDSHAKE_ABSOLUTE,
        SyncLocalClose.LIVENESS_TIMEOUT, SyncLocalClose.HELLO_REQUIRED_NACK ->
            SyncCloseDecision(SyncCloseAction.RECONNECT, SyncBackoffClass.TRANSIENT)
        SyncLocalClose.ACK_EXHAUSTED -> SyncCloseDecision(
            SyncCloseAction.RECONNECT, SyncBackoffClass.TRANSIENT, issue = SyncIssueCode.UNCONFIRMED_RECONNECT,
        )
        SyncLocalClose.STRUCTURAL_SNAPSHOT -> SyncCloseDecision(
            SyncCloseAction.RECONNECT, SyncBackoffClass.TRANSIENT, issue = SyncIssueCode.SNAPSHOT_STRUCTURAL,
        )
        SyncLocalClose.LIVE_WINDOW_RESYNC -> SyncCloseDecision(SyncCloseAction.RECONNECT_NOW)
        SyncLocalClose.SESSION_NACK -> SyncCloseDecision(
            SyncCloseAction.RECONNECT, SyncBackoffClass.TRANSIENT,
            issue = SyncIssueCode.SESSION_CONFLICT, retryOnForeground = true,
            countsAsSessionConflict = true,
        )
    }

    const val BUSY_FAILURES_BEFORE_SURFACE = 2
}

/**
 * Per-join failure counting that turns some reconnect rows into a stop.
 * Everything here is relay data, so the worst it can do is pause our own work.
 */
internal class SyncFailureCounters {
    private val sessionConflictTimes = ArrayDeque<Long>()
    private var structuralFailures = 0
    private var epochEscalations = 0
    private var busyChain = 0
    private var busySurfaced = false
    private var rateSurfaced = false

    /** True once this conflict makes it 3 in 10 min: time to stop. */
    fun recordSessionConflict(nowMs: Long): Boolean {
        while (sessionConflictTimes.isNotEmpty() &&
            nowMs - sessionConflictTimes.first() >= SESSION_CONFLICT_WINDOW_MS
        ) sessionConflictTimes.removeFirst()
        sessionConflictTimes.addLast(nowMs)
        return sessionConflictTimes.size >= SESSION_CONFLICTS_BEFORE_STOP
    }

    /** True on the third structural failure without a hello-ack in between. */
    fun recordStructuralFailure(): Boolean {
        structuralFailures += 1
        return structuralFailures >= STRUCTURAL_FAILURES_BEFORE_STOP
    }

    /** Returns false once we've already escalated 3 times this join. */
    fun tryEscalateEpoch(): Boolean {
        if (epochEscalations >= STALE_EPOCH_ESCALATIONS_BEFORE_STOP) return false
        epochEscalations += 1
        return true
    }

    /** Should a RELAY_BUSY / RELAY_RATE_LIMITED row be surfaced now. Once per chain. */
    fun recordBusy(decision: SyncCloseDecision): Boolean {
        val issue = decision.issue ?: return false
        return when (issue) {
            SyncIssueCode.RELAY_BUSY -> {
                busyChain += 1
                if (busyChain >= decision.surfaceAfterConsecutive && !busySurfaced) {
                    busySurfaced = true
                    true
                } else false
            }
            SyncIssueCode.RELAY_RATE_LIMITED -> {
                busyChain = 0
                if (!rateSurfaced) {
                    rateSurfaced = true
                    true
                } else false
            }
            else -> false
        }
    }

    /** Any failure that isn't busy breaks the "consecutive" chain. */
    fun recordOtherFailure() {
        busyChain = 0
    }

    /** hello-ack: the handshake worked, so chains restart. */
    fun helloAcked() {
        structuralFailures = 0
        busyChain = 0
        busySurfaced = false
        rateSurfaced = false
    }

    /** Retry button / new join wipes everything. */
    fun reset() {
        sessionConflictTimes.clear()
        structuralFailures = 0
        epochEscalations = 0
        busyChain = 0
        busySurfaced = false
        rateSurfaced = false
    }

    companion object {
        const val STRUCTURAL_FAILURES_BEFORE_STOP = 3
        const val SESSION_CONFLICTS_BEFORE_STOP = 3
        const val SESSION_CONFLICT_WINDOW_MS = 600_000L
        const val STALE_EPOCH_ESCALATIONS_BEFORE_STOP = 3
    }
}
