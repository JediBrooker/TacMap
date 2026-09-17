package com.tacmap.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import java.util.UUID
import kotlin.math.min

private const val SENSITIVE_EXTRA = "android.content.extra.IS_SENSITIVE"
private const val CLIP_TOKEN_EXTRA = "com.tacmap.clipboard.TOKEN"
private const val PREFS_NAME = "sensitive_clipboard_expiry"
private const val MAX_PENDING_CLIPS = 3
private const val CLEAR_RETRY_BACKOFF_MS = 5_000L
private const val TOKEN_SUFFIX = "token"
private const val CREATED_ELAPSED_SUFFIX = "created_elapsed_ms"
private const val EXPIRES_ELAPSED_SUFFIX = "expires_elapsed_ms"
private const val EXPIRES_WALL_SUFFIX = "expires_wall_ms"
private val PENDING_KEYS = (0 until MAX_PENDING_CLIPS).flatMapTo(mutableSetOf()) { slot ->
    listOf(
        pendingKey(slot, TOKEN_SUFFIX),
        pendingKey(slot, CREATED_ELAPSED_SUFFIX),
        pendingKey(slot, EXPIRES_ELAPSED_SUFFIX),
        pendingKey(slot, EXPIRES_WALL_SUFFIX),
    )
}
const val SENSITIVE_CLIP_TTL_MS = 60_000L

internal data class SensitiveClipExpiry(
    val token: String,
    val createdElapsedMs: Long,
    val expiresElapsedMs: Long,
    val expiresWallMs: Long,
) {
    fun isExpired(nowElapsedMs: Long, nowWallMs: Long): Boolean =
        nowElapsedMs < createdElapsedMs ||
            nowElapsedMs >= expiresElapsedMs ||
            nowWallMs >= expiresWallMs

    fun remainingMs(nowElapsedMs: Long, nowWallMs: Long): Long = min(
        (expiresElapsedMs - nowElapsedMs).coerceAtLeast(0L),
        (expiresWallMs - nowWallMs).coerceAtLeast(0L),
    )
}

internal data class SensitiveClipObservation(
    val descriptionPresent: Boolean,
    val token: String?,
)

internal data class SensitiveClipboardState(
    val candidates: List<SensitiveClipExpiry>,
) {
    init {
        require(candidates.isNotEmpty() && candidates.size <= MAX_PENDING_CLIPS)
        require(candidates.map { it.token }.toSet().size == candidates.size)
    }

    fun matching(token: String?): SensitiveClipExpiry? = candidates.firstOrNull { it.token == token }

    fun canTransitionTo(next: SensitiveClipExpiry): Boolean =
        matching(next.token) != null || candidates.size < MAX_PENDING_CLIPS

    fun transitionTo(next: SensitiveClipExpiry): SensitiveClipboardState {
        require(canTransitionTo(next)) { "Sensitive clipboard transition is full" }
        return SensitiveClipboardState(listOf(next) + candidates.filterNot { it.token == next.token })
    }

    /** Readback selects the published candidate; an ambiguous read retains all. */
    fun resolvedAfterReadback(
        observation: SensitiveClipObservation?,
        descriptionReadSucceeded: Boolean,
    ): SensitiveClipboardState = when {
        !descriptionReadSucceeded || observation?.descriptionPresent != true -> this
        else -> matching(observation.token)?.let { SensitiveClipboardState(listOf(it)) } ?: this
    }
}

internal fun shouldClearSensitiveClip(
    pending: SensitiveClipExpiry,
    currentToken: String?,
    nowElapsedMs: Long,
    nowWallMs: Long,
): Boolean = currentToken == pending.token && pending.isExpired(nowElapsedMs, nowWallMs)

internal enum class SensitiveClipClearDecision {
    RemoveState,
    RetrySoon,
    WaitForForeground,
}

internal fun sensitiveClipClearDecision(
    expectedToken: String,
    clearCallSucceeded: Boolean,
    observation: SensitiveClipObservation?,
    descriptionReadSucceeded: Boolean,
): SensitiveClipClearDecision = when {
    !clearCallSucceeded -> SensitiveClipClearDecision.RetrySoon
    !descriptionReadSucceeded || observation?.descriptionPresent != true ->
        SensitiveClipClearDecision.WaitForForeground
    observation.token == expectedToken -> SensitiveClipClearDecision.RetrySoon
    else -> SensitiveClipClearDecision.RemoveState
}

