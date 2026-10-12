package com.xiaomiscalesync.garmin

import java.io.IOException
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * MockWebServer tests for [GarminClient]: SSO sign-in, DI token exchange,
 * MFA resume, refresh-token reuse, the weight-range read and the weight
 * creation POST — all against a local fake, no real network.
 *
 * Wire facts pinned here come from the reference library
 * (`python-garminconnect`, `garminconnect/client.py` + `garminconnect/__init__.py`):
 *
 * - SSO: `POST /mobile/api/login?clientId=GCM_IOS_DARK&locale=en-US&service=…`
 *   with the `username/password/rememberMe/captchaToken` JSON body, and the
 *   `MFA_REQUIRED` → `/mobile/api/mfa/verifyCode` resume (fallback endpoint
 *   `/portal/api/mfa/verifyCode`), per `_do_mobile_login` / `_complete_mfa`.
 * - Token exchange: `POST https://diauth.garmin.com/di-oauth2-service/oauth/token`,
 *   `Authorization: Basic base64(clientId:)`, form body
 *   `client_id/service_ticket/grant_type/service_url`, walking
 *   `DI_CLIENT_IDS` in order, per `_exchange_service_ticket`.
 * - API: `connectapi` host, native header block + `Authorization: Bearer …`,
 *   per `get_api_headers` / `_native_headers`.
 * - Reads: `GET /weight-service/weight/range/<start>/<end>?includeAll=True`
 *   (the reference passes `{"includeAll": True}` — Python serialises it as
 *   `True`), response shape `dailyWeightSummaries[].allWeightMetrics[]`,
 *   `weight` in grams, `timestampGMT` epoch-ms, `calendarDate` the local day.
 * - Writes: `POST /weight-service/user-weight` with the payload pinned by the
 *   plan's Global Constraints (exact-JSON assertion below).
 *
 * Retry policy (plan/brief, scoped per the controller ruling): on the
 * weight-service API paths 401 → [GarminAuthError] and 429 →
 * [GarminRateLimited] immediately (exactly one request, never retried — the
 * push queue must stop); 5xx/network → 3 attempts with 1–10 s exponential
 * backoff + jitter (backoff injected as a no-op here). The sign-in flow's
 * two controller-ruled bounded fallbacks (DI client-id walk, MFA
 * dual-bucket) are pinned by the login-flow fallback bounds tests below.
 */
class GarminClientTest {

    private lateinit var server: MockWebServer
    private lateinit var store: MemoryTokenStore
    private val recorded = CopyOnWriteArrayList<RecordedRequest>()
    private val mfaPrompts = CopyOnWriteArrayList<String>()
    private var mfaCode = "999111"

    // --- fake route knobs ----------------------------------------------------
    private var ssoStatus = 200
    private var ssoBody =
        """{"responseStatus":{"type":"SUCCESSFUL"},"serviceTicketId":"ST-1"}"""
    private var mfaStatus = 200
    private var mfaBody =
        """{"responseStatus":{"type":"SUCCESSFUL"},"serviceTicketId":"ST-MFA"}"""
    private var portalMfaStatus = 200
    private var portalMfaBody =
        """{"responseStatus":{"type":"SUCCESSFUL"},"serviceTicketId":"ST-PORTAL"}"""
    private var tokenStatus = 200
    private var tokenBody =
        """{"access_token":"NEW_ACCESS","refresh_token":"NEW_REFRESH","expires_in":3600}"""

    /** FIFO of answers for `/weight-service/…`; empty → 200 `{}`. */
    private val apiQueue = ArrayDeque<Pair<Int, String>>()

    /** FIFO of answers for the DI token endpoint; empty → tokenStatus/tokenBody. */
    private val tokenQueue = ArrayDeque<Pair<Int, String>>()

    private val now = 1_760_000_000_000L
    private val weighInTs = ZonedDateTime.parse("2026-10-11T07:15:42+02:00")

