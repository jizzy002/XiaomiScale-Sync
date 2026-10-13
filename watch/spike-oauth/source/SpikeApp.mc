import Toybox.Application;
import Toybox.Communications;
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.System;
import Toybox.WatchUi;

//! Phase-0 throwaway spike: does Garmin's SSO sign-in open on the phone,
//! and does a `ticket` come back? Never prints or stores the ticket — the
//! on-screen answer is yes/no plus any error (ground rule 3).
class SpikeApp extends Application.AppBase {
    private var _view as SpikeView;

    function initialize() {
        AppBase.initialize();
        _view = new SpikeView();
    }

    function onStart(state as Dictionary?) as Void {
    }

    function onStop(state as Dictionary?) as Void {
    }

    function getInitialView() as [Views] or [Views, InputDelegates] {
        Communications.registerForOAuthMessages(method(:onOAuthMessage));
        return [_view, new SpikeDelegate(self)];
    }

    //! Result callback: the message's data Dictionary carries the params
    //! matched from the result URL's query string (resultKeys: ticket).
    function onOAuthMessage(message as Communications.OAuthMessage) as Void {
        var data = message.data;
        var hasTicket = false;
        if (data instanceof Dictionary) {
            hasTicket = (data as Dictionary).get("ticket") != null;
        }
        // Never log ticket material itself, only presence.
        System.println("spike: oauth message ticket=" + hasTicket);
        _view.setStatus("Result in.\n" + (hasTicket ? "ticket: YES" : "ticket: no"), hasTicket);
        WatchUi.requestUpdate();
    }

    //! Garmin portal SSO (same constants as the Android client's
    //! PORTAL_SSO_CLIENT_ID / PORTAL_SERVICE_URL). makeOAuthRequest opens
    //! the sign-in page in the phone's Garmin Connect app; on success the
    //! redirect carries ?ticket=... to the result URL, which the
    //! registerForOAuthMessages callback then receives.
    function startSignIn() as Void {
        _view.setStatus("Opening\nsign-in...", null);
        WatchUi.requestUpdate();
        Communications.makeOAuthRequest(
            "https://sso.garmin.com/sso/signin",
            {
                "clientId" => "GarminConnect",
                "service" => "https://connect.garmin.com/app",
                "locale" => "en-US",
                "consumeServiceTicket" => "true"
            },
            "https://connect.garmin.com/app",
            Communications.OAUTH_RESULT_TYPE_URL,
            { "ticket" => "ticket" }
        );
    }
}

//! Minimal screen: press START to begin, then the status text tells the
//! tester what happened.
class SpikeView extends WatchUi.View {
    private var _status as String = "Garmin OAuth spike\n\nPress START";
    private var _ticket as Boolean or Null = null;

    function initialize() {
        View.initialize();
    }

    function setStatus(status as String, ticket as Boolean or Null) as Void {
        _status = status;
        _ticket = ticket;
    }

    function onUpdate(dc as Dc) as Void {
        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_BLACK);
        dc.clear();
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.drawText(
            dc.getWidth() / 2,
            dc.getHeight() / 2,
            Graphics.FONT_XTINY,
            _status,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER
        );
    }
}

//! START triggers the sign-in attempt.
class SpikeDelegate extends WatchUi.BehaviorDelegate {
    private var _app as SpikeApp;

    function initialize(app as SpikeApp) {
        BehaviorDelegate.initialize();
        _app = app;
    }

    function onSelect() as Boolean {
        _app.startSignIn();
        return true;
    }
}
