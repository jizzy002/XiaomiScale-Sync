package com.xiaomiscalesync.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HKDF-SHA256 (RFC 5869), implemented exactly as the reference client:
 * extract `prk = HMAC-SHA256(salt, ikm)`, then expand
 * `t(i) = HMAC-SHA256(prk, t(i-1) || info || i)` — output is the first
 * [length] bytes of the concatenated blocks (so `length > 32` spans
 * multiple blocks).
 */
object Hkdf {

    private const val HASH_BYTES = 32
    private const val MAX_LENGTH_BYTES = 255 * HASH_BYTES

    fun sha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length > 0) { "HKDF output length must be positive, was $length" }
        require(length <= MAX_LENGTH_BYTES) {
            "HKDF output length must be <= $MAX_LENGTH_BYTES, was $length"
        }

        // RFC 5869 2.2: a zero-length salt is a string of HashLen zeros.
        val extractSalt = if (salt.isEmpty()) ByteArray(HASH_BYTES) else salt
        val prk = hmac(extractSalt, ikm)

        // RFC 5869 2.3: T(i) = HMAC(PRK, T(i-1) || info || i).
        val blocks = (length + HASH_BYTES - 1) / HASH_BYTES
        val out = ByteArray(blocks * HASH_BYTES)
        var previous = ByteArray(0)
        for (i in 1..blocks) {
            val block = hmac(prk, previous + info + byteArrayOf(i.toByte()))
            block.copyInto(out, (i - 1) * HASH_BYTES)
            previous = block
        }
        return if (length == out.size) out else out.copyOf(length)
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}
