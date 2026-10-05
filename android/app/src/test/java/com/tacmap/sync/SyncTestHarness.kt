package com.tacmap.sync

import android.content.SharedPreferences
import android.location.Location
import com.tacmap.drawings.DrawingStore
import com.tacmap.export.GeoJsonExporter
import com.tacmap.util.DataKey
import com.tacmap.util.SafeStore
import com.tacmap.waypoints.Waypoint
import com.tacmap.waypoints.WaypointStore
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

/** Socket the test drives by hand. Mirrors what Java-WebSocket reports. */
internal class FakeSocket(
    val url: String,
    val headers: Map<String, String>,
    val listener: SyncWebSocketListener,
    val createdAtMs: Long,
) : SyncWebSocket {
    val sent = ArrayList<String>()
    var isOpen = false
        private set
    var terminal = false
        private set
    var localClose: Pair<Int, String>? = null
        private set
    var cancelled = false
        private set
    /** What queuedBytes() reports; 0 means every send already hit the wire. */
    var unwrittenBytes = 0L
    var pings = 0

    override fun send(text: String): Boolean {
        if (!isOpen || terminal) return false
        sent += text
        return true
    }

    override fun close(code: Int, reason: String): Boolean {
        if (terminal) return false
        localClose = code to reason
        terminal = true
        listener.onClosed(this, code, reason)
        return true
    }

    override fun cancel() {
        if (terminal) return
        cancelled = true
        terminal = true
        listener.onClosed(this, 1006, "cancelled")
    }

    override fun queuedBytes(): Long = unwrittenBytes

    override fun sendPing(): Boolean {
        if (!isOpen || terminal) return false
        pings += 1
        return true
    }

    fun open() {
        isOpen = true
        listener.onOpen(this)
    }

    fun deliver(text: String) = listener.onTextMessage(this, text) {}
    fun deliver(frame: JSONObject) = deliver(frame.toString())
    fun deliverBinary(bytes: ByteArray) = listener.onBinaryMessage(this, bytes) {}
    fun progress() = listener.onInboundProgress(this)

    fun serverClose(code: Int, reason: String = "") {
        terminal = true
        listener.onClosed(this, code, reason)
    }

    /** What Java-WebSocket does when the upgrade answers something other than 101. */
    fun rejectUpgrade(status: Int) {
        terminal = true
        listener.onClosed(this, -1, "Invalid status code received: $status Status line: HTTP/1.1 $status Nope")
    }

    fun fail(message: String = "Connection reset") {
        terminal = true
        listener.onFailure(this, IOException(message))
    }

    fun sentFrames(): List<JSONObject> = sent.map(::JSONObject)
    fun sentOfType(t: String): List<JSONObject> = sentFrames().filter { it.optString("t") == t }
}

internal class FakeSyncTransport(private val now: () -> Long) : SyncTransportFactory {
    val sockets = ArrayList<FakeSocket>()
    override fun newWebSocket(
        url: String,
        headers: Map<String, String>,
        listener: SyncWebSocketListener,
    ): SyncWebSocket = FakeSocket(url, headers, listener, now()).also { sockets += it }

    val last: FakeSocket get() = sockets.last()
}

internal class FakeReachability : SyncReachability {
    var listener: SyncReachability.Listener? = null
    override fun start(listener: SyncReachability.Listener) { this.listener = listener }
    override fun stop() { listener = null }
}

internal object NoFixRequester : ForegroundFixRequester {
    override fun request(completion: (Location?) -> Unit): Boolean = false
    override fun cancel() = Unit
}

internal class FakeSharedPreferences : SharedPreferences {
    private val values = HashMap<String, Any?>()

    override fun getAll(): MutableMap<String, *> = HashMap(values)
    override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        values[key] as? MutableSet<String> ?: defValues
    override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String): Boolean = values.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = HashMap<String, Any?>()
        private val removals = HashSet<String>()
        private var clear = false
        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { removals += key }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean { applyNow(); return true }
        override fun apply() = applyNow()
        private fun applyNow() {
            if (clear) values.clear()
            removals.forEach(values::remove)
            values.putAll(pending)
        }
    }
}

