#!/usr/bin/env bash
# Manifest-driven host regression runner.
#
#   tools/test-host.sh                         # all host harnesses
#   tools/test-host.sh --list                  # list harnesses and areas
#   tools/test-host.sh --area=playback,tts     # only matching areas
#   tools/test-host.sh --check                 # verify manifest coverage
#   KOTLINC=... tools/test-host.sh
#
# The manifest is tools/host-tests.tsv. Every tools/*-test/TestMain.kt must be registered;
# --check fails otherwise so new tests cannot be forgotten. Harnesses are plain Kotlin/JVM:
# no Android SDK, emulator or Gradle is required.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 1
MANIFEST="$ROOT/tools/host-tests.tsv"

usage() {
  sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
}

die() { echo "ERROR: $*" >&2; exit 2; }

MODE="run"
FILTERS=()

while [ "$#" -gt 0 ]; do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    --list) MODE="list" ;;
    --check) MODE="check" ;;
    --area=*|--areas=*)
      IFS=',' read -ra parts <<< "${1#*=}"
      FILTERS+=("${parts[@]}")
      ;;
    --area|--areas)
      shift
      [ "$#" -gt 0 ] || die "$1 requires a comma-separated value"
      IFS=',' read -ra parts <<< "$1"
      FILTERS+=("${parts[@]}")
      ;;
    *) die "unknown argument: $1 (try --help)" ;;
  esac
  shift
done

[ -f "$MANIFEST" ] || die "manifest missing: $MANIFEST"

if [ "$MODE" = "check" ]; then
  status=0
  while IFS=$'\t' read -r name areas sources; do
    case "$name" in ''|\#*) continue ;; esac
    test_main="$ROOT/tools/$name/TestMain.kt"
    if [ ! -f "$test_main" ]; then
      echo "MISSING harness: $name ($test_main)" >&2
      status=1
    fi
    read -ra srcs <<< "$sources"
    for src in "${srcs[@]}"; do
      if [ ! -e "$ROOT/$src" ]; then
        echo "MISSING source for $name: $src" >&2
        status=1
      fi
    done
  done < "$MANIFEST"

  for test_main in "$ROOT"/tools/*-test/TestMain.kt; do
    [ -f "$test_main" ] || continue
    name="$(basename "$(dirname "$test_main")")"
    if ! awk -F'\t' -v n="$name" '$1==n {found=1} END{exit(found?0:1)}' "$MANIFEST"; then
      echo "UNREGISTERED harness: $name (add it to tools/host-tests.tsv)" >&2
      status=1
    fi
  done
  if [ "$status" -ne 0 ]; then
    echo "Host test manifest check FAILED" >&2
    exit 1
  fi
  echo "Host test manifest check: OK ($(grep -cvE '^#|^$' "$MANIFEST") harnesses)"
  exit 0
fi

if [ "$MODE" = "list" ]; then
  printf '%-28s %s\n' "HARNESS" "AREAS"
  while IFS=$'\t' read -r name areas sources; do
    case "$name" in ''|\#*) continue ;; esac
    printf '%-28s %s\n' "$name" "$areas"
  done < "$MANIFEST"
  exit 0
fi

KOTLINC="${KOTLINC:-$(command -v kotlinc || true)}"
if [ -z "$KOTLINC" ]; then
  for candidate in \
    "$HOME/.local/share/kotlin-2.2.20/bin/kotlinc" \
    "$HOME/.local/share/kotlin/kotlinc/bin/kotlinc"; do
    if [ -x "$candidate" ]; then KOTLINC="$candidate"; break; fi
  done
fi
[ -n "$KOTLINC" ] || die "kotlinc not found; set KOTLINC=/path/to/kotlinc (Kotlin 2.2+)"
KOTLIN_HOME="$(cd "$(dirname "$KOTLINC")/.." && pwd)"
KOTLIN_STDLIB="${KOTLIN_STDLIB:-$KOTLIN_HOME/lib/kotlin-stdlib.jar}"
[ -f "$KOTLIN_STDLIB" ] || die "KOTLIN_STDLIB not found: $KOTLIN_STDLIB"

OUT="${OUT:-${TMPDIR:-/tmp}/novelspeak-host-tests}"
mkdir -p "$OUT"
CP=""
for jar in "$ROOT/deps/commons-compress.jar" "$ROOT/deps/commons-io.jar"; do
  [ -f "$jar" ] && CP="${CP:+$CP:}$jar"
done

selected() {
  [ "${#FILTERS[@]}" -eq 0 ] && return 0
  local area filter
  IFS=',' read -ra test_areas <<< "$1"
  for filter in "${FILTERS[@]}"; do
    for area in "${test_areas[@]}"; do
      [ "$area" = "$filter" ] && return 0
    done
  done
  return 1
}

PASS=0
FAIL=0
SELECTED=0
FAILED=()

run_one() {
  local name="$1"; shift
  local jar="$OUT/$name.jar"
  local log="$OUT/$name.log"
  local srcs=("$@")
  local cp_args=()
  [ -n "$CP" ] && cp_args=(-classpath "$CP")
  if ! "$KOTLINC" "${srcs[@]}" "$ROOT/tools/$name/TestMain.kt" \
      "${cp_args[@]}" -d "$jar" >"$log" 2>&1; then
    printf 'COMPILE-FAIL %-28s\n' "$name"
    sed -n '1,10p' "$log"
    FAIL=$((FAIL + 1))
    FAILED+=("$name(compile)")
    return
  fi
  if java -cp "$jar:$KOTLIN_STDLIB" TestMainKt >>"$log" 2>&1; then
    local checks
    checks="$(grep -Eo '[0-9]+ checks?, [0-9]+ failure' "$log" | tail -1)"
    printf 'PASS  %-28s %s\n' "$name" "$checks"
    PASS=$((PASS + 1))
  else
    printf 'FAIL  %-28s log=%s\n' "$name" "$log"
    grep -E 'FAIL|FAILED|Exception|Error' "$log" | head -10
    FAIL=$((FAIL + 1))
    FAILED+=("$name")
  fi
}

while IFS=$'\t' read -r name areas sources; do
  case "$name" in ''|\#*) continue ;; esac
  selected "$areas" || continue
  SELECTED=$((SELECTED + 1))
  read -ra srcs <<< "$sources"
  run_one "$name" "${srcs[@]}"
done < "$MANIFEST"

if [ "$SELECTED" -eq 0 ]; then
  die "no harness matched area filter: ${FILTERS[*]}"
fi

printf '\nSUMMARY: %d passed, %d failed (%d selected)\n' "$PASS" "$FAIL" "$SELECTED"
if [ "$FAIL" -gt 0 ]; then
  printf 'Failed: %s\n' "${FAILED[*]}"
  exit 1
fi
