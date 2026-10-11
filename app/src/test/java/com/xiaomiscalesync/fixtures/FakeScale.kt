package com.xiaomiscalesync.fixtures

import com.xiaomiscalesync.proto.BleTransport
import com.xiaomiscalesync.proto.CharId
import com.xiaomiscalesync.proto.Framing
import com.xiaomiscalesync.proto.ProtocolError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory simulation of the scale's side of the protocol — test only.
 *
 * Mirrors faraga1/xiaomi-scale-s200-ble's `FakeScale`/`DeviceSide`: the
 * `a4` payload-size probe and both directions of the framing layer. Later
 * tasks grow it with the miauth login and the MIoT actions/events.
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
 */
class FakeScale(val token: ByteArray, var maxValue: Int = 244) {

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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val outbox = Channel<Outbound>(Channel.UNLIMITED)

    private class Outbound(val char: CharId, val tag: Int, val data: ByteArray)

    private enum class Probe { IDLE, ECHO_0500, ECHO_0501 }

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
     * values from the app, and services [sendFramed] requests — one frame
     * at a time, so the scale's replies stay strictly ordered.
     */
    private suspend fun respond(t: BleTransport) {
        var probe = Probe.IDLE
        while (true) {
            select<Unit> {
                outbox.onReceive { out ->
                    Framing.sendValue(t, out.char, out.tag, out.data, maxValue)
                    sentCount++
                }
                for ((char, ch) in t.notifications) {
                    ch.onReceive { frame ->
                        probe = handle(t, char, frame, probe)
                    }
                }
            }
        }
    }

    private suspend fun handle(t: BleTransport, char: CharId, frame: ByteArray, stage: Probe): Probe =
        when (stage) {
            Probe.ECHO_0500 -> {
                expectEcho(char, frame, PROBE_SMALL)
                t.write(CharId.AUTH, probeBig())
                Probe.ECHO_0501
            }

            Probe.ECHO_0501 -> {
                expectEcho(char, frame, probeBig())
                probeCount++
                Probe.IDLE
            }

            Probe.IDLE -> when {
                frame.contentEquals(PROBE_WRITE) -> {
                    if (char != CharId.LOGIN) {
                        throw ProtocolError("a4 probe arrived on $char, expected LOGIN")
                    }
                    t.write(CharId.AUTH, PROBE_SMALL)
                    Probe.ECHO_0500
                }

                isHeader(frame) -> {
                    receiveFramed(t, char, frame)
                    Probe.IDLE
                }

                else -> throw ProtocolError("scale got an unexpected frame on $char: ${frame.hex()}")
            }
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
    private suspend fun receiveFramed(t: BleTransport, char: CharId, header: ByteArray) {
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
    }

    private fun isHeader(frame: ByteArray): Boolean =
        frame.size == 6 && frame[0] == 0.toByte() && frame[1] == 0.toByte() && frame[2] == 0.toByte()

    /** Second probe frame: 4-byte prefix + a run of maxValue `0xf2` bytes. */
    private fun probeBig(): ByteArray =
        byteArrayOf(0, 0, 0x04, 0x01) + ByteArray(maxValue) { 0xf2.toByte() }

    private companion object {
        /** First probe frame, verbatim from the reference capture. */
        val PROBE_SMALL = hex("0000040006f2")

        /** The probe trigger the app writes to LOGIN. */
        val PROBE_WRITE = byteArrayOf(0xA4.toByte())

        val BYTES_RDY = hex(Framing.RCV_RDY)
        val BYTES_OK = hex(Framing.RCV_OK)

        fun u16(buf: ByteArray, at: Int): Int =
            (buf[at].toInt() and 0xFF) or ((buf[at + 1].toInt() and 0xFF) shl 8)

        fun hex(s: String): ByteArray =
            ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

        fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
    }
}
