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

# Koordinater regnes ut fra skjermstørrelse og tetthet (dp → piksler)
DENS="${DENS:-240}"
read W H < <(timeout 30 "$ADB_BIN" shell wm size | tail -1 | sed 's/.*: //; s/x/ /')
# Launcheren skalerer selv opp lave tettheter (skjermhøyde ≈ 500 dp), regn med den effektive
EFF=$(awk -v h="$H" -v w="$W" -v s="$DENS" 'BEGIN{m=(h<w)?h:w; if (m*160/s > 600) printf "%d", int(m*160/500); else print s}')
dp() { awk -v d="$1" -v s="$EFF" 'BEGIN{printf "%d", d*s/160}'; }
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
echo "Skjerm: ${W}x${H} @ ${DENS} dpi (CarLink bruker ${EFF})"
# Skjul emulatorens egne systemdialoger så de ikke dekker skjermbildene
adb shell settings put global hide_error_dialogs 1
adb shell settings put secure immersive_mode_confirmations confirmed
adb shell input keyevent 82
adb shell wm size

# Diagnose: skjermbilde av vanlig Android før CarLink startes, og fortløpende systemlogg
"$ADB_BIN" logcat -v time > "$OUT/logcat-stream.txt" 2>&1 &
shot 00-for-carlink 2

if [ "$SIM" = "telefon" ]; then
  adb install -r -g ut/CarLink-Sender-telefon.apk
  adb shell am start -W -n "$S" > /dev/null
  shot 01-sender-veiviser 6
  adb shell input keyevent 4
  shot 01b-sender-batteri-sporsmal 4
  adb shell input keyevent 4
  shot 01c-sender 3
  adb shell input swipe 540 1800 540 600 400
  shot 02-sender-rullet 3
  adb shell dumpsys meminfo no.carlink.sender > "$OUT/minne-telefon.txt"
  { echo "CarLink Sender på telefon (S24 Ultra-lik skjerm)"; grep -m1 "TOTAL PSS" "$OUT/minne-telefon.txt"; } > "$OUT/RAPPORT-$SIM.txt"
  exit 0
fi

echo "== Installerer"
adb install -r -g ut/CarLink-bilskjerm.apk
adb install -r -g ut/CarLink-Sender-telefon.apk

go home;  shot 01-hjem 12
adb logcat -d -s CarLinkUi:* > "$OUT/ui.txt" 2>&1 || true
adb shell wm density >> "$OUT/ui.txt" 2>&1 || true
# Bruk tettheten CarLink faktisk valgte (står i loggen)
E2=$(grep -o "bruker [0-9]* dpi" "$OUT/ui.txt" | tail -1 | grep -o "[0-9]*")
[ -n "$E2" ] && EFF=$E2
echo "CarLink-tetthet: $EFF"
# Skillelinjen mellom panel 1 og 2 i delt visning (meny 88dp, marg 10dp, 45 % / 30 % / 25 %)
DIV_X=$(awk -v W="$W" -v s="$EFF" 'BEGIN{k=s/160; avail=W-(88+20)*k-2*14*k; printf "%d", (98)*k + 0.45*avail + 7*k}')
DIV_Y=$((H / 2))
# «Neste låt» i musikklinjen
NEXT_X=$(dp 187); NEXT_Y=$((H - $(dp 62)))
# Trykk utenfor app-oversikten (nederst til venstre)
OUT_X=$(dp 30); OUT_Y=$((H - $(dp 20)))

          shot 02-hjem-litt-senere 3
go nav;   shot 03-hjem-navigasjon 5
go split; shot 04-delt-skjerm 15
adb shell input swipe $DIV_X $DIV_Y $((DIV_X-280)) $DIV_Y 900
          shot 05-delt-skjerm-etter-dra 3
go map;   shot 06-kart 15
go music; shot 07-musikk 5
go apps;  shot 08-apper-panel 5
go drawer; shot 08b-appoversikt 4
# Trykk i hjørnet utenfor (skal lukke → hjemmeskjerm)
adb shell input tap $OUT_X $OUT_Y
          shot 08d-trykk-utenfor 2
go home;  sleep 4
adb shell input tap $NEXT_X $NEXT_Y      # «neste låt» i musikklinjen → nytt cover og nye farger
          shot 09-hjem-neste-lat 6

echo "== Ytelse"
# --- Flyt i menyene: åpne/lukke app-oversikten med knappen og dra i delt skjerm (målt)
GRID_X=$(dp 54); GRID_Y=$(dp 44)
go home; sleep 4
adb shell dumpsys gfxinfo no.carlink.launcher reset > /dev/null
for i in 1 2 3 4; do
  adb shell input tap $GRID_X $GRID_Y; sleep 1.5
  adb shell input tap $OUT_X $OUT_Y;   sleep 1.5
done
adb shell dumpsys gfxinfo no.carlink.launcher > "$OUT/grafikk-oversikt.txt"
go split; sleep 6
adb shell dumpsys gfxinfo no.carlink.launcher reset > /dev/null
for i in 1 2 3; do
  adb shell input swipe $DIV_X $DIV_Y $((DIV_X-250)) $DIV_Y 700; sleep 0.8
  adb shell input swipe $((DIV_X-250)) $DIV_Y $DIV_X $DIV_Y 700; sleep 0.8
