#!/bin/bash
# Installe l'APK, pose le widget sur l'écran d'accueil et capture ses animations.
# Résultats dans test_output/.
set -x
OUT=test_output
rm -rf $OUT && mkdir -p $OUT
PKG=fr.jpb.meteowidget
adb install -r app/build/outputs/apk/release/app-release.apk > $OUT/install.txt 2>&1
adb shell pm grant $PKG android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION
adb logcat -c

tap() {  # tap "regex" "x y de secours"
  # uiautomator attend un écran immobile : avec les animations il échoue souvent
  timeout 20 adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  adb pull /sdcard/ui.xml $OUT/ui_last.xml > /dev/null 2>&1
  xy=$(python3 tools/ui_find.py $OUT/ui_last.xml "$1" 2>/dev/null)
  [ -z "$xy" ] && xy="$2"
  echo "tap $1 -> $xy" >> $OUT/taps.txt
  adb shell input tap $xy
}

burst() {  # burst nom nombre pause
  for i in $(seq 1 $2); do
    adb exec-out screencap -p > $OUT/$1_$i.png
    sleep $3
  done
}

ambience() {  # ambience COND:PHASE nom nombre pause
  adb shell am start -S -n $PKG/.MainActivity --es test_ambience $1 > /dev/null
  sleep 4
  adb shell input keyevent KEYCODE_HOME
  sleep 4
  burst $2 $3 $4
}

# 1. Lancement de l'appli (position : Paris) + demande d'ajout du widget
adb shell pm grant $PKG android.permission.ACCESS_BACKGROUND_LOCATION
adb shell cmd location set-location-enabled true
adb shell settings put secure location_mode 3
adb shell cmd location is-location-enabled > $OUT/localisation.txt 2>&1
adb emu geo fix 2.3522 48.8566
for i in 1 2 3 4; do adb emu geo fix 2.3522 48.8566; sleep 5; done
adb shell dumpsys location | grep -i -A2 "last location" | head -20 >> $OUT/localisation.txt
adb shell am start -W -n $PKG/.MainActivity --ez pin true --ei test_interval_s 60 > $OUT/start.txt 2>&1
for i in $(seq 1 30); do
  adb shell dumpsys window | grep -q "mCurrentFocus.*AddItem" && break
  sleep 2
done
adb shell dumpsys window | grep mCurrentFocus >> $OUT/taps.txt
adb exec-out screencap -p > $OUT/0_appli.png
for i in 1 2 3; do
  adb shell dumpsys window | grep -q "mCurrentFocus.*AddItem" || break
  tap "^add|ajouter" "831 2253"
  sleep 3
done
adb exec-out screencap -p > $OUT/0_apres_ajout.png

# 2. Mises à jour en veille profonde (Doze) : alarme toutes les 60 s, déplacement Paris -> Lyon
adb shell input keyevent KEYCODE_HOME
sleep 5
adb exec-out screencap -p > $OUT/1_paris.png
adb shell dumpsys battery unplug
adb shell input keyevent KEYCODE_SLEEP
sleep 2
adb shell dumpsys deviceidle force-idle > $OUT/doze.txt 2>&1
sleep 150
for i in $(seq 1 7); do adb emu geo fix 4.8357 45.7640; sleep 30; done
adb shell dumpsys deviceidle get deep >> $OUT/doze.txt
adb shell dumpsys deviceidle unforce >> $OUT/doze.txt
adb shell dumpsys battery reset
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
sleep 4
adb exec-out screencap -p > $OUT/1_lyon.png
adb logcat -d -s MeteoPaysage:I > $OUT/mises_a_jour.txt

# 3. Animations sur l'écran d'accueil
ambience CLEAR:NIGHT nuit 10 0.2
ambience RAIN:DAY pluie 6 0.1
ambience CLEAR:DAY soleil 5 2
ambience SNOW:NIGHT neige 4 1

adb shell dumpsys alarm | grep -A3 $PKG | head -20 > $OUT/alarmes.txt
adb shell pidof $PKG > $OUT/pid.txt
adb logcat -d -b crash > $OUT/crash.txt
adb logcat -d '*:W' | grep -v -E "chatty|GoogleApiManager" | tail -400 > $OUT/warnings.txt
exit 0
