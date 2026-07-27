package com.tacmap.sync

import com.tacmap.util.DataKey
import com.tacmap.util.SafeStore
import com.tacmap.util.SealedEnvelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Validates Android v3 protocol implementation against the shared fixture
 * (testdata/sync_protocol_v3.json). If these pass, the Android client will
 * interop byte-for-byte with the relay and iOS.
 */
class SyncProtocolV3Test {

    private val testStoreKey = ByteArray(32) { (it + 19).toByte() }
    private val sealedLabels = mutableSetOf<String>()

    @Before fun installStoreKey() {
        sealedLabels.clear()
        SafeStore.keyProvider = SafeStore.KeyProvider { testStoreKey }
        SafeStore.migrationPolicy = object : SafeStore.MigrationPolicy {
            override fun isSealedOnly(label: String) = label in sealedLabels
            override fun markSealedOnly(label: String) { sealedLabels += label }
        }
    }

    @After fun restoreStoreKey() {
        SafeStore.keyProvider = SafeStore.KeyProvider { DataKey.key() }
        SafeStore.migrationPolicy = object : SafeStore.MigrationPolicy {
            override fun isSealedOnly(label: String) = DataKey.isStoreSealedOnly(label)
            override fun markSealedOnly(label: String) = DataKey.markStoreSealedOnly(label)
        }
    }

