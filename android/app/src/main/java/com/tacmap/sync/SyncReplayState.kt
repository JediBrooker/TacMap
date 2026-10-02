package com.tacmap.sync

import com.tacmap.localization.L10n

import com.tacmap.util.SafeStore
import com.tacmap.models.ModelMutationEvent
import com.tacmap.models.ModelMutationOrigin
import org.json.JSONObject
import java.io.File
import java.math.BigInteger
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The encoder iterates this view on its worker, never at owner capture. */
private class SyncSparseMapView<V>(val base: Map<String, V>, val patch: Map<String, V?>) : AbstractMap<String, V>() {
    override fun get(key: String): V? = if (patch.containsKey(key)) patch[key] else base[key]
    override fun containsKey(key: String): Boolean = if (patch.containsKey(key)) patch[key] != null else base.containsKey(key)
    override val entries: Set<Map.Entry<String, V>> get() = object : AbstractSet<Map.Entry<String, V>>() {
        override val size: Int get() = base.size + patch.count { (k, v) -> v != null && k !in base } - patch.count { (k, v) -> v == null && k in base }
        override fun iterator(): Iterator<Map.Entry<String, V>> = sequence {
            for (entry in base.entries) if (entry.key !in patch) yield(entry)
            for ((key, value) in patch) if (value != null) yield(java.util.AbstractMap.SimpleImmutableEntry(key, value))
        }.iterator()
    }
}


/**
 * Durable v3 rollback state. Security-sensitive changes are committed here
 * before the corresponding model mutation or network send. A failed sealed
 * write rolls the in-memory change back and is reported to the caller.
 *
 * SP3 (plans/04 section 17): a transaction keeps an undo log of just the keys
 * it touched instead of copying every map, the room high water is a running
 * max, [runBatch] folds many commits into one sealed write, and presence
 * counters are persisted in strides (17.1) instead of on every frame.
 */
