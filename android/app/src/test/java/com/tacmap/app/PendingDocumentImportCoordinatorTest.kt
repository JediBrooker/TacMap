package com.tacmap.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingDocumentImportCoordinatorTest {
    @Test
    fun pendingResultCanBeClaimedAndCompletedExactlyOnce() {
        val coordinator = PendingDocumentImportCoordinator()
        val pending = coordinator.publish(
            DocumentImportKind.GEO_JSON,
            "content://documents/mission",
            persistableGrantTaken = true,
            token = "token-1",
        )

        assertEquals(pending, coordinator.claim("token-1"))
        assertNull(coordinator.claim("token-1"))
        assertNull(coordinator.complete("wrong-token"))
        assertEquals(pending, coordinator.complete("token-1"))
        assertNull(coordinator.current())
        assertNull(coordinator.complete("token-1"))
    }

    @Test
    fun composeTeardownAbandonsClaimAndAllowsOneRetry() {
        val pending = PendingDocumentImport(
            "token-2",
            DocumentImportKind.KML,
            "content://documents/overlay",
            false,
        )
        val coordinator = PendingDocumentImportCoordinator(pending)

        assertEquals(pending, coordinator.claim("token-2"))
        assertTrue(coordinator.abandon("token-2"))
        assertFalse(coordinator.abandon("token-2"))
        assertEquals(pending, coordinator.claim("token-2"))
    }

    @Test
    fun savedSnapshotRestoresAnInFlightClaimAsReady() {
        val original = PendingDocumentImportCoordinator()
        val pending = original.publish(
            DocumentImportKind.MBTILES,
            "content://documents/tiles",
            true,
            token = "token-3",
        )
        assertEquals(pending, original.claim("token-3"))

        val restored = PendingDocumentImportCoordinator(original.savedSnapshot())

        assertEquals(pending, restored.claim("token-3"))
    }
}
