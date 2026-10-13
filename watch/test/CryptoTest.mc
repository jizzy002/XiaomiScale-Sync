import Toybox.Lang;
import Toybox.Test;

//! Phase-1 crypto vector tests: every fixture in crypto_vectors.json is
//! run through the watch's Hkdf / AesCcm / SessionCrypto ports. The
//! fixture data itself is generated (scripts/watch_vectors.py) from the
//! same committed vectors the Kotlin tests use.
(:test)
function testAllVectors(logger as Logger) as Boolean {
    var fixtures = VectorsGenerated.fixtures();
    Test.assertMessage(fixtures.size() == 5, "expected 5 crypto fixtures");
    for (var i = 0; i < fixtures.size(); i++) {
        var f = fixtures[i];
        var name = f.get("name") as String;
        var type = f.get("type") as String;
        if (type.equals("ccm")) {
            runCcmFixture(f, name);
        } else if (type.equals("hkdf")) {
            runHkdfFixture(f, name);
        } else {
            Test.assertMessage(false, name + ": unknown fixture type " + type);
        }
    }
    return true;
}

//! CCM fixtures assert an exact ciphertext match (so a wrong tag or
//! keystream cannot pass) plus a round-trip decrypt. session_enc
//! additionally pins the SessionCrypto frame layout helpers.
function runCcmFixture(f as Dictionary<String, Object>, name as String) as Void {
    var key = hex(f.get("key") as String);
    var nonce = hex(f.get("nonce") as String);
    var aadHex = f.get("aad") as String or Null;
    var aad = (aadHex == null) ? null : hex(aadHex as String);
    var plaintext = hex(f.get("plaintext") as String);
    var ciphertext = hex(f.get("ciphertext") as String);

    var encrypted = AesCcm.encrypt(key, nonce, aad, plaintext);
    if (encrypted == null || !bytesEqual(encrypted, ciphertext)) {
        Test.assertMessage(false, name + ": encrypt mismatch");
        return;
    }
    var decrypted = AesCcm.decrypt(key, nonce, aad, ciphertext);
    if (decrypted == null || !bytesEqual(decrypted, plaintext)) {
        Test.assertMessage(false, name + ": decrypt mismatch");
        return;
    }

    if (name.equals("session_enc")) {
        var iv = hex(f.get("iv") as String);
        var ctr = f.get("ctr") as Number;
        var framed = SessionCrypto.encryptValue(key, iv, ctr as Number, plaintext);
        // framed = ctr_le16 || (ciphertext||tag).
        var expected = concat(le16(ctr as Number), ciphertext);
        if (framed == null || !bytesEqual(framed, expected)) {
            Test.assertMessage(false, name + ": SessionCrypto.encryptValue mismatch");
            return;
        }
        var opened = SessionCrypto.decryptValue(key, iv, framed);
        if (opened == null || !bytesEqual(opened, plaintext)) {
            Test.assertMessage(false, name + ": SessionCrypto.decryptValue mismatch");
        }
    }
}

//! HKDF fixtures derive and compare the full output.
function runHkdfFixture(f as Dictionary<String, Object>, name as String) as Void {
    var ikm = hex(f.get("ikm") as String);
    var salt = hex(f.get("salt") as String);
    var info = hex(f.get("info") as String);
    var length = f.get("length") as Number;
    var expected = hex(f.get("output") as String);
    var actual = Hkdf.sha256(ikm, salt, info, length as Number);
    if (actual == null || !bytesEqual(actual, expected)) {
        Test.assertMessage(false, name + ": Hkdf.sha256 mismatch");
    }
}

//! ------------------------------------------------------------- helpers

//! Hex string -> ByteArray. Char.toNumber() is the UTF-32 code point,
//! so hex digits are decoded by offset from their code points.
function hex(s as String) as ByteArray {
    var chars = s.toCharArray();
    var out = []b;
    for (var i = 0; i < chars.size() / 2; i++) {
        var hi = hexDigit(chars[2 * i]);
        var lo = hexDigit(chars[(2 * i) + 1]);
        out.add((hi << 4) | lo);
    }
    return out;
}

//! '0'-'9' -> 0-9, 'a'-'f'/'A'-'F' -> 10-15. Unknown chars yield 0
//! (fixtures are generated, so this never sees a non-hex char).
function hexDigit(c as Char) as Number {
    var v = c.toNumber();
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

function bytesEqual(a as ByteArray, b as ByteArray) as Boolean {
    return Bytes.equalsConstantTime(a, b);
}

function concat(a as ByteArray, b as ByteArray) as ByteArray {
    return Bytes.concat(a, b);
}

function le16(v as Number) as ByteArray {
    return [v & 0xFF, (v >> 8) & 0xFF]b;
}
