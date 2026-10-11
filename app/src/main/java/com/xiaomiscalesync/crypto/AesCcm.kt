package com.xiaomiscalesync.crypto

import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-CCM (RFC 3610) with a tag length fixed at 4 bytes, implemented over
 * JCA `AES/CTR` and `AES/CBC` (no padding) because Android's platform crypto
 * does not reliably provide `AES/CCM` (provider-dependent).
 *
 * - [encrypt] returns `ciphertext || tag` (tag = last 4 bytes).
 * - [decrypt] accepts `ciphertext || tag` and verifies the tag before
 *   returning; on any authentication failure it throws [AuthenticationMismatch]
 *   and never returns plaintext.
 *
 * Nonce: 7..13 bytes (RFC 3610); `L = 15 - nonce.size` counter bytes.
 */
object AesCcm {

    /** Tag length in bytes; fixed at 4 by the Xiaomi protocol. */
    const val TAG_LENGTH_BYTES = 4

    private const val MIN_NONCE_BYTES = 7
    private const val MAX_NONCE_BYTES = 13
    private const val BLOCK_BYTES = 16

    /** Returns `ciphertext || tag(4 bytes)`. */
    fun encrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray?, plaintext: ByteArray): ByteArray {
        val L = validate(key, nonce)
        requirePlaintextLength(plaintext.size, nonce.size, L)
        val tag = computeTag(key, nonce, L, aad, plaintext)
        val ciphertext = ctr(key, nonce, L, startCounter = 1L, input = plaintext)
        return ciphertext + tag
    }

    /** Verifies the trailing 4-byte tag and returns the plaintext. */
    fun decrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray?, data: ByteArray): ByteArray {
        val L = validate(key, nonce)
        if (data.size < TAG_LENGTH_BYTES) {
            throw AuthenticationMismatch(
                "input of ${data.size} bytes cannot hold a $TAG_LENGTH_BYTES-byte tag"
            )
        }
        val ciphertext = data.copyOfRange(0, data.size - TAG_LENGTH_BYTES)
        val receivedTag = data.copyOfRange(data.size - TAG_LENGTH_BYTES, data.size)
        requirePlaintextLength(ciphertext.size, nonce.size, L)

        // CTR is its own inverse; the plaintext is only released after the tag
        // check below succeeds.
        val plaintext = ctr(key, nonce, L, startCounter = 1L, input = ciphertext)
        val expectedTag = computeTag(key, nonce, L, aad, plaintext)
        if (!constantTimeEquals(expectedTag, receivedTag)) {
            throw AuthenticationMismatch("CCM tag verification failed")
        }
        return plaintext
    }

    // --- RFC 3610 internals -------------------------------------------------

    /**
     * tag = MSB_t(G XOR S0) where G is the CBC-MAC over the formatted data
     * and S0 is the first counter block's keystream.
     */
    private fun computeTag(
        key: ByteArray,
        nonce: ByteArray,
        L: Int,
        aad: ByteArray?,
        plaintext: ByteArray,
    ): ByteArray {
        val mac = cbcMac(key, formattedData(nonce, L, aad, plaintext))
        val s0 = ctr(key, nonce, L, startCounter = 0L, input = ByteArray(BLOCK_BYTES))
        return ByteArray(TAG_LENGTH_BYTES) { i -> (mac[i].toInt() xor s0[i].toInt()).toByte() }
    }

    /**
     * B0 || a-blocks || message blocks per RFC 3610 §2.2.
     *
     * B0 = flags || N || Q where flags = 64*Adata + 8*M' + L',
     * M' = (4-2)/2 = 1, and Q is the payload length as L big-endian bytes.
     * The a-blocks (l(a) encoding || a) are zero-padded to whole blocks
     * BEFORE the message blocks, which are padded separately at the end.
     */
    private fun formattedData(nonce: ByteArray, L: Int, aad: ByteArray?, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val hasAad = aad != null && aad.isNotEmpty()
        val flags = (if (hasAad) 0x40 else 0) or (((TAG_LENGTH_BYTES - 2) / 2) shl 3) or (L - 1)
        out.write(formattedB0(flags, nonce, L, payload))
        if (aad != null && aad.isNotEmpty()) {
            // a-block: l(a) as 2 octets MSB-first, or 0xFFFE || 4-octet l(a)
            // when (2^16 - 2^8) <= l(a) < 2^32 (10-octet form is unreachable
            // with Kotlin's Int-sized arrays).
            if (aad.size < 0xFF00) {
                out.write(aad.size shr 8)
                out.write(aad.size and 0xFF)
            } else {
                out.write(0xFF)
                out.write(0xFE)
                out.write(aad.size ushr 24)
                out.write(aad.size ushr 16)
                out.write(aad.size ushr 8)
                out.write(aad.size)
            }
            out.write(aad)
            val aadPad = (BLOCK_BYTES - out.size() % BLOCK_BYTES) % BLOCK_BYTES
            if (aadPad != 0) out.write(ByteArray(aadPad))
        }
        out.write(payload)
        val pad = (BLOCK_BYTES - out.size() % BLOCK_BYTES) % BLOCK_BYTES
        if (pad != 0) out.write(ByteArray(pad))
        return out.toByteArray()
    }

    private fun formattedB0(flags: Int, nonce: ByteArray, L: Int, payload: ByteArray): ByteArray {
        val b0 = ByteArray(1 + nonce.size + L)
        b0[0] = flags.toByte()
        nonce.copyInto(b0, 1)
        // Q: payload length as L big-endian bytes, right after the nonce.
        var length = payload.size.toLong()
        for (i in 1 + nonce.size + L - 1 downTo 1 + nonce.size) {
            b0[i] = (length and 0xFF).toByte()
            length = length ushr 8
        }
        return b0
    }

    /** CBC-MAC = last block of CBC(no padding, IV=0) over [data] (block-aligned). */
    private fun cbcMac(key: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(BLOCK_BYTES)))
        val out = cipher.doFinal(data)
        return out.copyOfRange(out.size - BLOCK_BYTES, out.size)
    }

    /** CTR crypt (encrypt/decrypt are the same XOR keystream). */
    private fun ctr(key: ByteArray, nonce: ByteArray, L: Int, startCounter: Long, input: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(counterBlock(nonce, L, startCounter)),
        )
        return cipher.doFinal(input)
    }

    /** A_i = (L-1) || nonce || counter(L bytes, big-endian). */
    private fun counterBlock(nonce: ByteArray, L: Int, counter: Long): ByteArray {
        val block = ByteArray(BLOCK_BYTES)
        block[0] = (L - 1).toByte()
        nonce.copyInto(block, 1)
        var c = counter
        for (i in BLOCK_BYTES - 1 downTo BLOCK_BYTES - L) {
            block[i] = (c and 0xFF).toByte()
            c = c ushr 8
        }
        return block
    }

    private fun validate(key: ByteArray, nonce: ByteArray): Int {
        require(key.size == 16 || key.size == 24 || key.size == 32) {
            "AES key must be 16, 24 or 32 bytes, was ${key.size}"
        }
        require(nonce.size in MIN_NONCE_BYTES..MAX_NONCE_BYTES) {
            "CCM nonce must be $MIN_NONCE_BYTES..$MAX_NONCE_BYTES bytes, was ${nonce.size}"
        }
        return 15 - nonce.size // L
    }

    private fun requirePlaintextLength(size: Int, nonceSize: Int, L: Int) {
        if (L >= 8) return // 2^(8*8) exceeds any ByteArray size
        val max = (1L shl (8 * L)) - 1
        require(size.toLong() <= max) {
            "CCM payload of $size bytes does not fit the Q field for a $nonceSize-byte nonce (max $max)"
        }
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }
}
