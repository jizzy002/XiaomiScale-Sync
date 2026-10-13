import Toybox.Lang;

//! Pure frame layer of Framing.kt: builders, classification and parcel
//! reassembly for values on the scale's GATT characteristics. The
//! transport/ack-waiting half lives in the phase-3 BLE session, which
//! drives these helpers from its callbacks.
//!
//!   direct: 00 00 02 <tag> <data>        (scale acks with RCV_ACK)
//!   header: 00 00 00 <tag> <count:le16>  (then RCV_RDY, parcels, RCV_OK)
//!   parcel: <no:le16> <chunk>            (no counts from 1)
class Framing {
    static const RCV_RDY = "00000101";
    static const RCV_OK = "00000100";
    static const RCV_ACK = "00000300";

    static const ACK_TIMEOUT_MS = 4000;

    static const KIND_OTHER = 0;
    static const KIND_DIRECT = 1;
    static const KIND_HEADER = 2;
    static const KIND_RDY = 3;
    static const KIND_OK = 4;
    static const KIND_ACK = 5;

    static function rdyBytes() as ByteArray {
        return [0x00, 0x00, 0x01, 0x01]b;
    }

    static function okBytes() as ByteArray {
        return [0x00, 0x00, 0x01, 0x00]b;
    }

    static function ackBytes() as ByteArray {
        return [0x00, 0x00, 0x03, 0x00]b;
    }

    static function header(tag as Number, count as Number) as ByteArray {
        return [0x00, 0x00, 0x00, tag & 0xFF, count & 0xFF, (count >> 8) & 0xFF]b;
    }

    static function parcel(no as Number, chunk as ByteArray) as ByteArray {
        var out = [no & 0xFF, (no >> 8) & 0xFF]b;
        out.addAll(chunk);
        return out;
    }

    static function direct(tag as Number, data as ByteArray) as ByteArray {
        var out = [0x00, 0x00, 0x02, tag & 0xFF]b;
        out.addAll(data);
        return out;
    }

    static function classify(frame as ByteArray) as Number {
        var n = frame.size();
        if (n >= 4 && frame[0] == 0 && frame[1] == 0 && frame[2] == 0x02) {
            return KIND_DIRECT;
        }
        if (n == 6 && frame[0] == 0 && frame[1] == 0 && frame[2] == 0) {
            return KIND_HEADER;
        }
        if (Bytes.equalsConstantTime(frame, rdyBytes())) {
            return KIND_RDY;
        }
        if (Bytes.equalsConstantTime(frame, okBytes())) {
            return KIND_OK;
        }
        if (Bytes.equalsConstantTime(frame, ackBytes())) {
            return KIND_ACK;
        }
        return KIND_OTHER;
    }

    //! {"tag" => Number, "data" => ByteArray}, or null if not a direct frame.
    static function parseDirect(frame as ByteArray) as Dictionary or Null {
        if (classify(frame) != KIND_DIRECT) {
            return null;
        }
        return { "tag" => frame[3] & 0xFF, "data" => frame.slice(4, frame.size()) };
    }

    //! {"tag" => Number, "count" => Number}, or null if not a header frame.
    static function parseHeader(frame as ByteArray) as Dictionary or Null {
        if (classify(frame) != KIND_HEADER) {
            return null;
        }
        return { "tag" => frame[3] & 0xFF, "count" => (frame[4] & 0xFF) | ((frame[5] & 0xFF) << 8) };
    }
}

//! Collects the parcels of one multi-parcel value. Parcels may arrive in
//! any order (Kotlin keeps a sortedMap); a repeated parcel number
//! overwrites rather than double-counting.
class ParcelReassembly {
    private var _count as Number;
    private var _parts as Dictionary<Number, ByteArray> = {};

    function initialize(count as Number) {
        _count = count;
    }

    //! False if `no` is outside 1..count.
    function add(no as Number, chunk as ByteArray) as Boolean {
        if (no < 1 || no > _count) {
            return false;
        }
        _parts.put(no, chunk);
        return true;
    }

    function isComplete() as Boolean {
        return _parts.size() == _count;
    }

    //! The joined value, or null until every parcel has arrived.
    function data() as ByteArray or Null {
        if (!isComplete()) {
            return null;
        }
        var out = []b;
        for (var no = 1; no <= _count; no++) {
            out.addAll(_parts.get(no) as ByteArray);
        }
        return out;
    }
}
