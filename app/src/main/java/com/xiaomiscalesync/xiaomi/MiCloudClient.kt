package com.xiaomiscalesync.xiaomi

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** The scale as listed in the Mi Cloud device inventory. */
data class ScaleDevice(
    val name: String,
    val mac: String,
    val did: String,
    val model: String,
    /** Cloud TOKEN, 24 hex chars (12 bytes) — the miauth GATT login secret. */
    val tokenHex: String?,
    /** BLE KEY from `blt_get_beaconkey`, 32 hex chars (16 bytes); null if absent. */
    val bleKeyHex: String?,
) {
    /**
     * Redacted on purpose: `tokenHex` and `bleKeyHex` are secrets, and a
     * data-class `toString()` would print them into any log line, exception
     * message or crash report that interpolates this object.
     * `equals`/`hashCode` keep their generated (full-field) semantics.
     */
    override fun toString(): String =
        "ScaleDevice(name=$name, mac=$mac, did=$did, model=$model, " +
            "tokenHex=${seal(tokenHex)}, bleKeyHex=${seal(bleKeyHex)})"
}

/** An authenticated Mi Cloud session ([MiCloudClient.login]'s result). */
data class MiAuth(
    /** Base64 session key the API request signing is derived from. */
    val ssecurity: String,
    val userId: String,
    val serviceToken: String,
) {
    /** Secrets redacted for the same reason as [ScaleDevice.toString]. */
    override fun toString(): String =
        "MiAuth(ssecurity=${seal(ssecurity)}, userId=$userId, " +
            "serviceToken=${seal(serviceToken)})"
}

/** Masks a secret in diagnostics: `null` stays `null`, anything else is `REDACTED`. */
private fun seal(value: String?): String = if (value == null) "null" else "REDACTED"

/** Base type for every Xiaomi-cloud failure this client raises. */
open class MiCloudException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** Xiaomi demanded a captcha — the UI falls back to the manual paste flow. */
class MiLoginBlocked(val captchaUrl: String) :
    MiCloudException("Xiaomi login is blocked by a captcha challenge")

/** Login failed (bad credentials, HTTP failure, missing tokens…). */
class MiLoginFailed(reason: String) : MiCloudException(reason)

/** `onSecondFactor` returned null: the user cancelled the 2FA prompt. */
class MiSecondFactorCancelled :
    MiCloudException("second-factor authentication was cancelled")

/** The scale was found but a required cloud field is missing or malformed. */
class MiFieldError(val field: String, val did: String, reason: String) :
    MiCloudException(reason)

/**
 * A signed API call answered with a non-200 status: the session was
 * rejected (401/403, e.g. an expired serviceToken) or the cloud failed
 * (500…). Deliberately *not* reported as an empty inventory — "no scale
 * found" and "the session was refused" must never look alike upstream.
 *
 * [region] and [path] identify the call; no URL, signature or token is
 * part of the message. The region walk in [MiCloudClient.extractScale]
 * aborts on 4xx and falls through to the next region on 5xx.
 */
class MiApiError(val code: Int, val region: String, val path: String) :
    MiCloudException("Xiaomi cloud API $region$path failed (HTTP $code)")

/**
 * A signed API call never reached the cloud at all (DNS, connect, timeout…) —
 * distinguished from [MiApiError] so the region walk can try the next region,
 * another regional endpoint may still answer. Carries the same `region`/`path`
 * identification, plus the transport failure as its cause.
 */
class MiNetworkError(val region: String, val path: String, cause: IOException) :
    MiCloudException("Xiaomi cloud API $region$path is unreachable", cause)

/**
 * Xiaomi cloud client: Mi Account sign-in (with the email-2FA branch) and
 * the RC4-signed Mi Cloud API used to extract the scale's MAC, TOKEN and
 * BLE KEY. Kotlin port of the reference client's login + device-list flow
 * (Xiaomi-cloud-tokens-extractor's `token_extractor.py`: password login
 * steps 1–3, the `identity` 2FA chain, `execute_api_call_encrypted`,
 * and the homeroom/home_device_list/blt_get_beaconkey walk).
 *
 * Secrets (ssecurity, serviceToken, TOKEN, BLE KEY) are never logged;
 * this class performs no logging at all.
 *
 * Injectable seams for tests: [accountBaseUrl], [apiBaseUrl] (the request
 * target; signing always uses the canonical `api.io.mi.com` URL), [regions],
 * [nowMillis], [userAgent] and [deviceId].
 */
