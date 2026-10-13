import Toybox.Lang;
import Toybox.StringUtil;

//! A decoded MIoT-spec operation message (port of MiotCodec.kt's
//! MiotMessage). `iid` is the aiid for actions, the eiid for events;
//! both are 0 for results and hello.
class MiotMessage {
    var tid as Number = 0;
    var op as Number = 0;
    var siid as Number = 0;
    var iid as Number = 0;
    var status as Number = 0;
    var params as Dictionary = {};

    function initialize(tid as Number, op as Number) {
        self.tid = tid;
        self.op = op;
    }
}

//! MIoT-spec parameter type codes seen on the scale's wire.
class ParamType {
    static const U8 = 0x1;
    static const U16 = 0x3;
    static const U64 = 0x8;
    static const STRING = 0xa;
}

//! Encode/decode for the MIoT messages carried inside the encrypted
//! session channel. Wire format:
//!   <len:le16 = 0x2000 | total_length><tid:le16><op:u8>[body]
//! where total_length includes the 2-byte length field itself.
//!
//! Divergence from the Kotlin port: malformed input returns null instead
//! of throwing ProtocolError (the watch has no checked exception flow).
class MiotCodec {
    static const OP_HELLO = 0xF0;
    static const OP_ACTION = 0x05;
    static const OP_RESULT = 0x06;
    static const OP_EVENT = 0x07;

    private static const LENGTH_FLAG = 0x2000;
    private static const LENGTH_MASK = 0x1FFF;
    private static const HEADER_BYTES = 5; // len(2) + tid(2) + op(1)

    //! Header + body; null if the message would exceed the 13-bit length.
    static function encode(tid as Number, op as Number, body as ByteArray) as ByteArray or Null {
        var total = HEADER_BYTES + body.size();
        if (total > LENGTH_MASK) {
            return null;
        }
        var out = le16(LENGTH_FLAG | total);
        out.addAll(le16(tid));
        out.add(op & 0xFF);
        out.addAll(body);
        return out;
    }

    //! Decodes one decrypted message. The body is bounded by the masked
    //! length field (trailing bytes beyond it are tolerated). Accepts null
    //! so a failed encode can be fed straight in.
    static function decode(plaintext as ByteArray or Null) as MiotMessage or Null {
        if (plaintext == null || plaintext.size() < HEADER_BYTES) {
            return null;
        }
        var end = u16(plaintext, 0) & LENGTH_MASK;
        if (plaintext.size() < end) {
            end = plaintext.size();
        }
        if (end < HEADER_BYTES) {
            return null;
        }
        var msg = new MiotMessage(u16(plaintext, 2), plaintext[4] & 0xFF);
        var body = plaintext.slice(HEADER_BYTES, end);
        var params = null;
        if (msg.op == OP_RESULT) {
            if (body.size() < 2) {
                return null;
            }
            msg.status = u16(body, 0);
            if (body.size() <= 2) {
                return msg;
            }
            params = decodeParams(body, 3, body[2] & 0xFF);
        } else if (msg.op == OP_EVENT) {
            if (body.size() < 4) {
                return null;
            }
            msg.siid = body[0] & 0xFF;
            msg.iid = body[1] & 0xFF;
            params = decodeParams(body, 4, body[3] & 0xFF);
        } else if (msg.op == OP_ACTION) {
            if (body.size() < 3) {
                return null;
            }
            msg.siid = body[0] & 0xFF;
            msg.iid = body[1] & 0xFF;
            params = decodeParams(body, 3, body[2] & 0xFF);
        } else {
            return msg; // hello and anything unexpected: header only
        }
        if (params == null) {
            return null;
        }
        msg.params = params;
        return msg;
    }

    //! One `<piid><type<<12|len><value>` parameter; null on a value of
    //! the wrong kind, out of range, or longer than 0xFFF bytes.
    //! U64 values are carried as Long (Monkey C has no 64-bit literal).
    static function param(piid as Number, type as Number, value as Object or Null) as ByteArray or Null {
        var raw = null;
        if (type == ParamType.STRING) {
            if (!(value instanceof String)) {
                return null;
            }
            raw = utf8(value);
        } else if (type == ParamType.U8) {
            var v = uint(value, 0xFF);
            if (v == null) {
                return null;
            }
            raw = [v]b;
        } else if (type == ParamType.U16) {
            var v = uint(value, 0xFFFF);
            if (v == null) {
                return null;
            }
            raw = le16(v);
        } else if (type == ParamType.U64) {
            if (!(value instanceof Long || value instanceof Number)) {
                return null;
            }
            var l = (value as Long or Number).toLong();
            if (l < 0) {
                return null;
            }
            raw = le64(l);
        } else {
            return null;
        }
        if (raw.size() > 0xFFF) {
            return null;
        }
        var out = le16(piid);
        out.addAll(le16((type << 12) | raw.size()));
        out.addAll(raw);
        return out;
    }

