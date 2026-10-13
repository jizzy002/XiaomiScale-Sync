import Toybox.BluetoothLowEnergy;
import Toybox.Lang;
import Toybox.System;
import Toybox.Time;
import Toybox.Timer;

//! What BleLink reports to the app.
class LinkListener {
    function initialize() {
    }

    //! LINK_* state changes (for the "SCALE NOT SEEN / SEEN / CONNECTED" dot).
    function onLinkState(state as Number) as Void {
    }

    //! GATT ready: the app creates a ScaleSession and calls link.attach().
    function onLinkReady() as Void {
    }

    //! The link failed before a session could run (no service, pair error).
    function onLinkFailed() as Void {
    }
}

//! The real SessionHost over Toybox.BluetoothLowEnergy: registers the
//! 0xFE95 profile, scans for the configured scale, pairs (connects),
//! resolves LOGIN/AUTH/CMD/PROP and runs GATT operations one at a time
//! (Connect IQ allows a single outstanding request). Writes are
//! write-without-response, like the Android transport.
//!
//! The scale is matched by its MAC with ScanResult.hasAddress(), or by
//! the MAC carried in its MiBeacon (0xFE95 service data). Neither the
//! MAC nor any key is ever logged.
class BleLink extends SessionHost {
    static const LINK_IDLE = 0;
    static const LINK_SCANNING = 1;
    static const LINK_SEEN = 2;
    static const LINK_CONNECTED = 3;
    static const LINK_CLOSED = 4;

    private static const SERVICE = "0000fe95-0000-1000-8000-00805f9b34fb";
    private static const CHARS = [
        "00000010-0000-1000-8000-00805f9b34fb", // LOGIN
        "00000019-0000-1000-8000-00805f9b34fb", // AUTH
        "0000001a-0000-1000-8000-00805f9b34fb", // CMD
        "0000001b-0000-1000-8000-00805f9b34fb"  // PROP
    ];
    private static const OP_WRITE = 0;
    private static const OP_CCCD = 1;

    private static var _profileRegistered as Boolean = false;

    var state as Number = LINK_IDLE;
    //! Why the last link attempt failed (shown on screen, safe to log).
    var lastFailure as String = "";
    //! True when the connected device was identified by its MAC; false
    //! when it was the soliciting-0xFE95 fallback (a failed login then
    //! means "not our scale", not "wrong TOKEN").
    var matchedByMac as Boolean = false;

    // MiBeacon frame-control bits (BeaconDecoder.kt).
    private static const FC_MAC = 0x10;
    private static const FC_SOLICIT = 0x0200;
    private static const MAX_LOGGED = 16;

    private static const CONNECT_TIMEOUT_MS = 15000;
    private static const SERVICE_RETRY_MS = 500;
    private static const SERVICE_RETRIES = 6;
    //! If onProfileRegister never arrives, pair anyway after this.
    private static const PROFILE_WAIT_MS = 2000;

    private var _listener as LinkListener;
    private var _events as BleEvents;
    private var _mac as String = "";
    private var _macBytes as ByteArray = []b;
    private var _device as BluetoothLowEnergy.Device or Null = null;
    private var _chars as Array = [null, null, null, null];
    private var _session as ScaleSession or Null = null;
    private var _queue as Array<Array> = [] as Array<Array>;
    private var _busy as Boolean = false;
    private var _timer as Timer.Timer = new Timer.Timer();
    private var _faultTimer as Timer.Timer = new Timer.Timer();
    private var _connTimer as Timer.Timer = new Timer.Timer();
    private var _serviceTries as Number = 0;
    private var _current as BluetoothLowEnergy.ScanResult or Null = null;
    //! Fallback devices that failed login this app run.
    private var _rejects as Array<BluetoothLowEnergy.ScanResult> = [] as Array<BluetoothLowEnergy.ScanResult>;
    //! [ScanResult, frameControl] already written to the log.
    private var _logged as Array<Array> = [] as Array<Array>;
    // Scan diagnostics: callbacks/results so far, devices described.
    private var _scanCallbacks as Number = 0;
    private var _scanResults as Number = 0;
    private var _fe95Results as Number = 0;
    private var _described as Array<BluetoothLowEnergy.ScanResult> = [] as Array<BluetoothLowEnergy.ScanResult>;
    private static const MAX_DESCRIBED = 10;

