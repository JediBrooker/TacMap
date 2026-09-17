package com.tacmap.calibration

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ManagedImportedMapFileLifecycleTest {

    @Test
    fun reconciliationKeepsAuthenticatedMapAndCleansEveryManagedRoot() {
        val parent = Files.createTempDirectory("managed-map-files").toFile()
        val pdfRoot = File(parent, "pdf_maps").apply { mkdirs() }
        val importedRoot = File(parent, "mbtiles").apply { mkdirs() }
        val generatedRoot = File(parent, "offline_tiles").apply { mkdirs() }
        val keep = File(importedRoot, "current.mbtiles").apply { writeText("keep") }
        val staleFiles = listOf(
            File(pdfRoot, "old.pdf").apply { writeText("old-pdf") },
            File(pdfRoot, "interrupted.pdf.partial").apply { writeText("partial-pdf") },
            File(importedRoot, "old.mbtiles").apply { writeText("old-import") },
            File(importedRoot, "interrupted.mbtiles.partial").apply { writeText("partial-import") },
            File(importedRoot, "interrupted.mbtiles.partial-wal").apply { writeText("wal") },
            File(importedRoot, "interrupted.mbtiles.partial-shm").apply { writeText("shm") },
            File(importedRoot, "interrupted.mbtiles.partial-journal").apply { writeText("journal") },
            File(generatedRoot, "old.mbtiles").apply { writeText("old-generated") },
            File(generatedRoot, "old.mbtiles-wal").apply { writeText("wal") },
            File(generatedRoot, "old.mbtiles-shm").apply { writeText("shm") },
            File(generatedRoot, "old.mbtiles-journal").apply { writeText("journal") },
        )
        val unrelated = File(pdfRoot, "notes.txt").apply { writeText("not a map") }
        val unrelatedPartial = File(pdfRoot, "notes.partial").apply { writeText("not a map") }
        val misleadingBackup = File(pdfRoot, "keep.pdf.partial.backup").apply {
            writeText("not a recognized residue")
        }

        assertTrue(
            ManagedImportedMapFileLifecycle.reconcile(
                managedParent = parent,
                directories = listOf(pdfRoot, importedRoot, generatedRoot),
                keeping = setOf(keep),
            )
        )

        assertTrue(keep.isFile)
        staleFiles.forEach { assertFalse(it.exists()) }
        assertTrue(unrelated.isFile)
        assertTrue(unrelatedPartial.isFile)
        assertTrue(misleadingBackup.isFile)
    }

    @Test
    fun symlinkNamedLikeMapIsNeverFollowedOrRemoved() {
        val parent = Files.createTempDirectory("managed-map-symlink").toFile()
        val root = File(parent, "pdf_maps").apply { mkdirs() }
        val stale = File(root, "stale.pdf").apply { writeText("stale") }
        val outside = Files.createTempFile("outside-map", ".pdf").toFile().apply {
            writeText("outside")
        }
        val link = File(root, "escape.pdf")
        Files.createSymbolicLink(link.toPath(), outside.toPath())

        assertFalse(
            ManagedImportedMapFileLifecycle.reconcile(
                managedParent = parent,
                directories = listOf(root),
                keeping = emptySet(),
            )
        )

        assertFalse(stale.exists())
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertTrue(outside.isFile)
        assertTrue(outside.readText() == "outside")
    }

    @Test
    fun retainedSymlinkOrUnmanagedFileFailsClosedBeforeAnyDeletion() {
        val parent = Files.createTempDirectory("managed-map-retained").toFile()
        val root = File(parent, "mbtiles").apply { mkdirs() }
        val stale = File(root, "stale.mbtiles").apply { writeText("stale") }
        val outside = Files.createTempFile("unmanaged-map", ".mbtiles").toFile()

        assertFalse(
            ManagedImportedMapFileLifecycle.reconcile(
                managedParent = parent,
                directories = listOf(root),
                keeping = setOf(outside),
            )
        )
        assertTrue(stale.isFile)
    }
}
