#!/bin/bash
# Installe l'APK, lance l'appli, capture écran + erreurs. Résultats dans test_output/.
set -x
OUT=test_output
rm -rf $OUT && mkdir -p $OUT
PKG=fr.jpb.meteowidget
adb install -r app/build/outputs/apk/release/app-release.apk > $OUT/install.txt 2>&1
adb logcat -c
adb shell am start -W -n $PKG/.MainActivity > $OUT/start1.txt 2>&1
sleep 12
adb exec-out screencap -p > $OUT/1_lancement.png
adb shell pm grant $PKG android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION
adb shell am force-stop $PKG
adb shell am start -W -n $PKG/.MainActivity > $OUT/start2.txt 2>&1
sleep 15
adb exec-out screencap -p > $OUT/2_avec_localisation.png
adb shell pidof $PKG > $OUT/pid.txt
# Clique sur quelques ambiances de l'aperçu
tap() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null
  adb shell cat /sdcard/ui.xml > /tmp/ui.xml
  b=$(grep -o "text=\"$1\"[^>]*bounds=\"[^\"]*\"" /tmp/ui.xml | grep -o 'bounds="[^"]*"' | head -1 | grep -o '[0-9]\+')
  [ -z "$b" ] && { echo "introuvable: $1" >> $OUT/taps.txt; return; }
  set -- $b
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}
i=3
for a in Coucher Nuit Nuageux Pluie; do
  tap "$a"; sleep 4
  adb exec-out screencap -p > $OUT/${i}_$a.png; i=$((i+1))
done
adb logcat -d -b crash > $OUT/crash.txt
adb logcat -d '*:W' | grep -v -E "chatty|GoogleApiManager" | tail -400 > $OUT/warnings.txt
exit 0
