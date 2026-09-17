package com.tacmap.sync

import com.tacmap.util.SafeStore
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * v3 room-scoped identity derivation and binary preimage construction.
 * Every method is pure + deterministic so it unit-tests on host JVM against
 * the shared fixture (testdata/sync_protocol_v3.json).
 */
object SyncIdentity {

    const val DOMAIN_PUT: Byte = 0x01
    const val DOMAIN_DELETE: Byte = 0x02
    const val DOMAIN_PRESENCE: Byte = 0x03
    const val DOMAIN_HELLO: Byte = 0x04
    const val PROTOCOL_VERSION: Byte = 0x03
    const val EXPLICIT_LEAVE_VERSION = 1
    const val EXPLICIT_LEAVE_KIND = "leave-v1"

    private val ACTOR_PREFIX = "tacmap-actor-v3\u0000".toByteArray(Charsets.UTF_8)
    private val WIRE_OBJ_PREFIX = "tacmap-wire-obj-v3\u0000".toByteArray(Charsets.UTF_8)
    private val LOCAL_STORE_PREFIX = "tacmap-local-room-store-v1\u0000".toByteArray(Charsets.UTF_8)

    internal enum class LocalStoreDomain(val value: String) {
        CHAT("tacmap-chat"),
        REPLAY("sync-replay"),
    }

    /**
     * Room-scoped actor ID: SHA-256("tacmap-actor-v3\0" || roomIdRaw || pubkeyRaw)
     * -> base64url no pad. Same device in different rooms has different actorIds.
     */
    fun actorId(roomIdRaw: ByteArray, pubkeyRaw: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(ACTOR_PREFIX)
        md.update(roomIdRaw)
        md.update(pubkeyRaw)
        return urlB64(md.digest())
    }

