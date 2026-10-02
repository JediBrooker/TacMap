package com.tacmap.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * R2-S2: Remove Offline Tiles deletes the bake + its sidecars itself, under the managed files
 * lock, once the record is cleared. Doesn't need the reconcile, so a missing PDF can't leave
 * the plaintext tiles behind
 */
class PdfBakeRemovalTest {
    private fun filesDir(): File = Files.createTempDirectory("r2s2").toFile().apply { deleteOnExit() }

    private fun bake(dir: File, name: String = "tacmap-bake-1.mbtiles"): List<File> {
        val tiles = File(dir, "offline_tiles").apply { mkdirs() }
        return listOf("", "-journal", "-wal", "-shm").map { File(tiles, name + it).apply { writeText("x") } }
    }

    @Test
    fun removeDeletesTheBakeAndSidecarsWhileThePdfIsMissing() {
        val dir = filesDir()
        val pdfs = File(dir, "pdf_maps").apply { mkdirs() }
        val missingPdf = File(pdfs, "import-gone.pdf")
        assertFalse(missingPdf.exists())
        val files = bake(dir)
        val other = File(dir, "offline_tiles/imported-pack.mbtiles").apply { writeText("pack") }
        var cleared = false
        var readerLetGo = false
        val r = removePdfBakeFile(dir, {
            cleared = true
            "tacmap-bake-1.mbtiles"
        }) {
            // the reader goes before the file
            assertTrue(files[0].exists())
            readerLetGo = true
        }
        assertEquals(true, r)
        assertTrue(cleared && readerLetGo)
        files.forEach { assertFalse("${it.name} still there", it.exists()) }
        assertTrue("only the named file goes", other.isFile)

        // and the reconcile on its own would have refused: the keep set has a missing PDF
        val again = bake(dir, "tacmap-bake-2.mbtiles")
        assertFalse(ManagedImportedMapFileLifecycle.reconcile(dir, listOf(pdfs, File(dir, "offline_tiles")), setOf(missingPdf)))
        assertTrue(again[0].isFile)
    }

    @Test
    fun nothingIsDeletedWhenTheRecordWasntCleared() {
        val dir = filesDir()
        val files = bake(dir)
        var called = false
        assertNull(removePdfBakeFile(dir, { null }) { called = true })
        assertFalse(called)
        files.forEach { assertTrue(it.isFile) }
    }

    @Test
    fun noRecordedBakeIsFineAndDeletesNothing() {
        val dir = filesDir()
        val files = bake(dir)
        assertEquals(true, removePdfBakeFile(dir, { "" }))
        files.forEach { assertTrue(it.isFile) }
    }

    @Test
    fun aNameThatIsntAPlainBakeFileNeverDeletesAnything() {
        val dir = filesDir()
        val outside = File(dir, "secret.mbtiles").apply { writeText("keep") }
        val pdf = File(dir, "offline_tiles/x.pdf").apply { parentFile!!.mkdirs(); writeText("keep") }
        for (bad in listOf("../secret.mbtiles", "sub/x.mbtiles", "x.pdf", ".mbtiles")) {
            assertFalse(bad, ManagedImportedMapFileLifecycle.deleteMBTiles(File(dir, "offline_tiles"), bad))
        }
        assertTrue(outside.isFile)
        assertTrue(pdf.isFile)
    }

    @Test
    fun aSymlinkNamedLikeTheBakeIsLeftAlone() {
        val dir = filesDir()
        val target = File(dir, "elsewhere.bin").apply { writeText("keep") }
        val tiles = File(dir, "offline_tiles").apply { mkdirs() }
        val link = File(tiles, "tacmap-bake-1.mbtiles")
        Files.createSymbolicLink(link.toPath(), target.toPath())
        assertFalse(ManagedImportedMapFileLifecycle.deleteMBTiles(tiles, link.name))
        assertTrue(target.isFile)
        assertTrue(Files.isSymbolicLink(link.toPath()))
    }

