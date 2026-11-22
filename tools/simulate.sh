#!/usr/bin/env bash
# Simulering av CarLink i Android-emulator. Kjøres av GitHub Actions.
# Lager skjermbilder av alle visninger, måler ytelse og ser etter krasj.
set -u
OUT=sim
mkdir -p "$OUT"
L=no.carlink.launcher/no.carlink.ReceiverActivity
S=no.carlink.sender/no.carlink.SenderActivity

shot() { sleep "$2"; adb exec-out screencap -p > "$OUT/$1.png"; echo "skjermbilde $1"; }
go()   { adb shell am start -W -n "$L" --ez demo true --es show "$1" > /dev/null; }

adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 1   # behold appens egne animasjoner
adb shell svc power stayon true
adb shell input keyevent 82

echo "== Installerer"
adb install -r -g ut/CarLink-bilskjerm.apk
adb install -r -g ut/CarLink-Sender-telefon.apk
adb shell dumpsys package no.carlink.launcher | grep -m1 versionName
adb logcat -c

# ---------- 21:9 bilskjerm (2520x1080)
adb shell wm size 2520x1080
adb shell wm density 320
sleep 3
go home;  shot 01-hjem-21x9 12
          shot 02-hjem-21x9-litt-senere 3
go nav;   shot 03-hjem-21x9-navigasjon 5
go split; shot 04-delt-skjerm-3-paneler 15
# Dra skillelinjen mellom kart og musikk mot venstre (test av dra-funksjonen)
adb shell input swipe 1230 560 860 560 900
          shot 05-delt-skjerm-etter-dra 3
go map;   shot 06-kart 12
go music; shot 07-musikk 5
go apps;  shot 08-apper 5
# Trykk spill/pause i musikkvisningen er dekket av demo; neste låt via hjem → bytter cover/farger
go home;  sleep 3
adb shell input keyevent KEYCODE_MEDIA_NEXT
          shot 09-hjem-21x9-ny-lat 6

echo "== Ytelse på hjemmeskjermen (21:9, demo med musikk og lydbølger)"
go home; sleep 5
adb shell dumpsys gfxinfo no.carlink.launcher reset > /dev/null
sleep 15
adb shell dumpsys gfxinfo no.carlink.launcher > "$OUT/grafikk-hjem.txt"
adb shell top -b -d 5 -n 2 -o PID,%CPU,%MEM,RES,NAME | grep -E "carlink|PID" | tail -4 > "$OUT/cpu-hjem.txt"
adb shell dumpsys meminfo no.carlink.launcher > "$OUT/minne-bilskjerm.txt"

# ---------- 16:9
adb shell wm size 1920x1080
sleep 3
go home;  shot 10-hjem-16x9 10

# ---------- Telefonen (sender)
adb shell wm size 1080x2400
adb shell wm density 420
sleep 3
adb shell am start -W -n "$S" > /dev/null
shot 11-telefon-sender 6
adb shell dumpsys meminfo no.carlink.sender > "$OUT/minne-telefon.txt"

# ---------- Krasj / ANR
adb logcat -d > "$OUT/logcat.txt"
adb shell wm size reset; adb shell wm density reset

{
  echo "CarLink – simuleringsrapport"
  echo "==========================="
  echo
  echo "Krasj (FATAL EXCEPTION) i CarLink:"
  grep -A 25 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -B2 -A25 "carlink" | head -60 || true
  echo
  echo "ANR (appen svarer ikke):"
  grep -E "ANR in no.carlink" "$OUT/logcat.txt" || echo "  ingen"
  echo
  echo "Bildeflyt på hjemmeskjermen i 15 s:"
  grep -E "Total frames rendered|Janky frames|50th percentile|90th percentile|99th percentile" "$OUT/grafikk-hjem.txt" | head -6
  echo
  echo "CPU og minne (hjemmeskjerm med demo):"
  cat "$OUT/cpu-hjem.txt"
  echo
  echo "Minne bilskjerm-appen (TOTAL PSS i kB):"
  grep -E "TOTAL PSS|TOTAL:" "$OUT/minne-bilskjerm.txt" | head -2
  echo "Minne telefon-appen (TOTAL PSS i kB):"
  grep -E "TOTAL PSS|TOTAL:" "$OUT/minne-telefon.txt" | head -2
  echo
  echo "APK-størrelser:"
  ls -l ut/*.apk | awk '{printf "  %s  %.1f MB\n", $9, $5/1048576}'
} > "$OUT/RAPPORT.txt"
cat "$OUT/RAPPORT.txt"

if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt" && grep -A3 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -q carlink; then
  echo "KRASJ FUNNET" > "$OUT/KRASJ.txt"
fi
exit 0
