package com.xiaomiscalesync.ui.onboarding

import com.xiaomiscalesync.data.KeyVault
import com.xiaomiscalesync.data.SettingsWriter
import com.xiaomiscalesync.xiaomi.MiLoginFailed
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the onboarding wizard's stateful decisions (plan
 * Task 14, step 1): manual-paste key validation, the Member ID default,
 * the history-question → backfill-cutoff mapping, and `onboarded`
 * flipping true exactly once — only on completion. Fakes stand in for
 * `SettingsStore`, `Secrets`, `MiCloudClient` and `GarminClient`; no
 * Android runtime, no Robolectric (same choice as [RepositoryFlowTest]).
 */
class OnboardingViewModelTest {

    private val now = 1_790_000_999L

    // ------------------------------------------------------------- fakes

    /** Records every writer call so tests can assert order and idempotence. */
    private class FakeSettings : SettingsWriter {
        val onboardedCalls = mutableListOf<Boolean>()
        val cutoffCalls = mutableListOf<Long?>()
        val midCalls = mutableListOf<String>()

        override suspend fun setOnboarded(v: Boolean) {
            onboardedCalls += v
            order += "onboarded"
        }

        override suspend fun setBackfillCutoff(v: Long?) {
            cutoffCalls += v
        }

        override suspend fun setMid(v: String) {
            midCalls += v
        }

        val profileCalls = mutableListOf<Triple<Int, Int, Int>>()

        /** Every writer call in order, so "profile before onboarded" is assertable. */
        val order = mutableListOf<String>()

        override suspend fun setProfile(heightCm: Int, age: Int, sex: Int) {
            profileCalls += Triple(heightCm, age, sex)
            order += "profile"
        }
    }

    private class FakeVault(
        presetMac: String? = null,
        presetToken: String? = null,
        presetBleKey: String? = null,
    ) : KeyVault {
        var mac: String? = presetMac
        var token: String? = presetToken
        var bleKey: String? = presetBleKey

        override fun saveMac(mac: String) {
            this.mac = mac
        }

        override fun saveToken(tokenHex: String) {
            this.token = tokenHex
        }

        override fun saveBleKey(bleKeyHex: String) {
            this.bleKey = bleKeyHex
        }

        override fun hasMac(): Boolean = mac != null

        override fun hasToken(): Boolean = token != null

        override fun hasBleKey(): Boolean = bleKey != null
    }

    private class FakeMi(
        private val result: ExtractedKeys = ExtractedKeys(
            mac = "AA:BB:CC:DD:EE:FF",
            tokenHex = "0123456789abcdef01234567",
            bleKeyHex = "0123456789abcdef0123456789abcdef",
            scaleName = "Smart Scale",
            model = "XMTZC05",
        ),
        private val failure: Exception? = null,
    ) : MiKeyExtractor {
        var calls = 0

        override suspend fun extractKeys(
            username: String,
            password: String,
            onCaptcha: (suspend (image: ByteArray, captchaUrl: String) -> String?)?,
            onSecondFactor: suspend (prompt: String) -> String?,
        ): ExtractedKeys {
            calls++
            failure?.let { throw it }
            return result
        }
    }

    private class FakeGarmin : GarminSignIn {
        var calls = 0

        override suspend fun signIn(
            email: String,
            password: String,
            mfa: suspend (method: String) -> String,
        ) {
            calls++
        }
    }

    private fun vm(
        settings: FakeSettings,
        vault: FakeVault,
        mi: FakeMi = FakeMi(),
        garmin: FakeGarmin = FakeGarmin(),
    ) = OnboardingViewModel(
        settings = settings,
        secrets = vault,
        mi = mi,
        garmin = garmin,
        nowUnix = { now },
    )

    /** Drives the wizard through steps 1–4 up to (but not including) Finish. */
    private suspend fun OnboardingViewModel.runToDone(historyChecked: Boolean) {
        extractKeys("owner@example.com", "pw")
        nextFromKeys()
        setHeightCm("180")
        setAge("34")
        setSex(1)
        nextFromProfile()
        setHistoryAlreadySynced(historyChecked)
        nextFromHistory()
        garminSignIn("owner@example.com", "pw") { "123456" }
        nextFromGarmin()
    }

    // ------------------------------------- (a) manual-paste key validation

    @Test
    fun manualPasteRejectsTwentyThreeHexToken() {
        val settings = FakeSettings()
        val vault = FakeVault()
        val vm = vm(settings, vault)

        vm.setManualMode(true)
        vm.setManualMac("AA:BB:CC:DD:EE:FF")
        vm.setManualToken("a".repeat(23)) // one hex char short
        vm.setManualBleKey("b".repeat(32))
        vm.submitManualKeys()

        val state = vm.state.value
        assertNotNull("a 23-hex TOKEN must be rejected", state.error)
        assertFalse(state.tokenOk)
        assertFalse(state.macOk)
        assertFalse(state.bleKeyOk)
        assertEquals(OnboardingStep.MI_KEYS, state.step)
        assertNull("nothing may be stored from a rejected paste", vault.mac)
        assertNull(vault.token)
        assertNull(vault.bleKey)
    }

