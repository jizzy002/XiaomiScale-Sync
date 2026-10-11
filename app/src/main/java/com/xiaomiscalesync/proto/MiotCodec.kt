package com.xiaomiscalesync.proto

/**
 * A decoded MIoT-spec operation message.
 *
 * [iid] carries the per-op instance id: the `aiid` for actions, the `eiid`
 * for events; both are 0 for results and hello.
 */
data class MiotMessage(
    val tid: Int,
    val op: Int,
    val siid: Int = 0,
    val iid: Int = 0,
    val status: Int = 0,
    val params: Map<Int, Any> = emptyMap(),
)

/** MIoT-spec parameter types seen on the scale's wire. */
enum class ParamType(val code: Int) {
    U8(0x1),
    U16(0x3),
    U64(0x8),
    STRING(0xa),
}

/**
 * Encode/decode for the MIoT messages carried inside the encrypted session
 * channel. Wire format (verified against faraga1/xiaomi-scale-s200-ble's
 * protocol reference, captured from the official app):
 *
 * ```
 * <len:le16 = 0x2000 | total_length><tid:le16><op:u8>[body]
 * ```
 *
 * The meaningful length (`len & 0x1FFF`) is the **total** message length
 * including the 2-byte length field itself; the `0x2000` flag is always set.
 * Bodies: action `<siid><aiid><n><params>`, result `<status:le16>[<n><params>]`,
 * event `<siid><eiid><0x00><n><params>`, hello none. Param:
 * `<piid:le16><type_len:le16 = type<<12 | value_len><value>` — value is
 * little-endian for the integer types, UTF-8 for [ParamType.STRING].
 */
object MiotCodec {

    const val OP_HELLO = 0xF0
    const val OP_ACTION = 0x05
    const val OP_RESULT = 0x06
    const val OP_EVENT = 0x07

    private const val LENGTH_FLAG = 0x2000
    private const val LENGTH_MASK = 0x1FFF
    private const val HEADER_BYTES = 5 // len(2) + tid(2) + op(1)

    /** Composes `len|tid|op|body` into a full wire message. */
    fun encode(tid: Int, op: Int, body: ByteArray): ByteArray {
        val total = HEADER_BYTES + body.size
        require(total <= LENGTH_MASK) { "MIoT message too long: $total bytes" }
        return le16(LENGTH_FLAG or total) + le16(tid) + byteArrayOf(op.toByte()) + body
    }

    /**
     * Decodes one decrypted message. The body is bounded by the masked
     * length field (tolerating trailing bytes beyond it, e.g. leftover
     * framing), matching the reference client's lenient behaviour.
     */
    fun decode(plaintext: ByteArray): MiotMessage {
        require(plaintext.size >= HEADER_BYTES) {
            "MIoT message too short: ${plaintext.size} bytes"
        }
        val end = minOf(plaintext.size, u16(plaintext, 0) and LENGTH_MASK)
        require(end >= HEADER_BYTES) { "MIoT length field too small: $end" }
        val tid = u16(plaintext, 2)
        val op = plaintext[4].toInt() and 0xFF
        val body = plaintext.copyOfRange(HEADER_BYTES, end)
        val msg = MiotMessage(tid = tid, op = op)
        return when (op) {
            OP_RESULT -> {
                require(body.size >= 2) { "result body too short: ${body.size} bytes" }
                val status = u16(body, 0)
                if (body.size > 2) {
                    msg.copy(status = status, params = decodeParams(body, 3, body[2].toInt() and 0xFF))
                } else {
                    msg.copy(status = status)
                }
            }

            OP_EVENT -> {
                require(body.size >= 4) { "event body too short: ${body.size} bytes" }
                msg.copy(
                    siid = body[0].toInt() and 0xFF,
                    iid = body[1].toInt() and 0xFF, // eiid
                    params = decodeParams(body, 4, body[3].toInt() and 0xFF),
                )
            }

            OP_ACTION -> {
                require(body.size >= 3) { "action body too short: ${body.size} bytes" }
                msg.copy(
                    siid = body[0].toInt() and 0xFF,
                    iid = body[1].toInt() and 0xFF, // aiid
                    params = decodeParams(body, 3, body[2].toInt() and 0xFF),
                )
            }

            else -> msg // hello (0xf0) and anything unexpected: header only
        }
    }

    /** Builds one `<piid><type<<12|len><value>` parameter (body fragment). */
    fun param(piid: Int, type: ParamType, value: Any): ByteArray {
        val raw = when (type) {
            ParamType.STRING -> (value as String).toByteArray(Charsets.UTF_8)
            ParamType.U8 -> byteArrayOf(uint(value, 0xFF, type).toByte())
            ParamType.U16 -> le16(uint(value, 0xFFFF, type).toInt())
            ParamType.U64 -> le64(uint(value, Long.MAX_VALUE, type))
        }
        require(raw.size <= 0xFFF) { "parameter $piid too long: ${raw.size} bytes" }
        return le16(piid) + le16((type.code shl 12) or raw.size) + raw
    }

