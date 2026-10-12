# Xiaomi Scale S200 → Garmin Sync: Design

Date: 2026-10-11
Status: builder check complete, owner answers incorporated (2026-10-11);
approved for implementation planning
Protocol reference: https://github.com/faraga1/xiaomi-scale-s200-ble (read in full before implementing the BLE layer)

## Intent and why

The product owner weighs on a **Xiaomi Smart Scale S200** and wants the data in
**Garmin Connect** without thinking about it. Today they run a shell script on a
computer that wraps an existing CLI executable (configured with YAML and
pre-extracted keys) — a manual, computer-bound flow built on top of the
`faraga1/xiaomi-scale-s200-ble` sync client.

The goal is a **phone app with a good GUI** that collapses the whole chain to:

> open the app → step on the scale → it's already in Garmin.

Secondary goals, both stated explicitly by the owner:

1. **Key extraction happens inside the app.** Signing in with the Xiaomi (Mi)
   Account and pulling MAC + TOKEN + BLE KEY from Xiaomi's cloud must be part of
   onboarding — no external tools, no copying hex strings, no YAML.
2. **Open source and shareable.** A stranger should install the APK, walk
   through onboarding, and have it working — without ever reading a protocol
   document.

The current computer-based setup (shell script + webhook) keeps running for
now — it already delivers history to Garmin. Nothing migrates: the app
archives from its install date forward (Backfill cutoff below), and a
Garmin-side duplicate guard keeps the two pipelines from double-posting
while they overlap. Once the app is proven, retiring the script closes the
only gap left: weigh-ins the script collects while the app is closed reach
Garmin but not the app's archive.

## Goals

- **G1** Android app, used in the foreground: the user opens it *before*
  weighing; it captures the weigh-in during that session and pushes it to
  Garmin.
- **G2** In-app Mi Account sign-in extracts MAC, cloud TOKEN (12 bytes), and
  BLE KEY (16 bytes), with a manual-paste fallback.
- **G3** Coexistence with the official **Xiaomi Home app**: this app never
  auto-deletes and never claims weigh-ins on the scale.
- **G4** A durable local archive of every weigh-in, with cleanup controls:
  delete selected / delete all **in the app**, plus a maintenance action to
  clear the **scale's** memory (all records or a selection).
- **G5** Garmin push is automatic after capture; failures queue and retry
  without user babysitting — and never duplicate what is already in Garmin,
  including what the still-running computer setup posts.
- **G5b** Weigh-ins the scale does not attribute to the member (flag 2) are
  archived under a **Needs review** category instead of being pushed: the
  owner approves one as theirs (then it queues for Garmin) or keeps it as a
  guest (archive only). Guest weigh-ins are visible in the app, never in
  Garmin without approval.
- **G6** Open source: MIT license, generic onboarding, APKs published via
  GitHub Releases.
- **G7 (v2)** Companion web: the phone serves a small read-only dashboard
  (history, chart, sync status) on the LAN. Built only after the core works.

## Non-goals and anti-goals

- **No background service, no always-on scanning.** Explicit product decision:
  "using the app and opening it before the weigh in is the primary goal, not
  using it as a background service." No persistent notification, no foreground
  service for BLE. (Standard OS work scheduling for queued uploads is fine —
  it is invisible and not a service the user manages.)
- **No cloud backend of ours.** No accounts on our infrastructure, no server
  component the user must operate. All data lives on the phone; Garmin is the
  only destination.
- **No body composition.** The S200 measures weight only; nothing in the
  protocol carries impedance or fat values. Do not design UI or data model
  around them (the archive may leave room, but v1 ships weight).
- **iOS out of scope** for now.
- **No multi-user profiles in v1.** One scale user per install (the scale
  profile push supports one `ud` entry; more is an open protocol question).
- **Anti-goal: babysitting.** If the user has to re-login often, re-trigger
  syncs, or juggle apps, the design has failed even if data transfers. Also an
  anti-goal: breaking the Xiaomi Home app's own sync with this one.

## Constraints

### The scale (protocol facts — from the reference repo, verified on firmware 2.1.2_0008.0010, product id 0x4dcb)