/**
 * Copies sensitive text without retaining the text for cleanup. Durable random
 * tokens let TacMap recover both sides of an uncertain ClipboardService write,
 * retry expiry after process recreation/sleep, and avoid clearing later clips.
 */
fun copySensitivePlainText(
    context: Context,
    label: String,
    text: String,
    ttlMs: Long = SENSITIVE_CLIP_TTL_MS,
): Boolean {
    require(ttlMs > 0L) { "Sensitive clipboard TTL must be positive" }
    val appContext = context.applicationContext
    val clipboard = appContext
        .getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return false
    val createdElapsedMs = SystemClock.elapsedRealtime()
    val next = SensitiveClipExpiry(
        token = UUID.randomUUID().toString(),
        createdElapsedMs = createdElapsedMs,
        expiresElapsedMs = saturatingAdd(createdElapsedMs, ttlMs),
        expiresWallMs = saturatingAdd(System.currentTimeMillis(), ttlMs),
    )
    var priorState = readPendingState(appContext)
    if (priorState != null) {
        val beforeRead = readClipToken(clipboard)
        val observation = beforeRead.getOrNull()
        if (beforeRead.isSuccess && observation?.descriptionPresent == true) {
            priorState = priorState.matching(observation.token)?.let {
                SensitiveClipboardState(listOf(it))
            }
            if (priorState == null) {
                clearPendingState(appContext)
            } else {
                writePendingState(appContext, priorState)
            }
        } else if (!priorState.canTransitionTo(next)) {
            // Do not evict an unresolved candidate merely to service another
            // ambiguous ClipboardService write. The caller reports failure and
            // can retry once foreground read access is available.
            return false
        }
    }
    val transition = priorState?.transitionTo(next)
        ?: SensitiveClipboardState(listOf(next))
    if (!writePendingState(appContext, transition)) return false

    val clip = ClipData.newPlainText(label, text).apply {
        description.extras = PersistableBundle().apply {
            putBoolean(SENSITIVE_EXTRA, true)
            putString(CLIP_TOKEN_EXTRA, next.token)
        }
    }
    runCatching { clipboard.setPrimaryClip(clip) }
    val readback = readClipToken(clipboard)
    val resolved = transition.resolvedAfterReadback(
        observation = readback.getOrNull(),
        descriptionReadSucceeded = readback.isSuccess,
    )
    val stateForRetry = if (writePendingState(appContext, resolved)) {
        resolved
    } else {
        // A failed normalization leaves the already-durable transition intact.
        transition
    }
    val nowElapsedMs = SystemClock.elapsedRealtime()
    val nowWallMs = System.currentTimeMillis()
    scheduleRetry(
        appContext,
        stateForRetry.candidates.minOf {
            if (it.isExpired(nowElapsedMs, nowWallMs)) 1L
            else it.remainingMs(nowElapsedMs, nowWallMs).coerceAtLeast(1L)
        },
    )

    val observation = readback.getOrNull()
    return observation?.token == next.token
}

/** Retry an expired exact-token clear once Android grants foreground access. */
fun retryExpiredSensitiveClipboard(context: Context) {
    retry(context.applicationContext)
}

private fun scheduleRetry(context: Context, delayMs: Long) {
    Handler(Looper.getMainLooper()).postDelayed(
        { retry(context) },
        delayMs,
    )
}

private fun retry(context: Context) {
    val state = readPendingState(context) ?: return
    val nowElapsedMs = SystemClock.elapsedRealtime()
    val nowWallMs = System.currentTimeMillis()
    if (state.candidates.none { it.isExpired(nowElapsedMs, nowWallMs) }) {
        scheduleRetry(
            context,
            state.candidates.minOf {
                it.remainingMs(nowElapsedMs, nowWallMs).coerceAtLeast(1L)
            },
        )
        return
    }

    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return
    val currentRead = readClipToken(clipboard)
    val observation = currentRead.getOrNull()
    val currentToken = observation?.token
    val current = state.matching(currentToken)
    if (current == null) {
        // A readable description without a matching token supersedes every
        // candidate. A missing description is ambiguous because Android may
        // deny clipboard access; preserve state for a later focused retry.
        if (currentRead.isSuccess && observation?.descriptionPresent == true) {
            removePendingStateIfMatches(context, state)
        }
        return
    }
    if (!current.isExpired(nowElapsedMs, nowWallMs)) {
        val normalized = SensitiveClipboardState(listOf(current))
        writePendingState(context, normalized)
        scheduleRetry(
            context,
            current.remainingMs(nowElapsedMs, nowWallMs).coerceAtLeast(1L),
        )
        return
    }

    val clearSucceeded = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            clipboard.clearPrimaryClip()
        } else {
            clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }.isSuccess
    val afterRead = if (clearSucceeded) readClipToken(clipboard) else null
    when (sensitiveClipClearDecision(
        expectedToken = current.token,
        clearCallSucceeded = clearSucceeded,
        observation = afterRead?.getOrNull(),
        descriptionReadSucceeded = afterRead?.isSuccess == true,
    )) {
        SensitiveClipClearDecision.RemoveState -> removePendingStateIfMatches(context, state)
        SensitiveClipClearDecision.RetrySoon -> scheduleRetry(context, CLEAR_RETRY_BACKOFF_MS)
        SensitiveClipClearDecision.WaitForForeground -> Unit
    }
}

