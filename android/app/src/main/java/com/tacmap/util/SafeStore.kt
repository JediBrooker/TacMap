package com.tacmap.util

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Durable + encrypted JSON persistence shared by every on-device store.
 *
 * Three guarantees, and they're all here b/c this is mission data (waypoints,
 * drawings, calibrations):
 *
 *  - [writeAtomically] writes to a sibling temp file, fsyncs, then renames
 *    over the target. Crash / process-kill / power-loss mid-write can never
 *    leave the primary file truncated - rename is atomic so a reader sees
 *    either old bytes or complete new bytes, never a half-written file.
 *
 *  - Everything is sealed with AES-256-GCM under the [DataKey] before it
 *    touches the disk. The `label` you pass is bound in as associated data,
 *    so a blob can't be moved from one store to another.
 *
 *  - [readOrQuarantine] never treats an unreadable-but-present file as "no
 *    data". On decode failure it moves the file aside as
 *    <name>.corrupt-<epochMs> and returns [LoadResult.Corrupt] so the caller
 *    surfaces an error instead of clobbering the only copy.
 *
 * Note the difference between Corrupt and [LoadResult.Locked]. Corrupt means
 * the bytes are bad and we set them aside. Locked means the bytes are almost
 * certainly fine but the key is auth-bound and nobody has authenticated yet.
 * Quarantining on Locked would be a catastrophe: we'd move a perfectly good
 * waypoints.json aside and then the first edit would persist an empty list
 * over the top. So Locked touches nothing, and callers must not write either.
 *
 * Legacy plaintext files from builds before at-rest encryption are detected by
 * the absent magic and re-sealed in place on first read. Read plaintext ->
 * decode (that's the verify) -> atomic-write sealed over it. The rename means
 * there's never a window with two copies, and never an orphaned plaintext.
 */
object SafeStore {

    /** Supplies the at-rest key. Production wires [DataKey]; tests swap in a fixed key. */
    fun interface KeyProvider { fun key(): ByteArray }

    @Volatile
    var keyProvider: KeyProvider = KeyProvider { DataKey.key() }

    sealed class LoadResult<out T> {
        /** file doesn't exist, genuine fresh install */
        object Empty : LoadResult<Nothing>()
        /** file decoded cleanly */
        data class Loaded<T>(val value: T) : LoadResult<T>()
        /** file existed but couldn't be read/decoded; preserved as quarantine */
        data class Corrupt(val quarantinedTo: File?, val error: Throwable) : LoadResult<Nothing>()
        /** key is auth-bound and locked. File untouched. Do not write. */
        data class Locked(val error: Throwable) : LoadResult<Nothing>()
    }

    /** Seal [text] under [label] and atomically replace [file]. */
    fun writeAtomically(file: File, label: String, text: String) {
        writeSealedBytes(file, SealedEnvelope.sealFile(keyProvider.key(), text.toByteArray(Charsets.UTF_8), label))
    }

    fun <T> readOrQuarantine(file: File, label: String, decode: (String) -> T): LoadResult<T> {
        if (!file.exists()) return LoadResult.Empty

        val key = try {
            keyProvider.key()
        } catch (e: DataKey.LockedException) {
            return LoadResult.Locked(e)
        } catch (e: DataKey.UnrecoverableException) {
            // KEK is gone so the bytes will never decrypt again. Still don't
            // quarantine - leave them be in case the user can restore the
            // keystore, and let the UI say what happened.
            return LoadResult.Locked(e)
        }

        return try {
            val raw = file.readBytes()
            val plain = if (SealedEnvelope.isSealedFile(raw)) {
                SealedEnvelope.openFile(key, raw, label)
                    ?: throw IOException("sealed store failed authentication (tampered, or wrong store)")
            } else {
                // Pre-encryption build wrote this. Decode it, then seal it in place.
                raw
            }
            val value = decode(plain.toString(Charsets.UTF_8))
            if (!SealedEnvelope.isSealedFile(raw)) {
                writeSealedBytes(file, SealedEnvelope.sealFile(key, plain, label))
            }
            LoadResult.Loaded(value)
        } catch (e: Throwable) {
            val quarantine = File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}")
            val moved = runCatching {
                if (file.renameTo(quarantine)) quarantine
                else { file.copyTo(quarantine, overwrite = true); file.delete(); quarantine }
            }.getOrNull()
            LoadResult.Corrupt(moved, e)
        }
    }

    private fun writeSealedBytes(file: File, bytes: ByteArray) {
        val dir = file.parentFile
            ?: throw IOException("No parent directory for ${file.absolutePath}")
        if (!dir.exists()) dir.mkdirs()
        val tmp = File(dir, file.name + ".tmp")
        FileOutputStream(tmp).use { fos ->
            fos.write(bytes)
            fos.flush()
            fos.fd.sync() // fsync before rename so we don't lose data on crash
        }
        if (!tmp.renameTo(file)) {
            // some filesystems won't rename over existing, so delete + rename as fallback
            if (!file.delete() || !tmp.renameTo(file)) {
                tmp.delete()
                throw IOException("Atomic rename failed for ${file.absolutePath}")
            }
        }
    }
}
