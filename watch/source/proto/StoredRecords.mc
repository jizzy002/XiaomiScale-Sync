import Toybox.Lang;

//! One stored weigh-in, as the scale's event 6.1 records it.
class StoredRecord {
    var no as Long;
    var mid as Long;
    var userType as Number;
    var weightGrams as Number;
    var flag as Number;
    var ts as Long;

    function initialize(no as Long, mid as Long, userType as Number, weightGrams as Number, flag as Number, ts as Long) {
        self.no = no;
        self.mid = mid;
        self.userType = userType;
        self.weightGrams = weightGrams;
        self.flag = flag;
        self.ts = ts;
    }
}

//! The records string of event 6.1: `no,mid,user_type,weight_g,flag,unix_ts`
//! records joined by `_`, e.g. `41,1,1,8025,0,1790000000_42,0,0,7260,2,1790000060`.
//!
//! Divergence from Kotlin: a malformed record makes parse() return null
//! instead of throwing. Fields are validated character by character
//! first, because String.toLong() silently ignores trailing garbage.
class StoredRecords {
    private static const FIELDS = 6;
    private static const INT_MAX = 2147483647l;

    //! Empty string -> empty array; any bad record -> null.
    static function parse(text as String) as Array<StoredRecord> or Null {
        var out = [] as Array<StoredRecord>;
        if (text.length() == 0) {
            return out;
        }
        var records = split(text, "_");
        for (var i = 0; i < records.size(); i++) {
            var rec = parseRecord(records[i]);
            if (rec == null) {
                return null;
            }
            out.add(rec);
        }
        return out;
    }

    private static function parseRecord(record as String) as StoredRecord or Null {
        var f = split(record, ",");
        if (f.size() != FIELDS) {
            return null;
        }
        var v = [] as Array<Long>;
        for (var i = 0; i < FIELDS; i++) {
            var n = parseLong(f[i]);
            if (n == null) {
                return null;
            }
            // userType, weightGrams, flag must fit a non-negative Int.
            if ((i == 2 || i == 3 || i == 4) && (n < 0 || n > INT_MAX)) {
                return null;
            }
            v.add(n);
        }
        return new StoredRecord(v[0], v[1], v[2].toNumber(), v[3].toNumber(), v[4].toNumber(), v[5]);
    }

    //! Kotlin `trim().toLongOrNull()` parity: optional sign, then one or
    //! more ASCII digits, surrounding whitespace ignored.
    private static function parseLong(field as String) as Long or Null {
        var chars = field.toCharArray();
        var start = 0;
        var end = chars.size();
        while (start < end && isSpace(chars[start].toNumber())) {
            start++;
        }
        while (end > start && isSpace(chars[end - 1].toNumber())) {
            end--;
        }
        var digitsFrom = start;
        if (digitsFrom < end) {
            var c = chars[digitsFrom].toNumber();
            if (c == 43 || c == 45) { // '+' or '-'
                digitsFrom++;
            }
        }
        if (digitsFrom >= end || end - digitsFrom > 18) {
            return null; // empty, or beyond what fits a Long without overflow checks
        }
        for (var i = digitsFrom; i < end; i++) {
            var d = chars[i].toNumber();
            if (d < 48 || d > 57) {
                return null;
            }
        }
        return field.substring(start, end).toLong();
    }

    private static function isSpace(cp as Number) as Boolean {
        return cp == 32 || cp == 9 || cp == 10 || cp == 13;
    }

    //! String has no split(); String.find() searches from the start, so
    //! always search the remainder.
    private static function split(s as String, sep as String) as Array<String> {
        var parts = [] as Array<String>;
        var rest = s;
        while (true) {
            var at = rest.find(sep);
            if (at == null) {
                parts.add(rest);
                return parts;
            }
            parts.add(rest.substring(0, at));
            rest = rest.substring(at + sep.length(), rest.length());
        }
        return parts;
    }
}
