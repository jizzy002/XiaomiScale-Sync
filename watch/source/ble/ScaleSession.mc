import Toybox.Lang;
import Toybox.Cryptography;
import Toybox.System;

//! What the session needs from the link: GATT writes (no response) and
//! CCCD enables, serialised by the host; one timer; teardown; the clock.
//! BleLink implements it over Toybox.BluetoothLowEnergy, tests with a
//! loopback fake. Calls must never re-enter the session synchronously.
class SessionHost {
    function initialize() {
    }

    function write(ch as Number, bytes as ByteArray) as Void {
    }

    function enableNotifications(ch as Number) as Void {
    }

    function close() as Void {
    }

    //! One pending timer; a new schedule replaces the old one.
    //! Expiry calls ScaleSession.onTimer().
    function schedule(ms as Number) as Void {
    }

    function cancelTimer() as Void {
    }

    function nowUnix() as Number {
        return 0;
    }
}

//! Progress the session reports to the UI.
class SessionListener {
    function initialize() {
    }

    function onSessionState(state as Number) as Void {
    }

    //! A plausible live reading (1/100 kg) while someone is on the scale.
    function onLive(weight as Number, stable as Boolean) as Void {
    }

    //! Called exactly once; ERR_NONE when the sync completed.
    function onDone(error as Number) as Void {
    }
}

//! One GATT session against the scale, as a callback-driven state machine
//! (port of ScaleSession.kt + SyncEngine.collect, without coroutines):
//!
//!   enable AUTH, CMD, PROP; LOGIN <- a4; echo both probe frames
//!   (byte 2: 04 -> 05); maxValue from the big probe; ~200 ms pause;
//!   enable LOGIN; LOGIN <- 24000000; AUTH rand_key (0x0b) ->;
//!   <- remote_key (0x0d), remote_info (0x0c); verify remote_info;
//!   AUTH login_info (0x0a) ->; LOGIN -> 21000000;
//!   hello; action 7.1 (profile); action 6.1 (stored fetch) and its
//!   event 6.1; live listen (5.3 readings, 5.4 finished) -> done.
//!
//! Encrypted values go out on CMD (acks come back on CMD) and come in on
//! PROP. Key material never reaches the log; only states and lengths do.
class ScaleSession {
    // Characteristic ids (BleLink maps them to the GATT objects).
    static const CH_LOGIN = 0;
    static const CH_AUTH = 1;
    static const CH_CMD = 2;
    static const CH_PROP = 3;

    // States, in order.
    static const S_IDLE = 0;
    static const S_PROBE_SMALL = 1;
    static const S_PROBE_BIG = 2;
    static const S_PAUSE = 3;
    static const S_SEND_RAND = 4;
    static const S_WAIT_REMOTE_KEY = 5;
    static const S_WAIT_REMOTE_INFO = 6;
    static const S_SEND_LOGIN_INFO = 7;
    static const S_WAIT_LOGIN_OK = 8;
    static const S_HELLO = 9;
    static const S_PROFILE = 10;
    static const S_FETCH = 11;
    static const S_WAIT_STORED = 12;
    static const S_LIVE = 13;
    static const S_DONE = 14;

    // Outcomes (onDone).
    static const ERR_NONE = 0;
    static const ERR_TIMEOUT = 1;
    static const ERR_PROTOCOL = 2;
    static const ERR_AUTH = 3; // remote_info mismatch: wrong scale or wrong TOKEN
    static const ERR_LOGIN_REJECTED = 4;
    static const ERR_STATUS = 5;
    static const ERR_LINK = 6;

    static const PAUSE_MS = 200;
    static const RESULT_TIMEOUT_MS = 6000;
    static const STORED_TIMEOUT_MS = 5000;
    static const LIVE_START_MS = 5000;
    static const LIVE_IDLE_MS = 15000;
    static const LIVE_MAX_MS = 600000;

    //! Connect IQ rejects writes over 20 bytes (no long writes).
    static const MAX_WRITE = 20;

    private static const TAG_DATA = 0x00;
    private static const TAG_LOGIN_INFO = 0x0a;
    private static const TAG_RAND_KEY = 0x0b;
    private static const TAG_REMOTE_INFO = 0x0c;
    private static const TAG_REMOTE_KEY = 0x0d;
    private static const SIID_LIVE = 5;
    private static const SIID_STORED = 6;
    private static const IID_LIVE_WEIGHT = 3;
    private static const IID_LIVE_FINISHED = 4;
    private static const AMBIGUOUS_FLAG = 2;

