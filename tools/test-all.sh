#!/usr/bin/env bash
# Full regression gate.
#
#   tools/test-all.sh                  # host + browser + device (if an emulator is attached)
#   tools/test-all.sh --quick          # host + browser only
#   tools/test-all.sh --no-device      # never touch a device
#   tools/test-all.sh --area=playback  # focused host area, still runs browser/device gates
#   tools/test-all.sh --device=emulator-5554
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 1
ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
DEVICE="${DEVICE:-emulator-5554}"
QUICK=0
RUN_DEVICE=1
AREA_ARGS=()

while [ "$#" -gt 0 ]; do
  case "$1" in
    -h|--help)
      sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    --quick) QUICK=1 ;;
    --no-device) RUN_DEVICE=0 ;;
    --device=*) DEVICE="${1#--device=}" ;;
    --device) shift; DEVICE="${1:-$DEVICE}" ;;
    --area=*|--areas=*) AREA_ARGS+=("$1") ;;
    --area|--areas)
      shift
      [ "$#" -gt 0 ] || { echo "--area requires a value" >&2; exit 2; }
      AREA_ARGS+=("--area=$1")
      ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done

echo "== Host regressions"
tools/test-host.sh --check || exit $?
if [ "${#AREA_ARGS[@]}" -gt 0 ]; then
  tools/test-host.sh "${AREA_ARGS[@]}" || exit $?
else
  tools/test-host.sh || exit $?
fi

echo
echo "== Browser regressions"
for script in tools/extract-test/run.sh tools/manga-extract-test/run.sh tools/adblock-test/procedural.sh; do
  if bash "$script"; then
    :
  else
    code=$?
    if [ "$code" -eq 77 ]; then
      echo "SKIP: $script (Chromium not available)"
    else
      exit "$code"
    fi
  fi
done

if [ "$QUICK" = 1 ]; then
  echo
  echo "== Device tests skipped (--quick)"
  exit 0
fi

if [ "$RUN_DEVICE" = 1 ] && "$ADB" -s "$DEVICE" get-state >/dev/null 2>&1; then
  export DEVICE ADB
  echo
  echo "== Device tests on $DEVICE"
  tools/maestro/run.sh || exit $?
  bash tools/maestro/flows/08_share_intent.txt || exit $?
  bash tools/maestro/adaptive.sh || exit $?
  bash tools/maestro/text-scale.sh || exit $?
else
  echo
  echo "== Device tests skipped: $DEVICE is not connected (or --no-device)"
fi

echo
echo "ALL SELECTED TESTS PASSED"
