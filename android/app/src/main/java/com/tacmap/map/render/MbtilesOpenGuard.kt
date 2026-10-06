package com.tacmap.map.render

import com.tacmap.map.render.pdf.GuardResolution
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

enum class MbtilesLaunchDecision(val code: String) {
    NONE("none"),
    /** don't open the restored pack, show online in memory and ask */
    SUPPRESS("suppress"),
}

/**
 * The MBTiles open crash loop breaker (s14.1), pinned by import_limits.json mbtilesOpenGuard.
 * Its own file and reducer, not a kind in the PDF guard: that one has a single in-progress
 * slot and MBTiles opens run next to PDF work. Armed on every foreground restore and
 * activation, not verified once. Holds library entry ids (random uuids) and nothing else
 */
class MbtilesOpenGuardState {
    private val inProgress = ArrayList<String>()

    /** oldest first */
    val armed: List<String> get() = inProgress.toList()

    var suspect: String? = null
        private set

    /** true when this arm left a marker. background never arms */
    fun arm(token: String, foreground: Boolean): Boolean {
        if (!foreground) return false
        // one marker per pack, moved to the newest end
        inProgress.remove(token)
        inProgress += token
        while (inProgress.size > MAX_IN_PROGRESS) inProgress.removeAt(0)
        return true
    }

    /** no effect on the suspect */
    fun complete(token: String) {
        inProgress.remove(token)
    }

    fun disarmBackground() {
        inProgress.clear()
    }

    fun launch(restoredToken: String?): MbtilesLaunchDecision {
        val decision = when {
            restoredToken != null && restoredToken in inProgress -> {
                suspect = restoredToken
                MbtilesLaunchDecision.SUPPRESS
            }
            restoredToken != null && suspect == restoredToken -> MbtilesLaunchDecision.SUPPRESS
            else -> MbtilesLaunchDecision.NONE
        }
        inProgress.clear()
        return decision
    }

    /** Not Now keeps it, so the next launch asks again */
    fun resolve(choice: GuardResolution) {
        if (choice == GuardResolution.OPEN_ANYWAY || choice == GuardResolution.DELETED) suspect = null
    }

    /** {"v":1,"inProgress":[..],"suspect":..} */
    fun toJson(): JsonObject = buildJsonObject {
        put("v", FILE_VERSION)
        put("inProgress", JsonArray(inProgress.map(::JsonPrimitive)))
        put("suspect", suspect?.let(::JsonPrimitive) ?: JsonNull)
    }

    companion object {
        const val FILE_VERSION = 1
        const val MAX_IN_PROGRESS = 4

        /** anything that doesn't parse is an empty state, the guard fails open (opens) not shut */
        fun fromJson(element: JsonElement?): MbtilesOpenGuardState {
            val state = MbtilesOpenGuardState()
            val o = element as? JsonObject ?: return state
            if ((o["v"] as? JsonPrimitive)?.intOrNull != FILE_VERSION) return state
            (o["inProgress"] as? JsonArray)?.forEach { item ->
                val token = (item as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return@forEach
                if (!isToken(token)) return@forEach
                state.inProgress.remove(token)
                state.inProgress += token
            }
            while (state.inProgress.size > MAX_IN_PROGRESS) state.inProgress.removeAt(0)
            state.suspect = (o["suspect"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf(::isToken)
            return state
        }

        private val TOKEN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        fun isToken(s: String): Boolean = TOKEN.matches(s)
    }
}

/**
 * noBackupFilesDir/mbtiles_open_guard.json. Every change goes temp + fsync + atomic rename
 * before the open it guards starts, otherwise the crash could beat the write to disk
 */
class MbtilesOpenGuard(private val file: File) {
    private val lock = Any()
    private var state: MbtilesOpenGuardState = load()
    private var launchTaken = false

    /** set when the last write didn't make it to disk */
    @Volatile var lastWriteFailed: Boolean = false
        private set

    private fun load(): MbtilesOpenGuardState = runCatching {
        if (!file.isFile) return@runCatching MbtilesOpenGuardState()
        MbtilesOpenGuardState.fromJson(Json.parseToJsonElement(file.readText(Charsets.UTF_8)))
    }.getOrElse { MbtilesOpenGuardState() }

    fun arm(token: String, foreground: Boolean): Boolean = synchronized(lock) {
        if (!MbtilesOpenGuardState.isToken(token)) return false
        val armed = state.arm(token, foreground)
        if (armed) persist()
        armed
    }

    fun complete(token: String) = synchronized(lock) {
        if (token !in state.armed) return
        state.complete(token)
        persist()
    }

    fun disarmBackground() = synchronized(lock) {
        if (state.armed.isEmpty()) return
        state.disarmBackground()
        persist()
    }

    fun isArmed(token: String): Boolean = synchronized(lock) { token in state.armed }

    /**
     * Once per process at the PDF guard's moment: the first caller runs the launch step (and
     * clears every marker). A later restore in the same process just checks the standing suspect
     */
    fun launchDecision(restoredToken: String?): MbtilesLaunchDecision = synchronized(lock) {
        if (launchTaken) {
            return if (restoredToken != null && state.suspect == restoredToken) MbtilesLaunchDecision.SUPPRESS
            else MbtilesLaunchDecision.NONE
        }
        launchTaken = true
        val decision = state.launch(restoredToken)
        persist()
        decision
    }

    val launchDecided: Boolean get() = synchronized(lock) { launchTaken }

    fun isSuspect(token: String?): Boolean = synchronized(lock) { token != null && state.suspect == token }

    fun resolve(choice: GuardResolution) = synchronized(lock) {
        state.resolve(choice)
        persist()
    }

    fun snapshot(): JsonObject = synchronized(lock) { state.toJson() }

    private fun persist() {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            FileOutputStream(tmp).use { out ->
                out.write(state.toJson().toString().toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            lastWriteFailed = false
        }.onFailure {
            // nothing to do but carry on, the guard just won't catch the next crash
            lastWriteFailed = true
        }
    }

    companion object {
        const val FILE_NAME = "mbtiles_open_guard.json"
    }
}

/** what an offline tile source tells the open guard about its reads */
interface TileReadWatch {
    fun readStarted()

    /** [delivered] = the read came back (a tile or no tile), false = it was cancelled */
    fun readEnded(delivered: Boolean)
}

/**
 * The open guard's first draw window for one publication of a pack (s14.1): settled once at
 * least one read came back and none has been pending for [FIRST_DRAW_QUIET_MS], or when no
 * read was asked for within [NO_READ_COMPLETE_MS] (camera off the pack, screen not up)
 */
class MbtilesFirstDraw(private val clock: () -> Long) : TileReadWatch {
    private val publishedAtMs = clock()
    private var pending = 0
    private var requested = false
    private var delivered = false
    private var quietSinceMs = publishedAtMs

    @Synchronized override fun readStarted() {
        requested = true
        pending++
    }

    @Synchronized override fun readEnded(delivered: Boolean) {
        if (pending > 0) pending--
        if (delivered) this.delivered = true
        if (pending == 0) quietSinceMs = clock()
    }

    @Synchronized fun settled(): Boolean {
        val now = clock()
        if (!requested) return now - publishedAtMs >= NO_READ_COMPLETE_MS
        return delivered && pending == 0 && now - quietSinceMs >= FIRST_DRAW_QUIET_MS
    }

    companion object {
        const val FIRST_DRAW_QUIET_MS = 500L
        const val NO_READ_COMPLETE_MS = 2_000L
    }
}
