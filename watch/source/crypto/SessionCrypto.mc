import Toybox.Lang;

//! Session-channel crypto helpers, ported from the Android app's
//! ScaleSession.kt companion (lines ~476-525): one session value is
//! `<ctr_le16><AES-CCM ct|tag(4)>` where the 12-byte nonce is
//! `iv(4) || 00000000 || ctr_le32` with no AAD — the CTR in the frame
//! and the CTR in the nonce are the same number (app_key/app_iv
//! outbound, dev_key/dev_iv inbound).
class SessionCrypto {
    private static const IV_BYTES = 4;
    private static const KEY_MATERIAL_BYTES = 40;

    static function sessionNonce(iv as ByteArray, ctr as Number) as ByteArray or Null {
        if (iv.size() != IV_BYTES) {
            return null;
        }
        var nonce = Bytes.zeroFill(12);
        for (var i = 0; i < IV_BYTES; i++) {
            nonce[i] = iv[i];
        }
        // Bytes 4..7 stay zero; ctr goes in 8..11 little-endian.
        nonce[8] = ctr & 0xFF;
        nonce[9] = (ctr >> 8) & 0xFF;
        nonce[10] = (ctr >> 16) & 0xFF;
        nonce[11] = (ctr >> 24) & 0xFF;
        return nonce;
    }

    //! One outbound session value: ctr_le16 || (ciphertext || tag).
    static function encryptValue(
        key as ByteArray,
        iv as ByteArray,
        ctr as Number,
        plaintext as ByteArray
    ) as ByteArray or Null {
        var nonce = sessionNonce(iv, ctr);
        if (nonce == null) {
            return null;
        }
        var sealed = AesCcm.encrypt(key, nonce, null, plaintext);
        if (sealed == null) {
            return null;
        }
        var out = Bytes.zeroFill(2 + sealed.size());
        out[0] = ctr & 0xFF;
        out[1] = (ctr >> 8) & 0xFF;
        for (var i = 0; i < sealed.size(); i++) {
            out[2 + i] = sealed[i];
        }
        return out;
    }

    //! Decrypts one inbound session value; the CTR comes from the frame
    //! itself. Returns null on a bad tag or a too-short value.
    static function decryptValue(
        key as ByteArray,
        iv as ByteArray,
        value as ByteArray
    ) as ByteArray or Null {
        if (value.size() < 2 + AesCcm.TAG_LENGTH) {
            return null;
        }
        var ctr = (value[0] & 0xFF) | ((value[1] & 0xFF) << 8);
        var nonce = sessionNonce(iv, ctr);
        if (nonce == null) {
            return null;
        }
        var body = value.slice(2, value.size());
        return AesCcm.decrypt(key, nonce, null, body);
    }

    //! The miauth key derivation, exactly as the reference client:
    //! HKDF-SHA256(ikm=token, salt=randKey || remoteKey,
    //! info="mible-login-info", 64 bytes) — the reference only uses the
    //! first 40 bytes: devKey = k[0..16], appKey = k[16..32],
    //! devIv = k[32..36], appIv = k[36..40].
    //! Returns [devKey, appKey, devIv, appIv] or null on bad input.
    static function deriveKeys(
        token as ByteArray,
        randKey as ByteArray,
        remoteKey as ByteArray
    ) as Array<ByteArray> or Null {
        var salt = Bytes.concat(randKey, remoteKey);
        var material = Hkdf.sha256(token, salt, loginInfo(), KEY_MATERIAL_BYTES);
        if (material == null) {
            return null;
        }
        return [
            material.slice(0, 16),
            material.slice(16, 32),
            material.slice(32, 36),
            material.slice(36, 40),
        ];
    }

    //! ASCII "mible-login-info" as bytes. Monkey C's Char.toNumber() only
    //! parses hex digits, and the info string contains others (m, l, o…),
    //! so the constant is pinned here rather than converted at runtime.
    private static function loginInfo() as ByteArray {
        return [
            0x6d, 0x69, 0x62, 0x6c, 0x65, 0x2d, 0x6c, 0x6f,
            0x67, 0x69, 0x6e, 0x2d, 0x69, 0x6e, 0x66, 0x6f,
        ]b;
    }
}
