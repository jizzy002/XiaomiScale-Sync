import Toybox.Lang;

//! Shared byte helpers for the (:test) suite. hex() ignores spaces, so
//! fixture hex strings can be transcribed from the Kotlin tests verbatim
//! (grouping spaces included).

//! Hex string -> ByteArray (spaces ignored, so fixture strings can be
//! transcribed from the Kotlin tests with their grouping intact).
//! Char.toNumber() is the UTF-32 code point, so hex digits are decoded
//! by offset from their code points.
function hex(s as String) as ByteArray {
    var chars = s.toCharArray();
    var out = []b;
    var hi = -1;
    for (var i = 0; i < chars.size(); i++) {
        var cp = chars[i].toNumber();
        if (cp == 0x20) {
            continue;
        }
        if (hi < 0) {
            hi = cp;
        } else {
            out.add((hexDigitCp(hi) << 4) | hexDigitCp(cp));
            hi = -1;
        }
    }
    return out;
}

//! UTF-32 code point of a hex digit -> 0-15. Unknown chars yield 0
//! (fixtures never contain a non-hex char).
function hexDigitCp(v as Number) as Number {
    if (v >= 48 && v <= 57) {
        return v - 48;
    }
    if (v >= 97 && v <= 102) {
        return v - 87;
    }
    if (v >= 65 && v <= 70) {
        return v - 55;
    }
    return 0;
}

//! Nullable so the nullable results of MiotCodec.encode/param can be
//! compared directly; null never equals anything.
function bytesEqual(a as ByteArray or Null, b as ByteArray or Null) as Boolean {
    if (a == null || b == null) {
        return false;
    }
    return Bytes.equalsConstantTime(a, b);
}

function concat(a as ByteArray, b as ByteArray) as ByteArray {
    return Bytes.concat(a, b);
}

function le16(v as Number) as ByteArray {
    return [v & 0xFF, (v >> 8) & 0xFF]b;
}

//! Little-endian bytes -> Long. ByteArray.decodeNumber has no 64-bit
//! format in SDK 9.2, so U64 fixtures are assembled by hand.
function leLong(b as ByteArray) as Long {
    var v = 0l;
    for (var i = b.size() - 1; i >= 0; i--) {
        v = (v << 8) | (b[i] & 0xFF);
    }
    return v;
}
