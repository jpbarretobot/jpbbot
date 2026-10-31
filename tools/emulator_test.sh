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
  adb shell am start -n $PKG/.MainActivity --es test_ambience $1 > /dev/null
  sleep 4
  adb shell input keyevent KEYCODE_HOME
  sleep 4
  burst $2 $3 $4
}

# 1. Lancement de l'appli + demande d'ajout du widget
adb shell am start -W -n $PKG/.MainActivity --es test_ambience CLEAR:NIGHT --ez pin true > $OUT/start.txt 2>&1
sleep 6
adb exec-out screencap -p > $OUT/0_appli.png
tap "^add|ajouter" "831 2253"
sleep 3
adb exec-out screencap -p > $OUT/0_apres_ajout.png

# 2. Animations sur l'écran d'accueil
ambience CLEAR:NIGHT nuit 10 0.2
ambience RAIN:DAY pluie 6 0.1
ambience CLEAR:DAY soleil 5 2
ambience SNOW:NIGHT neige 4 1

adb shell dumpsys alarm | grep -A3 $PKG | head -20 > $OUT/alarmes.txt
adb shell pidof $PKG > $OUT/pid.txt
adb logcat -d -b crash > $OUT/crash.txt
adb logcat -d '*:W' | grep -v -E "chatty|GoogleApiManager" | tail -400 > $OUT/warnings.txt
exit 0