- **Sleep discipline.** The scale sleeps (no advertising) most of the time.
  Stepping on wakes it. A connection keeps it awake; it sleeps seconds after
  disconnect. Never connect on a timer, on mere visibility, or during the
  ~50-minute status broadcasts — failed attempts have left a unit stuck
  "connected" for days until batteries were pulled. Connect **only**:
  - right after a weigh-in broadcast (object `0x4e16`), or
  - when frame control bit 9 (`0x5b10`, "connect to me") is set.
- **Memory.** Every finished weigh-in is stored (record no, unix ts) until a
  client deletes it. Record numbers keep counting across battery changes.
  This is the safety net for weigh-ins missed while the app was closed: the
  next session with the scale awake sweeps them up.
- **Two keys, from different places, never interchangeable:**
  - **TOKEN** (24 hex chars, 12 bytes): miauth GATT login secret.
  - **BLE KEY** (32 hex chars, 16 bytes): decrypts MiBeacon broadcasts.
  Both plus the **MAC** come from Xiaomi's cloud (see Onboarding flow).
- **GATT:** service `0xfe95`, characteristics by UUID (never by ATT handle):
  LOGIN `...0010`, AUTH `...0019`, CMD `...001a`, PROP `...001b`.
  - Notification order matters: enable AUTH/CMD/PROP → `a4` payload-size probe
    (echo `04 00`→`05 00`, `04 01`→`05 01`; probe length = max write size) →
    ~200 ms pause → enable LOGIN. Wrong order failed ~50% of logins.
  - Framing layer: direct (`00 00 02 <tag> <data>` → ack `00 00 03 00`) or
    framed multi-parcel (header `00 00 00 <tag> <n_le16>` → ready `00 00 01 01`
    → parcels `<no_le16><chunk>` back-to-back → received `00 00 01 00`). One
    ack before and one after, not per parcel. App always sends framed.
  - miauth login: write `24000000` → send rand_key (16B, tag `0x0b`) → receive
    remote_key (16B, tag `0x0d`), remote_info (32B, tag `0x0c`) → derive
    `HKDF-SHA256(ikm=TOKEN, salt=rand_key+remote_key, info="mible-login-info",
    64B)` → dev_key|app_key|dev_iv|app_iv → verify
    `remote_info == HMAC-SHA256(dev_key, remote_key+rand_key)` (wrong token
    fails here) → send `HMAC-SHA256(app_key, rand_key+remote_key)` (tag `0x0a`)
    → LOGIN notifies `21000000`.
  - Post-login encryption: value = `<ctr_le16><AES-CCM ct, 4-byte tag>`,
    nonce = `iv(4) + 00000000 + ctr_le32`, no AAD; app_key/app_iv outbound
    (own counter from 0), dev_key/dev_iv inbound (counter from the value).
  - Payloads are Xiaomi MIoT messages:
    `<len_le16=0x2000|total><tid_le16><op>[body]`; op `0xf0` hello, `0x05`
    action, `0x06` result (same tid), `0x07` event (own tid). Params:
    `<piid_le16><type_len_le16=type<<12|len><value>`; types u8=0x1, u16=0x3,
    u64=0x8, string=0xa.
  - Services used: **7.1** profile push (JSON string param; sets the clock;
    result p2 = stored count), **6.1** fetch stored (result p3 = count, then
    event 6.1 with records `no,mid,ut,weight_1/100kg,flag,unix_ts` joined by
    `_`, framed when long), **6.2** delete by record numbers (comma-separated
    string param p2 — the mechanism for G4 scale cleanup), **4.3** claim live
    weigh-in (we will NOT use: claiming removes storage, would surprise the
    Xiaomi Home app), events **5.3** live weight (p2 stable, p3 weight/100)
    and **5.4** finished weigh-in.
  - Broadcasts: MiBeacon v5 service data UUID `0xfe95` =
    `<fc_le16><product_le16><frame_counter><[mac_reversed]><[objects]>`;
    object `0x4e16` = `<u8 profile><u32 weight_1/100kg><u32 unix_ts>`,
    AES-CCM encrypted (4-byte tag, key = BLE KEY, nonce = MAC_le + product +
    frame_counter + extended_counter(3), AAD byte `0x11`, payload =
    ct + ext_counter + tag). Frame counter counts broadcast events (includes
    ~50-min status object `0x6623`), resets on battery change.
