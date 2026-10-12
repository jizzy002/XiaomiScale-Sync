package com.xiaomiscalesync.xiaomi

import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * MockWebServer tests for [MiCloudClient]: every endpoint of the reference
 * login + extraction flow is served by [FakeMiCloud]; no real network.
 *
 * Wire facts pinned here come from the reference client
 * (`token_extractor.py`): endpoint paths, parameter names, password as
 * uppercase MD5 hex, the POST-with-query-params shape, the encrypted-API
 * header/cookie set, region ordering, and the field validations
 * (TOKEN = 24 hex, BLE KEY = 32 hex).
 */
class MiCloudClientTest {

    private lateinit var fake: FakeMiCloud

    @Before
    fun setUp() {
        fake = FakeMiCloud()
        fake.start()
    }

    @After
    fun tearDown() {
        fake.close()
    }

    private fun client(
        regions: List<String> = listOf("de"),
        preferredRegion: String? = null,
    ) = MiCloudClient(
        http = OkHttpClient(),
        accountBaseUrl = fake.accountBase,
        apiBaseUrl = { region -> fake.apiBase(region) },
        regions = regions,
        preferredRegion = preferredRegion,
        nowMillis = { 1_760_000_000_000L },
        userAgent = "test-agent-abcdefghijk APP/com.xiaomi.mihome APPV/10.5.201",
        deviceId = "abcdef",
    )

    private val auth = MiAuth(
        ssecurity = "ABEiM0RVZneImaq7zN3u/w==",
        userId = "424242",
        serviceToken = "SERVICE_TOKEN_1",
    )

    private val scaleToken = "0123456789abcdef01234567" // 24 hex = 12 bytes
    private val scaleBleKey = "00112233445566778899aabbccddeeff" // 32 hex = 16 bytes

    private fun scaleJson(token: String?): String {
        val tokenField = if (token != null) ""","token":"$token"""" else ""
        return """{"name":"Xiaomi Scale S200 1A2B","did":"blt.3.1001","model":"xiaomi.scales.ms113","mac":"AA:BB:CC:DD:EE:FF"$tokenField}"""
    }

    private fun md5Upper(value: String): String =
        MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02X".format(it) }

    // ------------------------------------------------------------------ login

    @Test
    fun loginHappyPathYieldsSessionAndFollowsReferenceWireShape() = runBlocking<Unit> {
        fake.serviceLoginBody = """&&&START&&&{"code":0,"_sign":"SIGN_FIXTURE"}"""
        fake.auth2Body = """
            {"code":0,"ssecurity":"SSEC_FROM_AUTH2","userId":"1234567",
             "cUserId":"cuser_dummy","passToken":"pass_dummy",
             "location":"${fake.accountBase}/sts","description":"ok"}
        """.trimIndent().replace("\n", "")

        val session = client().login("tester@example.com", "hunter2-secret")

        assertEquals("SSEC_FROM_AUTH2", session.ssecurity)
        assertEquals("1234567", session.userId)
        assertEquals("SERVICE_TOKEN_1", session.serviceToken)

        // step 1: GET serviceLogin?sid=xiaomiio&_json=true with userId cookie
        val step1 = fake.requests.first { it.requestUrl!!.encodedPath == "/pass/serviceLogin" }
        assertEquals("GET", step1.method)
        assertEquals("xiaomiio", step1.requestUrl!!.queryParameter("sid"))
        assertEquals("true", step1.requestUrl!!.queryParameter("_json"))
        val step1Cookies = step1.getHeader("Cookie")!!
        assertTrue(step1Cookies, step1Cookies.contains("userId=tester@example.com"))
        assertTrue(step1Cookies, step1Cookies.contains("sdkVersion=accountsdk-18.8.15"))
        assertTrue(step1Cookies, step1Cookies.contains("deviceId=abcdef"))

        // step 2: POST serviceLoginAuth2, params in the query, empty body
        val auth2 = fake.requests.first { it.requestUrl!!.encodedPath == "/pass/serviceLoginAuth2" }
        assertEquals("POST", auth2.method)
        assertEquals(0L, auth2.bodySize)
        val q = auth2.requestUrl!!
        assertEquals("xiaomiio", q.queryParameter("sid"))
        assertEquals(md5Upper("hunter2-secret"), q.queryParameter("hash"))
        assertEquals("DF09B9F54E5DB483DC5ECC24DBEB3177", q.queryParameter("hash"))
        assertEquals("https://sts.api.io.mi.com/sts", q.queryParameter("callback"))
        assertEquals("%3Fsid%3Dxiaomiio%26_json%3Dtrue", q.queryParameter("qs"))
        assertEquals("tester@example.com", q.queryParameter("user"))
        assertEquals("SIGN_FIXTURE", q.queryParameter("_sign"))
        assertEquals("true", q.queryParameter("_json"))
        assertEquals(
            "test-agent-abcdefghijk APP/com.xiaomi.mihome APPV/10.5.201",
            auth2.getHeader("User-Agent"),
        )

        // step 3: the STS location is visited and yields the serviceToken
        assertTrue(fake.requests.any { it.requestUrl!!.encodedPath == "/sts" })
    }

