package com.tacmap.sync

import org.json.JSONObject

internal data class TacMapChatKeyFrame(
    val actorId: String,
    val sessionDomain: String,
    val keyExchange: String,
    val chatKeyId: String,
    val signature: String,
)

internal data class TacMapChatWireFrame(
    val scope: TacMapChatScope,
    val actorId: String,
    val sessionDomain: String,
    val versionStamp: String,
    val counterHex: String,
    val messageId: String,
    val fromChatKeyId: String,
    val ciphertext: String,
    val sealedRaw: ByteArray,
    val signature: String,
    val recipientActorId: String? = null,
    val recipientSessionDomain: String? = null,
    val recipientChatKeyId: String? = null,
) {
    fun headerFields(roomIdRaw: ByteArray): TacMapChatHeaderFields = TacMapChatHeaderFields(
        scope = scope,
        roomIdRaw = roomIdRaw,
        senderActorId = actorId,
        senderSessionDomainRaw = TacMapChatIds.canonicalBytes(sessionDomain, 32)!!,
        counterHex = counterHex,
        messageId = messageId,
        senderChatKeyId = fromChatKeyId,
        recipientActorId = recipientActorId,
        recipientSessionDomain = recipientSessionDomain,
        recipientChatKeyId = recipientChatKeyId,
    )
}

internal data class TacMapChatAck(
    val scope: TacMapChatScope,
    val actorId: String,
    val sessionDomain: String,
    val versionStamp: String,
    val messageId: String,
    val fromChatKeyId: String,
    val recipientActorId: String? = null,
    val recipientSessionDomain: String? = null,
    val recipientChatKeyId: String? = null,
)

internal data class TacMapChatNack(
    val actorId: String,
    val sessionDomain: String,
    val messageId: String,
    val code: String,
    val retry: Boolean,
)

/** Strict hostile-wire parsers: unknown, missing, mistyped, or noncanonical fields are rejected. */
internal object TacMapChatWire {
    private val keyFrameKeys = setOf("t", "cv", "by", "sd", "kx", "kid", "sig")
    private val roomFrameKeys = setOf(
        "t", "cv", "scope", "by", "sd", "vs", "mid", "fromKid", "ct", "sig"
    )
    private val directFrameKeys = roomFrameKeys + setOf("to", "toSd", "toKid")

    fun parseKeyFrame(value: JSONObject): TacMapChatKeyFrame? {
        if (!value.hasExactly(keyFrameKeys) || value.strictString("t") != "chat-key" ||
            value.strictInt("cv") != 1
        ) return null
        val actor = value.canonical32("by") ?: return null
        val session = value.canonical32("sd") ?: return null
        val kx = value.canonical32("kx") ?: return null
        if (TacMapChatIds.canonicalBytes(kx, 32)?.all { it == 0.toByte() } != false) return null
        return TacMapChatKeyFrame(
            actorId = actor,
            sessionDomain = session,
            keyExchange = kx,
            chatKeyId = value.canonical32("kid") ?: return null,
            signature = value.canonical64("sig") ?: return null,
        )
    }

    fun parseFrame(value: JSONObject): TacMapChatWireFrame? {
        if (value.strictString("t") != "chat" || value.strictInt("cv") != 1) return null
        val scope = when (value.strictString("scope")) {
            "room" -> TacMapChatScope.ROOM
            "direct" -> TacMapChatScope.DIRECT
            else -> return null
        }
        if (!value.hasExactly(if (scope == TacMapChatScope.ROOM) roomFrameKeys else directFrameKeys)) {
            return null
        }
        val actor = value.canonical32("by") ?: return null
        val session = value.canonical32("sd") ?: return null
        val stamp = value.strictString("vs") ?: return null
        val counter = parseCounter(stamp, actor) ?: return null
        val messageId = value.strictString("mid")?.takeIf(TacMapChatIds::isCanonicalMessageId)
            ?: return null
        val ciphertext = value.strictString("ct") ?: return null
        val sealed = TacMapChatCrypto.decodeStandardBase64(ciphertext) ?: return null
        val recipientActor: String?
        val recipientSession: String?
        val recipientKid: String?
        if (scope == TacMapChatScope.DIRECT) {
            recipientActor = value.canonical32("to")?.takeIf { it != actor } ?: return null
            recipientSession = value.canonical32("toSd") ?: return null
            recipientKid = value.canonical32("toKid") ?: return null
        } else {
            recipientActor = null
            recipientSession = null
            recipientKid = null
        }
        return TacMapChatWireFrame(
            scope = scope,
            actorId = actor,
            sessionDomain = session,
            versionStamp = stamp,
            counterHex = counter,
            messageId = messageId,
            fromChatKeyId = value.canonical32("fromKid") ?: return null,
            ciphertext = ciphertext,
            sealedRaw = sealed,
            signature = value.canonical64("sig") ?: return null,
            recipientActorId = recipientActor,
            recipientSessionDomain = recipientSession,
            recipientChatKeyId = recipientKid,
        )
    }

