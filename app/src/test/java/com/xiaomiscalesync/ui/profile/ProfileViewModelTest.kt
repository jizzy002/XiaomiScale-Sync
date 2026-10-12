package com.xiaomiscalesync.ui.profile

import com.xiaomiscalesync.data.Category
import com.xiaomiscalesync.data.FakeDao
import com.xiaomiscalesync.data.GarminStatus
import com.xiaomiscalesync.data.KeyReader
import com.xiaomiscalesync.data.KeyVault
import com.xiaomiscalesync.data.NewWeighIn
import com.xiaomiscalesync.data.Settings
import com.xiaomiscalesync.data.SettingsSource
import com.xiaomiscalesync.data.SettingsWriter
import com.xiaomiscalesync.data.WeighInRepository
import com.xiaomiscalesync.data.WeighInSource
import com.xiaomiscalesync.proto.ProfileJson
import com.xiaomiscalesync.proto.toProfile
import com.xiaomiscalesync.ui.onboarding.ExtractedKeys
import com.xiaomiscalesync.ui.onboarding.GarminSignIn
import com.xiaomiscalesync.ui.onboarding.MiKeyExtractor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the Profile tab (plan Task 17 + owner request
 * 2026-10-13: logins and login data): profile edits rewrite Settings and
 * reach the next `ProfileJson.build`; Garmin re-login re-queues rows the
 * expired sign-in blocked; re-extraction stores only valid keys; key
 * values stay hidden until an explicit reveal.
 */
class ProfileViewModelTest {

    /** In-memory Settings: one object is both the read and write seam. */
    private class MemorySettings(var s: Settings = Settings.DEFAULT.copy(onboarded = true)) :
        SettingsSource, SettingsWriter {
        override suspend fun current() = s
        override suspend fun setOnboarded(v: Boolean) { s = s.copy(onboarded = v) }
        override suspend fun setBackfillCutoff(v: Long?) { s = s.copy(backfillCutoff = v) }
        override suspend fun setMid(v: String) { s = s.copy(mid = v) }
        override suspend fun setProfile(heightCm: Int, age: Int, sex: Int) {
            s = s.copy(heightCm = heightCm, age = age, sex = sex)
        }
        override suspend fun setMiAccount(v: String) { s = s.copy(miAccount = v) }
        override suspend fun setGarminEmail(v: String) { s = s.copy(garminEmail = v) }
    }

    private class MemoryKeys(
        var mac: String? = "AA:BB:CC:DD:12:34",
        var token: String? = "0123456789abcdef01234567",
        var bleKey: String? = "0123456789abcdef0123456789abcdef",
    ) : KeyVault, KeyReader {
        override fun saveMac(mac: String) { this.mac = mac }
        override fun saveToken(tokenHex: String) { token = tokenHex }
        override fun saveBleKey(bleKeyHex: String) { bleKey = bleKeyHex }
        override fun hasMac() = mac != null
        override fun hasToken() = token != null
        override fun hasBleKey() = bleKey != null
        override fun mac() = mac
        override fun token() = token
        override fun bleKey() = bleKey
    }

    private class FakeMi(var result: ExtractedKeys) : MiKeyExtractor {
        override suspend fun extractKeys(
            username: String,
            password: String,
            onCaptcha: (suspend (image: ByteArray, captchaUrl: String) -> String?)?,
            onSecondFactor: suspend (prompt: String) -> String?,
        ) = result
    }

    private class FakeGarmin(var fail: Exception? = null) : GarminSignIn {
        var signedInAs: String? = null
        override suspend fun signIn(email: String, password: String, mfa: suspend (String) -> String) {
            fail?.let { throw it }
            signedInAs = email
        }
    }

    private inner class Harness(
        val settings: MemorySettings = MemorySettings(),
        val keys: MemoryKeys = MemoryKeys(),
        val mi: FakeMi = FakeMi(ExtractedKeys("AA:BB:CC:DD:EE:FF", "ffffffffffffffffffffffff", null, "Scale", "ms111")),
        val garmin: FakeGarmin = FakeGarmin(),
    ) {
        val dao = FakeDao()
        val repo = WeighInRepository(dao, settings, nowUnix = { 1_790_100_000 })
        var pushes = 0
        var garminTokens = true
        val vm = ProfileViewModel(
            settingsSource = settings,
            settings = settings,
            keys = keys,
            vault = keys,
            mi = mi,
            garmin = garmin,
            garminSignedIn = { garminTokens },
            repo = repo,
            enqueuePush = { pushes++ },
        )
    }

    // ------------------------------------------------------------ profile

    @Test
    fun profileEditRewritesSettingsAndTheNextProfileJson() = runBlocking {
        val h = Harness()
        h.vm.load()
        h.vm.setHeightCm("176")
        h.vm.setAge("41")
        h.vm.setSex(2)
        h.vm.setMid("3")

        h.vm.saveProfile()

        val s = h.settings.s
        assertEquals(176, s.heightCm)
        assertEquals(41, s.age)
        assertEquals(2, s.sex)
        assertEquals("3", s.mid)
        val p = s.toProfile(8420)
        assertEquals(
            """{"mid":"3","duid":1,"uc":1,"ow":1,"unit":1,"time":1,"ud":[{"duid":1,"ut":1,"age":41,"sex":2,"hi":176,"wt":8420}]}""",
            ProfileJson.build(p.mid, p.age, p.sex, p.heightCm, p.weightGrams, nowUnix = 1),
        )
        assertNull(h.vm.state.value.error)
    }

