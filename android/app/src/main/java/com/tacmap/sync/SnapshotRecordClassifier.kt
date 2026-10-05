package com.tacmap.sync

/**
 * Per-record outcome for snapshot items and live put/del (plans/04 section 2).
 * Structural problems kill the whole snapshot, per-record failures are skipped.
 * A skip is observably the same as the relay leaving the record out, which it
 * can already do, as long as the skip has no side effects at all.
 */
internal enum class SnapshotRecordCategory { FATAL, SKIP_UNVERIFIED, SKIP_UNSUPPORTED }

/** Names match testdata/sync_client_behaviour.json `snapshot.*` lists. */
internal enum class SnapshotRecordReason(val wireName: String, val category: SnapshotRecordCategory) {
    // structural, whole snapshot rejected
    BEGIN_WHEN_NOT_CONNECTING("snapshot_begin_when_not_connecting", SnapshotRecordCategory.FATAL),
    SECOND_BEGIN("second_snapshot_begin", SnapshotRecordCategory.FATAL),
    SEQ_NOT_NONNEGATIVE_INTEGER("seq_not_nonnegative_integer", SnapshotRecordCategory.FATAL),
    PAGE_BEFORE_BEGIN("page_before_begin", SnapshotRecordCategory.FATAL),
    PAGE_AFTER_FINAL("page_after_final_page", SnapshotRecordCategory.FATAL),
    MORE_NOT_BOOLEAN("more_not_boolean", SnapshotRecordCategory.FATAL),
    ITEMS_NOT_ARRAY("items_not_array", SnapshotRecordCategory.FATAL),
    ITEM_NOT_OBJECT("item_not_object", SnapshotRecordCategory.FATAL),
    ITEM_ID_INVALID("item_id_missing_or_not_canonical_32_byte_base64url", SnapshotRecordCategory.FATAL),
    DUPLICATE_WIRE_ID("duplicate_wire_id", SnapshotRecordCategory.FATAL),
    END_BEFORE_FINAL("end_before_final_page", SnapshotRecordCategory.FATAL),
    END_SEQ_MISMATCH("end_seq_mismatch", SnapshotRecordCategory.FATAL),
    AGGREGATE_BYTES("aggregate_bytes_over_54525952", SnapshotRecordCategory.FATAL),
    ITEM_COUNT("item_count_over_10000", SnapshotRecordCategory.FATAL),

    // skip, can't tell it apart from garbage
    VS_UNPARSEABLE("vs_unparseable", SnapshotRecordCategory.SKIP_UNVERIFIED),
    BY_NOT_VS_ACTOR("by_not_equal_vs_actor", SnapshotRecordCategory.SKIP_UNVERIFIED),
    PUB_OR_SD_NOT_CANONICAL("pub_or_sd_not_canonical", SnapshotRecordCategory.SKIP_UNVERIFIED),
    ACTOR_BINDING_MISMATCH("actor_binding_mismatch", SnapshotRecordCategory.SKIP_UNVERIFIED),
    PUB_DIFFERS_FROM_PIN("pub_differs_from_pinned_key", SnapshotRecordCategory.SKIP_UNVERIFIED),
    KIND_SYNTAX_INVALID("kind_syntax_invalid", SnapshotRecordCategory.SKIP_UNVERIFIED),
    TYPE_DELETED_INCONSISTENT("type_and_deleted_inconsistent", SnapshotRecordCategory.SKIP_UNVERIFIED),
    CT_INVALID("ct_not_canonical_or_too_short_or_too_long", SnapshotRecordCategory.SKIP_UNVERIFIED),
    AEAD_FAILED("aead_open_failed", SnapshotRecordCategory.SKIP_UNVERIFIED),
    INNER_JSON_INVALID("inner_json_invalid", SnapshotRecordCategory.SKIP_UNVERIFIED),
    SIGNATURE_INVALID("signature_missing_or_invalid", SnapshotRecordCategory.SKIP_UNVERIFIED),
    TOMBSTONE_EXTRA_KEYS("tombstone_inner_has_extra_keys", SnapshotRecordCategory.SKIP_UNVERIFIED),

