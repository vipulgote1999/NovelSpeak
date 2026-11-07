#!/usr/bin/env bash
# NovelSpeak on-device build (no Android SDK / Gradle required).
#
# Toolchain used here: Termux packages (aapt2, kotlinc, d8, apksigner, zip, openjdk-21) plus a
# platform android.jar. The same sources build on a desktop with the Android SDK build-tools;
# see README and RELEASING.md.
#
# DEBUGGABLE=1 adds android:debuggable="true" (needed by tools/tts-benchmark/run.sh). It is
# off by default so release builds do not ship a debuggable manifest.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
APP="$ROOT/app"
BUILD="$ROOT/build"
MANIFEST="${MANIFEST:-$APP/AndroidManifest.xml}"
DEPS="${DEPS:-$HOME/build/deps}"
# Play requires targetSdk 36 for new apps and updates since 2026-08-31. The manifest is the
# source of truth for the shipped target; this value must match it.
MIN_SDK="${MIN_SDK:-24}"
TARGET_SDK="${TARGET_SDK:-36}"
MANIFEST_TARGET_SDK="$(sed -n 's/.*android:targetSdkVersion="\([^"]*\)".*/\1/p' "$MANIFEST" | head -1)"
if [ -n "$MANIFEST_TARGET_SDK" ] && [ "$TARGET_SDK" != "$MANIFEST_TARGET_SDK" ]; then
  echo "TARGET_SDK=$TARGET_SDK does not match the manifest targetSdkVersion=$MANIFEST_TARGET_SDK" >&2
  exit 1
fi
if [ -z "${ANDROID_JAR:-}" ]; then
  if [ -n "${ANDROID_HOME:-}" ] && [ -f "$ANDROID_HOME/platforms/android-$TARGET_SDK/android.jar" ]; then
    ANDROID_JAR="$ANDROID_HOME/platforms/android-$TARGET_SDK/android.jar"
  else
    ANDROID_JAR="$HOME/build/cache/sdk$TARGET_SDK/android-$TARGET_SDK/android.jar"
  fi
fi
KOTLIN_STDLIB="${KOTLIN_STDLIB:-/data/data/com.termux/files/usr/opt/kotlin/lib/kotlin-stdlib.jar}"
PKG="app.novelspeak"
ABI="${ABI:-arm64-v8a}"
DEBUGGABLE="${DEBUGGABLE:-0}"
OUT_APK="${OUT_APK:-$BUILD/NovelSpeak-debug.apk}"
if [ -n "${KEYSTORE:-}" ]; then
  KS_ALIAS="${KS_ALIAS:?KS_ALIAS is required when KEYSTORE is set}"
  KS_PASS="${KS_PASS:?KS_PASS is required when KEYSTORE is set}"
else
  KEYSTORE="$BUILD/debug.keystore"
  KS_ALIAS="${KS_ALIAS:-androiddebugkey}"
  KS_PASS="${KS_PASS:-android}"
fi
KEY_PASS="${KEY_PASS:-$KS_PASS}"
ZIPALIGN="${ZIPALIGN:-}"
# zipalign must run before signing. Auto-detect it when present; Termux users may not have it.
if [ -z "$ZIPALIGN" ] && command -v zipalign >/dev/null 2>&1; then
  ZIPALIGN="$(command -v zipalign)"
fi

mkdir -p "$BUILD/stage" "$BUILD/dex" "$(dirname "$OUT_APK")"

# Optional compiled resources (app/res). The app is assets/WebView-first, but a few
# framework resources (network security config, backup rules, future UI XML) need aapt2.
RES_ARGS=()
RES_DIRS=("$APP/res")
if [ -n "${EXTRA_RES_DIR:-}" ]; then
  RES_DIRS+=("$EXTRA_RES_DIR")
fi
RES_INDEX=0
for RES_DIR in "${RES_DIRS[@]}"; do
  if [ -d "$RES_DIR" ]; then
    echo "==> 0/8 aapt2 compile (resources: $RES_DIR)"
    RES_ZIP="$BUILD/res-$RES_INDEX.zip"
    rm -f "$RES_ZIP"
    aapt2 compile --dir "$RES_DIR" -o "$RES_ZIP"
    RES_ARGS+=("$RES_ZIP")
    RES_INDEX=$((RES_INDEX + 1))
  fi
done

echo "==> 1/8 aapt2 link (manifest + assets + resources)"
LINK_ARGS=()
if [ "$DEBUGGABLE" = "1" ]; then
  # The bench/debug harness uses `run-as` to pull result JSON. This stays opt-in so a
  # release invocation (KEYSTORE=...) cannot accidentally ship a debuggable app.
  LINK_ARGS+=(--debug-mode)
fi
aapt2 link \
  -o "$BUILD/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$MANIFEST" \
  -A "$APP/assets" \
  "${RES_ARGS[@]}" \
  "${LINK_ARGS[@]}" \
  --min-sdk-version $MIN_SDK \
  --target-sdk-version $TARGET_SDK

echo "==> 2/7 preparing sherpa-onnx classes + native libs ($ABI)"
if [ ! -f "$DEPS/aar/classes.jar" ]; then
  mkdir -p "$DEPS/aar"
  unzip -o -q "$DEPS/sherpa.aar" classes.jar -d "$DEPS/aar"
fi
rm -rf "$BUILD/jni"
mkdir -p "$BUILD/jni/$ABI"
unzip -o -q -j "$DEPS/sherpa.aar" "jni/$ABI/*" -d "$BUILD/jni/$ABI"

echo "==> 3/7 kotlinc"
for dep in webkit-classes.jar annotation.jar; do
  if [ ! -f "$DEPS/$dep" ]; then
    echo "Missing $DEPS/$dep; run tools/fetch-webkit-deps.sh (see README)" >&2
    exit 1
  fi
done
rm -rf "$BUILD/classes"
kotlinc "$APP/src" \
  -classpath "$ANDROID_JAR:$DEPS/aar/classes.jar:$DEPS/commons-compress.jar:$DEPS/commons-io.jar:$DEPS/webkit-classes.jar:$DEPS/annotation.jar" \
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
  LIBS=("$BUILD/classes.jar" "$DEPS/aar/classes.jar" "$DEPS/commons-compress.jar" "$DEPS/commons-io.jar" "$KOTLIN_STDLIB" "$DEPS/webkit-classes.jar" "$DEPS/annotation.jar")
else
  D8=(d8)
  # Old d8 cannot parse multi-release jars; strip module-info before feeding it.
  for j in commons-compress commons-io; do
    if [ ! -f "$DEPS/$j-d8.jar" ]; then
      cp "$DEPS/$j.jar" "$DEPS/$j-d8.jar"
      zip -q -d "$DEPS/$j-d8.jar" 'META-INF/versions/*' 'module-info.class' 2>/dev/null || true
    fi
  done
  LIBS=("$BUILD/classes.jar" "$DEPS/aar/classes.jar" "$DEPS/commons-compress-d8.jar" "$DEPS/commons-io-d8.jar" "$KOTLIN_STDLIB" "$DEPS/webkit-classes.jar" "$DEPS/annotation.jar")
fi
"${D8[@]}" --lib "$ANDROID_JAR" --min-api $MIN_SDK --output "$BUILD/dex" "${LIBS[@]}"

echo "==> 5/7 packaging"
rm -rf "$BUILD/stage"; mkdir -p "$BUILD/stage/lib/$ABI"
cp "$BUILD/base.apk" "$BUILD/unsigned.apk"
cp "$BUILD/dex/classes.dex" "$BUILD/stage/classes.dex"
cp "$BUILD/jni/$ABI/"*.so "$BUILD/stage/lib/$ABI/"
# extractNativeLibs=false requires the .so entries to be stored uncompressed and 16 KB
# zip-aligned. zipalign below applies -P 16 when the installed build-tools support it.
( cd "$BUILD/stage" && zip -q -X -r "$BUILD/unsigned.apk" classes.dex )
( cd "$BUILD/stage" && zip -q -X -0 -r "$BUILD/unsigned.apk" lib )

echo "==> 6/7 signing"
if [ ! -f "$KEYSTORE" ]; then
  if [ "$KEYSTORE" != "$BUILD/debug.keystore" ]; then
    echo "Release keystore not found: $KEYSTORE" >&2
    exit 1
  fi
  keytool -genkeypair -keystore "$KEYSTORE" -alias "$KS_ALIAS" \
    -storepass "$KS_PASS" -keypass "$KEY_PASS" -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=NovelSpeak Debug,O=NovelSpeak,C=IN" >/dev/null 2>&1
fi
if [ -z "$ZIPALIGN" ]; then
  echo "zipalign is required: extractNativeLibs=false needs 16 KB-aligned, uncompressed .so entries" >&2
  exit 1
fi
rm -f "$BUILD/aligned.apk"
if "$ZIPALIGN" -h 2>&1 | grep -q -- '-P'; then
  "$ZIPALIGN" -f -P 16 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"
else
  "$ZIPALIGN" -f -p 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"
fi
SIGN_INPUT="$BUILD/aligned.apk"
# Pass secrets through the environment so they do not appear in the process list.
export KS_PASS KEY_PASS
rm -f "$OUT_APK"
apksigner sign --ks "$KEYSTORE" --ks-key-alias "$KS_ALIAS" \
  --ks-pass env:KS_PASS --key-pass env:KEY_PASS \
  --out "$OUT_APK" "$SIGN_INPUT"

echo "==> 7/7 verify"
apksigner verify --print-certs "$OUT_APK" | head -5
ls -la "$OUT_APK"
echo
echo "Install with:  termux-open $OUT_APK"
