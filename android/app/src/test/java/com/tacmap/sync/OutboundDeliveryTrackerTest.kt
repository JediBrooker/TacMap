package com.tacmap.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OutboundDeliveryTrackerTest {
    private fun pending(
        localId: String = "local-a",
        requestId: String = "request-a",
        generation: Long = 1,
        session: String? = "session-a",
        version: String = "v1",
        contentHash: String? = "content-a",
    ) = PendingOutboundDelivery(
        localId, requestId, generation, "actor-a", session, "wire-a", version,
        if (contentHash == null) "del" else "waypoint", "cipher-a", contentHash,
        contentHash?.let { "content" }, "frame",
    )

    private fun ack(requestId: String = "request-a", session: String? = "session-a", version: String = "v1") =
        DeliveryAck(1, requestId, "actor-a", session, "wire-a", version, "waypoint", "cipher-a")

    @Test fun lostSendRemainsPendingAndRetriesAreBounded() {
        val tracker = OutboundDeliveryTracker(maxAttempts = 3)
        tracker.register(pending())
        assertNotNull(tracker.nextAttempt("request-a", 1, "session-a"))
        assertNotNull(tracker.nextAttempt("request-a", 1, "session-a"))
        assertNull(tracker.nextAttempt("request-a", 1, "session-a"))
        assertNotNull(tracker.pending("local-a"))
    }

    @Test fun exactAckIsIdempotentAndStaleOrReplacementSessionAckIsIgnored() {
        val tracker = OutboundDeliveryTracker()
        tracker.register(pending())
        assertNull(tracker.acknowledge(ack(session = "replacement")))
        assertNull(tracker.acknowledge(ack(version = "old")))
        assertNotNull(tracker.acknowledge(ack()))
        assertNull(tracker.acknowledge(ack()))
    }

    @Test fun supersedingEditCancelsOldGenerationAndDelayedAckCannotClearIt() {
        val tracker = OutboundDeliveryTracker()
        tracker.register(pending())
        tracker.register(pending(requestId = "request-b", version = "v2", contentHash = "content-b"))
        assertNull(tracker.acknowledge(ack()))
        assertEquals("request-b", tracker.pending("local-a")?.requestId)
    }

    @Test fun rejectionIsActionableAndOnlyRetryableCodesMayRetry() {
        val tracker = OutboundDeliveryTracker()
        tracker.register(pending())
        val rejected = tracker.reject(DeliveryNack(1, "request-a", "actor-a", "session-a", "quota", false))
        assertEquals("quota", rejected?.rejectionCode)
        assertNull(tracker.nextAttempt("request-a", 1, "session-a"))

        val storage = OutboundDeliveryTracker()
        storage.register(pending())
        storage.reject(DeliveryNack(1, "request-a", "actor-a", "session-a", "storage", true))
        assertNotNull(storage.nextAttempt("request-a", 1, "session-a"))
    }

    @Test fun reconnectClearsTransportGenerationButReturnsEveryUnconfirmedObjectForReconciliation() {
        val tracker = OutboundDeliveryTracker()
        tracker.register(pending())
        tracker.register(pending(localId = "local-b", requestId = "request-b"))
        assertEquals(setOf("local-a", "local-b"), tracker.resetForReconnect())
        assertEquals(emptyList<PendingOutboundDelivery>(), tracker.all())
    }

    @Test fun reconnectDoesNotResendATombstoneAlreadyConfirmedByTheSnapshot() {
        val stamp = VersionStamp(7, "actor-a")
        assertEquals(false, shouldResendRecoverableDelete("wire-a", stamp, mapOf("wire-a" to stamp.encode())))
        assertEquals(true, shouldResendRecoverableDelete("wire-a", stamp, emptyMap()))
    }

    @Test fun acknowledgementVersionMustBeTheStrictJsonIntegerOne() {
        val tracker = OutboundDeliveryTracker()
        val delivery = pending(requestId = "request_delivery_0001", session = null, version = "1")
            .copy(ciphertextHash = "A".repeat(43))
        tracker.register(delivery)
        val base = """"rid":"${delivery.requestId}","by":"${delivery.actorId}","id":"${delivery.wireObjectId}","v":1,"kind":"${delivery.kind}","cth":"${"A".repeat(43)}""""

        listOf("1.5", "1e0", "true", "\"1\"").forEach { invalidVersion ->
            val parsed = parseDeliveryAckFrame(JSONObject("{$base,\"av\":$invalidVersion}"), v3 = false)
            if (parsed != null) tracker.acknowledge(parsed)
            assertEquals("invalid av=$invalidVersion must not retire pending delivery",
                delivery.requestId, tracker.pending(delivery.localId)?.requestId)
        }

        val ack = parseDeliveryAckFrame(JSONObject("{$base,\"av\":1}"), v3 = false)
        assertNotNull(ack)
        assertNotNull(tracker.acknowledge(ack!!))
    }

    @Test fun nackVersionRejectsFractionalExponentBooleanAndStringWithoutMutation() {
        val tracker = OutboundDeliveryTracker()
        val delivery = pending(requestId = "request_delivery_0001", session = null)
        tracker.register(delivery)
        val base = """"rid":"${delivery.requestId}","by":"${delivery.actorId}","code":"storage","retry":true"""

        listOf("1.5", "1e0", "true", "\"1\"").forEach { invalidVersion ->
            val parsed = parseDeliveryNackFrame(JSONObject("{$base,\"av\":$invalidVersion}"), v3 = false)
            if (parsed != null) tracker.reject(parsed)
            assertNull(tracker.pending(delivery.localId)?.rejectionCode)
        }
    }

    @Test fun onlyExactCurrentGenerationSnapshotTombstoneConfirmsLegacyDelete() {
        val delivery = pending(
            requestId = "request_delivery_0001",
            generation = 4,
            session = null,
            version = "7",
            contentHash = null,
        )
        val recovery = LegacyDeleteRecovery.from(delivery, snapshotGeneration = 5)

        assertEquals(true, recovery.matchesVerifiedTombstone(
            delivery.localId, delivery.wireObjectId, delivery.actorId, "7", "del", delivery.ciphertextHash, 5,
        ))
        assertEquals(false, recovery.matchesVerifiedTombstone(
            delivery.localId, delivery.wireObjectId, delivery.actorId, "6", "del", delivery.ciphertextHash, 5,
        ))
        assertEquals(false, recovery.matchesVerifiedTombstone(
            delivery.localId, delivery.wireObjectId, delivery.actorId, "7", "waypoint", delivery.ciphertextHash, 5,
        ))
        assertEquals(false, recovery.matchesVerifiedTombstone(
            delivery.localId, delivery.wireObjectId, delivery.actorId, "7", "del", delivery.ciphertextHash, 4,
        ))
        assertEquals(false, recovery.matchesVerifiedTombstone(
            delivery.localId, "different-wire-id", delivery.actorId, "7", "del", delivery.ciphertextHash, 5,
        ))
    }
}
