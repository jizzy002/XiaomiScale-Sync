package com.xiaomiscalesync.proto

import com.xiaomiscalesync.fixtures.FakeScale
import com.xiaomiscalesync.fixtures.LoopbackTransport
import com.xiaomiscalesync.fixtures.loopback
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ScaleSession] setup-order + miauth-login tests against [FakeScale].
 *
 * Order fidelity is the contract under test: the reference client's
 * notification order (AUTH/CMD/PROP → `a4` probe → ~200 ms → LOGIN) is
 * what keeps the real scale from stalling on ~50% of its logins, so the
 * fake's `failLoginUnlessOrdered` gate refuses a login trigger that
 * arrives out of order — the real session must pass it, and a
 * deliberately misordered client must be refused.
 */
class ScaleSessionLoginTest {

    /** The scale's configured miauth token (12 bytes, the cloud TOKEN). */
    private val scaleToken = hex("0123456789abcdef01234567")

    /** A different token: the session's login must fail remote_info verification. */
    private val wrongToken = hex("fedcba9876543210fedcba98")

    // ---------- helpers ----------

    /** Parses hex, spaces ignored. */
    private fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "")
        return ByteArray(clean.length / 2) {
            clean.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    /** Frames written by the app end as `CHAR|hex`, in write order. */
    private fun LoopbackTransport.appWrites(): List<String> =
        writes.map { "${it.char}|${it.bytes.hex()}" }

    /** Polls until [condition] holds; fails instead of hanging the suite. */
    private suspend fun awaitTrue(what: String, condition: () -> Boolean) {
        try {
            kotlinx.coroutines.withTimeout(5_000) {
                while (!condition()) delay(1)
            }
        } catch (e: Exception) {
            org.junit.Assert.fail("timed out waiting for $what")
        }
    }

    /**
     * A misordered client's own probe, exactly like a real session's:
     * `a4` to LOGIN, then echo both AUTH probe frames back with
     * byte 2 flipped `04 → 05`.
     */
    private suspend fun echoProbeManually(app: BleTransport) {
        app.write(CharId.LOGIN, hex("a4"))
        val small = app.notifications.getValue(CharId.AUTH).receive()
        app.write(CharId.AUTH, small.copyOf().also { it[2] = 0x05 })
        val big = app.notifications.getValue(CharId.AUTH).receive()
        app.write(CharId.AUTH, big.copyOf().also { it[2] = 0x05 })
    }

    // ---------- (a) happy path: setup() then login() ----------

    @Test(timeout = 20_000)
    fun setupThenLoginCompletesWithProbeMaxValue() = runBlocking<Unit> {
        val (app, scaleEnd) = loopback()
        val fake = FakeScale(scaleToken)
        fake.accept(scaleEnd)
        try {
            val session = ScaleSession(fake.watch(app), scaleToken)

            session.setup()
            session.login()

            // maxValue comes from the big probe (its 0xf2 payload length,
            // the 4-byte prefix excluded) and matches what the fake advertised.
            assertEquals(fake.maxValue, session.maxValue)
            assertEquals(244, session.maxValue)
            assertEquals(1, fake.probeCount)
            assertEquals(1, fake.loginCount)
            assertTrue("fake errors: ${fake.errors}", fake.errors.isEmpty())
            // On LOGIN, exactly two app writes, in order: probe trigger, login trigger.
            val appEnd = app as LoopbackTransport
            assertEquals(
                listOf("LOGIN|a4", "LOGIN|24000000"),
                appEnd.appWrites().filter { it.startsWith("LOGIN|") },
            )
            // The scale confirmed on LOGIN with exactly `21000000`
            // (the scale's writes are on the shared wire log).
            assertEquals(
                listOf("scale|LOGIN|21000000"),
                appEnd.wire.events
                    .filter { !it.fromFirst && it.char == CharId.LOGIN }
                    .map { "scale|${it.char}|${it.bytes.hex()}" },
            )
        } finally {
            app.close()
        }
    }

    // ---------- (b) wrong token fails remote_info verification ----------

    @Test(timeout = 20_000)
    fun wrongTokenFailsRemoteInfoVerification() = runBlocking<Unit> {
        val (app, scaleEnd) = loopback()
        val fake = FakeScale(scaleToken)
        fake.accept(scaleEnd)
        try {
            val session = ScaleSession(fake.watch(app), wrongToken)
            session.setup()

            val err = assertThrows(ProtocolError::class.java) { runBlocking { session.login() } }

            assertTrue(
                "message should name the failure, was: ${err.message}",
                err.message.orEmpty().contains("remote_info HMAC mismatch"),
            )
        } finally {
            app.close()
        }
    }

    // ---------- (b2) scale goes silent mid-handshake: bounded, no hang ----------

    @Test(timeout = 20_000)
    fun silentScaleAfterRandKeyFailsInsteadOfHanging() = runBlocking<Unit> {
        val (app, scaleEnd) = loopback()
        // The scale acks the framed rand_key transfer (all acks flow) and
        // then never delivers remote_key — the stall regime the session
        // must survive, not wait out forever.
        val fake = FakeScale(scaleToken, silentAfterRandKey = true)
        fake.accept(scaleEnd)
        try {
            val session = ScaleSession(fake.watch(app), scaleToken)
            session.setup()

            val err = assertThrows(ProtocolError::class.java) { runBlocking { session.login() } }

            assertTrue(
                "message should name the missing value and the bound, was: ${err.message}",
                err.message.orEmpty().contains("no remote_key on AUTH"),
            )
            assertEquals(0, fake.loginCount)
        } finally {
            app.close()
        }
    }

    // ---------- (c) misordered client: LOGIN enabled before the probe ----------

    @Test(timeout = 20_000)
    fun loginEnabledBeforeTheProbeIsRefusedByTheScale() = runBlocking<Unit> {
        val (app, scaleEnd) = loopback()
        val fake = FakeScale(scaleToken, failLoginUnlessOrdered = true)
        fake.accept(scaleEnd)
        try {
            // A deliberately misordered client: LOGIN notifications enabled
            // FIRST — the order that stalled about half the reference
            // client's logins — then AUTH/CMD/PROP, then the probe, then
            // the login straight on the heels of the pause.
            val misordered = fake.watch(app)
            misordered.enableNotifications(CharId.LOGIN)
            for (char in listOf(CharId.AUTH, CharId.CMD, CharId.PROP)) {
                misordered.enableNotifications(char)
            }
            echoProbeManually(misordered)
            delay(200)

            val session = ScaleSession(misordered, scaleToken)
            val err = assertThrows(ProtocolError::class.java) { runBlocking { session.login() } }
            // The fake refused the trigger (and died with the reason);
            // the session surfaces the stall as its own ProtocolError.
            assertTrue(
                "session error should stem from the refusal, was: ${err.message}",
                err.message.orEmpty().contains("no ready ack"),
            )
            awaitTrue("the scale records its refusal") { fake.errors.isNotEmpty() }
            val refusal = fake.errors.first()
            assertTrue(
                "refusal message: ${refusal.message}",
                refusal.message.orEmpty().contains("refusing login"),
            )
            assertEquals(0, fake.loginCount)
        } finally {
            app.close()
        }
    }

    // ---------- (d) the real session passes the ordering gate ----------

    @Test(timeout = 20_000)
    fun orderedSetupPassesTheFailLoginUnlessOrderedGate() = runBlocking<Unit> {
        val (app, scaleEnd) = loopback()
        val fake = FakeScale(scaleToken, failLoginUnlessOrdered = true)
        fake.accept(scaleEnd)
        try {
            val session = ScaleSession(fake.watch(app), scaleToken)

            // The real order, including the ~200 ms pause after the probe:
            session.setup()
            session.login()

            assertEquals(1, fake.loginCount)
            assertEquals(1, fake.probeCount)
            assertTrue("fake errors: ${fake.errors}", fake.errors.isEmpty())
            // The exact CCCD order the reference client requires:
            assertEquals(
                listOf(CharId.AUTH, CharId.CMD, CharId.PROP, CharId.LOGIN),
                fake.enableOrder,
            )
            // And on the wire: a4 first, 24000000 only after it.
            assertEquals(
                listOf("LOGIN|a4", "LOGIN|24000000"),
                (app as LoopbackTransport).appWrites().filter { it.startsWith("LOGIN|") },
            )
        } finally {
            app.close()
        }
    }
}
