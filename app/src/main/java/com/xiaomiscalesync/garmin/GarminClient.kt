package com.xiaomiscalesync.garmin

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.io.IOException
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** One weigh-in as Garmin reports it in a weight-range read. */
data class GarminWeighIn(
    /** `timestampGMT` epoch-ms rendered as `yyyy-MM-dd'T'HH:mm:ss.SSS` in UTC. */
    val gmtTimestamp: String?,
    /** `calendarDate` — the day Garmin filed the entry under. */
    val date: String,
    /** `weight` (grams) converted to kg. */
    val valueKg: Double,
)

/** Base type for every Garmin Connect failure this client raises. */
open class GarminException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** Authentication failed (bad credentials, rejected/expired token, failed MFA). */
class GarminAuthError(message: String, cause: Throwable? = null) :
    GarminException(message, cause)

/**
 * Garmin answered 429. Never retried by this client: Garmin rate-limits
 * automated logins aggressively and retrying makes it worse.
 */
class GarminRateLimited(message: String) : GarminException(message)

/**
 * A non-200 API answer that is neither an auth refusal nor a rate limit.
 * [code] and [path] identify the call; no token or ticket is in the message.
 */
class GarminApiError(val code: Int, val path: String) :
    GarminException("Garmin API $path failed (HTTP $code)")

/** The call never reached Garmin (DNS, connect, timeout…) after all retries. */
class GarminNetworkError(val path: String, cause: IOException) :
    GarminException("Garmin API $path is unreachable", cause)

/**
 * Garmin Connect client: mobile-SSO sign-in (with MFA resume), DI OAuth2
 * token exchange/refresh, weight-range reads and weight creation. Kotlin port
 * of the reference client's mobile flow (`python-garminconnect`,
 * `garminconnect/client.py`): `_do_mobile_login`, `_complete_mfa`,
 * `_exchange_service_ticket`, `_refresh_di_token`, `get_weigh_ins` and
 * `add_weigh_in` (`garminconnect/__init__.py`).
 *
 * Only the mobile iOS SSO strategy is ported — the reference's other four
 * strategies (TLS-rotation web/widget/portal logins) target desktop
 * scraping and are out of scope for the app. The JWT_WEB cookie fallback is
 * likewise not ported: the DI token exchange is the app's only auth tier.
 *
 * Wire facts are the reference's: SSO `responseStatus.type` dispatch,
 * `serviceTicketId`, the `DI_CLIENT_IDS` walk with `Basic base64(clientId:)`
 * auth, and the reference's native Android header block. `includeAll=True`
 * keeps the reference's Python-`True` casing (reference wins over the
 * lowercase a spec might expect).
 *
 * Secrets (access/refresh tokens, service tickets) are never logged; this
 * class performs no logging at all.
 *
 * Injectable seams for tests: [ssoBaseUrl], [connectApiBaseUrl],
 * [diAuthBaseUrl] (the request targets; canonical Garmin URLs stay in query
 * parameters and form fields), [nowMillis], [backoffMillis]. The class is
 * `open` with `open` weigh-in methods so the push-queue tests (Task 12) can
 * script a JVM fake without HTTP; sign-in stays final (wire-tested instead).
 */
