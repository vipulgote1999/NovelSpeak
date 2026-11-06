#!/usr/bin/env python3
"""Transcribe each cast chunk with an audio-capable router model and compute WER.

The reference is the exact text the app synthesized (from ParserMain.kt). The hypothesis comes
from the model with no source text in the prompt, so the comparison is not self-fulfilling.

    ROUTER_API_KEY=... python tools/cast-benchmark/transcribe.py \
      --chunks /tmp/opencode/scene-chunks.json \
      --audio-dir /tmp/opencode/cast-bench/wavs \
      --out /tmp/opencode/cast-bench/asr.json
"""
import argparse
import base64
import json
import os
import re
import time
import urllib.error
import urllib.request

BASE = os.environ.get("ROUTER_BASE", "https://free-llm-router.vipulgote5.workers.dev/v1")


def post(payload, key, timeout=180):
    req = urllib.request.Request(
        BASE + "/chat/completions",
        data=json.dumps(payload).encode(),
        headers={
            "Authorization": "Bearer " + key,
            "Content-Type": "application/json",
            "User-Agent": "novelspeak-cast-benchmark/1.0",
        },
    )
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8", "replace"))


def norm(text):
    text = text.lower().replace("’", "'").replace("“", '"').replace("”", '"')
    text = re.sub(r"[^a-z0-9' ]+", " ", text)
    return re.sub(r"\s+", " ", text).strip()


def wer(reference, hypothesis):
    ref = norm(reference).split()
    hyp = norm(hypothesis).split()
    if not ref:
        return 0.0 if not hyp else 1.0
    prev = list(range(len(hyp) + 1))
    for i, rw in enumerate(ref, 1):
        cur = [i]
        for j, hw in enumerate(hyp, 1):
            cur.append(min(
                prev[j] + 1,
                cur[j - 1] + 1,
                prev[j - 1] + (0 if rw == hw else 1),
            ))
        prev = cur
    return prev[-1] / len(ref)


def clean(text):
    text = text.strip()
    text = re.sub(r"^\d{1,2}:\d{2}(:\d{2})?\s*", "", text, flags=re.M)
    return text.strip()


def transcribe(path, model, key):
    audio = base64.b64encode(open(path, "rb").read()).decode()
    payload = {
        "model": model,
        "temperature": 0,
        "messages": [{
            "role": "user",
            "content": [
                {"type": "text", "text":
                    "Transcribe this audiobook clip word for word. Keep contractions. "
                    "Return only the spoken words, no commentary and no timestamps."},
                {"type": "input_audio", "input_audio": {"data": audio, "format": "wav"}},
            ],
        }],
    }
    for attempt in range(6):
        try:
            data = post(payload, key)
            return clean((data.get("choices", [{}])[0].get("message", {}).get("content") or ""))
        except urllib.error.HTTPError as e:
            body = e.read().decode("utf-8", "replace")[:200]
            if e.code in (403, 408, 429, 500, 502, 503, 504):
                time.sleep(12 + attempt * 8)
                continue
            raise RuntimeError(f"HTTP {e.code}: {body}")
    raise RuntimeError("retries exhausted")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--chunks", required=True)
    ap.add_argument("--audio-dir", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--model", default="gemini-2.5-flash")
    ap.add_argument("--sleep", type=float, default=1.2)
    args = ap.parse_args()
    key = os.environ.get("ROUTER_API_KEY")
    if not key:
        raise SystemExit("ROUTER_API_KEY is required")

    doc = json.load(open(args.chunks))
    done = {}
    if os.path.exists(args.out):
        try:
            previous = json.load(open(args.out))
            if previous.get("model") == args.model:
                done = {r["index"]: r for r in previous.get("rows", [])}
        except Exception:
            done = {}
    rows = []
    for i, c in enumerate(doc["chunks"]):
        speaker = c["speakerId"]
        if i in done:
            rows.append(done[i])
            print(f"{i:02d} {speaker:16} cached wer={done[i]['wer']:.2f}")
            continue
        path = os.path.join(args.audio_dir, f"{i:02d}_{speaker}.wav")
        hypothesis = transcribe(path, args.model, key)
        score = wer(c["text"], hypothesis)
        rows.append({
            "index": i,
            "speakerId": speaker,
            "reference": c["text"],
            "hypothesis": hypothesis,
            "wer": round(score, 3),
            "exact": norm(c["text"]) == norm(hypothesis),
        })
        print(f"{i:02d} {speaker:16} wer={score:.2f} ref={c['text'][:48]!r} hyp={hypothesis[:48]!r}")
        json.dump({"model": args.model, "rows": rows}, open(args.out, "w"), indent=2)
        time.sleep(args.sleep)

    overall = sum(r["wer"] for r in rows) / len(rows) if rows else 0.0
    word_errors = sum(r["wer"] * len(norm(r["reference"]).split()) for r in rows)
    words = sum(len(norm(r["reference"]).split()) for r in rows)
    by_speaker = {}
    for r in rows:
        by_speaker.setdefault(r["speakerId"], []).append(r["wer"])
    summary = {
        "model": args.model,
        "chunks": len(rows),
        "meanWer": round(overall, 3),
        "wordErrorRate": round(word_errors / words, 3) if words else 0.0,
        "exactMatches": sum(1 for r in rows if r["exact"]),
        "bySpeaker": {
            k: round(sum(v) / len(v), 3) for k, v in by_speaker.items()
        },
        "rows": rows,
    }
    json.dump(summary, open(args.out, "w"), indent=2)
    print(f"\nmodel={args.model} chunks={len(rows)} meanWER={summary['meanWer']:.3f} "
          f"wordErrorRate={summary['wordErrorRate']:.3f} exact={summary['exactMatches']}/{len(rows)}")
    print("bySpeaker", summary["bySpeaker"])


if __name__ == "__main__":
    main()