private fun writePendingState(context: Context, state: SensitiveClipboardState): Boolean {
    val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return DurablePreferenceCommit.preferences(
        preferences = preferences,
        keys = PENDING_KEYS,
        mutate = {
            PENDING_KEYS.forEach(::remove)
            state.candidates.forEachIndexed { slot, pending ->
                putString(pendingKey(slot, TOKEN_SUFFIX), pending.token)
                    .putLong(pendingKey(slot, CREATED_ELAPSED_SUFFIX), pending.createdElapsedMs)
                    .putLong(pendingKey(slot, EXPIRES_ELAPSED_SUFFIX), pending.expiresElapsedMs)
                    .putLong(pendingKey(slot, EXPIRES_WALL_SUFFIX), pending.expiresWallMs)
            }
            this
        },
        publish = {},
    )
}

private fun readPendingState(context: Context): SensitiveClipboardState? {
    val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val candidates = mutableListOf<SensitiveClipExpiry>()
    var malformed = false
    for (slot in 0 until MAX_PENDING_CLIPS) {
        val token = preferences.getString(pendingKey(slot, TOKEN_SUFFIX), null)
        if (token == null) {
            val hasOrphan = listOf(
                CREATED_ELAPSED_SUFFIX,
                EXPIRES_ELAPSED_SUFFIX,
                EXPIRES_WALL_SUFFIX,
            ).any { preferences.contains(pendingKey(slot, it)) }
            malformed = malformed || hasOrphan
            continue
        }
        val createdElapsedMs = preferences.getLong(pendingKey(slot, CREATED_ELAPSED_SUFFIX), -1L)
        val expiresElapsedMs = preferences.getLong(pendingKey(slot, EXPIRES_ELAPSED_SUFFIX), -1L)
        val expiresWallMs = preferences.getLong(pendingKey(slot, EXPIRES_WALL_SUFFIX), -1L)
        if (token.isBlank() || createdElapsedMs < 0L ||
            expiresElapsedMs < createdElapsedMs || expiresWallMs < 0L ||
            candidates.any { it.token == token }
        ) {
            malformed = true
            continue
        }
        candidates += SensitiveClipExpiry(token, createdElapsedMs, expiresElapsedMs, expiresWallMs)
    }
    if (malformed) {
        clearPendingState(context)
        return null
    }
    return candidates.takeIf { it.isNotEmpty() }?.let(::SensitiveClipboardState)
}

private fun removePendingStateIfMatches(context: Context, expected: SensitiveClipboardState) {
    val stored = readPendingState(context) ?: return
    if (stored.candidates.map { it.token } != expected.candidates.map { it.token }) return
    clearPendingState(context)
}

private fun clearPendingState(context: Context): Boolean {
    val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return DurablePreferenceCommit.preferences(
        preferences = preferences,
        keys = PENDING_KEYS,
        mutate = {
            PENDING_KEYS.forEach(::remove)
            this
        },
        publish = {},
    )
}

private fun readClipToken(clipboard: ClipboardManager): Result<SensitiveClipObservation> = runCatching {
    val description = clipboard.primaryClipDescription
    SensitiveClipObservation(
        descriptionPresent = description != null,
        token = description?.extras?.getString(CLIP_TOKEN_EXTRA),
    )
}

private fun pendingKey(slot: Int, suffix: String): String = "slot_${slot}_$suffix"

private fun saturatingAdd(value: Long, increment: Long): Long =
    if (value > Long.MAX_VALUE - increment) Long.MAX_VALUE else value + increment
