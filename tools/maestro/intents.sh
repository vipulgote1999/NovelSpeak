#!/usr/bin/env bash
set -uo pipefail
ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"; DEVICE="${DEVICE:-emulator-5554}"; PKG=app.novelspeak
"$ADB" -s "$DEVICE" shell am force-stop "$PKG" >/dev/null 2>&1
"$ADB" -s "$DEVICE" shell am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "The lantern flickered twice, and then the harbour went dark." -n "$PKG/.MainActivity" >/dev/null 2>&1
sleep 4
"$ADB" -s "$DEVICE" shell uiautomator dump /sdcard/share-ui.xml >/dev/null 2>&1
"$ADB" -s "$DEVICE" pull /sdcard/share-ui.xml /tmp/share-ui.xml >/dev/null 2>&1
if grep -q "Shared text" /tmp/share-ui.xml; then echo "PASS share intent"; exit 0; else echo "FAIL share intent"; grep -o 'text="[^"]*"' /tmp/share-ui.xml | head -20; exit 1; fi
