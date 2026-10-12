package com.xiaomiscalesync.ui.onboarding

import com.xiaomiscalesync.data.KeyFormat
import com.xiaomiscalesync.data.KeyVault
import com.xiaomiscalesync.data.Settings
import com.xiaomiscalesync.data.SettingsWriter
import com.xiaomiscalesync.garmin.GarminClient
import com.xiaomiscalesync.garmin.TokenStore
import com.xiaomiscalesync.xiaomi.MiCloudClient
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient

/** The wizard's five steps (spec order). */
enum class OnboardingStep { MI_KEYS, PROFILE, HISTORY, GARMIN, DONE }

/**
 * Everything the onboarding UI renders — one immutable snapshot behind a
 * [StateFlow] so the ViewModel stays JVM-testable without Compose.
 *
 * Secrets discipline (mirrors the spike's rule): this state never carries
 * key material — only *presence* flags (the green checks) and the field
 * drafts the owner typed. The stored MAC/TOKEN/BLE KEY live in
 * `Secrets` and are never logged.
 */
data class OnboardingState(
    val step: OnboardingStep = OnboardingStep.MI_KEYS,
    val busy: Boolean = false,
    /** Last step-level failure, sanitized (MAC-shaped fragments masked). */
    val error: String? = null,

    // Step 1 — key status (green checks; values live in Secrets only).
    val macOk: Boolean = false,
    val tokenOk: Boolean = false,
    val bleKeyOk: Boolean = false,
    val foundScale: String? = null,
    val manualMode: Boolean = false,
    val manualMac: String = "",
    val manualToken: String = "",
    val manualBleKey: String = "",

    // Step 2 — profile (kg is fixed in v1; only these fields are edited).
    val heightCm: String = "",
    val age: String = "",
    val sex: Int = 1,
    val mid: String = Settings.DEFAULT_MID,

    // Step 3 — the history question (default off).
    val historyAlreadySynced: Boolean = false,

    // Step 4 — Garmin sign-in.
    val garminUser: String = "",
    val garminSignedIn: Boolean = false,

    // Step 5 — Finish pressed, settings written.
    val completed: Boolean = false,
)

/**
 * The non-secret wizard answers that must survive rotation and process
 * death (saved-instance state, see `AppNavigation.wizardSaver`).
 *
 * Deliberately excludes everything secret: typed passwords never enter
 * [OnboardingState] at all (screen-local `remember`), manual key drafts
 * are left out on purpose — key *presence* re-derives from [KeyVault] at
 * construction, and the stored bytes stay in [com.xiaomiscalesync.data.Secrets].
 */
data class WizardAnswers(
    val step: OnboardingStep,
    val manualMode: Boolean,
    val heightCm: String,
    val age: String,
    val sex: Int,
    val mid: String,
    val historyAlreadySynced: Boolean,
    val garminUser: String,
    val garminSignedIn: Boolean,
)

/** What `MiCloudClient.login` + `extractScale` yield for the wizard. */
data class ExtractedKeys(
    val mac: String,
    val tokenHex: String?,
    val bleKeyHex: String?,
    val scaleName: String,
    val model: String,
)

/** Read seam over `MiCloudClient` — a fake signs the wizard tests in. */
interface MiKeyExtractor {
    suspend fun extractKeys(
        username: String,
        password: String,
        onCaptcha: (suspend (image: ByteArray, captchaUrl: String) -> String?)?,
        onSecondFactor: suspend (prompt: String) -> String?,
    ): ExtractedKeys
}

/** Read seam over `GarminClient.login` — a fake signs the wizard tests in. */
interface GarminSignIn {
    suspend fun signIn(email: String, password: String, mfa: suspend (method: String) -> String)
}

/** No scale of ours is listed in the Mi Cloud account. */
class ScaleNotListedException :
    IllegalStateException("no Xiaomi scale is listed in this account")

/**
 * Onboarding wizard logic — the three stateful decisions plan Task 14
 * pins with tests, plus the step machine around them:
 *
 * 1. manual-paste key validation (TOKEN exactly 24 hex, BLE KEY exactly
 *    32 hex — all-or-nothing, nothing stored from a rejected paste);
 * 2. Member ID defaults to `"1"`;
 * 3. history-question mapping (checked → `backfillCutoff = now`,
 *    unchecked → `backfillCutoff = null`);
 * 4. `onboarded` flips true only when [complete] runs, idempotently.
 *
 * Pure Kotlin: Android (`SettingsStore`, `Secrets`, OkHttp clients) plugs
 * in through [SettingsWriter] / [KeyVault] / [MiKeyExtractor] /
 * [GarminSignIn], so every decision above runs in JVM unit tests.
 * Class-level rule: **no key material in any state field or message** —
 * error text is sanitized (MAC-shaped fragments masked) like the spike's
 * log pane.
 */
