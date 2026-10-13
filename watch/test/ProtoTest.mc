import Toybox.Lang;
import Toybox.Test;

//! Phase-2 protocol vector tests: the watch's MiotCodec / Framing /
//! ProfileJson / StoredRecords ports against the same byte fixtures the
//! Kotlin tests pin (MiotCodecTest, FramingTest, ScaleSessionChannelTest).
//! Every fixture below is transcribed from those committed test sources.

(:test)
function testMiotCodec(logger as Logger) as Boolean {
    // ---- MiotCodecTest.decodesPinnedLiveWeightEvent ----
    // `19 20 0f 00 07 05 03 00 03 ...` = 70.00 kg, not yet stable, from
    // faraga1/xiaomi-scale-s200-ble's protocol reference.
    var ev = MiotCodec.decode(hex("19200f00070503000301000110 0002000110000300023058 1b"));
    Test.assertMessage(ev != null, "live weight event should decode");
    if (ev == null) {
        return false;
    }
    Test.assertMessage(ev.tid == 0x000f, "event tid");
    Test.assertMessage(ev.op == MiotCodec.OP_EVENT, "event op");
    Test.assertMessage(ev.siid == 5, "event siid");
    Test.assertMessage(ev.iid == 3, "event eiid");
    Test.assertMessage((ev.params.get(1) as Number) == 0, "event param 1");
    Test.assertMessage((ev.params.get(2) as Number) == 0, "event param 2");
    Test.assertMessage((ev.params.get(3) as Number) == 7000, "event param 3 = 70.00 kg in grams");

    // ---- MiotCodecTest.roundTripsActionWithProfileJsonStringParam ----
    // The real use case: pushing the user profile (a ~150-byte JSON
    // string) via action 7.1.
    var json = ProfileJson.build("1", 30, 1, 180, 8025, 1790000000);
    var sp = MiotCodec.param(1, ParamType.STRING, json);
    Test.assertMessage(sp != null, "string param should build");
    var body = MiotCodec.action(7, 1, [sp as ByteArray]);
    var wire = MiotCodec.encode(0x0009, MiotCodec.OP_ACTION, body);
    var back = MiotCodec.decode(wire);
    Test.assertMessage(back != null, "profile action should round trip");
    if (back == null) {
        return false;
    }
    Test.assertMessage(back.tid == 0x0009, "round trip tid");
    Test.assertMessage(back.op == MiotCodec.OP_ACTION, "round trip op");
    Test.assertMessage(back.siid == 7, "round trip siid");
    Test.assertMessage(back.iid == 1, "round trip aiid");
    Test.assertMessage((back.params.get(1) as String).equals(json), "round trip json param intact");

    // ---- MiotCodecTest.encodesActionBodyWithConcreteBytes ----
    // action(7, 1, [param(1, U8, 5)]):
    //   len = 0x2000 | (5 + 3 + 5) = 0x200d, tid = 0x0009, op = 0x05,
    //   body = 07 01 01 | 0100 0110 05
    var u8p = MiotCodec.param(1, ParamType.U8, 5);
    Test.assertMessage(u8p != null, "u8 param should build");
    var concrete = MiotCodec.encode(0x0009, MiotCodec.OP_ACTION, MiotCodec.action(7, 1, [u8p as ByteArray]));
    Test.assertMessage(bytesEqual(concrete, hex("0d20 0900 05 07 01 01 0100 0110 05")), "concrete action bytes");

    // ---- MiotCodecTest.decodesResultWithZeroStatusAndParams ----
    var res = MiotCodec.decode(hex("0d20 0900 06 0000 01 0300 0110 0c"));
    Test.assertMessage(res != null, "result should decode");
    if (res == null) {
        return false;
    }
    Test.assertMessage(res.tid == 0x0009, "result tid");
    Test.assertMessage(res.op == MiotCodec.OP_RESULT, "result op");
    Test.assertMessage(res.status == 0, "result status");
    Test.assertMessage((res.params.get(3) as Number) == 12, "result param 3");

    // ---- MiotCodecTest.rejectsU64ParamWithUndersizedWireLength ----
    // n=1, piid 3, type_len 0x8000 (U64, wire len 0): a fixed 8-byte read
    // would run past the body — must be a clean rejection.
    Test.assertMessage(MiotCodec.decode(hex("0d20 0900 07 05 03 00 01 03 00 00 80")) == null, "U64 wire length 0 rejected");

    // ---- MiotCodecTest.paramPacksTypeInHighNibbleAndLengthInLowTwelveBits ----
    Test.assertMessage(bytesEqual(MiotCodec.param(1, ParamType.U8, 0), hex("0100 0110 00")), "U8 0 packing");
    Test.assertMessage(bytesEqual(MiotCodec.param(3, ParamType.U16, 7000), hex("0300 0230 581b")), "U16 7000 packing");
    // Monkey C has no 64-bit integer literal; synthesize the pinned value
    // from its fixture bytes, then re-encode it.
    var u64 = leLong(hex("7856341278563412"));
    var p64 = MiotCodec.param(4, ParamType.U64, u64);
    Test.assertMessage(p64 != null, "u64 param should build");
    Test.assertMessage(bytesEqual(p64 as ByteArray, hex("0400 0880 7856341278563412")), "U64 packing");
    Test.assertMessage(bytesEqual(MiotCodec.param(1, ParamType.STRING, "hi!"), hex("0100 03a0 686921")), "STRING packing");

    return true;
}