    function initialize(listener as LinkListener) {
        SessionHost.initialize();
        _listener = listener;
        _events = new BleEvents(self);
        BluetoothLowEnergy.setDelegate(_events);
        // The profile is registered only once the scale has been seen:
        // a registered profile appears to filter scan results down to
        // devices listing its UUID, and the S200 carries 0xFE95 only as
        // service data (device log: zero results with it registered).
    }

    //! `mac` is the normalised "AA:BB:CC:DD:EE:FF" form.
    function startScan(mac as String) as Void {
        _mac = mac;
        _macBytes = AppConfig.macBytes(mac) as ByteArray;
        _session = null;
        _device = null;
        _current = null;
        _chars = [null, null, null, null];
        _queue = [] as Array<Array>;
        _busy = false;
        lastFailure = "";
        setState(LINK_SCANNING);
        log("scanning");
        BluetoothLowEnergy.setScanState(BluetoothLowEnergy.SCAN_STATE_SCANNING);
    }

    function attach(session as ScaleSession) as Void {
        _session = session;
    }

    // ---------- SessionHost ----------

    function write(ch as Number, bytes as ByteArray) as Void {
        _queue.add([OP_WRITE, ch, bytes]);
        pump();
    }

    function enableNotifications(ch as Number) as Void {
        _queue.add([OP_CCCD, ch, null]);
        pump();
    }

    function close() as Void {
        _timer.stop();
        _connTimer.stop();
        _queue = [] as Array<Array>;
        _busy = false;
        try {
            BluetoothLowEnergy.setScanState(BluetoothLowEnergy.SCAN_STATE_OFF);
        } catch (e) {
        }
        var device = _device;
        _device = null;
        if (device != null) {
            try {
                BluetoothLowEnergy.unpairDevice(device);
            } catch (e) {
            }
        }
        setState(LINK_CLOSED);
    }

    function schedule(ms as Number) as Void {
        _timer.stop();
        _timer.start(method(:onTimerFired), ms < 50 ? 50 : ms, false);
    }

    function cancelTimer() as Void {
        _timer.stop();
    }

    function nowUnix() as Number {
        return Time.now().value();
    }

    function onTimerFired() as Void {
        var s = _session;
        if (s != null) {
            s.onTimer();
        }
    }

    // ---------- GATT op queue ----------

    private function pump() as Void {
        if (_busy || _queue.size() == 0) {
            return;
        }
        var op = _queue[0];
        _queue = _queue.slice(1, _queue.size());
        var c = _chars[op[1] as Number] as BluetoothLowEnergy.Characteristic or Null;
        try {
            if (c == null) {
                throw new Lang.InvalidValueException("characteristic not resolved");
            }
            if (op[0] == OP_WRITE) {
                c.requestWrite(op[2] as ByteArray, { :writeType => BluetoothLowEnergy.WRITE_TYPE_DEFAULT });
            } else {
                var cccd = c.getDescriptor(BluetoothLowEnergy.cccdUuid());
                if (cccd == null) {
                    throw new Lang.InvalidValueException("no CCCD");
                }
                cccd.requestWrite([0x01, 0x00]b);
            }
            _busy = true;
        } catch (e) {
            log("GATT request failed");
            fault();
        }
    }

    //! Report a link fault outside the current call stack, so the session
    //! is never re-entered from inside one of its own writes.
    private function fault() as Void {
        _queue = [] as Array<Array>;
        _busy = false;
        _faultTimer.start(method(:onFault), 50, false);
    }

    function onFault() as Void {
        var s = _session;
        if (s != null) {
            s.onLinkLost();
        } else {
            failLink("GATT request failed");
        }
    }

    // ---------- BleDelegate callbacks (via BleEvents) ----------

    function onScanResults(results as BluetoothLowEnergy.Iterator) as Void {
        _scanCallbacks++;
        if (_scanCallbacks == 1 || _scanCallbacks % 20 == 0) {
            log("scan callback " + _scanCallbacks.toString() + " (state " + state.toString()
                + "), results " + _scanResults.toString() + ", fe95 " + _fe95Results.toString());
        }
        if (state != LINK_SCANNING) {
            return;
        }
        var fallback = null;
        for (var r = results.next(); r != null; r = results.next()) {
            var result = r as BluetoothLowEnergy.ScanResult;
            var kind = classify(result);
            if (kind == MATCH_MAC) {
                connect(result, true);
                return;
            }
            if (kind == MATCH_SOLICIT && fallback == null) {
                fallback = result;
            }
        }
        if (fallback != null) {
            connect(fallback, false);
        }
    }

