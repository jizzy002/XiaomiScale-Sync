package com.xiaomiscalesync.proto

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.withTimeout

/**
 * The direct/framed value layer used on AUTH, CMD and PROP.
 *
 * Wire format (faraga1/xiaomi-scale-s200-ble, decrypted from official-app
 * captures):
 *
 * ```
 * direct:  app -> <00 00 02><tag><data>          scale acks <00 00 03 00>
 * framed:  app -> <00 00 00><tag><count:le16>    header: `count` parcels follow
 *          scale -> <00 00 01 01>                ready, once
 *          app -> <no:le16><chunk> ...           parcels 1..count, back to back
 *          scale -> <00 00 01 00>                received, once
 * ```
 *
 * Parcels are numbered from 1; a full parcel carries `maxValue - 2` bytes
 * (the 2-byte parcel number sits in the same write). One ack before and
 * one after the parcels, never per parcel. The scale sends direct whenever
 * the value fits one write; the app always sends framed, even for a single
 * parcel — [sendValue] matches that, [recvValue] handles both shapes.
 */
object Framing {
    const val RCV_RDY = "00000101"
    const val RCV_OK = "00000100"
    const val RCV_ACK = "00000300"

    /**
     * Deadlock guard: any single ack/parcel wait longer than this raises
     * [ProtocolError] instead of hanging the session (the reference
     * client's `STEP_TIMEOUT_S = 4`).
     */
    const val ACK_TIMEOUT_MS = 4_000L

    private val BYTES_RDY = hex(RCV_RDY)
    private val BYTES_OK = hex(RCV_OK)
    private val BYTES_ACK = hex(RCV_ACK)

    /**
     * Sends [data] to [target] as a framed value: header, ready-ack wait,
     * parcels 1..count back to back, received-ack wait. The ack waits
     * time out into [ProtocolError] ([ACK_TIMEOUT_MS]) so a silent scale
     * can never deadlock the caller.
     */
    suspend fun sendValue(
        t: BleTransport,
        target: CharId,
        tag: Int,
        data: ByteArray,
        maxValue: Int,
    ) {
        require(tag in 0..0xFF) { "tag out of range: $tag" }
        require(maxValue > 2) { "maxValue must exceed the 2-byte parcel number: $maxValue" }
        require(data.size <= 0xFFFF) { "value too long for one header: ${data.size}" }
        val chunk = maxValue - 2
        val count = if (data.isEmpty()) 0 else (data.size + chunk - 1) / chunk
        t.write(target, byteArrayOf(0, 0, 0, tag.toByte()) + le16(count))
        awaitAck(t, target, BYTES_RDY, "ready", tag)
        var offset = 0
        for (no in 1..count) {
            val end = minOf(offset + chunk, data.size)
            t.write(target, le16(no) + data.copyOfRange(offset, end))
            offset = end
        }
        awaitAck(t, target, BYTES_OK, "received", tag)
    }

    /**
     * Receives one value from [source]: a direct `00 00 02` frame (acked
     * with `00 00 03 00`) or a framed transfer (ready once, parcels
     * collected by their number, received once) — returns `tag to data`.
     *
     * The first frame is awaited untimed: a receive loop may legitimately
     * idle between values (the reference client pumps its first PROP
     * frame with no timeout). Mid-transfer parcel gaps do time out, so a
     * transfer that dies half-way cannot wedge the session.
     */
    suspend fun recvValue(t: BleTransport, source: CharId): Pair<Int, ByteArray> =
        recvValue(t, source, receive(t, source))

    /**
     * Same as [recvValue], but the caller has already read the first frame
     * (e.g. a receive loop that must classify every frame itself — acks
     * for its own sends versus the start of an incoming value).
     */
    suspend fun recvValue(t: BleTransport, source: CharId, first: ByteArray): Pair<Int, ByteArray> {
        if (first.size >= 4 && first[0] == 0.toByte() && first[1] == 0.toByte() && first[2] == 0x02.toByte()) {
            t.write(source, BYTES_ACK)
            return (first[3].toInt() and 0xFF) to first.copyOfRange(4, first.size)
        }
        if (first.size == 6 && first[0] == 0.toByte() && first[1] == 0.toByte() && first[2] == 0.toByte()) {
            val tag = first[3].toInt() and 0xFF
            val count = u16(first, 4)
            t.write(source, BYTES_RDY)
            val parcels = sortedMapOf<Int, ByteArray>()
            while (parcels.size < count) {
                val frame = awaitFrame(t, source, "parcel")
                if (frame.size < 2) throw ProtocolError("$source: parcel too short: ${frame.hex()}")
                parcels[u16(frame, 0)] = frame.copyOfRange(2, frame.size)
            }
            t.write(source, BYTES_OK)
            val data = ByteArray(parcels.values.sumOf { it.size })
            var at = 0
            for (part in parcels.values) {
                part.copyInto(data, at)
                at += part.size
            }
            return tag to data
        }
        throw ProtocolError("$source: unexpected value ${first.hex()}")
    }

    /** Waits for the exact ack [want], timing out into [ProtocolError]. */
    private suspend fun awaitAck(
        t: BleTransport,
        target: CharId,
        want: ByteArray,
        what: String,
        tag: Int,
    ) {
        val got = try {
            withTimeout(ACK_TIMEOUT_MS) { t.notifications.getValue(target).receive() }
        } catch (e: TimeoutCancellationException) {
            throw ProtocolError("no $what ack on $target within ${ACK_TIMEOUT_MS}ms (tag=0x${tag.toString(16)})")
        } catch (e: ClosedReceiveChannelException) {
            throw ProtocolError("$target: link closed while waiting for the $what ack")
        }
        if (!got.contentEquals(want)) {
            throw ProtocolError("$target: expected $what ack ${want.hex()}, got ${got.hex()}")
        }
    }

    /** One mid-transfer frame, timed out into [ProtocolError]. */
    private suspend fun awaitFrame(t: BleTransport, char: CharId, what: String): ByteArray = try {
        withTimeout(ACK_TIMEOUT_MS) { t.notifications.getValue(char).receive() }
    } catch (e: TimeoutCancellationException) {
        throw ProtocolError("no $what on $char within ${ACK_TIMEOUT_MS}ms")
    } catch (e: ClosedReceiveChannelException) {
        throw ProtocolError("$char: link closed while waiting for a $what")
    }

    /** The first (untimed) frame of a value; a closed link is a protocol error. */
    private suspend fun receive(t: BleTransport, char: CharId): ByteArray = try {
        t.notifications.getValue(char).receive()
    } catch (e: ClosedReceiveChannelException) {
        throw ProtocolError("$char: link closed while receiving")
    }

    private fun le16(v: Int): ByteArray =
        byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    private fun u16(buf: ByteArray, at: Int): Int =
        (buf[at].toInt() and 0xFF) or ((buf[at + 1].toInt() and 0xFF) shl 8)

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
}
