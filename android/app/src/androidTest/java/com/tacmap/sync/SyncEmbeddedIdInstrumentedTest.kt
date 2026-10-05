package com.tacmap.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tacmap.export.GeoJsonExporter
import com.tacmap.waypoints.Waypoint
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * snapshot.embeddedIdCases (plans/04 2.7) on ART. The 3.0.0 poison record leaned on
 * libcore stuff, Character.digit and UUID.fromString taking more than a canonical
 * UUID, so run the rows through the real validator on a device too and not only
 * on the JVM. Manager level coverage is SyncHostileRecordFixtureTest.
 */
@RunWith(AndroidJUnit4::class)
class SyncEmbeddedIdInstrumentedTest {

    private fun hexBytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun embeddedIdCasesClassifyTheSameOnDevice() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val fixture = JSONObject(assets.open("sync_client_behaviour.json").use { it.readBytes().toString(Charsets.UTF_8) })
        val rows = fixture.getJSONObject("snapshot").getJSONArray("embeddedIdCases")
        assertTrue(rows.length() > 0)

        val keys = SyncCrypto.deriveRoomV3("ABCDEFGHJKMNPQRS")
        val seed = SyncSigning.generateSeed()
        val pub = SyncSigning.publicKey(seed)
        val actor = SyncIdentity.actorId(keys.roomIdRaw, SyncIdentity.urlB64Decode(pub))
        val sd = SyncIdentity.generateSessionDomain()
        val validator = SnapshotValidator(keys.roomKey, keys.roomIdRaw, keys.metadataKey, { null }, 1f)
        val hasher = WireIdHasher(keys.metadataKey)
        val failures = ArrayList<String>()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val name = row.getString("id")
            val embeddedId = row.getString("embeddedId")
            val expect = row.getJSONObject("expect")
            try {
                val wire = SyncIdentity.wireObjectId(keys.metadataKey, hexBytes(row.getString("outerWireIdFromBytesHex")))
                val content = GeoJsonExporter.export(
                    listOf(Waypoint(id = embeddedId, name = name, latitude = -35.0, longitude = 149.0, createdAt = 1_700_000_000_000L)),
                    emptyList(), emptyList(), density = 1f,
                )
                val counter = 20L + i
                val vs = VersionStamp(counter, actor).encode()
                val preimage = SyncIdentity.buildPreimage(
                    SyncIdentity.DOMAIN_PUT, keys.roomIdRaw, actor, sd, VersionStamp.counterHex16(counter), wire, "waypoint",
                    SyncIdentity.sha256(content.toByteArray(Charsets.UTF_8)),
                )
                val inner = JSONObject().put("c", content).put("sig", SyncSigning.sign(seed, preimage))
                val ct = SyncCrypto.encodeBase64(
                    SyncCrypto.seal(keys.roomKey, inner.toString().toByteArray(Charsets.UTF_8), SyncCrypto.aadV3(wire, vs, "waypoint")),
                )
                val rec = JSONObject().put("id", wire).put("vs", vs).put("by", actor).put("kind", "waypoint")
                    .put("ct", ct).put("pub", pub).put("sd", SyncIdentity.urlB64(sd)).put("deleted", false)
                val check = SnapshotRecordClassifier.classify(validator, rec, wire, emptyList())
                if (expect.getString("category") == "valid") {
                    val put = (check as? V3Check.Valid)?.record as? ValidatedV3.Put
                    assertNotNull("$check", put)
                    assertEquals(expect.getString("localId"), put!!.localId)
                    assertEquals(listOf(put.localId), put.parsed.waypoints.map { it.id })
                    // the replay commit's own check, on libcore this time
                    assertEquals(put.localId, UUID.fromString(put.localId).toString())
                    assertEquals(wire, hasher.wireId(embeddedId))
                } else {
                    assertTrue("$check", check is V3Check.Skip)
                    assertEquals(expect.getString("reason"), (check as V3Check.Skip).reason.wireName)
                    assertEquals(SnapshotRecordCategory.SKIP_UNSUPPORTED, check.reason.category)
                    assertNull(SyncIdentity.uuidToBytes(embeddedId))
                    assertNull(hasher.wireId(embeddedId))
                }
            } catch (e: Throwable) {
                failures += "$name: $e"
            }
        }
        hasher.close()
        validator.close()
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
