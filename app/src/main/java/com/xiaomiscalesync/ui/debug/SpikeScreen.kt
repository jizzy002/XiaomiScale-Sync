package com.xiaomiscalesync.ui.debug

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaomiscalesync.ble.AndroidTransport
import com.xiaomiscalesync.ble.Beacon
import com.xiaomiscalesync.ble.BeaconDecoder
import com.xiaomiscalesync.ble.BroadcastWeighIn
import com.xiaomiscalesync.ble.WeighInObjects
import com.xiaomiscalesync.data.ArchiveDb
import com.xiaomiscalesync.data.Category
import com.xiaomiscalesync.data.GarminStatus
import com.xiaomiscalesync.data.Settings
import com.xiaomiscalesync.data.SettingsStore
import com.xiaomiscalesync.data.WeighInRecord
import com.xiaomiscalesync.data.WeighInRepository
import com.xiaomiscalesync.garmin.GarminAuthError
import com.xiaomiscalesync.garmin.GarminClient
import com.xiaomiscalesync.garmin.GarminPusher
import com.xiaomiscalesync.proto.ScaleSession
import com.xiaomiscalesync.sync.EnginePhase
import com.xiaomiscalesync.sync.EngineState
import com.xiaomiscalesync.sync.SyncEngine
import com.xiaomiscalesync.sync.SyncKind
import com.xiaomiscalesync.sync.SyncResult
import com.xiaomiscalesync.work.PushWorker
import com.xiaomiscalesync.xiaomi.MiCloudClient
import java.text.SimpleDateFormat
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.ParcelUuid

/**
 * Milestone-0 debug controller: the wiring the owner's acceptance checklist
 * (`docs/testing/milestone-0.md`) drives. Deliberately thin glue over the
 * tested cores — [MiCloudClient] → [SpikeKeyHolder], scan → [BeaconDecoder]
 * → [SyncEngine] → [AndroidTransport] → [ScaleSession],
 * [GarminPusher] (+ [PushWorker.pusherFactory] install) — with one scrolling
 * log pane. Debug quality throughout: one instance per screen, no
 * ViewModel (rotation restarts an in-flight operation — acceptable for a
 * harness the owner runs deliberately), plain-prefs secrets (Task 14
 * replaces them with Keystore storage).
 *
 * Connect discipline is preserved even here: the transport only opens from
 * [openSession], which requires a *fresh* advertisement ([awaitVisible])
 * before touching `connectGatt` — a connect to a sleeping scale is exactly
 * the failed attempt the design spec warns can leave the unit stuck
 * "connected" for days. Manual "Sync scale now" is the reference client's
 * own explicit-sync path; it still refuses to connect to an awake-less scale.
 *
 * Secrets discipline: nothing in [log] ever carries TOKEN / BLE KEY /
 * Garmin token material — keys are reported as lengths + green/red status
 * only, MAC is masked (`AA:BB:CC:DD:**:**`).
 */
class SpikeController(private val appContext: Context) {

    // ---------------------------------------------------------------- log

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Scrolling log pane contents (newest appended; capped at [LOG_LIMIT]). */
    val logLines = mutableStateListOf<String>()

    /**
     * Appends one stamped line, marshalled onto the main thread so callers
     * (GATT callbacks, OkHttp threads, scan callbacks) can log freely.
     * **Call sites must never pass secret material** (class KDoc).
     */
    fun log(line: String) {
        val entry = "${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())} $line"
        if (Looper.myLooper() == Looper.getMainLooper()) appendLog(entry)
        else mainHandler.post { appendLog(entry) }
    }

    private fun appendLog(entry: String) {
        logLines += entry
        while (logLines.size > LOG_LIMIT) logLines.removeAt(0)
    }

    // ------------------------------------------------------------- storage

    private val prefs =
        appContext.getSharedPreferences("milestone0_spike", Context.MODE_PRIVATE)

    /** MAC / TOKEN / BLE KEY (plain prefs — Task 14 swaps in `Secrets`). */
    val keys = SpikeKeyHolder(prefs)

    private val tokenStore = SharedTokenStore(prefs)

    /** Key status for the green-check row; refreshed after every save. */
    data class KeyStatus(val mac: Boolean, val token: Boolean, val bleKey: Boolean)