/** Another room member with its own signing key, used to forge honest (and not so honest) records. */
internal class FakeV3Peer(private val keys: SyncCrypto.V3RoomKeys) {
    val seed: ByteArray = SyncSigning.generateSeed()
    val pub: String = SyncSigning.publicKey(seed)
    val pubRaw: ByteArray = SyncIdentity.urlB64Decode(pub)
    val actor: String = SyncIdentity.actorId(keys.roomIdRaw, pubRaw)
    val sd: ByteArray = SyncIdentity.generateSessionDomain()
    val sdText: String = SyncIdentity.urlB64(sd)
    var epoch = 1L

    fun wireId(localId: String): String =
        SyncIdentity.wireObjectId(keys.metadataKey, SyncIdentity.uuidToBytes(localId)!!)

    fun hello(): JSONObject {
        val epochHex = VersionStamp.counterHex16(epoch)
        val preimage = SyncIdentity.buildPreimage(
            SyncIdentity.DOMAIN_HELLO, keys.roomIdRaw, actor, sd, epochHex, "", "hello", SyncIdentity.sha256(pubRaw),
        )
        return JSONObject().put("t", "hello").put("by", actor).put("pub", pub).put("sd", sdText)
            .put("vs", "$epochHex:$actor").put("sig", SyncSigning.sign(seed, preimage))
    }

    /** A snapshot-shaped record. [t] turns it into a live frame. */
    fun record(
        wireId: String,
        counter: Long,
        kind: String,
        content: String?,
        deleted: Boolean = false,
        t: String? = null,
        ctOverride: String? = null,
        innerExtra: Map<String, String> = emptyMap(),
        badSignature: Boolean = false,
    ): JSONObject {
        val vs = VersionStamp(counter, actor).encode()
        val signedKind = if (deleted) "del" else kind
        val payload = if (deleted) ByteArray(0) else (content ?: "").toByteArray(Charsets.UTF_8)
        val preimage = SyncIdentity.buildPreimage(
            if (deleted) SyncIdentity.DOMAIN_DELETE else SyncIdentity.DOMAIN_PUT,
            keys.roomIdRaw, actor, sd, VersionStamp.counterHex16(counter), wireId, signedKind,
            SyncIdentity.sha256(payload),
        )
        val sig = if (badSignature) SyncSigning.sign(SyncSigning.generateSeed(), preimage) else SyncSigning.sign(seed, preimage)
        val inner = JSONObject()
        if (!deleted && content != null) inner.put("c", content)
        inner.put("sig", sig)
        innerExtra.forEach { (k, v) -> inner.put(k, v) }
        val ct = ctOverride ?: SyncCrypto.encodeBase64(
            SyncCrypto.seal(keys.roomKey, inner.toString().toByteArray(Charsets.UTF_8), SyncCrypto.aadV3(wireId, vs, signedKind))
        )
        return JSONObject().apply {
            t?.let { put("t", it) }
            put("id", wireId); put("vs", vs); put("by", actor); put("kind", signedKind)
            put("ct", ct); put("pub", pub); put("sd", sdText)
            if (t == null) put("deleted", deleted)
        }
    }

    fun waypointRecord(waypoint: Waypoint, counter: Long, t: String? = null): JSONObject =
        record(wireId(waypoint.id), counter, "waypoint", waypointContent(waypoint), t = t)

    companion object {
        fun waypointContent(waypoint: Waypoint): String =
            GeoJsonExporter.export(listOf(waypoint), emptyList(), emptyList(), density = 1f)
    }
}

/** Counts the background probe's partial wake lock. */
internal class FakeWakeLock : SyncWakeLock {
    var acquired = 0
    var released = 0
    var lastTimeoutMs = 0L
    val held: Boolean get() = acquired > released
    override fun acquire(timeoutMs: Long) {
        acquired += 1
        lastTimeoutMs = timeoutMs
    }
    override fun release() {
        if (held) released += 1
    }
}

