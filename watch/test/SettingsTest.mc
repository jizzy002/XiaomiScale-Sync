import Toybox.Lang;
import Toybox.Test;

//! Phase-4 settings validation (KeyFormat rules). All values are made up.

function goodSettings() as Dictionary {
    return {
        "mac" => "aa-bb-cc-dd-ee-ff",
        "token" => "0102030405060708090a0b0C",
        "bleKey" => "",
        "heightCm" => 180,
        "age" => 30,
        "sex" => 1,
        "memberId" => "1"
    };
}

function problemWith(key as String, value as Object or Null) as String or Null {
    var d = goodSettings();
    d.put(key, value);
    return AppConfig.fromValues(d).problem;
}

(:test)
function testSettingsValid(logger as Logger) as Boolean {
    var c = AppConfig.fromValues(goodSettings());
    Test.assertMessage(c.problem == null, "valid settings, got problem " + c.problem);
    Test.assertMessage(c.mac.equals("AA:BB:CC:DD:EE:FF"), "MAC normalised");
    Test.assertMessage(bytesEqual(c.token, hex("0102030405060708090a0b0c")), "TOKEN decoded");
    Test.assertMessage(c.heightCm == 180 && c.age == 30 && c.sex == 1, "profile numbers");
    Test.assertMessage(c.memberId.equals("1") && c.ourMid == 1, "member id");
    // Settings may arrive as strings (older Connect IQ apps) or numbers.
    var d = goodSettings();
    d.put("heightCm", "175");
    d.put("mac", "AABBCCDDEEFF");
    var s = AppConfig.fromValues(d);
    Test.assertMessage(s.problem == null && s.heightCm == 175, "numeric strings accepted");
    Test.assertMessage(s.mac.equals("AA:BB:CC:DD:EE:FF"), "bare MAC normalised");
    return true;
}

(:test)
function testSettingsProblems(logger as Logger) as Boolean {
    Test.assertMessage("MAC".equals(problemWith("mac", "")), "empty MAC");
    Test.assertMessage("MAC".equals(problemWith("mac", "AA:BB:CC:DD:EE")), "short MAC");
    Test.assertMessage("MAC".equals(problemWith("mac", "AA:BB:CC:DD:EE:GG")), "non-hex MAC");
    Test.assertMessage("TOKEN".equals(problemWith("token", "0102")), "short TOKEN");
    Test.assertMessage("TOKEN".equals(problemWith("token", "0102030405060708090a0b0z")), "non-hex TOKEN");
    Test.assertMessage("TOKEN".equals(problemWith("token", null)), "missing TOKEN");
    Test.assertMessage("BLE KEY".equals(problemWith("bleKey", "1234")), "bad BLE KEY");
    Test.assertMessage(problemWith("bleKey", "000102030405060708090a0b0c0d0e0f") == null, "32-hex BLE KEY ok");
    Test.assertMessage("Height".equals(problemWith("heightCm", 20)), "height range");
    Test.assertMessage("Age".equals(problemWith("age", 200)), "age range");
    Test.assertMessage("Sex".equals(problemWith("sex", 3)), "sex range");
    Test.assertMessage("Member ID".equals(problemWith("memberId", "x1")), "member id digits");
    Test.assertMessage("Member ID".equals(problemWith("memberId", "0")), "member id 0 is the guest marker");
    return true;
}