    //! A fallback device failed login: skip it for the rest of this run.
    function rejectCurrent() as Void {
        var c = _current;
        if (c != null) {
            _rejects.add(c);
        }
    }

    private function connect(result as BluetoothLowEnergy.ScanResult, byMac as Boolean) as Void {
        matchedByMac = byMac;
        _current = result;
        setState(LINK_SEEN);
        log("scale seen (" + (byMac ? "MAC" : "soliciting 0xfe95") + "), rssi " + result.getRssi().toString());
        BluetoothLowEnergy.setScanState(BluetoothLowEnergy.SCAN_STATE_OFF);
        if (_profileRegistered) {
            pair();
            return;
        }
        registerProfile();
        _connTimer.start(method(:pair), PROFILE_WAIT_MS, false);
    }

    function onProfileRegistered(status as Number) as Void {
        log("profile registered, status " + status.toString());
        if (state == LINK_SEEN && _device == null) {
            _connTimer.stop();
            pair();
        }
    }

    private function registerProfile() as Void {
        var chars = [];
        for (var i = 0; i < CHARS.size(); i++) {
            chars.add({
                :uuid => BluetoothLowEnergy.stringToUuid(CHARS[i]),
                :descriptors => [BluetoothLowEnergy.cccdUuid()]
            });
        }
        try {
            BluetoothLowEnergy.registerProfile({
                :uuid => BluetoothLowEnergy.stringToUuid(SERVICE),
                :characteristics => chars
            });
            log("profile registration requested");
        } catch (e instanceof BluetoothLowEnergy.ProfileRegistrationException) {
            // Registrations can outlive an app instance (max 3 profiles).
            log("profile already registered: " + e.getErrorMessage());
        }
        _profileRegistered = true;
    }

    function pair() as Void {
        var result = _current;
        if (state != LINK_SEEN || _device != null || result == null) {
            return;
        }
        try {
            _device = BluetoothLowEnergy.pairDevice(result);
        } catch (e) {
            log("pairDevice threw: " + e.getErrorMessage());
            _device = null;
        }
        if (_device == null) {
            failLink("Pair failed");
            return;
        }
        log("pairing, waiting for connection");
        _connTimer.start(method(:onConnectTimeout), CONNECT_TIMEOUT_MS, false);
    }

    function onConnectedStateChanged(device as BluetoothLowEnergy.Device, connState as BluetoothLowEnergy.ConnectionState) as Void {
        log("connection state " + connState.toString());
        if (state == LINK_CLOSED || state == LINK_IDLE) {
            return;
        }
        if (connState == BluetoothLowEnergy.CONNECTION_STATE_CONNECTED) {
            _connTimer.stop();
            _device = device;
            _serviceTries = 0;
            resolveService();
        } else {
            _connTimer.stop();
            _queue = [] as Array<Array>;
            _busy = false;
            var s = _session;
            if (s != null) {
                s.onLinkLost();
            } else {
                failLink(state == LINK_SEEN ? "Connect failed" : "Disconnected");
            }
        }
    }

    //! Services may not be discovered the instant the link comes up, so
    //! the lookup is retried a few times before giving up.
    function resolveService() as Void {
        var device = _device;
        if (device == null || state == LINK_CLOSED) {
            return;
        }
        var service = device.getService(BluetoothLowEnergy.stringToUuid(SERVICE));
        if (service == null) {
            _serviceTries++;
            if (_serviceTries < SERVICE_RETRIES) {
                _connTimer.start(method(:resolveService), SERVICE_RETRY_MS, false);
            } else {
                failLink("No scale service");
            }
            return;
        }
        for (var i = 0; i < CHARS.size(); i++) {
            _chars[i] = service.getCharacteristic(BluetoothLowEnergy.stringToUuid(CHARS[i]));
            if (_chars[i] == null) {
                log("characteristic " + i.toString() + " not found");
                failLink("No scale characteristic");
                return;
            }
        }
        log("GATT ready after " + _serviceTries.toString() + " retries");
        setState(LINK_CONNECTED);
        _listener.onLinkReady();
    }

    function onConnectTimeout() as Void {
        if (state == LINK_SEEN) {
            failLink("Connect timed out");
        }
    }

