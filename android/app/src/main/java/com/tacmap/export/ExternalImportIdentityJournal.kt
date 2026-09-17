package com.tacmap.export

import android.content.Context
import com.tacmap.util.SafeStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import com.tacmap.drawings.DrawingFeature
import com.tacmap.waypoints.Waypoint

/**
 * Encrypted retry journal for cross-store external imports.
 *
 * The complete caseKey -> resolved UUID map is durable before either mission
 * store is touched. If one store commits and the other fails, selecting the
 * same bytes again reuses the exact IDs and each store can idempotently skip
 * objects it already has.
 */
internal class ExternalImportIdentityJournal private constructor(
    private val file: File,
) {
    constructor(context: Context) : this(File(context.filesDir, FILE_NAME))

    @Serializable
    private data class Entry(val caseKey: String, val resolvedId: String)

    @Serializable
    private data class Batch(
        val batchKey: String,
        val entries: List<Entry>,
        val layerEntries: List<Entry> = emptyList(),
        val updatedAtEpochMs: Long,
    )

    @Serializable
    private data class Document(val batches: List<Batch> = emptyList())

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private var document: Document
    private var unavailableReason: String? = null

    init {
        document = when (val loaded = SafeStore.readOrQuarantine(file, FILE_NAME) {
            json.decodeFromString<Document>(it)
        }) {
            is SafeStore.LoadResult.Loaded -> loaded.value
            SafeStore.LoadResult.Empty -> Document()
            is SafeStore.LoadResult.Corrupt -> {
                unavailableReason = "The import retry journal could not be authenticated and was preserved for recovery."
                Document()
            }
            is SafeStore.LoadResult.Locked -> {
                unavailableReason = "The import retry journal is locked. Unlock mission data and retry."
                Document()
            }
        }
    }

    @Synchronized
    fun resolveAndPersist(
        batchKey: String,
        parsed: GeoJsonImporter.Result,
        occupied: List<OccupiedExternalImportIdentity>,
        resolver: ExternalImportIdentityResolver = ExternalImportIdentityResolver(),
    ): ResolvedExternalImportPayload {
        unavailableReason?.let { throw IllegalStateException(it) }
        require(batchKey.isNotBlank()) { "Import batch key is required" }
        val priorBatch = document.batches.firstOrNull { it.batchKey == batchKey }
        val priorMap = priorBatch?.entries
            ?.associate { it.caseKey to it.resolvedId }
            .orEmpty()
        val priorLayerMap = priorBatch?.layerEntries
            ?.associate { it.caseKey to it.resolvedId }
            .orEmpty()
        val resolved = resolver.resolveParsedResult(
            parsed = parsed,
            occupied = occupied,
            priorResolutionMap = priorMap,
            priorLayerResolutionMap = priorLayerMap,
        )
        persistResolved(batchKey, resolved)
        return resolved
    }

    /**
     * Rechecks IDs against the live committed stores immediately before the
     * main-thread store commits. A resolved ID that appeared while parsing was
     * suspended is reminted and the replacement is journaled first.
     */
    @Synchronized
    fun reconcileAndPersist(
        batchKey: String,
        resolved: ResolvedExternalImportPayload,
        liveWaypoints: List<Waypoint>,
        liveDrawings: List<DrawingFeature>,
        resolver: ExternalImportIdentityResolver = ExternalImportIdentityResolver(),
    ): ResolvedExternalImportPayload {
        unavailableReason?.let { throw IllegalStateException(it) }
        val incomingByCase = resolved.result.identityOrder.associateWith { identity ->
            when (identity.kind) {
                ExternalImportObjectKind.WAYPOINT -> resolved.result.waypoints[identity.indexInKind]
                ExternalImportObjectKind.DRAWING -> resolved.result.drawings[identity.indexInKind]
            }
        }
        val liveWaypointsById = liveWaypoints.associateBy { it.id.lowercase() }
        val liveDrawingsById = liveDrawings.associateBy { it.id.lowercase() }
        val reusableObjectMap = resolved.identities.objects.mapNotNull { identity ->
            val key = identity.resolvedId.lowercase()
            val liveWaypoint = liveWaypointsById[key]
            val liveDrawing = liveDrawingsById[key]
            val incoming = incomingByCase.entries.first { it.key.caseKey == identity.caseKey }.value
            val reusable = when (identity.kind) {
                ExternalImportObjectKind.WAYPOINT ->
                    liveDrawing == null && (liveWaypoint == null ||
                        sameImportedWaypoint(liveWaypoint, incoming as Waypoint))
                ExternalImportObjectKind.DRAWING ->
                    liveWaypoint == null && (liveDrawing == null ||
                        sameImportedDrawing(liveDrawing, incoming as DrawingFeature))
            }
            if (reusable) identity.caseKey to identity.resolvedId else null
        }.toMap()
        val occupied = liveWaypoints.map {
            OccupiedExternalImportIdentity(ExternalImportObjectKind.WAYPOINT, it.id)
        } + liveDrawings.map {
            OccupiedExternalImportIdentity(ExternalImportObjectKind.DRAWING, it.id)
        }
        val reconciled = resolver.resolveParsedResult(
            parsed = resolved.result,
            occupied = occupied,
            priorResolutionMap = reusableObjectMap,
            priorLayerResolutionMap = resolved.layerResolutionMap,
        )
        persistResolved(batchKey, reconciled)
        return reconciled
    }

    private fun persistResolved(batchKey: String, resolved: ResolvedExternalImportPayload) {
        val replacement = Batch(
            batchKey = batchKey,
            entries = resolved.identities.resolutionMap.map { Entry(it.key, it.value) },
            layerEntries = resolved.layerResolutionMap.map { Entry(it.key, it.value) },
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        val candidate = Document(
            batches = (document.batches.filterNot { it.batchKey == batchKey } + replacement)
                .sortedByDescending { it.updatedAtEpochMs }
                .take(MAX_BATCHES),
        )
        SafeStore.writeAtomically(file, FILE_NAME, json.encodeToString(candidate))
        document = candidate
    }

    private fun sameImportedWaypoint(a: Waypoint, b: Waypoint): Boolean =
        a.copy(createdAt = 0L) == b.copy(createdAt = 0L)

    private fun sameImportedDrawing(a: DrawingFeature, b: DrawingFeature): Boolean =
        a.copy(createdAt = 0L) == b.copy(createdAt = 0L)

    internal companion object {
        private const val FILE_NAME = "external_import_identity.json"
        private const val MAX_BATCHES = 64

        fun batchKey(format: String, bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return "$format:${digest.joinToString("") { "%02x".format(it) }}"
        }

        fun forTests(filesDir: File) = ExternalImportIdentityJournal(File(filesDir, FILE_NAME))
    }
}