    @Test
    fun manualPasteRejectsBleKeyThatIsNotThirtyTwoHex() {
        val vault = FakeVault()
        val vm = vm(FakeSettings(), vault)

        vm.setManualMode(true)
        vm.setManualMac("AA:BB:CC:DD:EE:FF")
        vm.setManualToken("0123456789abcdef01234567")
        vm.setManualBleKey("c".repeat(31)) // one hex char short
        vm.submitManualKeys()

        val state = vm.state.value
        assertNotNull(state.error)
        assertFalse(state.bleKeyOk)
        assertNull(vault.bleKey)
        assertEquals(OnboardingStep.MI_KEYS, state.step)
    }

    @Test
    fun manualPasteAcceptsTwentyFourHexTokenAndThirtyTwoHexBleKey() {
        val settings = FakeSettings()
        val vault = FakeVault()
        val vm = vm(settings, vault)

        val token = "0123456789ABCDEF01234567" // 24 hex, uppercase accepted
        val bleKey = "0123456789abcdef0123456789abcdef" // 32 hex
        vm.setManualMode(true)
        vm.setManualMac("aa-bb-cc-dd-ee-ff") // dashes accepted, normalized on save
        vm.setManualToken(token)
        vm.setManualBleKey(bleKey)
        vm.submitManualKeys()

        val state = vm.state.value
        assertNull(state.error)
        assertTrue(state.macOk)
        assertTrue(state.tokenOk)
        assertTrue(state.bleKeyOk)
        assertEquals("AA:BB:CC:DD:EE:FF", vault.mac)
        assertEquals(token, vault.token)
        assertEquals(bleKey, vault.bleKey)

        vm.nextFromKeys()
        assertEquals(OnboardingStep.PROFILE, vm.state.value.step)
    }

    // ------------------------------------------------------ (b) mid default

    @Test
    fun memberIdDefaultsToOne() {
        val vm = vm(FakeSettings(), FakeVault())
        assertEquals("1", vm.state.value.mid)
    }

    @Test
    fun blankMemberIdFallsBackToDefaultAtCompletion() = runBlocking {
        val settings = FakeSettings()
        val vm = vm(settings, FakeVault())

        vm.runToDone(historyChecked = false)
        vm.setMid("") // owner cleared the field
        vm.complete()

        assertEquals(listOf("1"), settings.midCalls)
    }

    // ------------------------------------------- (c) backfill flag mapping

    @Test
    fun historyAlreadySyncedSetsBackfillCutoffToNow() = runBlocking {
        val settings = FakeSettings()
        val vm = vm(settings, FakeVault())

        vm.runToDone(historyChecked = true)
        assertTrue("completion has not run yet", settings.cutoffCalls.isEmpty())

        vm.complete()
        assertEquals(listOf<Long?>(now), settings.cutoffCalls)
    }

    @Test
    fun historyNotSyncedClearsBackfillCutoff() = runBlocking {
        val settings = FakeSettings()
        val vm = vm(settings, FakeVault())

        vm.runToDone(historyChecked = false)
        vm.complete()

        assertEquals(listOf<Long?>(null), settings.cutoffCalls)
    }

    // ----------------------------------- (d) onboarded flips on completion

    @Test
    fun onboardedFlipsTrueOnlyOnCompletion() = runBlocking {
        val settings = FakeSettings()
        val vm = vm(settings, FakeVault())

        vm.runToDone(historyChecked = true)
        assertEquals(OnboardingStep.DONE, vm.state.value.step)
        assertTrue("no writer may flip onboarded before Finish", settings.onboardedCalls.isEmpty())
        assertFalse(vm.state.value.completed)

        vm.complete()
        assertEquals(listOf(true), settings.onboardedCalls)
        assertTrue(vm.state.value.completed)
        assertEquals(listOf("1"), settings.midCalls)
    }

    @Test
    fun completionIsIdempotent() = runBlocking {
        val settings = FakeSettings()
        val vm = vm(settings, FakeVault())

        vm.runToDone(historyChecked = true)
        vm.complete()
        vm.complete() // double-tap / re-entry must be a no-op

        assertEquals(listOf(true), settings.onboardedCalls)
        assertEquals(1, settings.midCalls.size)
        assertEquals(1, settings.cutoffCalls.size)
        assertEquals(1, settings.profileCalls.size)
    }

    @Test
    fun completionPersistsProfileBeforeOnboarded() = runBlocking {
        val settings = FakeSettings()
        val vm = vm(settings, FakeVault())

        vm.runToDone(historyChecked = false)
        assertTrue("profile is written only at Finish", settings.profileCalls.isEmpty())

        vm.complete()
        assertEquals(listOf(Triple(180, 34, 1)), settings.profileCalls)
        // The main screen's first sync reads the profile as soon as
        // onboarded flips — it must already be on disk by then.
        assertEquals(listOf("profile", "onboarded"), settings.order)
    }