class SyncReplayState(
    private val roomId: String,
    private val filesDir: File? = null,
    private val persistOverride: (() -> Boolean)? = null,
) {

    companion object {
        const val ADVANCE_WINDOW = 10_000L
        // every replay file write goes through this, the snapshot commit writes from a worker
        // and SafeStore uses one fixed tmp name per file
        private val writeLock = Any()
        private val persistenceOrder = Mutex()
        // Encodes are numbered process-wide, so even a re-joined room's new state
        // object never gets overwritten by an older object's slow write.
        private val encodeSeqSource = java.util.concurrent.atomic.AtomicLong()
        private val writtenSeqByLabel = HashMap<String, Long>()
        private val loadedInstanceByStore = HashMap<String, Long>()
        private val HASH_PATTERN = Regex("^[0-9a-f]{64}$")
    }

    data class AuthenticatedMutation(
        val wireObjectId: String,
        val stamp: VersionStamp,
        val pubkey: String,
        val contentHash: String?,
        val deleted: Boolean,
    )

    /** Null [priorModelHash] means the object was absent before acceptance. */
    data class RemoteMutation(
        val mutation: AuthenticatedMutation,
        val priorModelHash: String?,
        val localModelId: String? = null,
        val acceptedGeneration: Long = 0L,
        val expectedModelHash: String? = if (mutation.deleted) null else mutation.contentHash,
    )

    enum class PendingModelDecision { NONE, APPLY_INCOMING, ALREADY_APPLIED, LOCAL_DIVERGED }

    var localCounter: Long = 0L
        private set
    var lastSnapshotSeq: Long = -1L
        private set

    private val stamps = HashMap<String, VersionStamp>()
    private val tombstones = HashMap<String, VersionStamp>()
    private val contentHashes = HashMap<String, String>()
    private val actors = HashMap<String, String>()
    private val helloEpochs = HashMap<String, String>()
    private val presenceSessions = HashMap<String, PresenceSession>()
    private val pendingModelApplications = HashMap<String, RemoteMutation>()
    private val transientPresenceSeq = HashMap<String, Long>()

    // running max of every counter we hold, so nothing scans all stamps (17)
    private var highWater = 0L

    // presence fence persistence (plans/04 section 17.1)
    /** Flag that is on disk, or goes on disk with the next write. Absent in old files means exact. */
    private var presenceFenceExact = true
    /** Counter per actor as it sits on disk right now. */
    private val presenceDurable = HashMap<String, Long>()
    /** Actors whose in-memory counter is only the post-crash floor, nothing accepted since load. */
    private val presenceFloorOnly = HashSet<String>()
    /** Some accepted counter is ahead of disk, the 60 s flush has work. */
    private var presenceDirty = false

    // undo log for the open transaction, null when none is open
    private var undo: ArrayList<() -> Unit>? = null
    private var inBatch = false
    private var batchDirty = false

    private data class PresenceSession(
        val sessionDomain: String,
        val counter: Long,
    )

    private data class LoadedState(
        val localCounter: Long,
        val lastSnapshotSeq: Long,
        val stamps: HashMap<String, VersionStamp>,
        val tombstones: HashMap<String, VersionStamp>,
        val hashes: HashMap<String, String>,
        val actors: HashMap<String, String>,
        val helloEpochs: HashMap<String, String>,
        val presenceSessions: HashMap<String, PresenceSession>,
        val pendingModelApplications: HashMap<String, RemoteMutation>,
        val presenceFenceExact: Boolean,
    )

    private data class LocalIdentity(
        val actorId: String,
        val pubkey: String,
    )

    private data class DecodedLoad(
        val snapshot: LoadedState,
        val requiresRepair: Boolean,
    )

    private class Scalars(
        val localCounter: Long,
        val lastSnapshotSeq: Long,
        val highWater: Long,
        val presenceFenceExact: Boolean,
        val presenceDirty: Boolean,
    )

    private fun captureScalars() =
        Scalars(localCounter, lastSnapshotSeq, highWater, presenceFenceExact, presenceDirty)

    private fun rollback(log: List<() -> Unit>, scalars: Scalars) {
        for (i in log.indices.reversed()) log[i]()
        localCounter = scalars.localCounter
        lastSnapshotSeq = scalars.lastSnapshotSeq
        highWater = scalars.highWater
        presenceFenceExact = scalars.presenceFenceExact
        presenceDirty = scalars.presenceDirty
    }

    // tracked writes: the open transaction remembers the old value of exactly this key
    @Suppress("UNCHECKED_CAST")
    private fun <V> HashMap<String, V>.tput(key: String, value: V) {
        if (containsKey(key) && this[key] == value) return
        undo?.let { log ->
            touchedMaps?.getOrPut(this) { LinkedHashSet() }?.add(key)
            val had = containsKey(key)
            val prev = get(key)
            log.add {
                if (had) {
                    this[key] = prev as V
                } else {
                    remove(key)
                }
            }
        }
        this[key] = value
    }

    private fun <V> HashMap<String, V>.tremove(key: String) {
        if (!containsKey(key)) return
        undo?.let { log ->
            touchedMaps?.getOrPut(this) { LinkedHashSet() }?.add(key)
            val prev = getValue(key)
            log.add { this[key] = prev }
        }
        remove(key)
    }

    private fun HashSet<String>.tremove(key: String) {
        if (remove(key)) {
            touchedFloor?.add(key)
            undo?.add { add(key) }
        }
    }

    private fun bumpHighWater(counter: Long) {
        if (counter > highWater) highWater = counter
    }

    private fun replaceAll(state: LoadedState) {
        localCounter = state.localCounter
        lastSnapshotSeq = state.lastSnapshotSeq
        stamps.clear(); stamps.putAll(state.stamps)
        tombstones.clear(); tombstones.putAll(state.tombstones)
        contentHashes.clear(); contentHashes.putAll(state.hashes)
        actors.clear(); actors.putAll(state.actors)
        helloEpochs.clear(); helloEpochs.putAll(state.helloEpochs)
        pendingModelApplications.clear(); pendingModelApplications.putAll(state.pendingModelApplications)
        // one scan at load, never again
        var max = localCounter
        for (vs in stamps.values) if (vs.counter > max) max = vs.counter
        for (vs in tombstones.values) if (vs.counter > max) max = vs.counter
        highWater = max
        presenceFenceExact = state.presenceFenceExact
        presenceDurable.clear()
        presenceFloorOnly.clear()
        presenceSessions.clear()
        for ((actor, session) in state.presenceSessions) {
            presenceDurable[actor] = session.counter
            val floor = PresenceFencePersistence.loadFloor(session.counter, state.presenceFenceExact)
            if (floor != session.counter) presenceFloorOnly += actor
            presenceSessions[actor] = session.copy(counter = floor)
        }
        presenceDirty = false
    }

    private fun clearMemory() {
        localCounter = 0L
        lastSnapshotSeq = -1L
        highWater = 0L
        stamps.clear(); tombstones.clear(); contentHashes.clear(); actors.clear(); helloEpochs.clear()
        presenceSessions.clear(); pendingModelApplications.clear(); transientPresenceSeq.clear()
        presenceDurable.clear(); presenceFloorOnly.clear()
        presenceFenceExact = true
        presenceDirty = false
    }

    /**
     * One durable change. Outside a batch: run it, seal one write, roll back
     * only the touched keys if the write fails. Inside [runBatch]: run it in
     * memory, the batch seals once at the end.
     */
    private inline fun persistTransaction(change: () -> Unit): Boolean {
        if (inBatch) {
            change()
            batchDirty = true
            return true
        }
        val log = ArrayList<() -> Unit>()
        val scalars = captureScalars()
        undo = log
        return try {
            change()
            undo = null
            if (!save()) throw IllegalStateException(L10n.text("replay-state persistence failed"))
            true
        } catch (_: Throwable) {
            undo = null
            rollback(log, scalars)
            false
        }
    }

    private var batchLog: ArrayList<() -> Unit>? = null
    private var batchScalars: Scalars? = null
    private var touchedMaps: java.util.IdentityHashMap<Any, MutableSet<String>>? = null
    private var touchedFloor: MutableSet<String>? = null
    internal var lastAsyncOwnerPatchCount = 0
        private set
    internal var runtimeFullCopyCount = 0
        private set

    /**
     * Opens a batch: every commit until [commitBatch] stays in memory with an
     * undo entry, then one sealed write covers all of them (plans/04 section
     * 1.3 and 17). The caller must not expose anything from the batch before
     * [commitBatch] returns true.
     */
    fun beginBatch() {
        check(!inBatch) { "replay batches don't nest" }
        val log = ArrayList<() -> Unit>()
        batchLog = log
        batchScalars = captureScalars()
        touchedMaps = java.util.IdentityHashMap()
        touchedFloor = LinkedHashSet()
        undo = log
        inBatch = true
        batchDirty = false
    }

    /** One write for the whole batch. False means it failed and every change is gone again. */
    fun commitBatch(): Boolean {
        check(inBatch) { "no open replay batch" }
        val log = checkNotNull(batchLog)
        val scalars = checkNotNull(batchScalars)
        endBatch()
        if (!batchDirty) return true
        if (save()) return true
        rollback(log, scalars)
        return false
    }

    /** Drops every change made since [beginBatch]. */
    fun abortBatch() {
        if (!inBatch) return
        val log = checkNotNull(batchLog)
        val scalars = checkNotNull(batchScalars)
        endBatch()
        rollback(log, scalars)
    }

    private fun endBatch() {
        inBatch = false
        undo = null
        batchLog = null
        batchScalars = null
        touchedMaps = null
        touchedFloor = null
    }

    /** [beginBatch] + [block] + [commitBatch]; a throwing block rolls back and rethrows. */
    fun runBatch(block: () -> Unit): Boolean {
        beginBatch()
        try {
            block()
        } catch (t: Throwable) {
            abortBatch()
            throw t
        }
        return commitBatch()
    }

    val isInBatch: Boolean get() = inBatch

    private val persistenceMutex = Mutex()
    private val instanceEpoch = encodeSeqSource.incrementAndGet()
    private val instanceStoreKey: String get() = "${filesDir?.absolutePath.orEmpty()}|$label"
    val isPersistenceInFlight: Boolean get() = persistenceMutex.isLocked

    /** A protocol consumer suspends here; the UI dispatcher remains free. */
    suspend fun awaitPersistence() {
        // Unlock may already have handed ownership to a queued operation. Do
        // not let a protocol caller stage keys ahead of that operation.
        while (persistenceMutex.isLocked) persistenceMutex.withLock { }
    }

    /** Copy runtime counters as well as disk fields; loading would add a crash floor. */
    private fun copyRuntimeTo(target: SyncReplayState) {
        runtimeFullCopyCount += 1
        target.localCounter = localCounter
        target.lastSnapshotSeq = lastSnapshotSeq
        target.highWater = highWater
        target.presenceFenceExact = presenceFenceExact
        target.presenceDirty = presenceDirty
        target.stamps.clear(); target.stamps.putAll(stamps)
        target.tombstones.clear(); target.tombstones.putAll(tombstones)
        target.contentHashes.clear(); target.contentHashes.putAll(contentHashes)
        target.actors.clear(); target.actors.putAll(actors)
        target.helloEpochs.clear(); target.helloEpochs.putAll(helloEpochs)
        target.presenceSessions.clear(); target.presenceSessions.putAll(presenceSessions)
        target.pendingModelApplications.clear(); target.pendingModelApplications.putAll(pendingModelApplications)
        target.presenceDurable.clear(); target.presenceDurable.putAll(presenceDurable)
        target.presenceFloorOnly.clear(); target.presenceFloorOnly.addAll(presenceFloorOnly)
        target.transientPresenceSeq.clear(); target.transientPresenceSeq.putAll(transientPresenceSeq)
    }

    /** Seal an isolated candidate, then advance authoritative memory on the owner dispatcher. */
    suspend fun commitBatchOffMain(io: CoroutineContext): Boolean = persistenceMutex.withLock {
        commitBatchOffMainLocked(io)
    }

    private suspend fun commitBatchOffMainLocked(io: CoroutineContext): Boolean {
        check(inBatch) { "no open replay batch" }
        if (!batchDirty) { endBatch(); return true }
        // Capture only changed keys. The owner rolls them back before suspension;
        // the worker reads that stable authority through sparse, read-only overlays.
        // persistenceMutex serializes every production writer until adoption.
        val patches = ArrayList<() -> Unit>()
        var patchCount = 0
        fun <V> view(map: HashMap<String, V>): Map<String, V> {
            val values = LinkedHashMap<String, V?>()
            for (key in touchedMaps?.get(map).orEmpty()) values[key] = map[key]
            patchCount += values.size
            patches += { for ((key, value) in values) {
                if (value == null) map.remove(key) else map[key] = value
            } }
            return SyncSparseMapView(map, values)
        }
        val floorRemoved = touchedFloor.orEmpty().filter { it !in presenceFloorOnly }
        val candidate = EncodingView(captureScalars(), view(stamps), view(tombstones),
            view(contentHashes), view(actors), view(helloEpochs), view(presenceSessions),
            view(pendingModelApplications), view(presenceDurable),
            SparseSetView(presenceFloorOnly, floorRemoved.toSet()))
        val candidateScalars = candidate.scalars
        lastAsyncOwnerPatchCount = patchCount + floorRemoved.size
        abortBatch()
        val seq = encodeSeqSource.incrementAndGet()
        return withContext(NonCancellable) {
            var durableChanges: Map<String, Long?> = emptyMap()
            var clearFloors: List<String> = emptyList()
            val ok = persistenceOrder.withLock {
                if ((loadedInstanceByStore[instanceStoreKey] ?: instanceEpoch) > instanceEpoch) {
                    return@withLock false
                }
                withContext(io) {
                    val text = if (persistOverride == null && filesDir != null) encode(candidate).toString() else null
                    if (!writeEncoded(text, seq)) false else {
                        // Compute stride-base changes on the worker; adoption
                        // touches only actors whose persisted counters changed.
                        val changes = LinkedHashMap<String, Long?>()
                        for ((actor, session) in candidate.presenceSessions) {
                            val counter = candidate.encodedPresenceCounter(actor, session)
                            if (presenceDurable[actor] != counter) changes[actor] = counter
                        }
                        for (actor in presenceDurable.keys) {
                            if (actor !in candidate.presenceSessions) changes[actor] = null
                        }
                        durableChanges = changes
                        clearFloors = if (candidateScalars.presenceFenceExact) presenceFloorOnly.toList() else floorRemoved
                        true
                    }
                }
            }
            if (ok) {
                patches.forEach { it() }
                localCounter = candidateScalars.localCounter
                lastSnapshotSeq = candidateScalars.lastSnapshotSeq
                highWater = candidateScalars.highWater
                presenceFenceExact = candidateScalars.presenceFenceExact
                presenceDirty = false
                for ((actor, counter) in durableChanges) {
                    if (counter == null) presenceDurable.remove(actor) else presenceDurable[actor] = counter
                }
                clearFloors.forEach { presenceFloorOnly.remove(it) }
                lastAsyncOwnerPatchCount += patchCount + durableChanges.size + clearFloors.size
            }
            ok
        }
    }

    /**
     * Snapshot commit path (plans/04 section 19). Stage on the owner thread,
     * encode/seal/fsync the private candidate on [io], then advance authority
     * after durability. No staged replay value escapes during the suspension.
     */
    suspend fun persistOffMain(io: CoroutineContext, change: () -> Unit): Boolean = persistenceMutex.withLock {
        persistOffMainLocked(io, change)
    }

    private suspend fun persistOffMainLocked(io: CoroutineContext, change: () -> Unit): Boolean {
        if (inBatch) return false
        beginBatch()
        try {
            change()
            batchDirty = true
        } catch (_: Throwable) {
            abortBatch()
            return false
        }
        return commitBatchOffMainLocked(io)
    }

    suspend fun reserveHelloEpochOffMain(
        actorId: String, pubkey: String, floor: BigInteger?, spare: Int, io: CoroutineContext,
    ): String? = persistenceMutex.withLock {
        if (inBatch) return@withLock null
        beginBatch()
        val epoch = reserveHelloEpoch(actorId, pubkey, floor, spare)
        if (epoch == null) { abortBatch(); return@withLock null }
        epoch.takeIf { commitBatchOffMainLocked(io) }
    }

    suspend fun flushPresenceOffMain(io: CoroutineContext): Boolean = persistenceMutex.withLock {
        if (!presenceDirty) return@withLock true
        persistOffMainLocked(io) { presenceFenceExact = false }
    }

    suspend fun persistExactPresenceOffMain(io: CoroutineContext): Boolean = persistenceMutex.withLock {
        if (presenceFenceExact && !presenceDirty) return@withLock true
        persistOffMainLocked(io) { presenceFenceExact = true }
    }

    /** A fresh join must observe earlier writes from a departed instance of this room. */
    suspend fun loadOffMain(actorId: String, pubkey: String, io: CoroutineContext): Boolean =
        persistenceOrder.withLock {
            // A load is a new room authority. Deferred clean points from an
            // older departed instance may not overwrite its later counters.
            loadedInstanceByStore[instanceStoreKey] = instanceEpoch
            val candidate = SyncReplayState(roomId, filesDir, persistOverride)
            val ok = withContext(io) { candidate.load(actorId, pubkey) }
            if (ok) candidate.copyRuntimeTo(this)
            ok
        }

    private fun beatsCurrent(wireObjectId: String, incoming: VersionStamp): Boolean {
        val stamp = stamps[wireObjectId]
        val tomb = tombstones[wireObjectId]
        return (stamp == null || incoming > stamp) && (tomb == null || incoming > tomb)
    }

    fun canAcceptLive(wireObjectId: String, incoming: VersionStamp): Boolean {
        val high = roomHighWater()
        val withinWindow = incoming.counter <= high || incoming.counter - high <= ADVANCE_WINDOW
        return withinWindow && beatsCurrent(wireObjectId, incoming)
    }

    fun canAcceptSnapshot(wireObjectId: String, incoming: VersionStamp): Boolean =
        beatsCurrent(wireObjectId, incoming)

    enum class LiveDecision { ACCEPT, NOT_NEWER, OUTSIDE_WINDOW }

    /** Same rule as [canAcceptLive], split so a newer record past the advance
     * window can trigger a resync instead of a silent drop (plans/04 section 4). */
    fun liveDecision(wireObjectId: String, incoming: VersionStamp): LiveDecision {
        if (!beatsCurrent(wireObjectId, incoming)) return LiveDecision.NOT_NEWER
        val high = roomHighWater()
        val withinWindow = incoming.counter <= high || incoming.counter - high <= ADVANCE_WINDOW
        return if (withinWindow) LiveDecision.ACCEPT else LiveDecision.OUTSIDE_WINDOW
    }

    /** Commit one fully authenticated live mutation before touching app models. */
    fun commitAuthenticated(mutation: AuthenticatedMutation): Boolean {
        if (mutation.stamp.actorId.isEmpty() || !validMutationKind(mutation) || !canAcceptLive(mutation.wireObjectId, mutation.stamp)) return false
        val pinned = actors[mutation.stamp.actorId]
        if (pinned != null && pinned != mutation.pubkey) return false
        return persistTransaction { applyMutation(mutation) }
    }

    /** Accept a verified remote mutation and its pre-apply model fingerprint atomically. */
    fun commitRemoteAuthenticated(remote: RemoteMutation): Boolean {
        val mutation = remote.mutation
        if (!validRemote(remote) || !canAcceptLive(mutation.wireObjectId, mutation.stamp)) return false
        val pinned = actors[mutation.stamp.actorId]
        if (pinned != null && pinned != mutation.pubkey) return false
        return persistTransaction {
            applyMutation(mutation)
            pendingModelApplications.tput(mutation.wireObjectId, remote)
        }
    }

    /**
     * Atomically commit a fully authenticated snapshot and its fence. Snapshot
     * counters establish the reconnect baseline and therefore do not use the
     * live advance window. Older records are ignored but the fence is monotonic.
     */
    fun commitSnapshot(mutations: List<AuthenticatedMutation>, seq: Long): Boolean {
        if (seq < 0) return false
        val actorPins = HashMap<String, String>()
        for (mutation in mutations) {
            if (!validMutationKind(mutation)) return false
            val pinned = actorPins[mutation.stamp.actorId] ?: actors[mutation.stamp.actorId]
            if (pinned != null && pinned != mutation.pubkey) return false
            actorPins[mutation.stamp.actorId] = mutation.pubkey
        }
        return persistTransaction {
            for (mutation in mutations) {
                if (beatsCurrent(mutation.wireObjectId, mutation.stamp)) applyMutation(mutation)
            }
            lastSnapshotSeq = maxOf(lastSnapshotSeq, seq)
        }
    }

    /** Checks a remote snapshot batch before anything is touched. */
    private fun remoteSnapshotValid(remotes: List<RemoteMutation>, seq: Long): Boolean {
        if (seq < 0) return false
        val actorPins = HashMap<String, String>()
        for (remote in remotes) {
            if (!validRemote(remote)) return false
            val mutation = remote.mutation
            val pinned = actorPins[mutation.stamp.actorId] ?: actors[mutation.stamp.actorId]
            if (pinned != null && pinned != mutation.pubkey) return false
            actorPins[mutation.stamp.actorId] = mutation.pubkey
        }
        return true
    }

    private fun applyRemoteSnapshot(remotes: List<RemoteMutation>, seq: Long) {
        for (remote in remotes) {
            val mutation = remote.mutation
            if (beatsCurrent(mutation.wireObjectId, mutation.stamp)) {
                applyMutation(mutation)
                pendingModelApplications.tput(mutation.wireObjectId, remote)
            }
            // Exact persisted records retain an existing matching pending
            // marker. Exact records without one were already resolved.
        }
        lastSnapshotSeq = maxOf(lastSnapshotSeq, seq)
    }

    /** Snapshot variant that durably records model application work for new mutations. */
    fun commitRemoteSnapshot(remotes: List<RemoteMutation>, seq: Long): Boolean {
        if (!remoteSnapshotValid(remotes, seq)) return false
        return persistTransaction { applyRemoteSnapshot(remotes, seq) }
    }

    /** Same commit with the sealed write on [io] (plans/04 section 19). */
    suspend fun commitRemoteSnapshotOffMain(remotes: List<RemoteMutation>, seq: Long, io: CoroutineContext): Boolean =
        persistenceMutex.withLock {
            if (!remoteSnapshotValid(remotes, seq)) return@withLock false
            persistOffMainLocked(io) { applyRemoteSnapshot(remotes, seq) }
    }

    private fun applyMutation(mutation: AuthenticatedMutation) {
        actors.tput(mutation.stamp.actorId, mutation.pubkey)
        stamps.tput(mutation.wireObjectId, mutation.stamp)
        localCounter = maxOf(localCounter, mutation.stamp.counter)
        bumpHighWater(mutation.stamp.counter)
        if (mutation.deleted) {
            tombstones.tput(mutation.wireObjectId, mutation.stamp)
            contentHashes.tremove(mutation.wireObjectId)
        } else {
            tombstones.tremove(mutation.wireObjectId)
            mutation.contentHash?.let { contentHashes.tput(mutation.wireObjectId, it) }
        }
    }

    private fun validMutationKind(mutation: AuthenticatedMutation): Boolean =
        if (mutation.deleted) mutation.contentHash == null
        else mutation.contentHash?.matches(HASH_PATTERN) == true

    private fun validRemote(remote: RemoteMutation): Boolean =
        validMutationKind(remote.mutation) &&
            (remote.priorModelHash == null || remote.priorModelHash.matches(HASH_PATTERN)) &&
            (remote.expectedModelHash == null) == remote.mutation.deleted &&
            (remote.expectedModelHash == null || remote.expectedModelHash.matches(HASH_PATTERN)) &&
            (remote.localModelId == null || runCatching { java.util.UUID.fromString(remote.localModelId) }.isSuccess) &&
            remote.acceptedGeneration in 0..VersionStamp.MAX_COUNTER

    /**
     * Reserve a strictly increasing unsigned-64 hello epoch before signing.
     * The room-derived local identity pin and epoch are one durable transaction:
     * a crash can expose both or neither, never an undecodable orphan epoch.
     */
    fun reserveHelloEpoch(
        actorId: String,
        pubkey: String,
        floor: BigInteger? = null,
        spare: Int = 0,
    ): String? {
        val identity = validatedLocalIdentity(actorId, pubkey) ?: return null
        val pinned = actors[actorId]
        if (pinned != null && pinned != pubkey) return null
        val currentHex = helloEpochs[actorId]
        val current = if (currentHex == null) {
            null
        } else {
            SyncIdentity.parseHelloEpoch(currentHex)?.let { BigInteger(it, 16) } ?: return null
        }
        // floor/spare are the plans/04 section 14 knobs; plain callers still get +1
        val reserved = HelloEpochPolicy.reserve(current, floor, spare)
            as? HelloEpochPolicy.Result.Next ?: return null
        val hex = reserved.nextHex
        val storedHex = reserved.persistedHex
        return hex.takeIf {
            persistTransaction {
                actors.tput(identity.actorId, identity.pubkey)
                helloEpochs.tput(identity.actorId, storedHex)
            }
        }
    }

    /**
     * Persist a verified remote actor pin, epoch, and live session domain.
     *
     * A reconnect may receive the exact same still-live hello that was accepted
     * before this app disconnected. Equality is safe only when both the pinned
     * key and signed session domain match the persisted values; its persisted
     * presence counter is retained so old locations still cannot be replayed.
     */
    fun commitActorHello(
        actorId: String,
        pubkey: String,
        sessionDomain: String,
        epochHex: String,
    ): Boolean {
        if (SyncIdentity.parseHelloEpoch(epochHex) == null ||
            SyncIdentity.urlB64Decode32(sessionDomain) == null) return false
        val pinned = actors[actorId]
        if (pinned != null && pinned != pubkey) return false
        val old = helloEpochs[actorId]
        if (old != null && epochHex < old) return false
        if (old == epochHex) {
            return pinned == pubkey && presenceSessions[actorId]?.sessionDomain == sessionDomain
        }
        return persistTransaction {
            actors.tput(actorId, pubkey)
            helloEpochs.tput(actorId, epochHex)
            presenceSessions.tput(actorId, PresenceSession(sessionDomain, 0L))
            presenceFloorOnly.tremove(actorId)
        }
    }

    fun getHelloEpoch(actorId: String): String? = helloEpochs[actorId]

    fun canAcceptPresence(
        actorId: String,
        pubkey: String,
        sessionDomain: String,
        counter: Long,
    ): Boolean {
        if (actors[actorId] != pubkey || counter <= 0L) return false
        val session = presenceSessions[actorId] ?: return false
        if (session.sessionDomain != sessionDomain || counter <= session.counter) return false
        return counter - session.counter <= ADVANCE_WINDOW
    }

    /**
     * Accept an authenticated presence counter (plans/04 section 17.1). A
     * write happens before the peer is exposed only when the counter is 16 or
     * more past the one on disk, or the disk still says exact. Otherwise the
     * counter lives in memory until the 60 s flush; after a crash the floor is
     * disk + 15, so nothing accepted before the crash can be accepted again.
     * A failed required write rejects the update and rolls memory back.
     */
    fun commitPresence(
        actorId: String,
        pubkey: String,
        sessionDomain: String,
        counter: Long,
    ): Boolean {
        if (!canAcceptPresence(actorId, pubkey, sessionDomain, counter)) return false
        val durable = presenceDurable[actorId] ?: 0L
        if (!PresenceFencePersistence.mustPersistBeforeExposing(counter, durable, presenceFenceExact)) {
            presenceSessions.tput(actorId, PresenceSession(sessionDomain, counter))
            presenceFloorOnly.tremove(actorId)
            presenceDirty = true
            return true
        }
        return persistTransaction {
            presenceFenceExact = false
            presenceSessions.tput(actorId, PresenceSession(sessionDomain, counter))
            presenceFloorOnly.tremove(actorId)
        }
    }

    /** Some accepted counter isn't on disk yet. */
    val hasUnflushedPresence: Boolean get() = presenceDirty

    /** The 60 s presence flush. No-op when disk already has every counter. */
    fun flushPresence(): Boolean {
        if (!presenceDirty) return true
        return persistTransaction { presenceFenceExact = false }
    }

    /**
     * Clean point (leave, background entry, dispose): write the exact counters
     * so the next load needs no crash floor. The next acceptance writes the
     * flag back to false before it exposes anything.
     */
    fun persistExactPresence(): Boolean {
        if (presenceFenceExact && !presenceDirty) return true
        return persistTransaction { presenceFenceExact = true }
    }

    fun getPresenceSessionDomain(actorId: String): String? =
        presenceSessions[actorId]?.sessionDomain

    /** In-memory acceptance floor, after a non-exact load that's disk + 15. */
    fun getPresenceCounter(actorId: String): Long? =
        presenceSessions[actorId]?.counter

    /** Reserve and persist an outbound put stamp/hash before transmission. */
    fun reserveLocalPut(wireObjectId: String, actorId: String, pubkey: String, contentHash: String): VersionStamp? {
        if (SyncIdentity.urlB64Decode32(wireObjectId) == null ||
            SyncIdentity.urlB64Decode32(actorId) == null || SyncIdentity.urlB64Decode32(pubkey) == null ||
            !contentHash.matches(HASH_PATTERN)) return null
        if (localCounter >= VersionStamp.MAX_COUNTER) return null
        val next = maxOf(localCounter, roomHighWater()) + 1
        if (next < 0 || next > VersionStamp.MAX_COUNTER) return null
        val stamp = VersionStamp(next, actorId)
        val ok = persistTransaction {
            localCounter = next
            bumpHighWater(next)
            actors.tput(actorId, pubkey)
            stamps.tput(wireObjectId, stamp)
            tombstones.tremove(wireObjectId)
            contentHashes.tput(wireObjectId, contentHash)
        }
        return stamp.takeIf { ok }
    }

    /** Reserve and persist an outbound tombstone before transmission. */
    fun reserveLocalDelete(wireObjectId: String, actorId: String, pubkey: String): VersionStamp? {
        if (SyncIdentity.urlB64Decode32(wireObjectId) == null ||
            SyncIdentity.urlB64Decode32(actorId) == null || SyncIdentity.urlB64Decode32(pubkey) == null) return null
        if (localCounter >= VersionStamp.MAX_COUNTER) return null
        val next = maxOf(localCounter, roomHighWater()) + 1
        if (next < 0 || next > VersionStamp.MAX_COUNTER) return null
        val stamp = VersionStamp(next, actorId)
        val ok = persistTransaction {
            localCounter = next
            bumpHighWater(next)
            actors.tput(actorId, pubkey)
            stamps.tput(wireObjectId, stamp)
            tombstones.tput(wireObjectId, stamp)
            contentHashes.tremove(wireObjectId)
        }
        return stamp.takeIf { ok }
    }

    // Compatibility helpers retained for the pure replay-state tests.
    fun advance(wireObjectId: String, incoming: VersionStamp): Boolean {
        if (!canAcceptLive(wireObjectId, incoming)) return false
        stamps[wireObjectId] = incoming
        localCounter = maxOf(localCounter, incoming.counter)
        bumpHighWater(incoming.counter)
        return true
    }

    fun tombstone(wireObjectId: String, incoming: VersionStamp): Boolean {
        if (!canAcceptLive(wireObjectId, incoming)) return false
        stamps[wireObjectId] = incoming
        tombstones[wireObjectId] = incoming
        localCounter = maxOf(localCounter, incoming.counter)
        bumpHighWater(incoming.counter)
        return true
    }

    fun registerActor(actorId: String, pubkey: String): Boolean {
        val pinned = actors[actorId]
        if (pinned == null) actors[actorId] = pubkey
        return pinned == null || pinned == pubkey
    }

    fun isTombstoned(wireObjectId: String): Boolean = tombstones.containsKey(wireObjectId)
    fun getStamp(wireObjectId: String): VersionStamp? = stamps[wireObjectId]
    fun getPinnedPubkey(actorId: String): String? = actors[actorId]
    /** One copy per snapshot for the off-main validator, never per record. */
    fun actorPinsCopy(): Map<String, String> = HashMap(actors)

    /** Immutable replay view for the snapshot validator's layer staging. */
    fun snapshotStageEligibility(
        currentHash: (String?) -> String?,
        currentGeneration: (String?) -> Long,
    ): (AuthenticatedMutation) -> Boolean {
        val savedStamps = HashMap(stamps)
        val savedTombstones = HashMap(tombstones)
        val savedActors = HashMap(actors)
        val savedHashes = HashMap(contentHashes)
        val savedPending = HashMap(pendingModelApplications)
        return { mutation ->
            val id = mutation.wireObjectId
            val newer = (savedStamps[id]?.let { mutation.stamp > it } ?: true) &&
                (savedTombstones[id]?.let { mutation.stamp > it } ?: true)
            val exact = savedStamps[id] == mutation.stamp && savedActors[mutation.stamp.actorId] == mutation.pubkey &&
                if (mutation.deleted) savedTombstones[id] == mutation.stamp && savedHashes[id] == null
                else id !in savedTombstones && savedHashes[id] == mutation.contentHash
            val pending = savedPending[id]?.takeIf { it.mutation == mutation }
            val willApplyExact = exact && pending != null &&
                currentGeneration(pending.localModelId) == pending.acceptedGeneration &&
                currentHash(pending.localModelId) == pending.priorModelHash &&
                currentHash(pending.localModelId) != pending.expectedModelHash
            newer || willApplyExact
        }
    }

    /** Session-only compatibility helper; deliberately absent from persistence. */
    fun advancePresence(actorId: String, counter: Long): Boolean {
        val prior = transientPresenceSeq[actorId] ?: 0L
        if (counter <= prior) return false
        transientPresenceSeq[actorId] = counter
        return true
    }
    fun setContentHash(wireObjectId: String, hash: String) { contentHashes[wireObjectId] = hash }
    fun getContentHash(wireObjectId: String): String? = contentHashes[wireObjectId]

    /**
     * True only when the complete authenticated mutation is already durable.
     * Stamp equality alone is insufficient: after a persist-before-model crash,
     * a snapshot may use the same stamp with a conflicting key, hash, or kind.
     */
    fun isExactPersistedMutation(mutation: AuthenticatedMutation): Boolean {
        if (!validMutationKind(mutation) || stamps[mutation.wireObjectId] != mutation.stamp ||
            actors[mutation.stamp.actorId] != mutation.pubkey) return false
        return if (mutation.deleted) {
            tombstones[mutation.wireObjectId] == mutation.stamp && contentHashes[mutation.wireObjectId] == null
        } else {
            !tombstones.containsKey(mutation.wireObjectId) &&
                contentHashes[mutation.wireObjectId] == mutation.contentHash
        }
    }

    fun hasPendingModelApplications(): Boolean = pendingModelApplications.isNotEmpty()
    fun pendingRemoteMutations(): List<RemoteMutation> = pendingModelApplications.values.toList()

    fun pendingModelDecision(
        mutation: AuthenticatedMutation,
        currentModelHash: String?,
        currentGeneration: Long = 0L,
    ): PendingModelDecision {
        val pending = pendingModelApplications[mutation.wireObjectId]
            ?.takeIf { it.mutation == mutation } ?: return PendingModelDecision.NONE
        val incomingHash = pending.expectedModelHash
        if (currentModelHash == incomingHash) return PendingModelDecision.ALREADY_APPLIED
        if (currentGeneration != pending.acceptedGeneration) return PendingModelDecision.LOCAL_DIVERGED
        return if (currentModelHash == pending.priorModelHash) PendingModelDecision.APPLY_INCOMING
        else PendingModelDecision.LOCAL_DIVERGED
    }

    /** Clear only the exact pending record; persistence failure leaves it intact. */
    fun clearPendingModelApplication(mutation: AuthenticatedMutation): Boolean {
        pendingModelApplications[mutation.wireObjectId]
            ?.takeIf { it.mutation == mutation } ?: return false
        return persistTransaction { pendingModelApplications.tremove(mutation.wireObjectId) }
    }

    /**
     * Clears every exact pending record in [mutations] with one write (the
     * "1 marker clear" per snapshot or live batch). Records that aren't
     * pending, or whose marker was replaced, are skipped like the single form.
     */
    fun clearPendingModelApplications(mutations: Collection<AuthenticatedMutation>): Boolean {
        val exact = mutations.filter { pendingModelApplications[it.wireObjectId]?.mutation == it }
        if (exact.isEmpty()) return true
        return persistTransaction {
            for (mutation in exact) pendingModelApplications.tremove(mutation.wireObjectId)
        }
    }

    /** Off-main twin of [clearPendingModelApplications]. */
    suspend fun clearPendingModelApplicationsOffMain(
        mutations: Collection<AuthenticatedMutation>,
        io: CoroutineContext,
    ): Boolean = persistenceMutex.withLock {
        val exact = mutations.filter { pendingModelApplications[it.wireObjectId]?.mutation == it }
        if (exact.isEmpty()) return@withLock true
        persistOffMainLocked(io) {
            for (mutation in exact) pendingModelApplications.tremove(mutation.wireObjectId)
        }
    }

    fun recoverableLocalPut(wireObjectId: String, actorId: String, pubkey: String, contentHash: String): VersionStamp? {
        val stamp = stamps[wireObjectId] ?: return null
        return stamp.takeIf {
            it.actorId == actorId && actors[actorId] == pubkey && !tombstones.containsKey(wireObjectId) &&
                contentHashes[wireObjectId] == contentHash
        }
    }

    fun recoverableLocalDeletes(actorId: String, pubkey: String): List<Pair<String, VersionStamp>> =
        tombstones.mapNotNull { (id, tomb) ->
            (id to tomb).takeIf {
                tomb.actorId == actorId && actors[actorId] == pubkey && stamps[id] == tomb && contentHashes[id] == null
            }
        }

    /** Running max, O(1). Every stamp and tombstone counter is folded in as it lands. */
    fun roomHighWater(): Long = maxOf(localCounter, highWater)

    private val label = "sync/room/$roomId"

    private fun stateFile(): File? {
        val dir = filesDir ?: return null
        val syncDir = File(dir, "sync_replay")
        val namingKey = SafeStore.keyProvider.key()
        return try {
            // Resolve only this room. Global unlock migration still scans and
            // reports inactive-room failures independently.
            SyncLocalStore.resolveFile(
                directory = syncDir,
                roomId = roomId,
                domain = SyncIdentity.LocalStoreDomain.REPLAY,
                dataKey = namingKey,
            )
        } finally {
            namingKey.fill(0)
        }
    }

    /** Returns false instead of swallowing a locked key or failed atomic write. */
    fun save(): Boolean {
        val seq = encodeSeqSource.incrementAndGet()
        val text = if (persistOverride == null && filesDir != null) encode().toString() else null
        val ok = writeEncoded(text, seq)
        if (ok) afterSave()
        return ok
    }

    /**
     * Thread safe, only touches the file. [text] is null when there's nothing
     * to encode. An encode older than what's already on disk is dropped: the
     * newer one is a superset because every encode happens on the owning thread.
     */
    private fun writeEncoded(text: String?, seq: Long): Boolean = synchronized(writeLock) {
        val written = writtenSeqByLabel[label] ?: 0L
        persistOverride?.let { override ->
            val ok = runCatching { override() }.getOrDefault(false)
            if (ok && seq > written) writtenSeqByLabel[label] = seq
            return@synchronized ok
        }
        if (seq <= written) return@synchronized true
        try {
            if (text != null) {
                val file = stateFile()
                if (file != null) SafeStore.writeAtomically(file, label, text)
            }
            writtenSeqByLabel[label] = seq
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun afterSave() {
        // what just went to disk becomes the stride base
        for ((actor, session) in presenceSessions) {
            presenceDurable[actor] = encodedPresenceCounter(actor, session)
        }
        presenceDurable.keys.retainAll(presenceSessions.keys)
        if (presenceFenceExact) presenceFloorOnly.clear()
        presenceDirty = false
    }

    /**
     * Counter that goes on disk for one actor. A floor-only actor keeps its old
     * disk value under the non-exact flag, otherwise every write would push its
     * crash floor another 15 up. An exact write has to use the floor itself.
     */
    private fun encodedPresenceCounter(actor: String, session: PresenceSession): Long =
        if (!presenceFenceExact && actor in presenceFloorOnly) presenceDurable[actor] ?: session.counter
        else session.counter

    /** Empty state is valid; locked, corrupt, or invalid state fails closed. */
    fun load(): Boolean = load(localIdentity = null)

    /**
     * Production load with the room-derived local identity. Besides verifying
     * an existing local pin, this can repair the one historical invalid shape
     * written by the old hello reservation: exactly this local actor has an
     * epoch but no actor pin. No remote or ambiguous orphan is migrated.
     */
    fun load(localActorId: String, localPubkey: String): Boolean {
        val identity = validatedLocalIdentity(localActorId, localPubkey) ?: return false
        return load(identity)
    }

    private fun load(localIdentity: LocalIdentity?): Boolean {
        val file = try { stateFile() } catch (_: Throwable) { return false } ?: return true
        return try {
            when (val result = SafeStore.readOrQuarantine(file, label) {
                decodeForLoad(JSONObject(it), localIdentity)
            }) {
                is SafeStore.LoadResult.Empty -> true
                is SafeStore.LoadResult.Loaded -> {
                    replaceAll(result.value.snapshot)
                    if (result.value.requiresRepair && !save()) {
                        clearMemory()
                        false
                    } else {
                        true
                    }
                }
                is SafeStore.LoadResult.Locked, is SafeStore.LoadResult.Corrupt -> false
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun validatedLocalIdentity(actorId: String, pubkey: String): LocalIdentity? {
        val roomIdRaw = SyncIdentity.urlB64Decode32(roomId) ?: return null
        val pubkeyRaw = SyncIdentity.urlB64Decode32(pubkey) ?: return null
        if (SyncIdentity.actorId(roomIdRaw, pubkeyRaw) != actorId) return null
        return LocalIdentity(actorId, pubkey)
    }

    private fun decodeForLoad(json: JSONObject, localIdentity: LocalIdentity?): DecodedLoad {
        val strict = runCatching {
            decode(json).also { snapshot ->
                localIdentity?.let { identity ->
                    require(snapshot.actors[identity.actorId]?.let { it == identity.pubkey } != false)
                }
            }
        }
        strict.getOrNull()?.let { return DecodedLoad(it, requiresRepair = false) }

        val identity = localIdentity ?: throw checkNotNull(strict.exceptionOrNull())
        val repairedJson = repairLocalHelloEpochOrphan(json, identity)
            ?: throw checkNotNull(strict.exceptionOrNull())
        val repaired = decode(repairedJson)
        require(repaired.actors[identity.actorId] == identity.pubkey)
        return DecodedLoad(repaired, requiresRepair = true)
    }

    private fun repairLocalHelloEpochOrphan(
        json: JSONObject,
        identity: LocalIdentity,
    ): JSONObject? {
        val actorPins = runCatching {
            decodeStrings(json.getJSONObject("actors"))
        }.getOrNull() ?: return null
        val epochs = runCatching {
            decodeStrings(json.optJSONObject("helloEpochs") ?: JSONObject())
        }.getOrNull() ?: return null
        val orphanActors = epochs.keys - actorPins.keys
        if (orphanActors != setOf(identity.actorId)) return null

        // Prove this epoch is the document's sole defect. Removing only the
        // historical orphan must recover a completely valid pre-reservation
        // state; otherwise adding a pin could accidentally legitimize stamps,
        // pending mutations, or presence state that already referenced it.
        val withoutOrphan = JSONObject(json.toString())
        withoutOrphan.getJSONObject("helloEpochs").remove(identity.actorId)
        if (runCatching { decode(withoutOrphan) }.isFailure) return null

        return JSONObject(json.toString()).also { repaired ->
            repaired.getJSONObject("actors").put(identity.actorId, identity.pubkey)
        }
    }

    private class SparseSetView(val base: Set<String>, val removed: Set<String>) : AbstractSet<String>() {
        override val size: Int get() = base.size - removed.count { it in base }
        override fun contains(element: String): Boolean = element !in removed && element in base
        override fun iterator(): Iterator<String> = base.asSequence().filter { it !in removed }.iterator()
    }

    private data class EncodingView(
        val scalars: Scalars,
        val stamps: Map<String, VersionStamp>, val tombstones: Map<String, VersionStamp>,
        val contentHashes: Map<String, String>, val actors: Map<String, String>,
        val helloEpochs: Map<String, String>, val presenceSessions: Map<String, PresenceSession>,
        val pendingModelApplications: Map<String, RemoteMutation>,
        val presenceDurable: Map<String, Long>, val presenceFloorOnly: Set<String>,
    ) {
        fun encodedPresenceCounter(actor: String, session: PresenceSession): Long =
            if (!scalars.presenceFenceExact && actor in presenceFloorOnly) presenceDurable[actor] ?: session.counter
            else session.counter
    }

    private fun encodingView() = EncodingView(captureScalars(), stamps, tombstones, contentHashes, actors,
        helloEpochs, presenceSessions, pendingModelApplications, presenceDurable, presenceFloorOnly)

    private fun encode(view: EncodingView = encodingView()): JSONObject = with(view) { JSONObject().apply {
        put("schemaVersion", 3)
        put("localCounter", VersionStamp.counterHex16(scalars.localCounter))
        put("lastSnapshotSeq", scalars.lastSnapshotSeq)
        put("stamps", JSONObject().also { out -> for ((k, v) in stamps) out.put(k, v.encode()) })
        put("tombstones", JSONObject().also { out -> for ((k, v) in tombstones) out.put(k, v.encode()) })
        put("contentHashes", JSONObject().also { out -> for ((k, v) in contentHashes) out.put(k, v) })
        put("actors", JSONObject().also { out -> for ((k, v) in actors) out.put(k, v) })
        put("helloEpochs", JSONObject().also { out -> for ((k, v) in helloEpochs) out.put(k, v) })
        put("presenceSeq", JSONObject().also { out ->
            for ((actor, session) in presenceSessions) {
                out.put(actor, JSONObject().apply {
                    put("sd", session.sessionDomain)
                    put("counter", VersionStamp.counterHex16(encodedPresenceCounter(actor, session)))
                })
            }
        })
        // absent in files written before 17.1, which load as exact
        put("presenceFenceExact", scalars.presenceFenceExact)
        put("pendingModelApplications", JSONObject().also { out ->
            for ((id, remote) in pendingModelApplications) out.put(id, encodeRemote(remote))
        })
    }
 }
    private fun encodeRemote(remote: RemoteMutation): JSONObject = JSONObject().apply {
        val mutation = remote.mutation
        put("vs", mutation.stamp.encode())
        put("pub", mutation.pubkey)
        put("deleted", mutation.deleted)
        if (!mutation.deleted) put("hash", mutation.contentHash)
        put("priorHash", remote.priorModelHash ?: JSONObject.NULL)
        put("expectedHash", remote.expectedModelHash ?: JSONObject.NULL)
        put("localId", remote.localModelId ?: JSONObject.NULL)
        put("generation", VersionStamp.counterHex16(remote.acceptedGeneration))
    }

    private fun decode(json: JSONObject): LoadedState {
        require(json.optInt("schemaVersion", 0) == 3)
        val local = parseCounter(json.getString("localCounter"))
        val seq = json.getLong("lastSnapshotSeq").also { require(it >= -1) }
        val decodedStamps = decodeStamps(json.getJSONObject("stamps"))
        val decodedTombs = decodeStamps(json.getJSONObject("tombstones"))
        val hashes = decodeStrings(json.getJSONObject("contentHashes"))
        val actorPins = decodeStrings(json.getJSONObject("actors"))
        val epochs = decodeStrings(json.optJSONObject("helloEpochs") ?: JSONObject())
        val sessions = decodePresenceSessions(json.optJSONObject("presenceSeq") ?: JSONObject())
        val pending = decodePending(json.optJSONObject("pendingModelApplications") ?: JSONObject())
        val exact = if (json.has("presenceFenceExact")) {
            json.get("presenceFenceExact") as? Boolean ?: error("invalid presence flag")
        } else true
        require(actorPins.all { (actor, pub) ->
            SyncIdentity.urlB64Decode32(actor) != null && SyncIdentity.urlB64Decode32(pub) != null
        })
        require(decodedStamps.all { (id, stamp) ->
            SyncIdentity.urlB64Decode32(id) != null && actorPins[stamp.actorId] != null
        })
        require(decodedTombs.all { (id, stamp) -> decodedStamps[id] == stamp })
        require(hashes.all { (id, hash) -> id in decodedStamps && id !in decodedTombs && hash.matches(HASH_PATTERN) })
        require(decodedStamps.keys.all { (it in decodedTombs) xor (it in hashes) })
        require(epochs.all { (actor, epoch) -> actor in actorPins && SyncIdentity.parseHelloEpoch(epoch) != null })
        require(sessions.all { (actor, session) ->
            actor in actorPins && actor in epochs &&
                SyncIdentity.urlB64Decode32(session.sessionDomain) != null &&
                session.counter in 0..VersionStamp.MAX_COUNTER
        })
        require(pending.all { (id, remote) ->
            id == remote.mutation.wireObjectId && validRemote(remote) &&
                actorPins[remote.mutation.stamp.actorId] == remote.mutation.pubkey &&
                exactMutationIn(id, remote.mutation, decodedStamps, decodedTombs, hashes)
        })
        val authenticatedMax = decodedStamps.values.maxOfOrNull { it.counter } ?: 0L
        require(local >= authenticatedMax)
        return LoadedState(
            local, seq, decodedStamps, decodedTombs, hashes, actorPins, epochs,
            sessions, pending, exact,
        )
    }

    private fun decodePresenceSessions(obj: JSONObject): HashMap<String, PresenceSession> {
        val out = HashMap<String, PresenceSession>()
        for (actor in obj.keys()) {
            val value = obj.getJSONObject(actor)
            out[actor] = PresenceSession(
                sessionDomain = value.getString("sd"),
                counter = parseCounter(value.getString("counter")),
            )
        }
        return out
    }

    private fun decodePending(obj: JSONObject): HashMap<String, RemoteMutation> {
        val out = HashMap<String, RemoteMutation>()
        for (id in obj.keys()) {
            val value = obj.getJSONObject(id)
            val stamp = VersionStamp.parse(value.getString("vs")) ?: error("invalid pending stamp")
            val deleted = value.getBoolean("deleted")
            val hash = if (deleted) null else value.getString("hash")
            require(if (deleted) !value.has("hash") else hash != null)
            val prior = if (value.isNull("priorHash")) null else value.getString("priorHash")
            val expected = if (value.has("expectedHash")) {
                if (value.isNull("expectedHash")) null else value.getString("expectedHash")
            } else hash
            val localId = if (value.isNull("localId")) null else value.getString("localId")
            val generation = parseCounter(value.optString("generation", "0000000000000000"))
            out[id] = RemoteMutation(
                AuthenticatedMutation(id, stamp, value.getString("pub"), hash, deleted), prior, localId, generation, expected)
        }
        return out
    }

    private fun exactMutationIn(
        id: String,
        mutation: AuthenticatedMutation,
        savedStamps: Map<String, VersionStamp>,
        savedTombs: Map<String, VersionStamp>,
        savedHashes: Map<String, String>,
    ): Boolean = savedStamps[id] == mutation.stamp && if (mutation.deleted) {
        savedTombs[id] == mutation.stamp && savedHashes[id] == null
    } else {
        savedTombs[id] == null && savedHashes[id] == mutation.contentHash
    }

    private fun decodeStamps(obj: JSONObject): HashMap<String, VersionStamp> {
        val out = HashMap<String, VersionStamp>()
        for (key in obj.keys()) out[key] = VersionStamp.parse(obj.getString(key)) ?: error("invalid stamp")
        return out
    }

    private fun decodeStrings(obj: JSONObject): HashMap<String, String> {
        val out = HashMap<String, String>()
        for (key in obj.keys()) out[key] = obj.getString(key)
        return out
    }

    private fun parseCounter(value: String): Long {
        require(value.matches(Regex("^[0-7][0-9a-f]{15}$")))
        return java.lang.Long.parseUnsignedLong(value, 16)
    }

    fun clear() {
        clearMemory()
        try {
            stateFile()?.let { file ->
                file.delete()
                File(file.parentFile, ".${file.name}.sealed-only-v1").delete()
            }
        } catch (_: Throwable) { /* explicit forget is best effort */ }
    }
}

/**
 * App-global mutation journal, independent of any room. It survives Leave and
 * lets a later room reconnect distinguish hash ABA from no local mutation.
 */
class LocalModelRevisionJournal(
    private val filesDir: File,
    private val persistOverride: (() -> Boolean)? = null,
) {
    internal var lastPersistenceError: Throwable? = null
        private set
    private val generations = HashMap<String, Long>()
    private val file = File(filesDir, "sync_model_revisions.json")
    private val label = "sync/model-revisions"
    private val persistenceMutex = Mutex()
    val isPersistenceInFlight: Boolean get() = persistenceMutex.isLocked

    suspend fun awaitPersistence() = persistenceMutex.withLock { }

    /** Ordered event collector awaits durability without occupying the UI thread. */
    suspend fun bumpAllOffMain(localIds: Set<String>, io: CoroutineContext): Boolean = persistenceMutex.withLock {
        if (localIds.isEmpty()) return@withLock true
        val patch = synchronized(this) {
            LinkedHashMap<String, Long?>().also { values ->
                for (id in localIds) {
                    if (runCatching { UUID.fromString(id) }.isFailure) return@withLock false
                    val value = generations[id] ?: 0L
                    if (value >= VersionStamp.MAX_COUNTER) return@withLock false
                    values[id] = value + 1
                }
            }
        }
        withContext(NonCancellable) {
            val ok = withContext(io) { save(SyncSparseMapView(generations, patch)) }
            if (ok) synchronized(this@LocalModelRevisionJournal) {
                for ((id, value) in patch) generations[id] = checkNotNull(value)
            }
            ok
        }
    }

    @Synchronized fun generation(localId: String?): Long = localId?.let { generations[it] } ?: 0L

    /** Snapshot workers receive an immutable view, never the live journal. */
    @Synchronized fun generationsCopy(): Map<String, Long> = HashMap(generations)

    @Synchronized fun bump(localId: String): Boolean = bumpAll(setOf(localId))

    /**
     * Advances one durable generation for every object in a store mutation.
     * Bulk imports and layer operations can contain thousands of IDs; they are
     * one logical commit, so persist the complete journal once and roll the
     * entire in-memory candidate back if that atomic write fails.
     */
    @Synchronized fun bumpAll(localIds: Set<String>): Boolean {
        if (localIds.isEmpty()) return true

        val previous = HashMap<String, Long>(localIds.size)
        for (localId in localIds) {
            if (runCatching { UUID.fromString(localId) }.isFailure) return false
            val current = generations[localId] ?: 0L
            if (current >= VersionStamp.MAX_COUNTER) return false
            previous[localId] = current
        }
        for ((localId, current) in previous) generations[localId] = current + 1

        if (save()) return true
        for ((localId, current) in previous) {
            if (current == 0L) generations.remove(localId) else generations[localId] = current
        }
        return false
    }

    /** Startup reads and possible legacy resealing share the same writer order. */
    suspend fun loadOffMain(io: CoroutineContext): Boolean = persistenceMutex.withLock {
        val candidate = LocalModelRevisionJournal(filesDir, persistOverride)
        val ok = withContext(io) { candidate.load() }
        if (ok) synchronized(this@LocalModelRevisionJournal) {
            generations.clear(); generations.putAll(candidate.generations)
        }
        ok
    }

    @Synchronized fun load(): Boolean = try {
        when (val result = SafeStore.readOrQuarantine(file, label) { decodeJournal(it) }) {
            is SafeStore.LoadResult.Empty -> true
            is SafeStore.LoadResult.Loaded -> { generations.clear(); generations.putAll(result.value); true }
            is SafeStore.LoadResult.Locked, is SafeStore.LoadResult.Corrupt -> false
        }
    } catch (_: Throwable) { false }

    private fun save(values: Map<String, Long> = generations): Boolean {
        persistOverride?.let { return runCatching { it() }.getOrDefault(false) }
        return try {
            val json = buildJsonObject {
                put("schemaVersion", 1)
                put("generations", buildJsonObject {
                    for ((id, generation) in values) put(id, VersionStamp.counterHex16(generation))
                })
            }
            SafeStore.writeAtomically(file, label, json.toString())
            true
        } catch (error: Throwable) { lastPersistenceError = error; false }
    }

    private fun decodeJournal(text: String): HashMap<String, Long> {
        val json = Json.parseToJsonElement(text).jsonObject
        require(json["schemaVersion"]?.jsonPrimitive?.int == 1)
        val values = json["generations"]!!.jsonObject
        val out = HashMap<String, Long>()
        for ((id, element) in values) {
            require(runCatching { UUID.fromString(id) }.isSuccess)
            val encoded = element.jsonPrimitive.content
            require(encoded.matches(Regex("^[0-7][0-9a-f]{15}$")))
            out[id] = java.lang.Long.parseUnsignedLong(encoded, 16)
        }
        return out
    }
}

/** Extracted production event handler so lossless store paths are testable. */
class LocalRevisionEventProcessor(
    private val journal: LocalModelRevisionJournal,
    private val onPersistenceFailure: () -> Unit,
) {
    fun process(event: ModelMutationEvent): Boolean {
        if (event.origin == ModelMutationOrigin.REMOTE_SYNC) return true
        if (journal.bumpAll(event.localIds)) return true
        onPersistenceFailure()
        return false
    }
}