    var state as Number = S_IDLE;
    var error as Number = ERR_NONE;
    //! Largest value per write; 20 fits the default ATT MTU before the probe.
    var maxValue as Number = 20;
    var storedRecords as Array<StoredRecord> or Null = null;
    var liveCommit as WeighIn or Null = null;
    var lastStable as WeighIn or Null = null;

    private var _host as SessionHost;
    private var _listener as SessionListener;
    private var _token as ByteArray;
    private var _mid as String;
    private var _age as Number;
    private var _sex as Number;
    private var _heightCm as Number;
    private var _weightGrams as Number;

    private var _randKey as ByteArray or Null = null;
    private var _remoteKey as ByteArray or Null = null;
    private var _keys as Array<ByteArray> or Null = null; // devKey, appKey, devIv, appIv
    private var _appCtr as Number = 0;
    private var _nextTid as Number = 1;
    private var _pendingTid as Number = -1;
    private var _storedSeen as Boolean = false;
    private var _liveSeen as Boolean = false;
    private var _liveStartedAt as Number = 0;

    // outgoing framed value: one at a time
    private var _txCh as Number = -1;
    private var _txStage as Number = 0; // 0 none, 1 wait RDY, 2 wait OK
    private var _txParcels as Array<ByteArray> or Null = null;

    // incoming framed value
    private var _rx as ParcelReassembly or Null = null;
    private var _rxCh as Number = -1;
    private var _rxTag as Number = 0;

    function initialize(
        host as SessionHost,
        listener as SessionListener,
        token as ByteArray,
        mid as String,
        age as Number,
        sex as Number,
        heightCm as Number,
        weightGrams as Number
    ) {
        _host = host;
        _listener = listener;
        _token = token;
        _mid = mid;
        _age = age;
        _sex = sex;
        _heightCm = heightCm;
        _weightGrams = weightGrams;
    }

    //! Call once the GATT link is up and the characteristics resolved.
    function start() as Void {
        if (state != S_IDLE) {
            return;
        }
        // Same order as the Xiaomi app: LOGIN notifications only after the
        // probe and a pause; enabling all four up front stalled logins.
        _host.enableNotifications(CH_AUTH);
        _host.enableNotifications(CH_CMD);
        _host.enableNotifications(CH_PROP);
        _host.write(CH_LOGIN, [0xa4]b);
        enter(S_PROBE_SMALL);
        _host.schedule(Framing.ACK_TIMEOUT_MS);
    }

    //! A GATT notification from the scale.
    function onFrame(ch as Number, frame as ByteArray) as Void {
        if (state == S_DONE) {
            return;
        }
        if (state == S_PROBE_SMALL || state == S_PROBE_BIG) {
            onProbe(ch, frame);
            return;
        }
        if (ch == CH_LOGIN) {
            if (state != S_WAIT_LOGIN_OK) {
                return;
            }
            if (Bytes.equalsConstantTime(frame, [0x21, 0x00, 0x00, 0x00]b)) {
                log("login OK");
                enter(S_HELLO);
                sendRequest(MiotCodec.OP_HELLO, []b);
            } else {
                fail(ERR_LOGIN_REJECTED);
            }
            return;
        }
        var kind = Framing.classify(frame);
        if (_txStage != 0 && ch == _txCh) {
            if (kind == Framing.KIND_RDY && _txStage == 1) {
                var parcels = _txParcels as Array<ByteArray>;
                for (var i = 0; i < parcels.size(); i++) {
                    _host.write(ch, parcels[i]);
                }
                _txParcels = null;
                _txStage = 2;
                _host.schedule(Framing.ACK_TIMEOUT_MS);
                return;
            }
            if (kind == Framing.KIND_OK && _txStage == 2) {
                _txStage = 0;
                onSent();
                return;
            }
        }
        if (_rx != null && ch == _rxCh) {
            onParcel(ch, frame);
            return;
        }
        if (kind == Framing.KIND_DIRECT) {
            _host.write(ch, Framing.ackBytes());
            onValue(ch, frame[3] & 0xFF, frame.slice(4, frame.size()));
        } else if (kind == Framing.KIND_HEADER) {
            var count = (frame[4] & 0xFF) | ((frame[5] & 0xFF) << 8);
            _host.write(ch, Framing.rdyBytes());
            if (count == 0) {
                _host.write(ch, Framing.okBytes());
                onValue(ch, frame[3] & 0xFF, []b);
            } else {
                _rx = new ParcelReassembly(count);
                _rxCh = ch;
                _rxTag = frame[3] & 0xFF;
            }
        } else if (kind == Framing.KIND_OTHER) {
            log("unexpected frame, " + frame.size().toString() + " bytes");
            fail(ERR_PROTOCOL);
        }
        // Stray RDY/OK/ACK frames are ignored.
    }