    //! Action body `<siid><aiid><n><params>` (no header).
    static function action(siid as Number, aiid as Number, params as Array<ByteArray>) as ByteArray {
        var out = [siid & 0xFF, aiid & 0xFF, params.size() & 0xFF]b;
        return appendAll(out, params);
    }

    //! Result body `<status:le16>[<n><params>]` (no header).
    static function result(status as Number, params as Array<ByteArray>) as ByteArray {
        var out = le16(status);
        if (params.size() == 0) {
            return out;
        }
        out.add(params.size() & 0xFF);
        return appendAll(out, params);
    }

    //! Event body `<siid><eiid><0x00><n><params>` (no header).
    static function event(siid as Number, eiid as Number, params as Array<ByteArray>) as ByteArray {
        var out = [siid & 0xFF, eiid & 0xFF, 0, params.size() & 0xFF]b;
        return appendAll(out, params);
    }

    private static function appendAll(out as ByteArray, params as Array<ByteArray>) as ByteArray {
        for (var i = 0; i < params.size(); i++) {
            out.addAll(params[i]);
        }
        return out;
    }

    private static function decodeParams(buf as ByteArray, offset as Number, count as Number) as Dictionary or Null {
        var params = {};
        var pos = offset;
        for (var i = 0; i < count; i++) {
            if (pos + 4 > buf.size()) {
                return null;
            }
            var piid = u16(buf, pos);
            var typeLen = u16(buf, pos + 2);
            var type = typeLen >> 12;
            var len = typeLen & 0xFFF;
            pos += 4;
            if (pos + len > buf.size()) {
                return null;
            }
            if (type == ParamType.STRING) {
                params.put(piid, text(buf.slice(pos, pos + len)));
            } else if (type == ParamType.U8 || type == ParamType.U16) {
                params.put(piid, uN(buf, pos, len));
            } else if (type == ParamType.U64) {
                // A fixed 8-byte read would run past a shorter wire length.
                if (len != 8) {
                    return null;
                }
                params.put(piid, le64At(buf, pos));
            } else if (len == 1 || len == 2 || len == 4) {
                params.put(piid, uN(buf, pos, len));
            } else if (len == 8) {
                params.put(piid, le64At(buf, pos));
            } else {
                params.put(piid, buf.slice(pos, pos + len));
            }
            pos += len;
        }
        return params;
    }

    //! Number in 0..max, or null for anything else.
    private static function uint(value as Object or Null, max as Number) as Number or Null {
        if (!(value instanceof Number)) {
            return null;
        }
        var v = value as Number;
        if (v < 0 || v > max) {
            return null;
        }
        return v;
    }

    private static function utf8(s as String) as ByteArray {
        return StringUtil.convertEncodedString(s, {
            :fromRepresentation => StringUtil.REPRESENTATION_STRING_PLAIN_TEXT,
            :toRepresentation => StringUtil.REPRESENTATION_BYTE_ARRAY
        }) as ByteArray;
    }

    private static function text(b as ByteArray) as String {
        return StringUtil.convertEncodedString(b, {
            :fromRepresentation => StringUtil.REPRESENTATION_BYTE_ARRAY,
            :toRepresentation => StringUtil.REPRESENTATION_STRING_PLAIN_TEXT
        }) as String;
    }

    private static function le16(v as Number) as ByteArray {
        return [v & 0xFF, (v >> 8) & 0xFF]b;
    }

    private static function le64(v as Long) as ByteArray {
        var out = []b;
        for (var i = 0; i < 8; i++) {
            out.add(((v >> (8 * i)) & 0xFF).toNumber());
        }
        return out;
    }

    //! 8 little-endian bytes -> Long (the SDK has no 64-bit NUMBER_FORMAT).
    private static function le64At(buf as ByteArray, at as Number) as Long {
        var v = 0l;
        for (var i = 7; i >= 0; i--) {
            v = (v << 8) | (buf[at + i] & 0xFF);
        }
        return v;
    }

    private static function u16(buf as ByteArray, at as Number) as Number {
        return (buf[at] & 0xFF) | ((buf[at + 1] & 0xFF) << 8);
    }

    //! Little-endian unsigned integer of `len` bytes (1, 2 or 4).
    private static function uN(buf as ByteArray, at as Number, len as Number) as Number {
        var v = 0;
        for (var i = len - 1; i >= 0; i--) {
            v = (v << 8) | (buf[at + i] & 0xFF);
        }
        return v;
    }
}