    var keyStatus by mutableStateOf(KeyStatus(false, false, false))
        private set

    private fun refreshKeyStatus() {
        keyStatus = KeyStatus(
            mac = keys.mac != null,
            token = keys.tokenHex != null,
            bleKey = keys.bleKeyHex != null,
        )
    }

    // ------------------------------------------------------- core instances

    private val http = OkHttpClient()

    /** The Mi Cloud client the "Extract keys" flow drives. */
    val miClient = MiCloudClient(http)

    private val settings = SettingsStore.of(appContext)

    private val repo = WeighInRepository(
        dao = ArchiveDb.build(appContext).archiveDao(),
        settings = settings,
    )

    /** Newest-first archive rows for the on-screen preview (checklist 2–4). */
    val archive: Flow<List<WeighInRecord>> = repo.observe(null)

    private val miPrompt = CodePrompt()
    private val garminPrompt = CodePrompt()

    /** Shown as dialogs; both flows suspend on the owner's code entry. */
    val prompts: List<CodePrompt> = listOf(miPrompt, garminPrompt)

    private val garminClient = GarminClient(
        http,
        tokenStore,
        mfa = { method ->
            garminPrompt.ask("Garmin MFA via $method — enter the emailed code") ?: throw
                GarminAuthError("Garmin MFA dialog cancelled")
        },
    )

    /** The pusher the button drives and [PushWorker] reuses via the factory. */
    val pusher = GarminPusher(repo, garminClient, Clock.systemDefaultZone())

    /**
     * The engine under test: [openSession] supplies the real transport,
     * every trigger/gate/dedupe rule is the Task-9-tested one.
     */
    private val engine = SyncEngine(repo, openSession = { openSession() })

    /** Observable engine phase for the header line (Task 9's state flow). */
    val engineState: StateFlow<EngineState> = engine.state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The single in-flight debug operation (buttons disable while set). */
    var busy by mutableStateOf<String?>(null)
        private set

    /** Member id shown in the attribution field (default "1"). */
    var mid by mutableStateOf(Settings.DEFAULT_MID)
        private set

    init {
        PushWorker.pusherFactory = { pusher }
        refreshKeyStatus()
        scope.launch {
            // The owner's onboarding answer is already known (spec): history
            // was synced by the existing script. Mirroring it here keeps the
            // spike's first "Push pending" from treating years of stored
            // records as fresh — they archive as SKIPPED_EXISTING instead.
            if (settings.current().backfillCutoff == null) {
                settings.setBackfillCutoff(Clock.systemUTC().instant().epochSecond)
                log("backfill cutoff = now (owner's 'history already in Garmin' answer); older records archive as SKIPPED_EXISTING")
            }
            mid = settings.current().mid
            log("spike ready — keys: ${describeKeys()}")
        }
    }

    fun dispose() {
        stopListening()
        scope.cancel()
    }

    // ---------------------------------------------------------- permissions

    /**
     * The permission set the scan/connect path needs: API 31+ modern
     * `BLUETOOTH_SCAN` (neverForLocation — no location permission) +
     * `BLUETOOTH_CONNECT`; below 31 the legacy scan needs fine location
     * (the old Bluetooth permissions are install-time, never requested).
     */
    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun missingPermissions(): List<String> = requiredPermissions().filter {
        appContext.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
    }

    fun onPermissionsResult(results: Map<String, Boolean>) {
        val denied = results.filterValues { !it }.keys
        if (denied.isEmpty()) {
            log("Bluetooth permissions granted")
            startListening()
        } else {
            log("permission denied: ${denied.joinToString()} — scan and sync will not work until granted")
        }
    }

    // -------------------------------------------------------------- scanning

    @Volatile
    private var lastSeenMs = 0L

    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null

    var listening by mutableStateOf(false)
        private set

    var scaleVisible by mutableStateOf(false)
        private set

    fun toggleListening() {
        if (listening) stopListening() else startListening()
    }