class MiCloudClient(
    private val http: OkHttpClient,
    private val accountBaseUrl: String = "https://account.xiaomi.com",
    private val apiBaseUrl: (region: String) -> String = { region -> apiHost(region) },
    private val regions: List<String> = SERVERS,
    private val preferredRegion: String? = null,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val userAgent: String = randomUserAgent(),
    private val deviceId: String = randomDeviceId(),
) {

    companion object {
        /** Xiaomi cloud regions, in the reference's order. */
        val SERVERS: List<String> = listOf("cn", "de", "us", "ru", "tw", "sg", "in", "i2")

        /** Canonical API base: `https://[region.]api.io.mi.com/app` (cn has no prefix). */
        fun apiHost(region: String): String =
            "https://" + (if (region == "cn") "" else "$region.") + "api.io.mi.com/app"

        /** The reference's random Mi Home user agent. */
        fun randomUserAgent(): String {
            val agentId = (1..13).map { ('A'.code + (0..4).random()).toChar() }.joinToString("")
            val text = (1..18).map { ('a'.code + (0..25).random()).toChar() }.joinToString("")
            return "$text-$agentId APP/com.xiaomi.mihome APPV/10.5.201"
        }

        /** The reference's random 6-letter device id cookie. */
        fun randomDeviceId(): String =
            (1..6).map { ('a'.code + (0..25).random()).toChar() }.joinToString("")

        private val FORM = "application/x-www-form-urlencoded".toMediaType()
        private val EMPTY_BODY = ByteArray(0).toRequestBody(null)
        private val RESULT_CHECK_URL =
            Regex("""https://account\.xiaomi\.com/identity/result/check[^"']+""")
        private const val STS_MARKER = "https://sts.api.io.mi.com/sts"

        private const val HOMES_DATA =
            """{"fg": true, "fetch_share": true, "fetch_share_dev": true, "limit": 300, "app_ver": 7}"""
        private const val DEVICE_COUNT_DATA = """{ "fetch_own": true, "fetch_share": true}"""

        /** Exact reference template (note its spacing — signed verbatim). */
        fun deviceListData(ownerId: String, homeId: String): String =
            """{"home_owner": $ownerId,"home_id": $homeId,  "limit": 200,  "get_split_device": true, "support_smart_home": true}"""

        /** Exact reference template for the BLE-key lookup. */
        fun beaconKeyData(did: String): String = """{"did":"$did","pdid":1}"""
    }

    private val accountHost: String by lazy { accountBaseUrl.toHttpUrl().host }

    /**
     * The client every call runs through: the caller's [http] plus the
     * per-hop cookie jar below (a caller-configured jar is overridden — this
     * client owns its session cookies).
     */
    private val client: OkHttpClient by lazy {
        http.newBuilder()
            .cookieJar(hopCookies)
            .addNetworkInterceptor(replaySessionCookies)
            .build()
    }

    private val noRedirect: OkHttpClient by lazy {
        client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    }

    /** Session cookie store — the reference's `requests.Session` jar. */
    private val cookies = CookieStore().apply {
        // `sdkVersion` / `deviceId` are sent to every Xiaomi host, including
        // a test override of [accountBaseUrl].
        for ((domain, value) in listOf(
            "mi.com" to "accountsdk-18.8.15",
            "xiaomi.com" to "accountsdk-18.8.15",
            accountHost to "accountsdk-18.8.15",
        )) {
            put("sdkVersion", value, domain)
        }
        for (domain in listOf("mi.com", "xiaomi.com", accountHost)) {
            put("deviceId", deviceId, domain)
        }
    }

    /**
     * Per-hop cookie persistence (review finding 2). OkHttp runs its
     * cookie bridge **once per redirect hop** and hands every hop's
     * `Set-Cookie` headers to [CookieJar.saveFromResponse], so a cookie set
     * on a 302 — the STS shape — is stored here instead of being dropped
     * together with the intermediate response that [execute] never sees.
     *
     * [CookieJar.loadForRequest] deliberately answers nothing: this client
     * builds the reference's exact `Cookie` header itself, and a non-empty
     * jar answer would *replace* it — [replaySessionCookies] puts the stored
     * cookies back where they belong instead.
     */
    private val hopCookies: CookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = storeCookies(cookies)
        override fun loadForRequest(url: HttpUrl): List<Cookie> = emptyList()
    }

    /**
     * Second half of the per-hop fix: OkHttp rebuilds every redirect from the
     * *original* request, so a cookie stored from hop 1 would never reach hop
     * 2 — while the reference's `requests.Session` sends it. A network
     * interceptor runs once per hop (after the cookie bridge), so it can add
     * the session cookies that hop is missing. Names already present in the
     * request's own `Cookie` header win, keeping the pinned wire shape
     * byte-identical for the calls that spell their cookies out.
     */
    private val replaySessionCookies = Interceptor { chain ->
        val request = chain.request()
        val explicit = request.header("Cookie")
        val merged = LinkedHashMap<String, String>()
        explicit?.split("; ")?.forEach { pair ->
            val eq = pair.indexOf('=')
            if (eq > 0) merged[pair.substring(0, eq)] = pair.substring(eq + 1)
        }
        var added = false
        for ((name, value) in cookies.cookiesFor(request.url.host)) {
            if (name !in merged) {
                merged[name] = value
                added = true
            }
        }
        if (added) {
            val header = merged.entries.joinToString("; ") { (name, value) -> "$name=$value" }
            chain.proceed(request.newBuilder().header("Cookie", header).build())
        } else {
            chain.proceed(request)
        }
    }

    /** Copies parsed cookies into the session store (never logged). */
    private fun storeCookies(received: List<Cookie>) {
        for (cookie in received) cookies.put(cookie.name, cookie.value, cookie.domain)
    }

    // --------------------------------------------------------------- login

    /**
     * Signs in with username/password (uppercase-MD5 `serviceLoginAuth2`
     * flow) and returns the [MiAuth] session. [onSecondFactor] receives the
     * prompt text when Xiaomi demands email 2FA and returns the emailed
     * code (or null to cancel → [MiSecondFactorCancelled]).
     *
     * Throws [MiLoginBlocked] on a captcha challenge (the UI falls back to
     * manual key paste) and [MiLoginFailed] for every other failure.
     */
    suspend fun login(
        username: String,
        password: String,
        onSecondFactor: suspend (prompt: String) -> String? = { null },
    ): MiAuth = withContext(Dispatchers.IO) {
        var sign: String? = null
        var ssecurity: String? = null
        var userId: String? = null
        var location: String? = null
        var token: String? = null

        // login_step_1 — GET serviceLogin for the _sign challenge.
        val step1 = accountGet(
            "$accountBaseUrl/pass/serviceLogin?sid=xiaomiio&_json=true",
            extraCookies = mapOf("userId" to username),
        )
        if (step1.code != 200) {
            throw MiLoginFailed("serviceLogin failed (HTTP ${step1.code})")
        }
        val s1 = step1.json() ?: throw MiLoginFailed("serviceLogin returned unparseable JSON")
        when {
            s1.string("_sign") != null -> sign = s1.string("_sign")
            s1.string("ssecurity") != null -> {
                // Already-authenticated session: full credentials up front.
                ssecurity = s1.string("ssecurity")
                userId = s1.string("userId")
                location = s1.string("location")
            }
            else -> throw MiLoginFailed("invalid username")
        }

        // login_step_2 — POST serviceLoginAuth2 (params in the query string,
        // password as uppercase MD5 hex, redirects off).
        val signValue = sign
        if (signValue != null) {
            val fields = linkedMapOf(
                "sid" to "xiaomiio",
                "hash" to md5Upper(password),
                "callback" to STS_MARKER,
                "qs" to "%3Fsid%3Dxiaomiio%26_json%3Dtrue",
                "user" to username,
                "_sign" to signValue,
                "_json" to "true",
            )
            val step2 = execute(
                Request.Builder()
                    .url("$accountBaseUrl/pass/serviceLoginAuth2?${encodeQuery(fields)}")
                    .post(EMPTY_BODY)
                    .header("User-Agent", userAgent)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Cookie", cookieHeader(accountHost)),
                followRedirects = false,
            )
            if (step2.code != 200) {
                throw MiLoginFailed("serviceLoginAuth2 failed (HTTP ${step2.code})")
            }
            val s2 = step2.json()
                ?: throw MiLoginFailed("serviceLoginAuth2 returned unparseable JSON")
            val captcha = s2.string("captchaUrl")
            if (captcha != null) throw MiLoginBlocked(captcha)
            val sec = s2.string("ssecurity")
            if (sec != null && sec.length > 4) {
                ssecurity = sec
                userId = s2.string("userId") ?: userId
                location = s2.string("location") ?: location
            } else {
                val notificationUrl = s2.string("notificationUrl")
                    ?: throw MiLoginFailed(
                        "login failed: " + (s2.string("description") ?: "no credentials returned"),
                    )
                val (twoFaSsecurity, twoFaToken) = secondFactor(notificationUrl, onSecondFactor)
                ssecurity = twoFaSsecurity
                token = twoFaToken
                userId = s2.string("userId") ?: userId
            }
        }

        // login_step_3 — exchange the location for the serviceToken cookie.
        val serviceToken: String = token ?: run {
            if (location != null) {
                val step3 = accountGet(location)
                if (step3.code != 200) {
                    throw MiLoginFailed("service token exchange failed (HTTP ${step3.code})")
                }
            }
            cookies.get("serviceToken")
                ?: throw MiLoginFailed("no serviceToken after the login flow")
        }

        MiAuth(
            ssecurity = ssecurity ?: throw MiLoginFailed("no ssecurity after the login flow"),
            userId = userId ?: cookies.get("userId") ?: "",
            serviceToken = serviceToken,
        )
    }

    /**
     * The reference's `do_2fa_email_flow`: identity/list → sendEmailTicket
     * → user code → verifyEmail → result/check → Auth2/end (capturing the
     * `extension-pragma` header's ssecurity) → STS (serviceToken cookie).
     */
    private suspend fun secondFactor(
        notificationUrl: String,
        onSecondFactor: suspend (prompt: String) -> String?,
    ): Pair<String, String> {
        // 1) authStart, 2) the context token carried in the notification URL.
        accountGet(notificationUrl)
        val context = notificationUrl.toHttpUrl().queryParameter("context")
            ?: throw MiLoginFailed("2FA notification URL has no context parameter")

        // 3) identity/list — also captures the `ick` cookie.
        accountGet(
            "$accountBaseUrl/identity/list?" + encodeQuery(
                linkedMapOf("sid" to "xiaomiio", "context" to context, "_locale" to "en_US"),
            ),
        )

        // 4) sendEmailTicket — asks Xiaomi to email the code.
        val ick = cookies.get("ick") ?: ""
        accountPost(
            "$accountBaseUrl/identity/auth/sendEmailTicket?" + encodeQuery(
                linkedMapOf(
                    "_dc" to nowMillis().toString(),
                    "sid" to "xiaomiio",
                    "context" to context,
                    "mask" to "0",
                    "_locale" to "en_US",
                ),
            ),
            encodeQuery(
                linkedMapOf("retry" to "0", "icode" to "", "_json" to "true", "ick" to ick),
            ),
        )

        // 5) the user reads the emailed code.
        val code = onSecondFactor(
            "Two factor authentication required, please provide the code from the email.",
        ) ?: throw MiSecondFactorCancelled()

        // 6) verifyEmail.
        val verify = accountPost(
            "$accountBaseUrl/identity/auth/verifyEmail?" + encodeQuery(
                linkedMapOf(
                    "_flag" to "8", "_json" to "true", "sid" to "xiaomiio",
                    "context" to context, "mask" to "0", "_locale" to "en_US",
                ),
            ),
            encodeQuery(
                linkedMapOf(
                    "_flag" to "8", "ticket" to code, "trust" to "false",
                    "_json" to "true", "ick" to ick,
                ),
            ),
        )
        if (verify.code != 200) {
            throw MiLoginFailed("verifyEmail failed (HTTP ${verify.code})")
        }
        var finishUrl: String? = verify.json()?.string("location")
            ?: verify.headers["Location"]
            ?: RESULT_CHECK_URL.find(verify.body)?.value

        // Fallback: ask result/check where the flow should finish.
        if (finishUrl == null) {
            val hop = accountGet(
                "$accountBaseUrl/identity/result/check?" + encodeQuery(
                    linkedMapOf("sid" to "xiaomiio", "context" to context, "_locale" to "en_US"),
                ),
                followRedirects = false,
            )
            if (hop.code == 301 || hop.code == 302) {
                val location = hop.headers["Location"]
                finishUrl =
                    if (hop.url.toString().contains("serviceLoginAuth2/end")) hop.url.toString()
                    else location
            }
        }
        val resolved = finishUrl ?: throw MiLoginFailed("could not determine the 2FA finish URL")

        // First hop: result/check → Auth2/end (never follow: read Location).
        val endUrl: String =
            if (resolved.contains("identity/result/check")) {
                val hop = accountGet(resolved, followRedirects = false)
                hop.headers["Location"] ?: throw MiLoginFailed("no Auth2/end URL after result/check")
            } else {
                resolved
            }

        // Auth2/end — some servers answer an HTML Tips page first, then 302.
        var end = accountGet(endUrl, followRedirects = false)
        if (end.code == 200 && end.body.contains("Xiaomi Account - Tips")) {
            end = accountGet(endUrl, followRedirects = false)
        }

        // ssecurity rides in the extension-pragma header.
        val pragma = end.headers["extension-pragma"]
        val ssecurity = pragma?.let { parseMiJson(it) as? JsonObject }?.string("ssecurity")
            ?: throw MiLoginFailed("Auth2/end did not provide ssecurity")

        // STS redirect sets the serviceToken cookie.
        var stsUrl = end.headers["Location"]
        if (stsUrl == null) {
            val idx = end.body.indexOf(STS_MARKER)
            if (idx >= 0) {
                val quote = end.body.indexOf('"', idx)
                val stop = (if (quote == -1) idx + 300 else quote).coerceAtMost(end.body.length)
                stsUrl = end.body.substring(idx, stop)
            }
        }
        val sts = stsUrl ?: throw MiLoginFailed("Auth2/end did not provide an STS redirect")
        val done = accountGet(sts)
        if (done.code != 200) throw MiLoginFailed("STS exchange failed (HTTP ${done.code})")
        val token = cookies.get("serviceToken")
            ?: throw MiLoginFailed("no serviceToken after the STS exchange")
        return ssecurity to token
    }

    // ---------------------------------------------------------- extraction

    /**
     * Finds the Xiaomi scale in the account's device inventory and returns
     * its MAC, cloud TOKEN and BLE KEY. Regions are tried in order — the
     * user's [preferredRegion] first, then [regions] — stopping at the first
     * scale with a valid (24-hex) TOKEN.
     *
     * Returns null when no scale is listed anywhere (no-device). Throws
     * [MiFieldError] when a scale exists but a required field is missing or
     * malformed (token, did, or a non-32-hex beacon key). A missing
     * beacon-key result is not an error: `bleKeyHex` is simply null, which
     * the sync layer degrades for (broadcast decryption unavailable).
     *
     * Failure policy per region (a rejected session is never mistaken for an
     * empty inventory): a **4xx** ([MiApiError], e.g. an expired
     * serviceToken) aborts the walk immediately — the same credentials go to
     * every region, so another region cannot heal it; a **5xx** ([MiApiError])
     * or an unreachable endpoint ([MiNetworkError]) falls through to the next
     * region. If every region failed, the **last** region's typed error is
     * thrown (single typed value; `code`/`region` say where the walk ended).
     * If no region failed but a scale was seen with a bad field,
     * [MiFieldError] wins — it is the more actionable cause. Precedence:
     * 4xx > [MiFieldError] > 5xx/[MiNetworkError] > null.
     */
    suspend fun extractScale(auth: MiAuth): ScaleDevice? = withContext(Dispatchers.IO) {
        val order = (listOfNotNull(preferredRegion) + regions).distinct()
        var fieldError: MiFieldError? = null
        var regionFailure: MiCloudException? = null

        for (region in order) {
            try {
                val homes = apiJson(auth, region, "/v2/homeroom/gethome", linkedMapOf("data" to HOMES_DATA))
                val counts = apiJson(auth, region, "/v2/user/get_device_cnt", linkedMapOf("data" to DEVICE_COUNT_DATA))

                // Own homes (owner = our userId) plus shared-family homes.
                val homeIds = ArrayList<Pair<String, String>>()
                homes?.resultArray("homelist")?.forEach { element ->
                    val id = element.asJsonObject.string("id") ?: return@forEach
                    homeIds += id to auth.userId
                }
                counts?.nestedObject("result", "share")?.resultArray("share_family")?.forEach { element ->
                    val obj = element.asJsonObject
                    val id = obj.string("home_id") ?: return@forEach
                    val owner = obj.string("home_owner") ?: return@forEach
                    homeIds += id to owner
                }

                for ((homeId, owner) in homeIds) {
                    val list = apiJson(
                        auth, region, "/v2/home/home_device_list",
                        linkedMapOf("data" to deviceListData(owner, homeId)),
                    )
                    val devices = list?.resultArray("device_info") ?: continue
                    for (element in devices) {
                        val device = element.asJsonObject
                        val model = device.string("model") ?: continue
                        if (!isScaleModel(model)) continue
                        val did = device.string("did")
                        val token = device.string("token")
                        when {
                            did == null ->
                                fieldError = fieldError ?: MiFieldError(
                                    "did", "", "scale '$model' has no did in the device list",
                                )
                            token == null || !isHex(token, 24) ->
                                fieldError = fieldError ?: MiFieldError(
                                    "token", did, "scale $did has no valid 24-hex token",
                                )
                            else -> return@withContext ScaleDevice(
                                name = device.string("name") ?: "",
                                mac = device.string("mac") ?: "",
                                did = did,
                                model = model,
                                tokenHex = token,
                                bleKeyHex = fetchBleKey(auth, region, did),
                            )
                        }
                    }
                }
            } catch (e: MiApiError) {
                if (e.code in 400..499) throw e // session refused: no region can heal it
                regionFailure = e // 5xx: unhealthy region, try the next one
            } catch (e: MiNetworkError) {
                regionFailure = e // unreachable endpoint: try the next one
            }
        }
        // Precedence at the end of the walk: a scale we actually saw (bad
        // field) beats another region's server failure; a typed failure beats
        // "the account has no scale".
        fieldError?.let { throw it }
        regionFailure?.let { throw it }
        null
    }

    /** `blt` devices (the S200 included) need the beaconkey lookup. */
    private fun fetchBleKey(auth: MiAuth, region: String, did: String): String? {
        if (!did.startsWith("blt")) return null
        val response = apiJson(
            auth, region, "/v2/device/blt_get_beaconkey",
            linkedMapOf("data" to beaconKeyData(did)),
        )
        val key = response?.nestedObject("result")?.string("beaconkey") ?: return null
        if (!isHex(key, 32)) {
            throw MiFieldError("beaconkey", did, "beacon key for $did is not 32 hex characters")
        }
        return key
    }

    // The S200 is `xiaomi.scales.ms113`; accept any Xiaomi scale model.
    private fun isScaleModel(model: String): Boolean = model.startsWith("xiaomi.scales")

    private fun isHex(value: String, length: Int): Boolean =
        value.length == length && value.all { it in "0123456789abcdefABCDEF" }

    // ------------------------------------------------------------- plumbing

    /** One encrypted API call (`execute_api_call_encrypted` of the reference). */
    private fun apiJson(
        auth: MiAuth,
        region: String,
        path: String,
        params: Map<String, String>,
    ): JsonObject? {
        // Sign against the canonical URL even when the request target is
        // overridden (tests): the signature covers only the path segment.
        val canonical = apiHost(region) + path
        val millis = nowMillis()
        val nonce = MiSigner.generateNonce(millis)
        val signedNonce = MiSigner.signedNonce(auth.ssecurity, nonce)
        val fields = MiSigner.encParams(
            canonical, "POST", signedNonce, nonce, params, auth.ssecurity, millis,
        )
        val target = apiBaseUrl(region) + path + "?" + encodeQuery(fields)
        val response = try {
            execute(
                Request.Builder()
                    .url(target)
                    .post(EMPTY_BODY)
                    .header("Accept-Encoding", "identity")
                    .header("User-Agent", userAgent)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("x-xiaomi-protocal-flag-cli", "PROTOCAL-HTTP2")
                    .header("MIOT-ENCRYPT-ALGORITHM", "ENCRYPT-RC4")
                    .header("Cookie", apiCookieHeader(auth, target.toHttpUrl().host)),
            )
        } catch (e: IOException) {
            // Transport failure — typed so the region walk can fall through.
            throw MiNetworkError(region, path, e)
        }
        if (response.code != 200) throw MiApiError(response.code, region, path)
        val decrypted = try {
            MiSigner.decryptResponse(signedNonce, response.body)
        } catch (e: Exception) {
            throw MiCloudException("failed to decrypt a Xiaomi cloud response", e)
        }
        return parseMiJson(decrypted) as? JsonObject
            ?: throw MiCloudException("Xiaomi cloud returned unparseable JSON")
    }

    /** The reference's explicit API cookie set, merged over the session jar. */
    private fun apiCookieHeader(auth: MiAuth, host: String): String = cookieHeader(
        host,
        linkedMapOf(
            "userId" to auth.userId,
            "yetAnotherServiceToken" to auth.serviceToken,
            "serviceToken" to auth.serviceToken,
            "locale" to "en_GB",
            "timezone" to "GMT+02:00",
            "is_daylight" to "1",
            "dst_offset" to "3600000",
            "channel" to "MI_APP_STORE",
        ),
    )

    private fun accountGet(
        url: String,
        followRedirects: Boolean = true,
        extraCookies: Map<String, String> = emptyMap(),
    ): HttpResult = execute(
        Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Cookie", cookieHeader(url.toHttpUrl().host, extraCookies)),
        followRedirects = followRedirects,
    )

    private fun accountPost(url: String, body: String): HttpResult = execute(
        Request.Builder()
            .url(url)
            .post(body.toRequestBody(FORM))
            .header("User-Agent", userAgent)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Cookie", cookieHeader(accountHost)),
    )

    private class HttpResult(val code: Int, val body: String, val headers: Headers, val url: HttpUrl)

    private fun execute(builder: Request.Builder, followRedirects: Boolean = true): HttpResult {
        val callClient = if (followRedirects) client else noRedirect
        callClient.newCall(builder.build()).execute().use { response ->
            // Hops were already stored by [hopCookies]; this covers the final
            // response too, so the store never depends on one code path.
            cookies.capture(response.request.url, response.headers)
            return HttpResult(
                response.code,
                response.body.string(),
                response.headers,
                response.request.url,
            )
        }
    }

    private fun cookieHeader(host: String, extra: Map<String, String> = emptyMap()): String {
        val merged = LinkedHashMap<String, String>()
        merged.putAll(cookies.cookiesFor(host))
        merged.putAll(extra)
        return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun encodeQuery(params: Map<String, String>): String =
        params.entries.joinToString("&") { (k, v) ->
            URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
        }

    private fun md5Upper(value: String): String = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02X".format(it) }

    /** Strips the `&&&START&&&` prefix the account endpoints prepend. */
    private fun parseMiJson(text: String): JsonElement? = try {
        JsonParser.parseString(text.replace("&&&START&&&", ""))
    } catch (_: Exception) {
        null
    }

    private fun HttpResult.json(): JsonObject? = parseMiJson(body) as? JsonObject

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeUnless { it.isJsonNull }?.let { runCatching { it.asString }.getOrNull() }

    private fun JsonObject.resultArray(key: String): List<JsonElement>? =
        nestedObject("result")?.get(key)?.takeUnless { it.isJsonNull }?.asJsonArray?.toList()

    private fun JsonObject.nestedObject(vararg keys: String): JsonObject? {
        var current: JsonObject = this
        for (key in keys) {
            current = current.get(key)?.takeUnless { it.isJsonNull } as? JsonObject ?: return null
        }
        return current
    }

    /**
     * The reference's `requests.Session` cookie jar, approximated: cookies
     * are captured from every response and replayed to any host they
     * domain-match (suffix semantics, like the reference's `.mi.com`
     * cookies). Values are never logged.
     */
    private class CookieStore {
        private data class Entry(val name: String, val value: String, val domain: String)

        private val entries = LinkedHashMap<String, Entry>()

        fun put(name: String, value: String, domain: String) {
            entries["$name@$domain"] = Entry(name, value, domain)
        }

        fun capture(url: HttpUrl, headers: Headers) {
            for (index in 0 until headers.size) {
                if (!headers.name(index).equals("Set-Cookie", ignoreCase = true)) continue
                val cookie = Cookie.parse(url, headers.value(index)) ?: continue
                put(cookie.name, cookie.value, cookie.domain)
            }
        }

        fun cookiesFor(host: String): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            for (entry in entries.values) {
                val domain = entry.domain.trimStart('.')
                if (host == domain || host.endsWith(".$domain")) out[entry.name] = entry.value
            }
            return out
        }

        fun get(name: String): String? = entries.values.lastOrNull { it.name == name }?.value
    }
}
