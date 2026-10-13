import Toybox.Lang;

//! One weigh-in as the watch shows and pushes it. `weight` is in 1/100 kg
//! (8025 = 80.25 kg), `ts` is unix seconds (fits a Number until 2038).
//! `mine` follows ArchiveMerger.categoryFor: flag 0 and our member id.
class WeighIn {
    var weight as Number;
    var ts as Number;
    var flag as Number;
    var mid as Number;
    var live as Boolean;
    var mine as Boolean = false;

    function initialize(weight as Number, ts as Number, flag as Number, mid as Number, live as Boolean) {
        self.weight = weight;
        self.ts = ts;
        self.flag = flag;
        self.mid = mid;
        self.live = live;
    }
}

//! Chooses the weigh-in the main screen shows, with the same plausibility
//! and timestamp sanity rules as SyncEngine.
class WeighInPicker {
    //! Weight plausibility, 1/100 kg: 1 kg .. 300 kg.
    static const MIN_WEIGHT100 = 100;
    static const MAX_WEIGHT100 = 30000;
    //! Sanity floor: 2024-01-01 00:00:00 UTC.
    static const MIN_PLAUSIBLE_TS = 1704067200;
    //! A scale clock may not run this far ahead of ours.
    static const MAX_FUTURE_SKEW_S = 300;

    static function plausible(weight as Number) as Boolean {
        return weight >= MIN_WEIGHT100 && weight <= MAX_WEIGHT100;
    }

    static function isMine(flag as Number, mid as Number, ourMid as Number) as Boolean {
        return flag == 0 && mid != 0 && mid == ourMid;
    }

    //! The live commit when there is one (it is this session's weighing);
    //! otherwise the newest plausible stored record that is ours, falling
    //! back to the newest plausible record of anyone (shown as not mine).
    static function latest(
        records as Array<StoredRecord> or Null,
        live as WeighIn or Null,
        ourMid as Number,
        nowUnix as Number
    ) as WeighIn or Null {
        if (live != null) {
            live.mine = isMine(live.flag, live.mid, ourMid);
            return live;
        }
        if (records == null) {
            return null;
        }
        var best = null;
        var bestAny = null;
        for (var i = 0; i < records.size(); i++) {
            var r = records[i];
            if (!plausible(r.weightGrams)) {
                continue;
            }
            var w = new WeighIn(r.weightGrams, saneTs(r.ts, nowUnix), r.flag, r.mid.toNumber(), false);
            w.mine = isMine(w.flag, w.mid, ourMid);
            if (bestAny == null || w.ts >= bestAny.ts) {
                bestAny = w;
            }
            if (w.mine && (best == null || w.ts >= best.ts)) {
                best = w;
            }
        }
        return best != null ? best : bestAny;
    }

    //! The scale's ts, unless it predates 2024 or runs ahead of us.
    static function saneTs(ts as Long, nowUnix as Number) as Number {
        if (ts < MIN_PLAUSIBLE_TS || ts > nowUnix + MAX_FUTURE_SKEW_S) {
            return nowUnix;
        }
        return ts.toNumber();
    }
}
