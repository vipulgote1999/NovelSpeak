#!/usr/bin/env bash
# Narrow-phone device check: force a 320 dp window, verify the player controls are all
# reachable, then restore the display overrides even when the flow fails.
#
#   tools/maestro/narrow.sh
#   DEVICE=emulator-5554 tools/maestro/narrow.sh
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
DEVICE="${DEVICE:-emulator-5554}"
PKG=app.novelspeak

if ! "$ADB" -s "$DEVICE" get-state >/dev/null 2>&1; then
  echo "SKIP narrow: $DEVICE is not connected" >&2
  exit 77
fi

restore() {
  "$ADB" -s "$DEVICE" shell wm size reset >/dev/null 2>&1 || true
  "$ADB" -s "$DEVICE" shell wm density reset >/dev/null 2>&1 || true
}
trap restore EXIT

"$ADB" -s "$DEVICE" shell wm size 480x800 >/dev/null
"$ADB" -s "$DEVICE" shell wm density 240 >/dev/null
"$ADB" -s "$DEVICE" shell am force-stop "$PKG" >/dev/null 2>&1
"$ROOT/tools/maestro/run.sh" 30_narrow_player.yaml
status=$?
if [ "$status" -eq 0 ]; then
  echo "PASS narrow: 320 dp player controls all reachable"
else
  echo "FAIL narrow: flow 30 failed" >&2
fi
exit "$status"
