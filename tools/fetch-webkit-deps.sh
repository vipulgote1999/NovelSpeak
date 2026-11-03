#!/usr/bin/env bash
# Fetch the AndroidX WebKit classes used for DOCUMENT_START_SCRIPT support.
#
# NovelSpeak intentionally has no Gradle/AndroidX project integration. We vendor only the
# WebKit AAR's classes.jar (248 KB) and androidx.annotation's classes.jar. No resources or
# native libraries are required for WebViewCompat/WebViewFeature.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEPS="${DEPS:-$ROOT/deps}"
mkdir -p "$DEPS"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

WEBKIT_AAR="$TMP/webkit.aar"
ANNOTATION_JAR="$TMP/annotation.jar"

curl -4 -L --fail --retry 3 -o "$WEBKIT_AAR" \
  https://dl.google.com/dl/android/maven2/androidx/webkit/webkit/1.14.0/webkit-1.14.0.aar
echo "265604786ea1c9679e3f860f7f13072375c141f46db9092ed8ef5ecd599a516d  $WEBKIT_AAR" \
  | sha256sum -c -
unzip -p "$WEBKIT_AAR" classes.jar > "$DEPS/webkit-classes.jar"

curl -4 -L --fail --retry 3 -o "$ANNOTATION_JAR" \
  https://dl.google.com/dl/android/maven2/androidx/annotation/annotation/1.8.1/annotation-1.8.1.jar
echo "fa5e67ffcc52a0413f4b39c2737b43c1ba6c6975b4bd4a4ea8627549e766285b  $ANNOTATION_JAR" \
  | sha256sum -c -
cp "$ANNOTATION_JAR" "$DEPS/annotation.jar"

echo "Installed: $DEPS/webkit-classes.jar $DEPS/annotation.jar"
