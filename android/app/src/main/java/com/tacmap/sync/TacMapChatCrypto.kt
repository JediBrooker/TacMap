package com.tacmap.sync

import kotlinx.serialization.encodeToString
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer as NioByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal class TacMapChatEphemeralKey private constructor(
    private val privateKeyRaw: ByteArray,
    val publicKeyRaw: ByteArray,
) {
    val isCleared: Boolean get() = privateKeyRaw.all { it == 0.toByte() }

    fun deriveSharedSecret(peerPublicKeyRaw: ByteArray): ByteArray? {
        if (isCleared || peerPublicKeyRaw.size != 32 || peerPublicKeyRaw.all { it == 0.toByte() }) {
            return null
        }
        return runCatching {
            val agreement = X25519Agreement()
            agreement.init(X25519PrivateKeyParameters(privateKeyRaw, 0))
            val shared = ByteArray(agreement.agreementSize)
            agreement.calculateAgreement(X25519PublicKeyParameters(peerPublicKeyRaw, 0), shared, 0)
            shared.takeUnless { it.all { byte -> byte == 0.toByte() } }
                ?: run { shared.fill(0); null }
        }.getOrNull()
    }

    fun clear() {
        privateKeyRaw.fill(0)
        publicKeyRaw.fill(0)
    }

    companion object {
        fun generate(random: SecureRandom = SecureRandom()): TacMapChatEphemeralKey {
            val privateKey = X25519PrivateKeyParameters(random)
            return TacMapChatEphemeralKey(
                privateKeyRaw = privateKey.encoded,
                publicKeyRaw = privateKey.generatePublicKey().encoded,
            )
        }

        internal fun fromPrivateForTests(privateKeyRaw: ByteArray): TacMapChatEphemeralKey {
            require(privateKeyRaw.size == 32)
            val privateKey = X25519PrivateKeyParameters(privateKeyRaw, 0)
            return TacMapChatEphemeralKey(
                privateKeyRaw = privateKey.encoded,
                publicKeyRaw = privateKey.generatePublicKey().encoded,
            )
        }
    }
}

internal data class TacMapChatHeaderFields(
    val scope: TacMapChatScope,
    val roomIdRaw: ByteArray,
    val senderActorId: String,
    val senderSessionDomainRaw: ByteArray,
    val counterHex: String,
    val messageId: String,
    val senderChatKeyId: String,
    val recipientActorId: String? = null,
    val recipientSessionDomain: String? = null,
    val recipientChatKeyId: String? = null,
)

