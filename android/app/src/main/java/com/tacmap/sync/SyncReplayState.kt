package com.tacmap.sync

import com.tacmap.util.SafeStore
import org.json.JSONObject
import java.io.File

/**
 * Durable per-room replay state for v3 protocol. Survives leave/restart so
 * the relay can't roll back a reconnecting client. Sealed at rest via
 * SafeStore so a seized device can't read stamps.
 */
class SyncReplayState(private val roomId: String, private val filesDir: File? = null) {

    companion object {
        const val ADVANCE_WINDOW = 10_000L
    }

    var localCounter: Long = 0L
        private set
    var lastSnapshotSeq: Long = -1L
        private set

    // wireObjId -> highest accepted VersionStamp
    private val stamps = HashMap<String, VersionStamp>()
    // wireObjId -> delete VersionStamp (tombstone persists across reconnect)
    private val tombstones = HashMap<String, VersionStamp>()
    // wireObjId -> SHA-256 hex of last accepted plaintext
    private val contentHashes = HashMap<String, String>()
    // actorId -> pinned pubkey (base64url)
    private val actors = HashMap<String, String>()
    // actorId -> highest accepted presence counter
    private val presenceSeq = HashMap<String, Long>()
    // actorId -> session domain (base64url)
    private val sessionDomains = HashMap<String, String>()

    /**
     * Try to advance the stamp for [wireObjId]. Returns true if the incoming
     * stamp is strictly newer than anything we've seen (including tombstones).
     * Enforces the counter advance window relative to roomHighWater.
     */
    fun advance(wireObjId: String, incoming: VersionStamp): Boolean {
        if (incoming.counter > roomHighWater() + ADVANCE_WINDOW) return false
        val existing = stamps[wireObjId]
        val tomb = tombstones[wireObjId]
        if (existing != null && incoming <= existing) return false
        if (tomb != null && incoming <= tomb) return false
        stamps[wireObjId] = incoming
        return true
    }

    /**
     * Record a tombstone for [wireObjId]. Returns true if the incoming delete
     * stamp is strictly newer than any existing stamp/tombstone.
     */
    fun tombstone(wireObjId: String, incoming: VersionStamp): Boolean {
        if (incoming.counter > roomHighWater() + ADVANCE_WINDOW) return false
        val existing = stamps[wireObjId]
        val tomb = tombstones[wireObjId]
        if (existing != null && incoming <= existing) return false
        if (tomb != null && incoming <= tomb) return false
        tombstones[wireObjId] = incoming
        stamps[wireObjId] = incoming
        return true
    }

    fun isTombstoned(wireObjId: String): Boolean = tombstones.containsKey(wireObjId)

    fun getStamp(wireObjId: String): VersionStamp? = stamps[wireObjId]

    /**
     * TOFU-pin actorId -> pubkey. Returns true if ok (new pin or same key).
     * Returns false on key mismatch (swap attempt).
     */
    fun registerActor(actorId: String, pubkey: String): Boolean {
        val pinned = actors[actorId]
        if (pinned == null) {
            actors[actorId] = pubkey
            return true
        }
        return pinned == pubkey
    }

    fun getPinnedPubkey(actorId: String): String? = actors[actorId]

    fun updateSessionDomain(actorId: String, sd: String) {
        sessionDomains[actorId] = sd
    }

    fun getSessionDomain(actorId: String): String? = sessionDomains[actorId]

    /**
     * Check + advance presence counter for [actorId]. Returns true if counter
     * is strictly higher than what we've seen. Prevents presence replay.
     */
    fun advancePresence(actorId: String, counter: Long): Boolean {
        val existing = presenceSeq[actorId] ?: -1L
        if (counter <= existing) return false
        presenceSeq[actorId] = counter
        return true
    }

    fun setContentHash(wireObjId: String, hash: String) {
        contentHashes[wireObjId] = hash
    }

    fun getContentHash(wireObjId: String): String? = contentHashes[wireObjId]

    /**
     * Allocate the next counter value for a local write. Persisted BEFORE
     * send so a crash between persist and send just wastes one value.
     */
    fun nextCounter(): Long {
        localCounter += 1
        return localCounter
    }

