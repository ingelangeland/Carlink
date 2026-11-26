# Handover between Claude sessions

The owner works on CarLink both in a claude.ai chat and in Claude Code on the PC. The two do **not**
share conversations – this file is how they share what was learned and decided. Read it after
CLAUDE.md. When you finish a piece of work, add a short dated entry at the top of «Log» (newest first)
and update «Open questions», then commit and push.

Keep it short: decisions, findings and what is waiting for the owner – not a transcript.
No secrets or personal details (the repo is public).

## Open questions (waiting for the owner's answer)
- **Tunnel detection** (proposed 2026-11-26, not built): (1) GPS fix lost + car lights on = tunnel
  (phone GPS already reaches the screen), (2) map data – Norwegian road tunnels from Statens vegvesen
  (NVDB) / OpenStreetMap stored on the screen, (3) both. Claude recommended starting with (1).
  Today's rule (commit 800a455): daylight + car lights dims like a tunnel at most 3 min, and not when the
  lights have been on most of the last 10 min (grey weather) or right after start.
- **SIM / 4G**: owner inserted a SIM (PIN removed, screen restarted), «Mobile network» in Android
  Settings is still greyed out. Proposed: a read-only adb script for the PC folder (SIM state, operator,
  signal, airplane/mobile data, modem config in firmware `ro.baseband`, separate USB 4G module?), or a
  «Mobile network» section in CarLink Diagnose. Owner was asked which operator the SIM is from (for APN).
  Earlier logs: telephony stack runs (qcril, QtiGsmCdmaPhone, IMS), OUT_OF_SERVICE, rmnet_data0 exists.
- **System log covers only 40–75 s** in «Send log» (logs sent right after start-up). Proposed: filter
  noisy tags and/or bigger logcat buffer (`logcat -G 4M`). Not built.
- **Automatic tests** proposed: replay events from the owner's logs against the rules (light, music
  resume), plus an Android 9 emulator (1920×720) smoke test in CI, plus joystick recording replay. Not built.

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
