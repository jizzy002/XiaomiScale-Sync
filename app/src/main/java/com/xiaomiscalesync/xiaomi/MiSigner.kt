package com.xiaomiscalesync.xiaomi

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The Mi Cloud request-signing chain — a faithful Kotlin port of the
 * reference client's helpers (Xiaomi-cloud-tokens-extractor,
 * `token_extractor.py`): `signed_nonce`, `generate_nonce`,
 * `generate_enc_signature`, `generate_enc_params`, `encrypt_rc4` and
 * `decrypt_rc4` (RC4 with the first 1024 keystream bytes dropped, keyed by
 * the signed nonce). Pinned, byte-for-byte, by `micloud_vectors.json`
 * golden vectors (see `MiSignerTest`).
 */
object MiSigner {

    private val decoder: Base64.Decoder = Base64.getDecoder()
    private val encoder: Base64.Encoder = Base64.getEncoder()
    private val secureRandom = SecureRandom()

    /** `signed_nonce = base64(sha256(b64decode(ssecurity) || b64decode(nonce)))`. */
    fun signedNonce(ssecurityB64: String, nonceB64: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(decoder.decode(ssecurityB64))
        digest.update(decoder.decode(nonceB64))
        return encoder.encodeToString(digest.digest())
    }

    /**
     * `nonce = base64(random(8) || (millis / 60000) as 4 bytes big-endian)`.
     * [random] is injectable for deterministic tests; production callers
     * use the [millis]-only overload with `SecureRandom` bytes.
     */
    fun generateNonce(millis: Long, random: ByteArray): String {
        require(random.size == 8) { "nonce random prefix must be 8 bytes" }
        val minute = millis / 60_000
        val out = ByteArray(12)
        random.copyInto(out, 0)
        out[8] = (minute ushr 24).toByte()
        out[9] = (minute ushr 16).toByte()
        out[10] = (minute ushr 8).toByte()
        out[11] = minute.toByte()
        return encoder.encodeToString(out)
    }

    /** The production nonce: 8 fresh `SecureRandom` bytes + the minute. */
    fun generateNonce(millis: Long): String {
        val random = ByteArray(8)
        secureRandom.nextBytes(random)
        return generateNonce(millis, random)
    }

    /**
     * `generate_enc_signature`: SHA-1 (as plain digest, not HMAC — exactly
     * what the reference computes) over
     * `METHOD & <url path after "com", /app/ → /> & k=v… & signed_nonce`,
     * base64-encoded. Field order is the map's insertion order.
     */
    fun encSignature(
        url: String,
        method: String,
        signedNonceB64: String,
        params: Map<String, String>,
    ): String {
        val parts = mutableListOf(method.uppercase(), pathAfterCom(url).replace("/app/", "/"))
        for ((k, v) in params) parts += "$k=$v"
        parts += signedNonceB64
        val sha1 = MessageDigest.getInstance("SHA-1")
            .digest(parts.joinToString("&").toByteArray(Charsets.UTF_8))
        return encoder.encodeToString(sha1)
    }

    /**
     * `generate_enc_params`: adds `rc4_hash__` (the signature over the
     * *plaintext* values), RC4-encrypts every value in place (insertion
     * order preserved), then appends `signature` (over the *encrypted*
     * values), `ssecurity` and `_nonce`.
     *
     * [nonceB64] / [signedNonceB64] are supplied by the caller so the
     * output is deterministic under test; when empty they are derived from
     * [millis] (fresh random nonce, then its signed nonce) the way the
     * reference's `execute_api_call_encrypted` does.
     */
    fun encParams(
        url: String,
        method: String,
        signedNonceB64: String,
        nonceB64: String,
        params: Map<String, String>,
        ssecurityB64: String,
        millis: Long,
    ): Map<String, String> {
        val nonce = nonceB64.ifEmpty { generateNonce(millis) }
        val signedNonce = signedNonceB64.ifEmpty { signedNonce(ssecurityB64, nonce) }

        val fields = LinkedHashMap<String, String>()
        fields.putAll(params)
        // Reference order: signature computed before the assignment, i.e.
        // over the original parameters only.
        fields["rc4_hash__"] = encSignature(url, method, signedNonce, fields)
        val encrypted = fields.map { (k, v) -> k to encryptValue(signedNonce, v) }
        fields.clear()
        fields.putAll(encrypted)
        fields["signature"] = encSignature(url, method, signedNonce, fields)
        fields["ssecurity"] = ssecurityB64
        fields["_nonce"] = nonce
        return fields
    }

    /**
     * `encrypt_rc4`: base64 of the drop-1024 RC4 ciphertext of [value]
     * under the signed nonce. The server encrypts response bodies the same
     * way, so this doubles as the test-side response encryptor.
     */
    fun encryptValue(signedNonceB64: String, value: String): String =
        encryptResponse(signedNonceB64, value)

    /** Same transform as [encryptValue]; names the response-body role. */
    fun encryptResponse(signedNonceB64: String, plaintext: String): String =
        encoder.encodeToString(
            Rc4.applyDrop(
                decoder.decode(signedNonceB64),
                plaintext.toByteArray(Charsets.UTF_8),
            ),
        )

    /** `decrypt_rc4`: drop-1024 RC4 of the base64-decoded [body]. */
    fun decryptResponse(signedNonceB64: String, body: String): String = String(
        Rc4.applyDrop(decoder.decode(signedNonceB64), decoder.decode(body)),
        Charsets.UTF_8,
    )

    /**
     * The reference's `url.split("com")[1]` — the URL segment between the
     * first and second "com" (for the API hosts this is "/app/…", and the
     * trailing replace yields the bare "/v2/…" API path).
     */
    private fun pathAfterCom(url: String): String {
        val parts = url.split("com")
        require(parts.size >= 2) { "signing URL must contain 'com'" }
        return parts[1]
    }
}