open class GarminClient(
    private val http: OkHttpClient,
    private val store: TokenStore,
    /**
     * Supplies the MFA code for a challenge; receives the method Garmin used.
     * Suspending so a UI prompt (Compose) plugs in without `runBlocking`.
     */
    private val mfa: suspend (method: String) -> String,
    private val ssoBaseUrl: String = "https://sso.garmin.com",
    private val connectApiBaseUrl: String = "https://connectapi.garmin.com",
    private val diAuthBaseUrl: String = "https://diauth.garmin.com",
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    /** Delay before retry `attempt` (1-based); tests inject a no-op. */
    private val backoffMillis: (attempt: Int) -> Long = { attempt ->
        (1_000L shl (attempt - 1).coerceIn(0, 3)).coerceAtMost(10_000L) + (0..999).random()
    },
) {

    companion object {
        /** The reference's iOS mobile-app SSO constants. */
        const val IOS_SSO_CLIENT_ID = "GCM_IOS_DARK"
        const val IOS_SERVICE_URL = "https://mobile.integration.garmin.com/gcm/ios"
        const val IOS_LOGIN_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) " +
                "AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148"

        /** Portal fallback constants (MFA verify alternate endpoint). */
        const val PORTAL_SSO_CLIENT_ID = "GarminConnect"
        const val PORTAL_SERVICE_URL = "https://connect.garmin.com/app"

        const val DI_TOKEN_PATH = "/di-oauth2-service/oauth/token"
        val DI_GRANT_TYPE =
            "https://connectapi.garmin.com/di-oauth2-service/oauth/grant/service_ticket"

        /** DI client ids the reference walks in order until one is accepted. */
        val DI_CLIENT_IDS = listOf(
            "GARMIN_CONNECT_MOBILE_ANDROID_DI_2025Q2",
            "GARMIN_CONNECT_MOBILE_ANDROID_DI_2024Q4",
            "GARMIN_CONNECT_MOBILE_ANDROID_DI",
            "GARMIN_CONNECT_MOBILE_IOS_DI",
        )

        /** The reference's native Android header block. */
        const val NATIVE_API_USER_AGENT = "GCM-Android-5.23"
        const val NATIVE_X_GARMIN_USER_AGENT =
            "com.garmin.android.apps.connectmobile/5.23; ; " +
                "Google/sdk_gphone64_arm64/google; Android/33; Dalvik/2.1.0"

        /** 3 attempts on 5xx/network (never on 401/429). */
        const val MAX_ATTEMPTS = 3

        /** Refresh this long before expiry (the reference's 900 s). */
        const val REFRESH_MARGIN_MILLIS = 900_000L

        private val JSON = "application/json".toMediaType()
        private val TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)
        private val gson = Gson()
    }

    // ------------------------------------------------------------- sign-in

    /**
     * Signs in with email/password, or reuses stored tokens. Order: fresh
     * stored tokens → nothing to do; stored refresh token → refresh grant
     * (a rejected refresh falls back to SSO; a rate limit or network failure
     * propagates); otherwise the mobile SSO flow. [mfa] is invoked when
     * Garmin demands a second factor.
     */
    suspend fun login(email: String, password: String): Unit = withContext(Dispatchers.IO) {
        val stored = store.load()
        if (stored != null && !expiringSoon(stored)) return@withContext
        if (stored?.refreshToken != null) {
            try {
                refresh(stored)
                return@withContext
            } catch (_: GarminAuthError) {
                // Rejected refresh — fall through to a full SSO sign-in.
            }
        }
        ssoLogin(email, password)
    }

    /**
     * The reference's `_do_mobile_login`: POST the iOS app's SSO endpoint,
     * dispatch on `responseStatus.type` — `SUCCESSFUL` consumes the CAS
     * service ticket via the DI exchange, `MFA_REQUIRED` resumes through
     * [verifyMfa], `INVALID_USERNAME_PASSWORD` is a typed auth refusal.
     */
    private suspend fun ssoLogin(email: String, password: String) {
        val body = JsonObject().apply {
            addProperty("username", email)
            addProperty("password", password)
            addProperty("rememberMe", true)
            addProperty("captchaToken", "")
        }
        val url = "$ssoBaseUrl/mobile/api/login".toHttpUrl().newBuilder()
            .addQueryParameter("clientId", IOS_SSO_CLIENT_ID)
            .addQueryParameter("locale", "en-US")
            .addQueryParameter("service", IOS_SERVICE_URL)
            .build()
        val response = execute(
            Request.Builder()
                .url(url)
                .post(body.toString().toRequestBody(JSON))
                .header("User-Agent", IOS_LOGIN_UA)
                .header("Accept", "application/json, text/plain, */*")
                .header("Content-Type", "application/json")
                .header("Origin", "https://sso.garmin.com"),
            path = "/mobile/api/login",
        )
        val json = response.jsonObject()
            ?: throw GarminException("Garmin SSO login returned unparseable JSON")
        when (val status = json.string("responseStatus", "type")) {
            "SUCCESSFUL" -> {
                val ticket = json.string("serviceTicketId")
                    ?: throw GarminException("Garmin SSO login returned no service ticket")
                exchangeServiceTicket(ticket, IOS_SERVICE_URL)
            }
            "MFA_REQUIRED" -> {
                val method = json.string("customerMfaInfo", "mfaLastMethodUsed") ?: "email"
                val ticket = verifyMfa(method)
                exchangeServiceTicket(ticket, IOS_SERVICE_URL)
            }
            "INVALID_USERNAME_PASSWORD" ->
                throw GarminAuthError("401 Unauthorized (Invalid Username or Password)")
            else -> {
                // The reference also finds 429 buried in the JSON error body.
                if (json.string("error", "status-code") == "429") {
                    throw GarminRateLimited("Garmin SSO login is rate limited")
                }
                throw GarminException("Garmin SSO login failed: responseStatus=${status ?: "unknown"}")
            }
        }
    }

    /**
     * The reference's `_complete_mfa` for the mobile flow: POST the code to
     * `/mobile/api/mfa/verifyCode`; if that endpoint refuses (the reference
     * notes the two endpoints live in different rate-limit buckets), retry
     * once on `/portal/api/mfa/verifyCode`. Returns the new service ticket.
     *
     * **Controller-ruled login-flow exception** to the "never retry 429"
     * rule (which binds strictly on the weight-service API paths): after a
     * 429 on the mobile endpoint exactly one extra request may go to the
     * portal endpoint — the two buckets are independent, and this exact flow
     * is proven working for this account. Bounded at one fallback request;
     * both limited → typed [GarminRateLimited]. Pinned by
     * `mfaFallsBackToThePortalBucketWhenMobileIsRateLimited` and
     * `mfaSurfacesRateLimitedWhenBothBucketsAreRateLimited`.
     */
    private suspend fun verifyMfa(method: String): String {
        val code = mfa(method)
        val body = JsonObject().apply {
            addProperty("mfaMethod", method)
            addProperty("mfaVerificationCode", code)
            addProperty("rememberMyBrowser", true)
            add("reconsentList", JsonArray())
            addProperty("mfaSetup", false)
        }
        val endpoints = listOf(
            Triple("/mobile/api/mfa/verifyCode", IOS_SSO_CLIENT_ID, IOS_SERVICE_URL),
            Triple("/portal/api/mfa/verifyCode", PORTAL_SSO_CLIENT_ID, PORTAL_SERVICE_URL),
        )
        var rateLimited = false
        for ((path, clientId, service) in endpoints) {
            val url = "$ssoBaseUrl$path".toHttpUrl().newBuilder()
                .addQueryParameter("clientId", clientId)
                .addQueryParameter("locale", "en-US")
                .addQueryParameter("service", service)
                .build()
            val response = try {
                execute(
                    Request.Builder()
                        .url(url)
                        .post(body.toString().toRequestBody(JSON))
                        .header("User-Agent", IOS_LOGIN_UA)
                        .header("Accept", "application/json, text/plain, */*")
                        .header("Content-Type", "application/json")
                        .header("Origin", "https://sso.garmin.com"),
                    path = path,
                )
            } catch (_: GarminRateLimited) {
                // Both endpoints may be tried; only give up if both are limited.
                rateLimited = true
                continue
            }
            if (response.code == 200) {
                val json = response.jsonObject()
                if (json?.string("responseStatus", "type") == "SUCCESSFUL") {
                    return json.string("serviceTicketId")
                        ?: throw GarminException("Garmin MFA verify returned no service ticket")
                }
            }
        }
        if (rateLimited) throw GarminRateLimited("Garmin MFA verification is rate limited")
        throw GarminAuthError("Garmin MFA verification failed")
    }

    /**
     * The reference's `_exchange_service_ticket`: walk [DI_CLIENT_IDS] with
     * `Basic base64(clientId:)` auth until Garmin mints a DI token, then
     * persist it. 429 propagates (never retried); any other refusal — a 401
     * included — moves on to the next client id.
     *
     * **Controller-ruled login-flow exception** to the "never retry 401"
     * rule (which binds strictly on the weight-service API paths): the DI
     * token endpoint answers per client id, so a 401 for one id says nothing
     * about the others, and this exact flow is proven working for this
     * account. Bounded by [DI_CLIENT_IDS] — at most 4 POSTs, one attempt
     * each (`unauthorizedIsFatal = false`, so `execute` itself never retries
     * a 401) — then a typed [GarminAuthError]. Pinned by
     * `tokenExchangeWalksEveryClientIdThenSurfacesTypedError` and
     * `tokenExchangeStopsAtTheFirstAcceptedClientId`.
     */
    private suspend fun exchangeServiceTicket(ticket: String, serviceUrl: String) {
        for (clientId in DI_CLIENT_IDS) {
            val response = execute(
                Request.Builder()
                    .url(diAuthBaseUrl + DI_TOKEN_PATH)
                    .post(
                        FormBody.Builder()
                            .add("client_id", clientId)
                            .add("service_ticket", ticket)
                            .add("grant_type", DI_GRANT_TYPE)
                            .add("service_url", serviceUrl)
                            .build(),
                    )
                    .headers(nativeHeaders())
                    .header("Authorization", basicAuth(clientId))
                    .header("Accept", "application/json,text/html;q=0.9,*/*;q=0.8")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Cache-Control", "no-cache"),
                path = DI_TOKEN_PATH,
                unauthorizedIsFatal = false,
            )
            if (response.code != 200) continue
            val tokens = response.tokens(clientId) ?: continue
            store.save(tokens)
            return
        }
        throw GarminAuthError("Garmin DI token exchange failed for all client ids")
    }

    /**
     * The reference's `_refresh_di_token`: one refresh grant with the stored
     * client id. A rejected grant is [GarminAuthError] (the caller falls back
     * to SSO); the refreshed pair replaces the stored one.
     */
    private suspend fun refresh(stored: GarminTokens) {
        val refreshToken = stored.refreshToken
            ?: throw GarminAuthError("no Garmin refresh token stored")
        val response = execute(
            Request.Builder()
                .url(diAuthBaseUrl + DI_TOKEN_PATH)
                .post(
                    FormBody.Builder()
                        .add("grant_type", "refresh_token")
                        .add("client_id", stored.clientId)
                        .add("refresh_token", refreshToken)
                        .build(),
                )
                .headers(nativeHeaders())
                .header("Authorization", basicAuth(stored.clientId))
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Cache-Control", "no-cache"),
            path = DI_TOKEN_PATH,
        )
        if (response.code != 200) {
            throw GarminAuthError("Garmin DI token refresh failed (HTTP ${response.code})")
        }
        val json = response.jsonObject()
            ?: throw GarminAuthError("Garmin DI token refresh returned unparseable JSON")
        val access = json.string("access_token")
            ?: throw GarminAuthError("Garmin DI token refresh returned no access token")
        store.save(
            GarminTokens(
                accessToken = access,
                // Garmin may omit it on refresh — keep the previous one.
                refreshToken = json.string("refresh_token") ?: refreshToken,
                clientId = stored.clientId,
                expiresAtMillis = json.long("expires_in")?.let { nowMillis() + it * 1000 },
            ),
        )
    }

    // ---------------------------------------------------------- weigh-ins

    /**
     * Reads Garmin's weigh-ins for [date] (`get_weigh_ins` of the reference:
     * `GET /weight-service/weight/range/<date>/<date>?includeAll=True`).
     * An empty day is an empty list, never an error.
     */
    open suspend fun weighInsOn(date: LocalDate): List<GarminWeighIn> =
        withContext(Dispatchers.IO) {
            val tokens = authenticated()
            val url = "$connectApiBaseUrl/weight-service/weight/range/$date/$date"
                .toHttpUrl().newBuilder()
                // The reference sends Python's `True`; keep its casing.
                .addQueryParameter("includeAll", "True")
                .build()
            val path = "/weight-service/weight/range/$date/$date"
            val response = execute(
                Request.Builder()
                    .url(url)
                    .get()
                    .headers(apiHeaders(tokens.accessToken)),
                path = path,
            )
            if (response.code != 200) throw GarminApiError(response.code, path)
            val range = try {
                gson.fromJson(response.body, WeightRange::class.java)
            } catch (_: Exception) {
                null
            } ?: throw GarminException("Garmin weight range returned unparseable JSON")
            range.dailyWeightSummaries.orEmpty().flatMap { summary ->
                summary.allWeightMetrics.orEmpty().map { metric ->
                    GarminWeighIn(
                        gmtTimestamp = metric.timestampGMT?.let { utcStamp(it) },
                        date = metric.calendarDate.orEmpty(),
                        valueKg = (metric.weight ?: 0.0) / 1000.0,
                    )
                }
            }
        }

    /**
     * Uploads one weigh-in (`add_weigh_in` of the reference):
     * `POST /weight-service/user-weight` with local + GMT timestamps in
     * `yyyy-MM-dd'T'HH:mm:ss.SSS`, `unitKey: kg`, `sourceType: MANUAL` and
     * the weight in kg. Re-uploading the same timestamp/value is how Garmin
     * behaves — dedupe stays on our side.
     */
    open suspend fun addWeighIn(weightGrams: Int, localTs: ZonedDateTime) {
        withContext(Dispatchers.IO) {
            val tokens = authenticated()
            val payload = JsonObject().apply {
                addProperty("dateTimestamp", TIMESTAMP.format(localTs))
                addProperty("gmtTimestamp", TIMESTAMP.format(localTs.withZoneSameInstant(ZoneOffset.UTC)))
                addProperty("unitKey", "kg")
                addProperty("sourceType", "MANUAL")
                add("value", JsonPrimitive(BigDecimal(String.format(Locale.US, "%.2f", weightGrams / 100.0))))
            }
            val path = "/weight-service/user-weight"
            val response = execute(
                Request.Builder()
                    .url(connectApiBaseUrl + path)
                    .post(payload.toString().toRequestBody(JSON))
                    .headers(apiHeaders(tokens.accessToken)),
                path = path,
            )
            // Any 2xx means stored: the live endpoint does not answer a bare
            // 200 (device finding — a 200-only check turned every successful
            // upload into a retry the duplicate guard then marked SKIPPED).
            if (response.code !in 200..299) throw GarminApiError(response.code, path)
        }
    }

    // ------------------------------------------------------------ plumbing

    /** Stored tokens, proactively refreshed when within the expiry margin. */
    private suspend fun authenticated(): GarminTokens {
        val stored = store.load()
            ?: throw GarminAuthError("not signed in to Garmin Connect")
        if (!expiringSoon(stored)) return stored
        // No refresh token: use what we have and let a 401 surface typed.
        stored.refreshToken ?: return stored
        refresh(stored)
        return store.load() ?: stored
    }

    private fun expiringSoon(tokens: GarminTokens): Boolean {
        val expiresAt = tokens.expiresAtMillis ?: return false
        return nowMillis() > expiresAt - REFRESH_MARGIN_MILLIS
    }

    private class HttpResult(val code: Int, val body: String)

    /**
     * One HTTP call with the plan's retry policy: 401 → [GarminAuthError]
     * and 429 → [GarminRateLimited] immediately (never retried — the push
     * queue must stop); 5xx and network failures → up to [MAX_ATTEMPTS]
     * attempts with injected backoff, typed [GarminNetworkError] /
     * [GarminApiError] once exhausted.
     *
     * The never-retry-401/429 rule binds strictly on the **weight-service
     * API paths** (reads and writes: exactly one request, tested). The
     * **sign-in flow** carries two controller-ruled bounded fallbacks that
     * are exceptions to it — the DI client-id walk ([exchangeServiceTicket],
     * which calls this with [unauthorizedIsFatal] = false) and the MFA
     * dual-bucket ([verifyMfa], which catches a typed 429 to try the second
     * endpoint once). Both are bounded and pinned by request-count tests;
     * this function itself never retries a 401 or 429 on any path.
     *
     * @param unauthorizedIsFatal false lets a caller (the DI client-id walk)
     * receive a 401 response instead of an exception, so it can move on to
     * the next client id. Exactly one request is made either way.
     */
    private fun execute(
        builder: Request.Builder,
        path: String,
        unauthorizedIsFatal: Boolean = true,
    ): HttpResult {
        var lastStatus = 0
        var lastFailure: IOException? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            if (attempt > 1) {
                val wait = backoffMillis(attempt - 1)
                if (wait > 0) runBlockingDelay(wait)
            }
            val response = try {
                http.newCall(builder.build()).execute().use { response ->
                    HttpResult(response.code, response.body.string())
                }
            } catch (e: IOException) {
                lastFailure = e
                continue
            }
            when {
                response.code == 401 && unauthorizedIsFatal ->
                    throw GarminAuthError("Garmin API $path refused the token (HTTP 401)")
                response.code == 429 ->
                    throw GarminRateLimited("Garmin API $path is rate limited (HTTP 429)")
                response.code >= 500 -> {
                    lastStatus = response.code
                    continue
                }
                else -> return response
            }
        }
        lastFailure?.let { throw GarminNetworkError(path, it) }
        throw GarminApiError(lastStatus, path)
    }

    /**
     * Blocking sleep inside a synchronous call: [execute] runs on the IO
     * dispatcher (every caller wraps it in `withContext`), so a plain sleep
     * is fine and keeps the retry loop free of coroutine plumbing.
     */
    private fun runBlockingDelay(millis: Long) = Thread.sleep(millis)

    /** The reference's `_native_headers` plus the DI bearer token. */
    private fun apiHeaders(accessToken: String): Headers = nativeHeaders().newBuilder()
        .add("Authorization", "Bearer $accessToken")
        .add("Accept", "application/json")
        .build()

    private fun nativeHeaders(): Headers = Headers.Builder()
        .add("User-Agent", NATIVE_API_USER_AGENT)
        .add("X-Garmin-User-Agent", NATIVE_X_GARMIN_USER_AGENT)
        .add("X-Garmin-Paired-App-Version", "10861")
        .add("X-Garmin-Client-Platform", "Android")
        .add("X-App-Ver", "10861")
        .add("X-Lang", "en")
        .add("X-GCExperience", "GC5")
        .add("Accept-Language", "en-US,en;q=0.9")
        .build()

    private fun basicAuth(clientId: String): String =
        "Basic " + Base64.getEncoder().encodeToString("$clientId:".toByteArray())

    private fun utcStamp(epochMillis: Long): String =
        TIMESTAMP.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC))

    private fun HttpResult.jsonObject(): JsonObject? = try {
        JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject
    } catch (_: Exception) {
        null
    }

    /** The DI token response → persisted [GarminTokens], or null if malformed. */
    private fun HttpResult.tokens(clientId: String): GarminTokens? {
        val json = jsonObject() ?: return null
        val access = json.string("access_token") ?: return null
        return GarminTokens(
            accessToken = access,
            refreshToken = json.string("refresh_token"),
            clientId = clientId,
            expiresAtMillis = json.long("expires_in")?.let { nowMillis() + it * 1000 },
        )
    }

    private fun JsonObject.string(vararg keys: String): String? {
        var current: JsonElement = this
        for (key in keys) {
            current = (current as? JsonObject)?.get(key) ?: return null
            if (current.isJsonNull) return null
        }
        return runCatching { current.asString }.getOrNull()
    }

    private fun JsonObject.long(key: String): Long? =
        runCatching { get(key)?.asLong }.getOrNull()

    // Weight-range response shapes (`dailyWeightSummaries[].allWeightMetrics[]`).
    private data class WeightRange(val dailyWeightSummaries: List<DailyWeightSummary>?)
    private data class DailyWeightSummary(val allWeightMetrics: List<WeightMetric>?)
    private data class WeightMetric(
        val weight: Double?,
        val calendarDate: String?,
        val timestampGMT: Long?,
    )
}
