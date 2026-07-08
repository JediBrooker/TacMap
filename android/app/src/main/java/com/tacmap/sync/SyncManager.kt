package com.tacmap.sync

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
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Real-time shared-tactical-picture sync client. Connects to the E2E-blind relay
 * ([RELAY_BASE]) for a unit room derived from a join code, and keeps waypoints +
 * drawings in step across the unit's devices.
 *
 * Each object is serialised as a single-feature GeoJSON document (the same
 * cross-platform schema TacMap already round-trips), encrypted with the
 * room key ([SyncCrypto]) and relayed as opaque ciphertext — the server never
 * sees plaintext. Layers ride along in the feature properties, so they
 * reconstruct on the receiver without a separate channel.
 *
 * Merge is last-write-wins on a per-object Lamport version; echo is suppressed
 * by tracking the last serialised form we sent/received for each id so applying
 * a remote change doesn't bounce back out.
 *
 * NOTE: compile-verified; convergence / no-echo behaviour should be confirmed
 * on two devices against the live relay.
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
    /** Join code of the active room (for display), or null when not syncing. */
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

    /** Provide a location supplier so sendPresence can read the latest fix. */
    var locationProvider: (() -> Location?)? = null

    private val prefs = context.applicationContext.getSharedPreferences("sync", Context.MODE_PRIVATE)
    private val clientId: String = prefs.getString("clientId", null)
        ?: UUID.randomUUID().toString().also { prefs.edit().putString("clientId", it).apply() }

    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private var roomKey: ByteArray? = null
    private var wantConnected = false
    private var observeJob: Job? = null

    private var clock: Long = 0
    private val versions = HashMap<String, Long>()        // id -> last-applied version
    private val lastContent = HashMap<String, String>()   // id -> last serialised GeoJSON (echo guard)
    private val kindById = HashMap<String, String>()       // id -> "waypoint" | "drawing"

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
        roomKey = SyncCrypto.roomKey(code)
        _room.value = code
        connect(SyncCrypto.roomId(code))
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
        roomKey = null
        _room.value = null
        _status.value = Status.OFFLINE
        _peers.value = emptyMap()
        versions.clear(); lastContent.clear(); kindById.clear()
    }

    // ----- Connection -----

    private fun connect(roomId: String) {
        _status.value = Status.CONNECTING
        val request = Request.Builder().url(RELAY_BASE + roomId).build()
        ws = http.newWebSocket(request, object : WebSocketListener() {
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

        // New / changed → put.
        for ((id, kc) in current) {
            val (kind, content) = kc
            if (lastContent[id] == content) continue
            clock += 1
            versions[id] = clock
            lastContent[id] = content
            kindById[id] = kind
            sendPut(id, clock, kind, content)
        }
        // Removed → del.
        val gone = lastContent.keys.filter { it !in current }
        for (id in gone) {
            clock += 1
            sendDel(id, clock)
            lastContent.remove(id); versions.remove(id); kindById.remove(id)
        }
    }

    private fun sendPut(id: String, v: Long, kind: String, content: String) {
        val key = roomKey ?: return
        val aad = SyncCrypto.aad(id, v, kind)
        val ct = SyncCrypto.encodeBase64(SyncCrypto.seal(key, content.toByteArray(Charsets.UTF_8), aad))
        ws?.send(JSONObject().apply {
            put("t", "put"); put("id", id); put("v", v); put("by", clientId); put("kind", kind); put("ct", ct)
        }.toString())
    }

    private fun sendDel(id: String, v: Long) {
        ws?.send(JSONObject().apply {
            put("t", "del"); put("id", id); put("v", v); put("by", clientId)
        }.toString())
    }

    // ----- Inbound -----

    private fun handleMessage(text: String) {
        val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (msg.optString("t")) {
            "snapshot" -> {
                val items = msg.optJSONArray("items") ?: JSONArray()
                for (i in 0 until items.length()) applyRecord(items.getJSONObject(i))
                // Populate peers from the snapshot's members array (first chunk).
                val members = msg.optJSONArray("members")
                if (members != null) {
                    for (i in 0 until members.length()) {
                        applyPresence(members.getJSONObject(i))
                    }
                }
            }
            "put" -> applyRecord(msg)
            "del" -> applyDelete(msg.optString("id"), msg.optLong("v"))
            "loc" -> applyPresence(msg)
            "leave" -> {
                val peerId = msg.optString("clientId").ifEmpty { return }
                _peers.value = _peers.value - peerId
            }
        }
    }

    private fun applyRecord(rec: JSONObject) {
        val id = rec.optString("id").ifEmpty { return }
        val v = rec.optLong("v")
        if ((versions[id] ?: Long.MIN_VALUE) >= v && lastContent.containsKey(id)) return // stale
        clock = maxOf(clock, v)
        if (rec.optBoolean("deleted", false)) { applyDelete(id, v); return }
        val key = roomKey ?: return
        val kind = rec.optString("kind", "unknown")
        val aad = SyncCrypto.aad(id, v, kind)
        val plain = SyncCrypto.open(key, SyncCrypto.decodeBase64(rec.optString("ct")), aad) ?: return
        val content = String(plain, Charsets.UTF_8)

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
        // Re-export the applied object so the echo guard matches what our own
        // diff will compute next emission (import→export must be a fixed point).
        kindById[id] = if (parsed.waypoints.isNotEmpty()) "waypoint" else "drawing"
        lastContent[id] = reexport(id)

        // Notify the UI that a remote change was applied (conflict notification).
        val objectName = parsed.waypoints.firstOrNull()?.name
            ?: parsed.drawings.firstOrNull()?.name
            ?: "Object"
        val kindLabel = if (parsed.waypoints.isNotEmpty()) "Waypoint" else "Drawing"
        _remoteUpdates.tryEmit("$kindLabel '$objectName' updated by another device")
    }

    private fun applyDelete(id: String, v: Long) {
        if (id.isEmpty()) return
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

    private fun upsertWaypoint(wp: Waypoint) {
        if (waypointStore.waypoints.value.any { it.id == wp.id }) waypointStore.updateNoUndo(wp)
        else waypointStore.add(wp)
    }

    private fun upsertDrawing(f: DrawingFeature) {
        if (drawingStore.document.value.features.any { it.id == f.id }) drawingStore.updateFeatureNoUndo(f)
        else drawingStore.addFeature(f)
    }

    /** Re-serialise the now-local object so the next diff sees no spurious change. */
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
        val loc = locationProvider?.invoke() ?: return
        val key = roomKey ?: return
        val cfg = presenceConfig
        if (cfg.callsign.isBlank()) return
        val payload = JSONObject().apply {
            put("callsign", cfg.callsign)
            put("affiliation", cfg.affiliation.name)
            put("echelon", cfg.echelon.name)
            put("function", cfg.function.name)
            put("isHQ", cfg.isHQ)
            put("lat", loc.latitude)
            put("lon", loc.longitude)
            put("heading", if (loc.hasBearing()) loc.bearing.toDouble() else 0.0)
            put("speed", if (loc.hasSpeed()) loc.speed.toDouble() else 0.0)
            put("ts", System.currentTimeMillis())
        }
        val aad = "loc|$clientId".toByteArray(Charsets.UTF_8)
        val ct = SyncCrypto.encodeBase64(SyncCrypto.seal(key, payload.toString().toByteArray(Charsets.UTF_8), aad))
        ws?.send(JSONObject().apply {
            put("t", "loc")
            put("clientId", clientId)
            put("ct", ct)
        }.toString())
    }

    private fun applyPresence(msg: JSONObject) {
        val peerId = msg.optString("clientId").ifEmpty { return }
        if (peerId == clientId) return
        val key = roomKey ?: return
        val ctB64 = msg.optString("ct").ifEmpty { return }
        val aad = "loc|$peerId".toByteArray(Charsets.UTF_8)
        val plain = SyncCrypto.open(key, SyncCrypto.decodeBase64(ctB64), aad) ?: return
        val obj = runCatching { JSONObject(String(plain, Charsets.UTF_8)) }.getOrNull() ?: return
        val peer = PresencePeer(
            clientId = peerId,
            callsign = obj.optString("callsign", ""),
            affiliation = obj.optString("affiliation", "FRIEND"),
            echelon = obj.optString("echelon", "TEAM"),
            function = obj.optString("function", "INFANTRY"),
            isHQ = obj.optBoolean("isHQ", false),
            lat = obj.optDouble("lat", 0.0),
            lon = obj.optDouble("lon", 0.0),
            heading = obj.optDouble("heading", 0.0),
            speed = obj.optDouble("speed", 0.0),
            ts = obj.optLong("ts", 0L)
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

    private fun savePresenceConfig() {
        val cfg = presenceConfig
        prefs.edit()
            .putString("callsign", cfg.callsign)
            .putBoolean("shareLocation", cfg.shareLocation)
            .putString("affiliation", cfg.affiliation.name)
            .putString("echelon", cfg.echelon.name)
            .putString("function", cfg.function.name)
            .putBoolean("isHQ", cfg.isHQ)
            .apply()
    }

    private fun loadPresenceConfig() {
        presenceConfig = PresenceConfig(
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
    }

    companion object {
        /** Default E2E-blind relay. Self-hosters can point this at their own Worker. */
        const val RELAY_BASE = "wss://tacmap-sync.christianbrooker.workers.dev/room/"
    }
}
