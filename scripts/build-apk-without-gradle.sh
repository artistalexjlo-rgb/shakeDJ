#!/usr/bin/env bash
# Builds a debug APK from the same sources without Gradle / Android Gradle Plugin.
# Useful in sandboxes where Google's Maven repository is unreachable. Needs:
#   - JDK (javac, keytool)
#   - an Android SDK platform android.jar ($ANDROID_JAR, default: platforms/android-35)
#   - aapt2, zipalign, apksigner, and dx (Ubuntu: dalvik-exchange) or d8
# Output: build/manual/ShakeDJ-debug.apk
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-/opt/android-sdk}"
ANDROID_JAR="${ANDROID_JAR:-$SDK/platforms/android-35/android.jar}"
# Older aapt2 builds (e.g. Debian's) can't parse the sparse resource table of newer platforms;
# linking resources against an older android.jar is fine since only long-standing attributes are used.
RES_ANDROID_JAR="${RES_ANDROID_JAR:-$ANDROID_JAR}"
BT="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1 || true)"
AAPT2="${AAPT2:-$BT/aapt2}"
ZIPALIGN="${ZIPALIGN:-$BT/zipalign}"
APKSIGNER="${APKSIGNER:-$(command -v apksigner || echo "$BT/apksigner")}"
PKG=com.shakedj.app
MIN_SDK=26
TARGET_SDK=35
VERSION_CODE="${VERSION_CODE:-4}"
VERSION_NAME="${VERSION_NAME:-0.4}"

SRC="$ROOT/app/src/main"
OUT="$ROOT/build/manual"
rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "> resources"
sed "s#<manifest #<manifest package=\"$PKG\" #" "$SRC/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"
"$AAPT2" compile --dir "$SRC/res" -o "$OUT/res.zip"
"$AAPT2" link -I "$RES_ANDROID_JAR" --manifest "$OUT/AndroidManifest.xml" \
    --min-sdk-version $MIN_SDK --target-sdk-version $TARGET_SDK \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
    --java "$OUT/gen" -o "$OUT/base.apk" "$OUT/res.zip"

echo "> java"
find "$SRC/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -nowarn -Xlint:-options -encoding UTF-8 -source 8 -target 8 \
    -bootclasspath "$ANDROID_JAR" -d "$OUT/classes" @"$OUT/sources.txt"

echo "> dex"
if command -v d8 >/dev/null; then
    d8 --min-api $MIN_SDK --lib "$ANDROID_JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
else
    dalvik-exchange --dex --min-sdk-version=$MIN_SDK --output="$OUT/dex/classes.dex" "$OUT/classes"
fi

echo "> package"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q "$OUT/unsigned.apk" classes.dex)
"$ZIPALIGN" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

KS="${KEYSTORE:-$HOME/.android/debug.keystore}"
if [ ! -f "$KS" ]; then
    mkdir -p "$(dirname "$KS")"
    keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias androiddebugkey \
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US" >/dev/null
fi
"$APKSIGNER" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --out "$OUT/ShakeDJ-debug.apk" "$OUT/aligned.apk"
"$APKSIGNER" verify "$OUT/ShakeDJ-debug.apk"
echo "OK: $OUT/ShakeDJ-debug.apk ($(du -h "$OUT/ShakeDJ-debug.apk" | cut -f1))"