    private function failLink(reason as String) as Void {
        log("link failed: " + reason);
        lastFailure = reason;
        _listener.onLinkFailed();
    }

    function onCharacteristicChanged(c as BluetoothLowEnergy.Characteristic, value as ByteArray) as Void {
        var s = _session;
        var ch = indexOf(c);
        if (s != null && ch >= 0) {
            s.onFrame(ch, value);
        }
    }

    function onWriteDone(status as BluetoothLowEnergy.Status) as Void {
        _busy = false;
        if (status != BluetoothLowEnergy.STATUS_SUCCESS) {
            log("GATT write status " + status.toString());
            fault();
            return;
        }
        pump();
    }

    // ---------- helpers ----------

    private static const MATCH_NONE = 0;
    private static const MATCH_MAC = 1;
    private static const MATCH_SOLICIT = 2;

    //! MATCH_MAC: the advertiser is the configured scale (hasAddress, or
    //! the MAC field of its MiBeacon). MATCH_SOLICIT: an unknown 0xFE95
    //! advertiser asking for a client ("connect to me", fc bit 0x0200),
    //! tried when the MAC cannot be seen; a failed login rejects it.
    private function classify(r as BluetoothLowEnergy.ScanResult) as Number {
        var addr = r.hasAddress(_mac) || r.hasAddress(_mac.toLower()) || r.hasAddress(_macBytes);
        _scanResults++;
        describe(r);
        var data = fe95Data(r);
        if (data != null) {
            _fe95Results++;
        }
        var fc = data != null && data.size() >= 5 ? (data[0] & 0xFF) | ((data[1] & 0xFF) << 8) : -1;
        var beaconMac = false;
        if (fc >= 0 && (fc & FC_MAC) != 0 && data.size() >= 11) {
            beaconMac = true;
            for (var i = 0; i < 6; i++) {
                if (data[5 + i] != _macBytes[5 - i]) {
                    beaconMac = false;
                }
            }
        }
        if (addr || fc >= 0) {
            logAdvertiser(r, data, fc, addr, beaconMac);
        }
        if (addr || beaconMac) {
            return MATCH_MAC;
        }
        if (fc >= 0 && (fc & FC_SOLICIT) != 0 && !isRejected(r)) {
            return MATCH_SOLICIT;
        }
        return MATCH_NONE;
    }

    //! The 0xFE95 service data: from getServiceData(), or parsed from the
    //! raw advertisement (AD type 0x16 = 16-bit service data, UUID LE)
    //! in case the 128-bit lookup misses a 16-bit entry.
    private function fe95Data(r as BluetoothLowEnergy.ScanResult) as ByteArray or Null {
        var d = r.getServiceData(BluetoothLowEnergy.stringToUuid(SERVICE));
        if (d != null && d.size() > 0) {
            return d;
        }
        var raw = r.getRawData();
        if (raw == null) {
            return null;
        }
        var at = 0;
        while (at + 1 < raw.size()) {
            var len = raw[at] & 0xFF;
            if (len == 0 || at + 1 + len > raw.size()) {
                return null;
            }
            var type = raw[at + 1] & 0xFF;
            if (type == 0x16 && len >= 3 && raw[at + 2] == 0x95 && raw[at + 3] == 0xFE) {
                return raw.slice(at + 4, at + 1 + len);
            }
            at += 1 + len;
        }
        return null;
    }

    //! First few devices: which AD types (and lengths) they advertise.
    //! Types and lengths only - no payload, no address.
    private function describe(r as BluetoothLowEnergy.ScanResult) as Void {
        if (_described.size() >= MAX_DESCRIBED) {
            return;
        }
        for (var i = 0; i < _described.size(); i++) {
            if (r.isSameDevice(_described[i])) {
                return;
            }
        }
        _described.add(r);
        var raw = r.getRawData();
        var ads = "";
        var at = 0;
        while (raw != null && at + 1 < raw.size()) {
            var len = raw[at] & 0xFF;
            if (len == 0 || at + 1 + len > raw.size()) {
                break;
            }
            var type = raw[at + 1] & 0xFF;
            ads += type.format("%02x") + "/" + len.toString();
            if (type == 0x16 || type == 0x03 || type == 0x02) {
                // 16-bit UUID of service data / service lists (not secret)
                ads += "(" + ((raw[at + 3] & 0xFF) << 8 | (raw[at + 2] & 0xFF)).format("%04x") + ")";
            }
            ads += " ";
            at += 1 + len;
        }
        log("dev #" + _described.size().toString() + " rssi=" + r.getRssi().toString()
            + " raw=" + (raw == null ? "null" : raw.size().toString()) + " ad: " + ads);
    }

