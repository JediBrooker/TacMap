package com.tacmap.util

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Durable JSON persistence primitives shared by every on-device store.
 *
 * Two guarantees the old `file.writeText` / `runCatching { decode }` pattern
 * didn't provide, and they matter b/c this is mission data (waypoints,
 * drawings, calibrations):
 *
 *  - [writeAtomically] writes to a sibling temp file, fsyncs, then renames
 *    over the target. Crash / process-kill / power-loss mid-write can never
 *    leave the primary file truncated - rename is atomic so a reader sees
 *    either old bytes or complete new bytes, never a half-written file.
 *
 *  - [readOrQuarantine] never treats an unreadable-but-present file as "no
 *    data". On decode failure it moves the file aside as
 *    <name>.corrupt-<epochMs> and returns [LoadResult.Corrupt] so the caller
 *    surfaces an error instead of clobbering the only copy.
 */
object SafeStore {

    sealed class LoadResult<out T> {
        /** file doesn't exist, genuine fresh install */
        object Empty : LoadResult<Nothing>()
        /** file decoded cleanly */
        data class Loaded<T>(val value: T) : LoadResult<T>()
        /** file existed but couldn't be read/decoded; preserved as quarantine */
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