    fun parseKeyAck(value: JSONObject): Triple<String, String, String>? {
        if (!value.hasExactly(setOf("t", "cv", "by", "sd", "kid")) ||
            value.strictString("t") != "chat-key-ack" || value.strictInt("cv") != 1
        ) return null
        return Triple(
            value.canonical32("by") ?: return null,
            value.canonical32("sd") ?: return null,
            value.canonical32("kid") ?: return null,
        )
    }

    fun parseAck(value: JSONObject): TacMapChatAck? {
        if (value.strictString("t") != "chat-ack" || value.strictInt("cv") != 1) return null
        val scope = when (value.strictString("scope")) {
            "room" -> TacMapChatScope.ROOM
            "direct" -> TacMapChatScope.DIRECT
            else -> return null
        }
        val expected = if (scope == TacMapChatScope.ROOM) {
            setOf("t", "cv", "by", "sd", "vs", "mid", "scope", "fromKid")
        } else {
            setOf(
                "t", "cv", "by", "sd", "vs", "mid", "scope", "fromKid",
                "to", "toSd", "toKid"
            )
        }
        if (!value.hasExactly(expected)) return null
        val actor = value.canonical32("by") ?: return null
        val stamp = value.strictString("vs") ?: return null
        if (parseCounter(stamp, actor) == null) return null
        val recipientActor = if (scope == TacMapChatScope.DIRECT) {
            value.canonical32("to")?.takeIf { it != actor } ?: return null
        } else null
        return TacMapChatAck(
            scope = scope,
            actorId = actor,
            sessionDomain = value.canonical32("sd") ?: return null,
            versionStamp = stamp,
            messageId = value.strictString("mid")?.takeIf(TacMapChatIds::isCanonicalMessageId)
                ?: return null,
            fromChatKeyId = value.canonical32("fromKid") ?: return null,
            recipientActorId = recipientActor,
            recipientSessionDomain = if (scope == TacMapChatScope.DIRECT) {
                value.canonical32("toSd") ?: return null
            } else null,
            recipientChatKeyId = if (scope == TacMapChatScope.DIRECT) {
                value.canonical32("toKid") ?: return null
            } else null,
        )
    }

    fun parseNack(value: JSONObject): TacMapChatNack? {
        val expected = setOf("t", "cv", "by", "sd", "mid", "code", "retry")
        if (!value.hasExactly(expected) || value.strictString("t") != "chat-nack" ||
            value.strictInt("cv") != 1
        ) return null
        val code = value.strictString("code")
            ?.takeIf { it.matches(Regex("^[a-z0-9_]{1,64}$")) } ?: return null
        return TacMapChatNack(
            actorId = value.canonical32("by") ?: return null,
            sessionDomain = value.canonical32("sd") ?: return null,
            messageId = value.strictString("mid")?.takeIf(TacMapChatIds::isCanonicalMessageId)
                ?: return null,
            code = code,
            retry = value.strictBoolean("retry") ?: return null,
        )
    }

    fun parseKeyNack(value: JSONObject): Triple<String, String, String>? {
        if (!value.hasExactly(setOf("t", "cv", "by", "sd", "code")) ||
            value.strictString("t") != "chat-key-nack" || value.strictInt("cv") != 1
        ) return null
        val code = value.strictString("code")
            ?.takeIf { it.matches(Regex("^[a-z0-9_]{1,64}$")) } ?: return null
        return Triple(
            value.canonical32("by") ?: return null,
            value.canonical32("sd") ?: return null,
            code,
        )
    }

    private fun parseCounter(stamp: String, actorId: String): String? {
        if (stamp.length != 60 || stamp[16] != ':' || stamp.substring(17) != actorId) return null
        return stamp.substring(0, 16).takeIf {
            it.matches(Regex("^[0-7][0-9a-f]{15}$")) && it != "0000000000000000"
        }
    }

    private fun JSONObject.hasExactly(expected: Set<String>): Boolean =
        keys().asSequence().toSet() == expected

    private fun JSONObject.strictString(key: String): String? = opt(key) as? String

    private fun JSONObject.strictInt(key: String): Int? = when (val value = opt(key)) {
        is Int -> value
        is Long -> value.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
        else -> null
    }

    private fun JSONObject.strictBoolean(key: String): Boolean? = opt(key) as? Boolean

    private fun JSONObject.canonical32(key: String): String? =
        strictString(key)?.takeIf(TacMapChatIds::isCanonical32)

    private fun JSONObject.canonical64(key: String): String? =
        strictString(key)?.takeIf { TacMapChatIds.canonicalBytes(it, 64) != null }
}
