package com.tacmap.waypoints

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.tacmap.util.SafeStore
import com.tacmap.util.SealedEnvelope
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Portable, passive PNG artwork. Embedded in placed markers for export and Unit Sync. */
@Serializable
data class CustomSymbol(val id: String, val name: String, val png: String) {
    fun image(): Bitmap? = runCatching {
        val bytes = checkedBytes()
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    fun checkedBytes(): ByteArray {
        require(name.isNotBlank() && name.length <= 160 && png.length <= 44000)
        val bytes = Base64.decode(png, Base64.NO_WRAP)
        require(bytes.size in 24..32768)
        require(bytes.take(8) == listOf(137,80,78,71,13,10,26,10).map { it.toByte() })
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        require(options.outWidth in 1..256 && options.outHeight in 1..256)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        require(id == digest)
        return bytes
    }
}

@Serializable
data class CustomSymbolPack(val format: Int, val name: String, val attribution: String, val symbols: List<CustomSymbol>)

object CustomSymbolStore {
    private val json = Json { ignoreUnknownKeys = false }
    private var file: File? = null
    @Volatile var packs: List<CustomSymbolPack> = emptyList(); private set
    private const val storeLabel = "custom-symbol-packs/v1"
    fun initialize(context: Context) { file = File(context.filesDir, "custom-symbol-packs.sealed") }
    fun clear() { packs = emptyList() }
    @Synchronized fun reload() {
        val target = requireNotNull(file)
        val key = try { SafeStore.keyProvider.key() } catch (e: Exception) { clear(); throw e }
        try {
            val loaded = if (!target.exists()) emptyList() else {
                val bytes = target.inputStream().use { readBounded(it, 32 * 1024 * 1024 + 64) }
                require(SealedEnvelope.isSealedFile(bytes))
                val plain = requireNotNull(SealedEnvelope.openFile(key, bytes, storeLabel))
                json.decodeFromString<List<CustomSymbolPack>>(plain.decodeToString()).also { list ->
                    require(list.size <= 32); list.forEach(::validate)
                }
            }
            packs = loaded
        } catch (e: Exception) { clear(); throw e
        } finally { key.fill(0) }
    }
    fun symbol(id: String): CustomSymbol? = packs.asSequence().flatMap { it.symbols.asSequence() }.firstOrNull { it.id == id }
    fun entries(): List<MarkerCatalog.Entry> = packs.flatMap { pack -> pack.symbols.map {
        MarkerCatalog.Entry(it.id, "${pack.name} / ${it.name}", "?", "#3B7BE0", customName = true)
    }}.distinctBy { it.id }
    fun readBounded(input: InputStream, max: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer); if (n < 0) break
            require(output.size() <= max - n)
            output.write(buffer, 0, n)
        }
        return output.toByteArray()
    }
    fun preflight(text: String) {
        var depth = 0; var tokens = 0; var quoted = false; var escaped = false
        for (c in text) {
            if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false }
            else when (c) {
                '"' -> { quoted = true; require(++tokens <= 30000) }
                '{', '[' -> { require(++depth <= 8); require(++tokens <= 30000) }
                '}', ']' -> require(--depth >= 0)
                ',' -> require(++tokens <= 30000)
            }
        }
        require(!quoted && depth == 0)
    }
    fun validate(pack: CustomSymbolPack) {
        require(pack.format == 1 && pack.name.isNotBlank() && pack.name.length <= 100 && pack.attribution.length <= 2000)
        require(pack.symbols.size in 1..1000 && pack.symbols.map { it.id }.distinct().size == pack.symbols.size)
        pack.symbols.forEach { requireNotNull(it.image()).recycle() }
    }
    fun preparePack(input: InputStream): CustomSymbolPack {
        val text = readBounded(input, 16 * 1024 * 1024).decodeToString()
        preflight(text)
        val pack = json.decodeFromString<CustomSymbolPack>(text)
        validate(pack)
        return pack
    }
    // Production commits on the Activity's main thread after background parsing returns.
    // No suspension/key copy crosses the lifecycle lock boundary during the commit.
    @Synchronized fun installPack(pack: CustomSymbolPack): CustomSymbolPack {
        reload() // A locked, corrupt or oversized existing store must never become an empty writable store.
        validate(pack)
        val updated = packs.filterNot { it.name == pack.name } + pack
        val bytes = json.encodeToString(updated).toByteArray()
        require(updated.size <= 32 && bytes.size <= 32 * 1024 * 1024)
        SafeStore.writeAtomically(requireNotNull(file), storeLabel, bytes.decodeToString())
        packs = updated
        return pack
    }
    fun importPack(input: InputStream): CustomSymbolPack = installPack(preparePack(input))
}

/** Case/diacritic-insensitive AND search across pack, category and symbol names. */
object CustomSymbolSearch {
    private fun folded(value: String): String = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(java.util.Locale.ROOT)
    fun matches(text: String, query: String): Boolean {
        val value = folded(text)
        return folded(query).trim().split(Regex("\\s+")).all { value.contains(it) }
    }
}
