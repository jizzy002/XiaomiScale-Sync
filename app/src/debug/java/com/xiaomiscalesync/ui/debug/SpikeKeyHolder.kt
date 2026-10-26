package com.xiaomiscalesync.ui.debug

import android.content.SharedPreferences
import androidx.core.content.edit
import com.xiaomiscalesync.garmin.GarminTokens
import com.xiaomiscalesync.garmin.TokenStore
import java.util.Locale

/**
 * Milestone-0 key storage: a plain [SharedPreferences] stand-in for the
 * Keystore-backed `data/Secrets.kt` that Task 14 (onboarding) builds.
 *
 * Debug quality **on purpose** — MAC / TOKEN / BLE KEY sit in cleartext
 * preferences so the spike can run before the Keystore layer exists. The
 * spike's job is proving the protocol on real hardware; onboarding swaps
 * this for `Secrets` wholesale. Never ship this path (recorded in the
 * milestone-0 report and checklist).
 *
 * Validation lives here because this is the spike's only write path:
 * a malformed MAC would crash [com.xiaomiscalesync.ble.BeaconDecoder]
 * (its `scaleMac` contract) and a short TOKEN would fail the miauth login
 * with a confusing HMAC error instead of a clear "bad key" message.
 */
class SpikeKeyHolder(private val prefs: SharedPreferences) {

    companion object {
        private const val KEY_MAC = "spike_mac"
        private const val KEY_TOKEN = "spike_token"
        private const val KEY_BLE_KEY = "spike_ble_key"

        /** 6 bytes of hex, colons/dashes/case optional (the decoder's contract). */
        fun isMac(value: String): Boolean =
            value.filter { it.isLetterOrDigit() }.let { hex ->
                hex.length == 12 && hex.all { it in "0123456789abcdefABCDEF" }
            }

        /** Exact-length hex string check (TOKEN 24, BLE KEY 32). */
        fun isHex(value: String?, length: Int): Boolean =
            value != null && value.length == length &&
                value.all { it in "0123456789abcdefABCDEF" }

        /**
         * Canonical MAC form (uppercase, colon-separated) — what
         * `BluetoothDevice.address` and `BluetoothAdapter.getRemoteDevice`
         * both use. [isMac] accepts bare/dash hex for input convenience;
         * normalizing once at the single write path guarantees the stored
         * value can never break the scan-equals check or `getRemoteDevice`.
         */
        fun normalizeMac(value: String): String =
            value.filter { it.isLetterOrDigit() }
                .uppercase(Locale.US)
                .chunked(2)
                .joinToString(":")
    }

    /** The scale's MAC (canonical `AA:BB:…` form), or null before extraction. */
    var mac: String? = prefs.getString(KEY_MAC, null)
        private set

    /** Cloud TOKEN, 24 hex chars — the miauth GATT login secret. */
    var tokenHex: String? = prefs.getString(KEY_TOKEN, null)
        private set

    /** BLE KEY, 32 hex chars — decrypts MiBeacon broadcasts; may be absent. */
    var bleKeyHex: String? = prefs.getString(KEY_BLE_KEY, null)
        private set

    /**
     * Persists the extracted keys after validating them.
     *
     * The MAC is normalized to the canonical `AA:BB:CC:DD:EE:FF` form here —
     * the only write path — so downstream scan-equals / `getRemoteDevice`
     * calls always see the platform's own format.
     *
     * @throws IllegalArgumentException with a field-level message (never
     *   echoing the value) when the MAC or TOKEN is malformed (the BLE KEY
     *   may be null — the session path works without it; only broadcast
     *   capture degrades).
     */
    fun save(mac: String, tokenHex: String?, bleKeyHex: String?) {
        require(isMac(mac)) { "MAC malformed: expected 6 bytes of hex" }
        require(isHex(tokenHex, 24)) { "TOKEN is not 24 hex characters" }
        require(bleKeyHex == null || isHex(bleKeyHex, 32)) { "BLE KEY is not 32 hex characters" }
        val normalizedMac = normalizeMac(mac)
        prefs.edit {
            putString(KEY_MAC, normalizedMac)
            putString(KEY_TOKEN, tokenHex)
            putString(KEY_BLE_KEY, bleKeyHex)
        }
        this.mac = normalizedMac
        this.tokenHex = tokenHex
        this.bleKeyHex = bleKeyHex
    }
}

/**
 * Spike [TokenStore]: plain [SharedPreferences] persistence of the Garmin
 * DI tokens. Debug quality like [SpikeKeyHolder] — the tokens must survive
 * restarts so the duplicate guard can run against a signed-in session, but
 * Keystore-backed storage is a later task's job (the interface KDoc
 * already defers it). `GarminTokens.toString` is redacted upstream, so a
 * token can never leak through the log pane via this class.
 */
class SharedTokenStore(private val prefs: SharedPreferences) : TokenStore {

    companion object {
        private const val KEY_ACCESS = "garmin_access_token"
        private const val KEY_REFRESH = "garmin_refresh_token"
        private const val KEY_CLIENT = "garmin_client_id"
        private const val KEY_EXPIRES = "garmin_expires_at"
    }

    override suspend fun load(): GarminTokens? {
        val access = prefs.getString(KEY_ACCESS, null) ?: return null
        return GarminTokens(
            accessToken = access,
            refreshToken = prefs.getString(KEY_REFRESH, null),
            clientId = prefs.getString(KEY_CLIENT, null) ?: return null,
            expiresAtMillis = prefs.getLong(KEY_EXPIRES, -1L).takeIf { it >= 0 },
        )
    }

    override suspend fun save(tokens: GarminTokens) {
        prefs.edit {
            putString(KEY_ACCESS, tokens.accessToken)
            putString(KEY_REFRESH, tokens.refreshToken)
            putString(KEY_CLIENT, tokens.clientId)
            putLong(KEY_EXPIRES, tokens.expiresAtMillis ?: -1L)
        }
    }
}