    @Test
    fun wrongPasswordThrowsMiLoginFailed() {
        fake.auth2Body = """{"code":70016,"description":"invalid username or password"}"""
        val error = assertThrows(MiLoginFailed::class.java) {
            runBlocking { client().login("tester@example.com", "wrong-password") }
        }
        assertTrue(error.message!!, error.message!!.contains("password"))
    }

    @Test
    fun captchaChallengeThrowsMiLoginBlocked() {
        fake.auth2Body =
            """{"code":27001,"captchaUrl":"https://account.xiaomi.com/checksec/captcha?sid=xiaomiio"}"""
        assertThrows(MiLoginBlocked::class.java) {
            runBlocking { client().login("tester@example.com", "hunter2-secret") }
        }
    }

    // ------------------------------------------------------------------- 2FA

    @Test
    fun secondFactorEmailFlowCompletesAndVerifiesTheUserTicket() = runBlocking<Unit> {
        fake.auth2Body =
            """{"code":0,"userId":"1234567","notificationUrl":"${fake.accountBase}/authStart?context=CTX_1"}"""
        fake.verifyEmailBody =
            """{"code":0,"location":"${fake.accountBase}/identity/result/check?context=CTX_1"}"""

        var prompt: String? = null
        val session = client().login("tester@example.com", "hunter2-secret") { text ->
            prompt = text
            "654321"
        }

        assertEquals("SSEC_FROM_2FA", session.ssecurity)
        assertEquals("SERVICE_TOKEN_1", session.serviceToken)
        assertEquals("1234567", session.userId)
        assertNotNull(prompt)
        val asked = prompt!!
        assertTrue(asked, asked.contains("email"))

        // The reference's step order.
        val flow = fake.requests.mapNotNull { it.requestUrl?.encodedPath }.filter {
            it == "/authStart" || it == "/identity/list" ||
                it == "/identity/auth/sendEmailTicket" || it == "/identity/auth/verifyEmail" ||
                it == "/identity/result/check" || it == "/pass/serviceLoginAuth2/end" ||
                it == "/sts"
        }
        assertEquals(
            listOf(
                "/authStart",
                "/identity/list",
                "/identity/auth/sendEmailTicket",
                "/identity/auth/verifyEmail",
                "/identity/result/check",
                "/pass/serviceLoginAuth2/end",
                "/sts",
            ),
            flow,
        )

        // The emailed code is POSTed as the `ticket` form field …
        val verify = fake.requests.first {
            it.requestUrl!!.encodedPath == "/identity/auth/verifyEmail"
        }
        val verifyBody = verify.body.clone().readUtf8()
        assertTrue(verifyBody, verifyBody.contains("ticket=654321"))
        assertTrue(verifyBody, verifyBody.contains("_json=true"))
        // … and the ick cookie captured from identity/list is echoed back.
        val send = fake.requests.first {
            it.requestUrl!!.encodedPath == "/identity/auth/sendEmailTicket"
        }
        val sendBody = send.body.clone().readUtf8()
        assertTrue(sendBody, sendBody.contains("ick=ICK_COOKIE_1"))

        // ssecurity came from the extension-pragma header of Auth2/end.
        assertTrue(fake.pragmaPaths.contains("/pass/serviceLoginAuth2/end"))
    }

    @Test
    fun secondFactorCancellationThrows() {
        fake.auth2Body =
            """{"code":0,"userId":"1234567","notificationUrl":"${fake.accountBase}/authStart?context=CTX_1"}"""
        assertThrows(MiSecondFactorCancelled::class.java) {
            runBlocking { client().login("tester@example.com", "hunter2-secret") { null } }
        }
    }

