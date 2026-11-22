#!/usr/bin/env bash
# Focused regression selector.
#
# Map changed files to host-test areas / browser checks / Maestro tags:
#   tools/test-changed.sh                 # uncommitted + untracked files
#   tools/test-changed.sh origin/main     # committed diff against a base
#   tools/test-changed.sh --run-device    # also run the suggested Maestro tag
#
# Unknown source files deliberately select all host tests: a focused run must never
# silently skip a test because a new file was not mapped yet.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 1
BASE=""
RUN_DEVICE=0
DEVICE="${DEVICE:-emulator-5554}"

while [ "$#" -gt 0 ]; do
  case "$1" in
    -h|--help)
      sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    --run-device) RUN_DEVICE=1 ;;
    --device=*) DEVICE="${1#--device=}" ;;
    --device) shift; DEVICE="${1:-$DEVICE}" ;;
    -*) echo "unknown option: $1" >&2; exit 2 ;;
    *) BASE="$1" ;;
  esac
  shift
done

if [ -n "$BASE" ]; then
  FILES="$(git diff --name-only "$BASE" 2>/dev/null || true)"
else
  FILES="$(
    { git diff --name-only; git diff --cached --name-only; git ls-files --others --exclude-standard; } \
      | sort -u
  )"
fi

if [ -z "$FILES" ]; then
  echo "No changed files."
  exit 0
fi

echo "Changed files:"
printf '  %s\n' $FILES

AREAS=()
TAGS=()
RUN_CHROME=0
ALL=0

add_area() {
  local a existing
  for a in "$@"; do
    existing=0
    for e in "${AREAS[@]:-}"; do [ "$e" = "$a" ] && existing=1; done
    [ "$existing" = 0 ] && AREAS+=("$a")
  done
}
add_tag() {
  local t existing
  for t in "$@"; do
    existing=0
    for e in "${TAGS[@]:-}"; do [ "$e" = "$t" ] && existing=1; done
    [ "$existing" = 0 ] && TAGS+=("$t")
  done
}

while IFS= read -r f; do
  [ -n "$f" ] || continue
  case "$f" in
    app/src/app/novelspeak/Reader.kt|app/src/app/novelspeak/PcmPlayer.kt|app/src/app/novelspeak/PlaybackService.kt|app/src/app/novelspeak/SherpaEngine.kt|app/src/app/novelspeak/SystemTtsEngine.kt|app/src/app/novelspeak/AudioWritePolicy.kt|app/src/app/novelspeak/PrerollPolicy.kt|app/src/app/novelspeak/DrainGuard.kt|app/src/app/novelspeak/TtsEngine.kt)
      add_area playback tts
      add_tag reader tts regression
      ;;
    app/src/app/novelspeak/DiagnosticsBundle.kt|app/src/app/novelspeak/DiagnosticsRedaction.kt|app/src/app/novelspeak/DiagLog.kt|app/src/app/novelspeak/AboutPrivacyActivity.kt)
      add_area privacy diagnostics
      add_tag privacy
      ;;
    app/src/app/novelspeak/EpubImporter.kt|app/src/app/novelspeak/PdfTextImporter.kt)
      add_area import
      ;;
    app/assets/extract.js|app/src/app/novelspeak/WebExtractor.kt|app/src/app/novelspeak/ChapterUrls.kt)
      add_area extraction url
      add_tag reading fixture url
      RUN_CHROME=1
      ;;
    app/assets/adblock/*|app/src/app/novelspeak/adblock/*|app/src/app/novelspeak/AdBlocker.kt)
      add_area adblock url
      RUN_CHROME=1
      ;;
    app/src/app/novelspeak/library/*)
      add_area library
      add_tag library
      ;;
    app/src/app/novelspeak/cast/*)
      add_area cast tts
      ;;
    app/src/app/novelspeak/UrlInput.kt)
      add_area url web
      add_tag url reading
      ;;
    app/src/app/novelspeak/ModelManager.kt|app/src/app/novelspeak/ModelValidation.kt|app/src/app/novelspeak/ModelInstallPolicy.kt|app/src/app/novelspeak/ModelCatalog.kt)
      add_area model tts
      ;;
    app/src/app/novelspeak/Design.kt|app/src/app/novelspeak/PlayerChrome.kt|app/src/app/novelspeak/Motion.kt|app/src/app/novelspeak/library/LibraryGrid.kt)
      add_area ui
      add_tag ui smoke settings home
      ;;
    app/src/app/novelspeak/SettingsActivity.kt|app/src/app/novelspeak/LibraryActivity.kt|app/src/app/novelspeak/MainActivity.kt)
      ALL=1
      add_tag smoke settings home reading library
      ;;
    tools/maestro/*|tools/test-page/*)
      add_tag smoke ui reading library settings home
      ;;
    docs/*|*.md|README*|CHANGELOG*|LICENSE|.gitignore)
      ;;
    *)
      [ -e "$f" ] && ALL=1
      ;;
  esac
done <<< "$FILES"

if [ "$ALL" = 1 ]; then
  AREAS=()
  echo
  echo "Unmapped or broad change: running the complete host suite."
  tools/test-host.sh || exit $?
elif [ "${#AREAS[@]}" -gt 0 ]; then
  CSV="$(IFS=,; echo "${AREAS[*]}")"
  echo
  echo "Host areas: $CSV"
  tools/test-host.sh --area="$CSV" || exit $?
else
  echo
  echo "No host-test area selected (docs-only change)."
fi

if [ "$RUN_CHROME" = 1 ]; then
  echo
  echo "Browser regressions:"
  bash tools/extract-test/run.sh || exit $?
  bash tools/adblock-test/procedural.sh || exit $?
fi

if [ "${#TAGS[@]}" -gt 0 ]; then
  TAG_CSV="$(IFS=,; echo "${TAGS[*]}")"
  echo
  echo "Suggested device focus:"
  echo "  tools/maestro/run.sh --tag=$TAG_CSV"
  if [ "$RUN_DEVICE" = 1 ]; then
    if "$HOME/Android/Sdk/platform-tools/adb" -s "$DEVICE" get-state >/dev/null 2>&1; then
      DEVICE="$DEVICE" tools/maestro/run.sh --tag="$TAG_CSV"
    else
      echo "  skipped: $DEVICE is not connected"
    fi
  fi
fi
