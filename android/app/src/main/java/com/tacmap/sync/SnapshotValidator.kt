package com.tacmap.sync

import com.tacmap.drawings.DrawingDocument
import com.tacmap.drawings.DrawingLayer
import com.tacmap.export.GeoJsonExporter
import com.tacmap.export.GeoJsonImporter
import org.json.JSONObject

/** Result of checking one v3 record (plans/04 section 2). */
internal sealed interface V3Check {
    data class Valid(val record: ValidatedV3) : V3Check
    data class Skip(val wireId: String, val reason: SnapshotRecordReason) : V3Check
}

internal sealed interface ValidatedV3 {
    val mutation: SyncReplayState.AuthenticatedMutation
    val localModelId: String?
    val expectedModelHash: String?

    data class Put(
        override val mutation: SyncReplayState.AuthenticatedMutation,
        val parsed: GeoJsonImporter.Result,
        val localId: String,
        val content: String,
        override val expectedModelHash: String,
    ) : ValidatedV3 {
        override val localModelId: String get() = localId
        val kind: String get() = if (parsed.waypoints.isNotEmpty()) "waypoint" else "drawing"
    }

    /** [localId] is resolved by the caller against the wire id index, the
     * worker never looks at local stores. */
    data class Delete(
        override val mutation: SyncReplayState.AuthenticatedMutation,
        val localId: String?,
    ) : ValidatedV3 {
        override val localModelId: String? get() = localId
        override val expectedModelHash: String? get() = null
    }
}

/**
 * Per-record v3 checks: outer shape, actor binding, AEAD, signature, importer
 * against a layer set, wire id vs embedded uuid, expected model hash
 * (plans/04 sections 2, 3 and 19).
 *
 * Nothing in here touches the replay state or the stores, so the snapshot copy
 * can run on a worker thread with copies of the keys and actor pins. The live
 * copy runs on the protocol thread and reads pins straight from replay state.
 * Not thread safe, one instance per thread.
 */
