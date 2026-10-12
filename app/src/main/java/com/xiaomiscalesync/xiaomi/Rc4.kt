package com.xiaomiscalesync.xiaomi

/**
 * Pure-Kotlin RC4 (the Mi Cloud API's `MIOT-ENCRYPT-ALGORITHM: ENCRYPT-RC4`).
 *
 * Android's JCA has no RC4 implementation worth relying on (legacy,
 * provider-dependent), and the cipher is ~25 lines, so it lives here.
 * Port of the reference client's `ARC4.new(key).encrypt(...)` usage.
 */
object Rc4 {

    /**
     * Raw RC4: key-schedule (KSA) followed by the pseudo-random generation
     * algorithm, XORed with [data]. RC4 is symmetric — applying it twice
     * with the same key decrypts.
     */
    fun apply(key: ByteArray, data: ByteArray): ByteArray = applyDrop(key, data, drop = 0)

    /**
     * RC4 that discards the first [drop] keystream bytes before use. The
     * reference client does exactly this against the signed nonce:
     * `r = ARC4.new(key); r.encrypt(bytes(1024)); r.encrypt(payload)` —
     * encrypting [drop] zero bytes consumes those keystream bytes.
     */
    fun applyDrop(key: ByteArray, data: ByteArray, drop: Int = 1024): ByteArray {
        require(key.isNotEmpty()) { "RC4 key must not be empty" }
        require(drop >= 0) { "drop must be >= 0" }

        // KSA
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
            val tmp = s[i]; s[i] = s[j]; s[j] = tmp
        }

        // PRGA: advance past the dropped bytes, then produce the keystream.
        val out = ByteArray(data.size)
        var i = 0
        var j2 = 0
        var produced = 0
        while (produced < drop + data.size) {
            i = (i + 1) and 0xFF
            j2 = (j2 + s[i]) and 0xFF
            val tmp = s[i]; s[i] = s[j2]; s[j2] = tmp
            val keystreamByte = s[(s[i] + s[j2]) and 0xFF]
            if (produced >= drop) {
                out[produced - drop] = (data[produced - drop].toInt() xor keystreamByte).toByte()
            }
            produced++
        }
        return out
    }
}