    // ------------------------------------------------------- extraction path

    @Test
    fun freshViewModelSeedsKeyStatusFromVaultAndUnblocksNext() {
        // Rotation / process death constructs a fresh VM at MI_KEYS while
        // valid keys already sit in the vault.
        val vault = FakeVault(
            presetMac = "AA:BB:CC:DD:EE:FF",
            presetToken = "0123456789abcdef01234567",
            presetBleKey = "0123456789abcdef0123456789abcdef",
        )
        val vm = vm(FakeSettings(), vault)

        val state = vm.state.value
        assertEquals(OnboardingStep.MI_KEYS, state.step)
        assertTrue("stored keys must show as present", state.macOk)
        assertTrue(state.tokenOk)
        assertTrue(state.bleKeyOk)
        assertNull(state.error)

        vm.nextFromKeys() // disabled/blocked before the fix
        assertEquals(OnboardingStep.PROFILE, vm.state.value.step)
    }

    @Test
    fun partialVaultSeedsPartialChecksAndKeepsNextBlocked() {
        val vault = FakeVault(
            presetMac = "AA:BB:CC:DD:EE:FF",
            presetToken = "0123456789abcdef01234567",
        )
        val vm = vm(FakeSettings(), vault)

        val state = vm.state.value
        assertTrue(state.macOk)
        assertTrue(state.tokenOk)
        assertFalse("BLE KEY absent must stay unchecked", state.bleKeyOk)

        vm.nextFromKeys()
        assertEquals(OnboardingStep.MI_KEYS, vm.state.value.step)
    }

    @Test
    fun nonSecretAnswersSurviveARestart() = runBlocking {
        val vm = vm(FakeSettings(), FakeVault())
        vm.runToDone(historyChecked = true)

        // Rotation: the answers go through saved-instance state …
        val saved = vm.answers()
        // … and a brand-new VM applies them on top of vault-seeded flags.
        val fresh = vm(FakeSettings(), FakeVault())
        fresh.restore(saved)

        val state = fresh.state.value
        assertEquals(OnboardingStep.DONE, state.step)
        assertEquals("180", state.heightCm)
        assertEquals("34", state.age)
        assertEquals(1, state.sex)
        assertEquals("1", state.mid)
        assertTrue(state.historyAlreadySynced)
        assertTrue(state.garminSignedIn)
        assertEquals("owner@example.com", state.garminUser)
        assertFalse("completion itself is never restored", state.completed)
        assertNull(state.error)
    }

    @Test
    fun extractionStoresAllThreeKeysAndShowsGreenChecks() = runBlocking {
        val vault = FakeVault()
        val vm = vm(FakeSettings(), vault)

        vm.extractKeys("owner@example.com", "pw")

        val state = vm.state.value
        assertNull(state.error)
        assertTrue(state.macOk)
        assertTrue(state.tokenOk)
        assertTrue(state.bleKeyOk)
        assertEquals("AA:BB:CC:DD:EE:FF", vault.mac)
        assertEquals("0123456789abcdef01234567", vault.token)
        assertEquals("0123456789abcdef0123456789abcdef", vault.bleKey)
    }

    @Test
    fun failedExtractionReportsErrorAndStaysOnKeysStep() = runBlocking {
        val vm = vm(
            FakeSettings(),
            FakeVault(),
            mi = FakeMi(failure = MiLoginFailed("bad password")),
        )

        vm.extractKeys("owner@example.com", "wrong")

        val state = vm.state.value
        assertNotNull(state.error)
        assertFalse(state.macOk)
        assertEquals(OnboardingStep.MI_KEYS, state.step)
    }

    @Test
    fun garminSignInIsRequiredBeforeTheDoneStep() = runBlocking {
        val settings = FakeSettings()
        val garmin = FakeGarmin()
        val vm = vm(settings, FakeVault(), garmin = garmin)

        // Walk steps 1–3 only, then try to jump to DONE unsigned-in.
        vm.extractKeys("owner@example.com", "pw")
        vm.nextFromKeys()
        vm.setHeightCm("180")
        vm.setAge("34")
        vm.nextFromProfile()
        vm.nextFromHistory()
        vm.nextFromGarmin()

        assertEquals(OnboardingStep.GARMIN, vm.state.value.step)
        assertNotNull(vm.state.value.error)

        vm.garminSignIn("owner@example.com", "pw") { "123456" }
        assertEquals(1, garmin.calls)
        vm.nextFromGarmin()
        assertEquals(OnboardingStep.DONE, vm.state.value.step)

        vm.complete()
        assertEquals(listOf(true), settings.onboardedCalls)
    }
}
