package com.xiaomiscalesync.ble

import com.xiaomiscalesync.crypto.Vectors
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests [BeaconDecoder] and [WeighInObjects] against the committed
 * `beacon_vectors.json` fixtures (`scripts/gen_vectors.py --beacon`).
 */
class BeaconTest {

    private val fixtures = BeaconVectors.load()

    private fun fixture(name: String) = fixtures.first { it.name == name }

    @Test
    fun loadsAllBeaconFixtures() {
        assertEquals(
            listOf("beacon_weigh_in", "beacon_status", "beacon_solicit"),
            fixtures.map { it.name },
        )
    }

    @Test
    fun weighInFrameDecodes() {
        val f = fixture("beacon_weigh_in")
        val beacon = checkNotNull(BeaconDecoder.decode(f.serviceData, f.mac, f.bindKey))
        assertEquals(0x5948, beacon.frameControl)
        assertEquals(42, beacon.frameCounter)
        assertFalse(beacon.solicit)
        // eventKey = counter * 2^24 + extended counter, exactly.
        assertEquals(42L * (1L shl 24) + 0x010203, beacon.eventKey)
        assertEquals(f.eventKey, beacon.eventKey)
        assertObjectsMatch(f, beacon)
    }

    @Test
    fun weighInObjectParsesToPinnedBroadcast() {
        val f = fixture("beacon_weigh_in")
        val beacon = checkNotNull(BeaconDecoder.decode(f.serviceData, f.mac, f.bindKey))
        val value = beacon.objects.single { it.first == WeighInObjects.OBJ_WEIGH_IN }.second
        assertEquals(BroadcastWeighIn(1, 7215, 0x6ab13b80), WeighInObjects.parse(value))
        assertEquals(f.weighIn, WeighInObjects.parse(value))
    }

    @Test
    fun statusFrameDecodes() {
        val f = fixture("beacon_status")
        val beacon = checkNotNull(BeaconDecoder.decode(f.serviceData, f.mac, f.bindKey))
        assertEquals(0x5958, beacon.frameControl)
        assertEquals(7, beacon.frameCounter)
        assertFalse(beacon.solicit)
        assertEquals(7L shl 24, beacon.eventKey)
        assertEquals(f.eventKey, beacon.eventKey)
        assertObjectsMatch(f, beacon)
        assertNull(f.weighIn) // the status fixture carries no weigh-in
    }

    @Test
    fun solicitFrameDecodes() {
        val f = fixture("beacon_solicit")
        val beacon = checkNotNull(BeaconDecoder.decode(f.serviceData, f.mac, f.bindKey))
        assertEquals(0x5b10, beacon.frameControl)
        assertEquals(17, beacon.frameCounter)
        assertTrue(beacon.solicit)
        assertEquals(17L shl 24, beacon.eventKey)
        assertEquals(f.eventKey, beacon.eventKey)
        assertTrue(beacon.objects.isEmpty())
    }

    @Test
    fun wrongBindKeyYieldsEmptyObjectsAndNoException() {
        val f = fixture("beacon_weigh_in")
        val wrongKey = f.bindKey.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        val beacon = checkNotNull(BeaconDecoder.decode(f.serviceData, f.mac, wrongKey))
        assertTrue(beacon.objects.isEmpty())
        // The unauthenticated header fields are still readable.
        assertEquals(f.frameControl, beacon.frameControl)
        assertEquals(f.frameCounter, beacon.frameCounter)
        assertEquals(f.eventKey, beacon.eventKey)
    }

    @Test
    fun missingBindKeyYieldsEmptyObjects() {
        val f = fixture("beacon_weigh_in")
        val beacon = checkNotNull(BeaconDecoder.decode(f.serviceData, f.mac, null))
        assertTrue(beacon.objects.isEmpty())
    }

    @Test
    fun wrongLengthBindKeyYieldsEmptyObjects() {
        val f = fixture("beacon_weigh_in")
        val beacon = checkNotNull(BeaconDecoder.decode(f.serviceData, f.mac, ByteArray(15)))
        assertTrue(beacon.objects.isEmpty())
    }

    @Test
    fun truncatedServiceDataReturnsNull() {
        // Header shorter than fc(2)+product(2)+counter(1).
        val weighIn = fixture("beacon_weigh_in")
        assertTrue(weighIn.serviceData.size > 12)
        for (n in 0..11) {
            assertNull("weigh-in prefix of $n bytes", BeaconDecoder.decode(weighIn.serviceData.copyOf(n), weighIn.mac, weighIn.bindKey))
        }
        // Same bound one field deeper: the MAC field (6) precedes the payload.
        val status = fixture("beacon_status")
        assertTrue(status.serviceData.size > 18)
        for (n in 0..17) {
            assertNull("status prefix of $n bytes", BeaconDecoder.decode(status.serviceData.copyOf(n), status.mac, status.bindKey))
        }
        // Unencrypted frame: truncated exactly at the MAC field boundary.
        val solicit = fixture("beacon_solicit")
        assertEquals(11, solicit.serviceData.size)
        for (n in 0..10) {
            assertNull("solicit prefix of $n bytes", BeaconDecoder.decode(solicit.serviceData.copyOf(n), solicit.mac, solicit.bindKey))
        }
        assertNotNull(BeaconDecoder.decode(solicit.serviceData, solicit.mac, solicit.bindKey))
    }

