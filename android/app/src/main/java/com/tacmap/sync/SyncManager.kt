package com.tacmap.sync

import android.content.Context
import android.location.Location
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
import com.tacmap.util.SafeStore
import com.tacmap.util.SealedEnvelope
import kotlinx.coroutines.CoroutineScope
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

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
    private val managerJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + managerJob)

    private val _status = MutableStateFlow(Status.OFFLINE)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _room = MutableStateFlow<String?>(null)
    /** Join code of active room (for display), null when not syncing. */
    val room: StateFlow<String?> = _room.asStateFlow()

    private val _peers = MutableStateFlow<Map<String, PresencePeer>>(emptyMap())
    val peers: StateFlow<Map<String, PresencePeer>> = _peers.asStateFlow()

    private val _remoteUpdates = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val remoteUpdates: SharedFlow<String> = _remoteUpdates.asSharedFlow()

    var presenceConfig: PresenceConfig = PresenceConfig()
        set(value) {
            field = value
            savePresenceConfig()
        }

    /** Hook up a location supplier so sendPresence can grab the latest fix. */
    var locationProvider: (() -> Location?)? = null

    private val appFilesDir: File = context.applicationContext.filesDir
    private val prefs = context.applicationContext.getSharedPreferences("sync", Context.MODE_PRIVATE)
    private val clientId: String = prefs.getString("clientId", null)
        ?: UUID.randomUUID().toString().also { prefs.edit().putString("clientId", it).apply() }

    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private var roomKey: ByteArray? = null
    private var authToken: String? = null
    // Resolved from OPSEC settings at join time so a self-hoster's relay is
    // actually used; falls back to ours. Kept for the reconnect path.
    private var relayBase: String = RELAY_BASE
    private var wantConnected = false
    private val lifecycleGate = SyncLifecycleGate()
    private var reconnectJob: Job? = null
    private var observeJob: Job? = null
    private var revisionJob: Job? = null
    private val modelRevisionJournal = LocalModelRevisionJournal(appFilesDir)
    private val revisionEventProcessor = LocalRevisionEventProcessor(modelRevisionJournal) {
        revisionJournalAvailable = false
        _remoteUpdates.tryEmit("Local revision history could not be saved; sync is paused.")
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
    private var snapshotAggregateBytes = 0L
    private val snapshotWireIds = HashSet<String>()
    private val pendingSnapshot = ArrayList<ValidatedV3>()
    private val forcedLocalDiff = HashSet<String>()
    private var resolvingPendingModel = false

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

    init {
        loadPresenceConfig()
        revisionJournalAvailable = modelRevisionJournal.load()
        startModelRevisionObservation()
    }

    // ----- Public API -----

    fun join(joinCode: String) {
        if (lifecycleGate.isDisposed) return
        val code = joinCode.trim()
        if (code.isEmpty()) return
        if (!code.startsWith("3:") && !code.startsWith("2:")) {
            _remoteUpdates.tryEmit("Join code must start with 3:. Legacy rooms require an explicit 2: prefix.")
            return
        }
        if (SyncCrypto.isJoinCodeTooWeak(code)) {
            _remoteUpdates.tryEmit("Join code is too short to be safe")
            return
        }
        leave()
        if (runCatching { deviceSeed; myPublicKey }.isFailure) {
            _remoteUpdates.tryEmit("Sync signing identity is locked or damaged")
            return
        }
        relayBase = normalizeRelayBase(OpsecSettings.shared?.relayUrl?.value?.takeIf { it.isNotBlank() } ?: RELAY_BASE)

        if (code.startsWith("3:")) {
            val setup = runCatching {
                // Resolving the signing identity can fail while the at-rest key
                // is locked or if the sealed seed is corrupt. Never rotate it.
                val pubRaw = myPublicKeyRaw
                val keys = SyncCrypto.deriveRoomV3(code.removePrefix("3:"))
                val replay = SyncReplayState(keys.roomId, appFilesDir)
                check(replay.load()) { "replay state unavailable" }
                Triple(keys, replay, SyncIdentity.actorId(keys.roomIdRaw, pubRaw))
            }.getOrElse {
                _status.value = Status.OFFLINE
                _remoteUpdates.tryEmit("Sync identity or rollback state is locked or damaged")
                return
            }
            protocolVersion = 3
            v3Keys = setup.first
            roomKey = setup.first.roomKey
            authToken = setup.first.authToken
            replayState = setup.second
            myActorId = setup.third
            _room.value = code
            wantConnected = true
            connect(setup.first.roomId)
        } else {
            // v2 protocol: unchanged
            protocolVersion = 2
            val keys = SyncCrypto.deriveRoom(code.removePrefix("2:"))
            roomKey = keys.roomKey
            authToken = keys.authToken
            _room.value = code
            wantConnected = true
            connect(keys.roomId)
        }
        startObserving()
        startPresenceBroadcast()
        startStalenessSweep()
    }

    fun leave() {
        wantConnected = false
        reconnectJob?.cancel(); reconnectJob = null
        observeJob?.cancel(); observeJob = null
        presenceJob?.cancel(); presenceJob = null
        stalenessSweepJob?.cancel(); stalenessSweepJob = null
        ws?.close(1000, "leave"); ws?.cancel(); ws = null
        clearRoomSecrets()
        authToken = null
        _room.value = null
        _status.value = Status.OFFLINE
        _peers.value = emptyMap()
        versions.clear(); lastContent.clear(); kindById.clear()
        forcedLocalDiff.clear(); resolvingPendingModel = false
        peerKeys.clear(); peerTs.clear()
        // v3 state: clear transport-session fields but NOT replayState (durable)
        myActorId = null
        presenceCounter = 0L
        activeSessions.clear()
        awaitingHelloAck = false
        localHelloVersion = null
        resetSnapshot()
        replayState = null
        protocolVersion = 2
    }

    /** Terminal teardown for the composition/key lifetime. Idempotent and
     * synchronous: after this returns no socket, observer, reconnect or
     * presence work can run, and in-memory room/signing secrets are zeroed. */
    fun dispose() {
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
            ws?.close(1000, "dispose")
            ws?.cancel()
            ws = null
            http.dispatcher.cancelAll()
            http.connectionPool.evictAll()
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

    private fun clearRoomStateAfterDispose() {
        clearRoomSecrets()
        authToken = null
        relayBase = RELAY_BASE
        locationProvider = null
        _room.value = null
        _status.value = Status.OFFLINE
        _peers.value = emptyMap()
        versions.clear(); lastContent.clear(); kindById.clear()
        forcedLocalDiff.clear(); resolvingPendingModel = false
        peerKeys.clear(); peerTs.clear()
        myActorId = null
        replayState = null
        presenceCounter = 0L
        activeSessions.clear()
        awaitingHelloAck = false
        localHelloVersion = null
        resetSnapshot()
        revisionJournalAvailable = false
        waypointStoreRef = null
        drawingStoreRef = null
        protocolVersion = 2
    }

    // ----- Connection -----

    private fun connect(roomId: String) {
        if (lifecycleGate.isDisposed) return
        _status.value = Status.CONNECTING
        if (protocolVersion == 3) {
            sessionDomain = SyncIdentity.generateSessionDomain()
            presenceCounter = 0L
            activeSessions.clear()
            awaitingHelloAck = false
            localHelloVersion = null
            resetSnapshot()
        }
        val path = if (protocolVersion == 3) "v3/room/" else "room/"
        val base = normalizeRelayBase(relayBase)
        val url = "$base/$path$roomId"
        val builder = Request.Builder().url(url)
        authToken?.let { builder.header("Authorization", "Bearer $it") }
        if (protocolVersion == 3) {
            builder.header("X-Protocol", "3")
            builder.header("X-Room-Id", roomId)
        }
        lifecycleGate.runIfActive {
        ws = http.newWebSocket(builder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                lifecycleGate.runIfActive callback@{
                    if (ws !== webSocket) return@callback
                    // v3 is not allowed to publish until a complete authenticated
                    // snapshot fence has been applied.
                    if (protocolVersion == 2) _status.value = Status.CONNECTED
                }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                lifecycleGate.runIfActive callback@{
                    if (ws !== webSocket) return@callback
                    scope.launch { handleMessage(text) }
                }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                lifecycleGate.runIfActive callback@{
                    if (ws !== webSocket) return@callback
                    ws = null
                    _status.value = Status.OFFLINE
                    if (wantConnected) {
                        reconnectJob?.cancel()
                        reconnectJob = scope.launch {
                            kotlinx.coroutines.delay(3000)
                            if (wantConnected && !lifecycleGate.isDisposed) connect(roomId)
                        }
                    }
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                lifecycleGate.runIfActive callback@{
                    if (ws !== webSocket) return@callback
                    ws = null
                    _status.value = Status.OFFLINE
                    if (wantConnected) {
                        reconnectJob?.cancel()
                        reconnectJob = scope.launch {
                            kotlinx.coroutines.delay(3000)
                            if (wantConnected && !lifecycleGate.isDisposed) connect(roomId)
                        }
                    }
                }
            }
        })
        }
    }

    private fun sendFrame(frame: String): Boolean =
        lifecycleGate.sendIfActive { ws?.send(frame) == true }

    // ----- Outbound: observe local stores, diff, send -----

    private fun startObserving() {
        observeJob = scope.launch {
            combine(waypointStore.waypoints, drawingStore.document) { wps, doc -> wps to doc }
                .debounce(250)
                .collect { (wps, doc) -> syncLocalState(wps, doc) }
        }
    }

    /** Lifetime observer: revisions continue across disconnect and Leave. */
    private fun startModelRevisionObservation() {
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
            for (wp in wps) current[wp.id] = "waypoint" to GeoJsonExporter.export(listOf(wp), emptyList(), doc.layers)
            for (f in doc.features) current[f.id] = "drawing" to GeoJsonExporter.export(emptyList(), listOf(f), doc.layers)

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
            if (lastContent[id] == content) continue
            clock += 1
            versions[id] = clock
            lastContent[id] = content
            kindById[id] = kind
            sendPut(id, clock, kind, content)
        }
        val gone = lastContent.keys.filter { it !in current }
        for (id in gone) {
            clock += 1
            sendDel(id, clock)
            lastContent.remove(id); versions.remove(id); kindById.remove(id)
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
            val vs = replay.recoverableLocalPut(wireId, actor, myPublicKey, contentHash)
                ?: replay.reserveLocalPut(wireId, actor, myPublicKey, contentHash)
                ?: return persistenceFailure()
            if (sendPutV3(wireId, vs, kind, content)) {
                lastContent[id] = content
                kindById[id] = kind
                forcedLocalDiff.remove(id)
            }
        }
        val gone = (lastContent.keys + forcedLocalDiff).filter { it !in current }.distinct()
        for (id in gone) {
            val wireId = runCatching {
                SyncIdentity.wireObjectId(keys.metadataKey, SyncIdentity.uuidToBytes(id))
            }.getOrNull() ?: continue
            val vs = replay.reserveLocalDelete(wireId, actor, myPublicKey)
                ?: return persistenceFailure()
            if (sendDelV3(wireId, vs)) {
                lastContent.remove(id); kindById.remove(id)
                forcedLocalDiff.remove(id)
            }
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
        sendFrame(JSONObject().apply {
            put("t", "put"); put("id", id); put("v", v); put("by", clientId); put("kind", kind); put("ct", ct)
        }.toString())
    }

    private fun sendDel(id: String, v: Long) {
        val key = roomKey ?: return
        val sig = SyncSigning.sign(deviceSeed, SyncSigning.objectMessage(id, v, "del", clientId, ""))
        val inner = JSONObject().apply { put("pub", myPublicKey); put("sig", sig) }
        val aad = SyncCrypto.aad(id, v, "del")
        val ct = SyncCrypto.encodeBase64(SyncCrypto.seal(key, inner.toString().toByteArray(Charsets.UTF_8), aad))
        sendFrame(JSONObject().apply {
            put("t", "del"); put("id", id); put("v", v); put("by", clientId); put("ct", ct)
        }.toString())
    }

    // -- v3 outbound --

    private fun sendHelloV3(): Boolean {
        val keys = v3Keys ?: return false
        val actor = myActorId ?: return false
        val sd = sessionDomain ?: return false
        val epoch = replayState?.reserveHelloEpoch(actor) ?: return false
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

    private fun sendPutV3(wireId: String, vs: VersionStamp, kind: String, content: String): Boolean {
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
        return sendFrame(JSONObject().apply {
            put("t", "put"); put("id", wireId); put("vs", vs.encode())
            put("by", actor); put("kind", kind); put("ct", ct); put("pub", myPublicKey)
            put("sd", SyncIdentity.urlB64(sd))
        }.toString())
    }

    private fun sendDelV3(wireId: String, vs: VersionStamp): Boolean {
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
        return sendFrame(JSONObject().apply {
            put("t", "del"); put("id", wireId); put("vs", vs.encode())
            put("by", actor); put("kind", "del"); put("ct", ct); put("pub", myPublicKey)
            put("sd", SyncIdentity.urlB64(sd))
        }.toString())
    }

    // ----- Inbound -----

    companion object {
        /** Default E2E-blind relay base URL (path added per-protocol-version). */
        const val RELAY_BASE = "wss://tacmap-sync.christianbrooker.workers.dev"
        internal fun normalizeRelayBase(value: String): String {
            var base = value.trim().trimEnd('/')
            if (base.endsWith("/v3/room")) base = base.removeSuffix("/v3/room")
            if (base.endsWith("/room")) base = base.removeSuffix("/room")
            return base.trimEnd('/')
        }
        /** SharedPreferences key holding the sealed presence-config blob. */
        private const val KEY_PRESENCE = "config_sealed"
        /** AEAD label binding that blob so it can't be opened as another pref. */
        private const val PRESENCE_LABEL = "sync/presenceConfig"
        /** Sealed per-device Ed25519 signing seed + its AEAD label. */
        private const val KEY_DEVICE_SEED = "device_seed"
        private const val DEVICE_SEED_LABEL = "sync/deviceSeed"

        private const val MAX_FRAME_BYTES = 1_048_576        // 1 MiB text
        private const val MAX_BASE64_BYTES = 1_048_576        // 1 MiB encoded ct
        private const val MAX_SNAPSHOT_ITEMS = 10_000
        private const val MAX_SNAPSHOT_AGGREGATE_BYTES = 54_525_952L
        private const val MAX_VERSION = 1_000_000_000_000L   // matches relay MAX_V
    }

    private fun handleMessage(text: String) {
        lifecycleGate.runIfActive {
            if (text.length > MAX_FRAME_BYTES) return@runIfActive
            try {
                val msg = JSONObject(text)
                if (protocolVersion == 3) {
                    handleMessageV3(msg, text.toByteArray(Charsets.UTF_8).size)
                } else {
                    handleMessageV2(msg)
                }
            } catch (_: Throwable) {
                // Silently drop -- don't log frame content (SEC-019).
            }
        }
    }

    private fun handleMessageV2(msg: JSONObject) {
        when (msg.optString("t")) {
            "snapshot" -> {
                val items = msg.optJSONArray("items") ?: JSONArray()
                if (items.length() > MAX_SNAPSHOT_ITEMS) return
                for (i in 0 until items.length()) applyRecord(items.getJSONObject(i))
                val members = msg.optJSONArray("members")
                if (members != null) {
                    val cap = minOf(members.length(), MAX_SNAPSHOT_ITEMS)
                    for (i in 0 until cap) {
                        applyPresence(members.getJSONObject(i))
                    }
                }
            }
            "put" -> applyRecord(msg)
            "del" -> applyDelete(msg)
            "loc" -> applyPresence(msg)
            "leave" -> {
                val peerId = msg.optString("clientId").ifEmpty { return }
                _peers.value = _peers.value - peerId
            }
        }
    }

    private fun handleMessageV3(msg: JSONObject, frameBytes: Int) {
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
                val replay = replayState ?: return failSnapshot()
                if (replay.lastSnapshotSeq >= 0 && seq < replay.lastSnapshotSeq) {
                    android.util.Log.w("SyncManager", "Sync relay supplied an older snapshot fence")
                    _remoteUpdates.tryEmit("Sync rollback warning: relay snapshot is older than previously seen state")
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
            }
            "hello-ack" -> applyHelloAckV3(msg)
            // Active peer hello/presence frames follow snapshot-end. Accept
            // authenticated inbound traffic while our own hello ack is pending,
            // but keep local observers/presence outbound gated until CONNECTED.
            "hello" -> if (acceptsLiveInboundV3()) applyHelloV3(msg)
            "put" -> if (acceptsLiveInboundV3()) applyLiveRecordV3(msg)
            "del" -> if (acceptsLiveInboundV3()) applyLiveRecordV3(msg)
            "loc" -> if (acceptsLiveInboundV3()) applyPresenceV3(msg)
            "leave" -> {
                val peerId = msg.optString("by").ifEmpty { return }
                _peers.value = _peers.value - peerId
            }
        }
    }

    private fun applyRecord(rec: JSONObject) {
        // Snapshot tombstones arrive as records with deleted=true; route them
        // through the same signed-delete verification as a live "del".
        if (rec.optBoolean("deleted", false)) { applyDelete(rec); return }
        val id = rec.optString("id").ifEmpty { return }
        val v = strictVersion(rec, "v") ?: return
        // Monotonic per-id version: reject anything <= the highest we've applied,
        // and keep rejecting even after a delete (versions[id] survives as a
        // tombstone) so a relay can't resurrect a deleted object by replaying an
        // older-but-validly-signed put.
        val known = versions[id]
        if (known != null && known >= v) return // stale / superseded / post-delete
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
        // advance clock only AFTER AEAD + sig pass — a hostile relay can't
        // poison the clock with a forged message it can't sign
        clock = maxOf(clock, v)

        val doc = drawingStore.document.value
        val fallback = doc.layers.firstOrNull()?.id ?: DrawingDocument.DEFAULT_LAYER_ID
        val parsed = runCatching {
            GeoJsonImporter.parse(content, existingLayers = doc.layers, fallbackLayerId = fallback)
        }.getOrNull() ?: return

        parsed.newLayers.forEach { layer ->
            if (doc.layers.none { it.id == layer.id }) drawingStore.addLayerVerbatim(layer, ModelMutationOrigin.REMOTE_SYNC)
        }
        parsed.waypoints.forEach { upsertWaypoint(it) }
        parsed.drawings.forEach { upsertDrawing(it) }

        versions[id] = v
        // re-export so echo guard matches what our next diff will see
        // (import -> export must be a fixed point)
        kindById[id] = if (parsed.waypoints.isNotEmpty()) "waypoint" else "drawing"
        lastContent[id] = reexport(id)

        // notify UI about the remote change (conflict notification)
        val objectName = parsed.waypoints.firstOrNull()?.name
            ?: parsed.drawings.firstOrNull()?.name
            ?: "Object"
        val kindLabel = if (parsed.waypoints.isNotEmpty()) "Waypoint" else "Drawing"
        _remoteUpdates.tryEmit("$kindLabel '$objectName' updated by another device")
    }

    private fun applyDelete(rec: JSONObject) {
        val id = rec.optString("id").ifEmpty { return }
        val v = strictVersion(rec, "v") ?: return
        val known = versions[id]
        if (known != null && known >= v) return // stale / already-superseded delete
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
        clock = maxOf(clock, v)
        val kindLabel = when (kindById[id]) {
            "drawing" -> "Drawing"
            "waypoint" -> "Waypoint"
            else -> "Object"
        }
        when (kindById[id]) {
            "drawing" -> drawingStore.removeFeature(id, ModelMutationOrigin.REMOTE_SYNC)
            "waypoint" -> waypointStore.waypoints.value.firstOrNull { it.id == id }
                ?.let { waypointStore.remove(it, ModelMutationOrigin.REMOTE_SYNC) }
            else -> {
                drawingStore.removeFeature(id, ModelMutationOrigin.REMOTE_SYNC)
                waypointStore.waypoints.value.firstOrNull { it.id == id }
                    ?.let { waypointStore.remove(it, ModelMutationOrigin.REMOTE_SYNC) }
            }
        }
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
        _status.value = Status.CONNECTED
        replayState?.recoverableLocalDeletes(actor, myPublicKey)?.forEach { (wireId, stamp) -> sendDelV3(wireId, stamp) }
        syncLocalState(waypointStore.waypoints.value, drawingStore.document.value)
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
        activeSessions[by] = pub to sd
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
        val doc = drawingStore.document.value
        val fallback = doc.layers.firstOrNull()?.id ?: DrawingDocument.DEFAULT_LAYER_ID
        val parsed = runCatching {
            GeoJsonImporter.parse(content, existingLayers = doc.layers, fallbackLayerId = fallback)
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
            GeoJsonExporter.export(listOf(it), emptyList(), layers)
        } ?: parsed.drawings.singleOrNull { it.id == localId }?.let {
            GeoJsonExporter.export(emptyList(), listOf(it), layers)
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
            kindById[localId] = if (waypointStore.waypoints.value.any { it.id == localId }) "waypoint" else "drawing"
        }
        forcedLocalDiff.remove(localId)
    }

    private fun applyValidatedV3(validated: ValidatedV3) {
        when (validated) {
            is ValidatedV3.Put -> {
                val doc = drawingStore.document.value
                validated.parsed.newLayers.forEach { layer ->
                    if (doc.layers.none { it.id == layer.id }) {
                        drawingStore.addLayerVerbatim(layer, ModelMutationOrigin.REMOTE_SYNC)
                    }
                }
                validated.parsed.waypoints.forEach { upsertWaypoint(it) }
                validated.parsed.drawings.forEach { upsertDrawing(it) }
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
                when (kindById[sourceId]) {
                    "drawing" -> drawingStore.removeFeature(sourceId, ModelMutationOrigin.REMOTE_SYNC)
                    "waypoint" -> waypointStore.waypoints.value.firstOrNull { it.id == sourceId }
                        ?.let { waypointStore.remove(it, ModelMutationOrigin.REMOTE_SYNC) }
                    else -> {
                        drawingStore.removeFeature(sourceId, ModelMutationOrigin.REMOTE_SYNC)
                        waypointStore.waypoints.value.firstOrNull { it.id == sourceId }
                            ?.let { waypointStore.remove(it, ModelMutationOrigin.REMOTE_SYNC) }
                    }
                }
                lastContent.remove(sourceId); kindById.remove(sourceId)
                _remoteUpdates.tryEmit("$kindLabel deleted by another device")
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

        if (!payload.isValid()) return
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
            ts = System.currentTimeMillis()
        )
        _peers.value = _peers.value + (by to peer)
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
        for (wp in waypointStore.waypoints.value) {
            val computed = SyncIdentity.wireObjectId(keys.metadataKey, SyncIdentity.uuidToBytes(wp.id))
            if (computed == wireId) return wp.id
        }
        for (f in drawingStore.document.value.features) {
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

    private fun upsertWaypoint(wp: Waypoint) {
        if (waypointStore.waypoints.value.any { it.id == wp.id }) {
            waypointStore.updateNoUndo(wp, ModelMutationOrigin.REMOTE_SYNC)
        } else waypointStore.add(wp, ModelMutationOrigin.REMOTE_SYNC)
    }

    private fun upsertDrawing(f: DrawingFeature) {
        if (drawingStore.document.value.features.any { it.id == f.id }) {
            drawingStore.updateFeatureNoUndo(f, ModelMutationOrigin.REMOTE_SYNC)
        } else drawingStore.addFeature(f, ModelMutationOrigin.REMOTE_SYNC)
    }

    /** Re-serialise now-local object so next diff doesn't see a spurious change. */
    private fun reexport(id: String): String {
        val doc = drawingStore.document.value
        waypointStore.waypoints.value.firstOrNull { it.id == id }
            ?.let { return GeoJsonExporter.export(listOf(it), emptyList(), doc.layers) }
        doc.features.firstOrNull { it.id == id }
            ?.let { return GeoJsonExporter.export(emptyList(), listOf(it), doc.layers) }
        return ""
    }

    // ----- Presence broadcasting -----

    private fun startPresenceBroadcast() {
        presenceJob = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(5_000)
                if (_status.value == Status.CONNECTED && presenceConfig.shareLocation) {
                    sendPresence()
                }
            }
        }
    }

    private fun sendPresence() {
        if (protocolVersion == 3) { sendPresenceV3(); return }
        val loc = locationProvider?.invoke() ?: return
        val key = roomKey ?: return
        val cfg = presenceConfig
        val callsign = boundCallsign(cfg.callsign)
        val ts = System.currentTimeMillis()
        val lat = loc.latitude
        val lon = loc.longitude
        val heading = if (loc.hasBearing()) loc.bearing.toDouble() else 0.0
        val speed = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0
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
        sendFrame(JSONObject().apply {
            put("t", "loc")
            put("clientId", clientId)
            put("ct", ct)
        }.toString())
    }

    private fun sendPresenceV3() {
        if (_status.value != Status.CONNECTED) return
        val loc = locationProvider?.invoke() ?: return
        val key = roomKey ?: return
        val keys = v3Keys ?: return
        val actor = myActorId ?: return
        val sd = sessionDomain ?: return
        val cfg = presenceConfig
        val callsign = boundCallsign(cfg.callsign)
        val lat = loc.latitude
        val lon = loc.longitude
        val heading = if (loc.hasBearing()) loc.bearing.toDouble() else 0.0
        val speed = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0
        if (presenceCounter >= VersionStamp.MAX_COUNTER) return failConnection()
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
        val aad = SyncCrypto.aadPresenceV3(actor, vs.encode())
        val ct = SyncCrypto.encodeBase64(
            SyncCrypto.seal(key, envelope.toString().toByteArray(Charsets.UTF_8), aad)
        )
        sendFrame(JSONObject().apply {
            put("t", "loc"); put("by", actor); put("ct", ct)
            put("pub", myPublicKey); put("sd", SyncIdentity.urlB64(sd)); put("vs", vs.encode())
        }.toString())
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
        val lat = obj.optDouble("lat", 0.0)
        val lon = obj.optDouble("lon", 0.0)
        val heading = obj.optDouble("heading", 0.0)
        val speed = obj.optDouble("speed", 0.0)
        val ts = obj.optLong("ts", 0L)
        if (callsign.codePointCount(0, callsign.length) > 64) return

        // Per-device auth: pin the peer's key on first sight (TOFU), then require
        // every later presence to be signed by that same key. A room member
        // cannot impersonate an established peer; a changed key is rejected as a
        // possible swap; a relay replaying an old (signed) blob is caught by ts.
        val pub = obj.optString("pub").ifEmpty { return }
        val sig = obj.optString("sig").ifEmpty { return }
        val pinned = peerKeys[peerId]
        if (pinned == null) peerKeys[peerId] = pub else if (pinned != pub) return
        val signed = SyncSigning.presenceMessage(
            peerId, ts, lat, lon, heading, speed, callsign, affiliation, echelon, function, isHQ)
        if (!SyncSigning.verify(pub, signed, sig)) return
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
        stalenessSweepJob = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(30_000)
                val cutoff = System.currentTimeMillis() - 45_000
                val current = _peers.value
                val fresh = current.filterValues { it.receivedAt > cutoff }
                if (fresh.size != current.size) {
                    _peers.value = fresh
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

    private fun savePresenceConfig() {
        val cfg = presenceConfig
        val jsonStr = JSONObject()
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
        }.getOrNull() ?: return  // key locked/unavailable: keep what's on disk, don't wipe legacy
        prefs.edit()
            .putString(KEY_PRESENCE, sealed)
            .remove("callsign").remove("shareLocation").remove("affiliation")
            .remove("echelon").remove("function").remove("isHQ")
            .apply()
    }

    private fun loadPresenceConfig() {
        // Setting presenceConfig triggers savePresenceConfig() (its setter),
        // which seals it and wipes any legacy plaintext - so a legacy install
        // self-migrates to sealed on first launch.
        presenceConfig = readSealedPresenceConfig() ?: readLegacyPresenceConfig()
    }

    private fun readSealedPresenceConfig(): PresenceConfig? {
        val stored = prefs.getString(KEY_PRESENCE, null) ?: return null
        val obj = runCatching {
            val blob = Base64.decode(stored, Base64.NO_WRAP)
            SealedEnvelope.openFile(SafeStore.keyProvider.key(), blob, PRESENCE_LABEL)
                ?.let { JSONObject(String(it, Charsets.UTF_8)) }
        }.getOrNull() ?: return null
        return PresenceConfig(
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
            if (!prefs.edit().putString(KEY_DEVICE_SEED, sealed).commit()) {
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

    internal fun boundCallsign(value: String): String {
        val count = value.codePointCount(0, value.length)
        return if (count <= 64) value else value.substring(0, value.offsetByCodePoints(0, 64))
    }

    private fun failSnapshot() {
        awaitingHelloAck = false
        resetSnapshot()
        _remoteUpdates.tryEmit("Sync snapshot authentication failed")
        failConnection()
    }

    private fun failConnection() {
        awaitingHelloAck = false
        _status.value = Status.OFFLINE
        ws?.cancel()
    }

    private fun persistenceFailure() {
        awaitingHelloAck = false
        wantConnected = false
        _status.value = Status.OFFLINE
        _remoteUpdates.tryEmit("Sync stopped because rollback state could not be secured")
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