internal class SnapshotValidator(
    roomKey: ByteArray,
    roomIdRaw: ByteArray,
    metadataKey: ByteArray,
    private val pinnedKey: (String) -> String?,
    private val displayDensity: Float,
) {
    // own copies so leave() zeroing the manager's arrays can't race a worker
    private val roomKey = roomKey.copyOf()
    private val roomIdRaw = roomIdRaw.copyOf()
    private val hasher = WireIdHasher(metadataKey)
    @Volatile private var closed = false

    private class OuterV3(
        val wireId: String,
        val stamp: VersionStamp,
        val stampText: String,
        val actorId: String,
        val pub: String,
        val sessionDomain: ByteArray,
        val kind: String,
        val ciphertext: ByteArray,
        val deleted: Boolean,
    )

    private sealed interface OuterCheck {
        class Ok(val outer: OuterV3) : OuterCheck
        class Bad(val reason: SnapshotRecordReason) : OuterCheck
    }

    /** Outer shape only, nothing authenticated yet. [wireId] was checked by the caller. */
    private fun parseOuter(rec: JSONObject, wireId: String): OuterCheck {
        val vsStr = rec.opt("vs") as? String ?: return OuterCheck.Bad(SnapshotRecordReason.VS_UNPARSEABLE)
        val vs = VersionStamp.parse(vsStr) ?: return OuterCheck.Bad(SnapshotRecordReason.VS_UNPARSEABLE)
        val by = rec.opt("by") as? String ?: return OuterCheck.Bad(SnapshotRecordReason.BY_NOT_VS_ACTOR)
        if (vs.actorId != by) return OuterCheck.Bad(SnapshotRecordReason.BY_NOT_VS_ACTOR)
        val pub = rec.opt("pub") as? String
        val sdText = rec.opt("sd") as? String
        val pubRaw = pub?.let(SyncIdentity::urlB64Decode32)
        val sd = sdText?.let(SyncIdentity::urlB64Decode32)
        if (pub == null || pubRaw == null || sd == null) {
            return OuterCheck.Bad(SnapshotRecordReason.PUB_OR_SD_NOT_CANONICAL)
        }
        if (SyncIdentity.actorId(roomIdRaw, pubRaw) != by) {
            return OuterCheck.Bad(SnapshotRecordReason.ACTOR_BINDING_MISMATCH)
        }
        if (pinnedKey(by)?.let { it != pub } == true) {
            return OuterCheck.Bad(SnapshotRecordReason.PUB_DIFFERS_FROM_PIN)
        }
        val kind = rec.opt("kind") as? String ?: return OuterCheck.Bad(SnapshotRecordReason.KIND_SYNTAX_INVALID)
        if (!V3_KIND_PATTERN.matches(kind)) return OuterCheck.Bad(SnapshotRecordReason.KIND_SYNTAX_INVALID)
        val type = rec.optString("t")
        if (type.isNotEmpty() && type != "put" && type != "del") {
            return OuterCheck.Bad(SnapshotRecordReason.TYPE_DELETED_INCONSISTENT)
        }
        val deletedField = rec.opt("deleted")
        if (deletedField != null && deletedField !is Boolean) {
            return OuterCheck.Bad(SnapshotRecordReason.TYPE_DELETED_INCONSISTENT)
        }
        val storedDeleted = deletedField as? Boolean ?: false
        if ((type == "put" && storedDeleted) || (type == "del" && deletedField == false)) {
            return OuterCheck.Bad(SnapshotRecordReason.TYPE_DELETED_INCONSISTENT)
        }
        val deleted = type == "del" || storedDeleted
        if (deleted != (kind == "del")) return OuterCheck.Bad(SnapshotRecordReason.TYPE_DELETED_INCONSISTENT)
        val ctB64 = rec.opt("ct") as? String ?: return OuterCheck.Bad(SnapshotRecordReason.CT_INVALID)
        if (ctB64.isEmpty() || ctB64.length > MAX_BASE64_BYTES) return OuterCheck.Bad(SnapshotRecordReason.CT_INVALID)
        val ct = runCatching { SyncCrypto.decodeBase64(ctB64) }.getOrNull()
            ?: return OuterCheck.Bad(SnapshotRecordReason.CT_INVALID)
        if (ct.size < 28 || SyncCrypto.encodeBase64(ct) != ctB64) return OuterCheck.Bad(SnapshotRecordReason.CT_INVALID)
        return OuterCheck.Ok(OuterV3(wireId, vs, vsStr, by, pub, sd, kind, ct, deleted))
    }

    /**
     * Classify one record (plans/04 section 2.1). Anything that fails
     * authentication is SKIP_UNVERIFIED, anything authentic this build can't
     * use is SKIP_UNSUPPORTED. Unknown kinds still get opened and verified with
     * their own kind so newer formats aren't mistaken for garbage.
     */
    fun check(
        rec: JSONObject,
        wireId: String,
        layers: List<DrawingLayer>,
        /** Kind of the local object with this UUID, if there is one. */
        localKindOf: (String) -> String? = { null },
        /** lowercase UUID -> the id a local object is stored under when that's another casing */
        localIdOf: (String) -> String? = { null },
    ): V3Check {
        fun skip(reason: SnapshotRecordReason) = V3Check.Skip(wireId, reason)
        if (closed) return skip(SnapshotRecordReason.AEAD_FAILED)
        val outer = when (val parsed = parseOuter(rec, wireId)) {
            is OuterCheck.Bad -> return skip(parsed.reason)
            is OuterCheck.Ok -> parsed.outer
        }
        val aad = SyncCrypto.aadV3(outer.wireId, outer.stampText, outer.kind)
        val plain = SyncCrypto.open(roomKey, outer.ciphertext, aad) ?: return skip(SnapshotRecordReason.AEAD_FAILED)
        val inner = runCatching { JSONObject(String(plain, Charsets.UTF_8)) }.getOrNull()
            ?: return skip(SnapshotRecordReason.INNER_JSON_INVALID)
        val sig = (inner.opt("sig") as? String)?.ifEmpty { null } ?: return skip(SnapshotRecordReason.SIGNATURE_INVALID)
        if (outer.deleted) {
            val hash = SyncIdentity.sha256(ByteArray(0))
            val preimage = SyncIdentity.buildPreimage(
                SyncIdentity.DOMAIN_DELETE, roomIdRaw, outer.actorId, outer.sessionDomain,
                VersionStamp.counterHex16(outer.stamp.counter), outer.wireId, "del", hash
            )
            if (!SyncSigning.verify(outer.pub, preimage, sig)) return skip(SnapshotRecordReason.SIGNATURE_INVALID)
            // honest senders only ever put sig in a tombstone, same rule as iOS
            if (inner.length() != 1) return skip(SnapshotRecordReason.TOMBSTONE_EXTRA_KEYS)
            return V3Check.Valid(ValidatedV3.Delete(
                SyncReplayState.AuthenticatedMutation(outer.wireId, outer.stamp, outer.pub, null, deleted = true),
                localId = null,
            ))
        }
        val content = inner.opt("c") as? String ?: ""
        val payloadHash = SyncIdentity.sha256(content.toByteArray(Charsets.UTF_8))
        val preimage = SyncIdentity.buildPreimage(
            SyncIdentity.DOMAIN_PUT, roomIdRaw, outer.actorId, outer.sessionDomain,
            VersionStamp.counterHex16(outer.stamp.counter), outer.wireId, outer.kind, payloadHash
        )
        if (!SyncSigning.verify(outer.pub, preimage, sig)) return skip(SnapshotRecordReason.SIGNATURE_INVALID)
        // from here on it's authentic, just maybe not something this build can show
        if (outer.kind !in V3_OBJECT_KINDS) return skip(SnapshotRecordReason.KIND_UNKNOWN)
        if (content.isEmpty()) return skip(SnapshotRecordReason.CONTENT_MISSING)
        val fallback = layers.firstOrNull()?.id ?: DrawingDocument.DEFAULT_LAYER_ID
        val parsed = runCatching {
            GeoJsonImporter.parse(
                content,
                existingLayers = layers,
                fallbackLayerId = fallback,
                density = displayDensity,
                keepRingAnchors = true,
            )
        }.getOrNull() ?: return skip(SnapshotRecordReason.IMPORTER_FAILED)
        if (parsed.invalidSkipped != 0) return skip(SnapshotRecordReason.IMPORTER_INVALID_SKIPPED)
        if (parsed.waypoints.size + parsed.drawings.size != 1) return skip(SnapshotRecordReason.OBJECT_COUNT)
        val embeddedId = when (outer.kind) {
            "waypoint" -> parsed.waypoints.singleOrNull()?.id
            "drawing" -> parsed.drawings.singleOrNull()?.id
            else -> null
        } ?: return skip(SnapshotRecordReason.KIND_CONTENT_MISMATCH)
        // canonical before we hash it (plans/04 2.7). a dashless or non hex id used to
        // hash fine here, then the replay commit refused it and sync stopped for good
        val canonical = SyncIdentity.canonicalUuid(embeddedId)
            ?: return skip(SnapshotRecordReason.EMBEDDED_UUID_MISMATCH)
        if (hasher.wireId(canonical) != outer.wireId) return skip(SnapshotRecordReason.EMBEDDED_UUID_MISMATCH)
        // an object we already keep under an uppercase id (old imports kept the raw feature id)
        // stays under it. folding past it made a second copy, or tombstoned our own record
        val localId = localIdOf(canonical) ?: canonical
        val folded = withLocalId(parsed, localId)
        // a waypoint and a drawing never share one UUID. applying it anyway left
        // two objects behind one id and the diff ping-ponged them forever
        localKindOf(localId)?.let { existing ->
            if (existing != outer.kind) return skip(SnapshotRecordReason.IDENTITY_COLLISION)
        }
        val expected = expectedModelHash(folded, localId, layers, displayDensity)
            ?: return skip(SnapshotRecordReason.EXPECTED_HASH_UNAVAILABLE)
        return V3Check.Valid(ValidatedV3.Put(
            SyncReplayState.AuthenticatedMutation(
                outer.wireId, outer.stamp, outer.pub, SyncIdentity.bytesToHex(payloadHash), deleted = false
            ),
            folded,
            localId,
            content,
            expected,
        ))
    }

    fun close() {
        closed = true
        roomKey.fill(0)
        roomIdRaw.fill(0)
        hasher.close()
    }

    companion object {
        const val MAX_BASE64_BYTES = 1_048_576
        private val V3_KIND_PATTERN = Regex("^[A-Za-z0-9_-]{1,32}$")
        private val V3_OBJECT_KINDS = setOf("waypoint", "drawing")

        /**
         * The record's object under its local id: lowercase, or the casing a local object
         * already has. Uppercase is fine on the wire but one object must never end up with
         * two local ids (plans/04 2.7). The restage reparse needs this too, it starts from
         * the sender's bytes again.
         */
        fun withLocalId(parsed: GeoJsonImporter.Result, localId: String): GeoJsonImporter.Result {
            val canonical = SyncIdentity.canonicalUuid(localId) ?: return parsed
            fun folds(id: String) = id != localId && SyncIdentity.canonicalUuid(id) == canonical
            if (parsed.waypoints.none { folds(it.id) } && parsed.drawings.none { folds(it.id) }) return parsed
            return parsed.copy(
                waypoints = parsed.waypoints.map { if (folds(it.id)) it.copy(id = localId) else it },
                drawings = parsed.drawings.map { if (folds(it.id)) it.copy(id = localId) else it },
            )
        }

        /**
         * lowercase UUID -> the id a local object is really stored under, only for the ones
         * kept in another casing. Android up to 1.2.2 kept a GeoJSON import's raw feature id
         * and iOS 1.0 exported uppercase, so those are out there. An exact lowercase twin
         * wins and never shows up in here.
         */
        fun caseAliases(storedIds: Iterable<String>, isStored: (String) -> Boolean): Map<String, String> {
            var out: HashMap<String, String>? = null
            for (id in storedIds) {
                if (id.none { it in 'A'..'F' }) continue
                val lower = SyncIdentity.canonicalUuid(id) ?: continue
                if (isStored(lower)) continue
                (out ?: HashMap<String, String>().also { out = it }).putIfAbsent(lower, id)
            }
            return out ?: emptyMap()
        }

        /** Receiver-local fixed-point hash; the authenticated payload hash stays the sender's bytes. */
        fun expectedModelHash(
            parsed: GeoJsonImporter.Result,
            localId: String,
            existingLayers: List<DrawingLayer>,
            density: Float,
        ): String? {
            val layers = (existingLayers + parsed.newLayers).distinctBy { it.id }
            val content = parsed.waypoints.singleOrNull { it.id == localId }?.let {
                GeoJsonExporter.export(listOf(it), emptyList(), layers, density = density)
            } ?: parsed.drawings.singleOrNull { it.id == localId }?.let {
                GeoJsonExporter.export(emptyList(), listOf(it), layers, density = density)
            } ?: return null
            return SyncIdentity.bytesToHex(SyncIdentity.sha256(content.toByteArray(Charsets.UTF_8)))
        }

        /** First one wins, same rule the apply step uses (plans/04 section 3). */
        fun stage(staged: MutableList<DrawingLayer>, check: V3Check) {
            val put = (check as? V3Check.Valid)?.record as? ValidatedV3.Put ?: return
            for (layer in put.parsed.newLayers) {
                if (staged.none { it.id == layer.id }) staged += layer
            }
        }
    }
}