    @Test
    fun secondFactorWithoutVerifyLocationFallsBackToResultCheck() = runBlocking<Unit> {
        fake.auth2Body =
            """{"code":0,"userId":"1234567","notificationUrl":"${fake.accountBase}/authStart?context=CTX_1"}"""
        // verifyEmail answers 200 with an empty body: no location anywhere.
        fake.verifyEmailBody = ""

        val session = client().login("tester@example.com", "hunter2-secret") { "111222" }

        assertEquals("SSEC_FROM_2FA", session.ssecurity)
        assertEquals("SERVICE_TOKEN_1", session.serviceToken)
        assertTrue(fake.requests.any {
            it.requestUrl!!.encodedPath == "/identity/result/check"
        })
    }

    // ------------------------------------------------------------- extraction

    @Test
    fun extractScaleReturnsMacTokenAndBleKeyForTheS200() = runBlocking<Unit> {
        fake.gethome["de"] =
            """{"code":0,"result":{"homelist":[{"id":700000001,"name":"Home"}]}}"""
        fake.deviceList["de"] = """{"code":0,"result":{"device_info":[
            {"name":"Bulb","did":"blt.9.9","model":"yeelink.light.bulb1","mac":"11:22:33:44:55:66","token":"aaaaaaaaaaaaaaaaaaaaaaaa"},
            ${scaleJson(scaleToken)}
        ]}}"""
        fake.beaconKey["de"] =
            """{"code":0,"result":{"beaconkey":"$scaleBleKey"}}"""

        val scale = client().extractScale(auth)

        assertEquals(
            ScaleDevice(
                name = "Xiaomi Scale S200 1A2B",
                mac = "AA:BB:CC:DD:EE:FF",
                did = "blt.3.1001",
                model = "xiaomi.scales.ms113",
                tokenHex = scaleToken,
                bleKeyHex = scaleBleKey,
            ),
            scale,
        )

        // The beaconkey request carries the reference's exact data payload.
        val beacon = fake.decryptedData.first { it.first.endsWith("/v2/device/blt_get_beaconkey") }
        assertEquals("""{"did":"blt.3.1001","pdid":1}""", beacon.second)
        // … and the device list carries the reference's exact home payload.
        val list = fake.decryptedData.first { it.first.endsWith("/v2/home/home_device_list") }
        assertEquals(
            """{"home_owner": 424242,"home_id": 700000001,  "limit": 200,  "get_split_device": true, "support_smart_home": true}""",
            list.second,
        )
        // Every signed request verified against the reference's formulas.
        assertTrue(fake.allSignaturesValid)
    }

    @Test
    fun apiRequestsCarryRc4HeadersSigningFieldsAndAuthCookies() = runBlocking<Unit> {
        fake.gethome["de"] =
            """{"code":0,"result":{"homelist":[{"id":700000001,"name":"Home"}]}}"""
        fake.deviceList["de"] = """{"code":0,"result":{"device_info":[${scaleJson(scaleToken)}]}}"""

        client().extractScale(auth)

        val gethome = fake.requests.first {
            it.requestUrl!!.encodedPath.endsWith("/v2/homeroom/gethome")
        }
        assertEquals("POST", gethome.method)
        assertEquals(0L, gethome.bodySize)
        assertEquals("ENCRYPT-RC4", gethome.getHeader("MIOT-ENCRYPT-ALGORITHM"))
        assertEquals("PROTOCAL-HTTP2", gethome.getHeader("x-xiaomi-protocal-flag-cli"))
        assertEquals("identity", gethome.getHeader("Accept-Encoding"))
        assertEquals("application/x-www-form-urlencoded", gethome.getHeader("Content-Type"))
        assertEquals(
            "test-agent-abcdefghijk APP/com.xiaomi.mihome APPV/10.5.201",
            gethome.getHeader("User-Agent"),
        )
        // Encrypted fields travel as query parameters.
        val q = gethome.requestUrl!!
        assertNotNull(q.queryParameter("_nonce"))
        assertNotNull(q.queryParameter("signature"))
        assertNotNull(q.queryParameter("rc4_hash__"))
        assertEquals(auth.ssecurity, q.queryParameter("ssecurity"))
        // The reference's explicit cookie set.
        val cookie = gethome.getHeader("Cookie")!!
        for (pair in listOf(
            "userId=424242",
            "serviceToken=SERVICE_TOKEN_1",
            "yetAnotherServiceToken=SERVICE_TOKEN_1",
            "locale=en_GB",
            "timezone=GMT+02:00",
            "is_daylight=1",
            "dst_offset=3600000",
            "channel=MI_APP_STORE",
        )) {
            assertTrue(cookie, cookie.contains(pair))
        }
        assertTrue(fake.allSignaturesValid)
    }

