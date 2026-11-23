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

ORIG_FONT="$("$ADB" -s "$DEVICE" shell settings get system font_scale 2>/dev/null | tr -d '\r')"
case "$ORIG_FONT" in
  ''|null) ORIG_FONT=1.0 ;;
esac

restore() {
  "$ADB" -s "$DEVICE" shell wm size reset >/dev/null 2>&1 || true
  "$ADB" -s "$DEVICE" shell wm density reset >/dev/null 2>&1 || true
  "$ADB" -s "$DEVICE" shell settings put system font_scale "$ORIG_FONT" >/dev/null 2>&1 || true
}
trap restore EXIT

"$ADB" -s "$DEVICE" shell wm size 480x800 >/dev/null
"$ADB" -s "$DEVICE" shell wm density 240 >/dev/null
"$ADB" -s "$DEVICE" shell settings put system font_scale 1.0 >/dev/null
"$ADB" -s "$DEVICE" shell am force-stop "$PKG" >/dev/null 2>&1
"$ROOT/tools/maestro/run.sh" 30_narrow_player.yaml
status=$?
if [ "$status" -eq 0 ]; then
  # 320 dp and 2x are separate gates elsewhere; exercise them together because that is where
  # the Read/Next/Cast strip used to squeeze Cast into a wrapped column.
  "$ADB" -s "$DEVICE" shell settings put system font_scale 2.0 >/dev/null
  "$ADB" -s "$DEVICE" shell am force-stop "$PKG" >/dev/null 2>&1
  "$ROOT/tools/maestro/run.sh" 30_narrow_player.yaml
  status=$?
fi
if [ "$status" -eq 0 ]; then
  echo "PASS narrow: 320 dp player controls all reachable at 1x and 2x"
else
  echo "FAIL narrow: flow 30 failed" >&2
fi
exit "$status"
