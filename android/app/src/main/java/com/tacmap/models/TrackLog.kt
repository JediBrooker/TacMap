package com.tacmap.models

import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

/**
 * Append-only NDJSON log of track points. Pure file I/O with no Android
 * deps so its unit-testable on JVM. TrackRecorder delegates here so
 * recordings survive process death - every fix is fsync'd as it arrives,
 * and read() recovers the track on next launch.
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

    /** Read all well-formed points, skip any partial line from an interrupted
     *  write. fsync per line makes torn writes unlikely but recovery must
     *  never throw regardless. */
    fun read(file: File): List<TrackPoint> {
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            runCatching { json.decodeFromString(TrackPoint.serializer(), line) }.getOrNull()
        }
    }

    /** Nuke the log and start fresh. Atomic enough that a crash can't
     *  merge old track with a new one. */
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
