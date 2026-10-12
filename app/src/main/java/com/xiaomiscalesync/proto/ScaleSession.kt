package com.xiaomiscalesync.proto

import com.xiaomiscalesync.crypto.AesCcm
import com.xiaomiscalesync.crypto.AuthenticationMismatch
import com.xiaomiscalesync.crypto.Hkdf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
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

    /** Stored weigh-ins the scale pushes (event siid 6, after a 6.1 fetch). */
    val storedEvents: Channel<MiotMessage> = Channel(Channel.UNLIMITED)

    /** Live weigh-in events the scale pushes (event siid 5). */
    val liveEvents: Channel<MiotMessage> = Channel(Channel.UNLIMITED)

    /** Pending result/hello futures, keyed by the tid they were sent with. */
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<MiotMessage>>()

    /**
     * Outgoing (app→scale) encryption counter: starts at 0 per session and
     * increases by 1 per sent message; the same number goes into the
     * value's `ctr_le16` frame and the CCM nonce.
     */
    private var appCtr = 0

    /** Next outgoing tid (the scale picks its own tids for its events). */
    private val nextTid = AtomicInteger(1)

    /** Serialises outgoing sends so their framing handshakes on CMD cannot interleave. */
    private val sendMutex = Mutex()

    private var pumpJob: Job? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * RCV_* frames (`<rdy>`/`<ok>`/`<ack>`) the pump classifies on PROP.
     * Session requests go on CMD and await their acks as CMD
     * notifications, so nothing receives from here during a send — it
     * exists so a stray ack on PROP cannot kill the pump (the scale's
     * direction is PROP = scale→app values; its acks answer app sends on
     * CMD, per the reference `send_value`/`_expect` pairing). [sendRequest]
     * drains it after a failed send.
     */
    private val acks = Channel<ByteArray>(Channel.UNLIMITED)

    /**
     * The transport outgoing sends see: writes pass through, reads on CMD
     * (where the scale's acks for app sends arrive) pass through to the
     * transport's own channel, and PROP reads are redirected to [acks] —
     * the pump stays PROP's sole consumer, so no send can steal a
     * scale→app value from it.
     */
    private val sendTransport: BleTransport = object : BleTransport {
        override val notifications: Map<CharId, Channel<ByteArray>> =
            transport.notifications + (CharId.PROP to acks)

        override suspend fun write(to: CharId, bytes: ByteArray) = transport.write(to, bytes)
        override suspend fun close() = transport.close()
        override suspend fun enableNotifications(char: CharId) = transport.enableNotifications(char)
    }

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
        val expectedRemoteInfo = hmac(derived.devKey, remoteKey + randKey)
        if (!MessageDigest.isEqual(expectedRemoteInfo, remoteInfo)) {
            throw ProtocolError(
                "remote_info HMAC mismatch -- token is wrong (it must be the cloud " +
                    "'TOKEN' field, not the BLE KEY/bindkey)",
            )
        }
        keys = derived

        val loginInfo = hmac(derived.appKey, randKey + remoteKey)
        Framing.sendValue(transport, CharId.AUTH, TAG_LOGIN_INFO, loginInfo, maxValue)

        val confirm = awaitFrame(CharId.LOGIN, "login confirmation")
        if (!confirm.contentEquals(LOGIN_OK)) {
            throw ProtocolError("login rejected: ${confirm.hex()}")
        }
        log("login OK")
    }

    // ---------- encrypted channel ----------

    /**
     * Starts the PROP receive loop — the sole consumer of PROP
     * notifications. Each incoming value is decrypted (`dev_key`/`dev_iv`,
     * the CTR taken from the value's own frame) and dispatched by op:
     * OP_RESULT and OP_HELLO resolve the pending future registered under
     * their tid, OP_EVENT siid 6 → [storedEvents], siid 5 → [liveEvents],
     * everything else is logged and ignored. A second call is a no-op.
     */
    suspend fun startPump() {
        check(keys != null) { "login before startPump" }
        if (pumpJob != null) return
        pumpJob = scope.launch { pump() }
    }

    /** Sends OP_HELLO and awaits the scale's echo (same tid): non-zero status → [ProtocolError]. */
    suspend fun hello(): MiotMessage {
        val (tid, future) = register()
        try {
            sendRequest(MiotCodec.encode(tid, MiotCodec.OP_HELLO, ByteArray(0)))
            val reply = awaitFuture(tid, future)
            if (reply.status != 0) {
                throw ProtocolError("hello failed: status ${reply.status}")
            }
            return reply
        } finally {
            pending.remove(tid)
        }
    }

    /**
     * Sends action `<siid>.<aiid>` (params as pre-encoded [MiotCodec.param]
     * fragments) and awaits its OP_RESULT on the same tid: 6 s of silence
     * or a non-zero status surface as [ProtocolError]. Dispatch is by tid,
     * so events arriving meanwhile never disturb this wait.
     */
    suspend fun action(siid: Int, aiid: Int, vararg params: ByteArray): MiotMessage {
        val (tid, future) = register()
        try {
            val body = MiotCodec.action(siid, aiid, params.toList())
            sendRequest(MiotCodec.encode(tid, MiotCodec.OP_ACTION, body))
            val result = awaitFuture(tid, future)
            if (result.status != 0) {
                throw ProtocolError("action $siid.$aiid failed: status ${result.status}")
            }
            return result
        } finally {
            pending.remove(tid)
        }
    }

    /** Stops the pump, closes the event queues, and tears down the link. */
    suspend fun close() {
        pumpJob?.cancelAndJoin()
        storedEvents.close()
        liveEvents.close()
        acks.close()
        failPending(ProtocolError("session closed before a reply arrived"))
        transport.close()
    }

    // ---------- channel internals ----------

    private suspend fun pump() {
        val prop = transport.notifications.getValue(CharId.PROP)
        try {
            while (true) {
                val frame = prop.receive()
                when {
                    frame.contentEquals(BYTES_RDY) || frame.contentEquals(BYTES_OK) ||
                        frame.contentEquals(BYTES_ACK) -> acks.send(frame)

                    isValueStart(frame) -> {
                        val (_, value) = Framing.recvValue(transport, CharId.PROP, frame)
                        dispatch(decryptAndDecode(value))
                    }

                    else -> throw ProtocolError("PROP: unexpected frame ${frame.hex()}")
                }
            }
        } catch (e: ClosedReceiveChannelException) {
            log("pump: link closed")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log("pump: failed: ${e.message}")
            failPending(ProtocolError("session pump failed: ${e.message}"))
        }
    }

    /** Routes one decrypted message: results/hellos by tid, events by siid. */
    private fun dispatch(msg: MiotMessage) {
        when (msg.op) {
            MiotCodec.OP_RESULT, MiotCodec.OP_HELLO -> {
                val future = pending.remove(msg.tid)
                if (future == null) {
                    log("pump: no pending request for tid ${msg.tid} (op 0x${msg.op.toString(16)})")
                } else {
                    future.complete(msg)
                }
            }

            MiotCodec.OP_EVENT -> when (msg.siid) {
                SIID_STORED -> storedEvents.trySend(msg)
                SIID_LIVE -> liveEvents.trySend(msg)
                else -> log("pump: ignoring event ${msg.siid}.${msg.iid}")
            }

            else -> log("pump: ignoring message op 0x${msg.op.toString(16)} tid ${msg.tid}")
        }
    }

    private fun decryptAndDecode(value: ByteArray): MiotMessage {
        val k = keys ?: throw ProtocolError("session value arrived before login")
        val plaintext = try {
            // The CTR comes from the value's own frame; no counter guessing.
            decryptValue(k.devKey, k.devIv, value)
        } catch (e: AuthenticationMismatch) {
            throw ProtocolError("session decrypt failed: ${e.message}")
        }
        return MiotCodec.decode(plaintext)
    }

    private fun isValueStart(frame: ByteArray): Boolean =
        (frame.size >= 4 && frame[0] == 0.toByte() && frame[1] == 0.toByte() && frame[2] == 0x02.toByte()) ||
            (frame.size == 6 && frame[0] == 0.toByte() && frame[1] == 0.toByte() && frame[2] == 0.toByte())

    private fun failPending(error: Throwable) {
        for (future in pending.values) future.completeExceptionally(error)
        pending.clear()
    }

    /**
     * Encrypts and sends one app→scale message on CMD — the reference's
     * direction: `CMD 0x001a` is app→scale, `PROP 0x001b` is scale→app,
     * and `send_value("CMD", …)` awaits its `RCV_RDY`/`RCV_OK` from CMD's
     * own notifications. A request on PROP is never heard by the scale
     * (it only sends there).
     */
    private suspend fun sendRequest(plaintext: ByteArray) {
        val k = keys ?: throw ProtocolError("not logged in")
        sendMutex.withLock {
            val ctr = appCtr
            appCtr += 1
            val value = encryptValue(k.appKey, k.appIv, ctr, plaintext)
            try {
                Framing.sendValue(sendTransport, CharId.CMD, TAG_SESSION, value, maxValue)
            } catch (e: Throwable) {
                // A half-dead transfer can leave an ack in flight — on CMD,
                // where this send's acks arrive, or in the pump's PROP sink.
                // Drop both so the next send cannot pair with a stale ack.
                while (acks.tryReceive().isSuccess) { /* drain */ }
                val cmd = transport.notifications.getValue(CharId.CMD)
                while (cmd.tryReceive().isSuccess) { /* drain */ }
                throw e
            }
        }
    }

    private fun register(): Pair<Int, CompletableDeferred<MiotMessage>> {
        val tid = nextTid.getAndIncrement() and 0xFFFF
        val future = CompletableDeferred<MiotMessage>()
        pending[tid] = future
        return tid to future
    }

    private suspend fun awaitFuture(
        tid: Int,
        future: CompletableDeferred<MiotMessage>,
    ): MiotMessage = try {
        withTimeout(RESULT_TIMEOUT_MS) { future.await() }
    } catch (e: TimeoutCancellationException) {
        throw ProtocolError("no reply for tid $tid within ${RESULT_TIMEOUT_MS}ms")
    }

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

        /** The framing tag session values use (app→scale on CMD, scale→app on PROP). */
        private const val TAG_SESSION = 0x01

        /** MIoT service ids the pump routes events by. */
        private const val SIID_LIVE = 5
        private const val SIID_STORED = 6

        private const val IV_BYTES = 4

        /** How long [hello]/[action] wait for their result on the same tid. */
        const val RESULT_TIMEOUT_MS = 6_000L

        private val ZERO4 = ByteArray(4)
        private val BYTES_RDY = hex(Framing.RCV_RDY)
        private val BYTES_OK = hex(Framing.RCV_OK)
        private val BYTES_ACK = hex(Framing.RCV_ACK)

        /**
         * One session value, both directions: `<ctr_le16><AES-CCM ct|tag(4)>`
         * where the nonce is `iv(4) || 00000000 || ctr_le32` with no AAD —
         * the CTR in the frame and the CTR in the nonce are the same
         * number (`app_key`/`app_iv` outbound, `dev_key`/`dev_iv` inbound).
         */
        fun encryptValue(key: ByteArray, iv: ByteArray, ctr: Int, plaintext: ByteArray): ByteArray =
            le16(ctr) + AesCcm.encrypt(key, sessionNonce(iv, ctr), null, plaintext)

        /** Decrypts one session value; the CTR comes from the frame itself. */
        fun decryptValue(key: ByteArray, iv: ByteArray, value: ByteArray): ByteArray {
            require(value.size >= 2 + AesCcm.TAG_LENGTH_BYTES) {
                "session value too short for ctr+tag: ${value.size} bytes"
            }
            val ctr = u16(value, 0)
            return AesCcm.decrypt(key, sessionNonce(iv, ctr), null, value.copyOfRange(2, value.size))
        }

        /** `iv(4) || 00000000 || ctr_le32` — the 12-byte CCM nonce of one message. */
        fun sessionNonce(iv: ByteArray, ctr: Int): ByteArray {
            require(iv.size == IV_BYTES) { "session iv must be $IV_BYTES bytes, was ${iv.size}" }
            return iv + ZERO4 + le32(ctr)
        }

        private fun le16(v: Int): ByteArray =
            byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

        private fun le32(v: Int): ByteArray = ByteArray(4) { i -> (v shr (8 * i)).toByte() }

        private fun u16(buf: ByteArray, at: Int): Int =
            (buf[at].toInt() and 0xFF) or ((buf[at + 1].toInt() and 0xFF) shl 8)

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
