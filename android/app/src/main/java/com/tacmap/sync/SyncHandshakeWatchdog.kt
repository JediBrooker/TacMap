package com.tacmap.sync

/**
 * Pre-CONNECTED watchdogs (plans/04 section 9). Progress based so a big room
 * on a slow link still finishes, but a black-holed upgrade or a stalled
 * snapshot can't leave us stuck in CONNECTING forever (S2-02, S2-03, S3-05).
 *
 *  - connect/open: 30 s from socket creation to open (or first message)
 *  - stall: 60 s with no inbound progress, from open until hello is written,
 *    suspended while we validate/apply between snapshot-end and hello
 *  - hello-ack: 30 s after hello is written
 *  - absolute: 900 s from socket creation, no matter what
 *
 * Pure state machine, the manager polls [check] and sleeps until [nextDeadlineMs].
 */
internal class SyncHandshakeWatchdog {
    private var createdAtMs: Long? = null
    private var openedAtMs: Long? = null
    private var lastProgressMs: Long? = null
    private var stallSuspended = false
    private var helloWrittenAtMs: Long? = null
    private var done = true

    fun socketCreated(nowMs: Long) {
        createdAtMs = nowMs
        openedAtMs = null
        lastProgressMs = null
        stallSuspended = false
        helloWrittenAtMs = null
        done = false
    }

    /** Transport open (101) or the first inbound message, whichever is first. */
    fun opened(nowMs: Long) {
        if (done) return
        if (openedAtMs == null) openedAtMs = nowMs
        progress(nowMs)
    }

    /** Inbound bytes, a complete message, or a local validation step finishing. */
    fun progress(nowMs: Long) {
        if (done) return
        if (openedAtMs == null) openedAtMs = nowMs
        val last = lastProgressMs
        if (last == null || nowMs > last) lastProgressMs = nowMs
    }

    /** snapshot-end received: local apply time is not the network's fault. */
    fun snapshotEnded(nowMs: Long) {
        progress(nowMs)
        stallSuspended = true
    }

    fun helloWritten(nowMs: Long) {
        if (done) return
        stallSuspended = true
        helloWrittenAtMs = nowMs
    }

    fun connected() {
        done = true
    }

    fun cancel() {
        done = true
    }

    val isActive: Boolean get() = !done

    fun check(nowMs: Long): SyncLocalClose? {
        if (done) return null
        val created = createdAtMs ?: return null
        if (nowMs - created >= ABSOLUTE_MAX_MS) return SyncLocalClose.HANDSHAKE_ABSOLUTE
        val opened = openedAtMs
        if (opened == null) {
            return if (nowMs - created >= CONNECT_OPEN_TIMEOUT_MS) SyncLocalClose.CONNECT_OPEN_TIMEOUT else null
        }
        helloWrittenAtMs?.let { written ->
            return if (nowMs - written >= HELLO_ACK_TIMEOUT_MS) SyncLocalClose.HELLO_ACK_TIMEOUT else null
        }
        if (!stallSuspended) {
            val last = lastProgressMs ?: opened
            if (nowMs - last >= STALL_TIMEOUT_MS) return SyncLocalClose.HANDSHAKE_STALL
        }
        return null
    }

    fun nextDeadlineMs(): Long? {
        if (done) return null
        val created = createdAtMs ?: return null
        val absolute = created + ABSOLUTE_MAX_MS
        val phase = when {
            openedAtMs == null -> created + CONNECT_OPEN_TIMEOUT_MS
            helloWrittenAtMs != null -> helloWrittenAtMs!! + HELLO_ACK_TIMEOUT_MS
            stallSuspended -> null
            else -> (lastProgressMs ?: openedAtMs!!) + STALL_TIMEOUT_MS
        }
        return if (phase == null) absolute else minOf(phase, absolute)
    }

    companion object {
        const val CONNECT_OPEN_TIMEOUT_MS = 30_000L
        const val STALL_TIMEOUT_MS = 60_000L
        const val HELLO_ACK_TIMEOUT_MS = 30_000L
        const val ABSOLUTE_MAX_MS = 900_000L
    }
}
