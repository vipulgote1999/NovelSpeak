#!/usr/bin/env bash
# Regression test: text hidden by an adblock cosmetic rule must not reach TTS extraction.
# Needs a Chromium binary; set CHROME=/path/to/chrome if it is not "google-chrome".
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CHROME="${CHROME:-google-chrome}"
FIXTURE="$ROOT/tools/extract-test/ad-hidden.html"
OUT="$(mktemp)"
trap 'rm -f "$OUT"' EXIT

if ! command -v "$CHROME" >/dev/null 2>&1; then
  echo "SKIP: Chromium binary not found ($CHROME)" >&2
  exit 77
fi

"$CHROME" --headless=new --no-sandbox --disable-gpu \
  --allow-file-access-from-files --virtual-time-budget=1500 \
  --dump-dom "file://$FIXTURE" >"$OUT" 2>/dev/null

python3 - "$OUT" <<'PY'
import html
import json
import re
import sys

raw = open(sys.argv[1], encoding='utf-8').read()
match = re.search(r'<pre id="out">(.*?)</pre>', raw, re.S)
if not match:
    raise SystemExit("FAIL: extraction output not found")
payload = html.unescape(match.group(1))
if payload.startswith("ERROR"):
    raise SystemExit("FAIL: " + payload)
data = json.loads(payload)
texts = [b.get("text", "") for b in data.get("blocks", [])]
real = "The harbour was quiet" in " ".join(texts)
ad = "SPONSORED" in " ".join(texts)
if not real:
    raise SystemExit("FAIL: visible chapter text was dropped")
if ad:
    raise SystemExit("FAIL: cosmetic-hidden ad text was extracted for TTS")
print("PASS: hidden ad dropped, visible chapter text kept")
PY