    /**
     * Starts a foreground scan filtered on service `0xfe95`. Every result
     * updates the freshness clock [openSession] gates connects on; decoded
     * beacons feed [SyncEngine.onBeacon] exactly like Task 15's scanner
     * will (including the caller obligation to run `syncNow()` when a
     * trigger was armed — see [handleBeacon]).
     */
    fun startListening() {
        if (listening) return
        val adapter = adapterOrNull() ?: run {
            log("no Bluetooth adapter — cannot scan")
            return
        }
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            log("scan permission missing (${missing.joinToString()}) — grant it first")
            return
        }
        val bleScanner = try {
            adapter.bluetoothLeScanner
        } catch (e: SecurityException) {
            log("scan denied: ${e.message}")
            return
        } ?: run {
            log("Bluetooth is off — turn it on, then tap Listen again")
            return
        }
        val callback = buildScanCallback()
        try {
            // Deliberately unfiltered: SDK 37 offers no presence-only
            // service-data ScanFilter (`setServiceUuid` matches the Service-
            // UUID *list* AD type, which the scale may not carry, and
            // `setServiceDataUuid` is gone from the SDK). Filtering for
            // 0xfe95 service data in [buildScanCallback] instead means a
            // filter quirk can never drop every scale packet.
            bleScanner.startScan(emptyList(), SCAN_SETTINGS, callback)
        } catch (e: SecurityException) {
            log("scan denied: ${e.message}")
            return
        } catch (e: IllegalStateException) {
            log("scan failed: ${e.message}")
            return
        }
        scanner = bleScanner
        scanCallback = callback
        listening = true
        log("listening for scale advertisements (service 0xfe95)")
    }

    fun stopListening() {
        if (!listening) return
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
            // Permission revoked mid-scan: the scanner dies with the app anyway.
        } catch (_: IllegalStateException) {
            // Bluetooth turned off underneath us.
        }
        scanner = null
        scanCallback = null
        listening = false
        scaleVisible = false
        log("scan stopped")
    }

    private fun buildScanCallback() = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // Presence match, in code: only 0xfe95 *service data* frames (AD
            // type 0x16) are the scale — the same bytes BeaconDecoder reads.
            // SDK 37 exposes service data through the scan record only
            // (ScanResult.getServiceData was dropped from the public API).
            val serviceData = result.scanRecord?.getServiceData(SERVICE_PARCEL) ?: return
            val address = result.device?.address ?: return
            val mac = keys.mac
            if (mac != null && !address.equals(mac, ignoreCase = true)) return

            val now = System.currentTimeMillis()
            val gap = lastSeenMs == 0L || now - lastSeenMs > VISIBLE_LOG_GAP_MS
            lastSeenMs = now
            if (gap) {
                scaleVisible = true
                log("scale visible: ${maskMac(address)} (rssi ${result.rssi})")
            }
            if (mac == null) return // keys not extracted yet: visibility only

            val bleKey = keys.bleKeyHex?.let(::hexToBytes)
            val beacon = try {
                BeaconDecoder.decode(serviceData, mac, bleKey)
            } catch (e: IllegalArgumentException) {
                // Task 7 note: a malformed stored MAC throws here — surfaced,
                // not swallowed, and sanitized (the message may embed it).
                log("beacon decode failed: ${sanitize(e.message)}")
                return
            } ?: return
            val weighIn = beacon.objects
                .firstOrNull { it.first == WeighInObjects.OBJ_WEIGH_IN }
                ?.let { WeighInObjects.parse(it.second) }
            scope.launch { handleBeacon(beacon, weighIn) }
        }

        override fun onScanFailed(errorCode: Int) {
            log("scan failed (errorCode $errorCode)")
            listening = false
            scanner = null
            scanCallback = null
        }
    }

    /**
     * Feeds one advertisement to the engine. When the beacon was *handled*
     * (weigh-in archived or a trigger armed) this runs `syncNow()` — the
     * caller obligation Task 9 pinned for the scanner loop; the engine's
     * gates/single-flight decide whether a link actually opens.
     */
    private suspend fun handleBeacon(beacon: Beacon, weighIn: BroadcastWeighIn?) {
        val handled = try {
            engine.onBeacon(beacon, weighIn)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            log("beacon handling failed: ${t.message}")
            return
        }
        if (handled && weighIn != null) {
            log(
                "weigh-in broadcast handled: ${fmtWeight(weighIn.weightGrams)} kg at " +
                    "${fmtTs(weighIn.unixTs)} (archive dedupes repeats)",
            )
        }
        if (handled) {
            engine.syncNow()?.let { log(syncLine(it)) }
        }
    }

    // ------------------------------------------------------------- buttons

    /**
     * "Extract keys (Mi login)": sign in to the Mi Cloud (2FA dialog when
     * Xiaomi asks), find the scale, validate + store MAC/TOKEN/BLE KEY.
     * Passwords stay in the composable's memory; only keys are persisted.
     */
    fun extractKeys(username: String, password: String) = runOperation("extract") {
        if (username.isBlank() || password.isBlank()) {
            log("enter the Mi account email/phone and password first")
            return@runOperation
        }
        log("signing in to the Mi Cloud as $username …")
        val auth = miClient.login(username, password) { prompt -> miPrompt.ask(prompt) }
        log("Mi sign-in OK (userId ${auth.userId})")
        val device = miClient.extractScale(auth)
        if (device == null) {
            log("no Xiaomi scale is listed in this account")
            return@runOperation
        }
        try {
            keys.save(device.mac, device.tokenHex, device.bleKeyHex)
        } catch (e: IllegalArgumentException) {
            log("scale found but keys rejected: ${sanitize(e.message)}")
            return@runOperation
        }
        refreshKeyStatus()
        log("scale found: '${device.name}' (${device.model})")
        log("MAC: ${maskMac(device.mac)} ✓")
        log("TOKEN: 24 hex ✓ (value redacted)")
        log(
            if (device.bleKeyHex != null) "BLE KEY: 32 hex ✓ (value redacted)"
            else "BLE KEY: missing — broadcasts cannot be decrypted; sync still works",
        )
    }

    /**
     * "Sync scale now": force one engine sync (the reference client's
     * explicit-sync button). [openSession] still refuses to connect unless
     * the scale is awake — step on it first.
     */
    fun syncNow() = runOperation("sync") {
        if (missingPermissions().isNotEmpty()) {
            log("permissions missing (${missingPermissions().joinToString()}) — grant them first")
            return@runOperation
        }
        if (!listening) startListening()
        when (val result = engine.syncNow(force = true)) {
            null -> log("sync skipped — another sync is running")
            else -> log(syncLine(result))
        }
    }

    /** Garmin sign-in (email + password; MFA dialog when demanded). */
    fun garminSignIn(email: String, password: String) = runOperation("garmin-signin") {
        if (email.isBlank() || password.isBlank()) {
            log("enter the Garmin email and password first")
            return@runOperation
        }
        log("signing in to Garmin Connect as $email …")
        garminClient.login(email, password)
        log("Garmin sign-in OK (tokens stored)")
    }

    /**
     * "Push pending to Garmin": one [GarminPusher] drain — every row gets
     * the duplicate guard first (checklist 4: the old script's copy blocks
     * ours when it raced).
     */
    fun pushPending() = runOperation("push") {
        if (tokenStore.load() == null) {
            log("not signed in to Garmin — tap \"Garmin sign-in\" first")
            return@runOperation
        }
        val summary = pusher.pushPending()
        log(
            "push finished: ${summary.pushed} pushed, ${summary.skippedExisting} skipped " +
                "(already in Garmin — guard), ${summary.failed} failed" +
                if (summary.authBlocked) "; AUTH REQUIRED — sign in again" else "",
        )
    }

    /** Persists the attribution member id (cosmetic on the scale, drives `mid` matching). */
    fun saveMid(value: String) = runOperation("save-mid") {
        val normalized = value.trim().ifBlank { Settings.DEFAULT_MID }
        settings.setMid(normalized)
        mid = normalized
        log("member id = '$normalized' (scale attribution; archive category derives from it)")
    }

    // ---------------------------------------------------------- internals

    /**
     * The [SyncEngine] session factory: keys present → wait for a *fresh*
     * advertisement → connect [AndroidTransport] → hand [ScaleSession] over
     * for the engine to drive (setup order, login, fetch, live listen).
     * Returns null (engine sees SCALE_ABSENT) instead of ever attempting a
     * connection to a scale that is not awake.
     */
    private suspend fun openSession(): ScaleSession? {
        val mac = keys.mac
        val tokenHex = keys.tokenHex
        if (mac == null || tokenHex == null) {
            log("no keys yet — tap \"Extract keys (Mi login)\" first")
            return null
        }
        if (!listening) startListening()
        if (!awaitVisible()) {
            log("scale not visible — step on it to wake it; NOT connecting (sleep discipline)")
            return null
        }
        return try {
            val adapter = adapterOrNull() ?: throw IllegalStateException("no Bluetooth adapter")
            log("connecting to ${maskMac(mac)} …")
            val transport = AndroidTransport.connect(adapter.getRemoteDevice(mac), appContext, ::log)
            try {
                ScaleSession(transport, hexToBytes(tokenHex), ::log)
            } catch (c: CancellationException) {
                // A connected GATT must not leak if session setup is cancelled.
                withContext(NonCancellable) { runCatching { transport.close() } }
                throw c
            } catch (t: Throwable) {
                // ditto when the constructor itself throws after connect succeeded
                runCatching { transport.close() }
                log(
                    "session setup failed: " +
                        sanitize(t.message).ifEmpty { t::class.java.simpleName },
                )
                null
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            log("connect failed: ${sanitize(t.message).ifEmpty { t::class.java.simpleName }}")
            null
        }
    }

    /** Fresh-advertisement gate: [VISIBLE_FRESH_MS]-recent sighting within 10 s. */
    private suspend fun awaitVisible(): Boolean {
        val deadline = System.currentTimeMillis() + VISIBILITY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (freshlySeen()) return true
            delay(200)
        }
        return freshlySeen()
    }

    private fun freshlySeen(): Boolean {
        val seen = lastSeenMs
        return seen != 0L && System.currentTimeMillis() - seen < VISIBLE_FRESH_MS
    }

    /**
     * One debug operation at a time: sets [busy], runs [block] on the
     * controller scope, and turns any escape into a log line (a
     * [CancellationException] is rethrown — a disposed screen must not
     * swallow its own cancellation).
     */
    private fun runOperation(name: String, block: suspend CoroutineScope.() -> Unit) {
        val current = busy
        if (current != null) {
            log("busy with '$current' — wait for it to finish")
            return
        }
        busy = name
        scope.launch {
            try {
                block()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                log("$name failed: ${t.message ?: t::class.java.simpleName}")
            } finally {
                busy = null
            }
        }
    }

    private fun adapterOrNull(): BluetoothAdapter? =
        appContext.getSystemService(BluetoothManager::class.java)?.adapter

    private fun describeKeys(): String =
        listOf(
            (if (keyStatus.mac) "MAC ✓" else "MAC –"),
            (if (keyStatus.token) "TOKEN ✓" else "TOKEN –"),
            (if (keyStatus.bleKey) "BLE KEY ✓" else "BLE KEY –"),
        ).joinToString("  ")

    private fun syncLine(result: SyncResult): String = when (result.kind) {
        SyncKind.OK ->
            "sync OK after ${result.attempts} attempt(s): ${result.collected} stored record(s) merged" +
                if (result.collected == 0) " (all already archived)" else ""

        SyncKind.SCALE_ABSENT -> "scale absent: no session opened"
        SyncKind.FAILED -> "sync FAILED after ${result.attempts} attempt(s) — see log"
    }

    companion object {
        private const val LOG_LIMIT = 500

        /** Re-log visibility at most once per minute while the scale broadcasts. */
        private const val VISIBLE_LOG_GAP_MS = 60_000L

        /** How long [openSession] waits for a wake-up advertisement. */
        private const val VISIBILITY_TIMEOUT_MS = 10_000L

        /** An advertisement older than this no longer counts as awake. */
        private const val VISIBLE_FRESH_MS = 4_000L

        private val SERVICE_PARCEL =
            ParcelUuid(UUID.fromString(AndroidTransport.SERVICE_UUID))

        private val SCAN_SETTINGS = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        /** `AA:BB:CC:DD:**:**` — enough to recognise the device, not a handle. */
        fun maskMac(mac: String): String {
            val hex = mac.filter { it.isLetterOrDigit() }.uppercase(Locale.US)
            if (hex.length != 12) return "**:**:**:**:**:**"
            return (0 until 6).joinToString(":") { i ->
                if (i < 4) hex.substring(i * 2, i * 2 + 2) else "**"
            }
        }

        /**
         * Any MAC-shaped fragment in free text: the canonical colon/dash
         * form, or a bare 12-hex word (word-bounded so a 24-hex TOKEN can
         * never be mistaken for one).
         */
        private val MAC_IN_TEXT = Regex(
            "(?:(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2})" +
                "|(?<![0-9A-Fa-f])[0-9A-Fa-f]{12}(?![0-9A-Fa-f])",
        )

        /**
         * Exception text made safe for the log pane: error paths can echo
         * the raw MAC we handed to `getRemoteDevice` — every MAC-shaped
         * fragment is masked before logging.
         */
        fun sanitize(text: String?): String =
            text?.replace(MAC_IN_TEXT) { maskMac(it.value) } ?: ""

        /** Validated hex → bytes (only ever fed [SpikeKeyHolder]-validated input). */
        fun hexToBytes(hex: String): ByteArray =
            ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

        /** 1/100 kg → `80.25 kg`. */
        fun fmtWeight(weight100: Int): String =
            String.format(Locale.US, "%.2f", weight100 / 100.0)

        private val TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        fun fmtTs(unixSeconds: Long): String =
            Instant.ofEpochSecond(unixSeconds).atZone(ZoneId.systemDefault()).format(TS_FMT)
    }
}