    private function isRejected(r as BluetoothLowEnergy.ScanResult) as Boolean {
        for (var i = 0; i < _rejects.size(); i++) {
            if (r.isSameDevice(_rejects[i])) {
                return true;
            }
        }
        return false;
    }

    //! One line per advertiser and frame-control value: bits, product id,
    //! RSSI and match results only, never the address itself.
    private function logAdvertiser(r as BluetoothLowEnergy.ScanResult, data as ByteArray or Null,
            fc as Number, addr as Boolean, beaconMac as Boolean) as Void {
        for (var i = 0; i < _logged.size(); i++) {
            var e = _logged[i];
            if (e[1] == fc && r.isSameDevice(e[0] as BluetoothLowEnergy.ScanResult)) {
                return;
            }
        }
        if (_logged.size() >= MAX_LOGGED) {
            return;
        }
        _logged.add([r, fc]);
        var pid = data != null && data.size() >= 4 ? (data[2] & 0xFF) | ((data[3] & 0xFF) << 8) : -1;
        log("adv #" + _logged.size().toString()
            + " fc=" + (fc >= 0 ? fc.format("%04x") : "none")
            + " pid=" + (pid >= 0 ? pid.format("%04x") : "none")
            + " len=" + (data != null ? data.size().toString() : "0")
            + " rssi=" + r.getRssi().toString()
            + " hasAddress=" + (addr ? "yes" : "no")
            + " beaconMac=" + ((fc >= 0 && (fc & FC_MAC) != 0) ? (beaconMac ? "match" : "other") : "absent")
            + " solicit=" + ((fc >= 0 && (fc & FC_SOLICIT) != 0) ? "yes" : "no"));
    }

    private function indexOf(c as BluetoothLowEnergy.Characteristic) as Number {
        var uuid = c.getUuid();
        for (var i = 0; i < CHARS.size(); i++) {
            if (uuid.equals(BluetoothLowEnergy.stringToUuid(CHARS[i]))) {
                return i;
            }
        }
        return -1;
    }

    private function setState(s as Number) as Void {
        state = s;
        _listener.onLinkState(s);
    }

    private function log(msg as String) as Void {
        System.println("link: " + msg);
    }
}

//! Forwards the system's BLE callbacks to BleLink (Monkey C has single
//! inheritance; BleLink already extends SessionHost).
class BleEvents extends BluetoothLowEnergy.BleDelegate {
    private var _link as BleLink;

    function initialize(link as BleLink) {
        BleDelegate.initialize();
        _link = link;
    }

    function onScanResults(scanResults as BluetoothLowEnergy.Iterator) as Void {
        _link.onScanResults(scanResults);
    }

    function onConnectedStateChanged(device as BluetoothLowEnergy.Device, state as BluetoothLowEnergy.ConnectionState) as Void {
        _link.onConnectedStateChanged(device, state);
    }

    function onCharacteristicChanged(characteristic as BluetoothLowEnergy.Characteristic, value as ByteArray) as Void {
        _link.onCharacteristicChanged(characteristic, value);
    }

    function onCharacteristicWrite(characteristic as BluetoothLowEnergy.Characteristic, status as BluetoothLowEnergy.Status) as Void {
        _link.onWriteDone(status);
    }

    function onDescriptorWrite(descriptor as BluetoothLowEnergy.Descriptor, status as BluetoothLowEnergy.Status) as Void {
        _link.onWriteDone(status);
    }

    function onScanStateChange(scanState as BluetoothLowEnergy.ScanState, status as BluetoothLowEnergy.Status) as Void {
        System.println("link: scan state " + scanState.toString() + ", status " + status.toString());
    }

    function onEncryptionStatus(device as BluetoothLowEnergy.Device, status as BluetoothLowEnergy.Status) as Void {
        System.println("link: encryption status " + status.toString());
    }

    function onProfileRegister(uuid as BluetoothLowEnergy.Uuid, status as BluetoothLowEnergy.Status) as Void {
        _link.onProfileRegistered(status);
    }
}
