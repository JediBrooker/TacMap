package com.tacmap.calibration

import java.io.File

/**
 * The file side of the imported-map library: the reconcile keep set, the bake-only sweep
 * and Remove Offline Tiles' delete (WP2 guarantees on the WP4 library, M5-M7). MapViewModel
 * calls these, JVM tests too, so the tests run the same wiring the app does.
 */
internal object LibraryMapFiles {
    /**
     * Delete map files in pdf_maps, mbtiles and offline_tiles no entry owns. Keep = every
     * entry's file + sidecars, every PDF's valid bake + sidecars, anything in flight. Under
     * the managed files lock the bake publish and Remove take. Only ever with a Loaded
     * library, the caller has one
     */
    fun reconcile(filesDir: File, state: LibraryState, inFlight: Set<File> = InFlightImportFiles.snapshot()): Boolean {
        if (!state.permitsCleanup) return true
        val store = ImportedMapLibraryStore(filesDir)
        val keep = store.managedFiles(state).filter { it.exists() }.toSet()
        return ActiveMapSelectionStore.withManagedFilesLock {
            ManagedImportedMapFileLifecycle.reconcile(
                managedParent = filesDir,
                directories = ImportedMapLibraryStore.MANAGED_DIRECTORIES.map { File(filesDir, it) },
                keeping = keep,
                inFlight = inFlight,
            )
        }
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
