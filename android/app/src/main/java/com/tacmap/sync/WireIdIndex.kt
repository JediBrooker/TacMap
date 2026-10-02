package com.tacmap.sync

import com.tacmap.drawings.DrawingFeature
import com.tacmap.waypoints.Waypoint
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * One HMAC instance per metadata key. Mac.getInstance + init per object was
 * a big chunk of the old cost (S3-10), and a Mac isn't thread safe, so every
 * thread that hashes wire ids owns its own hasher.
 */
internal class WireIdHasher(metadataKey: ByteArray) {
    private val key = metadataKey.copyOf()
    private val mac: Mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }

    fun wireId(localId: String): String? {
        val uuid = runCatching { SyncIdentity.uuidToBytes(localId) }.getOrNull() ?: return null
        WireIdIndex.hmacCount.incrementAndGet()
        mac.reset()
        mac.update(WIRE_OBJ_PREFIX)
        mac.update(uuid)
        return SyncIdentity.urlB64(mac.doFinal())
    }

    /** Best effort, the JCE copy of the key can't be reached from here. */
    fun close() = key.fill(0)

    private companion object {
        val WIRE_OBJ_PREFIX = "tacmap-wire-obj-v3\u0000".toByteArray(Charsets.UTF_8)
    }
}

/**
 * localId <-> wireId for one room session (plans/04 section 18, S5-02,
 * S4-03). Built once after keys and stores are attached, kept up to date from
 * store contents and remote applies, dropped on leave, join and store detach.
 *
 * Both directions are O(1). A reverse miss means no local object, there is no
 * fallback scan. Entries are never dropped inside a session: a deleted object
 * still needs its wire id for the outbound tombstone, and resolving a remote
 * tombstone to an id that's already gone locally is a harmless no-op.
 */
internal class WireIdIndex(metadataKey: ByteArray) {
    private val hasher = WireIdHasher(metadataKey)
    private val forward = HashMap<String, String>()
    private val reverse = HashMap<String, String>()
    // identity of the store lists we last folded in, so an unchanged store costs nothing
    private var seenWaypoints: Any? = null
    private var seenFeatures: Any? = null

    val size: Int get() = forward.size

    /** Forward lookup, hashing a new id once. Null only for a non-UUID id. */
    fun wireId(localId: String): String? {
        forward[localId]?.let { return it }
        val wire = hasher.wireId(localId) ?: return null
        forward[localId] = wire
        reverse[wire] = localId
        return wire
    }

    fun localId(wireId: String): String? = reverse[wireId]

    fun add(localId: String) {
        wireId(localId)
    }

    /**
     * Folds the current store contents in. Only lists we haven't seen yet are
     * walked, and only ids we don't know yet get hashed.
     */
    fun refresh(waypoints: List<Waypoint>, features: List<DrawingFeature>) {
        if (seenWaypoints !== waypoints) {
            for (wp in waypoints) if (wp.id !in forward) add(wp.id)
            seenWaypoints = waypoints
        }
        if (seenFeatures !== features) {
            for (f in features) if (f.id !in forward) add(f.id)
            seenFeatures = features
        }
    }

    fun close() {
        forward.clear()
        reverse.clear()
        seenWaypoints = null
        seenFeatures = null
        hasher.close()
    }

    companion object {
        /** Test hook: wire id HMACs computed by every hasher in this process. */
        val hmacCount = AtomicLong()
    }
}
