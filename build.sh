#!/data/data/com.termux/files/usr/bin/bash
# NovelSpeak on-device build (no Android SDK / Gradle required).
#
# Toolchain used here: Termux packages (aapt2, kotlinc, d8, apksigner, zip, openjdk-21) plus a
# platform android.jar. The same sources build in Android Studio with the Gradle files in
# android/ (see README).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
APP="$ROOT/app"
BUILD="$ROOT/build"
DEPS="${DEPS:-$HOME/build/deps}"
ANDROID_JAR="${ANDROID_JAR:-$HOME/build/cache/sdk34/android-34/android.jar}"
KOTLIN_STDLIB="${KOTLIN_STDLIB:-/data/data/com.termux/files/usr/opt/kotlin/lib/kotlin-stdlib.jar}"
PKG="app.novelspeak"
ABI="${ABI:-arm64-v8a}"
MIN_SDK=24
TARGET_SDK=34

mkdir -p "$BUILD/stage" "$BUILD/dex"

echo "==> 1/7 aapt2 link (manifest + assets)"
aapt2 link \
  -o "$BUILD/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$APP/AndroidManifest.xml" \
  -A "$APP/assets" \
  --min-sdk-version $MIN_SDK \
  --target-sdk-version $TARGET_SDK \
  --version-code 13 --version-name 0.3.1

echo "==> 2/7 preparing sherpa-onnx classes + native libs ($ABI)"
if [ ! -f "$DEPS/aar/classes.jar" ]; then
  mkdir -p "$DEPS/aar"
  unzip -o -q "$DEPS/sherpa.aar" classes.jar -d "$DEPS/aar"
fi
rm -rf "$BUILD/jni"
mkdir -p "$BUILD/jni/$ABI"
unzip -o -q -j "$DEPS/sherpa.aar" "jni/$ABI/*" -d "$BUILD/jni/$ABI"

echo "==> 3/7 kotlinc"
rm -rf "$BUILD/classes"
kotlinc "$APP/src" \
  -classpath "$ANDROID_JAR:$DEPS/aar/classes.jar:$DEPS/commons-compress.jar:$DEPS/commons-io.jar" \
  -jvm-target 1.8 \
  -nowarn \
  -d "$BUILD/classes"

echo "==> 4/7 d8 (dex)"
rm -f "$BUILD/classes.jar"
( cd "$BUILD/classes" && jar cf "$BUILD/classes.jar" . )
rm -rf "$BUILD/dex"; mkdir -p "$BUILD/dex"
# Prefer a modern R8/D8 from a jar: Termux's d8 3.3 crashes on newer libraries (commons-io).
if [ -f "$DEPS/r8.jar" ]; then
  D8=(java -cp "$DEPS/r8.jar" com.android.tools.r8.D8)
  LIBS=("$BUILD/classes.jar" "$DEPS/aar/classes.jar" "$DEPS/commons-compress.jar" "$DEPS/commons-io.jar" "$KOTLIN_STDLIB")
else
  D8=(d8)
  # Old d8 cannot parse multi-release jars; strip module-info before feeding it.
  for j in commons-compress commons-io; do
    if [ ! -f "$DEPS/$j-d8.jar" ]; then
      cp "$DEPS/$j.jar" "$DEPS/$j-d8.jar"
      zip -q -d "$DEPS/$j-d8.jar" 'META-INF/versions/*' 'module-info.class' 2>/dev/null || true
    fi
  done
  LIBS=("$BUILD/classes.jar" "$DEPS/aar/classes.jar" "$DEPS/commons-compress-d8.jar" "$DEPS/commons-io-d8.jar" "$KOTLIN_STDLIB")
fi
"${D8[@]}" --lib "$ANDROID_JAR" --min-api $MIN_SDK --output "$BUILD/dex" "${LIBS[@]}"

echo "==> 5/7 packaging"
rm -rf "$BUILD/stage"; mkdir -p "$BUILD/stage/lib/$ABI"
cp "$BUILD/base.apk" "$BUILD/unsigned.apk"
cp "$BUILD/dex/classes.dex" "$BUILD/stage/classes.dex"
cp "$BUILD/jni/$ABI/"*.so "$BUILD/stage/lib/$ABI/"
( cd "$BUILD/stage" && zip -q -X -r "$BUILD/unsigned.apk" classes.dex lib )

echo "==> 6/7 signing"
KEYSTORE="$BUILD/debug.keystore"
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" -alias androiddebugkey \
    -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=NovelSpeak Debug,O=NovelSpeak,C=IN" >/dev/null 2>&1
fi
apksigner sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --out "$BUILD/NovelSpeak-debug.apk" "$BUILD/unsigned.apk"

echo "==> 7/7 verify"
apksigner verify --print-certs "$BUILD/NovelSpeak-debug.apk" | head -5
ls -la "$BUILD/NovelSpeak-debug.apk"
echo
echo "Install with:  termux-open $BUILD/NovelSpeak-debug.apk"
