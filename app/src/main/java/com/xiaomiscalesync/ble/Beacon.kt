package com.xiaomiscalesync.ble

import com.xiaomiscalesync.crypto.AesCcm
import com.xiaomiscalesync.crypto.AuthenticationMismatch

/**
 * One decoded MiBeacon v5 advertisement (service data for UUID `0xfe95`).
 *
 * @property frameControl the raw frame-control field; its bits say what the
 *   frame carries (0x08 encrypted, 0x10 MAC included, 0x20 capability
 *   included, bit 9 = 0x0200 "connect to me", 0x40 object included).
 * @property frameCounter counts broadcast events (weigh-ins and the ~50-minute
 *   status broadcasts alike); resets on battery change.
 * @property solicit true when frame-control bit 9 is set — the scale wants a
 *   client to connect.
 * @property eventKey `frameCounter * 2^24 + extendedCounter`; identifies one
 *   broadcast for dedupe (the extended counter only exists on encrypted
 *   frames and is 0 elsewhere).
 * @property objects decrypted `<id, value>` objects — empty when the frame
 *   carries none, when no (correct) bind key was available, or when
 *   decryption failed.
 */
data class Beacon(
    val frameControl: Int,
    val frameCounter: Int,
    val solicit: Boolean,
    val eventKey: Long,
    val objects: List<Pair<Int, ByteArray>>,
)

/**
 * A weigh-in read from broadcast object `0x4e16`.
 *
 * The scale reports weight in 1/100 kg; [weightCentiKg] carries that raw value
 * unchanged (e.g. 7215 = 72.15 kg) — multiply by 10 for real grams.
 */
data class BroadcastWeighIn(
    val profileId: Int,
    val weightCentiKg: Int,
    val unixTs: Long,
)

/**
 * Decodes MiBeacon v5 service data as the S200 broadcasts it.
 *
 * Layout: `fc_le16 || product_le16 || counter(1) || [mac(6) if fc & 0x10] ||
 * [capability(2) if fc & 0x20] || payload`. An encrypted frame (`fc & 0x08`)
 * carries `payload = ct || ext_counter(3) || tag(4)`, AES-CCM with
 * key = bind key, nonce = `mac_le(6) || product(2, as it appears) ||
 * counter(1) || ext_counter(3)` and AAD = the single byte `0x11`.
 *
 * Failure rules: structurally truncated service data yields null; a wrong or
 * missing bind key (or a tampered ciphertext) yields a [Beacon] with empty
 * objects — decrypt failures are swallowed, never thrown.
 */
object BeaconDecoder {

    /** Frame-control bit: the payload is AES-CCM encrypted. */
    private const val FC_ENCRYPTED = 0x08

    /** Frame-control bit: a 6-byte MAC field (the scale's, little-endian). */
    private const val FC_MAC = 0x10

    /** Frame-control bit: a 2-byte capability field follows the MAC. */
    private const val FC_CAPABILITY = 0x20

    /** Frame-control bit 9: "connect to me" — the scale wants a client. */
    private const val FC_SOLICIT = 0x0200

    /** fc(2) + product id(2) + frame counter(1). */
    private const val HEADER_BYTES = 5
    private const val MAC_BYTES = 6
    private const val CAPABILITY_BYTES = 2

    /** Encrypted-payload trailer: extended counter (3) + tag (4). */
    private const val TRAILER_BYTES = 7
    private const val TAG_BYTES = 4
    private const val EXT_COUNTER_BYTES = 3
    private const val BIND_KEY_BYTES = 16
    private const val EVENT_COUNTER_SHIFT = 24

    /** Associated data for advertisement CCM: the single byte 0x11. */
    private val AAD = byteArrayOf(0x11)

    /** Returns null when [serviceData] is structurally truncated. */
    fun decode(serviceData: ByteArray, scaleMac: String, bindKey: ByteArray?): Beacon? {
        if (serviceData.size < HEADER_BYTES) return null
        val frameControl = u16le(serviceData, 0)
        val frameCounter = serviceData[4].toInt() and 0xFF

        var offset = HEADER_BYTES
        if (frameControl and FC_MAC != 0) {
            if (serviceData.size < offset + MAC_BYTES) return null
            // The frame's MAC field is the scale's own MAC in little-endian
            // order — the same bytes the CCM nonce uses. The nonce takes the
            // scale's MAC (scaleMac) either way, so the field is only skipped.
            offset += MAC_BYTES
        }
        if (frameControl and FC_CAPABILITY != 0) {
            if (serviceData.size < offset + CAPABILITY_BYTES) return null
            offset += CAPABILITY_BYTES
        }
        val payload = serviceData.copyOfRange(offset, serviceData.size)

        val encrypted = frameControl and FC_ENCRYPTED != 0
        val extCounter = if (encrypted) {
            if (payload.size < TRAILER_BYTES) return null
            u24le(payload, payload.size - TRAILER_BYTES)
        } else {
            0
        }
        val objects = if (encrypted) {
            decryptObjects(payload, serviceData, scaleMac, frameCounter, extCounter, bindKey)
        } else {
            parseObjects(payload)
        }
        return Beacon(
            frameControl = frameControl,
            frameCounter = frameCounter,
            solicit = frameControl and FC_SOLICIT != 0,
            eventKey = frameCounter.toLong() * (1L shl EVENT_COUNTER_SHIFT) + extCounter,
            objects = objects,
        )
    }

