package com.xiaomiscalesync.proto

import com.xiaomiscalesync.crypto.Hkdf
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The session keys derived by the miauth login
 * (`dev_key|app_key|dev_iv|app_iv` from the 64-byte HKDF output).
 * The encrypted channel (Task 6) uses them: `app_key`/`app_iv` for
 * app→scale values, `dev_key`/`dev_iv` for scale→app ones.
 */
data class SessionKeys(
    val devKey: ByteArray,
    val appKey: ByteArray,
    val devIv: ByteArray,
    val appIv: ByteArray,
)

/**
 * One GATT session against a Xiaomi scale: the notification-enable and
 * probe order the scale *requires* ([setup]), the miauth login ([login]),
 * and the teardown ([close]).
 *
 * Order fidelity is the whole point of [setup]: the reference client
 * (faraga1/xiaomi-scale-s200-ble) enabled all four notifications up
 * front and started the login right after the probe — about 57% of those
 * logins then got no answer at all. The order that works, and that this
 * class reproduces byte-for-byte:
 *
 * ```
 * enable AUTH, CMD, PROP           (CCCD subscribe, in this order)
 * LOGIN  ← a4                      payload-size probe trigger
 * AUTH   ⇄ 00000400 06f2           scale sends; app echoes 00000500 06f2
 * AUTH   ⇄ 00000401 + f2-run       scale sends; app echoes 00000501 + f2-run
 * maxValue = big probe payload     (the 4-byte prefix excluded)
 * … ~200 ms pause …                (the stall guard; the fake gates on ≥150 ms)
 * enable LOGIN
 * LOGIN  ← 24000000                login trigger
 * AUTH   ⇄ rand_key  (tag 0x0b)    16 random bytes, app → scale
 * AUTH   ⇄ remote_key (tag 0x0d)   16 bytes, scale → app
 * AUTH   ⇄ remote_info (tag 0x0c)  32 bytes, scale → app
 *        keys = HKDF-SHA256(ikm=TOKEN, salt=rand_key+remote_key,
 *                           info=b"mible-login-info", length=64)
 *        remote_info must equal HMAC-SHA256(dev_key, remote_key+rand_key)
 * AUTH   ⇄ login_info (tag 0x0a)   HMAC-SHA256(app_key, rand_key+remote_key)
 * LOGIN  → 21000000                login OK
 * ```
 */
