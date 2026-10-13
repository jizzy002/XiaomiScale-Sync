import Toybox.Application;
import Toybox.Lang;
import Toybox.WatchUi;

//! Everything the main screen draws. The controller fills it; the view
//! only reads it.
class UiModel {
    var linkState as Number = BleLink.LINK_IDLE;
    var reading as WeighIn or Null = null;       // this session's result
    var lastWeight as Number or Null = null;     // previous session, 1/100 kg
    var lastTs as Number or Null = null;
    var liveWeight as Number or Null = null;     // while someone stands on the scale
    var reading_ as Boolean = false;             // session running: progress arc
    var settingsProblem as String or Null = null;
    var message as String or Null = null;        // error line

    function initialize() {
    }
}

//! Wires settings, the BLE link and the scale session together for one
//! app run: scan -> connect -> sync -> show the latest weigh-in.
class SyncController extends LinkListener {
    private static const STORE_LAST = "last";
    private static const DEFAULT_PROFILE_WEIGHT = 8000;

    var model as UiModel = new UiModel();

    private var _config as AppConfig or Null = null;
    private var _link as BleLink or Null = null;
    private var _session as ScaleSession or Null = null;
    private var _events as SessionEvents;

    function initialize() {
        LinkListener.initialize();
        _events = new SessionEvents(self);
        loadLast();
    }

    function start() as Void {
        var config = AppConfig.load();
        _config = config;
        model.settingsProblem = config.problem;
        if (config.problem != null) {
            WatchUi.requestUpdate();
            return;
        }
        model.message = null;
        model.reading = null;
        model.liveWeight = null;
        if (_link == null) {
            _link = new BleLink(self);
        }
        (_link as BleLink).startScan(config.mac);
        WatchUi.requestUpdate();
    }

    //! Start button: drop whatever is running and scan again.
    function retry() as Void {
        stop();
        start();
    }

    function stop() as Void {
        var link = _link;
        if (link != null) {
            link.close();
        }
        _session = null;
        model.reading_ = false;
    }

    // ---------- LinkListener ----------

    function onLinkState(state as Number) as Void {
        model.linkState = state;
        WatchUi.requestUpdate();
    }

    function onLinkReady() as Void {
        var config = _config as AppConfig;
        var link = _link as BleLink;
        var weight = model.lastWeight != null ? model.lastWeight as Number : DEFAULT_PROFILE_WEIGHT;
        var session = new ScaleSession(link, _events, config.token, config.memberId,
            config.age, config.sex, config.heightCm, weight);
        _session = session;
        link.attach(session);
        model.reading_ = true;
        session.start();
        WatchUi.requestUpdate();
    }

    function onLinkFailed() as Void {
        model.message = "Scale not reachable";
        stop();
        WatchUi.requestUpdate();
    }

    // ---------- session (via SessionEvents) ----------

    function onLive(weight as Number, stable as Boolean) as Void {
        model.liveWeight = weight;
        WatchUi.requestUpdate();
    }

    function onDone(error as Number) as Void {
        model.reading_ = false;
        model.liveWeight = null;
        var session = _session;
        var config = _config as AppConfig;
        if (error == ScaleSession.ERR_NONE && session != null) {
            var latest = WeighInPicker.latest(session.storedRecords, session.liveCommit,
                config.ourMid, (_link as BleLink).nowUnix());
            model.reading = latest;
            if (latest != null && latest.mine) {
                saveLast(latest);
            }
        } else {
            model.message = messageFor(error);
        }
        WatchUi.requestUpdate();
    }

    private function messageFor(error as Number) as String {
        if (error == ScaleSession.ERR_AUTH) {
            return "Wrong scale or TOKEN";
        }
        if (error == ScaleSession.ERR_TIMEOUT) {
            return "Scale stopped answering";
        }
        if (error == ScaleSession.ERR_LINK) {
            return "Connection lost";
        }
        return "Sync failed";
    }

    private function loadLast() as Void {
        var v = Application.Storage.getValue(STORE_LAST);
        if (v instanceof Array && v.size() == 2) {
            model.lastWeight = v[0] as Number;
            model.lastTs = v[1] as Number;
        }
    }

    private function saveLast(w as WeighIn) as Void {
        Application.Storage.setValue(STORE_LAST, [w.weight, w.ts]);
        model.lastWeight = w.weight;
        model.lastTs = w.ts;
    }
}

//! Forwards session callbacks to the controller (single inheritance).
class SessionEvents extends SessionListener {
    private var _controller as SyncController;

    function initialize(controller as SyncController) {
        SessionListener.initialize();
        _controller = controller;
    }

    function onLive(weight as Number, stable as Boolean) as Void {
        _controller.onLive(weight, stable);
    }

    function onDone(error as Number) as Void {
        _controller.onDone(error);
    }
}
