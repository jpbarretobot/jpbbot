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
adb logcat -d -b crash > $OUT/crash.txt
adb logcat -d '*:W' | grep -v -E "chatty|GoogleApiManager" | tail -400 > $OUT/warnings.txt
exit 0
