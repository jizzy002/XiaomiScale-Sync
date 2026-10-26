package com.xiaomiscalesync.proto

import com.xiaomiscalesync.crypto.AesCcm
import com.xiaomiscalesync.crypto.VectorFixture
import com.xiaomiscalesync.crypto.Vectors
import com.xiaomiscalesync.fixtures.FakeScale
import com.xiaomiscalesync.fixtures.LoopbackTransport
import com.xiaomiscalesync.fixtures.loopback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ScaleSession]'s post-login encrypted channel: per-message AES-CCM with
 * incrementing counters, the PROP pump's dispatch, and the stored-record
 * parser — all against [FakeScale] over the loopback.
 *
 * Dispatch by tid is the contract under test: OP_RESULT/OP_HELLO resolve
 * the pending future registered under their tid, OP_EVENT goes to the
 * siid-routed event queues, and a live event arriving while a result is
 * awaited must not disturb that future (test (c) pins it).
 */
class ScaleSessionChannelTest {

    /** The scale's configured miauth token (12 bytes, the cloud TOKEN). */
    private val scaleToken = hex("0123456789abcdef01234567")

    // ---------- helpers ----------

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun u16(buf: ByteArray, at: Int): Int =
        (buf[at].toInt() and 0xFF) or ((buf[at + 1].toInt() and 0xFF) shl 8)

    private fun le16(v: Int): ByteArray =
        byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    /**
     * A logged-in session with the pump running: setup → login → startPump,
     * then [block], then close. [FakeScale] starts with the default long
     * records fixture unless the caller reconfigures it first.
     */
    private fun withSession(
        block: suspend CoroutineScope.(ScaleSession, FakeScale, LoopbackTransport) -> Unit,
    ) = runBlocking<Unit> {
        val (app, scaleEnd) = loopback()
        val fake = FakeScale(scaleToken)
        fake.accept(scaleEnd)
        try {
            val session = ScaleSession(fake.watch(app), scaleToken)
            session.setup()
            session.login()
            session.startPump()
            block(session, fake, app as LoopbackTransport)
            session.close()
        } finally {
            app.close()
        }
    }

    /** Delegates to the stored-record parser under test. */
    private fun parseStoredRecords(text: String): List<StoredRecord> = StoredRecords.parse(text)

    // ---------- (a) hello round-trip ----------

    @Test(timeout = 20_000)
    fun helloRoundTrip() = withSession { session, fake, _ ->
        val hello = session.hello()

        assertEquals(MiotCodec.OP_HELLO, hello.op)
        assertEquals(0, hello.status)
        // The first message the app ever encrypts uses app counter 0.
        assertEquals(listOf(0), fake.appCounters.toList())
        assertTrue("fake errors: ${fake.errors}", fake.errors.isEmpty())
    }

    // ---------- counter discipline: 0-based, +1 per sent message ----------

    @Test(timeout = 20_000)
    fun outboundCounterStartsAtZeroAndIncreasesPerSentMessage() = withSession { session, fake, _ ->
        // Two messages in one session: the fake sees the frame counter of
        // each — 0 for the first, 1 for the second. (A single-message test
        // cannot pin the increment; the appCtr += 1 deletion is caught here.)
        assertEquals(MiotCodec.OP_HELLO, session.hello().op)
        session.action(7, 1, MiotCodec.param(1, ParamType.STRING, "{}"))

        assertEquals(listOf(0, 1), fake.appCounters.toList())
        assertTrue("fake errors: ${fake.errors}", fake.errors.isEmpty())
    }

    // ---------- direction: app→scale requests go on CMD (0x001a), never PROP ----------

