package com.xiaomiscalesync.fixtures

import com.xiaomiscalesync.proto.BleTransport
import com.xiaomiscalesync.proto.CharId
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.CopyOnWriteArrayList

/** One frame crossing a [loopback] link, tagged with the end that wrote it. */
data class WireFrame(val fromFirst: Boolean, val char: CharId, val bytes: ByteArray)

/**
 * The shared, ordered record of every frame a [loopback] link carried.
 * Both ends reference the same instance, so tests can assert the exact
 * interleaving of writes across the two directions — not just each end's
 * own list.
 */
class WireLog {
    private val frames = CopyOnWriteArrayList<WireFrame>()

    internal fun record(frame: WireFrame) {
        frames += frame
    }

    /** Every frame, in the order it hit the wire. */
    val events: List<WireFrame> get() = frames.toList()
}

/**
 * One in-memory end of a [loopback] link: a write is recorded in the
 * shared [WireLog] and delivered — buffered, never blocking — to the peer
 * end's notification channel for the same characteristic.
 */
class LoopbackTransport internal constructor(
    /** True for the first end returned by [loopback]. */
    val isFirst: Boolean,
    /** Shared with the peer end: the full wire history of the link. */
    val wire: WireLog,
    override val notifications: Map<CharId, Channel<ByteArray>>,
    private val outbox: Map<CharId, Channel<ByteArray>>,
) : BleTransport {

    /** Frames this end wrote, in wire order. */
    val writes: List<WireFrame> get() = wire.events.filter { it.fromFirst == isFirst }

    override suspend fun write(to: CharId, bytes: ByteArray) {
        val copy = bytes.copyOf()
        wire.record(WireFrame(isFirst, to, copy))
        outbox.getValue(to).send(copy)
    }

    /** Tears the link down: closes every channel, both directions. */
    override suspend fun close() {
        notifications.values.forEach { it.close() }
        outbox.values.forEach { it.close() }
    }
}

/**
 * Two cross-wired in-memory transports: what the first end writes arrives
 * on the second end's notification channels and vice versa. Unbounded
 * buffers model BLE's non-blocking write queue, so a lone writer never
 * deadlocks — silence is caught by the framing layer's ack timeouts.
 */
fun loopback(): Pair<BleTransport, BleTransport> {
    val log = WireLog()
    val firstToSecond = CharId.entries.associateWith { Channel<ByteArray>(Channel.UNLIMITED) }
    val secondToFirst = CharId.entries.associateWith { Channel<ByteArray>(Channel.UNLIMITED) }
    val first = LoopbackTransport(true, log, notifications = secondToFirst, outbox = firstToSecond)
    val second = LoopbackTransport(false, log, notifications = firstToSecond, outbox = secondToFirst)
    return first to second
}