internal class SyncHarness(
    val dir: File = Files.createTempDirectory("sync-harness").toFile(),
    /** Validation, sealed writes and PBKDF2 on their own virtual dispatchers, stepped by hand. */
    separateWorkers: Boolean = false,
    /** The 21.4 switch; production ships it off until doc change D1. */
    backgroundReconnect: Boolean = false,
    persistenceWorker: kotlinx.coroutines.CoroutineDispatcher? = null,
) {
    val dispatcher = VirtualTimeDispatcher()
    val validationDispatcher = if (separateWorkers) VirtualTimeDispatcher() else dispatcher
    val persistenceDispatcher = if (separateWorkers) VirtualTimeDispatcher() else dispatcher
    val deriveDispatcher = if (separateWorkers) VirtualTimeDispatcher() else dispatcher
    val wakeLock = FakeWakeLock()
    val scheduler get() = dispatcher
    /** Anything that escaped a manager coroutine. Tests assert this stays empty. */
    val uncaught = java.util.Collections.synchronizedList(ArrayList<Throwable>())
    val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, e -> uncaught += e })
    val clock = object : SyncClock {
        override fun elapsedRealtimeMs(): Long = BOOT_MS + scheduler.currentTime
        override fun elapsedRealtimeNanos(): Long = elapsedRealtimeMs() * 1_000_000L
        override fun wallClockMs(): Long = WALL_MS + scheduler.currentTime
    }
    val transport = FakeSyncTransport { scheduler.currentTime }
    val reachability = FakeReachability()
    var randomValue = 1.0
    val waypointStore: WaypointStore = WaypointStore.forTests(File(dir, "waypoints").apply { mkdirs() })
    val drawingStore: DrawingStore = DrawingStore.forTests(File(dir, "drawings").apply { mkdirs() })
    val preferences = FakeSharedPreferences()
    val chatStore = TacMapChatHistoryStore.forTests(File(dir, "chat"))
    val env = SyncEnvironment(
        displayDensity = 1f,
        filesDir = File(dir, "files").apply { mkdirs() },
        preferences = preferences,
        chatHistoryStore = chatStore,
        foregroundFixRequester = NoFixRequester,
        transportFactory = transport,
        clock = clock,
        random = { randomValue },
        reachability = reachability,
        dispatcher = dispatcher,
        deriveRoomV3 = ::cachedV3,
        deriveRoomV2 = ::cachedV2,
        validationDispatcher = validationDispatcher,
        persistenceDispatcher = persistenceWorker ?: persistenceDispatcher,
        deriveDispatcher = deriveDispatcher,
        wakeLock = wakeLock,
        allowsSimulatorTeleport = { false },
        backgroundReconnectEnabled = backgroundReconnect,
    )
    val manager = SyncManager(waypointStore, drawingStore, scope, env)

    fun runCurrent() {
        scheduler.runCurrent()
        // The separate-worker scripts hold snapshot seals explicitly, while a
        // fresh join's file load is setup following the explicitly driven KDF.
        if (manager.status.value == SyncManager.Status.CONNECTING && manager.replayStateForTests == null) {
            persistenceDispatcher.runCurrent()
            scheduler.runCurrent()
        }
    }
    fun advance(ms: Long) {
        scheduler.advanceTimeBy(ms)
        scheduler.runCurrent()
    }
    val now: Long get() = scheduler.currentTime
    val socket: FakeSocket get() = transport.last

    fun keys(code: String = CODE): SyncCrypto.V3RoomKeys = cachedV3(code.removePrefix("3:"))

    fun join(code: String = CODE) {
        manager.join(code)
        runCurrent()
    }

    fun deliver(frame: JSONObject, socket: FakeSocket = this.socket) {
        socket.deliver(frame)
        runCurrent()
    }

    fun deliverText(text: String, socket: FakeSocket = this.socket) {
        socket.deliver(text)
        runCurrent()
    }

    fun beginSnapshot(seq: Long = 1, socket: FakeSocket = this.socket) {
        if (!socket.isOpen) {
            socket.open()
            runCurrent()
        }
        deliver(JSONObject().put("t", "snapshot-begin").put("seq", seq).put("highWater", "0000000000000000"), socket)
    }

    fun snapshotPage(items: List<Any>, more: Boolean = false, socket: FakeSocket = this.socket) {
        deliver(JSONObject().put("t", "snapshot").put("items", JSONArray(items)).put("more", more), socket)
    }

    fun endSnapshot(seq: Long = 1, socket: FakeSocket = this.socket) {
        deliver(JSONObject().put("t", "snapshot-end").put("seq", seq), socket)
    }

    /** Full snapshot then hello-ack. Returns the hello we sent. */
    fun completeHandshake(items: List<JSONObject> = emptyList(), seq: Long = 1, socket: FakeSocket = this.socket): JSONObject {
        beginSnapshot(seq, socket)
        snapshotPage(items, more = false, socket = socket)
        endSnapshot(seq, socket)
        val hello = socket.sentOfType("hello").last()
        deliver(JSONObject().put("t", "hello-ack").put("by", hello.getString("by"))
            .put("sd", hello.getString("sd")).put("vs", hello.getString("vs")), socket)
        return hello
    }

    fun ackPut(frame: JSONObject, socket: FakeSocket = this.socket) {
        val cth = SyncIdentity.urlB64(SyncIdentity.sha256(frame.getString("ct").toByteArray(Charsets.UTF_8)))
        deliver(JSONObject().put("t", "op-ack").put("av", 1).put("rid", frame.getString("rid"))
            .put("by", frame.getString("by")).put("sd", frame.getString("sd")).put("id", frame.getString("id"))
            .put("vs", frame.getString("vs")).put("kind", frame.getString("kind")).put("cth", cth), socket)
    }

    fun nack(frame: JSONObject, code: String, retry: Boolean, socket: FakeSocket = this.socket) {
        deliver(JSONObject().put("t", "op-nack").put("av", 1).put("rid", frame.getString("rid"))
            .put("by", frame.getString("by")).put("sd", frame.getString("sd")).put("code", code).put("retry", retry), socket)
    }

    fun addWaypoint(name: String = "W", lat: Double = -35.0, lon: Double = 149.0): Waypoint {
        val wp = Waypoint(name = name, latitude = lat, longitude = lon, createdAt = 1_700_000_000_000L)
        check(waypointStore.add(wp))
        return wp
    }

    fun close() {
        manager.dispose()
        scope.cancel()
        // Non-cancellable durable completions own the serial replay order until
        // their owner-dispatcher callbacks run. Never strand that lock in a test.
        repeat(8) {
            persistenceDispatcher.runCurrent()
            validationDispatcher.runCurrent()
            deriveDispatcher.runCurrent()
            scheduler.runCurrent()
        }
        dir.deleteRecursively()
    }

    companion object {
        const val CODE = "3:ABCDEFGHJKMNPQRS"
        const val CODE_V2 = "2:ABCDEFGHJKMNPQRS"
        const val BOOT_MS = 1_000_000L
        const val WALL_MS = 1_790_812_800_000L
        private val v3Cache = ConcurrentHashMap<String, SyncCrypto.V3RoomKeys>()
        private val v2Cache = ConcurrentHashMap<String, SyncCrypto.RoomKeys>()
        private val storeKey = ByteArray(32) { (it + 41).toByte() }

        // PBKDF2 at 210k rounds is slow on the JVM; hand out copies because leave() zeroes them
        fun cachedV3(code: String): SyncCrypto.V3RoomKeys {
            val k = v3Cache.getOrPut(code) { SyncCrypto.deriveRoomV3(code) }
            return SyncCrypto.V3RoomKeys(k.roomId, k.roomIdRaw.copyOf(), k.roomKey.copyOf(), k.metadataKey.copyOf(), k.authToken)
        }

        fun cachedV2(code: String): SyncCrypto.RoomKeys {
            val k = v2Cache.getOrPut(code) { SyncCrypto.deriveRoom(code) }
            return SyncCrypto.RoomKeys(k.roomId, k.roomKey.copyOf(), k.authToken)
        }

        private val sealedLabels = ConcurrentHashMap.newKeySet<String>()

        fun installStoreKey() {
            SafeStore.keyProvider = SafeStore.KeyProvider { storeKey.copyOf() }
            SafeStore.migrationPolicy = object : SafeStore.MigrationPolicy {
                override fun isSealedOnly(label: String) = label in sealedLabels
                override fun markSealedOnly(label: String) { sealedLabels += label }
            }
        }

        fun restoreStoreKey() {
            SafeStore.keyProvider = SafeStore.KeyProvider { DataKey.key() }
            SafeStore.migrationPolicy = object : SafeStore.MigrationPolicy {
                override fun isSealedOnly(label: String) = DataKey.isStoreSealedOnly(label)
                override fun markSealedOnly(label: String) = DataKey.markStoreSealedOnly(label)
            }
        }
    }
}