    /** Coerces to Long, rejecting negatives and values over [max]. */
    private fun uint(value: Any, max: Long, type: ParamType): Long {
        val v = (value as Number).toLong()
        require(v in 0..max) { "$type value out of range: $v" }
        return v
    }

    /** Builds an action body `<siid><aiid><n><params>` (no header). */
    fun action(siid: Int, aiid: Int, params: List<ByteArray>): ByteArray {
        require(params.size <= 0xFF) { "too many params: ${params.size}" }
        return byteArrayOf(siid.toByte(), aiid.toByte(), params.size.toByte()) +
            params.fold(ByteArray(0)) { acc, p -> acc + p }
    }

    /** Builds a result body `<status:le16>[<n><params>]` (no header). */
    fun result(status: Int, params: List<ByteArray>): ByteArray {
        require(params.size <= 0xFF) { "too many params: ${params.size}" }
        if (params.isEmpty()) return le16(status)
        return le16(status) + byteArrayOf(params.size.toByte()) +
            params.fold(ByteArray(0)) { acc, p -> acc + p }
    }

    /** Builds an event body `<siid><eiid><0x00><n><params>` (no header). */
    fun event(siid: Int, eiid: Int, params: List<ByteArray>): ByteArray {
        require(params.size <= 0xFF) { "too many params: ${params.size}" }
        return byteArrayOf(siid.toByte(), eiid.toByte(), 0, params.size.toByte()) +
            params.fold(ByteArray(0)) { acc, p -> acc + p }
    }

    private fun decodeParams(buf: ByteArray, offset: Int, count: Int): Map<Int, Any> {
        val params = LinkedHashMap<Int, Any>(count)
        var pos = offset
        repeat(count) {
            require(pos + 4 <= buf.size) { "truncated parameter list at offset $pos" }
            val piid = u16(buf, pos)
            val typeLen = u16(buf, pos + 2)
            val type = typeLen shr 12
            val len = typeLen and 0xFFF
            pos += 4
            require(pos + len <= buf.size) { "truncated parameter $piid: need $len bytes" }
            params[piid] = when (type) {
                ParamType.STRING.code -> String(buf, pos, len, Charsets.UTF_8)
                ParamType.U8.code, ParamType.U16.code -> uN(buf, pos, len)
                ParamType.U64.code -> {
                    // le64 reads a fixed 8 bytes; a corrupted frame declaring
                    // a smaller wire length would otherwise run past the body.
                    require(len == 8) { "U64 parameter $piid has wire length $len, expected 8" }
                    le64(buf, pos)
                }
                else -> when (len) { // unknown type: fall back to length-based decoding
                    1, 2, 4 -> uN(buf, pos, len)
                    8 -> le64(buf, pos)
                    else -> buf.copyOfRange(pos, pos + len)
                }
            }
            pos += len
        }
        return params
    }

    private fun le16(value: Int): ByteArray =
        byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())

    private fun le64(value: Long): ByteArray = ByteArray(8) { i -> (value shr (8 * i)).toByte() }

    private fun le64(buf: ByteArray, at: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (buf[at + i].toLong() and 0xFF)
        return v
    }

    private fun u16(buf: ByteArray, at: Int): Int =
        (buf[at].toInt() and 0xFF) or ((buf[at + 1].toInt() and 0xFF) shl 8)

    /** Little-endian unsigned integer of [len] bytes (1, 2 or 4). */
    private fun uN(buf: ByteArray, at: Int, len: Int): Int {
        var v = 0
        for (i in len - 1 downTo 0) v = (v shl 8) or (buf[at + i].toInt() and 0xFF)
        return v
    }
}

/** One stored weigh-in, as the scale's event 6.1 records it. */
data class StoredRecord(
    val no: Long,
    val mid: Long,
    val userType: Int,
    val weightGrams: Int,
    val flag: Int,
    val ts: Long,
)

/**
 * The records string of event 6.1: `no,mid,user_type,weight_g,flag,unix_ts`
 * records joined by `_` (the scale's own decimal formatting, no spaces) —
 * e.g. `41,1,1,8025,0,1790000000_42,0,0,7260,2,1790000060`.
 */
object StoredRecords {

    /** Parses one records string; an empty string is no records. */
    fun parse(text: String): List<StoredRecord> {
        if (text.isEmpty()) return emptyList()
        return text.split("_").map { parseRecord(it) }
    }

    private fun parseRecord(record: String): StoredRecord {
        val f = record.split(",")
        if (f.size != FIELDS) {
            throw ProtocolError("bad stored record ($FIELDS comma fields expected): '$record'")
        }
        return StoredRecord(
            no = long(f[0], record),
            mid = long(f[1], record),
            userType = int(f[2], record),
            weightGrams = int(f[3], record),
            flag = int(f[4], record),
            ts = long(f[5], record),
        )
    }

    private fun long(field: String, record: String): Long =
        field.trim().toLongOrNull()
            ?: throw ProtocolError("bad stored record field '$field' in '$record'")

    private fun int(field: String, record: String): Int {
        val v = long(field, record)
        if (v < 0 || v > Int.MAX_VALUE) {
            throw ProtocolError("stored record field out of int range: '$field' in '$record'")
        }
        return v.toInt()
    }

    private const val FIELDS = 6
}