    /**
     * Wire-truth pin, from the reference client (scale_reader.py):
     * `UUIDS` says `CMD 0x001a (app->scale), PROP 0x001b (scale->app)`,
     * and `request()` sends with `send_value("CMD", TAG_DATA, payload)`
     * while the receive loop pumps only PROP. A request written to PROP
     * is never heard by the scale (it only sends there), so the char of
     * the encrypted hello/action bytes — not just their payload — is the
     * contract.
     */
    @Test(timeout = 20_000)
    fun sessionRequestsAreWrittenToCmdNeverProp() = withSession { session, fake, appEnd ->
        session.hello()
        session.action(7, 1, MiotCodec.param(1, ParamType.STRING, "{}"))

        val appWrites = appEnd.wire.events.filter { it.fromFirst }
        // The framing headers of the two session sends: 6 bytes,
        // `00 00 00 <tag> <count:le16>` — the app writes framed values only
        // to CMD (session requests) or PROP (framing acks, 4 bytes), so a
        // size-6 header on either char is a session request wherever it went.
        val headers = appWrites.filter {
            (it.char == CharId.CMD || it.char == CharId.PROP) && it.bytes.size == 6 &&
                it.bytes[0] == 0.toByte() && it.bytes[1] == 0.toByte() && it.bytes[2] == 0.toByte()
        }
        assertEquals("one session header per request", 2, headers.size)
        assertTrue(
            "session request headers written to ${headers.map { it.char }}, expected CMD " +
                "(reference: app→scale values go on CMD 0x001a)",
            headers.all { it.char == CharId.CMD },
        )
        // …carrying the protocol's data tag: "Tags: 0x00 encrypted data
        // (CMD/PROP)" — reference scale_reader.py `TAG_DATA = 0x00`, used
        // as `send_value("CMD", TAG_DATA, payload)`. No tag 0x01 exists.
        assertTrue(
            "session data tags ${headers.map { "%02x".format(it.bytes[3]) }}, expected 0x00 " +
                "(reference TAG_DATA = 0x00)",
            headers.all { it.bytes[3] == 0x00.toByte() },
        )

        // On PROP the app may only ever write the framing acks of the
        // scale's own transfers; any other payload there means a request
        // went the wrong way.
        val framingAcks = listOf(hex("00000101"), hex("00000100"), hex("00000300"))
        val propWrites = appWrites.filter { it.char == CharId.PROP }
        for (frame in propWrites) {
            assertTrue(
                "app wrote a non-ack frame to PROP: " +
                    frame.bytes.joinToString("") { "%02x".format(it) },
                framingAcks.any { it.contentEquals(frame.bytes) },
            )
        }
        assertTrue("fake errors: ${fake.errors}", fake.errors.isEmpty())
    }

    // ---------- (b) action 7.1 returns the pinned result params ----------

    @Test(timeout = 20_000)
    fun action71ReturnsPinnedProfileResult() = withSession { session, fake, _ ->
        val profile = """{"mid":"1","height":180,"weight":8025}"""

        val result = session.action(7, 1, MiotCodec.param(1, ParamType.STRING, profile))

        assertEquals(0, result.status)
        assertEquals(fake.storedCount, (result.params[2] as Number).toInt())
        assertEquals("2.1.2_0008.0010", result.params[5])
        assertEquals(listOf(7 to 1), fake.actionsReceived.toList())
        // One sent message: app counter 0 (0-based, per session).
        assertEquals(listOf(0), fake.appCounters.toList())
        assertTrue("fake errors: ${fake.errors}", fake.errors.isEmpty())
    }

    // ---------- (c) interleaving: event while a result is awaited ----------

    @Test(timeout = 20_000)
    fun liveEventWhileResultIsPendingLandsInLiveEventsAndFutureResolves() =
        withSession { session, fake, _ ->
            // The fake injects the live 5.3 the moment 6.1 arrives — strictly
            // before its result hits the wire, so the event is genuinely in
            // flight while the 6.1 future is pending.
            fake.liveOnStoredFetch = 6500 to true

            val pendingFetch = async { session.action(6, 1) }

            val event = session.liveEvents.receive()
            assertEquals(5, event.siid)
            assertEquals(3, event.iid)
            assertEquals(1, (event.params[2] as Number).toInt()) // stable
            assertEquals(6500, (event.params[3] as Number).toInt())

            // The awaited future was not disturbed by the event: it still
            // holds the 6.1 result.
            val result = pendingFetch.await()
            assertEquals(MiotCodec.OP_RESULT, result.op)
            assertEquals(0, result.status)
            assertEquals(fake.storedCount, (result.params[3] as Number).toInt())
            assertTrue("fake errors: ${fake.errors}", fake.errors.isEmpty())
        }

    // ---------- (d) framed 6.1 records event reassembles and parses ----------

    @Test(timeout = 20_000)
    fun framedStoredRecordsEventReassemblesAndParsesIntoPinnedRecords() =
        withSession { session, fake, appEnd ->
            val result = session.action(6, 1)
            assertEquals(fake.storedCount, (result.params[3] as Number).toInt())

            val event = session.storedEvents.receive()
            assertEquals(6, event.siid)
            assertEquals(1, event.iid)
            val text = event.params[1] as String
            assertEquals(fake.recordsString, text)

            val records = parseStoredRecords(text)
            assertTrue("records: ${records.size}", records.size >= 2)
            val r1 = records[0]
            assertEquals(41L, r1.no)
            assertEquals(1L, r1.mid)
            assertEquals(1, r1.userType)
            assertEquals(8025, r1.weightCentiKg)
            assertEquals(0, r1.flag)
            assertEquals(1790000000L, r1.ts)
            val r2 = records[1]
            assertEquals(42L, r2.no)
            assertEquals(0L, r2.mid)
            assertEquals(0, r2.userType)
            assertEquals(7260, r2.weightCentiKg)
            assertEquals(2, r2.flag)
            assertEquals(1790000060L, r2.ts)

            // The records event really crossed as a framed multi-parcel
            // transfer: a scale-side PROP header with a parcel count >= 2.
            val parcelCounts = appEnd.wire.events
                .filter { !it.fromFirst && it.char == CharId.PROP && it.bytes.size == 6 && it.bytes[2] == 0.toByte() }
                .map { u16(it.bytes, 4) }
            assertTrue(
                "expected a >=2-parcel framed transfer on PROP, got parcel counts $parcelCounts",
                parcelCounts.any { it >= 2 },
            )
            assertTrue("fake errors: ${fake.errors}", fake.errors.isEmpty())
        }