    @Test
    fun truncatedObjectStreamKeepsIntactObjects() {
        // Unencrypted frame (fc 0x0040: object included, nothing else) whose
        // object stream is cut inside a second object: the first survives,
        // the truncated one is dropped, nothing throws.
        val mac = fixture("beacon_weigh_in").mac
        val serviceData = Vectors.decodeHex("4000cb4d03") + // fc 0x0040, product 0x4dcb, counter 3
            Vectors.decodeHex("23660100") + // object 0x6623, 1 byte 0x00
            Vectors.decodeHex("164e09") + // object 0x4e16 header claiming 9 value bytes…
            Vectors.decodeHex("012f1c") // …but only 3 follow
        val beacon = checkNotNull(BeaconDecoder.decode(serviceData, mac, null))
        assertEquals(1, beacon.objects.size)
        assertEquals(0x6623, beacon.objects.single().first)
        assertArrayEquals(Vectors.decodeHex("00"), beacon.objects.single().second)
        assertFalse(beacon.solicit)
        assertEquals(3L shl 24, beacon.eventKey)
    }

    @Test
    fun malformedScaleMacThrows() {
        val f = fixture("beacon_weigh_in")
        assertThrows(IllegalArgumentException::class.java) {
            BeaconDecoder.decode(f.serviceData, "not-a-mac", f.bindKey)
        }
    }

    @Test
    fun bareAndLowercaseMacFormsDecodeIdentically() {
        val f = fixture("beacon_weigh_in")
        val colon = BeaconDecoder.decode(f.serviceData, f.mac, f.bindKey)!!
        val bare = BeaconDecoder.decode(f.serviceData, f.mac.lowercase().replace(":", ""), f.bindKey)!!
        assertArrayEquals(colon.objects.single().second, bare.objects.single().second)
    }

    @Test
    fun weighInParseRejectsWrongLengths() {
        val pinned = Vectors.decodeHex("012f1c0000803bb16a")
        assertNull(WeighInObjects.parse(ByteArray(0)))
        assertNull(WeighInObjects.parse(pinned.copyOf(8)))
        assertNull(WeighInObjects.parse(pinned + 0x00.toByte()))
    }

    @Test
    fun weighInParseRejectsImplausibleWeight() {
        // The weight field is 1/100 kg: 100 = 1 kg, 30000 = 300 kg.
        assertNull(WeighInObjects.parse(withWeight(0)))
        assertNull(WeighInObjects.parse(withWeight(50)))
        assertNull(WeighInObjects.parse(withWeight(99)))
        assertNull(WeighInObjects.parse(withWeight(30001)))
    }

    @Test
    fun weighInParseAcceptsBoundaryWeights() {
        assertEquals(100, WeighInObjects.parse(withWeight(100))!!.weightGrams)
        assertEquals(30000, WeighInObjects.parse(withWeight(30000))!!.weightGrams)
    }

    @Test
    fun weighInParsePassesThroughProfileAndTimestamp() {
        val weighIn = WeighInObjects.parse(withWeight(8025, profileId = 2, ts = 0xFFFFFFFFL))!!
        assertEquals(2, weighIn.profileId)
        assertEquals(8025, weighIn.weightGrams)
        assertEquals(0xFFFFFFFFL, weighIn.unixTs) // u32 read as unsigned
    }

    // --- helpers ------------------------------------------------------------

    private fun assertObjectsMatch(f: BeaconFixture, beacon: Beacon) {
        assertEquals(f.objects.size, beacon.objects.size)
        for (i in f.objects.indices) {
            assertEquals(f.objects[i].first, beacon.objects[i].first)
            assertArrayEquals("object ${f.objects[i].first}", f.objects[i].second, beacon.objects[i].second)
        }
    }

    /** Builds a 9-byte weigh-in object value: profile, weight (1/100 kg), ts. */
    private fun withWeight(weight100: Int, profileId: Int = 1, ts: Long = 0x6ab13b80): ByteArray {
        fun le32(v: Long) = ByteArray(4) { i -> ((v shr (8 * i)) and 0xFF).toByte() }
        return byteArrayOf(profileId.toByte()) + le32(weight100.toLong()) + le32(ts)
    }
}
