#!/usr/bin/env bash
# Minimal no-Gradle APK build for the route5 spike.
set -euo pipefail
cd "$(dirname "$0")"
SDK=~/Library/Android/sdk
BT=$SDK/build-tools/36.0.0
JAR=$SDK/platforms/android-37.0/android.jar
JH="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
rm -rf build && mkdir -p build/gen build/classes
$BT/aapt2 compile --dir res -o build/res.zip
$BT/aapt2 link -I $JAR --manifest AndroidManifest.xml --min-sdk-version 31 --target-sdk-version 34 \
  --java build/gen -o build/base.apk build/res.zip
"$JH/bin/javac" -nowarn --release 11 -cp $JAR -d build/classes $(find src build/gen -name '*.java') 2>&1 | grep -v '^Note' || true
JAVA_HOME="$JH" $BT/d8 --min-api 31 --lib $JAR --output build $(find build/classes -name '*.class')
cp build/base.apk build/unsigned.apk
(cd build && zip -q unsigned.apk classes.dex)
$BT/zipalign -f 4 build/unsigned.apk build/aligned.apk
JAVA_HOME="$JH" $BT/apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android --out build/route5.apk build/aligned.apk
ls -la build/route5.apk