@OptIn(ExperimentalSerializationApi::class)
internal object TacMapChatPayloadCodec {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
    }
    private val requiredKeys = setOf("pv", "kind", "body", "createdAt")

    fun encode(payload: TacMapChatPayload): ByteArray? {
        if (!payload.isValid) return null
        val encoded = runCatching { json.encodeToString(payload).toByteArray(Charsets.UTF_8) }
            .getOrNull() ?: return null
        return encoded.takeIf { it.size <= TacMapChatPayload.MAX_PLAINTEXT_BYTES }
    }

    fun decode(plaintext: ByteArray): TacMapChatPayload? {
        if (plaintext.isEmpty() || plaintext.size > TacMapChatPayload.MAX_PLAINTEXT_BYTES) return null
        return runCatching {
            val decodedText = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(NioByteBuffer.wrap(plaintext))
                .toString()
            val lexicalKeys = topLevelLiteralKeys(decodedText) ?: return null
            if (lexicalKeys.size != lexicalKeys.toSet().size) return null
            val element = json.parseToJsonElement(decodedText) as? JsonObject
                ?: return null
            val keys = element.keys
            if (lexicalKeys.toSet() != keys) return null
            if (!keys.containsAll(requiredKeys) ||
                keys.any { it !in requiredKeys && it != "replyTo" }
            ) return null
            json.decodeFromJsonElement(TacMapChatPayload.serializer(), element)
                .takeIf(TacMapChatPayload::isValid)
        }.getOrNull()
    }

    /** Extract root-object keys while respecting strings/nesting; escaped keys are noncanonical. */
    private fun topLevelLiteralKeys(value: String): List<String>? {
        var index = 0
        while (index < value.length && value[index].isWhitespace()) index++
        if (index >= value.length || value[index] != '{') return null
        index++
        var depth = 1
        var expectingKey = true
        val keys = mutableListOf<String>()
        while (index < value.length && depth > 0) {
            val char = value[index]
            if (depth == 1 && expectingKey) {
                if (char.isWhitespace() || char == ',') {
                    index++
                    continue
                }
                if (char == '}') {
                    depth = 0
                    index++
                    break
                }
                if (char != '"') return null
                val start = ++index
                while (index < value.length && value[index] != '"') {
                    // Property-name escapes are legal JSON but deliberately not
                    // canonical for this security-sensitive payload schema.
                    if (value[index] == '\\' || value[index].code < 0x20) return null
                    index++
                }
                if (index >= value.length) return null
                keys += value.substring(start, index)
                index++
                while (index < value.length && value[index].isWhitespace()) index++
                if (index >= value.length || value[index] != ':') return null
                expectingKey = false
                index++
                continue
            }
            when (char) {
                '"' -> {
                    index++
                    var escaped = false
                    while (index < value.length) {
                        val current = value[index++]
                        if (escaped) escaped = false
                        else if (current == '\\') escaped = true
                        else if (current == '"') break
                    }
                }
                '{', '[' -> { depth++; index++ }
                '}' -> { depth--; index++ }
                ']' -> { depth--; index++ }
                ',' -> {
                    if (depth == 1) expectingKey = true
                    index++
                }
                else -> index++
            }
        }
        if (depth != 0) return null
        while (index < value.length && value[index].isWhitespace()) index++
        return keys.takeIf { index == value.length }
    }
}

/** Byte-exact TacMap Chat v1 cryptography shared with iOS and the relay. */
internal object TacMapChatCrypto {
    private val KID_PREFIX = "tacmap-chat-kid-v1\u0000".toByteArray(Charsets.UTF_8)
    private val ROOM_KEY_LABEL = "tacmap-chat-room-v1".toByteArray(Charsets.UTF_8)
    private val DIRECT_SALT_PREFIX = "tacmap-chat-direct-salt-v1\u0000".toByteArray(Charsets.UTF_8)
    private val DIRECT_INFO_PREFIX = "tacmap-chat-direct-info-v1\u0000".toByteArray(Charsets.UTF_8)
    private val AAD_PREFIX = "tacmap-chat-aad-v1\u0000".toByteArray(Charsets.UTF_8)
    private val ZERO_32 = ByteArray(32)
    private const val CHAT_VERSION: Byte = 0x01
    private const val PROTOCOL_VERSION: Byte = 0x03
    private const val CHAT_KEY_DOMAIN: Byte = 0x05
    private const val CHAT_MESSAGE_DOMAIN: Byte = 0x06
    private const val ZERO_COUNTER = "0000000000000000"
    const val MAX_CIPHERTEXT_BASE64_CHARS = 16_384

    fun chatKeyId(
        roomIdRaw: ByteArray,
        actorId: String,
        sessionDomainRaw: ByteArray,
        keyExchangeRaw: ByteArray,
    ): String {
        require(roomIdRaw.size == 32 && sessionDomainRaw.size == 32 && keyExchangeRaw.size == 32)
        require(TacMapChatIds.isCanonical32(actorId))
        val actor = actorId.toByteArray(Charsets.UTF_8)
        val bytes = ByteArrayOutputStream().apply {
            write(KID_PREFIX)
            write(roomIdRaw)
            writeLe16(actor.size)
            write(actor)
            write(sessionDomainRaw)
            write(keyExchangeRaw)
        }.toByteArray()
        return SyncIdentity.urlB64(SyncIdentity.sha256(bytes))
    }