    // skip, authentic but this build can't use it
    KIND_UNKNOWN("kind_unknown_but_authentic", SnapshotRecordCategory.SKIP_UNSUPPORTED),
    CONTENT_MISSING("put_content_missing_or_empty", SnapshotRecordCategory.SKIP_UNSUPPORTED),
    IMPORTER_FAILED("importer_failed", SnapshotRecordCategory.SKIP_UNSUPPORTED),
    IMPORTER_INVALID_SKIPPED("importer_invalid_skipped_nonzero", SnapshotRecordCategory.SKIP_UNSUPPORTED),
    OBJECT_COUNT("object_count_not_exactly_one", SnapshotRecordCategory.SKIP_UNSUPPORTED),
    KIND_CONTENT_MISMATCH("kind_content_mismatch", SnapshotRecordCategory.SKIP_UNSUPPORTED),
    EMBEDDED_UUID_MISMATCH("embedded_uuid_does_not_match_wire_id", SnapshotRecordCategory.SKIP_UNSUPPORTED),
    IDENTITY_COLLISION("identity_collision_with_other_object_kind", SnapshotRecordCategory.SKIP_UNSUPPORTED),
    EXPECTED_HASH_UNAVAILABLE("expected_model_hash_unavailable", SnapshotRecordCategory.SKIP_UNSUPPORTED),
}

/**
 * Same unit name as iOS (plans/04 section 23.1). The per-record crypto and
 * parse checks live in [SnapshotValidator] so the snapshot worker can own its
 * own key copies; this is the table the fixture lists are checked against and
 * the one entry point both the snapshot and live paths classify through.
 */
internal object SnapshotRecordClassifier {
    private val byWireName = SnapshotRecordReason.entries.associateBy { it.wireName }

    /** FATAL kills the whole snapshot, the two SKIP_* drop one record. */
    fun category(wireName: String): SnapshotRecordCategory? = byWireName[wireName]?.category

    fun classify(
        validator: SnapshotValidator,
        rec: org.json.JSONObject,
        wireId: String,
        layers: List<com.tacmap.drawings.DrawingLayer>,
        localKindOf: (String) -> String? = { null },
        localIdOf: (String) -> String? = { null },
    ): V3Check = validator.check(rec, wireId, layers, localKindOf, localIdOf)
}

/** Snapshot fence checks that don't need keys. Seq regression is a relay hint:
 * surface it once per join and keep syncing (plans/04 section 5). */
internal object SnapshotFence {
    fun isRegression(seq: Long, lastSnapshotSeq: Long): Boolean =
        lastSnapshotSeq >= 0 && seq < lastSnapshotSeq
}

/**
 * Live put/del that is authentic and newer but past roomHighWater + window
 * means our baseline is behind (relay idle expiry / compaction). Resync by
 * reconnecting, at most once a minute and 6 times an hour (plans/04 section 4).
 */
internal class LiveWindowResyncPolicy {
    private var lastResyncMs: Long? = null
    private var pending = false
    private val recent = ArrayDeque<Long>()

    /** A live record got rejected by our window. True means resync right now. */
    fun windowRejection(nowMs: Long): Boolean {
        if (allowedNow(nowMs)) {
            record(nowMs)
            return true
        }
        pending = true
        return false
    }

    /** Called at the cooldown end. True means do the remembered resync now. */
    fun tick(nowMs: Long): Boolean {
        if (!pending || !allowedNow(nowMs)) return false
        record(nowMs)
        return true
    }

    val hasPending: Boolean get() = pending

    /** A fresh snapshot just landed, an older remembered resync is moot. */
    fun clearPending() {
        pending = false
    }

    fun nextTickMs(nowMs: Long): Long? {
        if (!pending) return null
        val cooldownEnd = (lastResyncMs ?: nowMs) + COOLDOWN_MS
        prune(nowMs)
        val hourEnd = if (recent.size >= MAX_PER_HOUR) recent.first() + HOUR_MS else Long.MIN_VALUE
        return maxOf(cooldownEnd, hourEnd, nowMs)
    }

    fun reset() {
        lastResyncMs = null
        pending = false
        recent.clear()
    }

    private fun allowedNow(nowMs: Long): Boolean {
        val last = lastResyncMs
        if (last != null && nowMs - last < COOLDOWN_MS) return false
        prune(nowMs)
        return recent.size < MAX_PER_HOUR
    }

    private fun record(nowMs: Long) {
        lastResyncMs = nowMs
        pending = false
        recent.addLast(nowMs)
    }

    private fun prune(nowMs: Long) {
        while (recent.isNotEmpty() && nowMs - recent.first() >= HOUR_MS) recent.removeFirst()
    }

    companion object {
        const val COOLDOWN_MS = 60_000L
        const val MAX_PER_HOUR = 6
        private const val HOUR_MS = 3_600_000L
    }
}
