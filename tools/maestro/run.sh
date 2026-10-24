#!/usr/bin/env bash
# NovelSpeak Maestro E2E runner.
#
#   tools/maestro/run.sh                     # run every flow in flows/
#   tools/maestro/run.sh 01_launch.yaml 02_settings.yaml
#   tools/maestro/run.sh --pending           # run flows_pending/ (new-build gate)
#
# What it does, in order:
#   1. verify emulator-5554 is connected
#   2. start the fixture web server on 127.0.0.1:8765 if it is not already up
#   3. adb reverse tcp:8765 -> host 8765
#   4. verify app.novelspeak is installed and print its version
#   5. run maestro with a JUnit report in tools/maestro/reports/
#   6. tear down the server/reverse unless KEEP=1
#   7. print a pass/fail summary and exit with Maestro's status
#
# Environment:
#   DEVICE=emulator-5554   ADB=...   MAESTRO=...   FIXTURE_PORT=8765   KEEP=1
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
FIXTURE_DIR="$ROOT/tools/test-page"
REPORT_DIR="$SCRIPT_DIR/reports"
JUNIT="$REPORT_DIR/junit.xml"
RUN_LOG="$REPORT_DIR/maestro.log"
ARTIFACTS="$REPORT_DIR/artifacts"
PIDFILE="${TMPDIR:-/tmp}/novelspeak-fixture-http.pid"
SERVER_LOG="${TMPDIR:-/tmp}/novelspeak-fixture-http.log"
PORT="${FIXTURE_PORT:-8765}"
DEVICE="${DEVICE:-emulator-5554}"
ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
MAESTRO="${MAESTRO:-$HOME/.maestro/bin/maestro}"

SERVER_STARTED=0
SERVER_PID=""
REVERSE_SET=0

usage() {
  sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'
}

die() { echo "ERROR: $*" >&2; exit "${2:-1}"; }

cleanup() {
  if [ "${KEEP:-0}" = "1" ]; then
    echo
    echo "KEEP=1: leaving fixture server and adb reverse in place"
    [ -n "$SERVER_PID" ] && echo "  fixture server pid $SERVER_PID (log: $SERVER_LOG)"
    echo "  http://localhost:$PORT/index.html"
    return
  fi
  if [ "$REVERSE_SET" = "1" ]; then
    "$ADB" -s "$DEVICE" reverse --remove "tcp:$PORT" >/dev/null 2>&1 || true
  fi
  if [ "$SERVER_STARTED" = "1" ] && [ -n "$SERVER_PID" ] && kill -0 "$SERVER_PID" 2>/dev/null; then
    kill "$SERVER_PID" 2>/dev/null || true
    wait "$SERVER_PID" 2>/dev/null || true
  fi
  if [ "$SERVER_STARTED" = "1" ]; then rm -f "$PIDFILE"; fi
}
trap cleanup EXIT

server_healthy() {
  curl -fsS --max-time 2 "http://127.0.0.1:$PORT/index.html" >/dev/null 2>&1
}

ensure_server() {
  [ -d "$FIXTURE_DIR" ] || die "fixture directory missing: $FIXTURE_DIR"
  if server_healthy; then
    echo "== fixture server: already up on 127.0.0.1:$PORT"
    return
  fi
  if [ -f "$PIDFILE" ]; then
    local old
    old="$(cat "$PIDFILE" 2>/dev/null || true)"
    if [ -n "$old" ] && kill -0 "$old" 2>/dev/null; then
      # Only kill it if the pid still belongs to our http.server (pid reuse guard).
      if tr '\0' ' ' <"/proc/$old/cmdline" 2>/dev/null | grep -q 'http\.server'; then
        echo "== fixture server: stale pid $old is running but not serving; stopping it"
        kill "$old" 2>/dev/null || true
        sleep 1
      else
        echo "== fixture server: stale pid file ignored (pid $old is not our server)"
      fi
    fi
    rm -f "$PIDFILE"
  fi
  echo "== fixture server: starting python3 -m http.server $PORT --bind 127.0.0.1 --directory $FIXTURE_DIR"
  nohup python3 -m http.server "$PORT" --bind 127.0.0.1 --directory "$FIXTURE_DIR" \
    >"$SERVER_LOG" 2>&1 &
  SERVER_PID=$!
  SERVER_STARTED=1
  echo "$SERVER_PID" >"$PIDFILE"
  local i
  for i in $(seq 1 50); do
    server_healthy && break
    sleep 0.1
  done
  if ! server_healthy; then
    echo "--- server log ---" >&2
    tail -20 "$SERVER_LOG" >&2 || true
    die "fixture server did not come up on port $PORT" 2
  fi
  echo "== fixture server: pid $SERVER_PID"
}

ensure_device() {
  if ! "$ADB" devices | awk -v d="$DEVICE" '$1==d && $2=="device"{f=1} END{exit(f?0:1)}'; then
    echo "Connected devices:" >&2
    "$ADB" devices -l >&2 || true
    die "$DEVICE is not connected. Start it (e.g. emulator -avd MedTrail_API35) and retry." 2
  fi
  echo "== device: $DEVICE"
}