    fun chatKeyPreimage(
        roomIdRaw: ByteArray,
        actorId: String,
        sessionDomainRaw: ByteArray,
        keyExchangeRaw: ByteArray,
        chatKeyId: String,
    ): ByteArray {
        require(roomIdRaw.size == 32 && sessionDomainRaw.size == 32 && keyExchangeRaw.size == 32)
        val keyIdRaw = TacMapChatIds.canonicalBytes(chatKeyId, 32)
            ?: throw IllegalArgumentException("invalid chat key id")
        require(chatKeyId(roomIdRaw, actorId, sessionDomainRaw, keyExchangeRaw) == chatKeyId)
        val actor = actorId.toByteArray(Charsets.UTF_8)
        val kind = "chat-key-v1".toByteArray(Charsets.UTF_8)
        val payloadHash = SyncIdentity.sha256(byteArrayOf(CHAT_VERSION) + keyExchangeRaw + keyIdRaw)
        return ByteArrayOutputStream().apply {
            write(byteArrayOf(CHAT_KEY_DOMAIN, PROTOCOL_VERSION))
            write(roomIdRaw)
            writeLe16(actor.size)
            write(actor)
            write(sessionDomainRaw)
            write(ZERO_COUNTER.toByteArray(Charsets.US_ASCII))
            writeLe16(0)
            write(kind.size)
            write(kind)
            write(payloadHash)
        }.toByteArray()
    }

    /** Canonical header H; the AAD is a domain prefix followed by these bytes. */
    fun header(fields: TacMapChatHeaderFields): ByteArray {
        require(fields.roomIdRaw.size == 32)
        require(TacMapChatIds.isCanonical32(fields.senderActorId))
        require(fields.senderSessionDomainRaw.size == 32)
        require(fields.counterHex.matches(Regex("^[0-7][0-9a-f]{15}$")) &&
            fields.counterHex != ZERO_COUNTER)
        val messageIdRaw = TacMapChatIds.canonicalBytes(fields.messageId, 16)
            ?: throw IllegalArgumentException("invalid message id")
        val fromKidRaw = TacMapChatIds.canonicalBytes(fields.senderChatKeyId, 32)
            ?: throw IllegalArgumentException("invalid sender chat key id")
        val sender = fields.senderActorId.toByteArray(Charsets.UTF_8)

        val recipient: ByteArray
        val recipientSession: ByteArray
        val recipientKid: ByteArray
        when (fields.scope) {
            TacMapChatScope.ROOM -> {
                require(fields.recipientActorId == null && fields.recipientSessionDomain == null &&
                    fields.recipientChatKeyId == null)
                recipient = ByteArray(0)
                recipientSession = ZERO_32
                recipientKid = ZERO_32
            }
            TacMapChatScope.DIRECT -> {
                val recipientActor = fields.recipientActorId
                    ?: throw IllegalArgumentException("direct recipient missing")
                require(recipientActor != fields.senderActorId && TacMapChatIds.isCanonical32(recipientActor))
                recipient = recipientActor.toByteArray(Charsets.UTF_8)
                recipientSession = TacMapChatIds.canonicalBytes(
                    fields.recipientSessionDomain ?: "", 32
                ) ?: throw IllegalArgumentException("invalid recipient session")
                recipientKid = TacMapChatIds.canonicalBytes(
                    fields.recipientChatKeyId ?: "", 32
                ) ?: throw IllegalArgumentException("invalid recipient chat key id")
            }
        }

        return ByteArrayOutputStream().apply {
            write(CHAT_VERSION.toInt())
            write(if (fields.scope == TacMapChatScope.ROOM) 0x01 else 0x02)
            write(fields.roomIdRaw)
            writeLe16(sender.size)
            write(sender)
            write(fields.senderSessionDomainRaw)
            write(fields.counterHex.toByteArray(Charsets.US_ASCII))
            write(messageIdRaw)
            write(fromKidRaw)
            writeLe16(recipient.size)
            write(recipient)
            write(recipientSession)
            write(recipientKid)
        }.toByteArray()
    }

