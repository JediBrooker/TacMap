package com.tacmap.map

import com.tacmap.calibration.ActiveMapSelectionPersistence
import com.tacmap.calibration.BasemapStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveMapSelectionCommitCoordinatorTest {

    @Test
    fun onlineSwitchPublishesOnlyAfterDurableWriteAndRetryPreservesRetainedMap() {
        val persistence = FakePersistence(
            active = "PDF:${BasemapStyle.OSM_TOPO.name}",
            retained = "PDF:${BasemapStyle.OSM_TOPO.name}",
        ).apply { failNextWrite = true }
        val publications = mutableListOf<DurableMapPublication<String>>()
        val coordinator = ActiveMapSelectionCommitCoordinator(persistence, publications::add)

        val failed = coordinator.selectOnline("online-street", BasemapStyle.OSM_STREET)

        assertEquals(
            MapSelectionCommitResult.Failed(MapSelectionCommitFailure.SELECTOR),
            failed,
        )
        assertTrue(publications.isEmpty())
        assertEquals("PDF:${BasemapStyle.OSM_TOPO.name}", persistence.active)
        assertEquals("PDF:${BasemapStyle.OSM_TOPO.name}", persistence.retained)

        assertEquals(
            MapSelectionCommitResult.Succeeded,
            coordinator.selectOnline("online-street", BasemapStyle.OSM_STREET),
        )
        assertEquals("ONLINE:${BasemapStyle.OSM_STREET.name}", persistence.active)
        assertEquals("PDF:${BasemapStyle.OSM_TOPO.name}", persistence.retained)
        assertEquals("online-street", publications.single().active)
        assertTrue(publications.single().retained === RetainedMapPublication.Keep)
    }

    @Test
    fun pdfSessionFailureLeavesKnownGoodSelectorsAndDoesNotPublish() {
        val persistence = FakePersistence(active = "ONLINE:old", retained = "OFFLINE:old.mbtiles")
        val publications = mutableListOf<DurableMapPublication<String>>()
        val coordinator = ActiveMapSelectionCommitCoordinator(persistence, publications::add)
        var rollbackCalled = false

        val result = coordinator.activatePdf(
            source = "new-pdf",
            preferredOnlineStyle = BasemapStyle.OSM_TOPO,
            persistPdfSession = { false },
            rollbackPdfSession = { rollbackCalled = true; true },
        )

        assertEquals(
            MapSelectionCommitResult.Failed(MapSelectionCommitFailure.PDF_SESSION),
            result,
        )
        assertTrue(rollbackCalled)
        assertEquals("ONLINE:old", persistence.active)
        assertEquals("OFFLINE:old.mbtiles", persistence.retained)
        assertTrue(publications.isEmpty())
    }

    @Test
    fun pdfSelectorFailureRollsSessionBackBeforeRetryAndPublishesBothTogether() {
        val persistence = FakePersistence(active = "ONLINE:old", retained = "OFFLINE:old.mbtiles")
            .apply { failNextWrite = true }
        val publications = mutableListOf<DurableMapPublication<String>>()
        val coordinator = ActiveMapSelectionCommitCoordinator(persistence, publications::add)
        var session = "old-session"

        val failed = coordinator.activatePdf(
            source = "new-pdf",
            preferredOnlineStyle = BasemapStyle.OSM_TOPO,
            persistPdfSession = { session = "new-session"; true },
            rollbackPdfSession = { session = "old-session"; true },
        )

        assertEquals(
            MapSelectionCommitResult.Failed(MapSelectionCommitFailure.SELECTOR),
            failed,
        )
        assertEquals("old-session", session)
        assertEquals("ONLINE:old", persistence.active)
        assertEquals("OFFLINE:old.mbtiles", persistence.retained)
        assertTrue(publications.isEmpty())

        val retried = coordinator.activatePdf(
            source = "new-pdf",
            preferredOnlineStyle = BasemapStyle.OSM_TOPO,
            persistPdfSession = { session = "new-session"; true },
            rollbackPdfSession = { session = "old-session"; true },
        )

        assertEquals(MapSelectionCommitResult.Succeeded, retried)
        assertEquals("new-session", session)
        assertEquals("PDF:${BasemapStyle.OSM_TOPO.name}", persistence.active)
        assertEquals(persistence.active, persistence.retained)
        assertEquals("new-pdf", publications.single().active)
        assertEquals(
            "new-pdf",
            (publications.single().retained as RetainedMapPublication.Set).source,
        )
    }

    @Test
    fun mbtilesFailureAndRetryHaveProcessRestorableActiveAndRetainedDescriptors() {
        val persistence = FakePersistence(active = "ONLINE:old", retained = null)
            .apply { failNextWrite = true }
        val publications = mutableListOf<DurableMapPublication<String>>()
        val coordinator = ActiveMapSelectionCommitCoordinator(persistence, publications::add)

        assertEquals(
            MapSelectionCommitResult.Failed(MapSelectionCommitFailure.SELECTOR),
            coordinator.activateOffline(
                source = "map-object",
                path = "mbtiles/operation.mbtiles",
                preferredOnlineStyle = BasemapStyle.OSM_TOPO,
            ),
        )
        assertEquals("ONLINE:old", persistence.active)
        assertEquals(null, persistence.retained)
        assertTrue(publications.isEmpty())

        assertEquals(
            MapSelectionCommitResult.Succeeded,
            coordinator.activateOffline(
                source = "map-object",
                path = "mbtiles/operation.mbtiles",
                preferredOnlineStyle = BasemapStyle.OSM_TOPO,
            ),
        )
        // A new process has only these persisted values; both resolve to the
        // same private MBTiles file and neither depends on the old UI object.
        assertEquals("OFFLINE:mbtiles/operation.mbtiles", persistence.active)
        assertEquals(persistence.active, persistence.retained)
        assertEquals(1, publications.size)
    }

    @Test
    fun activeAndRetainedClearIsOneWriteAndFailureCannotPartiallyUnload() {
        val persistence = FakePersistence(
            active = "OFFLINE:mbtiles/operation.mbtiles",
            retained = "OFFLINE:mbtiles/operation.mbtiles",
        ).apply { failNextWrite = true }
        val publications = mutableListOf<DurableMapPublication<String>>()
        val coordinator = ActiveMapSelectionCommitCoordinator(persistence, publications::add)

        assertEquals(
            MapSelectionCommitResult.Failed(MapSelectionCommitFailure.SELECTOR),
            coordinator.unloadImportedMap(
                onlineSource = "online",
                style = BasemapStyle.OSM_TOPO,
                clearRetained = true,
            ),
        )
        assertEquals("OFFLINE:mbtiles/operation.mbtiles", persistence.active)
        assertEquals(persistence.active, persistence.retained)
        assertTrue(publications.isEmpty())

        assertEquals(
            MapSelectionCommitResult.Succeeded,
            coordinator.unloadImportedMap(
                onlineSource = "online",
                style = BasemapStyle.OSM_TOPO,
                clearRetained = true,
            ),
        )
        assertEquals("ONLINE:${BasemapStyle.OSM_TOPO.name}", persistence.active)
        assertEquals(null, persistence.retained)
        assertTrue(publications.single().retained === RetainedMapPublication.Clear)
    }

    private class FakePersistence(
        var active: String?,
        var retained: String?,
    ) : ActiveMapSelectionPersistence {
        var failNextWrite = false

        override fun saveOnline(style: BasemapStyle): Boolean = write {
            active = "ONLINE:${style.name}"
        }

        override fun saveActiveAndRetainedPdf(preferredOnlineStyle: BasemapStyle): Boolean = write {
            val value = "PDF:${preferredOnlineStyle.name}"
            active = value
            retained = value
        }

        override fun saveActiveAndRetainedOffline(
            path: String,
            preferredOnlineStyle: BasemapStyle,
        ): Boolean = write {
            val value = "OFFLINE:$path"
            active = value
            retained = value
        }

        override fun saveOnlineAndClearRetained(style: BasemapStyle): Boolean = write {
            active = "ONLINE:${style.name}"
            retained = null
        }

        private fun write(mutation: () -> Unit): Boolean {
            if (failNextWrite) {
                failNextWrite = false
                return false
            }
            mutation()
            return true
        }
    }
}