    @Test
    fun removeWontDeleteARecordedNameWithoutTheBakePrefix() {
        // R3-5 belt and braces: a record naming someone else's pack clears, the file stays
        val dir = filesDir()
        val pack = File(dir, "offline_tiles/imported-pack.mbtiles").apply { parentFile!!.mkdirs(); writeText("pack") }
        var letGo = false
        assertEquals(false, removePdfBakeFile(dir, { "imported-pack.mbtiles" }) { letGo = true })
        assertTrue(letGo)
        assertTrue(pack.isFile)
    }

    // ---- R3-2 bake-only sweep ----

    private class Planted(val keep: List<File>, val gone: List<File>)

    private fun plant(dir: File): Planted {
        val tiles = File(dir, "offline_tiles").apply { mkdirs() }
        val named = bake(dir, "tacmap-bake-named.mbtiles")
        val orphan = bake(dir, "tacmap-bake-orphan.mbtiles")
        // sidecars whose main file is already gone, the half a failed delete can leave
        val loose = listOf("-wal", "-shm").map { File(tiles, "tacmap-bake-loose.mbtiles$it").apply { writeText("x") } }
        val others = listOf(
            File(tiles, "imported-pack.mbtiles"),
            File(tiles, "imported-pack.mbtiles-wal"),
            File(tiles, "x.pdf"),
            File(tiles, "tacmap-bake-notes.txt"),
            File(tiles, "tacmap-bake-p.mbtiles.partial"),
            File(dir, "tacmap-bake-up.mbtiles"),
            File(dir, "mbtiles/tacmap-bake-m.mbtiles"),
            File(tiles, "sub/tacmap-bake-nested.mbtiles"),
        ).onEach { it.parentFile!!.mkdirs(); it.writeText("keep") }
        return Planted(keep = named + others, gone = orphan + loose)
    }

    @Test
    fun theSweepTakesOnlyUnnamedBakesInOfflineTiles() {
        val dir = filesDir()
        val p = plant(dir)
        assertEquals(true, sweepOrphanPdfBakes(dir) { PdfBakeRecordRead.Read("tacmap-bake-named.mbtiles") })
        p.gone.forEach { assertFalse("${it.name} still there", it.exists()) }
        p.keep.forEach { assertTrue("${it.path} went", it.isFile) }
    }

    @Test
    fun noRecordMeansEveryBakeIsAnOrphan() {
        val dir = filesDir()
        val p = plant(dir)
        assertEquals(true, sweepOrphanPdfBakes(dir) { PdfBakeRecordRead.Read(null) })
        (p.gone + p.keep.filter { it.name.startsWith("tacmap-bake-named.mbtiles") }).forEach {
            assertFalse("${it.name} still there", it.exists())
        }
        p.keep.filterNot { it.name.startsWith("tacmap-bake-named.mbtiles") }.forEach { assertTrue("${it.path} went", it.isFile) }
    }

    @Test
    fun anUnreadableRecordSweepsNothing() {
        val dir = filesDir()
        val p = plant(dir)
        assertNull(sweepOrphanPdfBakes(dir) { PdfBakeRecordRead.Unreadable })
        (p.keep + p.gone).forEach { assertTrue("${it.path} went on a guess", it.isFile) }
    }

    @Test
    fun theSweepWorksWithThePdfMissingAndNoOfflineTilesIsFine() {
        val dir = filesDir()
        assertEquals(true, sweepOrphanPdfBakes(dir) { PdfBakeRecordRead.Read(null) })
        // pdf_maps empty (the PDF's gone), the sweep doesn't care
        File(dir, "pdf_maps").mkdirs()
        val orphan = bake(dir, "tacmap-bake-o.mbtiles")
        assertEquals(true, sweepOrphanPdfBakes(dir) { PdfBakeRecordRead.Read("tacmap-bake-gone.mbtiles") })
        orphan.forEach { assertFalse(it.exists()) }
    }

