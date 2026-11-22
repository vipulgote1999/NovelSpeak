#!/usr/bin/env bash
# Adaptive text-scale device check: set font_scale to 2.0, run the 23_text_scale flow,
# then restore the previous value even when the flow fails.
#
#   tools/maestro/text-scale.sh
#   DEVICE=emulator-5554 tools/maestro/text-scale.sh
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
DEVICE="${DEVICE:-emulator-5554}"
PKG=app.novelspeak

if ! "$ADB" -s "$DEVICE" get-state >/dev/null 2>&1; then
  echo "SKIP text-scale: $DEVICE is not connected" >&2
  exit 77
fi

ORIG="$("$ADB" -s "$DEVICE" shell settings get system font_scale 2>/dev/null | tr -d '\r')"
case "$ORIG" in
  ''|null) ORIG=1.0 ;;
esac

restore() {
  "$ADB" -s "$DEVICE" shell settings put system font_scale "$ORIG" >/dev/null 2>&1 || true
}
trap restore EXIT

"$ADB" -s "$DEVICE" shell settings put system font_scale 2.0 >/dev/null
"$ADB" -s "$DEVICE" shell am force-stop "$PKG" >/dev/null 2>&1
"$ROOT/tools/maestro/run.sh" 23_text_scale.yaml
status=$?
if [ "$status" -eq 0 ]; then
  echo "PASS text-scale: home and settings usable at 2.0"
else
  echo "FAIL text-scale: flow 23 failed" >&2
fi
exit "$status"
