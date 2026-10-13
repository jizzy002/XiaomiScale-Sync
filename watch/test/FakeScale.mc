import Toybox.Lang;

//! Test double for the scale, ported from the Android test fixture
//! FakeScale.kt: it is the session's SessionHost (writes, notification
//! enables, timers) and answers like the S200 — the a4 probe, the miauth
//! login, framed/direct values in both directions, actions 7.1 / 6.1 and
//! scripted live events. Nothing is delivered re-entrantly: writes are
//! queued and pump() moves frames both ways, firing the pending timer
//! only once both queues are idle (time passing).
//!
//! The token here is a made-up test value, never a real scale's key.
class FakeScale extends SessionHost {
    // ---- configuration ----
    var token as ByteArray;
    var maxValue as Number = 16; // what a 23-byte ATT MTU allows: 4 + 16 = 20
    var records as String = "41,1,1,8025,0,1790000000_42,0,0,7260,2,1790000060";
    var dropActionResults as Boolean = false;
    //! 5.3 weight (1/100 kg, stable) emitted when 6.1 arrives, before its result.
    var liveStable as Number or Null = null;
    //! 5.4 payload `mid,user_type,weight,flag,ts` emitted after liveStable.
    var finishedPayload as String or Null = null;
    var now as Number = 1790000500;

    // ---- observations ----
    var enableOrder as Array<Number> = [] as Array<Number>;
    var actions as Array<String> = [] as Array<String>;
    var errors as Array<String> = [] as Array<String>;
    var firedTimers as Array<Number> = [] as Array<Number>;
    var oversizeWrites as Number = 0;
    var loginCount as Number = 0;
    var closed as Boolean = false;
    var pausedBeforeLogin as Boolean = false;

    // ---- loopback state ----
    var session as ScaleSession or Null = null;
    private var _toScale as Array<Array> = [] as Array<Array>;
    private var _toApp as Array<Array> = [] as Array<Array>;
    private var _timerMs as Number or Null = null;

    private var _stage as Number = 0; // 0 idle, 1 echo 0500, 2 echo 0501, 3 probed, 4 login
    private var _probeDone as Boolean = false;
    private var _pauseFired as Boolean = false;

    private var _randKey as ByteArray or Null = null;
    private var _keys as Array<ByteArray> or Null = null;
    private var _devCtr as Number = 0;
    private var _eventTid as Number = 0x7000;

    // outbound (scale -> app) values: [ch, tag, data]
    private var _out as Array<Array> = [] as Array<Array>;
    private var _txCh as Number = -1;
    private var _txStage as Number = 0; // 0 none, 1 wait RDY, 2 wait OK, 3 wait ACK
    private var _txParcels as Array<ByteArray> = [] as Array<ByteArray>;

    // inbound framed value
    private var _rx as ParcelReassembly or Null = null;
    private var _rxCh as Number = -1;
    private var _rxTag as Number = 0;

    private static const REMOTE_KEY_SEED = 0x51;

    function initialize(token as ByteArray) {
        SessionHost.initialize();
        self.token = token;
    }

    // ---------- SessionHost ----------

    function write(ch as Number, bytes as ByteArray) as Void {
        _toScale.add([ch, bytes]);
    }

    function enableNotifications(ch as Number) as Void {
        enableOrder.add(ch);
        if (ch == ScaleSession.CH_LOGIN) {
            pausedBeforeLogin = _probeDone && _pauseFired;
        }
    }

    function close() as Void {
        closed = true;
    }

    function schedule(ms as Number) as Void {
        _timerMs = ms;
    }

    function cancelTimer() as Void {
        _timerMs = null;
    }

    function nowUnix() as Number {
        return now;
    }

    // ---------- loopback ----------

    function run(s as ScaleSession) as Void {
        session = s;
        s.start();
        pump();
    }

    function pump() as Void {
        var s = session as ScaleSession;
        for (var guard = 0; guard < 20000; guard++) {
            if (_toScale.size() > 0) {
                var w = _toScale[0];
                _toScale = _toScale.slice(1, _toScale.size());
                handleApp(w[0] as Number, w[1] as ByteArray);
            } else if (_toApp.size() > 0) {
                var n = _toApp[0];
                _toApp = _toApp.slice(1, _toApp.size());
                s.onFrame(n[0] as Number, n[1] as ByteArray);
            } else if (_timerMs != null) {
                var ms = _timerMs as Number;
                _timerMs = null;
                firedTimers.add(ms);
                if (_probeDone && ms >= 150) {
                    _pauseFired = true;
                }
                s.onTimer();
            } else {
                return;
            }
        }
        errors.add("pump did not settle");
    }

    // ---------- scale side ----------

    private function emit(ch as Number, bytes as ByteArray) as Void {
        _toApp.add([ch, bytes]);
    }