    @Test
    fun theSweepLeavesSymlinksAndDirectoriesAndSaysSo() {
        val dir = filesDir()
        val tiles = File(dir, "offline_tiles").apply { mkdirs() }
        val target = File(dir, "elsewhere.bin").apply { writeText("keep") }
        val link = File(tiles, "tacmap-bake-link.mbtiles")
        Files.createSymbolicLink(link.toPath(), target.toPath())
        val d = File(tiles, "tacmap-bake-dir.mbtiles").apply { mkdirs() }
        File(d, "inside").writeText("keep")
        assertEquals(false, sweepOrphanPdfBakes(dir) { PdfBakeRecordRead.Read(null) })
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertTrue(target.isFile)
        assertTrue(File(d, "inside").isFile)

        // and a symlinked offline_tiles is never walked
        val dir2 = filesDir()
        val real = File(dir2, "real").apply { mkdirs() }
        val victim = File(real, "tacmap-bake-v.mbtiles").apply { writeText("keep") }
        Files.createSymbolicLink(File(dir2, "offline_tiles").toPath(), real.toPath())
        assertEquals(false, sweepOrphanPdfBakes(dir2) { PdfBakeRecordRead.Read(null) })
        assertTrue(victim.isFile)
    }

    // ---- F6: same name + record rules as iOS ----

    @Test
    fun aBakeNameNeedsSomethingBetweenThePrefixAndTheSuffix() {
        assertTrue(ManagedImportedMapFileLifecycle.isGeneratedBakeName("tacmap-bake-1.mbtiles"))
        assertTrue(ManagedImportedMapFileLifecycle.isGeneratedBakeName("tacmap-bake-x.MBTILES"))
        for (bad in listOf("tacmap-bake-.mbtiles", "tacmap-bake.mbtiles", "x-tacmap-bake-1.mbtiles", "tacmap-bake-1.mbtiles-wal", "tacmap-bake-1.pdf", "")) {
            assertFalse(bad, ManagedImportedMapFileLifecycle.isGeneratedBakeName(bad))
        }
        val dir = filesDir()
        val empty = bake(dir, "tacmap-bake-.mbtiles")
        // Remove won't take it, neither will the sweep
        assertEquals(false, removePdfBakeFile(dir, { "tacmap-bake-.mbtiles" }))
        assertEquals(true, sweepOrphanPdfBakes(dir) { PdfBakeRecordRead.Read(null) })
        empty.forEach { assertTrue("${it.name} went", it.isFile) }
    }

    @Test
    fun onlyABakeRecordWeWouldHaveWrittenIsValid() {
        val key = "0123456789abcdef".repeat(4)
        val ok = PersistedPdfBake("tacmap-bake-1.mbtiles", key, 0, 15, 672, 1234L)
        assertTrue(isValidBakeRecord(ok))
        assertTrue(isValidBakeRecord(ok.copy(bakeKey = key.uppercase(), tilePx = 16, maxZoom = 22, bytes = 0)))
        for (bad in listOf(
            ok.copy(fileName = ""),
            ok.copy(fileName = "../tacmap-bake-1.mbtiles"),
            ok.copy(fileName = "sub/tacmap-bake-1.mbtiles"),
            ok.copy(fileName = "a\\b.mbtiles"),
            ok.copy(fileName = "tacmap-bake-1.pdf"),
            // D9: a plain .mbtiles that isn't one of ours, and the empty middle
            ok.copy(fileName = "someones-pack.mbtiles"),
            ok.copy(fileName = "tacmap-bake-.mbtiles"),
            ok.copy(bakeKey = "k"),
            ok.copy(bakeKey = "g".repeat(64)),
            ok.copy(bakeKey = key + "0"),
            ok.copy(tilePx = 15),
            ok.copy(tilePx = 1025),
            ok.copy(minZoom = -1),
            ok.copy(minZoom = 16, maxZoom = 15),
            ok.copy(maxZoom = 23),
            ok.copy(bytes = -1),
        )) assertFalse(bad.toString(), isValidBakeRecord(bad))
    }
}
