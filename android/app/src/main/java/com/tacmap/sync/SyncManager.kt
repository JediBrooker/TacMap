package com.tacmap.sync

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.SystemClock
import android.util.Base64
import com.tacmap.drawings.DrawingDocument
import com.tacmap.drawings.DrawingFeature
import com.tacmap.export.GeoJsonExporter
import com.tacmap.export.GeoJsonImporter
import com.tacmap.waypoints.SymbolAffiliation
import com.tacmap.waypoints.SymbolEchelon
import com.tacmap.waypoints.SymbolFunction
import com.tacmap.waypoints.Waypoint
import com.tacmap.waypoints.WaypointStore
import com.tacmap.drawings.DrawingStore
import com.tacmap.settings.OpsecSettings
import com.tacmap.models.ModelMutationOrigin
import com.tacmap.util.DataKey
import com.tacmap.util.SafeStore
import com.tacmap.util.SealedEnvelope
import com.tacmap.util.DurablePreferenceCommit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

private val STRICT_SYNC_UUID = Regex(
    "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
)

internal fun canonicalLegacySyncId(raw: String): String? {
    if (!STRICT_SYNC_UUID.matches(raw)) return null
    return runCatching { UUID.fromString(raw).toString() }
        .getOrNull()
        ?.takeIf { it == raw }
}

/** Canonicalizes before the version lookup so casing can never create a second key. */
internal fun acceptedLegacySyncRecordId(
    rawId: String,
    version: Long,
    versions: Map<String, Long>,
): String? {
    val id = canonicalLegacySyncId(rawId) ?: return null
    return id.takeIf { (versions[id] ?: Long.MIN_VALUE) < version }
}

internal fun isValidLegacySyncPut(
    recordId: String,
    kind: String,
    parsed: GeoJsonImporter.Result,
): Boolean {
    val canonicalRecordId = canonicalLegacySyncId(recordId) ?: return false
    val objectId = when (kind) {
        "waypoint" -> parsed.waypoints.singleOrNull()?.id?.takeIf { parsed.drawings.isEmpty() }
        "drawing" -> parsed.drawings.singleOrNull()?.id?.takeIf { parsed.waypoints.isEmpty() }
        else -> null
    } ?: return false
    val canonicalObjectId = canonicalLegacySyncId(objectId) ?: return false
    return canonicalObjectId == canonicalRecordId && objectId == recordId
}

internal enum class V3DepartureKind { EXPLICIT, TRANSIENT, REPLACEMENT }

internal data class V3Departure(
    val actorId: String,
    val sessionDomain: String,
    val kind: V3DepartureKind = V3DepartureKind.TRANSIENT,
)

/** A v3 leave is relay-attested liveness metadata, not a peer signature. Only
 * accept it when it names the exact currently authenticated hello/session so a
 * delayed close for a superseded socket cannot erase its replacement. */
internal fun acceptedV3Departure(
    msg: JSONObject,
    activeSessions: Map<String, Pair<String, String>>,
    ownActorId: String?,
): V3Departure? {
    val actor = msg.optString("by").ifEmpty { return null }
    val session = msg.optString("sd").ifEmpty { return null }
    if (actor == ownActorId) return null
    if (SyncIdentity.urlB64Decode32(actor) == null || SyncIdentity.urlB64Decode32(session) == null) return null
    val current = activeSessions[actor] ?: return null
    val kinds = mutableListOf<V3DepartureKind>()
    for ((field, kind) in listOf(
        "explicit" to V3DepartureKind.EXPLICIT,
        "transient" to V3DepartureKind.TRANSIENT,
        "replaced" to V3DepartureKind.REPLACEMENT,
    )) {
        if (!msg.has(field)) continue
        val raw = msg.opt(field)
        if (raw !is Boolean || !raw) return null
        kinds += kind
    }
    if (kinds.size > 1) return null
    // An older relay sends no discriminator. Treat that as transient: a
    // bounded, visibly stale marker is safer than silently erasing a unit.
    val kind = kinds.singleOrNull() ?: V3DepartureKind.TRANSIENT
    return V3Departure(actor, session, kind).takeIf { current.second == session }
}

/** Replacement/transient close is transport loss, not a deliberate departure.
 * Keep its last authenticated fix visibly stale; only an authenticated explicit
 * leave removes the exact matching marker immediately. */
internal fun peerAfterV3Departure(
    peer: PresencePeer?,
    departure: V3Departure,
    nowUptimeMs: Long,
): PresencePeer? {
    if (peer == null) return null
    if (peer.sessionDomain != departure.sessionDomain) return peer
    return if (departure.kind == V3DepartureKind.EXPLICIT) null else {
        PresenceExpiryPolicy.markedStale(peer, nowUptimeMs)
    }
}

/**
 * Real-time shared-tactical-picture sync client. Connects to the E2E-blind
 * relay ([RELAY_BASE]) for a unit room derived from a join code, keeps
 * waypoints + drawings in step across the unit's devices.
 *
 * Each object is serialised as a single-feature GeoJSON doc (same cross-
 * platform schema TacMap already round-trips), encrypted with room key
 * ([SyncCrypto]) and relayed as opaque ciphertext - server never sees
 * plaintext. Layers ride along in feature properties so they reconstruct
 * on the reciever without a separate channel.
 *
 * Merge is last-write-wins on per-object Lamport version; echo suppressed
 * by tracking last serialised form we sent/recieved for each id so
 * applying a remote change doesn't bounce back out.
 *
 * NOTE: compile-verified; convergence / no-echo should be confirmed on
 * two devices against the live relay.
 */
