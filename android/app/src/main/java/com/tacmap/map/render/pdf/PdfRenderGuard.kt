package com.tacmap.map.render.pdf

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

/** what the guard was watching when the process died */
enum class GuardKind(val code: String) {
    IMPORT("import"), BASE("base"), VECTOR("vector"), BAKE("bake");

    companion object {
        fun fromCode(code: String?): GuardKind? = entries.firstOrNull { it.code == code }
    }
}

/**
 * What launch found (contract I, K1 amendment). [decision] is the first matching row of
 * the launch table without the bake row, [bakeInterrupted] is reported on its own so an
 * interrupted import and an interrupted bake both get their notice.
 */
data class GuardLaunchDecision(
    val decision: Decision,
    /** the pending import that never finished its probe, only with IMPORT_INTERRUPTED */
    val operationKey: String? = null,
    /** a bake was running when the process died, clean its work dir + say so */
    val bakeInterrupted: Boolean = false,
) {
    enum class Decision(val code: String) {
        NONE("none"),
        /** the pending import with [operationKey] never finished its probe */
        IMPORT_INTERRUPTED("importInterrupted"),
        /** don't auto render the restored PDF, ask first */
        SUPPRESS("suppress"),
    }

    val code: String get() = decision.code
    val suppress: Boolean get() = decision == Decision.SUPPRESS
    val importInterrupted: Boolean get() = decision == Decision.IMPORT_INTERRUPTED

    companion object {
        val NONE = GuardLaunchDecision(Decision.NONE)
    }
}

enum class GuardResolution(val code: String) {
    OPEN_ANYWAY("openAnyway"), DELETED("deleted"), NOT_NOW("notNow");
}

/**
 * Crash loop breaker state machine (contract I), pinned by the fixture's
 * crashGuard cases. Holds only random UUIDs, never a file or map name: the
 * file sits outside the sealed store, and a sheet name would give away the AO.
 */
class PdfRenderGuardState {
    data class InProgress(val kind: GuardKind, val token: String, val op: String? = null)

    /** import, base or vector. a bake never goes in here any more, it has its own slot */
    var inProgress: InProgress? = null
        private set
    /** the running bake's token (K1). only arm/complete(bake) touch it */
    var bakeInProgress: String? = null
        private set
    var suspect: String? = null
        private set
    /** oldest first, kinds kept in the order base, vector */
    private val verified = LinkedHashMap<String, List<GuardKind>>()

    val verifiedOrder: List<String> get() = verified.keys.toList()

    fun verifiedKinds(token: String): List<GuardKind> = verified[token].orEmpty()

    /** true when this arm left a marker behind */
    fun arm(kind: GuardKind, token: String, foreground: Boolean = true, operationKey: String? = null): Boolean {
        when (kind) {
            GuardKind.BAKE -> {
                // one bake at a time, a new one just replaces the slot. never touches inProgress
                bakeInProgress = token
                return true
            }
            GuardKind.IMPORT -> {
                inProgress = InProgress(kind, token, operationKey?.takeIf { it.isNotEmpty() })
                return true
            }
            GuardKind.BASE, GuardKind.VECTOR -> {
                // a bake marker doesn't block these, first renders stay guarded during a bake
                if (!foreground || kind in verifiedKinds(token) || inProgress != null) return false
                inProgress = InProgress(kind, token)
                return true
            }
        }
    }

    fun complete(kind: GuardKind, token: String) {
        when (kind) {
            GuardKind.BAKE -> {
                // verifies nothing, and only clears its own marker
                if (bakeInProgress == token) bakeInProgress = null
                return
            }
            GuardKind.IMPORT -> verify(token, GuardKind.BASE)
            GuardKind.BASE, GuardKind.VECTOR -> verify(token, kind)
        }
        val ip = inProgress
        if (ip != null && ip.kind == kind && ip.token == token) inProgress = null
    }

    fun disarmBackground() {
        val ip = inProgress ?: return
        if (ip.kind == GuardKind.BASE || ip.kind == GuardKind.VECTOR) inProgress = null
    }

    fun launch(restoredToken: String?): GuardLaunchDecision = launch(listOfNotNull(restoredToken))

