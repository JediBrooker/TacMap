package com.tacmap.sync

import com.tacmap.util.SafeStore
import com.tacmap.models.ModelMutationEvent
import com.tacmap.models.ModelMutationOrigin
import org.json.JSONObject
import java.io.File
import java.math.BigInteger
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Durable v3 rollback state. Security-sensitive changes are committed here
 * before the corresponding model mutation or network send. A failed sealed
 * write rolls the in-memory change back and is reported to the caller.
 */
class SyncReplayState(
    private val roomId: String,
    private val filesDir: File? = null,
    private val persistOverride: (() -> Boolean)? = null,
) {

    companion object {
        const val ADVANCE_WINDOW = 10_000L
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

    private data class PresenceSession(
        val sessionDomain: String,
        val counter: Long,
    )

    private data class MemorySnapshot(
        val localCounter: Long,
        val lastSnapshotSeq: Long,
        val stamps: HashMap<String, VersionStamp>,
        val tombstones: HashMap<String, VersionStamp>,
        val hashes: HashMap<String, String>,
        val actors: HashMap<String, String>,
        val helloEpochs: HashMap<String, String>,
        val presenceSessions: HashMap<String, PresenceSession>,
        val pendingModelApplications: HashMap<String, RemoteMutation>,
    )

    private fun memorySnapshot() = MemorySnapshot(
        localCounter, lastSnapshotSeq, HashMap(stamps), HashMap(tombstones),
        HashMap(contentHashes), HashMap(actors), HashMap(helloEpochs),
        HashMap(presenceSessions), HashMap(pendingModelApplications)
    )

    private fun restore(snapshot: MemorySnapshot) {
        localCounter = snapshot.localCounter
        lastSnapshotSeq = snapshot.lastSnapshotSeq
        stamps.clear(); stamps.putAll(snapshot.stamps)
        tombstones.clear(); tombstones.putAll(snapshot.tombstones)
        contentHashes.clear(); contentHashes.putAll(snapshot.hashes)
        actors.clear(); actors.putAll(snapshot.actors)
        helloEpochs.clear(); helloEpochs.putAll(snapshot.helloEpochs)
        presenceSessions.clear(); presenceSessions.putAll(snapshot.presenceSessions)
        pendingModelApplications.clear(); pendingModelApplications.putAll(snapshot.pendingModelApplications)
    }

    private inline fun persistTransaction(change: () -> Unit): Boolean {
        val before = memorySnapshot()
        return try {
            change()
            if (!save()) throw IllegalStateException("replay-state persistence failed")
            true
        } catch (_: Throwable) {
            restore(before)
            false
        }
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
            pendingModelApplications[mutation.wireObjectId] = remote
        }
    }

    /**
     * Atomically commit a fully authenticated snapshot and its fence. Snapshot
     * counters establish the reconnect baseline and therefore do not use the
     * live advance window. Older records are ignored but the fence is monotonic.
     */
    fun commitSnapshot(mutations: List<AuthenticatedMutation>, seq: Long): Boolean {
        if (seq < 0) return false
        val actorPins = HashMap(actors)
        for (mutation in mutations) {
            if (!validMutationKind(mutation)) return false
            val pinned = actorPins[mutation.stamp.actorId]
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

    /** Snapshot variant that durably records model application work for new mutations. */
    fun commitRemoteSnapshot(remotes: List<RemoteMutation>, seq: Long): Boolean {
        if (seq < 0) return false
        val actorPins = HashMap(actors)
        for (remote in remotes) {
            if (!validRemote(remote)) return false
            val mutation = remote.mutation
            val pinned = actorPins[mutation.stamp.actorId]
            if (pinned != null && pinned != mutation.pubkey) return false
            actorPins[mutation.stamp.actorId] = mutation.pubkey
        }
        return persistTransaction {
            for (remote in remotes) {
                val mutation = remote.mutation
                if (beatsCurrent(mutation.wireObjectId, mutation.stamp)) {
                    applyMutation(mutation)
                    pendingModelApplications[mutation.wireObjectId] = remote
                }
                // Exact persisted records retain an existing matching pending
                // marker. Exact records without one were already resolved.
            }
            lastSnapshotSeq = maxOf(lastSnapshotSeq, seq)
        }
    }

    private fun applyMutation(mutation: AuthenticatedMutation) {
        actors[mutation.stamp.actorId] = mutation.pubkey
        stamps[mutation.wireObjectId] = mutation.stamp
        localCounter = maxOf(localCounter, mutation.stamp.counter)
        if (mutation.deleted) {
            tombstones[mutation.wireObjectId] = mutation.stamp
            contentHashes.remove(mutation.wireObjectId)
        } else {
            tombstones.remove(mutation.wireObjectId)
            mutation.contentHash?.let { contentHashes[mutation.wireObjectId] = it }
        }
    }

    private fun validMutationKind(mutation: AuthenticatedMutation): Boolean =
        if (mutation.deleted) mutation.contentHash == null
        else mutation.contentHash?.matches(Regex("^[0-9a-f]{64}$")) == true

    private fun validRemote(remote: RemoteMutation): Boolean =
        validMutationKind(remote.mutation) &&
            (remote.priorModelHash == null || remote.priorModelHash.matches(Regex("^[0-9a-f]{64}$"))) &&
            (remote.expectedModelHash == null) == remote.mutation.deleted &&
            (remote.expectedModelHash == null || remote.expectedModelHash.matches(Regex("^[0-9a-f]{64}$"))) &&
            (remote.localModelId == null || runCatching { java.util.UUID.fromString(remote.localModelId) }.isSuccess) &&
            remote.acceptedGeneration in 0..VersionStamp.MAX_COUNTER

    /** Reserve a strictly increasing unsigned-64 hello epoch before signing. */
    fun reserveHelloEpoch(actorId: String): String? {
        if (SyncIdentity.urlB64Decode32(actorId) == null) return null
        val current = helloEpochs[actorId]?.let { runCatching { BigInteger(it, 16) }.getOrNull() } ?: BigInteger.ZERO
        val next = current + BigInteger.ONE
        if (next > BigInteger("ffffffffffffffff", 16)) return null
        val hex = next.toString(16).padStart(16, '0')
        return hex.takeIf { persistTransaction { helloEpochs[actorId] = hex } }
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
            actors[actorId] = pubkey
            helloEpochs[actorId] = epochHex
            presenceSessions[actorId] = PresenceSession(sessionDomain, 0L)
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
     * Persist the authenticated presence counter before exposing the peer to
     * the map. A failed secure write rejects the update and rolls memory back.
     */
    fun commitPresence(
        actorId: String,
        pubkey: String,
        sessionDomain: String,
        counter: Long,
    ): Boolean {
        if (!canAcceptPresence(actorId, pubkey, sessionDomain, counter)) return false
        return persistTransaction {
            presenceSessions[actorId] = PresenceSession(sessionDomain, counter)
        }
    }

    fun getPresenceSessionDomain(actorId: String): String? =
        presenceSessions[actorId]?.sessionDomain

    fun getPresenceCounter(actorId: String): Long? =
        presenceSessions[actorId]?.counter

    /** Reserve and persist an outbound put stamp/hash before transmission. */
    fun reserveLocalPut(wireObjectId: String, actorId: String, pubkey: String, contentHash: String): VersionStamp? {
        if (SyncIdentity.urlB64Decode32(wireObjectId) == null ||
            SyncIdentity.urlB64Decode32(actorId) == null || SyncIdentity.urlB64Decode32(pubkey) == null ||
            !contentHash.matches(Regex("^[0-9a-f]{64}$"))) return null
        if (localCounter >= VersionStamp.MAX_COUNTER) return null
        val next = maxOf(localCounter, roomHighWater()) + 1
        if (next < 0 || next > VersionStamp.MAX_COUNTER) return null
        val stamp = VersionStamp(next, actorId)
        val ok = persistTransaction {
            localCounter = next
            actors[actorId] = pubkey
            stamps[wireObjectId] = stamp
            tombstones.remove(wireObjectId)
            contentHashes[wireObjectId] = contentHash
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
            actors[actorId] = pubkey
            stamps[wireObjectId] = stamp
            tombstones[wireObjectId] = stamp
            contentHashes.remove(wireObjectId)
        }
        return stamp.takeIf { ok }
    }

    // Compatibility helpers retained for the pure replay-state tests.
    fun advance(wireObjectId: String, incoming: VersionStamp): Boolean {
        if (!canAcceptLive(wireObjectId, incoming)) return false
        stamps[wireObjectId] = incoming
        localCounter = maxOf(localCounter, incoming.counter)
        return true
    }

    fun tombstone(wireObjectId: String, incoming: VersionStamp): Boolean {
        if (!canAcceptLive(wireObjectId, incoming)) return false
        stamps[wireObjectId] = incoming
        tombstones[wireObjectId] = incoming
        localCounter = maxOf(localCounter, incoming.counter)
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
        return persistTransaction { pendingModelApplications.remove(mutation.wireObjectId) }
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

    fun roomHighWater(): Long {
        var max = localCounter
        for (vs in stamps.values) max = maxOf(max, vs.counter)
        for (vs in tombstones.values) max = maxOf(max, vs.counter)
        return max
    }

    private val label = "sync/room/$roomId"

    private fun stateFile(): File? {
        val dir = filesDir ?: return null
        val syncDir = File(dir, "sync_replay")
        if (!syncDir.exists() && !syncDir.mkdirs()) throw IllegalStateException("cannot create replay-state directory")
        return File(syncDir, "$roomId.json")
    }

    /** Returns false instead of swallowing a locked key or failed atomic write. */
    fun save(): Boolean {
        persistOverride?.let { return runCatching { it() }.getOrDefault(false) }
        val file = stateFile() ?: return true
        return try {
            SafeStore.writeAtomically(file, label, encode().toString())
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** Empty state is valid; locked, corrupt, or invalid state fails closed. */
    fun load(): Boolean {
        val file = try { stateFile() } catch (_: Throwable) { return false } ?: return true
        return try {
            when (val result = SafeStore.readOrQuarantine(file, label) { decode(JSONObject(it)) }) {
                is SafeStore.LoadResult.Empty -> true
                is SafeStore.LoadResult.Loaded -> {
                    restore(result.value)
                    true
                }
                is SafeStore.LoadResult.Locked, is SafeStore.LoadResult.Corrupt -> false
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun encode(): JSONObject = JSONObject().apply {
        put("schemaVersion", 3)
        put("localCounter", VersionStamp.counterHex16(localCounter))
        put("lastSnapshotSeq", lastSnapshotSeq)
        put("stamps", JSONObject().also { out -> for ((k, v) in stamps) out.put(k, v.encode()) })
        put("tombstones", JSONObject().also { out -> for ((k, v) in tombstones) out.put(k, v.encode()) })
        put("contentHashes", JSONObject().also { out -> for ((k, v) in contentHashes) out.put(k, v) })
        put("actors", JSONObject().also { out -> for ((k, v) in actors) out.put(k, v) })
        put("helloEpochs", JSONObject().also { out -> for ((k, v) in helloEpochs) out.put(k, v) })
        put("presenceSeq", JSONObject().also { out ->
            for ((actor, session) in presenceSessions) {
                out.put(actor, JSONObject().apply {
                    put("sd", session.sessionDomain)
                    put("counter", VersionStamp.counterHex16(session.counter))
                })
            }
        })
        put("pendingModelApplications", JSONObject().also { out ->
            for ((id, remote) in pendingModelApplications) out.put(id, encodeRemote(remote))
        })
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

    private fun decode(json: JSONObject): MemorySnapshot {
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
        require(actorPins.all { (actor, pub) ->
            SyncIdentity.urlB64Decode32(actor) != null && SyncIdentity.urlB64Decode32(pub) != null
        })
        require(decodedStamps.all { (id, stamp) ->
            SyncIdentity.urlB64Decode32(id) != null && actorPins[stamp.actorId] != null
        })
        require(decodedTombs.all { (id, stamp) -> decodedStamps[id] == stamp })
        require(hashes.all { (id, hash) -> id in decodedStamps && id !in decodedTombs && hash.matches(Regex("^[0-9a-f]{64}$")) })
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
        return MemorySnapshot(
            local, seq, decodedStamps, decodedTombs, hashes, actorPins, epochs,
            sessions, pending
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
        localCounter = 0L
        lastSnapshotSeq = -1L
        stamps.clear(); tombstones.clear(); contentHashes.clear(); actors.clear(); helloEpochs.clear()
        presenceSessions.clear(); pendingModelApplications.clear(); transientPresenceSeq.clear()
        try { stateFile()?.delete() } catch (_: Throwable) { /* explicit forget is best effort */ }
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

    @Synchronized fun generation(localId: String?): Long = localId?.let { generations[it] } ?: 0L

    @Synchronized fun bump(localId: String): Boolean {
        if (runCatching { UUID.fromString(localId) }.isFailure) return false
        val current = generations[localId] ?: 0L
        if (current >= VersionStamp.MAX_COUNTER) return false
        generations[localId] = current + 1
        if (save()) return true
        if (current == 0L) generations.remove(localId) else generations[localId] = current
        return false
    }

    @Synchronized fun load(): Boolean = try {
        when (val result = SafeStore.readOrQuarantine(file, label) { decodeJournal(it) }) {
            is SafeStore.LoadResult.Empty -> true
            is SafeStore.LoadResult.Loaded -> { generations.clear(); generations.putAll(result.value); true }
            is SafeStore.LoadResult.Locked, is SafeStore.LoadResult.Corrupt -> false
        }
    } catch (_: Throwable) { false }

    private fun save(): Boolean {
        persistOverride?.let { return runCatching { it() }.getOrDefault(false) }
        return try {
            val json = buildJsonObject {
                put("schemaVersion", 1)
                put("generations", buildJsonObject {
                    for ((id, generation) in generations) put(id, VersionStamp.counterHex16(generation))
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
        if (event.localIds.all { journal.bump(it) }) return true
        onPersistenceFailure()
        return false
    }
}
