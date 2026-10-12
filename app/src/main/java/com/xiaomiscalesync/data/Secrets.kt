package com.xiaomiscalesync.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.xiaomiscalesync.garmin.GarminTokens
import com.xiaomiscalesync.garmin.TokenStore
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Format rules for the three scale secrets (pure Kotlin — JVM-testable).
 *
 * These are the production twins of the spike's `SpikeKeyHolder`
 * companion: TOKEN is exactly 24 hex characters (12 bytes), BLE KEY
 * exactly 32 hex (16 bytes), MAC is 6 bytes of hex with colons/dashes/
 * case optional on input and canonicalized on save. Validation happens
 * before anything touches storage so a malformed key can never reach
 * [com.xiaomiscalesync.ble.BeaconDecoder] or a miauth login.
 */
object KeyFormat {

    const val TOKEN_LENGTH = 24
    const val BLE_KEY_LENGTH = 32

    /** 6 bytes of hex, colons/dashes/case optional. */
    fun isMac(value: String): Boolean =
        value.filter { it.isLetterOrDigit() }.let { hex ->
            hex.length == 12 && hex.all { it in "0123456789abcdefABCDEF" }
        }

    /** Exact-length hex string check (TOKEN 24, BLE KEY 32). */
    fun isHex(value: String?, length: Int): Boolean =
        value != null && value.length == length &&
            value.all { it in "0123456789abcdefABCDEF" }

    fun isToken(value: String?): Boolean = isHex(value, TOKEN_LENGTH)

    fun isBleKey(value: String?): Boolean = isHex(value, BLE_KEY_LENGTH)

    /**
     * Canonical MAC form (uppercase, colon-separated) — what
     * `BluetoothDevice.address` and `BluetoothAdapter.getRemoteDevice`
     * both use.
     */
    fun normalizeMac(value: String): String =
        value.filter { it.isLetterOrDigit() }
            .uppercase(java.util.Locale.US)
            .chunked(2)
            .joinToString(":")
}

/**
 * Write seam for the scale's secrets — [Secrets] on the device,
 * a fake in the onboarding tests. Lives in `data` (not in the UI package)
 * so nothing above the storage layer decides how keys are persisted.
 *
 * The `has*` probes are **presence booleans only**: the wizard seeds its
 * green-check flags from them on re-entry (rotation/process death) and
 * key bytes never travel up to the VM or any screen.
 */
interface KeyVault {
    /** Persists the scale's MAC in canonical `AA:BB:…` form. */
    fun saveMac(mac: String)

    /** Persists the cloud TOKEN (24 hex chars). */
    fun saveToken(tokenHex: String)

    /** Persists the BLE KEY (32 hex chars). */
    fun saveBleKey(bleKeyHex: String)

    /** True when a readable MAC record exists (decrypted successfully). */
    fun hasMac(): Boolean

    /** True when a readable TOKEN record exists. */
    fun hasToken(): Boolean

    /** True when a readable BLE KEY record exists. */
    fun hasBleKey(): Boolean
}

/**
 * Keystore-backed secret storage — the production replacement for the
 * spike's plain-prefs `SpikeKeyHolder` (which the debug screen keeps for
 * itself; nothing here reads or writes it).
 *
 * Every value is encrypted with an AES-256/GCM key held in the Android
 * Keystore (`AndroidKeyStore`), and only the ciphertext + random IV land
 * in `SharedPreferences` (`secrets.xml`, excluded from backups by
 * `AndroidManifest.xml`). The key never leaves secure hardware and is
 * never exported, so a backup/extract of the prefs file reveals nothing.
 *
 * This class performs **no logging at all** — key material (and the
 * Garmin tokens, via [TokenStore]) must never appear in log lines,
 * exception messages or crash reports; diagnostics report lengths and
 * green/red status only.
 *
 * Decryption failures (Keystore wiped, ciphertext from another device's
 * key) read as "absent" so the UI routes the owner back through
 * onboarding/Settings rather than crashing.
 */
class Secrets(context: Context) : KeyVault, TokenStore {

    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    // ------------------------------------------------------------- KeyVault

    override fun saveMac(mac: String) {
        require(KeyFormat.isMac(mac)) { "MAC malformed: expected 6 bytes of hex" }
        put(KEY_MAC, KeyFormat.normalizeMac(mac))
    }

