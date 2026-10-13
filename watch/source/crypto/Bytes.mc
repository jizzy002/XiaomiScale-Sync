import Toybox.Lang;

//! Small ByteArray helpers shared by the crypto classes. ByteArrays are
//! built with the `[]b` literal plus add/addAll; Monkey C has no
//! Array.toByteArray(). Every helper returns a fresh ByteArray.
class Bytes {
    static function concat(a as ByteArray, b as ByteArray) as ByteArray {
        var out = []b;
        out.addAll(a);
        out.addAll(b);
        return out;
    }

    //! Empty ByteArray of `size` zero bytes.
    static function zeroFill(size as Number) as ByteArray {
        var out = []b;
        for (var i = 0; i < size; i++) {
            out.add(0);
        }
        return out;
    }

    static function equalsConstantTime(a as ByteArray, b as ByteArray) as Boolean {
        if (a.size() != b.size()) {
            return false;
        }
        var diff = 0;
        for (var i = 0; i < a.size(); i++) {
            diff = diff | (a[i] ^ b[i]);
        }
        return diff == 0;
    }
}