(:test)
function testFraming(logger as Logger) as Boolean {
    // ---- FramingTest pins ----
    Test.assertMessage(Framing.ACK_TIMEOUT_MS == 4000, "ack timeout 4000ms");

    // header: tag 0x0b, 1 parcel (the exact bytes FramingTest sees)
    Test.assertMessage(bytesEqual(Framing.header(0x0b, 1), hex("0000000b0100")), "header tag 0x0b count 1");
    // scale-side framed header of the 600-byte transfer: tag 0x0c, 3 parcels
    Test.assertMessage(bytesEqual(Framing.header(0x0c, 3), hex("0000000c0300")), "header tag 0x0c count 3");

    var data10 = hex("10111213141516171819");
    Test.assertMessage(bytesEqual(Framing.parcel(1, data10), hex("010010111213141516171819")), "parcel 1 of 1");

    var direct = Framing.direct(0x0d, hex("0102030405"));
    Test.assertMessage(bytesEqual(direct, hex("0000020d0102030405")), "direct frame bytes");

    // classify + parse
    Test.assertMessage(Framing.classify(direct) == Framing.KIND_DIRECT, "classify direct");
    var pd = Framing.parseDirect(direct);
    Test.assertMessage(pd != null, "parseDirect returns");
    if (pd != null) {
        Test.assertMessage((pd.get("tag") as Number) == 0x0d, "direct tag");
        Test.assertMessage(bytesEqual(pd.get("data") as ByteArray, hex("0102030405")), "direct data");
    }
    var hd = hex("0000000c0300");
    Test.assertMessage(Framing.classify(hd) == Framing.KIND_HEADER, "classify header");
    var ph = Framing.parseHeader(hd);
    Test.assertMessage(ph != null, "parseHeader returns");
    if (ph != null) {
        Test.assertMessage((ph.get("tag") as Number) == 0x0c, "header tag");
        Test.assertMessage((ph.get("count") as Number) == 3, "header count");
    }
    Test.assertMessage(Framing.classify(hex("00000101")) == Framing.KIND_RDY, "classify ready ack");
    Test.assertMessage(Framing.classify(hex("00000100")) == Framing.KIND_OK, "classify received ack");
    Test.assertMessage(Framing.classify(hex("00000300")) == Framing.KIND_ACK, "classify direct ack");
    Test.assertMessage(Framing.classify(hex("0000")) == Framing.KIND_OTHER, "short frame is other");
    Test.assertMessage(Framing.classify(hex("ffffffff")) == Framing.KIND_OTHER, "garbage is other");
    Test.assertMessage(Framing.parseDirect(hex("00000101")) == null, "parseDirect rejects non-direct");

    // ---- reassembly: the FramingTest 600-byte transfer, chunk 242,
    // parcels delivered OUT of order (the sortedMap contract) ----
    var big = []b;
    for (var i = 0; i < 600; i++) {
        big.add(i & 0xFF);
    }
    var r = new ParcelReassembly(3);
    Test.assertMessage(r.add(2, big.slice(242, 484)), "add parcel 2");
    Test.assertMessage(r.add(1, big.slice(0, 242)), "add parcel 1");
    Test.assertMessage(!r.isComplete(), "not complete before parcel 3");
    Test.assertMessage(r.data() == null, "no data before complete");
    Test.assertMessage(r.add(9, data10) == false, "out-of-range parcel rejected");
    Test.assertMessage(r.add(3, big.slice(484, 600)), "add parcel 3");
    Test.assertMessage(r.isComplete(), "complete after 3 parcels");
    var joined = r.data();
    Test.assertMessage(joined != null, "reassembled data");
    if (joined != null) {
        Test.assertMessage(bytesEqual(joined, big), "reassembled equals original 600 bytes");
    }

    return true;
}

