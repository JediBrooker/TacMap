package com.tacmap.calibration

import com.tacmap.util.DataKey
import com.tacmap.util.SafeStore
import com.tacmap.util.SealedEnvelope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ActiveMapSelectionStoreTest {

    private val sealedLabels = mutableSetOf<String>()

    @Before
    fun installTestKey() {
        SafeStore.keyProvider = SafeStore.KeyProvider { ByteArray(32) { (it + 7).toByte() } }
        SafeStore.migrationPolicy = object : SafeStore.MigrationPolicy {
            override fun isSealedOnly(label: String) = label in sealedLabels
            override fun markSealedOnly(label: String) {
                sealedLabels += label
            }
        }
    }

    @After
    fun restoreKeyProvider() {
        SafeStore.keyProvider = SafeStore.KeyProvider { DataKey.key() }
        SafeStore.migrationPolicy = object : SafeStore.MigrationPolicy {
            override fun isSealedOnly(label: String) = DataKey.isStoreSealedOnly(label)
            override fun markSealedOnly(label: String) = DataKey.markStoreSealedOnly(label)
        }
    }

    @Test
    fun onlineSelectionRoundTrips() {
        val dir = tempDir()
        val store = ActiveMapSelectionStore.forTests(dir)

        assertTrue(store.saveOnline(BasemapStyle.OSM_STREET))

        val loaded = store.loadedSelection()
        assertEquals(ActiveMapKind.ONLINE, loaded.kind)
        assertEquals(BasemapStyle.OSM_STREET.name, loaded.preferredOnlineStyle)
    }

    @Test
    fun offlineSelectionIsEncryptedAndResolvesInsideSandbox() {
        val dir = tempDir()
        val map = File(dir, "mbtiles/operation-kestrel.mbtiles").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val store = ActiveMapSelectionStore.forTests(dir)

        assertTrue(store.saveOffline(map.path, BasemapStyle.OSM_TOPO))

        val persisted = File(dir, "active_map_source.json").readBytes()
        assertTrue(SealedEnvelope.isSealedFile(persisted))
        assertFalse(
            "AO-identifying imported filename must not be plaintext",
            persisted.toString(Charsets.ISO_8859_1).contains("operation-kestrel")
        )
        val loaded = store.loadedSelection()
        assertEquals(ActiveMapKind.OFFLINE_TILES, loaded.kind)
        assertEquals(map.canonicalFile, store.offlineFile(loaded))
    }

    @Test
    fun staleOrEscapingOfflinePathIsRejected() {
        val dir = tempDir()
        val store = ActiveMapSelectionStore.forTests(dir)
        val outside = File(dir.parentFile, "outside.mbtiles").apply { writeBytes(byteArrayOf(1)) }
        val escaping = ActiveMapSelection(
            kind = ActiveMapKind.OFFLINE_TILES,
            preferredOnlineStyle = BasemapStyle.OSM_TOPO.name,
            offlineRelativePath = "../${outside.name}"
        )
        val missing = escaping.copy(offlineRelativePath = "mbtiles/missing.mbtiles")

        assertNull(store.offlineFile(escaping))
        assertNull(store.offlineFile(missing))
        assertTrue(outside.exists())
    }

    @Test
    fun loadStateDistinguishesMissingCorruptAndLocked() {
        val missingDir = tempDir()
        assertTrue(
            ActiveMapSelectionStore.forTests(missingDir).loadState() ===
                ActiveMapSelectionLoadState.Missing
        )

        val corruptDir = tempDir()
        File(corruptDir, "active_map_source.json").writeText("{not valid json")
        val corrupt = ActiveMapSelectionStore.forTests(corruptDir).loadState()
        assertEquals(
            ActiveMapSelectionFailure.CORRUPT,
            (corrupt as ActiveMapSelectionLoadState.Unavailable).reason
        )

        val lockedDir = tempDir()
        val lockedStore = ActiveMapSelectionStore.forTests(lockedDir)
        assertTrue(lockedStore.saveOnline(BasemapStyle.OSM_TOPO))
        SafeStore.keyProvider = SafeStore.KeyProvider { throw DataKey.LockedException() }
        val locked = lockedStore.loadState()
        assertEquals(
            ActiveMapSelectionFailure.LOCKED,
            (locked as ActiveMapSelectionLoadState.Unavailable).reason
        )
    }

    @Test
    fun genuinelyMissingDescriptorCanRunLegacyPdfMigration() {
        val dir = tempDir()
        val store = ActiveMapSelectionStore.forTests(dir)

        assertTrue(store.loadState() === ActiveMapSelectionLoadState.Missing)
        assertTrue(store.legacyPdfMigrationPending())
    }

    @Test
    fun retainedLegacyOfflineFilesDoNotBlockFirstPdfMigration() {
        listOf("mbtiles", "offline_tiles").forEach { directory ->
            val dir = tempDir()
            File(dir, "$directory/legacy-map.mbtiles").apply {
                parentFile!!.mkdirs()
                writeBytes(byteArrayOf(1))
            }

            assertTrue(ActiveMapSelectionStore.forTests(dir).legacyPdfMigrationPending())
        }
    }

    @Test
    fun completedMigrationCannotResurrectPdfIfDescriptorLaterDisappears() {
        val dir = tempDir()
        val store = ActiveMapSelectionStore.forTests(dir)
        assertTrue(store.saveOnline(BasemapStyle.OSM_TOPO))
        assertTrue(File(dir, "active_map_source.json").delete())

        assertTrue(store.loadState() === ActiveMapSelectionLoadState.Missing)
        assertFalse(store.legacyPdfMigrationPending())
    }

    private fun ActiveMapSelectionStore.loadedSelection(): ActiveMapSelection =
        (loadState() as ActiveMapSelectionLoadState.Loaded).selection

    private fun tempDir(): File = Files.createTempDirectory("active-map-selection").toFile()
}
