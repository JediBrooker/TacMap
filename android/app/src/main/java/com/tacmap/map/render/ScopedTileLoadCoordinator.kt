package com.tacmap.map.render

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Owns tile jobs by source identity, generation, and tile key. Reconciliation
 * removes a token before cancellation, so a late non-cooperative completion can
 * neither publish stale data nor remove a replacement job for the same key.
 */
internal class ScopedTileLoadCoordinator<Source : Any, Key : Any, Value : Any>(
    private val scope: CoroutineScope,
    private val load: suspend (Source, Key) -> Value?,
    private val publish: (Source, Key, Value) -> Unit,
    private val discard: (Value) -> Unit,
) {
    private class Token<Source, Key>(
        val source: Source,
        val generation: Long,
        val key: Key,
    )

    private data class Entry<Source, Key>(
        val token: Token<Source, Key>,
        val job: Job,
    )

    private var activeSource: Source? = null
    private var generation = 0L
    private val entries = HashMap<Key, Entry<Source, Key>>()

    /** Returns true when the source identity changed and its cache must clear. */
    @Synchronized
    fun reconcile(
        source: Source?,
        wanted: Set<Key>,
        isLoaded: (Key) -> Boolean,
        onSourceChanged: () -> Unit = {},
    ): Boolean {
        val sourceChanged = activeSource !== source
        if (sourceChanged) {
            activeSource = source
            generation = if (generation == Long.MAX_VALUE) 0L else generation + 1L
            cancelAllLocked()
            onSourceChanged()
        }

        val obsolete = entries.keys.filterTo(HashSet()) { it !in wanted }
        for (key in obsolete) removeAndCancelLocked(key)

        if (source != null) {
            for (key in wanted) {
                if (isLoaded(key)) {
                    removeAndCancelLocked(key)
                } else if (entries[key] == null) {
                    startLocked(source, key)
                }
            }
        }
        return sourceChanged
    }

    @Synchronized
    fun dispose() {
        activeSource = null
        generation = if (generation == Long.MAX_VALUE) 0L else generation + 1L
        cancelAllLocked()
    }

    private fun startLocked(source: Source, key: Key) {
        val token = Token(source, generation, key)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var value: Value? = null
            try {
                value = load(source, key)
                val accepted = complete(token)
                val delivered = value
                value = null
                delivered?.let { if (accepted) publish(source, key, it) else discard(it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Ordinary source failure is a cache miss; the next reconcile
                // may retry it. Provider health is tracked at the source.
            } finally {
                complete(token)
                value?.let(discard)
            }
        }
        entries[key] = Entry(token, job)
        job.start()
    }

    @Synchronized
    private fun complete(token: Token<Source, Key>): Boolean {
        val current = entries[token.key] ?: return false
        if (current.token !== token || activeSource !== token.source || generation != token.generation) {
            return false
        }
        entries.remove(token.key)
        return true
    }

    private fun removeAndCancelLocked(key: Key) {
        entries.remove(key)?.job?.cancel()
    }

    private fun cancelAllLocked() {
        val jobs = entries.values.map { it.job }
        entries.clear()
        jobs.forEach(Job::cancel)
    }
}
