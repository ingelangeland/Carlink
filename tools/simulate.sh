#!/usr/bin/env bash
# Simulering av CarLink i Android-emulator. Kjøres av GitHub Actions, én gang per skjermformat.
# SIM=21x9 (1920x824) eller SIM=16x9 (1920x1080). Emulatoren starter direkte i riktig størrelse.
set -u
ADB_BIN="$(command -v adb)"
adb() { timeout 120 "$ADB_BIN" "$@"; }
alive() { [ "$(timeout 10 "$ADB_BIN" get-state 2>/dev/null)" = "device" ]; }
SIM="${SIM:-16x9}"
TAG="${TAG:-}"
OUT=sim
mkdir -p "$OUT"
L=no.carlink.launcher/no.carlink.ReceiverActivity
S=no.carlink.sender/no.carlink.SenderActivity

if [ "$SIM" = "21x9" ]; then DENS=240; DIV_X=925; DIV_Y=412; NEXT_X=280; NEXT_Y=730; GAP_X=792;  GAP_Y=357
else                         DENS=320; DIV_X=950; DIV_Y=540; NEXT_X=374; NEXT_Y=956; GAP_X=1072; GAP_Y=345; fi

shot() {
  sleep "$2"
  if ! alive; then echo "EMULATOREN SVARER IKKE (ved $1)" | tee -a "$OUT/EMULATOR-$SIM$TAG.txt"; return; fi
  adb exec-out screencap -p > "$OUT/$SIM$TAG-$1.png"; echo "skjermbilde $SIM$TAG-$1"
}
EXTRA="${EXTRA:-}"
go() { adb shell am start -W -n "$L" --ez demo true --es show "$1" $EXTRA > /dev/null; }

adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell svc power stayon true
adb shell wm density "$DENS"
adb shell input keyevent 82
adb shell wm size

# Diagnose: skjermbilde av vanlig Android før CarLink startes, og fortløpende systemlogg
"$ADB_BIN" logcat -v time > "$OUT/logcat-stream.txt" 2>&1 &
shot 00-for-carlink 2

echo "== Installerer"
adb install -r -g ut/CarLink-bilskjerm.apk
adb install -r -g ut/CarLink-Sender-telefon.apk

go home;  shot 01-hjem 12
          shot 02-hjem-litt-senere 3
go nav;   shot 03-hjem-navigasjon 5
go split; shot 04-delt-skjerm 15
adb shell input swipe $DIV_X $DIV_Y $((DIV_X-280)) $DIV_Y 900
          shot 05-delt-skjerm-etter-dra 3
go map;   shot 06-kart 15
go music; shot 07-musikk 5
go apps;  shot 08-apper-panel 5
go drawer; shot 08b-appoversikt 4
# Trykk mellom to apper (skal IKKE lukke), så trykk i hjørnet utenfor (skal lukke → hjemmeskjerm)
adb shell input tap $GAP_X $GAP_Y
          shot 08c-trykk-mellom-apper 2
adb shell input tap 80 760
          shot 08d-trykk-utenfor 2
go home;  sleep 4
adb shell input tap $NEXT_X $NEXT_Y      # «neste låt» i musikklinjen → nytt cover og nye farger
          shot 09-hjem-neste-lat 6

echo "== Ytelse"
go home; sleep 5
adb shell dumpsys gfxinfo no.carlink.launcher reset > /dev/null
sleep 15
adb shell dumpsys gfxinfo no.carlink.launcher > "$OUT/grafikk.txt"
adb shell top -b -d 5 -n 2 -o PID,%CPU,%MEM,RES,NAME | grep -E "carlink|PID" | tail -4 > "$OUT/cpu.txt"
adb shell dumpsys meminfo no.carlink.launcher > "$OUT/minne-bilskjerm.txt"

if [ "$SIM" = "16x9" ]; then
  adb shell am start -W -n "$S" > /dev/null
  shot 10-telefon-sender 6
  adb shell dumpsys meminfo no.carlink.sender > "$OUT/minne-telefon.txt"
fi

adb logcat -d > "$OUT/logcat.txt"
{
  echo "CarLink – simuleringsrapport ($SIM)"
  echo "==============================="
  echo; echo "Krasj i CarLink:"
  grep -A 30 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -B3 -A30 "carlink" | head -60 || true
  echo; echo "ANR (appen svarer ikke):"; grep -E "ANR in no.carlink" "$OUT/logcat.txt" || echo "  ingen"
  echo; echo "Bildeflyt på hjemmeskjermen i 15 s:"
  grep -E "Total frames rendered|Janky frames|50th percentile|90th percentile|99th percentile" "$OUT/grafikk.txt" | head -6
  echo; echo "CPU (hjemmeskjerm med demo, programvaregrafikk i emulator):"; cat "$OUT/cpu.txt"
  echo; echo "Minne (TOTAL PSS i kB):"
  echo -n "  bilskjerm: "; grep -m1 "TOTAL PSS" "$OUT/minne-bilskjerm.txt" || true
  [ -f "$OUT/minne-telefon.txt" ] && { echo -n "  telefon:   "; grep -m1 "TOTAL PSS" "$OUT/minne-telefon.txt"; }
  echo; echo "APK-størrelser:"; ls -l ut/*.apk | awk '{printf "  %s  %.1f MB\n", $9, $5/1048576}'
} > "$OUT/RAPPORT-$SIM$TAG.txt"
cat "$OUT/RAPPORT-$SIM$TAG.txt"
tail -400 "$OUT/logcat-stream.txt" > "$OUT/LOGG-SISTE-$SIM$TAG.txt" 2>/dev/null
if grep -A3 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -q carlink; then echo "KRASJ FUNNET" > "$OUT/KRASJ-$SIM$TAG.txt"; fi
exit 0