    /**
     * Wire object ID: HMAC-SHA256(metadataKey, "tacmap-wire-obj-v3\0" || localUUID_bytes)
     * -> base64url no pad. The relay never sees the local UUID.
     */
    fun wireObjectId(metadataKey: ByteArray, localUuidBytes: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(metadataKey, "HmacSHA256"))
        mac.update(WIRE_OBJ_PREFIX)
        mac.update(localUuidBytes)
        return urlB64(mac.doFinal())
    }

    /**
     * Opaque DEK-bound local filename for per-room sealed state. The room ID
     * remains authenticated inside the store label but is absent from
     * filesystem metadata.
     */
    internal fun localStoreFileName(
        dataKey: ByteArray,
        roomId: String,
        domain: LocalStoreDomain,
    ): String? {
        val roomBytes = roomId.toByteArray(Charsets.UTF_8)
        if (dataKey.size != 32 || roomBytes.isEmpty() || roomBytes.size > 4_096) return null
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(dataKey, "HmacSHA256"))
        mac.update(LOCAL_STORE_PREFIX)
        mac.update(domain.value.toByteArray(Charsets.UTF_8))
        mac.update(0.toByte())
        mac.update(roomBytes)
        return "v1_${urlB64(mac.doFinal())}.json"
    }

    /**
     * Deterministic binary preimage for Ed25519 signing (ADR-001 section 5).
     * Typed + length-prefixed, no delimiter ambiguity.
     */
    fun buildPreimage(
        domain: Byte,
        roomIdRaw: ByteArray,
        actorId: String,
        sessionDomain: ByteArray,
        counterHex16: String,
        objectId: String,
        kind: String,
        payloadHash: ByteArray
    ): ByteArray {
        require(domain in DOMAIN_PUT..DOMAIN_HELLO) { "unknown signature domain" }
        require(roomIdRaw.size == 32) { "room id must be 32 bytes" }
        require(sessionDomain.size == 32) { "session domain must be 32 bytes" }
        val counterPattern = if (domain == DOMAIN_HELLO) "^[0-9a-f]{16}$" else "^[0-7][0-9a-f]{15}$"
        require(counterHex16.matches(Regex(counterPattern))) { "invalid counter" }
        require(payloadHash.size == 32) { "payload hash must be 32 bytes" }
        val actorIdBytes = actorId.toByteArray(Charsets.UTF_8)
        val objectIdBytes = objectId.toByteArray(Charsets.UTF_8)
        val kindBytes = kind.toByteArray(Charsets.UTF_8)
        val counterBytes = counterHex16.toByteArray(Charsets.US_ASCII)
        require(actorIdBytes.size <= 0xffff) { "actor id too long" }
        require(objectIdBytes.size <= 0xffff) { "object id too long" }
        require(kindBytes.size <= 0xff) { "kind too long" }

        val size = 1 + 1 + 32 + 2 + actorIdBytes.size + 32 + 16 +
            2 + objectIdBytes.size + 1 + kindBytes.size + 32
        val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

        buf.put(domain)
        buf.put(PROTOCOL_VERSION)
        buf.put(roomIdRaw)
        buf.putShort(actorIdBytes.size.toShort())
        buf.put(actorIdBytes)
        buf.put(sessionDomain)
        buf.put(counterBytes)
        buf.putShort(objectIdBytes.size.toShort())
        buf.put(objectIdBytes)
        buf.put(kindBytes.size.toByte())
        buf.put(kindBytes)
        buf.put(payloadHash)

        return buf.array()
    }

    fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    fun generateSessionDomain(): ByteArray {
        val random = java.security.SecureRandom()
        val raw = ByteArray(32)
        random.nextBytes(raw)
        return sha256(raw)
    }

    fun uuidToBytes(uuid: String): ByteArray {
        val stripped = uuid.replace("-", "")
        require(stripped.length == 32) { "UUID must be 32 hex chars" }
        return hexToBytes(stripped)
    }

    fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val out = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            out[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
        }
        return out
    }

    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    fun urlB64(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun urlB64Decode(s: String): ByteArray = Base64.getUrlDecoder().decode(s)

    /** Strict canonical base64url-no-padding decoder used on hostile wire input. */
    fun urlB64Decode32(s: String): ByteArray? {
        if (!s.matches(Regex("^[A-Za-z0-9_-]{43}$"))) return null
        return try {
            val decoded = Base64.getUrlDecoder().decode(s)
            decoded.takeIf { it.size == 32 && urlB64(it) == s }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun parseHelloEpoch(value: String): String? = value.takeIf {
        it.matches(Regex("^[0-9a-f]{16}$")) && it != "0000000000000000"
    }

    fun verifyHello(
        actorId: String, publicKey: String, sessionDomain: String,
        versionStamp: String, signature: String, roomIdRaw: ByteArray
    ): Boolean {
        val pubRaw = urlB64Decode32(publicKey) ?: return false
        val sdRaw = urlB64Decode32(sessionDomain) ?: return false
        if (urlB64Decode32(actorId) == null || this.actorId(roomIdRaw, pubRaw) != actorId) return false
        if (versionStamp.length != 60 || versionStamp.substring(16) != ":$actorId") return false
        val epoch = parseHelloEpoch(versionStamp.substring(0, 16)) ?: return false
        val preimage = buildPreimage(
            DOMAIN_HELLO, roomIdRaw, actorId, sdRaw, epoch, "", "hello", sha256(pubRaw)
        )
        return SyncSigning.verify(publicKey, preimage, signature)
    }

    /** Session-bound proof used only for a user's deliberate Leave action.
     * Ordinary socket loss has no such proof and is therefore announced by the
     * relay as transient. */
    fun explicitLeavePreimage(
        roomIdRaw: ByteArray,
        actorId: String,
        sessionDomain: ByteArray,
        helloVersion: String,
    ): ByteArray? {
        if (helloVersion.length != 60 || helloVersion.substring(16) != ":$actorId") return null
        val epoch = parseHelloEpoch(helloVersion.substring(0, 16)) ?: return null
        return buildPreimage(
            DOMAIN_HELLO,
            roomIdRaw,
            actorId,
            sessionDomain,
            epoch,
            "",
            EXPLICIT_LEAVE_KIND,
            sha256(ByteArray(0)),
        )
    }

    fun helloAckMatches(
        actorId: String, sessionDomain: ByteArray, expectedVersion: String,
        frameActorId: String, frameSessionDomain: String, frameVersion: String
    ): Boolean = frameActorId == actorId && frameSessionDomain == urlB64(sessionDomain) && frameVersion == expectedVersion
}

/** Filesystem migration paired with [SyncIdentity.localStoreFileName]. */
internal object SyncLocalStore {
    /** Proactively migrate every historical per-room filename once the mission
     * DEK is available, including rooms that the user never rejoins. */
    fun migrateAllLegacyStores(filesDir: File): Int {
        if (!Files.exists(filesDir.toPath(), LinkOption.NOFOLLOW_LINKS)) return 0
        requireSafeStoreDirectory(filesDir, create = false)
        // KeyProvider returns a caller-owned copy. Avoid a second plaintext DEK
        // allocation and wipe the owned value as soon as naming is complete.
        val namingKey = SafeStore.keyProvider.key()
        return try {
            var migrated = 0
            var firstFailure: Exception? = null
            for ((directoryName, domain) in listOf(
                "tacmap_chat" to SyncIdentity.LocalStoreDomain.CHAT,
                "sync_replay" to SyncIdentity.LocalStoreDomain.REPLAY,
            )) {
                try {
                    migrated += migrateLegacyFiles(
                        directory = File(filesDir, directoryName),
                        domain = domain,
                        dataKey = namingKey,
                    )
                } catch (failure: Exception) {
                    if (firstFailure == null) firstFailure = failure
                }
            }
            firstFailure?.let { throw it }
            migrated
        } finally {
            namingKey.fill(0)
        }
    }

    /** Scan only exact historical names. Arbitrary/malformed directory entries
     * are never interpreted as room identities or moved. */
    fun migrateLegacyFiles(
        directory: File,
        domain: SyncIdentity.LocalStoreDomain,
        dataKey: ByteArray,
    ): Int {
        if (!Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) return 0
        requireSafeStoreDirectory(directory, create = false)
        val entries = directory.listFiles()
            ?: throw IllegalStateException("cannot enumerate sealed room-store directory")
        val roomIds = entries.mapNotNull { legacyRoomId(it.name) }.toSortedSet()
        var migrated = 0
        var firstFailure: Exception? = null
        for (roomId in roomIds) {
            try {
                resolveFile(directory, roomId, domain, dataKey)
                migrated += 1
            } catch (failure: Exception) {
                if (firstFailure == null) firstFailure = failure
            }
        }
        firstFailure?.let { throw it }
        return migrated
    }

    @Synchronized
    fun resolveFile(
        directory: File,
        roomId: String,
        domain: SyncIdentity.LocalStoreDomain,
        dataKey: ByteArray,
    ): File {
        requireSafeStoreDirectory(directory, create = true)
        val targetName = SyncIdentity.localStoreFileName(dataKey, roomId, domain)
            ?: throw IllegalArgumentException("invalid sealed room-store identity")
        val target = File(directory, targetName)
        requireSafeChild(directory, target)
        rejectSymbolicLink(target)
        val legacyName = safeLegacyFileName(roomId) ?: return target
        val legacy = File(directory, legacyName)
        val legacyMarker = sealedOnlyMarker(legacy)
        val targetMarker = sealedOnlyMarker(target)
        val legacyMarkerTmp = File(legacyMarker.parentFile, legacyMarker.name + ".tmp")
        listOf(legacy, legacyMarker, legacyMarkerTmp, targetMarker).forEach {
            requireSafeChild(directory, it)
            rejectSymbolicLink(it)
        }

        var adoptedBase = target

        if (legacy.exists()) {
            if (target.exists()) {
                // Marker-first makes interruption recoverable: on retry the
                // marker-only conflict slot is reused for the still-legacy data.
                val conflict = pendingConflictBase(directory, target)
                    ?: File(directory, "${target.name}.legacy-conflict-${UUID.randomUUID()}")
                requireSafeChild(directory, conflict)
                rejectSymbolicLink(conflict)
                if (legacyMarker.exists()) {
                    moveNoReplace(legacyMarker, sealedOnlyMarker(conflict))
                }
                if (legacyMarkerTmp.exists()) {
                    val conflictMarker = sealedOnlyMarker(conflict)
                    moveNoReplace(
                        legacyMarkerTmp,
                        File(conflictMarker.parentFile, conflictMarker.name + ".tmp"),
                    )
                }
                moveNoReplace(legacy, conflict)
                adoptedBase = conflict
            } else {
                moveNoReplace(legacy, target)
            }
        }

        // Retry marker adoption independently after an interruption between
        // the data and marker renames. Older builds moved data first, so prefer
        // the unique unmarked conflict copy instead of misbinding it to current.
        if (legacyMarker.exists()) {
            val recoveryBase = uniqueConflictDataWithoutMarker(directory, target)
            val destinationMarker = recoveryBase?.let(::sealedOnlyMarker) ?: targetMarker
            adoptedBase = recoveryBase ?: adoptedBase
            rejectSymbolicLink(destinationMarker)
            if (destinationMarker.exists()) {
                if (!legacyMarker.delete()) {
                    throw IllegalStateException("cannot remove adopted sealed-store marker")
                }
            } else {
                moveNoReplace(legacyMarker, destinationMarker)
            }
        }
        if (adoptedBase == target && !legacy.exists() &&
            (legacyMarkerTmp.exists() || hasLegacyCompanions(directory, legacyName))
        ) {
            adoptedBase = uniqueConflictData(directory, target) ?: target
        }
        if (legacyMarkerTmp.exists()) {
            val adoptedMarker = sealedOnlyMarker(adoptedBase)
            var targetMarkerTmp = File(adoptedMarker.parentFile, adoptedMarker.name + ".tmp")
            rejectSymbolicLink(targetMarkerTmp)
            if (targetMarkerTmp.exists()) {
                targetMarkerTmp = File(
                    directory,
                    "${targetMarkerTmp.name}.legacy-conflict-${UUID.randomUUID()}",
                )
            }
            moveNoReplace(legacyMarkerTmp, targetMarkerTmp)
        }
        adoptLegacyCompanions(directory, legacyName, adoptedBase.name)
        return target
    }

    private fun adoptLegacyCompanions(directory: File, legacyName: String, targetName: String) {
        for (source in directory.listFiles().orEmpty()) {
            val sourceSuffix = source.name.removePrefix(legacyName)
            val suffix = when {
                source.name.startsWith(legacyName) && isCorruptCompanionSuffix(sourceSuffix) ->
                    sourceSuffix
                source.name == "$legacyName.tmp" -> ".tmp"
                else -> continue
            }
            requireSafeChild(directory, source)
            rejectSymbolicLink(source)
            var destination = File(directory, targetName + suffix)
            requireSafeChild(directory, destination)
            rejectSymbolicLink(destination)
            if (destination.exists()) {
                destination = File(
                    directory,
                    "${destination.name}.legacy-conflict-${UUID.randomUUID()}",
                )
            }
            moveNoReplace(source, destination)
        }
    }

    private fun hasLegacyCompanions(directory: File, legacyName: String): Boolean =
        directory.listFiles().orEmpty().any { source ->
            val suffix = source.name.removePrefix(legacyName)
            source.name == "$legacyName.tmp" ||
                (source.name.startsWith(legacyName) && isCorruptCompanionSuffix(suffix))
        }

    /** NIO's ATOMIC_MOVE is allowed to ignore no-replace semantics. The default
     * same-directory move is atomic on Android's filesystem and must fail if
     * [destination] appears concurrently. */
    internal fun moveNoReplace(source: File, destination: File) {
        Files.move(source.toPath(), destination.toPath())
    }

    private fun requireSafeStoreDirectory(directory: File, create: Boolean) {
        val path = directory.toPath()
        if (Files.isSymbolicLink(path)) {
            throw IllegalStateException("sealed room-store directory cannot be a symbolic link")
        }
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (!create || !directory.mkdirs()) {
                throw IllegalStateException("cannot create sealed room-store directory")
            }
        }
        if (Files.isSymbolicLink(path) ||
            !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
        ) {
            throw IllegalStateException("sealed room-store path is not a safe directory")
        }
    }

    private fun requireSafeChild(directory: File, child: File) {
        if (child.absoluteFile.parentFile?.canonicalFile != directory.canonicalFile) {
            throw IllegalStateException("sealed room-store path escaped its directory")
        }
    }

    private fun rejectSymbolicLink(file: File) {
        if (Files.isSymbolicLink(file.toPath())) {
            throw IllegalStateException("sealed room-store entries cannot be symbolic links")
        }
        if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
        ) {
            throw IllegalStateException("sealed room-store entries must be regular files")
        }
    }

    private fun pendingConflictBase(directory: File, target: File): File? {
        val candidates = directory.listFiles().orEmpty().mapNotNull { entry ->
            val baseName = conflictBaseNameFromMarker(entry.name, target.name)
                ?: return@mapNotNull null
            requireSafeChild(directory, entry)
            rejectSymbolicLink(entry)
            val base = File(directory, baseName)
            if (!base.exists()) base else null
        }.distinctBy { it.name }
        candidates.forEach {
            requireSafeChild(directory, it)
            rejectSymbolicLink(sealedOnlyMarker(it))
        }
        if (candidates.size > 1) {
            throw IllegalStateException("ambiguous interrupted sealed-store conflict")
        }
        return candidates.singleOrNull()
    }

    private fun uniqueConflictDataWithoutMarker(directory: File, target: File): File? {
        val candidates = conflictData(directory, target).filter { entry ->
            !sealedOnlyMarker(entry).exists()
        }
        if (candidates.size > 1) {
            throw IllegalStateException("ambiguous interrupted sealed-store conflict")
        }
        return candidates.singleOrNull()
    }

    private fun uniqueConflictData(directory: File, target: File): File? {
        val candidates = conflictData(directory, target)
        if (candidates.size > 1) {
            throw IllegalStateException("ambiguous interrupted sealed-store companion")
        }
        return candidates.singleOrNull()
    }

    private fun conflictData(directory: File, target: File): List<File> =
        directory.listFiles().orEmpty().filter { entry ->
            isConflictDataName(entry.name, target.name)
        }.onEach {
            requireSafeChild(directory, it)
            rejectSymbolicLink(it)
        }

    private fun conflictBaseNameFromMarker(name: String, targetName: String): String? {
        val markerSuffix = ".sealed-only-v1"
        val normalized = name.removeSuffix(".tmp")
        if (!normalized.startsWith(".$targetName.legacy-conflict-") ||
            !normalized.endsWith(markerSuffix)
        ) return null
        val base = normalized.removePrefix(".").removeSuffix(markerSuffix)
        return base.takeIf { isConflictDataName(it, targetName) }
    }

    private fun isConflictDataName(name: String, targetName: String): Boolean {
        val prefix = "$targetName.legacy-conflict-"
        if (!name.startsWith(prefix)) return false
        return runCatching { UUID.fromString(name.removePrefix(prefix)) }.isSuccess
    }

    private fun sealedOnlyMarker(file: File): File =
        File(file.parentFile, ".${file.name}.sealed-only-v1")

    private fun safeLegacyFileName(roomId: String): String? {
        if (roomId.isEmpty() || roomId == "." || roomId == ".." ||
            '/' in roomId || '\\' in roomId || '\u0000' in roomId ||
            roomId.toByteArray(Charsets.UTF_8).size > 240
        ) return null
        return "$roomId.json"
    }

    private fun legacyRoomId(entryName: String): String? {
        val normalized = if (entryName.startsWith(".") &&
            (entryName.endsWith(".json.sealed-only-v1") ||
                entryName.endsWith(".json.sealed-only-v1.tmp"))
        ) {
            entryName.removePrefix(".")
                .removeSuffix(".sealed-only-v1.tmp")
                .removeSuffix(".sealed-only-v1")
        } else {
            val boundary = entryName.indexOf(".json")
            if (boundary < 0) return null
            val suffix = entryName.substring(boundary + ".json".length)
            if (suffix.isNotEmpty() && suffix != ".tmp" &&
                !isCorruptCompanionSuffix(suffix)
            ) {
                return null
            }
            entryName.substring(0, boundary + ".json".length)
        }
        if (!normalized.endsWith(".json")) return null
        val roomId = normalized.removeSuffix(".json")
        return roomId.takeIf { SyncIdentity.urlB64Decode32(it) != null }
    }

    private fun isCorruptCompanionSuffix(suffix: String): Boolean {
        val timestamp = suffix.removePrefix(".corrupt-")
        return suffix.startsWith(".corrupt-") && timestamp.isNotEmpty() &&
            timestamp.all { it in '0'..'9' }
    }
}