class OnboardingViewModel(
    private val settings: SettingsWriter,
    private val secrets: KeyVault,
    private val mi: MiKeyExtractor,
    private val garmin: GarminSignIn,
    private val nowUnix: () -> Long = { Clock.systemUTC().instant().epochSecond },
) {

    private val _state = MutableStateFlow(
        OnboardingState(
            // Re-entry seeding (rotation / process death / relaunch): a
            // fresh VM must show already-stored keys as present so the
            // green checks are lit and "Next" is unblocked without a
            // second Mi sign-in (2FA again) or a full re-paste.
            macOk = secrets.hasMac(),
            tokenOk = secrets.hasToken(),
            bleKeyOk = secrets.hasBleKey(),
        ),
    )
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    /**
     * Snapshot of the non-secret answers for saved-instance state
     * (nothing secret is included — see [WizardAnswers]).
     */
    fun answers(): WizardAnswers = _state.value.let {
        WizardAnswers(
            step = it.step,
            manualMode = it.manualMode,
            heightCm = it.heightCm,
            age = it.age,
            sex = it.sex,
            mid = it.mid,
            historyAlreadySynced = it.historyAlreadySynced,
            garminUser = it.garminUser,
            garminSignedIn = it.garminSignedIn,
        )
    }

    /**
     * Applies answers restored from saved-instance state onto this fresh
     * VM. Key flags are intentionally untouched: they were seeded from
     * [KeyVault] at construction and the vault — not the snapshot — is
     * the source of truth for key presence.
     */
    fun restore(answers: WizardAnswers) = update {
        it.copy(
            step = answers.step,
            manualMode = answers.manualMode,
            heightCm = answers.heightCm,
            age = answers.age,
            sex = answers.sex,
            mid = answers.mid,
            historyAlreadySynced = answers.historyAlreadySynced,
            garminUser = answers.garminUser,
            garminSignedIn = answers.garminSignedIn,
            error = null,
        )
    }

    private fun update(f: (OnboardingState) -> OnboardingState) {
        _state.value = f(_state.value)
    }

    // ------------------------------------------------------------- step 1

    fun setManualMode(on: Boolean) = update {
        it.copy(manualMode = on, error = null)
    }

    fun setManualMac(value: String) = update { it.copy(manualMac = value) }

    fun setManualToken(value: String) = update { it.copy(manualToken = value) }

    fun setManualBleKey(value: String) = update { it.copy(manualBleKey = value) }

    /**
     * Validates the pasted trio and stores it — all-or-nothing, so a
     * rejected paste (e.g. a 23-hex TOKEN) leaves [KeyVault] untouched
     * and the wizard on step 1 with a field-level error.
     */
    fun submitManualKeys() {
        val state = _state.value
        val mac = state.manualMac.trim()
        val token = state.manualToken.trim()
        val bleKey = state.manualBleKey.trim()
        val problem = when {
            !KeyFormat.isMac(mac) -> "MAC must be 6 bytes of hex, e.g. AA:BB:CC:DD:EE:FF"
            !KeyFormat.isToken(token) -> "TOKEN must be exactly 24 hex characters"
            !KeyFormat.isBleKey(bleKey) -> "BLE KEY must be exactly 32 hex characters"
            else -> null
        }
        if (problem != null) {
            update { it.copy(error = problem) }
            return
        }
        secrets.saveMac(KeyFormat.normalizeMac(mac))
        secrets.saveToken(token)
        secrets.saveBleKey(bleKey)
        update {
            it.copy(
                macOk = true,
                tokenOk = true,
                bleKeyOk = true,
                error = null,
            )
        }
    }

    /**
     * Step 1 extraction: signs in to the Mi Cloud (2FA/captcha dialogs
     * answer through [onCaptcha]/[onSecondFactor]), finds the scale, and
     * stores whatever keys are valid — each green check reflects what was
     * actually saved. A missing BLE KEY blocks "Next" (it is required to
     * finish onboarding) so the owner pastes it via manual mode.
     */
    suspend fun extractKeys(
        username: String,
        password: String,
        onCaptcha: (suspend (image: ByteArray, captchaUrl: String) -> String?)? = null,
        onSecondFactor: suspend (prompt: String) -> String? = { null },
    ) {
        if (_state.value.busy) return
        if (username.isBlank() || password.isBlank()) {
            update { it.copy(error = "enter the Mi account email/phone and password first") }
            return
        }
        update { it.copy(busy = true, error = null) }
        try {
            val device = mi.extractKeys(username, password, onCaptcha, onSecondFactor)
            val macOk = KeyFormat.isMac(device.mac)
            val tokenOk = KeyFormat.isToken(device.tokenHex)
            val bleKeyOk = KeyFormat.isBleKey(device.bleKeyHex)
            if (macOk) secrets.saveMac(KeyFormat.normalizeMac(device.mac))
            if (tokenOk) secrets.saveToken(device.tokenHex!!)
            if (bleKeyOk) secrets.saveBleKey(device.bleKeyHex!!)
            update {
                it.copy(
                    macOk = macOk,
                    tokenOk = tokenOk,
                    bleKeyOk = bleKeyOk,
                    foundScale = "${device.scaleName} (${device.model})",
                    error = when {
                        !macOk -> "scale found but its MAC is malformed — paste the keys manually"
                        !tokenOk -> "scale found but its TOKEN is missing — paste the keys manually"
                        !bleKeyOk ->
                            "BLE KEY not found for this scale — required to finish; " +
                                "paste it with \"I already have the keys\""
                        else -> null
                    },
                )
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            update { it.copy(error = sanitize(t.message)) }
        } finally {
            update { it.copy(busy = false) }
        }
    }

    fun nextFromKeys() {
        val state = _state.value
        if (state.macOk && state.tokenOk && state.bleKeyOk) {
            update { it.copy(step = OnboardingStep.PROFILE, error = null) }
        } else {
            update { it.copy(error = "extract or paste all three keys first (MAC, TOKEN, BLE KEY)") }
        }
    }

    // ------------------------------------------------------------- step 2

    fun setHeightCm(value: String) = update { it.copy(heightCm = value) }

    fun setAge(value: String) = update { it.copy(age = value) }

    fun setSex(value: Int) = update { it.copy(sex = value) }

    fun setMid(value: String) = update { it.copy(mid = value) }

    fun nextFromProfile() {
        val state = _state.value
        val height = state.heightCm.trim().toIntOrNull()
        val age = state.age.trim().toIntOrNull()
        val problem = when {
            height == null || height !in 50..250 -> "Height must be a number of cm (50–250)"
            age == null || age !in 5..120 -> "Age must be a number of years (5–120)"
            else -> null
        }
        if (problem != null) {
            update { it.copy(error = problem) }
        } else {
            update { it.copy(step = OnboardingStep.HISTORY, error = null) }
        }
    }

    // ------------------------------------------------------------- step 3

    fun setHistoryAlreadySynced(checked: Boolean) = update {
        it.copy(historyAlreadySynced = checked)
    }

    fun nextFromHistory() = update { it.copy(step = OnboardingStep.GARMIN, error = null) }

    // ------------------------------------------------------------- step 4

    /**
     * Garmin sign-in; [mfa] answers Garmin's MFA challenge (the onboarding
     * dialog suspends it). Success is remembered so "Next" unlocks and the
     * DI tokens are persisted by the client through its [TokenStore].
     */
    suspend fun garminSignIn(
        email: String,
        password: String,
        mfa: suspend (method: String) -> String,
    ) {
        if (_state.value.busy) return
        if (email.isBlank() || password.isBlank()) {
            update { it.copy(error = "enter the Garmin email and password first") }
            return
        }
        update { it.copy(busy = true, error = null) }
        try {
            garmin.signIn(email, password, mfa)
            update { it.copy(garminSignedIn = true, garminUser = email) }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            update { it.copy(error = sanitize(t.message)) }
        } finally {
            update { it.copy(busy = false) }
        }
    }

    fun nextFromGarmin() {
        if (_state.value.garminSignedIn) {
            update { it.copy(step = OnboardingStep.DONE, error = null) }
        } else {
            update { it.copy(error = "sign in to Garmin first") }
        }
    }

    // ------------------------------------------------- navigation + finish

    /** One step back (allowed everywhere except step 1). */
    fun back() = update {
        when (it.step) {
            OnboardingStep.PROFILE -> it.copy(step = OnboardingStep.MI_KEYS, error = null)
            OnboardingStep.HISTORY -> it.copy(step = OnboardingStep.PROFILE, error = null)
            OnboardingStep.GARMIN -> it.copy(step = OnboardingStep.HISTORY, error = null)
            OnboardingStep.DONE -> it.copy(step = OnboardingStep.GARMIN, error = null)
            OnboardingStep.MI_KEYS -> it
        }
    }

    /**
     * The wizard's completion — the only place `onboarded` flips true.
     * Writes `mid` (blank falls back to [Settings.DEFAULT_MID]), the
     * backfill cutoff (`now` when the history question was checked,
     * `null` when it was not), the profile, then `onboarded = true`. Idempotent: a
     * second call is a no-op, so a double-tap or re-entry can never
     * rewrite the answers.
     */
    suspend fun complete() {
        val state = _state.value
        if (state.completed || state.busy) return
        val height = state.heightCm.trim().toIntOrNull()
        val age = state.age.trim().toIntOrNull()
        if (height == null || age == null) {
            // Only reachable via a restored snapshot that skipped step 2's
            // validation — send the owner back there instead of crashing.
            update { it.copy(step = OnboardingStep.PROFILE, error = "check height and age") }
            return
        }
        update { it.copy(busy = true, error = null) }
        try {
            val mid = state.mid.trim().ifBlank { Settings.DEFAULT_MID }
            settings.setMid(mid)
            settings.setBackfillCutoff(if (state.historyAlreadySynced) nowUnix() else null)
            // nextFromProfile validated both; the profile lands before
            // onboarded so Main's first sync already pushes the real answers.
            settings.setProfile(heightCm = height, age = age, sex = state.sex)
            settings.setOnboarded(true)
            update { it.copy(completed = true, busy = false, mid = mid) }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            update { it.copy(busy = false, error = sanitize(t.message)) }
        }
    }

    // ---------------------------------------------------------- sanitizing

    /**
     * Exception text made safe for display: error paths can echo the raw
     * MAC (e.g. from `getRemoteDevice`) — every MAC-shaped fragment is
     * masked. Mirrors the spike's `SpikeController.sanitize()`; TOKEN and
     * BLE KEY never reach exception messages in the first place (the Mi
     * client performs no logging and redacts `toString`).
     * null → a generic fallback (never leaks a stack trace to the UI).
     */
    private fun sanitize(text: String?): String {
        val masked = text?.replace(MAC_IN_TEXT) { maskMac(it.value) }
        return if (masked.isNullOrEmpty()) "sign-in failed" else masked
    }

    /** `AA:BB:CC:DD:**:**` — enough to recognise, never a handle. */
    private fun maskMac(mac: String): String {
        val hex = mac.filter { it.isLetterOrDigit() }.uppercase(java.util.Locale.US)
        if (hex.length != 12) return mac
        return (0 until 6).joinToString(":") { i ->
            if (i < 4) hex.substring(i * 2, i * 2 + 2) else "**"
        }
    }

    private companion object {
        /**
         * Any MAC-shaped fragment in free text — canonical colon/dash
         * form, or a bare 12-hex word (word-bounded so a 24-hex TOKEN can
         * never be mistaken for one).
         */
        val MAC_IN_TEXT = Regex(
            "(?:(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2})" +
                "|(?<![0-9A-Fa-f])[0-9A-Fa-f]{12}(?![0-9A-Fa-f])",
        )
    }
}

// ---------------------------------------------------- production adapters

/**
 * Production [MiKeyExtractor]: the Task-13 `MiCloudClient` with the
 * caller's captcha/2FA dialogs wired straight into `login`. Throws
 * [ScaleNotListedException] when the account lists no scale.
 */
class MiCloudKeyExtractor(private val client: MiCloudClient) : MiKeyExtractor {

    override suspend fun extractKeys(
        username: String,
        password: String,
        onCaptcha: (suspend (image: ByteArray, captchaUrl: String) -> String?)?,
        onSecondFactor: suspend (prompt: String) -> String?,
    ): ExtractedKeys {
        val auth = client.login(username, password, onCaptcha, onSecondFactor)
        val device = client.extractScale(auth) ?: throw ScaleNotListedException()
        return ExtractedKeys(
            mac = device.mac,
            tokenHex = device.tokenHex,
            bleKeyHex = device.bleKeyHex,
            scaleName = device.name,
            model = device.model,
        )
    }
}

/**
 * Production [GarminSignIn]: a fresh [GarminClient] per sign-in because
 * the MFA prompt is constructor-bound — the client is stateless beyond
 * its [TokenStore] (which persists the DI tokens across restarts).
 */
class GarminSso(private val http: OkHttpClient, private val store: TokenStore) : GarminSignIn {

    override suspend fun signIn(
        email: String,
        password: String,
        mfa: suspend (method: String) -> String,
    ) {
        GarminClient(http, store, mfa).login(email, password)
    }
}