- **One client at a time.** The Xiaomi Home app deletes what it collects;
  whatever it syncs never reaches us, and vice versa. This is accepted (G3):
  we never delete, so both see the same stored weigh-ins until Home sweeps
  them.
- **Keep-on-scale side effects (accepted):** stored weigh-ins are re-sent on
  every sync (dedupe absorbs this), and the scale re-advertises "connect to
  me" ~5 min after each uncollected weigh-in before sleeping (small battery
  cost, accepted by the owner).
- **Open protocol questions** (from the reference repo, do not block v1):
  meaning of object `0x6623`; exact stuck-scale trigger; timestamps before the
  first profile push; non-kg units; behavior when deleting an already-gone
  record number (treat as harmless no-op, verify during implementation).

### Android platform

- Foreground-only BLE: scanning runs only while the app is open (no service).
  Use `BLUETOOTH_SCAN` with `neverForLocation` (no location permission
  needed), `BLUETOOTH_CONNECT` for GATT. Scan filter on service UUID
  `0xfe95`; `ScanResult.serviceData` gives the MiBeacon bytes directly —
  first-class on Android, no plugin gymnastics.
- **AES-CCM is not reliably available** from Android's platform crypto
  (`AES/CCM/NoPadding` support is provider-dependent). Decision: implement
  RFC 3610 CCM in Kotlin (AES-CTR + CBC-MAC over `javax.crypto` primitives,
  ~100 lines) with test vectors generated from the Python `cryptography`
  reference. Same code path used for broadcast decryption and the
  post-login CMD/PROP channel.
- HKDF/HMAC/SHA: standard JCA (implement HKDF explicitly like the reference
  client — it is 6 lines).
- Secrets (Mi credentials session, TOKEN, BLE KEY, Garmin tokens) in
  Android Keystore-backed storage; never in logs or exported backups.

### Xiaomi cloud (key extraction — port of `Xiaomi-cloud-tokens-extractor`)

- Login: `account.xiaomi.com/pass/serviceLogin?sid=xiaomiio&_json=true` →
  `POST serviceLoginAuth2` (password as uppercase MD5 hex) → on
  `notificationUrl`: email-2FA flow (`identity/list` → `sendEmailTicket` →
  user enters code → `verifyEmail` → `result/check` → `Auth2/end` → capture
  `extension-pragma` header for `ssecurity` → STS redirect → `serviceToken`
  cookie) → on `captchaUrl`: captcha branch (in-app image + input; if it
  can't be handled gracefully, fail with a clear message pointing at the
  manual-paste fallback).
- API calls: `https://[region.]api.io.mi.com/app` with RC4 encryption
  (`MIOT-ENCRYPT-ALGORITHM: ENCRYPT-RC4`, signed_nonce =
  `SHA256(base64(ssecurity) || base64(nonce))`, plain SHA-1 signatures
  (erratum: an earlier revision said HMAC-SHA1 — Task 10 verified against
  the reference `token_extractor.py`, which uses plain SHA-1; reference
  wire behavior is authoritative),
  RC4 with the first 1024 bytes dropped — port `generate_enc_params` /
  `decrypt_rc4` exactly). Cookies: `userId`, `serviceToken`,
  `yetAnotherServiceToken`.
- Endpoints: `/v2/homeroom/gethome` → `/v2/home/home_device_list` (TOKEN and
  MAC from the device entry) → `/v2/device/blt_get_beaconkey` for BLE-key
  devices (`did` starts with `blt`). Regions to try: `cn, de, us, ru, tw, sg,
  in, i2` (skip `cn` last-resort ordering: try the user's likely region
  first, then all).
- RC4 has no JCA presence we want to rely on: implement it in Kotlin (~25
  lines) — it is a symmetric stream cipher, trivially testable.

### Garmin Connect (port of `python-garminconnect` weight functions)

