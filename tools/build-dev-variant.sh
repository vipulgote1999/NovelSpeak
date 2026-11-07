#!/usr/bin/env bash
# Builds a second, side-by-side NovelSpeak: package `app.novelspeak.dev`, debug-signed.
#
# Use it to test a work-in-progress build next to an installed release app. The package id,
# label, version and diagnostics provider authority are distinct, so Android treats it as a
# separate app and neither install nor uninstall touches the release app's data.
#
#   tools/build-dev-variant.sh
#   OUT_APK=/tmp/NovelSpeak-dev-arm64-v8a.apk ABI=arm64-v8a tools/build-dev-variant.sh
#
# The variant is intentionally debuggable.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/app/AndroidManifest.xml"
OUT_MANIFEST="$ROOT/build/AndroidManifest-dev.xml"
mkdir -p "$ROOT/build"

sed \
  -e 's/package="app\.novelspeak"/package="app.novelspeak.dev"/' \
  -e 's/android:name="\./android:name="app.novelspeak./g' \
  -e 's/android:label="NovelSpeak"/android:label="NovelSpeak Dev"/' \
  -e 's/android:authorities="app\.novelspeak\.fileprovider"/android:authorities="app.novelspeak.dev.fileprovider"/' \
  -e 's/android:authorities="app\.novelspeak\.mlkitinitprovider"/android:authorities="app.novelspeak.dev.mlkitinitprovider"/' \
  -e 's/android:versionName="1\.3\.0"/android:versionName="1.3.0-dev"/' \
  "$SRC" > "$OUT_MANIFEST"

# Every component must be fully qualified now that the package id differs from the class
# package. A relative name would resolve to app.novelspeak.dev.<Class> and fail at launch.
if grep -q 'android:name="\.' "$OUT_MANIFEST"; then
  echo "dev manifest still has relative component names:" >&2
  grep -n 'android:name="\.' "$OUT_MANIFEST" >&2
  exit 1
fi

export MANIFEST="$OUT_MANIFEST"
export OUT_APK="${OUT_APK:-$ROOT/build/NovelSpeak-dev-arm64-v8a.apk}"
export ABI="${ABI:-arm64-v8a}"
export DEBUGGABLE=1

exec "$ROOT/build.sh"
