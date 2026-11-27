#!/usr/bin/env bash
# Regression test for the manga page extractor.
#
# Proves three things on a real browser DOM:
#  1. a serialized chapter payload (Next.js style) yields every consecutive page even when only
#     two <img> tags are rendered;
#  2. logo/avatar images are rejected;
#  3. pages come back in numeric page order.
#
# Needs a Chromium binary; set CHROME=/path/to/chrome if it is not "google-chrome".
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CHROME="${CHROME:-google-chrome}"
FIXTURE="$ROOT/tools/manga-extract-test/fixture.html"
OUT="$(mktemp)"
trap 'rm -f "$OUT"' EXIT

if ! command -v "$CHROME" >/dev/null 2>&1; then
  echo "SKIP: Chromium binary not found ($CHROME)" >&2
  exit 77
fi

"$CHROME" --headless=new --no-sandbox --disable-gpu \
  --allow-file-access-from-files --virtual-time-budget=20000 \
  --dump-dom "file://$FIXTURE" >"$OUT" 2>/dev/null

python3 - "$OUT" <<'PY'
import html
import json
import re
import sys

raw = open(sys.argv[1], encoding='utf-8').read()
match = re.search(r'<pre id="out">(.*?)</pre>', raw, re.S)
if not match:
    raise SystemExit("FAIL: extractor output not found")
payload = html.unescape(match.group(1)).strip()
if payload == "PENDING" or not payload:
    raise SystemExit("FAIL: extractor did not finish")
if payload.startswith("ERROR"):
    raise SystemExit("FAIL: " + payload)
data = json.loads(payload)
urls = data.get("urls", [])
if len(urls) != 5:
    raise SystemExit("FAIL: expected 5 consecutive pages, got %d: %s" % (len(urls), urls))
expected = ["p0%d.png" % i for i in range(1, 6)]
for i, suffix in enumerate(expected):
    if not urls[i].endswith(suffix):
        raise SystemExit("FAIL: page %d is %s, expected %s" % (i + 1, urls[i], suffix))
joined = " ".join(urls).lower()
if "logo" in joined or "avatar" in joined:
    raise SystemExit("FAIL: chrome/avatar images leaked into the page list")
if data.get("count") != 5:
    raise SystemExit("FAIL: count mismatch: %s" % data.get("count"))
print("PASS: 5 consecutive pages recovered from payload, in order, without chrome images")
PY
