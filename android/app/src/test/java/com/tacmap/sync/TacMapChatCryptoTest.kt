package com.tacmap.sync

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TacMapChatCryptoTest {
    private val fixture: JSONObject by lazy { JSONObject(loadFixture("tacmap_chat_v1.json")) }

    @Test
    fun chatKeyAdvertMatchesSharedVector() {
        val room = fixture.getJSONObject("room")
        val session = fixture.getJSONObject("sessions").getJSONObject("a")
        val vector = fixture.getJSONObject("chat_keys").getJSONObject("a")
        val frame = TacMapChatWire.parseKeyFrame(vector.getJSONObject("frame"))
        assertNotNull(frame)
        frame!!
        val roomRaw = hex(room.getString("room_id_raw_hex"))
        val sd = hex(session.getString("sd_hex"))
        val kx = hex(session.getString("x25519_public_hex"))

        assertEquals(
            session.getString("kid"),
            TacMapChatCrypto.chatKeyId(roomRaw, session.getString("actor_id"), sd, kx),
        )
        val preimage = TacMapChatCrypto.chatKeyPreimage(
            roomRaw,
            session.getString("actor_id"),
            sd,
            kx,
            session.getString("kid"),
        )
        assertArrayEquals(hex(vector.getString("preimage_hex")), preimage)
        val signingPub = SyncIdentity.urlB64(hex(session.getString("ed25519_pub_hex")))
        assertTrue(SyncSigning.verify(signingPub, preimage, frame.signature))
    }

    @Test
    fun roomMessageMatchesHeaderAadKeySignatureAndPlaintextVectors() {
        val room = fixture.getJSONObject("room")
        val session = fixture.getJSONObject("sessions").getJSONObject("a")
        val vector = fixture.getJSONObject("room_message")
        val parsed = TacMapChatWire.parseFrame(vector.getJSONObject("frame"))
        assertNotNull(parsed)
        parsed!!
        val header = TacMapChatCrypto.header(
            parsed.headerFields(hex(room.getString("room_id_raw_hex")))
        )
        assertArrayEquals(hex(vector.getString("header_hex")), header)
        assertArrayEquals(hex(vector.getString("aad_hex")), TacMapChatCrypto.aad(header))
        val key = TacMapChatCrypto.roomChatKey(hex(room.getString("room_key_hex")))
        assertArrayEquals(hex(room.getString("room_chat_key_hex")), key)
        val signingPub = SyncIdentity.urlB64(hex(session.getString("ed25519_pub_hex")))
        val plaintext = TacMapChatCrypto.verifyThenOpen(
            signingPub,
            parsed.signature,
            key,
            parsed.sealedRaw,
            header,
        )
        assertNotNull(plaintext)
        assertArrayEquals(hex(vector.getString("plaintext_hex")), plaintext)
        assertEquals(TacMapChatContentKind.REPORT, TacMapChatPayloadCodec.decode(plaintext!!)!!.kind)
    }

    @Test
    fun directMessageMatchesX25519HkdfAndSignedCiphertextVectors() {
        val room = fixture.getJSONObject("room")
        val sessions = fixture.getJSONObject("sessions")
        val a = sessions.getJSONObject("a")
        val b = sessions.getJSONObject("b")
        val vector = fixture.getJSONObject("direct_message")
        val parsed = TacMapChatWire.parseFrame(vector.getJSONObject("frame"))!!
        val roomRaw = hex(room.getString("room_id_raw_hex"))
        val header = TacMapChatCrypto.header(parsed.headerFields(roomRaw))
        assertArrayEquals(hex(vector.getString("header_hex")), header)
        val aKey = TacMapChatEphemeralKey.fromPrivateForTests(hex(a.getString("x25519_private_hex")))
        val bKey = TacMapChatEphemeralKey.fromPrivateForTests(hex(b.getString("x25519_private_hex")))
        assertArrayEquals(hex(a.getString("x25519_public_hex")), aKey.publicKeyRaw)
        assertArrayEquals(hex(b.getString("x25519_public_hex")), bKey.publicKeyRaw)
        assertArrayEquals(
            hex(vector.getString("shared_secret_hex")),
            aKey.deriveSharedSecret(bKey.publicKeyRaw),
        )
        val senderKey = TacMapChatCrypto.directChatKey(aKey, bKey.publicKeyRaw, roomRaw, header)
        val recipientKey = TacMapChatCrypto.directChatKey(bKey, aKey.publicKeyRaw, roomRaw, header)
        assertArrayEquals(hex(vector.getString("key_hex")), senderKey)
        assertArrayEquals(senderKey, recipientKey)
        val signingPub = SyncIdentity.urlB64(hex(a.getString("ed25519_pub_hex")))
        val plaintext = TacMapChatCrypto.verifyThenOpen(
            signingPub,
            parsed.signature,
            recipientKey!!,
            parsed.sealedRaw,
            header,
        )
        assertArrayEquals(hex(vector.getString("plaintext_hex")), plaintext)
        assertEquals(TacMapChatContentKind.TEXT, TacMapChatPayloadCodec.decode(plaintext!!)!!.kind)
        aKey.clear()
        bKey.clear()
        assertTrue(aKey.isCleared)
        assertNull(aKey.deriveSharedSecret(hex(b.getString("x25519_public_hex"))))
    }

    @Test
    fun payloadEncodingMatchesVectorAndRejectsMalformedUtf8UnknownKeysAndReservedReceipt() {
        val vector = fixture.getJSONObject("direct_message")
        val payload = TacMapChatPayload(
            pv = 1,
            kind = TacMapChatContentKind.TEXT,
            body = "RV at checkpoint 4.",
            createdAt = 1_720_000_001_000,
            replyTo = null,
        )
        assertArrayEquals(hex(vector.getString("plaintext_hex")), TacMapChatPayloadCodec.encode(payload))
        assertNull(TacMapChatPayloadCodec.decode(byteArrayOf(0xc3.toByte(), 0x28)))
        assertNull(TacMapChatPayloadCodec.decode(
            "{\"pv\":1,\"kind\":\"text\",\"body\":\"ok\",\"createdAt\":1,\"extra\":1}"
                .toByteArray()
        ))
        assertNull(TacMapChatPayloadCodec.decode(
            "{\"pv\":1,\"kind\":\"receipt\",\"body\":\"ok\",\"createdAt\":1}"
                .toByteArray()
        ))
        assertNull(TacMapChatPayloadCodec.decode(
            "{\"pv\":1,\"kind\":\"text\",\"body\":\"first\",\"body\":\"second\",\"createdAt\":1}"
                .toByteArray()
        ))
    }

    @Test
    fun wireParserRejectsExtraFieldsAndNoncanonicalCiphertext() {
        val source = fixture.getJSONObject("room_message").getJSONObject("frame")
        val extra = JSONObject(source.toString()).put("epk", fixture
            .getJSONObject("chat_keys").getJSONObject("a").getJSONObject("frame").getString("kx"))
        assertNull(TacMapChatWire.parseFrame(extra))
        val noncanonical = JSONObject(source.toString()).put("ct", source.getString("ct") + "=")
        assertNull(TacMapChatWire.parseFrame(noncanonical))
        val tampered = JSONObject(source.toString()).put("scope", "direct")
        assertNull(TacMapChatWire.parseFrame(tampered))
    }

    @Test
    fun directTargetDoesNotRetargetAfterSessionOrKeyRotation() {
        val session = fixture.getJSONObject("sessions").getJSONObject("b")
        val actor = session.getString("actor_id")
        val original = TacMapChatPeerKey(
            actor,
            SyncIdentity.urlB64(hex(session.getString("sd_hex"))),
            session.getString("kid"),
            hex(session.getString("x25519_public_hex")),
            hex(session.getString("ed25519_pub_hex")),
            "Bravo",
        )
        val target = original.immutableTarget()
        assertTrue(TacMapChatTargetGate.evaluate(target, true, true, mapOf(actor to original))
            is TacMapChatSendGate.Ready)
        val replacement = original.copy(
            sessionDomain = SyncIdentity.urlB64(ByteArray(32) { 9 }),
            chatKeyId = SyncIdentity.urlB64(ByteArray(32) { 8 }),
        )
        val result = TacMapChatTargetGate.evaluate(target, true, true, mapOf(actor to replacement))
        assertTrue(result is TacMapChatSendGate.Blocked)
        assertFalse(result is TacMapChatSendGate.Ready)
    }

    @Test
    fun inboundChatMayRaceAheadOfLocalKeyAckWhileOutboundRemainsBlocked() {
        val source = sourceText(
            "android/app/src/main/java/com/tacmap/sync/SyncManager.kt"
        )
        val inboundHandler = source
            .substringAfter("private fun applyChatV3(msg: JSONObject)")
            .substringBefore("\n    private fun ")

        // The relay can route a frame after accepting our advertised key but before
        // this socket processes its key ACK. Inbound still performs the exact local-key
        // recipient check, signature verification, AEAD open, and replay-store checks.
        assertFalse(inboundHandler.contains("localChatKeyAcknowledged"))
        assertTrue(inboundHandler.contains("val ownKid = localChatKeyId ?: return"))
        assertTrue(inboundHandler.contains("frame.recipientChatKeyId != ownKid"))
        assertTrue(inboundHandler.contains("SyncSigning.verify"))
        assertTrue(inboundHandler.contains("TacMapChatCrypto.open"))
        assertTrue(inboundHandler.contains("chatHistoryStore.acceptInbound"))

        // Sending and its UI readiness remain gated until that ACK is processed.
        val outbound = TacMapChatTargetGate.evaluate(
            target = TacMapChatTarget.EntireRoom,
            connectedV3 = true,
            localChatKeyAcknowledged = false,
            peerKeys = emptyMap(),
        )
        assertTrue(outbound is TacMapChatSendGate.Blocked)
        assertEquals("Secure chat is still starting", (outbound as TacMapChatSendGate.Blocked).reason)
    }

    private fun hex(value: String): ByteArray = SyncIdentity.hexToBytes(value)

    private fun loadFixture(name: String): String {
        var dir = File(checkNotNull(System.getProperty("user.dir")))
        repeat(6) {
            val candidate = File(dir, "testdata/$name")
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile ?: return@repeat
        }
        error("Could not locate testdata/$name")
    }

    private fun sourceText(relativePath: String): String {
        var directory = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            val source = File(directory, relativePath)
            if (source.exists()) return source.readText()
            directory = directory.parentFile ?: return@repeat
        }
        error("Could not locate $relativePath")
    }
}
