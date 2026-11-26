# CarLink – notes for Claude

Read this first. It is written for Claude Code sessions on the owner's Windows PC.
Then read **HANDOVER.md**: what was decided and found in the owner's other Claude sessions, and what is
waiting for an answer. Before you start: `git pull`. When you finish: add a short entry to HANDOVER.md,
commit and push, so the other session sees it.

## The owner
- Write to the owner in **Norwegian**, in plain language. The owner is not a programmer: explain what
  happened and what it means, not the code.
- **If the owner's message ends with a question mark, only answer it.** Make no changes and run no
  commands that change anything. Words like «ja», «lag det», «fiks», «lag nr 1» mean go ahead.
- The UI text of all apps is **English**.
- The owner tests in the car and sends logs. When a log arrives, read it before you answer, and give
  times (hh:mm:ss) so the owner can relate them to what they did.

## What CarLink is
Android Auto–like system for a Lexus with an aftermarket Android head unit.

| Module | App | Runs on |
|---|---|---|
| `launcher` | `no.carlink.launcher` – home screen/launcher, music, map, split screen, joystick mouse | car screen |
| `sender` | `no.carlink.sender` – phone app (GPS, music, logs) | Galaxy S24 Ultra |
| `core` | shared code: `LinkService` (Wi-Fi link), `Hub` (state flows), `MediaListener`, `LocalResume`, `AppLog`, `ConnTimeline`, `Updater` | both |
| `diagnose` | `no.carlink.diagnose` – CarLink Diagnose (tests, mouse test, report). No services, no background work, never blocks sleep | car screen |

- Phone and screen talk over Wi-Fi (phone hotspot). Phone app auto-starts on the car's Bluetooth.
- Logs: `AppLog` (CarLink's own log, 1500 lines, `files/carlink-log.txt`), `ConnTimeline` (connection
  steps, «timeline: SUMMARY …» lines). «Send log to Claude» sends CarLink log + system log to the phone.

## The car screen (head unit)
- Qualcomm msm8953, really Android 9 / API 28 (claims «Android 12»), 1920×720, **rooted**.
- Root: run scripts as `su -c sh` with the script on **stdin**. `su -c "<cmd>"` fails («su: failed to exec»).
  In code: `Root.run()` (launcher), `rootSh()` (LinkService), `Sh.run()` (diagnose).
- adb over Wi-Fi: port **7575**. The screen's address on the hotspot has been `10.67.90.252`
  (check it if adb can't connect). The PC must be on the phone's hotspot.
- Vendor parts: `com.spd.*` apps, car bus via logcat tag `XCarBusLexusCar` (rxData frames),
  `SpdService-HandlerManager voltage=137` (battery in tenths of a volt), screensaver `com.spd.screen.saver`.
- At ACC off the vendor force-stops apps, pauses music and turns Wi-Fi off; CarLink is restarted ~10 s
  later by the notification-listener rebind.
- Joystick: car frames `0x57` = stick position, `0x22 2 <code> <state>` = buttons
  (0x10 press, 0x2a menu, 0x32/0x33 scroll up/down, 0x34 home), `0x21 2 0x11/0x10` = wheel next/previous.
  **The steering wheel has no play/pause button.**
- It has a 4G modem (telephony stack running). SIM/mobile data is not working yet – investigate before changing.

### Never do this
- **Do not disable or remove `com.spd.home`** (factory launcher). It broke start-up before; it is Android's fallback if CarLink crashes.
- **Do not run `dumpsys` against the `spd` / `spd.Hal` services** – it can hang or reboot the screen.
- Do not decompile the vendor's apps.
- Nothing in CarLink or Diagnose may keep the screen awake when the car is off (no wake locks/alarms for that).
- Do not factory-reset or reboot the screen without asking.

### Known behaviour
- YouTube Music after a cold start ignores play until it has loaded («player wasn't loaded»), and
  showing the app counts as user interaction so it may skip restoring the queue. It refuses
  MediaBrowser connections from CarLink. See `LocalResume.playLoop` for the current order of attempts.
- `SystemClock.elapsedRealtime()` is near 0 right after a reboot: never use `0L` to mean «long ago».
- Opening an Activity or sending a HOME intent closes CarLink's menus – never do it while the user is
  using the screen (`Hub.userActiveAt`, `Hub.frontApp`).

## Building
- Gradle, Kotlin, JDK 17, compileSdk 35, minSdk 24. All apps are signed with `carlink.keystore` (in repo).
- CI: `.github/workflows` builds launcher, sender and diagnose on every push to `main` and publishes them to
  the GitHub release **`siste`** (`CarLink-bilskjerm.apk`, `CarLink-Sender-telefon.apk`,
  `CarLink-Diagnose.apk`, `version.json`). versionCode = GitHub run number. The apps update themselves from there.
- There is no `gradlew` script in the repo: CI uses Gradle **8.11.1**. Locally, install Gradle 8.11.1 (or generate
  the wrapper with it: `gradle wrapper --gradle-version 8.11.1`) and the Android SDK (platform 35, build-tools).
- Local test build: `gradle :launcher:assembleDebug` (debug is signed with the same key), then
  `adb install -r -d launcher/build/outputs/apk/debug/launcher-debug.apk`. A local build has versionCode 1,
  so the app will later offer the GitHub version as an update – that is fine.
- When something works locally, commit and push to `main` so CI makes the real version.
  End commit messages with a Co-Authored-By line for Claude.

## Secrets
- `nokkel-til-github.txt` in the owner's PC folder is a GitHub token. Use it only for git; never print it,
  never commit it, never put it in a file in this repo (the repo is public).

## Planned (not built yet)
- YouTube Music playlists/queue in CarLink via the YouTube Data API (owner's own Google account).
- Theme for the screen's Android Settings (root overlay, switchable).
- CarLink's «Screen off» button should turn the backlight fully off like the vendor's pull-down menu
  (`SpdService-BKLight req:isOn=false reason=0x1`; services `spd` [com.spd.system.aidl.ISpdService]
  and `spd.Hal` [android.spd.ISpdHalService]).