/**
 * One suspendable second-factor prompt (Mi email 2FA / Garmin MFA): the
 * login call suspends in [ask] until the dialog submits or dismisses.
 * State is snapshot-backed so the dialog reacts instantly; the deferred
 * hand-off is safe across the login thread (OkHttp IO) and the main thread.
 */
class CodePrompt {
    var prompt by mutableStateOf<String?>(null)
        private set

    @Volatile
    private var pending: CompletableDeferred<String?>? = null

    /** Suspends until the dialog answers; null = cancelled. */
    suspend fun ask(text: String): String? {
        check(pending == null) { "a code prompt is already open" }
        val deferred = CompletableDeferred<String?>()
        pending = deferred
        prompt = text
        return try {
            deferred.await()
        } finally {
            prompt = null
            pending = null
        }
    }

    /** Called from the dialog (main thread). */
    fun submit(code: String?) {
        prompt = null
        val deferred = pending
        pending = null
        deferred?.complete(code)
    }
}

// ------------------------------------------------------------------- UI

/**
 * The milestone-0 spike screen (debug quality, Material 3): key status,
 * Mi/Garmin credentials, the three brief-mandated buttons ("Extract keys
 * (Mi login)", "Sync scale now", "Push pending to Garmin"), a listen
 * toggle, member-id field, archive preview, and the scrolling log pane.
 */
