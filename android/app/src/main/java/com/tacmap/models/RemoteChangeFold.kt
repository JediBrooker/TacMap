package com.tacmap.models

/**
 * Carries one Unit Sync write from a peer into saved undo/redo snapshots.
 *
 * Store undo restores whole snapshots. Recording a peer's write as its own undo step (what we
 * used to do) meant Undo reverted *their* work, e.g. deleting a unit they just placed room wide,
 * while your own range rings stayed put. Just skipping the step is no good either, restoring an
 * older snapshot would clobber the peer edit. So peer writes never become steps, they get folded
 * into every saved snapshot instead: updated objects take the new value where the snapshot has
 * them, deleted ones drop out, brand new ones get appended in the order the store holds them.
 * Undo/redo then only ever move the local user's own changes.
 */
internal class RemoteChangeFold<T>(
    before: List<T>,
    after: List<T>,
    private val id: (T) -> String,
) {
    private val afterById = after.associateBy(id)
    private val changed: Set<String> = before.associateBy(id).let { beforeById ->
        (beforeById.keys + afterById.keys).filterTo(HashSet()) { beforeById[it] != afterById[it] }
    }
    // only objects the peer created. An update to something a snapshot predates stays out of it,
    // otherwise undoing our own create would leave the object behind
    private val created: List<T> = before.mapTo(HashSet(), id).let { beforeIds ->
        after.filter { id(it) in changed && id(it) !in beforeIds }
    }

    val isEmpty: Boolean get() = changed.isEmpty()

    fun applyTo(snapshot: List<T>): List<T> {
        if (changed.isEmpty()) return snapshot
        val present = HashSet<String>()
        val kept = snapshot.mapNotNull { item ->
            val key = id(item)
            present += key
            if (key in changed) afterById[key] else item
        }
        val missing = created.filter { id(it) !in present }
        return if (missing.isEmpty()) kept else kept + missing
    }
}
