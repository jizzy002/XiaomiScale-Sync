import Toybox.Cryptography;
import Toybox.Lang;

//! AES-CCM (RFC 3610) with a 4-byte tag, built on the platform's AES-128
//! ECB single-block cipher (the watch exposes no CCM and no raw CTR):
//! CTR keystream = ECB(counter blocks), CBC-MAC = last block of CBC(no
//! padding, IV=0) over the RFC 3610 formatted data. Port of the Android
//! app's AesCcm.kt.
//!
//! - encrypt() returns ciphertext || tag (tag = last 4 bytes).
//! - decrypt() verifies the tag and returns the plaintext, or null on any
//!   authentication failure — it never releases unverified plaintext.
//!
//! Nonce: 7..13 bytes (RFC 3610); L = 15 - nonce.size counter bytes.
class AesCcm {
    //! Tag length in bytes; fixed at 4 by the Xiaomi protocol.
    static const TAG_LENGTH = 4;

    private static const BLOCK_BYTES = 16;
    private static const MIN_NONCE_BYTES = 7;
    private static const MAX_NONCE_BYTES = 13;

    //! Returns ciphertext || tag(4), or null on invalid input sizes.
    static function encrypt(
        key as ByteArray,
        nonce as ByteArray,
        aad as ByteArray or Null,
        plaintext as ByteArray
    ) as ByteArray or Null {
        var L = validate(key, nonce);
        if (L == null) {
            return null;
        }
        var tag = computeTag(key, nonce, L, aad, plaintext);
        var ciphertext = ctr(key, nonce, L, 1, plaintext);
        return Bytes.concat(ciphertext, tag);
    }

    //! Verifies the trailing 4-byte tag; returns plaintext or null.
    static function decrypt(
        key as ByteArray,
        nonce as ByteArray,
        aad as ByteArray or Null,
        data as ByteArray
    ) as ByteArray or Null {
        var L = validate(key, nonce);
        if (L == null || data.size() < TAG_LENGTH) {
            return null;
        }
        var ciphertext = data.slice(0, data.size() - TAG_LENGTH);
        var receivedTag = data.slice(data.size() - TAG_LENGTH, data.size());
        // CTR is its own inverse; the plaintext only becomes the return
        // value after the tag check below succeeds.
        var plaintext = ctr(key, nonce, L, 1, ciphertext);
        var expectedTag = computeTag(key, nonce, L, aad, plaintext);
        if (!Bytes.equalsConstantTime(expectedTag, receivedTag)) {
            return null;
        }
        return plaintext;
    }

    //! tag = MSB_t(G XOR S0): CBC-MAC over the formatted data, XORed with
    //! the first counter block's keystream.
    private static function computeTag(
        key as ByteArray,
        nonce as ByteArray,
        L as Number,
        aad as ByteArray or Null,
        plaintext as ByteArray
    ) as ByteArray {
        var mac = cbcMac(key, formattedData(nonce, L, aad, plaintext));
        var zero = Bytes.zeroFill(BLOCK_BYTES);
        var s0 = ctr(key, nonce, L, 0, zero);
        var tag = []b;
        for (var i = 0; i < TAG_LENGTH; i++) {
            tag.add(mac[i] ^ s0[i]);
        }
        return tag;
    }

    //! B0 || a-blocks || message blocks per RFC 3610 section 2.2:
    //! B0 = flags || N || Q where flags = 64*Adata + 8*M' + L', M' = 1 for
    //! a 4-byte tag, and Q is the payload length as L big-endian bytes.
    //! The a-blocks are zero-padded to whole blocks BEFORE the message
    //! blocks, which are padded separately at the end.
    private static function formattedData(
        nonce as ByteArray,
        L as Number,
        aad as ByteArray or Null,
        payload as ByteArray
    ) as ByteArray {
        var hasAad = (aad != null) && ((aad as ByteArray).size() > 0);
        var flags = (hasAad ? 0x40 : 0) | (((TAG_LENGTH - 2) / 2) << 3) | (L - 1);
        var out = formattedB0(flags, nonce, L, payload);
        if (hasAad) {
            var a = aad as ByteArray;
            // l(a) as 2 octets MSB-first, or 0xFFFE || 4-octet l(a) once
            // l(a) >= 2^16 - 2^8 (unreachable with scale frames).
            if (a.size() < 0xFF00) {
                out = Bytes.concat(out, twoByteBe(a.size()));
            } else {
                var wide = []b;
                wide.add(0xFF);
                wide.add(0xFE);
                wide.add((a.size() >> 24) & 0xFF);
                wide.add((a.size() >> 16) & 0xFF);
                wide.add((a.size() >> 8) & 0xFF);
                wide.add(a.size() & 0xFF);
                out = Bytes.concat(out, wide);
            }
            out = Bytes.concat(out, a);
            var aadPad = (BLOCK_BYTES - (out.size() % BLOCK_BYTES)) % BLOCK_BYTES;
            if (aadPad != 0) {
                out = Bytes.concat(out, Bytes.zeroFill(aadPad));
            }
        }
        out = Bytes.concat(out, payload);
        var pad = (BLOCK_BYTES - (out.size() % BLOCK_BYTES)) % BLOCK_BYTES;
        if (pad != 0) {
            out = Bytes.concat(out, Bytes.zeroFill(pad));
        }
        return out;
    }