    /**
     * Decrypts the `ct || ext_counter(3) || tag(4)` payload and parses its
     * objects. A wrong/missing bind key or a bad tag yields an empty list —
     * decrypt failures never propagate.
     */
    private fun decryptObjects(
        payload: ByteArray,
        serviceData: ByteArray,
        scaleMac: String,
        frameCounter: Int,
        extCounter: Int,
        bindKey: ByteArray?,
    ): List<Pair<Int, ByteArray>> {
        if (bindKey == null || bindKey.size != BIND_KEY_BYTES) return emptyList()
        val ctLength = payload.size - TRAILER_BYTES
        // The tag rides after the extended counter, so ct and tag are not
        // contiguous: AesCcm wants ct || tag.
        val ctAndTag = payload.copyOfRange(0, ctLength) +
            payload.copyOfRange(payload.size - TAG_BYTES, payload.size)
        val nonce = macLittleEndian(scaleMac) +
            byteArrayOf(serviceData[2], serviceData[3]) + // product id, as it appears (le16)
            byteArrayOf(frameCounter.toByte()) +
            u24leBytes(extCounter)
        return try {
            parseObjects(AesCcm.decrypt(bindKey, nonce, AAD, ctAndTag))
        } catch (_: AuthenticationMismatch) {
            emptyList()
        }
    }

    /**
     * Parses the `<id_le16><len:u8><value>` object stream, stopping at a
     * truncated object (its siblings already parsed survive).
     */
    private fun parseObjects(plain: ByteArray): List<Pair<Int, ByteArray>> {
        val objects = mutableListOf<Pair<Int, ByteArray>>()
        var i = 0
        while (i + 3 <= plain.size) {
            val id = u16le(plain, i)
            val length = plain[i + 2].toInt() and 0xFF
            if (i + 3 + length > plain.size) break
            objects.add(id to plain.copyOfRange(i + 3, i + 3 + length))
            i += 3 + length
        }
        return objects
    }

    /**
     * Parses `AA:BB:CC:DD:EE:FF` (colons/dashes optional, any case) into the
     * 6 bytes reversed — the scale's MAC in little-endian order, as both the
     * frame's MAC field and the CCM nonce carry it.
     */
    private fun macLittleEndian(scaleMac: String): ByteArray {
        val hex = scaleMac.filter { it.isLetterOrDigit() }
        require(hex.length == 12) { "scale MAC must be 6 bytes of hex, was '$scaleMac'" }
        val mac = ByteArray(6) { i ->
            val hi = Character.digit(hex[2 * i], 16)
            val lo = Character.digit(hex[2 * i + 1], 16)
            require(hi >= 0 && lo >= 0) { "scale MAC must be 6 bytes of hex, was '$scaleMac'" }
            ((hi shl 4) or lo).toByte()
        }
        return mac.reversedArray()
    }
}

/**
 * Broadcast weigh-in object `0x4e16`: 9 bytes
 * `<profile id:u8><weight:le32, 1/100 kg><unix time:le32>`.
 */
object WeighInObjects {

    const val OBJ_WEIGH_IN = 0x4E16

    private const val VALUE_BYTES = 9

    /** Sanity bounds on the weight field (1/100 kg): 1 kg .. 300 kg. */
    private const val MIN_WEIGHT_CENTIKG = 100
    private const val MAX_WEIGHT_CENTIKG = 30000

    /** Returns null unless the value is exactly 9 bytes with a plausible weight. */
    fun parse(value: ByteArray): BroadcastWeighIn? {
        if (value.size != VALUE_BYTES) return null
        val weightCentiKg = u32le(value, 1)
        if (weightCentiKg < MIN_WEIGHT_CENTIKG || weightCentiKg > MAX_WEIGHT_CENTIKG) return null
        return BroadcastWeighIn(
            profileId = value[0].toInt() and 0xFF,
            weightCentiKg = weightCentiKg.toInt(),
            unixTs = u32le(value, 5),
        )
    }
}

// --- little helpers shared by the decode path -------------------------------

private fun u16le(buf: ByteArray, at: Int): Int =
    (buf[at].toInt() and 0xFF) or ((buf[at + 1].toInt() and 0xFF) shl 8)

private fun u24le(buf: ByteArray, at: Int): Int =
    (buf[at].toInt() and 0xFF) or
        ((buf[at + 1].toInt() and 0xFF) shl 8) or
        ((buf[at + 2].toInt() and 0xFF) shl 16)

private fun u24leBytes(v: Int): ByteArray = byteArrayOf(
    (v and 0xFF).toByte(),
    ((v shr 8) and 0xFF).toByte(),
    ((v shr 16) and 0xFF).toByte(),
)

/** Little-endian unsigned 32-bit read. */
private fun u32le(buf: ByteArray, at: Int): Long {
    var v = 0L
    for (i in 3 downTo 0) v = (v shl 8) or (buf[at + i].toLong() and 0xFF)
    return v
}
