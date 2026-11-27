#!/usr/bin/env bash
# Regression test: text hidden by an adblock cosmetic rule must not reach TTS extraction.
# Needs a Chromium binary; set CHROME=/path/to/chrome if it is not "google-chrome".
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CHROME="${CHROME:-google-chrome}"
FIXTURE="$ROOT/tools/extract-test/ad-hidden.html"
OVERSIZE_FIXTURE="$ROOT/tools/extract-test/oversize.html"
NEXT_FIXTURE="$ROOT/tools/extract-test/next-link.html"
OUT="$(mktemp)"
OUT2="$(mktemp)"
OUT3="$(mktemp)"
trap 'rm -f "$OUT" "$OUT2" "$OUT3"' EXIT

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

"$CHROME" --headless=new --no-sandbox --disable-gpu \
  --allow-file-access-from-files --virtual-time-budget=2500 \
  --dump-dom "file://$OVERSIZE_FIXTURE" >"$OUT2" 2>/dev/null

python3 - "$OUT2" <<'PY'
import html
import json
import re
import sys

raw = open(sys.argv[1], encoding='utf-8').read()
match = re.search(r'<pre id="out">(.*?)</pre>', raw, re.S)
if not match:
    raise SystemExit("FAIL: oversized extraction output not found")
payload = html.unescape(match.group(1))
if payload.startswith("ERROR"):
    raise SystemExit("FAIL: " + payload)
data = json.loads(payload)
blocks = data.get("blocks", [])
chars = sum(len(b.get("text", "")) for b in blocks)
if len(blocks) > 4000:
    raise SystemExit("FAIL: block cap not applied (%d)" % len(blocks))
if chars > 250000:
    raise SystemExit("FAIL: char cap not applied (%d)" % chars)
if not data.get("truncated"):
    raise SystemExit("FAIL: oversized page was not marked truncated")
print("PASS: oversized page capped at %d blocks / %d chars" % (len(blocks), chars))
PY

"$CHROME" --headless=new --no-sandbox --disable-gpu \
  --allow-file-access-from-files --virtual-time-budget=1500 \
  --dump-dom "file://$NEXT_FIXTURE" >"$OUT3" 2>/dev/null

python3 - "$OUT3" <<'PY'
import html
import re
import sys

raw = open(sys.argv[1], encoding='utf-8').read()
match = re.search(r'<pre id="out">(.*?)</pre>', raw, re.S)
if not match:
    raise SystemExit("FAIL: next-link output not found")
payload = html.unescape(match.group(1)).strip()
if payload.startswith("ERROR"):
    raise SystemExit("FAIL: " + payload)
if "chapter-two.html" not in payload:
    raise SystemExit("FAIL: __nsNextLink did not resolve the rel=next target: " + payload)
if not payload.startswith("file://"):
    raise SystemExit("FAIL: expected an absolute file URL, got: " + payload)
print("PASS: __nsNextLink resolves the rel=next target")
PY
