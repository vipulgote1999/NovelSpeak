#!/usr/bin/env bash
# Regression test for the manga page extractor.
#
# Proves:
#  1. a serialized chapter payload (Next.js style) yields every consecutive page even when only
#     two <img> tags are rendered;
#  2. logo/avatar images are rejected and pages come back in numeric page order;
#  3. a Webtoons-style viewer (`img._images[data-url]`, declared width/height, placeholder src)
#     yields every page in DOM order;
#  4. a Tapas-style viewer (`img.content__img[data-src]`, unique CDN directories) does the same.
#
# Needs a Chromium binary; set CHROME=/path/to/chrome if it is not "google-chrome".
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CHROME="${CHROME:-google-chrome}"
DIR="$ROOT/tools/manga-extract-test"
OUT="$(mktemp)"
OUT_WT="$(mktemp)"
OUT_TAPAS="$(mktemp)"
trap 'rm -f "$OUT" "$OUT_WT" "$OUT_TAPAS"' EXIT

if ! command -v "$CHROME" >/dev/null 2>&1; then
  echo "SKIP: Chromium binary not found ($CHROME)" >&2
  exit 77
fi

dump() {
  local fixture="$1" out="$2"
  "$CHROME" --headless=new --no-sandbox --disable-gpu \
    --allow-file-access-from-files --virtual-time-budget=20000 \
    --dump-dom "file://$DIR/$fixture" >"$out" 2>/dev/null
}

dump fixture.html "$OUT"
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

dump webtoons.html "$OUT_WT"
python3 - "$OUT_WT" <<'PY'
import html
import json
import re
import sys

raw = open(sys.argv[1], encoding='utf-8').read()
match = re.search(r'<pre id="out">(.*?)</pre>', raw, re.S)
if not match:
    raise SystemExit("FAIL: Webtoons fixture output not found")
data = json.loads(html.unescape(match.group(1)).strip())
urls = data.get("urls", [])
expected = ["viewer/a.png", "viewer/b.png", "viewer/c.png", "viewer/d.png"]
if len(urls) != 4 or not all(urls[i].endswith(expected[i]) for i in range(4)):
    raise SystemExit("FAIL: Webtoons viewer pages wrong: %s" % urls)
if any("thumbnail" in u.lower() for u in urls):
    raise SystemExit("FAIL: Webtoons thumbnails leaked into pages")
print("PASS: Webtoons viewer stack recovered in DOM order")
PY

dump tapas.html "$OUT_TAPAS"
python3 - "$OUT_TAPAS" <<'PY'
import html
import json
import re
import sys

raw = open(sys.argv[1], encoding='utf-8').read()
match = re.search(r'<pre id="out">(.*?)</pre>', raw, re.S)
if not match:
    raise SystemExit("FAIL: Tapas fixture output not found")
data = json.loads(html.unescape(match.group(1)).strip())
urls = data.get("urls", [])
expected = ["viewer/a.png", "viewer/b.png", "viewer/c.png", "viewer/d.png"]
if len(urls) != 4 or not all(urls[i].endswith(expected[i]) for i in range(4)):
    raise SystemExit("FAIL: Tapas viewer pages wrong: %s" % urls)
if any("logo" in u.lower() for u in urls):
    raise SystemExit("FAIL: Tapas logo leaked into pages")
print("PASS: Tapas viewer stack recovered in DOM order")
PY
