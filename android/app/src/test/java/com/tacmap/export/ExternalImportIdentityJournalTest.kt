package com.tacmap.export

import com.tacmap.drawings.DrawingFeature
import com.tacmap.drawings.DrawingGeometry
import com.tacmap.drawings.DrawingPoint
import com.tacmap.util.DataKey
import com.tacmap.util.SafeStore
import com.tacmap.waypoints.Waypoint
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.ArrayDeque

class ExternalImportIdentityJournalTest {
    private val sealedLabels = mutableSetOf<String>()

    @Before
    fun installTestPersistence() {
        SafeStore.keyProvider = SafeStore.KeyProvider { ByteArray(32) { (it + 1).toByte() } }
        SafeStore.migrationPolicy = object : SafeStore.MigrationPolicy {
            override fun isSealedOnly(label: String) = label in sealedLabels
            override fun markSealedOnly(label: String) { sealedLabels += label }
        }
    }

    @After
    fun restorePersistence() {
        SafeStore.keyProvider = SafeStore.KeyProvider { DataKey.key() }
        SafeStore.migrationPolicy = object : SafeStore.MigrationPolicy {
            override fun isSealedOnly(label: String) = DataKey.isStoreSealedOnly(label)
            override fun markSealedOnly(label: String) = DataKey.markStoreSealedOnly(label)
        }
    }

    @Test
    fun persistedResolutionSurvivesRecreationAndRetryConsumesNoIds() {
        val directory = Files.createTempDirectory("identity-journal").toFile()
        val parsed = parsedPayload()
        val existing = listOf(
            OccupiedExternalImportIdentity(
                ExternalImportObjectKind.WAYPOINT,
                "11111111-1111-4111-8111-111111111111",
            )
        )
        val ids = ArrayDeque(
            listOf(
                "90000000-0000-4000-8000-000000000001",
                "90000000-0000-4000-8000-000000000002",
            )
        )
        val first = ExternalImportIdentityJournal.forTests(directory).resolveAndPersist(
            batchKey = "geojson:fixture",
            parsed = parsed,
            occupied = existing,
            resolver = ExternalImportIdentityResolver { ids.removeFirst() },
        )
        assertTrue(ids.isEmpty())

        val retryOccupied = existing +
            first.result.waypoints.map {
                OccupiedExternalImportIdentity(ExternalImportObjectKind.WAYPOINT, it.id)
            } + first.result.drawings.map {
                OccupiedExternalImportIdentity(ExternalImportObjectKind.DRAWING, it.id)
            }
        val retry = ExternalImportIdentityJournal.forTests(directory).resolveAndPersist(
            batchKey = "geojson:fixture",
            parsed = parsed,
            occupied = retryOccupied,
            resolver = ExternalImportIdentityResolver { error("retry reminted an ID") },
        )

        assertEquals(first.identities.resolutionMap, retry.identities.resolutionMap)
        assertTrue(retry.identities.consumedRemintIds.isEmpty())
        val journalFile = File(directory, "external_import_identity.json")
        assertFalse(journalFile.readBytes().toString(Charsets.ISO_8859_1).contains("90000000"))
    }

