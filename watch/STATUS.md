# Watch app status — Forerunner 255 (feat/watch-app)

**Outcome: on hold.** Everything that doesn't touch the Bluetooth radio is
built and unit-tested. The one blocker is the live BLE connection to the
Xiaomi Smart Scale S200 on the Forerunner 255; after extensive on-device
testing it could not be made to complete, and the BLE approach was paused by
decision on 2026-10-13.

## What works (ported from the Android app, 12/12 `(:test)` pass in the fr255 simulator)

- **Crypto** (`source/crypto/`): HKDF-SHA256, AES-CCM (4-byte tag), the
  session nonce/value helpers, miauth key derivation. All 5 vector fixtures
  pass.
- **Protocol** (`source/proto/`): `MiotCodec` (encode/decode, params incl.
  U64 as `Long`), `Framing` (header/parcel/direct + `ParcelReassembly`),
  `ProfileJson`, `StoredRecords`. Byte-pinned against the Kotlin tests.
- **Session** (`source/ble/ScaleSession.mc`): the full login + sync state
  machine (probe → ~200 ms pause → miauth → hello → 7.1 profile → 6.1 stored
  fetch → live 5.3/5.4 listen), callback-driven, no coroutines. Exercised end
  to end against a loopback `FakeScale` in the tests.
- **Settings** (`source/settings/AppConfig.mc`): MAC/TOKEN/BLE-KEY/profile/
  member-id validation with the `KeyFormat` rules; "Check settings: <field>".
- **UI** (`source/MainView.mc`): the status screen (link state, weight, last
  reading, errors). Functional; the mockup polish (phase 6) was not reached.

No real secrets are in the code, tests, or logs. The dev key stays at
`C:\Users\jizzy\keys\connectiq-developer.der` (outside the repo).

## The blocker — BLE connection catch-22 on the FR255

Connect IQ offers only `scan → pairDevice(ScanResult)`; it has no
connect-by-MAC (the Android app uses `connectGatt(mac)`, which CIQ lacks).
On this watch, against this scale, two facts hold together and conflict:

1. **With the 0xFE95 profile registered, `pairDevice` never completes.**
   `pairDevice` returns a Device, but `onConnectedStateChanged(CONNECTED)`
   never fires; it times out. Reproduced ~5×.
2. **Without a profile registered, it connects — but exposes no services.**
   `onConnectedStateChanged(CONNECTED)` fires, but `getServices()` returns 0,
   and registering the profile *after* the connection never yields services
   (`discovery: 0` even after it completes + 5 s). Connect IQ only discovers
   services at connect time, for profiles registered beforehand.

The scale also accepts a connection only while **soliciting** (MiBeacon
frame-control bit `0x0200`, for ~5 min after a weigh-in), so a non-soliciting
beacon times out regardless.

Permutations tried (all in the git history on this branch): connect-first vs
profile-first; scan left on vs stopped before pairing; explicit no-bond
`setConnectionStrategy`; longer/shorter connect timeouts; clearing stale
pairings; many retries across the solicit window. None produced
`connection state 1` with the profile registered.

## The unresolved lead (why this is "on hold", not "impossible")

The shipping app **"Scale Reader"** (Connect IQ store `3e6b7caa-8779-4ab3-
9495-7ae61a76b200`, by LukeSwimmer; targets the Xiaomi S400) reportedly
reaches a **weighing** state on the *same FR255* against this scale — i.e. it
opens a profile-registered GATT connection there. That implies the wall is
**our profile definition**, not the platform. The prime suspect is that our
profile registers all four characteristics (`0010/0019/001a/001b`) each with
a CCCD descriptor, and that setup is rejected during connection. The untested
fix is a **minimal / varied profile** (e.g. without CCCD descriptors, or a
different characteristic set). This was not attempted before the pause.

## To revive

1. In `source/ble/BleLink.mc` → `registerProfile()`, try a minimal 0xFE95
   profile and vary it; re-test on device for `connection state 1`.
2. Device-test loop: sideload `bin/scalesync.prg` to `GARMIN/Apps/`, settings
   via the simulator's App Settings Editor → `GARMIN/APPS/SETTINGS/
   SCALESYNC.SET` (the Connect IQ phone app does **not** list sideloaded
   apps). The device log needs an existing empty `GARMIN/Apps/LOGS/
   SCALESYNC.TXT`. Phone Bluetooth off; step on the scale to make it solicit.
3. Full BLE rulings are in `.superpowers/sdd/watch-app-handoff/progress.md`.

The Android app under `app/` was never touched.