    private function handleApp(ch as Number, bytes as ByteArray) as Void {
        if (bytes.size() > 20) {
            oversizeWrites++;
        }
        if (_stage == 0) {
            if (ch == ScaleSession.CH_LOGIN && bytesEqual(bytes, [0xa4]b)) {
                if (enableOrder.size() != 3) {
                    errors.add("probe before AUTH/CMD/PROP were enabled");
                }
                _stage = 1;
                emit(ScaleSession.CH_AUTH, hex("0000040006f2"));
            } else {
                errors.add("expected the a4 probe");
            }
            return;
        }
        if (_stage == 1) {
            expectEcho(ch, bytes, hex("0000050006f2"));
            _stage = 2;
            emit(ScaleSession.CH_AUTH, probeBig());
            return;
        }
        if (_stage == 2) {
            var echo = probeBig();
            echo[2] = 0x05;
            expectEcho(ch, bytes, echo);
            _stage = 3;
            _probeDone = true;
            return;
        }
        if (ch == ScaleSession.CH_LOGIN) {
            if (_stage == 3 && bytesEqual(bytes, hex("24000000"))) {
                _stage = 4;
            } else {
                errors.add("unexpected LOGIN write");
            }
            return;
        }
        if (_txStage != 0 && ch == _txCh) {
            var kind = Framing.classify(bytes);
            if (kind == Framing.KIND_RDY && _txStage == 1) {
                for (var i = 0; i < _txParcels.size(); i++) {
                    emit(ch, _txParcels[i]);
                }
                _txStage = 2;
                return;
            }
            if ((kind == Framing.KIND_OK && _txStage == 2) || (kind == Framing.KIND_ACK && _txStage == 3)) {
                _txStage = 0;
                startNextTx();
                return;
            }
        }
        if (_rx != null && ch == _rxCh) {
            if (bytes.size() < 2) {
                errors.add("short parcel");
                return;
            }
            var rx = _rx as ParcelReassembly;
            if (!rx.add((bytes[0] & 0xFF) | ((bytes[1] & 0xFF) << 8), bytes.slice(2, bytes.size()))) {
                errors.add("parcel out of range");
                return;
            }
            if (rx.isComplete()) {
                _rx = null;
                emit(ch, Framing.okBytes());
                onValue(ch, _rxTag, rx.data() as ByteArray);
            }
            return;
        }
        var kind = Framing.classify(bytes);
        if (kind == Framing.KIND_HEADER) {
            var h = Framing.parseHeader(bytes) as Dictionary;
            emit(ch, Framing.rdyBytes());
            _rxCh = ch;
            _rxTag = h.get("tag") as Number;
            _rx = new ParcelReassembly(h.get("count") as Number);
            return;
        }
        if (kind == Framing.KIND_DIRECT) {
            var d = Framing.parseDirect(bytes) as Dictionary;
            emit(ch, Framing.ackBytes());
            onValue(ch, d.get("tag") as Number, d.get("data") as ByteArray);
            return;
        }
        errors.add("unexpected app frame");
    }

    private function onValue(ch as Number, tag as Number, data as ByteArray) as Void {
        if (tag == 0x0b && ch == ScaleSession.CH_AUTH) {
            if (_stage != 4 || data.size() != 16) {
                errors.add("bad rand_key");
                return;
            }
            _randKey = data;
            _keys = SessionCrypto.deriveKeys(token, data, remoteKey());
            var k = _keys as Array<ByteArray>;
            send(ScaleSession.CH_AUTH, 0x0d, remoteKey());
            send(ScaleSession.CH_AUTH, 0x0c, Hkdf.hmac(k[0], concat(remoteKey(), data)));
            return;
        }
        if (tag == 0x0a && ch == ScaleSession.CH_AUTH) {
            var k = _keys as Array<ByteArray>;
            var expected = Hkdf.hmac(k[1], concat(_randKey as ByteArray, remoteKey()));
            if (!bytesEqual(expected, data)) {
                errors.add("login_info HMAC mismatch");
                return;
            }
            loginCount++;
            emit(ScaleSession.CH_LOGIN, hex("21000000"));
            return;
        }
        if (tag == 0x00 && ch == ScaleSession.CH_CMD) {
            handleSession(data);
            return;
        }
        errors.add("unexpected value tag " + tag.toString());
    }

