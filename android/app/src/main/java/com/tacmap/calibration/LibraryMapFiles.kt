package com.tacmap.calibration

import java.io.File

/**
 * The file side of the imported-map library: the reconcile keep set, the bake-only sweep
 * and Remove Offline Tiles' delete (WP2 guarantees on the WP4 library, M5-M7). MapViewModel
 * calls these, JVM tests too, so the tests run the same wiring the app does. The app hands
 * in the sealed library read under the lock, never a screen's in-memory copy of it.
 */
internal object LibraryMapFiles {
    /**
     * Delete map files in pdf_maps, mbtiles and offline_tiles no entry owns, then hand the
     * entries' content keys to [pruneDrafts]. Keep = every entry's file + sidecars, every PDF's
     * valid bake + sidecars, anything in flight. [state] and [inFlight] are both read once the
     * managed files lock is held (the bake publish and Remove take it too): the keep set is the
     * sealed library as it is right then, and a bake moved in while this waited for the lock
     * is in the snapshot. null state = no authoritative read (locked, corrupt, migrating),
     * nothing goes and it says null
     */
    fun reconcile(
        filesDir: File,
        inFlight: () -> Set<File> = InFlightImportFiles::snapshot,
        pruneDrafts: (Set<String>) -> Unit = {},
        state: () -> LibraryState?,
    ): Boolean? = ActiveMapSelectionStore.withManagedFilesLock {
        val s = state() ?: return@withManagedFilesLock null
        if (!s.permitsCleanup) return@withManagedFilesLock true
        val keep = ImportedMapLibraryStore(filesDir).managedFiles(s).filter { it.exists() }.toSet()
        val clean = ManagedImportedMapFileLifecycle.reconcile(
            managedParent = filesDir,
            directories = ImportedMapLibraryStore.MANAGED_DIRECTORIES.map { File(filesDir, it) },
            keeping = keep,
            inFlight = inFlight(),
        )
        pruneDrafts(s.entries.mapNotNull { it.contentKey }.toSet())
        clean
    }

    /**
     * R3-2 bake-only sweep: tacmap-bake-*.mbtiles + sidecars in offline_tiles no PDF entry
     * names. [state] is read inside the lock (the newest one wins), null = no authoritative
     * read (locked, corrupt, still migrating): skip and say null, never delete on a guess.
     * Doesn't need the PDFs to be there
     */
    fun sweepBakes(
        filesDir: File,
        inFlight: () -> Set<File> = InFlightImportFiles::snapshot,
        state: () -> LibraryState?,
    ): Boolean? = sweepOrphanPdfBakes(filesDir, inFlight) {
        state()?.takeIf { it.permitsCleanup }?.let { PdfBakeRecordRead.Read(it.bakeFileNames) } ?: PdfBakeRecordRead.Unreadable
    }

    /**
     * Remove Offline Tiles, file side (R2-S2, R3-5): the record's already off the entry, so
     * the file + -journal/-wal/-shm go straight away, PDF there or not. Only a
     * tacmap-bake-<x>.mbtiles plain file directly in offline_tiles. True = nothing left
     */
    fun deleteBake(filesDir: File, fileName: String, onCleared: () -> Unit = {}): Boolean =
        removePdfBakeFile(filesDir, { fileName }, onCleared) == true
}
