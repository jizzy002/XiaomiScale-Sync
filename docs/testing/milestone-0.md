# Milestone 0 — owner acceptance checklist (real accounts, real scale)

**Status:** PASS — all five checks passed on the owner's phone with the
owner's real Mi Account and Garmin Connect account (2026-10-12). The
gate that preceded all UI tasks (plan Task 13, step 5) is closed.

**Build under test:** debug APK on branch `impl/xiaomi-scale-garmin-sync`
(the gate itself drove two wire fixes — see Notes).

## Before you start

1. Install the debug build (`gradlew installDebug`, or sideload
   `app/build/outputs/apk/debug/app-debug.apk`).
2. Open the app once and grant the Bluetooth permission prompt
   (on Android 10 or below also Location — required for scanning there).
3. Turn Bluetooth on. Keep the Xiaomi Home app installed and untouched —
   coexistence (G3) is part of what we're proving.
4. The screen's three key chips (**MAC / TOKEN / BLE KEY**) start grey;
   they turn **green** when extracted. The scrolling green-on-black pane
   is the log — it must never contain key or token material (lengths and
   ✓ only; that is itself a pass condition of check 1).

## Checklist

### 1. Mi sign-in incl. 2FA → three keys shown green

- [x] Enter the Mi account email/phone + password, tap
      **Extract keys (Mi login)**.
- [x] When Xiaomi asks, enter the emailed 2FA code in the dialog.
- [x] **Pass:** MAC, TOKEN, and BLE KEY chips all show green ✓, and the
      log confirms `MAC: …✓`, `TOKEN: 24 hex ✓ (value redacted)`,
      `BLE KEY: 32 hex ✓ (value redacted)` — with **no hex key material
      anywhere in the log**.

### 2. scale visible → sync fetches stored records (count matches what Xiaomi Home shows)

- [x] Wake the scale (step on / off it) so it advertises; the log shows
      `scale visible: …` within a few seconds.
- [x] Tap **Sync scale now** (a weigh-in-triggered sync also works —
      it starts automatically when a trigger is armed).
- [x] **Pass:** the log shows the session completing
      (`sync OK …: N stored record(s) merged`) and the header's archive
      count equals the number of entries Xiaomi Home shows for the scale
      — no records deleted or claimed anywhere (Xiaomi Home still shows
      its own copy).

### 3. step on scale with app open → weigh-in archived with correct weight/time

- [x] With the app open and listening, weigh normally (step on, stand
      still, step off).
- [x] **Pass:** a new row appears at the top of the archive preview with
      your weight (±0.05 kg of the scale's display) and the weigh-in
      time (not the moment the app noticed it), source `BROADCAST`,
      `LIVE`, or `STORED`.

### 4. push pending → appears in Garmin exactly once, guard blocks the old script's copy if it raced

- [x] Sign in with **Garmin sign-in** (MFA dialog if Garmin asks).
- [x] Tap **Push pending to Garmin**.
- [x] **Pass:** the log reports `push finished: ≥1 pushed, … skipped
      (already in Garmin — guard)`; opening Garmin Connect shows the new
      weigh-in **exactly once** — no double entry even when the old
      shell-script pipeline had just pushed the same measurement (its
      copy blocks ours: that row reads `SKIPPED_EXISTING`).

### 5. scale returns to sleep (icon blinks) after session

- [x] Right after a sync session ends (log shows the session closing),
      stop stepping on the scale and watch its display.
- [x] **Pass:** within seconds the scale's icon starts blinking and it
      stops advertising (the app's log goes quiet — no more
      `scale visible` lines for it); it is not left stuck in a
      "connected" state.

## Recording the result

> **Run date:** 2026-10-12 · **Device / Android version:** Samsung
> SM-S928B (SM-S928B, serial RFCX70F5PDK; Android version to be filled
> on next adb attach) · **Result:** PASS (5/5) · **Notes:** scale model
> `xiaomi.scales.ms111` (reference repo verified `ms113` — protocol
> compatible). The gate itself exposed two wire bugs, fixed and
> re-verified within the run: session requests written to PROP instead
> of CMD (`2bc5378`) and session tag 0x01 instead of protocol TAG_DATA
> 0x00 (`2064d19`). Check 4: 2 pushed, 1 `SKIPPED_EXISTING` (old shell
> script's copy blocked the duplicate) — owner confirmed weigh-ins in
> Garmin Connect. Mi Home coexistence intact: 3 records, none claimed
> or deleted. Broadcast capture verified against the scale's own
> timestamp (85.10 kg @ 15:17:24). In-app captcha pop-out used for the
> Mi 2FA step.