@Composable
fun SpikeScreen(controller: SpikeController) {
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { controller.onPermissionsResult(it) }

    LaunchedEffect(Unit) {
        val missing = controller.missingPermissions()
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
        else controller.startListening()
    }

    var miUser by rememberSaveable { mutableStateOf("") }
    var miPass by remember { mutableStateOf("") }
    var garminUser by rememberSaveable { mutableStateOf("") }
    var garminPass by remember { mutableStateOf("") }
    var midField by remember(controller.mid) { mutableStateOf(controller.mid) }

    val records by controller.archive.collectAsState(initial = emptyList())
    val pendingCount = records.count {
        it.garminStatus == GarminStatus.PENDING && it.category == Category.MINE
    }
    val pushedCount = records.count { it.garminStatus == GarminStatus.PUSHED }
    val engineState by controller.engineState.collectAsState()
    val busy = controller.busy

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("Milestone 0 — spike", style = MaterialTheme.typography.titleMedium)
        if (busy != null) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
            Text("running: $busy", style = MaterialTheme.typography.bodySmall)
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text(
                "Archive: ${records.size} weigh-ins · $pendingCount pending · " +
                    "$pushedCount pushed · ${phaseLabel(engineState)}",
                style = MaterialTheme.typography.bodySmall,
            )

            records.take(5).forEach { record ->
                Text(
                    "${SpikeController.fmtWeight(record.weightGrams)} kg  " +
                        "${SpikeController.fmtTs(record.recordedAt)}  " +
                        "${record.source} · ${record.category} · ${record.garminStatus}",
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    color = Color(0xFF666666),
                )
            }

            KeyStatusRow(controller.keyStatus)

            OutlinedTextField(
                value = miUser,
                onValueChange = { miUser = it },
                label = { Text("Mi account email / phone") },
                singleLine = true,
                enabled = busy == null,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = miPass,
                onValueChange = { miPass = it },
                label = { Text("Mi account password") },
                singleLine = true,
                enabled = busy == null,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { controller.extractKeys(miUser, miPass) },
                enabled = busy == null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Extract keys (Mi login)")
            }

            OutlinedTextField(
                value = garminUser,
                onValueChange = { garminUser = it },
                label = { Text("Garmin email") },
                singleLine = true,
                enabled = busy == null,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = garminPass,
                onValueChange = { garminPass = it },
                label = { Text("Garmin password") },
                singleLine = true,
                enabled = busy == null,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { controller.garminSignIn(garminUser, garminPass) },
                enabled = busy == null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Garmin sign-in")
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { controller.syncNow() },
                    enabled = busy == null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Sync scale now")
                }
                Button(
                    onClick = { controller.pushPending() },
                    enabled = busy == null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Push pending to Garmin")
                }
            }
            Button(
                onClick = { controller.toggleListening() },
                enabled = busy == null || controller.listening,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (controller.listening) {
                        "Listening for scale — tap to stop"
                    } else {
                        "Listen for scale — tap to start"
                    },
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = midField,
                    onValueChange = { midField = it },
                    label = { Text("Member ID") },
                    singleLine = true,
                    enabled = busy == null,
                    modifier = Modifier.width(140.dp),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = { controller.saveMid(midField) },
                    enabled = busy == null,
                ) {
                    Text("Save")
                }
            }
        }

        LogPane(controller.logLines, Modifier.weight(1f).fillMaxWidth().heightIn(min = 120.dp))
    }

    controller.prompts.forEach { prompt ->
        prompt.prompt?.let { text ->
            CodeDialog(text) { code -> prompt.submit(code) }
        }
    }
}