(:test)
function testProfileJson(logger as Logger) as Boolean {
    // Profile.kt: exact key order verified against the reference client's
    // captured app traffic.
    var json = ProfileJson.build("1", 30, 1, 180, 8025, 1790000000);
    Test.assertMessage(
        json.equals("{\"mid\":\"1\",\"duid\":1,\"uc\":1,\"ow\":1,\"unit\":1,\"time\":1790000000,\"ud\":[{\"duid\":1,\"ut\":1,\"age\":30,\"sex\":1,\"hi\":180,\"wt\":8025}]}"),
        "profile JSON byte-exact");
    return true;
}

(:test)
function testStoredRecords(logger as Logger) as Boolean {
    // ScaleSessionChannelTest / FakeScale.DEFAULT_RECORDS: two pinned
    // records (the rest of the fixture is generator noise for size).
    var recs = StoredRecords.parse("41,1,1,8025,0,1790000000_42,0,0,7260,2,1790000060");
    Test.assertMessage(recs != null, "records parse");
    if (recs == null) {
        return false;
    }
    Test.assertMessage(recs.size() == 2, "two records");
    var r1 = recs[0] as StoredRecord;
    Test.assertMessage(r1.no.toString().equals("41"), "r1.no");
    Test.assertMessage(r1.mid.toString().equals("1"), "r1.mid");
    Test.assertMessage(r1.userType == 1, "r1.userType");
    Test.assertMessage(r1.weightGrams == 8025, "r1.weightGrams");
    Test.assertMessage(r1.flag == 0, "r1.flag");
    Test.assertMessage(r1.ts.toString().equals("1790000000"), "r1.ts");
    var r2 = recs[1] as StoredRecord;
    Test.assertMessage(r2.no.toString().equals("42"), "r2.no");
    Test.assertMessage(r2.mid.toString().equals("0"), "r2.mid");
    Test.assertMessage(r2.userType == 0, "r2.userType");
    Test.assertMessage(r2.weightGrams == 7260, "r2.weightGrams");
    Test.assertMessage(r2.flag == 2, "r2.flag");
    Test.assertMessage(r2.ts.toString().equals("1790000060"), "r2.ts");

    // empty string is no records, not an error
    var none = StoredRecords.parse("");
    Test.assertMessage(none != null && none.size() == 0, "empty string yields no records");

    // malformed records are rejected cleanly (Kotlin throws ProtocolError)
    Test.assertMessage(StoredRecords.parse("1,2,3,4,5") == null, "five fields rejected");
    Test.assertMessage(StoredRecords.parse("x,1,1,8025,0,1") == null, "non-numeric field rejected");
    Test.assertMessage(StoredRecords.parse("1,1,1,8025,0,1790000000_broken") == null, "bad second record rejected");

    return true;
}
