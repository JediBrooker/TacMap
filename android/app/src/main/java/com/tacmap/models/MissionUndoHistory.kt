package com.tacmap.models

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The mission store an undoable action was recorded in. */
enum class UndoTarget { SYMBOLS, DRAWINGS }

/**
 * One chronological undo/redo order across the symbol and drawing stores.
 * Each store keeps its own snapshots; this only remembers which store every
 * step went to, so Undo reverses the most recent action whatever its type.
 * iOS gets the same behaviour from one `UndoManager` shared by both stores.
 *
 * Call [recorded] whenever a store pushes an undo step. [undo] and [redo] ask
 * the matching store to act and only move the step when it succeeds, so a
 * failed write leaves the order untouched for a retry.
 */
class MissionUndoHistory(private val storeLimit: Int = STORE_UNDO_LIMIT) {
    private val undoSteps = ArrayDeque<UndoTarget>()
    private val redoSteps = ArrayDeque<UndoTarget>()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    /** A new action in [target]. Like the stores, it clears every redo step. */
    @Synchronized
    fun recorded(target: UndoTarget) {
        // Each store drops its oldest snapshot past its limit; drop that
        // store's oldest step here too so the two never drift apart.
        if (undoSteps.count { it == target } >= storeLimit) {
            undoSteps.removeAt(undoSteps.indexOfFirst { it == target })
        }
        undoSteps.addLast(target)
        redoSteps.clear()
        publish()
    }

    /** Undoes the most recent step. [perform] runs the store's own undo and
     * returns whether it succeeded. The lock is not held while it runs, so a
     * store that records a step from another thread cannot deadlock. */
    fun undo(perform: (UndoTarget) -> Boolean): Boolean {
        val target = synchronized(this) { undoSteps.lastOrNull() } ?: return false
        if (!perform(target)) return false
        move(target, from = undoSteps, to = redoSteps)
        return true
    }

    /** Redoes the most recently undone step; see [undo]. */
    fun redo(perform: (UndoTarget) -> Boolean): Boolean {
        val target = synchronized(this) { redoSteps.lastOrNull() } ?: return false
        if (!perform(target)) return false
        move(target, from = redoSteps, to = undoSteps)
        return true
    }

    @Synchronized
    private fun move(target: UndoTarget, from: ArrayDeque<UndoTarget>, to: ArrayDeque<UndoTarget>) {
        val index = from.lastIndexOf(target)
        if (index >= 0) from.removeAt(index)
        to.addLast(target)
        publish()
    }

    private fun publish() {
        _canUndo.value = undoSteps.isNotEmpty()
        _canRedo.value = redoSteps.isNotEmpty()
    }

    companion object {
        /** Snapshots each mission store keeps before dropping the oldest. */
        const val STORE_UNDO_LIMIT = 50
    }
}