    @Test
    fun invalidProfileIsRejectedAndNothingIsWritten() = runBlocking {
        val h = Harness()
        h.vm.load()
        h.vm.setHeightCm("17") // not plausible cm
        h.vm.setAge("41")

        h.vm.saveProfile()

        assertNotNull(h.vm.state.value.error)
        assertNull(h.settings.s.heightCm)
    }

    @Test
    fun memberIdMustBeAPositiveNumber() = runBlocking {
        for (bad in listOf("abc", "0", "-3", "1.5")) {
            val h = Harness()
            h.vm.load()
            h.vm.setHeightCm("176")
            h.vm.setAge("41")
            h.vm.setMid(bad)

            h.vm.saveProfile()

            assertNotNull("'$bad' must be rejected", h.vm.state.value.error)
            assertNull("nothing written for '$bad'", h.settings.s.heightCm)
            assertEquals(Settings.DEFAULT_MID, h.settings.s.mid)
        }
    }

    @Test
    fun blankMemberIdSavesAsOne() = runBlocking {
        val h = Harness(settings = MemorySettings(Settings.DEFAULT.copy(onboarded = true, mid = "7")))
        h.vm.load()
        h.vm.setHeightCm("176")
        h.vm.setAge("41")
        h.vm.setMid("  ")

        h.vm.saveProfile()

        assertNull(h.vm.state.value.error)
        assertEquals("1", h.settings.s.mid)
        assertEquals("1", h.vm.state.value.mid)
    }

    @Test
    fun loadShowsSavedAnswersAndAccounts() = runBlocking {
        val h = Harness(
            settings = MemorySettings(
                Settings.DEFAULT.copy(
                    onboarded = true, heightCm = 180, age = 34, sex = 1, mid = "1",
                    miAccount = "owner@mi.com", garminEmail = "owner@garmin.com",
                ),
            ),
        )
        h.vm.load()
        val st = h.vm.state.value
        assertEquals("180", st.heightCm)
        assertEquals("34", st.age)
        assertEquals("owner@mi.com", st.miAccount)
        assertEquals("owner@garmin.com", st.garminEmail)
        assertTrue(st.garminSignedIn)
        assertEquals("AA:BB:CC:DD:**:**", st.macMasked)
        assertTrue(st.tokenOk && st.bleKeyOk)
    }

    // ------------------------------------------------------------- reveal

    @Test
    fun keyValuesStayHiddenUntilRevealed() = runBlocking {
        val h = Harness()
        h.vm.load()
        assertNull("keys are never in state by default", h.vm.state.value.revealed)

        h.vm.reveal()
        val r = h.vm.state.value.revealed!!
        assertEquals("AA:BB:CC:DD:12:34", r.mac)
        assertEquals("0123456789abcdef01234567", r.token)
        assertEquals("0123456789abcdef0123456789abcdef", r.bleKey)

        h.vm.hide()
        assertNull(h.vm.state.value.revealed)
    }

    // ------------------------------------------------------ garmin re-login

    @Test
    fun garminReloginRequeuesAuthBlockedRowsAndPushes() = runBlocking {
        val h = Harness()
        h.repo.merge(listOf(NewWeighIn(8420, 1_790_000_000, WeighInSource.LIVE, null, 0, 1)))
        val id = h.dao.rows.value.single().id
        h.repo.setGarminStatus(listOf(id), GarminStatus.AUTH_REQUIRED)
        h.vm.load()
        assertTrue(h.vm.state.value.authExpired)

        h.vm.garminSignIn("new@garmin.com", "pw") { "123456" }

        assertEquals(GarminStatus.PENDING, h.dao.rows.value.single().garminStatus)
        assertEquals(Category.MINE, h.dao.rows.value.single().category)
        assertEquals(1, h.pushes)
        assertEquals("new@garmin.com", h.settings.s.garminEmail)
        assertFalse(h.vm.state.value.authExpired)
        assertTrue(h.vm.state.value.garminSignedIn)
    }

    @Test
    fun failedGarminLoginChangesNothing() = runBlocking {
        val h = Harness(garmin = FakeGarmin(fail = IllegalStateException("bad credentials")))
        h.vm.load()

        h.vm.garminSignIn("new@garmin.com", "pw") { "" }

        assertEquals("bad credentials", h.vm.state.value.error)
        assertNull(h.settings.s.garminEmail)
        assertEquals(0, h.pushes)
    }

    // ------------------------------------------------------ mi re-extract

    @Test
    fun reExtractStoresOnlyValidKeysAndRecordsTheAccount() = runBlocking {
        val keys = MemoryKeys()
        // Cloud returned a new MAC + TOKEN but no BLE KEY.
        val h = Harness(keys = keys)
        h.vm.load()

        h.vm.reExtract("owner@mi.com", "pw")

        assertEquals("AA:BB:CC:DD:EE:FF", keys.mac)
        assertEquals("ffffffffffffffffffffffff", keys.token)
        assertEquals("previous BLE KEY kept", "0123456789abcdef0123456789abcdef", keys.bleKey)
        assertEquals("owner@mi.com", h.settings.s.miAccount)
        assertNotNull("missing BLE KEY is reported", h.vm.state.value.error)
    }
}