    /**
     * same table, but more than one map can come back on its own at launch: the active PDF
     * and the entry a calibration auto-resume would preview (C8). Whichever of them was
     * drawing when the process died is the suspect
     */
    fun launch(restoredTokens: Collection<String>): GuardLaunchDecision {
        val ip = inProgress
        // an older build kept the bake in inProgress, fromJson moves that into the slot
        val bakeInterrupted = bakeInProgress != null || ip?.kind == GuardKind.BAKE
        val decision = when {
            ip != null && ip.kind == GuardKind.IMPORT ->
                GuardLaunchDecision(GuardLaunchDecision.Decision.IMPORT_INTERRUPTED, ip.op, bakeInterrupted)
            ip != null && (ip.kind == GuardKind.BASE || ip.kind == GuardKind.VECTOR) &&
                ip.token in restoredTokens -> {
                suspect = ip.token
                GuardLaunchDecision(GuardLaunchDecision.Decision.SUPPRESS, null, bakeInterrupted)
            }
            suspect != null && suspect in restoredTokens ->
                GuardLaunchDecision(GuardLaunchDecision.Decision.SUPPRESS, null, bakeInterrupted)
            else -> GuardLaunchDecision(GuardLaunchDecision.Decision.NONE, null, bakeInterrupted)
        }
        inProgress = null
        bakeInProgress = null
        return decision
    }

    fun resolve(choice: GuardResolution) {
        if (choice == GuardResolution.OPEN_ANYWAY || choice == GuardResolution.DELETED) suspect = null
    }

    private fun verify(token: String, kind: GuardKind) {
        val had = verified.remove(token).orEmpty()
        verified[token] = KIND_ORDER.filter { it in had || it == kind }
        while (verified.size > MAX_VERIFIED_TOKENS) {
            verified.remove(verified.keys.first())
        }
    }

    /** the on disk shape, {"v":1,"inProgress":..,"bakeInProgress":..,"suspect":..,"verified":{..}} */
    fun toJson(): JsonObject = buildJsonObject {
        put("v", FILE_VERSION)
        val ip = inProgress
        if (ip == null) put("inProgress", JsonNull) else put("inProgress", buildJsonObject {
            put("kind", ip.kind.code)
            put("token", ip.token)
            ip.op?.let { put("op", it) }
        })
        val bake = bakeInProgress
        if (bake == null) put("bakeInProgress", JsonNull) else put("bakeInProgress", buildJsonObject { put("token", bake) })
        put("suspect", suspect?.let(::JsonPrimitive) ?: JsonNull)
        put("verified", JsonObject(verified.mapValues { (_, kinds) -> JsonArray(kinds.map { JsonPrimitive(it.code) }) }))
    }

    companion object {
        const val MAX_VERIFIED_TOKENS = 16
        const val FILE_VERSION = 1
        private val KIND_ORDER = listOf(GuardKind.BASE, GuardKind.VECTOR)

        /** anything that doesn't parse is a fresh state, the guard fails open (renders) not shut */
        fun fromJson(element: JsonElement?): PdfRenderGuardState {
            val state = PdfRenderGuardState()
            val o = element as? JsonObject ?: return state
            if ((o["v"] as? JsonPrimitive)?.intOrNull != FILE_VERSION) return state
            (o["inProgress"] as? JsonObject)?.let { ip ->
                val kind = GuardKind.fromCode((ip["kind"] as? JsonPrimitive)?.contentOrNull)
                val token = (ip["token"] as? JsonPrimitive)?.contentOrNull
                if (kind == GuardKind.BAKE && token != null && isToken(token)) {
                    // legacy file from before the bake slot, it still means a bake was running
                    state.bakeInProgress = token
                } else if (kind != null && token != null && isToken(token)) {
                    state.inProgress = InProgress(kind, token, (ip["op"] as? JsonPrimitive)?.contentOrNull)
                }
            }
            // missing reads as null, the file stays v1
            ((o["bakeInProgress"] as? JsonObject)?.get("token") as? JsonPrimitive)?.contentOrNull
                ?.takeIf(::isToken)?.let { state.bakeInProgress = it }
            state.suspect = (o["suspect"] as? JsonPrimitive)?.contentOrNull?.takeIf(::isToken)
            (o["verified"] as? JsonObject)?.forEach { (token, kinds) ->
                if (!isToken(token)) return@forEach
                val list = (kinds as? JsonArray)?.mapNotNull { GuardKind.fromCode((it as? JsonPrimitive)?.contentOrNull) }
                    ?.filter { it in KIND_ORDER }.orEmpty()
                state.verified[token] = KIND_ORDER.filter { it in list }
            }
            while (state.verified.size > MAX_VERIFIED_TOKENS) state.verified.remove(state.verified.keys.first())
            return state
        }

        // tokens are random uuids. anything else in the file is junk or tampering
        private val TOKEN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        fun isToken(s: String): Boolean = TOKEN.matches(s)
    }
}

