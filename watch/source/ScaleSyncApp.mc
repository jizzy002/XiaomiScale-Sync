import Toybox.Application;
import Toybox.Lang;
import Toybox.WatchUi;

//! Standalone watch app: reads the latest weigh-in from the Xiaomi S200
//! while open. No background BLE, no settings on the watch.
class ScaleSyncApp extends Application.AppBase {
    private var _controller as SyncController or Null = null;

    function initialize() {
        AppBase.initialize();
    }

    function onStart(state as Dictionary?) as Void {
        _controller = new SyncController();
    }

    function onStop(state as Dictionary?) as Void {
        var c = _controller;
        if (c != null) {
            c.stop();
        }
    }

    function getInitialView() as [Views] or [Views, InputDelegates] {
        var c = _controller as SyncController;
        c.start();
        return [new MainView(c.model), new MainDelegate(c)];
    }

    //! Settings changed in the Connect IQ app: validate again and rescan.
    function onSettingsChanged() as Void {
        var c = _controller;
        if (c != null) {
            c.retry();
        }
    }
}

//! Start = retry; Back keeps its default (exit).
class MainDelegate extends WatchUi.BehaviorDelegate {
    private var _controller as SyncController;

    function initialize(controller as SyncController) {
        BehaviorDelegate.initialize();
        _controller = controller;
    }

    function onSelect() as Boolean {
        _controller.retry();
        return true;
    }
}
