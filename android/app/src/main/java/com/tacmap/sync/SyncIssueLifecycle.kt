package com.tacmap.sync

import com.tacmap.localization.LocalizedMessage

/** Classification matters because a transport reconnect is allowed to retire
 * an ordinary connection error, but it must not erase a rollback warning from
 * the snapshot it just accepted. */
internal enum class SyncIssueKind {
    CONNECTION,
    SECURITY,
}

internal data class SyncIssue(
    val pendingMessage: LocalizedMessage,
    val kind: SyncIssueKind,
    val generation: Long,
) {
    val message: String get() = pendingMessage.text
}

/** Generation-aware lifecycle for the actionable Sync banner.
 *
 * A security warning survives ordinary connection failures and the hello ack
 * from the same snapshot. It is retired only by explicit dismissal or by a
 * clean, fully verified snapshot from a later connection generation.
 */
internal class SyncIssueLifecycle {
    private var generation: Long = 0
    private var transientIssue: SyncIssue? = null
    private var persistentSecurityIssue: SyncIssue? = null

    val issue: SyncIssue?
        get() = transientIssue?.takeIf { it.kind == SyncIssueKind.SECURITY }
            ?: persistentSecurityIssue
            ?: transientIssue

    fun beginConnection(): Long {
        generation += 1
        return generation
    }

    fun report(message: LocalizedMessage, kind: SyncIssueKind, atGeneration: Long = generation): SyncIssue? {
        val current = transientIssue
        transientIssue = when {
            current == null -> SyncIssue(message, kind, atGeneration)
            kind == SyncIssueKind.SECURITY &&
                current.kind == SyncIssueKind.SECURITY &&
                atGeneration < current.generation -> current
            kind == SyncIssueKind.SECURITY -> SyncIssue(message, kind, atGeneration)
            current.kind == SyncIssueKind.SECURITY -> current
            atGeneration >= current.generation -> SyncIssue(message, kind, atGeneration)
            else -> current
        }
        return issue
    }

    /** Storage migration failures cannot be proven resolved by a clean relay
     * snapshot. They remain visible until a later local migration succeeds. */
    fun reportPersistentSecurity(
        message: LocalizedMessage,
        atGeneration: Long = generation,
    ): SyncIssue? {
        persistentSecurityIssue = SyncIssue(message, SyncIssueKind.SECURITY, atGeneration)
        return issue
    }

    fun clearPersistentSecurity(): SyncIssue? {
        persistentSecurityIssue = null
        return issue
    }

    fun connectionSucceeded(
        atGeneration: Long,
        verifiedCleanSnapshot: Boolean,
    ): SyncIssue? {
        val current = transientIssue ?: return issue
        transientIssue = when (current.kind) {
            SyncIssueKind.CONNECTION -> current.takeIf { it.generation > atGeneration }
            SyncIssueKind.SECURITY -> current.takeUnless {
                verifiedCleanSnapshot && atGeneration > it.generation
            }
        }
        return issue
    }

    fun dismiss(): SyncIssue? {
        transientIssue = null
        return issue
    }
}
