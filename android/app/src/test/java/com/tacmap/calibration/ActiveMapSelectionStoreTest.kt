package com.tacmap.calibration

import com.tacmap.util.DataKey
import com.tacmap.util.SafeStore
import com.tacmap.util.SealedEnvelope
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
    fun retainedImportedSelectionSurvivesOnlineSwitchAndCanBeCleared() {
        val dir = tempDir()
        val map = File(dir, "mbtiles/retained-operation.mbtiles").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val store = ActiveMapSelectionStore.forTests(dir)

        assertTrue(store.saveRetainedOffline(map.path, BasemapStyle.OSM_TOPO))
        assertTrue(store.saveOnline(BasemapStyle.OSM_STREET))

        val retained = (store.loadRetainedImportedState() as ActiveMapSelectionLoadState.Loaded).selection
        assertEquals(ActiveMapKind.OFFLINE_TILES, retained.kind)
        assertEquals(map.canonicalFile, store.offlineFile(retained))
        // Active + retained now share one encrypted atomic snapshot.
        assertTrue(SealedEnvelope.isSealedFile(File(dir, "active_map_source.json").readBytes()))
        assertTrue(store.clearRetainedImported())
        assertTrue(store.loadRetainedImportedState() === ActiveMapSelectionLoadState.Missing)
    }

    @Test
    fun activeAndRetainedOfflineSelectionRoundTripsFromOneAtomicSnapshot() {
        val dir = tempDir()
        val map = File(dir, "mbtiles/process-restore.mbtiles").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }

        assertTrue(
            ActiveMapSelectionStore.forTests(dir)
                .saveActiveAndRetainedOffline(map.path, BasemapStyle.OSM_TOPO)
        )

        val restoredStore = ActiveMapSelectionStore.forTests(dir)
        val active = (restoredStore.loadState() as ActiveMapSelectionLoadState.Loaded).selection
        val retained = (restoredStore.loadRetainedImportedState() as ActiveMapSelectionLoadState.Loaded)
            .selection
        assertEquals(active, retained)
        assertEquals(map.canonicalFile, restoredStore.offlineFile(active))
    }

    @Test
    fun failedAtomicClearPreservesBothPreviouslyDurableSelectors() {
        val dir = tempDir()
        val map = File(dir, "mbtiles/known-good.mbtiles").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val store = ActiveMapSelectionStore.forTests(dir)
        assertTrue(store.saveActiveAndRetainedOffline(map.path, BasemapStyle.OSM_TOPO))

        SafeStore.keyProvider = SafeStore.KeyProvider { throw DataKey.LockedException() }
        assertFalse(store.saveOnlineAndClearRetained(BasemapStyle.OSM_STREET))

        SafeStore.keyProvider = SafeStore.KeyProvider { ByteArray(32) { (it + 7).toByte() } }
        val active = (store.loadState() as ActiveMapSelectionLoadState.Loaded).selection
        val retained = (store.loadRetainedImportedState() as ActiveMapSelectionLoadState.Loaded)
            .selection
        assertEquals(ActiveMapKind.OFFLINE_TILES, active.kind)
        assertEquals(active, retained)
        assertEquals(map.canonicalFile, store.offlineFile(active))
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
    fun snapshotShapeRequiresTheCurrentExplicitSchemaVersion() {
        val invalidSnapshots = listOf(
            """{"active":{"kind":"ONLINE","preferredOnlineStyle":"OSM_TOPO"}}""",
            """{"schemaVersion":99,"active":{"kind":"ONLINE","preferredOnlineStyle":"OSM_TOPO"}}""",
            """{"schemaVersion":"2","active":{"kind":"ONLINE","preferredOnlineStyle":"OSM_TOPO"}}""",
        )

        invalidSnapshots.forEachIndexed { index, raw ->
            val dir = tempDir()
            SafeStore.writeAtomically(
                File(dir, "active_map_source.json"),
                "active_map_source.json",
                raw,
            )

            val loaded = ActiveMapSelectionStore.forTests(dir).loadState()

            assertEquals(
                "invalid schema case $index",
                ActiveMapSelectionFailure.CORRUPT,
                (loaded as ActiveMapSelectionLoadState.Unavailable).reason,
            )
        }
    }

    @Test
    fun migrationMarkerFailureIsReportedBeforeTheLegacySelectorChanges() {
        val dir = tempDir()
        val selector = File(dir, "active_map_source.json")
        SafeStore.writeAtomically(
            selector,
            "active_map_source.json",
            """{"kind":"ONLINE","preferredOnlineStyle":"OSM_TOPO"}""",
        )
        val knownGoodBytes = selector.readBytes()
        var markerAttempts = 0
        val store = ActiveMapSelectionStore.forTests(dir) {
            markerAttempts += 1
            false
        }

        assertFalse(store.saveOnline(BasemapStyle.OSM_STREET))

        assertEquals(1, markerAttempts)
        assertArrayEquals(knownGoodBytes, selector.readBytes())
        assertTrue(store.legacyPdfMigrationPending())
        assertEquals(BasemapStyle.OSM_TOPO.name, store.loadedSelection().preferredOnlineStyle)
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

    @Test
    fun currentPdfSnapshotCleansSupersededMapsAcrossAllManagedRoots() {
        val dir = tempDir()
        val pdfRoot = File(dir, "pdf_maps").apply { mkdirs() }
        val current = File(pdfRoot, "current.pdf").apply { writeText("current") }
        val stalePdf = File(pdfRoot, "stale.pdf").apply { writeText("stale") }
        val staleImported = File(dir, "mbtiles/stale.mbtiles").apply {
            parentFile!!.mkdirs()
            writeText("stale")
        }
        val staleGenerated = File(dir, "offline_tiles/stale.mbtiles").apply {
            parentFile!!.mkdirs()
            writeText("stale")
        }
        val store = ActiveMapSelectionStore.forTests(dir)
        assertTrue(store.saveActiveAndRetainedPdf(BasemapStyle.OSM_TOPO))
        var clearCalls = 0

        assertTrue(
            store.reconcileManagedImportedMapFiles(
                currentPdfFile = { current },
                clearPdfSession = { clearCalls += 1; true },
            )
        )

        assertTrue(current.isFile)
        assertFalse(stalePdf.exists())
        assertFalse(staleImported.exists())
        assertFalse(staleGenerated.exists())
        assertEquals(0, clearCalls)
    }

    @Test
    fun currentOfflineSnapshotClearsPdfMetadataThenKeepsOnlyRetainedMbtiles() {
        val dir = tempDir()
        val current = File(dir, "offline_tiles/current.mbtiles").apply {
            parentFile!!.mkdirs()
            writeText("current")
        }
        val stalePdf = File(dir, "pdf_maps/stale.pdf").apply {
            parentFile!!.mkdirs()
            writeText("stale")
        }
        val staleImported = File(dir, "mbtiles/stale.mbtiles").apply {
            parentFile!!.mkdirs()
            writeText("stale")
        }
        val store = ActiveMapSelectionStore.forTests(dir)
        assertTrue(store.saveActiveAndRetainedOffline(current.path, BasemapStyle.OSM_TOPO))
        var clearCalls = 0

        assertTrue(
            store.reconcileManagedImportedMapFiles(
                currentPdfFile = { stalePdf },
                clearPdfSession = { clearCalls += 1; true },
            )
        )

        assertTrue(current.isFile)
        assertFalse(stalePdf.exists())
        assertFalse(staleImported.exists())
        assertEquals(1, clearCalls)
    }

    @Test
    fun failedPdfMetadataClearPreservesEveryBackingFileForRetry() {
        val dir = tempDir()
        val current = File(dir, "mbtiles/current.mbtiles").apply {
            parentFile!!.mkdirs()
            writeText("current")
        }
        val stalePdf = File(dir, "pdf_maps/recoverable.pdf").apply {
            parentFile!!.mkdirs()
            writeText("recoverable")
        }
        val store = ActiveMapSelectionStore.forTests(dir)
        assertTrue(store.saveActiveAndRetainedOffline(current.path, BasemapStyle.OSM_TOPO))

        assertFalse(
            store.reconcileManagedImportedMapFiles(
                currentPdfFile = { stalePdf },
                clearPdfSession = { false },
            )
        )

        assertTrue(current.isFile)
        assertTrue(stalePdf.isFile)
    }

    @Test
    fun missingAndLegacySnapshotsNeverAuthorizeColdStartDeletion() {
        listOf(false, true).forEach { legacy ->
            val dir = tempDir()
            val orphan = File(dir, "pdf_maps/recoverable.pdf").apply {
                parentFile!!.mkdirs()
                writeText("recoverable")
            }
            if (legacy) {
                SafeStore.writeAtomically(
                    File(dir, "active_map_source.json"),
                    "active_map_source.json",
                    """{"kind":"ONLINE","preferredOnlineStyle":"OSM_TOPO"}""",
                )
            }
            val store = ActiveMapSelectionStore.forTests(dir)

            assertFalse(store.hasAuthenticatedCurrentSnapshot())
            assertFalse(
                store.reconcileManagedImportedMapFiles(
                    currentPdfFile = { orphan },
                    clearPdfSession = { true },
                )
            )
            assertTrue(orphan.isFile)
        }
    }

    @Test
    fun lockedSnapshotNeverAuthorizesColdStartDeletion() {
        val dir = tempDir()
        val orphan = File(dir, "pdf_maps/recoverable.pdf").apply {
            parentFile!!.mkdirs()
            writeText("recoverable")
        }
        val store = ActiveMapSelectionStore.forTests(dir)
        assertTrue(store.saveOnline(BasemapStyle.OSM_TOPO))
        SafeStore.keyProvider = SafeStore.KeyProvider { throw DataKey.LockedException() }

        assertFalse(store.hasAuthenticatedCurrentSnapshot())
        assertFalse(
            store.reconcileManagedImportedMapFiles(
                currentPdfFile = { orphan },
                clearPdfSession = { true },
            )
        )
        assertTrue(orphan.isFile)
    }

    @Test
    fun corruptSnapshotPreservesEveryManagedRootAcrossRelaunchUntilExplicitReplacement() {
        val dir = tempDir()
        val current = File(dir, "pdf_maps/current.pdf").apply {
            parentFile!!.mkdirs()
            writeText("current")
        }
        val stalePdf = File(dir, "pdf_maps/stale.pdf").apply { writeText("stale") }
        val staleImported = File(dir, "mbtiles/stale.mbtiles").apply {
            parentFile!!.mkdirs()
            writeText("stale")
        }
        val staleGenerated = File(dir, "offline_tiles/stale.mbtiles.partial").apply {
            parentFile!!.mkdirs()
            writeText("crash residue")
        }
        val initial = ActiveMapSelectionStore.forTests(dir)
        assertTrue(initial.saveOnline(BasemapStyle.OSM_TOPO))
        File(dir, "active_map_source.json").writeText("tampered selector")

        val firstRead = initial.loadState()
        assertEquals(
            ActiveMapSelectionFailure.CORRUPT,
            (firstRead as ActiveMapSelectionLoadState.Unavailable).reason,
        )
        val relaunched = ActiveMapSelectionStore.forTests(dir)
        val coldRead = relaunched.loadState()
        assertEquals(
            ActiveMapSelectionFailure.CORRUPT,
            (coldRead as ActiveMapSelectionLoadState.Unavailable).reason,
        )
        assertFalse(relaunched.hasAuthenticatedCurrentSnapshot())
        assertFalse(
            relaunched.reconcileManagedImportedMapFiles(
                currentPdfFile = { current },
                clearPdfSession = { true },
            )
        )
        listOf(current, stalePdf, staleImported, staleGenerated).forEach {
            assertTrue(it.isFile)
        }

        // A deliberate replacement is new durable authority and may finally
        // retire the quarantined selector's now-unreferenced map bytes.
        assertTrue(relaunched.saveActiveAndRetainedPdf(BasemapStyle.OSM_TOPO))
        assertTrue(
            relaunched.reconcileManagedImportedMapFiles(
                currentPdfFile = { current },
                clearPdfSession = { true },
            )
        )
        assertTrue(current.isFile)
        assertFalse(stalePdf.exists())
        assertFalse(staleImported.exists())
        assertFalse(staleGenerated.exists())
    }

    private fun ActiveMapSelectionStore.loadedSelection(): ActiveMapSelection =
        (loadState() as ActiveMapSelectionLoadState.Loaded).selection

    private fun tempDir(): File = Files.createTempDirectory("active-map-selection").toFile()
}