    // ---------- (e) pinned ciphertext vector for the session layout ----------

    @Test
    fun sessionEncVectorMatchesPinnedCiphertextAndRoundTrips() {
        val fixture = Vectors.load("crypto_vectors")
            .filterIsInstance<VectorFixture.Ccm>()
            .first { it.name == "session_enc" }

        // Nonce layout: iv(4) || 00000000(4) || ctr_le32(4), no AAD.
        val iv = fixture.nonce.copyOfRange(0, 4)
        assertEquals(4, iv.size)
        assertTrue(byteArrayOf(0, 0, 0, 0).contentEquals(fixture.nonce.copyOfRange(4, 8)))
        val ctr = (fixture.nonce[8].toInt() and 0xFF) or
            ((fixture.nonce[9].toInt() and 0xFF) shl 8) or
            ((fixture.nonce[10].toInt() and 0xFF) shl 16) or
            ((fixture.nonce[11].toInt() and 0xFF) shl 24)

        // The pinned ciphertext itself.
        assertTrue(
            fixture.ciphertext.contentEquals(
                AesCcm.encrypt(fixture.key, fixture.nonce, null, fixture.plaintext),
            ),
        )

        // The session's own encrypt path: <ctr_le16><ct||tag>.
        val value = ScaleSession.encryptValue(fixture.key, iv, ctr, fixture.plaintext)
        assertTrue((le16(ctr) + fixture.ciphertext).contentEquals(value))

        // The session's own decrypt path recovers the plaintext from it.
        assertTrue(
            fixture.plaintext.contentEquals(
                ScaleSession.decryptValue(fixture.key, iv, value),
            ),
        )
    }

    // ---------- 6.2 cleanup records deletions ----------

    @Test(timeout = 20_000)
    fun delete62RecordsDeletions() = withSession { session, fake, _ ->
        val result = session.action(6, 2, MiotCodec.param(2, ParamType.STRING, "41,42"))

        assertEquals(0, result.status)
        assertEquals(listOf(41L, 42L), fake.deletedRecordNos.toList())
        assertTrue("fake errors: ${fake.errors}", fake.errors.isEmpty())
    }

    // ---------- result contract: timeout and non-zero status ----------

    @Test(timeout = 20_000)
    fun missingResultTimesOutAsProtocolError() = withSession { session, fake, _ ->
        fake.dropActionResults = true

        val err = assertThrows(ProtocolError::class.java) { runBlocking { session.action(7, 1) } }

        assertTrue("message: ${err.message}", err.message.orEmpty().contains("no reply"))
    }

    @Test(timeout = 20_000)
    fun nonZeroStatusSurfacesProtocolError() = withSession { session, fake, _ ->
        fake.failActionStatus = 5

        val err = assertThrows(ProtocolError::class.java) { runBlocking { session.action(7, 1) } }

        assertTrue("message: ${err.message}", err.message.orEmpty().contains("status 5"))
    }

    @Test(timeout = 20_000)
    fun helloNonZeroStatusSurfacesProtocolError() = withSession { session, fake, _ ->
        fake.helloFailStatus = 3

        val err = assertThrows(ProtocolError::class.java) { runBlocking { session.hello() } }

        assertTrue("message: ${err.message}", err.message.orEmpty().contains("status 3"))
    }

    // ---------- close terminates the pump cleanly ----------

    @Test(timeout = 20_000)
    fun closeEndsThePumpAndClosesEventChannels() = runBlocking<Unit> {
        val (app, scaleEnd) = loopback()
        val fake = FakeScale(scaleToken)
        fake.accept(scaleEnd)
        try {
            val session = ScaleSession(fake.watch(app), scaleToken)
            session.setup()
            session.login()
            session.startPump()
            assertEquals(MiotCodec.OP_HELLO, session.hello().op)

            session.close()

            assertThrows(ClosedReceiveChannelException::class.java) { runBlocking { session.storedEvents.receive() } }
            assertThrows(ClosedReceiveChannelException::class.java) { runBlocking { session.liveEvents.receive() } }
            // A second close is a no-op, not a crash.
            session.close()
        } finally {
            app.close()
        }
    }
}