    private static function formattedB0(
        flags as Number,
        nonce as ByteArray,
        L as Number,
        payload as ByteArray
    ) as ByteArray {
        var b0 = Bytes.zeroFill(1 + nonce.size() + L);
        b0[0] = flags & 0xFF;
        for (var i = 0; i < nonce.size(); i++) {
            b0[1 + i] = nonce[i];
        }
        // Q: payload length as L big-endian bytes, right after the nonce.
        var length = payload.size();
        for (var i = 1 + nonce.size() + L - 1; i >= 1 + nonce.size(); i--) {
            b0[i] = length & 0xFF;
            length = length >> 8;
        }
        return b0;
    }

    //! CBC-MAC = last block of CBC(no padding, IV=0) over block-aligned data.
    private static function cbcMac(key as ByteArray, data as ByteArray) as ByteArray {
        var cipher = new Cryptography.Cipher({
            :algorithm => Cryptography.CIPHER_AES128,
            :mode => Cryptography.MODE_CBC,
            :key => key,
            :iv => Bytes.zeroFill(BLOCK_BYTES),
        });
        var encrypted = cipher.encrypt(data);
        return encrypted.slice(encrypted.size() - BLOCK_BYTES, encrypted.size());
    }

    //! CTR crypt (encrypt == decrypt: XOR with the counter keystream).
    private static function ctr(
        key as ByteArray,
        nonce as ByteArray,
        L as Number,
        startCounter as Number,
        input as ByteArray
    ) as ByteArray {
        var ecb = new Cryptography.Cipher({
            :algorithm => Cryptography.CIPHER_AES128,
            :mode => Cryptography.MODE_ECB,
            :key => key,
        });
        var out = Bytes.zeroFill(input.size());
        var blocks = (input.size() + BLOCK_BYTES - 1) / BLOCK_BYTES;
        for (var b = 0; b < blocks; b++) {
            var keystream = ecb.encrypt(counterBlock(nonce, L, startCounter + b));
            var offset = b * BLOCK_BYTES;
            var chunk = BLOCK_BYTES;
            if (offset + chunk > input.size()) {
                chunk = input.size() - offset;
            }
            for (var i = 0; i < chunk; i++) {
                out[offset + i] = input[offset + i] ^ keystream[i];
            }
        }
        return out;
    }

    //! A_i = (L-1) || nonce || counter(L bytes, big-endian).
    private static function counterBlock(nonce as ByteArray, L as Number, counter as Number) as ByteArray {
        var block = Bytes.zeroFill(BLOCK_BYTES);
        block[0] = (L - 1) & 0xFF;
        for (var i = 0; i < nonce.size(); i++) {
            block[1 + i] = nonce[i];
        }
        var c = counter;
        for (var i = BLOCK_BYTES - 1; i >= BLOCK_BYTES - L; i--) {
            block[i] = c & 0xFF;
            c = c >> 8;
        }
        return block;
    }

    //! Returns L (RFC 3610: L = 15 - nonce.size counter bytes), or null
    //! when key/nonce sizes are invalid.
    private static function validate(key as ByteArray, nonce as ByteArray) as Number or Null {
        if (key.size() != 16) {
            return null; // AES-128 only; the protocol never uses 24/32.
        }
        if (nonce.size() < MIN_NONCE_BYTES || nonce.size() > MAX_NONCE_BYTES) {
            return null;
        }
        return 15 - nonce.size();
    }

    private static function twoByteBe(v as Number) as ByteArray {
        return [((v >> 8) & 0xFF), (v & 0xFF)]b;
    }
}