    private val fixture: JsonObject by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            val f = File(dir, "testdata/sync_protocol_v3.json")
            if (f.exists()) return@lazy Json.parseToJsonElement(f.readText()).jsonObject
            dir = dir?.parentFile
        }
        error("Could not locate testdata/sync_protocol_v3.json")
    }

    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
    private val actorA: String get() = fixture["identity"]!!.jsonObject["device_a"]!!.jsonObject.str("actor_id")
    private val actorB: String get() = fixture["identity"]!!.jsonObject["device_b"]!!.jsonObject.str("actor_id")

    // -- Key derivation --

    @Test
    fun v3KeyDerivationMatchesFixture() {
        val kd = fixture["key_derivation"]!!.jsonObject
        val joinCode = kd.str("join_code")
        val keys = SyncCrypto.deriveRoomV3(joinCode)

        assertEquals(kd.str("room_id"), keys.roomId)
        assertEquals(kd.str("room_id_raw_hex"), SyncIdentity.bytesToHex(keys.roomIdRaw))
        assertEquals(kd.str("room_key_hex"), SyncIdentity.bytesToHex(keys.roomKey))
        assertEquals(kd.str("metadata_key_hex"), SyncIdentity.bytesToHex(keys.metadataKey))
        assertEquals(kd.str("auth_token_base64url"), keys.authToken)
    }

    @Test
    fun generatedCodesActivateV3WhileLegacyCodesRemainJoinable() {
        repeat(20) {
            val code = SyncCrypto.generateJoinCode()
            assertTrue(code.startsWith("3:"))
            assertEquals(16, code.removePrefix("3:").length)
            assertFalse(SyncCrypto.isJoinCodeTooWeak(code))
        }
        assertFalse("legacy v2 codes remain accepted", SyncCrypto.isJoinCodeTooWeak("ABCDEFGHJKMNPQRS"))
        assertTrue("the v3 prefix must not count as entropy", SyncCrypto.isJoinCodeTooWeak("3:ABCDEFGHIJKL"))
    }

    // -- Actor ID --

    @Test
    fun actorIdMatchesFixture() {
        val kd = fixture["key_derivation"]!!.jsonObject
        val identity = fixture["identity"]!!.jsonObject
        val roomIdRaw = SyncIdentity.hexToBytes(kd.str("room_id_raw_hex"))

        val devA = identity["device_a"]!!.jsonObject
        val pubA = SyncIdentity.hexToBytes(devA.str("pubkey_raw_hex"))
        assertEquals(devA.str("actor_id"), SyncIdentity.actorId(roomIdRaw, pubA))

        val devB = identity["device_b"]!!.jsonObject
        val pubB = SyncIdentity.hexToBytes(devB.str("pubkey_raw_hex"))
        assertEquals(devB.str("actor_id"), SyncIdentity.actorId(roomIdRaw, pubB))
    }

    @Test
    fun crossRoomCorrelationPrevented() {
        val identity = fixture["identity"]!!.jsonObject
        val devA = identity["device_a"]!!.jsonObject
        val cross = identity["cross_room_correlation"]!!.jsonObject

        val pubA = SyncIdentity.hexToBytes(devA.str("pubkey_raw_hex"))
        val altKeys = SyncCrypto.deriveRoomV3(cross.str("alt_join_code"))
        val altActorId = SyncIdentity.actorId(altKeys.roomIdRaw, pubA)

        assertEquals(cross.str("device_a_actor_id_in_alt_room"), altActorId)
        assertNotEquals(devA.str("actor_id"), altActorId)
    }

    // -- Wire object IDs --

    @Test
    fun wireObjectIdMatchesFixture() {
        val wids = fixture["wire_object_ids"]!!.jsonObject
        val metadataKey = SyncIdentity.hexToBytes(wids.str("metadata_key_hex"))
        val cases = wids["cases"]!!.jsonArray

        for (cEl in cases) {
            val c = cEl.jsonObject
            val localUuid = SyncIdentity.hexToBytes(c.str("local_uuid_hex"))
            assertEquals(c.str("wire_object_id"), SyncIdentity.wireObjectId(metadataKey, localUuid))
        }
    }

    // -- VersionStamp --

    @Test
    fun versionStampComparisonMatchesFixture() {
        val vs = fixture["version_stamp"]!!.jsonObject
        val cases = vs["comparison_cases"]!!.jsonArray

        for (cEl in cases) {
            val c = cEl.jsonObject
            val a = VersionStamp.parse(c.str("a"))!!
            val b = VersionStamp.parse(c.str("b"))!!
            val winner = c.str("winner")
            if (winner == "a") {
                assertTrue("${c.str("name")}: a should win", a > b)
            } else {
                assertTrue("${c.str("name")}: b should win", b > a)
            }
        }
    }

    @Test
    fun versionStampRoundTrip() {
        val vs = VersionStamp(42, actorA)
        val encoded = vs.encode()
        assertEquals("000000000000002a:$actorA", encoded)
        val parsed = VersionStamp.parse(encoded)!!
        assertEquals(vs, parsed)
    }

    @Test
    fun versionStampMaxCounter() {
        val vs = VersionStamp(VersionStamp.MAX_COUNTER, actorA)
        assertEquals("7fffffffffffffff:$actorA", vs.encode())
        val parsed = VersionStamp.parse(vs.encode())!!
        assertEquals(VersionStamp.MAX_COUNTER, parsed.counter)
    }

    @Test
    fun versionStampParseBadInput() {
        assertNull(VersionStamp.parse(""))
        assertNull(VersionStamp.parse("not-a-stamp"))
        assertNull(VersionStamp.parse("0000000000000001"))
        assertNull(VersionStamp.parse("000000000000001:x"))
        assertNull(VersionStamp.parse("00000000000000001:x"))
        assertNull(VersionStamp.parse("000000000000000g:x"))
        assertNull(VersionStamp.parse("000000000000000A:$actorA"))
        assertNull(VersionStamp.parse("0000000000000001:not-a-32-byte-actor"))
    }

    // -- Signed preimage --

    @Test
    fun signedPreimageMatchesFixture() {
        val sp = fixture["signed_preimage"]!!.jsonObject
        val kd = fixture["key_derivation"]!!.jsonObject
        val roomIdRaw = SyncIdentity.hexToBytes(kd.str("room_id_raw_hex"))
        val sessionDomain = SyncIdentity.hexToBytes(sp.str("session_domain_hex"))
        val cases = sp["cases"]!!.jsonArray

        for (cEl in cases) {
            val c = cEl.jsonObject
            val domainByte = Integer.decode(c.str("domain_byte")).toByte()
            val actorId = c.str("actor_id")
            val counter = c["counter"]!!.jsonPrimitive.long
            val objectId = c.str("object_id")
            val kind = c.str("kind")
            val payloadHash = SyncIdentity.hexToBytes(c.str("payload_hash_hex"))

            val preimage = SyncIdentity.buildPreimage(
                domainByte, roomIdRaw, actorId, sessionDomain,
                VersionStamp.counterHex16(counter), objectId, kind, payloadHash)

            assertEquals(
                "${c.str("name")}: preimage mismatch",
                c.str("preimage_hex"),
                SyncIdentity.bytesToHex(preimage)
            )
        }
    }

    @Test
    fun signatureVerifiesAgainstFixture() {
        val sp = fixture["signed_preimage"]!!.jsonObject
        val identity = fixture["identity"]!!.jsonObject
        val devA = identity["device_a"]!!.jsonObject
        val pubKeyB64 = devA.str("pubkey_base64url")
        val cases = sp["cases"]!!.jsonArray

        for (cEl in cases) {
            val c = cEl.jsonObject
            val preimageBytes = SyncIdentity.hexToBytes(c.str("preimage_hex"))
            val sigB64 = c.str("signature_base64url")
            assertTrue(
                "${c.str("name")}: signature should verify",
                SyncSigning.verify(pubKeyB64, preimageBytes, sigB64)
            )
        }
    }

    // -- Auth verification --

    @Test
    fun authVerificationMatchesFixture() {
        val av = fixture["auth_verification"]!!.jsonObject
        val authTokenB64 = av.str("authTokenBase64url")
        val expectedRoomId = av.str("roomId")
        val authTokenRaw = SyncIdentity.urlB64Decode(authTokenB64)
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update("tacmap-room-id-v3".toByteArray(Charsets.UTF_8))
        md.update(byteArrayOf(0))
        md.update(authTokenRaw)
        val roomIdRaw = md.digest()
        assertEquals(expectedRoomId, SyncIdentity.urlB64(roomIdRaw))
    }

    // -- Replay state --

    @Test
    fun replayAcceptNewer() {
        val state = SyncReplayState("test-room")
        val existing = VersionStamp.parse("0000000000000003:$actorA")!!
        assertTrue(state.advance("obj1", existing))
        val incoming = VersionStamp.parse("0000000000000005:$actorA")!!
        assertTrue(state.advance("obj1", incoming))
    }

    @Test
    fun replayRejectOlder() {
        val state = SyncReplayState("test-room")
        assertTrue(state.advance("obj1", VersionStamp.parse("0000000000000005:$actorA")!!))
        assertFalse(state.advance("obj1", VersionStamp.parse("0000000000000003:$actorA")!!))
    }

    @Test
    fun replayRejectEqualSameActor() {
        val state = SyncReplayState("test-room")
        assertTrue(state.advance("obj1", VersionStamp.parse("0000000000000005:$actorA")!!))
        assertFalse(state.advance("obj1", VersionStamp.parse("0000000000000005:$actorA")!!))
    }

    @Test
    fun replayAcceptEqualCounterHigherActor() {
        val state = SyncReplayState("test-room")
        val lower = minOf(actorA, actorB)
        val higher = maxOf(actorA, actorB)
        assertTrue(state.advance("obj1", VersionStamp.parse("0000000000000005:$lower")!!))
        assertTrue(state.advance("obj1", VersionStamp.parse("0000000000000005:$higher")!!))
    }

    @Test
    fun replayTombstonePersists() {
        val state = SyncReplayState("test-room")
        assertTrue(state.tombstone("obj1", VersionStamp.parse("0000000000000005:$actorA")!!))
        assertTrue(state.isTombstoned("obj1"))
        assertFalse(state.advance("obj1", VersionStamp.parse("0000000000000003:$actorB")!!))
        assertTrue(state.advance("obj1", VersionStamp.parse("0000000000000007:$actorB")!!))
    }

    @Test
    fun counterAdvanceWindowEnforced() {
        val state = SyncReplayState("test-room")
        assertTrue(state.advance("obj1", VersionStamp(100, "actorA")))
        assertTrue(state.advance("obj2", VersionStamp(10099, "actorA")))
        assertFalse(state.advance("obj3", VersionStamp(20100, "actorA")))
        assertTrue(state.advance("obj3", VersionStamp(20099, "actorA")))
    }

    @Test
    fun actorRegistrationRejectsKeySwap() {
        val state = SyncReplayState("test-room")
        assertTrue(state.registerActor("actorA", "pubkey-A"))
        assertTrue(state.registerActor("actorA", "pubkey-A"))
        assertFalse(state.registerActor("actorA", "pubkey-B"))
    }

    @Test
    fun presenceCounterEnforced() {
        val state = SyncReplayState("test-room")
        assertTrue(state.advancePresence("actorA", 1))
        assertTrue(state.advancePresence("actorA", 5))
        assertFalse(state.advancePresence("actorA", 3))
        assertFalse(state.advancePresence("actorA", 5))
        assertTrue(state.advancePresence("actorA", 6))
    }

    @Test
    fun equalLiveHelloReconnectRetainsPersistedPresenceHighWater() {
        val publicKey = fixture["identity"]!!.jsonObject["device_a"]!!.jsonObject
            .str("pubkey_base64url")
        val firstSession = SyncIdentity.urlB64(
            SyncIdentity.hexToBytes(
                fixture["signed_preimage"]!!.jsonObject.str("session_domain_hex")
            )
        )
        val secondSession = SyncIdentity.urlB64(ByteArray(32) { 0x42 })
        val dir = Files.createTempDirectory("sync-presence-reconnect").toFile()

        val first = SyncReplayState("presence-reconnect", dir)
        assertTrue(first.load())
        assertTrue(first.commitActorHello(
            actorA, publicKey, firstSession, "0000000000000001"
        ))
        assertTrue(first.commitPresence(actorA, publicKey, firstSession, 7))
        val stateFile = File(dir, "sync_replay/presence-reconnect.json")
        val stored = JSONObject(String(
            requireNotNull(SealedEnvelope.openFile(
                testStoreKey, stateFile.readBytes(), "sync/room/presence-reconnect"
            )),
            Charsets.UTF_8,
        ))
        assertTrue(stored.has("presenceSeq"))
        assertFalse(stored.has("presenceSessions"))

        val reloaded = SyncReplayState("presence-reconnect", dir)
        assertTrue(reloaded.load())
        assertEquals(firstSession, reloaded.getPresenceSessionDomain(actorA))
        assertEquals(7L, reloaded.getPresenceCounter(actorA))

        // The relay may return the exact still-live signed hello after this
        // client reconnects. Reactivate it, but never reset its replay counter.
        assertTrue(reloaded.commitActorHello(
            actorA, publicKey, firstSession, "0000000000000001"
        ))
        assertFalse(reloaded.canAcceptPresence(actorA, publicKey, firstSession, 7))
        assertFalse(reloaded.commitPresence(actorA, publicKey, firstSession, 6))
        assertTrue(reloaded.commitPresence(actorA, publicKey, firstSession, 8))

        // Equal epoch with a different session is a replay/substitution.
        assertFalse(reloaded.commitActorHello(
            actorA, publicKey, secondSession, "0000000000000001"
        ))

        // A genuinely newer signed hello establishes a fresh counter domain.
        assertTrue(reloaded.commitActorHello(
            actorA, publicKey, secondSession, "0000000000000002"
        ))
        assertEquals(0L, reloaded.getPresenceCounter(actorA))
        assertTrue(reloaded.commitPresence(actorA, publicKey, secondSession, 1))
    }

    @Test
    fun presenceIsNotExposedWhenReplayStateCannotPersistCounter() {
        val publicKey = fixture["identity"]!!.jsonObject["device_a"]!!.jsonObject
            .str("pubkey_base64url")
        val session = SyncIdentity.urlB64(ByteArray(32) { 0x24 })
        var persistenceAvailable = true
        val state = SyncReplayState(
            "presence-persist-failure",
            persistOverride = { persistenceAvailable },
        )
        assertTrue(state.commitActorHello(
            actorA, publicKey, session, "0000000000000001"
        ))
        persistenceAvailable = false
        assertFalse(state.commitPresence(actorA, publicKey, session, 1))
        assertEquals(0L, state.getPresenceCounter(actorA))
    }

    @Test
    fun signedHelloUsesDedicatedDomainAndFixtureProof() {
        val kd = fixture["key_derivation"]!!.jsonObject
        val identity = fixture["identity"]!!.jsonObject
        val devA = identity["device_a"]!!.jsonObject
        val hello = fixture["signed_preimage"]!!.jsonObject["cases"]!!.jsonArray
            .map { it.jsonObject }.single { it.str("name") == "hello_announcement" }
        val preimage = SyncIdentity.buildPreimage(
            SyncIdentity.DOMAIN_HELLO,
            SyncIdentity.hexToBytes(kd.str("room_id_raw_hex")),
            devA.str("actor_id"),
            SyncIdentity.hexToBytes(fixture["signed_preimage"]!!.jsonObject.str("session_domain_hex")),
            "0000000000000001", "", "hello",
            SyncIdentity.sha256(SyncIdentity.hexToBytes(devA.str("pubkey_raw_hex")))
        )
        assertEquals(hello.str("preimage_hex"), SyncIdentity.bytesToHex(preimage))
        assertTrue(SyncSigning.verify(devA.str("pubkey_base64url"), preimage, hello.str("signature_base64url")))
    }

    @Test
    fun authenticatedSnapshotEstablishesHighCounterAndMonotonicFence() {
        val devA = fixture["identity"]!!.jsonObject["device_a"]!!.jsonObject
        val wireId = fixture["wire_object_ids"]!!.jsonObject["cases"]!!.jsonArray[0].jsonObject.str("wire_object_id")
        val state = SyncReplayState("test-room")
        val remote = SyncReplayState.AuthenticatedMutation(
            wireId, VersionStamp(50_000, actorA), devA.str("pubkey_base64url"), "ab".repeat(32), false
        )
        assertTrue(state.commitSnapshot(listOf(remote), 20))
        assertEquals(50_000, state.localCounter)
        assertTrue(state.commitSnapshot(emptyList(), 10))
        assertEquals("stale fence must never lower persisted state", 20, state.lastSnapshotSeq)
        val local = state.reserveLocalPut(wireId, actorA, devA.str("pubkey_base64url"), "cd".repeat(32))
        assertEquals(50_001, local!!.counter)
    }

    @Test
    fun persistenceFailureRollsBackOutboundReservation() {
        val devA = fixture["identity"]!!.jsonObject["device_a"]!!.jsonObject
        val wireId = fixture["wire_object_ids"]!!.jsonObject["cases"]!!.jsonArray[0].jsonObject.str("wire_object_id")
        val state = SyncReplayState("test-room", persistOverride = { false })
        assertNull(state.reserveLocalDelete(wireId, actorA, devA.str("pubkey_base64url")))
        assertEquals(0, state.localCounter)
        assertNull(state.getStamp(wireId))
        assertFalse(state.isTombstoned(wireId))
    }

    @Test
    fun outboundReservationPersistsStampTombstoneAndHashBeforeSend() {
        val devA = fixture["identity"]!!.jsonObject["device_a"]!!.jsonObject
        val wireId = fixture["wire_object_ids"]!!.jsonObject["cases"]!!.jsonArray[0].jsonObject.str("wire_object_id")
        var writes = 0
        val state = SyncReplayState("test-room", persistOverride = { writes++; true })
        val put = state.reserveLocalPut(wireId, actorA, devA.str("pubkey_base64url"), "ef".repeat(32))!!
        assertEquals(put, state.getStamp(wireId))
        assertEquals("ef".repeat(32), state.getContentHash(wireId))
        val delete = state.reserveLocalDelete(wireId, actorA, devA.str("pubkey_base64url"))!!
        assertEquals(delete, state.getStamp(wireId))
        assertTrue(state.isTombstoned(wireId))
        assertNull(state.getContentHash(wireId))
        assertEquals(2, writes)
    }

    @Test
    fun helloEpochAndCrashRecoveryAreExact() {
        val devA = fixture["identity"]!!.jsonObject["device_a"]!!.jsonObject
        val pub = devA.str("pubkey_base64url")
        val wireId = fixture["wire_object_ids"]!!.jsonObject["cases"]!!.jsonArray[0].jsonObject.str("wire_object_id")
        val state = SyncReplayState("test-room")
        assertEquals("0000000000000001", state.reserveHelloEpoch(actorA))
        assertEquals("0000000000000002", state.reserveHelloEpoch(actorA))
        val hash = "ab".repeat(32)
        val put = state.reserveLocalPut(wireId, actorA, pub, hash)!!
        assertEquals(put, state.recoverableLocalPut(wireId, actorA, pub, hash))
        assertNull(state.recoverableLocalPut(wireId, actorA, pub, "cd".repeat(32)))
        val del = state.reserveLocalDelete(wireId, actorA, pub)!!
        assertNull(state.recoverableLocalPut(wireId, actorA, pub, hash))
        assertEquals(listOf(wireId to del), state.recoverableLocalDeletes(actorA, pub))
    }

    @Test
    fun snapshotReappliesOnlyExactMutationAfterPersistBeforeModelCrash() {
        val devA = fixture["identity"]!!.jsonObject["device_a"]!!.jsonObject
        val pub = devA.str("pubkey_base64url")
        val wireId = fixture["wire_object_ids"]!!.jsonObject["cases"]!!.jsonArray[0].jsonObject.str("wire_object_id")
        val state = SyncReplayState("crash-recovery-room")

        // Simulate a kill after durable reservation but before the app model changed.
        val putHash = "12".repeat(32)
        val putStamp = state.reserveLocalPut(wireId, actorA, pub, putHash)!!
        val exactPut = SyncReplayState.AuthenticatedMutation(wireId, putStamp, pub, putHash, false)
        assertTrue(state.commitSnapshot(listOf(exactPut), 1))
        assertTrue("the returned exact snapshot put must repair the empty model",
            state.isExactPersistedMutation(exactPut))
        assertFalse(state.isExactPersistedMutation(exactPut.copy(contentHash = "34".repeat(32))))
        assertFalse(state.isExactPersistedMutation(exactPut.copy(contentHash = null, deleted = true)))

        // The same crash window exists for tombstones.
        val deleteStamp = state.reserveLocalDelete(wireId, actorA, pub)!!
        val exactDelete = SyncReplayState.AuthenticatedMutation(wireId, deleteStamp, pub, null, true)
        assertTrue(state.commitSnapshot(listOf(exactDelete), 2))
        assertTrue("the returned exact snapshot delete must repair a stale model",
            state.isExactPersistedMutation(exactDelete))
        assertFalse(state.isExactPersistedMutation(exactDelete.copy(contentHash = putHash, deleted = false)))
    }

    @Test
    fun pendingModelMarkerProtectsOfflinePutDeleteAndRecreate() {
        val pub = fixture["identity"]!!.jsonObject["device_a"]!!.jsonObject.str("pubkey_base64url")
        val wireId = fixture["wire_object_ids"]!!.jsonObject["cases"]!!.jsonArray[0].jsonObject.str("wire_object_id")
        val prior = "10".repeat(32)
        val incoming = "20".repeat(32)
        val offline = "30".repeat(32)

        fun putState(): Pair<SyncReplayState, SyncReplayState.AuthenticatedMutation> {
            val state = SyncReplayState("pending-put")
            val mutation = SyncReplayState.AuthenticatedMutation(
                wireId, VersionStamp(1, actorA), pub, incoming, deleted = false)
            assertTrue(state.commitRemoteAuthenticated(SyncReplayState.RemoteMutation(mutation, prior)))
            return state to mutation
        }

        putState().let { (state, mutation) ->
            assertEquals(SyncReplayState.PendingModelDecision.APPLY_INCOMING,
                state.pendingModelDecision(mutation, prior)) // crash before model apply
            assertTrue(state.clearPendingModelApplication(mutation))
        }
        putState().let { (state, mutation) ->
            assertEquals(SyncReplayState.PendingModelDecision.ALREADY_APPLIED,
                state.pendingModelDecision(mutation, incoming)) // crash after model apply
            assertTrue(state.clearPendingModelApplication(mutation))
        }
        putState().let { (state, mutation) ->
            assertEquals(SyncReplayState.PendingModelDecision.LOCAL_DIVERGED,
                state.pendingModelDecision(mutation, offline)) // offline edit wins
            assertTrue(state.clearPendingModelApplication(mutation))
            assertTrue(state.reserveLocalPut(wireId, actorA, pub, offline)!!.counter > mutation.stamp.counter)
        }
        putState().let { (state, mutation) ->
            assertEquals(SyncReplayState.PendingModelDecision.LOCAL_DIVERGED,
                state.pendingModelDecision(mutation, null)) // offline delete wins
            assertTrue(state.clearPendingModelApplication(mutation))
            assertTrue(state.reserveLocalDelete(wireId, actorA, pub)!!.counter > mutation.stamp.counter)
        }

        fun deleteState(): Pair<SyncReplayState, SyncReplayState.AuthenticatedMutation> {
            val state = SyncReplayState("pending-delete")
            val mutation = SyncReplayState.AuthenticatedMutation(
                wireId, VersionStamp(1, actorA), pub, null, deleted = true)
            assertTrue(state.commitRemoteAuthenticated(SyncReplayState.RemoteMutation(mutation, prior)))
            return state to mutation
        }
        deleteState().let { (state, mutation) ->
            assertEquals(SyncReplayState.PendingModelDecision.APPLY_INCOMING,
                state.pendingModelDecision(mutation, prior)) // crash before delete apply
            assertTrue(state.clearPendingModelApplication(mutation))
        }
        deleteState().let { (state, mutation) ->
            assertEquals(SyncReplayState.PendingModelDecision.ALREADY_APPLIED,
                state.pendingModelDecision(mutation, null)) // crash after delete apply
            assertTrue(state.clearPendingModelApplication(mutation))
        }
        deleteState().let { (state, mutation) ->
            assertEquals(SyncReplayState.PendingModelDecision.LOCAL_DIVERGED,
                state.pendingModelDecision(mutation, offline)) // offline recreate wins
            assertTrue(state.clearPendingModelApplication(mutation))
            assertTrue(state.reserveLocalPut(wireId, actorA, pub, offline)!!.counter > mutation.stamp.counter)
        }
    }

    @Test
    fun pendingModelMarkerSeparatesSenderPayloadHashFromReceiverModelHash() {
        val pub = fixture["identity"]!!.jsonObject["device_a"]!!.jsonObject.str("pubkey_base64url")
        val id = fixture["wire_object_ids"]!!.jsonObject["cases"]!!.jsonArray[0].jsonObject.str("wire_object_id")
        val senderPayloadHash = "41".repeat(32)
        val receiverModelHash = "42".repeat(32)
        val mutation = SyncReplayState.AuthenticatedMutation(
            id, VersionStamp(1, actorA), pub, senderPayloadHash, deleted = false)
        val state = SyncReplayState("cross-export-hash")
        assertTrue(state.commitRemoteAuthenticated(SyncReplayState.RemoteMutation(
            mutation, priorModelHash = null, expectedModelHash = receiverModelHash)))
        assertEquals(SyncReplayState.PendingModelDecision.ALREADY_APPLIED,
            state.pendingModelDecision(mutation, receiverModelHash))
        assertEquals(SyncReplayState.PendingModelDecision.LOCAL_DIVERGED,
            state.pendingModelDecision(mutation, senderPayloadHash))
    }

    @Test
    fun sealedReplayReloadPreservesDistinctReceiverModelHash() {
        val pub = fixture["identity"]!!.jsonObject["device_a"]!!.jsonObject.str("pubkey_base64url")
        val id = fixture["wire_object_ids"]!!.jsonObject["cases"]!!.jsonArray[0].jsonObject.str("wire_object_id")
        val rawHash = "51".repeat(32)
        val expectedHash = "52".repeat(32)
        val mutation = SyncReplayState.AuthenticatedMutation(
            id, VersionStamp(1, actorA), pub, rawHash, deleted = false)
        val dir = Files.createTempDirectory("sync-replay-expected").toFile()
        val state = SyncReplayState("sealed-expected", dir)
        assertTrue(state.load())
        assertTrue(state.save())
        assertTrue(state.commitRemoteAuthenticated(SyncReplayState.RemoteMutation(
            mutation, priorModelHash = null, expectedModelHash = expectedHash)))
        val file = File(dir, "sync_replay/sealed-expected.json")
        assertTrue(SealedEnvelope.isSealedFile(file.readBytes()))

        val reloaded = SyncReplayState("sealed-expected", dir)
        assertTrue(reloaded.load())
        assertEquals(SyncReplayState.PendingModelDecision.ALREADY_APPLIED,
            reloaded.pendingModelDecision(mutation, expectedHash))
        assertEquals(SyncReplayState.PendingModelDecision.LOCAL_DIVERGED,
            reloaded.pendingModelDecision(mutation, rawHash))
    }

    @Test
    fun legacySealedPendingWithoutExpectedHashFallsBackToRawHash() {
        val pub = fixture["identity"]!!.jsonObject["device_a"]!!.jsonObject.str("pubkey_base64url")
        val id = fixture["wire_object_ids"]!!.jsonObject["cases"]!!.jsonArray[0].jsonObject.str("wire_object_id")
        val rawHash = "61".repeat(32)
        val expectedHash = "62".repeat(32)
        val mutation = SyncReplayState.AuthenticatedMutation(
            id, VersionStamp(1, actorA), pub, rawHash, deleted = false)
        val room = "legacy-pending"
        val dir = Files.createTempDirectory("sync-replay-legacy").toFile()
        val state = SyncReplayState(room, dir)
        assertTrue(state.load())
        assertTrue(state.save())
        assertTrue(state.commitRemoteAuthenticated(SyncReplayState.RemoteMutation(
            mutation, priorModelHash = null, expectedModelHash = expectedHash)))
        val file = File(dir, "sync_replay/$room.json")
        val label = "sync/room/$room"
        val plain = requireNotNull(SealedEnvelope.openFile(testStoreKey, file.readBytes(), label))
        val json = JSONObject(String(plain, Charsets.UTF_8))
        json.getJSONObject("pendingModelApplications").getJSONObject(id).remove("expectedHash")
        SafeStore.writeAtomically(file, label, json.toString())

        val reloaded = SyncReplayState(room, dir)
        assertTrue(reloaded.load())
        assertEquals(SyncReplayState.PendingModelDecision.ALREADY_APPLIED,
            reloaded.pendingModelDecision(mutation, rawHash))
        assertEquals(SyncReplayState.PendingModelDecision.LOCAL_DIVERGED,
            reloaded.pendingModelDecision(mutation, expectedHash))
    }

    @Test
    fun globalGenerationClosesAbaAcrossLeaveAndRestart() {
        val pub = fixture["identity"]!!.jsonObject["device_a"]!!.jsonObject.str("pubkey_base64url")
        val wireId = fixture["wire_object_ids"]!!.jsonObject["cases"]!!.jsonArray[0].jsonObject.str("wire_object_id")
        val localId = "71d0f3d2-7d33-4af4-a593-d4cb70fb808d"
        val prior = "41".repeat(32)
        val incoming = "42".repeat(32)
        val journal = LocalModelRevisionJournal(
            File(System.getProperty("java.io.tmpdir") ?: "."), persistOverride = { true })

        fun pending(): Pair<SyncReplayState, SyncReplayState.AuthenticatedMutation> {
            val state = SyncReplayState("aba-room")
            val mutation = SyncReplayState.AuthenticatedMutation(
                wireId, VersionStamp(1, actorA), pub, incoming, deleted = false)
            assertTrue(state.commitRemoteAuthenticated(SyncReplayState.RemoteMutation(
                mutation, prior, localId, journal.generation(localId))))
            return state to mutation
        }

        // No edit: crash before and after apply remain recoverable.
        pending().let { (state, mutation) ->
            assertEquals(SyncReplayState.PendingModelDecision.APPLY_INCOMING,
                state.pendingModelDecision(mutation, prior, journal.generation(localId)))
            assertEquals(SyncReplayState.PendingModelDecision.ALREADY_APPLIED,
                state.pendingModelDecision(mutation, incoming, journal.generation(localId)))
        }

        // The journal is independent of replay/room lifetime: simulate Leave,
        // restart, then an offline delete of an applied remote PUT (nil ABA).
        val (deleteState, deleteMutation) = pending()
        assertTrue(journal.bump(localId))
        assertEquals(SyncReplayState.PendingModelDecision.LOCAL_DIVERGED,
            deleteState.pendingModelDecision(deleteMutation, null, journal.generation(localId)))

        // Edit then exact revert to the prior hash must still be divergence.
        val (revertState, revertMutation) = pending()
        val accepted = journal.generation(localId)
        assertTrue(journal.bump(localId)); assertTrue(journal.bump(localId))
        assertEquals(SyncReplayState.PendingModelDecision.LOCAL_DIVERGED,
            revertState.pendingModelDecision(revertMutation, prior, journal.generation(localId)))
        assertTrue(journal.generation(localId) > accepted)

        // Recreate/delete ending at the incoming state clears as already applied,
        // per the required decision ordering.
        val (cycleState, cycleMutation) = pending()
        assertTrue(journal.bump(localId)); assertTrue(journal.bump(localId))
        assertEquals(SyncReplayState.PendingModelDecision.ALREADY_APPLIED,
            cycleState.pendingModelDecision(cycleMutation, incoming, journal.generation(localId)))
    }

    @Test
    fun relayBaseNormalizationRemovesConfiguredRoomPath() {
        assertEquals("wss://example.test", SyncManager.normalizeRelayBase("wss://example.test/room/"))
        assertEquals("wss://example.test", SyncManager.normalizeRelayBase("wss://example.test/v3/room"))
    }
}
