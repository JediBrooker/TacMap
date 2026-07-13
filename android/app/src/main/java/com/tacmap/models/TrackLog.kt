package com.tacmap.models

import com.tacmap.util.SafeStore
import com.tacmap.util.SealedEnvelope
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Append-only log of track points. Pure file I/O with no Android deps so its
 * unit-testable on JVM. TrackRecorder delegates here so recordings survive
 * process death - every fix is fsync'd as it arrives, and read() recovers the
 * track on next launch.
 *
 * Each line is sealed on its own rather than sealing the whole file, for two
 * reasons. Appending to a whole-file envelope would mean decrypt-all,
 * re-encrypt-all, rewrite-all on every single GPS fix, which is O(n^2) over a
 * patrol. And per-line sealing keeps the property we actually care about: a
 * torn or garbled line fails its tag check and gets skipped, and every other
 * line still opens. One bad byte costs you one fix, not the track.
 *
 * That does mean we don't bind line ordering into the AAD. Someone with write
 * access to the app sandbox could reorder or drop lines without detection.
 * We took that trade knowingly - binding the line index would make a single
 * torn line invalidate the whole tail of the file, and an attacker who can
 * write inside our sandbox has already beaten at-rest encryption anyway.
 */
object TrackLog {
    private val json = Json { ignoreUnknownKeys = true }

    /** Bound in as AEAD associated data, keeps these lines from opening elsewhere. */
    const val LABEL = "tracks/recording.ndjson"

    /** What [read] found on disk, so the caller knows whether to re-seal. */
    data class Recovered(val points: List<TrackPoint>, val hadLegacyLines: Boolean)

    /** Append one point and force it to stable storage before returning. */
    fun append(file: File, point: TrackPoint) {
        SafeStore.markSealedOnlyAuthenticated(LABEL)
        val line = SealedEnvelope.sealLine(
            SafeStore.keyProvider.key(),
            json.encodeToString(TrackPoint.serializer(), point).toByteArray(Charsets.UTF_8),
            LABEL
        )
        file.parentFile?.mkdirs()
        FileOutputStream(file, /* append = */ true).use { fos ->
            fos.write((line + "\n").toByteArray(Charsets.UTF_8))
            fos.flush()
            fos.fd.sync()
        }
        markSealedOnly(file)
    }

    /**
     * Read every well-formed point, skipping any line that won't open (a partial
     * line from an interrupted write, or one that got mangled). Recovery must
     * never throw. Plaintext lines written by a pre-encryption build still read,
     * and get reported so the caller can [reseal].
     */
    fun read(file: File): Recovered {
        if (!file.exists()) return Recovered(emptyList(), false)
        val key = SafeStore.keyProvider.key()
        var legacy = false
        val lines = file.readLines()
        val sealedOnly = SafeStore.isSealedOnlyAuthenticated(LABEL)
        if (sealedOnly && lines.any { it.isNotBlank() && !SealedEnvelope.isSealedLine(it) }) {
            throw java.io.IOException("plaintext track data rejected after sealed-only migration")
        }
        val points = lines.mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            if (SealedEnvelope.isSealedLine(line)) {
                val plain = SealedEnvelope.openLine(key, line, LABEL) ?: return@mapNotNull null
                runCatching { json.decodeFromString(TrackPoint.serializer(), plain.toString(Charsets.UTF_8)) }
                    .getOrNull()
            } else {
                legacy = true
                runCatching { json.decodeFromString(TrackPoint.serializer(), line) }.getOrNull()
            }
        }
        return Recovered(points, legacy)
    }

    /**
     * Rewrite the log with every point sealed. Used once to migrate a plaintext
     * log from an older build. Goes via temp + rename so a crash halfway can't
     * leave us with half a track.
     */
    fun reseal(file: File, points: List<TrackPoint>) {
        val key = SafeStore.keyProvider.key()
        SafeStore.markSealedOnlyAuthenticated(LABEL)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { fos ->
            points.forEach { point ->
                val line = SealedEnvelope.sealLine(
                    key,
                    json.encodeToString(TrackPoint.serializer(), point).toByteArray(Charsets.UTF_8),
                    LABEL
                )
                fos.write((line + "\n").toByteArray(Charsets.UTF_8))
            }
            fos.flush()
            fos.fd.sync()
        }
        val moved = runCatching {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.isSuccess
        if (!moved) {
            tmp.delete()
            throw java.io.IOException("Atomic track reseal failed for ${file.absolutePath}")
        }
        markSealedOnly(file)
    }

    /** Nuke the log and start fresh. Atomic enough that a crash can't
     *  merge old track with a new one. */
    fun truncate(file: File) {
        SafeStore.markSealedOnlyAuthenticated(LABEL)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".empty.tmp")
        FileOutputStream(tmp, /* append = */ false).use { fos ->
            fos.flush()
            fos.fd.sync()
        }
        val moved = runCatching {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.isSuccess
        if (!moved) {
            // Do not delete the old log as a rename fallback: that would turn
            // a recoverable filesystem failure into irreversible track loss.
            tmp.delete()
            throw java.io.IOException("Atomic track reset failed for ${file.absolutePath}")
        }
        markSealedOnly(file)
    }

    fun delete(file: File) {
        if (file.exists() && !file.delete()) {
            throw java.io.IOException("Could not remove encrypted track log")
        }
        // Intentionally retain the marker. Once this install has migrated to
        // encrypted track logs, a later plaintext file at the same path is
        // never accepted as a legacy downgrade.
    }

    private fun marker(file: File) = File(file.parentFile, ".${file.name}.sealed-only-v1")

    private fun markSealedOnly(file: File) {
        val marker = marker(file)
        if (marker.exists()) return
        marker.parentFile?.mkdirs()
        val tmp = File(marker.parentFile, marker.name + ".tmp")
        FileOutputStream(tmp).use { fos ->
            fos.write(byteArrayOf(1)); fos.flush(); fos.fd.sync()
        }
        if (!tmp.renameTo(marker)) {
            if (!marker.exists()) {
                tmp.delete()
                throw java.io.IOException("Could not persist track migration marker")
            }
            tmp.delete()
        }
    }
}
