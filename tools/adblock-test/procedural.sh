#!/usr/bin/env bash
# Regression test for uBO-style procedural cosmetic operators in content.js.
# Needs a Chromium binary; set CHROME=/path/to/chrome if it is not "google-chrome".
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CHROME="${CHROME:-google-chrome}"
FIXTURE="$ROOT/tools/adblock-test/procedural.html"
OUT="$(mktemp)"
trap 'rm -f "$OUT"' EXIT

if ! command -v "$CHROME" >/dev/null 2>&1; then
  echo "SKIP: Chromium binary not found ($CHROME)" >&2
  exit 77
fi

"$CHROME" --headless=new --no-sandbox --disable-gpu \
  --allow-file-access-from-files --virtual-time-budget=2000 \
  --dump-dom "file://$FIXTURE" >"$OUT" 2>/dev/null

python3 - "$OUT" <<'PY'
import html
import re
import sys

raw = open(sys.argv[1], encoding='utf-8').read()
match = re.search(r'<pre id="out">(.*?)</pre>', raw, re.S)
if not match:
    raise SystemExit("FAIL: procedural output not found")
payload = html.unescape(match.group(1))
if payload.startswith("FAIL") or not payload.startswith("PASS"):
    raise SystemExit(payload)
print(payload)
PY
