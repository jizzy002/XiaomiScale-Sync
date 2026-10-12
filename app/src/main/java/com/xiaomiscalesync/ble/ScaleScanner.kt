package com.xiaomiscalesync.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import com.xiaomiscalesync.proto.ScaleSession
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The scale's secrets as the scanner needs them (hex, validated upstream). */
data class ScaleKeys(val mac: String, val tokenHex: String?, val bleKeyHex: String?) {
    override fun toString(): String = "ScaleKeys(mac=${ScaleScanner.maskMac(mac)}, token=<redacted>, bleKey=<redacted>)"
}

/**
 * The foreground BLE side of the app, shared by the main weighing screen
 * (Task 15) and the milestone-0 spike: scan for `0xfe95` advertisements,
 * decode them with [BeaconDecoder], hand each decoded beacon to [onBeacon]
 * (the caller drives `SyncEngine.onBeacon` → `syncNow`), and open
 * [ScaleSession]s for the engine — only ever to a scale that advertised
 * within [VISIBLE_FRESH_MS] (sleep discipline: never connect to a scale
 * that is not awake).
 *
 * Scans only between [startListening] and [stopListening]; there is no
 * background scanning and no timer that connects.
 *
 * Secrets discipline: [log] lines carry masked MACs and sanitized error
 * text only — TOKEN / BLE KEY never reach it.
 */
class ScaleScanner(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val keys: () -> ScaleKeys?,
    private val log: (String) -> Unit = {},
    private val onBeacon: suspend (Beacon, BroadcastWeighIn?) -> Unit,
) {

    @Volatile
    private var lastSeenMs = 0L

    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null

    private val _listening = MutableStateFlow(false)

    /**
     * Master gate (owner request 2026-10-13: an off switch, and leaving the
     * app must stop listening). While false nothing scans and nothing
     * connects — not even [openSession]'s own wake-up scan, which could
     * otherwise restart scanning after the user left. The owning screen
     * sets it from its resumed state + the listening switch.
     */
    @Volatile
    var allowed: Boolean = true

    /** True while a scan is running. */
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    // ---------------------------------------------------------- permissions

    /**
     * API 31+: `BLUETOOTH_SCAN` (neverForLocation) + `BLUETOOTH_CONNECT`;
     * below 31 the legacy scan needs fine location (the old Bluetooth
     * permissions are install-time, never requested).
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

    // -------------------------------------------------------------- scanning

    /**
     * Starts a foreground scan. Returns false (with a log line saying why)
     * when the adapter, Bluetooth, or a permission is missing.
     */
    fun startListening(): Boolean {
        if (!allowed) return false
        if (_listening.value) return true
        val adapter = adapterOrNull() ?: run {
            log("no Bluetooth adapter — cannot scan")
            return false
        }
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            log("scan permission missing (${missing.joinToString()}) — grant it first")
            return false
        }
        val bleScanner = try {
            adapter.bluetoothLeScanner
        } catch (e: SecurityException) {
            log("scan denied: ${e.message}")
            return false
        } ?: run {
            log("Bluetooth is off — turn it on to listen for the scale")
            return false
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
            return false
        } catch (e: IllegalStateException) {
            log("scan failed: ${e.message}")
            return false
        }
        scanner = bleScanner
        scanCallback = callback
        _listening.value = true
        log("listening for scale advertisements (service 0xfe95)")
        return true
    }

    fun stopListening() {
        if (!_listening.value) return
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
            // Permission revoked mid-scan: the scanner dies with the app anyway.
        } catch (_: IllegalStateException) {
            // Bluetooth turned off underneath us.
        }
        scanner = null
        scanCallback = null
        _listening.value = false
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
            val k = keys()
            if (k != null && !address.equals(k.mac, ignoreCase = true)) return

            val now = System.currentTimeMillis()
            val gap = lastSeenMs == 0L || now - lastSeenMs > VISIBLE_LOG_GAP_MS
            lastSeenMs = now
            if (gap) log("scale visible: ${maskMac(address)} (rssi ${result.rssi})")
            if (k == null) return // keys not stored yet: visibility only

            val bleKey = k.bleKeyHex?.let(::hexToBytes)
            val beacon = try {
                BeaconDecoder.decode(serviceData, k.mac, bleKey)
            } catch (e: IllegalArgumentException) {
                // A malformed stored MAC throws here — surfaced, not
                // swallowed, and sanitized (the message may embed it).
                log("beacon decode failed: ${sanitize(e.message)}")
                return
            } ?: return
            val weighIn = beacon.objects
                .firstOrNull { it.first == WeighInObjects.OBJ_WEIGH_IN }
                ?.let { WeighInObjects.parse(it.second) }
            scope.launch {
                try {
                    onBeacon(beacon, weighIn)
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    log("beacon handling failed: ${sanitize(t.message)}")
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            log("scan failed (errorCode $errorCode)")
            _listening.value = false
            scanner = null
            scanCallback = null
        }
    }

    // ------------------------------------------------------------- sessions

    /**
     * The `SyncEngine` session factory: keys present → wait for a *fresh*
     * advertisement → connect [AndroidTransport] → hand a [ScaleSession]
     * over. Returns null (engine sees SCALE_ABSENT) instead of ever
     * attempting a connection to a scale that is not awake.
     */
    suspend fun openSession(): ScaleSession? {
        val k = keys()
        val tokenHex = k?.tokenHex
        if (k == null || tokenHex == null) {
            log("no scale keys stored — cannot connect")
            return null
        }
        if (!allowed) {
            log("listening is off — NOT connecting")
            return null
        }
        if (!_listening.value) startListening()
        if (!awaitVisible()) {
            log("scale not visible — step on it to wake it; NOT connecting (sleep discipline)")
            return null
        }
        return try {
            val adapter = adapterOrNull() ?: throw IllegalStateException("no Bluetooth adapter")
            log("connecting to ${maskMac(k.mac)} …")
            val transport = AndroidTransport.connect(adapter.getRemoteDevice(k.mac), appContext, log)
            try {
                ScaleSession(transport, hexToBytes(tokenHex), log)
            } catch (c: CancellationException) {
                // A connected GATT must not leak if session setup is cancelled.
                withContext(NonCancellable) { runCatching { transport.close() } }
                throw c
            } catch (t: Throwable) {
                // ditto when the constructor itself throws after connect succeeded
                runCatching { transport.close() }
                log("session setup failed: " + sanitize(t.message).ifEmpty { t::class.java.simpleName })
                null
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            log("connect failed: ${sanitize(t.message).ifEmpty { t::class.java.simpleName }}")
            null
        }
    }

    /** Fresh-advertisement gate: a [VISIBLE_FRESH_MS]-recent sighting within 10 s. */
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

    private fun adapterOrNull(): BluetoothAdapter? =
        appContext.getSystemService(BluetoothManager::class.java)?.adapter

    companion object {
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

        /** Exception text made safe for logs/UI: every MAC-shaped fragment masked. */
        fun sanitize(text: String?): String =
            text?.replace(MAC_IN_TEXT) { maskMac(it.value) } ?: ""

        /** Validated hex → bytes (only ever fed upstream-validated input). */
        fun hexToBytes(hex: String): ByteArray =
            ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