@OptIn(FlowPreview::class)
class SyncManager(
    waypointStore: WaypointStore,
    drawingStore: DrawingStore,
    parentScope: CoroutineScope,
    context: Context,
) {
    enum class Status { OFFLINE, CONNECTING, SNAPSHOTTING, CONNECTED }

    private var waypointStoreRef: WaypointStore? = waypointStore
    private var drawingStoreRef: DrawingStore? = drawingStore
    private val waypointStore: WaypointStore get() = checkNotNull(waypointStoreRef) { "sync disposed" }
    private val drawingStore: DrawingStore get() = checkNotNull(drawingStoreRef) { "sync disposed" }
    /** Drawing widths are stored as renderer pixels on Android but travel as
     * screen-independent units. Keep one application-context density for every
     * Sync import/export boundary; never rewrite the existing persisted file. */
    private val displayDensity = context.applicationContext.resources.displayMetrics.density
    // Keep the transport independent of a Compose scope so the app-scoped
    // runtime can retain one already-authenticated v3 presence session while
    // MapScreen is removed for the mission-key lock. dispose() is still the
    // sole terminal owner of this job.
    private val managerJob = SupervisorJob()
    private val scope = CoroutineScope(
        parentScope.coroutineContext + managerJob + Dispatchers.Main.immediate
    )

    private val _status = MutableStateFlow(Status.OFFLINE)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _room = MutableStateFlow<String?>(null)
    /** Join code of active room (for display), null when not syncing. */
    val room: StateFlow<String?> = _room.asStateFlow()

    private val _roomName = MutableStateFlow("")
    /** Encrypted client-local display label for the active or pending room. */
    val roomName: StateFlow<String> = _roomName.asStateFlow()
    private var activeRoomStorageId: String? = null
    private var pendingRoomName: String = ""

    private val _peers = MutableStateFlow<Map<String, PresencePeer>>(emptyMap())
    /** Live map locations, populated only when a peer explicitly shares one. */
    val peers: StateFlow<Map<String, PresencePeer>> = _peers.asStateFlow()

    private val onlineMemberTracker = OnlineMemberTracker()
    private val _onlineMembers = MutableStateFlow<Map<String, OnlineMember>>(emptyMap())
    /** Signature-verified v3 sessions currently reported by the relay,
     * independent of location sharing. Relay-attested liveness is not cryptographic
     * proof of current connection liveness. */
    val onlineMembers: StateFlow<Map<String, OnlineMember>> = _onlineMembers.asStateFlow()

    /** Persistent, actionable failure detail for SyncDialog. Transient peer
     * update notices remain on [remoteUpdates]. Security warnings stay visible
     * until dismissed or superseded by a clean later snapshot generation. */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()
    private val issueLifecycle = SyncIssueLifecycle()
    private var activeConnectionGeneration: Long = 0

    private val _remoteUpdates = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val remoteUpdates: SharedFlow<String> = _remoteUpdates.asSharedFlow()

    private val presenceConfigLock = Any()
    @Volatile
    var presenceConfig: PresenceConfig = PresenceConfig()
        private set
    private var presenceConfigDurable = false

    /** Hook up a location supplier so sendPresence can grab the latest fix. */
    var locationProvider: (() -> Location?)? = null

    /** Installed only by [UnitSyncRuntime]. Direct test clients retain the
     * historical standalone lifecycle. */
    internal var runtimeStateChanged: (() -> Unit)? = null
    internal var backgroundTransportEnded: (() -> Unit)? = null

    private val appFilesDir: File = context.applicationContext.filesDir
    private val chatHistoryStore = TacMapChatHistoryStore(context.applicationContext)
    val chatMessages: StateFlow<List<TacMapChatMessage>> = chatHistoryStore.messages
    /** Aggregate unread metadata for chrome; no message content is exposed here. */
    val unreadChatMessageCount: StateFlow<Int> = chatHistoryStore.unreadCount
    internal val chatHistoryAvailability: StateFlow<TacMapChatHistoryAvailability> =
        chatHistoryStore.availability
    val chatHistoryIssue: StateFlow<String?> = chatHistoryStore.issue
    private val _chatRecipients = MutableStateFlow<Map<String, TacMapChatTarget.SelectedUnit>>(emptyMap())
    val chatRecipients: StateFlow<Map<String, TacMapChatTarget.SelectedUnit>> =
        _chatRecipients.asStateFlow()
    private val _chatSessionReady = MutableStateFlow(false)
    val chatSessionReady: StateFlow<Boolean> = _chatSessionReady.asStateFlow()
    private val _chatAvailabilityMessage = MutableStateFlow<String?>(
        "Join a connected v3 Unit Sync room to use TacMap Chat"
    )
    val chatAvailabilityMessage: StateFlow<String?> = _chatAvailabilityMessage.asStateFlow()
    private val prefs = context.applicationContext.getSharedPreferences("sync", Context.MODE_PRIVATE)
    private val clientId: String = prefs.getString("clientId", null)
        ?: UUID.randomUUID().toString().also { prefs.edit().putString("clientId", it).apply() }

    private val webSocketTransport = SyncWebSocketTransport()

    private var ws: SyncWebSocket? = null
    private val inboundFrameCloseGate = SyncInboundFrameCloseGate()
    private val liveReceiveBudget = SyncLiveReceiveBudget()
    private var roomKey: ByteArray? = null
    private var authToken: String? = null
    // Resolved from OPSEC settings at join time so a self-hoster's relay is
    // actually used; falls back to ours. Kept for the reconnect path.
    private var relayBase: String = RELAY_BASE
    private var wantConnected = false
    private val lifecycleGate = SyncLifecycleGate()
    private var reconnectJob: Job? = null
    private val reconnectBackoff = SyncReconnectBackoff()
    private var observeJob: Job? = null
    private var revisionJob: Job? = null
    private val modelRevisionJournal = LocalModelRevisionJournal(appFilesDir)
    private val revisionEventProcessor = LocalRevisionEventProcessor(modelRevisionJournal) {
        revisionJournalAvailable = false
        reportError(
            "Local revision history could not be saved; sync is paused. Leave and rejoin after checking available storage.",
            SyncIssueKind.SECURITY,
        )
        persistenceFailure()
    }
    private var revisionJournalAvailable = false

    // Per-device Ed25519 signing identity. Seed is sealed at rest; the public
    // key rides every presence AND every object write so peers pin it (TOFU) and
    // reject a room member impersonating an established device. One identity per
    // clientId, shared by presence + object writes. Room state cleared on leave.
    private val deviceSeedDelegate = lazy { loadOrCreateDeviceSeed() }
    private val deviceSeed: ByteArray by deviceSeedDelegate
    private val myPublicKeyDelegate = lazy { SyncSigning.publicKey(deviceSeed) }
    private val myPublicKey: String by myPublicKeyDelegate
    private val myPublicKeyRawDelegate = lazy {
        SyncIdentity.urlB64Decode(myPublicKey)
    }
    private val myPublicKeyRaw: ByteArray by myPublicKeyRawDelegate
    private val peerKeys = HashMap<String, String>()   // clientId -> pinned pubkey
    private val peerTs = HashMap<String, Long>()        // clientId -> last accepted presence ts

    private var clock: Long = 0
    private val versions = HashMap<String, Long>()        // id -> last-applied version
    private val lastContent = HashMap<String, String>()   // id -> last serialised GeoJSON (echo guard)
    private val kindById = HashMap<String, String>()       // id -> "waypoint" | "drawing"

    // v3 protocol state
    private var protocolVersion = 2
    private var v3Keys: SyncCrypto.V3RoomKeys? = null
    private var myActorId: String? = null
    private var replayState: SyncReplayState? = null
    private var sessionDomain: ByteArray? = null
    private var presenceCounter: Long = 0L
    private val activeSessions = HashMap<String, Pair<String, String>>() // actor -> (pub, sd)
    private var snapshotSeq: Long? = null
    private var snapshotSawFinalPage = false
    private var snapshotItemCount = 0
    private var snapshotInvalid = false
    private var awaitingHelloAck = false
    private var localHelloVersion: String? = null
    private var chatEphemeralKey: TacMapChatEphemeralKey? = null
    private var localChatKeyId: String? = null
    private var localChatKeyAcknowledged = false
    private var chatCounter: Long = 0L
    private var localChatAdvertFrame: String? = null
    private var chatKeyRetryJob: Job? = null
    private val chatPeerKeys = HashMap<String, TacMapChatPeerKey>()
    private val pendingChat = HashMap<String, TacMapChatAck>()
    private var snapshotAggregateBytes = 0L
    private val snapshotWireIds = HashSet<String>()
    private val pendingSnapshot = ArrayList<ValidatedV3>()
    private val snapshotConfirmedLocalDeletes = HashMap<String, String>()
    private val forcedLocalDiff = HashSet<String>()
    private val forcedLegacyDeletes = HashMap<String, LegacyDeleteRecovery>()
    private var resolvingPendingModel = false
    private val outboundDeliveries = OutboundDeliveryTracker()
    private val deliveryRetryJobs = HashMap<String, Job>()
    private val v2SnapshotGate = V2SnapshotGate(MAX_SNAPSHOT_ITEMS, MAX_SNAPSHOT_AGGREGATE_BYTES)
    private var v2SnapshotTimeoutJob: Job? = null
    private var v2SnapshotFailureGeneration: Long? = null
    private var v3HandshakeTimeoutJob: Job? = null
    private var v3HandshakeFailureGeneration: Long? = null
    private var lastGoodLocalPresenceFix: PresenceLocationFix? = null
    private var localPresenceCandidateCluster: PresenceCandidateCluster? = null
    private val remotePresenceCandidateClusters = HashMap<String, PresenceCandidateCluster>()

    private sealed interface ValidatedV3 {
        val mutation: SyncReplayState.AuthenticatedMutation

        data class Put(
            override val mutation: SyncReplayState.AuthenticatedMutation,
            val parsed: GeoJsonImporter.Result,
            val localId: String,
            val expectedModelHash: String,
        ) : ValidatedV3

        data class Delete(
            override val mutation: SyncReplayState.AuthenticatedMutation,
            val localId: String?,
        ) : ValidatedV3
    }

    private var presenceJob: Job? = null
    private var stalenessSweepJob: Job? = null
    private val presenceCadence = UnitSyncPresenceCadence()
    private val foregroundPresenceLiveness = ForegroundPresenceLiveness()
    private val foregroundGpsFixRequester = ForegroundGpsFixRequester(context.applicationContext)
    @Volatile private var backgroundPresenceOnly = false
    @Volatile private var awaitingForegroundStores = false

    init {
        loadPresenceConfig()
        migrateLegacyLocalStoresAfterUnlock()
        revisionJournalAvailable = modelRevisionJournal.load()
        startModelRevisionObservation()
    }

    // ----- Public API -----

    internal val isDisposed: Boolean get() = lifecycleGate.isDisposed
    internal val isBackgroundPresenceOnly: Boolean get() = backgroundPresenceOnly

    internal fun canArmBackgroundLocationService(): Boolean =
        !lifecycleGate.isDisposed && protocolVersion == 3 &&
            _room.value?.startsWith("3:") == true && presenceConfig.shareLocation

    /**
     * Strip the manager down before MainActivity locks the mission data key.
     * The already-authenticated v3 socket and the material required to sign one
     * location frame remain; mission stores, plaintext model baselines, chat,
     * observers, retries, and all inbound processing do not.
     */
    internal fun enterBackgroundPresenceOnly(
        interval: com.tacmap.settings.BackgroundUnitSyncInterval,
    ): Boolean {
        if (lifecycleGate.isDisposed || protocolVersion != 3 ||
            _status.value != Status.CONNECTED || !presenceConfig.shareLocation ||
            waypointStoreRef == null || drawingStoreRef == null
        ) return false

        val transitionNow = SystemClock.elapsedRealtimeNanos()
        val transitionLocation = locationProvider?.invoke()?.takeIf { location ->
            location.provider == android.location.LocationManager.GPS_PROVIDER &&
                ForegroundPresenceLiveness.isGenuinelyRecentFix(
                    candidateFixElapsedRealtimeNanos = location.elapsedRealtimeNanos,
                    nowElapsedRealtimeNanos = transitionNow,
                )
        }
        // This gate is deliberately first. Production callbacks and lifecycle
        // work share the main dispatcher, so no later inbound frame can reach a
        // store once teardown begins.
        backgroundPresenceOnly = true
        awaitingForegroundStores = false
        detachMissionStateForKeyLock()
        presenceCadence.reset()
        // The previous foreground frame expires after 45 seconds. Bridge to the
        // selected screen-off retention only with a genuinely recent GPS fix;
        // otherwise wait for the service's next real GPS callback.
        transitionLocation?.let { location ->
            sendBackgroundPresence(location, interval)
        }
        return true
    }

    /** Preserve an opted-in v3 room through a transient CONNECTING/snapshot or
     * unavailable-location pause, but keep no socket or background egress. */
    internal fun suspendUntilForegroundStores(): Boolean {
        if (lifecycleGate.isDisposed || protocolVersion != 3 ||
            _room.value?.startsWith("3:") != true
        ) return false

        val storesAttached = waypointStoreRef != null && drawingStoreRef != null
        val alreadyRestricted = backgroundPresenceOnly && awaitingForegroundStores &&
            waypointStoreRef == null && drawingStoreRef == null
        if (!storesAttached && !alreadyRestricted) return false

        backgroundPresenceOnly = true
        awaitingForegroundStores = true
        if (storesAttached) detachMissionStateForKeyLock()
        presenceCadence.reset()
        closeSocketForLifecycleTransition("waiting for foreground unlock")
        return true
    }

    private fun detachMissionStateForKeyLock() {
        reconnectJob?.cancel(); reconnectJob = null
        observeJob?.cancel(); observeJob = null
        revisionJob?.cancel(); revisionJob = null
        presenceJob?.cancel(); presenceJob = null
        stalenessSweepJob?.cancel(); stalenessSweepJob = null
        v2SnapshotTimeoutJob?.cancel(); v2SnapshotTimeoutJob = null
        v3HandshakeTimeoutJob?.cancel(); v3HandshakeTimeoutJob = null
        cancelForegroundPresenceRefresh()
        clearOutboundDeliveries(markForReconciliation = true)
        resetSnapshot()
        versions.clear()
        lastContent.clear()
        kindById.clear()
        forcedLegacyDeletes.clear()
        snapshotConfirmedLocalDeletes.clear()
        clearChatTransport(markPendingFailed = true)
        chatHistoryStore.lock()
        _peers.value = emptyMap()
        _onlineMembers.value = onlineMemberTracker.clear()
        activeSessions.clear()
        remotePresenceCandidateClusters.clear()
        locationProvider = null
        waypointStoreRef = null
        drawingStoreRef = null
    }

    /** Stop background sends immediately on foreground return, but wait for the
     * mission key and fresh stores before reconnecting and accepting a snapshot. */
    internal fun prepareForForegroundUnlock() {
        if (!backgroundPresenceOnly && !awaitingForegroundStores) return
        awaitingForegroundStores = true
        backgroundPresenceOnly = true
        closeSocketForLifecycleTransition("foreground unlock")
    }

    /** Rebind the newly unlocked stores and require a fresh authenticated v3
     * snapshot before model sync resumes. */
    internal fun attachForegroundStores(
        newWaypointStore: WaypointStore,
        newDrawingStore: DrawingStore,
        newLocationProvider: () -> Location?,
    ): Boolean {
        if (lifecycleGate.isDisposed) return false
        waypointStoreRef = newWaypointStore
        drawingStoreRef = newDrawingStore
        locationProvider = newLocationProvider
        backgroundPresenceOnly = false
        awaitingForegroundStores = false
        presenceCadence.reset()
        migrateLegacyLocalStoresAfterUnlock()

        revisionJournalAvailable = modelRevisionJournal.load()
        startModelRevisionObservation()
        val roomId = activeRoomStorageId
        if (roomId != null && _room.value != null) {
            if (protocolVersion == 3) chatHistoryStore.open(roomId)
            startObserving()
            startPresenceBroadcast()
            startStalenessSweep()
            wantConnected = true
            connect(roomId)
        }
        return true
    }

    /** Close the session that advertised an extended location lifetime. If the
     * app is unlocked, rotate to a new foreground session; while locked, never
     * reconnect or touch mission state. */
    internal fun revokeBackgroundLocationEligibility(reconnectIfForeground: Boolean) {
        if (lifecycleGate.isDisposed || protocolVersion != 3 || _room.value == null) return
        val roomId = activeRoomStorageId
        closeSocketForLifecycleTransition("background location disabled")
        presenceCadence.reset()
        if (reconnectIfForeground && !backgroundPresenceOnly && !awaitingForegroundStores &&
            waypointStoreRef != null && drawingStoreRef != null && roomId != null
        ) {
            wantConnected = true
            connect(roomId)
        }
    }

    /** Called only with a fresh callback from the foreground location service. */
    internal fun sendBackgroundPresence(
        location: Location,
        interval: com.tacmap.settings.BackgroundUnitSyncInterval,
        nowElapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos(),
    ): Boolean {
        if (!backgroundPresenceOnly || awaitingForegroundStores ||
            protocolVersion != 3 || _status.value != Status.CONNECTED ||
            !presenceConfig.shareLocation
        ) return false
        return sendPresenceAtCadence(
            location = location,
            isBackground = true,
            backgroundInterval = interval,
            nowElapsedRealtimeNanos = nowElapsedRealtimeNanos,
        )
    }

    private data class PresenceConfigUpdateResult(
        val succeeded: Boolean,
        val changed: Boolean,
    )

    /** Persist the complete encrypted presence/consent record before making it
     * observable to location senders or UI. A failed disk commit retains the
     * prior durable config and surfaces a persistent security issue.
     *
     * The runtime callback must run after releasing [presenceConfigLock].
     * [UnitSyncRuntime] owns a separate lifecycle monitor and calls back into
     * this manager while holding it; invoking the callback under this lock
     * would invert that ordering and could deadlock location-sharing changes. */
    fun updatePresenceConfig(value: PresenceConfig): Boolean =
        transactPresenceConfig { value }

    private fun transactPresenceConfig(
        candidate: (PresenceConfig) -> PresenceConfig?,
    ): Boolean {
        val result = synchronized(presenceConfigLock) {
            val current = presenceConfig
            val value = candidate(current)
                ?: return@synchronized PresenceConfigUpdateResult(
                    succeeded = true,
                    changed = false,
                )
            if (value == current && presenceConfigDurable) {
                return@synchronized PresenceConfigUpdateResult(
                    succeeded = true,
                    changed = false,
                )
            }
            if (!persistPresenceConfig(value)) {
                return@synchronized PresenceConfigUpdateResult(
                    succeeded = false,
                    changed = false,
                )
            }
            presenceConfig = value
            presenceConfigDurable = true
            PresenceConfigUpdateResult(
                succeeded = true,
                changed = value != current,
            )
        }
        if (!result.succeeded) {
            reportError(
                "Could not save Unit Sync identity/location sharing. The previous setting remains active; check available storage and try again.",
                SyncIssueKind.SECURITY,
            )
        } else if (result.changed) {
            runtimeStateChanged?.invoke()
        }
        return result.succeeded
    }

    fun setRoomName(value: String) {
        val bounded = boundRoomName(value)
        _roomName.value = bounded
        val storageId = activeRoomStorageId
        if (storageId == null) {
            pendingRoomName = bounded
        } else {
            persistRoomName(storageId, bounded.trim())
        }
    }

    private fun activateRoomName(storageId: String, requestedName: String) {
        activeRoomStorageId = storageId
        val requested = boundRoomName(requestedName).trim()
        val resolved = requested.ifBlank { presenceConfig.roomNamesById[storageId].orEmpty() }
        pendingRoomName = ""
        _roomName.value = resolved
        if (requested.isNotBlank()) persistRoomName(storageId, requested)
    }

    private fun persistRoomName(storageId: String, name: String) {
        transactPresenceConfig { current ->
            val updated = updatedRoomNamesById(current.roomNamesById, storageId, name)
            if (updated === current.roomNamesById || updated == current.roomNamesById) {
                null
            } else {
                current.copy(roomNamesById = updated)
            }
        }
    }

    fun join(joinCode: String) {
        if (lifecycleGate.isDisposed) return
        val code = joinCode.trim()
        if (code.isEmpty()) return
        if (!code.startsWith("3:") && !code.startsWith("2:")) {
            reportError("Join code must start with 3:. Legacy rooms require an explicit 2: prefix.")
            return
        }
        if (SyncCrypto.isJoinCodeTooWeak(code)) {
            reportError("Join code is too short to be safe. Generate a new strong room code.")
            return
        }
        val configuredRelay = validatedRelayBaseForRuntime(
            OpsecSettings.shared?.relayUrl?.value ?: RELAY_BASE
        )
        if (configuredRelay == null) {
            reportError(
                "The configured Unit Sync relay is unsafe or invalid. Correct it in Privacy & OPSEC.",
                SyncIssueKind.SECURITY,
            )
            return
        }
        val requestedRoomName = pendingRoomName
        leave()
        pendingRoomName = requestedRoomName
        _roomName.value = requestedRoomName
        if (runCatching { deviceSeed; myPublicKey }.isFailure) {
            reportError(
                "Sync signing identity is locked or damaged. Unlock mission data, then try joining again.",
                SyncIssueKind.SECURITY,
            )
            return
        }
        // Hold the validated origin for reconnects. connect() revalidates it
        // before every bounded WebSocket is created.
        relayBase = configuredRelay

        if (code.startsWith("3:")) {
            val setup = runCatching {
                // Resolving the signing identity can fail while the at-rest key
                // is locked or if the sealed seed is corrupt. Never rotate it.
                val pubRaw = myPublicKeyRaw
                val keys = SyncCrypto.deriveRoomV3(code.removePrefix("3:"))
                val actor = SyncIdentity.actorId(keys.roomIdRaw, pubRaw)
                val replay = SyncReplayState(keys.roomId, appFilesDir)
                check(replay.load(actor, myPublicKey)) { "replay state unavailable" }
                Triple(keys, replay, actor)
            }.getOrElse {
                _status.value = Status.OFFLINE
                reportError(
                    "Sync identity or rollback state is locked or damaged. Unlock mission data, then try joining again.",
                    SyncIssueKind.SECURITY,
                )
                return
            }
            protocolVersion = 3
            v3Keys = setup.first
            roomKey = setup.first.roomKey
            authToken = setup.first.authToken
            replayState = setup.second
            myActorId = setup.third
            chatHistoryStore.open(setup.first.roomId)
            activateRoomName(setup.first.roomId, requestedRoomName)
            _room.value = code
            wantConnected = true
            connect(setup.first.roomId)
        } else {
            // v2 protocol: unchanged
            protocolVersion = 2
            chatHistoryStore.close()
            clearChatTransport(markPendingFailed = true)
            val keys = SyncCrypto.deriveRoom(code.removePrefix("2:"))
            roomKey = keys.roomKey
            authToken = keys.authToken
            activateRoomName(keys.roomId, requestedRoomName)
            _room.value = code
            wantConnected = true
            connect(keys.roomId)
        }
        startObserving()
        startPresenceBroadcast()
        startStalenessSweep()
        runtimeStateChanged?.invoke()
    }

    fun leave() {
        backgroundPresenceOnly = false
        awaitingForegroundStores = false
        presenceCadence.reset()
        reconnectBackoff.reset()
        wantConnected = false
        reconnectJob?.cancel(); reconnectJob = null
        observeJob?.cancel(); observeJob = null
        presenceJob?.cancel(); presenceJob = null
        stalenessSweepJob?.cancel(); stalenessSweepJob = null
        cancelForegroundPresenceRefresh()
        val leavingSocket = ws
        if (leavingSocket != null) sendExplicitLeaveV3(leavingSocket)
        if (leavingSocket?.close(1000, "leave") == false) leavingSocket.cancel()
        ws = null
        clearChatTransport(markPendingFailed = true)
        chatHistoryStore.close()
        clearRoomSecrets()
        authToken = null
        activeRoomStorageId = null
        pendingRoomName = ""
        _roomName.value = ""
        _room.value = null
        _status.value = Status.OFFLINE
        _peers.value = emptyMap()
        _onlineMembers.value = onlineMemberTracker.clear()
        versions.clear(); lastContent.clear(); kindById.clear()
        forcedLocalDiff.clear(); resolvingPendingModel = false
        forcedLegacyDeletes.clear()
        clearOutboundDeliveries(markForReconciliation = false)
        v2SnapshotTimeoutJob?.cancel(); v2SnapshotTimeoutJob = null
        v2SnapshotGate.cancel()
        v2SnapshotFailureGeneration = null
        v3HandshakeTimeoutJob?.cancel(); v3HandshakeTimeoutJob = null
        v3HandshakeFailureGeneration = null
        peerKeys.clear(); peerTs.clear()
        lastGoodLocalPresenceFix = null
        localPresenceCandidateCluster = null
        remotePresenceCandidateClusters.clear()
        // v3 state: clear transport-session fields but NOT replayState (durable)
        myActorId = null
        presenceCounter = 0L
        activeSessions.clear()
        remotePresenceCandidateClusters.clear()
        awaitingHelloAck = false
        localHelloVersion = null
        resetSnapshot()
        replayState = null
        protocolVersion = 2
        refreshChatAvailability()
        runtimeStateChanged?.invoke()
    }

    fun acknowledgeLastError() {
        _lastError.value = issueLifecycle.dismiss()?.message
    }

    internal fun reportBackgroundLocationIssue(message: String) {
        reportError(message)
    }

    /** Terminal teardown for the composition/key lifetime. Idempotent and
     * synchronous: after this returns no socket, observer, reconnect or
     * presence work can run, and in-memory room/signing secrets are zeroed. */
    fun dispose() {
        backgroundPresenceOnly = false
        awaitingForegroundStores = false
        presenceCadence.reset()
        val secretBuffers = buildList<ByteArray?> {
            add(roomKey)
            add(sessionDomain)
            v3Keys?.let { add(it.roomIdRaw); add(it.roomKey); add(it.metadataKey) }
            if (deviceSeedDelegate.isInitialized()) add(deviceSeed)
            if (myPublicKeyRawDelegate.isInitialized()) add(myPublicKeyRaw)
        }
        lifecycleGate.dispose(secretBuffers) {
            wantConnected = false
            managerJob.cancel()
            reconnectJob?.cancel(); reconnectJob = null
            observeJob?.cancel(); observeJob = null
            revisionJob?.cancel(); revisionJob = null
            presenceJob?.cancel(); presenceJob = null
            stalenessSweepJob?.cancel(); stalenessSweepJob = null
            cancelForegroundPresenceRefresh()
            v3HandshakeTimeoutJob?.cancel(); v3HandshakeTimeoutJob = null
            ws?.close(1000, "dispose")
            ws?.cancel()
            ws = null
            clearChatTransport(markPendingFailed = true)
            chatHistoryStore.lock()
            webSocketTransport.shutdown()
            clearRoomStateAfterDispose()
        }
    }

    private fun clearRoomSecrets() {
        roomKey?.fill(0)
        sessionDomain?.fill(0)
        v3Keys?.let {
            it.roomIdRaw.fill(0)
            it.roomKey.fill(0)
            it.metadataKey.fill(0)
        }
        roomKey = null
        sessionDomain = null
        v3Keys = null
    }

    /** Clear every websocket-scoped chat secret and route snapshot. */
    private fun clearChatTransport(markPendingFailed: Boolean) {
        chatKeyRetryJob?.cancel()
        chatKeyRetryJob = null
        localChatAdvertFrame = null
        chatEphemeralKey?.clear()
        chatEphemeralKey = null
        localChatKeyId = null
        localChatKeyAcknowledged = false
        chatCounter = 0L
        chatPeerKeys.values.forEach { peer ->
            peer.x25519PublicKey.fill(0)
            peer.signingPublicKey.fill(0)
        }
        chatPeerKeys.clear()
        _chatRecipients.value = emptyMap()
        if (markPendingFailed) {
            pendingChat.keys.toList().forEach { messageId ->
                chatHistoryStore.updateDelivery(
                    messageId,
                    TacMapChatDeliveryState.FAILED,
                    "disconnected",
                )
            }
        }
        pendingChat.clear()
        refreshChatAvailability()
    }

    private fun removeChatPeer(actorId: String, sessionDomain: String? = null) {
        val current = chatPeerKeys[actorId] ?: return
        if (sessionDomain != null && current.sessionDomain != sessionDomain) return
        chatPeerKeys.remove(actorId)
        current.x25519PublicKey.fill(0)
        current.signingPublicKey.fill(0)
        publishChatRecipients()
    }

    private fun chatDisplayName(actorId: String): String =
        onlineMembers.value[actorId]?.displayName
            ?.let(TacMapChatPayload::boundedDisplayName)
            ?.takeIf { it.isNotBlank() }
            ?: "Unknown unit"

    private fun publishChatRecipients() {
        _chatRecipients.value = chatPeerKeys.mapValues { (actorId, peer) ->
            peer.copy(displayName = chatDisplayName(actorId)).immutableTarget()
        }
    }

    private fun refreshChatAvailability() {
        val issue = when {
            protocolVersion != 3 || _room.value == null ->
                "Join a v3 Unit Sync room to use TacMap Chat"
            chatHistoryStore.availability.value == TacMapChatHistoryAvailability.LOCKED ->
                "Unlock mission data to use TacMap Chat"
            chatHistoryStore.availability.value == TacMapChatHistoryAvailability.CORRUPT ->
                chatHistoryStore.issue.value ?: "Encrypted chat history is unavailable"
            chatHistoryStore.availability.value == TacMapChatHistoryAvailability.UNAVAILABLE ->
                chatHistoryStore.issue.value ?: "Encrypted chat history could not be saved"
            chatHistoryStore.availability.value != TacMapChatHistoryAvailability.READY ->
                "Secure chat history is unavailable"
            _status.value != Status.CONNECTED -> "TacMap Chat is waiting for Unit Sync"
            !localChatKeyAcknowledged -> "Secure chat is still starting"
            else -> null
        }
        _chatAvailabilityMessage.value = issue
        _chatSessionReady.value = issue == null
    }

    private fun clearRoomStateAfterDispose() {
        clearRoomSecrets()
        authToken = null
        relayBase = RELAY_BASE
        locationProvider = null
        _room.value = null
        _status.value = Status.OFFLINE
        _peers.value = emptyMap()
        _onlineMembers.value = onlineMemberTracker.clear()
        versions.clear(); lastContent.clear(); kindById.clear()
        forcedLocalDiff.clear(); resolvingPendingModel = false
        forcedLegacyDeletes.clear()
        clearOutboundDeliveries(markForReconciliation = false)
        v2SnapshotTimeoutJob?.cancel(); v2SnapshotTimeoutJob = null
        v2SnapshotGate.cancel()
        v2SnapshotFailureGeneration = null
        v3HandshakeTimeoutJob?.cancel(); v3HandshakeTimeoutJob = null
        v3HandshakeFailureGeneration = null
        cancelForegroundPresenceRefresh()
        peerKeys.clear(); peerTs.clear()
        myActorId = null
        replayState = null
        presenceCounter = 0L
        activeSessions.clear()
        remotePresenceCandidateClusters.clear()
        awaitingHelloAck = false
        localHelloVersion = null
        resetSnapshot()
        revisionJournalAvailable = false
        waypointStoreRef = null
        drawingStoreRef = null
        backgroundPresenceOnly = false
        awaitingForegroundStores = false
        protocolVersion = 2
        refreshChatAvailability()
    }

    private fun closeSocketForLifecycleTransition(reason: String) {
        reconnectJob?.cancel(); reconnectJob = null
        v3HandshakeTimeoutJob?.cancel(); v3HandshakeTimeoutJob = null
        cancelForegroundPresenceRefresh()
        val socket = ws
        ws = null
        socket?.close(1000, reason)
        socket?.cancel()
        _status.value = Status.OFFLINE
        clearChatTransport(markPendingFailed = true)
        clearOutboundDeliveries(markForReconciliation = true)
        sessionDomain?.fill(0)
        sessionDomain = null
        presenceCounter = 0L
        activeSessions.clear()
        remotePresenceCandidateClusters.clear()
        _onlineMembers.value = onlineMemberTracker.clear()
        markPeersStale()
        awaitingHelloAck = false
        localHelloVersion = null
        resetSnapshot()
        refreshChatAvailability()
    }

    private fun shouldReconnectInForeground(): Boolean =
        wantConnected && !backgroundPresenceOnly && !awaitingForegroundStores &&
            waypointStoreRef != null && drawingStoreRef != null && !lifecycleGate.isDisposed

    private fun markPeersStale(nowUptimeMs: Long = System.nanoTime() / 1_000_000L) {
        if (_peers.value.isEmpty()) return
        _peers.value = _peers.value.mapValues { (_, peer) ->
            PresenceExpiryPolicy.markedStale(peer, nowUptimeMs)
        }
    }

    /** The WebSocket transport invokes listeners on its own threads; all protocol state enters
     * the manager scope through this socket+generation fence. */
    private fun dispatchSocketCallback(
        socket: SyncWebSocket,
        connectionGeneration: Long,
        onComplete: () -> Unit = {},
        block: () -> Unit,
    ) {
        val job = scope.launch {
            lifecycleGate.runIfActive callback@{
                if (!isCurrentSocketCallback(
                        connectionGeneration,
                        activeConnectionGeneration,
                        ws === socket,
                    )
                ) {
                    return@callback
                }
                block()
            }
        }
        // invokeOnCompletion also runs when disposal cancels this job before
        // its body starts, so the reader thread can never wait indefinitely.
        job.invokeOnCompletion { onComplete() }
    }

    private fun handleSocketEnded(
        socket: SyncWebSocket,
        roomId: String,
        connectionGeneration: Long,
        detail: String?,
    ) {
        if (!isCurrentSocketCallback(
                connectionGeneration,
                activeConnectionGeneration,
                ws === socket,
            )
        ) return
        ws = null
        _status.value = Status.OFFLINE
        clearChatTransport(markPendingFailed = true)
        activeSessions.clear()
        remotePresenceCandidateClusters.clear()
        _onlineMembers.value = onlineMemberTracker.clear()
        markPeersStale()
        snapshotConfirmedLocalDeletes.clear()
        v2SnapshotTimeoutJob?.cancel(); v2SnapshotTimeoutJob = null
        v3HandshakeTimeoutJob?.cancel(); v3HandshakeTimeoutJob = null
        cancelForegroundPresenceRefresh()
        v2SnapshotGate.cancel()
        clearOutboundDeliveries(markForReconciliation = true)
        if (shouldReconnectInForeground()) {
            if (v2SnapshotFailureGeneration != connectionGeneration &&
                v3HandshakeFailureGeneration != connectionGeneration
            ) {
                val message = detail?.takeIf { it.isNotBlank() }?.let {
                    "Unit Sync connection failed: $it. Check the relay or network; reconnecting automatically."
                } ?: "Unit Sync disconnected. Check the relay or network; reconnecting automatically."
                reportError(message)
            }
            reconnectJob?.cancel()
            val delayMs = reconnectBackoff.nextDelayMs()
            reconnectJob = scope.launch {
                kotlinx.coroutines.delay(delayMs)
                if (activeConnectionGeneration == connectionGeneration &&
                    shouldReconnectInForeground()
                ) connect(roomId)
            }
        } else if (backgroundPresenceOnly || awaitingForegroundStores) {
            backgroundTransportEnded?.invoke()
        }
    }

    // ----- Connection -----

    private fun connect(roomId: String) {
        if (lifecycleGate.isDisposed || backgroundPresenceOnly || awaitingForegroundStores ||
            waypointStoreRef == null || drawingStoreRef == null
        ) return
        val base = validatedRelayBaseForRuntime(relayBase)
        if (base == null) {
            wantConnected = false
            _status.value = Status.OFFLINE
            reportError(
                "The configured Unit Sync relay is unsafe or invalid. Correct it in Privacy & OPSEC.",
                SyncIssueKind.SECURITY,
            )
            return
        }
        relayBase = base
        v3HandshakeTimeoutJob?.cancel(); v3HandshakeTimeoutJob = null
        cancelForegroundPresenceRefresh()
        v2SnapshotTimeoutJob?.cancel(); v2SnapshotTimeoutJob = null
        v2SnapshotGate.cancel()
        clearOutboundDeliveries(markForReconciliation = true)
        val connectionGeneration = issueLifecycle.beginConnection()
        activeConnectionGeneration = connectionGeneration
        forcedLegacyDeletes.replaceAll { _, recovery ->
            recovery.copy(snapshotGeneration = connectionGeneration)
        }
        _status.value = Status.CONNECTING
        if (protocolVersion == 3) {
            clearChatTransport(markPendingFailed = true)
            sessionDomain?.fill(0)
            sessionDomain = SyncIdentity.generateSessionDomain()
            presenceCounter = 0L
            activeSessions.clear()
            remotePresenceCandidateClusters.clear()
            _onlineMembers.value = onlineMemberTracker.clear()
            awaitingHelloAck = false
            localHelloVersion = null
            resetSnapshot()
            snapshotConfirmedLocalDeletes.clear()
            refreshChatAvailability()
        } else {
            // v2 relays deployed before delivery acks ignore `rid`. Never carry
            // an optimistic baseline across reconnect: rebuild it from the
            // snapshot or resend the current model with a fresh version.
            lastContent.clear()
            kindById.clear()
            versions.clear()
        }
        val path = if (protocolVersion == 3) "v3/room/" else "room/"
        val url = "$base/$path$roomId"
        val headers = buildMap {
            authToken?.let { put("Authorization", "Bearer $it") }
            if (protocolVersion == 3) {
                put("X-Protocol", "3")
                put("X-Room-Id", roomId)
            }
        }
        lifecycleGate.runIfActive {
        ws = webSocketTransport.newWebSocket(url, headers, object : SyncWebSocketListener {
            override fun onOpen(webSocket: SyncWebSocket) {
                dispatchSocketCallback(webSocket, connectionGeneration) {
                    // v3 is not allowed to publish until a complete authenticated
                    // snapshot fence has been applied.
                    if (protocolVersion == 2) {
                        v2SnapshotGate.start(webSocket, connectionGeneration)
                        scheduleV2SnapshotTimeout(webSocket, connectionGeneration)
                    } else {
                        scheduleV3HandshakeTimeout(webSocket, connectionGeneration)
                    }
                }
            }
            override fun onTextMessage(
                webSocket: SyncWebSocket,
                text: String,
                consumed: () -> Unit,
            ) {
                dispatchSocketCallback(webSocket, connectionGeneration, consumed) {
                    handleMessage(text, webSocket, connectionGeneration)
                }
            }
            override fun onBinaryMessage(
                webSocket: SyncWebSocket,
                bytes: ByteArray,
                consumed: () -> Unit,
            ) {
                dispatchSocketCallback(webSocket, connectionGeneration, consumed) {
                    val rejection = SyncInboundFramePolicy.inspectBinary(bytes.size)
                        as SyncInboundFrameDecision.Reject
                    rejectInboundFrame(
                        webSocket,
                        connectionGeneration,
                        rejection.reason,
                    )
                }
            }
            override fun onClosed(webSocket: SyncWebSocket, code: Int, reason: String) {
                dispatchSocketCallback(webSocket, connectionGeneration) {
                    handleSocketEnded(webSocket, roomId, connectionGeneration, null)
                }
            }
            override fun onFailure(webSocket: SyncWebSocket, failure: Throwable) {
                dispatchSocketCallback(webSocket, connectionGeneration) {
                    val detail = failure.message?.takeIf { it.isNotBlank() }
                        ?: failure.javaClass.simpleName
                    handleSocketEnded(webSocket, roomId, connectionGeneration, detail)
                }
            }
        })
        }
    }

    private fun sendFrame(frame: String): Boolean =
        lifecycleGate.sendIfActive { ws?.send(frame) == true }

    private fun newDeliveryRequestId(): String = UUID.randomUUID().toString().replace("-", "")

    private fun contentHash(content: String): String =
        SyncIdentity.bytesToHex(SyncIdentity.sha256(content.toByteArray(Charsets.UTF_8)))

    private fun ciphertextHash(ciphertext: String): String =
        SyncIdentity.urlB64(SyncIdentity.sha256(ciphertext.toByteArray(Charsets.UTF_8)))

    private fun queueDelivery(delivery: PendingOutboundDelivery) {
        outboundDeliveries.register(delivery)?.let { prior ->
            deliveryRetryJobs.remove(prior.requestId)?.cancel()
        }
        sendFrame(delivery.frame)
        scheduleDeliveryRetry(delivery)
    }

    private fun scheduleDeliveryRetry(delivery: PendingOutboundDelivery) {
        deliveryRetryJobs.remove(delivery.requestId)?.cancel()
        val delayMs = (1_000L shl (delivery.attempts - 1).coerceIn(0, 3))
        deliveryRetryJobs[delivery.requestId] = scope.launch {
            kotlinx.coroutines.delay(delayMs)
            val retry = outboundDeliveries.nextAttempt(
                delivery.requestId,
                delivery.connectionGeneration,
                delivery.sessionDomain,
            )
            if (retry == null) {
                if (outboundDeliveries.pending(delivery.localId)?.requestId == delivery.requestId) {
                    reportError(
                        "A Unit Sync change is still unconfirmed after bounded retries. Reconnecting to reconcile it; the local edit remains saved.",
                    )
                    ws?.cancel()
                }
                return@launch
            }
            sendFrame(retry.frame)
            scheduleDeliveryRetry(retry)
        }
    }

    private fun clearOutboundDeliveries(markForReconciliation: Boolean) {
        deliveryRetryJobs.values.forEach(Job::cancel)
        deliveryRetryJobs.clear()
        val pending = outboundDeliveries.all()
        val unconfirmed = outboundDeliveries.resetForReconnect()
        if (markForReconciliation) {
            forcedLocalDiff += unconfirmed.filterNot { it.startsWith("wire:") }
            if (protocolVersion == 2) pending
                .filter { it.desiredContent == null && !it.localId.startsWith("wire:") }
                .forEach {
                    forcedLegacyDeletes[it.localId] = LegacyDeleteRecovery.from(
                        it,
                        snapshotGeneration = -1,
                    )
                }
        }
    }

    private fun reportError(
        message: String,
        kind: SyncIssueKind = SyncIssueKind.CONNECTION,
        generation: Long = activeConnectionGeneration,
    ) {
        _lastError.value = issueLifecycle.report(message, kind, generation)?.message
        _remoteUpdates.tryEmit(message)
    }

    /** Cold-upgrade cleanup for inactive per-room Chat/replay files. A locked
     * auth-bound key is an expected retry state and causes no filesystem work. */
    private fun migrateLegacyLocalStoresAfterUnlock() {
        try {
            SyncLocalStore.migrateAllLegacyStores(appFilesDir)
            _lastError.value = issueLifecycle.clearPersistentSecurity()?.message
        } catch (_: DataKey.LockedException) {
            return
        } catch (failure: Throwable) {
            val message =
                "Saved Unit Sync metadata could not be migrated to private filenames. " +
                    "Check available storage, then unlock mission data and try again."
            _lastError.value = issueLifecycle.reportPersistentSecurity(
                message,
                activeConnectionGeneration,
            )?.message
            _remoteUpdates.tryEmit(message)
        }
    }

    // ----- Outbound: observe local stores, diff, send -----

    private fun startObserving() {
        observeJob?.cancel()
        observeJob = scope.launch {
            combine(waypointStore.committedWaypoints, drawingStore.committedDocument) { wps, doc -> wps to doc }
                .debounce(250)
                .collect { (wps, doc) -> syncLocalState(wps, doc) }
        }
    }

    /** Lifetime observer: revisions continue across disconnect and Leave. */
    private fun startModelRevisionObservation() {
        revisionJob?.cancel()
        revisionJob = scope.launch {
            merge(waypointStore.mutations, drawingStore.mutations)
                .collect { event ->
                    if (!revisionJournalAvailable) { persistenceFailure(); return@collect }
                    if (!revisionEventProcessor.process(event)) return@collect
                }
        }
    }

    private fun syncLocalState(wps: List<Waypoint>, doc: DrawingDocument) {
        lifecycleGate.runIfActive {
            if (_status.value != Status.CONNECTED) return@runIfActive
            val current = HashMap<String, Pair<String, String>>() // id -> (kind, content)
            for (wp in wps) current[wp.id] = "waypoint" to GeoJsonExporter.export(
                listOf(wp), emptyList(), doc.layers, density = displayDensity,
            )
            for (f in doc.features) current[f.id] = "drawing" to GeoJsonExporter.export(
                emptyList(), listOf(f), doc.layers, density = displayDensity,
            )

            if (protocolVersion == 3) {
                syncLocalStateV3(current)
            } else {
                syncLocalStateV2(current)
            }
        }
    }

    private fun syncLocalStateV2(current: HashMap<String, Pair<String, String>>) {
        for ((id, kc) in current) {
            val (kind, content) = kc
            if (forcedLegacyDeletes.containsKey(id)) {
                // A genuine local recreation supersedes an older unconfirmed
                // v2 delete. Snapshot puts are intercepted before model apply.
                forcedLegacyDeletes.remove(id)
            }
            if (lastContent[id] == content && id !in forcedLocalDiff) continue
            val contentHash = contentHash(content)
            val pending = outboundDeliveries.pending(id)
            if (pending?.desiredContentHash == contentHash && pending.kind == kind) continue
            clock += 1
            versions[id] = clock
            sendPut(id, clock, kind, content)
        }
        val gone = (lastContent.keys + forcedLocalDiff + forcedLegacyDeletes.keys + outboundDeliveries.all().map { it.localId })
            .filter { it !in current && !it.startsWith("wire:") }
            .distinct()
        for (id in gone) {
            val pending = outboundDeliveries.pending(id)
            if (pending != null && pending.desiredContentHash == null && pending.kind == "del") continue
            clock += 1
            sendDel(id, clock)
        }
    }

    private fun syncLocalStateV3(current: HashMap<String, Pair<String, String>>) {
        val keys = v3Keys ?: return
        val actor = myActorId ?: return
        val replay = replayState ?: return
        if (!revisionJournalAvailable || resolvingPendingModel || replay.hasPendingModelApplications()) return

        for ((id, kc) in current) {
            val (kind, content) = kc
            val contentHash = SyncIdentity.bytesToHex(SyncIdentity.sha256(content.toByteArray(Charsets.UTF_8)))
            val wireId = runCatching {
                SyncIdentity.wireObjectId(keys.metadataKey, SyncIdentity.uuidToBytes(id))
            }.getOrNull() ?: continue
            if (lastContent[id] == content && id !in forcedLocalDiff) continue
            val pending = outboundDeliveries.pending(id)
            if (pending?.desiredContentHash == contentHash && pending.kind == kind) continue
            val vs = replay.recoverableLocalPut(wireId, actor, myPublicKey, contentHash)
                ?: replay.reserveLocalPut(wireId, actor, myPublicKey, contentHash)
                ?: return persistenceFailure()
            sendPutV3(id, wireId, vs, kind, content)
        }
        val gone = (lastContent.keys + forcedLocalDiff + outboundDeliveries.all().map { it.localId })
            .filter { it !in current && !it.startsWith("wire:") }.distinct()
        for (id in gone) {
            val pending = outboundDeliveries.pending(id)
            if (pending != null && pending.desiredContentHash == null && pending.kind == "del") continue
            val wireId = runCatching {
                SyncIdentity.wireObjectId(keys.metadataKey, SyncIdentity.uuidToBytes(id))
            }.getOrNull() ?: continue
            val vs = replay.reserveLocalDelete(wireId, actor, myPublicKey)
                ?: return persistenceFailure()
            sendDelV3(id, wireId, vs)
        }
    }

    private fun sendPut(id: String, v: Long, kind: String, content: String) {
        val key = roomKey ?: return
        // Sign the write, then seal {content, pub, sig} together. The signature
        // rides INSIDE the sealed blob so the relay stays E2E-blind to device
        // identity; a receiver proves room-key possession by opening it and
        // device authorship by verifying the sig against the pinned key.
        val sig = SyncSigning.sign(deviceSeed, SyncSigning.objectMessage(id, v, kind, clientId, content))
        val inner = JSONObject().apply { put("c", content); put("pub", myPublicKey); put("sig", sig) }
        val aad = SyncCrypto.aad(id, v, kind)
        val ct = SyncCrypto.encodeBase64(SyncCrypto.seal(key, inner.toString().toByteArray(Charsets.UTF_8), aad))
        val rid = newDeliveryRequestId()
        val frame = JSONObject().apply {
            put("t", "put"); put("id", id); put("v", v); put("by", clientId); put("kind", kind); put("ct", ct)
            put("rid", rid)
        }.toString()
        queueDelivery(PendingOutboundDelivery(
            id, rid, activeConnectionGeneration, clientId, null, id, v.toString(), kind,
            ciphertextHash(ct), contentHash(content), content, frame,
        ))
    }

    private fun sendDel(id: String, v: Long) {
        val key = roomKey ?: return
        val sig = SyncSigning.sign(deviceSeed, SyncSigning.objectMessage(id, v, "del", clientId, ""))
        val inner = JSONObject().apply { put("pub", myPublicKey); put("sig", sig) }
        val aad = SyncCrypto.aad(id, v, "del")
        val ct = SyncCrypto.encodeBase64(SyncCrypto.seal(key, inner.toString().toByteArray(Charsets.UTF_8), aad))
        val rid = newDeliveryRequestId()
        val frame = JSONObject().apply {
            put("t", "del"); put("id", id); put("v", v); put("by", clientId); put("ct", ct)
            put("rid", rid)
        }.toString()
        queueDelivery(PendingOutboundDelivery(
            id, rid, activeConnectionGeneration, clientId, null, id, v.toString(), "del",
            ciphertextHash(ct), null, null, frame,
        ))
    }

    // -- v3 outbound --

    private fun sendHelloV3(): Boolean {
        val keys = v3Keys ?: return false
        val actor = myActorId ?: return false
        val sd = sessionDomain ?: return false
        val epoch = replayState?.reserveHelloEpoch(actor, myPublicKey) ?: return false
        val vs = "$epoch:$actor"
        localHelloVersion = vs
        val preimage = SyncIdentity.buildPreimage(
            SyncIdentity.DOMAIN_HELLO, keys.roomIdRaw, actor, sd,
            epoch, "", "hello", SyncIdentity.sha256(myPublicKeyRaw)
        )
        val sig = SyncSigning.sign(deviceSeed, preimage)
        return sendFrame(JSONObject().apply {
            put("t", "hello")
            put("by", actor)
            put("pub", myPublicKey)
            put("sd", SyncIdentity.urlB64(sd))
            put("vs", vs)
            put("sig", sig)
        }.toString())
    }

    private fun sendExplicitLeaveV3(socket: SyncWebSocket): Boolean {
        if (protocolVersion != 3 || awaitingHelloAck || _status.value != Status.CONNECTED) return false
        val keys = v3Keys ?: return false
        val actor = myActorId ?: return false
        val sd = sessionDomain ?: return false
        val helloVersion = localHelloVersion ?: return false
        val preimage = SyncIdentity.explicitLeavePreimage(
            keys.roomIdRaw,
            actor,
            sd,
            helloVersion,
        ) ?: return false
        val signature = SyncSigning.sign(deviceSeed, preimage)
        return socket.send(JSONObject().apply {
            put("t", "leave")
            put("lv", SyncIdentity.EXPLICIT_LEAVE_VERSION)
            put("by", actor)
            put("sd", SyncIdentity.urlB64(sd))
            put("vs", helloVersion)
            put("sig", signature)
        }.toString())
    }

    private fun startChatSessionV3() {
        if (_status.value != Status.CONNECTED || protocolVersion != 3 ||
            chatHistoryStore.availability.value != TacMapChatHistoryAvailability.READY
        ) {
            refreshChatAvailability()
            return
        }
        val keys = v3Keys ?: return
        val actor = myActorId ?: return
        val sd = sessionDomain ?: return
        chatEphemeralKey?.clear()
        val ephemeral = TacMapChatEphemeralKey.generate()
        val kid = runCatching {
            TacMapChatCrypto.chatKeyId(keys.roomIdRaw, actor, sd, ephemeral.publicKeyRaw)
        }.getOrElse {
            ephemeral.clear()
            refreshChatAvailability()
            return
        }
        val preimage = TacMapChatCrypto.chatKeyPreimage(
            keys.roomIdRaw,
            actor,
            sd,
            ephemeral.publicKeyRaw,
            kid,
        )
        val signature = SyncSigning.sign(deviceSeed, preimage)
        chatEphemeralKey = ephemeral
        localChatKeyId = kid
        localChatKeyAcknowledged = false
        chatCounter = 0L
        val frame = JSONObject().apply {
            put("t", "chat-key")
            put("cv", 1)
            put("by", actor)
            put("sd", SyncIdentity.urlB64(sd))
            put("kx", SyncIdentity.urlB64(ephemeral.publicKeyRaw))
            put("kid", kid)
            put("sig", signature)
        }.toString()
        localChatAdvertFrame = frame
        val sent = sendFrame(frame)
        if (!sent) {
            ephemeral.clear()
            chatEphemeralKey = null
            localChatKeyId = null
            localChatAdvertFrame = null
        } else {
            scheduleChatKeyAckTimeout(frame, actor, SyncIdentity.urlB64(sd), kid)
        }
        refreshChatAvailability()
    }

    private fun scheduleChatKeyAckTimeout(
        exactFrame: String,
        actorId: String,
        sessionDomain: String,
        chatKeyId: String,
    ) {
        chatKeyRetryJob?.cancel()
        chatKeyRetryJob = scope.launch {
            repeat(CHAT_KEY_MAX_ATTEMPTS - 1) {
                kotlinx.coroutines.delay(CHAT_KEY_RETRY_DELAY_MS)
                if (localChatKeyAcknowledged || localChatAdvertFrame != exactFrame ||
                    myActorId != actorId || this@SyncManager.sessionDomain?.let(SyncIdentity::urlB64) != sessionDomain ||
                    localChatKeyId != chatKeyId || _status.value != Status.CONNECTED
                ) return@launch
                sendFrame(exactFrame)
            }
            kotlinx.coroutines.delay(CHAT_KEY_RETRY_DELAY_MS)
            if (!localChatKeyAcknowledged && localChatAdvertFrame == exactFrame &&
                myActorId == actorId &&
                this@SyncManager.sessionDomain?.let(SyncIdentity::urlB64) == sessionDomain &&
                localChatKeyId == chatKeyId
            ) {
                chatKeyRetryJob = null
                clearChatTransport(markPendingFailed = true)
                _chatAvailabilityMessage.value =
                    "This relay does not confirm TacMap Chat support"
                _chatSessionReady.value = false
            }
        }
    }

    fun chatTargetFor(actorId: String): TacMapChatTarget.SelectedUnit? =
        _chatRecipients.value[actorId]

    fun chatHistory(target: TacMapChatTarget): List<TacMapChatMessage> =
        chatHistoryStore.history(target)

    fun unreadChatMessageCount(target: TacMapChatTarget): Int =
        chatHistoryStore.unreadCount(target)

    fun markChatMessagesRead(target: TacMapChatTarget): Boolean =
        chatHistoryStore.markRead(target)

    fun chatSendBlockReason(target: TacMapChatTarget): String? {
        chatHistoryStore.issue.value?.let { issue ->
            if (chatHistoryStore.availability.value != TacMapChatHistoryAvailability.READY) return issue
        }
        val gate = TacMapChatTargetGate.evaluate(
            target = target,
            connectedV3 = protocolVersion == 3 && _status.value == Status.CONNECTED,
            localChatKeyAcknowledged = localChatKeyAcknowledged,
            peerKeys = chatPeerKeys,
        )
        if (gate is TacMapChatSendGate.Blocked) return gate.reason
        if (target === TacMapChatTarget.EntireRoom && chatPeerKeys.isEmpty()) {
            return "No chat-ready units are available"
        }
        return null
    }

    /** Encrypts and routes one plain-text/report payload. No attachment or location is inferred. */
    fun sendChat(
        target: TacMapChatTarget,
        kind: TacMapChatContentKind,
        body: String,
    ): TacMapChatSendResult {
        chatSendBlockReason(target)?.let { return TacMapChatSendResult.Blocked(it) }
        if (body.isBlank()) return TacMapChatSendResult.Blocked("Enter a message")
        if (body.toByteArray(Charsets.UTF_8).size > TacMapChatPayload.MAX_BODY_UTF8_BYTES) {
            return TacMapChatSendResult.Blocked("Message is longer than 4096 UTF-8 bytes")
        }
        val keys = v3Keys ?: return TacMapChatSendResult.Blocked("Secure room is unavailable")
        val actor = myActorId ?: return TacMapChatSendResult.Blocked("Secure identity is unavailable")
        val sessionRaw = sessionDomain ?: return TacMapChatSendResult.Blocked("Secure session is unavailable")
        val sessionText = SyncIdentity.urlB64(sessionRaw)
        val fromKid = localChatKeyId
            ?: return TacMapChatSendResult.Blocked("Secure chat is still starting")
        val ephemeral = chatEphemeralKey
            ?: return TacMapChatSendResult.Blocked("Secure chat is still starting")
        if (chatCounter >= VersionStamp.MAX_COUNTER) {
            clearChatTransport(markPendingFailed = true)
            return TacMapChatSendResult.Blocked("Secure chat session must reconnect")
        }

        val peer = (TacMapChatTargetGate.evaluate(
            target,
            connectedV3 = true,
            localChatKeyAcknowledged = true,
            peerKeys = chatPeerKeys,
        ) as? TacMapChatSendGate.Ready)?.peer
        val counter = chatCounter + 1L
        val counterHex = VersionStamp.counterHex16(counter)
        val versionStamp = "$counterHex:$actor"
        val messageId = TacMapChatIds.newMessageId()
        val scope = if (target === TacMapChatTarget.EntireRoom) {
            TacMapChatScope.ROOM
        } else {
            TacMapChatScope.DIRECT
        }
        val selected = target as? TacMapChatTarget.SelectedUnit
        val fields = TacMapChatHeaderFields(
            scope = scope,
            roomIdRaw = keys.roomIdRaw,
            senderActorId = actor,
            senderSessionDomainRaw = sessionRaw,
            counterHex = counterHex,
            messageId = messageId,
            senderChatKeyId = fromKid,
            recipientActorId = selected?.actorId,
            recipientSessionDomain = selected?.sessionDomain,
            recipientChatKeyId = selected?.chatKeyId,
        )
        val header = runCatching { TacMapChatCrypto.header(fields) }.getOrElse {
            return TacMapChatSendResult.Blocked("Selected unit is no longer available")
        }
        val payload = TacMapChatPayload(
            pv = TacMapChatPayload.VERSION,
            kind = kind,
            body = body,
            createdAt = System.currentTimeMillis(),
            replyTo = null,
        )
        val plaintext = TacMapChatPayloadCodec.encode(payload)
            ?: return TacMapChatSendResult.Blocked("Message could not be encoded safely")
        val messageKey = if (scope == TacMapChatScope.ROOM) {
            TacMapChatCrypto.roomChatKey(keys.roomKey)
        } else {
            val currentPeer = peer
                ?: return TacMapChatSendResult.Blocked("Selected unit is no longer available")
            TacMapChatCrypto.directChatKey(
                ephemeral,
                currentPeer.x25519PublicKey,
                keys.roomIdRaw,
                header,
            ) ?: return TacMapChatSendResult.Blocked("Selected unit's secure key is invalid")
        }
        val sealed = try {
            TacMapChatCrypto.seal(messageKey, plaintext, header)
        } finally {
            messageKey.fill(0)
            plaintext.fill(0)
        } ?: return TacMapChatSendResult.Blocked("Message could not be encrypted")
        val signature = SyncSigning.sign(
            deviceSeed,
            TacMapChatCrypto.signaturePreimage(header, sealed),
        )
        val ciphertext = TacMapChatCrypto.encodeStandardBase64(sealed)
        val frame = JSONObject().apply {
            put("t", "chat")
            put("cv", 1)
            put("scope", if (scope == TacMapChatScope.ROOM) "room" else "direct")
            put("by", actor)
            put("sd", sessionText)
            put("vs", versionStamp)
            put("mid", messageId)
            put("fromKid", fromKid)
            if (selected != null) {
                put("to", selected.actorId)
                put("toSd", selected.sessionDomain)
                put("toKid", selected.chatKeyId)
            }
            put("ct", ciphertext)
            put("sig", signature)
        }.toString()
        val senderName = TacMapChatPayload.boundedDisplayName(presenceConfig.callsign)
            .ifBlank { "This device" }
        val localMessage = TacMapChatMessage(
            id = messageId,
            roomId = keys.roomId,
            scope = scope,
            senderActorId = actor,
            senderName = senderName,
            recipientActorId = selected?.actorId,
            recipientName = selected?.displayName,
            kind = kind,
            body = body,
            sentAtMilliseconds = payload.createdAt,
            isOutgoing = true,
            deliveryState = TacMapChatDeliveryState.SENT,
        )
        if (!chatHistoryStore.append(localMessage)) {
            refreshChatAvailability()
            return TacMapChatSendResult.Blocked(
                chatHistoryStore.issue.value ?: "Encrypted chat history could not be saved"
            )
        }
        chatCounter = counter
        val expectedAck = TacMapChatAck(
            scope = scope,
            actorId = actor,
            sessionDomain = sessionText,
            versionStamp = versionStamp,
            messageId = messageId,
            fromChatKeyId = fromKid,
            recipientActorId = selected?.actorId,
            recipientSessionDomain = selected?.sessionDomain,
            recipientChatKeyId = selected?.chatKeyId,
        )
        pendingChat[messageId] = expectedAck
        if (!sendFrame(frame)) {
            pendingChat.remove(messageId)
            chatHistoryStore.updateDelivery(messageId, TacMapChatDeliveryState.FAILED, "send_failed")
            refreshChatAvailability()
            return TacMapChatSendResult.Blocked("Message could not be sent")
        }
        return TacMapChatSendResult.Sent(messageId)
    }

    private fun sendPutV3(localId: String, wireId: String, vs: VersionStamp, kind: String, content: String): Boolean {
        val key = roomKey ?: return false
        val keys = v3Keys ?: return false
        val actor = myActorId ?: return false
        val sd = sessionDomain ?: return false
        val payloadHash = SyncIdentity.sha256(content.toByteArray(Charsets.UTF_8))
        val preimage = SyncIdentity.buildPreimage(
            SyncIdentity.DOMAIN_PUT, keys.roomIdRaw, actor, sd,
            VersionStamp.counterHex16(vs.counter), wireId, kind, payloadHash)
        val sig = SyncSigning.sign(deviceSeed, preimage)
        val inner = JSONObject().apply { put("c", content); put("sig", sig) }
        val aad = SyncCrypto.aadV3(wireId, vs.encode(), kind)
        val ct = SyncCrypto.encodeBase64(SyncCrypto.seal(key, inner.toString().toByteArray(Charsets.UTF_8), aad))
        val rid = newDeliveryRequestId()
        val session = SyncIdentity.urlB64(sd)
        val frame = JSONObject().apply {
            put("t", "put"); put("id", wireId); put("vs", vs.encode())
            put("by", actor); put("kind", kind); put("ct", ct); put("pub", myPublicKey)
            put("sd", session); put("rid", rid)
        }.toString()
        queueDelivery(PendingOutboundDelivery(
            localId, rid, activeConnectionGeneration, actor, session, wireId, vs.encode(), kind,
            ciphertextHash(ct), contentHash(content), content, frame,
        ))
        return true
    }

    private fun sendDelV3(localId: String, wireId: String, vs: VersionStamp): Boolean {
        val key = roomKey ?: return false
        val keys = v3Keys ?: return false
        val actor = myActorId ?: return false
        val sd = sessionDomain ?: return false
        val payloadHash = SyncIdentity.sha256(ByteArray(0))
        val preimage = SyncIdentity.buildPreimage(
            SyncIdentity.DOMAIN_DELETE, keys.roomIdRaw, actor, sd,
            VersionStamp.counterHex16(vs.counter), wireId, "del", payloadHash)
        val sig = SyncSigning.sign(deviceSeed, preimage)
        val inner = JSONObject().apply { put("sig", sig) }
        val aad = SyncCrypto.aadV3(wireId, vs.encode(), "del")
        val ct = SyncCrypto.encodeBase64(SyncCrypto.seal(key, inner.toString().toByteArray(Charsets.UTF_8), aad))
        val rid = newDeliveryRequestId()
        val session = SyncIdentity.urlB64(sd)
        val frame = JSONObject().apply {
            put("t", "del"); put("id", wireId); put("vs", vs.encode())
            put("by", actor); put("kind", "del"); put("ct", ct); put("pub", myPublicKey)
            put("sd", session); put("rid", rid)
        }.toString()
        queueDelivery(PendingOutboundDelivery(
            localId, rid, activeConnectionGeneration, actor, session, wireId, vs.encode(), "del",
            ciphertextHash(ct), null, null, frame,
        ))
        return true
    }

    // ----- Inbound -----

    companion object {
        /** Default E2E-blind relay base URL (path added per-protocol-version). */
        const val RELAY_BASE = "wss://tacmap-sync.christianbrooker.workers.dev"
        internal fun validatedRelayBaseForRuntime(
            value: String,
            allowInsecureLoopback: Boolean = com.tacmap.BuildConfig.DEBUG,
        ): String? = when (
            val result = RelayEndpointPolicy.normalize(value, allowInsecureLoopback)
        ) {
            is RelayEndpointPolicy.Result.Valid -> result.endpoint
            is RelayEndpointPolicy.Result.Invalid -> null
        }
        /** SharedPreferences key holding the sealed presence-config blob. */
        private const val KEY_PRESENCE = "config_sealed"
        /** AEAD label binding that blob so it can't be opened as another pref. */
        private const val PRESENCE_LABEL = "sync/presenceConfig"
        /** Sealed per-device Ed25519 signing seed + its AEAD label. */
        private const val KEY_DEVICE_SEED = "device_seed"
        private const val DEVICE_SEED_LABEL = "sync/deviceSeed"

        private const val MAX_BASE64_BYTES = 1_048_576        // 1 MiB encoded ct
        private const val MAX_SNAPSHOT_ITEMS = 10_000
        private const val MAX_SNAPSHOT_AGGREGATE_BYTES = 54_525_952L
        internal const val V2_SNAPSHOT_TIMEOUT_MS = 10_000L
        internal const val V3_HANDSHAKE_PROGRESS_TIMEOUT_MS = 20_000L
        private const val CHAT_KEY_RETRY_DELAY_MS = 2_000L
        private const val CHAT_KEY_MAX_ATTEMPTS = 4
        private const val MAX_VERSION = 1_000_000_000_000L   // matches relay MAX_V
    }

    private fun handleMessage(text: String, socket: SyncWebSocket, connectionGeneration: Long) {
        lifecycleGate.runIfActive {
            if (ws !== socket || activeConnectionGeneration != connectionGeneration) return@runIfActive
            val decision = SyncInboundFramePolicy.inspectText(text)
            if (decision is SyncInboundFrameDecision.Reject) {
                rejectInboundFrame(socket, connectionGeneration, decision.reason)
                return@runIfActive
            }
            val frameBytes = (decision as SyncInboundFrameDecision.Accept).byteCount
            if (!liveReceiveBudget.admit(
                newGeneration = connectionGeneration,
                byteCount = frameBytes,
                newPhase = if (_status.value == Status.CONNECTED) {
                    SyncLiveReceiveBudget.Phase.LIVE
                } else {
                    SyncLiveReceiveBudget.Phase.INITIAL
                },
                nowMs = SystemClock.elapsedRealtime(),
            )) {
                rejectInboundFrame(
                    socket,
                    connectionGeneration,
                    SyncInboundFrameRejection.RATE_LIMITED,
                )
                return@runIfActive
            }
            // The locked/background session is egress-only. Do not even parse
            // relay traffic while mission stores and replay persistence are unavailable.
            if (backgroundPresenceOnly || awaitingForegroundStores) return@runIfActive
            try {
                val msg = JSONObject(text)
                if (protocolVersion == 3) {
                    handleMessageV3(
                        msg,
                        frameBytes,
                        socket,
                        connectionGeneration,
                    )
                } else {
                    handleMessageV2(
                        msg,
                        frameBytes,
                        socket,
                        connectionGeneration,
                    )
                }
            } catch (_: Throwable) {
                // Silently drop -- don't log frame content (SEC-019).
            }
        }
    }

    private fun rejectInboundFrame(
        socket: SyncWebSocket,
        connectionGeneration: Long,
        rejection: SyncInboundFrameRejection,
    ) {
        if (ws !== socket || activeConnectionGeneration != connectionGeneration) return
        if (!inboundFrameCloseGate.claimClose(connectionGeneration)) return
        val code = when (rejection) {
            SyncInboundFrameRejection.OVERSIZED -> 1009
            SyncInboundFrameRejection.BINARY -> 1003
            SyncInboundFrameRejection.RATE_LIMITED -> 1008
        }
        if (!socket.close(code, "Unsupported relay frame")) socket.cancel()
    }

    private fun handleMessageV2(
        msg: JSONObject,
        frameBytes: Int,
        socket: SyncWebSocket,
        connectionGeneration: Long,
    ) {
        if (_status.value != Status.CONNECTED) {
            when (val event = v2SnapshotGate.accept(socket, connectionGeneration, msg, frameBytes)) {
                V2SnapshotGateEvent.Ignored, V2SnapshotGateEvent.PageAccepted -> Unit
                V2SnapshotGateEvent.Began -> _status.value = Status.SNAPSHOTTING
                is V2SnapshotGateEvent.Rejected ->
                    failV2Snapshot(socket, connectionGeneration, event.reason)
                is V2SnapshotGateEvent.Completed -> {
                    v2SnapshotTimeoutJob?.cancel(); v2SnapshotTimeoutJob = null
                    for (record in event.batch.records) {
                        applyRecord(record, snapshotGeneration = connectionGeneration)
                    }
                    for (member in event.batch.members) applyPresence(member)
                    if (ws !== socket || activeConnectionGeneration != connectionGeneration) return
                    v2SnapshotFailureGeneration = null
                    reconnectBackoff.reset()
                    _status.value = Status.CONNECTED
                    _lastError.value = issueLifecycle.connectionSucceeded(
                        atGeneration = connectionGeneration,
                        verifiedCleanSnapshot = false,
                    )?.message
                    syncLocalState(
                        waypointStore.committedWaypoints.value,
                        drawingStore.committedDocument.value,
                    )
                }
            }
            return
        }
        when (msg.optString("t")) {
            "put" -> applyRecord(msg)
            "del" -> applyDelete(msg)
            "loc" -> applyPresence(msg)
            "op-ack" -> applyDeliveryAck(msg, v3 = false)
            "op-nack" -> applyDeliveryNack(msg, v3 = false)
            "leave" -> {
                val peerId = msg.optString("clientId").ifEmpty { return }
                _peers.value = _peers.value - peerId
            }
        }
    }

    private fun handleMessageV3(
        msg: JSONObject,
        frameBytes: Int,
        socket: SyncWebSocket,
        connectionGeneration: Long,
    ) {
        when (msg.optString("t")) {
            "snapshot-begin" -> {
                if (_status.value != Status.CONNECTING || snapshotSeq != null) return failSnapshot()
                val seq = strictNonNegativeLong(msg, "seq") ?: return failSnapshot()
                snapshotSeq = seq
                snapshotSawFinalPage = false
                snapshotItemCount = 0
                snapshotInvalid = false
                snapshotAggregateBytes = frameBytes.toLong()
                snapshotWireIds.clear()
                pendingSnapshot.clear()
                _status.value = Status.SNAPSHOTTING
                scheduleV3HandshakeTimeout(socket, connectionGeneration)
                val replay = replayState ?: return failSnapshot()
                if (replay.lastSnapshotSeq >= 0 && seq < replay.lastSnapshotSeq) {
                    android.util.Log.w("SyncManager", "Sync relay supplied an older snapshot fence")
                    reportError(
                        "Sync rollback warning: the relay snapshot is older than state already seen on this device. Verify the room code and relay before continuing.",
                        SyncIssueKind.SECURITY,
                    )
                }
            }
            "snapshot" -> {
                snapshotAggregateBytes += frameBytes
                if (snapshotAggregateBytes > MAX_SNAPSHOT_AGGREGATE_BYTES) return failSnapshot()
                if (_status.value != Status.SNAPSHOTTING || snapshotSeq == null || snapshotSawFinalPage) {
                    return failSnapshot()
                }
                val items = msg.optJSONArray("items") ?: JSONArray()
                snapshotItemCount += items.length()
                if (snapshotItemCount > MAX_SNAPSHOT_ITEMS) return failSnapshot()
                for (i in 0 until items.length()) {
                    val rec = items.optJSONObject(i)
                    if (rec == null) {
                        snapshotInvalid = true
                        continue
                    }
                    val validated = validateRecordV3(rec)
                    if (validated == null || !snapshotWireIds.add(validated.mutation.wireObjectId)) {
                        snapshotInvalid = true
                    } else pendingSnapshot += validated
                }
                val more = msg.opt("more") as? Boolean ?: return failSnapshot()
                if (!more) snapshotSawFinalPage = true
                scheduleV3HandshakeTimeout(socket, connectionGeneration)
            }
            "snapshot-end" -> {
                snapshotAggregateBytes += frameBytes
                if (snapshotAggregateBytes > MAX_SNAPSHOT_AGGREGATE_BYTES) return failSnapshot()
                val expected = snapshotSeq ?: return failSnapshot()
                val seq = strictNonNegativeLong(msg, "seq") ?: return failSnapshot()
                if (_status.value != Status.SNAPSHOTTING || seq != expected || !snapshotSawFinalPage || snapshotInvalid) {
                    return failSnapshot()
                }
                val replay = replayState ?: return failSnapshot()
                val remoteMutations = pendingSnapshot.map { validated ->
                    SyncReplayState.RemoteMutation(
                        validated.mutation, modelContentHash(validated.localModelId()), validated.localModelId(),
                        modelRevisionJournal.generation(validated.localModelId()), validated.expectedModelHash())
                }
                if (!replay.commitRemoteSnapshot(remoteMutations, seq)) {
                    return persistenceFailure()
                }
                val actor = myActorId
                snapshotConfirmedLocalDeletes.clear()
                if (actor != null) pendingSnapshot.filterIsInstance<ValidatedV3.Delete>()
                    .filter { it.mutation.stamp.actorId == actor }
                    .forEach { snapshotConfirmedLocalDeletes[it.mutation.wireObjectId] = it.mutation.stamp.encode() }
                resolvingPendingModel = true
                try {
                    for ((index, validated) in pendingSnapshot.withIndex()) {
                        if (!resolvePendingModelApplication(validated, remoteMutations[index].priorModelHash)) {
                            return persistenceFailure()
                        }
                    }
                    if (!resolveUnmatchedPendingModelApplications()) return persistenceFailure()
                } finally {
                    resolvingPendingModel = false
                }
                resetSnapshot()
                awaitingHelloAck = true
                if (!sendHelloV3()) {
                    awaitingHelloAck = false
                    return failConnection()
                }
                scheduleV3HandshakeTimeout(socket, connectionGeneration)
            }
            "hello-ack" -> applyHelloAckV3(msg)
            "chat-key-ack" -> applyChatKeyAckV3(msg)
            "chat-key-nack" -> applyChatKeyNackV3(msg)
            "chat-ack" -> applyChatAckV3(msg)
            "chat-nack" -> applyChatNackV3(msg)
            "op-ack" -> applyDeliveryAck(msg, v3 = true)
            "op-nack" -> applyDeliveryNack(msg, v3 = true)
            // Active peer hello/presence frames follow snapshot-end. Accept
            // authenticated inbound traffic while our own hello ack is pending,
            // but keep local observers/presence outbound gated until CONNECTED.
            "hello" -> if (acceptsLiveInboundV3()) applyHelloV3(msg)
            "chat-key" -> if (acceptsLiveInboundV3()) applyChatKeyV3(msg)
            "chat" -> if (acceptsLiveInboundV3()) applyChatV3(msg)
            "put" -> if (acceptsLiveInboundV3()) applyLiveRecordV3(msg)
            "del" -> if (acceptsLiveInboundV3()) applyLiveRecordV3(msg)
            "loc" -> if (acceptsLiveInboundV3()) applyPresenceV3(msg)
            "leave" -> {
                val departure = acceptedV3Departure(msg, activeSessions, myActorId) ?: return
                activeSessions.remove(departure.actorId)
                remotePresenceCandidateClusters.remove(departure.actorId)
                removeChatPeer(departure.actorId, departure.sessionDomain)
                _onlineMembers.value = onlineMemberTracker.remove(
                    departure.actorId,
                    departure.sessionDomain,
                )
                val peer = _peers.value[departure.actorId]
                val retained = peerAfterV3Departure(
                    peer,
                    departure,
                    System.nanoTime() / 1_000_000L,
                )
                if (retained !== peer) {
                    _peers.value = if (retained == null) {
                        _peers.value - departure.actorId
                    } else {
                        _peers.value + (departure.actorId to retained)
                    }
                }
            }
        }
    }

    private fun applyDeliveryAck(msg: JSONObject, v3: Boolean) {
        val ack = parseDeliveryAckFrame(msg, v3) ?: return
        val delivered = outboundDeliveries.acknowledge(ack) ?: return
        deliveryRetryJobs.remove(delivered.requestId)?.cancel()

        if (delivered.desiredContent != null) {
            if (reexport(delivered.localId) == delivered.desiredContent) {
                lastContent[delivered.localId] = delivered.desiredContent
                kindById[delivered.localId] = delivered.kind
                forcedLocalDiff.remove(delivered.localId)
                forcedLegacyDeletes.remove(delivered.localId)
            } else {
                forcedLocalDiff += delivered.localId
            }
        } else if (!localObjectExists(delivered.localId)) {
            lastContent.remove(delivered.localId)
            kindById.remove(delivered.localId)
            forcedLocalDiff.remove(delivered.localId)
            forcedLegacyDeletes.remove(delivered.localId)
        } else {
            forcedLocalDiff += delivered.localId
        }
        if (_status.value == Status.CONNECTED) {
            syncLocalState(waypointStore.committedWaypoints.value, drawingStore.committedDocument.value)
        }
    }

    private fun applyDeliveryNack(msg: JSONObject, v3: Boolean) {
        val nack = parseDeliveryNackFrame(msg, v3) ?: return
        val code = nack.code
        val retryable = nack.retryable
        val pending = outboundDeliveries.reject(nack) ?: return
        val message = when (code) {
            "quota" -> "The Unit Sync room is full, so this saved local change was not uploaded. Remove room content or use a new room, then reconnect."
            "storage" -> "The Unit Sync relay could not durably save this change. It remains saved locally and will be retried."
            "stale", "not-found", "counter-window" -> "The Unit Sync relay rejected an out-of-date change. The local edit remains saved; reconnecting will reconcile it from a verified snapshot."
            "session-replaced", "session-mismatch", "hello-required" -> "This Unit Sync session can no longer confirm changes. The local edit remains saved; reconnecting with a fresh authenticated session."
            else -> "The Unit Sync relay rejected a change as invalid. The local edit remains saved; open Unit Sync for recovery guidance."
        }
        reportError(message, SyncIssueKind.SECURITY)
        if (retryable) {
            scheduleDeliveryRetry(pending)
        } else {
            deliveryRetryJobs.remove(pending.requestId)?.cancel()
            if (code in setOf("stale", "not-found", "counter-window", "session-replaced", "session-mismatch", "hello-required")) {
                ws?.cancel()
            }
        }
    }

    private fun localObjectExists(localId: String): Boolean =
        waypointStore.committedWaypoints.value.any { it.id == localId } ||
            drawingStore.committedDocument.value.features.any { it.id == localId }

    private fun applyRecord(rec: JSONObject, snapshotGeneration: Long? = null) {
        // Snapshot tombstones arrive as records with deleted=true; route them
        // through the same signed-delete verification as a live "del".
        if (rec.optBoolean("deleted", false)) {
            applyDelete(rec, snapshotGeneration = snapshotGeneration)
            return
        }
        val rawId = rec.optString("id").ifEmpty { return }
        val v = strictVersion(rec, "v") ?: return
        // Monotonic per-id version: reject anything <= the highest we've applied,
        // and keep rejecting even after a delete (versions[id] survives as a
        // tombstone) so a relay can't resurrect a deleted object by replaying an
        // older-but-validly-signed put.
        val id = acceptedLegacySyncRecordId(rawId, v, versions) ?: return
        val key = roomKey ?: return
        val kind = rec.optString("kind", "unknown")
        val by = rec.optString("by")
        val ctB64 = rec.optString("ct")
        if (ctB64.isEmpty() || ctB64.length > MAX_BASE64_BYTES) return
        val aad = SyncCrypto.aad(id, v, kind)
        val plain = SyncCrypto.open(key, SyncCrypto.decodeBase64(ctB64), aad) ?: return
        val inner = runCatching { JSONObject(String(plain, Charsets.UTF_8)) }.getOrNull() ?: return
        val content = inner.optString("c")
        // Device authorship: the write must be signed by the key pinned to `by`
        // (TOFU). A room member can't forge a write as another established
        // device; a key that doesn't match the pin is rejected as a swap.
        if (!verifyObjectSig(by, inner, SyncSigning.objectMessage(id, v, kind, by, content))) return
        val doc = drawingStore.committedDocument.value
        val fallback = doc.layers.firstOrNull()?.id ?: DrawingDocument.DEFAULT_LAYER_ID
        val parsed = runCatching {
            GeoJsonImporter.parse(
                content,
                existingLayers = doc.layers,
                fallbackLayerId = fallback,
                density = displayDensity,
            )
        }.getOrNull() ?: return
        if (!isValidLegacySyncPut(id, kind, parsed)) return

        if (forcedLegacyDeletes.containsKey(id)) {
            // An ack-lost local delete wins over an older reconnect snapshot.
            // Record the remote clock but leave the model absent and resend a
            // fresh authenticated tombstone after the snapshot fence.
            clock = maxOf(clock, v)
            versions[id] = v
            return
        }
        if (id in forcedLocalDiff) {
            val current = reexport(id)
            clock = maxOf(clock, v)
            versions[id] = v
            if (current == content) {
                lastContent[id] = current
                kindById[id] = kind
                forcedLocalDiff.remove(id)
            }
            return
        }

        for (layer in parsed.newLayers) {
            if (doc.layers.none { it.id == layer.id } &&
                !drawingStore.addLayerVerbatim(layer, ModelMutationOrigin.REMOTE_SYNC)) return
        }
        val persisted = when (kind) {
            "waypoint" -> upsertWaypoint(parsed.waypoints.single())
            "drawing" -> upsertDrawing(parsed.drawings.single())
            else -> false
        }
        if (!persisted) return

        // Advance only after authentication, strict object validation, and a
        // durable model apply. Malformed records cannot poison the clock.
        clock = maxOf(clock, v)
        versions[id] = v
        // re-export so echo guard matches what our next diff will see
        // (import -> export must be a fixed point)
        kindById[id] = kind
        lastContent[id] = reexport(id)

        // notify UI about the remote change (conflict notification)
        val objectName = parsed.waypoints.firstOrNull()?.name
            ?: parsed.drawings.firstOrNull()?.name
            ?: "Object"
        val kindLabel = if (kind == "waypoint") "Waypoint" else "Drawing"
        _remoteUpdates.tryEmit("$kindLabel '$objectName' updated by another device")
    }

    private fun applyDelete(rec: JSONObject, snapshotGeneration: Long? = null) {
        val rawId = rec.optString("id").ifEmpty { return }
        val v = strictVersion(rec, "v") ?: return
        val id = acceptedLegacySyncRecordId(rawId, v, versions) ?: return
        val key = roomKey ?: return
        val by = rec.optString("by")
        val ctB64 = rec.optString("ct")
        if (ctB64.isEmpty() || ctB64.length > MAX_BASE64_BYTES) return
        // Open the sealed proof (proves room-key possession, so a relay with no
        // room key can't forge a delete) then verify the device signature.
        val aad = SyncCrypto.aad(id, v, "del")
        val plain = SyncCrypto.open(key, SyncCrypto.decodeBase64(ctB64), aad) ?: return
        val inner = runCatching { JSONObject(String(plain, Charsets.UTF_8)) }.getOrNull() ?: return
        if (!verifyObjectSig(by, inner, SyncSigning.objectMessage(id, v, "del", by, ""))) return
        val recovery = forcedLegacyDeletes[id]
        if (recovery != null) {
            val exactSnapshotConfirmation = snapshotGeneration != null &&
                recovery.matchesVerifiedTombstone(
                    localId = id,
                    wireObjectId = rawId,
                    actorId = by,
                    objectVersion = v.toString(),
                    kind = rec.optString("kind", "del"),
                    ciphertextHash = ciphertextHash(ctB64),
                    snapshotGeneration = snapshotGeneration,
                )
            clock = maxOf(clock, v)
            versions[id] = v
            if (exactSnapshotConfirmation) {
                deliveryRetryJobs.remove(recovery.requestId)?.cancel()
                forcedLegacyDeletes.remove(id)
                lastContent.remove(id)
                kindById.remove(id)
                if (localObjectExists(id)) forcedLocalDiff += id else forcedLocalDiff.remove(id)
            }
            return
        }
        if (id in forcedLocalDiff && localObjectExists(id)) {
            clock = maxOf(clock, v)
            versions[id] = v
            return
        }
        val kindLabel = when (kindById[id]) {
            "drawing" -> "Drawing"
            "waypoint" -> "Waypoint"
            else -> "Object"
        }
        if (!removeSyncedObject(id, kindById[id])) {
            reportError(
                "A synced symbol delete could not be saved. Check available storage, then leave and rejoin to retry.",
                SyncIssueKind.SECURITY,
            )
            return
        }
        clock = maxOf(clock, v)
        versions[id] = v
        lastContent.remove(id); kindById.remove(id)
        _remoteUpdates.tryEmit("$kindLabel deleted by another device")
    }

    // -- v3 inbound handlers --

    private fun acceptsLiveInboundV3(): Boolean =
        _status.value == Status.CONNECTED ||
            (_status.value == Status.SNAPSHOTTING && awaitingHelloAck && snapshotSeq == null)

    private fun applyHelloAckV3(msg: JSONObject) {
        if (_status.value != Status.SNAPSHOTTING || !awaitingHelloAck || snapshotSeq != null) return
        val actor = myActorId ?: return
        val sd = sessionDomain ?: return
        val expectedVs = localHelloVersion ?: return
        if (!SyncIdentity.helloAckMatches(
                actor, sd, expectedVs, msg.optString("by"), msg.optString("sd"), msg.optString("vs"))) return
        awaitingHelloAck = false
        v3HandshakeTimeoutJob?.cancel(); v3HandshakeTimeoutJob = null
        reconnectBackoff.reset()
        _status.value = Status.CONNECTED
        startChatSessionV3()
        refreshChatAvailability()
        _lastError.value = issueLifecycle.connectionSucceeded(
            atGeneration = activeConnectionGeneration,
            verifiedCleanSnapshot = true,
        )?.message
        // A reconnect rotates the v3 session domain, so publish on the new
        // authenticated transport immediately. A still-recent GPS fix may seed
        // this session once; older fixes must wait for a new OS callback.
        presenceCadence.beginAuthenticatedSession()
        sendCurrentOrRequestFreshForegroundPresence(forceFreshRequest = true)
        replayState?.recoverableLocalDeletes(actor, myPublicKey)?.forEach { (wireId, stamp) ->
            if (!shouldResendRecoverableDelete(wireId, stamp, snapshotConfirmedLocalDeletes)) return@forEach
            val localId = findLocalIdForWireId(wireId) ?: "wire:$wireId"
            sendDelV3(localId, wireId, stamp)
        }
        snapshotConfirmedLocalDeletes.clear()
        syncLocalState(waypointStore.committedWaypoints.value, drawingStore.committedDocument.value)
    }

    private fun applyHelloV3(msg: JSONObject) {
        val by = msg.optString("by").ifEmpty { return }
        val pub = msg.optString("pub").ifEmpty { return }
        val sd = msg.optString("sd").ifEmpty { return }
        val vs = msg.optString("vs").ifEmpty { return }
        val sig = msg.optString("sig").ifEmpty { return }
        if (by == myActorId) return
        val keys = v3Keys ?: return
        val replay = replayState ?: return
        SyncIdentity.urlB64Decode32(pub) ?: return
        SyncIdentity.urlB64Decode32(sd) ?: return
        if (replay.getPinnedPubkey(by)?.let { it != pub } == true) return
        if (!SyncIdentity.verifyHello(by, pub, sd, vs, sig, keys.roomIdRaw)) return
        val epoch = vs.substringBefore(':')
        if (!replay.commitActorHello(by, pub, sd, epoch)) return
        if (activeSessions[by]?.second != sd) {
            removeChatPeer(by)
            remotePresenceCandidateClusters.remove(by)
        }
        if (activeSessions[by]?.second != sd) {
            _peers.value[by]?.let { peer ->
                val nowUptimeMs = System.nanoTime() / 1_000_000L
                val updated = if (peer.sessionDomain == sd) {
                    PresenceExpiryPolicy.transportRestored(peer, nowUptimeMs)
                } else {
                    PresenceExpiryPolicy.markedStale(peer, nowUptimeMs)
                }
                if (updated != peer) {
                    _peers.value = _peers.value + (by to updated)
                }
            }
        }
        activeSessions[by] = pub to sd
        _onlineMembers.value = onlineMemberTracker.authenticatedHello(
            clientId = by,
            sessionDomain = sd,
            nowMs = System.currentTimeMillis(),
        )
    }

    private fun applyChatKeyV3(msg: JSONObject) {
        val frame = TacMapChatWire.parseKeyFrame(msg) ?: return
        if (frame.actorId == myActorId) return
        val active = activeSessions[frame.actorId] ?: return
        if (active.second != frame.sessionDomain) return
        val keys = v3Keys ?: return
        val sessionRaw = TacMapChatIds.canonicalBytes(frame.sessionDomain, 32) ?: return
        val keyExchangeRaw = TacMapChatIds.canonicalBytes(frame.keyExchange, 32) ?: return
        val expectedKid = runCatching {
            TacMapChatCrypto.chatKeyId(
                keys.roomIdRaw,
                frame.actorId,
                sessionRaw,
                keyExchangeRaw,
            )
        }.getOrNull() ?: return
        if (expectedKid != frame.chatKeyId) return
        val preimage = runCatching {
            TacMapChatCrypto.chatKeyPreimage(
                keys.roomIdRaw,
                frame.actorId,
                sessionRaw,
                keyExchangeRaw,
                frame.chatKeyId,
            )
        }.getOrNull() ?: return
        if (!SyncSigning.verify(active.first, preimage, frame.signature)) return
        val existing = chatPeerKeys[frame.actorId]
        if (existing != null) {
            // A socket/session gets exactly one ephemeral key advertisement.
            // An exact relay retry is idempotent; an in-session rotation is not.
            if (existing.sessionDomain == frame.sessionDomain &&
                existing.chatKeyId == frame.chatKeyId &&
                existing.x25519PublicKey.contentEquals(keyExchangeRaw)
            ) {
                publishChatRecipients()
            }
            return
        }
        chatPeerKeys[frame.actorId] = TacMapChatPeerKey(
            actorId = frame.actorId,
            sessionDomain = frame.sessionDomain,
            chatKeyId = frame.chatKeyId,
            x25519PublicKey = keyExchangeRaw,
            signingPublicKey = TacMapChatIds.canonicalBytes(active.first, 32) ?: return,
            displayName = chatDisplayName(frame.actorId),
        )
        publishChatRecipients()
    }

    private fun applyChatKeyAckV3(msg: JSONObject) {
        val ack = TacMapChatWire.parseKeyAck(msg) ?: return
        val actor = myActorId ?: return
        val session = sessionDomain?.let(SyncIdentity::urlB64) ?: return
        val kid = localChatKeyId ?: return
        if (ack.first != actor || ack.second != session || ack.third != kid ||
            chatEphemeralKey == null
        ) return
        localChatKeyAcknowledged = true
        chatKeyRetryJob?.cancel()
        chatKeyRetryJob = null
        localChatAdvertFrame = null
        refreshChatAvailability()
    }

    private fun applyChatKeyNackV3(msg: JSONObject) {
        val nack = TacMapChatWire.parseKeyNack(msg) ?: return
        val actor = myActorId ?: return
        val session = sessionDomain?.let(SyncIdentity::urlB64) ?: return
        if (nack.first != actor || nack.second != session) return
        clearChatTransport(markPendingFailed = true)
        _chatAvailabilityMessage.value = "Secure chat key was rejected (${nack.third})"
        _chatSessionReady.value = false
    }

    private fun applyChatAckV3(msg: JSONObject) {
        val ack = TacMapChatWire.parseAck(msg) ?: return
        val expected = pendingChat[ack.messageId] ?: return
        if (ack != expected) return
        pendingChat.remove(ack.messageId)
        if (!chatHistoryStore.updateDelivery(
                ack.messageId,
                TacMapChatDeliveryState.ROUTED,
            )
        ) {
            clearChatTransport(markPendingFailed = true)
            refreshChatAvailability()
        }
    }

    private fun applyChatNackV3(msg: JSONObject) {
        val nack = TacMapChatWire.parseNack(msg) ?: return
        val actor = myActorId ?: return
        val session = sessionDomain?.let(SyncIdentity::urlB64) ?: return
        if (nack.actorId != actor || nack.sessionDomain != session ||
            pendingChat[nack.messageId] == null
        ) return
        pendingChat.remove(nack.messageId)
        if (!chatHistoryStore.updateDelivery(
                nack.messageId,
                TacMapChatDeliveryState.FAILED,
                nack.code,
            )
        ) {
            clearChatTransport(markPendingFailed = true)
            refreshChatAvailability()
        }
    }

    private fun applyChatV3(msg: JSONObject) {
        if (chatHistoryStore.availability.value != TacMapChatHistoryAvailability.READY) return
        val frame = TacMapChatWire.parseFrame(msg) ?: return
        if (frame.actorId == myActorId) return
        val active = activeSessions[frame.actorId] ?: return
        if (active.second != frame.sessionDomain) return
        val senderKey = chatPeerKeys[frame.actorId] ?: return
        if (senderKey.sessionDomain != frame.sessionDomain ||
            senderKey.chatKeyId != frame.fromChatKeyId
        ) return
        val keys = v3Keys ?: return
        val ownActor = myActorId ?: return
        val ownSession = sessionDomain ?: return
        val ownSessionText = SyncIdentity.urlB64(ownSession)
        val ownKid = localChatKeyId ?: return
        if (frame.scope == TacMapChatScope.DIRECT &&
            (frame.recipientActorId != ownActor ||
                frame.recipientSessionDomain != ownSessionText ||
                frame.recipientChatKeyId != ownKid)
        ) return
        val header = runCatching {
            TacMapChatCrypto.header(frame.headerFields(keys.roomIdRaw))
        }.getOrNull() ?: return
        val signaturePreimage = TacMapChatCrypto.signaturePreimage(header, frame.sealedRaw)
        // Signature validation precedes key derivation and every decrypt attempt.
        if (!SyncSigning.verify(active.first, signaturePreimage, frame.signature)) return
        val fingerprint = TacMapChatCrypto.fingerprint(
            header,
            frame.sealedRaw,
            frame.signature,
        ) ?: return
        val messageKey = if (frame.scope == TacMapChatScope.ROOM) {
            TacMapChatCrypto.roomChatKey(keys.roomKey)
        } else {
            val ephemeral = chatEphemeralKey ?: return
            TacMapChatCrypto.directChatKey(
                ephemeral,
                senderKey.x25519PublicKey,
                keys.roomIdRaw,
                header,
            ) ?: return
        }
        val plaintext = try {
            TacMapChatCrypto.open(messageKey, frame.sealedRaw, header)
        } finally {
            messageKey.fill(0)
        } ?: return
        val payload = try {
            TacMapChatPayloadCodec.decode(plaintext)
        } finally {
            plaintext.fill(0)
        } ?: return
        val senderName = chatDisplayName(frame.actorId)
        val message = TacMapChatMessage(
            id = frame.messageId,
            roomId = keys.roomId,
            scope = frame.scope,
            senderActorId = frame.actorId,
            senderName = senderName,
            recipientActorId = if (frame.scope == TacMapChatScope.DIRECT) ownActor else null,
            recipientName = if (frame.scope == TacMapChatScope.DIRECT) {
                TacMapChatPayload.boundedDisplayName(presenceConfig.callsign).ifBlank { "This device" }
            } else null,
            kind = payload.kind,
            body = payload.body,
            sentAtMilliseconds = payload.createdAt,
            isOutgoing = false,
            deliveryState = TacMapChatDeliveryState.RECEIVED,
        )
        when (chatHistoryStore.acceptInbound(
            message = message,
            actorId = frame.actorId,
            sessionDomain = frame.sessionDomain,
            chatKeyId = frame.fromChatKeyId,
            counterHex = frame.counterHex,
            fingerprint = fingerprint,
        )) {
            TacMapChatInboundResult.ACCEPTED,
            TacMapChatInboundResult.DUPLICATE -> {
                _onlineMembers.value = onlineMemberTracker.authenticatedActivity(
                    frame.actorId,
                    frame.sessionDomain,
                    System.currentTimeMillis(),
                )
            }
            TacMapChatInboundResult.REPLAY_REJECTED -> Unit
            TacMapChatInboundResult.STORE_UNAVAILABLE -> {
                clearChatTransport(markPendingFailed = true)
                refreshChatAvailability()
            }
        }
    }

    private data class OuterV3(
        val wireId: String,
        val stamp: VersionStamp,
        val stampText: String,
        val actorId: String,
        val pub: String,
        val pubRaw: ByteArray,
        val sessionDomain: ByteArray,
        val kind: String,
        val ciphertext: ByteArray,
        val deleted: Boolean,
    )

    private fun parseOuterV3(rec: JSONObject): OuterV3? {
        val wireId = rec.optString("id").ifEmpty { return null }
        if (SyncIdentity.urlB64Decode32(wireId) == null) return null
        val vsStr = rec.optString("vs").ifEmpty { return null }
        val vs = VersionStamp.parse(vsStr) ?: return null
        val by = rec.optString("by").ifEmpty { return null }
        val pub = rec.optString("pub").ifEmpty { return null }
        val sdText = rec.optString("sd").ifEmpty { return null }
        val keys = v3Keys ?: return null
        val replay = replayState ?: return null
        val pubRaw = SyncIdentity.urlB64Decode32(pub) ?: return null
        val sd = SyncIdentity.urlB64Decode32(sdText) ?: return null
        if (vs.actorId != by || SyncIdentity.actorId(keys.roomIdRaw, pubRaw) != by) return null
        if (replay.getPinnedPubkey(by)?.let { it != pub } == true) return null
        val type = rec.optString("t")
        if (type.isNotEmpty() && type != "put" && type != "del") return null
        val deletedField = rec.opt("deleted")
        if (deletedField != null && deletedField !is Boolean) return null
        val storedDeleted = deletedField as? Boolean ?: false
        if ((type == "put" && storedDeleted) || (type == "del" && deletedField == false)) return null
        val deleted = type == "del" || storedDeleted
        val kind = rec.optString("kind").ifEmpty { return null }
        if (!kind.matches(Regex("^[A-Za-z0-9_-]{1,32}$"))) return null
        if ((deleted && kind != "del") || (!deleted && kind !in setOf("waypoint", "drawing"))) return null
        val ctB64 = rec.optString("ct")
        if (ctB64.isEmpty() || ctB64.length > MAX_BASE64_BYTES) return null
        val ct = runCatching { SyncCrypto.decodeBase64(ctB64) }.getOrNull() ?: return null
        if (ct.size < 28 || SyncCrypto.encodeBase64(ct) != ctB64) return null
        return OuterV3(wireId, vs, vsStr, by, pub, pubRaw, sd, kind, ct, deleted)
    }

    private fun validateRecordV3(rec: JSONObject): ValidatedV3? {
        val outer = parseOuterV3(rec) ?: return null
        val key = roomKey ?: return null
        val keys = v3Keys ?: return null
        val aad = SyncCrypto.aadV3(outer.wireId, outer.stampText, outer.kind)
        val plain = SyncCrypto.open(key, outer.ciphertext, aad) ?: return null
        val inner = runCatching { JSONObject(String(plain, Charsets.UTF_8)) }.getOrNull() ?: return null
        val sig = inner.optString("sig").ifEmpty { return null }
        if (outer.deleted) {
            val hash = SyncIdentity.sha256(ByteArray(0))
            val preimage = SyncIdentity.buildPreimage(
                SyncIdentity.DOMAIN_DELETE, keys.roomIdRaw, outer.actorId, outer.sessionDomain,
                VersionStamp.counterHex16(outer.stamp.counter), outer.wireId, "del", hash
            )
            if (!SyncSigning.verify(outer.pub, preimage, sig)) return null
            return ValidatedV3.Delete(
                SyncReplayState.AuthenticatedMutation(
                    outer.wireId, outer.stamp, outer.pub, null, deleted = true
                ),
                findLocalIdForWireId(outer.wireId)
            )
        }
        val content = inner.optString("c").ifEmpty { return null }
        val contentBytes = content.toByteArray(Charsets.UTF_8)
        val payloadHash = SyncIdentity.sha256(contentBytes)
        val preimage = SyncIdentity.buildPreimage(
            SyncIdentity.DOMAIN_PUT, keys.roomIdRaw, outer.actorId, outer.sessionDomain,
            VersionStamp.counterHex16(outer.stamp.counter), outer.wireId, outer.kind, payloadHash
        )
        if (!SyncSigning.verify(outer.pub, preimage, sig)) return null
        val doc = drawingStore.committedDocument.value
        val fallback = doc.layers.firstOrNull()?.id ?: DrawingDocument.DEFAULT_LAYER_ID
        val parsed = runCatching {
            GeoJsonImporter.parse(
                content,
                existingLayers = doc.layers,
                fallbackLayerId = fallback,
                density = displayDensity,
            )
        }.getOrNull() ?: return null
        if (parsed.invalidSkipped != 0) return null
        val localId = when (outer.kind) {
            "waypoint" -> parsed.waypoints.singleOrNull()?.id?.takeIf { parsed.drawings.isEmpty() }
            "drawing" -> parsed.drawings.singleOrNull()?.id?.takeIf { parsed.waypoints.isEmpty() }
            else -> null
        } ?: return null
        val expectedWireId = runCatching {
            SyncIdentity.wireObjectId(keys.metadataKey, SyncIdentity.uuidToBytes(localId))
        }.getOrNull() ?: return null
        if (expectedWireId != outer.wireId) return null
        return ValidatedV3.Put(
            SyncReplayState.AuthenticatedMutation(
                outer.wireId, outer.stamp, outer.pub, SyncIdentity.bytesToHex(payloadHash), deleted = false
            ),
            parsed,
            localId,
            expectedModelHash(parsed, localId, doc.layers) ?: return null
        )
    }

    private fun applyLiveRecordV3(rec: JSONObject) {
        val validated = validateRecordV3(rec) ?: return
        val replay = replayState ?: return
        if (!replay.canAcceptLive(validated.mutation.wireObjectId, validated.mutation.stamp)) return
        val priorHash = modelContentHash(validated.localModelId())
        if (!replay.commitRemoteAuthenticated(
                SyncReplayState.RemoteMutation(
                    validated.mutation, priorHash, validated.localModelId(),
                    modelRevisionJournal.generation(validated.localModelId()), validated.expectedModelHash()))) {
            return persistenceFailure()
        }
        resolvingPendingModel = true
        try {
            if (!resolvePendingModelApplication(validated, priorHash)) return persistenceFailure()
        } finally {
            resolvingPendingModel = false
        }
    }

    private fun ValidatedV3.localModelId(): String? = when (this) {
        is ValidatedV3.Put -> localId
        is ValidatedV3.Delete -> localId
    }

    private fun ValidatedV3.expectedModelHash(): String? = when (this) {
        is ValidatedV3.Put -> expectedModelHash
        is ValidatedV3.Delete -> null
    }

    /** Receiver-local fixed-point hash; the authenticated payload hash remains sender bytes. */
    private fun expectedModelHash(
        parsed: GeoJsonImporter.Result,
        localId: String,
        existingLayers: List<com.tacmap.drawings.DrawingLayer>,
    ): String? {
        val layers = (existingLayers + parsed.newLayers).distinctBy { it.id }
        val content = parsed.waypoints.singleOrNull { it.id == localId }?.let {
            GeoJsonExporter.export(listOf(it), emptyList(), layers, density = displayDensity)
        } ?: parsed.drawings.singleOrNull { it.id == localId }?.let {
            GeoJsonExporter.export(emptyList(), listOf(it), layers, density = displayDensity)
        } ?: return null
        return SyncIdentity.bytesToHex(SyncIdentity.sha256(content.toByteArray(Charsets.UTF_8)))
    }

    private fun modelContentHash(localId: String?): String? {
        localId ?: return null
        val content = reexport(localId)
        if (content.isEmpty()) return null
        return SyncIdentity.bytesToHex(SyncIdentity.sha256(content.toByteArray(Charsets.UTF_8)))
    }

    /** Resolve durable model work without overwriting a divergent offline edit. */
    private fun resolvePendingModelApplication(validated: ValidatedV3, currentHash: String?): Boolean {
        val replay = replayState ?: return false
        val mutation = validated.mutation
        return when (replay.pendingModelDecision(
            mutation, currentHash, modelRevisionJournal.generation(validated.localModelId()))) {
            SyncReplayState.PendingModelDecision.APPLY_INCOMING -> {
                applyValidatedV3(validated)
                val expected = validated.expectedModelHash()
                if (modelContentHash(validated.localModelId()) != expected) false
                else replay.clearPendingModelApplication(mutation)
            }
            SyncReplayState.PendingModelDecision.ALREADY_APPLIED -> {
                markModelBaseline(validated)
                replay.clearPendingModelApplication(mutation)
            }
            SyncReplayState.PendingModelDecision.LOCAL_DIVERGED -> {
                validated.localModelId()?.let { forcedLocalDiff += it }
                replay.clearPendingModelApplication(mutation)
            }
            SyncReplayState.PendingModelDecision.NONE -> {
                // An exact resolved record may establish the echo baseline, but
                // must never overwrite a model that has since diverged.
                if (replay.isExactPersistedMutation(mutation)) {
                    val expected = validated.expectedModelHash()
                    if (currentHash == expected) markModelBaseline(validated)
                    else validated.localModelId()?.let { forcedLocalDiff += it }
                }
                true
            }
        }
    }

    private fun markModelBaseline(validated: ValidatedV3) {
        when (validated) {
            is ValidatedV3.Put -> {
                val content = reexport(validated.localId)
                if (content.isNotEmpty()) {
                    lastContent[validated.localId] = content
                    kindById[validated.localId] = if (validated.parsed.waypoints.isNotEmpty()) "waypoint" else "drawing"
                }
                forcedLocalDiff.remove(validated.localId)
            }
            is ValidatedV3.Delete -> validated.localId?.let {
                lastContent.remove(it); kindById.remove(it); forcedLocalDiff.remove(it)
            }
        }
    }

    /** Pending records omitted or contradicted by the snapshot cannot be
     * repaired safely. Preserve the model and force it to win at a new stamp. */
    private fun resolveUnmatchedPendingModelApplications(): Boolean {
        val replay = replayState ?: return false
        for (remote in replay.pendingRemoteMutations()) {
            val localId = remote.localModelId
            val current = modelContentHash(localId)
            val incoming = remote.expectedModelHash
            if (current == incoming) {
                markCurrentModelBaseline(localId)
            } else {
                localId?.let { forcedLocalDiff += it }
            }
            if (!replay.clearPendingModelApplication(remote.mutation)) return false
        }
        return true
    }

    private fun markCurrentModelBaseline(localId: String?) {
        localId ?: return
        val content = reexport(localId)
        if (content.isEmpty()) {
            lastContent.remove(localId); kindById.remove(localId)
        } else {
            lastContent[localId] = content
            kindById[localId] = if (waypointStore.committedWaypoints.value.any { it.id == localId }) "waypoint" else "drawing"
        }
        forcedLocalDiff.remove(localId)
    }

    private fun applyValidatedV3(validated: ValidatedV3) {
        when (validated) {
            is ValidatedV3.Put -> {
                val doc = drawingStore.committedDocument.value
                for (layer in validated.parsed.newLayers) {
                    if (doc.layers.none { it.id == layer.id }) {
                        if (!drawingStore.addLayerVerbatim(layer, ModelMutationOrigin.REMOTE_SYNC)) return
                    }
                }
                if (!validated.parsed.waypoints.all(::upsertWaypoint)) return
                if (!validated.parsed.drawings.all(::upsertDrawing)) return
                val kind = if (validated.parsed.waypoints.isNotEmpty()) "waypoint" else "drawing"
                kindById[validated.localId] = kind
                lastContent[validated.localId] = reexport(validated.localId)
                val objectName = validated.parsed.waypoints.firstOrNull()?.name
                    ?: validated.parsed.drawings.firstOrNull()?.name ?: "Object"
                val label = if (kind == "waypoint") "Waypoint" else "Drawing"
                _remoteUpdates.tryEmit("$label '$objectName' updated by another device")
            }
            is ValidatedV3.Delete -> validated.localId?.let { sourceId ->
                val kindLabel = when (kindById[sourceId]) {
                "drawing" -> "Drawing"
                "waypoint" -> "Waypoint"
                else -> "Object"
                }
                if (!removeSyncedObject(sourceId, kindById[sourceId])) return
                lastContent.remove(sourceId); kindById.remove(sourceId)
                _remoteUpdates.tryEmit("$kindLabel deleted by another device")
            }
        }
    }

    /** Every Sync delete is durable-before-publish. Missing objects count as an
     * idempotent success; a failed store write leaves the baseline untouched so
     * replay/reconciliation can retry it. Global import identity prevents a
     * waypoint and drawing from sharing one canonical ID. */
    private fun removeSyncedObject(id: String, kind: String?): Boolean = when (kind) {
        "drawing" -> {
            val exists = drawingStore.committedDocument.value.features.any { it.id == id }
            !exists || drawingStore.removeFeature(id, ModelMutationOrigin.REMOTE_SYNC)
        }
        "waypoint" -> {
            val waypoint = waypointStore.committedWaypoints.value.firstOrNull { it.id == id }
            waypoint == null || waypointStore.remove(waypoint, ModelMutationOrigin.REMOTE_SYNC)
        }
        else -> {
            val drawingExists = drawingStore.committedDocument.value.features.any { it.id == id }
            if (drawingExists) {
                drawingStore.removeFeature(id, ModelMutationOrigin.REMOTE_SYNC)
            } else {
                val waypoint = waypointStore.committedWaypoints.value.firstOrNull { it.id == id }
                waypoint == null || waypointStore.remove(waypoint, ModelMutationOrigin.REMOTE_SYNC)
            }
        }
    }

    private fun applyPresenceV3(msg: JSONObject) {
        val by = msg.optString("by").ifEmpty { return }
        if (by == myActorId) return
        val pub = msg.optString("pub").ifEmpty { return }
        val vsStr = msg.optString("vs").ifEmpty { return }
        val vs = VersionStamp.parse(vsStr) ?: return
        val key = roomKey ?: return
        val keys = v3Keys ?: return
        val replay = replayState ?: return
        val sdText = msg.optString("sd").ifEmpty { return }
        val ctB64 = msg.optString("ct").ifEmpty { return }
        if (ctB64.length > MAX_BASE64_BYTES) return
        val pubRaw = SyncIdentity.urlB64Decode32(pub) ?: return
        val sd = SyncIdentity.urlB64Decode32(sdText) ?: return
        if (vs.actorId != by || SyncIdentity.actorId(keys.roomIdRaw, pubRaw) != by) return
        if (activeSessions[by] != (pub to sdText)) return
        if (replay.getPinnedPubkey(by) != pub) return
        if (!replay.canAcceptPresence(by, pub, sdText, vs.counter)) return
        val aad = SyncCrypto.aadPresenceV3(by, vsStr)
        val ct = runCatching { SyncCrypto.decodeBase64(ctB64) }.getOrNull() ?: return
        if (ct.size < 28 || SyncCrypto.encodeBase64(ct) != ctB64) return
        val plain = SyncCrypto.open(key, ct, aad) ?: return
        val obj = runCatching { JSONObject(String(plain, Charsets.UTF_8)) }.getOrNull() ?: return
        val sig = obj.optString("sig").ifEmpty { return }
        if (obj.optString("pub") != pub) return
        val hasExactEnvelope = obj.has("pv") || obj.has("p")
        val exact = if (hasExactEnvelope) {
            val pv = obj.opt("pv")
            val isVersionOne = (pv is Int && pv == PresencePayloadV3.ENVELOPE_VERSION) ||
                (pv is Long && pv == PresencePayloadV3.ENVELOPE_VERSION.toLong())
            if (!isVersionOne) return
            val encoded = obj.opt("p") as? String ?: return
            PresencePayloadV3.decodeCanonicalStandardBase64(encoded) ?: return
        } else null
        val payload = exact?.value ?: legacyPresencePayload(obj)
        val payloadBytes = exact?.bytes ?: buildLegacyPresencePayloadBytes(obj)
        val payloadHash = SyncIdentity.sha256(payloadBytes)
        val preimage = SyncIdentity.buildPreimage(
            SyncIdentity.DOMAIN_PRESENCE, keys.roomIdRaw, by, sd,
            VersionStamp.counterHex16(vs.counter), "", "loc", payloadHash)
        if (!SyncSigning.verify(pub, preimage, sig)) return

        val retentionWindowMs = when (val decoded = PresenceRetentionV3.decode(obj)) {
            PresenceRetentionV3.DecodeResult.Absent -> PresenceExpiryPolicy.LIVE_UPDATE_WINDOW_MS
            PresenceRetentionV3.DecodeResult.Invalid -> return
            is PresenceRetentionV3.DecodeResult.Valid -> {
                val advertisement = decoded.advertisement
                val retentionPreimage = SyncIdentity.buildPreimage(
                    SyncIdentity.DOMAIN_PRESENCE, keys.roomIdRaw, by, sd,
                    VersionStamp.counterHex16(vs.counter), "",
                    PresenceRetentionV3.SIGNATURE_KIND,
                    SyncIdentity.sha256(advertisement.signedPayload),
                )
                if (!SyncSigning.verify(pub, retentionPreimage, advertisement.signature)) return
                advertisement.seconds * 1_000L
            }
        }

        val horizontalAccuracyMetres = when (val decoded = PresenceAccuracyV3.decode(obj)) {
            PresenceAccuracyV3.DecodeResult.Absent -> null
            PresenceAccuracyV3.DecodeResult.Invalid -> return
            is PresenceAccuracyV3.DecodeResult.Valid -> {
                val advertisement = decoded.advertisement
                val accuracyPreimage = SyncIdentity.buildPreimage(
                    SyncIdentity.DOMAIN_PRESENCE, keys.roomIdRaw, by, sd,
                    VersionStamp.counterHex16(vs.counter), "",
                    PresenceAccuracyV3.SIGNATURE_KIND,
                    SyncIdentity.sha256(advertisement.signedPayload),
                )
                if (!SyncSigning.verify(pub, accuracyPreimage, advertisement.signature)) return
                advertisement.horizontalAccuracyMetres
            }
        }

        if (!payload.isValid()) return
        val nowUptimeMs = System.nanoTime() / 1_000_000L
        if (horizontalAccuracyMetres != null) {
            val previous = _peers.value[by]?.let { old ->
                old.horizontalAccuracyMetres?.let { oldAccuracy ->
                    PresenceLocationFix(old.lat, old.lon, oldAccuracy, old.receivedAtUptimeMs)
                }
            }
            val candidate = PresenceLocationFix(
                payload.lat,
                payload.lon,
                horizontalAccuracyMetres,
                nowUptimeMs,
            )
            val evaluation = PresenceLocationQuality.evaluateJump(
                previous = previous,
                candidate = candidate,
                existingCluster = remotePresenceCandidateClusters[by],
                allowSimulatorTeleport = PresenceLocationQuality.allowsSimulatorTeleport(),
            )
            if (!evaluation.accepted) {
                evaluation.nextCluster?.let { remotePresenceCandidateClusters[by] = it }
                    ?: remotePresenceCandidateClusters.remove(by)
                return
            }
            remotePresenceCandidateClusters.remove(by)
        } else {
            remotePresenceCandidateClusters.remove(by)
        }
        if (!replay.commitPresence(by, pub, sdText, vs.counter)) {
            persistenceFailure()
            return
        }
        val peer = PresencePeer(
            clientId = by,
            callsign = payload.callsign,
            affiliation = payload.affiliation,
            echelon = payload.echelon,
            function = payload.function,
            isHQ = payload.isHQ,
            lat = payload.lat, lon = payload.lon,
            heading = payload.heading, speed = payload.speed,
            ts = System.currentTimeMillis(),
            sessionDomain = sdText,
            retentionWindowMs = retentionWindowMs,
            horizontalAccuracyMetres = horizontalAccuracyMetres,
            receivedAtUptimeMs = nowUptimeMs,
        )
        _peers.value = _peers.value + (by to peer)
        val now = System.currentTimeMillis()
        onlineMemberTracker.authenticatedActivity(by, sdText, now)
        _onlineMembers.value = onlineMemberTracker.updatePresenceMetadata(
            clientId = by,
            sessionDomain = sdText,
            callsign = payload.callsign,
            affiliation = payload.affiliation,
            echelon = payload.echelon,
            function = payload.function,
            isHQ = payload.isHQ,
            nowMs = now,
        )
        publishChatRecipients()
    }

    private fun legacyPresencePayload(obj: JSONObject): PresencePayloadV3 =
        PresencePayloadV3(
            callsign = obj.optString("callsign", ""),
            affiliation = obj.optString("affiliation", "UNKNOWN"),
            echelon = obj.optString("echelon", "TEAM"),
            function = obj.optString("function", "INFANTRY"),
            isHQ = obj.optBoolean("isHQ", false),
            lat = obj.optDouble("lat", 0.0),
            lon = obj.optDouble("lon", 0.0),
            heading = obj.optDouble("heading", 0.0),
            speed = obj.optDouble("speed", 0.0),
        )

    private fun buildLegacyPresencePayloadBytes(obj: JSONObject): ByteArray {
        // canonical JSON -- keys alphabetical to match iOS JSONSerialization(.sortedKeys)
        val canonical = JSONObject()
        canonical.put("affiliation", obj.optString("affiliation", "UNKNOWN"))
        canonical.put("callsign", obj.optString("callsign", ""))
        canonical.put("echelon", obj.optString("echelon", "TEAM"))
        canonical.put("function", obj.optString("function", "INFANTRY"))
        canonical.put("heading", obj.optDouble("heading", 0.0))
        canonical.put("isHQ", obj.optBoolean("isHQ", false))
        canonical.put("lat", obj.optDouble("lat", 0.0))
        canonical.put("lon", obj.optDouble("lon", 0.0))
        canonical.put("speed", obj.optDouble("speed", 0.0))
        return canonical.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Reverse-lookup: given a v3 wire object ID, find the local UUID that maps to it.
     * This is O(n) over local objects, which is fine for typical room sizes.
     */
    private fun findLocalIdForWireId(wireId: String): String? {
        val keys = v3Keys ?: return null
        for (id in lastContent.keys) {
            val computed = SyncIdentity.wireObjectId(keys.metadataKey, SyncIdentity.uuidToBytes(id))
            if (computed == wireId) return id
        }
        // also check current stores
        for (wp in waypointStore.committedWaypoints.value) {
            val computed = SyncIdentity.wireObjectId(keys.metadataKey, SyncIdentity.uuidToBytes(wp.id))
            if (computed == wireId) return wp.id
        }
        for (f in drawingStore.committedDocument.value.features) {
            val computed = SyncIdentity.wireObjectId(keys.metadataKey, SyncIdentity.uuidToBytes(f.id))
            if (computed == wireId) return f.id
        }
        return null
    }

    /** TOFU-pin [by]'s signing key and verify [signed] under it. Shared by
     *  object puts and deletes and by presence, so a device has one identity per
     *  clientId. Missing/garbage fields, or a key that doesn't match the existing
     *  pin, -> false (the write is rejected). */
    private fun verifyObjectSig(by: String, inner: JSONObject, signed: ByteArray): Boolean {
        if (by.isEmpty()) return false
        val pub = inner.optString("pub").ifEmpty { return false }
        val sig = inner.optString("sig").ifEmpty { return false }
        val pinned = peerKeys[by]
        if (pinned == null) peerKeys[by] = pub else if (pinned != pub) return false
        return SyncSigning.verify(pub, signed, sig)
    }

    private fun upsertWaypoint(wp: Waypoint): Boolean {
        return if (waypointStore.committedWaypoints.value.any { it.id == wp.id }) {
            waypointStore.updateNoUndo(wp, ModelMutationOrigin.REMOTE_SYNC)
        } else waypointStore.add(wp, ModelMutationOrigin.REMOTE_SYNC)
    }

    private fun upsertDrawing(f: DrawingFeature): Boolean {
        return if (drawingStore.committedDocument.value.features.any { it.id == f.id }) {
            drawingStore.updateFeatureNoUndo(f, ModelMutationOrigin.REMOTE_SYNC)
        } else drawingStore.addFeature(f, ModelMutationOrigin.REMOTE_SYNC)
    }

    /** Re-serialise now-local object so next diff doesn't see a spurious change. */
    private fun reexport(id: String): String {
        val doc = drawingStore.committedDocument.value
        waypointStore.committedWaypoints.value.firstOrNull { it.id == id }
            ?.let {
                return GeoJsonExporter.export(
                    listOf(it), emptyList(), doc.layers, density = displayDensity,
                )
            }
        doc.features.firstOrNull { it.id == id }
            ?.let {
                return GeoJsonExporter.export(
                    emptyList(), listOf(it), doc.layers, density = displayDensity,
                )
            }
        return ""
    }

    // ----- Presence broadcasting -----

    private fun startPresenceBroadcast() {
        presenceJob?.cancel()
        presenceJob = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(5_000)
                if (!backgroundPresenceOnly && !awaitingForegroundStores &&
                    _status.value == Status.CONNECTED && presenceConfig.shareLocation
                ) {
                    sendCurrentOrRequestFreshForegroundPresence()
                }
            }
        }
    }

    private fun sendCurrentOrRequestFreshForegroundPresence(
        forceFreshRequest: Boolean = false,
    ) {
        if (backgroundPresenceOnly || awaitingForegroundStores ||
            _status.value != Status.CONNECTED || !presenceConfig.shareLocation
        ) return

        val now = SystemClock.elapsedRealtimeNanos()
        val lastSuccessfulFix = presenceCadence.latestSuccessfulFixElapsedRealtimeNanos
        val current = locationProvider?.invoke()
        val currentIsGenuinelyFresh = current != null && (
            presenceCadence.isAuthenticatedSessionSeedFix(
                fixElapsedRealtimeNanos = current.elapsedRealtimeNanos,
                nowElapsedRealtimeNanos = now,
            ) || ForegroundPresenceLiveness.isGenuinelyFreshRequestedFix(
                baselineFixElapsedRealtimeNanos = lastSuccessfulFix,
                candidateFixElapsedRealtimeNanos = current.elapsedRealtimeNanos,
                nowElapsedRealtimeNanos = now,
            )
        )
        if (currentIsGenuinelyFresh) {
            val interval = OpsecSettings.shared?.backgroundUnitSyncInterval?.value
                ?: com.tacmap.settings.BackgroundUnitSyncInterval.DEFAULT
            if (sendPresenceAtCadence(
                    location = checkNotNull(current),
                    isBackground = false,
                    backgroundInterval = interval,
                    nowElapsedRealtimeNanos = now,
                )
            ) return
        }

        if (!foregroundPresenceLiveness.shouldRequestFreshFix(
                lastSuccessfulFixElapsedRealtimeNanos = lastSuccessfulFix,
                nowElapsedRealtimeNanos = now,
                force = forceFreshRequest,
            )
        ) return

        val providerFixTime = current?.elapsedRealtimeNanos
        val baselineFixTime = listOfNotNull(lastSuccessfulFix, providerFixTime).maxOrNull()
        val expectedSocket = ws ?: return
        val expectedGeneration = activeConnectionGeneration
        val expectedSessionDomain = sessionDomain?.let(SyncIdentity::urlB64)
        foregroundPresenceLiveness.recordRequest(now)
        foregroundGpsFixRequester.request { requestedLocation ->
            if (requestedLocation == null ||
                requestedLocation.provider != android.location.LocationManager.GPS_PROVIDER
            ) return@request
            scope.launch {
                val receivedAt = SystemClock.elapsedRealtimeNanos()
                if (ws !== expectedSocket || activeConnectionGeneration != expectedGeneration ||
                    backgroundPresenceOnly || awaitingForegroundStores ||
                    _status.value != Status.CONNECTED || !presenceConfig.shareLocation ||
                    sessionDomain?.let(SyncIdentity::urlB64) != expectedSessionDomain ||
                    !ForegroundPresenceLiveness.isGenuinelyFreshRequestedFix(
                        baselineFixElapsedRealtimeNanos = baselineFixTime,
                        candidateFixElapsedRealtimeNanos = requestedLocation.elapsedRealtimeNanos,
                        nowElapsedRealtimeNanos = receivedAt,
                    )
                ) return@launch
                val interval = OpsecSettings.shared?.backgroundUnitSyncInterval?.value
                    ?: com.tacmap.settings.BackgroundUnitSyncInterval.DEFAULT
                sendPresenceAtCadence(
                    location = requestedLocation,
                    isBackground = false,
                    backgroundInterval = interval,
                    nowElapsedRealtimeNanos = receivedAt,
                )
            }
        }
    }

    private fun cancelForegroundPresenceRefresh() {
        foregroundGpsFixRequester.cancel()
        foregroundPresenceLiveness.reset()
    }

    private fun sendPresenceAtCadence(
        location: Location,
        isBackground: Boolean,
        backgroundInterval: com.tacmap.settings.BackgroundUnitSyncInterval,
        nowElapsedRealtimeNanos: Long,
    ): Boolean {
        val fixElapsedRealtimeNanos = location.elapsedRealtimeNanos
        if (!presenceCadence.isSendDue(
                fixElapsedRealtimeNanos = fixElapsedRealtimeNanos,
                nowElapsedRealtimeNanos = nowElapsedRealtimeNanos,
                isBackground = isBackground,
                backgroundInterval = backgroundInterval,
            )
        ) return false
        val qualityFix = PresenceLocationQuality.fromLocation(location) ?: return false
        val heading = if (location.hasBearing()) location.bearing.toDouble() else 0.0
        val speed = if (location.hasSpeed()) location.speed.toDouble() else 0.0
        if (!PresenceLocationQuality.hasValidWireValues(
                qualityFix.latitude,
                qualityFix.longitude,
                heading,
                speed,
            )
        ) return false
        val qualityEvaluation = PresenceLocationQuality.evaluateJump(
            previous = lastGoodLocalPresenceFix,
            candidate = qualityFix,
            existingCluster = localPresenceCandidateCluster,
            allowSimulatorTeleport = PresenceLocationQuality.allowsSimulatorTeleport(),
        )
        localPresenceCandidateCluster = qualityEvaluation.nextCluster
        if (!qualityEvaluation.accepted) return false
        // Preserve this independently of transport success: it is the last GPS
        // fix that passed the quality boundary, not an optimistic relay state.
        lastGoodLocalPresenceFix = qualityFix
        val retentionSeconds = UnitSyncPresenceCadence.retentionSeconds(
            isBackground = isBackground,
            backgroundInterval = backgroundInterval,
        ).toInt()
        val successful = if (protocolVersion == 3) {
            sendPresenceV3(location, retentionSeconds)
        } else {
            sendPresenceV2(location)
        }
        presenceCadence.recordSendResult(
            successful = successful,
            fixElapsedRealtimeNanos = fixElapsedRealtimeNanos,
            completedAtElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
        )
        return successful
    }

    private fun sendPresenceV2(loc: Location): Boolean {
        val key = roomKey ?: return false
        val cfg = presenceConfig
        if (!cfg.shareLocation) return false
        val callsign = boundCallsign(cfg.callsign)
        val ts = System.currentTimeMillis()
        val lat = loc.latitude
        val lon = loc.longitude
        val heading = if (loc.hasBearing()) loc.bearing.toDouble() else 0.0
        val speed = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0
        if (!lat.isFinite() || lat !in -90.0..90.0 ||
            !lon.isFinite() || lon !in -180.0..180.0 ||
            !heading.isFinite() || !speed.isFinite()
        ) return false
        val sig = SyncSigning.sign(deviceSeed, SyncSigning.presenceMessage(
            clientId, ts, lat, lon, heading, speed,
            callsign, cfg.affiliation.name, cfg.echelon.name, cfg.function.name, cfg.isHQ))
        val payload = JSONObject().apply {
            put("callsign", callsign)
            put("affiliation", cfg.affiliation.name)
            put("echelon", cfg.echelon.name)
            put("function", cfg.function.name)
            put("isHQ", cfg.isHQ)
            put("lat", lat)
            put("lon", lon)
            put("heading", heading)
            put("speed", speed)
            put("ts", ts)
            put("pub", myPublicKey)
            put("sig", sig)
        }
        val aad = "loc|$clientId".toByteArray(Charsets.UTF_8)
        val ct = SyncCrypto.encodeBase64(SyncCrypto.seal(key, payload.toString().toByteArray(Charsets.UTF_8), aad))
        val frame = JSONObject().apply {
            put("t", "loc")
            put("clientId", clientId)
            put("ct", ct)
        }.toString()
        return sendPresenceFrameIfConfigCurrent(cfg, frame)
    }

    private fun sendPresenceV3(loc: Location, retentionSeconds: Int): Boolean {
        if (_status.value != Status.CONNECTED) return false
        val key = roomKey ?: return false
        val keys = v3Keys ?: return false
        val actor = myActorId ?: return false
        val sd = sessionDomain ?: return false
        val cfg = presenceConfig
        if (!cfg.shareLocation) return false
        val callsign = boundCallsign(cfg.callsign)
        val lat = loc.latitude
        val lon = loc.longitude
        val heading = if (loc.hasBearing()) loc.bearing.toDouble() else 0.0
        val speed = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0
        if (presenceCounter >= VersionStamp.MAX_COUNTER) {
            failConnection()
            return false
        }
        val counter = ++presenceCounter
        val vs = VersionStamp(counter, actor)
        val payload = PresencePayloadV3(
            callsign = callsign,
            affiliation = cfg.affiliation.name,
            echelon = cfg.echelon.name,
            function = cfg.function.name,
            isHQ = cfg.isHQ,
            lat = lat,
            lon = lon,
            heading = heading,
            speed = speed,
        )
        if (!payload.isValid()) return false
        // Hash the exact bytes embedded below. Receivers never reserialize
        // these values with a platform-specific JSON number formatter.
        val exact = PresencePayloadV3.encode(payload)
        val payloadHash = SyncIdentity.sha256(exact.bytes)
        val preimage = SyncIdentity.buildPreimage(
            SyncIdentity.DOMAIN_PRESENCE, keys.roomIdRaw, actor, sd,
            VersionStamp.counterHex16(vs.counter), "", "loc", payloadHash)
        val sig = SyncSigning.sign(deviceSeed, preimage)
        val envelope = JSONObject()
        payload.putFlatFields(envelope)
        envelope.put("pv", PresencePayloadV3.ENVELOPE_VERSION)
        envelope.put("p", exact.standardBase64)
        envelope.put("pub", myPublicKey)
        envelope.put("sig", sig)
        val accuracyPayload = PresenceAccuracyV3.encodePayload(loc.accuracy.toDouble()) ?: return false
        val accuracyPreimage = SyncIdentity.buildPreimage(
            SyncIdentity.DOMAIN_PRESENCE, keys.roomIdRaw, actor, sd,
            VersionStamp.counterHex16(vs.counter), "", PresenceAccuracyV3.SIGNATURE_KIND,
            SyncIdentity.sha256(accuracyPayload),
        )
        envelope.put(PresenceAccuracyV3.VERSION_FIELD, PresenceAccuracyV3.ENVELOPE_VERSION)
        envelope.put(PresenceAccuracyV3.PAYLOAD_FIELD, SyncCrypto.encodeBase64(accuracyPayload))
        envelope.put(
            PresenceAccuracyV3.SIGNATURE_FIELD,
            SyncSigning.sign(deviceSeed, accuracyPreimage),
        )
        val retentionPayload = PresenceRetentionV3.encodePayload(retentionSeconds) ?: return false
        val retentionPreimage = SyncIdentity.buildPreimage(
            SyncIdentity.DOMAIN_PRESENCE, keys.roomIdRaw, actor, sd,
            VersionStamp.counterHex16(vs.counter), "", PresenceRetentionV3.SIGNATURE_KIND,
            SyncIdentity.sha256(retentionPayload),
        )
        envelope.put(PresenceRetentionV3.VERSION_FIELD, PresenceRetentionV3.ENVELOPE_VERSION)
        envelope.put(PresenceRetentionV3.PAYLOAD_FIELD, SyncCrypto.encodeBase64(retentionPayload))
        envelope.put(
            PresenceRetentionV3.SIGNATURE_FIELD,
            SyncSigning.sign(deviceSeed, retentionPreimage),
        )
        val aad = SyncCrypto.aadPresenceV3(actor, vs.encode())
        val ct = SyncCrypto.encodeBase64(
            SyncCrypto.seal(key, envelope.toString().toByteArray(Charsets.UTF_8), aad)
        )
        val frame = JSONObject().apply {
            put("t", "loc"); put("by", actor); put("ct", ct)
            put("pub", myPublicKey); put("sd", SyncIdentity.urlB64(sd)); put("vs", vs.encode())
        }.toString()
        return sendPresenceFrameIfConfigCurrent(cfg, frame)
    }

    /** The final consent check and WebSocket enqueue share the same lock as
     * durable config publication. Therefore an OFF update either waits for an
     * already-authorized enqueue, or wins first and prevents that enqueue; no
     * location frame can begin after the UI observes OFF. */
    private fun sendPresenceFrameIfConfigCurrent(
        expectedConfig: PresenceConfig,
        frame: String,
    ): Boolean = synchronized(presenceConfigLock) {
        if (!presenceConfig.shareLocation || presenceConfig != expectedConfig) {
            false
        } else {
            sendFrame(frame)
        }
    }

    private fun applyPresence(msg: JSONObject) {
        val peerId = msg.optString("clientId").ifEmpty { return }
        if (peerId == clientId) return
        val key = roomKey ?: return
        val ctB64 = msg.optString("ct").ifEmpty { return }
        if (ctB64.length > MAX_BASE64_BYTES) return
        val aad = "loc|$peerId".toByteArray(Charsets.UTF_8)
        val plain = SyncCrypto.open(key, SyncCrypto.decodeBase64(ctB64), aad) ?: return
        val obj = runCatching { JSONObject(String(plain, Charsets.UTF_8)) }.getOrNull() ?: return

        // A peer whose affiliation field is missing is UNKNOWN, never FRIEND -
        // don't paint an unidentified contact friendly-blue.
        val callsign = obj.optString("callsign", "")
        val affiliation = obj.optString("affiliation", "UNKNOWN")
        val echelon = obj.optString("echelon", "TEAM")
        val function = obj.optString("function", "INFANTRY")
        val isHQ = obj.optBoolean("isHQ", false)
        val lat = (obj.opt("lat") as? Number)?.toDouble() ?: return
        val lon = (obj.opt("lon") as? Number)?.toDouble() ?: return
        val heading = (obj.opt("heading") as? Number)?.toDouble() ?: return
        val speed = (obj.opt("speed") as? Number)?.toDouble() ?: return
        val ts = strictNonNegativeLong(obj, "ts") ?: return
        if (callsign.codePointCount(0, callsign.length) > 64) return
        if (!PresenceLocationQuality.hasValidWireValues(lat, lon, heading, speed)) return
        if (!PresenceLocationQuality.isPlausibleLegacyTimestamp(
                ts,
                System.currentTimeMillis(),
            )
        ) return

        // Per-device auth: pin the peer's key on first sight (TOFU), then require
        // every later presence to be signed by that same key. A room member
        // cannot impersonate an established peer; a changed key is rejected as a
        // possible swap; a relay replaying an old (signed) blob is caught by ts.
        val pub = obj.optString("pub").ifEmpty { return }
        val sig = obj.optString("sig").ifEmpty { return }
        val pinned = peerKeys[peerId]
        if (pinned != null && pinned != pub) return
        val signed = SyncSigning.presenceMessage(
            peerId, ts, lat, lon, heading, speed, callsign, affiliation, echelon, function, isHQ)
        if (!SyncSigning.verify(pub, signed, sig)) return
        // Pin only an actually verified first frame. A malformed first packet
        // must not poison this in-memory TOFU slot and hide the real unit.
        if (pinned == null) peerKeys[peerId] = pub
        val lastTs = peerTs[peerId]
        if (lastTs != null && ts <= lastTs) return  // replay / rollback
        peerTs[peerId] = ts

        val peer = PresencePeer(
            clientId = peerId,
            callsign = callsign,
            affiliation = affiliation,
            echelon = echelon,
            function = function,
            isHQ = isHQ,
            lat = lat,
            lon = lon,
            heading = heading,
            speed = speed,
            ts = ts
        )
        _peers.value = _peers.value + (peerId to peer)
    }

    // ----- Staleness sweep -----

    private fun startStalenessSweep() {
        stalenessSweepJob?.cancel()
        stalenessSweepJob = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(30_000)
                val nowMs = System.currentTimeMillis()
                val nowUptimeMs = System.nanoTime() / 1_000_000L
                val current = _peers.value
                val fresh = current.mapNotNull { (clientId, originalPeer) ->
                    val peer = PresenceExpiryPolicy.withFreshness(originalPeer, nowUptimeMs)
                    if (PresenceExpiryPolicy.shouldRetain(
                        peer = peer,
                        activeSessionDomain = activeSessions[clientId]?.second,
                        nowUptimeMs = nowUptimeMs,
                    )) clientId to peer else null
                }.toMap()
                if (fresh != current) {
                    _peers.value = fresh
                }
                val members = onlineMemberTracker.expireStaleMetadata(nowMs)
                if (members != _onlineMembers.value) {
                    _onlineMembers.value = members
                    publishChatRecipients()
                }
            }
        }
    }

    // ----- Presence config persistence -----
    //
    // Callsign + affiliation/echelon/function/HQ are unit identity - exactly
    // what a seized device shouldn't hand over in cleartext - so they're sealed
    // at rest (AES-256-GCM under the app data key) like waypoints. Prefs only
    // ever hold the sealed blob; a legacy install's plaintext keys are read once
    // then wiped on the next save.

    @SuppressLint("ApplySharedPref")
    private fun persistPresenceConfig(cfg: PresenceConfig): Boolean {
        val roomNames = JSONObject().apply {
            cfg.roomNamesById.entries.toList().takeLast(MAX_LOCAL_ROOM_NAMES).forEach { (id, name) ->
                if (id.isNotBlank() && id.length <= MAX_LOCAL_ROOM_ID_LENGTH) {
                    val bounded = boundRoomName(name).trim()
                    if (bounded.isNotBlank()) put(id, bounded)
                }
            }
        }
        val jsonStr = JSONObject()
            .put("roomNamesById", roomNames)
            .put("callsign", cfg.callsign)
            .put("shareLocation", cfg.shareLocation)
            .put("affiliation", cfg.affiliation.name)
            .put("echelon", cfg.echelon.name)
            .put("function", cfg.function.name)
            .put("isHQ", cfg.isHQ)
            .toString()
        val sealed = runCatching {
            Base64.encodeToString(
                SealedEnvelope.sealFile(
                    SafeStore.keyProvider.key(), jsonStr.toByteArray(Charsets.UTF_8), PRESENCE_LABEL),
                Base64.NO_WRAP
            )
        }.getOrNull() ?: return false // key locked/unavailable: preserve disk + legacy
        return DurablePreferenceCommit.preferences(
            preferences = prefs,
            keys = setOf(
                KEY_PRESENCE,
                "callsign",
                "shareLocation",
                "affiliation",
                "echelon",
                "function",
                "isHQ",
                "roomName",
            ),
            mutate = {
                putString(KEY_PRESENCE, sealed)
                    .remove("callsign").remove("shareLocation").remove("affiliation")
                    .remove("echelon").remove("function").remove("isHQ")
                    .remove("roomName")
            },
            publish = {},
        )
    }

    private fun loadPresenceConfig() {
        when (val decision = resolvePresenceConfigLoad(
            sealedStored = prefs.contains(KEY_PRESENCE),
            readSealed = ::readSealedPresenceConfig,
            readLegacy = ::readLegacyPresenceConfig,
        )) {
            PresenceConfigLoadDecision.RejectInvalidSealed -> {
                // Once a sealed record exists, legacy plaintext is never a
                // fallback: it could be older, attacker-restored, and have
                // Share my location enabled. Keep the fail-closed default.
                reportError(
                    "Saved Unit Sync identity/location sharing is locked or damaged. Location sharing remains off.",
                    SyncIssueKind.SECURITY,
                )
            }
            is PresenceConfigLoadDecision.UseSealed -> {
                presenceConfig = decision.config
                presenceConfigDurable = true
            }
            is PresenceConfigLoadDecision.MigrateLegacy -> {
                // First migration is one checked preference transaction: write
                // the sealed record and remove every plaintext key before
                // publication.
                if (persistPresenceConfig(decision.config)) {
                    presenceConfig = decision.config
                    presenceConfigDurable = true
                } else {
                    reportError(
                        "Could not migrate Unit Sync identity/location sharing to encrypted storage. Location sharing remains off.",
                        SyncIssueKind.SECURITY,
                    )
                }
            }
        }
    }

    private fun readSealedPresenceConfig(): PresenceConfig? {
        val stored = prefs.getString(KEY_PRESENCE, null) ?: return null
        val obj = runCatching {
            val blob = Base64.decode(stored, Base64.NO_WRAP)
            SealedEnvelope.openFile(SafeStore.keyProvider.key(), blob, PRESENCE_LABEL)
                ?.let { JSONObject(String(it, Charsets.UTF_8)) }
        }.getOrNull() ?: return null
        return PresenceConfig(
            roomNamesById = decodeRoomNames(obj.optJSONObject("roomNamesById")),
            callsign = obj.optString("callsign", ""),
            shareLocation = obj.optBoolean("shareLocation", false),
            affiliation = SymbolAffiliation.entries.firstOrNull { it.name == obj.optString("affiliation") }
                ?: SymbolAffiliation.FRIEND,
            echelon = SymbolEchelon.entries.firstOrNull { it.name == obj.optString("echelon") }
                ?: SymbolEchelon.TEAM,
            function = SymbolFunction.entries.firstOrNull { it.name == obj.optString("function") }
                ?: SymbolFunction.INFANTRY,
            isHQ = obj.optBoolean("isHQ", false)
        )
    }

    private fun readLegacyPresenceConfig(): PresenceConfig = PresenceConfig(
        callsign = prefs.getString("callsign", "") ?: "",
        shareLocation = prefs.getBoolean("shareLocation", false),
        affiliation = prefs.getString("affiliation", null)?.let { name ->
            SymbolAffiliation.entries.firstOrNull { it.name == name }
        } ?: SymbolAffiliation.FRIEND,
        echelon = prefs.getString("echelon", null)?.let { name ->
            SymbolEchelon.entries.firstOrNull { it.name == name }
        } ?: SymbolEchelon.TEAM,
        function = prefs.getString("function", null)?.let { name ->
            SymbolFunction.entries.firstOrNull { it.name == name }
        } ?: SymbolFunction.INFANTRY,
        isHQ = prefs.getBoolean("isHQ", false)
    )

    private fun decodeRoomNames(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val decoded = LinkedHashMap<String, String>()
        val keys = obj.keys()
        while (keys.hasNext() && decoded.size < MAX_LOCAL_ROOM_NAMES) {
            val id = keys.next()
            if (id.isBlank() || id.length > MAX_LOCAL_ROOM_ID_LENGTH) continue
            val name = boundRoomName(obj.optString(id, "")).trim()
            if (name.isNotBlank()) decoded[id] = name
        }
        return decoded
    }

    /** Stable Ed25519 seed. Locked/corrupt storage fails closed; never rotate. */
    private fun loadOrCreateDeviceSeed(): ByteArray {
        prefs.getString(KEY_DEVICE_SEED, null)?.let { stored ->
            val seed = try {
                SealedEnvelope.openFile(
                    SafeStore.keyProvider.key(), Base64.decode(stored, Base64.NO_WRAP), DEVICE_SEED_LABEL)
            } catch (t: Throwable) {
                throw IllegalStateException("sync signing seed unavailable", t)
            }
            if (seed == null || seed.size != 32) throw IllegalStateException("sync signing seed corrupt")
            return seed
        }
        val seed = SyncSigning.generateSeed()
        try {
            val sealed = Base64.encodeToString(
                SealedEnvelope.sealFile(SafeStore.keyProvider.key(), seed, DEVICE_SEED_LABEL), Base64.NO_WRAP)
            if (!DurablePreferenceCommit.preferences(
                    preferences = prefs,
                    keys = setOf(KEY_DEVICE_SEED),
                    mutate = { putString(KEY_DEVICE_SEED, sealed) },
                    publish = {},
                )
            ) {
                throw IllegalStateException("could not persist sync signing seed")
            }
        } catch (t: Throwable) {
            throw IllegalStateException("sync signing seed unavailable", t)
        }
        return seed
    }

    private fun resetSnapshot() {
        snapshotSeq = null
        snapshotSawFinalPage = false
        snapshotItemCount = 0
        snapshotInvalid = false
        pendingSnapshot.clear()
        snapshotAggregateBytes = 0L
        snapshotWireIds.clear()
    }

    private fun scheduleV2SnapshotTimeout(socket: SyncWebSocket, connectionGeneration: Long) {
        v2SnapshotTimeoutJob?.cancel()
        v2SnapshotTimeoutJob = scope.launch {
            kotlinx.coroutines.delay(V2_SNAPSHOT_TIMEOUT_MS)
            when (val event = v2SnapshotGate.timeout(socket, connectionGeneration)) {
                is V2SnapshotGateEvent.Rejected ->
                    failV2Snapshot(socket, connectionGeneration, event.reason)
                else -> Unit
            }
        }
    }

    /**
     * v3 has two relay-controlled phases before publishing is safe: the fenced
     * snapshot and the signed hello acknowledgement. Require progress in each
     * phase so an incompatible or half-open relay cannot leave the UI stuck in
     * SNAPSHOTTING forever. The socket identity and generation prevent an old
     * timeout from cancelling a newer authenticated session.
     */
    private fun scheduleV3HandshakeTimeout(socket: SyncWebSocket, connectionGeneration: Long) {
        v3HandshakeTimeoutJob?.cancel()
        v3HandshakeTimeoutJob = scope.launch {
            kotlinx.coroutines.delay(V3_HANDSHAKE_PROGRESS_TIMEOUT_MS)
            val pending = _status.value == Status.CONNECTING ||
                _status.value == Status.SNAPSHOTTING
            if (!isCurrentPendingV3Handshake(
                    expectedConnectionGeneration = connectionGeneration,
                    activeConnectionGeneration = activeConnectionGeneration,
                    expectedSocketIsCurrent = ws === socket,
                    protocolVersion = protocolVersion,
                    isHandshakePending = pending,
                )
            ) return@launch
            v3HandshakeTimeoutJob = null
            v3HandshakeFailureGeneration = connectionGeneration
            _status.value = Status.OFFLINE
            reportError(
                "Unit Sync secure handshake timed out. Check the relay or network; reconnecting automatically.",
                SyncIssueKind.CONNECTION,
                connectionGeneration,
            )
            socket.cancel()
        }
    }

    private fun failV2Snapshot(socket: SyncWebSocket, connectionGeneration: Long, reason: String) {
        if (ws !== socket || activeConnectionGeneration != connectionGeneration) return
        v2SnapshotTimeoutJob?.cancel(); v2SnapshotTimeoutJob = null
        v2SnapshotFailureGeneration = connectionGeneration
        _status.value = Status.OFFLINE
        reportError(
            "Unit Sync snapshot failed: $reason Verify the relay or network; reconnecting automatically.",
            SyncIssueKind.CONNECTION,
            connectionGeneration,
        )
        socket.cancel()
    }

    internal fun boundCallsign(value: String): String {
        val count = value.codePointCount(0, value.length)
        return if (count <= 64) value else value.substring(0, value.offsetByCodePoints(0, 64))
    }

    private fun failSnapshot() {
        v3HandshakeTimeoutJob?.cancel(); v3HandshakeTimeoutJob = null
        v3HandshakeFailureGeneration = activeConnectionGeneration
        awaitingHelloAck = false
        resetSnapshot()
        reportError(
            "Sync snapshot authentication failed. No unverified room data was applied; verify the room code and relay, then rejoin.",
            SyncIssueKind.SECURITY,
        )
        failConnection(clearPeers = true)
    }

    private fun failConnection(clearPeers: Boolean = false) {
        v3HandshakeTimeoutJob?.cancel(); v3HandshakeTimeoutJob = null
        cancelForegroundPresenceRefresh()
        awaitingHelloAck = false
        _status.value = Status.OFFLINE
        clearChatTransport(markPendingFailed = true)
        activeSessions.clear()
        remotePresenceCandidateClusters.clear()
        _onlineMembers.value = onlineMemberTracker.clear()
        if (clearPeers) _peers.value = emptyMap() else markPeersStale()
        ws?.cancel()
    }

    private fun persistenceFailure() {
        v3HandshakeTimeoutJob?.cancel(); v3HandshakeTimeoutJob = null
        cancelForegroundPresenceRefresh()
        awaitingHelloAck = false
        wantConnected = false
        _status.value = Status.OFFLINE
        clearChatTransport(markPendingFailed = true)
        activeSessions.clear()
        remotePresenceCandidateClusters.clear()
        _onlineMembers.value = onlineMemberTracker.clear()
        _peers.value = emptyMap()
        reportError(
            "Sync stopped because rollback state could not be secured. Check available storage, then leave and rejoin.",
            SyncIssueKind.SECURITY,
        )
        ws?.close(4014, "secure state unavailable")
    }

    private fun strictNonNegativeLong(obj: JSONObject, key: String): Long? {
        val raw = obj.opt(key) ?: return null
        val value = when (raw) {
            is Int -> raw.toLong()
            is Long -> raw
            else -> return null
        }
        return value.takeIf { it >= 0 }
    }

    /** Strict version parsing — rejects NaN, infinity, fractional, negative, and
     *  out-of-range values that optLong would silently coerce to 0 or truncate. */
    private fun strictVersion(rec: JSONObject, key: String): Long? {
        if (!rec.has(key)) return null
        val raw = rec.opt(key) ?: return null
        // reject strings, booleans, arrays, objects — only Number accepted
        if (raw !is Number) return null
        val d = raw.toDouble()
        if (!d.isFinite() || d != kotlin.math.floor(d) || d < 0 || d > MAX_VERSION.toDouble()) return null
        val v = raw.toLong()
        if (v < 0 || v > MAX_VERSION) return null
        return v
    }
}
