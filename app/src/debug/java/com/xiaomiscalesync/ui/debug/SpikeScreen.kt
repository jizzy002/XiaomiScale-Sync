package com.xiaomiscalesync.ui.debug

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
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
import com.xiaomiscalesync.ble.ScaleKeys
import com.xiaomiscalesync.ble.ScaleScanner
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
import com.xiaomiscalesync.ui.CodePrompt
import com.xiaomiscalesync.work.PushWorker
import com.xiaomiscalesync.xiaomi.MiCloudClient
import java.text.SimpleDateFormat
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import android.bluetooth.BluetoothDevice

/**
 * Milestone-0 debug controller: the wiring the owner's acceptance checklist
 * (`docs/testing/milestone-0.md`) drives. Deliberately thin glue over the
 * tested cores — [MiCloudClient] → [SpikeKeyHolder], scan → [BeaconDecoder]
 * → [SyncEngine] → [AndroidTransport] → [ScaleSession],
 * [GarminPusher] (driven directly, never via [PushWorker]) — with one scrolling
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

    /** The pusher the "Push pending" button drives (spike token store). */
    val pusher = GarminPusher(repo, garminClient, Clock.systemDefaultZone())

    /**
     * The engine under test: [openSession] supplies the real transport,
     * every trigger/gate/dedupe rule is the Task-9-tested one.
     */
    private val engine = SyncEngine(repo, openSession = { openSession() }, log = ::log)

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
        // No PushWorker.pusherFactory install here any more: the worker
        // resolves the production AppGraph pusher, and the spike's own
        // plain-prefs token store must never replace it.
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

    // -------------------------------------------------------------- scanning

    /**
     * The shared foreground scanner — the exact scan → [BeaconDecoder] →
     * [SyncEngine.onBeacon] path the main screen runs, fed from the
     * spike's own key holder and log pane.
     */
    private val scanner = ScaleScanner(
        appContext,
        scope,
        keys = { keys.mac?.let { ScaleKeys(it, keys.tokenHex, keys.bleKeyHex) } },
        log = ::log,
        onBeacon = { beacon, weighIn -> handleBeacon(beacon, weighIn) },
    )

    /** True while the scan runs (drives the listen toggle). */
    val listening: StateFlow<Boolean> = scanner.listening

    fun missingPermissions(): List<String> = scanner.missingPermissions()

    fun onPermissionsResult(results: Map<String, Boolean>) {
        val denied = results.filterValues { !it }.keys
        if (denied.isEmpty()) {
            log("Bluetooth permissions granted")
            startListening()
        } else {
            log("permission denied: ${denied.joinToString()} — scan and sync will not work until granted")
        }
    }

    fun toggleListening() {
        if (listening.value) stopListening() else startListening()
    }

    fun startListening() {
        scanner.startListening()
    }

    fun stopListening() = scanner.stopListening()

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
                "weigh-in broadcast handled: ${fmtWeight(weighIn.weightCentiKg)} kg at " +
                    "${fmtTs(weighIn.unixTs)} (archive dedupes repeats)",
            )
        }
        if (handled) {
            engine.syncNow()?.let { log(syncLine(it)) }
        }
    }

    // ------------------------------------------------------------- buttons

    /**
     * "Extract keys (Mi login)": sign in to the Mi Cloud (captcha dialog
     * and 2FA dialog when Xiaomi asks), find the scale, validate + store
     * MAC/TOKEN/BLE KEY. Passwords stay in the composable's memory; only
     * keys are persisted.
     */
    fun extractKeys(username: String, password: String) = runOperation("extract") {
        if (username.isBlank() || password.isBlank()) {
            log("enter the Mi account email/phone and password first")
            return@runOperation
        }
        log("signing in to the Mi Cloud as $username …")
        val auth = miClient.login(
            username,
            password,
            onCaptcha = { image, url -> miPrompt.askCaptcha(image, url) },
        ) { prompt -> miPrompt.ask(prompt) }
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
        if (!listening.value) startListening()
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

    /** Keys gate first (spike wording), then the shared scanner's session factory. */
    private suspend fun openSession(): ScaleSession? {
        if (keys.mac == null || keys.tokenHex == null) {
            log("no keys yet — tap \"Extract keys (Mi login)\" first")
            return null
        }
        return scanner.openSession()
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

        fun maskMac(mac: String): String = ScaleScanner.maskMac(mac)

        fun sanitize(text: String?): String = ScaleScanner.sanitize(text)

        /** 1/100 kg → `80.25 kg`. */
        fun fmtWeight(weightCentiKg: Int): String =
            String.format(Locale.US, "%.2f", weightCentiKg / 100.0)

        private val TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        fun fmtTs(unixSeconds: Long): String =
            Instant.ofEpochSecond(unixSeconds).atZone(ZoneId.systemDefault()).format(TS_FMT)
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
    val listening by controller.listening.collectAsState()
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
                    "${SpikeController.fmtWeight(record.weightCentiKg)} kg  " +
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
                enabled = busy == null || listening,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (listening) {
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
        prompt.captcha?.let { challenge ->
            CaptchaDialog(challenge.image) { code -> prompt.submit(code) }
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

/**
 * Captcha dialog (Mi login): the fetched image decoded to a bitmap, a text
 * input, Submit/Cancel — dismiss = cancel, like [CodeDialog]. Debug quality;
 * the suspend hand-off runs through [CodePrompt.askCaptcha].
 */
@Composable
private fun CaptchaDialog(image: ByteArray, onSubmit: (String?) -> Unit) {
    var code by remember { mutableStateOf("") }
    val bitmap = remember(image) { BitmapFactory.decodeByteArray(image, 0, image.size) }
    AlertDialog(
        onDismissRequest = { onSubmit(null) },
        title = { Text("Captcha required") },
        text = {
            Column {
                Text(
                    "Xiaomi wants a captcha — type the characters shown in the image.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.heightIn(min = 8.dp))
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "captcha image",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 140.dp),
                    )
                } else {
                    Text(
                        "captcha image could not be decoded",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.heightIn(min = 8.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    singleLine = true,
                    label = { Text("Captcha code") },
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