done
adb shell dumpsys gfxinfo no.carlink.launcher > "$OUT/grafikk-dra.txt"
# Samme uten kart (skiller kartets tegnekostnad fra selve draingen)
go split2; sleep 4
DIV2_X=$(awk -v W="$W" -v s="$EFF" 'BEGIN{k=s/160; avail=W-(88+20)*k-14*k; printf "%d", (98)*k + 0.5*avail + 7*k}')
adb shell dumpsys gfxinfo no.carlink.launcher reset > /dev/null
for i in 1 2 3; do
  adb shell input swipe $DIV2_X $DIV_Y $((DIV2_X-250)) $DIV_Y 700; sleep 0.8
  adb shell input swipe $((DIV2_X-250)) $DIV_Y $DIV2_X $DIV_Y 700; sleep 0.8
done
adb shell dumpsys gfxinfo no.carlink.launcher > "$OUT/grafikk-dra-utenkart.txt"

# --- Ekte tilkobling uten Bluetooth: skjermen finner telefonen over Wi-Fi (samme enhet i testen)
# Gi tillatelsene på forhånd så veiviserne ikke dekker bildene
adb shell cmd role add-role-holder android.app.role.HOME no.carlink.launcher 2>/dev/null || true
adb shell cmd notification allow_listener no.carlink.launcher/no.carlink.MediaListener 2>/dev/null || true
adb shell cmd notification allow_listener no.carlink.sender/no.carlink.MediaListener 2>/dev/null || true
adb shell dumpsys deviceidle whitelist +no.carlink.sender > /dev/null 2>&1 || true
adb shell am force-stop no.carlink.launcher
adb shell am start -W -n "$L" > /dev/null
sleep 3
adb shell am start -W -n "$S" --ez autoconnect true > /dev/null
          shot 10-sender-wifi-tilkobling 12
adb shell am start -W -n "$L" > /dev/null
          shot 11-skjerm-wifi-tilkoblet 4
adb logcat -d | grep -E "Tilkoblet|CarLink" | tail -5 > "$OUT/wifi.txt" || true
adb shell am force-stop no.carlink.sender
adb shell am force-stop no.carlink.launcher

go home; sleep 5
adb shell dumpsys gfxinfo no.carlink.launcher reset > /dev/null
sleep 15
adb shell dumpsys gfxinfo no.carlink.launcher > "$OUT/grafikk.txt"
adb shell top -b -d 5 -n 2 -o PID,%CPU,%MEM,RES,NAME | grep -E "carlink|PID" | tail -4 > "$OUT/cpu.txt"
adb shell dumpsys meminfo no.carlink.launcher > "$OUT/minne-bilskjerm.txt"


adb logcat -d > "$OUT/logcat.txt"
{
  echo "CarLink – simuleringsrapport ($SIM)"
  echo "==============================="
  echo; echo "Krasj i CarLink:"
  grep -A 30 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -B3 -A30 "carlink" | head -60 || true
  echo; echo "ANR (appen svarer ikke):"; grep -E "ANR in no.carlink" "$OUT/logcat.txt" || echo "  ingen"
  echo; echo "Bildeflyt på hjemmeskjermen i 15 s:"
  grep -E "Total frames rendered|Janky frames|50th percentile|90th percentile|99th percentile" "$OUT/grafikk.txt" | head -6
  echo; echo "Flyt: åpne/lukke app-oversikten 4 ganger:"
  grep -E "Total frames rendered|Janky frames:|50th percentile|90th percentile|50th gpu|90th gpu" "$OUT/grafikk-oversikt.txt" | head -6
  echo; echo "Flyt: dra skillelinjen i delt skjerm 6 ganger:"
  grep -E "Total frames rendered|Janky frames:|50th percentile|90th percentile|50th gpu|90th gpu" "$OUT/grafikk-dra.txt" | head -6
  echo; echo "Flyt: dra i delt skjerm uten kart:"
  grep -E "Total frames rendered|Janky frames:|50th percentile|90th percentile|50th gpu|90th gpu" "$OUT/grafikk-dra-utenkart.txt" | head -6
  echo; echo "CPU (hjemmeskjerm med demo, programvaregrafikk i emulator):"; cat "$OUT/cpu.txt"
  echo; echo "Minne (TOTAL PSS i kB):"
  echo -n "  bilskjerm: "; grep -m1 "TOTAL PSS" "$OUT/minne-bilskjerm.txt" || true
  [ -f "$OUT/minne-telefon.txt" ] && { echo -n "  telefon:   "; grep -m1 "TOTAL PSS" "$OUT/minne-telefon.txt"; }
  echo; echo "Skjermskalering:"; cat "$OUT/ui.txt" 2>/dev/null | tail -6
  echo; echo "APK-størrelser:"; ls -l ut/*.apk | awk '{printf "  %s  %.1f MB\n", $9, $5/1048576}'
} > "$OUT/RAPPORT-$SIM$TAG.txt"
cat "$OUT/RAPPORT-$SIM$TAG.txt"
tail -400 "$OUT/logcat-stream.txt" > "$OUT/LOGG-SISTE-$SIM$TAG.txt" 2>/dev/null
if grep -A3 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -q carlink; then echo "KRASJ FUNNET" > "$OUT/KRASJ-$SIM$TAG.txt"; fi
exit 0
