package com.xiaomiscalesync.ui.profile

import com.xiaomiscalesync.data.KeyFormat
import com.xiaomiscalesync.data.KeyReader
import com.xiaomiscalesync.data.KeyVault
import com.xiaomiscalesync.data.Settings
import com.xiaomiscalesync.data.SettingsSource
import com.xiaomiscalesync.data.SettingsWriter
import com.xiaomiscalesync.data.WeighInRepository
import com.xiaomiscalesync.data.storeExtracted
import com.xiaomiscalesync.ui.onboarding.GarminSignIn
import com.xiaomiscalesync.ui.onboarding.MiKeyExtractor
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Key values shown only after an explicit "Reveal" (for backup/copy). */
data class RevealedKeys(val mac: String?, val token: String?, val bleKey: String?)

data class ProfileState(
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    /** One-shot confirmation ("Profile saved", …), cleared by the next action. */
    val message: String? = null,

    // Scale keys — presence + masked MAC; values only via [revealed].
    val macMasked: String? = null,
    val tokenOk: Boolean = false,
    val bleKeyOk: Boolean = false,
    val miAccount: String? = null,
    val revealed: RevealedKeys? = null,

    // Garmin.
    val garminEmail: String? = null,
    val garminSignedIn: Boolean = false,
    /** Some row is blocked on an expired sign-in (the Home banner's target). */
    val authExpired: Boolean = false,

    // Profile drafts (kg is fixed in v1).
    val heightCm: String = "",
    val age: String = "",
    val sex: Int = 1,
    val mid: String = Settings.DEFAULT_MID,
)

/**
 * The Profile tab (plan Task 17, owner request 2026-10-13): login status
 * and login data for both accounts, Mi re-extraction, Garmin re-login
 * (re-queues rows the expired sign-in blocked, then pushes), the scale
 * profile editor, and the explicit key reveal. Pure Kotlin — Android
 * plugs in through the same seams onboarding uses.
 *
 * Secrets discipline: key *values* enter [ProfileState] only through
 * [reveal] and leave on [hide]; nothing here logs, and error text never
 * carries key material.
 */
