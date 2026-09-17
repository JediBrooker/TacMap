package com.tacmap.export

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.ArrayDeque

class ExternalImportIdentityResolverTest {
    @Test
    fun sharedFixtureEnforcesGlobalNamespaceAndDeterministicRemintOrder() {
        val fixture = fixture()
        val remints = fixture.getJSONArray("injectedRemintIds").strings().toCollection(ArrayDeque())
        val resolver = ExternalImportIdentityResolver(
            ExternalImportIdFactory { remints.removeFirst() }
        )

        val resolution = resolver.resolve(
            incoming = fixture.getJSONArray("incomingObjects").identityInputs(),
            occupied = fixture.getJSONArray("existingObjects").occupiedIdentities(),
        )

        val expected = fixture.getJSONObject("expected")
        val expectedObjects = expected.getJSONArray("resolvedObjectsInInputOrder")
        assertEquals(expected.getInt("importedObjectCount"), resolution.objects.size)
        assertEquals(
            expected.getJSONArray("consumedRemintIdsInOrder").strings(),
            resolution.consumedRemintIds,
        )
        resolution.objects.forEachIndexed { index, actual ->
            val wanted = expectedObjects.getJSONObject(index)
            assertEquals(wanted.getString("caseKey"), actual.caseKey)
            assertEquals(wanted.getString("kind"), actual.kind.wireName)
            assertEquals(wanted.nullableString("sourceId"), actual.sourceId)
            assertEquals(wanted.getString("resolvedId"), actual.resolvedId)
            assertEquals(wanted.getString("resolution"), actual.reason.wireName)
        }

        val finalIds = fixture.getJSONArray("existingObjects").stringsFor("id") +
            resolution.objects.map { it.resolvedId }
        assertEquals(expected.getInt("finalGlobalObjectCountIncludingExisting"), finalIds.toSet().size)
        // The fixture's incoming layer intentionally reuses an existing object
        // UUID. Layers are a separate namespace and never enter this resolver.
        assertEquals(
            "11111111-1111-4111-8111-111111111111",
            fixture.getJSONObject("layers").getJSONArray("incoming").getJSONObject(0).getString("id"),
        )
    }

    @Test
    fun retryReusesPersistedMapWithoutConsumingOrRemintingAgain() {
        val fixture = fixture()
        val retry = fixture.getJSONObject("retry")
        val prior = retry.getJSONArray("priorResolutionMap").objects().associate {
            it.getString("caseKey") to it.getString("resolvedId")
        }
        val committedKeys = retry.getJSONObject("partialFirstAttempt")
            .getJSONArray("committedCaseKeys").strings().toSet()
        val incoming = fixture.getJSONArray("incomingObjects").identityInputs()
        val byCaseKey = incoming.associateBy { it.caseKey }
        val occupied = fixture.getJSONArray("existingObjects").occupiedIdentities() +
            committedKeys.map { key ->
                OccupiedExternalImportIdentity(
                    kind = checkNotNull(byCaseKey[key]).kind,
                    id = checkNotNull(prior[key]),
                )
            }
        val resolver = ExternalImportIdentityResolver(
            ExternalImportIdFactory { error("retry must not mint another ID") }
        )

        val resolution = resolver.resolve(incoming, occupied, prior)

        assertTrue(resolution.objects.all { it.reason == ExternalImportIdentityReason.RETRY_REUSED })
        assertTrue(resolution.consumedRemintIds.isEmpty())
        assertEquals(
            retry.getJSONArray("expectedResolvedIdsOnRetryInInputOrder").strings(),
            resolution.objects.map { it.resolvedId },
        )
        val alreadyPresent = occupied.mapTo(HashSet()) { it.id }
        val additional = resolution.objects.count { it.resolvedId !in alreadyPresent }
        assertEquals(retry.getJSONObject("partialFirstAttempt").getInt("expectedAdditionalObjectsOnRetry"), additional)
    }

    @Test
    fun uuidComparisonIsCaseInsensitiveButOutputIsCanonicalLowercase() {
        val resolver = ExternalImportIdentityResolver(
            ExternalImportIdFactory { "90000000-0000-4000-8000-000000000001" }
        )
        val resolution = resolver.resolve(
            incoming = listOf(
                ExternalImportIdentityInput(
                    caseKey = "uppercase-collision",
                    kind = ExternalImportObjectKind.DRAWING,
                    sourceId = "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA",
                )
            ),
            occupied = listOf(
                OccupiedExternalImportIdentity(
                    kind = ExternalImportObjectKind.WAYPOINT,
                    id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                )
            ),
        )

        assertEquals(ExternalImportIdentityReason.EXISTING_CROSS_TYPE_COLLISION, resolution.objects.single().reason)
        assertEquals("90000000-0000-4000-8000-000000000001", resolution.objects.single().resolvedId)
    }

    private fun fixture(): JSONObject = JSONObject(findFixture("import_identity.json").readText())

    private fun findFixture(name: String): File {
        var dir = File(checkNotNull(System.getProperty("user.dir")))
        repeat(6) {
            val candidate = File(dir, "testdata/$name")
            if (candidate.isFile) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        error("Could not locate testdata/$name")
    }

    private fun JSONArray.identityInputs(): List<ExternalImportIdentityInput> = objects().map { value ->
        ExternalImportIdentityInput(
            caseKey = value.getString("caseKey"),
            kind = ExternalImportObjectKind.fromWireName(value.getString("kind")),
            sourceId = value.nullableString("id"),
        )
    }

    private fun JSONArray.occupiedIdentities(): List<OccupiedExternalImportIdentity> = objects().map { value ->
        OccupiedExternalImportIdentity(
            kind = ExternalImportObjectKind.fromWireName(value.getString("kind")),
            id = value.getString("id"),
        )
    }

    private fun JSONArray.objects(): List<JSONObject> =
        (0 until length()).map(::getJSONObject)

    private fun JSONArray.strings(): List<String> =
        (0 until length()).map(::getString)

    private fun JSONArray.stringsFor(key: String): List<String> =
        objects().map { it.getString(key) }

    private fun JSONObject.nullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else getString(key)
}
