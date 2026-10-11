package com.xiaomiscalesync.proto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Tests for [MiotCodec] against the spec's pinned live-weight event capture:
 * `19 20 0f 00 07 05 03 00 03 …` = 70.00 kg, not yet stable, from
 * faraga1/xiaomi-scale-s200-ble's protocol reference.
 */
class MiotCodecTest {

    /** Parses hex with spaces (ignored), e.g. `hex("0d20 1100")`. */
    private fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "")
        return ByteArray(clean.length / 2) {
            clean.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }

    @Test
    fun decodesPinnedLiveWeightEvent() {
        val bytes = hex("19200f00070503000301000110 0002000110000300023058 1b")
        assertEquals(
            MiotMessage(
                tid = 0x000f,
                op = MiotCodec.OP_EVENT,
                siid = 5,
                iid = 3,
                params = mapOf(1 to 0, 2 to 0, 3 to 7000),
            ),
            MiotCodec.decode(bytes),
        )
    }

    @Test
    fun roundTripsActionWithProfileJsonStringParam() {
        // The real use case: pushing the user profile (a ~150-byte JSON
        // string) via action 7.1 — long enough to exercise STRING length
        // packing beyond the 12-bit boundary conditions.
        val profileJson = """{"mid":"1","duid":1,"uc":1,"ow":1,"unit":1,"time":1790000000,"ud":[{"duid":1,"ut":1,"age":30,"sex":1,"hi":180,"wt":8025}]}"""
        val body = MiotCodec.action(
            siid = 7,
            aiid = 1,
            params = listOf(MiotCodec.param(1, ParamType.STRING, profileJson)),
        )
        val wire = MiotCodec.encode(tid = 0x0009, op = MiotCodec.OP_ACTION, body = body)
        assertEquals(
            MiotMessage(
                tid = 0x0009,
                op = MiotCodec.OP_ACTION,
                siid = 7,
                iid = 1,
                params = mapOf(1 to profileJson),
            ),
            MiotCodec.decode(wire),
        )
    }

    @Test
    fun encodesActionBodyWithConcreteBytes() {
        // Pin encode against hand-computed bytes, not only the round trip.
        // action(7, 1, [param(1, U8, 5)]):
        //   len = 0x2000 | (5 + 3 + 5) = 0x200d, tid = 0x0009, op = 0x05,
        //   body = 07 01 01 | 0100 1001 05
        val body = MiotCodec.action(7, 1, listOf(MiotCodec.param(1, ParamType.U8, 5)))
        assertArrayEquals(
            hex("0d20 0900 05 07 01 01 0100 0110 05"),
            MiotCodec.encode(tid = 0x0009, op = MiotCodec.OP_ACTION, body = body),
        )
    }

    @Test
    fun decodesResultWithZeroStatusAndParams() {
        // result, tid 0x0009, status 0, one param piid=3 u8 = 12:
        //   len = 0x2000 | (5 + 2 + 1 + 5) = 0x200d
        val msg = MiotCodec.decode(hex("0d20 0900 06 0000 01 0300 0110 0c"))
        assertEquals(
            MiotMessage(tid = 0x0009, op = MiotCodec.OP_RESULT, status = 0, params = mapOf(3 to 12)),
            msg,
        )
    }

    @Test
    fun rejectsU64ParamWithUndersizedWireLength() {
        // Malformed event: n=1, piid 3, type_len 0x8000 (U64, wire len 0).
        // The generic bound check passes (0 bytes needed), but a fixed
        // 8-byte U64 read would run past the body -> must be a clean
        // IllegalArgumentException, not ArrayIndexOutOfBoundsException.
        assertThrows(IllegalArgumentException::class.java) {
            MiotCodec.decode(hex("0d20 0900 07 05 03 00 01 03 00 00 80"))
        }
    }

    @Test
    fun paramPacksTypeInHighNibbleAndLengthInLowTwelveBits() {
        assertArrayEquals(hex("0100 0110 00"), MiotCodec.param(1, ParamType.U8, 0))
        assertArrayEquals(hex("0300 0230 581b"), MiotCodec.param(3, ParamType.U16, 7000))
        assertArrayEquals(
            hex("0400 0880 7856341278563412"),
            MiotCodec.param(4, ParamType.U64, 0x1234567812345678L),
        )
        assertArrayEquals(
            hex("0100 03a0 686921"),
            MiotCodec.param(1, ParamType.STRING, "hi!"),
        )
    }
}
