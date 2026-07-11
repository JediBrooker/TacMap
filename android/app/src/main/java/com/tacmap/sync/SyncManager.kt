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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
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
    private val waypointStore: WaypointStore,
    private val drawingStore: DrawingStore,
    private val scope: CoroutineScope,
    context: Context,
) {
    enum class Status { OFFLINE, CONNECTING, CONNECTED }

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
    private var observeJob: Job? = null

    // Per-device Ed25519 signing identity. Seed is sealed at rest; the public
    // key rides every presence AND every object write so peers pin it (TOFU) and
    // reject a room member impersonating an established device. One identity per
    // clientId, shared by presence + object writes. Room state cleared on leave.
    private val deviceSeed: ByteArray by lazy { loadOrCreateDeviceSeed() }
    private val myPublicKey: String by lazy { SyncSigning.publicKey(deviceSeed) }
    private val myPublicKeyRaw: ByteArray by lazy {
        SyncIdentity.urlB64Decode(myPublicKey)
    }
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

    private var presenceJob: Job? = null
    private var stalenessSweepJob: Job? = null

    init {
        loadPresenceConfig()
    }

    // ----- Public API -----

    fun join(joinCode: String) {
        val code = joinCode.trim()
        if (code.isEmpty()) return
        leave()
        wantConnected = true
        relayBase = OpsecSettings.shared?.relayUrl?.value?.takeIf { it.isNotBlank() } ?: RELAY_BASE

        if (code.startsWith("3:")) {
            // v3 protocol: strip prefix, derive v3 keys, setup identity
            protocolVersion = 3
            val rawCode = code.removePrefix("3:")
            val keys = SyncCrypto.deriveRoomV3(rawCode)
            v3Keys = keys
            roomKey = keys.roomKey
            authToken = keys.authToken
            myActorId = SyncIdentity.actorId(keys.roomIdRaw, myPublicKeyRaw)
            sessionDomain = SyncIdentity.generateSessionDomain()
            val replay = SyncReplayState(keys.roomId, appFilesDir)
            replay.load()
            replayState = replay
            _room.value = code
            connect(keys.roomId)
        } else {
            // v2 protocol: unchanged
            protocolVersion = 2
            val keys = SyncCrypto.deriveRoom(code)
            roomKey = keys.roomKey
            authToken = keys.authToken
            _room.value = code
            connect(keys.roomId)
        }
        startObserving()
        startPresenceBroadcast()
        startStalenessSweep()
    }

    fun leave() {
        wantConnected = false
        observeJob?.cancel(); observeJob = null
        presenceJob?.cancel(); presenceJob = null
        stalenessSweepJob?.cancel(); stalenessSweepJob = null
        ws?.close(1000, "leave"); ws = null
        // v3: persist replay state before clearing transport (survives leave/restart)
        replayState?.save()
        roomKey = null
        authToken = null
        _room.value = null
        _status.value = Status.OFFLINE
        _peers.value = emptyMap()
        versions.clear(); lastContent.clear(); kindById.clear()
        peerKeys.clear(); peerTs.clear()
        // v3 state: clear transport-session fields but NOT replayState (durable)
        v3Keys = null
        myActorId = null
        sessionDomain = null
        replayState = null
        protocolVersion = 2
    }

    // ----- Connection -----

    private fun connect(roomId: String) {
        _status.value = Status.CONNECTING
        val path = if (protocolVersion == 3) "v3/room/" else "room/"
        val base = if (relayBase.endsWith("/")) relayBase.dropLast(1) else relayBase
        val url = "$base/$path$roomId"
        val builder = Request.Builder().url(url)
        authToken?.let { builder.header("Authorization", "Bearer $it") }
        if (protocolVersion == 3) {
            builder.header("X-Protocol", "3")
            builder.header("X-Room-Id", roomId)
        }
        ws = http.newWebSocket(builder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _status.value = Status.CONNECTED
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch { handleMessage(text) }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _status.value = Status.OFFLINE
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _status.value = Status.OFFLINE
                if (wantConnected) {
                    scope.launch {
                        kotlinx.coroutines.delay(3000)
                        if (wantConnected) connect(roomId)
                    }
                }
            }
        })
    }

    // ----- Outbound: observe local stores, diff, send -----

    private fun startObserving() {
        observeJob = scope.launch {
            combine(waypointStore.waypoints, drawingStore.document) { wps, doc -> wps to doc }
                .debounce(250)
                .collect { (wps, doc) -> syncLocalState(wps, doc) }
        }
    }

    private fun syncLocalState(wps: List<Waypoint>, doc: DrawingDocument) {
        if (_status.value != Status.CONNECTED) return
        val current = HashMap<String, Pair<String, String>>() // id -> (kind, content)
        for (wp in wps) current[wp.id] = "waypoint" to GeoJsonExporter.export(listOf(wp), emptyList(), doc.layers)
        for (f in doc.features) current[f.id] = "drawing" to GeoJsonExporter.export(emptyList(), listOf(f), doc.layers)

        if (protocolVersion == 3) {
            syncLocalStateV3(current)
        } else {
            syncLocalStateV2(current)
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

        for ((id, kc) in current) {
            val (kind, content) = kc
            if (lastContent[id] == content) continue
            val wireId = SyncIdentity.wireObjectId(keys.metadataKey, SyncIdentity.uuidToBytes(id))
            val counter = replay.nextCounter()
            val vs = VersionStamp(counter, actor)
            replay.save()
            lastContent[id] = content
            kindById[id] = kind
            sendPutV3(wireId, vs, kind, content)
        }
        val gone = lastContent.keys.filter { it !in current }
        for (id in gone) {
            val wireId = SyncIdentity.wireObjectId(keys.metadataKey, SyncIdentity.uuidToBytes(id))
            val counter = replay.nextCounter()
            val vs = VersionStamp(counter, actor)
            replay.save()
            sendDelV3(wireId, vs)
            lastContent.remove(id); kindById.remove(id)
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
        ws?.send(JSONObject().apply {
            put("t", "put"); put("id", id); put("v", v); put("by", clientId); put("kind", kind); put("ct", ct)
        }.toString())
    }

    private fun sendDel(id: String, v: Long) {
        val key = roomKey ?: return
        val sig = SyncSigning.sign(deviceSeed, SyncSigning.objectMessage(id, v, "del", clientId, ""))
        val inner = JSONObject().apply { put("pub", myPublicKey); put("sig", sig) }
        val aad = SyncCrypto.aad(id, v, "del")
        val ct = SyncCrypto.encodeBase64(SyncCrypto.seal(key, inner.toString().toByteArray(Charsets.UTF_8), aad))
        ws?.send(JSONObject().apply {
            put("t", "del"); put("id", id); put("v", v); put("by", clientId); put("ct", ct)
        }.toString())
    }

    // -- v3 outbound --

    private fun sendHelloV3() {
        val actor = myActorId ?: return
        val sd = sessionDomain ?: return
        ws?.send(JSONObject().apply {
            put("t", "hello")
            put("by", actor)
            put("pub", myPublicKey)
            put("sd", SyncIdentity.urlB64(sd))
        }.toString())
    }

    private fun sendPutV3(wireId: String, vs: VersionStamp, kind: String, content: String) {
        val key = roomKey ?: return
        val keys = v3Keys ?: return
        val actor = myActorId ?: return
        val sd = sessionDomain ?: return
        val payloadHash = SyncIdentity.sha256(content.toByteArray(Charsets.UTF_8))
        val preimage = SyncIdentity.buildPreimage(
            SyncIdentity.DOMAIN_PUT, keys.roomIdRaw, actor, sd,
            VersionStamp.counterHex16(vs.counter), wireId, kind, payloadHash)
        val sig = SyncSigning.sign(deviceSeed, preimage)
        val inner = JSONObject().apply { put("c", content); put("sig", sig) }
        val aad = SyncCrypto.aadV3(wireId, vs.encode(), kind)
        val ct = SyncCrypto.encodeBase64(SyncCrypto.seal(key, inner.toString().toByteArray(Charsets.UTF_8), aad))
        ws?.send(JSONObject().apply {
            put("t", "put"); put("id", wireId); put("vs", vs.encode())
            put("by", actor); put("kind", kind); put("ct", ct); put("pub", myPublicKey)
        }.toString())
    }

    private fun sendDelV3(wireId: String, vs: VersionStamp) {
        val key = roomKey ?: return
        val keys = v3Keys ?: return
        val actor = myActorId ?: return
        val sd = sessionDomain ?: return
        val payloadHash = SyncIdentity.sha256(ByteArray(0))
        val preimage = SyncIdentity.buildPreimage(
            SyncIdentity.DOMAIN_DELETE, keys.roomIdRaw, actor, sd,
            VersionStamp.counterHex16(vs.counter), wireId, "del", payloadHash)
        val sig = SyncSigning.sign(deviceSeed, preimage)
        val inner = JSONObject().apply { put("sig", sig) }
        val aad = SyncCrypto.aadV3(wireId, vs.encode(), "del")
        val ct = SyncCrypto.encodeBase64(SyncCrypto.seal(key, inner.toString().toByteArray(Charsets.UTF_8), aad))
        ws?.send(JSONObject().apply {
            put("t", "del"); put("id", wireId); put("vs", vs.encode())
            put("by", actor); put("kind", "del"); put("ct", ct); put("pub", myPublicKey)
        }.toString())
    }

    // ----- Inbound -----

    companion object {
        /** Default E2E-blind relay base URL (path added per-protocol-version). */
        const val RELAY_BASE = "wss://tacmap-sync.christianbrooker.workers.dev"
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
        private const val MAX_VERSION = 1_000_000_000_000L   // matches relay MAX_V
    }

    private fun handleMessage(text: String) {
        if (text.length > MAX_FRAME_BYTES) return
        try {
            val msg = JSONObject(text)
            if (protocolVersion == 3) {
                handleMessageV3(msg)
            } else {
                handleMessageV2(msg)
            }
        } catch (_: Throwable) {
            // silently drop -- don't log frame content (SEC-019)
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

    private fun handleMessageV3(msg: JSONObject) {
        when (msg.optString("t")) {
            "snapshot-begin" -> {
                val seq = msg.optLong("seq", -1L)
                val replay = replayState ?: return
                if (replay.lastSnapshotSeq >= 0 && seq < replay.lastSnapshotSeq) {
                    // relay is serving stale state -- log warning but still apply
                    android.util.Log.w("SyncManager", "Snapshot gap: relay seq=$seq < last=${replay.lastSnapshotSeq}")
                }
            }
            "snapshot" -> {
                val items = msg.optJSONArray("items") ?: JSONArray()
                if (items.length() > MAX_SNAPSHOT_ITEMS) return
                for (i in 0 until items.length()) applyRecordV3(items.getJSONObject(i))
            }
            "snapshot-end" -> {
                val seq = msg.optLong("seq", -1L)
                if (seq >= 0) replayState?.updateSnapshotSeq(seq)
                replayState?.save()
                // send hello after snapshot to register our identity
                sendHelloV3()
            }
            "hello" -> applyHelloV3(msg)
            "put" -> applyRecordV3(msg)
            "del" -> applyDeleteV3(msg)
            "loc" -> applyPresenceV3(msg)
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
            if (doc.layers.none { it.id == layer.id }) drawingStore.addLayerVerbatim(layer)
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
            "drawing" -> drawingStore.removeFeature(id)
            "waypoint" -> waypointStore.waypoints.value.firstOrNull { it.id == id }?.let { waypointStore.remove(it) }
            else -> {
                drawingStore.removeFeature(id)
                waypointStore.waypoints.value.firstOrNull { it.id == id }?.let { waypointStore.remove(it) }
            }
        }
        versions[id] = v
        lastContent.remove(id); kindById.remove(id)
        _remoteUpdates.tryEmit("$kindLabel deleted by another device")
    }

    // -- v3 inbound handlers --

    private fun applyHelloV3(msg: JSONObject) {
        val by = msg.optString("by").ifEmpty { return }
        val pub = msg.optString("pub").ifEmpty { return }
        val sd = msg.optString("sd").ifEmpty { return }
        if (by == myActorId) return
        val replay = replayState ?: return
        if (!replay.registerActor(by, pub)) return  // key swap -> reject
        replay.updateSessionDomain(by, sd)
        replay.save()
    }

    private fun applyRecordV3(rec: JSONObject) {
        if (rec.optBoolean("deleted", false)) { applyDeleteV3(rec); return }
        val wireId = rec.optString("id").ifEmpty { return }
        val vsStr = rec.optString("vs").ifEmpty { return }
        val vs = VersionStamp.parse(vsStr) ?: return
        val by = rec.optString("by").ifEmpty { return }
        val pub = rec.optString("pub").ifEmpty { return }
        if (by == myActorId) return  // echo
        val key = roomKey ?: return
        val keys = v3Keys ?: return
        val replay = replayState ?: return
        val kind = rec.optString("kind", "unknown")
        val ctB64 = rec.optString("ct")
        if (ctB64.isEmpty() || ctB64.length > MAX_BASE64_BYTES) return
        // actor registration check
        if (!replay.registerActor(by, pub)) return
        // replay protection: stamp must be newer than anything we've seen
        if (!replay.advance(wireId, vs)) return
        // AEAD open
        val aad = SyncCrypto.aadV3(wireId, vsStr, kind)
        val plain = SyncCrypto.open(key, SyncCrypto.decodeBase64(ctB64), aad) ?: return
        val inner = runCatching { JSONObject(String(plain, Charsets.UTF_8)) }.getOrNull() ?: return
        val content = inner.optString("c")
        val sig = inner.optString("sig").ifEmpty { return }
        // verify Ed25519 signature over the binary preimage
        val payloadHash = SyncIdentity.sha256(content.toByteArray(Charsets.UTF_8))
        val peerSd = SyncIdentity.urlB64Decode(replay.getSessionDomain(by) ?: return)
        val preimage = SyncIdentity.buildPreimage(
            SyncIdentity.DOMAIN_PUT, keys.roomIdRaw, by, peerSd,
            VersionStamp.counterHex16(vs.counter), wireId, kind, payloadHash)
        if (!SyncSigning.verify(pub, preimage, sig)) return
        replay.save()

        val doc = drawingStore.document.value
        val fallback = doc.layers.firstOrNull()?.id ?: DrawingDocument.DEFAULT_LAYER_ID
        val parsed = runCatching {
            GeoJsonImporter.parse(content, existingLayers = doc.layers, fallbackLayerId = fallback)
        }.getOrNull() ?: return

        parsed.newLayers.forEach { layer ->
            if (doc.layers.none { it.id == layer.id }) drawingStore.addLayerVerbatim(layer)
        }
        parsed.waypoints.forEach { upsertWaypoint(it) }
        parsed.drawings.forEach { upsertDrawing(it) }

        // echo guard: track the last content we received so local diff doesn't bounce it
        val sourceId = findLocalIdForWireId(wireId)
        if (sourceId != null) {
            kindById[sourceId] = if (parsed.waypoints.isNotEmpty()) "waypoint" else "drawing"
            lastContent[sourceId] = reexport(sourceId)
        }

        val objectName = parsed.waypoints.firstOrNull()?.name
            ?: parsed.drawings.firstOrNull()?.name ?: "Object"
        val kindLabel = if (parsed.waypoints.isNotEmpty()) "Waypoint" else "Drawing"
        _remoteUpdates.tryEmit("$kindLabel '$objectName' updated by another device")
    }

    private fun applyDeleteV3(rec: JSONObject) {
        val wireId = rec.optString("id").ifEmpty { return }
        val vsStr = rec.optString("vs").ifEmpty { return }
        val vs = VersionStamp.parse(vsStr) ?: return
        val by = rec.optString("by").ifEmpty { return }
        val pub = rec.optString("pub").ifEmpty { return }
        if (by == myActorId) return
        val key = roomKey ?: return
        val keys = v3Keys ?: return
        val replay = replayState ?: return
        val ctB64 = rec.optString("ct")
        if (ctB64.isEmpty() || ctB64.length > MAX_BASE64_BYTES) return
        if (!replay.registerActor(by, pub)) return
        if (!replay.tombstone(wireId, vs)) return
        val aad = SyncCrypto.aadV3(wireId, vsStr, "del")
        val plain = SyncCrypto.open(key, SyncCrypto.decodeBase64(ctB64), aad) ?: return
        val inner = runCatching { JSONObject(String(plain, Charsets.UTF_8)) }.getOrNull() ?: return
        val sig = inner.optString("sig").ifEmpty { return }
        val payloadHash = SyncIdentity.sha256(ByteArray(0))
        val peerSd = SyncIdentity.urlB64Decode(replay.getSessionDomain(by) ?: return)
        val preimage = SyncIdentity.buildPreimage(
            SyncIdentity.DOMAIN_DELETE, keys.roomIdRaw, by, peerSd,
            VersionStamp.counterHex16(vs.counter), wireId, "del", payloadHash)
        if (!SyncSigning.verify(pub, preimage, sig)) return
        replay.save()

        val sourceId = findLocalIdForWireId(wireId)
        if (sourceId != null) {
            val kindLabel = when (kindById[sourceId]) {
                "drawing" -> "Drawing"
                "waypoint" -> "Waypoint"
                else -> "Object"
            }
            when (kindById[sourceId]) {
                "drawing" -> drawingStore.removeFeature(sourceId)
                "waypoint" -> waypointStore.waypoints.value.firstOrNull { it.id == sourceId }
                    ?.let { waypointStore.remove(it) }
                else -> {
                    drawingStore.removeFeature(sourceId)
                    waypointStore.waypoints.value.firstOrNull { it.id == sourceId }
                        ?.let { waypointStore.remove(it) }
                }
            }
            lastContent.remove(sourceId); kindById.remove(sourceId)
            _remoteUpdates.tryEmit("$kindLabel deleted by another device")
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
        val ctB64 = msg.optString("ct").ifEmpty { return }
        if (ctB64.length > MAX_BASE64_BYTES) return
        if (!replay.registerActor(by, pub)) return
        if (!replay.advancePresence(by, vs.counter)) return
        val aad = SyncCrypto.aadPresenceV3(by, vsStr)
        val plain = SyncCrypto.open(key, SyncCrypto.decodeBase64(ctB64), aad) ?: return
        val obj = runCatching { JSONObject(String(plain, Charsets.UTF_8)) }.getOrNull() ?: return
        val sig = obj.optString("sig").ifEmpty { return }
        val payloadBytes = buildPresencePayloadBytes(obj)
        val payloadHash = SyncIdentity.sha256(payloadBytes)
        val peerSd = SyncIdentity.urlB64Decode(replay.getSessionDomain(by) ?: return)
        val preimage = SyncIdentity.buildPreimage(
            SyncIdentity.DOMAIN_PRESENCE, keys.roomIdRaw, by, peerSd,
            VersionStamp.counterHex16(vs.counter), "", "loc", payloadHash)
        if (!SyncSigning.verify(pub, preimage, sig)) return
        replay.save()

        val callsign = obj.optString("callsign", "")
        val affiliation = obj.optString("affiliation", "UNKNOWN")
        val echelon = obj.optString("echelon", "TEAM")
        val function = obj.optString("function", "INFANTRY")
        val isHQ = obj.optBoolean("isHQ", false)
        val lat = obj.optDouble("lat", 0.0)
        val lon = obj.optDouble("lon", 0.0)
        val heading = obj.optDouble("heading", 0.0)
        val speed = obj.optDouble("speed", 0.0)
        val peer = PresencePeer(
            clientId = by,
            callsign = callsign,
            affiliation = affiliation,
            echelon = echelon,
            function = function,
            isHQ = isHQ,
            lat = lat, lon = lon, heading = heading, speed = speed,
            ts = System.currentTimeMillis()
        )
        _peers.value = _peers.value + (by to peer)
    }

    private fun buildPresencePayloadBytes(obj: JSONObject): ByteArray {
        // canonical JSON matching what the sender produces for hashing
        val canonical = JSONObject()
        canonical.put("lat", obj.optDouble("lat", 0.0))
        canonical.put("lon", obj.optDouble("lon", 0.0))
        canonical.put("heading", obj.optDouble("heading", 0.0))
        canonical.put("speed", obj.optDouble("speed", 0.0))
        canonical.put("callsign", obj.optString("callsign", ""))
        canonical.put("affiliation", obj.optString("affiliation", "UNKNOWN"))
        canonical.put("echelon", obj.optString("echelon", "TEAM"))
        canonical.put("function", obj.optString("function", "INFANTRY"))
        canonical.put("isHQ", obj.optBoolean("isHQ", false))
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
        if (waypointStore.waypoints.value.any { it.id == wp.id }) waypointStore.updateNoUndo(wp)
        else waypointStore.add(wp)
    }

    private fun upsertDrawing(f: DrawingFeature) {
        if (drawingStore.document.value.features.any { it.id == f.id }) drawingStore.updateFeatureNoUndo(f)
        else drawingStore.addFeature(f)
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
        if (cfg.callsign.isBlank()) return
        val ts = System.currentTimeMillis()
        val lat = loc.latitude
        val lon = loc.longitude
        val heading = if (loc.hasBearing()) loc.bearing.toDouble() else 0.0
        val speed = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0
        val sig = SyncSigning.sign(deviceSeed, SyncSigning.presenceMessage(
            clientId, ts, lat, lon, heading, speed,
            cfg.callsign, cfg.affiliation.name, cfg.echelon.name, cfg.function.name, cfg.isHQ))
        val payload = JSONObject().apply {
            put("callsign", cfg.callsign)
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
        ws?.send(JSONObject().apply {
            put("t", "loc")
            put("clientId", clientId)
            put("ct", ct)
        }.toString())
    }

    private fun sendPresenceV3() {
        val loc = locationProvider?.invoke() ?: return
        val key = roomKey ?: return
        val keys = v3Keys ?: return
        val actor = myActorId ?: return
        val sd = sessionDomain ?: return
        val replay = replayState ?: return
        val cfg = presenceConfig
        if (cfg.callsign.isBlank()) return
        val lat = loc.latitude
        val lon = loc.longitude
        val heading = if (loc.hasBearing()) loc.bearing.toDouble() else 0.0
        val speed = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0
        val counter = replay.nextCounter()
        val vs = VersionStamp(counter, actor)
        replay.save()
        // canonical presence payload (key order matters for hash)
        val payload = JSONObject()
        payload.put("lat", lat)
        payload.put("lon", lon)
        payload.put("heading", heading)
        payload.put("speed", speed)
        payload.put("callsign", cfg.callsign)
        payload.put("affiliation", cfg.affiliation.name)
        payload.put("echelon", cfg.echelon.name)
        payload.put("function", cfg.function.name)
        payload.put("isHQ", cfg.isHQ)
        val payloadHash = SyncIdentity.sha256(payload.toString().toByteArray(Charsets.UTF_8))
        val preimage = SyncIdentity.buildPreimage(
            SyncIdentity.DOMAIN_PRESENCE, keys.roomIdRaw, actor, sd,
            VersionStamp.counterHex16(vs.counter), "", "loc", payloadHash)
        val sig = SyncSigning.sign(deviceSeed, preimage)
        payload.put("sig", sig)
        val aad = SyncCrypto.aadPresenceV3(actor, vs.encode())
        val ct = SyncCrypto.encodeBase64(SyncCrypto.seal(key, payload.toString().toByteArray(Charsets.UTF_8), aad))
        ws?.send(JSONObject().apply {
            put("t", "loc"); put("by", actor); put("ct", ct)
            put("pub", myPublicKey); put("vs", vs.encode())
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

    /** The device's Ed25519 signing seed, sealed at rest. Generated once and
     *  reused so this device keeps a stable identity across sessions (peers pin
     *  it). If the seal can't be persisted (locked auth-bound key), a fresh seed
     *  is used for this session only. */
    private fun loadOrCreateDeviceSeed(): ByteArray {
        prefs.getString(KEY_DEVICE_SEED, null)?.let { stored ->
            runCatching {
                SealedEnvelope.openFile(
                    SafeStore.keyProvider.key(), Base64.decode(stored, Base64.NO_WRAP), DEVICE_SEED_LABEL)
            }.getOrNull()?.takeIf { it.size == 32 }?.let { return it }
        }
        val seed = SyncSigning.generateSeed()
        runCatching {
            val sealed = Base64.encodeToString(
                SealedEnvelope.sealFile(SafeStore.keyProvider.key(), seed, DEVICE_SEED_LABEL), Base64.NO_WRAP)
            prefs.edit().putString(KEY_DEVICE_SEED, sealed).apply()
        }
        return seed
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
