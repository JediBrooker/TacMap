package com.tacmap.util

/**
 * Authenticated plaintext carried inside the Keystore-wrapped DEK blob.
 * Version 1 was exactly 32 raw DEK bytes and may create the sentinel once.
 * Version 2 carries an authenticated magic prefix, so mutable preferences
 * cannot turn sentinel deletion back into a legacy migration.
 */
internal object DataKeyPayload {
    private val V2_MAGIC = byteArrayOf(0x54, 0x4D, 0x44, 0x4B, 0x02) // TMDK + v2
    private const val DEK_BYTES = 32

    data class Decoded(val dek: ByteArray, val sentinelRequired: Boolean)

    fun encodeV2(dek: ByteArray): ByteArray {
        require(dek.size == DEK_BYTES)
        return V2_MAGIC + dek
    }

    fun decode(plain: ByteArray): Decoded? = when {
        plain.size == DEK_BYTES -> Decoded(plain.copyOf(), sentinelRequired = false)
        plain.size == V2_MAGIC.size + DEK_BYTES &&
            V2_MAGIC.indices.all { plain[it] == V2_MAGIC[it] } ->
            Decoded(plain.copyOfRange(V2_MAGIC.size, plain.size), sentinelRequired = true)
        else -> null
    }
}
