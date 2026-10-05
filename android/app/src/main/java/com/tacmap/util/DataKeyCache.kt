package com.tacmap.util

/**
 * The unwrapped mission DEK that [DataKey] holds for the process, plus the relock gate.
 *
 * lock() wipes the cached copy and closes the gate. While its closed get() throws
 * LockedException instead of unwrapping, so a sealed write that was still running when the
 * Activity paused can't quietly put the DEK back in memory for the whole background stint.
 * Only unlock() opens it again, and that's the app's explicit unlock: device-mode resume,
 * the App Lock PIN, a fresh platform credential. Plain Kotlin so the host tests can drive
 * the same rules DataKey runs.
 */
internal class DataKeyCache {
    private var cached: ByteArray? = null
    private var relocked = false

    val isCached: Boolean
        @Synchronized get() = cached != null

    /** lock() ran and nothing unlocked since */
    val isRelocked: Boolean
        @Synchronized get() = relocked

    /** Copy of the cached DEK, else unwrap and keep a copy. unwrap hands over an array we zero. */
    @Synchronized
    fun get(unwrap: () -> ByteArray): ByteArray {
        cached?.let { return it.copyOf() }
        // gate closed = fail, never unwrap. device mode would otherwise sit on the key for
        // the rest of the background and auth mode would grab it inside its 30 s window
        if (relocked) throw DataKey.LockedException()
        val dek = unwrap()
        try {
            store(dek)
            return dek.copyOf()
        } finally {
            dek.fill(0)
        }
    }

    /** The explicit unlock. Opens the gate and caches the DEK, a failed unwrap leaves the gate how it was. */
    @Synchronized
    fun unlock(unwrap: () -> ByteArray) {
        val wasRelocked = relocked
        relocked = false
        try {
            get(unwrap).fill(0)
        } catch (t: Throwable) {
            relocked = wasRelocked
            throw t
        }
    }

    /** Keep a copy of dek. The caller still owns (and zeroes) its own array. */
    @Synchronized
    fun store(dek: ByteArray) {
        cached?.fill(0)
        cached = dek.copyOf()
    }

    /** Lifecycle lock: wipe the copy and keep the gate shut till unlock(). */
    @Synchronized
    fun lock() {
        drop()
        relocked = true
    }

    /** Wipe the copy but leave the gate alone, the next get() unwraps fresh. */
    @Synchronized
    fun drop() {
        cached?.fill(0)
        cached = null
    }
}