    fun updateSnapshotSeq(seq: Long) {
        lastSnapshotSeq = seq
    }

    private fun roomHighWater(): Long {
        var max = localCounter
        for ((_, vs) in stamps) {
            if (vs.counter > max) max = vs.counter
        }
        for ((_, vs) in tombstones) {
            if (vs.counter > max) max = vs.counter
        }
        return max
    }

    // -- Persistence via SafeStore atomic write --

    private val label = "sync/room/$roomId"

    private fun stateFile(): File? {
        val dir = filesDir ?: return null
        val syncDir = File(dir, "sync_replay")
        if (!syncDir.exists()) syncDir.mkdirs()
        return File(syncDir, "$roomId.json")
    }

    fun save() {
        val file = stateFile() ?: return
        try {
            val json = JSONObject()
            json.put("schemaVersion", 3)
            json.put("localCounter", VersionStamp.counterHex16(localCounter))
            json.put("lastSnapshotSeq", lastSnapshotSeq)

            val stampsObj = JSONObject()
            for ((k, v) in stamps) stampsObj.put(k, v.encode())
            json.put("stamps", stampsObj)

            val tombObj = JSONObject()
            for ((k, v) in tombstones) tombObj.put(k, v.encode())
            json.put("tombstones", tombObj)

            val hashObj = JSONObject()
            for ((k, v) in contentHashes) hashObj.put(k, v)
            json.put("contentHashes", hashObj)

            val actorsObj = JSONObject()
            for ((k, v) in actors) actorsObj.put(k, v)
            json.put("actors", actorsObj)

            val presObj = JSONObject()
            for ((k, v) in presenceSeq) presObj.put(k, VersionStamp.counterHex16(v))
            json.put("presenceSeq", presObj)

            val sdObj = JSONObject()
            for ((k, v) in sessionDomains) sdObj.put(k, v)
            json.put("sessionDomains", sdObj)

            SafeStore.writeAtomically(file, label, json.toString())
        } catch (_: Throwable) {
            // key unavailable (auth-bound, locked) -- state kept in memory only
        }
    }

    fun load(): Boolean {
        val file = stateFile() ?: return false
        try {
            val result = SafeStore.readOrQuarantine(file, label) { JSONObject(it) }
            val json = when (result) {
                is SafeStore.LoadResult.Loaded -> result.value
                else -> return false
            }
            if (json.optInt("schemaVersion", 0) != 3) return false

            localCounter = java.lang.Long.parseUnsignedLong(
                json.optString("localCounter", "0000000000000000"), 16)
            lastSnapshotSeq = json.optLong("lastSnapshotSeq", -1L)

            val stampsObj = json.optJSONObject("stamps")
            if (stampsObj != null) {
                for (k in stampsObj.keys()) {
                    VersionStamp.parse(stampsObj.getString(k))?.let { stamps[k] = it }
                }
            }

            val tombObj = json.optJSONObject("tombstones")
            if (tombObj != null) {
                for (k in tombObj.keys()) {
                    VersionStamp.parse(tombObj.getString(k))?.let { tombstones[k] = it }
                }
            }

            val hashObj = json.optJSONObject("contentHashes")
            if (hashObj != null) {
                for (k in hashObj.keys()) contentHashes[k] = hashObj.getString(k)
            }

            val actorsObj = json.optJSONObject("actors")
            if (actorsObj != null) {
                for (k in actorsObj.keys()) actors[k] = actorsObj.getString(k)
            }

            val presObj = json.optJSONObject("presenceSeq")
            if (presObj != null) {
                for (k in presObj.keys()) {
                    presenceSeq[k] = java.lang.Long.parseUnsignedLong(presObj.getString(k), 16)
                }
            }

            val sdObj = json.optJSONObject("sessionDomains")
            if (sdObj != null) {
                for (k in sdObj.keys()) sessionDomains[k] = sdObj.getString(k)
            }

            return true
        } catch (_: Throwable) {
            return false
        }
    }

    fun clear() {
        localCounter = 0L
        lastSnapshotSeq = -1L
        stamps.clear()
        tombstones.clear()
        contentHashes.clear()
        actors.clear()
        presenceSeq.clear()
        sessionDomains.clear()
        stateFile()?.delete()
    }
}