    @Before
    fun setUp() {
        store = MemoryTokenStore()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                recorded.add(request)
                val path = request.requestUrl?.encodedPath
                    ?: return MockResponse().setResponseCode(400)
                return when {
                    path == "/mobile/api/login" -> json(ssoStatus, ssoBody)
                    path == "/mobile/api/mfa/verifyCode" -> json(mfaStatus, mfaBody)
                    path == "/portal/api/mfa/verifyCode" -> json(portalMfaStatus, portalMfaBody)
                    path == "/di-oauth2-service/oauth/token" ->
                        if (tokenQueue.isEmpty()) json(tokenStatus, tokenBody)
                        else tokenQueue.removeFirst().let { (code, body) -> json(code, body) }
                    path.startsWith("/weight-service/") ->
                        if (apiQueue.isEmpty()) json(200, "{}")
                        else apiQueue.removeFirst().let { (code, body) -> json(code, body) }
                    else -> MockResponse().setResponseCode(404).setBody("no route: $path")
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun json(code: Int, body: String): MockResponse =
        MockResponse().setResponseCode(code)
            .setBody(body)
            .setHeader("Content-Type", "application/json")

    private fun base(): String = server.url("/").toString().trimEnd('/')

    private fun client(
        http: OkHttpClient = OkHttpClient(),
        store: TokenStore = this.store,
    ): GarminClient = GarminClient(
        http = http,
        store = store,
        mfa = { method -> mfaPrompts.add(method); mfaCode },
        ssoBaseUrl = base(),
        connectApiBaseUrl = base(),
        diAuthBaseUrl = base(),
        nowMillis = { now },
        backoffMillis = { 0L },
    )

    /** A signed-in client: access token far from expiry, so no refresh fires. */
    private fun seedTokens() {
        store.tokens = GarminTokens(
            accessToken = "ACCESS_TOKEN_1",
            refreshToken = "REFRESH_1",
            clientId = "GARMIN_CONNECT_MOBILE_ANDROID_DI_2025Q2",
            expiresAtMillis = now + 3_600_000L,
        )
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) {
            "missing test fixture $name"
        }.bufferedReader().use { it.readText() }

    /** The DI client id an exchange request authenticated as (Basic header). */
    private fun clientIdOf(request: RecordedRequest): String {
        val decoded = String(
            Base64.getDecoder().decode(request.getHeader("Authorization")!!.removePrefix("Basic ")),
        )
        return decoded.removeSuffix(":")
    }

    // ------------------------------------------------------------- addWeighIn

    /**
     * The brief's exact-JSON pin: `addWeighIn(8025, 2026-10-11T07:15:42+02:00)`
     * must POST precisely this body to `/weight-service/user-weight` — local
     * and GMT timestamps in `yyyy-MM-dd'T'HH:mm:ss.SSS`, `value` = 80.25 kg
     * (two decimals), native bearer headers from the reference.
     */
    @Test
    fun addWeighInPostsThePinnedPayloadToTheWeightService() = runBlocking<Unit> {
        seedTokens()

        client().addWeighIn(8025, weighInTs)

        val request = recorded.single()
        assertEquals("POST", request.method)
        assertEquals("/weight-service/user-weight", request.requestUrl!!.encodedPath)
        assertEquals(
            """{"dateTimestamp":"2026-10-11T07:15:42.000","gmtTimestamp":"2026-10-11T05:15:42.000","unitKey":"kg","sourceType":"MANUAL","value":80.25}""",
            request.body.clone().readUtf8(),
        )
        assertEquals("Bearer ACCESS_TOKEN_1", request.getHeader("Authorization"))
        assertTrue(
            request.getHeader("Content-Type")!!,
            request.getHeader("Content-Type")!!.startsWith("application/json"),
        )
        // The reference's native header block.
        assertEquals("GCM-Android-5.23", request.getHeader("User-Agent"))
        assertEquals(
            "com.garmin.android.apps.connectmobile/5.23; ; " +
                "Google/sdk_gphone64_arm64/google; Android/33; Dalvik/2.1.0",
            request.getHeader("X-Garmin-User-Agent"),
        )
        assertEquals("Android", request.getHeader("X-Garmin-Client-Platform"))
        assertEquals("10861", request.getHeader("X-App-Ver"))
        assertEquals("10861", request.getHeader("X-Garmin-Paired-App-Version"))
        assertEquals("en", request.getHeader("X-Lang"))
        assertEquals("GC5", request.getHeader("X-GCExperience"))
        assertEquals("application/json", request.getHeader("Accept"))
    }

    /** 5xx is retried: 500, 500, 200 → success after exactly 3 requests. */
    @Test
    fun addWeighInRetriesServerErrorsUpToThreeAttempts() = runBlocking<Unit> {
        seedTokens()
        apiQueue += 500 to """{"error":"boom"}"""
        apiQueue += 500 to """{"error":"boom"}"""
        apiQueue += 200 to """{}"""

        client().addWeighIn(8025, weighInTs)

        assertEquals(3, recorded.size)
        assertTrue(recorded.all { it.requestUrl!!.encodedPath == "/weight-service/user-weight" })
    }

    /** 401 stops the queue: typed error, exactly one request, no retry. */
    @Test
    fun addWeighInNeverRetriesUnauthorized() {
        seedTokens()
        apiQueue += 401 to """{"error":"token expired"}"""

        assertThrows(GarminAuthError::class.java) {
            runBlocking { client().addWeighIn(8025, weighInTs) }
        }
        assertEquals(1, recorded.size)
    }

    /** 429 stops the queue likewise: typed error, exactly one request. */
    @Test
    fun addWeighInNeverRetriesRateLimit() {
        seedTokens()
        apiQueue += 429 to """{"error":"too many requests"}"""

        assertThrows(GarminRateLimited::class.java) {
            runBlocking { client().addWeighIn(8025, weighInTs) }
        }
        assertEquals(1, recorded.size)
    }

    /** A 400 validation rejection is a generic typed failure, not auth. */
    @Test
    fun addWeighInSurfacesValidationErrorsAsGarminApiError() {
        seedTokens()
        apiQueue += 400 to """{"message":"invalid weight"}"""

        val error = assertThrows(GarminApiError::class.java) {
            runBlocking { client().addWeighIn(8025, weighInTs) }
        }
        assertEquals(400, error.code)
        assertEquals("/weight-service/user-weight", error.path)
        assertEquals(1, recorded.size)
    }

    /** Exhausted network failures surface typed after 3 attempts. */
    @Test
    fun addWeighInRetriesNetworkFailuresThreeTimesThenSurfacesTyped() {
        seedTokens()
        val attempts = AtomicInteger()
        val offline = OkHttpClient.Builder()
            .addInterceptor { chain ->
                attempts.incrementAndGet()
                throw IOException("simulated network failure")
            }
            .build()

        assertThrows(GarminNetworkError::class.java) {
            runBlocking { client(http = offline).addWeighIn(8025, weighInTs) }
        }
        assertEquals(3, attempts.get())
    }

    // ------------------------------------------------------------- weighInsOn

    /**
     * Parses the recorded real-shaped response (copied from a captured
     * `/weight-service/weight/range` answer) into [GarminWeighIn] rows:
     * `weight` grams → `valueKg`, `calendarDate` → `date`, `timestampGMT`
     * epoch-ms → `gmtTimestamp` in the pinned format.
     */
    @Test
    fun weighInsOnParsesTheRecordedWeightRangeFixture() = runBlocking<Unit> {
        seedTokens()
        apiQueue += 200 to fixture("garmin/fixtures/weight_range.json")

        val weighIns = client().weighInsOn(LocalDate.parse("2025-06-14"))

        assertEquals(2, weighIns.size)
        val manual = weighIns[0]
        assertEquals("2025-06-14", manual.date)
        assertEquals(59.5, manual.valueKg, 0.0)
        assertEquals("2025-06-15T00:52:05.175", manual.gmtTimestamp)
        val scale = weighIns[1]
        assertEquals("2025-06-14", scale.date)
        assertEquals(59.13, scale.valueKg, 0.0)
        assertEquals("2025-06-14T13:53:00.000", scale.gmtTimestamp)

        // The reference's date-range call: same path and params.
        val request = recorded.single()
        assertEquals("GET", request.method)
        assertEquals(
            "/weight-service/weight/range/2025-06-14/2025-06-14",
            request.requestUrl!!.encodedPath,
        )
        assertEquals("True", request.requestUrl!!.queryParameter("includeAll"))
        assertEquals("Bearer ACCESS_TOKEN_1", request.getHeader("Authorization"))
        assertEquals("application/json", request.getHeader("Accept"))
    }

    /** An empty `dailyWeightSummaries` is an empty list, not an error. */
    @Test
    fun weighInsOnAnswersEmptyListWhenThereAreNoWeighIns() = runBlocking<Unit> {
        seedTokens()
        apiQueue += 200 to """{"dailyWeightSummaries":[]}"""

        assertEquals(emptyList<GarminWeighIn>(), client().weighInsOn(LocalDate.parse("2026-10-11")))
        assertEquals(1, recorded.size)
    }

    /** Read path obeys the same never-retry-401 rule. */
    @Test
    fun weighInsOnNeverRetriesUnauthorized() {
        seedTokens()
        apiQueue += 401 to """{"error":"token expired"}"""

        assertThrows(GarminAuthError::class.java) {
            runBlocking { client().weighInsOn(LocalDate.parse("2026-10-11")) }
        }
        assertEquals(1, recorded.size)
    }

    /** …and the same never-retry-429 rule. */
    @Test
    fun weighInsOnNeverRetriesRateLimit() {
        seedTokens()
        apiQueue += 429 to """{"error":"too many requests"}"""

        assertThrows(GarminRateLimited::class.java) {
            runBlocking { client().weighInsOn(LocalDate.parse("2026-10-11")) }
        }
        assertEquals(1, recorded.size)
    }

    // ------------------------------------------------------------------ login

    /** SSO sign-in → CAS ticket → DI token exchange, tokens persisted. */
    @Test
    fun loginWalksTheMobileSsoFlowAndPersistsTheDiTokens() = runBlocking<Unit> {
        client().login("tester@example.com", "hunter2-secret")

        val tokens = store.tokens
        assertNotNull(tokens)
        assertEquals("NEW_ACCESS", tokens!!.accessToken)
        assertEquals("NEW_REFRESH", tokens.refreshToken)
        assertEquals("GARMIN_CONNECT_MOBILE_ANDROID_DI_2025Q2", tokens.clientId)
        assertEquals(now + 3_600_000L, tokens.expiresAtMillis)

        // 1) the reference's mobile SSO login request.
        val sso = recorded[0]
        assertEquals("POST", sso.method)
        assertEquals("/mobile/api/login", sso.requestUrl!!.encodedPath)
        assertEquals("GCM_IOS_DARK", sso.requestUrl!!.queryParameter("clientId"))
        assertEquals("en-US", sso.requestUrl!!.queryParameter("locale"))
        assertEquals(
            "https://mobile.integration.garmin.com/gcm/ios",
            sso.requestUrl!!.queryParameter("service"),
        )
        assertEquals(
            """{"username":"tester@example.com","password":"hunter2-secret","rememberMe":true,"captchaToken":""}""",
            sso.body.clone().readUtf8(),
        )
        assertEquals("https://sso.garmin.com", sso.getHeader("Origin"))
        assertEquals(
            "Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) " +
                "AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148",
            sso.getHeader("User-Agent"),
        )

        // 2) the DI token exchange, first client id, reference form fields.
        val exchange = recorded[1]
        assertEquals("POST", exchange.method)
        assertEquals("/di-oauth2-service/oauth/token", exchange.requestUrl!!.encodedPath)
        assertEquals(
            "Basic " + Base64.getEncoder()
                .encodeToString("GARMIN_CONNECT_MOBILE_ANDROID_DI_2025Q2:".toByteArray()),
            exchange.getHeader("Authorization"),
        )
        val form = exchange.body.clone().readUtf8()
        assertTrue(form, form.contains("client_id=GARMIN_CONNECT_MOBILE_ANDROID_DI_2025Q2"))
        assertTrue(form, form.contains("service_ticket=ST-1"))
        assertTrue(
            form,
            form.contains(
                "grant_type=https%3A%2F%2Fconnectapi.garmin.com%2Fdi-oauth2-service" +
                    "%2Foauth%2Fgrant%2Fservice_ticket",
            ),
        )
        assertTrue(
            form,
            form.contains("service_url=https%3A%2F%2Fmobile.integration.garmin.com%2Fgcm%2Fios"),
        )
    }

    /** Wrong credentials stop at the SSO step with [GarminAuthError]. */
    @Test
    fun loginWithBadCredentialsThrowsGarminAuthError() {
        ssoBody = """{"responseStatus":{"type":"INVALID_USERNAME_PASSWORD"}}"""

        assertThrows(GarminAuthError::class.java) {
            runBlocking { client().login("tester@example.com", "wrong-password") }
        }
        assertTrue(
            "the token exchange must not run after a credential rejection",
            recorded.none { it.requestUrl?.encodedPath == "/di-oauth2-service/oauth/token" },
        )
    }

    /**
     * `MFA_REQUIRED` → the injected prompt supplies the code →
     * `/mobile/api/mfa/verifyCode` (the reference's ios-flow endpoint) →
     * ticket → token exchange.
     */
    @Test
    fun loginResumesMfaThroughTheVerifyCodeEndpoint() = runBlocking<Unit> {
        ssoBody =
            """{"responseStatus":{"type":"MFA_REQUIRED"},"customerMfaInfo":{"mfaLastMethodUsed":"sms"}}"""

        client().login("tester@example.com", "hunter2-secret")

        assertEquals(listOf("sms"), mfaPrompts.toList())
        assertEquals(
            listOf(
                "/mobile/api/login",
                "/mobile/api/mfa/verifyCode",
                "/di-oauth2-service/oauth/token",
            ),
            recorded.map { it.requestUrl!!.encodedPath },
        )
        val verify = recorded[1]
        assertEquals("GCM_IOS_DARK", verify.requestUrl!!.queryParameter("clientId"))
        assertEquals(
            "https://mobile.integration.garmin.com/gcm/ios",
            verify.requestUrl!!.queryParameter("service"),
        )
        val body = verify.body.clone().readUtf8()
        assertTrue(body, body.contains(""""mfaMethod":"sms""""))
        assertTrue(body, body.contains(""""mfaVerificationCode":"999111""""))
        assertTrue(body, body.contains(""""rememberMyBrowser":true"""))
        assertTrue(body, body.contains(""""mfaSetup":false"""))
        assertEquals("NEW_ACCESS", store.tokens!!.accessToken)
    }

    /**
     * A stored refresh token skips SSO entirely (the brief's "later runs
     * skip SSO"): only the refresh grant runs, and the new tokens persist.
     */
    @Test
    fun loginWithAStoredRefreshTokenSkipsSso() = runBlocking<Unit> {
        store.tokens = GarminTokens(
            accessToken = "OLD_ACCESS",
            refreshToken = "OLD_REFRESH",
            clientId = "GARMIN_CONNECT_MOBILE_ANDROID_DI_2025Q2",
            expiresAtMillis = now - 1L, // already expired → refresh first
        )
        tokenBody =
            """{"access_token":"REFRESHED","refresh_token":"REFRESHED_RT","expires_in":3600}"""

        client().login("tester@example.com", "hunter2-secret")

        assertTrue(
            "SSO must be skipped when a refresh token is stored",
            recorded.none { it.requestUrl?.encodedPath == "/mobile/api/login" },
        )
        assertEquals(1, recorded.size)
        val refresh = recorded[0]
        assertEquals("/di-oauth2-service/oauth/token", refresh.requestUrl!!.encodedPath)
        val form = refresh.body.clone().readUtf8()
        assertTrue(form, form.contains("grant_type=refresh_token"))
        assertTrue(form, form.contains("refresh_token=OLD_REFRESH"))
        assertTrue(form, form.contains("client_id=GARMIN_CONNECT_MOBILE_ANDROID_DI_2025Q2"))
        assertEquals("REFRESHED", store.tokens!!.accessToken)
        assertEquals("REFRESHED_RT", store.tokens!!.refreshToken)
        assertEquals(now + 3_600_000L, store.tokens!!.expiresAtMillis)
    }

    /** A 429 during sign-in is [GarminRateLimited] — never retried. */
    @Test
    fun loginRateLimitSurfacesGarminRateLimited() {
        ssoStatus = 429
        ssoBody = """{"error":"too many requests"}"""

        assertThrows(GarminRateLimited::class.java) {
            runBlocking { client().login("tester@example.com", "hunter2-secret") }
        }
        assertEquals(1, recorded.size)
    }

    // --------------------------------------- login-flow fallback bounds (ruling)

    /**
     * Controller-ruled login-flow exception (finding 1): a 401 on the DI
     * token endpoint is walked past to the next client id — Garmin decides
     * per client id — but the walk is bounded by [GarminClient.DI_CLIENT_IDS]
     * (4 POSTs) and ends in a typed [GarminAuthError]. The weight-service
     * API paths keep the strict never-retry-401 rule.
     */
    @Test
    fun tokenExchangeWalksEveryClientIdThenSurfacesTypedError() {
        tokenStatus = 401
        tokenBody = """{"error":"unauthorized"}"""

        assertThrows(GarminAuthError::class.java) {
            runBlocking { client().login("tester@example.com", "hunter2-secret") }
        }
        val exchanges = recorded.filter {
            it.requestUrl?.encodedPath == "/di-oauth2-service/oauth/token"
        }
        // Bounded: exactly one POST per client id, then the typed error.
        assertEquals(GarminClient.DI_CLIENT_IDS.size, exchanges.size)
        assertEquals(GarminClient.DI_CLIENT_IDS, exchanges.map { clientIdOf(it) })
        // …and the SSO login itself ran exactly once (the walk never re-logins).
        assertEquals(1, recorded.count { it.requestUrl?.encodedPath == "/mobile/api/login" })
    }

    /** The walk stops at the first client id Garmin accepts (third here). */
    @Test
    fun tokenExchangeStopsAtTheFirstAcceptedClientId() = runBlocking<Unit> {
        tokenQueue += 401 to """{"error":"unauthorized"}"""
        tokenQueue += 401 to """{"error":"unauthorized"}"""
        tokenQueue += 200 to
            """{"access_token":"THIRD_ACCESS","refresh_token":"THIRD_REFRESH","expires_in":3600}"""

        client().login("tester@example.com", "hunter2-secret")

        val exchanges = recorded.filter {
            it.requestUrl?.encodedPath == "/di-oauth2-service/oauth/token"
        }
        assertEquals(3, exchanges.size)
        assertEquals("GARMIN_CONNECT_MOBILE_ANDROID_DI", exchanges.last().let { clientIdOf(it) })
        assertEquals("THIRD_ACCESS", store.tokens!!.accessToken)
        assertEquals("THIRD_REFRESH", store.tokens!!.refreshToken)
        assertEquals("GARMIN_CONNECT_MOBILE_ANDROID_DI", store.tokens!!.clientId)
    }

    /**
     * Controller-ruled login-flow exception (finding 2): a 429 on the mobile
     * MFA endpoint triggers exactly one extra request to the portal endpoint
     * (separate Garmin rate-limit bucket), which completes the login here.
     */
    @Test
    fun mfaFallsBackToThePortalBucketWhenMobileIsRateLimited() = runBlocking<Unit> {
        ssoBody =
            """{"responseStatus":{"type":"MFA_REQUIRED"},"customerMfaInfo":{"mfaLastMethodUsed":"sms"}}"""
        mfaStatus = 429
        mfaBody = """{"error":"too many requests"}"""

        client().login("tester@example.com", "hunter2-secret")

        assertEquals(
            listOf(
                "/mobile/api/login",
                "/mobile/api/mfa/verifyCode",
                "/portal/api/mfa/verifyCode",
                "/di-oauth2-service/oauth/token",
            ),
            recorded.map { it.requestUrl!!.encodedPath },
        )
        // The portal bucket's ticket is the one exchanged.
        assertTrue(recorded.last().body.clone().readUtf8().contains("service_ticket=ST-PORTAL"))
        assertEquals("NEW_ACCESS", store.tokens!!.accessToken)
    }

    /** Both buckets 429 → [GarminRateLimited] after exactly one try each. */
    @Test
    fun mfaSurfacesRateLimitedWhenBothBucketsAreRateLimited() {
        ssoBody =
            """{"responseStatus":{"type":"MFA_REQUIRED"},"customerMfaInfo":{"mfaLastMethodUsed":"sms"}}"""
        mfaStatus = 429
        mfaBody = """{"error":"too many requests"}"""
        portalMfaStatus = 429
        portalMfaBody = """{"error":"too many requests"}"""

        assertThrows(GarminRateLimited::class.java) {
            runBlocking { client().login("tester@example.com", "hunter2-secret") }
        }
        assertEquals(
            listOf(
                "/mobile/api/login",
                "/mobile/api/mfa/verifyCode",
                "/portal/api/mfa/verifyCode",
            ),
            recorded.map { it.requestUrl!!.encodedPath },
        )
        // The prompt ran once; the dual-bucket never re-asks the user.
        assertEquals(listOf("sms"), mfaPrompts.toList())
    }

    /**
     * Finding 3: the [GarminClient] `mfa` seam is `suspend`, so a suspending
     * UI prompt plugs in without `runBlocking`. The lambda below suspends
     * (`delay`) — it only compiles against a suspend parameter.
     */
    @Test
    fun mfaPromptSeamAcceptsSuspendingPrompts() = runBlocking<Unit> {
        ssoBody =
            """{"responseStatus":{"type":"MFA_REQUIRED"},"customerMfaInfo":{"mfaLastMethodUsed":"email"}}"""
        val suspendingClient = GarminClient(
            http = OkHttpClient(),
            store = store,
            mfa = { method ->
                mfaPrompts.add(method)
                delay(1) // suspends: proves the seam is suspend
                "555123"
            },
            ssoBaseUrl = base(),
            connectApiBaseUrl = base(),
            diAuthBaseUrl = base(),
            nowMillis = { now },
            backoffMillis = { 0L },
        )

        suspendingClient.login("tester@example.com", "hunter2-secret")

        assertEquals(listOf("email"), mfaPrompts.toList())
        assertEquals("NEW_ACCESS", store.tokens!!.accessToken)
        val verify = recorded.first { it.requestUrl!!.encodedPath == "/mobile/api/mfa/verifyCode" }
        assertTrue(verify.body.clone().readUtf8().contains(""""mfaVerificationCode":"555123""""))
    }

    // ------------------------------------------------------------ token safety

    /** Tokens must never print themselves (logs, exceptions, crash reports). */
    @Test
    fun tokensToStringNeverRevealsSecrets() {
        val tokens = GarminTokens(
            accessToken = "SECRET_ACCESS_TOKEN",
            refreshToken = "SECRET_REFRESH_TOKEN",
            clientId = "GARMIN_CONNECT_MOBILE_ANDROID_DI_2025Q2",
            expiresAtMillis = now,
        )

        val shown = tokens.toString()
        assertFalse(shown, shown.contains("SECRET_ACCESS_TOKEN"))
        assertFalse(shown, shown.contains("SECRET_REFRESH_TOKEN"))
        assertTrue(shown, shown.contains("REDACTED"))
        assertTrue(shown, shown.contains("GARMIN_CONNECT_MOBILE_ANDROID_DI_2025Q2"))
        // …and value semantics are untouched.
        assertEquals(
            tokens,
            GarminTokens(
                "SECRET_ACCESS_TOKEN",
                "SECRET_REFRESH_TOKEN",
                "GARMIN_CONNECT_MOBILE_ANDROID_DI_2025Q2",
                now,
            ),
        )
    }

    /** In-memory [TokenStore] double (the app ships a Keystore-backed one). */
    private class MemoryTokenStore : TokenStore {
        var tokens: GarminTokens? = null
        override suspend fun load(): GarminTokens? = tokens
        override suspend fun save(tokens: GarminTokens) {
            this.tokens = tokens
        }
    }
}
