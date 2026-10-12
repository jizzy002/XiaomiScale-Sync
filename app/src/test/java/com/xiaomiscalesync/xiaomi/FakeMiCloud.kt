package com.xiaomiscalesync.xiaomi

import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/**
 * A fake Xiaomi cloud on MockWebServer that speaks both halves of the
 * reference protocol (`token_extractor.py`):
 *
 * - the account login / email-2FA endpoints, in the clear;
 * - the RC4-signed API endpoints — every response body is encrypted with
 *   the signed nonce the *client* used, and every incoming request is
 *   decrypted and re-verified with the reference's `generate_enc_signature`
 *   formulas (recorded in [allSignaturesValid]).
 *
 * Encrypted endpoints are routed under `/regions/<region>/app/...` so tests
 * can observe which region the client tried first; the client still signs
 * against the canonical `https://<region>.api.io.mi.com/app/...` URL.
 */
class FakeMiCloud : AutoCloseable {

    val server = MockWebServer()

    // --- account flow (plaintext) ---------------------------------------------
    var serviceLoginCode = 200
    var serviceLoginBody = """{"code":0,"_sign":"SIGN_FIXTURE"}"""
    var auth2Code = 200
    var auth2Body = """{"code":0}"""
    var stsServiceToken: String? = "serviceToken=SERVICE_TOKEN_1"

    /**
     * When true, `/sts` answers **302 → /sts/ok** and sets its cookie on the
     * redirect hop only — the shape that breaks a client which captures
     * Set-Cookie from the final response alone.
     */
    var stsRedirectsToOk = false
    var ickCookie: String? = "ick=ICK_COOKIE_1"
    var verifyEmailCode = 200
    var verifyEmailBody: String = ""
    var verifyEmailLocation: String? = null
    var endCode = 302
    var endPragmaHeader: String? = """{"ssecurity":"SSEC_FROM_2FA","psecurity":"PSEC_DUMMY"}"""
    var endLocationHeader: String? = null // null → /sts on this server

    // --- encrypted API: plaintext JSON answers per region ---------------------
    val gethome = mutableMapOf<String, String>()
    val deviceCount = mutableMapOf<String, String>()
    val deviceList = mutableMapOf<String, String>()
    val beaconKey = mutableMapOf<String, String>()

    /**
     * region → HTTP status every encrypted endpoint in that region must
     * answer (401 = rejected session, 500 = server failure, …), bypassing
     * the protocol. Empty = answer normally.
     */
    val apiStatus = mutableMapOf<String, Int>()

    /** Paths served with an `extension-pragma` response header. */
    val pragmaPaths = CopyOnWriteArrayList<String>()

    // --- observations ---------------------------------------------------------
    private val seen = CopyOnWriteArrayList<RecordedRequest>()
    val requests: List<RecordedRequest> get() = seen.toList()

    /** Encrypted-endpoint path → the decrypted `data` parameter, as sent. */
    val decryptedData = CopyOnWriteArrayList<Pair<String, String>>()

    private val signatureChecks = CopyOnWriteArrayList<Boolean>()

    /** Human-readable reasons for any failed verification (diagnostics). */
    val signatureNotes = CopyOnWriteArrayList<String>()

    /** True when at least one signed request was seen and every one verified. */
    val allSignaturesValid: Boolean
        get() = signatureChecks.isNotEmpty() && signatureChecks.all { it }

    val accountBase: String get() = server.url("/").toString().trimEnd('/')

    fun apiBase(region: String): String =
        server.url("/regions/$region/app").toString().trimEnd('/')

    fun start() = server.start()

