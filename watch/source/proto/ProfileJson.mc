import Toybox.Lang;

//! The action 7.1 profile JSON (port of Profile.kt) — exact key order
//! verified against the reference client's captured app traffic. It sets
//! the scale's clock and drives user recognition; `wt` is the latest
//! weight in 1/100 kg. `unit` is always 1 (kg).
class ProfileJson {
    static function build(
        mid as String,
        age as Number,
        sex as Number,
        heightCm as Number,
        weightGrams as Number,
        nowUnix as Number
    ) as String {
        return "{\"mid\":\"" + mid + "\",\"duid\":1,\"uc\":1,\"ow\":1,\"unit\":1,\"time\":" + nowUnix.toString() +
            ",\"ud\":[{\"duid\":1,\"ut\":1,\"age\":" + age.toString() +
            ",\"sex\":" + sex.toString() +
            ",\"hi\":" + heightCm.toString() +
            ",\"wt\":" + weightGrams.toString() + "}]}";
    }
}