    @Test
    fun kmlLayerUuidAndObjectLayerReferenceSurvivePartialRetry() {
        val directory = Files.createTempDirectory("kml-layer-journal").toFile()
        val parsed = KmlImporter.parse(
            """<kml><Document><name>Recon</name><Placemark><name>OP</name>""" +
                """<Point><coordinates>151,-33</coordinates></Point></Placemark></Document></kml>""",
            existingLayers = emptyList(),
            fallbackLayerId = "fallback",
        )
        val ids = ArrayDeque(
            listOf(
                "90000000-0000-4000-8000-000000000011",
                "90000000-0000-4000-8000-000000000012",
            )
        )
        val first = ExternalImportIdentityJournal.forTests(directory).resolveAndPersist(
            batchKey = "kml:layer-fixture",
            parsed = parsed,
            occupied = emptyList(),
            resolver = ExternalImportIdentityResolver { ids.removeFirst() },
        )
        val firstLayerId = first.result.newLayers.single().id
        assertEquals(firstLayerId, first.result.waypoints.single().layerId)

        val retry = ExternalImportIdentityJournal.forTests(directory).resolveAndPersist(
            batchKey = "kml:layer-fixture",
            parsed = parsed,
            occupied = listOf(
                OccupiedExternalImportIdentity(
                    ExternalImportObjectKind.WAYPOINT,
                    first.result.waypoints.single().id,
                )
            ),
            resolver = ExternalImportIdentityResolver { error("retry reminted an ID or layer") },
        )

        assertEquals(firstLayerId, retry.result.newLayers.single().id)
        assertEquals(firstLayerId, retry.result.waypoints.single().layerId)
        assertEquals(first.layerResolutionMap, retry.layerResolutionMap)
    }

    @Test
    fun suspendedImportRaceIsRemintedAgainstLiveGlobalStateAndJournalUpdated() {
        val directory = Files.createTempDirectory("identity-live-race").toFile()
        val parsed = GeoJsonImporter.Result(
            waypoints = listOf(Waypoint(id = "", name = "Imported", latitude = 1.0, longitude = 2.0)),
            drawings = emptyList(),
            newLayers = emptyList(),
            identityOrder = listOf(
                ParsedExternalImportIdentity(
                    "feature-0",
                    ExternalImportObjectKind.WAYPOINT,
                    0,
                    null,
                )
            ),
        )
        val firstId = "90000000-0000-4000-8000-000000000021"
        val remintedId = "90000000-0000-4000-8000-000000000022"
        val journal = ExternalImportIdentityJournal.forTests(directory)
        val preliminary = journal.resolveAndPersist(
            "geojson:suspended-race",
            parsed,
            emptyList(),
            ExternalImportIdentityResolver { firstId },
        )

        // A different object lands while URI parsing/journaling is suspended.
        val racedWaypoint = Waypoint(
            id = firstId,
            name = "Concurrent local object",
            latitude = 9.0,
            longitude = 9.0,
        )
        val reconciled = journal.reconcileAndPersist(
            batchKey = "geojson:suspended-race",
            resolved = preliminary,
            liveWaypoints = listOf(racedWaypoint),
            liveDrawings = emptyList(),
            resolver = ExternalImportIdentityResolver { remintedId },
        )

        assertEquals(remintedId, reconciled.result.waypoints.single().id)
        assertEquals(listOf(remintedId), reconciled.identities.consumedRemintIds)
        val retry = ExternalImportIdentityJournal.forTests(directory).resolveAndPersist(
            "geojson:suspended-race",
            parsed,
            listOf(OccupiedExternalImportIdentity(ExternalImportObjectKind.WAYPOINT, firstId)),
            ExternalImportIdentityResolver { error("updated race mapping was not reused") },
        )
        assertEquals(remintedId, retry.result.waypoints.single().id)
    }

    private fun parsedPayload() = GeoJsonImporter.Result(
        waypoints = listOf(
            Waypoint(
                id = "11111111-1111-4111-8111-111111111111",
                name = "Collision",
                latitude = 0.0,
                longitude = 0.0,
            )
        ),
        drawings = listOf(
            DrawingFeature(
                id = "",
                name = "Missing",
                geometry = DrawingGeometry.LINE,
                points = listOf(DrawingPoint(0.0, 0.0), DrawingPoint(1.0, 1.0)),
            )
        ),
        newLayers = emptyList(),
        identityOrder = listOf(
            ParsedExternalImportIdentity(
                "feature-0",
                ExternalImportObjectKind.WAYPOINT,
                0,
                "11111111-1111-4111-8111-111111111111",
            ),
            ParsedExternalImportIdentity(
                "feature-1",
                ExternalImportObjectKind.DRAWING,
                0,
                null,
            ),
        ),
    )
}
