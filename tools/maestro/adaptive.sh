#!/usr/bin/env bash
# Adaptive-layout check: rotate to landscape (two-pane) and back, assert the reader UI survives.
set -uo pipefail
ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"; DEVICE="${DEVICE:-emulator-5554}"; PKG=app.novelspeak

dump_ui() { "$ADB" -s "$DEVICE" shell uiautomator dump /sdcard/adaptive.xml >/dev/null 2>&1; "$ADB" -s "$DEVICE" pull /sdcard/adaptive.xml /tmp/adaptive.xml >/dev/null 2>&1; }
check() {
  dump_ui
  if grep -q 'content-desc="Address bar' /tmp/adaptive.xml && grep -q 'text="Read"' /tmp/adaptive.xml; then
    echo "  OK $1: address bar + Read present"
    return 0
  fi
  echo "  FAIL $1"; grep -oE '(text|content-desc)="[^"]{2,50}"' /tmp/adaptive.xml | head -15; return 1
}

"$ADB" -s "$DEVICE" shell settings put system accelerometer_rotation 0 >/dev/null 2>&1
"$ADB" -s "$DEVICE" shell am force-stop "$PKG" >/dev/null 2>&1
"$ADB" -s "$DEVICE" shell settings put system user_rotation 1 >/dev/null 2>&1   # landscape
"$ADB" -s "$DEVICE" shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1
sleep 4
landscape_ok=1; check "landscape two-pane" || landscape_ok=0
"$ADB" -s "$DEVICE" shell settings put system user_rotation 0 >/dev/null 2>&1   # portrait
sleep 3
portrait_ok=1; check "portrait single column" || portrait_ok=0
"$ADB" -s "$DEVICE" shell settings put system accelerometer_rotation 1 >/dev/null 2>&1
if [ "$landscape_ok" = "1" ] && [ "$portrait_ok" = "1" ]; then echo "PASS adaptive layout"; exit 0; fi
echo "FAIL adaptive layout"; exit 1