class ScaleSession(
    private val transport: BleTransport,
    private val token: ByteArray,
    private val log: (String) -> Unit = {},
) {

    /**
     * Largest value that fits one write, from the big probe frame: its
     * `0xf2` payload length (the 4-byte prefix excluded, per the pre-flight
     * ruling), so `session.maxValue == fakeScale.maxValue` on the loopback.
     * 20 is what fits the BLE default ATT_MTU of 23, before the probe.
     */
    var maxValue: Int = 20

    /** The keys a successful [login] derived; the Task 6 channel uses them. */
    var keys: SessionKeys? = null
        private set

    /**
     * Runs the setup order the scale requires: enable AUTH/CMD/PROP, the
     * `a4` payload-size probe (echoing both probe frames back with byte 2
     * flipped `04 → 05`, exactly like the reference app), [maxValue] from
     * the big probe, the ~200 ms pause, and only then LOGIN notifications.
     */
    suspend fun setup() {
        // Same order as the Xiaomi app: LOGIN notifications only after the
        // probe, with a pause in between. Enabling all four up front and
        // logging in straight after the probe stalled ~57% of logins.
        for (char in listOf(CharId.AUTH, CharId.CMD, CharId.PROP)) {
            log("enable notifications on $char")
            transport.enableNotifications(char)
        }

        log("probe: writing a4")
        transport.write(CharId.LOGIN, PROBE_WRITE)
        val small = awaitFrame(CharId.AUTH, "small probe")
        expectPrefix(small, PROBE_SMALL_PREFIX, "small probe")
        transport.write(CharId.AUTH, echo(small))
        val big = awaitFrame(CharId.AUTH, "big probe")
        expectPrefix(big, PROBE_BIG_PREFIX, "big probe")
        transport.write(CharId.AUTH, echo(big))
        maxValue = big.size - 4 // the 0xf2 payload; the 4-byte prefix is not part of a value
        log("probe: maxValue=$maxValue")

        delay(PAUSE_MS) // the reference's stall guard
        log("enable notifications on LOGIN")
        transport.enableNotifications(CharId.LOGIN)
    }

    /**
     * miauth login: the `24000000` trigger, the rand_key exchange on AUTH,
     * key derivation from [token], remote_info verification (this is where
     * a wrong token fails), login_info, and the `21000000` confirmation.
     */
    suspend fun login() {
        val randKey = ByteArray(RAND_KEY_BYTES).also { random.nextBytes(it) }

        log("login: trigger")
        transport.write(CharId.LOGIN, LOGIN_TRIGGER)
        Framing.sendValue(transport, CharId.AUTH, TAG_RAND_KEY, randKey, maxValue)

        // Bounded like every other handshake step (the framing layer's
        // first-frame wait is untimed by design for the receive loop, but
        // mid-handshake silence is the stall this session must survive).
        val (remoteKeyTag, remoteKey) = awaitValue(CharId.AUTH, "remote_key")
        if (remoteKeyTag != TAG_REMOTE_KEY || remoteKey.size != REMOTE_KEY_BYTES) {
            throw ProtocolError(
                "expected remote_key (tag 0x%02x, %d bytes), got tag 0x%02x, %d bytes"
                    .format(TAG_REMOTE_KEY, REMOTE_KEY_BYTES, remoteKeyTag, remoteKey.size),
            )
        }
        val (remoteInfoTag, remoteInfo) = awaitValue(CharId.AUTH, "remote_info")
        if (remoteInfoTag != TAG_REMOTE_INFO || remoteInfo.size != REMOTE_INFO_BYTES) {
            throw ProtocolError(
                "expected remote_info (tag 0x%02x, %d bytes), got tag 0x%02x, %d bytes"
                    .format(TAG_REMOTE_INFO, REMOTE_INFO_BYTES, remoteInfoTag, remoteInfo.size),
            )
        }

        val derived = deriveKeys(token, randKey, remoteKey)
        keys = derived
        val expectedRemoteInfo = hmac(derived.devKey, remoteKey + randKey)
        if (!MessageDigest.isEqual(expectedRemoteInfo, remoteInfo)) {
            throw ProtocolError(
                "remote_info HMAC mismatch -- token is wrong (it must be the cloud " +
                    "'TOKEN' field, not the BLE KEY/bindkey)",
            )
        }

        val loginInfo = hmac(derived.appKey, randKey + remoteKey)
        Framing.sendValue(transport, CharId.AUTH, TAG_LOGIN_INFO, loginInfo, maxValue)

        val confirm = awaitFrame(CharId.LOGIN, "login confirmation")
        if (!confirm.contentEquals(LOGIN_OK)) {
            throw ProtocolError("login rejected: ${confirm.hex()}")
        }
        log("login OK")
    }

    /** Closes the link. */
    suspend fun close() = transport.close()

    // ---------- helpers ----------

    /** One raw (un-framed) frame, timed out into [ProtocolError]. */
    private suspend fun awaitFrame(char: CharId, what: String): ByteArray = try {
        withTimeout(Framing.ACK_TIMEOUT_MS) { transport.notifications.getValue(char).receive() }
    } catch (e: TimeoutCancellationException) {
        throw ProtocolError("no $what on $char within ${Framing.ACK_TIMEOUT_MS}ms")
    } catch (e: ClosedReceiveChannelException) {
        throw ProtocolError("$char: link closed while waiting for the $what")
    }

    /**
     * One framed/direct value, bounded like every other handshake step:
     * [Framing.recvValue]'s first-frame wait is untimed by design (a
     * receive loop may idle between values), but mid-handshake silence is
     * a stall, so expiry surfaces as [ProtocolError]. A closed link is
     * already a [ProtocolError] inside [Framing.recvValue].
     */
    private suspend fun awaitValue(char: CharId, what: String): Pair<Int, ByteArray> = try {
        withTimeout(Framing.ACK_TIMEOUT_MS) { Framing.recvValue(transport, char) }
    } catch (e: TimeoutCancellationException) {
        throw ProtocolError("no $what on $char within ${Framing.ACK_TIMEOUT_MS}ms")
    }

    private fun expectPrefix(frame: ByteArray, prefix: ByteArray, what: String) {
        if (frame.size < prefix.size || !prefix.indices.all { frame[it] == prefix[it] }) {
            throw ProtocolError("unexpected $what ${frame.hex()}, expected prefix ${prefix.hex()}")
        }
    }

    /** A probe frame echoed back: the whole frame, byte 2 flipped `04 → 05`. */
    private fun echo(frame: ByteArray): ByteArray = frame.copyOf().also { it[2] = 0x05 }

    companion object {
        private const val RAND_KEY_BYTES = 16
        private const val REMOTE_KEY_BYTES = 16
        private const val REMOTE_INFO_BYTES = 32
        private const val KEY_MATERIAL_BYTES = 64

        /** The reference client's stall guard between probe and login. */
        const val PAUSE_MS = 200L

        private const val TAG_RAND_KEY = 0x0b
        private const val TAG_REMOTE_KEY = 0x0d
        private const val TAG_REMOTE_INFO = 0x0c
        private const val TAG_LOGIN_INFO = 0x0a

        private val PROBE_WRITE = hex("a4")
        private val PROBE_SMALL_PREFIX = hex("00000400")
        private val PROBE_BIG_PREFIX = hex("00000401")
        private val LOGIN_TRIGGER = hex("24000000")
        private val LOGIN_OK = hex("21000000")
        private val LOGIN_INFO = "mible-login-info".toByteArray(Charsets.US_ASCII)

        private val random = SecureRandom()

        /**
         * The miauth key derivation, exactly as the reference client:
         * `HKDF-SHA256(ikm=TOKEN, salt=rand_key+remote_key,
         * info=b"mible-login-info", length=64)` split into
         * `dev_key=k[0..16], app_key=k[16..32], dev_iv=k[32..36],
         * app_iv=k[36..40]`.
         */
        fun deriveKeys(token: ByteArray, randKey: ByteArray, remoteKey: ByteArray): SessionKeys {
            val k = Hkdf.sha256(token, randKey + remoteKey, LOGIN_INFO, KEY_MATERIAL_BYTES)
            return SessionKeys(
                devKey = k.copyOfRange(0, 16),
                appKey = k.copyOfRange(16, 32),
                devIv = k.copyOfRange(32, 36),
                appIv = k.copyOfRange(36, 40),
            )
        }

        private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(data)
        }

        private fun hex(s: String): ByteArray =
            ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

        private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
    }
}