    private function handleSession(value as ByteArray) as Void {
        if (loginCount == 0) {
            errors.add("session value before login");
            return;
        }
        var k = _keys as Array<ByteArray>;
        var msg = MiotCodec.decode(SessionCrypto.decryptValue(k[1], k[3], value));
        if (msg == null) {
            errors.add("undecodable session value");
            return;
        }
        if (msg.op == MiotCodec.OP_HELLO) {
            reply(msg.tid, MiotCodec.OP_HELLO, []b);
            return;
        }
        if (msg.op != MiotCodec.OP_ACTION) {
            errors.add("unexpected session op");
            return;
        }
        actions.add(msg.siid.toString() + "." + msg.iid.toString());
        if (dropActionResults) {
            return;
        }
        var count = storedCount();
        if (msg.siid == 7 && msg.iid == 1) {
            if (!(msg.params.get(1) instanceof String)) {
                errors.add("7.1 without a profile string");
            }
            reply(msg.tid, MiotCodec.OP_RESULT, MiotCodec.result(0, [
                MiotCodec.param(2, ParamType.U16, count) as ByteArray,
                MiotCodec.param(5, ParamType.STRING, "2.1.2_0008.0010") as ByteArray
            ]));
        } else if (msg.siid == 6 && msg.iid == 1) {
            if (liveStable != null) {
                event(5, 3, [
                    MiotCodec.param(2, ParamType.U8, 1) as ByteArray,
                    MiotCodec.param(3, ParamType.U16, liveStable) as ByteArray
                ]);
            }
            if (finishedPayload != null) {
                event(5, 4, [MiotCodec.param(4, ParamType.STRING, finishedPayload) as ByteArray]);
            }
            reply(msg.tid, MiotCodec.OP_RESULT, MiotCodec.result(0, [MiotCodec.param(3, ParamType.U16, count) as ByteArray]));
            if (count > 0) {
                reply(msg.tid, MiotCodec.OP_EVENT,
                    MiotCodec.event(6, 1, [MiotCodec.param(1, ParamType.STRING, records) as ByteArray]));
            }
        } else {
            errors.add("unknown action");
        }
    }

    private function event(siid as Number, eiid as Number, params as Array<ByteArray>) as Void {
        reply(_eventTid, MiotCodec.OP_EVENT, MiotCodec.event(siid, eiid, params));
        _eventTid++;
    }

    private function reply(tid as Number, op as Number, body as ByteArray) as Void {
        var k = _keys as Array<ByteArray>;
        var value = SessionCrypto.encryptValue(k[0], k[2], _devCtr, MiotCodec.encode(tid, op, body) as ByteArray);
        _devCtr++;
        send(ScaleSession.CH_PROP, 0x00, value as ByteArray);
    }

    //! Queues one scale -> app value: direct when it fits one 20-byte
    //! notification, framed otherwise. One value is in flight at a time.
    private function send(ch as Number, tag as Number, data as ByteArray) as Void {
        _out.add([ch, tag, data]);
        startNextTx();
    }

    private function startNextTx() as Void {
        if (_txStage != 0 || _out.size() == 0) {
            return;
        }
        var v = _out[0];
        _out = _out.slice(1, _out.size());
        var ch = v[0] as Number;
        var tag = v[1] as Number;
        var data = v[2] as ByteArray;
        _txCh = ch;
        if (data.size() + 4 <= 20) {
            _txStage = 3;
            emit(ch, Framing.direct(tag, data));
            return;
        }
        var chunk = maxValue - 2;
        _txParcels = [] as Array<ByteArray>;
        var no = 1;
        for (var at = 0; at < data.size(); at += chunk) {
            var end = at + chunk < data.size() ? at + chunk : data.size();
            _txParcels.add(Framing.parcel(no, data.slice(at, end)));
            no++;
        }
        _txStage = 1;
        emit(ch, Framing.header(tag, _txParcels.size()));
    }

    private function storedCount() as Number {
        if (records.length() == 0) {
            return 0;
        }
        var recs = StoredRecords.parse(records);
        return recs == null ? 0 : recs.size();
    }

    private function expectEcho(ch as Number, got as ByteArray, want as ByteArray) as Void {
        if (ch != ScaleSession.CH_AUTH || !bytesEqual(got, want)) {
            errors.add("bad probe echo");
        }
    }

    private function probeBig() as ByteArray {
        var out = [0x00, 0x00, 0x04, 0x01]b;
        for (var i = 0; i < maxValue; i++) {
            out.add(0xf2);
        }
        return out;
    }

    private function remoteKey() as ByteArray {
        var out = []b;
        for (var i = 0; i < 16; i++) {
            out.add(REMOTE_KEY_SEED + i);
        }
        return out;
    }
}

//! Records what the session reports to its UI.
class RecordingListener extends SessionListener {
    var states as Array<Number> = [] as Array<Number>;
    var lives as Array<Number> = [] as Array<Number>;
    var doneError as Number = -1;
    var doneCount as Number = 0;

    function initialize() {
        SessionListener.initialize();
    }

    function onSessionState(state as Number) as Void {
        states.add(state);
    }

    function onLive(weight as Number, stable as Boolean) as Void {
        lives.add(weight);
    }

    function onDone(error as Number) as Void {
        doneError = error;
        doneCount++;
    }
}
