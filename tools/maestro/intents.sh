#!/usr/bin/env bash
# Share-intent E2E check: sends text via ACTION_SEND and asserts it reached the reader.
# The payload is passed inside ONE device-shell command so quoting survives adb;
# otherwise am parses the sentence words as extra arguments.
set -uo pipefail
ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"; DEVICE="${DEVICE:-emulator-5554}"; PKG=app.novelspeak
TEXT="The lantern flickered twice, and then the harbour went dark."

"$ADB" -s "$DEVICE" shell am force-stop "$PKG" >/dev/null 2>&1
"$ADB" -s "$DEVICE" shell "am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT '$TEXT' -n $PKG/.MainActivity" >/dev/null 2>&1

ok=0
for i in $(seq 1 15); do
  "$ADB" -s "$DEVICE" shell uiautomator dump /sdcard/share-ui.xml >/dev/null 2>&1
  "$ADB" -s "$DEVICE" pull /sdcard/share-ui.xml /tmp/share-ui.xml >/dev/null 2>&1
  if grep -qE 'Shared text|Sentence 1 of [0-9]+' /tmp/share-ui.xml; then ok=1; break; fi
  sleep 1
done
if [ "$ok" = "1" ]; then
  echo "PASS share intent: shared text reached the reader"
  exit 0
else
  echo "FAIL share intent: shared text was not rendered"
  grep -o 'text="[^"]*"' /tmp/share-ui.xml | head -20
  exit 1
fi