    override fun saveToken(tokenHex: String) {
        require(KeyFormat.isToken(tokenHex)) { "TOKEN is not 24 hex characters" }
        put(KEY_TOKEN, tokenHex)
    }

    override fun saveBleKey(bleKeyHex: String) {
        require(KeyFormat.isBleKey(bleKeyHex)) { "BLE KEY is not 32 hex characters" }
        put(KEY_BLE_KEY, bleKeyHex)
    }

    /** The scale's MAC (canonical `AA:BB:…` form), or null before extraction. */
    fun mac(): String? = get(KEY_MAC)

    /** Cloud TOKEN (24 hex chars), or null before extraction. */
    fun token(): String? = get(KEY_TOKEN)

    /** BLE KEY (32 hex chars), or null before extraction. */
    fun bleKey(): String? = get(KEY_BLE_KEY)

    /**
     * Presence probes for the wizard's re-entry seeding — booleans only,
     * backed by a successful decrypt (a foreign/rotated Keystore record
     * reads as absent, exactly like the value getters).
     */
    override fun hasMac(): Boolean = get(KEY_MAC) != null

    override fun hasToken(): Boolean = get(KEY_TOKEN) != null

    override fun hasBleKey(): Boolean = get(KEY_BLE_KEY) != null

    // ------------------------------------------------------------ TokenStore

    /** Stored Garmin DI tokens, or null when the owner never signed in. */
    override suspend fun load(): GarminTokens? {
        val access = get(KEY_GARMIN_ACCESS) ?: return null
        return GarminTokens(
            accessToken = access,
            refreshToken = get(KEY_GARMIN_REFRESH),
            clientId = get(KEY_GARMIN_CLIENT) ?: return null,
            expiresAtMillis = get(KEY_GARMIN_EXPIRES)?.toLongOrNull(),
        )
    }

    /** Persists Garmin DI tokens, replacing any previously stored set. */
    override suspend fun save(tokens: GarminTokens) {
        val refresh = tokens.refreshToken
        val expires = tokens.expiresAtMillis
        put(KEY_GARMIN_ACCESS, tokens.accessToken)
        if (refresh != null) put(KEY_GARMIN_REFRESH, refresh)
        else prefs.edit().remove(KEY_GARMIN_REFRESH).apply()
        put(KEY_GARMIN_CLIENT, tokens.clientId)
        if (expires != null) put(KEY_GARMIN_EXPIRES, expires.toString())
        else prefs.edit().remove(KEY_GARMIN_EXPIRES).apply()
    }

    // -------------------------------------------------------------- crypto

    private fun put(key: String, value: String) {
        prefs.edit().putString(key, encrypt(value)).apply()
    }

    private fun get(key: String): String? =
        prefs.getString(key, null)?.let { stored ->
            try {
                decrypt(stored)
            } catch (_: GeneralSecurityException) {
                // Keystore key gone / foreign ciphertext → treat as absent.
                null
            } catch (_: IllegalArgumentException) {
                // Corrupt Base64 or malformed record → treat as absent.
                null
            }
        }

    /** AES/GCM/NoPadding; returns `base64(iv):base64(ciphertext)`. */
    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(cipher.iv) +
            IV_SEPARATOR +
            Base64.getEncoder().encodeToString(ciphertext)
    }

    private fun decrypt(stored: String): String {
        val sep = stored.indexOf(IV_SEPARATOR)
        require(sep > 0) { "malformed secret record" }
        val iv = Base64.getDecoder().decode(stored.substring(0, sep))
        val ciphertext = Base64.getDecoder().decode(stored.substring(sep + 1))
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    /**
     * The Android Keystore AES-256 key, created on first use. Synchronized
     * so two first-use writers can never mint competing keys (whose
     * ciphertext the loser could not read back).
     */
    @Synchronized
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)
            ?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val FILE_NAME = "secrets"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "xiaomiscalesync_secrets"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val IV_SEPARATOR = ":"

        const val KEY_MAC = "scale_mac"
        const val KEY_TOKEN = "scale_token"
        const val KEY_BLE_KEY = "scale_ble_key"

        const val KEY_GARMIN_ACCESS = "garmin_access_token"
        const val KEY_GARMIN_REFRESH = "garmin_refresh_token"
        const val KEY_GARMIN_CLIENT = "garmin_client_id"
        const val KEY_GARMIN_EXPIRES = "garmin_expires_at"
    }
}