    //! The host's timer expired.
    function onTimer() as Void {
        if (state == S_PAUSE) {
            beginLogin();
        } else if (state == S_LIVE) {
            finishLive();
        } else if (state != S_DONE && state != S_IDLE) {
            log("timeout in state " + state.toString());
            fail(ERR_TIMEOUT);
        }
    }

    //! The link dropped or a GATT operation failed.
    function onLinkLost() as Void {
        if (state == S_LIVE) {
            // The weighing is over once the scale sleeps; keep what we have.
            finishLive();
        } else if (state != S_DONE) {
            fail(ERR_LINK);
        }
    }

    // ---------- setup and login ----------

    private function onProbe(ch as Number, frame as ByteArray) as Void {
        if (ch != CH_AUTH) {
            return;
        }
        var bigProbe = state == S_PROBE_BIG;
        var prefix = bigProbe ? [0x00, 0x00, 0x04, 0x01]b : [0x00, 0x00, 0x04, 0x00]b;
        if (frame.size() < 4 || !Bytes.equalsConstantTime(frame.slice(0, 4), prefix)) {
            fail(ERR_PROTOCOL);
            return;
        }
        var echo = frame.slice(0, frame.size());
        echo[2] = 0x05;
        _host.write(CH_AUTH, echo);
        if (!bigProbe) {
            enter(S_PROBE_BIG);
            _host.schedule(Framing.ACK_TIMEOUT_MS);
            return;
        }
        // The 0xf2 payload length, capped at what one CIQ write can carry.
        maxValue = frame.size() - 4;
        if (maxValue > MAX_WRITE) {
            maxValue = MAX_WRITE;
        }
        if (maxValue < 3) {
            fail(ERR_PROTOCOL);
            return;
        }
        log("probe: maxValue=" + maxValue.toString());
        enter(S_PAUSE);
        _host.schedule(PAUSE_MS);
    }

    private function beginLogin() as Void {
        _host.enableNotifications(CH_LOGIN);
        _host.write(CH_LOGIN, [0x24, 0x00, 0x00, 0x00]b);
        _randKey = Cryptography.randomBytes(16);
        enter(S_SEND_RAND);
        sendValue(CH_AUTH, TAG_RAND_KEY, _randKey as ByteArray);
    }

    private function onValue(ch as Number, tag as Number, data as ByteArray) as Void {
        if (state == S_WAIT_REMOTE_KEY) {
            if (ch != CH_AUTH || tag != TAG_REMOTE_KEY || data.size() != 16) {
                fail(ERR_PROTOCOL);
                return;
            }
            _remoteKey = data;
            enter(S_WAIT_REMOTE_INFO);
            _host.schedule(Framing.ACK_TIMEOUT_MS);
            return;
        }
        if (state == S_WAIT_REMOTE_INFO) {
            if (ch != CH_AUTH || tag != TAG_REMOTE_INFO || data.size() != 32) {
                fail(ERR_PROTOCOL);
                return;
            }
            verifyAndSendLoginInfo(data);
            return;
        }
        if (ch == CH_PROP && _keys != null && tag == TAG_DATA) {
            var k = _keys as Array<ByteArray>;
            var msg = MiotCodec.decode(SessionCrypto.decryptValue(k[0], k[2], data));
            if (msg == null) {
                log("undecodable session value, " + data.size().toString() + " bytes");
                fail(ERR_PROTOCOL);
                return;
            }
            dispatch(msg);
            return;
        }
        fail(ERR_PROTOCOL);
    }

