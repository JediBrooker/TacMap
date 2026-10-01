package com.tacmap.sync

import android.content.Context
import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.location.Location
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.io.File
import kotlin.random.Random

/** Builds sockets. Production is [SyncWebSocketTransport]; tests hand in a fake. */
internal interface SyncTransportFactory {
    fun newWebSocket(url: String, headers: Map<String, String>, listener: SyncWebSocketListener): SyncWebSocket
    fun shutdown() {}
}

/** Every clock SyncManager reads, so tests can run on virtual time. */
internal interface SyncClock {
    /** Monotonic, keeps counting through deep sleep (elapsedRealtime). */
    fun elapsedRealtimeMs(): Long
    fun elapsedRealtimeNanos(): Long
    fun wallClockMs(): Long
}

internal object AndroidSyncClock : SyncClock {
    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
    override fun elapsedRealtimeNanos(): Long = SystemClock.elapsedRealtimeNanos()
    override fun wallClockMs(): Long = System.currentTimeMillis()
}

/** Default-network changes (plans/04 section 10). */
internal interface SyncReachability {
    interface Listener {
        /** onAvailable, or a network gaining VALIDATED. */
        fun onNetworkAvailable()
        /** The network we were on went away or the default switched. */
        fun onNetworkLostOrChanged()
    }

    fun start(listener: Listener)
    fun stop()
}

internal class ConnectivitySyncReachability(context: Context) : SyncReachability {
    private val connectivity = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Synchronized
    override fun start(listener: SyncReachability.Listener) {
        if (callback != null) return
        val manager = connectivity ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            private var current: Network? = null
            private var validated = false

            override fun onAvailable(network: Network) {
                val previous = current
                current = network
                if (previous != null && previous != network) listener.onNetworkLostOrChanged()
                listener.onNetworkAvailable()
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val nowValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (nowValidated && !validated) listener.onNetworkAvailable()
                validated = nowValidated
            }

            override fun onLost(network: Network) {
                if (current == network) current = null
                validated = false
                listener.onNetworkLostOrChanged()
            }
        }
        // missing permission or a weird OEM build just means no shortcut, not a crash
        runCatching { manager.registerDefaultNetworkCallback(cb) }.onSuccess { callback = cb }
    }

    @Synchronized
    override fun stop() {
        val cb = callback ?: return
        callback = null
        runCatching { connectivity?.unregisterNetworkCallback(cb) }
    }
}

/** Short partial wake lock for the background send probe (plans/04 section 21.3). */
internal interface SyncWakeLock {
    fun acquire(timeoutMs: Long)
    fun release()
}

internal class PowerManagerSyncWakeLock(context: Context) : SyncWakeLock {
    private val lock = (context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager)
        ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tacmap:unit-sync-probe")
        ?.apply { setReferenceCounted(false) }

    @SuppressLint("WakelockTimeout")
    override fun acquire(timeoutMs: Long) {
        runCatching { lock?.acquire(timeoutMs) }
    }

    override fun release() {
        runCatching { if (lock?.isHeld == true) lock.release() }
    }
}

/** Fresh GPS fix requests for foreground presence. */
internal interface ForegroundFixRequester {
    fun request(completion: (Location?) -> Unit): Boolean
    fun cancel()
}

/**
 * Everything SyncManager needs from Android, bundled so the state machine can
 * run on a plain JVM with a fake socket and virtual time (S6-02).
 */
internal class SyncEnvironment(
    val displayDensity: Float,
    val filesDir: File,
    val preferences: SharedPreferences,
    val chatHistoryStore: TacMapChatHistoryStore,
    val foregroundFixRequester: ForegroundFixRequester,
    val transportFactory: SyncTransportFactory,
    val clock: SyncClock,
    val random: () -> Double,
    val reachability: SyncReachability?,
    val dispatcher: CoroutineDispatcher,
    val deriveRoomV3: (String) -> SyncCrypto.V3RoomKeys = SyncCrypto::deriveRoomV3,
    val deriveRoomV2: (String) -> SyncCrypto.RoomKeys = SyncCrypto::deriveRoom,
    /** Snapshot record checks run here, one record at a time in item order (section 19). */
    val validationDispatcher: CoroutineDispatcher = dispatcher,
    /** Big sealed replay writes (snapshot commit and marker clear). */
    val persistenceDispatcher: CoroutineDispatcher = dispatcher,
    /** PBKDF2 at join (section 20.3). */
    val deriveDispatcher: CoroutineDispatcher = dispatcher,
    val wakeLock: SyncWakeLock? = null,
    /** The inbound protocol worker. Production posts to main so a callback can never re-enter it. */
    val inboundDispatcher: CoroutineDispatcher = dispatcher,
    /** Build.FINGERPRINT check, a stub field on the plain JVM. */
    val allowsSimulatorTeleport: () -> Boolean = PresenceLocationQuality::allowsSimulatorTeleport,
    /**
     * Presence-only screen-off reconnect (plans/04 section 21.4). Ships as
     * the policy constant, which stays false until doc change D1 lands; the
     * tests turn it on to drive the path.
     */
    val backgroundReconnectEnabled: Boolean = BackgroundPresencePolicy.RECONNECT_ENABLED,
) {
    companion object {
        @OptIn(ExperimentalCoroutinesApi::class)
        fun android(context: Context): SyncEnvironment {
            val app = context.applicationContext
            return SyncEnvironment(
                displayDensity = app.resources.displayMetrics.density,
                filesDir = app.filesDir,
                preferences = app.getSharedPreferences("sync", Context.MODE_PRIVATE),
                chatHistoryStore = TacMapChatHistoryStore(app),
                foregroundFixRequester = ForegroundGpsFixRequester(app),
                transportFactory = SyncWebSocketTransport(),
                clock = AndroidSyncClock,
                random = { Random.nextDouble() },
                reachability = ConnectivitySyncReachability(app),
                dispatcher = Dispatchers.Main.immediate,
                validationDispatcher = Dispatchers.Default.limitedParallelism(1),
                persistenceDispatcher = Dispatchers.IO.limitedParallelism(1),
                deriveDispatcher = Dispatchers.Default,
                wakeLock = PowerManagerSyncWakeLock(app),
                inboundDispatcher = Dispatchers.Main,
            )
        }
    }
}
