#!/usr/bin/env python3
"""Generate the committed AES-CCM / HKDF test-vector fixtures.

Emits a JSON array of fixtures consumed by the Kotlin unit tests in
`app/src/test/java/com/xiaomiscalesync/crypto/` (the tests read the JSON;
they never call Python).

Fixtures:
  ccm_no_aad        AES-CCM (tag=4), 13-byte nonce, no AAD
  ccm_aad           AES-CCM (tag=4), spec-style session nonce
                    iv(4) || 00000000(4) || ctr_le32(4) (12 bytes), with AAD
  hkdf_login        HKDF-SHA256, info=b"mible-login-info", L=64
  hkdf_rfc5869_tc1  RFC 5869 Appendix A.1 Test Case 1 (independent KAT)

All inputs are fixed dummy (non-secret) bytes so regeneration is
reproducible; the ccm_no_aad nonce is a random value pinned here for the
same reason.

Usage:
    python scripts/gen_vectors.py > app/src/test/resources/crypto_vectors.json
"""

import json
import sys

from cryptography.hazmat.primitives.ciphers.aead import AESCCM
from cryptography.hazmat.primitives.hashes import SHA256
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

TAG_LENGTH = 4

fixtures = []


def hx(data: bytes) -> str:
    return data.hex()


# --- ccm_no_aad: 13-byte nonce, AAD = null ----------------------------------

ccm_key = bytes.fromhex("00112233445566778899aabbccddeeff")
ccm_no_aad_nonce = bytes.fromhex("5a17c3e90b84d26f1a75e04c93")  # random, pinned
assert len(ccm_no_aad_nonce) == 13
ccm_no_aad_plaintext = b"mi.scale.v2 frame: 70.55 kg"
ccm_no_aad_ciphertext = AESCCM(ccm_key, tag_length=TAG_LENGTH).encrypt(
    ccm_no_aad_nonce, ccm_no_aad_plaintext, None
)
fixtures.append(
    {
        "name": "ccm_no_aad",
        "type": "ccm",
        "key": hx(ccm_key),
        "nonce": hx(ccm_no_aad_nonce),
        "aad": None,
        "plaintext": hx(ccm_no_aad_plaintext),
        "ciphertext": hx(ccm_no_aad_ciphertext),
    }
)

# --- ccm_aad: spec-style session nonce, non-null AAD ------------------------

session_iv = bytes.fromhex("01020304")  # 4-byte session iv prefix (dummy)
session_ctr = 1
session_nonce = (
    session_iv
    + b"\x00\x00\x00\x00"
    + session_ctr.to_bytes(4, "little")  # ctr_le32
)
assert len(session_nonce) == 12
ccm_aad_bytes = bytes.fromhex("11")
ccm_aad_plaintext = bytes.fromhex("01020304fffe807f00112233445566778899aabbcc")
ccm_aad_ciphertext = AESCCM(ccm_key, tag_length=TAG_LENGTH).encrypt(
    session_nonce, ccm_aad_plaintext, ccm_aad_bytes
)
fixtures.append(
    {
        "name": "ccm_aad",
        "type": "ccm",
        "key": hx(ccm_key),
        "nonce": hx(session_nonce),
        "aad": hx(ccm_aad_bytes),
        "plaintext": hx(ccm_aad_plaintext),
        "ciphertext": hx(ccm_aad_ciphertext),
    }
)

# --- hkdf_login: mible-login-info, 64-byte output ---------------------------

login_ikm = bytes.fromhex("000102030405060708090a0b0c0d0e0f")  # 16 dummy bytes
login_salt = bytes(range(0x10, 0x30))  # 32 dummy bytes
login_info = b"mible-login-info"
login_output = HKDF(
    algorithm=SHA256(), length=64, salt=login_salt, info=login_info
).derive(login_ikm)
assert len(login_output) == 64
fixtures.append(
    {
        "name": "hkdf_login",
        "type": "hkdf",
        "ikm": hx(login_ikm),
        "salt": hx(login_salt),
        "info": hx(login_info),
        "length": 64,
        "output": hx(login_output),
    }
)

# --- hkdf_rfc5869_tc1: RFC 5869 Appendix A.1 known answer --------------------

rfc_ikm = bytes.fromhex("0b" * 22)
rfc_salt = bytes.fromhex("000102030405060708090a0b0c")
rfc_info = bytes.fromhex("f0f1f2f3f4f5f6f7f8f9")
rfc_okm = HKDF(algorithm=SHA256(), length=42, salt=rfc_salt, info=rfc_info).derive(
    rfc_ikm
)
assert rfc_okm.hex() == (
    "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf"
    "34007208d5b887185865"
), f"RFC 5869 TC1 mismatch: {rfc_okm.hex()}"
fixtures.append(
    {
        "name": "hkdf_rfc5869_tc1",
        "type": "hkdf",
        "ikm": hx(rfc_ikm),
        "salt": hx(rfc_salt),
        "info": hx(rfc_info),
        "length": 42,
        "output": hx(rfc_okm),
    }
)

json.dump(fixtures, sys.stdout, indent=2)
sys.stdout.write("\n")
