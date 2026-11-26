# Handover between Claude sessions

The owner works on CarLink both in a claude.ai chat and in Claude Code on the PC. The two do **not**
share conversations – this file is how they share what was learned and decided. Read it after
CLAUDE.md. When you finish a piece of work, add a short dated entry at the top of «Log» (newest first)
and update «Open questions», then commit and push.

Keep it short: decisions, findings and what is waiting for the owner – not a transcript.
No secrets or personal details (the repo is public).

## Decided plan (owner, 2026-11-26)
Goal: every CarLink function gets learning like the joystick (small steps within fixed limits, can be
reset), driven by the log, optimising until nothing more can be gained without hurting stability,
performance or smoothness on screen.
- **Order:** 1) better log (done, see Log) → 2) Wi-Fi connect time (learn the fastest join sequence) →
  3) light/tunnel → then start-up, music, memory.
- **Tunnel:** build **both** – GPS fix lost + car lights on, *and* a stored list of Norwegian road tunnels
  (NVDB/OSM) so dimming can start at the tunnel mouth and work in long tunnels (no 3-min limit).
  Today's rule (800a455) stays until then.
- **Music resume:** must work for **whatever app played last** (mainly YouTube Music, also NRK Radio,
  Tidal, maybe others). Remember the last app; per-app learning of what works to resume it.
- **Steps:** one round per log / adb session from the owner, then a few days of testing. Changes in
  different areas may go in the same round; within one area one change at a time. Each learner logs
  what it changed so a bad step can be undone alone. How often: decided per session.
- **«Fully optimised» register:** when an area is judged done, record it – a list here **and** its own
  tab in **CarLink Diagnose**. It stays «done» until the owner's experience says otherwise.
- **On/off options:** anything it is natural to switch on/off goes into CarLink's Settings where it fits
  (directly, in an existing sub-menu, or a new sub-menu) – must be easy to find.
- **Unused code:** remove it when sure it is unused and nothing else breaks.
- **Tests:** yes to automatic tests on GitHub (log replay of rules + Android 9 emulator 1920×720), even
  if CI takes a few minutes longer. Yes to keeping several days of log.

## Fully optimised (keep until the owner reports otherwise)
- (nothing yet)

## Open questions (waiting for the owner's answer)
- **SIM / 4G**: owner inserted a SIM (PIN removed, screen restarted), «Mobile network» in Android
  Settings is still greyed out. Proposed: a read-only adb script for the PC folder (SIM state, operator,
  signal, airplane/mobile data, modem config in firmware `ro.baseband`, separate USB 4G module?), or a
  «Mobile network» section in CarLink Diagnose. Owner was asked which operator the SIM is from (for APN).
  Earlier logs: telephony stack runs (qcril, QtiGsmCdmaPhone, IMS), OUT_OF_SERVICE, rmnet_data0 exists.
- **Waiting for the first log with the new logging** (a few days of normal driving, then «Send log to
  Claude»). Check in it: start-up SUMMARY lines, «connection ended» reasons, «screen froze» /
  «jerky screen» warnings, memory lines, how many minutes the system log now covers, and the
  «busiest tags» list (silence the top noisy ones in `LogSender.QUIET`). Then step 2: Wi-Fi learning.

## Known problems not solved yet
- **Scroll buttons sometimes jump to CarLink's home screen** (2026-11-25 23:03, three times while in
  CarLink Diagnose). The safety net brings the user back ~0.5–1 s later (log: «CarLink came to front …
  after an arrow … back to …»). Drag gesture is already kept 14 % away from the edges (commit 79349bc).
  Cause still unknown – look at what the car/vendor does on 0x32/0x33 in that moment.
- YouTube Music **refuses MediaBrowser connections** from CarLink (log «refused a background
  connection»), so after a cold start it falls back to opening YTM briefly. Opening it counts as user
  interaction and YTM may then skip restoring the queue («Restored queue is empty», «Abandoning
  warm-start resumption»). Current order in `LocalResume.playLoop`: wait until ready (≤15 s) → play ×3 →
  next → playFromSearch("") → open the app only if the user isn't using the screen.

## Log (newest first)
- **2026-11-26** (Claude Code) **Better log (step 1).** `AppLog`: one file per day in `files/logs/`, 7 days /
  8 MB kept, new lines appended every 3 s (errors at once) on a background thread – before, up to 60 s were
  lost when the vendor force-stops CarLink at ACC off. New `Perf` (core): start-up steps + «startup:
  SUMMARY» (process start → first picture), «screen froze» (main thread blocked ≥0.7 s, with stack and
  what was shown), «jerky screen» per minute (FrameMetrics, only when it happens), memory every 15 min,
  Android low-memory callbacks, slow root commands (≥3 s). `ui:` lines for what is shown. Link: «connection
  ended after X min: reason». Phone (Android 11+): why the previous run ended. «Send log»: CarLink log for
  the last days (≤2.5 MB), memory now, more event types, busiest system-log tags, system log 20 000 lines;
  logcat buffer raised at start (main 4 MB, system 1 MB). Phone adds its own log to the shared file.
  Diagnose reads the new log files. Built locally (release) OK; not yet tested on the car screen.
- **2026-11-26** (Claude Code, PC) Local build set up on the owner's PC without admin: Temurin JDK 17,
  Gradle 8.11.1 and Android SDK (platform 35, build-tools 35.0.0, platform-tools) under `%LOCALAPPDATA%`,
  `JAVA_HOME`/`ANDROID_HOME`/PATH set for the user. `gradle :launcher:assembleRelease :sender:assembleRelease
  :diagnose:assembleRelease` builds all three APKs locally (~5 min first time).
- **2026-11-26** Light: grey-weather rule above (800a455). CLAUDE.md and this file added.
  Home: max 4 favourites, menus in CarLink's own style (`CarMenu`, 129514f). Cover fallback from the media
  notification / art URI when the session has no bitmap; phone's connection notification is now silent
  (channel `link_quiet`, IMPORTANCE_MIN); the «log from the screen» notification is unchanged (fca414a).
- **2026-11-25** Music resume reworked several times (see commits 9b11d31…69f4f19): steering NEXT
  starts music when nothing plays; bug fixed where all resume attempts were silently blocked right after a
  full reboot (`elapsedRealtime` near 0 vs `lastResume = 0L`); never open apps or send HOME while the user
  is using the screen (HOME intent closes CarLink's menus – that was why menus «couldn't be pressed»).
  Wi-Fi: quick scan on the hotspot's last channel (pref `hotspotFreq`) before joining (f1d9ff0); owner set
  the hotspot to 2.4 GHz only. Connect times were 15–21 s when Wi-Fi had to be turned on, 6 s when it
  was already on, 0.1 s on quick off/on. App switcher: closed apps stay hidden until reopened (79349bc).
  New app **CarLink Diagnose** (31545f4), installed from CarLink: Settings → Updates; report goes to the
  phone via CarLink (`DiagnoseReceiver`, signature permission `no.carlink.permission.DIAGNOSE`).
- Owner's preferences learned: no car start screen/logo at ignition (CarLink straight away); YTM must
  not be on screen longer than needed; joystick recording is shared from the phone as is.
