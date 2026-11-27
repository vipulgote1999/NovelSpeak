#!/usr/bin/env bash
# Build a signed Android App Bundle (AAB) for Google Play from this framework-only project.
#
# The normal build.sh produces the APK, classes.dex and native libs. This script re-links the
# manifest/resources with aapt2 in proto format, assembles a bundletool base module, writes a
# BundleConfig that requests 16 KB page alignment, and signs the .aab with the upload key.
#
#   source "$HOME/.keys/novelspeak-release.env"
#   KEYSTORE="$HOME/.keys/novelspeak-release.jks" \
#   KS_ALIAS=novelspeak KS_PASS="$NOVELSPEAK_KS_PASS" KEY_PASS="$NOVELSPEAK_KEY_PASS" \
#   tools/build-aab.sh
#
# Environment:
#   DEPS, KOTLIN_STDLIB, ANDROID_HOME, ANDROID_JAR, BUILD_TOOLS, BUNDLETOOL_CP
#   OUT_AAB (default build/NovelSpeak-<version>.aab)
#   KEYSTORE/KS_ALIAS/KS_PASS/KEY_PASS (required; Play upload key)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/app"
BUILD="$ROOT/build"
AAB_WORK="$BUILD/aab"
BUILD_TOOLS="${BUILD_TOOLS:-${ANDROID_HOME:?ANDROID_HOME is required}/build-tools/36.0.0}"
AAPT2="$BUILD_TOOLS/aapt2"
ZIPALIGN="$BUILD_TOOLS/zipalign"
ANDROID_JAR="${ANDROID_JAR:-${ANDROID_HOME:?ANDROID_HOME is required}/platforms/android-36/android.jar}"
VERSION="$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$APP/AndroidManifest.xml" | head -1)"
VERSION_CODE="$(sed -n 's/.*android:versionCode="\([^"]*\)".*/\1/p' "$APP/AndroidManifest.xml" | head -1)"
TARGET_SDK="$(sed -n 's/.*android:targetSdkVersion="\([^"]*\)".*/\1/p' "$APP/AndroidManifest.xml" | head -1)"
OUT_AAB="${OUT_AAB:-$BUILD/NovelSpeak-$VERSION.aab}"

if [ -z "${KEYSTORE:-}" ] || [ -z "${KS_ALIAS:-}" ] || [ -z "${KS_PASS:-}" ]; then
  echo "KEYSTORE, KS_ALIAS and KS_PASS are required (use the Play upload key)." >&2
  exit 1
fi
KEY_PASS="${KEY_PASS:-$KS_PASS}"

if [ ! -f "$AAPT2" ] || [ ! -f "$ZIPALIGN" ]; then
  echo "Android build-tools not found at $BUILD_TOOLS" >&2
  exit 1
fi
if [ ! -f "$BUILD/dex/classes.dex" ] || [ ! -d "$BUILD/jni/arm64-v8a" ]; then
  echo "Run ./build.sh first so build/dex and build/jni exist." >&2
  exit 1
fi

# Resolve bundletool and its runtime dependencies. Prefer an explicit BUNDLETOOL_CP; otherwise
# use a local extracted bundletool-all jar or the Gradle module cache used by this machine.
resolve_bundletool_cp() {
  if [ -n "${BUNDLETOOL_CP:-}" ]; then
    printf '%s' "$BUNDLETOOL_CP"
    return 0
  fi
  local cache="$HOME/.gradle/caches/modules-2/files-2.1"
  local bt
  bt="$(find "$cache" -name 'bundletool-1.18.3.jar' 2>/dev/null | head -1)"
  if [ -z "$bt" ]; then
    return 1
  fi
  local names=(
    'aapt2-proto-7.3.0-alpha07-8248216.jar'
    'auto-value-annotations-1.6.2.jar'
    'protobuf-java-util-3.22.3.jar'
    'guava-32.0.1-jre.jar'
    'error_prone_annotations-2.18.0.jar'
    'protobuf-java-3.22.3.jar'
    'dagger-2.28.3.jar'
    'javax.inject-1.jar'
    'jose4j-0.9.5.jar'
    'slf4j-api-1.7.30.jar'
    'failureaccess-1.0.1.jar'
    'listenablefuture-9999.0-empty-to-avoid-conflict-with-guava.jar'
    'jsr305-3.0.2.jar'
    'checker-qual-3.33.0.jar'
    'j2objc-annotations-2.8.jar'
    'gson-2.8.9.jar'
  )
  local cp="$bt"
  local n
  for n in "${names[@]}"; do
    local f
    f="$(find "$cache" -name "$n" 2>/dev/null | head -1)"
    if [ -z "$f" ] && [ -f "$ROOT/deps/tools/bundletool-libs/$n" ]; then
      f="$ROOT/deps/tools/bundletool-libs/$n"
    fi
    if [ -z "$f" ]; then
      echo "Missing bundletool dependency: $n" >&2
      return 1
    fi
    cp="$cp:$f"
  done
  printf '%s' "$cp"
}

BUNDLETOOL_CP="$(resolve_bundletool_cp)" || {
  echo "Set BUNDLETOOL_CP to the bundletool + dependency classpath." >&2
  exit 1
}

echo "==> AAB 1/6 compile resources (proto)"
rm -rf "$AAB_WORK"
mkdir -p "$AAB_WORK"
"$AAPT2" compile --dir "$APP/res" -o "$AAB_WORK/res.zip"

echo "==> AAB 2/6 link proto APK"
"$AAPT2" link \
  --proto-format \
  -o "$AAB_WORK/base-proto.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$APP/AndroidManifest.xml" \
  -A "$APP/assets" \
  "$AAB_WORK/res.zip" \
  --min-sdk-version 24 \
  --target-sdk-version "$TARGET_SDK"

echo "==> AAB 3/6 assemble base module"
rm -rf "$AAB_WORK/base"
mkdir -p "$AAB_WORK/base"
unzip -q "$AAB_WORK/base-proto.apk" -d "$AAB_WORK/base"
mkdir -p "$AAB_WORK/base/manifest" "$AAB_WORK/base/dex" "$AAB_WORK/base/lib/arm64-v8a"
if [ -f "$AAB_WORK/base/AndroidManifest.xml" ]; then
  mv "$AAB_WORK/base/AndroidManifest.xml" "$AAB_WORK/base/manifest/AndroidManifest.xml"
fi
if [ ! -f "$AAB_WORK/base/resources.pb" ]; then
  echo "aapt2 proto output is missing resources.pb" >&2
  exit 1
fi
cp "$BUILD/dex/classes.dex" "$AAB_WORK/base/dex/classes.dex"
cp "$BUILD/jni/arm64-v8a/"*.so "$AAB_WORK/base/lib/arm64-v8a/"
( cd "$AAB_WORK/base" && zip -q -X -r "$AAB_WORK/base.zip" . )

cat > "$AAB_WORK/BundleConfig.json" <<'JSON'
{
  "optimizations": {
    "uncompressNativeLibraries": {
      "enabled": true,
      "alignment": "PAGE_ALIGNMENT_16K"
    }
  }
}
JSON

echo "==> AAB 4/6 bundletool build-bundle"
rm -f "$OUT_AAB"
java -cp "$BUNDLETOOL_CP" com.android.tools.build.bundletool.BundleToolMain build-bundle \
  --modules="$AAB_WORK/base.zip" \
  --output="$OUT_AAB" \
  --config="$AAB_WORK/BundleConfig.json" \
  --overwrite

echo "==> AAB 5/6 sign with Play upload key"
export KS_PASS KEY_PASS
jarsigner -keystore "$KEYSTORE" -storepass:env KS_PASS -keypass:env KEY_PASS \
  -sigalg SHA256withRSA -digestalg SHA-256 "$OUT_AAB" "$KS_ALIAS"
jarsigner -verify -verbose -certs "$OUT_AAB" > "$AAB_WORK/jarsigner-verify.txt"
grep -q 'jar verified' "$AAB_WORK/jarsigner-verify.txt"

echo "==> AAB 6/6 verify manifest/config and 16 KB alignment"
java -cp "$BUNDLETOOL_CP" com.android.tools.build.bundletool.BundleToolMain dump manifest \
  --bundle="$OUT_AAB" | grep -E 'versionCode|versionName|targetSdkVersion' || true
java -cp "$BUNDLETOOL_CP" com.android.tools.build.bundletool.BundleToolMain dump config \
  --bundle="$OUT_AAB" | grep -E 'uncompressNativeLibraries|alignment|PAGE_ALIGNMENT' || true

echo
echo "AAB: $OUT_AAB"
ls -lh "$OUT_AAB"
sha256sum "$OUT_AAB"
echo
echo "Optional Play-equivalent APK check:"
echo "  java -cp \"\$BUNDLETOOL_CP\" com.android.tools.build.bundletool.BundleToolMain build-apks \\"
echo "    --bundle=$OUT_AAB --output=$BUILD/verify.apks --mode=universal --aapt2=$AAPT2"
