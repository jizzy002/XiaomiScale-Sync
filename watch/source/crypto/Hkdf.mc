import Toybox.Cryptography;
import Toybox.Lang;

//! HKDF-SHA256 (RFC 5869), ported from the Android app's Hkdf.kt and
//! matching the reference client: extract `prk = HMAC(salt, ikm)`, then
//! expand `t(i) = HMAC(prk, t(i-1) || info || i)` — the output is the
//! first `length` bytes of the concatenated blocks.
class Hkdf {
    static const HASH_BYTES = 32;
    static const MAX_LENGTH_BYTES = 255 * 32;

    //! Returns the first `length` bytes of HKDF-SHA256 output, or null on
    //! an invalid length.
    static function sha256(
        ikm as ByteArray,
        salt as ByteArray,
        info as ByteArray,
        length as Number
    ) as ByteArray or Null {
        if (length <= 0 || length > MAX_LENGTH_BYTES) {
            return null;
        }
        // RFC 5869 2.2: an empty salt means HashLen zero bytes.
        var extractSalt = (salt.size() == 0) ? Bytes.zeroFill(HASH_BYTES) : salt;
        var prk = hmac(extractSalt, ikm);

        // RFC 5869 2.3: T(i) = HMAC(PRK, T(i-1) || info || i).
        var blocks = (length + HASH_BYTES - 1) / HASH_BYTES;
        var out = Bytes.zeroFill(blocks * HASH_BYTES);
        var previous = Bytes.zeroFill(0);
        for (var i = 1; i <= blocks; i++) {
            var counterByte = []b;
            counterByte.add(i & 0xFF);
            var suffix = Bytes.concat(info, counterByte);
            var block = hmac(prk, Bytes.concat(previous, suffix));
            for (var j = 0; j < HASH_BYTES; j++) {
                out[(i - 1) * HASH_BYTES + j] = block[j];
            }
            previous = block;
        }
        if (length == out.size()) {
            return out;
        }
        return out.slice(0, length);
    }

    //! HMAC-SHA256 over one shot (the platform HMAC object resets after
    //! digest(), so a fresh object per call keeps this simple).
    static function hmac(key as ByteArray, data as ByteArray) as ByteArray {
        var mac = new Cryptography.HashBasedMessageAuthenticationCode({
            :algorithm => Cryptography.HASH_SHA256,
            :key => key,
        });
        mac.update(data);
        return mac.digest();
    }
}
