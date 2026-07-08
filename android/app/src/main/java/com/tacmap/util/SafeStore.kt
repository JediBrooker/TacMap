package com.tacmap.util

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Durable JSON persistence primitives shared by every on-device store.
 *
 * Two guarantees the previous `file.writeText` / `runCatching { decode }`
 * pattern did not provide, and which matter because the data here is mission
 * data (waypoints, drawings, calibrations):
 *
 *  - [writeAtomically] writes to a sibling temp file, fsyncs it, then renames
 *    over the target. A crash / process-kill / power-loss mid-write can never
 *    leave the primary file truncated — the rename is atomic, so a reader sees
 *    either the old bytes or the complete new bytes, never a half-written file.
 *
 *  - [readOrQuarantine] never treats an unreadable-but-present file as "no
 *    data". On a decode failure it moves the file aside as
 *    `<name>.corrupt-<epochMs>` and returns [LoadResult.Corrupt], so the caller
 *    surfaces an error instead of seeding-then-overwriting the only copy.
 */
object SafeStore {

    sealed class LoadResult<out T> {
        /** File does not exist — a genuine fresh install. */
        object Empty : LoadResult<Nothing>()
        /** File decoded cleanly. */
        data class Loaded<T>(val value: T) : LoadResult<T>()
        /** File existed but could not be read/decoded; it was preserved. */
        data class Corrupt(val quarantinedTo: File?, val error: Throwable) : LoadResult<Nothing>()
    }

    fun writeAtomically(file: File, text: String) {
        val dir = file.parentFile
            ?: throw IOException("No parent directory for ${file.absolutePath}")
        if (!dir.exists()) dir.mkdirs()
        val tmp = File(dir, file.name + ".tmp")
        FileOutputStream(tmp).use { fos ->
            fos.write(text.toByteArray(Charsets.UTF_8))
            fos.flush()
            fos.fd.sync() // force bytes to stable storage before the rename
        }
        if (!tmp.renameTo(file)) {
            // Some filesystems refuse rename-over-existing; fall back explicitly.
            if (!file.delete() || !tmp.renameTo(file)) {
                tmp.delete()
                throw IOException("Atomic rename failed for ${file.absolutePath}")
            }
        }
    }

    fun <T> readOrQuarantine(file: File, decode: (String) -> T): LoadResult<T> {
        if (!file.exists()) return LoadResult.Empty
        return try {
            LoadResult.Loaded(decode(file.readText()))
        } catch (e: Throwable) {
            val quarantine = File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}")
            val moved = runCatching {
                if (file.renameTo(quarantine)) quarantine
                else { file.copyTo(quarantine, overwrite = true); file.delete(); quarantine }
            }.getOrNull()
            LoadResult.Corrupt(moved, e)
        }
    }
}