    override fun close() = server.shutdown()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen.add(request)
                val url = request.requestUrl ?: return MockResponse().setResponseCode(400)
                val path = url.encodedPath
                return when {
                    path == "/pass/serviceLogin" ->
                        json(serviceLoginCode, serviceLoginBody)
                    path == "/pass/serviceLoginAuth2" ->
                        json(auth2Code, auth2Body)
                    path == "/sts" ->
                        if (stsRedirectsToOk) {
                            MockResponse().setResponseCode(302)
                                .setHeader("Location", server.url("/sts/ok").toString())
                                .withSetCookie(stsServiceToken)
                        } else {
                            json(200, """{"code":0,"description":"ok"}""")
                                .withSetCookie(stsServiceToken)
                        }
                    path == "/sts/ok" ->
                        json(200, """{"code":0,"description":"ok"}""")
                    path == "/authStart" ->
                        json(200, "{}")
                    path == "/identity/list" ->
                        json(200, """{"code":0,"option":[]}""")
                            .withSetCookie(ickCookie)
                    path == "/identity/auth/sendEmailTicket" ->
                        json(200, """{"code":0,"description":"ok"}""")
                    path == "/identity/auth/verifyEmail" ->
                        json(verifyEmailCode, verifyEmailBody).apply {
                            verifyEmailLocation?.let { addHeader("Location", it) }
                        }
                    path.startsWith("/identity/result/check") ->
                        MockResponse().setResponseCode(302)
                            .setHeader("Location", server.url("/pass/serviceLoginAuth2/end").toString())
                    path == "/pass/serviceLoginAuth2/end" -> {
                        if (endPragmaHeader != null) pragmaPaths.add(path)
                        MockResponse().setResponseCode(endCode)
                            .setHeader("Location", endLocationHeader ?: server.url("/sts").toString())
                            .apply { endPragmaHeader?.let { addHeader("extension-pragma", it) } }
                    }
                    path.contains("/app/v2/") ->
                        apiStatus[regionOf(path)]?.let { status ->
                            json(status, """{"code":$status,"description":"forced by test"}""")
                        } ?: encryptedResponse(request, path, regionOf(path))
                    else -> MockResponse().setResponseCode(404).setBody("no route: $path")
                }
            }
        }
    }

    private fun json(code: Int, body: String): MockResponse =
        MockResponse().setResponseCode(code)
            .setBody(body)
            .setHeader("Content-Type", "application/json")

    private fun MockResponse.withSetCookie(pair: String?): MockResponse =
        if (pair == null) this else addHeader("Set-Cookie", pair)

    private fun regionOf(path: String): String =
        path.substringAfter("/regions/", "").substringBefore("/")

    /** The canonical URL the client must have signed for [path]. */
    private fun canonicalUrl(region: String, path: String): String {
        val idx = path.indexOf("/app/")
        val apiPath = if (idx >= 0) path.substring(idx + 4) else path
        return "https://" + (if (region == "cn") "" else "$region.") +
            "api.io.mi.com/app" + apiPath
    }

    private fun plaintextFor(path: String, region: String): String = when {
        path.endsWith("/v2/homeroom/gethome") ->
            gethome[region] ?: DEFAULT_HOMES
        path.endsWith("/v2/user/get_device_cnt") ->
            deviceCount[region] ?: DEFAULT_DEVICE_COUNT
        path.endsWith("/v2/home/home_device_list") ->
            deviceList[region] ?: DEFAULT_DEVICE_LIST
        path.endsWith("/v2/device/blt_get_beaconkey") ->
            beaconKey[region] ?: DEFAULT_BEACON_KEY
        else -> """{"code":404,"result":null}"""
    }

    private fun encryptedResponse(
        request: RecordedRequest,
        path: String,
        region: String,
    ): MockResponse {
        val q = queryParams(request)
        val nonce = q["_nonce"]
        val ssecurity = q["ssecurity"]
        val dataCipher = q["data"]
        if (nonce == null || ssecurity == null || dataCipher == null) {
            signatureChecks.add(false)
            signatureNotes.add("$path: missing signing fields (nonce=$nonce ssecurity=$ssecurity data=$dataCipher)")
            return json(500, "missing signing fields")
        }
        val signedNonce = MiSigner.signedNonce(ssecurity, nonce)
        val dataPlain = runCatching {
            MiSigner.decryptResponse(signedNonce, dataCipher)
        }.getOrNull()
        if (dataPlain == null) {
            signatureChecks.add(false)
            signatureNotes.add("$path: undecryptable data field")
            return json(500, "undecryptable data field")
        }
        decryptedData.add(path to dataPlain)

        // Re-verify the request with the reference's formulas: `signature`
        // covers the encrypted values in order, `rc4_hash__` covers the
        // plaintext values before encryption.
        val canonical = canonicalUrl(region, path)
        val encryptedFields = LinkedHashMap(q)
        encryptedFields.remove("signature")
        encryptedFields.remove("ssecurity")
        encryptedFields.remove("_nonce")
        val expectedSignature = MiSigner.encSignature(canonical, "POST", signedNonce, encryptedFields)
        val expectedHash = MiSigner.encSignature(
            canonical, "POST", signedNonce, linkedMapOf("data" to dataPlain),
        )
        val sigOk = q["signature"] == expectedSignature
        // On the wire `rc4_hash__` carries the *encrypted* signature — the
        // reference encrypts every value, including its own hash.
        val actualHashPlain = runCatching {
            MiSigner.decryptResponse(signedNonce, q["rc4_hash__"] ?: "")
        }.getOrNull()
        val hashOk = actualHashPlain == expectedHash
        signatureChecks.add(sigOk && hashOk)
        if (!sigOk || !hashOk) {
            signatureNotes.add(
                "$path: sigOk=$sigOk hashOk=$hashOk canonical=$canonical " +
                    "expectedSig=$expectedSignature actualSig=${q["signature"]} " +
                    "expectedHash=$expectedHash actualHashPlain=$actualHashPlain",
            )
        }
        return json(200, MiSigner.encryptResponse(signedNonce, plaintextFor(path, region)))
    }

    /** Form-decodes the raw query the way the real server (Python) does. */
    private fun queryParams(request: RecordedRequest): Map<String, String> {
        val raw = request.requestUrl?.encodedQuery ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in raw.split("&")) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val key = if (eq >= 0) pair.substring(0, eq) else pair
            val value = if (eq >= 0) pair.substring(eq + 1) else ""
            out[URLDecoder.decode(key, "UTF-8")] = URLDecoder.decode(value, "UTF-8")
        }
        return out
    }

    companion object {
        private const val DEFAULT_HOMES = """{"code":0,"result":{"homelist":[]}}"""
        private const val DEFAULT_DEVICE_COUNT =
            """{"code":0,"result":{"share":{"share_family":[]}}}"""
        private const val DEFAULT_DEVICE_LIST = """{"code":0,"result":{"device_info":[]}}"""
        private const val DEFAULT_BEACON_KEY = """{"code":0,"result":{}}"""
    }
}
