package com.tacmap.sync

/** Serialises outbound/scheduling work against disposal. Once [dispose]
 * returns, no guarded socket send or reconnect can begin, cleanup has run, and
 * every supplied secret buffer has been zeroed. */
internal class SyncLifecycleGate {
    @Volatile private var disposed = false

    val isDisposed: Boolean get() = disposed

    @Synchronized
    fun runIfActive(work: () -> Unit): Boolean {
        if (disposed) return false
        work()
        return true
    }

    @Synchronized
    fun sendIfActive(send: () -> Boolean): Boolean = !disposed && send()

    @Synchronized
    fun dispose(secrets: Iterable<ByteArray?>, cleanup: () -> Unit): Boolean {
        if (disposed) return false
        disposed = true
        cleanup()
        secrets.forEach { it?.fill(0) }
        return true
    }
}
