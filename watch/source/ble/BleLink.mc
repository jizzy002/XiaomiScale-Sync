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

    private static const CONNECT_TIMEOUT_MS = 15000;
    private static const SERVICE_RETRY_MS = 500;
    private static const SERVICE_RETRIES = 6;

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

    function initialize(listener as LinkListener) {
        SessionHost.initialize();
        _listener = listener;
        _events = new BleEvents(self);
        BluetoothLowEnergy.setDelegate(_events);
        if (!_profileRegistered) {
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
            } catch (e instanceof BluetoothLowEnergy.ProfileRegistrationException) {
                // Registrations can outlive this app instance (the test
                // harness restarts the app per test; max 3 profiles):
                // the profile is already known to the system.
                System.println("link: profile already registered: " + e.getErrorMessage());
            }
            _profileRegistered = true;
        }
    }

    //! `mac` is the normalised "AA:BB:CC:DD:EE:FF" form.
    function startScan(mac as String) as Void {
        _mac = mac;
        _macBytes = AppConfig.macBytes(mac) as ByteArray;
        _session = null;
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
        if (state != LINK_SCANNING) {
            return;
        }
        for (var r = results.next(); r != null; r = results.next()) {
            var result = r as BluetoothLowEnergy.ScanResult;
            if (!matches(result)) {
                continue;
            }
            setState(LINK_SEEN);
            log("scale seen, rssi " + result.getRssi().toString());
            BluetoothLowEnergy.setScanState(BluetoothLowEnergy.SCAN_STATE_OFF);
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
            return;
        }
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

    private function matches(r as BluetoothLowEnergy.ScanResult) as Boolean {
        if (r.hasAddress(_mac)) {
            return true;
        }
        // MiBeacon: frame control(2) product id(2) frame counter(1) then,
        // when the MAC-included bit (0x10) is set, the MAC little-endian.
        var data = r.getServiceData(BluetoothLowEnergy.stringToUuid(SERVICE));
        if (data == null || data.size() < 11 || (data[0] & 0x10) == 0) {
            return false;
        }
        for (var i = 0; i < 6; i++) {
            if (data[5 + i] != _macBytes[5 - i]) {
                return false;
            }
        }
        return true;
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
        System.println("link: profile registered, status " + status.toString());
    }
}
