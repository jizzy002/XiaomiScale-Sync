#!/usr/bin/env python3
"""Generate the committed AES-CCM / HKDF / MiBeacon test-vector fixtures.

Emits JSON arrays of fixtures consumed by the Kotlin unit tests (the tests
read the JSON; they never call Python).

Default (crypto_vectors.json), fixtures:
  ccm_no_aad        AES-CCM (tag=4), 13-byte nonce, no AAD
  ccm_aad           AES-CCM (tag=4), spec-style session nonce
                    iv(4) || 00000000(4) || ctr_le32(4) (12 bytes), with AAD
  session_enc       AES-CCM (tag=4), spec-style session nonce, no AAD —
                    the exact post-login channel layout: the value is
                    ctr_le16 || ciphertext||tag; the CTR in the frame is
                    the same number used in the nonce
  hkdf_login        HKDF-SHA256, info=b"mible-login-info", L=64
  hkdf_rfc5869_tc1  RFC 5869 Appendix A.1 Test Case 1 (independent KAT)

With --beacon (beacon_vectors.json), fixtures:
  beacon_weigh_in   MiBeacon v5 encrypted 0x4e16 weigh-in frame
                    (fc 0x5948, no MAC field — nonce uses the scale's own)
  beacon_status     MiBeacon v5 encrypted 0x6623 status frame
                    (fc 0x5958, MAC field included)
  beacon_solicit    MiBeacon v5 unencrypted "connect to me" frame
                    (fc 0x5b10, MAC field, no object)

All inputs are fixed dummy (non-secret) bytes so regeneration is
reproducible; the ccm_no_aad nonce is a random value pinned here for the
same reason.

Usage:
    python scripts/gen_vectors.py > app/src/test/resources/crypto_vectors.json
    python scripts/gen_vectors.py --beacon > app/src/test/resources/beacon_vectors.json
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

# --- session_enc: post-login session channel encryption ---------------------
# The layout ScaleSession uses on PROP: value = ctr_le16 || (ciphertext||
# tag) where the CTR in the frame is the same number as the nonce's ctr_le32.
# The plaintext below is a real MIoT message: an OP_HELLO (0xf0) for tid 1
# (`len=0x0005|0x2000`, `tid=1`, `op=0xf0`, no body).

session_enc_key = bytes.fromhex("0f0e0d0c0b0a09080706050403020100")
session_enc_iv = bytes.fromhex("01020304")
session_enc_ctr = 0x0102  # 258: big enough that le32 differs from be32
session_enc_nonce = (
    session_enc_iv + b"\x00\x00\x00\x00" + session_enc_ctr.to_bytes(4, "little")
)
assert len(session_enc_nonce) == 12
session_enc_plaintext = bytes.fromhex("05200100f0")
session_enc_ciphertext = AESCCM(session_enc_key, tag_length=TAG_LENGTH).encrypt(
    session_enc_nonce, session_enc_plaintext, None
)
fixtures.append(
    {
        "name": "session_enc",
        "type": "ccm",
        "key": hx(session_enc_key),
        "nonce": hx(session_enc_nonce),
        "aad": None,
        "plaintext": hx(session_enc_plaintext),
        "ciphertext": hx(session_enc_ciphertext),
        "iv": hx(session_enc_iv),
        "ctr": session_enc_ctr,
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

# --- beacon fixtures: MiBeacon v5 advertisement decode ----------------------
# Full advertisement builder mirroring `ble/Beacon.kt`'s decode rules:
#   service data = fc_le16 || product_le16 || counter(1)
#                  || [mac_le(6) if fc & 0x10] || [capability(2) if fc & 0x20]
#                  || payload
#   encrypted (fc & 0x08): payload = ct || ext_counter(3) || tag(4) with
#   nonce = mac_le(6) || product_le(2, as it appears) || counter(1)
#           || ext_counter(3)  and AAD = the single byte 0x11.

BEACON_MAC = "58:2D:34:AA:BB:CC"  # dummy scale MAC (display order)
BEACON_BIND_KEY = bytes.fromhex("00112233445566778899aabbccddeeff")
BEACON_PRODUCT = 0x4DCB  # the S200's product id
BEACON_AAD = b"\x11"

FC_ENCRYPTED = 0x08
FC_MAC = 0x10
FC_CAPABILITY = 0x20
FC_SOLICIT = 0x0200

MAC_LE = bytes.fromhex(BEACON_MAC.replace(":", ""))[::-1]


def build_advertisement(
    frame_control: int,
    frame_counter: int,
    ext_counter: int,
    plaintext: bytes,
) -> bytes:
    """Builds MiBeacon v5 service data for one frame.

    `plaintext` is the decrypted object stream for encrypted frames, or the
    raw payload for unencrypted ones (empty for a plain "connect to me").
    """
    out = (
        frame_control.to_bytes(2, "little")
        + BEACON_PRODUCT.to_bytes(2, "little")
        + bytes([frame_counter])
    )
    if frame_control & FC_MAC:
        out += MAC_LE
    if frame_control & FC_CAPABILITY:
        out += b"\x00\x00"  # capability field (2 bytes; never set by this scale)
    if frame_control & FC_ENCRYPTED:
        nonce = (
            MAC_LE  # the scale's MAC, little-endian
            + BEACON_PRODUCT.to_bytes(2, "little")  # product id, as it appears
            + bytes([frame_counter])
            + ext_counter.to_bytes(3, "little")
        )
        assert len(nonce) == 12
        ct_and_tag = AESCCM(BEACON_BIND_KEY, tag_length=TAG_LENGTH).encrypt(
            nonce, plaintext, BEACON_AAD
        )
        out += ct_and_tag[:-TAG_LENGTH] + ext_counter.to_bytes(3, "little") + ct_and_tag[-TAG_LENGTH:]
    else:
        out += plaintext
    return out


def parse_object_stream(plain: bytes):
    """Parses `<id:le16><len:u8><value>` objects; stops at a truncated one."""
    objects, i = [], 0
    while i + 3 <= len(plain):
        obj_id = int.from_bytes(plain[i : i + 2], "little")
        length = plain[i + 2]
        if i + 3 + length > len(plain):
            break
        objects.append((obj_id, plain[i + 3 : i + 3 + length]))
        i += 3 + length
    return objects


beacon_fixtures = []


def beacon_fixture(name, frame_control, frame_counter, ext_counter, plaintext, weigh_in=None):
    encrypted = bool(frame_control & FC_ENCRYPTED)
    service_data = build_advertisement(frame_control, frame_counter, ext_counter, plaintext)
    fixture = {
        "name": name,
        "type": "beacon",
        "mac": BEACON_MAC,
        "bindKey": hx(BEACON_BIND_KEY),
        "serviceData": hx(service_data),
        "frameControl": frame_control,
        "frameCounter": frame_counter,
        "solicit": bool(frame_control & FC_SOLICIT),
        "eventKey": frame_counter * 2**24 + (ext_counter if encrypted else 0),
        "objects": [
            {"id": obj_id, "value": hx(value)}
            for obj_id, value in parse_object_stream(plaintext)
        ],
    }
    if weigh_in is not None:
        fixture["weighIn"] = weigh_in
    beacon_fixtures.append(fixture)


# beacon_weigh_in: the pinned weigh-in example from the protocol reference —
# object value `01 2f1c0000 803bb16a` = profile 1, 72.15 kg (weight field in
# 1/100 kg), 2026-09-21 14:13:20 UTC. `weightGrams` in the fixture carries the
# raw 1/100 kg value, matching `WeighInObjects.parse`'s pinned output.
beacon_fixture(
    name="beacon_weigh_in",
    frame_control=0x5948,  # encrypted + object, no MAC field
    frame_counter=42,
    ext_counter=0x010203,  # big enough that byte order matters
    plaintext=bytes.fromhex("164e09") + bytes.fromhex("012f1c0000803bb16a"),
    weigh_in={"profileId": 1, "weightGrams": 7215, "unixTs": 0x6AB13B80},
)

# beacon_status: the ~50-minute status broadcast, MAC field included.
beacon_fixture(
    name="beacon_status",
    frame_control=0x5958,  # encrypted + MAC + object
    frame_counter=7,
    ext_counter=0x000000,
    plaintext=bytes.fromhex("23660100"),  # object 0x6623, 1 byte 0x00
)

# beacon_solicit: unencrypted "connect to me" frame — MAC field, no object.
beacon_fixture(
    name="beacon_solicit",
    frame_control=0x5B10,  # bit 9 set, MAC included, not encrypted
    frame_counter=17,
    ext_counter=0,
    plaintext=b"",
)

json.dump(beacon_fixtures if "--beacon" in sys.argv else fixtures, sys.stdout, indent=2)
sys.stdout.write("\n")
