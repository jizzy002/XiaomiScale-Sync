import Toybox.Lang;
import Toybox.Application;

//! The Connect IQ app settings, validated with the Android app's
//! KeyFormat rules. `problem` names the first bad field ("TOKEN", ...)
//! for the main screen's "Check settings: <field>"; null when all valid.
//! Values never reach the log.
class AppConfig {
    var mac as String = "";
    var token as ByteArray = []b;
    var heightCm as Number = 180;
    var age as Number = 30;
    var sex as Number = 1;
    var memberId as String = "1";
    var ourMid as Number = 1;
    var problem as String or Null = null;

    function initialize() {
    }

    //! From the app's properties (Application.Properties).
    static function load() as AppConfig {
        var keys = ["mac", "token", "bleKey", "heightCm", "age", "sex", "memberId"];
        var d = {};
        for (var i = 0; i < keys.size(); i++) {
            var key = keys[i];
            try {
                d.put(key, Application.Properties.getValue(key));
            } catch (e) {
                d.put(key, null);
            }
        }
        return fromValues(d);
    }

    static function fromValues(d as Dictionary) as AppConfig {
        var c = new AppConfig();

        var macHex = alnum(str(d.get("mac")));
        if (macHex.length() != 12 || hexBytes(macHex) == null) {
            return c.bad("MAC");
        }
        c.mac = colonMac(macHex.toUpper());

        var token = hexBytes(str(d.get("token")));
        if (token == null || token.size() != 12) {
            return c.bad("TOKEN");
        }
        c.token = token;

        // The watch reads the scale by logging in with TOKEN; BLE KEY is
        // optional here (only the phone's broadcast path needs it), but a
        // value that is entered must be well-formed.
        var bleKey = str(d.get("bleKey"));
        if (bleKey.length() != 0) {
            var k = hexBytes(bleKey);
            if (k == null || k.size() != 16) {
                return c.bad("BLE KEY");
            }
        }

        var h = num(d.get("heightCm"));
        if (h == null || h < 50 || h > 250) {
            return c.bad("Height");
        }
        c.heightCm = h;
        var a = num(d.get("age"));
        if (a == null || a < 5 || a > 120) {
            return c.bad("Age");
        }
        c.age = a;
        var s = num(d.get("sex"));
        if (s == null || s < 0 || s > 2) {
            return c.bad("Sex");
        }
        c.sex = s;

        var mid = str(d.get("memberId"));
        var midNum = mid.length() > 0 && mid.length() <= 9 && isDigits(mid) ? mid.toNumber() : null;
        if (midNum == null || midNum == 0) {
            return c.bad("Member ID");
        }
        c.memberId = midNum.toString();
        c.ourMid = midNum;
        return c;
    }

    function bad(field as String) as AppConfig {
        problem = field;
        return self;
    }

    //! "AA:BB:CC:DD:EE:FF" (any separators) -> 6 bytes, big-endian.
    static function macBytes(mac as String) as ByteArray or Null {
        var b = hexBytes(alnum(mac));
        return b != null && b.size() == 6 ? b : null;
    }

    //! Hex string (even length, any case) -> bytes; null on anything else.
    static function hexBytes(s as String) as ByteArray or Null {
        var chars = s.toCharArray();
        if (chars.size() == 0 || chars.size() % 2 != 0) {
            return null;
        }
        var out = []b;
        for (var i = 0; i < chars.size(); i += 2) {
            var hi = nibble(chars[i].toNumber());
            var lo = nibble(chars[i + 1].toNumber());
            if (hi < 0 || lo < 0) {
                return null;
            }
            out.add((hi << 4) | lo);
        }
        return out;
    }

    //! UTF-32 code point of a hex digit -> 0..15, or -1.
    private static function nibble(cp as Number) as Number {
        if (cp >= 48 && cp <= 57) {
            return cp - 48;
        }
        if (cp >= 97 && cp <= 102) {
            return cp - 87;
        }
        if (cp >= 65 && cp <= 70) {
            return cp - 55;
        }
        return -1;
    }

    private static function str(v as Object or Null) as String {
        if (v == null) {
            return "";
        }
        if (v instanceof String) {
            return trim(v);
        }
        return v.toString();
    }

    private static function num(v as Object or Null) as Number or Null {
        if (v instanceof Number) {
            return v;
        }
        if (v instanceof String) {
            var t = trim(v);
            return t.length() > 0 && t.length() <= 9 && isDigits(t) ? t.toNumber() : null;
        }
        return null;
    }

    private static function isDigits(s as String) as Boolean {
        var chars = s.toCharArray();
        for (var i = 0; i < chars.size(); i++) {
            var cp = chars[i].toNumber();
            if (cp < 48 || cp > 57) {
                return false;
            }
        }
        return true;
    }

    //! Letters and digits only (drops ':' '-' and spaces from a MAC).
    private static function alnum(s as String) as String {
        var chars = s.toCharArray();
        var out = "";
        for (var i = 0; i < chars.size(); i++) {
            var cp = chars[i].toNumber();
            if ((cp >= 48 && cp <= 57) || (cp >= 65 && cp <= 90) || (cp >= 97 && cp <= 122)) {
                out += chars[i].toString();
            }
        }
        return out;
    }

    private static function trim(s as String) as String {
        var chars = s.toCharArray();
        var start = 0;
        var end = chars.size();
        while (start < end && chars[start].toNumber() <= 32) {
            start++;
        }
        while (end > start && chars[end - 1].toNumber() <= 32) {
            end--;
        }
        return s.substring(start, end) as String;
    }

    private static function colonMac(hex12 as String) as String {
        var out = hex12.substring(0, 2) as String;
        for (var i = 2; i < 12; i += 2) {
            out += ":" + hex12.substring(i, i + 2);
        }
        return out;
    }
}