/** The three key indicators; all green = checklist check 1 passes. */
@Composable
private fun KeyStatusRow(status: SpikeController.KeyStatus) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(vertical = 6.dp),
    ) {
        KeyChip("MAC", status.mac)
        KeyChip("TOKEN", status.token)
        KeyChip("BLE KEY", status.bleKey)
    }
}

@Composable
private fun KeyChip(label: String, ok: Boolean) {
    Text(
        "${if (ok) "✔" else "○"} $label",
        color = if (ok) Color(0xFF1B8E3D) else Color(0xFF9E9E9E),
        fontWeight = if (ok) FontWeight.Bold else FontWeight.Normal,
        fontSize = 13.sp,
    )
}

/** The scrolling, monospace log pane (never carries secret material). */
@Composable
private fun LogPane(lines: List<String>, modifier: Modifier) {
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) state.scrollToItem(lines.size - 1)
    }
    LazyColumn(
        state = state,
        modifier = modifier
            .background(Color(0xFF12161A))
            .padding(6.dp),
    ) {
        itemsIndexed(lines) { _, line ->
            Text(
                line,
                color = Color(0xFF8FE388),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/** Second-factor code dialog (Mi 2FA / Garmin MFA); dismiss = cancel. */
@Composable
private fun CodeDialog(promptText: String, onSubmit: (String?) -> Unit) {
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { onSubmit(null) },
        title = { Text("Verification code") },
        text = {
            Column {
                Text(promptText, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.heightIn(min = 8.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    singleLine = true,
                    label = { Text("Code") },
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(code) }) { Text("Submit") } },
        dismissButton = { TextButton(onClick = { onSubmit(null) }) { Text("Cancel") } },
    )
}

private fun phaseLabel(state: EngineState): String =
    when (val phase = state.phase) {
        EnginePhase.Idle -> "idle"
        EnginePhase.Listening -> "listening"
        EnginePhase.Connecting -> "syncing"
        is EnginePhase.LastResult -> "last sync code ${phase.code}"
    }
