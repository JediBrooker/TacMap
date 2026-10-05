package com.tacmap.sync

import com.tacmap.util.SafeStore
import com.tacmap.util.SealedEnvelope
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Raw v2 ids that 2.x iOS peers used, one per object (plans/04 section 16,
 * 3.0.1 amendment). Shipped 2.x iOS echo-deletes an edit of its own object that
 * comes back lowercase, so our put and del for it reuse the id it arrived with.
 * Sealed per room under a DEK-bound opaque name, object UUIDs only. A lost or
 * unreadable file just means lowercase sends until a snapshot teaches it again.
 *
 * Not thread safe: learn, remembered and takePendingWrite belong to the sync
 * owner thread. load runs before the store is handed over, write only touches
 * the file.
 */
internal class LegacyV2IdStore(private val directory: File, private val roomId: String) {
    private val ids = HashMap<String, String>() // state key -> raw id exactly as received
    private var dirty = false
    private val storeLabel = "sync/v2-ids/$roomId"

    val count: Int get() = ids.size

    fun remembered(stateKey: String): String? = ids[stateKey]

    /** Only for a record that already passed beats, AEAD, signature and, for a put, the embedded id check. */
    fun learn(rawId: String) {
        val key = LegacyV2Ids.stateKey(rawId) ?: return
        val current = ids[key]
        val next = LegacyV2Ids.remember(current, rawId) ?: return
        if (next == current) return
        // full means we stop learning, nothing gets evicted
        if (ids.size >= MAX_ENTRIES) return
        ids[key] = next
        dirty = true
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

    /** File only. False on a locked key or a failed write, the next learned id tries again. */
    fun write(text: String): Boolean = runCatching {
        SafeStore.writeAtomically(file(), storeLabel, text)
    }.isSuccess

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
        require(root.opt("version") as? Int == VERSION)
        val list = root.getJSONArray("ids")
        require(list.length() <= MAX_ENTRIES)
        val out = HashMap<String, String>(list.length())
        for (i in 0 until list.length()) {
            val raw = list.get(i) as? String ?: error("id isnt a string")
            val key = LegacyV2Ids.stateKey(raw) ?: error("id isnt a uuid")
            // only uppercase bearing ids ever get written
            require(LegacyV2Ids.remember(null, raw) == raw)
            require(out.put(key, raw) == null)
        }
        return out
    }

    companion object {
        const val DIRECTORY_NAME = "sync_v2_ids"
        const val MAX_ENTRIES = 10_000
        private const val VERSION = 1
        private const val MAX_FILE_BYTES = 1_048_576
    }
}
