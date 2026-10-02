package com.tacmap.calibration

import com.tacmap.map.render.pdf.PdfBakePlan
import com.tacmap.mgrs.MgrsGridBuildSpec
import com.tacmap.mgrs.MgrsGridRenderer
import com.tacmap.map.render.MapCamera
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** WP2 bake bits that live outside the render maths: files, keys, the sealed DTO */
class PdfBakeLifecycleTest {

    @Test
    fun reconcileKeepsThePdfAndItsBakeAndNeverTouchesTheWorkDir() {
        val parent = Files.createTempDirectory("bake-reconcile").toFile()
        try {
            val pdfRoot = File(parent, "pdf_maps").apply { mkdirs() }
            val generated = File(parent, "offline_tiles").apply { mkdirs() }
            val work = File(parent, "pdf_bake_work").apply { mkdirs() }
            val pdf = File(pdfRoot, "sheet.pdf").apply { writeText("pdf") }
            val bake = File(generated, "tacmap-bake-1.mbtiles").apply { writeText("tiles") }
            val staleBake = File(generated, "tacmap-bake-0.mbtiles").apply { writeText("old georef") }
            val running = File(work, "abc.mbtiles.partial").apply { writeText("half done") }
            assertTrue(
                ManagedImportedMapFileLifecycle.reconcile(
                    managedParent = parent,
                    // what ActiveMapSelectionStore passes: the work dir is not one of the roots
                    directories = listOf(pdfRoot, File(parent, "mbtiles"), generated),
                    keeping = setOf(pdf, bake),
                )
            )
            assertTrue(pdf.isFile)
            assertTrue(bake.isFile)
            assertFalse(staleBake.exists())
            assertTrue("an in progress bake survives a reconcile", running.isFile)
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun bakeKeyChangesWithTheGeorefAndTilePxOnly() {
        val a = PdfBakePlan.bakeKey("{\"page\":0}", 672)
        assertEquals(a, PdfBakePlan.bakeKey("{\"page\":0}", 672))
        assertEquals(64, a.length)
        assertNotEquals(a, PdfBakePlan.bakeKey("{\"page\":0}", 768))
        assertNotEquals(a, PdfBakePlan.bakeKey("{\"page\":1}", 672))
    }

    @Test
    fun sealedSessionCarriesTheRenderBitsAndOldBlobsStillDecode() {
        val json = Json { ignoreUnknownKeys = true }
        val bake = PersistedPdfBake("tacmap-bake-x.mbtiles", "k".repeat(64), 0, 15, 672, 1234L)
        val dto = PersistedPdfSource(
            schemaVersion = 2, fileName = "a.pdf", displayName = "Sheet",
            renderGuardToken = "00000000-0000-4000-8000-000000000001", contentKey = "sha256:ab", bake = bake,
        )
        val back = json.decodeFromString<PersistedPdfSource>(json.encodeToString(dto))
        assertEquals(dto.renderGuardToken, back.renderGuardToken)
        assertEquals(dto.contentKey, back.contentKey)
        assertEquals(bake, back.bake)
        // a pre WP2 blob has none of them
        val old = json.decodeFromString<PersistedPdfSource>("{\"schemaVersion\":2,\"fileName\":\"a.pdf\",\"displayName\":\"S\"}")
        assertNull(old.renderGuardToken)
        assertNull(old.bake)
    }

    @Test
    fun highLatitudeZoomedOutGridDoesntBuildTensOfThousandsOfSquares() {
        // Android built ~67k 100 km squares near 83.5N at z3, iOS skips the ones too small to label
        for ((lat, z) in listOf(83.5 to 3.0, 83.5 to 5.0, 70.0 to 4.0, 0.0 to 5.5)) {
            val camera = MapCamera(lat, 10.0, z, 0.0, 393.0, 852.0)
            val lod = MgrsGridRenderer.lod(camera.zoom, camera.centerLat)
            val g = MgrsGridBuildSpec.forCamera(camera, 3.0, lod).build()
            assertTrue("lat $lat z$z squares ${g.squares.size}", g.squares.size < 5_000)
        }
    }
}