    fun aad(header: ByteArray): ByteArray = AAD_PREFIX + header

    fun signaturePreimage(header: ByteArray, sealed: ByteArray): ByteArray =
        byteArrayOf(CHAT_MESSAGE_DOMAIN, PROTOCOL_VERSION) + header + SyncIdentity.sha256(sealed)

    fun roomChatKey(roomKey: ByteArray): ByteArray {
        require(roomKey.size == 32)
        return hmacSha256(roomKey, ROOM_KEY_LABEL)
    }

    fun directChatKey(
        ephemeral: TacMapChatEphemeralKey,
        peerPublicKeyRaw: ByteArray,
        roomIdRaw: ByteArray,
        header: ByteArray,
    ): ByteArray? {
        if (roomIdRaw.size != 32) return null
        val shared = ephemeral.deriveSharedSecret(peerPublicKeyRaw) ?: return null
        val salt = SyncIdentity.sha256(DIRECT_SALT_PREFIX + roomIdRaw)
        val info = SyncIdentity.sha256(DIRECT_INFO_PREFIX + header)
        return try {
            hkdfSha256(shared, salt, info)
        } finally {
            shared.fill(0)
            salt.fill(0)
            info.fill(0)
        }
    }

    fun seal(key: ByteArray, plaintext: ByteArray, header: ByteArray): ByteArray? {
        if (key.size != 32 || plaintext.isEmpty() || plaintext.size > TacMapChatPayload.MAX_PLAINTEXT_BYTES) {
            return null
        }
        return SyncCrypto.seal(key, plaintext, aad(header))
    }

    fun open(key: ByteArray, sealed: ByteArray, header: ByteArray): ByteArray? {
        if (key.size != 32 || sealed.size < 28) return null
        return SyncCrypto.open(key, sealed, aad(header))
            ?.takeIf { it.size <= TacMapChatPayload.MAX_PLAINTEXT_BYTES }
    }

    fun verifyThenOpen(
        senderSigningPublicKey: String,
        signature: String,
        key: ByteArray,
        sealed: ByteArray,
        header: ByteArray,
    ): ByteArray? {
        val preimage = signaturePreimage(header, sealed)
        // Authenticate routing and ciphertext before attempting decryption.
        if (!SyncSigning.verify(senderSigningPublicKey, preimage, signature)) return null
        return open(key, sealed, header)
    }

    fun fingerprint(header: ByteArray, sealed: ByteArray, signature: String): String? {
        val sig = TacMapChatIds.canonicalBytes(signature, 64) ?: return null
        return SyncIdentity.urlB64(SyncIdentity.sha256(signaturePreimage(header, sealed) + sig))
    }

    fun encodeStandardBase64(sealed: ByteArray): String = Base64.getEncoder().encodeToString(sealed)

    fun decodeStandardBase64(value: String): ByteArray? {
        if (value.isEmpty() || value.length > MAX_CIPHERTEXT_BASE64_CHARS || value.length % 4 != 0 ||
            !value.matches(Regex("^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$"))
        ) return null
        val decoded = runCatching { Base64.getDecoder().decode(value) }.getOrNull() ?: return null
        return decoded.takeIf { it.size >= 28 && encodeStandardBase64(it) == value }
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** RFC 5869; output length is exactly one SHA-256 block. */
    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
        val prk = hmacSha256(salt, ikm)
        return try {
            hmacSha256(prk, info + byteArrayOf(0x01))
        } finally {
            prk.fill(0)
        }
    }

    private fun ByteArrayOutputStream.writeLe16(value: Int) {
        require(value in 0..0xffff)
        write(value and 0xff)
        write((value ushr 8) and 0xff)
    }
}