class ProfileViewModel(
    private val settingsSource: SettingsSource,
    private val settings: SettingsWriter,
    private val keys: KeyReader,
    private val vault: KeyVault,
    private val mi: MiKeyExtractor,
    private val garmin: GarminSignIn,
    private val garminSignedIn: suspend () -> Boolean,
    private val repo: WeighInRepository,
    private val enqueuePush: () -> Unit,
) {
    private val _state = MutableStateFlow(ProfileState())
    val state: StateFlow<ProfileState> = _state.asStateFlow()

    private fun update(f: (ProfileState) -> ProfileState) {
        _state.value = f(_state.value)
    }

    /** (Re)reads everything shown; profile drafts reset to the saved values. */
    suspend fun load() {
        val s = settingsSource.current()
        val signedIn = garminSignedIn()
        val blocked = repo.hasAuthBlocked()
        update {
            it.copy(
                loaded = true,
                macMasked = keys.mac()?.let(KeyFormat::maskMac),
                tokenOk = keys.token() != null,
                bleKeyOk = keys.bleKey() != null,
                miAccount = s.miAccount,
                garminEmail = s.garminEmail,
                garminSignedIn = signedIn,
                authExpired = blocked,
                heightCm = s.heightCm?.toString() ?: "",
                age = s.age?.toString() ?: "",
                sex = s.sex,
                mid = s.mid,
            )
        }
    }

    // ------------------------------------------------------------ profile

    fun setHeightCm(v: String) = update { it.copy(heightCm = v, message = null) }

    fun setAge(v: String) = update { it.copy(age = v, message = null) }

    fun setSex(v: Int) = update { it.copy(sex = v, message = null) }

    fun setMid(v: String) = update { it.copy(mid = v, message = null) }

    /** Same plausibility rules as onboarding step 2; nothing is written on failure. */
    suspend fun saveProfile() {
        val st = _state.value
        val height = st.heightCm.trim().toIntOrNull()
        val age = st.age.trim().toIntOrNull()
        val mid = st.mid.trim().ifBlank { Settings.DEFAULT_MID }
        val problem = when {
            height == null || height !in 50..250 -> "Height must be a number of cm (50–250)"
            age == null || age !in 5..120 -> "Age must be a number of years (5–120)"
            // The archive compares numerically (WeighInRepository: toLongOrNull);
            // anything else would silently become "1" there while showing
            // something different here.
            mid.toLongOrNull()?.let { it > 0 } != true -> "Member ID must be a whole number (normally 1)"
            else -> null
        }
        if (problem != null) {
            update { it.copy(error = problem, message = null) }
            return
        }
        settings.setProfile(heightCm = height!!, age = age!!, sex = st.sex)
        settings.setMid(mid)
        update { it.copy(mid = mid, error = null, message = "Profile saved — sent to the scale on the next sync") }
    }

    // ------------------------------------------------------------- reveal

    fun reveal() = update {
        it.copy(revealed = RevealedKeys(keys.mac(), keys.token(), keys.bleKey()))
    }

    fun hide() = update { it.copy(revealed = null) }

    // ------------------------------------------------------- mi re-extract

    /**
     * Mi Cloud sign-in → store whichever keys validate (a missing or
     * malformed key keeps the previously stored one) → refresh the status.
     */
    suspend fun reExtract(
        username: String,
        password: String,
        onCaptcha: (suspend (image: ByteArray, captchaUrl: String) -> String?)? = null,
        onSecondFactor: suspend (prompt: String) -> String? = { null },
    ) = runBusy {
        if (username.isBlank() || password.isBlank()) {
            update { it.copy(error = "enter the Mi account email/phone and password first") }
            return@runBusy
        }
        val device = mi.extractKeys(username, password, onCaptcha, onSecondFactor)
        val stored = vault.storeExtracted(device.mac, device.tokenHex, device.bleKeyHex)
        settings.setMiAccount(username.trim())
        val problem = when {
            !stored.mac -> "scale found but its MAC is malformed — previous MAC kept"
            !stored.token -> "TOKEN missing from the cloud — previous TOKEN kept"
            !stored.bleKey -> "BLE KEY missing from the cloud — previous BLE KEY kept"
            else -> null
        }
        load()
        update {
            it.copy(
                error = problem,
                revealed = null,
                message = if (problem == null) "Keys updated from ${device.scaleName}" else null,
            )
        }
    }

    // ------------------------------------------------------ garmin re-login

    /**
     * Garmin sign-in ([mfa] answers the MFA challenge). On success: the
     * email is recorded, rows the expired sign-in blocked go back to
     * PENDING, and one push pass is queued (the duplicate guard runs first).
     */
    suspend fun garminSignIn(email: String, password: String, mfa: suspend (String) -> String) = runBusy {
        if (email.isBlank() || password.isBlank()) {
            update { it.copy(error = "enter the Garmin email and password first") }
            return@runBusy
        }
        garmin.signIn(email.trim(), password, mfa)
        settings.setGarminEmail(email.trim())
        val requeued = repo.requeueAuthBlocked()
        if (requeued > 0 || repo.pending().isNotEmpty()) enqueuePush()
        load()
        update {
            it.copy(
                message = "Signed in to Garmin" +
                    if (requeued > 0) " — $requeued weigh-in(s) queued for push" else "",
            )
        }
    }

    /** One operation at a time; failures land in [ProfileState.error], never thrown to the UI. */
    private suspend fun runBusy(block: suspend () -> Unit) {
        if (_state.value.busy) return
        update { it.copy(busy = true, error = null, message = null) }
        try {
            block()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            update { it.copy(error = KeyFormat.sanitize(t.message).ifBlank { "sign-in failed" }) }
        } finally {
            update { it.copy(busy = false) }
        }
    }
}
