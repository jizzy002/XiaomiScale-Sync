package com.xiaomiscalesync.proto

import com.xiaomiscalesync.fixtures.FakeScale
import com.xiaomiscalesync.fixtures.LoopbackTransport
import com.xiaomiscalesync.fixtures.loopback
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Framing layer tests against [FakeScale] over an in-memory [loopback].
 *
 * Byte-exact frame ordering is the contract: every assertion pins the
 * exact hex sequences AND their order on the wire (the loopback's shared
 * wire log), not just eventual state — the real GATT transport (Task 13)
 * has to reproduce exactly this interleaving.
 *
 * Wire notation: `app|CHAR|hex` = written by the app (the first end of
 * [loopback]), `scale|CHAR|hex` = written by the scale (the second end).
 */
class FramingTest {

    /** miauth token placeholder (the framing layer itself is token-agnostic). */
    private val token = ByteArray(12)

    // ---------- helpers ----------

    /** Parses hex, spaces ignored. */
    private fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "")
        return ByteArray(clean.length / 2) {
            clean.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    private fun le16(v: Int): ByteArray =
        byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    /** Frames written by this end as `CHAR|hex`, in write order. */
    private fun BleTransport.ownWrites(): List<String> =
        (this as LoopbackTransport).writes.map { "${it.char}|${it.bytes.hex()}" }

    /** Every frame on the link, in wire order (app = loopback's first end). */
    private fun BleTransport.timeline(): List<String> =
        (this as LoopbackTransport).wire.events.map {
            (if (it.fromFirst) "app" else "scale") + "|" + it.char + "|" + it.bytes.hex()
        }

    /** Polls until [condition] holds; fails instead of hanging the suite. */
    private suspend fun awaitTrue(what: String, condition: () -> Boolean) {
        try {
            withTimeout(5_000) {
                while (!condition()) delay(1)
            }
        } catch (e: Exception) {
            fail("timed out waiting for $what")
        }
    }

    // ---------- probe responder (FakeScale core) ----------

    @Test(timeout = 10_000)
    fun probeRespondsToA4WithExactByteSequence() = runBlocking<Unit> {
        val (app, scaleEnd) = loopback()
        val fake = FakeScale(token, maxValue = 244)
        fake.accept(scaleEnd)

        // a4 goes to LOGIN; both probe frames and echoes live on AUTH.
        app.write(CharId.LOGIN, hex("a4"))
        val small = app.notifications.getValue(CharId.AUTH).receive()
        assertEquals("0000040006f2", small.hex())

        app.write(CharId.AUTH, hex("0000050006f2"))
        val big = app.notifications.getValue(CharId.AUTH).receive()
        assertEquals("00000401" + "f2".repeat(fake.maxValue), big.hex())

        app.write(CharId.AUTH, hex("00000501") + ByteArray(fake.maxValue) { 0xf2.toByte() })
        awaitTrue("the fake completes the probe") { fake.probeCount == 1 || fake.errors.isNotEmpty() }

        assertEquals(1, fake.probeCount)
        assertTrue("fake errors: ${fake.errors}", fake.errors.isEmpty())
        // Exact byte order across both characteristics, echoes included:
        assertEquals(
            listOf(
                "app|LOGIN|a4",
                "scale|AUTH|0000040006f2",
                "app|AUTH|0000050006f2",
                "scale|AUTH|00000401" + "f2".repeat(fake.maxValue),
                "app|AUTH|00000501" + "f2".repeat(fake.maxValue),
            ),
            app.timeline(),
        )
        app.close()
    }

    // ---------- Step 1(a): app sends a 10-byte framed value ----------

    @Test
    fun sendValueWritesHeaderWaitsForReadyThenSendsParcelsInOrder() = runBlocking<Unit> {
        val (app, scaleEnd) = loopback()
        val fake = FakeScale(token)
        fake.accept(scaleEnd)

        val data = ByteArray(10) { (0x10 + it).toByte() }
        Framing.sendValue(app, CharId.AUTH, tag = 0x0b, data, maxValue = 244)

        // Exact interleaved wire order: header -> ready -> parcel -> received.
        // (The parcel may only follow the ready-ack: the sender waits for it.)
        assertEquals(
            listOf(
                "app|AUTH|0000000b0100", // header: tag 0x0b, 1 parcel
                "scale|AUTH|00000101", // ready, before any parcel
                "app|AUTH|0100" + data.hex(), // parcel 1 of 1
                "scale|AUTH|00000100", // received, exactly once
            ),
            app.timeline(),
        )
        // Each end's own writes, ordered:
        assertEquals(listOf("AUTH|0000000b0100", "AUTH|0100" + data.hex()), app.ownWrites())
        assertEquals(listOf("AUTH|00000101", "AUTH|00000100"), scaleEnd.ownWrites())

        // The fake reassembled the value and never saw a protocol error.
        assertEquals(1, fake.receivedValues.size)
        assertEquals(0x0b, fake.receivedValues[0].first)
        assertArrayEquals(data, fake.receivedValues[0].second)
        assertTrue("fake errors: ${fake.errors}", fake.errors.isEmpty())
        app.close()
    }

    // ---------- Step 1(b): scale sends a 600-byte framed value ----------

    @Test(timeout = 10_000)
    fun recvValueReassemblesFramedTransferAndAcksReadyAndReceivedOnce() = runBlocking<Unit> {
        val (app, scaleEnd) = loopback()
        val fake = FakeScale(token) // maxValue 244, the advertised max
        fake.accept(scaleEnd)

        val data = ByteArray(600) { it.toByte() }
        val chunk = fake.maxValue - 2 // 242 bytes per parcel
        // Parcel count from the advertised max, per the pre-flight ruling:
        // ceil(600 / 242) = 3 parcels (the brief's "2" doesn't hold).
        val expectedParcels = (data.size + chunk - 1) / chunk

        val received = async { Framing.recvValue(app, CharId.AUTH) }
        fake.sendFramed(CharId.AUTH, tag = 0x0c, data)
        val (tag, value) = received.await()
        awaitTrue("the fake finishes its send") {
            fake.sentCount == 1 || fake.errors.isNotEmpty()
        }

        assertEquals(0x0c, tag)
        assertArrayEquals(data, value)

        // The app acked ready once and received once — never per parcel:
        assertEquals(listOf("AUTH|00000101", "AUTH|00000100"), app.ownWrites())

        // Exact wire order: header -> ready -> parcels back-to-back -> received.
        val expected = mutableListOf("scale|AUTH|0000000c" + le16(expectedParcels).hex())
        expected += "app|AUTH|00000101"
        for (no in 1..expectedParcels) {
            val from = (no - 1) * chunk
            val to = minOf(no * chunk, data.size)
            expected += "scale|AUTH|" + le16(no).hex() + data.copyOfRange(from, to).hex()
        }
        expected += "app|AUTH|00000100"
        assertEquals(expected, app.timeline())

        assertTrue("fake errors: ${fake.errors}", fake.errors.isEmpty())
        assertEquals(1, fake.sentCount)
        app.close()
    }

    // ---------- Step 1(c): direct value path ----------

    @Test
    fun recvValueDirectReturnsTagAndDataAndWritesAck() = runBlocking<Unit> {
        val (app, scaleEnd) = loopback()
        // 00 00 02 <tag 0x0d> <5 bytes of data>, injected by the scale end.
        scaleEnd.write(CharId.PROP, hex("0000020d0102030405"))

        val (tag, data) = Framing.recvValue(app, CharId.PROP)

        assertEquals(0x0d, tag)
        assertArrayEquals(hex("0102030405"), data)
        // The injected direct frame, then the ack — nothing else on the wire:
        assertEquals(
            listOf("scale|PROP|0000020d0102030405", "app|PROP|00000300"),
            app.timeline(),
        )
        assertEquals(listOf("PROP|00000300"), app.ownWrites())
        app.close()
    }

    // ---------- Step 4: deadlock guard ----------

    @Test(timeout = 15_000)
    fun sendValueThrowsProtocolErrorWhenReadyAckNeverArrives() = runBlocking<Unit> {
        val (app, _scaleEnd) = loopback() // nobody answers: no responder at all
        assertEquals(4_000L, Framing.ACK_TIMEOUT_MS)

        val startedAt = System.nanoTime()
        val error = runCatching {
            Framing.sendValue(app, CharId.AUTH, tag = 0x0b, data = ByteArray(4), maxValue = 244)
        }.exceptionOrNull()
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue("expected ProtocolError, got $error", error is ProtocolError)
        assertTrue(
            "the guard should wait ~4s (waited ${elapsedMs}ms)",
            elapsedMs >= 3_500 && elapsedMs < 10_000,
        )
        // The header went out, but no parcel may follow before the ready-ack:
        assertEquals(listOf("AUTH|0000000b0100"), app.ownWrites())
        app.close()
    }
}