ensure_app() {
  local packages
  packages="$("$ADB" -s "$DEVICE" shell pm list packages 2>/dev/null | tr -d '\r')"
  if ! printf '%s\n' "$packages" | grep -qx 'package:app.novelspeak'; then
    die "app.novelspeak is not installed on $DEVICE (no builds/installs are done by this runner)." 3
  fi
  local ver code
  ver="$("$ADB" -s "$DEVICE" shell dumpsys package app.novelspeak 2>/dev/null | tr -d '\r' | sed -n 's/.*versionName=//p' | head -1)"
  code="$("$ADB" -s "$DEVICE" shell dumpsys package app.novelspeak 2>/dev/null | tr -d '\r' | sed -n 's/.*versionCode=//p' | head -1)"
  echo "== app: app.novelspeak versionName=${ver:-?} versionCode=${code:-?}"
}

ensure_reverse() {
  local reversals
  reversals="$("$ADB" -s "$DEVICE" reverse --list 2>/dev/null)"
  if printf '%s\n' "$reversals" | grep -q "tcp:$PORT tcp:$PORT"; then
    echo "== adb reverse: already present for tcp:$PORT"
    return
  fi
  "$ADB" -s "$DEVICE" reverse "tcp:$PORT" "tcp:$PORT" >/dev/null || die "adb reverse failed" 2
  REVERSE_SET=1
  echo "== adb reverse: tcp:$PORT -> host tcp:$PORT"
}

resolve_targets() {
  local a
  TARGETS=()
  for a in "$@"; do
    if [ -e "$a" ]; then
      TARGETS+=("$a")
    elif [ -e "$SCRIPT_DIR/$a" ]; then
      TARGETS+=("$SCRIPT_DIR/$a")
    elif [ -e "$SCRIPT_DIR/flows/$a" ]; then
      TARGETS+=("$SCRIPT_DIR/flows/$a")
    elif [ -e "$SCRIPT_DIR/flows/${a}.yaml" ]; then
      TARGETS+=("$SCRIPT_DIR/flows/${a}.yaml")
    elif [ -e "$SCRIPT_DIR/flows_pending/$a" ]; then
      TARGETS+=("$SCRIPT_DIR/flows_pending/$a")
    elif [ -e "$SCRIPT_DIR/flows_pending/${a}.yaml" ]; then
      TARGETS+=("$SCRIPT_DIR/flows_pending/${a}.yaml")
    else
      local hit
      hit="$(ls "$SCRIPT_DIR/flows_pending"/"${a}"*.yaml 2>/dev/null | head -1)"
      if [ -n "$hit" ]; then
        TARGETS+=("$hit")
      else
        die "flow not found: $a" 4
      fi
    fi
  done
}

MODE="flows"
ARGS=()
for arg in "$@"; do
  case "$arg" in
    -h|--help) usage; exit 0 ;;
    --pending) MODE="pending" ;;
    *) ARGS+=("$arg") ;;
  esac
done

[ -x "$MAESTRO" ] || die "Maestro not found/executable at $MAESTRO (set MAESTRO=...)" 5
command -v curl >/dev/null || die "curl is required for the fixture-server health check" 5
command -v python3 >/dev/null || die "python3 is required for the fixture server and JUnit summary" 5

if [ "${#ARGS[@]}" -gt 0 ]; then
  resolve_targets "${ARGS[@]}"
else
  if [ "$MODE" = "pending" ]; then
    TARGETS=("$SCRIPT_DIR/flows_pending")
  else
    TARGETS=("$SCRIPT_DIR/flows")
  fi
fi

echo "== NovelSpeak Maestro E2E"
echo "   workspace: $ROOT"
echo "   targets:   ${TARGETS[*]}"

ensure_device
ensure_app
ensure_server
ensure_reverse

mkdir -p "$REPORT_DIR" "$ARTIFACTS"
rm -f "$JUNIT"

echo
echo "== maestro test --format junit --output $JUNIT"
"$MAESTRO" test "${TARGETS[@]}" \
  --format junit --output "$JUNIT" \
  --udid "$DEVICE" --no-ansi \
  --test-output-dir "$ARTIFACTS" 2>&1 | tee "$RUN_LOG"
STATUS=${PIPESTATUS[0]}

echo
echo "== summary"
python3 - "$JUNIT" <<'PY'
import sys, xml.etree.ElementTree as ET
path = sys.argv[1]
try:
    root = ET.parse(path).getroot()
except Exception as e:
    print(f"  no JUnit report at {path} ({e})")
    raise SystemExit(0)
suites = root.findall('.//testsuite') or ([root] if root.tag == 'testsuite' else [])
tests = sum(int(s.get('tests') or 0) for s in suites)
failures = sum(int(s.get('failures') or 0) for s in suites)
errors = sum(int(s.get('errors') or 0) for s in suites)
skipped = sum(int(s.get('skipped') or 0) for s in suites)
print(f"  tests={tests} failures={failures} errors={errors} skipped={skipped}")
for tc in root.iter('testcase'):
    bad = tc.find('failure')
    if bad is None:
        bad = tc.find('error')
    if bad is not None:
        msg = (bad.get('message') or bad.text or 'failed').strip().replace('\n', ' ')
        print(f"  FAIL {tc.get('name')}: {msg[:220]}")
PY
echo "  report: $JUNIT"
echo "  log:    $RUN_LOG"
if [ "$STATUS" -eq 0 ]; then
  echo "== RESULT: PASS"
else
  echo "== RESULT: FAIL (maestro exit $STATUS)"
fi
exit "$STATUS"
