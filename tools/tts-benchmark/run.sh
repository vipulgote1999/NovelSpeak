#!/usr/bin/env bash
# NovelSpeak TTS benchmark runner (emulator or physical device).
#
# Usage:
#   tools/tts-benchmark/run.sh
#   DEVICE=<serial> THREADS=8 REPS=3 tools/tts-benchmark/run.sh
#   MODELS="kokoro-int8-en-v0_19 sherpa-onnx-supertonic-3-tts-int8-2026-05-11" \
#       tools/tts-benchmark/run.sh
#
# Prerequisites:
#   - app.novelspeak debug build installed (the BenchmarkActivity is in the manifest)
#   - requested neural model bundles installed under files/models on the device
#   - adb access to the device
#
# Each model runs in a fresh process, so load_ms is a cold ONNX Runtime + model load.
# Synthesis is measured without AudioTrack playback; RTF = generation wall time / audio time.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DEVICE="${DEVICE:-}"
THREADS="${THREADS:-4}"
REPS="${REPS:-2}"
MODELS="${MODELS:-system kokoro-int8-en-v0_19 sherpa-onnx-pocket-tts-int8-2026-01-26 sherpa-onnx-supertonic-3-tts-int8-2026-05-11 sherpa-onnx-zipvoice-distill-int8-zh-en-emilia}"
ADB="${ADB:-adb}"
TAG="NovelSpeakBench"
WAIT_SECONDS="${WAIT_SECONDS:-300}"

if [ -z "$DEVICE" ]; then
  mapfile -t ONLINE < <("$ADB" devices | awk '$2=="device"{print $1}')
  if [ "${#ONLINE[@]}" -eq 0 ]; then
    echo "No adb device online. Start an emulator or connect a phone." >&2
    exit 2
  fi
  if [ "${#ONLINE[@]}" -gt 1 ]; then
    echo "Multiple devices are online; set DEVICE explicitly:" >&2
    printf '  %s\n' "${ONLINE[@]}" >&2
    exit 2
  fi
  DEVICE="${ONLINE[0]}"
fi

serial_safe="$(printf '%s' "$DEVICE" | tr -c 'A-Za-z0-9._-' '_')"
OUT="$ROOT/tools/tts-benchmark/reports/$serial_safe"
mkdir -p "$OUT"

echo "== device: $DEVICE"
"$ADB" -s "$DEVICE" shell getprop ro.product.manufacturer
"$ADB" -s "$DEVICE" shell getprop ro.product.model
"$ADB" -s "$DEVICE" shell getprop ro.build.version.release
"$ADB" -s "$DEVICE" shell 'cat /proc/cpuinfo | grep -c ^processor'
echo "== threads=$THREADS reps=$REPS"
echo "== output: $OUT"

# Make sure the harness exists before spending time on the run.
if [ "$("$ADB" -s "$DEVICE" shell pm list packages | tr -d '\r' | grep -cx 'package:app.novelspeak' || true)" -eq 0 ]; then
  echo "app.novelspeak is not installed on $DEVICE" >&2
  exit 3
fi
if [ "$("$ADB" -s "$DEVICE" shell cmd package resolve-activity --brief -n app.novelspeak/.BenchmarkActivity 2>/dev/null | grep -c 'app.novelspeak/.BenchmarkActivity' || true)" -eq 0 ]; then
  echo "Installed APK does not contain BenchmarkActivity; install the benchmark build." >&2
  exit 4
fi

# run-as works for the debug build. Writing the result in filesDir avoids scoped-storage
# restrictions on /sdcard/Android/data on newer devices.
read_result() {
  local model="$1"
  "$ADB" -s "$DEVICE" exec-out run-as app.novelspeak cat \
    "/data/data/app.novelspeak/files/bench/result-$model.json" 2>/dev/null
}

for model in $MODELS; do
  echo
  echo "== $model =="
  "$ADB" -s "$DEVICE" shell am force-stop app.novelspeak >/dev/null 2>&1 || true
  "$ADB" -s "$DEVICE" shell run-as app.novelspeak rm -f \
    "/data/data/app.novelspeak/files/bench/result-$model.json" >/dev/null 2>&1 || true
  "$ADB" -s "$DEVICE" logcat -c >/dev/null 2>&1 || true
  start="$("$ADB" -s "$DEVICE" shell am start -W -n app.novelspeak/.BenchmarkActivity \
    --es model "$model" --ei threads "$THREADS" --ei reps "$REPS" 2>&1 | tr -d '\r')"
  printf '%s\n' "$start" > "$OUT/$model.start.txt"
  deadline=$((SECONDS + WAIT_SECONDS))
  ok=0
  while [ "$SECONDS" -lt "$deadline" ]; do
    if "$ADB" -s "$DEVICE" shell run-as app.novelspeak ls \
      "/data/data/app.novelspeak/files/bench/result-$model.json" >/dev/null 2>&1; then
      ok=1
      break
    fi
    sleep 2
  done
  if [ "$ok" = 1 ]; then
    read_result "$model" > "$OUT/$model.json"
    if python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print("ok" if d.get("ok") else "failed: "+str(d.get("error")))' "$OUT/$model.json" >/dev/null 2>&1; then
      line="$(python3 - "$OUT/$model.json" <<'PY'
import json,sys
d=json.load(open(sys.argv[1]))
s=d.get("summary",{})
print(f"ok={d.get('ok')} load={d.get('load_ms')}ms "
      f"gen={s.get('gen_ms_median')}ms rtf={s.get('rtf_median')} "
      f"xrt={s.get('x_realtime_median')} ttfc={s.get('first_chunk_ms_median')}ms")
PY
)"
      echo "  $line"
    else
      echo "  result written but unreadable: $OUT/$model.json"
    fi
  else
    echo "  TIMEOUT after ${WAIT_SECONDS}s (no result file)"
  fi
  "$ADB" -s "$DEVICE" logcat -d -s "$TAG":I "$TAG":E > "$OUT/$model.log" 2>&1 || true
done

python3 "$ROOT/tools/tts-benchmark/summarize.py" "$OUT"
