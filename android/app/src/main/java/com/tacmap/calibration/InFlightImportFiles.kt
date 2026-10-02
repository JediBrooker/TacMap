package com.tacmap.calibration

import java.io.File

/**
 * Files an import or bake is still writing (or holding for the page picker). The
 * reconcile keeps these, otherwise a .partial in the middle of a copy got deleted
 * by the next library write. Memory only: after process death they're orphans,
 * which is exactly what reconcile is for.
 */
internal object InFlightImportFiles {
    private val files = LinkedHashSet<File>()

    @Synchronized
    fun register(file: File) {
        files += file.absoluteFile
    }

    @Synchronized
    fun release(file: File) {
        files -= file.absoluteFile
    }

    @Synchronized
    fun snapshot(): Set<File> = files.toSet()

    /** test hook */
    @Synchronized
    internal fun clear() = files.clear()
}
