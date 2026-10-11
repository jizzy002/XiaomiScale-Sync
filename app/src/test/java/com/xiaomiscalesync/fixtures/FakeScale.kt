package com.xiaomiscalesync.fixtures

import com.xiaomiscalesync.crypto.Hkdf
import com.xiaomiscalesync.proto.BleTransport
import com.xiaomiscalesync.proto.CharId
import com.xiaomiscalesync.proto.Framing
import com.xiaomiscalesync.proto.ProtocolError
import com.xiaomiscalesync.proto.SessionKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * In-memory simulation of the scale's side of the protocol — test only.
 *
 * Mirrors faraga1/xiaomi-scale-s200-ble's `FakeScale`/`DeviceSide`: the
 * `a4` payload-size probe, both directions of the framing layer, and the
 * miauth login.
 *
 * Bind it to the scale end of a [loopback] via [accept]; the responder
 * runs on its own dispatcher, records every rejected frame in [errors]
 * and every value received from the app in [receivedValues], so tests
 * can assert the scale's side of the conversation exactly.
 *
 * Probe sequence (reference client, verbatim): app writes `a4` to LOGIN;
 * the scale answers on AUTH with `00 00 04 00 06 f2`, expects the echo
 * `00 00 05 00 06 f2`, then sends `00 00 04 01` + `maxValue` bytes of
 * `0xf2` and expects that frame echoed with `04` replaced by `05`.
 *
 * miauth login (reference client, verbatim): `24000000` on LOGIN, then
 * rand_key (tag `0x0b`, 16B, framed) on AUTH; the scale answers with
 * direct frames `remote_key` (tag `0x0d`, 16B) and `remote_info` (tag
 * `0x0c`, 32B = `HMAC-SHA256(dev_key, remote_key+rand_key)` derived from
 * its own token), validates `login_info` (tag `0x0a` =
 * `HMAC-SHA256(app_key, rand_key+remote_key)`) and confirms with
 * `21000000` on LOGIN.
 *
 * With [failLoginUnlessOrdered] the scale simulates the reference's
 * stall: the `24000000` trigger is refused (responder error, no reply)
 * unless AUTH/CMD/PROP notifications were enabled first, the probe then
 * completed, and LOGIN was enabled no earlier than 150 ms after it.
 * The enable calls are observed through [watch], which wraps the app end.
 */
