import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Time;
import Toybox.Time.Gregorian;
import Toybox.WatchUi;

//! The single status screen (phase-3 functional layout; phase 6 matches
//! the mockup).
class MainView extends WatchUi.View {
    private var _model as UiModel;

    function initialize(model as UiModel) {
        View.initialize();
        _model = model;
    }

    function onUpdate(dc as Dc) as Void {
        var w = dc.getWidth();
        var h = dc.getHeight();
        var cx = w / 2;
        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_BLACK);
        dc.clear();
        var m = _model;

        if (m.settingsProblem != null) {
            text(dc, cx, h / 2 - 20, Graphics.FONT_SMALL, 0xFFAA00, "Check settings:");
            text(dc, cx, h / 2 + 10, Graphics.FONT_SMALL, 0xFFFFFF, m.settingsProblem as String);
            return;
        }

        // Top: link state.
        var label = "SCALE NOT SEEN";
        var color = 0xAAAAAA;
        if (m.linkState == BleLink.LINK_CONNECTED) {
            label = "CONNECTED";
            color = 0x55FFAA;
        } else if (m.linkState == BleLink.LINK_SEEN) {
            label = "SCALE SEEN";
            color = 0xFFFFFF;
        }
        text(dc, cx, h / 5, Graphics.FONT_XTINY, color, label);

        // Centre: weight + time.
        var weight = m.liveWeight;
        var ts = null;
        if (weight == null && m.reading != null) {
            weight = (m.reading as WeighIn).weight;
            ts = (m.reading as WeighIn).ts;
        }
        if (weight != null) {
            text(dc, cx, h / 2 - 10, Graphics.FONT_NUMBER_MEDIUM, 0xFFFFFF, kg(weight as Number) + " kg");
            if (ts != null) {
                text(dc, cx, h / 2 + 30, Graphics.FONT_TINY, 0xAAAAAA, clock(ts as Number));
            }
        } else {
            text(dc, cx, h / 2 - 10, Graphics.FONT_NUMBER_MEDIUM, 0x555555, "--.--");
            if (m.lastWeight != null && m.lastTs != null) {
                text(dc, cx, h / 2 + 30, Graphics.FONT_TINY, 0xAAAAAA,
                    "Last: " + kg(m.lastWeight as Number) + " · " + day(m.lastTs as Number));
            }
        }

        // Bottom: error line.
        if (m.message != null) {
            text(dc, cx, h * 4 / 5, Graphics.FONT_XTINY, 0xFFAA00, m.message as String);
        }
    }

    private function text(dc as Dc, x as Number, y as Number, font as Graphics.FontType, color as Number, s as String) as Void {
        dc.setColor(color, Graphics.COLOR_TRANSPARENT);
        dc.drawText(x, y, font, s, Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
    }

    //! 1/100 kg -> "80.25".
    static function kg(weight as Number) as String {
        return (weight / 100).toString() + "." + (weight % 100).format("%02d");
    }

    //! Unix seconds -> local "07:42".
    static function clock(ts as Number) as String {
        var info = Gregorian.info(new Time.Moment(ts), Time.FORMAT_SHORT);
        return info.hour.format("%02d") + ":" + info.min.format("%02d");
    }

    //! Unix seconds -> local "Mon".
    static function day(ts as Number) as String {
        var info = Gregorian.info(new Time.Moment(ts), Time.FORMAT_MEDIUM);
        return info.day_of_week as String;
    }
}
