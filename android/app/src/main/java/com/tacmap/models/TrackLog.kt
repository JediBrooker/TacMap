package com.tacmap.models

import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

/**
 * Append-only on-disk log of track points (newline-delimited JSON).
 *
 * Pure file/serialisation logic with no Android dependencies, so it is unit
 * testable on the JVM. [TrackRecorder] delegates persistence here so that a
 * recording survives process death: every accepted fix is fsync'd to disk as it
 * arrives, and [read] recovers the whole track on next launch.
 */
object TrackLog {
    private val json = Json { ignoreUnknownKeys = true }

    /** Append one point and force it to stable storage before returning. */
    fun append(file: File, point: TrackPoint) {
        file.parentFile?.mkdirs()
        FileOutputStream(file, /* append = */ true).use { fos ->
            fos.write((json.encodeToString(TrackPoint.serializer(), point) + "\n").toByteArray(Charsets.UTF_8))
            fos.flush()
            fos.fd.sync()
        }
    }

    /** Read every well-formed point; skips any trailing partial line from a
     *  write that was interrupted mid-append (defensive — the fsync per line
     *  makes torn writes unlikely, but recovery must never throw). */
    fun read(file: File): List<TrackPoint> {
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            runCatching { json.decodeFromString(TrackPoint.serializer(), line) }.getOrNull()
        }
    }

    /** Start a fresh log, discarding any prior contents atomically enough that
     *  a crash can't merge an old track with a new one. */
    fun truncate(file: File) {
        file.parentFile?.mkdirs()
        FileOutputStream(file, /* append = */ false).use { fos ->
            fos.flush()
            fos.fd.sync()
        }
    }

    fun delete(file: File) {
        runCatching { file.delete() }
    }
}
