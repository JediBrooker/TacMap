package com.tacmap.export

import java.util.UUID

/** Object kinds that share the single mission-object UUID namespace. */
enum class ExternalImportObjectKind(val wireName: String) {
    WAYPOINT("waypoint"),
    DRAWING("drawing");

    companion object {
        fun fromWireName(value: String): ExternalImportObjectKind =
            entries.firstOrNull { it.wireName == value }
                ?: throw IllegalArgumentException("Unsupported import object kind: $value")
    }
}

internal data class ExternalImportIdentityInput(
    /** Stable within a batch. This is metadata, never an application object ID. */
    val caseKey: String,
    val kind: ExternalImportObjectKind,
    val sourceId: String?,
)

/** Parser metadata retained without changing or minting the parsed model ID. */
data class ParsedExternalImportIdentity(
    val caseKey: String,
    val kind: ExternalImportObjectKind,
    val indexInKind: Int,
    val sourceId: String?,
)

/** Parser metadata for a KML-created layer whose UUID must survive retries. */
data class ParsedExternalImportLayerIdentity(
    val caseKey: String,
    val indexInNewLayers: Int,
)

internal data class OccupiedExternalImportIdentity(
    val kind: ExternalImportObjectKind,
    val id: String,
)

internal enum class ExternalImportIdentityReason(val wireName: String) {
    PRESERVED("preserved"),
    EXISTING_SAME_KIND_COLLISION("existing_same_kind_collision"),
    EXISTING_CROSS_TYPE_COLLISION("existing_cross_type_collision"),
    INCOMING_DUPLICATE("incoming_duplicate"),
    MISSING_ID("missing_id"),
    INVALID_ID("invalid_id"),
    RETRY_REUSED("retry_reused"),
}

internal data class ResolvedExternalImportIdentity(
    val caseKey: String,
    val kind: ExternalImportObjectKind,
    val sourceId: String?,
    val resolvedId: String,
    val reason: ExternalImportIdentityReason,
)

internal data class ExternalImportIdentityResolution(
    val objects: List<ResolvedExternalImportIdentity>,
    val resolutionMap: Map<String, String>,
    val consumedRemintIds: List<String>,
)

internal data class ResolvedExternalImportPayload(
    val result: GeoJsonImporter.Result,
    val identities: ExternalImportIdentityResolution,
    val layerResolutionMap: Map<String, String> = emptyMap(),
)

/** Injected so fixture tests can prove exact remint order. */
internal fun interface ExternalImportIdFactory {
    fun newId(): String
}

/**
 * Identity policy for user-selected GeoJSON/KML only.
 *
 * The generic parsers deliberately do not call this resolver: authenticated
 * Unit Sync payload IDs must be preserved and validated, never reminted.
 */
