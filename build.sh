#!/bin/bash
# Builds build/cleanhome.apk with the bare Android SDK tools (no Gradle, no dependencies).
set -euo pipefail
cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export PATH="$JAVA_HOME/bin:$PATH"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-34/android.jar"
KEY="$HOME/.android/tvhome.keystore"

rm -rf build && mkdir -p build/classes build/res build/dex build/gen-res/values

# Local settings (weather location, units) become string resources.
CONFIG=config.properties
[ -f "$CONFIG" ] || CONFIG=config.example.properties
prop() { grep -E "^$1=" "$CONFIG" | head -1 | cut -d= -f2- | sed 's/&/\&amp;/g; s/</\&lt;/g'; }
cat > build/gen-res/values/config.xml <<XML
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="weather_place" translatable="false">$(prop WEATHER_PLACE)</string>
    <string name="weather_lat" translatable="false">$(prop WEATHER_LAT)</string>
    <string name="weather_lon" translatable="false">$(prop WEATHER_LON)</string>
    <string name="weather_unit" translatable="false">$(prop WEATHER_UNIT)</string>
</resources>
XML

"$BT/aapt2" compile --dir res -o build/res/res.zip
"$BT/aapt2" compile build/gen-res/values/config.xml -o build/res/
"$BT/aapt2" link -o build/unsigned.apk -I "$JAR" --manifest AndroidManifest.xml \
    --java build/gen build/res/res.zip build/res/values_config.arsc.flat
javac -nowarn --release 8 -classpath "$JAR" -d build/classes \
    $(find src build/gen -name '*.java') 2> build/javac.log || { cat build/javac.log; exit 1; }
"$BT/d8" --min-api 25 --release --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
(cd build/dex && zip -q ../unsigned.apk classes.dex)

if [ ! -f "$KEY" ]; then
    mkdir -p "$(dirname "$KEY")"
    keytool -genkeypair -keystore "$KEY" -storepass android -keypass android -alias tvhome \
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=tvhome" >/dev/null
fi
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner" sign --ks "$KEY" --ks-pass pass:android --out build/cleanhome.apk build/aligned.apk
ls -lh build/cleanhome.apk