    @Test
    fun extractScaleReturnsNullWhenTheAccountHasNoScale() = runBlocking<Unit> {
        fake.gethome["de"] =
            """{"code":0,"result":{"homelist":[{"id":700000001,"name":"Home"}]}}"""
        fake.deviceList["de"] = """{"code":0,"result":{"device_info":[
            {"name":"Bulb","did":"blt.9.9","model":"yeelink.light.bulb1","mac":"11:22:33:44:55:66","token":"aaaaaaaaaaaaaaaaaaaaaaaa"}
        ]}}"""
        assertNull(client().extractScale(auth))
    }

    @Test
    fun extractScaleThrowsMiFieldErrorWhenTokenMissing() {
        fake.gethome["de"] =
            """{"code":0,"result":{"homelist":[{"id":700000001,"name":"Home"}]}}"""
        fake.deviceList["de"] = """{"code":0,"result":{"device_info":[${scaleJson(null)}]}}"""
        val error = assertThrows(MiFieldError::class.java) {
            runBlocking { client().extractScale(auth) }
        }
        assertEquals("token", error.field)
        assertEquals("blt.3.1001", error.did)
    }

    @Test
    fun extractScaleRejectsTwentyThreeHexToken() {
        // 23 hex chars — one short of the required 24-hex TOKEN.
        fake.gethome["de"] =
            """{"code":0,"result":{"homelist":[{"id":700000001,"name":"Home"}]}}"""
        fake.deviceList["de"] =
            """{"code":0,"result":{"device_info":[${scaleJson("0123456789abcdef0123456")}]}}"""
        val error = assertThrows(MiFieldError::class.java) {
            runBlocking { client().extractScale(auth) }
        }
        assertEquals("token", error.field)
    }

    @Test
    fun extractScaleThrowsMiFieldErrorWhenBeaconKeyIsNotThirtyTwoHex() {
        fake.gethome["de"] =
            """{"code":0,"result":{"homelist":[{"id":700000001,"name":"Home"}]}}"""
        fake.deviceList["de"] = """{"code":0,"result":{"device_info":[${scaleJson(scaleToken)}]}}"""
        fake.beaconKey["de"] =
            """{"code":0,"result":{"beaconkey":"00112233445566778899aabbccddee"}}""" // 31 hex
        val error = assertThrows(MiFieldError::class.java) {
            runBlocking { client().extractScale(auth) }
        }
        assertEquals("beaconkey", error.field)
        assertEquals("blt.3.1001", error.did)
    }

    @Test
    fun extractScaleYieldsNullBleKeyWhenBeaconKeyAbsent() = runBlocking<Unit> {
        fake.gethome["de"] =
            """{"code":0,"result":{"homelist":[{"id":700000001,"name":"Home"}]}}"""
        fake.deviceList["de"] = """{"code":0,"result":{"device_info":[${scaleJson(scaleToken)}]}}"""
        fake.beaconKey["de"] = """{"code":0,"result":{}}"""

        val scale = client().extractScale(auth)

        assertNotNull(scale)
        assertEquals(scaleToken, scale!!.tokenHex)
        assertNull(scale.bleKeyHex)
    }

    @Test
    fun preferredRegionIsTriedFirstThenTheRestOfTheServerList() = runBlocking<Unit> {
        // The scale lives in cn; us is tried first (preferred), then de, then cn.
        fake.gethome["cn"] =
            """{"code":0,"result":{"homelist":[{"id":700000001,"name":"Home"}]}}"""
        fake.deviceList["cn"] = """{"code":0,"result":{"device_info":[${scaleJson(scaleToken)}]}}"""

        val scale = client(regions = listOf("de", "cn"), preferredRegion = "us")
            .extractScale(auth)
        assertNotNull(scale)

        val attempts = fake.requests
            .mapNotNull { it.requestUrl?.encodedPath }
            .filter { it.endsWith("/v2/homeroom/gethome") }
        assertEquals(
            listOf(
                "/regions/us/app/v2/homeroom/gethome",
                "/regions/de/app/v2/homeroom/gethome",
                "/regions/cn/app/v2/homeroom/gethome",
            ),
            attempts,
        )
    }
}
