#!/usr/bin/env bash
# Desktop dev build wrapper for NovelSpeak (host toolchain, no Gradle/AndroidX).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export PATH="/home/vipul/.local/share/kotlin-2.2.20/bin:$HOME/Android/Sdk/build-tools/36.0.0:$HOME/Android/Sdk/platform-tools:$HOME/jdk-17/bin:$PATH"
export ANDROID_JAR="$HOME/Android/Sdk/platforms/android-34/android.jar"
export DEPS="$ROOT/deps"
export KOTLIN_STDLIB="/home/vipul/.local/share/kotlin-2.2.20/lib/kotlin-stdlib.jar"
exec "$ROOT/build.sh" "$@"
