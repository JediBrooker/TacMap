package com.tacmap.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncIssueLifecycleTest {
    @Test
    fun rollbackWarningSurvivesTheAckFromItsOwnSnapshotGeneration() {
        val lifecycle = SyncIssueLifecycle()
        val generation = lifecycle.beginConnection()
        lifecycle.report("rollback", SyncIssueKind.SECURITY, generation)

        val remaining = lifecycle.connectionSucceeded(
            atGeneration = generation,
            verifiedCleanSnapshot = true,
        )

        assertEquals("rollback", remaining?.message)
        assertEquals(SyncIssueKind.SECURITY, remaining?.kind)
    }

    @Test
    fun onlyALaterCleanSnapshotSupersedesAnUndismissedSecurityWarning() {
        val lifecycle = SyncIssueLifecycle()
        val warnedGeneration = lifecycle.beginConnection()
        lifecycle.report("rollback", SyncIssueKind.SECURITY, warnedGeneration)

        val retryGeneration = lifecycle.beginConnection()
        lifecycle.report("temporary disconnect", SyncIssueKind.CONNECTION, retryGeneration)
        assertEquals("rollback", lifecycle.issue?.message)
        assertNull(lifecycle.connectionSucceeded(retryGeneration, verifiedCleanSnapshot = true))
    }

    @Test
    fun ordinaryConnectionErrorsClearOnSuccessButNotFromAnOlderCallback() {
        val lifecycle = SyncIssueLifecycle()
        val oldGeneration = lifecycle.beginConnection()
        lifecycle.report("first failure", SyncIssueKind.CONNECTION, oldGeneration)
        val currentGeneration = lifecycle.beginConnection()
        lifecycle.report("current failure", SyncIssueKind.CONNECTION, currentGeneration)

        assertEquals(
            "current failure",
            lifecycle.connectionSucceeded(oldGeneration, verifiedCleanSnapshot = false)?.message,
        )
        assertNull(lifecycle.connectionSucceeded(currentGeneration, verifiedCleanSnapshot = false))
    }

    @Test
    fun explicitDismissalClearsEitherIssueKind() {
        val lifecycle = SyncIssueLifecycle()
        val generation = lifecycle.beginConnection()
        lifecycle.report("rollback", SyncIssueKind.SECURITY, generation)

        assertNull(lifecycle.dismiss())
        assertNull(lifecycle.issue)
    }

    @Test
    fun localMigrationWarningSurvivesSnapshotsAndDismissalUntilMigrationSucceeds() {
        val lifecycle = SyncIssueLifecycle()
        val generation = lifecycle.beginConnection()
        lifecycle.reportPersistentSecurity("storage migration", generation)

        val retryGeneration = lifecycle.beginConnection()
        assertEquals(
            "storage migration",
            lifecycle.connectionSucceeded(retryGeneration, verifiedCleanSnapshot = true)?.message,
        )
        lifecycle.report("rollback", SyncIssueKind.SECURITY, retryGeneration)
        assertEquals("rollback", lifecycle.issue?.message)
        assertEquals("storage migration", lifecycle.dismiss()?.message)
        assertNull(lifecycle.clearPersistentSecurity())
    }
}
