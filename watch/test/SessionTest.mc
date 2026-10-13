import Toybox.Lang;
import Toybox.Test;

//! Phase-3 session tests: the watch's callback-driven ScaleSession against
//! the FakeScale loopback (port of the Android ScaleSession/SyncEngine
//! loopback tests). The token is a made-up test value.

function testToken() as ByteArray {
    return hex("0102030405060708090a0b0c");
}

function newSession(scale as FakeScale, listener as RecordingListener) as ScaleSession {
    return new ScaleSession(scale, listener, testToken(), "1", 30, 1, 180, 8000);
}

(:test)
function testSessionFullSync(logger as Logger) as Boolean {
    var scale = new FakeScale(testToken());
    scale.liveStable = 8100;
    scale.finishedPayload = "1,1,8100,0,1790000400";
    var listener = new RecordingListener();
    var s = newSession(scale, listener);
    scale.run(s);

    Test.assertMessage(scale.errors.size() == 0, "fake scale errors: " + scale.errors.toString());
    // Reference order: AUTH, CMD, PROP, probe, ~200 ms pause, LOGIN.
    Test.assertMessage(scale.enableOrder.toString().equals("[1, 2, 3, 0]"), "enable order " + scale.enableOrder.toString());
    Test.assertMessage(scale.pausedBeforeLogin, "LOGIN enabled only after the post-probe pause");
    Test.assertMessage(scale.oversizeWrites == 0, "no write over 20 bytes");
    Test.assertMessage(s.maxValue == 16, "maxValue from the big probe");
    Test.assertMessage(scale.loginCount == 1, "one login");
    Test.assertMessage(scale.actions.toString().equals("[7.1, 6.1]"), "actions " + scale.actions.toString());

    var recs = s.storedRecords;
    Test.assertMessage(recs != null && recs.size() == 2, "stored records delivered");
    var live = s.liveCommit;
    Test.assertMessage(live != null, "5.4 committed");
    if (live != null) {
        Test.assertMessage(live.weight == 8100, "live weight");
        Test.assertMessage(live.flag == 0 && live.mid == 1, "live attribution from 5.4");
        Test.assertMessage(live.ts == 1790000500, "live ts = commit time");
    }
    Test.assertMessage(listener.lives.toString().equals("[8100]"), "live reading reported");
    Test.assertMessage(listener.doneCount == 1 && listener.doneError == ScaleSession.ERR_NONE, "done OK");
    Test.assertMessage(scale.closed, "link closed at the end");
    return true;
}

(:test)
function testSessionWrongToken(logger as Logger) as Boolean {
    // The scale holds a different token: remote_info fails the HMAC check,
    // so no login_info is sent and the session reports a key error.
    var scale = new FakeScale(hex("ffeeddccbbaa998877665544"));
    var listener = new RecordingListener();
    var s = newSession(scale, listener);
    scale.run(s);
    Test.assertMessage(listener.doneError == ScaleSession.ERR_AUTH, "wrong token -> ERR_AUTH, got " + listener.doneError.toString());
    Test.assertMessage(scale.loginCount == 0, "no login completed");
    Test.assertMessage(scale.actions.size() == 0, "no actions after a failed login");
    Test.assertMessage(scale.closed, "link closed on failure");
    return true;
}

(:test)
function testSessionActionTimeout(logger as Logger) as Boolean {
    var scale = new FakeScale(testToken());
    scale.dropActionResults = true;
    var listener = new RecordingListener();
    var s = newSession(scale, listener);
    scale.run(s);
    Test.assertMessage(scale.actions.toString().equals("[7.1]"), "7.1 sent once, then silence");
    Test.assertMessage(listener.doneError == ScaleSession.ERR_TIMEOUT, "silence -> ERR_TIMEOUT");
    Test.assertMessage(scale.firedTimers.indexOf(ScaleSession.RESULT_TIMEOUT_MS) >= 0, "result timeout fired");
    Test.assertMessage(scale.closed, "link closed on timeout");
    return true;
}

(:test)
function testSessionStoredOnly(logger as Logger) as Boolean {
    // No live events: the listen ends after the start window and the
    // session still finishes cleanly with the stored archive.
    var scale = new FakeScale(testToken());
    var listener = new RecordingListener();
    var s = newSession(scale, listener);
    scale.run(s);
    Test.assertMessage(scale.errors.size() == 0, "fake scale errors: " + scale.errors.toString());
    Test.assertMessage(listener.doneError == ScaleSession.ERR_NONE, "done OK");
    Test.assertMessage(s.liveCommit == null, "no live commit");
    Test.assertMessage(scale.firedTimers.indexOf(ScaleSession.LIVE_START_MS) >= 0, "live start window elapsed");

    var latest = WeighInPicker.latest(s.storedRecords, s.liveCommit, 1, 1790000500);
    Test.assertMessage(latest != null, "a stored weigh-in is picked");
    if (latest != null) {
        // Record 42 is newer but flag 2 / mid 0 (ambiguous); 41 is ours.
        Test.assertMessage(latest.weight == 8025 && latest.ts == 1790000000, "latest MINE stored record");
        Test.assertMessage(latest.mine, "picked record is mine");
    }
    return true;
}

(:test)
function testWeighInPicker(logger as Logger) as Boolean {
    var recs = StoredRecords.parse("41,1,1,8025,0,1790000000_42,1,1,8030,0,1790000060_43,1,1,50,0,1790000120");
    // 43 is implausible (0.5 kg) and dropped; 42 is the newest MINE.
    var p = WeighInPicker.latest(recs, null, 1, 1790000500);
    Test.assertMessage(p != null && p.weight == 8030, "newest plausible mine");
    // A scale clock before 2024 is replaced by the receive time.
    var old = StoredRecords.parse("7,1,1,8000,0,1000");
    var q = WeighInPicker.latest(old, null, 1, 1790000500);
    Test.assertMessage(q != null && q.ts == 1790000500, "insane ts -> receive time");
    // A live commit always wins over stored records.
    var live = new WeighIn(8200, 1790000400, 0, 1, true);
    var r = WeighInPicker.latest(recs, live, 1, 1790000500);
    Test.assertMessage(r != null && r.weight == 8200 && r.live, "live wins");
    // Only foreign records: still shown, but not mine.
    var guest = StoredRecords.parse("9,2,1,6000,0,1790000000");
    var g = WeighInPicker.latest(guest, null, 1, 1790000500);
    Test.assertMessage(g != null && !g.mine, "foreign record is not mine");
    Test.assertMessage(WeighInPicker.latest(null, null, 1, 0) == null, "nothing -> null");
    return true;
}
