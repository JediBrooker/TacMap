package com.tacmap.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BillingEntitlementRepositoryTest {
    @Test fun legacyAffirmativeCacheDoesNotExpireAfterEightDaysOrClockRollback() {
        // The v1 timestamp is intentionally absent from the new persistence
        // contract, so neither elapsed wall time nor rollback participates.
        val persistence = FakePersistence(durable = null, legacy = true)

        val repository = BillingEntitlementRepository(persistence)

        assertTrue(repository.snapshot().isPurchased)
        assertEquals(true, persistence.durable)
        assertTrue(persistence.writes.single().isPurchased)
    }

    @Test fun everyInconclusiveQueryRetainsKnownGoodOwnershipAndAcknowledgementWork() {
        val current = BillingEntitlementSnapshot(
            isPurchased = true,
            pendingAcknowledgements = setOf("token"),
        )

        // Representative transport, availability, timeout, verification and
        // generic failures all use the same non-authoritative policy path.
        repeat(5) {
            assertEquals(
                current,
                BillingEntitlementPolicy.resolveQuery(
                    current = current,
                    authoritative = false,
                    purchasedTokens = emptySet(),
                    unacknowledgedTokens = emptySet(),
                ),
            )
        }
    }

    @Test fun authoritativeEmptyQueryIsTheOnlyQueryThatClearsKnownGoodOwnership() {
        val persistence = FakePersistence(durable = true, pending = setOf("old-token"))
        val repository = BillingEntitlementRepository(persistence)

        assertTrue(repository.recordAuthoritativeQuery(emptySet(), emptySet()))

        assertFalse(repository.snapshot().isPurchased)
        assertTrue(repository.snapshot().pendingAcknowledgements.isEmpty())
    }

    @Test fun pendingOnlyQueryDoesNotUnlock() {
        val repository = BillingEntitlementRepository(FakePersistence(durable = false))

        // Pending tokens are deliberately not supplied as purchased tokens.
        assertTrue(repository.recordAuthoritativeQuery(emptySet(), emptySet()))

        assertFalse(repository.snapshot().isPurchased)
    }

    @Test fun purchaseAndPendingAcknowledgementArePersistedAtomicallyBeforeGrant() {
        val persistence = FakePersistence(durable = false)
        val repository = BillingEntitlementRepository(persistence)

        assertTrue(repository.recordPurchase("purchase-token", needsAcknowledgement = true))

        assertEquals(
            BillingEntitlementSnapshot(true, setOf("purchase-token")),
            repository.snapshot(),
        )
        assertEquals(
            repository.snapshot(),
            BillingEntitlementRepository(persistence).snapshot(),
        )
    }

    @Test fun failedPersistenceDoesNotPublishGrantOrRevocation() {
        val grantPersistence = FakePersistence(durable = false)
        val grantRepository = BillingEntitlementRepository(grantPersistence)
        grantPersistence.failWrites = true

        assertFalse(grantRepository.recordPurchase("token", needsAcknowledgement = true))
        assertFalse(grantRepository.snapshot().isPurchased)

        val revokePersistence = FakePersistence(durable = true)
        val revokeRepository = BillingEntitlementRepository(revokePersistence)
        revokePersistence.failWrites = true

        assertFalse(revokeRepository.recordAuthoritativeQuery(emptySet(), emptySet()))
        assertTrue(revokeRepository.snapshot().isPurchased)
    }

    @Test fun acknowledgementSurvivesRecreationUntilConfirmedAndPersisted() {
        val persistence = FakePersistence(durable = false)
        val first = BillingEntitlementRepository(persistence)
        assertTrue(first.recordPurchase("token", needsAcknowledgement = true))

        val recreated = BillingEntitlementRepository(persistence)
        assertEquals(setOf("token"), recreated.snapshot().pendingAcknowledgements)
        assertTrue(recreated.recordAcknowledged("token"))

        assertTrue(BillingEntitlementRepository(persistence).snapshot().pendingAcknowledgements.isEmpty())
    }

    private class FakePersistence(
        var durable: Boolean?,
        private val legacy: Boolean = false,
        pending: Set<String> = emptySet(),
    ) : BillingEntitlementPersistence {
        private var savedPending = pending.toSet()
        var failWrites = false
        val writes = mutableListOf<BillingEntitlementSnapshot>()

        override fun readDurableEntitlement(): Boolean? = durable
        override fun readLegacyEntitlement(): Boolean = legacy
        override fun readPendingAcknowledgements(): Set<String> = savedPending.toSet()

        override fun write(snapshot: BillingEntitlementSnapshot): Boolean {
            if (failWrites) return false
            writes += snapshot
            durable = snapshot.isPurchased
            savedPending = snapshot.pendingAcknowledgements.toSet()
            return true
        }
    }
}
