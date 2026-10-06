package com.tacmap.sync

import com.tacmap.util.SafeStore
import com.tacmap.util.SealedEnvelope
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The v2 id casing pinned per object (plans/04 section 16, 3.0.1 and 3.0.2
 * amendments). Shipped 2.x iOS echo-deletes an edit of its own object that
 * comes back lowercase, and shipped 2.x Android drops anything uppercase, so
 * the first casing this device sent or accepted for an object is what our
 * put and del keep using. Sealed per room under a DEK-bound opaque name,
 * object UUIDs only. A lost or unreadable file just means lowercase sends
 * until a snapshot or our next send pins it again.
 *
 * Not thread safe: learn, pinOwn, remembered and takePendingWrite belong to
 * the sync owner thread. load runs before the store is handed over, write
 * only touches the file.
 */
internal class LegacyV2IdStore(private val directory: File, private val roomId: String) {
    private val ids = HashMap<String, String>() // state key -> pinned raw id, exact casing
    private var dirty = false
    private val storeLabel = "sync/v2-ids/$roomId"

    val count: Int get() = ids.size

    fun remembered(stateKey: String): String? = ids[stateKey]

    /** Only for a record that already passed beats, AEAD, signature and, for a put, the embedded id check. */
    fun learn(rawId: String) {
        val key = LegacyV2Ids.stateKey(rawId) ?: return
        pin(key, LegacyV2Ids.remember(ids[key], rawId))
    }

    /** Our own put or del, before its wire id. True when that pinned something new. */
    fun pinOwn(localId: String): Boolean {
        val key = LegacyV2Ids.stateKey(localId) ?: return false
        return pin(key, LegacyV2Ids.pinOwn(ids[key], localId))
    }

    private fun pin(key: String, next: String?): Boolean {
        if (next == null || next == ids[key]) return false
        // full means nothing new gets pinned, nothing gets evicted either
        if (ids.size >= MAX_ENTRIES) return false
        ids[key] = next
        dirty = true
        return true
    }

    /** Missing, locked and damaged all come back empty. Off main. */
    fun load() {
        ids.clear()
        dirty = false
        val file = runCatching { file() }.getOrNull() ?: return
        if (file.exists()) {
            // never had a plaintext format, so a bare file isnt ours to migrate
            val raw = runCatching { file.readBytes() }.getOrNull() ?: return
            if (raw.size > MAX_FILE_BYTES || !SealedEnvelope.isSealedFile(raw)) return
        }
        val loaded = runCatching { SafeStore.readOrQuarantine(file, storeLabel, ::decode) }.getOrNull()
        if (loaded is SafeStore.LoadResult.Loaded) ids.putAll(loaded.value)
    }

    /** What to write, encoded on the owner thread, or null if nothing new was learned since the last one. */
    fun takePendingWrite(): String? {
        if (!dirty) return null
        dirty = false
        return JSONObject()
            .put("version", VERSION)
            .put("ids", JSONArray(ids.values.sorted()))
            .toString()
    }

    /** File only. False on a locked key or a failed write, the caller hands that back through writeFailed. */
    fun write(text: String): Boolean = runCatching {
        SafeStore.writeAtomically(file(), storeLabel, text)
    }.isSuccess

    /** The last takePendingWrite didn't make it to disk, so the next one hands it out again. */
    fun writeFailed() {
        dirty = true
    }

    private fun file(): File {
        val namingKey = SafeStore.keyProvider.key()
        return try {
            SyncLocalStore.resolveFile(
                directory = directory,
                roomId = roomId,
                domain = SyncIdentity.LocalStoreDomain.LEGACY_V2_IDS,
                dataKey = namingKey,
            )
        } finally {
            namingKey.fill(0)
        }
    }

    private fun decode(text: String): Map<String, String> {
        val root = JSONObject(text)
        require(root.keys().asSequence().toSet() == setOf("version", "ids"))
        val version = root.opt("version") as? Int
        require(version == VERSION || version == VERSION_301)
        val list = root.getJSONArray("ids")
        require(list.length() <= MAX_ENTRIES)
        val out = HashMap<String, String>(list.length())
        for (i in 0 until list.length()) {
            val raw = list.get(i) as? String ?: error("id isnt a string")
            val key = LegacyV2Ids.stateKey(raw) ?: error("id isnt a uuid")
            // 3.0.1 only ever wrote uppercase bearing ids, they load as they are and stick
            if (version == VERSION_301) require(raw.any { it in 'A'..'F' })
            require(out.put(key, raw) == null)
        }
        return out
    }

    companion object {
        const val DIRECTORY_NAME = "sync_v2_ids"
        const val MAX_ENTRIES = 10_000
        /** 3.0.2, pins in either case. */
        private const val VERSION = 2
        /** 3.0.1, uppercase only. */
        private const val VERSION_301 = 1
        private const val MAX_FILE_BYTES = 1_048_576
    }
}