class FakeScale(
    val token: ByteArray,
    var maxValue: Int = 244,
    val failLoginUnlessOrdered: Boolean = false,
    /**
     * Test hardening: accept (ack) the framed rand_key transfer but then
     * never deliver remote_key/remote_info — the scale went silent
     * mid-handshake while every ack still flowed. The session's value
     * waits must surface this as a [ProtocolError], not hang.
     */
    val silentAfterRandKey: Boolean = false,
) {

    /** Scale-side end of the loopback; bound by [accept]. */
    lateinit var transport: BleTransport
        private set

    /** Values fully received from the app: (tag, data), in arrival order. */
    val receivedValues = CopyOnWriteArrayList<Pair<Int, ByteArray>>()

    /** Anything the responder rejected; tests assert this stays empty. */
    val errors = CopyOnWriteArrayList<Throwable>()

    /** Completed `a4` probes. */
    @Volatile
    var probeCount: Int = 0
        private set

    /** Framed values this scale has finished sending to the app. */
    @Volatile
    var sentCount: Int = 0
        private set

    /** Completed miauth logins (a `21000000` was sent). */
    @Volatile
    var loginCount: Int = 0
        private set

    /** Direct frames from the scale that the app acked (`00 00 03 00`). */
    @Volatile
    var directAckCount: Int = 0
        private set

    /**
     * The characteristics whose notifications the app enabled, in call
     * order (via [watch]) — tests assert the exact reference order
     * AUTH, CMD, PROP … LOGIN.
     */
    val enableOrder = CopyOnWriteArrayList<CharId>()

    /** When the app enabled notifications on each characteristic (nanoTime). */
    private val enabledAt = ConcurrentHashMap<CharId, Long>()

    /** When the `a4` arrived / the probe completed (nanoTime; 0 = not yet). */
    @Volatile
    private var probeStartedAt: Long = 0

    @Volatile
    private var probeDoneAt: Long = 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val outbox = Channel<Outbound>(Channel.UNLIMITED)

    private class Outbound(val char: CharId, val tag: Int, val data: ByteArray)

    private enum class Stage { IDLE, ECHO_0500, ECHO_0501, WAIT_RAND_KEY, WAIT_LOGIN_INFO }

    private var stage = Stage.IDLE
    private var randKey = ByteArray(0)

    /** Starts the responder on the scale-side end of the link. */
    fun accept(t: BleTransport) {
        check(!::transport.isInitialized) { "FakeScale is already bound" }
        transport = t
        scope.launch {
            try {
                respond(t)
            } catch (e: ClosedReceiveChannelException) {
                // The link closed: the connection simply ended, not an error.
            } catch (e: Throwable) {
                errors += e
            }
        }
    }

    /**
     * Wraps the app end of the loopback so this fake observes the app's
     * notification-enable calls (the loopback itself has no CCCD; the
     * real GATT transport does). Everything else passes straight through,
     * so wire assertions can still cast the inner transport.
     */
    fun watch(appSide: BleTransport): BleTransport = object : BleTransport {
        override val notifications: Map<CharId, Channel<ByteArray>> get() = appSide.notifications
        override suspend fun write(to: CharId, bytes: ByteArray) = appSide.write(to, bytes)
        override suspend fun close() = appSide.close()
        override suspend fun enableNotifications(char: CharId) {
            notificationsEnabled(char)
            appSide.enableNotifications(char)
        }
    }

    /** Records one app-side notification-enable call, for the ordering gate. */
    fun notificationsEnabled(char: CharId) {
        enableOrder += char
        enabledAt[char] = System.nanoTime()
    }

    /**
     * Queues a framed value for the scale to send to the app
     * (header → ready wait → parcels → received wait, via
     * [Framing.sendValue]). Processed by the responder, so no two
     * coroutines ever consume the same notification channel.
     */
    suspend fun sendFramed(char: CharId, tag: Int, data: ByteArray) {
        check(::transport.isInitialized) { "call accept() first" }
        outbox.send(Outbound(char, tag, data))
    }

    /**
     * The responder: answers the `a4` probe, receives framed (or direct)
     * values from the app, services [sendFramed] requests, and runs the
     * miauth login — one frame at a time, so the scale's replies stay
     * strictly ordered.
     */
    private suspend fun respond(t: BleTransport) {
        while (true) {
            select<Unit> {
                outbox.onReceive { out ->
                    Framing.sendValue(t, out.char, out.tag, out.data, maxValue)
                    sentCount++
                }
                for ((char, ch) in t.notifications) {
                    ch.onReceive { frame ->
                        handle(t, char, frame)
                    }
                }
            }
        }
    }

    private suspend fun handle(t: BleTransport, char: CharId, frame: ByteArray) {
        // The app acking a direct frame — only expected while the miauth
        // replies (remote_key/remote_info) are in flight.
        if (frame.contentEquals(BYTES_ACK)) {
            if (stage == Stage.WAIT_RAND_KEY || stage == Stage.WAIT_LOGIN_INFO) {
                directAckCount++
                return
            }
            throw ProtocolError("scale got an unsolicited direct ack on $char")
        }
        when (stage) {
            Stage.ECHO_0500 -> {
                expectEcho(char, frame, PROBE_SMALL)
                t.write(CharId.AUTH, probeBig())
                stage = Stage.ECHO_0501
            }

            Stage.ECHO_0501 -> {
                expectEcho(char, frame, probeBig())
                probeDoneAt = System.nanoTime()
                probeCount++
                stage = Stage.IDLE
            }

            Stage.WAIT_RAND_KEY -> handleRandKey(t, char, frame)
            Stage.WAIT_LOGIN_INFO -> handleLoginInfo(t, char, frame)

            Stage.IDLE -> when {
                frame.contentEquals(PROBE_WRITE) -> {
                    if (char != CharId.LOGIN) {
                        throw ProtocolError("a4 probe arrived on $char, expected LOGIN")
                    }
                    probeStartedAt = System.nanoTime()
                    t.write(CharId.AUTH, PROBE_SMALL)
                    stage = Stage.ECHO_0500
                }

                frame.contentEquals(LOGIN_TRIGGER) -> {
                    if (char != CharId.LOGIN) {
                        throw ProtocolError("login trigger arrived on $char, expected LOGIN")
                    }
                    checkLoginOrder()
                    stage = Stage.WAIT_RAND_KEY
                }

                isHeader(frame) -> {
                    receiveFramed(t, char, frame)
                    stage = Stage.IDLE
                }

                else -> throw ProtocolError("scale got an unexpected frame on $char: ${frame.hex()}")
            }
        }
    }

    /**
     * The rand_key arrives framed on AUTH (tag `0x0b`, 16B); the scale
     * then sends remote_key and remote_info as direct values — the shapes
     * the reference scale uses whenever a value fits one write.
     */
    private suspend fun handleRandKey(t: BleTransport, char: CharId, frame: ByteArray) {
        if (char != CharId.AUTH || !isHeader(frame)) {
            throw ProtocolError("scale expected the rand_key value on AUTH, got ${frame.hex()} on $char")
        }
        val (tag, data) = receiveFramed(t, char, frame)
        if (tag != TAG_RAND_KEY || data.size != RAND_KEY_BYTES) {
            throw ProtocolError("expected rand_key (tag 0x%02x, 16 bytes), got tag 0x%02x, %d bytes"
                .format(TAG_RAND_KEY, tag, data.size))
        }
        randKey = data
        if (silentAfterRandKey) {
            // The transfer was acked; the scale then goes silent — no
            // remote_key, no remote_info, no further reply.
            return
        }
        val keys = sessionKeys(randKey, REMOTE_KEY)
        sendDirect(t, CharId.AUTH, TAG_REMOTE_KEY, REMOTE_KEY)
        sendDirect(t, CharId.AUTH, TAG_REMOTE_INFO, hmac(keys.devKey, REMOTE_KEY + randKey))
        stage = Stage.WAIT_LOGIN_INFO
    }

    /** The app's login_info (tag `0x0a`, 32B): validated, then `21000000`. */
    private suspend fun handleLoginInfo(t: BleTransport, char: CharId, frame: ByteArray) {
        if (char != CharId.AUTH || !isHeader(frame)) {
            throw ProtocolError("scale expected the login_info value on AUTH, got ${frame.hex()} on $char")
        }
        val (tag, data) = receiveFramed(t, char, frame)
        if (tag != TAG_LOGIN_INFO || data.size != LOGIN_INFO_BYTES) {
            throw ProtocolError("expected login_info (tag 0x%02x, 32 bytes), got tag 0x%02x, %d bytes"
                .format(TAG_LOGIN_INFO, tag, data.size))
        }
        val keys = sessionKeys(randKey, REMOTE_KEY)
        val expected = hmac(keys.appKey, randKey + REMOTE_KEY)
        if (!MessageDigest.isEqual(expected, data)) {
            throw ProtocolError("scale got a login_info HMAC mismatch: ${data.hex()}")
        }
        t.write(CharId.LOGIN, LOGIN_OK)
        loginCount++
        stage = Stage.IDLE
    }

    /**
     * The reference's stall, simulated: with [failLoginUnlessOrdered], the
     * login trigger is refused (the responder dies with this error, so the
     * app gets no answer) unless AUTH/CMD/PROP were enabled before the
     * probe and LOGIN only ≥150 ms after it completed.
     */
    private fun checkLoginOrder() {
        if (!failLoginUnlessOrdered) return
        val violations = mutableListOf<String>()
        for (char in listOf(CharId.AUTH, CharId.CMD, CharId.PROP)) {
            val at = enabledAt[char]
            if (at == null) {
                violations += "$char notifications were never enabled"
            } else if (probeStartedAt != 0L && at > probeStartedAt) {
                violations += "$char notifications were enabled after the probe started"
            }
        }
        if (probeDoneAt == 0L) {
            violations += "the probe has not completed"
        } else {
            val loginAt = enabledAt[CharId.LOGIN]
            if (loginAt == null) {
                violations += "LOGIN notifications were never enabled"
            } else if (loginAt < probeDoneAt + MIN_PAUSE_NS) {
                violations += "LOGIN notifications were enabled less than 150 ms after the probe"
            }
        }
        if (violations.isNotEmpty()) {
            throw ProtocolError("refusing login trigger, wrong setup order: ${violations.joinToString("; ")}")
        }
    }

    /** The scale side of the miauth key derivation, from its own token. */
    private fun sessionKeys(randKey: ByteArray, remoteKey: ByteArray): SessionKeys {
        val k = Hkdf.sha256(token, randKey + remoteKey, LOGIN_INFO, 64)
        return SessionKeys(
            devKey = k.copyOfRange(0, 16),
            appKey = k.copyOfRange(16, 32),
            devIv = k.copyOfRange(32, 36),
            appIv = k.copyOfRange(36, 40),
        )
    }

    /** Direct value frame `00 00 02 <tag> <data>` (the app acks `00 00 03 00`). */
    private suspend fun sendDirect(t: BleTransport, char: CharId, tag: Int, data: ByteArray) {
        t.write(char, byteArrayOf(0, 0, 0x02, tag.toByte()) + data)
    }

    /** The app must echo a probe frame back with byte 2 (04) flipped to 05. */
    private fun expectEcho(char: CharId, frame: ByteArray, sent: ByteArray) {
        if (char != CharId.AUTH) {
            throw ProtocolError("probe echo arrived on $char, expected AUTH")
        }
        val expected = sent.copyOf().also { it[2] = 0x05 }
        if (!frame.contentEquals(expected)) {
            throw ProtocolError("expected probe echo ${expected.hex()}, got ${frame.hex()}")
        }
    }

    /** Scale side of a framed receive: ready once, parcels 1..count in
     *  order, received once; the reassembled value lands in [receivedValues]. */
    private suspend fun receiveFramed(t: BleTransport, char: CharId, header: ByteArray): Pair<Int, ByteArray> {
        val tag = header[3].toInt() and 0xFF
        val count = u16(header, 4)
        t.write(char, BYTES_RDY)
        var data = ByteArray(0)
        for (no in 1..count) {
            val parcel = try {
                t.notifications.getValue(char).receive()
            } catch (e: ClosedReceiveChannelException) {
                throw ProtocolError("$char: link closed in the middle of a framed value")
            }
            if (parcel.size < 2) throw ProtocolError("$char: parcel too short: ${parcel.hex()}")
            if (u16(parcel, 0) != no) {
                throw ProtocolError("$char: expected parcel $no, got parcel ${u16(parcel, 0)}")
            }
            if (parcel.size > maxValue) {
                throw ProtocolError("$char: parcel of ${parcel.size} bytes exceeds maxValue $maxValue")
            }
            data += parcel.copyOfRange(2, parcel.size)
        }
        t.write(char, BYTES_OK)
        receivedValues += tag to data
        return tag to data
    }

    private fun isHeader(frame: ByteArray): Boolean =
        frame.size == 6 && frame[0] == 0.toByte() && frame[1] == 0.toByte() && frame[2] == 0.toByte()

    /** Second probe frame: 4-byte prefix + a run of maxValue `0xf2` bytes. */
    private fun probeBig(): ByteArray =
        byteArrayOf(0, 0, 0x04, 0x01) + ByteArray(maxValue) { 0xf2.toByte() }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private companion object {
        /** The reference's stall guard, as the fake measures it. */
        const val MIN_PAUSE_NS = 150_000_000L

        const val RAND_KEY_BYTES = 16
        const val LOGIN_INFO_BYTES = 32

        const val TAG_RAND_KEY = 0x0b
        const val TAG_REMOTE_INFO = 0x0c
        const val TAG_REMOTE_KEY = 0x0d
        const val TAG_LOGIN_INFO = 0x0a

        /** First probe frame, verbatim from the reference capture. */
        val PROBE_SMALL = hex("0000040006f2")

        /** The probe trigger the app writes to LOGIN. */
        val PROBE_WRITE = byteArrayOf(0xA4.toByte())

        /** The login trigger the app writes to LOGIN. */
        val LOGIN_TRIGGER = hex("24000000")

        /** The login OK the scale notifies on LOGIN. */
        val LOGIN_OK = hex("21000000")

        val LOGIN_INFO = "mible-login-info".toByteArray(Charsets.US_ASCII)

        /**
         * The scale's remote_key: fixed (not random) so wire-level test
         * assertions stay deterministic; it only needs to be 16 bytes.
         */
        val REMOTE_KEY = ByteArray(16) { (0x51 + it).toByte() }

        val BYTES_RDY = hex(Framing.RCV_RDY)
        val BYTES_OK = hex(Framing.RCV_OK)
        val BYTES_ACK = hex(Framing.RCV_ACK)

        fun u16(buf: ByteArray, at: Int): Int =
            (buf[at].toInt() and 0xFF) or ((buf[at + 1].toInt() and 0xFF) shl 8)

        fun hex(s: String): ByteArray =
            ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

        fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
    }
}