/**
 * The guard's plaintext file (noBackupFilesDir/pdf_render_guard.json). Every
 * change is written temp + fsync + atomic rename before the risky render starts,
 * otherwise a crash could beat the write to disk.
 */
class PdfRenderGuard(private val file: File) {
    private val lock = Any()
    private var state: PdfRenderGuardState = load()
    private var launchDecision: Pair<Set<String>, GuardLaunchDecision>? = null

    /** set when the last write didn't make it to disk */
    @Volatile var lastWriteFailed: Boolean = false
        private set

    private fun load(): PdfRenderGuardState = runCatching {
        if (!file.isFile) return@runCatching PdfRenderGuardState()
        PdfRenderGuardState.fromJson(Json.parseToJsonElement(file.readText(Charsets.UTF_8)))
    }.getOrElse { PdfRenderGuardState() }

    fun arm(kind: GuardKind, token: String, foreground: Boolean = true, operationKey: String? = null): Boolean =
        synchronized(lock) {
            if (!PdfRenderGuardState.isToken(token)) return false
            val armed = state.arm(kind, token, foreground, operationKey)
            if (armed) persist()
            armed
        }

    fun complete(kind: GuardKind, token: String) = synchronized(lock) {
        if (!PdfRenderGuardState.isToken(token)) return
        state.complete(kind, token)
        persist()
    }

    fun disarmBackground() = synchronized(lock) {
        val before = state.inProgress
        state.disarmBackground()
        if (before != state.inProgress) persist()
    }

    fun isVerified(kind: GuardKind, token: String): Boolean = synchronized(lock) { kind in state.verifiedKinds(token) }

    /**
     * Once per process: the first caller runs the launch step (and clears any marker),
     * later callers get the same answer for the same token. A restore that happens
     * after the decision just checks the standing suspect.
     */
    fun launchDecision(restoredToken: String?): GuardLaunchDecision = launchDecision(listOfNotNull(restoredToken))

    /** [launchDecision] for every map that restores on its own (active PDF + an auto-resume preview) */
    fun launchDecision(restoredTokens: Collection<String>): GuardLaunchDecision = synchronized(lock) {
        val wanted = restoredTokens.toSet()
        launchDecision?.let { (tokens, decision) ->
            if (tokens == wanted) return decision
            return if (state.suspect != null && state.suspect in wanted) {
                GuardLaunchDecision(GuardLaunchDecision.Decision.SUPPRESS)
            } else GuardLaunchDecision.NONE
        }
        val decision = state.launch(wanted)
        persist()
        launchDecision = wanted to decision
        decision
    }

    /** the launch step already ran this process */
    val launchDecided: Boolean get() = synchronized(lock) { launchDecision != null }

    /** [token] is the standing crash suspect (only Open Anyway or Delete clears it) */
    fun isSuspect(token: String?): Boolean = synchronized(lock) { token != null && state.suspect == token }

    private var noticeTaken = false

    /** the import/bake interrupted notices show once per process, whichever screen asks first */
    fun takeNotice(): Boolean = synchronized(lock) {
        if (noticeTaken) false else {
            noticeTaken = true
            true
        }
    }

    fun resolve(choice: GuardResolution) = synchronized(lock) {
        state.resolve(choice)
        launchDecision = launchDecision?.let { (token, d) ->
            token to (if (d.suppress && choice != GuardResolution.NOT_NOW) d.copy(decision = GuardLaunchDecision.Decision.NONE) else d)
        }
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
            // can't do much, the guard just won't catch the next crash. stays pure jvm so no Log here
            lastWriteFailed = true
        }
    }

    companion object {
        const val FILE_NAME = "pdf_render_guard.json"
    }
}

/** the once per launch notices that come after the crash guard's launch step */
sealed class PdfLaunchNotice {
    /** TacMap closed while preparing an imported map */
    data class ImportInterrupted(val operationKey: String?) : PdfLaunchNotice()
    /** Offline tiles not finished */
    data object BakeInterrupted : PdfLaunchNotice()

    companion object {
        /**
         * In the order they show (K1): the decision's own alert first, then the bake one.
         * Suppress has no notice here, its alert is the crash recovery one, and the UI
         * holds this list back until that's answered.
         */
        fun from(d: GuardLaunchDecision): List<PdfLaunchNotice> = buildList {
            if (d.importInterrupted) add(ImportInterrupted(d.operationKey))
            if (d.bakeInterrupted) add(BakeInterrupted)
        }
    }
}