    private function verifyAndSendLoginInfo(remoteInfo as ByteArray) as Void {
        var randKey = _randKey as ByteArray;
        var remoteKey = _remoteKey as ByteArray;
        var keys = SessionCrypto.deriveKeys(_token, randKey, remoteKey);
        if (keys == null) {
            fail(ERR_AUTH);
            return;
        }
        var expected = Hkdf.hmac(keys[0], Bytes.concat(remoteKey, randKey));
        if (!Bytes.equalsConstantTime(expected, remoteInfo)) {
            log("remote_info mismatch: wrong scale or wrong TOKEN");
            fail(ERR_AUTH);
            return;
        }
        _keys = keys;
        enter(S_SEND_LOGIN_INFO);
        sendValue(CH_AUTH, TAG_LOGIN_INFO, Hkdf.hmac(keys[1], Bytes.concat(randKey, remoteKey)));
    }

    // ---------- framing ----------

    //! Header, RDY wait, parcels 1..count, OK wait (Framing.sendValue).
    private function sendValue(ch as Number, tag as Number, data as ByteArray) as Void {
        var chunk = maxValue - 2;
        var parcels = [] as Array<ByteArray>;
        var no = 1;
        for (var at = 0; at < data.size(); at += chunk) {
            var end = at + chunk < data.size() ? at + chunk : data.size();
            parcels.add(Framing.parcel(no, data.slice(at, end)));
            no++;
        }
        _txCh = ch;
        _txParcels = parcels;
        _txStage = 1;
        _host.write(ch, Framing.header(tag, parcels.size()));
        _host.schedule(Framing.ACK_TIMEOUT_MS);
    }

    private function onSent() as Void {
        if (state == S_SEND_RAND) {
            enter(S_WAIT_REMOTE_KEY);
            _host.schedule(Framing.ACK_TIMEOUT_MS);
        } else if (state == S_SEND_LOGIN_INFO) {
            enter(S_WAIT_LOGIN_OK);
            _host.schedule(Framing.ACK_TIMEOUT_MS);
        } else if (_pendingTid >= 0) {
            _host.schedule(RESULT_TIMEOUT_MS);
        }
    }

    private function onParcel(ch as Number, frame as ByteArray) as Void {
        var rx = _rx as ParcelReassembly;
        if (frame.size() < 2 || !rx.add((frame[0] & 0xFF) | ((frame[1] & 0xFF) << 8), frame.slice(2, frame.size()))) {
            fail(ERR_PROTOCOL);
            return;
        }
        if (!rx.isComplete()) {
            return;
        }
        _rx = null;
        _host.write(ch, Framing.okBytes());
        onValue(ch, _rxTag, rx.data() as ByteArray);
    }

    // ---------- encrypted channel ----------

    private function sendRequest(op as Number, body as ByteArray) as Void {
        var k = _keys as Array<ByteArray>;
        var tid = _nextTid & 0xFFFF;
        _nextTid++;
        var plaintext = MiotCodec.encode(tid, op, body);
        var value = plaintext == null ? null : SessionCrypto.encryptValue(k[1], k[3], _appCtr, plaintext);
        if (value == null) {
            fail(ERR_PROTOCOL);
            return;
        }
        _appCtr++;
        _pendingTid = tid;
        sendValue(CH_CMD, TAG_DATA, value);
    }

    private function sendAction(siid as Number, aiid as Number, params as Array<ByteArray>) as Void {
        sendRequest(MiotCodec.OP_ACTION, MiotCodec.action(siid, aiid, params));
    }

    private function dispatch(msg as MiotMessage) as Void {
        if (msg.op == MiotCodec.OP_RESULT || msg.op == MiotCodec.OP_HELLO) {
            if (msg.tid != _pendingTid) {
                log("no pending request for tid " + msg.tid.toString());
                return;
            }
            _pendingTid = -1;
            if (msg.status != 0) {
                log("request failed, status " + msg.status.toString());
                fail(ERR_STATUS);
                return;
            }
            onResult(msg);
        } else if (msg.op == MiotCodec.OP_EVENT && msg.siid == SIID_STORED) {
            onStored(msg);
        } else if (msg.op == MiotCodec.OP_EVENT && msg.siid == SIID_LIVE) {
            onLiveEvent(msg);
        }
    }

