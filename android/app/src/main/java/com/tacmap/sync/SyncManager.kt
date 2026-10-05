package com.tacmap.sync

import com.tacmap.localization.LocalizedMessage

import com.tacmap.localization.Messages

import com.tacmap.localization.L10n

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
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
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
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
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** v2 wire ids may arrive in either case (shipped iOS sends uppercase), state is keyed lowercase. */
internal fun canonicalLegacySyncId(raw: String): String? = LegacyV2Ids.stateKey(raw)

/** Canonicalizes before the version lookup so casing can never create a second key.
 * Equal versions go to the larger `by`, same as the relay (plans/04 section 16). */
internal fun acceptedLegacySyncRecordId(
    rawId: String,
    version: Long,
    by: String,
    versions: Map<String, Long>,
    lastBy: Map<String, String>,
): String? {
    val id = canonicalLegacySyncId(rawId) ?: return null
    return id.takeIf { LegacyV2Ids.beats(version, by, versions[id], lastBy[id]) }
}

internal fun isValidLegacySyncPut(
    recordId: String,
    kind: String,
    parsed: GeoJsonImporter.Result,
): Boolean {
    canonicalLegacySyncId(recordId) ?: return false
    val objectId = when (kind) {
        "waypoint" -> parsed.waypoints.singleOrNull()?.id?.takeIf { parsed.drawings.isEmpty() }
        "drawing" -> parsed.drawings.singleOrNull()?.id?.takeIf { parsed.waypoints.isEmpty() }
        else -> null
    } ?: return false
    return LegacyV2Ids.embeddedMatches(recordId, objectId)
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
class SyncManager internal constructor(
    waypointStore: WaypointStore,
    drawingStore: DrawingStore,
    parentScope: CoroutineScope,
    private val env: SyncEnvironment,
) {
    constructor(
        waypointStore: WaypointStore,
        drawingStore: DrawingStore,
        parentScope: CoroutineScope,
        context: Context,
    ) : this(waypointStore, drawingStore, parentScope, SyncEnvironment.android(context))

    enum class Status { OFFLINE, CONNECTING, SNAPSHOTTING, CONNECTED }

    private var waypointStoreRef: WaypointStore? = waypointStore
    private var drawingStoreRef: DrawingStore? = drawingStore
    private val waypointStore: WaypointStore get() = checkNotNull(waypointStoreRef) { "sync disposed" }
    private val drawingStore: DrawingStore get() = checkNotNull(drawingStoreRef) { "sync disposed" }
    /** Drawing widths are stored as renderer pixels on Android but travel as
     * screen-independent units. Keep one application-context density for every
     * Sync import/export boundary; never rewrite the existing persisted file. */
    private val displayDensity = env.displayDensity
    private val syncClock: SyncClock = env.clock
    // Keep the transport independent of a Compose scope so the app-scoped
    // runtime can retain one already-authenticated v3 presence session while
    // MapScreen is removed for the mission-key lock. dispose() is still the
    // sole terminal owner of this job.
    private val managerJob = SupervisorJob()
    private val scope = CoroutineScope(
        parentScope.coroutineContext + managerJob + env.dispatcher
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
    private val _lastError = MutableStateFlow<LocalizedMessage?>(null)
    val lastError: StateFlow<LocalizedMessage?> = _lastError.asStateFlow()
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
    /** Test seam, the JVM can't build a Location. Wins over [locationProvider] when set. */
    internal var locationSampleProvider: (() -> PresenceFixSample?)? = null

    private fun currentLocationSample(): PresenceFixSample? =
        locationSampleProvider?.invoke() ?: locationProvider?.invoke()?.let(PresenceFixSample::from)

    /** Installed only by [UnitSyncRuntime]. Direct test clients retain the
     * historical standalone lifecycle. */
    internal var runtimeStateChanged: (() -> Unit)? = null
    internal var backgroundTransportEnded: (() -> Unit)? = null

    private val appFilesDir: File = env.filesDir
    private val chatHistoryStore = env.chatHistoryStore
    val chatMessages: StateFlow<List<TacMapChatMessage>> = chatHistoryStore.messages
    /** Aggregate unread metadata for chrome; no message content is exposed here. */
    val unreadChatMessageCount: StateFlow<Int> = chatHistoryStore.unreadCount
    internal val chatHistoryAvailability: StateFlow<TacMapChatHistoryAvailability> =
        chatHistoryStore.availability
    val chatHistoryIssue: StateFlow<LocalizedMessage?> = chatHistoryStore.issue
    private val _chatRecipients = MutableStateFlow<Map<String, TacMapChatTarget.SelectedUnit>>(emptyMap())
    val chatRecipients: StateFlow<Map<String, TacMapChatTarget.SelectedUnit>> =
        _chatRecipients.asStateFlow()
    private val _chatSessionReady = MutableStateFlow(false)
    val chatSessionReady: StateFlow<Boolean> = _chatSessionReady.asStateFlow()
    private val _chatAvailabilityMessage = MutableStateFlow<LocalizedMessage?>(
        Messages.chatJoinAConnectedVUnitSyncRoomToUseMessage()
    )
    val chatAvailabilityMessage: StateFlow<LocalizedMessage?> = _chatAvailabilityMessage.asStateFlow()
    private val prefs = env.preferences
    private val clientId: String = prefs.getString("clientId", null)
        ?: UUID.randomUUID().toString().also { prefs.edit().putString("clientId", it).apply() }

    private val webSocketTransport = env.transportFactory

    private var ws: SyncWebSocket? = null
    private val inboundFrameCloseGate = SyncInboundFrameCloseGate()
    private val receiveBudget = SyncReceiveBudget()
    private var roomKey: ByteArray? = null
    private var authToken: String? = null
    // Resolved from OPSEC settings at join time so a self-hoster's relay is
    // actually used; falls back to ours. Kept for the reconnect path.
    private var relayBase: String = RELAY_BASE
    private var wantConnected = false
    private val lifecycleGate = SyncLifecycleGate()
    private var reconnectJob: Job? = null
    private var reconnectRoomId: String? = null
    private val backoff = SyncBackoffPolicy(env.random)
    private val failureCounters = SyncFailureCounters()
    private val handshakeWatchdog = SyncHandshakeWatchdog()
    private var watchdogJob: Job? = null
    private var stableSessionJob: Job? = null
    // written on the transport reader thread, read by the watchdog
    private val lastInboundProgressMs = java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE)
    private var socketOpened = false
    private var localCloseGeneration: Long? = null
    private var localCloseReason: SyncLocalClose? = null
    private var nextSessionAfter4008 = false
    private var rejectedHelloEpoch: java.math.BigInteger? = null
    private var sessionHelloEpoch: java.math.BigInteger? = null
    private var livenessProbeJob: Job? = null
    private val liveWindowResync = LiveWindowResyncPolicy()
    private var liveResyncJob: Job? = null
    private val _pausedActionRequired = MutableStateFlow(false)
    /** Sync stopped on its own (room full, identity rejected, ...). The dialog shows Retry. */
    val pausedActionRequired: StateFlow<Boolean> = _pausedActionRequired.asStateFlow()
    private var pausedRetryOnForeground = false
    /** Hooked up by UnitSyncRuntime; reserves the background spare epochs (plans/04 section 14). */
    internal var backgroundPresenceOptIn: () -> Boolean = { false }

    // outbound pacing (plans/04 section 11)
    private var pacer = SyncOutboundPacer<OutboundFrame>(0.0)
    private var pumpJob: Job? = null
    private val ackTimer = SyncAckTimer()
    private var ackCheckJob: Job? = null
    private var writePollJob: Job? = null
    private var enqueuedWireBytes = 0L
    private val pendingWrites = LinkedHashMap<String, Long>() // rid -> stream offset of its last byte
    private val writtenUnacked = LinkedHashSet<String>()
    private var diffJob: Job? = null

    // scoped to one room membership, cleared on join and leave
    private val surfacedIssueKeys = HashSet<String>()
    private val skippedWireIds = HashMap<String, SnapshotRecordCategory>()
    private val suppressedUntilLocalEdit = HashMap<String, Long>() // localId -> journal generation
    private var mutationsPaused = false
    private val lastByV2 = HashMap<String, String>()
    // v2 casing a 2.x iOS sender used per object, sticky and sealed per room (plans/04 section 16)
    private var legacyV2Ids: LegacyV2IdStore? = null
    private var observeJob: Job? = null
    private var revisionJob: Job? = null
    private val modelRevisionJournal = LocalModelRevisionJournal(appFilesDir)
    private var revisionJournalAvailable = false
    private var revisionJournalLoad: kotlinx.coroutines.Deferred<Boolean>? = null
    private var foregroundAttachGeneration = 0L

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
    private var snapshotSeqRegressed = false
    private var pendingVerifiedClean = true
    // off-main record checks for the snapshot in flight (plans/04 section 19)
    private var snapshotRun: SnapshotRun? = null
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
    private val snapshotConfirmedLocalDeletes = HashMap<String, String>()
    private val forcedLocalDiff = HashSet<String>()
    private val forcedLegacyDeletes = HashMap<String, LegacyDeleteRecovery>()
    private var resolvingPendingModel = false
    private val outboundDeliveries = OutboundDeliveryTracker()
    private val v2SnapshotGate = V2SnapshotGate(MAX_SNAPSHOT_ITEMS, MAX_SNAPSHOT_AGGREGATE_BYTES)
    private var v2SnapshotTimeoutJob: Job? = null
    private var v2SnapshotFailureGeneration: Long? = null
    private var v3HandshakeFailureGeneration: Long? = null
    private var lastGoodLocalPresenceFix: PresenceLocationFix? = null
    private var localPresenceCandidateCluster: PresenceCandidateCluster? = null
    private val remotePresenceCandidateClusters = HashMap<String, PresenceCandidateCluster>()

    // SP3 (plans/04 sections 1, 17-21)
    private var wireIndex: WireIdIndex? = null
    private var liveValidator: SnapshotValidator? = null
    private var liveBatch: LiveBatch? = null
    private val inbound = InboundQueue()
    private val presencePolicy = PresenceSendPolicy()
    private var presenceFlushJob: Job? = null
    private var joinJob: Job? = null
    private var joinToken = 0L
    private var backgroundProbeJob: Job? = null
    private var backgroundPausedAtWallMs: Long? = null
    /** UnitSyncRuntime swaps the service notification for a paused one (section 21.5). */
    internal var backgroundPresencePaused: ((pausedAtWallMs: Long) -> Unit)? = null

    // presence-only screen-off reconnect (section 21.4), off until doc change D1
    private val backgroundReconnectEnabled = env.backgroundReconnectEnabled
    /** Epoch of the last foreground hello and how many spares above it are on disk already. */
    private var lastForegroundHelloEpoch: java.math.BigInteger? = null
    private var lastForegroundSpareCount = 0
    private var backgroundReconnect: BackgroundReconnect? = null
    private var backgroundSession: BackgroundSession? = null
    private var backgroundInterval = com.tacmap.settings.BackgroundUnitSyncInterval.DEFAULT
    /** Newest screen-off GPS sample and when it arrived, so a session that comes back can send it. */
    private var lastBackgroundSample: PresenceFixSample? = null
    private var lastBackgroundSampleAtMs = Long.MIN_VALUE
    private var backgroundWakeLockHeld = false

    /** Armed at background entry when a drop may be followed by a new presence-only session. */
    private class BackgroundReconnect(lastForegroundEpoch: java.math.BigInteger, spares: Int) {
        val policy = BackgroundPresencePolicy(reconnectEnabled = true)
        val spares = BackgroundSpareEpochs(lastForegroundEpoch, spares)
        var dropped = false
        var droppedAtWallMs: Long? = null
    }

    /**
     * The handshake of one presence-only session (21.4). The snapshot is
     * drained, not read: fence order and the 4 MiB ceiling only, no record is
     * opened, verified, applied or stored. Then hello, hello-ack, loc only.
     */
    private class BackgroundSession(val epoch: java.math.BigInteger) {
        enum class Phase { AWAITING_BEGIN, RECEIVING, FINAL_PAGE, AWAITING_HELLO_ACK, LIVE }
        var phase = Phase.AWAITING_BEGIN
        var seq: Long? = null
        var bytes = 0L
    }

    /** What the pacer holds. Deliveries carry their rid so acks and retries line up. */
    private sealed interface OutboundFrame {
        val text: String

        class Plain(
            override val text: String,
            /** Checked right before the write, e.g. presence consent. */
            val guard: (() -> Boolean)? = null,
            val afterWrite: (() -> Unit)? = null,
        ) : OutboundFrame

        class Delivery(
            override val text: String,
            val requestId: String,
            val localId: String,
        ) : OutboundFrame
    }

    /** What the transport hands over, kept in arrival order with everything else from that socket. */
    private sealed class InboundEvent(val socket: SyncWebSocket, val generation: Long) {
        class Text(socket: SyncWebSocket, generation: Long, val text: String) : InboundEvent(socket, generation)
        class Binary(socket: SyncWebSocket, generation: Long, val size: Int) : InboundEvent(socket, generation)
        class Opened(socket: SyncWebSocket, generation: Long) : InboundEvent(socket, generation)
        class Ended(socket: SyncWebSocket, generation: Long, val roomId: String, val end: SocketEnd) :
            InboundEvent(socket, generation)
    }

    /**
     * Bounded FIFO between the reader threads and the protocol worker
     * (plans/04 section 1.1). A reader is let go as soon as its frame is
     * queued; only a full queue makes it wait, and the transport gate cuts a
     * reader that waits 60 s. One worker drains it in batches of up to 64.
     */
    private inner class InboundQueue {
        private val lock = Any()
        private val events = ArrayDeque<InboundEvent>()
        private val waiting = ArrayDeque<Pair<InboundEvent, () -> Unit>>()
        private var frames = 0
        private var bytes = 0L
        private var draining = false

        // frames are base64 and JSON, so chars are bytes near enough
        private fun size(event: InboundEvent): Long = when (event) {
            is InboundEvent.Text -> event.text.length.toLong()
            is InboundEvent.Binary -> event.size.toLong()
            else -> 0L
        }

        private fun isFrame(event: InboundEvent) = event is InboundEvent.Text || event is InboundEvent.Binary

        private fun fits(event: InboundEvent): Boolean = !isFrame(event) || frames == 0 ||
            (frames < INBOUND_QUEUE_MAX_FRAMES && bytes + size(event) <= INBOUND_QUEUE_MAX_BYTES)

        private fun push(event: InboundEvent) {
            events.addLast(event)
            if (isFrame(event)) {
                frames += 1
                bytes += size(event)
            }
        }

        /** Reader thread. [consumed] releases the reader, now or once there's room. */
        fun offer(event: InboundEvent, consumed: () -> Unit) {
            val release: Boolean
            val start: Boolean
            synchronized(lock) {
                release = waiting.isEmpty() && fits(event)
                if (release) push(event) else waiting.addLast(event to consumed)
                start = !draining
                draining = true
            }
            if (release) consumed()
            if (start) startDrain()
        }

        /** Opens and closes never wait, they're tiny and must not sit behind a full queue. */
        fun offerControl(event: InboundEvent) {
            val start: Boolean
            synchronized(lock) {
                push(event)
                start = !draining
                draining = true
            }
            if (start) startDrain()
        }

        /** Next batch, or null (and the worker stops) once the queue is empty. */
        fun takeBatch(max: Int): List<InboundEvent>? {
            val released = ArrayList<() -> Unit>()
            val batch: List<InboundEvent>?
            synchronized(lock) {
                if (events.isEmpty()) {
                    draining = false
                    batch = null
                } else {
                    val out = ArrayList<InboundEvent>(minOf(max, events.size))
                    while (out.size < max && events.isNotEmpty()) {
                        val event = events.removeFirst()
                        if (isFrame(event)) {
                            frames -= 1
                            bytes -= size(event)
                        }
                        out += event
                    }
                    while (waiting.isNotEmpty() && fits(waiting.first().first)) {
                        val (event, consumed) = waiting.removeFirst()
                        push(event)
                        released += consumed
                    }
                    batch = out
                }
            }
            released.forEach { it() }
            return batch
        }

        fun stopped() {
            synchronized(lock) { draining = false }
        }

        fun clear() {
            val released: List<() -> Unit>
            synchronized(lock) {
                events.clear()
                frames = 0
                bytes = 0L
                released = waiting.map { it.second }
                waiting.clear()
            }
            // the socket is going away anyway, don't leave its reader parked for 60 s
            released.forEach { it() }
        }
    }

    /**
     * Consecutive live put/del/loc/hello frames of one inbound batch share one
     * replay transaction (plans/04 section 1.3). Peers, online members and the
     * model only change after that transaction is durable.
     */
    private class LiveBatch(
        val replay: SyncReplayState,
        val layers: MutableList<com.tacmap.drawings.DrawingLayer>,
        lookupOnFirstUse: () -> ModelLookup,
    ) {
        val records = ArrayList<ValidatedV3>()
        /**
         * The committed stores, id indexed, built once the first record needs
         * it. Nothing in the batch touches the model before the flush, so every
         * record's prior hash and kind come from here instead of a store scan
         * per record (S5-03). A presence-only batch never builds it.
         */
        val before: ModelLookup by lazy(LazyThreadSafetyMode.NONE, lookupOnFirstUse)
        /** This batch's own puts, a later record in it sees them for the collision check. */
        val stagedKinds = HashMap<String, String>()

        fun localKind(localId: String): String? = stagedKinds[localId] ?: before.kind(localId)
        fun localIdOf(canonical: String): String? = before.localIdOf(canonical)
        var peers: Map<String, PresencePeer>? = null
        var onlineMembers: Map<String, OnlineMember>? = null
        var chatRecipientsDirty = false
        var failed = false
    }

    /**
     * One snapshot's record checks on the validation worker (plans/04 section
     * 19): sequential in item order, layers staged as they go, nothing
     * committed until snapshot-end. Pages get validated as they arrive.
     */
    private class SnapshotRun(
        private val validator: SnapshotValidator,
        val committedLayers: List<com.tacmap.drawings.DrawingLayer>,
        /** localId -> kind at snapshot-begin, a copy the worker can read safely. */
        private val localKinds: Map<String, String>,
        /** lowercase UUID -> stored id for objects kept in another casing, same snapshot-begin copy */
        private val localIdAliases: Map<String, String>,
        private val stageEligible: (SyncReplayState.AuthenticatedMutation) -> Boolean,
        parent: Job,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        private val onProgress: () -> Unit,
    ) {
        private val job = SupervisorJob(parent)
        private val workerScope = CoroutineScope(job + dispatcher)
        private val staged = ArrayList(committedLayers)
        // written by the worker, read on the protocol thread only after join()
        private val results = ArrayList<V3Check>()
        private var tail: Job? = null
        private var submitted = 0

        init {
            job.invokeOnCompletion { validator.close() }
        }

        fun submit(items: List<Pair<JSONObject, String>>) {
            submitted += items.size
            val previous = tail
            tail = workerScope.launch {
                previous?.join()
                for ((rec, wireId) in items) {
                    val check = try {
                        SnapshotRecordClassifier.classify(validator, rec, wireId, staged, localKinds::get, localIdAliases::get)
                    } catch (cancel: kotlinx.coroutines.CancellationException) {
                        throw cancel
                    } catch (_: Throwable) {
                        // couldn't check it, so it's not trusted. same as the relay leaving it out
                        V3Check.Skip(wireId, SnapshotRecordReason.INNER_JSON_INVALID)
                    }
                    if (check is V3Check.Valid && stageEligible(check.record.mutation)) {
                        SnapshotValidator.stage(staged, check)
                    }
                    results += check
                }
                onProgress()
            }
        }

        /** Every submitted record, in item order, or null if the worker didn't finish them all. */
        suspend fun await(): List<V3Check>? {
            tail?.join()
            return if (results.size == submitted) ArrayList(results) else null
        }

        /** Cancels pending checks; the validator zeroes its key copies once the worker is idle. */
        fun close() {
            job.cancel()
        }
    }

    /** Id-indexed view of the committed stores so a batch never scans them per record. */
    private inner class ModelLookup {
        val document: DrawingDocument = drawingStore.committedDocument.value
        private val waypointList: List<Waypoint> = waypointStore.committedWaypoints.value
        val waypoints: Map<String, Waypoint> = waypointList.associateBy { it.id }
        val features: Map<String, DrawingFeature> = document.features.associateBy { it.id }

        /** Committed values are immutable, so same instances means nothing moved since this was built. */
        fun isCurrent(): Boolean =
            drawingStoreRef?.committedDocument?.value === document &&
                waypointStoreRef?.committedWaypoints?.value === waypointList
        // the view never changes, so each object gets exported and hashed once at most
        private val hashes = HashMap<String, String?>()

        fun export(id: String?): String {
            id ?: return ""
            waypoints[id]?.let {
                return GeoJsonExporter.export(listOf(it), emptyList(), document.layers, density = displayDensity)
            }
            features[id]?.let {
                return GeoJsonExporter.export(emptyList(), listOf(it), document.layers, density = displayDensity)
            }
            return ""
        }

        fun hash(id: String?): String? {
            id ?: return null
            if (hashes.containsKey(id)) return hashes[id]
            val content = export(id)
            val hash = if (content.isEmpty()) null
            else SyncIdentity.bytesToHex(SyncIdentity.sha256(content.toByteArray(Charsets.UTF_8)))
            hashes[id] = hash
            return hash
        }

        fun kind(id: String): String? = when {
            id in waypoints -> "waypoint"
            id in features -> "drawing"
            else -> null
        }

        private val caseAliases: Map<String, String> by lazy(LazyThreadSafetyMode.NONE) {
            SnapshotValidator.caseAliases(waypoints.keys + features.keys) { it in waypoints || it in features }
        }

        /** the id an object with this lowercase UUID is stored under, when that isn't lowercase */
        fun localIdOf(canonical: String): String? = caseAliases[canonical]
    }

    private var presenceJob: Job? = null
    private var stalenessSweepJob: Job? = null
    private val presenceCadence = UnitSyncPresenceCadence()
    private val foregroundPresenceLiveness = ForegroundPresenceLiveness()
    private val foregroundGpsFixRequester = env.foregroundFixRequester
    @Volatile private var backgroundPresenceOnly = false
    @Volatile private var awaitingForegroundStores = false

    init {
        // chat fences for sessions the replay state has moved past get pruned
        chatHistoryStore.durableSessionDomain = { actor -> replayState?.getPresenceSessionDomain(actor) }
        loadPresenceConfig()
        migrateLegacyLocalStoresAfterUnlock()
        reloadRevisionJournal()
        startModelRevisionObservation()
    }

    private fun reloadRevisionJournal() {
        revisionJournalAvailable = false
        revisionJournalLoad = scope.async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            val ok = modelRevisionJournal.loadOffMain(env.persistenceDispatcher)
            if (!lifecycleGate.isDisposed) revisionJournalAvailable = ok
            ok
        }
    }

    // ----- Public API -----

    internal val isDisposed: Boolean get() = lifecycleGate.isDisposed
    internal val isBackgroundPresenceOnly: Boolean get() = backgroundPresenceOnly
    /** Read-only peeks for the state machine tests. */
    internal val currentIssueKind: SyncIssueKind? get() = issueLifecycle.issue?.kind
    internal val replayStateForTests: SyncReplayState? get() = replayState
    internal val myActorIdForTests: String? get() = myActorId
    /** Issue codes surfaced this join (the once-per-scope set, scope stripped). */
    internal val surfacedIssueCodesForTests: Set<String>
        get() = surfacedIssueKeys.mapTo(HashSet()) { it.substringBefore('|') }
    internal fun skippedCategoryForTests(wireId: String): SnapshotRecordCategory? = skippedWireIds[wireId]
    internal val lastSnapshotVerifiedCleanForTests: Boolean get() = pendingVerifiedClean
    internal fun rememberedV2IdForTests(stateKey: String): String? = legacyV2Ids?.remembered(stateKey)
    internal val mutationsPausedForTests: Boolean get() = mutationsPaused

    /** The dialog's Retry after a stop. Clears every failure counter and connects. */
    fun retryAfterPause() {
        if (lifecycleGate.isDisposed || !_pausedActionRequired.value) return
        val roomId = activeRoomStorageId ?: return
        clearPausedState()
        retireStopIssue()
        failureCounters.reset()
        backoff.reset()
        rejectedHelloEpoch = null
        if (backgroundPresenceOnly || awaitingForegroundStores) return
        wantConnected = true
        connect(roomId)
    }

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

        val transitionNow = syncClock.elapsedRealtimeNanos()
        val transitionLocation = currentLocationSample()?.takeIf { location ->
            location.provider == android.location.LocationManager.GPS_PROVIDER &&
                ForegroundPresenceLiveness.isBridgeableFix(
                    candidateFixElapsedRealtimeNanos = location.elapsedRealtimeNanos,
                    nowElapsedRealtimeNanos = transitionNow,
                )
        }
        // This gate is deliberately first. Production callbacks and lifecycle
        // work share the main dispatcher, so no later inbound frame can reach a
        // store once teardown begins.
        backgroundPresenceOnly = true
        awaitingForegroundStores = false
        // clean point for the presence fences, the key is still unlocked here (17.1)
        persistPresenceCleanPoint()
        detachMissionStateForKeyLock()
        presenceCadence.reset()
        presencePolicy.reset()
        backgroundPausedAtWallMs = null
        backgroundInterval = interval
        // a later drop may come back on one of the spares the last foreground
        // hello already put on disk (21.4); with the switch off it pauses (21.5)
        clearBackgroundReconnect()
        if (backgroundReconnectEnabled) {
            lastForegroundHelloEpoch?.let { backgroundReconnect = BackgroundReconnect(it, lastForegroundSpareCount) }
        }
        // pings every 60 s now; the wake probe in sendBackgroundPresence does the real check (21.3)
        ws?.setKeepaliveSeconds((BackgroundPresencePolicy.BACKGROUND_PING_INTERVAL_MS / 1_000L).toInt())
        // The previous foreground frame expires after 45 seconds. Bridge to the
        // selected screen-off retention with the newest GPS fix that's at most
        // two minutes old (section 21.1); otherwise wait for the service's next
        // real GPS callback. The fix keeps its own timestamp, nothing restamped.
        transitionLocation?.let { location ->
            sendPresenceAtCadence(
                sample = location,
                isBackground = true,
                backgroundInterval = interval,
                nowElapsedRealtimeNanos = transitionNow,
            )
        }
        return true
    }

    /** Keep a joined room (v2 or v3) across an Activity pause with no socket
     * and no egress. Room keys, replay state and chat binding stay in memory
     * only; the DataKey and mission stores go (plans/04 section 13, S2-01). */
    internal fun suspendUntilForegroundStores(): Boolean {
        if (lifecycleGate.isDisposed || _room.value == null) return false

        val storesAttached = waypointStoreRef != null && drawingStoreRef != null
        val alreadyRestricted = backgroundPresenceOnly && awaitingForegroundStores &&
            waypointStoreRef == null && drawingStoreRef == null
        if (!storesAttached && !alreadyRestricted) return false

        backgroundPresenceOnly = true
        awaitingForegroundStores = true
        if (storesAttached) {
            persistPresenceCleanPoint()
            detachMissionStateForKeyLock()
        }
        presenceCadence.reset()
        presencePolicy.reset()
        clearBackgroundReconnect()
        closeSocketForLifecycleTransition("waiting for foreground unlock")
        return true
    }

    private fun detachMissionStateForKeyLock() {
        foregroundAttachGeneration += 1
        reconnectJob?.cancel(); reconnectJob = null
        // nothing durable gets written behind the key lock, not even a presence flush
        presenceFlushJob?.cancel(); presenceFlushJob = null
        observeJob?.cancel(); observeJob = null
        revisionJob?.cancel(); revisionJob = null
        presenceJob?.cancel(); presenceJob = null
        stalenessSweepJob?.cancel(); stalenessSweepJob = null
        v2SnapshotTimeoutJob?.cancel(); v2SnapshotTimeoutJob = null
        cancelForegroundPresenceRefresh()
        diffJob?.cancel(); diffJob = null
        clearOutboundDeliveries(markForReconciliation = true)
        resetSnapshot()
        dropSessionIndexes()
        versions.clear()
        lastByV2.clear()
        lastContent.clear()
        exportCache.clear()
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
        foregroundAttachGeneration += 1
        awaitingForegroundStores = true
        backgroundPresenceOnly = true
        // the foreground hello reserves above every spare, so a background session just ends
        clearBackgroundReconnect()
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
        presencePolicy.reset()
        backgroundProbeJob?.cancel(); backgroundProbeJob = null
        clearBackgroundReconnect()
        migrateLegacyLocalStoresAfterUnlock()
        backgroundPausedAtWallMs?.let { pausedAt ->
            backgroundPausedAtWallMs = null
            surfaceIssue(SyncIssueCode.BACKGROUND_PAUSED, Messages.syncBackgroundPausedMessage(formatPauseTime(pausedAt)))
        }

        awaitingForegroundStores = true
        backgroundPresenceOnly = true
        val attachGeneration = ++foregroundAttachGeneration
        val attachJoinToken = joinToken
        reloadRevisionJournal()
        startModelRevisionObservation()
        scope.launch {
            val available = revisionJournalLoad?.await() == true
            if (lifecycleGate.isDisposed || foregroundAttachGeneration != attachGeneration || joinToken != attachJoinToken ||
                waypointStoreRef !== newWaypointStore || drawingStoreRef !== newDrawingStore) return@launch
            if (!available) { persistenceFailure(); return@launch }
            backgroundPresenceOnly = false
            awaitingForegroundStores = false
            val roomId = activeRoomStorageId
            if (roomId != null && _room.value != null) {
                if (protocolVersion == 3) chatHistoryStore.open(roomId)
                startObserving()
                startPresenceBroadcast()
                startStalenessSweep()
                if (_pausedActionRequired.value) {
                    if (!pausedRetryOnForeground) return@launch
                    clearPausedState()
                    retireStopIssue()
                    failureCounters.reset()
                    backoff.reset()
                }
                wantConnected = true
                connect(roomId)
            }
        }
        return true
    }

    /** Close the session that advertised an extended location lifetime. If the
     * app is unlocked, rotate to a new foreground session; while locked, never
     * reconnect or touch mission state. */
    internal fun revokeBackgroundLocationEligibility(reconnectIfForeground: Boolean) {
        if (lifecycleGate.isDisposed || protocolVersion != 3 || _room.value == null) return
        val roomId = activeRoomStorageId
        // lost eligibility ends any presence-only session for good (21.4)
        clearBackgroundReconnect()
        closeSocketForLifecycleTransition("background location disabled")
        presenceCadence.reset()
        if (reconnectIfForeground && !backgroundPresenceOnly && !awaitingForegroundStores &&
            waypointStoreRef != null && drawingStoreRef != null && roomId != null
        ) {
            wantConnected = true
            connect(roomId)
        }
    }

    /**
     * Called only with a fresh callback from the foreground location service.
     *
     * The CPU sleeps between background fixes and the library pinger doesn't
     * run then, so the socket can be dead without anyone noticing (S2-06).
     * If nothing came in for 75 s, hold a short wake lock, ping, and only send
     * once a pong or frame shows up; silence means the socket is dead and
     * background sharing pauses loudly (plans/04 sections 21.3 and 21.5), or
     * with the 21.4 switch on, this fix brings up a presence-only session.
     */
    internal fun sendBackgroundPresence(
        location: Location,
        interval: com.tacmap.settings.BackgroundUnitSyncInterval,
        nowElapsedRealtimeNanos: Long = syncClock.elapsedRealtimeNanos(),
    ): Boolean = sendBackgroundPresence(PresenceFixSample.from(location), interval, nowElapsedRealtimeNanos)

    internal fun sendBackgroundPresence(
        location: PresenceFixSample,
        interval: com.tacmap.settings.BackgroundUnitSyncInterval,
        nowElapsedRealtimeNanos: Long = syncClock.elapsedRealtimeNanos(),
    ): Boolean {
        if (!backgroundPresenceOnly || awaitingForegroundStores ||
            protocolVersion != 3 || !presenceConfig.shareLocation
        ) return false
        backgroundInterval = interval
        lastBackgroundSample = location
        lastBackgroundSampleAtMs = nowMs()
        backgroundReconnect?.let { reconnect ->
            // the next send opportunity after a drop (21.4)
            if (ws == null && reconnect.dropped) return backgroundReconnectDue(reconnect)
        }
        if (_status.value != Status.CONNECTED) return false
        val socket = ws ?: return false
        val now = nowMs()
        val lastInbound = lastInboundProgressMs.get().takeIf { it != Long.MIN_VALUE }
        if (!BackgroundPresencePolicy.needsProbe(now, lastInbound)) {
            return sendPresenceAtCadence(
                sample = location,
                isBackground = true,
                backgroundInterval = interval,
                nowElapsedRealtimeNanos = nowElapsedRealtimeNanos,
            )
        }
        if (backgroundProbeJob?.isActive == true) return false
        val wakeLock = env.wakeLock
        wakeLock?.acquire(BackgroundPresencePolicy.WAKE_LOCK_MAX_MS)
        if (!socket.sendPing()) {
            wakeLock?.release()
            closeLocally(SyncLocalClose.LIVENESS_TIMEOUT, socket)
            return false
        }
        val generation = activeConnectionGeneration
        backgroundProbeJob = scope.launch {
            try {
                val deadline = now + BackgroundPresencePolicy.PROBE_PONG_TIMEOUT_MS
                while (lastInboundProgressMs.get() < now && nowMs() < deadline) {
                    kotlinx.coroutines.delay(BACKGROUND_PROBE_POLL_MS)
                }
                lifecycleGate.runIfActive {
                    if (ws !== socket || activeConnectionGeneration != generation || !backgroundPresenceOnly) {
                        return@runIfActive
                    }
                    if (lastInboundProgressMs.get() >= now) {
                        sendPresenceAtCadence(
                            sample = location,
                            isBackground = true,
                            backgroundInterval = interval,
                            nowElapsedRealtimeNanos = syncClock.elapsedRealtimeNanos(),
                        )
                    } else {
                        // half-open or reset socket: close it, handleSocketEnded pauses and
                        // says so, or comes back on a spare epoch when 21.4 is on
                        closeLocally(SyncLocalClose.LIVENESS_TIMEOUT, socket)
                    }
                }
            } finally {
                wakeLock?.release()
            }
        }
        return true
    }

    // ----- Presence-only background reconnect (plans/04 section 21.4) -----

    private fun backgroundReconnectDue(reconnect: BackgroundReconnect): Boolean {
        val action = reconnect.policy.onFixDue(
            nowMs = nowMs(),
            socketUsable = false,
            sparesLeft = reconnect.spares.left,
            eligible = canArmBackgroundLocationService(),
        )
        return when (action) {
            BackgroundPresencePolicy.Action.CONNECT -> openBackgroundPresenceSession(reconnect)
            BackgroundPresencePolicy.Action.PAUSE -> {
                pauseBackgroundPresence()
                false
            }
            BackgroundPresencePolicy.Action.WAIT, BackgroundPresencePolicy.Action.SEND -> false
        }
    }

    /**
     * Fresh in-memory session domain on a spare epoch the foreground already
     * made durable. Nothing on this session writes to disk, opens a record or
     * sends anything but hello and loc. Uses only the room keys and signing
     * seed the screen-off path keeps anyway, never the DataKey or a store.
     */
    private fun openBackgroundPresenceSession(reconnect: BackgroundReconnect): Boolean {
        val roomId = activeRoomStorageId
        val base = validatedRelayBaseForRuntime(relayBase)
        if (roomId == null || base == null || v3Keys == null || myActorId == null) {
            pauseBackgroundPresence()
            return false
        }
        val epoch = reconnect.spares.take() ?: run {
            pauseBackgroundPresence()
            return false
        }
        reconnect.dropped = false
        cancelSessionTimers()
        val connectionGeneration = issueLifecycle.beginConnection()
        activeConnectionGeneration = connectionGeneration
        socketOpened = false
        localCloseGeneration = null
        localCloseReason = null
        lastInboundProgressMs.set(Long.MIN_VALUE)
        val startedAt = nowMs()
        pacer = SyncOutboundPacer(startedAt.toDouble())
        enqueuedWireBytes = 0L
        _status.value = Status.CONNECTING
        sessionDomain?.fill(0)
        sessionDomain = SyncIdentity.generateSessionDomain()
        presenceCounter = 0L
        activeSessions.clear()
        awaitingHelloAck = false
        localHelloVersion = null
        backgroundSession = BackgroundSession(epoch)
        // keep the CPU up through the handshake, the next wake might be an hour off
        acquireBackgroundWakeLock()
        openSocket(base, roomId, connectionGeneration, startedAt)
        return true
    }

    /**
     * One frame of a presence-only handshake. Only the snapshot fence order and
     * the 4 MiB ceiling are checked, records stay unread, and any other room
     * traffic is dropped like everything else in background.
     */
    private fun handleBackgroundSessionFrame(session: BackgroundSession, text: String, frameBytes: Int, socket: SyncWebSocket) {
        if (session.phase == BackgroundSession.Phase.LIVE) return
        val fail = { closeLocally(SyncLocalClose.STRUCTURAL_SNAPSHOT, socket) }
        if (session.phase != BackgroundSession.Phase.AWAITING_HELLO_ACK) {
            session.bytes += frameBytes
            if (session.bytes > BackgroundPresencePolicy.MAX_SNAPSHOT_DRAIN_BYTES) {
                // too big to drain on battery, give up until the user opens the app
                pauseBackgroundPresence()
                return
            }
        }
        // junk is dropped like any other unparseable frame
        val msg = try { JSONObject(text) } catch (_: Throwable) { return }
        val type = msg.opt("t") as? String
        if (session.phase == BackgroundSession.Phase.AWAITING_HELLO_ACK) {
            if (type != "hello-ack") return
            val actor = myActorId ?: return
            val sd = sessionDomain ?: return
            val expected = localHelloVersion ?: return
            if (!SyncIdentity.helloAckMatches(
                    actor, sd, expected, msg.optString("by"), msg.optString("sd"), msg.optString("vs"))) return
            onBackgroundSessionLive(session, socket)
            return
        }
        when (type) {
            "snapshot-begin" -> {
                if (session.phase != BackgroundSession.Phase.AWAITING_BEGIN) return fail()
                session.seq = strictNonNegativeLong(msg, "seq") ?: return fail()
                session.phase = BackgroundSession.Phase.RECEIVING
            }
            "snapshot" -> {
                if (session.phase != BackgroundSession.Phase.RECEIVING) return fail()
                val more = msg.opt("more") as? Boolean ?: return fail()
                if (!more) session.phase = BackgroundSession.Phase.FINAL_PAGE
            }
            "snapshot-end" -> {
                if (session.phase != BackgroundSession.Phase.FINAL_PAGE) return fail()
                val seq = strictNonNegativeLong(msg, "seq") ?: return fail()
                if (seq != session.seq) return fail()
                session.phase = BackgroundSession.Phase.AWAITING_HELLO_ACK
                handshakeWatchdog.snapshotEnded(nowMs())
                // the spare is already covered on disk, so no reservation here
                if (!enqueueSignedHello(HelloEpochPolicy.hex(session.epoch))) fail()
            }
            // room traffic before our hello-ack, dropped unread
            else -> Unit
        }
    }

    private fun onBackgroundSessionLive(session: BackgroundSession, socket: SyncWebSocket) {
        session.phase = BackgroundSession.Phase.LIVE
        backgroundReconnect?.let {
            it.policy.attemptSucceeded()
            it.dropped = false
            it.droppedAtWallMs = null
        }
        handshakeWatchdog.connected()
        watchdogJob?.cancel(); watchdogJob = null
        _status.value = Status.CONNECTED
        lastInboundProgressMs.set(nowMs())
        socket.setKeepaliveSeconds((BackgroundPresencePolicy.BACKGROUND_PING_INTERVAL_MS / 1_000L).toInt())
        presenceCadence.beginAuthenticatedSession()
        // send the fix that brought us back, if it's still inside the two minute rule
        val now = syncClock.elapsedRealtimeNanos()
        lastBackgroundSample?.takeIf {
            ForegroundPresenceLiveness.isBridgeableFix(it.elapsedRealtimeNanos, now)
        }?.let { sample ->
            sendPresenceAtCadence(
                sample = sample,
                isBackground = true,
                backgroundInterval = backgroundInterval,
                nowElapsedRealtimeNanos = now,
            )
        }
        releaseBackgroundWakeLock()
    }

    /** Background sharing stops without the user asking: say when, here and on return (21.5). */
    private fun pauseBackgroundPresence() {
        val pausedAt = backgroundReconnect?.droppedAtWallMs ?: syncClock.wallClockMs()
        clearBackgroundReconnect()
        if (ws != null) closeSocketForLifecycleTransition("background presence paused")
        backgroundPausedAtWallMs = pausedAt
        backgroundPresencePaused?.invoke(pausedAt)
        backgroundTransportEnded?.invoke()
    }

    private fun clearBackgroundReconnect() {
        backgroundReconnect = null
        backgroundSession = null
        lastBackgroundSample = null
        lastBackgroundSampleAtMs = Long.MIN_VALUE
        releaseBackgroundWakeLock()
    }

    /** The fix that found a drop counts as the send opportunity only within the same wake. */
    private fun backgroundSampleFromThisWake(): Boolean =
        lastBackgroundSample != null && lastBackgroundSampleAtMs != Long.MIN_VALUE &&
            nowMs() - lastBackgroundSampleAtMs <= BackgroundPresencePolicy.WAKE_LOCK_MAX_MS

    private fun acquireBackgroundWakeLock() {
        if (backgroundWakeLockHeld) return
        env.wakeLock?.acquire(BackgroundPresencePolicy.WAKE_LOCK_MAX_MS)
        backgroundWakeLockHeld = true
    }

    private fun releaseBackgroundWakeLock() {
        if (!backgroundWakeLockHeld) return
        backgroundWakeLockHeld = false
        env.wakeLock?.release()
    }

    private fun formatPauseTime(wallMs: Long): String =
        java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(wallMs))

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
                Messages.syncCouldNotSaveUnitSyncIdentityLocationSharingTheMessage(),
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
            reportError(Messages.syncJoinCodeMustStartWithLegacyRoomsRequireAnMessage())
            return
        }
        if (SyncCrypto.isJoinCodeTooWeak(code)) {
            reportError(Messages.syncJoinCodeIsTooShortToBeSafeGenerateMessage())
            return
        }
        val configuredRelay = validatedRelayBaseForRuntime(
            OpsecSettings.shared?.relayUrl?.value ?: RELAY_BASE
        )
        if (configuredRelay == null) {
            reportError(
                Messages.syncConfiguredRelayUnavailableMessage(),
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
                Messages.syncSyncSigningIdentityIsLockedOrDamagedUnlockMissionMessage(),
                SyncIssueKind.SECURITY,
            )
            return
        }
        // Hold the validated origin for reconnects. connect() revalidates it
        // before every bounded WebSocket is created.
        relayBase = configuredRelay

        // PBKDF2 (210k rounds) runs off the UI thread; the result only lands if
        // this join is still the current one (plans/04 section 20.3, S5-13)
        val token = ++joinToken
        _status.value = Status.CONNECTING
        val v3 = code.startsWith("3:")
        joinJob = scope.launch {
            val derived = withContext(env.deriveDispatcher) {
                runCatching {
                    if (v3) env.deriveRoomV3(code.removePrefix("3:")) else env.deriveRoomV2(code.removePrefix("2:"))
                }
            }
            if (lifecycleGate.isDisposed || token != joinToken) {
                wipeDerived(derived.getOrNull())
                return@launch
            }
            completeJoin(code, derived, requestedRoomName, token)
            if (token == joinToken) joinJob = null
        }
    }

    private fun wipeDerived(keys: Any?) {
        when (keys) {
            is SyncCrypto.V3RoomKeys -> { keys.roomIdRaw.fill(0); keys.roomKey.fill(0); keys.metadataKey.fill(0) }
            is SyncCrypto.RoomKeys -> keys.roomKey.fill(0)
        }
    }

    private suspend fun completeJoin(code: String, derived: Result<Any>, requestedRoomName: String, token: Long) {
        if (code.startsWith("3:") &&
            (revisionJournalLoad?.await() != true || lifecycleGate.isDisposed || token != joinToken)) {
            wipeDerived(derived.getOrNull())
            if (!lifecycleGate.isDisposed && token == joinToken) persistenceFailure()
            return
        }
        if (code.startsWith("3:")) {
            val setup = runCatching {
                // Resolving the signing identity can fail while the at-rest key
                // is locked or if the sealed seed is corrupt. Never rotate it.
                val pubRaw = myPublicKeyRaw
                val keys = derived.getOrThrow() as SyncCrypto.V3RoomKeys
                val actor = SyncIdentity.actorId(keys.roomIdRaw, pubRaw)
                val replay = SyncReplayState(keys.roomId, appFilesDir)
                check(replay.loadOffMain(actor, myPublicKey, env.persistenceDispatcher)) { L10n.text("replay state unavailable") }
                Triple(keys, replay, actor)
            }.getOrElse {
                wipeDerived(derived.getOrNull())
                if (lifecycleGate.isDisposed || token != joinToken) return
                _status.value = Status.OFFLINE
                reportError(
                    Messages.syncSyncIdentityOrRollbackStateIsLockedOrDamagedMessage(),
                    SyncIssueKind.SECURITY,
                )
                return
            }
            if (lifecycleGate.isDisposed || token != joinToken) {
                wipeDerived(setup.first)
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
            env.reachability?.start(reachabilityListener)
            connect(setup.first.roomId)
        } else {
            // v2 protocol: unchanged
            val keys = derived.getOrNull() as? SyncCrypto.RoomKeys ?: run {
                _status.value = Status.OFFLINE
                reportError(
                    Messages.syncSyncIdentityOrRollbackStateIsLockedOrDamagedMessage(),
                    SyncIssueKind.SECURITY,
                )
                return
            }
            // learned 2.x iOS casing goes in before the first diff can send anything
            val v2Ids = LegacyV2IdStore(File(appFilesDir, LegacyV2IdStore.DIRECTORY_NAME), keys.roomId)
            withContext(env.persistenceDispatcher) { v2Ids.load() }
            if (lifecycleGate.isDisposed || token != joinToken) {
                wipeDerived(keys)
                return
            }
            protocolVersion = 2
            legacyV2Ids = v2Ids
            chatHistoryStore.close()
            clearChatTransport(markPendingFailed = true)
            roomKey = keys.roomKey
            authToken = keys.authToken
            activateRoomName(keys.roomId, requestedRoomName)
            _room.value = code
            wantConnected = true
            env.reachability?.start(reachabilityListener)
            connect(keys.roomId)
        }
        startObserving()
        startPresenceBroadcast()
        startStalenessSweep()
        runtimeStateChanged?.invoke()
    }

    fun leave() {
        joinToken += 1
        joinJob?.cancel(); joinJob = null
        backgroundPresenceOnly = false
        awaitingForegroundStores = false
        presenceCadence.reset()
        presencePolicy.reset()
        backgroundProbeJob?.cancel(); backgroundProbeJob = null
        backgroundPausedAtWallMs = null
        clearBackgroundReconnect()
        lastForegroundHelloEpoch = null
        lastForegroundSpareCount = 0
        wantConnected = false
        reconnectJob?.cancel(); reconnectJob = null
        observeJob?.cancel(); observeJob = null
        presenceJob?.cancel(); presenceJob = null
        stalenessSweepJob?.cancel(); stalenessSweepJob = null
        cancelForegroundPresenceRefresh()
        cancelSessionTimers()
        env.reachability?.stop()
        val leavingSocket = ws
        markLocalClose(SyncLocalClose.LEAVE)
        // Discard queued work, then count a best-effort signed leave against
        // this socket's remaining control budget before closing it.
        pacer.clear()
        if (leavingSocket != null) sendExplicitLeaveV3(leavingSocket)
        if (leavingSocket?.close(1000, "leave") == false) leavingSocket.cancel()
        ws = null
        resetJoinScopedState()
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
        versions.clear(); lastContent.clear(); kindById.clear(); exportCache.clear()
        forcedLocalDiff.clear(); resolvingPendingModel = false
        forcedLegacyDeletes.clear()
        legacyV2Ids = null
        clearOutboundDeliveries(markForReconciliation = false)
        v2SnapshotTimeoutJob?.cancel(); v2SnapshotTimeoutJob = null
        v2SnapshotGate.cancel()
        v2SnapshotFailureGeneration = null
        v3HandshakeFailureGeneration = null
        peerKeys.clear(); peerTs.clear()
        lastGoodLocalPresenceFix = null
        localPresenceCandidateCluster = null
        remotePresenceCandidateClusters.clear()
        // v3 state: clear transport-session fields but NOT replayState (durable)
        dropSessionIndexes()
        // clean point (17.1): exact counters so the next load needs no crash floor
        persistPresenceCleanPoint()
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
        _lastError.value = issueLifecycle.dismiss()?.pendingMessage
    }

    internal fun reportBackgroundLocationIssue(message: LocalizedMessage) {
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
            joinToken += 1
            persistPresenceCleanPoint()
            clearBackgroundReconnect()
            managerJob.cancel()
            inbound.clear()
            reconnectJob?.cancel(); reconnectJob = null
            observeJob?.cancel(); observeJob = null
            revisionJob?.cancel(); revisionJob = null
            presenceJob?.cancel(); presenceJob = null
            stalenessSweepJob?.cancel(); stalenessSweepJob = null
            cancelForegroundPresenceRefresh()
            cancelSessionTimers()
            env.reachability?.stop()
            markLocalClose(SyncLocalClose.LEAVE)
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
            ?: L10n.text("Unknown unit")

    private fun publishChatRecipients() {
        _chatRecipients.value = chatPeerKeys.mapValues { (actorId, peer) ->
            peer.copy(displayName = chatDisplayName(actorId)).immutableTarget()
        }
    }

    private fun refreshChatAvailability() {
        val issue = when {
            protocolVersion != 3 || _room.value == null ->
                Messages.chatJoinAVUnitSyncRoomToUseTacmapMessage()
            chatHistoryStore.availability.value == TacMapChatHistoryAvailability.LOCKED ->
                Messages.chatUnlockMissionDataToUseTacmapChatMessage()
            chatHistoryStore.availability.value == TacMapChatHistoryAvailability.CORRUPT ->
                chatHistoryStore.issue.value ?: Messages.chatEncryptedChatHistoryIsUnavailableMessage()
            chatHistoryStore.availability.value == TacMapChatHistoryAvailability.UNAVAILABLE ->
                chatHistoryStore.issue.value ?: Messages.chatEncryptedChatHistoryCouldNotBeSavedMessage()
            chatHistoryStore.availability.value != TacMapChatHistoryAvailability.READY ->
                Messages.chatSecureChatHistoryIsUnavailableMessage()
            _status.value != Status.CONNECTED -> Messages.chatTacmapChatIsWaitingForUnitSyncMessage()
            !localChatKeyAcknowledged -> Messages.chatSecureChatIsStillStartingMessage()
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
        versions.clear(); lastContent.clear(); kindById.clear(); exportCache.clear()
        forcedLocalDiff.clear(); resolvingPendingModel = false
        forcedLegacyDeletes.clear()
        legacyV2Ids = null
        clearOutboundDeliveries(markForReconciliation = false)
        v2SnapshotTimeoutJob?.cancel(); v2SnapshotTimeoutJob = null
        v2SnapshotGate.cancel()
        v2SnapshotFailureGeneration = null
        v3HandshakeFailureGeneration = null
        cancelForegroundPresenceRefresh()
        resetJoinScopedState()
        peerKeys.clear(); peerTs.clear()
        myActorId = null
        replayState = null
        presenceCounter = 0L
        activeSessions.clear()
        remotePresenceCandidateClusters.clear()
        awaitingHelloAck = false
        localHelloVersion = null
        resetSnapshot()
        dropSessionIndexes()
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
        cancelSessionTimers()
        cancelForegroundPresenceRefresh()
        val socket = ws
        markLocalClose(SyncLocalClose.LIFECYCLE_PAUSE)
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

    /** Wire index, live validator and any open batch die with the stores or the room (section 18). */
    private fun dropSessionIndexes() {
        liveBatch?.let { batch ->
            liveBatch = null
            batch.replay.abortBatch()
        }
        wireIndex?.close(); wireIndex = null
        liveValidator?.close(); liveValidator = null
    }

    private fun cancelSessionTimers() {
        presenceFlushJob?.cancel(); presenceFlushJob = null
        watchdogJob?.cancel(); watchdogJob = null
        handshakeWatchdog.cancel()
        stableSessionJob?.cancel(); stableSessionJob = null
        livenessProbeJob?.cancel(); livenessProbeJob = null
        liveResyncJob?.cancel(); liveResyncJob = null
        pumpJob?.cancel(); pumpJob = null
        ackCheckJob?.cancel(); ackCheckJob = null
        writePollJob?.cancel(); writePollJob = null
    }

    /** Remember why we're closing so the close callback isn't read as relay data. */
    private fun markLocalClose(reason: SyncLocalClose) {
        localCloseGeneration = activeConnectionGeneration
        localCloseReason = reason
    }

    private fun closeLocally(reason: SyncLocalClose, socket: SyncWebSocket? = ws) {
        val target = socket ?: return
        if (target === ws) markLocalClose(reason)
        val code = SyncCloseClassifier.classifyLocal(reason).localCloseCode ?: 1000
        if (!target.close(code, reason.wireName)) target.cancel()
    }

    /** Everything that lives exactly as long as one room membership. */
    private fun resetJoinScopedState() {
        surfacedIssueKeys.clear()
        skippedWireIds.clear()
        suppressedUntilLocalEdit.clear()
        mutationsPaused = false
        lastByV2.clear()
        failureCounters.reset()
        backoff.reset()
        liveWindowResync.reset()
        rejectedHelloEpoch = null
        sessionHelloEpoch = null
        nextSessionAfter4008 = false
        clearPausedState()
        _lastError.value = issueLifecycle.resetForLeave()?.pendingMessage
    }

    private fun clearPausedState() {
        _pausedActionRequired.value = false
        pausedRetryOnForeground = false
    }

    /** PAUSED_ACTION_REQUIRED: no socket, no reconnect, room and keys kept, Retry shown. */
    private fun enterPausedActionRequired(issue: SyncIssueCode, retryOnForeground: Boolean) {
        wantConnected = false
        reconnectJob?.cancel(); reconnectJob = null
        cancelSessionTimers()
        _status.value = Status.OFFLINE
        pausedRetryOnForeground = retryOnForeground
        _pausedActionRequired.value = true
        surfacePinned(issue, issueMessage(issue))
        runtimeStateChanged?.invoke()
    }

    /** Report an issue at most once per [key] within the current join. */
    private fun surfaceOnce(code: SyncIssueCode, key: String, message: LocalizedMessage = issueMessage(code)) {
        if (!surfacedIssueKeys.add(code.name + "|" + key)) return
        surfaceIssue(code, message)
    }

    /**
     * Which banner slot an issue lands in, same split as iOS. Security and
     * failure-chain issues are transient (a later clean connection retires
     * them), changes-paused is pinned until leave, and every other once per
     * join notice survives reconnects until it's dismissed. Without that the
     * hello-ack of the snapshot that skipped a record wiped its own notice.
     */
    private fun surfaceIssue(code: SyncIssueCode, message: LocalizedMessage = issueMessage(code)) {
        when {
            code == SyncIssueCode.ROOM_RESET_CHANGES_PAUSED -> surfacePinned(code, message)
            code.kind == SyncIssueKind.SECURITY || code in TRANSIENT_ISSUES -> reportError(message, code.kind)
            else -> {
                _lastError.value = issueLifecycle.reportNotice(message, activeConnectionGeneration)?.pendingMessage
                _remoteUpdates.tryEmit(message.text)
            }
        }
    }

    /** A Retry (button or foreground) ends the stop, so its banner goes too. */
    private fun retireStopIssue() {
        _lastError.value = issueLifecycle.clearPinned()?.pendingMessage
        // writes stay paused for the rest of the join, so the banner stays too
        // (no second toast, that one was already said once this join)
        if (mutationsPaused) {
            val code = SyncIssueCode.ROOM_RESET_CHANGES_PAUSED
            _lastError.value = issueLifecycle.reportPinned(issueMessage(code), code.kind, activeConnectionGeneration)
                ?.pendingMessage
        }
    }

    private fun surfaceOncePerSession(code: SyncIssueCode) =
        surfaceOnce(code, "session:$activeConnectionGeneration")

    /** Survives reconnects until dismissed, Retry, or leave. */
    private fun surfacePinned(code: SyncIssueCode, message: LocalizedMessage) {
        _lastError.value = issueLifecycle.reportPinned(message, code.kind, activeConnectionGeneration)?.pendingMessage
        _remoteUpdates.tryEmit(message.text)
    }

    private fun issueMessage(code: SyncIssueCode, count: Int = 1): LocalizedMessage = when (code) {
        SyncIssueCode.SKIPPED_UNSUPPORTED -> Messages.syncRecordsSkippedUnsupportedMessage(count.toString())
        SyncIssueCode.SKIPPED_UNVERIFIED -> Messages.syncRecordsSkippedUnverifiedMessage(count.toString())
        SyncIssueCode.ROOM_RESET_SUSPECTED -> Messages.syncRoomResetSuspectedMessage()
        SyncIssueCode.ROOM_RESET_CHANGES_PAUSED -> Messages.syncRoomResetChangesPausedMessage()
        SyncIssueCode.OBJECT_TOO_LARGE -> Messages.syncObjectTooLargeMessage()
        SyncIssueCode.ROOM_QUOTA_NACK -> Messages.syncTheUnitSyncRoomIsFullSoThisSavedMessage()
        SyncIssueCode.RELAY_INVALID_NACK -> Messages.syncTheUnitSyncRelayRejectedAChangeAsInvalidMessage()
        SyncIssueCode.UNCONFIRMED_RECONNECT -> Messages.syncAUnitSyncChangeIsStillUnconfirmedAfterBoundedMessage()
        SyncIssueCode.SNAPSHOT_STRUCTURAL ->
            Messages.syncSyncSnapshotAuthenticationFailedNoUnverifiedRoomDataWasMessage()
        SyncIssueCode.RELAY_BUSY -> Messages.syncRelayBusyMessage()
        SyncIssueCode.RELAY_RATE_LIMITED -> Messages.syncRelayRateLimitedMessage()
        SyncIssueCode.ROOM_FULL_CANNOT_JOIN -> Messages.syncRoomFullCannotJoinMessage()
        SyncIssueCode.RELAY_REFUSED_ROOM -> Messages.syncRelayRefusedRoomMessage()
        SyncIssueCode.IDENTITY_REJECTED -> Messages.syncIdentityRejectedMessage()
        SyncIssueCode.SESSION_CONFLICT -> Messages.syncSessionConflictMessage()
        SyncIssueCode.SESSION_COUNTER_BEHIND -> Messages.syncSessionCounterBehindMessage()
        SyncIssueCode.SNAPSHOT_MALFORMED_STOPPED -> Messages.syncSnapshotMalformedStoppedMessage()
        SyncIssueCode.CHAT_REPLAY_FULL -> Messages.chatReplayTableFullMessage()
        SyncIssueCode.BACKGROUND_PAUSED -> Messages.syncBackgroundPausedMessage(formatPauseTime(syncClock.wallClockMs()))
    }

    private val reachabilityListener = object : SyncReachability.Listener {
        override fun onNetworkAvailable() {
            scope.launch { lifecycleGate.runIfActive { onNetworkAvailableOnMain() } }
        }

        override fun onNetworkLostOrChanged() {
            scope.launch { lifecycleGate.runIfActive { startLivenessProbe() } }
        }
    }

    /** A transient reconnect gets pulled in, never closer than 2 s after the last try. */
    private fun onNetworkAvailableOnMain() {
        if (backgroundPresenceOnly && !awaitingForegroundStores) {
            // screen-off: the network coming back is a reconnect opportunity too (21.4)
            val reconnect = backgroundReconnect ?: return
            if (ws == null && reconnect.dropped) backgroundReconnectDue(reconnect)
            return
        }
        if (reconnectJob?.isActive != true || backgroundPresenceOnly || awaitingForegroundStores) return
        val roomId = reconnectRoomId ?: return
        val before = backoff.pendingReconnectAtMs() ?: return
        val at = backoff.networkAvailable(nowMs()) ?: return
        if (at >= before) return
        scheduleReconnectAt(at, roomId, activeConnectionGeneration)
    }

    /** Path changed under a live socket: ping, and treat silence as transport loss. */
    private fun startLivenessProbe() {
        if (_status.value != Status.CONNECTED || backgroundPresenceOnly) return
        val socket = ws ?: return
        if (livenessProbeJob?.isActive == true) return
        val sentAt = nowMs()
        if (!socket.sendPing()) return
        livenessProbeJob = scope.launch {
            kotlinx.coroutines.delay(PATH_CHANGE_PROBE_TIMEOUT_MS)
            if (ws !== socket) return@launch
            if (lastInboundProgressMs.get() < sentAt) closeLocally(SyncLocalClose.LIVENESS_TIMEOUT, socket)
        }
    }

    private fun nowMs(): Long = syncClock.elapsedRealtimeMs()

    private fun shouldReconnectInForeground(): Boolean =
        wantConnected && !backgroundPresenceOnly && !awaitingForegroundStores &&
            waypointStoreRef != null && drawingStoreRef != null && !lifecycleGate.isDisposed

    private fun markPeersStale(nowUptimeMs: Long = System.nanoTime() / 1_000_000L) {
        if (_peers.value.isEmpty()) return
        _peers.value = _peers.value.mapValues { (_, peer) ->
            PresenceExpiryPolicy.markedStale(peer, nowUptimeMs)
        }
    }

    private fun startDrain() {
        scope.launch(env.inboundDispatcher) { drainInbound() }
    }

    /** The protocol worker: batches of up to 64 already-queued events, never waiting for more. */
    private suspend fun drainInbound() {
        try {
            while (true) {
                val batch = inbound.takeBatch(INBOUND_BATCH_MAX_FRAMES) ?: return
                if (lifecycleGate.isDisposed) {
                    inbound.clear()
                    continue
                }
                processInboundBatch(batch)
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            inbound.stopped()
            throw cancel
        }
    }

    private suspend fun processInboundBatch(batch: List<InboundEvent>) {
        for (event in batch) {
            if (lifecycleGate.isDisposed) return
            when (event) {
                is InboundEvent.Text -> handleTextEvent(event)
                is InboundEvent.Binary -> {
                    flushLiveBatch()
                    lifecycleGate.runIfActive {
                        if (!isCurrentSocketCallback(event.generation, activeConnectionGeneration, ws === event.socket)) {
                            return@runIfActive
                        }
                        val rejection = SyncInboundFramePolicy.inspectBinary(event.size)
                            as SyncInboundFrameDecision.Reject
                        rejectInboundFrame(event.socket, event.generation, rejection.reason)
                    }
                }
                is InboundEvent.Opened -> {
                    flushLiveBatch()
                    lifecycleGate.runIfActive { onSocketOpened(event.socket, event.generation) }
                }
                is InboundEvent.Ended -> {
                    flushLiveBatch()
                    lifecycleGate.runIfActive {
                        handleSocketEnded(event.socket, event.roomId, event.generation, event.end)
                    }
                }
            }
        }
        flushLiveBatch()
        persistLegacyV2Ids()
    }

    /** At most one sealed write per inbound batch (a v2 snapshot lands in one), and only if something new was learned. */
    private suspend fun persistLegacyV2Ids() {
        val store = legacyV2Ids ?: return
        // nothing durable behind the key lock, it stays dirty for the next batch after unlock
        if (lifecycleGate.isDisposed || backgroundPresenceOnly || awaitingForegroundStores) return
        val text = store.takePendingWrite() ?: return
        withContext(kotlinx.coroutines.NonCancellable) {
            withContext(env.persistenceDispatcher) { store.write(text) }
        }
    }

    private fun onSocketOpened(webSocket: SyncWebSocket, connectionGeneration: Long) {
        if (!isCurrentSocketCallback(connectionGeneration, activeConnectionGeneration, ws === webSocket)) return
        socketOpened = true
        handshakeWatchdog.opened(nowMs())
        // v3 is not allowed to publish until a complete authenticated
        // snapshot fence has been applied.
        if (protocolVersion == 2) {
            // v2 keeps its own snapshot timer once the socket is open
            handshakeWatchdog.cancel()
            watchdogJob?.cancel(); watchdogJob = null
            v2SnapshotGate.start(webSocket, connectionGeneration)
            scheduleV2SnapshotTimeout(webSocket, connectionGeneration)
        }
    }

    /** How a socket ended, before deciding what that means. */
    private sealed interface SocketEnd {
        data class Closed(val code: Int, val reason: String) : SocketEnd
        data class Failed(val detail: String) : SocketEnd
    }

    private fun handleSocketEnded(
        socket: SyncWebSocket,
        roomId: String,
        connectionGeneration: Long,
        end: SocketEnd,
    ) {
        if (!isCurrentSocketCallback(
                connectionGeneration,
                activeConnectionGeneration,
                ws === socket,
            )
        ) return
        val opened = socketOpened
        val local = localCloseReason.takeIf { localCloseGeneration == connectionGeneration }
        ws = null
        _status.value = Status.OFFLINE
        clearChatTransport(markPendingFailed = true)
        activeSessions.clear()
        remotePresenceCandidateClusters.clear()
        _onlineMembers.value = onlineMemberTracker.clear()
        markPeersStale()
        snapshotConfirmedLocalDeletes.clear()
        v2SnapshotTimeoutJob?.cancel(); v2SnapshotTimeoutJob = null
        cancelSessionTimers()
        backoff.sessionEnded()
        cancelForegroundPresenceRefresh()
        v2SnapshotGate.cancel()
        clearOutboundDeliveries(markForReconciliation = true)
        awaitingHelloAck = false
        resetSnapshot()
        backgroundProbeJob?.cancel(); backgroundProbeJob = null
        if (!shouldReconnectInForeground()) {
            val session = backgroundSession
            backgroundSession = null
            if (backgroundPresenceOnly && !awaitingForegroundStores && local != SyncLocalClose.LEAVE &&
                local != SyncLocalClose.LIFECYCLE_PAUSE
            ) {
                val reconnect = backgroundReconnect
                if (reconnect != null) {
                    // 21.4: keep the location service (our only wake source) and
                    // come back on a spare epoch at the next fix or network change
                    if (session != null && session.phase != BackgroundSession.Phase.LIVE) {
                        reconnect.policy.attemptFailed()
                    }
                    reconnect.dropped = true
                    if (reconnect.droppedAtWallMs == null) reconnect.droppedAtWallMs = syncClock.wallClockMs()
                    releaseBackgroundWakeLock()
                    if (!reconnect.policy.canStillReconnect(reconnect.spares.left, canArmBackgroundLocationService())) {
                        pauseBackgroundPresence()
                    } else if (backgroundSampleFromThisWake()) {
                        // the fix that just found the drop is that opportunity
                        backgroundReconnectDue(reconnect)
                    }
                    return
                }
                // lost while screen-off with 21.4 off (it waits on doc change D1):
                // THREAT_MODEL section 7 says pause, so pause loudly (21.5)
                val pausedAt = syncClock.wallClockMs()
                backgroundPausedAtWallMs = pausedAt
                backgroundPresencePaused?.invoke(pausedAt)
            }
            if (backgroundPresenceOnly || awaitingForegroundStores) backgroundTransportEnded?.invoke()
            return
        }
        // our own closes are classified by why we closed, never by the echoed code
        val decision = when {
            local != null -> SyncCloseClassifier.classifyLocal(local)
            end is SocketEnd.Closed && opened -> SyncCloseClassifier.classifyClose(end.code)
            end is SocketEnd.Closed ->
                SyncCloseClassifier.classifyHttp(SyncCloseClassifier.parseHandshakeStatus(end.reason))
            end is SocketEnd.Failed && !opened ->
                SyncCloseClassifier.classifyHttp(SyncCloseClassifier.parseHandshakeStatus(end.detail))
            else -> SyncCloseClassifier.classifyClose(1006)
        }
        applyCloseDecision(decision, local, end, roomId, connectionGeneration)
    }

    private fun applyCloseDecision(
        decision: SyncCloseDecision,
        local: SyncLocalClose?,
        end: SocketEnd,
        roomId: String,
        connectionGeneration: Long,
    ) {
        when (decision.action) {
            SyncCloseAction.NONE -> return
            SyncCloseAction.STOP -> {
                enterPausedActionRequired(checkNotNull(decision.issue), decision.retryOnForeground)
                return
            }
            SyncCloseAction.RECONNECT_NOW -> {
                // live window resync: not a failure, backoff untouched
                scheduleReconnectAt(nowMs(), roomId, connectionGeneration)
                return
            }
            SyncCloseAction.ESCALATE_EPOCH_THEN_RECONNECT -> {
                if (!failureCounters.tryEscalateEpoch()) {
                    enterPausedActionRequired(SyncIssueCode.SESSION_COUNTER_BEHIND, retryOnForeground = false)
                    return
                }
                rejectedHelloEpoch = sessionHelloEpoch ?: rejectedHelloEpoch ?: java.math.BigInteger.ONE
            }
            SyncCloseAction.RECONNECT -> Unit
        }
        if (decision.countsAsSessionConflict && failureCounters.recordSessionConflict(nowMs())) {
            enterPausedActionRequired(SyncIssueCode.SESSION_CONFLICT, retryOnForeground = true)
            return
        }
        if (local == SyncLocalClose.STRUCTURAL_SNAPSHOT && failureCounters.recordStructuralFailure()) {
            enterPausedActionRequired(SyncIssueCode.SNAPSHOT_MALFORMED_STOPPED, retryOnForeground = false)
            return
        }
        if (decision.pacerAfter4008) nextSessionAfter4008 = true
        val busy = decision.issue == SyncIssueCode.RELAY_BUSY || decision.issue == SyncIssueCode.RELAY_RATE_LIMITED
        if (busy) {
            if (failureCounters.recordBusy(decision)) surfaceIssue(checkNotNull(decision.issue))
        } else {
            failureCounters.recordOtherFailure()
        }
        val alreadyExplained = busy || local == SyncLocalClose.ACK_EXHAUSTED ||
            local == SyncLocalClose.STRUCTURAL_SNAPSHOT ||
            v2SnapshotFailureGeneration == connectionGeneration ||
            v3HandshakeFailureGeneration == connectionGeneration
        if (!alreadyExplained) {
            val detail = (end as? SocketEnd.Failed)?.detail?.takeIf { it.isNotBlank() }
            reportError(
                detail?.let { Messages.syncUnitSyncConnectionFailedCheckTheRelayOrNetworkMessage(it) }
                    ?: Messages.syncUnitSyncDisconnectedCheckTheRelayOrNetworkReconnectingMessage()
            )
        }
        val delayMs = backoff.scheduleReconnect(decision.backoffClass ?: SyncBackoffClass.TRANSIENT, nowMs())
        scheduleReconnectAt(nowMs() + delayMs, roomId, connectionGeneration)
    }

    private fun scheduleReconnectAt(atMs: Long, roomId: String, connectionGeneration: Long) {
        reconnectJob?.cancel()
        reconnectRoomId = roomId
        val delayMs = (atMs - nowMs()).coerceAtLeast(0L)
        reconnectJob = scope.launch {
            kotlinx.coroutines.delay(delayMs)
            if (activeConnectionGeneration == connectionGeneration &&
                shouldReconnectInForeground()
            ) connect(roomId)
        }
    }

    // ----- Connection -----

    private fun connect(roomId: String) {
        if (lifecycleGate.isDisposed || backgroundPresenceOnly || awaitingForegroundStores ||
            waypointStoreRef == null || drawingStoreRef == null || _pausedActionRequired.value
        ) return
        val base = validatedRelayBaseForRuntime(relayBase)
        if (base == null) {
            wantConnected = false
            _status.value = Status.OFFLINE
            reportError(
                Messages.syncConfiguredRelayUnavailableMessage(),
                SyncIssueKind.SECURITY,
            )
            return
        }
        relayBase = base
        cancelSessionTimers()
        cancelForegroundPresenceRefresh()
        v2SnapshotTimeoutJob?.cancel(); v2SnapshotTimeoutJob = null
        v2SnapshotGate.cancel()
        clearOutboundDeliveries(markForReconciliation = true)
        val connectionGeneration = issueLifecycle.beginConnection()
        activeConnectionGeneration = connectionGeneration
        socketOpened = false
        localCloseGeneration = null
        localCloseReason = null
        sessionHelloEpoch = null
        lastInboundProgressMs.set(Long.MIN_VALUE)
        val startedAt = nowMs()
        backoff.attemptStarted(startedAt)
        pacer = SyncOutboundPacer(startedAt.toDouble(), after4008 = nextSessionAfter4008)
        nextSessionAfter4008 = false
        enqueuedWireBytes = 0L
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
            lastByV2.clear()
        }
        openSocket(base, roomId, connectionGeneration, startedAt)
    }

    /** Creates the socket for [connectionGeneration] and arms the handshake watchdogs. */
    private fun openSocket(base: String, roomId: String, connectionGeneration: Long, startedAt: Long) {
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
            // reader threads only enqueue; one protocol worker does the rest in order (section 1.1)
            override fun onOpen(webSocket: SyncWebSocket) {
                inbound.offerControl(InboundEvent.Opened(webSocket, connectionGeneration))
            }
            override fun onTextMessage(
                webSocket: SyncWebSocket,
                text: String,
                consumed: () -> Unit,
            ) {
                inbound.offer(InboundEvent.Text(webSocket, connectionGeneration, text), consumed)
            }
            override fun onBinaryMessage(
                webSocket: SyncWebSocket,
                bytes: ByteArray,
                consumed: () -> Unit,
            ) {
                inbound.offer(InboundEvent.Binary(webSocket, connectionGeneration, bytes.size), consumed)
            }
            override fun onClosed(webSocket: SyncWebSocket, code: Int, reason: String) {
                inbound.offerControl(
                    InboundEvent.Ended(webSocket, connectionGeneration, roomId, SocketEnd.Closed(code, reason))
                )
            }
            override fun onFailure(webSocket: SyncWebSocket, failure: Throwable) {
                val detail = failure.message?.takeIf { it.isNotBlank() }
                    ?: failure.javaClass.simpleName
                inbound.offerControl(
                    InboundEvent.Ended(webSocket, connectionGeneration, roomId, SocketEnd.Failed(detail))
                )
            }
            override fun onInboundProgress(webSocket: SyncWebSocket) {
                // reader thread: just stamp the time, the watchdog picks it up
                lastInboundProgressMs.set(nowMs())
            }
        })
        }
        handshakeWatchdog.socketCreated(startedAt)
        scheduleWatchdog(connectionGeneration)
    }

    /** Sleeps until the next pre-CONNECTED deadline (plans/04 section 9). */
    private fun scheduleWatchdog(connectionGeneration: Long) {
        watchdogJob?.cancel(); watchdogJob = null
        val socket = ws ?: return
        if (!handshakeWatchdog.isActive) return
        watchdogJob = scope.launch {
            while (true) {
                val progressAt = lastInboundProgressMs.get()
                if (progressAt != Long.MIN_VALUE) handshakeWatchdog.progress(progressAt)
                val now = nowMs()
                val fired = handshakeWatchdog.check(now)
                if (fired != null) {
                    lifecycleGate.runIfActive { onHandshakeWatchdog(fired, socket, connectionGeneration) }
                    return@launch
                }
                val deadline = handshakeWatchdog.nextDeadlineMs() ?: return@launch
                kotlinx.coroutines.delay((deadline - now).coerceAtLeast(1L))
            }
        }
    }

    private fun onHandshakeWatchdog(fired: SyncLocalClose, socket: SyncWebSocket, connectionGeneration: Long) {
        if (ws !== socket || activeConnectionGeneration != connectionGeneration) return
        v3HandshakeFailureGeneration = connectionGeneration
        _status.value = Status.OFFLINE
        // a screen-off attempt just counts as failed, nobody's looking at a banner
        if (backgroundSession == null) {
            reportError(
                Messages.syncUnitSyncSecureHandshakeTimedOutCheckTheRelayMessage(),
                SyncIssueKind.CONNECTION,
                connectionGeneration,
            )
        }
        closeLocally(fired, socket)
    }

    // ----- Outbound pacing (plans/04 section 11) -----

    private fun utf8Size(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    /** Queue one frame behind the pacer. True means it belongs to this socket now. */
    private fun enqueueFrame(
        cls: SyncOutboundClass,
        text: String,
        guard: (() -> Boolean)? = null,
        afterWrite: (() -> Unit)? = null,
    ): Boolean {
        if (ws == null || lifecycleGate.isDisposed) return false
        pacer.offer(SyncOutboundPacer.Entry(cls, utf8Size(text), null, OutboundFrame.Plain(text, guard, afterWrite)))
        pumpOutbound()
        return true
    }

    private fun pumpOutbound() {
        val socket = ws ?: return
        while (true) {
            val entry = pacer.poll(nowMs().toDouble()) ?: break
            writeFrame(socket, entry)
        }
        pumpJob?.cancel(); pumpJob = null
        val next = pacer.nextReadyAtMs(nowMs().toDouble()) ?: return
        val delayMs = kotlin.math.ceil(next - nowMs()).toLong().coerceAtLeast(1L)
        pumpJob = scope.launch {
            kotlinx.coroutines.delay(delayMs)
            lifecycleGate.runIfActive { if (ws === socket) pumpOutbound() }
        }
    }

    private fun writeFrame(socket: SyncWebSocket, entry: SyncOutboundPacer.Entry<OutboundFrame>) {
        when (val frame = entry.payload) {
            is OutboundFrame.Plain -> {
                val guard = frame.guard
                val sent = if (guard != null) {
                    // presence consent and the write share the config lock, so no
                    // location frame starts after the UI has seen sharing turned off
                    synchronized(presenceConfigLock) {
                        guard() && lifecycleGate.sendIfActive { socket.send(frame.text) }
                    }
                } else {
                    lifecycleGate.sendIfActive { socket.send(frame.text) }
                }
                if (sent) {
                    noteEnqueued(frame.text)
                    frame.afterWrite?.invoke()
                }
            }
            is OutboundFrame.Delivery -> {
                // superseded or resolved while it sat in the queue
                if (outboundDeliveries.pending(frame.localId)?.requestId != frame.requestId) {
                    if (entry.cls == SyncOutboundClass.MUTATION) pacer.release(frame.localId)
                    return
                }
                if (!lifecycleGate.sendIfActive { socket.send(frame.text) }) return
                pendingWrites[frame.requestId] = noteEnqueued(frame.text)
                checkWrites()
            }
        }
    }

    /** Running position in the outbound byte stream, framing included. */
    private fun noteEnqueued(text: String): Long {
        val payload = utf8Size(text).toLong()
        val lengthBytes = when {
            payload < 126 -> 0L
            payload < 65_536 -> 2L
            else -> 8L
        }
        enqueuedWireBytes += payload + 2L + lengthBytes + 4L
        return enqueuedWireBytes
    }

    /**
     * A copy counts as written once the transport queue drained past its last
     * byte. Only then does its ack timer start (plans/04 section 11.3, S3-06).
     */
    private fun checkWrites() {
        val socket = ws ?: return
        if (pendingWrites.isEmpty()) {
            writePollJob?.cancel(); writePollJob = null
            return
        }
        val drained = enqueuedWireBytes - socket.queuedBytes().coerceAtLeast(0L)
        val now = nowMs()
        val done = pendingWrites.filterValues { it <= drained }.keys.toList()
        for (rid in done) {
            pendingWrites.remove(rid)
            val ahead = writtenUnacked.count { it != rid }
            writtenUnacked += rid
            ackTimer.writeComplete(rid, now, ahead)
        }
        if (done.isNotEmpty()) scheduleAckCheck()
        if (pendingWrites.isNotEmpty() && writePollJob?.isActive != true) {
            writePollJob = scope.launch {
                while (pendingWrites.isNotEmpty() && ws === socket) {
                    kotlinx.coroutines.delay(SyncAckTimer.ANDROID_WRITE_POLL_MS)
                    lifecycleGate.runIfActive { checkWrites() }
                }
            }
        }
    }

    private fun scheduleAckCheck() {
        ackCheckJob?.cancel(); ackCheckJob = null
        val next = ackTimer.nextDeadlineMs() ?: return
        val socket = ws ?: return
        ackCheckJob = scope.launch {
            kotlinx.coroutines.delay((next - nowMs()).coerceAtLeast(0L))
            lifecycleGate.runIfActive { if (ws === socket) runAckChecks() }
        }
    }

    private fun runAckChecks() {
        val now = nowMs()
        for (rid in ackTimer.pendingRequestIds()) {
            when (ackTimer.check(rid, now)) {
                SyncAckTimer.Due.NOTHING -> Unit
                SyncAckTimer.Due.RETRANSMIT -> {
                    val pending = outboundDeliveries.all().firstOrNull { it.requestId == rid }
                    if (pending == null || mutationsPaused) {
                        ackTimer.resolved(rid)
                        continue
                    }
                    pacer.offer(SyncOutboundPacer.Entry(
                        SyncOutboundClass.RETRY, utf8Size(pending.frame), pending.localId,
                        OutboundFrame.Delivery(pending.frame, rid, pending.localId),
                    ))
                }
                SyncAckTimer.Due.EXHAUSTED -> {
                    // no ack after three copies: a transport problem, reconnect and reconcile
                    surfaceOncePerSession(SyncIssueCode.UNCONFIRMED_RECONNECT)
                    closeLocally(SyncLocalClose.ACK_EXHAUSTED)
                    return
                }
            }
        }
        pumpOutbound()
        scheduleAckCheck()
    }

    private fun newDeliveryRequestId(): String = UUID.randomUUID().toString().replace("-", "")

    private fun contentHash(content: String): String =
        SyncIdentity.bytesToHex(SyncIdentity.sha256(content.toByteArray(Charsets.UTF_8)))

    private fun ciphertextHash(ciphertext: String): String =
        SyncIdentity.urlB64(SyncIdentity.sha256(ciphertext.toByteArray(Charsets.UTF_8)))

    private fun queueDelivery(delivery: PendingOutboundDelivery) {
        outboundDeliveries.register(delivery)?.let { prior -> dropDeliveryTimers(prior.requestId) }
        ackTimer.enqueued(delivery.requestId)
        pacer.offer(SyncOutboundPacer.Entry(
            SyncOutboundClass.MUTATION, utf8Size(delivery.frame), delivery.localId,
            OutboundFrame.Delivery(delivery.frame, delivery.requestId, delivery.localId),
        ))
        pumpOutbound()
    }

    private fun dropDeliveryTimers(requestId: String) {
        ackTimer.resolved(requestId)
        pendingWrites.remove(requestId)
        writtenUnacked.remove(requestId)
    }

    /** Op is done (acked or a final nack): out of the tracker, the timers and the window. */
    private fun resolveDelivery(delivery: PendingOutboundDelivery) {
        outboundDeliveries.resolve(delivery.requestId)
        dropDeliveryTimers(delivery.requestId)
        pacer.release(delivery.localId)
        pacer.removeQueuedIf { (it.payload as? OutboundFrame.Delivery)?.requestId == delivery.requestId }
    }

    private fun clearOutboundDeliveries(markForReconciliation: Boolean) {
        ackTimer.clear()
        pendingWrites.clear()
        writtenUnacked.clear()
        ackCheckJob?.cancel(); ackCheckJob = null
        writePollJob?.cancel(); writePollJob = null
        pumpJob?.cancel(); pumpJob = null
        pacer.clear()
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

    /** Coalesce diff passes; acks never run one synchronously (plans/04 section 17). */
    private fun scheduleDiff() {
        if (diffJob?.isActive == true) return
        diffJob = scope.launch {
            kotlinx.coroutines.delay(DIFF_DEBOUNCE_MS)
            diffJob = null
            lifecycleGate.runIfActive {
                if (_status.value == Status.CONNECTED && waypointStoreRef != null && drawingStoreRef != null) {
                    syncLocalState(waypointStore.committedWaypoints.value, drawingStore.committedDocument.value)
                }
            }
        }
    }

    private fun suppressUntilLocalEdit(localId: String) {
        if (localId.startsWith("wire:")) return
        suppressedUntilLocalEdit[localId] = modelRevisionJournal.generation(localId)
    }

    /** True while the user hasn't touched this object since we decided not to publish it. */
    private fun isSuppressed(localId: String): Boolean {
        val generation = suppressedUntilLocalEdit[localId] ?: return false
        if (modelRevisionJournal.generation(localId) == generation) return true
        suppressedUntilLocalEdit.remove(localId)
        return false
    }

    /** counter-window: the relay proved it can't take our writes this join. */
    private fun pauseMutationsForJoin() {
        if (mutationsPaused) return
        mutationsPaused = true
        pacer.removeQueuedIf { it.payload is OutboundFrame.Delivery }
        if (surfacedIssueKeys.add(SyncIssueCode.ROOM_RESET_CHANGES_PAUSED.name + "|join")) {
            surfacePinned(SyncIssueCode.ROOM_RESET_CHANGES_PAUSED, issueMessage(SyncIssueCode.ROOM_RESET_CHANGES_PAUSED))
        }
    }

    /** Record a skipped record for this join; an untouched local copy is not pushed over it. */
    private fun recordSkip(wireId: String, category: SnapshotRecordCategory) {
        skippedWireIds[wireId] = category
        val localId = findLocalIdForWireId(wireId) ?: return
        if (!suppressedUntilLocalEdit.containsKey(localId)) suppressUntilLocalEdit(localId)
    }

    private fun reportError(
        message: LocalizedMessage,
        kind: SyncIssueKind = SyncIssueKind.CONNECTION,
        generation: Long = activeConnectionGeneration,
    ) {
        _lastError.value = issueLifecycle.report(message, kind, generation)?.pendingMessage
        _remoteUpdates.tryEmit(message.text)
    }

    /** Cold-upgrade cleanup for inactive per-room Chat/replay files. A locked
     * auth-bound key is an expected retry state and causes no filesystem work. */
    private fun migrateLegacyLocalStoresAfterUnlock() {
        try {
            SyncLocalStore.migrateAllLegacyStores(appFilesDir)
            _lastError.value = issueLifecycle.clearPersistentSecurity()?.pendingMessage
        } catch (_: DataKey.LockedException) {
            return
        } catch (failure: Throwable) {
            val message =
                Messages.syncMetadataMigrationFailedMessage()
            _lastError.value = issueLifecycle.reportPersistentSecurity(
                message,
                activeConnectionGeneration,
            )?.pendingMessage
            _remoteUpdates.tryEmit(message.text)
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
        val observedWaypoints = waypointStore
        val observedDrawings = drawingStore
        revisionJob = scope.launch {
            merge(observedWaypoints.mutations, observedDrawings.mutations)
                .collect { event ->
                    revisionJournalLoad?.await()
                    if (lifecycleGate.isDisposed || waypointStoreRef !== observedWaypoints || drawingStoreRef !== observedDrawings) return@collect
                    if (!revisionJournalAvailable) { persistenceFailure(); return@collect }
                    if (event.origin != ModelMutationOrigin.REMOTE_SYNC &&
                        !modelRevisionJournal.bumpAllOffMain(event.localIds, env.persistenceDispatcher)) {
                        // the bump is NonCancellable, so it comes back here even after a pause
                        // detached the stores and relocked the key it needed. thats not a
                        // security stop and must not kill background presence, the foreground
                        // attach reloads the journal from disk
                        if (!currentCoroutineContext().isActive || lifecycleGate.isDisposed ||
                            waypointStoreRef !== observedWaypoints || drawingStoreRef !== observedDrawings) return@collect
                        revisionJournalAvailable = false
                        reportError(Messages.syncLocalRevisionHistoryCouldNotBeSavedSyncIsMessage(), SyncIssueKind.SECURITY)
                        persistenceFailure()
                        return@collect
                    }
                }
        }
    }

    /**
     * Last export per object, keyed on the exact instances it came from. Store
     * values are immutable and an edit swaps in a new instance, so the same
     * instance plus the same layer list means the same GeoJSON. Without this
     * every diff pass (every 250 ms while acks trickle in during a bulk publish)
     * re-exported the whole map. Plaintext like lastContent, so it dies with it.
     */
    private class CachedExport(val source: Any, val layers: Any, val content: String)
    private val exportCache = HashMap<String, CachedExport>()
    /** Real GeoJSON exports done by the diff, for the efficiency tests. */
    internal var diffExportsForTests = 0L
        private set

    private inline fun cachedExport(id: String, source: Any, layers: Any, export: () -> String): String {
        exportCache[id]?.let { hit -> if (hit.source === source && hit.layers === layers) return hit.content }
        diffExportsForTests += 1
        return export().also { exportCache[id] = CachedExport(source, layers, it) }
    }

    private fun syncLocalState(wps: List<Waypoint>, doc: DrawingDocument) {
        lifecycleGate.runIfActive {
            if (_status.value != Status.CONNECTED) return@runIfActive
            val current = HashMap<String, Pair<String, String>>() // id -> (kind, content)
            val layers = doc.layers
            for (wp in wps) current[wp.id] = "waypoint" to cachedExport(wp.id, wp, layers) {
                GeoJsonExporter.export(listOf(wp), emptyList(), layers, density = displayDensity)
            }
            for (f in doc.features) current[f.id] = "drawing" to cachedExport(f.id, f, layers) {
                GeoJsonExporter.export(emptyList(), listOf(f), layers, density = displayDensity)
            }
            // gone objects don't keep their old GeoJSON around
            exportCache.keys.retainAll(current.keys)

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
            if (isSuppressed(id)) continue
            val contentHash = contentHash(content)
            val pending = outboundDeliveries.pending(id)
            if (pending?.desiredContentHash == contentHash && pending.kind == kind) continue
            clock += 1
            versions[id] = clock
            lastByV2[id] = clientId
            sendPut(id, clock, kind, content)
        }
        val gone = (lastContent.keys + forcedLocalDiff + forcedLegacyDeletes.keys + outboundDeliveries.all().map { it.localId })
            .filter { it !in current && !it.startsWith("wire:") }
            .distinct()
        for (id in gone) {
            if (isSuppressed(id)) continue
            val pending = outboundDeliveries.pending(id)
            if (pending != null && pending.desiredContentHash == null && pending.kind == "del") continue
            clock += 1
            versions[id] = clock
            lastByV2[id] = clientId
            sendDel(id, clock)
        }
    }

    private fun syncLocalStateV3(current: HashMap<String, Pair<String, String>>) {
        val actor = myActorId ?: return
        val replay = replayState ?: return
        val sd = sessionDomain ?: return
        if (!revisionJournalAvailable || resolvingPendingModel || replay.hasPendingModelApplications()) return
        // counter-window: the relay proved it can't take our writes, stop trying for this join
        if (mutationsPaused) return
        if (replay.isInBatch || replay.isPersistenceInFlight || modelRevisionJournal.isPersistenceInFlight) {
            scheduleDiff()
            return
        }
        val index = ensureWireIndex() ?: return
        val session = SyncIdentity.urlB64(sd)

        // every stamp of this pass in one replay write, frames only after it's durable (section 17)
        class PendingSend(val localId: String, val wireId: String, val stamp: VersionStamp, val kind: String?, val content: String?)
        val sends = ArrayList<PendingSend>()
        var failed = false
        replay.beginBatch()
        for ((id, kc) in current) {
            val (kind, content) = kc
            if (lastContent[id] == content && id !in forcedLocalDiff) continue
            if (isSuppressed(id)) continue
            val contentHash = SyncIdentity.bytesToHex(SyncIdentity.sha256(content.toByteArray(Charsets.UTF_8)))
            val pending = outboundDeliveries.pending(id)
            if (pending?.desiredContentHash == contentHash && pending.kind == kind) continue
            val wireId = index.wireId(id) ?: continue
            // measure before reserving: an object the relay can't take never burns a stamp
            if (!OutboundSizeCheck.v3PutFits(wireId, actor, myPublicKey, session, kind, content)) {
                suppressUntilLocalEdit(id)
                surfaceOnce(SyncIssueCode.OBJECT_TOO_LARGE, "obj:$id")
                continue
            }
            val vs = replay.recoverableLocalPut(wireId, actor, myPublicKey, contentHash)
                ?: replay.reserveLocalPut(wireId, actor, myPublicKey, contentHash)
            if (vs == null) {
                failed = true
                break
            }
            sends += PendingSend(id, wireId, vs, kind, content)
        }
        if (!failed) {
            val gone = (lastContent.keys + forcedLocalDiff + outboundDeliveries.all().map { it.localId })
                .filter { it !in current && !it.startsWith("wire:") }.distinct()
            for (id in gone) {
                if (isSuppressed(id)) continue
                val pending = outboundDeliveries.pending(id)
                if (pending != null && pending.desiredContentHash == null && pending.kind == "del") continue
                val wireId = index.wireId(id) ?: continue
                val vs = replay.reserveLocalDelete(wireId, actor, myPublicKey)
                if (vs == null) {
                    failed = true
                    break
                }
                sends += PendingSend(id, wireId, vs, null, null)
            }
        }
        if (failed) {
            replay.abortBatch()
            return persistenceFailure()
        }
        val socket = ws
        val generation = activeConnectionGeneration
        val token = joinToken
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            if (!replay.commitBatchOffMain(env.persistenceDispatcher)) {
                if (foregroundPersistenceCurrent(replay, socket, generation, token)) persistenceFailure()
                return@launch
            }
            if (!foregroundPersistenceCurrent(replay, socket, generation, token)) return@launch
            for (send in sends) {
                if (send.content != null && send.kind != null) {
                    sendPutV3(send.localId, send.wireId, send.stamp, send.kind, send.content)
                } else {
                    sendDelV3(send.localId, send.wireId, send.stamp)
                }
            }
        }
    }

    /**
     * v2 frame id for a local object. An object that reached us under a 2.x
     * iOS uppercase id goes back out under that exact id, AAD and signature
     * included, or 2.x iOS echoes a put plus a del of our casing and the
     * object is gone room wide (gap-v2-room-2x-interop-2).
     */
    private fun legacyV2WireId(localId: String): String =
        LegacyV2Ids.outboundId(localId, LegacyV2Ids.stateKey(localId)?.let { legacyV2Ids?.remembered(it) })

    private fun sendPut(id: String, v: Long, kind: String, content: String) {
        val key = roomKey ?: return
        val wireId = legacyV2WireId(id)
        // Sign the write, then seal {content, pub, sig} together. The signature
        // rides INSIDE the sealed blob so the relay stays E2E-blind to device
        // identity; a receiver proves room-key possession by opening it and
        // device authorship by verifying the sig against the pinned key.
        val sig = SyncSigning.sign(deviceSeed, SyncSigning.objectMessage(wireId, v, kind, clientId, content))
        val inner = JSONObject().apply { put("c", content); put("pub", myPublicKey); put("sig", sig) }
        val aad = SyncCrypto.aad(wireId, v, kind)
        val ct = SyncCrypto.encodeBase64(SyncCrypto.seal(key, inner.toString().toByteArray(Charsets.UTF_8), aad))
        val rid = newDeliveryRequestId()
        val frame = JSONObject().apply {
            put("t", "put"); put("id", wireId); put("v", v); put("by", clientId); put("kind", kind); put("ct", ct)
            put("rid", rid)
        }.toString()
        queueDelivery(PendingOutboundDelivery(
            id, rid, activeConnectionGeneration, clientId, null, wireId, v.toString(), kind,
            ciphertextHash(ct), contentHash(content), content, frame,
        ))
    }

    private fun sendDel(id: String, v: Long) {
        val key = roomKey ?: return
        val wireId = legacyV2WireId(id)
        val sig = SyncSigning.sign(deviceSeed, SyncSigning.objectMessage(wireId, v, "del", clientId, ""))
        val inner = JSONObject().apply { put("pub", myPublicKey); put("sig", sig) }
        val aad = SyncCrypto.aad(wireId, v, "del")
        val ct = SyncCrypto.encodeBase64(SyncCrypto.seal(key, inner.toString().toByteArray(Charsets.UTF_8), aad))
        val rid = newDeliveryRequestId()
        val frame = JSONObject().apply {
            put("t", "del"); put("id", wireId); put("v", v); put("by", clientId); put("ct", ct)
            put("rid", rid)
        }.toString()
        queueDelivery(PendingOutboundDelivery(
            id, rid, activeConnectionGeneration, clientId, null, wireId, v.toString(), "del",
            ciphertextHash(ct), null, null, frame,
        ))
    }

    // -- v3 outbound --

    private enum class HelloResult { SENT, EXHAUSTED, FAILED }

    private fun sendHelloV3(): HelloResult {
        val actor = myActorId ?: return HelloResult.FAILED
        val replay = replayState ?: return HelloResult.FAILED
        // plans/04 section 14: time floor for lost state, doubling after 4014,
        // spare block when background presence is on. Still persisted before signing.
        val persisted = HelloEpochPolicy.parse(replay.getHelloEpoch(actor))
        val rejected = rejectedHelloEpoch
        val floor = HelloEpochPolicy.floor(persisted, syncClock.wallClockMs(), rejected != null, rejected)
        val spare = if (backgroundPresenceOptIn()) HelloEpochPolicy.BACKGROUND_SPARE_BLOCK else 0
        val planned = HelloEpochPolicy.reserve(persisted, floor, spare)
        if (planned is HelloEpochPolicy.Result.Exhausted) return HelloResult.EXHAUSTED
        val socket = ws
        val generation = activeConnectionGeneration
        val token = joinToken
        val pubkey = myPublicKey
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            val epoch = replay.reserveHelloEpochOffMain(actor, pubkey, floor, spare, env.persistenceDispatcher)
            if (!foregroundPersistenceCurrent(replay, socket, generation, token)) return@launch
            if (epoch == null) {
                awaitingHelloAck = false
                persistenceFailure()
                return@launch
            }
            sessionHelloEpoch = java.math.BigInteger(epoch, 16)
            lastForegroundHelloEpoch = sessionHelloEpoch
            lastForegroundSpareCount = (planned as HelloEpochPolicy.Result.Next).spareCount
            if (!enqueueSignedHello(epoch)) {
                awaitingHelloAck = false
                failConnection(SyncLocalClose.LIVENESS_TIMEOUT)
            }
        }
        return HelloResult.SENT
    }

    /** Signs and queues the hello for an epoch that's already safe to use. */
    private fun enqueueSignedHello(epochHex: String): Boolean {
        val keys = v3Keys ?: return false
        val actor = myActorId ?: return false
        val sd = sessionDomain ?: return false
        val vs = "$epochHex:$actor"
        localHelloVersion = vs
        val preimage = SyncIdentity.buildPreimage(
            SyncIdentity.DOMAIN_HELLO, keys.roomIdRaw, actor, sd,
            epochHex, "", "hello", SyncIdentity.sha256(myPublicKeyRaw)
        )
        val sig = SyncSigning.sign(deviceSeed, preimage)
        val generation = activeConnectionGeneration
        return enqueueFrame(SyncOutboundClass.CONTROL, JSONObject().apply {
            put("t", "hello")
            put("by", actor)
            put("pub", myPublicKey)
            put("sd", SyncIdentity.urlB64(sd))
            put("vs", vs)
            put("sig", sig)
        }.toString(), afterWrite = {
            handshakeWatchdog.helloWritten(nowMs())
            scheduleWatchdog(generation)
        })
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
        return enqueueFrame(SyncOutboundClass.CONTROL, JSONObject().apply {
            put("t", "leave")
            put("lv", SyncIdentity.EXPLICIT_LEAVE_VERSION)
            put("by", actor)
            put("sd", SyncIdentity.urlB64(sd))
            put("vs", helloVersion)
            put("sig", signature)
        }.toString(), guard = { ws === socket })
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
        val sent = enqueueFrame(SyncOutboundClass.CONTROL, frame)
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
                enqueueFrame(SyncOutboundClass.CONTROL, exactFrame)
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
                    Messages.chatThisRelayDoesNotConfirmTacmapChatSupportMessage()
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

    fun chatSendBlockReason(target: TacMapChatTarget): LocalizedMessage? {
        chatHistoryStore.issue.value?.let { issue ->
            if (chatHistoryStore.availability.value != TacMapChatHistoryAvailability.READY) return issue
        }
        val gate = TacMapChatTargetGate.evaluate(
            target = target,
            connectedV3 = protocolVersion == 3 && _status.value == Status.CONNECTED,
            localChatKeyAcknowledged = localChatKeyAcknowledged,
            peerKeys = chatPeerKeys,
        )
        if (gate is TacMapChatSendGate.Blocked) return gate.pendingReason
        if (target === TacMapChatTarget.EntireRoom && chatPeerKeys.isEmpty()) {
            return Messages.chatNoChatReadyUnitsAreAvailableMessage()
        }
        (target as? TacMapChatTarget.SelectedUnit)?.let { selected ->
            // a session advertising the long screen-off retention drops chat unread (S6-04)
            val retentionSeconds = _peers.value[selected.actorId]
                ?.takeIf { it.sessionDomain == selected.sessionDomain }
                ?.retentionWindowMs?.div(1_000L)
            if (ChatSendGate.blockedForBackground(direct = true, recipientRetentionSeconds = retentionSeconds)) {
                return Messages.chatRecipientInBackgroundMessage()
            }
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
        if (body.isBlank()) return TacMapChatSendResult.Blocked(Messages.chatEnterAMessageMessage())
        if (body.toByteArray(Charsets.UTF_8).size > TacMapChatPayload.MAX_BODY_UTF8_BYTES) {
            return TacMapChatSendResult.Blocked(Messages.chatMessageIsLongerThanUtfBytesMessage())
        }
        val keys = v3Keys ?: return TacMapChatSendResult.Blocked(Messages.chatSecureRoomIsUnavailableMessage())
        val actor = myActorId ?: return TacMapChatSendResult.Blocked(Messages.chatSecureIdentityIsUnavailableMessage())
        val sessionRaw = sessionDomain ?: return TacMapChatSendResult.Blocked(Messages.chatSecureSessionIsUnavailableMessage())
        val sessionText = SyncIdentity.urlB64(sessionRaw)
        val fromKid = localChatKeyId
            ?: return TacMapChatSendResult.Blocked(Messages.chatSecureChatIsStillStartingMessage())
        val ephemeral = chatEphemeralKey
            ?: return TacMapChatSendResult.Blocked(Messages.chatSecureChatIsStillStartingMessage())
        if (chatCounter >= VersionStamp.MAX_COUNTER) {
            clearChatTransport(markPendingFailed = true)
            return TacMapChatSendResult.Blocked(Messages.chatSecureChatSessionMustReconnectMessage())
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
            return TacMapChatSendResult.Blocked(Messages.chatSelectedUnitIsNoLongerAvailableMessage())
        }
        val payload = TacMapChatPayload(
            pv = TacMapChatPayload.VERSION,
            kind = kind,
            body = body,
            createdAt = System.currentTimeMillis(),
            replyTo = null,
        )
        val plaintext = TacMapChatPayloadCodec.encode(payload)
            ?: return TacMapChatSendResult.Blocked(Messages.chatMessageCouldNotBeEncodedSafelyMessage())
        val messageKey = if (scope == TacMapChatScope.ROOM) {
            TacMapChatCrypto.roomChatKey(keys.roomKey)
        } else {
            val currentPeer = peer
                ?: return TacMapChatSendResult.Blocked(Messages.chatSelectedUnitIsNoLongerAvailableMessage())
            TacMapChatCrypto.directChatKey(
                ephemeral,
                currentPeer.x25519PublicKey,
                keys.roomIdRaw,
                header,
            ) ?: return TacMapChatSendResult.Blocked(Messages.chatSelectedUnitSSecureKeyIsInvalidMessage())
        }
        val sealed = try {
            TacMapChatCrypto.seal(messageKey, plaintext, header)
        } finally {
            messageKey.fill(0)
            plaintext.fill(0)
        } ?: return TacMapChatSendResult.Blocked(Messages.chatMessageCouldNotBeEncryptedMessage())
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
            .ifBlank { L10n.text("This device") }
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
                chatHistoryStore.issue.value ?: Messages.chatEncryptedChatHistoryCouldNotBeSavedMessage()
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
        if (!enqueueFrame(SyncOutboundClass.CHAT, frame)) {
            pendingChat.remove(messageId)
            chatHistoryStore.updateDelivery(messageId, TacMapChatDeliveryState.FAILED, "send_failed")
            refreshChatAvailability()
            return TacMapChatSendResult.Blocked(Messages.chatMessageCouldNotBeSentMessage())
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
        /** How long the pause holds the main thread for sealed writes before the DEK locks. */
        internal const val KEY_LOCK_DRAIN_MS = 2_000L
        private const val CHAT_KEY_RETRY_DELAY_MS = 2_000L
        private const val CHAT_KEY_MAX_ATTEMPTS = 4
        private const val MAX_VERSION = 1_000_000_000_000L   // matches relay MAX_V
        internal const val PATH_CHANGE_PROBE_TIMEOUT_MS = 5_000L
        internal const val DIFF_DEBOUNCE_MS = 250L
        // plans/04 persistenceBatching
        internal const val INBOUND_BATCH_MAX_FRAMES = 64
        internal const val INBOUND_QUEUE_MAX_FRAMES = 1_024
        internal const val INBOUND_QUEUE_MAX_BYTES = 16L * 1024L * 1024L
        private val LIVE_BATCH_TYPES = setOf("put", "del", "loc", "hello")
        /** Issues a later clean connection retires, like any connection error. */
        private val TRANSIENT_ISSUES = setOf(
            SyncIssueCode.UNCONFIRMED_RECONNECT, SyncIssueCode.RELAY_BUSY, SyncIssueCode.RELAY_RATE_LIMITED,
        )
        internal const val BACKGROUND_PROBE_POLL_MS = 250L
        private val V3_KIND_PATTERN = Regex("^[A-Za-z0-9_-]{1,32}$")
        private val V3_OBJECT_KINDS = setOf("waypoint", "drawing")
    }

    private class AdmittedFrame(val msg: JSONObject, val bytes: Int)

    /**
     * Size, budget and parse for one text frame, in arrival order. Null means
     * it was dropped or the socket got closed for it.
     */
    private fun admitText(event: InboundEvent.Text): AdmittedFrame? {
        var admitted: AdmittedFrame? = null
        lifecycleGate.runIfActive {
            val socket = event.socket
            val connectionGeneration = event.generation
            if (ws !== socket || activeConnectionGeneration != connectionGeneration) return@runIfActive
            val text = event.text
            val decision = SyncInboundFramePolicy.inspectText(text)
            if (decision is SyncInboundFrameDecision.Reject) {
                rejectInboundFrame(socket, connectionGeneration, decision.reason)
                return@runIfActive
            }
            val frameBytes = (decision as SyncInboundFrameDecision.Accept).byteCount
            val now = nowMs()
            // a complete message also proves the upgrade went through
            socketOpened = true
            if (handshakeWatchdog.isActive) handshakeWatchdog.progress(now)
            // The locked/background session is egress-only. Do not even parse
            // relay traffic while mission stores and replay persistence are
            // unavailable; it only counts against its own budget.
            if (backgroundPresenceOnly || awaitingForegroundStores) {
                if (!receiveBudget.admit(
                        connectionGeneration, frameBytes, SyncReceiveBudget.Phase.BACKGROUND,
                        SyncReceiveBudget.Bucket.ROOM, 0, now,
                    )
                ) {
                    rejectInboundFrame(socket, connectionGeneration, SyncInboundFrameRejection.RATE_LIMITED)
                    return@runIfActive
                }
                // a presence-only handshake looks at the fence and nothing else (21.4)
                backgroundSession?.let { handleBackgroundSessionFrame(it, text, frameBytes, socket) }
                return@runIfActive
            }
            val msg = try {
                JSONObject(text)
            } catch (_: Throwable) {
                null
            }
            // our own acks get their own budget, so a bulk publish can't trip us (S2-04)
            val bucket = SyncReceiveBudget.bucketFor(msg?.opt("t") as? String)
            val phase = if (_status.value == Status.CONNECTED) {
                SyncReceiveBudget.Phase.LIVE
            } else {
                SyncReceiveBudget.Phase.INITIAL
            }
            if (!receiveBudget.admit(connectionGeneration, frameBytes, phase, bucket, activeSessions.size, now)) {
                rejectInboundFrame(socket, connectionGeneration, SyncInboundFrameRejection.RATE_LIMITED)
                return@runIfActive
            }
            if (msg != null) admitted = AdmittedFrame(msg, frameBytes)
        }
        return admitted
    }

    private suspend fun handleTextEvent(event: InboundEvent.Text) {
        if (!backgroundPresenceOnly && !awaitingForegroundStores) {
            replayState?.awaitPersistence()
            modelRevisionJournal.awaitPersistence()
            replayState?.awaitPersistence()
        }
        // Background-session parsing is egress-only and admitText owns its
        // small fence/budget path. Snapshot publication uses the stronger
        // foreground fence; transport admission must still reach that path.
        if (lifecycleGate.isDisposed || ws !== event.socket || activeConnectionGeneration != event.generation) return
        val frame = admitText(event)
        if (frame == null) {
            flushLiveBatch()
            return
        }
        val msg = frame.msg
        val type = msg.optString("t")
        if (protocolVersion == 3 && type in setOf("put", "del") &&
            liveBatch?.records?.any { it.mutation.wireObjectId == msg.optString("id") } == true
        ) {
            // The later copy would replace the earlier pending marker. End
            // this subgroup so its adopted layers are actually committed first.
            flushLiveBatch()
            if (!stillCurrent(event.socket, event.generation)) return
        }
        if (protocolVersion == 3 && type in LIVE_BATCH_TYPES && acceptsLiveInboundV3() && openLiveBatch() != null) {
            lifecycleGate.runIfActive {
                try {
                    handleLiveFrameV3(msg, type)
                } catch (_: Throwable) {
                    // Silently drop -- don't log frame content (SEC-019).
                }
            }
            return
        }
        flushLiveBatch()
        if (!stillCurrent(event.socket, event.generation)) return
        if (protocolVersion == 3 && type == "snapshot-end") {
            if (lifecycleGate.isDisposed) return
            try {
                finishSnapshotV3(msg, frame.bytes, event.socket, event.generation)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (_: Throwable) {
                // same as any other unparseable frame
            }
            return
        }
        lifecycleGate.runIfActive {
            try {
                if (protocolVersion == 3) {
                    handleMessageV3(msg, frame.bytes)
                } else {
                    handleMessageV2(msg, frame.bytes, event.socket, event.generation)
                }
            } catch (_: Throwable) {
                // Silently drop -- don't log frame content (SEC-019).
            }
        }
    }

    private fun handleLiveFrameV3(msg: JSONObject, type: String) {
        when (type) {
            "hello" -> applyHelloV3(msg)
            "put", "del" -> applyLiveRecordV3(msg)
            "loc" -> applyPresenceV3(msg)
        }
    }

    /** Opens the replay batch for a run of live frames (one sealed write for the run). */
    private fun openLiveBatch(): LiveBatch? {
        liveBatch?.let { return it }
        val replay = replayState ?: return null
        val drawings = drawingStoreRef ?: return null
        if (replay.isInBatch) return null
        replay.beginBatch()
        return LiveBatch(replay, ArrayList(drawings.committedDocument.value.layers)) { ModelLookup() }
            .also { liveBatch = it }
    }

    /**
     * Ends the run: one replay write, then the model with one write per store,
     * receiver hashes, one marker clear, and only then peers and members go
     * out (plans/04 section 1.3).
     */
    private suspend fun flushLiveBatch() {
        val batch = liveBatch ?: return
        liveBatch = null
        val socket = ws
        val generation = activeConnectionGeneration
        val token = joinToken
        if (batch.failed || !foregroundPersistenceCurrent(batch.replay, socket, generation, token)) {
            batch.replay.abortBatch()
            return
        }
        if (!batch.replay.commitBatchOffMain(env.persistenceDispatcher)) {
            if (foregroundPersistenceCurrent(batch.replay, socket, generation, token)) persistenceFailure()
            return
        }
        if (!foregroundPersistenceCurrent(batch.replay, socket, generation, token)) return
        if (batch.records.isNotEmpty() && waypointStoreRef != null && drawingStoreRef != null) {
            resolvingPendingModel = true
            val ok = try {
                modelRevisionJournal.awaitPersistence()
                if (!foregroundPersistenceCurrent(batch.replay, socket, generation, token)) return
                val clears = applyRemoteRecords(batch.records, batch.before.takeIf { it.isCurrent() })
                clears != null && batch.replay.clearPendingModelApplicationsOffMain(clears, env.persistenceDispatcher)
            } finally {
                // Leave/new join owns the new resolver state.
                if (replayState === batch.replay) resolvingPendingModel = false
            }
            if (!foregroundPersistenceCurrent(batch.replay, socket, generation, token)) return
            if (!ok) { persistenceFailure(); return }
        }
        if (!foregroundPersistenceCurrent(batch.replay, socket, generation, token)) return
        lifecycleGate.runIfActive {
            batch.peers?.let { _peers.value = it }
            batch.onlineMembers?.let { _onlineMembers.value = it }
            if (batch.chatRecipientsDirty) publishChatRecipients()
        }
    }

    private fun foregroundPersistenceCurrent(
        replay: SyncReplayState, socket: SyncWebSocket?, generation: Long, token: Long,
    ): Boolean = !lifecycleGate.isDisposed && !backgroundPresenceOnly && !awaitingForegroundStores &&
        replayState === replay && ws === socket && activeConnectionGeneration == generation && joinToken == token

    private fun persistPresenceCleanPoint() {
        val replay = replayState ?: return
        // An uncommitted live run never escaped to the UI. Discard it before
        // queuing the exact fence after any already-running durable operation.
        if (replay.isInBatch) {
            replay.abortBatch()
            if (liveBatch?.replay === replay) liveBatch = null
        }
        scope.launch(kotlinx.coroutines.NonCancellable, start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            replay.persistExactPresenceOffMain(env.persistenceDispatcher)
        }
    }

    /**
     * Parks the caller until every sealed write already handed to the persistence worker has
     * landed, at most [timeoutMs]. UnitSyncRuntime runs this right before MainActivity locks
     * the DEK, so the clean point a pause queues seals with the key that's still cached
     * instead of hitting the lock. The worker is a single FIFO lane, so a marker posted now
     * runs after all of it. Writes still waiting on the owner thread can't move while it's
     * parked here; they fail closed after the lock and a failed clean point keeps the safe floor.
     */
    internal fun awaitPersistenceWorkerIdle(timeoutMs: Long): Boolean {
        val worker = env.persistenceDispatcher
        // same dispatcher as the owner = withContext(io) ran inline, nothing can be queued
        if (worker === env.dispatcher) return true
        val drained = java.util.concurrent.CountDownLatch(1)
        worker.asExecutor().execute { drained.countDown() }
        return drained.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /** Peers as this frame should see them: the open batch's staged copy, or live. */
    private var peersView: Map<String, PresencePeer>
        get() = liveBatch?.peers ?: _peers.value
        set(value) {
            val batch = liveBatch
            if (batch != null) batch.peers = value else _peers.value = value
        }

    private fun publishOnlineMembers(value: Map<String, OnlineMember>) {
        val batch = liveBatch
        if (batch != null) batch.onlineMembers = value else _onlineMembers.value = value
    }

    private fun publishChatRecipientsAfterCommit() {
        val batch = liveBatch
        if (batch != null) batch.chatRecipientsDirty = true else publishChatRecipients()
    }

    private fun rejectInboundFrame(
        socket: SyncWebSocket,
        connectionGeneration: Long,
        rejection: SyncInboundFrameRejection,
    ) {
        if (ws !== socket || activeConnectionGeneration != connectionGeneration) return
        if (!inboundFrameCloseGate.claimClose(connectionGeneration)) return
        closeLocally(
            when (rejection) {
                SyncInboundFrameRejection.OVERSIZED -> SyncLocalClose.OVERSIZED_INBOUND
                SyncInboundFrameRejection.BINARY -> SyncLocalClose.BINARY_INBOUND
                SyncInboundFrameRejection.RATE_LIMITED -> SyncLocalClose.RECEIVE_BUDGET_EXCEEDED
            },
            socket,
        )
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
                    onSessionConnected()
                    _status.value = Status.CONNECTED
                    _lastError.value = issueLifecycle.connectionSucceeded(
                        atGeneration = connectionGeneration,
                        verifiedCleanSnapshot = false,
                    )?.pendingMessage
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

    /** Handshake done (v3 hello-ack or v2 snapshot). Backoff waits for a stable session. */
    private fun onSessionConnected() {
        handshakeWatchdog.connected()
        watchdogJob?.cancel(); watchdogJob = null
        failureCounters.helloAcked()
        backoff.connected(nowMs())
        stableSessionJob?.cancel()
        stableSessionJob = scope.launch {
            kotlinx.coroutines.delay(SyncBackoffPolicy.STABLE_SESSION_MS)
            lifecycleGate.runIfActive { backoff.tick(nowMs()) }
        }
    }

    private fun handleMessageV3(msg: JSONObject, frameBytes: Int) {
        when (msg.optString("t")) {
            "snapshot-begin" -> {
                if (_status.value != Status.CONNECTING) {
                    return failSnapshot(SnapshotRecordReason.BEGIN_WHEN_NOT_CONNECTING)
                }
                if (snapshotSeq != null) return failSnapshot(SnapshotRecordReason.SECOND_BEGIN)
                val seq = strictNonNegativeLong(msg, "seq")
                    ?: return failSnapshot(SnapshotRecordReason.SEQ_NOT_NONNEGATIVE_INTEGER)
                val replay = replayState ?: return failSnapshot(SnapshotRecordReason.BEGIN_WHEN_NOT_CONNECTING)
                val keys = v3Keys ?: return failSnapshot(SnapshotRecordReason.BEGIN_WHEN_NOT_CONNECTING)
                val key = roomKey ?: return failSnapshot(SnapshotRecordReason.BEGIN_WHEN_NOT_CONNECTING)
                snapshotSeq = seq
                snapshotSawFinalPage = false
                snapshotItemCount = 0
                snapshotAggregateBytes = frameBytes.toLong()
                snapshotWireIds.clear()
                snapshotRun?.close()
                // the worker gets copies: keys, actor pins, the committed layers (section 19)
                val pins = replay.actorPinsCopy()
                val model = ModelLookup()
                val generations = modelRevisionJournal.generationsCopy()
                val kinds = localObjectKinds()
                snapshotRun = SnapshotRun(
                    validator = SnapshotValidator(key, keys.roomIdRaw, keys.metadataKey, pins::get, displayDensity),
                    committedLayers = drawingStore.committedDocument.value.layers,
                    localKinds = kinds,
                    localIdAliases = SnapshotValidator.caseAliases(kinds.keys, kinds::containsKey),
                    stageEligible = replay.snapshotStageEligibility(model::hash, { generations[it] ?: 0L }),
                    parent = managerJob,
                    dispatcher = env.validationDispatcher,
                    // a finished page counts as handshake progress (section 9)
                    onProgress = { lastInboundProgressMs.set(nowMs()) },
                )
                _status.value = Status.SNAPSHOTTING
                // relay hint only: say it once per join and keep syncing, the
                // per-object replay rules already refuse older state
                snapshotSeqRegressed = SnapshotFence.isRegression(seq, replay.lastSnapshotSeq)
                if (snapshotSeqRegressed) surfaceOnce(SyncIssueCode.ROOM_RESET_SUSPECTED, "join")
            }
            "snapshot" -> {
                snapshotAggregateBytes += frameBytes
                if (snapshotAggregateBytes > MAX_SNAPSHOT_AGGREGATE_BYTES) {
                    return failSnapshot(SnapshotRecordReason.AGGREGATE_BYTES)
                }
                if (_status.value != Status.SNAPSHOTTING || snapshotSeq == null) {
                    return failSnapshot(SnapshotRecordReason.PAGE_BEFORE_BEGIN)
                }
                val run = snapshotRun ?: return failSnapshot(SnapshotRecordReason.PAGE_BEFORE_BEGIN)
                if (snapshotSawFinalPage) return failSnapshot(SnapshotRecordReason.PAGE_AFTER_FINAL)
                val more = msg.opt("more") as? Boolean ?: return failSnapshot(SnapshotRecordReason.MORE_NOT_BOOLEAN)
                val items = msg.opt("items") as? JSONArray ?: return failSnapshot(SnapshotRecordReason.ITEMS_NOT_ARRAY)
                snapshotItemCount += items.length()
                if (snapshotItemCount > MAX_SNAPSHOT_ITEMS) return failSnapshot(SnapshotRecordReason.ITEM_COUNT)
                // structure stays here so a bad page fails fast; the crypto goes to the worker
                val page = ArrayList<Pair<JSONObject, String>>(items.length())
                for (i in 0 until items.length()) {
                    val rec = items.opt(i) as? JSONObject ?: return failSnapshot(SnapshotRecordReason.ITEM_NOT_OBJECT)
                    val wireId = (rec.opt("id") as? String)?.takeIf { SyncIdentity.urlB64Decode32(it) != null }
                        ?: return failSnapshot(SnapshotRecordReason.ITEM_ID_INVALID)
                    // duplicate ids would let the relay pick the order, so that one stays fatal
                    if (!snapshotWireIds.add(wireId)) return failSnapshot(SnapshotRecordReason.DUPLICATE_WIRE_ID)
                    page += rec to wireId
                }
                if (!more) snapshotSawFinalPage = true
                run.submit(page)
                handshakeWatchdog.progress(nowMs())
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

    /**
     * snapshot-end (plans/04 sections 1.3, 3 and 19). Waits for the worker,
     * then on this thread: recompute expected hashes if layers moved, resolve
     * tombstones against the wire index and capture prior hashes and journal
     * generations now, commit once (sealed off-main), apply the model with one
     * write per store, check hashes, clear every marker once, send hello.
     */
    private suspend fun finishSnapshotV3(
        msg: JSONObject,
        frameBytes: Int,
        socket: SyncWebSocket,
        connectionGeneration: Long,
    ) {
        if (!stillCurrent(socket, connectionGeneration)) return
        snapshotAggregateBytes += frameBytes
        if (snapshotAggregateBytes > MAX_SNAPSHOT_AGGREGATE_BYTES) {
            return failSnapshot(SnapshotRecordReason.AGGREGATE_BYTES)
        }
        val expected = snapshotSeq ?: return failSnapshot(SnapshotRecordReason.PAGE_BEFORE_BEGIN)
        if (_status.value != Status.SNAPSHOTTING) return failSnapshot(SnapshotRecordReason.PAGE_BEFORE_BEGIN)
        val seq = strictNonNegativeLong(msg, "seq")
            ?: return failSnapshot(SnapshotRecordReason.SEQ_NOT_NONNEGATIVE_INTEGER)
        if (!snapshotSawFinalPage) return failSnapshot(SnapshotRecordReason.END_BEFORE_FINAL)
        if (seq != expected) return failSnapshot(SnapshotRecordReason.END_SEQ_MISMATCH)
        handshakeWatchdog.snapshotEnded(nowMs())
        val replay = replayState ?: return failSnapshot(SnapshotRecordReason.PAGE_BEFORE_BEGIN)
        val run = snapshotRun ?: return failSnapshot(SnapshotRecordReason.PAGE_BEFORE_BEGIN)

        val checks = run.await()
        if (!stillCurrent(socket, connectionGeneration) || replayState !== replay || snapshotRun !== run) return
        run.close()
        snapshotRun = null
        if (checks == null) return failSnapshot(SnapshotRecordReason.PAGE_BEFORE_BEGIN)

        val validated = ArrayList<ValidatedV3>(checks.size)
        val skips = LinkedHashMap<String, SnapshotRecordReason>()
        for (check in checks) when (check) {
            is V3Check.Valid -> validated += check.record
            is V3Check.Skip -> skips[check.wireId] = check.reason
        }
        var before: ModelLookup
        while (true) {
            modelRevisionJournal.awaitPersistence()
            if (!stillCurrent(socket, connectionGeneration) || replayState !== replay) return
            before = ModelLookup()
            // an object kept under an uppercase id may have come or gone while validation ran,
            // a put has to land on whatever id the stores use now
            val rebound = rebindLocalIds(validated, before)
            // Stores may have changed while validation ran. A newly created
            // opposite-kind object is unsupported before any replay commit.
            val collided = validated.removeAll { record ->
                if (record is ValidatedV3.Put && before.kind(record.localId)?.let { it != record.kind } == true) {
                    skips[record.mutation.wireObjectId] = SnapshotRecordReason.IDENTITY_COLLISION
                    true
                } else false
            }
            restageIfLayersChanged(validated, run.committedLayers, before.document.layers, skips, collided || rebound)
            if (!stillCurrent(socket, connectionGeneration) || replayState !== replay) return
            if (before.isCurrent()) break
        }
        val index = ensureWireIndex()
        val resolved = validated.map { record ->
            if (record is ValidatedV3.Delete) record.copy(localId = index?.localId(record.mutation.wireObjectId)) else record
        }
        val remotes = resolved.map { record ->
            SyncReplayState.RemoteMutation(
                record.mutation, before.hash(record.localModelId), record.localModelId,
                modelRevisionJournal.generation(record.localModelId), record.expectedModelHash,
            )
        }
        resolvingPendingModel = true
        try {
            if (!replay.commitRemoteSnapshotOffMain(remotes, seq, env.persistenceDispatcher)) {
                if (stillCurrent(socket, connectionGeneration)) persistenceFailure()
                return
            }
            // committed but not applied is the same as a crash here: the markers
            // get resolved by the next snapshot
            modelRevisionJournal.awaitPersistence()
            if (!stillCurrent(socket, connectionGeneration) || replayState !== replay) return
            lifecycleGate.runIfActive {
                // skips commit nothing; the seq still advances like the relay left them out
                recordSnapshotSkips(skips)
                val actor = myActorId
                snapshotConfirmedLocalDeletes.clear()
                if (actor != null) resolved.filterIsInstance<ValidatedV3.Delete>()
                    .filter { it.mutation.stamp.actorId == actor }
                    .forEach { snapshotConfirmedLocalDeletes[it.mutation.wireObjectId] = it.mutation.stamp.encode() }
            }
            if (lifecycleGate.isDisposed) return
            // the user may have edited while the commit was sealing; if not, the
            // prior hashes above are still the model and don't need redoing
            val clears = applyRemoteRecords(resolved, before.takeIf { it.isCurrent() })?.let { applied ->
                resolveUnmatchedPendingModelApplications(applied)?.let { unmatched -> applied + unmatched }
            } ?: return persistenceFailure()
            if (!replay.clearPendingModelApplicationsOffMain(clears, env.persistenceDispatcher)) {
                if (stillCurrent(socket, connectionGeneration)) persistenceFailure()
                return
            }
        } finally {
            resolvingPendingModel = false
        }
        if (!stillCurrent(socket, connectionGeneration) || replayState !== replay) return
        lifecycleGate.runIfActive {
            pendingVerifiedClean = !snapshotSeqRegressed &&
                skips.values.none { it.category == SnapshotRecordCategory.SKIP_UNVERIFIED }
            resetSnapshot()
            awaitingHelloAck = true
            when (sendHelloV3()) {
                HelloResult.SENT -> Unit
                HelloResult.EXHAUSTED -> {
                    awaitingHelloAck = false
                    enterPausedActionRequired(SyncIssueCode.SESSION_COUNTER_BEHIND, retryOnForeground = false)
                    closeLocally(SyncLocalClose.LEAVE, socket)
                }
                HelloResult.FAILED -> {
                    awaitingHelloAck = false
                    failConnection(SyncLocalClose.LIVENESS_TIMEOUT)
                }
            }
        }
    }

    private fun stillCurrent(socket: SyncWebSocket, connectionGeneration: Long): Boolean =
        !lifecycleGate.isDisposed && !backgroundPresenceOnly && !awaitingForegroundStores &&
            ws === socket && activeConnectionGeneration == connectionGeneration

    /**
     * Puts whose local id no longer matches the casing the stores keep that UUID under now.
     * They get the current one, and true means the caller restages so the folded object and
     * its expected hash follow. Nothing stored under that UUID any more keeps what it had,
     * the bookkeeping for a delete is still keyed by it.
     */
    private fun rebindLocalIds(validated: MutableList<ValidatedV3>, model: ModelLookup): Boolean {
        var changed = false
        val iterator = validated.listIterator()
        while (iterator.hasNext()) {
            val record = iterator.next() as? ValidatedV3.Put ?: continue
            val canonical = SyncIdentity.canonicalUuid(record.localId) ?: continue
            val now = model.localIdOf(canonical) ?: canonical
            if (now == record.localId || model.kind(now) == null) continue
            iterator.set(record.copy(localId = now))
            changed = true
        }
        return changed
    }

    /** User touched layers while the snapshot was in flight: recompute against what's committed now (section 3). */
    private suspend fun restageIfLayersChanged(
        validated: MutableList<ValidatedV3>,
        committedAtBegin: List<com.tacmap.drawings.DrawingLayer>,
        current: List<com.tacmap.drawings.DrawingLayer>,
        skips: MutableMap<String, SnapshotRecordReason>,
        force: Boolean = false,
    ) {
        if (!force && current == committedAtBegin) return
        val model = ModelLookup()
        val generations = modelRevisionJournal.generationsCopy()
        val stageEligible = replayState?.snapshotStageEligibility(model::hash, { generations[it] ?: 0L }) ?: return
        // Reparse against captured current layers on the validation worker.
        // The caller rechecks the captured stores before starting durability.
        withContext(env.validationDispatcher) {
            val staged = ArrayList(current)
            val iterator = validated.listIterator()
            while (iterator.hasNext()) {
                val record = iterator.next() as? ValidatedV3.Put ?: continue
                val parsed = runCatching { GeoJsonImporter.parse(
                    record.content, staged, staged.firstOrNull()?.id ?: DrawingDocument.DEFAULT_LAYER_ID,
                    density = displayDensity, keepRingAnchors = true,
                ) }.getOrNull()?.let { SnapshotValidator.withLocalId(it, record.localId) }
                val hash = parsed?.let {
                    SnapshotValidator.expectedModelHash(it, record.localId, staged, displayDensity)
                }
                if (parsed == null || hash == null) {
                    skips[record.mutation.wireObjectId] = if (parsed == null)
                        SnapshotRecordReason.IMPORTER_FAILED else SnapshotRecordReason.EXPECTED_HASH_UNAVAILABLE
                    iterator.remove()
                    continue
                }
                iterator.set(record.copy(parsed = parsed, expectedModelHash = hash))
                if (stageEligible(record.mutation)) {
                    for (layer in parsed.newLayers) if (staged.none { it.id == layer.id }) staged += layer
                }
            }
            lastInboundProgressMs.set(nowMs())
        }
    }

    private fun recordSnapshotSkips(skips: Map<String, SnapshotRecordReason>) {
        if (skips.isEmpty()) return
        var unverified = 0
        var unsupported = 0
        for ((wireId, reason) in skips) {
            recordSkip(wireId, reason.category)
            if (reason.category == SnapshotRecordCategory.SKIP_UNVERIFIED) unverified++ else unsupported++
        }
        if (unverified > 0) {
            surfaceOnce(SyncIssueCode.SKIPPED_UNVERIFIED, "join", issueMessage(SyncIssueCode.SKIPPED_UNVERIFIED, unverified))
        }
        if (unsupported > 0) {
            surfaceOnce(SyncIssueCode.SKIPPED_UNSUPPORTED, "join", issueMessage(SyncIssueCode.SKIPPED_UNSUPPORTED, unsupported))
        }
    }

    private fun applyDeliveryAck(msg: JSONObject, v3: Boolean) {
        val ack = parseDeliveryAckFrame(msg, v3) ?: return
        val delivered = outboundDeliveries.acknowledge(ack) ?: return
        resolveDelivery(delivered)
        // first op-ack of the session proves it works, so the backoff can reset
        backoff.opAcked()
        establishBaseline(delivered)
        if (_status.value == Status.CONNECTED) scheduleDiff()
        pumpOutbound()
    }

    /** Echo baseline once the relay holds our write. */
    private fun establishBaseline(delivered: PendingOutboundDelivery) {
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
    }

    /**
     * plans/04 section 6. A nack is relay data: it can drop, retry, pause or
     * reconnect our own work, never touch replay state and never be SECURITY.
     */
    private fun applyDeliveryNack(msg: JSONObject, v3: Boolean) {
        val nack = parseDeliveryNackFrame(msg, v3) ?: return
        val pending = outboundDeliveries.reject(nack) ?: return
        val decision = SyncNackPolicy.decide(
            nack.code,
            nack.retryable,
            rejectedStampEqualsOwnPersisted = v3 && ownStampStillPersisted(pending),
            wireIdSkippedThisJoin = pending.wireObjectId in skippedWireIds,
        )
        when {
            decision.resolveOp -> {
                resolveDelivery(pending)
                if (decision.markConfirmed) establishBaseline(pending)
                if (decision.suppressUntilLocalEdit) suppressUntilLocalEdit(pending.localId)
                if (decision.pauseMutationsForJoin) pauseMutationsForJoin()
                decision.issue?.takeIf { it != SyncIssueCode.ROOM_RESET_CHANGES_PAUSED }
                    ?.let(::surfaceOncePerSession)
                pumpOutbound()
            }
            decision.retry -> {
                val ahead = writtenUnacked.count { it != pending.requestId }
                ackTimer.restartTimer(pending.requestId, nowMs(), ahead)
                scheduleAckCheck()
            }
            decision.reconnect -> closeLocally(decision.localClose ?: SyncLocalClose.SESSION_NACK)
        }
    }

    /** Relay says stale and still holds exactly the stamp we reserved: that's our write. */
    private fun ownStampStillPersisted(pending: PendingOutboundDelivery): Boolean {
        val replay = replayState ?: return false
        val stamp = VersionStamp.parse(pending.version) ?: return false
        return stamp.actorId == myActorId && replay.getStamp(pending.wireObjectId) == stamp
    }

    /** Every local object's kind by id, for the identity collision check (section 2.1). */
    private fun localObjectKinds(): HashMap<String, String> {
        val kinds = HashMap<String, String>()
        drawingStoreRef?.committedDocument?.value?.features?.forEach { kinds[it.id] = "drawing" }
        waypointStoreRef?.committedWaypoints?.value?.forEach { kinds[it.id] = "waypoint" }
        return kinds
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
        val by = rec.optString("by")
        // Monotonic per-id (v, by): reject anything not newer than what we
        // applied, and keep rejecting after a delete (versions[id] survives as
        // a tombstone) so a relay can't resurrect a deleted object by replaying
        // an older-but-validly-signed put. Either case is fine on the wire,
        // state is keyed by lowercase (S3-01), equal v goes to the larger by (S3-14).
        val id = acceptedLegacySyncRecordId(rawId, v, by, versions, lastByV2) ?: return
        val key = roomKey ?: return
        val kind = rec.optString("kind", "unknown")
        val ctB64 = rec.optString("ct")
        if (ctB64.isEmpty() || ctB64.length > MAX_BASE64_BYTES) return
        // AEAD and signature cover the id exactly as the sender wrote it
        val aad = SyncCrypto.aad(rawId, v, kind)
        val plain = SyncCrypto.open(key, SyncCrypto.decodeBase64(ctB64), aad) ?: return
        val inner = runCatching { JSONObject(String(plain, Charsets.UTF_8)) }.getOrNull() ?: return
        val content = inner.optString("c")
        // Device authorship: the write must be signed by the key pinned to `by`
        // (TOFU). A room member can't forge a write as another established
        // device; a key that doesn't match the pin is rejected as a swap.
        if (!verifyObjectSig(by, inner, SyncSigning.objectMessage(rawId, v, kind, by, content))) return
        val doc = drawingStore.committedDocument.value
        val fallback = doc.layers.firstOrNull()?.id ?: DrawingDocument.DEFAULT_LAYER_ID
        val imported = runCatching {
            GeoJsonImporter.parse(
                content,
                existingLayers = doc.layers,
                fallbackLayerId = fallback,
                density = displayDensity,
                keepRingAnchors = true,
            )
        }.getOrNull() ?: return
        if (!isValidLegacySyncPut(rawId, kind, imported)) return
        // accepted, applied or not, so learn the casing a 2.x iOS sender used
        legacyV2Ids?.learn(rawId)
        // the local object lives under the lowercase key too, otherwise the next
        // diff would see two ids and echo a delete back (S3-01 in reverse)
        val parsed = imported.copy(
            waypoints = imported.waypoints.map { if (it.id == id) it else it.copy(id = id) },
            drawings = imported.drawings.map { if (it.id == id) it else it.copy(id = id) },
        )

        if (forcedLegacyDeletes.containsKey(id)) {
            // An ack-lost local delete wins over an older reconnect snapshot.
            // Record the remote clock but leave the model absent and resend a
            // fresh authenticated tombstone after the snapshot fence.
            clock = maxOf(clock, v)
            versions[id] = v
            lastByV2[id] = by
            return
        }
        if (id in forcedLocalDiff) {
            val current = reexport(id)
            clock = maxOf(clock, v)
            versions[id] = v
            lastByV2[id] = by
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
        lastByV2[id] = by
        // re-export so echo guard matches what our next diff will see
        // (import -> export must be a fixed point)
        kindById[id] = kind
        lastContent[id] = reexport(id)

        // notify UI about the remote change (conflict notification)
        val objectName = parsed.waypoints.firstOrNull()?.name
            ?: parsed.drawings.firstOrNull()?.name
            ?: L10n.text("Object")
        val kindLabel = if (kind == "waypoint") L10n.text("Waypoint") else L10n.text("Drawing")
        _remoteUpdates.tryEmit(L10n.text("%1\$s '%2\$s' updated by another device", kindLabel, objectName))
    }

    private fun applyDelete(rec: JSONObject, snapshotGeneration: Long? = null) {
        val rawId = rec.optString("id").ifEmpty { return }
        val v = strictVersion(rec, "v") ?: return
        val by = rec.optString("by")
        val id = acceptedLegacySyncRecordId(rawId, v, by, versions, lastByV2) ?: return
        val key = roomKey ?: return
        val ctB64 = rec.optString("ct")
        if (ctB64.isEmpty() || ctB64.length > MAX_BASE64_BYTES) return
        // Open the sealed proof (proves room-key possession, so a relay with no
        // room key can't forge a delete) then verify the device signature.
        val aad = SyncCrypto.aad(rawId, v, "del")
        val plain = SyncCrypto.open(key, SyncCrypto.decodeBase64(ctB64), aad) ?: return
        val inner = runCatching { JSONObject(String(plain, Charsets.UTF_8)) }.getOrNull() ?: return
        if (!verifyObjectSig(by, inner, SyncSigning.objectMessage(rawId, v, "del", by, ""))) return
        // same as a put: accepted is enough, the entry outlives the delete so an undo keeps the casing
        legacyV2Ids?.learn(rawId)
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
            lastByV2[id] = by
            if (exactSnapshotConfirmation) {
                dropDeliveryTimers(recovery.requestId)
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
            lastByV2[id] = by
            return
        }
        val kindLabel = when (kindById[id]) {
            "drawing" -> L10n.text("Drawing")
            "waypoint" -> L10n.text("Waypoint")
            else -> L10n.text("Object")
        }
        if (!removeSyncedObject(id, kindById[id])) {
            reportError(
                Messages.syncASyncedSymbolDeleteCouldNotBeSavedCheckMessage(),
                SyncIssueKind.SECURITY,
            )
            return
        }
        clock = maxOf(clock, v)
        versions[id] = v
        lastByV2[id] = by
        lastContent.remove(id); kindById.remove(id)
        _remoteUpdates.tryEmit(L10n.text("%1\$s deleted by another device", kindLabel))
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
        // not a backoff reset: that waits for the first op-ack or 30 s connected
        onSessionConnected()
        rejectedHelloEpoch = null
        liveWindowResync.clearPending()
        _status.value = Status.CONNECTED
        startChatSessionV3()
        refreshChatAvailability()
        _lastError.value = issueLifecycle.connectionSucceeded(
            atGeneration = activeConnectionGeneration,
            verifiedCleanSnapshot = pendingVerifiedClean,
        )?.pendingMessage
        // A reconnect rotates the v3 session domain, so publish on the new
        // authenticated transport immediately. A still-recent GPS fix may seed
        // this session once; older fixes must wait for a new OS callback.
        presenceCadence.beginAuthenticatedSession()
        presencePolicy.beginSession()
        startPresenceFenceFlush()
        sendCurrentOrRequestFreshForegroundPresence(forceFreshRequest = true)
        // paced like everything else, so a departed author's tombstone backlog
        // can't trip the relay's rate window right after hello-ack
        if (!mutationsPaused) replayState?.recoverableLocalDeletes(actor, myPublicKey)?.forEach { (wireId, stamp) ->
            if (!shouldResendRecoverableDelete(wireId, stamp, snapshotConfirmedLocalDeletes)) return@forEach
            if (wireId in skippedWireIds) return@forEach
            val localId = findLocalIdForWireId(wireId) ?: "wire:$wireId"
            if (isSuppressed(localId)) return@forEach
            sendDelV3(localId, wireId, stamp)
        }
        snapshotConfirmedLocalDeletes.clear()
        syncLocalState(waypointStore.committedWaypoints.value, drawingStore.committedDocument.value)
    }

    /** Flush accepted presence counters at most every 60 s while they're ahead of disk (17.1). */
    private fun startPresenceFenceFlush() {
        presenceFlushJob?.cancel()
        val socket = ws ?: return
        presenceFlushJob = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(PresenceFencePersistence.FLUSH_MS)
                lifecycleGate.runIfActive {
                    if (ws !== socket) return@runIfActive
                    val replay = replayState ?: return@runIfActive
                    // a failed flush isn't a safety problem, the stride write before exposure is
                    if (replay.hasUnflushedPresence && !replay.isInBatch && !replay.isPersistenceInFlight &&
                        !backgroundPresenceOnly && !awaitingForegroundStores) {
                        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                            replay.flushPresenceOffMain(env.persistenceDispatcher)
                        }
                    }
                }
                if (ws !== socket) return@launch
            }
        }
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
            peersView[by]?.let { peer ->
                val nowUptimeMs = System.nanoTime() / 1_000_000L
                val updated = if (peer.sessionDomain == sd) {
                    PresenceExpiryPolicy.transportRestored(peer, nowUptimeMs)
                } else {
                    PresenceExpiryPolicy.markedStale(peer, nowUptimeMs)
                }
                if (updated != peer) {
                    peersView = peersView + (by to updated)
                }
            }
        }
        activeSessions[by] = pub to sd
        publishOnlineMembers(onlineMemberTracker.authenticatedHello(
            clientId = by,
            sessionDomain = sd,
            nowMs = System.currentTimeMillis(),
        ))
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
        _chatAvailabilityMessage.value = Messages.chatSecureChatKeyWasRejectedMessage(nack.third)
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
                TacMapChatPayload.boundedDisplayName(presenceConfig.callsign).ifBlank { L10n.text("This device") }
            } else null,
            kind = payload.kind,
            body = payload.body,
            sentAtMilliseconds = payload.createdAt,
            isOutgoing = false,
            deliveryState = TacMapChatDeliveryState.RECEIVED,
        )
        when (val accepted = chatHistoryStore.acceptInbound(
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
                // Tell the map a new message arrived; a duplicate was already announced.
                if (accepted == TacMapChatInboundResult.ACCEPTED) {
                    _remoteUpdates.tryEmit(
                        if (payload.kind == TacMapChatContentKind.REPORT) {
                            Messages.chatNewReportNotice(senderName)
                        } else {
                            Messages.chatNewMessageNotice(senderName)
                        },
                    )
                }
            }
            TacMapChatInboundResult.REPLAY_REJECTED -> Unit
            TacMapChatInboundResult.REPLAY_TABLE_FULL -> surfaceOnce(SyncIssueCode.CHAT_REPLAY_FULL, "join")
            TacMapChatInboundResult.STORE_UNAVAILABLE -> {
                clearChatTransport(markPendingFailed = true)
                refreshChatAvailability()
            }
        }
    }

    /** One validator per room session for live records, pins read straight from replay state. */
    private fun liveValidatorOrNull(): SnapshotValidator? {
        liveValidator?.let { return it }
        val keys = v3Keys ?: return null
        val key = roomKey ?: return null
        val validator = SnapshotValidator(
            key, keys.roomIdRaw, keys.metadataKey,
            pinnedKey = { actor -> replayState?.getPinnedPubkey(actor) },
            displayDensity = displayDensity,
        )
        liveValidator = validator
        return validator
    }

    /**
     * localId <-> wireId for this room session (plans/04 section 18), built
     * once and folded forward from the store contents. Null without keys or
     * stores.
     */
    private fun ensureWireIndex(): WireIdIndex? {
        val keys = v3Keys ?: return null
        val waypoints = waypointStoreRef ?: return null
        val drawings = drawingStoreRef ?: return null
        val index = wireIndex ?: WireIdIndex(keys.metadataKey).also { created ->
            wireIndex = created
            lastContent.keys.forEach(created::add)
            // objects deleted before the stores went away are still owed a
            // tombstone. without them the hello-ack resend can't find the local
            // id, files it under wire:, and the diff reserves a second delete
            forcedLocalDiff.forEach(created::add)
        }
        index.refresh(waypoints.committedWaypoints.value, drawings.committedDocument.value.features)
        return index
    }

    private fun applyLiveRecordV3(rec: JSONObject) {
        val wireId = (rec.opt("id") as? String)?.takeIf { SyncIdentity.urlB64Decode32(it) != null } ?: return
        val openedHere = liveBatch == null
        val batch = openLiveBatch() ?: return
        try {
            val validator = liveValidatorOrNull() ?: return
            val checked = SnapshotRecordClassifier.classify(
                validator, rec, wireId, batch.layers, batch::localKind, batch::localIdOf,
            )
            var validated = when (checked) {
                is V3Check.Skip -> {
                    recordSkip(wireId, checked.reason.category)
                    val code = if (checked.reason.category == SnapshotRecordCategory.SKIP_UNVERIFIED) {
                        SyncIssueCode.SKIPPED_UNVERIFIED
                    } else {
                        SyncIssueCode.SKIPPED_UNSUPPORTED
                    }
                    surfaceOnce(code, "join", issueMessage(code, 1))
                    return
                }
                is V3Check.Valid -> checked.record
            }
            if (validated is ValidatedV3.Delete) validated = validated.copy(localId = findLocalIdForWireId(wireId))
            val replay = batch.replay
            when (replay.liveDecision(validated.mutation.wireObjectId, validated.mutation.stamp)) {
                SyncReplayState.LiveDecision.NOT_NEWER -> return
                SyncReplayState.LiveDecision.OUTSIDE_WINDOW -> {
                    // authentic and newer, but our baseline is too far behind: resync (section 4)
                    onLiveWindowRejection()
                    return
                }
                SyncReplayState.LiveDecision.ACCEPT -> Unit
            }
            // the model before this batch; nothing in the batch has touched it yet
            val priorHash = batch.before.hash(validated.localModelId)
            if (!replay.commitRemoteAuthenticated(
                    SyncReplayState.RemoteMutation(
                        validated.mutation, priorHash, validated.localModelId,
                        modelRevisionJournal.generation(validated.localModelId), validated.expectedModelHash))) {
                return persistenceFailure()
            }
            // later records in the same batch see this one's layers (section 3)
            SnapshotValidator.stage(batch.layers, checked)
            batch.records += validated
            (validated as? ValidatedV3.Put)?.let { batch.stagedKinds[it.localId] = it.kind }
        } finally {
            if (openedHere) scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { flushLiveBatch() }
        }
    }

    private fun onLiveWindowRejection() {
        if (liveWindowResync.windowRejection(nowMs())) {
            closeLocally(SyncLocalClose.LIVE_WINDOW_RESYNC)
            return
        }
        if (liveResyncJob?.isActive == true) return
        val at = liveWindowResync.nextTickMs(nowMs()) ?: return
        val socket = ws ?: return
        liveResyncJob = scope.launch {
            kotlinx.coroutines.delay((at - nowMs()).coerceAtLeast(0L))
            lifecycleGate.runIfActive {
                if (ws === socket && liveWindowResync.tick(nowMs())) closeLocally(SyncLocalClose.LIVE_WINDOW_RESYNC, socket)
            }
        }
    }

    /**
     * Model side of one snapshot or live batch, without overwriting a
     * divergent offline edit. Every record is decided against the model as it
     * is now; the incoming ones go in with one write per store; every receiver
     * hash is checked. Returns the markers to clear (in one replay write), or
     * null when a store write or a hash check failed.
     */
    private fun applyRemoteRecords(
        records: List<ValidatedV3>,
        /** Pass the batch's view when the model can't have moved since it was built. */
        model: ModelLookup? = null,
    ): List<SyncReplayState.AuthenticatedMutation>? {
        val replay = replayState ?: return null
        if (records.isEmpty()) return emptyList()
        val before = model ?: ModelLookup()
        val clears = ArrayList<SyncReplayState.AuthenticatedMutation>()
        val incoming = ArrayList<ValidatedV3>()
        val baselines = ArrayList<ValidatedV3>()
        for (record in records) {
            val mutation = record.mutation
            val current = before.hash(record.localModelId)
            when (replay.pendingModelDecision(
                mutation, current, modelRevisionJournal.generation(record.localModelId))) {
                SyncReplayState.PendingModelDecision.APPLY_INCOMING -> {
                    incoming += record
                    clears += mutation
                }
                SyncReplayState.PendingModelDecision.ALREADY_APPLIED -> {
                    baselines += record
                    clears += mutation
                }
                SyncReplayState.PendingModelDecision.LOCAL_DIVERGED -> {
                    record.localModelId?.let { forcedLocalDiff += it }
                    clears += mutation
                }
                SyncReplayState.PendingModelDecision.NONE -> {
                    // An exact resolved record may establish the echo baseline, but
                    // must never overwrite a model that has since diverged.
                    if (replay.isExactPersistedMutation(mutation)) {
                        if (current == record.expectedModelHash) baselines += record
                        else record.localModelId?.let { forcedLocalDiff += it }
                    }
                }
            }
        }
        val after = if (incoming.isEmpty()) before else {
            if (!writeIncoming(incoming, before)) return null
            ModelLookup()
        }
        for (record in incoming) {
            if (after.hash(record.localModelId) != record.expectedModelHash) return null
        }
        for (record in incoming) {
            markModelBaseline(record, after)
            announceRemoteChange(record, before)
        }
        for (record in baselines) markModelBaseline(record, after)
        return clears
    }

    /** One write per store for everything [incoming] adds, changes or deletes. */
    private fun writeIncoming(incoming: List<ValidatedV3>, before: ModelLookup): Boolean {
        val layerIds = before.document.layers.mapTo(HashSet()) { it.id }
        val newLayers = ArrayList<com.tacmap.drawings.DrawingLayer>()
        val waypointUpserts = ArrayList<Waypoint>()
        val featureUpserts = ArrayList<DrawingFeature>()
        val waypointRemovals = HashSet<String>()
        val featureRemovals = HashSet<String>()
        for (record in incoming) when (record) {
            is ValidatedV3.Put -> {
                // first one wins, same rule the validator staged with
                for (layer in record.parsed.newLayers) if (layerIds.add(layer.id)) newLayers += layer
                waypointUpserts += record.parsed.waypoints
                featureUpserts += record.parsed.drawings
            }
            is ValidatedV3.Delete -> record.localId?.let { id ->
                when (before.kind(id)) {
                    "waypoint" -> waypointRemovals += id
                    "drawing" -> featureRemovals += id
                    // already gone counts as an idempotent success
                }
            }
        }
        if (!waypointStore.applyRemoteBatch(waypointUpserts, waypointRemovals, ModelMutationOrigin.REMOTE_SYNC)) return false
        if (!drawingStore.applyRemoteBatch(newLayers, featureUpserts, featureRemovals, ModelMutationOrigin.REMOTE_SYNC)) return false
        val index = wireIndex
        for (record in incoming) {
            if (record is ValidatedV3.Put) index?.add(record.localId)
        }
        return true
    }

    private fun announceRemoteChange(record: ValidatedV3, before: ModelLookup) {
        when (record) {
            is ValidatedV3.Put -> {
                val objectName = record.parsed.waypoints.firstOrNull()?.name
                    ?: record.parsed.drawings.firstOrNull()?.name ?: L10n.text("Object")
                val label = if (record.kind == "waypoint") L10n.text("Waypoint") else L10n.text("Drawing")
                _remoteUpdates.tryEmit(L10n.text("%1\$s '%2\$s' updated by another device", label, objectName))
            }
            is ValidatedV3.Delete -> {
                val id = record.localId ?: return
                val kindLabel = when (before.kind(id) ?: kindById[id]) {
                    "drawing" -> L10n.text("Drawing")
                    "waypoint" -> L10n.text("Waypoint")
                    else -> L10n.text("Object")
                }
                _remoteUpdates.tryEmit(L10n.text("%1\$s deleted by another device", kindLabel))
            }
        }
    }

    private fun markModelBaseline(record: ValidatedV3, lookup: ModelLookup) {
        when (record) {
            is ValidatedV3.Put -> {
                val content = lookup.export(record.localId)
                if (content.isNotEmpty()) {
                    lastContent[record.localId] = content
                    kindById[record.localId] = record.kind
                }
                forcedLocalDiff.remove(record.localId)
            }
            is ValidatedV3.Delete -> record.localId?.let {
                lastContent.remove(it); kindById.remove(it); forcedLocalDiff.remove(it)
            }
        }
    }

    /**
     * Pending records omitted or contradicted by the snapshot cannot be
     * repaired safely. Preserve the model and force it to win at a new stamp.
     * [alreadyResolved] are this snapshot's own markers, cleared with these.
     */
    private fun resolveUnmatchedPendingModelApplications(
        alreadyResolved: List<SyncReplayState.AuthenticatedMutation>,
    ): List<SyncReplayState.AuthenticatedMutation>? {
        val replay = replayState ?: return null
        val resolved = alreadyResolved.toHashSet()
        val leftovers = replay.pendingRemoteMutations().filter { it.mutation !in resolved }
        if (leftovers.isEmpty()) return emptyList()
        val lookup = ModelLookup()
        val clears = ArrayList<SyncReplayState.AuthenticatedMutation>(leftovers.size)
        for (remote in leftovers) {
            val localId = remote.localModelId
            if (lookup.hash(localId) == remote.expectedModelHash) {
                markCurrentModelBaseline(localId, lookup)
            } else {
                localId?.let { forcedLocalDiff += it }
            }
            clears += remote.mutation
        }
        return clears
    }

    private fun markCurrentModelBaseline(localId: String?, lookup: ModelLookup) {
        localId ?: return
        val content = lookup.export(localId)
        if (content.isEmpty()) {
            lastContent.remove(localId); kindById.remove(localId)
        } else {
            lastContent[localId] = content
            kindById[localId] = lookup.kind(localId) ?: "drawing"
        }
        forcedLocalDiff.remove(localId)
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
            val previous = peersView[by]?.let { old ->
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
                allowSimulatorTeleport = env.allowsSimulatorTeleport(),
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
        peersView = peersView + (by to peer)
        val now = System.currentTimeMillis()
        onlineMemberTracker.authenticatedActivity(by, sdText, now)
        publishOnlineMembers(onlineMemberTracker.updatePresenceMetadata(
            clientId = by,
            sessionDomain = sdText,
            callsign = payload.callsign,
            affiliation = payload.affiliation,
            echelon = payload.echelon,
            function = payload.function,
            isHQ = payload.isHQ,
            nowMs = now,
        ))
        publishChatRecipientsAfterCommit()
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

    /** Reverse lookup through the wire index; a miss means no local object, never a scan (section 18). */
    private fun findLocalIdForWireId(wireId: String): String? = ensureWireIndex()?.localId(wireId)

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

        val now = syncClock.elapsedRealtimeNanos()
        val lastSuccessfulFix = presenceCadence.latestSuccessfulFixElapsedRealtimeNanos
        val current = currentLocationSample()
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
                    sample = checkNotNull(current),
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
                val receivedAt = syncClock.elapsedRealtimeNanos()
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
                    sample = PresenceFixSample.from(requestedLocation),
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
        sample: PresenceFixSample,
        isBackground: Boolean,
        backgroundInterval: com.tacmap.settings.BackgroundUnitSyncInterval,
        nowElapsedRealtimeNanos: Long,
    ): Boolean {
        val fixElapsedRealtimeNanos = sample.elapsedRealtimeNanos
        if (!presenceCadence.isSendDue(
                fixElapsedRealtimeNanos = fixElapsedRealtimeNanos,
                nowElapsedRealtimeNanos = nowElapsedRealtimeNanos,
                isBackground = isBackground,
                backgroundInterval = backgroundInterval,
            )
        ) return false
        val qualityFix = sample.qualityFix() ?: return false
        val heading = sample.bearingDegrees ?: 0.0
        val speed = sample.speedMps ?: 0.0
        // foreground only: a parked unit sends a 20 s heartbeat instead of every 5 s (S5-10)
        val policyFix = PresenceSendPolicy.Fix(
            lat = sample.latitude,
            lon = sample.longitude,
            speedMps = sample.speedMps,
            courseDeg = sample.bearingDegrees,
            horizontalAccuracyM = sample.accuracyMetres,
        )
        val policyConfig = presenceFingerprint()
        if (!isBackground && !presencePolicy.shouldSend(policyFix, nowMs(), policyConfig)) return false
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
            allowSimulatorTeleport = env.allowsSimulatorTeleport(),
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
            sendPresenceV3(sample, retentionSeconds)
        } else {
            sendPresenceV2(sample)
        }
        presenceCadence.recordSendResult(
            successful = successful,
            fixElapsedRealtimeNanos = fixElapsedRealtimeNanos,
            completedAtElapsedRealtimeNanos = syncClock.elapsedRealtimeNanos(),
        )
        if (successful && !isBackground) presencePolicy.recordSent(policyFix, nowMs(), policyConfig)
        return successful
    }

    /** The parts of the presence config peers see; a change sends right away. */
    private fun presenceFingerprint(): List<Any> = presenceConfig.let {
        listOf(it.callsign, it.affiliation, it.echelon, it.function, it.isHQ)
    }

    private fun sendPresenceV2(loc: PresenceFixSample): Boolean {
        val key = roomKey ?: return false
        val cfg = presenceConfig
        if (!cfg.shareLocation) return false
        val callsign = boundCallsign(cfg.callsign)
        val ts = System.currentTimeMillis()
        val lat = loc.latitude
        val lon = loc.longitude
        val heading = loc.bearingDegrees ?: 0.0
        val speed = loc.speedMps ?: 0.0
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

    private fun sendPresenceV3(loc: PresenceFixSample, retentionSeconds: Int): Boolean {
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
        val heading = loc.bearingDegrees ?: 0.0
        val speed = loc.speedMps ?: 0.0
        if (presenceCounter >= VersionStamp.MAX_COUNTER) {
            // counter space is gone for this session domain, rotate to a fresh one
            failConnection(SyncLocalClose.LIVENESS_TIMEOUT)
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
        val accuracyPayload = PresenceAccuracyV3.encodePayload(loc.accuracyMetres ?: return false) ?: return false
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
            // the pacer re-checks consent under the same lock right before the write
            enqueueFrame(SyncOutboundClass.PRESENCE, frame, guard = {
                presenceConfig.shareLocation && presenceConfig == expectedConfig
            })
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
            java.util.Base64.getEncoder().encodeToString(
                SealedEnvelope.sealFile(
                    SafeStore.keyProvider.key(), jsonStr.toByteArray(Charsets.UTF_8), PRESENCE_LABEL),
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
                    Messages.syncSavedUnitSyncIdentityLocationSharingIsLockedOrMessage(),
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
                        Messages.syncCouldNotMigrateUnitSyncIdentityLocationSharingToMessage(),
                        SyncIssueKind.SECURITY,
                    )
                }
            }
        }
    }

    private fun readSealedPresenceConfig(): PresenceConfig? {
        val stored = prefs.getString(KEY_PRESENCE, null) ?: return null
        val obj = runCatching {
            val blob = java.util.Base64.getDecoder().decode(stored)
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
                    SafeStore.keyProvider.key(), java.util.Base64.getDecoder().decode(stored), DEVICE_SEED_LABEL)
            } catch (t: Throwable) {
                throw IllegalStateException(L10n.text("sync signing seed unavailable"), t)
            }
            if (seed == null || seed.size != 32) throw IllegalStateException(L10n.text("sync signing seed corrupt"))
            return seed
        }
        val seed = SyncSigning.generateSeed()
        try {
            val sealed = java.util.Base64.getEncoder().encodeToString(
                SealedEnvelope.sealFile(SafeStore.keyProvider.key(), seed, DEVICE_SEED_LABEL))
            if (!DurablePreferenceCommit.preferences(
                    preferences = prefs,
                    keys = setOf(KEY_DEVICE_SEED),
                    mutate = { putString(KEY_DEVICE_SEED, sealed) },
                    publish = {},
                )
            ) {
                throw IllegalStateException(L10n.text("could not persist sync signing seed"))
            }
        } catch (t: Throwable) {
            throw IllegalStateException(L10n.text("sync signing seed unavailable"), t)
        }
        return seed
    }

    private fun resetSnapshot() {
        snapshotSeq = null
        snapshotSawFinalPage = false
        snapshotItemCount = 0
        snapshotSeqRegressed = false
        snapshotRun?.close()
        snapshotRun = null
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

    private fun failV2Snapshot(socket: SyncWebSocket, connectionGeneration: Long, reason: String) {
        if (ws !== socket || activeConnectionGeneration != connectionGeneration) return
        v2SnapshotTimeoutJob?.cancel(); v2SnapshotTimeoutJob = null
        v2SnapshotFailureGeneration = connectionGeneration
        _status.value = Status.OFFLINE
        reportError(
            Messages.syncUnitSyncSnapshotFailedVerifyTheRelayOrNetworkMessage(reason),
            SyncIssueKind.CONNECTION,
            connectionGeneration,
        )
        // legacy rooms keep today's plain transient retry
        closeLocally(SyncLocalClose.HANDSHAKE_STALL, socket)
    }

    internal fun boundCallsign(value: String): String {
        val count = value.codePointCount(0, value.length)
        return if (count <= 64) value else value.substring(0, value.offsetByCodePoints(0, 64))
    }

    /**
     * Structural snapshot failure (plans/04 section 2.4): nothing from this
     * snapshot is committed, say so once per session, close and retry with
     * transient backoff. Three in a row without a hello-ack and we stop.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun failSnapshot(reason: SnapshotRecordReason) {
        v3HandshakeFailureGeneration = activeConnectionGeneration
        awaitingHelloAck = false
        resetSnapshot()
        surfaceOncePerSession(SyncIssueCode.SNAPSHOT_STRUCTURAL)
        failConnection(SyncLocalClose.STRUCTURAL_SNAPSHOT, clearPeers = true)
    }

    private fun failConnection(reason: SyncLocalClose, clearPeers: Boolean = false) {
        cancelSessionTimers()
        cancelForegroundPresenceRefresh()
        awaitingHelloAck = false
        _status.value = Status.OFFLINE
        clearChatTransport(markPendingFailed = true)
        activeSessions.clear()
        remotePresenceCandidateClusters.clear()
        _onlineMembers.value = onlineMemberTracker.clear()
        if (clearPeers) _peers.value = emptyMap() else markPeersStale()
        closeLocally(reason)
    }

    private fun persistenceFailure() {
        liveBatch?.failed = true
        cancelSessionTimers()
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
            Messages.syncSyncStoppedBecauseRollbackStateCouldNotBeSecuredMessage(),
            SyncIssueKind.SECURITY,
        )
        markLocalClose(SyncLocalClose.PERSISTENCE_FAILURE)
        ws?.close(4014, L10n.text("secure state unavailable"))
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