internal class ExternalImportIdentityResolver(
    private val idFactory: ExternalImportIdFactory = ExternalImportIdFactory {
        UUID.randomUUID().toString()
    },
) {
    fun resolveParsedResult(
        parsed: GeoJsonImporter.Result,
        occupied: List<OccupiedExternalImportIdentity>,
        priorResolutionMap: Map<String, String> = emptyMap(),
        priorLayerResolutionMap: Map<String, String> = emptyMap(),
    ): ResolvedExternalImportPayload {
        val metadata = parsed.identityOrder.ifEmpty {
            parsed.waypoints.mapIndexed { index, waypoint ->
                ParsedExternalImportIdentity(
                    caseKey = "waypoint-$index",
                    kind = ExternalImportObjectKind.WAYPOINT,
                    indexInKind = index,
                    sourceId = waypoint.id.takeIf(String::isNotBlank),
                )
            } + parsed.drawings.mapIndexed { index, drawing ->
                ParsedExternalImportIdentity(
                    caseKey = "drawing-$index",
                    kind = ExternalImportObjectKind.DRAWING,
                    indexInKind = index,
                    sourceId = drawing.id.takeIf(String::isNotBlank),
                )
            }
        }
        val identities = resolve(
            incoming = metadata.map {
                ExternalImportIdentityInput(it.caseKey, it.kind, it.sourceId)
            },
            occupied = occupied,
            priorResolutionMap = priorResolutionMap,
        )
        val idsByCaseKey = identities.resolutionMap
        val resolvedWaypoints = parsed.waypoints.toMutableList()
        val resolvedDrawings = parsed.drawings.toMutableList()
        metadata.forEach { identity ->
            val resolvedId = checkNotNull(idsByCaseKey[identity.caseKey])
            when (identity.kind) {
                ExternalImportObjectKind.WAYPOINT -> {
                    val original = resolvedWaypoints.getOrNull(identity.indexInKind)
                        ?: error("Waypoint identity metadata is out of bounds")
                    resolvedWaypoints[identity.indexInKind] = original.copy(id = resolvedId)
                }
                ExternalImportObjectKind.DRAWING -> {
                    val original = resolvedDrawings.getOrNull(identity.indexInKind)
                        ?: error("Drawing identity metadata is out of bounds")
                    resolvedDrawings[identity.indexInKind] = original.copy(id = resolvedId)
                }
            }
        }
        val layerMetadata = parsed.layerIdentityOrder
        require(layerMetadata.map { it.caseKey }.toSet().size == layerMetadata.size) {
            "Import layer case keys must be unique within a batch"
        }
        val layerKeys = layerMetadata.mapTo(HashSet()) { it.caseKey }
        require(priorLayerResolutionMap.keys.all { it in layerKeys }) {
            "Retry identity map contains a layer outside this import batch"
        }
        val usedLayerIds = HashSet<String>()
        parsed.newLayers.forEachIndexed { index, layer ->
            if (layerMetadata.none { it.indexInNewLayers == index }) {
                canonicalUuid(layer.id)?.let(usedLayerIds::add)
            }
        }
        val layerResolutionMap = LinkedHashMap<String, String>()
        val oldToResolvedLayerId = HashMap<String, String>()
        layerMetadata.forEach { identity ->
            val layer = parsed.newLayers.getOrNull(identity.indexInNewLayers)
                ?: error("Layer identity metadata is out of bounds")
            val prior = priorLayerResolutionMap[identity.caseKey]?.let { raw ->
                canonicalUuid(raw)
                    ?: throw IllegalArgumentException("Retry layer identity is not a canonical UUID for ${identity.caseKey}")
            }
            val resolvedId = prior ?: nextUniqueId(usedLayerIds)
            require(usedLayerIds.add(resolvedId) || prior == null) {
                "Retry identity map assigns one layer UUID more than once"
            }
            layerResolutionMap[identity.caseKey] = resolvedId
            oldToResolvedLayerId[layer.id] = resolvedId
        }
        val resolvedLayers = parsed.newLayers.map { layer ->
            oldToResolvedLayerId[layer.id]?.let { layer.copy(id = it) } ?: layer
        }
        val layerResolvedWaypoints = resolvedWaypoints.map { waypoint ->
            oldToResolvedLayerId[waypoint.layerId]?.let { waypoint.copy(layerId = it) } ?: waypoint
        }
        val layerResolvedDrawings = resolvedDrawings.map { drawing ->
            oldToResolvedLayerId[drawing.layerId]?.let { drawing.copy(layerId = it) } ?: drawing
        }
        return ResolvedExternalImportPayload(
            result = parsed.copy(
                waypoints = layerResolvedWaypoints,
                drawings = layerResolvedDrawings,
                newLayers = resolvedLayers,
                identityOrder = metadata,
            ),
            identities = identities,
            layerResolutionMap = layerResolutionMap,
        )
    }

    fun resolve(
        incoming: List<ExternalImportIdentityInput>,
        occupied: List<OccupiedExternalImportIdentity>,
        priorResolutionMap: Map<String, String> = emptyMap(),
    ): ExternalImportIdentityResolution {
        require(incoming.map { it.caseKey }.toSet().size == incoming.size) {
            "Import case keys must be unique within a batch"
        }

        val existingById = HashMap<String, ExternalImportObjectKind>()
        occupied.forEach { objectId ->
            canonicalUuid(objectId.id)?.let { canonical ->
                val previous = existingById.putIfAbsent(canonical, objectId.kind)
                require(previous == null || previous == objectId.kind) {
                    "Existing mission data already violates the global object UUID namespace"
                }
            }
        }

        val incomingKeys = incoming.mapTo(HashSet()) { it.caseKey }
        require(priorResolutionMap.keys.all { it in incomingKeys }) {
            "Retry identity map contains an object outside this import batch"
        }

        // Retry IDs are reserved before resolving any new item. This makes a
        // partial retry independent of which store committed on the first try.
        val retryIds = LinkedHashMap<String, String>()
        val reservedRetryIds = HashSet<String>()
        priorResolutionMap.forEach { (caseKey, rawId) ->
            val canonical = canonicalUuid(rawId)
                ?: throw IllegalArgumentException("Retry identity is not a canonical UUID for $caseKey")
            require(reservedRetryIds.add(canonical)) {
                "Retry identity map assigns one object UUID more than once"
            }
            retryIds[caseKey] = canonical
        }

        val usedIds = HashSet<String>().apply {
            addAll(existingById.keys)
            addAll(reservedRetryIds)
        }
        val claimedFreshIds = HashMap<String, ExternalImportObjectKind>()
        val resolved = ArrayList<ResolvedExternalImportIdentity>(incoming.size)
        val consumed = ArrayList<String>()

        incoming.forEach { input ->
            val retryId = retryIds[input.caseKey]
            if (retryId != null) {
                resolved += ResolvedExternalImportIdentity(
                    caseKey = input.caseKey,
                    kind = input.kind,
                    sourceId = input.sourceId,
                    resolvedId = retryId,
                    reason = ExternalImportIdentityReason.RETRY_REUSED,
                )
                return@forEach
            }

            val source = canonicalUuid(input.sourceId)
            val reason = when {
                input.sourceId == null || input.sourceId.isBlank() ->
                    ExternalImportIdentityReason.MISSING_ID
                source == null -> ExternalImportIdentityReason.INVALID_ID
                existingById[source] == input.kind ->
                    ExternalImportIdentityReason.EXISTING_SAME_KIND_COLLISION
                existingById.containsKey(source) ->
                    ExternalImportIdentityReason.EXISTING_CROSS_TYPE_COLLISION
                claimedFreshIds.containsKey(source) ->
                    ExternalImportIdentityReason.INCOMING_DUPLICATE
                source in reservedRetryIds ->
                    ExternalImportIdentityReason.INCOMING_DUPLICATE
                else -> ExternalImportIdentityReason.PRESERVED
            }

            val resolvedId = if (reason == ExternalImportIdentityReason.PRESERVED) {
                checkNotNull(source).also {
                    usedIds += it
                    claimedFreshIds[it] = input.kind
                }
            } else {
                nextUniqueId(usedIds).also {
                    consumed += it
                    claimedFreshIds[it] = input.kind
                }
            }
            resolved += ResolvedExternalImportIdentity(
                caseKey = input.caseKey,
                kind = input.kind,
                sourceId = input.sourceId,
                resolvedId = resolvedId,
                reason = reason,
            )
        }

        return ExternalImportIdentityResolution(
            objects = resolved,
            resolutionMap = resolved.associate { it.caseKey to it.resolvedId },
            consumedRemintIds = consumed,
        )
    }

    private fun nextUniqueId(usedIds: MutableSet<String>): String {
        repeat(MAX_FACTORY_ATTEMPTS) {
            val canonical = canonicalUuid(idFactory.newId())
                ?: throw IllegalArgumentException("External import ID factory returned an invalid UUID")
            if (usedIds.add(canonical)) return canonical
        }
        throw IllegalStateException("External import ID factory could not produce a unique UUID")
    }

    private fun canonicalUuid(raw: String?): String? {
        val value = raw?.trim() ?: return null
        if (!CANONICAL_UUID.matches(value)) return null
        return runCatching { UUID.fromString(value).toString() }.getOrNull()
    }

    private companion object {
        const val MAX_FACTORY_ATTEMPTS = 1_024
        val CANONICAL_UUID = Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
        )
    }
}