    private function onResult(msg as MiotMessage) as Void {
        if (state == S_HELLO) {
            enter(S_PROFILE);
            var json = ProfileJson.build(_mid, _age, _sex, _heightCm, _weightGrams, _host.nowUnix());
            var p = MiotCodec.param(1, ParamType.STRING, json);
            if (p == null) {
                fail(ERR_PROTOCOL);
                return;
            }
            sendAction(7, 1, [p]);
        } else if (state == S_PROFILE) {
            enter(S_FETCH);
            sendAction(6, 1, [] as Array<ByteArray>);
        } else if (state == S_FETCH) {
            var count = msg.params.get(3);
            if (count instanceof Number && count > 0 && !_storedSeen) {
                enter(S_WAIT_STORED);
                _host.schedule(STORED_TIMEOUT_MS);
            } else {
                startLive();
            }
        }
    }

    private function onStored(msg as MiotMessage) as Void {
        _storedSeen = true;
        var text = msg.params.get(1);
        if (text instanceof String) {
            storedRecords = StoredRecords.parse(text);
            log("stored records: " + (storedRecords == null ? "unparseable" : storedRecords.size().toString()));
        }
        if (state == S_WAIT_STORED) {
            startLive();
        }
    }

    // ---------- live listen ----------

    private function startLive() as Void {
        enter(S_LIVE);
        _liveStartedAt = System.getTimer();
        if (liveCommit != null) {
            finishLive();
            return;
        }
        // Events already seen mean someone is on the scale: idle window.
        _host.schedule(_liveSeen ? LIVE_IDLE_MS : LIVE_START_MS);
    }

    private function onLiveEvent(msg as MiotMessage) as Void {
        _liveSeen = true;
        if (msg.iid == IID_LIVE_WEIGHT) {
            var weight = msg.params.get(3);
            var stable = msg.params.get(2);
            if (weight instanceof Number && WeighInPicker.plausible(weight)) {
                var isStable = stable instanceof Number && stable == 1;
                _listener.onLive(weight, isStable);
                if (isStable) {
                    // No 5.4 (yet): who stood on the scale is ambiguous.
                    lastStable = new WeighIn(weight, _host.nowUnix(), AMBIGUOUS_FLAG, 0, true);
                }
            }
        } else if (msg.iid == IID_LIVE_FINISHED) {
            var payload = msg.params.get(4);
            if (payload instanceof String) {
                var parsed = parseFinished(payload);
                if (parsed != null) {
                    liveCommit = parsed;
                }
            }
            if (state == S_LIVE) {
                finishLive();
            }
            return;
        }
        if (state == S_LIVE) {
            if (System.getTimer() - _liveStartedAt > LIVE_MAX_MS) {
                finishLive();
            } else {
                _host.schedule(LIVE_IDLE_MS);
            }
        }
    }

    //! Event 5.4 param 4: `mid,user_type,weight,flag,ts`; recorded at the
    //! commit time, like SyncEngine.parseFinished.
    private function parseFinished(payload as String) as WeighIn or Null {
        var f = StoredRecords.split(payload, ",");
        if (f.size() < 5) {
            return null;
        }
        var mid = StoredRecords.parseLong(f[0]);
        var weight = StoredRecords.parseLong(f[2]);
        var flag = StoredRecords.parseLong(f[3]);
        if (mid == null || weight == null || flag == null) {
            return null;
        }
        if (weight < WeighInPicker.MIN_WEIGHT100 || weight > WeighInPicker.MAX_WEIGHT100) {
            return null;
        }
        return new WeighIn(weight.toNumber(), _host.nowUnix(), flag.toNumber(), mid.toNumber(), true);
    }

    private function finishLive() as Void {
        if (liveCommit == null && lastStable != null) {
            liveCommit = lastStable;
        }
        done(ERR_NONE);
    }

    // ---------- teardown ----------

    private function fail(err as Number) as Void {
        done(err);
    }

    private function done(err as Number) as Void {
        if (state == S_DONE) {
            return;
        }
        error = err;
        enter(S_DONE);
        _host.cancelTimer();
        _host.close();
        _listener.onDone(err);
    }

    private function enter(s as Number) as Void {
        state = s;
        _listener.onSessionState(s);
    }

    private function log(msg as String) as Void {
        System.println("session: " + msg);
    }
}