- Login: username/password against Garmin SSO; the reference library uses a
  token strategy chain with MFA support (`prompt_mfa` / `resume_login`) and
  persists a refresh token so subsequent runs skip SSO. Port the login →
  token persistence → refresh behavior; handle the MFA code prompt in-app.
  Garmin blocks some automated logins (Cloudflare, 429): never retry 401/429,
  retry 5xx/network with exponential backoff + jitter (mirror the reference
  library's rules).
- Upload (v1, weight only):
  `POST https://connectapi.garmin.com/weight-service/user-weight`
  (host tier "connectapi") with JSON
  `{"dateTimestamp": "<local ms>", "gmtTimestamp": "<utc ms>",
  "unitKey": "kg", "sourceType": "MANUAL", "value": <kg>}` — timestamps in
  `YYYY-MM-DDTHH:MM:SS.mmm` format, so the scale's real weigh-in time is
  preserved. (The FIT-file upload route `add_body_composition` is richer but
  unnecessary without body composition — note as future option only.)
- Re-uploading the same timestamp/value is how duplicates behave in Garmin;
  we prevent duplicates on our side (dedupe) rather than relying on Garmin.

## The how (decided)

### Stack

- **Kotlin, single-module Android app**, Jetpack Compose + Material 3,
  Coroutines/Flow, Room, OkHttp, DataStore/Keystore, WorkManager (queued
  upload retries only — not scanning). Companion server (v2): a small
  embedded HTTP server (NanoHTTPD or Ktor CIO) serving static HTML+JSON.
- Min SDK: 26+ (needs `neverForLocation` BLUETOOTH_SCAN, i.e. API 31
  behavior, but degrade gracefully with legacy permissions below 31);
  target latest.
- Distribution: GitHub Releases APK + CI build (GitHub Actions). Play Store
  explicitly deferred (developer account / policy cost is a later decision
  for the owner).
- License: MIT. Repository: this project, named to reflect
  "Xiaomi scale → Garmin".

**Why native Kotlin rather than a shared TypeScript/Capacitor core:** the web
companion is served *by the phone*, so no second platform needs the protocol
code. One implementation, one test suite, full access to Android BLE.

### Architecture (layers)

```
ui/          Compose screens (see UI below)
sync/        SyncEngine — the state machine ported from scale_reader.py
ble/         BleScanner (advertisement decode + AES-CCM broadcast decrypt)
             GattSession (framing, probe, miauth, encrypted channel, MIoT
             codec, receive-loop dispatch by tid / event queues)
crypto/      AesCcm (RFC 3610), Hkdf, Hmac helpers; vectors-tested
xiaomi/      MiCloudClient (login, 2FA, RC4-signed API, key extraction)
garmin/      GarminClient (SSO login, MFA, token store, addWeighIn, retries)
data/        Room archive (weigh-ins: weight, recordedAt, source, recordNo,
             scale flag, category, garminStatus, dedupe key) +
             repositories; Keystore secret store
companion/   (v2) LAN HTTP server + static dashboard assets
```

### SyncEngine behavior (ports the reference client's proven discipline)

1. While the app is open, scan continuously (fresh short scan cycles; check
   every ~0.3 s while the scale advertises — each check is a discovery pass
   because the controller reports only the first advertisement per device).
2. Decode every advertisement: MiBeacon frame control, frame counter, and
   decrypted objects. A `0x4e16` weigh-in object (deduped by frame counter +
   extended counter) is archived immediately and marked as "captured".
3. **Connection triggers** (all gated, in priority order):
   - after handling a weigh-in broadcast, or
   - frame control bit 9 set (`0x5b10` "connect to me"),
   - rate limit: max one connection attempt per 10 minutes, max 2 attempts
     per sync, exponential backoff after failure; a failed or fruitless
     solicited sync mutes further solicitations for 3 hours.
   - Never connect on mere visibility, on the 50-minute status broadcasts,
     or on any timer.
4. Session sequence: connect → enable AUTH/CMD/PROP → `a4` probe → ~200 ms →
   enable LOGIN → miauth login → hello → action 7.1 profile push (this also
   sets the scale clock; profile JSON carries the latest known weight as
   `wt`) → action 6.1 fetch stored → merge records (never delete/claim) →
   listen for live 5.3/5.4 briefly (live view while the user stands on the
   scale; a finished 5.4 is archived) → disconnect as soon as it's quiet
   (idle ~15 s, hard cap ~5 min).
5. **Archive merge:** dedupe key = (recordedAt unix second, weight in 1/100
   kg). A weigh-in arriving via broadcast, live event, and stored fetch counts
   once. Implausible timestamps (scale clock unset — before 2024-01-01 or in
   the future) are replaced with receive-time and flagged. Weights outside
   1–300 kg are discarded with a warning.
6. **Categorisation (owner decision):** every record carries
   `category: mine | needs_review`, derived from the scale's flag/member id
   (stored records and event 5.4 both carry `flag`: flag 0 + our mid = mine,
   flag 2 / mid 0 = needs review). The broadcast object's profile-id byte is
   assumed to follow the same rule — verify at implementation, defaulting to
   needs_review when ambiguous. `needs_review` records are archived but never
   pushed. Review actions: **Approve as mine** (→ queued for Garmin,
   retroactively and individually) or **Keep as guest** (archive only).
7. **Backfill cutoff (owner decision):** onboarding asks whether a previous
   setup already synced history to Garmin (owner's answer: yes). Records
   captured with `recordedAt` before the app's install date are archived
   with `garminStatus: skipped_existing` — never pushed.
8. **Garmin queue + duplicate guard:** every pushable weigh-in starts
   `pending`. A push runs right after capture and on app open (WorkManager
   one-shot/explicit call — no background scanning involved). **Before every
   push**, query Garmin's weigh-ins for that day and skip if an entry with
   the same timestamp (fallback: same date + same value) already exists —
   this is what keeps the still-running computer setup from double-posting.
   Success marks `pushed`; a guard-skip marks `skipped_existing`; failures
   keep `pending` with backoff; 401 flips to `auth_required` and surfaces in
   Settings until the user re-logs in.
9. **Silence banner:** if no advertisement has been heard for 12+ hours of
   app-open time (normally a status broadcast every ~50 min), show a banner
   with the stuck-scale advice (batteries out for 10 s). Shown when the app
   is open — there is no background alerting (accepted by G1 non-goal).
10. **Scale cleanup (G4):** Settings/History action that issues action 6.2
   with all known record numbers or a user-selected subset. Requires the
   scale to be awake → same trigger rules as a sync (run it as part of an
   active session when possible; if the scale is asleep, queue the request
   for the next session).

### UI (screens)

- **Onboarding wizard (first launch):**
  1. Mi Account sign-in (email/phone + password; 2FA code screen when
     Xiaomi demands it) → discover the scale → show extracted MAC/TOKEN/BLE
     KEY status (green check each) — with "I already have the keys" manual
     paste path at every step.
  2. Profile: height, age, sex, unit (kg default), and **Member ID** —
     prefilled with a pointer to the value in the owner's existing setup
     (e.g. `XIAOMI_PROFILE_MEMBER_ID` from their YAML), default 1.
     Cosmetic only (Home-app user attribution); weigh-ins are unaffected
     either way.
  3. "Did a previous setup already sync your history to Garmin?" — yes
     (the owner's case) sets the backfill cutoff.
  4. Garmin sign-in (email + password + MFA code screen if required).
  5. Done → main screen.
- **Main:** big "Ready to weigh" state with live status (scanning / scale
  seen / syncing); after a weigh-in: confirmation card (weight, time, source
  broadcast|live|stored, sync chips *Recorded* → *Pushed to Garmin* or
  *Queued*). Live weight updates while someone stands on the scale when the
  session is connected. Inline banners for silence alert,
  Garmin-auth-required, and "someone else weighed in — review" after a
  needs-review capture.
- **History:** weight chart (line, daily points) + reverse-chronological
  list with category filter chips (All / Mine / Needs review / Guest);
  needs-review items carry **Approve as mine** (→ queued for Garmin,
  individually or bulk) and **Keep as guest** actions. Multi-select mode →
  *Delete selected*, *Delete all* (app archive, with confirmation); overflow
  → *Clean scale memory* (all / select record numbers) with a clear warning
  that this does not affect the app archive.
- **Settings:** key status + re-run extraction, re-login to Mi/Garmin,
  profile editing, unit, companion server toggle (v2), about/version.

### Companion web (G7, v2 — after core works)

Toggle in Settings starts an HTTP server on the LAN (random or fixed port,
clearly displayed URL + optional QR). Serves a static dashboard (history
list, chart, last-sync status, scale-silence indicator) reading from the
local database through JSON endpoints. Read-only in v1 of the companion.
No authentication beyond "on the LAN" (personal tool; note the trade-off in
README). Not exposed to the internet.

### Testing strategy

- **Milestone 0 — owner-run spike (owner-approved):** before any UI work,
  the protocol core is proven end-to-end with the owner's real accounts:
  Mi sign-in + key extraction, one real GATT session fetching stored
  records, one weigh-in pushed to Garmin. The UI is built on top of a core
  that has already touched real hardware. Subsequent milestones each end
  with a short written acceptance checklist run by the owner on their phone
  (2FA codes arrive in the owner's inbox during onboarding tests; live
  Garmin/Mi smoke tests use the owner's account and never run in CI).
- **Protocol unit tests without hardware:** port the reference repo's
  `FakeScale` simulation (its `test_scale_reader.py` runs the real client
  against a simulated scale side) into Kotlin test fixtures; run SyncEngine
  sessions against it: login happy path, wrong-token failure, framed
  transfers, stored-fetch, dedupe, trigger/rate-limit rules.
- **Crypto vectors:** AES-CCM encrypt/decrypt vectors generated from Python
  `cryptography` (including the broadcast nonce/AAD layout and the
  post-login counter layout); HKDF/HMAC known-answer tests; RC4 known-answer
  tests; MiCloud request signing golden tests (recorded from the reference
  Python tool's outputs, no personal data).
- **Garmin client:** contract tests against recorded fixtures (requests
  built + responses parsed); live smoke test only with the owner's account,
  never in CI.
- **On-device manual checklist:** real-scale session flow (the four
  scenarios that matter: broadcast-only capture, connect-and-fetch, live
  weigh-in display, Home-app coexistence over multiple days).

## What's left to the builder

Decisions a sensible implementer can make without re-asking:

- Gradle project structure, dependency versions, CI workflow details,
  min/target SDK within the constraint above.
- Exact scan cadence and Android BLE API plumbing (workaround for
  "first advertisement per discovery session" behavior).
- Retry/backoff constants (initial values given by the reference client:
  step timeout 4 s, result 6 s, records 8 s; sync backoff 60 s → 30 min).
- Chart library choice (Compose-native Canvas is preferred for zero deps).
- Companion server library choice and dashboard styling.
- Captcha-in-app UX if it proves fiddly: fail soft to manual-paste.
- Whether Room or DataStore holds profile settings (Room for archive is fixed).
- Wording/copy of banners and confirmations.

Calls settled during the builder-check round (flagged for the owner to
skim): release signing uses one long-lived keystore the owner owns and
backs up (CI consumes it via secrets — signature continuity for installed
APKs); the first profile push uses the latest archive weight, or 80 kg when
the archive is empty; the Garmin duplicate guard runs on every push
(always-on, one GET per push); the main screen keeps the screen on while
listening; the early real-account spike is milestone 0.

Open technical items to resolve during implementation (known unknowns):

- Confirm Garmin SSO token strategy details against `python-garminconnect`'s
  `client.py` at port time (the login chain has several strategies; MFA
  resume path must be covered).
- Verify Android notification-order timing quirk (the ~200 ms pause) behaves
  the same under Android's BLE stack; if logins stall, adjust pause — root
  cause unknown upstream.
- Verify deleting a stale record number on the scale is harmless.
- Behavior of Garmin when the same timestamp is uploaded twice (we dedupe
  first; just confirm no error).
- Whether Xiaomi's login flow throws newer 2FA variants (push approval)
  beyond email codes; cover what the reference tool covers, fail soft
  otherwise.
- Confirm the broadcast object's profile-id byte maps to member recognition
  the same way stored-record flags do (needed for categorisation); default
  to needs_review when it is unclear.
