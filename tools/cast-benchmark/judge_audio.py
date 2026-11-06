#!/usr/bin/env python3
"""Multimodal quality judge for a Story Cast scene.

Sends the synthesized WAV, the source scene with speaker labels, and the cast-sheet screenshot to
an audio-capable model. The model must return strict JSON ratings; the script stores the raw
response and the parsed object.

    ROUTER_API_KEY=... python tools/cast-benchmark/judge_audio.py \
      --wav /tmp/opencode/cast-bench/scene.wav \
      --source tools/cast-parser-test/fixtures/unattributed-scene.txt \
      --image docs/media/ui-audit/phone-cast.png \
      --model gemini-2.5-flash \
      --out /tmp/opencode/cast-bench/judge.json
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

PROMPT = """You are a strict, experienced audiobook director evaluating a text-to-speech cast.

Listen to the entire audio clip. Then compare it with the source scene and cast sheet below.

Judge these dimensions on a 1-5 scale, where 3 is acceptable, 4 is good and 5 is professional:
- naturalness: prosody, rhythm, pronunciation, absence of robotic artifacts
- character_distinction: can you tell the narrator, Rennick, Mara and Tomas apart by voice alone?
- emotional_fit: does the delivery match the tension of the scene?
- pacing: are pauses and speaker turns natural, without dead air or clipped words?
- intelligibility: are the words clear and complete?
- overall: the single overall score for using this in a reading app

Also report:
- "biggest_issue": one concrete problem, or "none"
- "per_character": an object mapping each named character to a one-sentence note
- "segment_notes": up to 3 specific lines/timestamps with problems
- "confidence": 0.0-1.0

Be honest and specific. Do not reward the concept; judge the audio you actually hear.
Return ONLY valid JSON with exactly these keys. No markdown fence.

CAST:
- Narrator / unresolved lines: Supertonic F1
- Marshal Rennick: Supertonic M1 (sid 5)
- Mara: Supertonic F2 (sid 1)
- Tomas: Supertonic M2 (sid 6)
Two lines ("He's right here." and "Mine,") are intentionally unresolved and stay in the narrator
voice; do not treat that as a casting failure, but do note whether it sounds confusing.

SOURCE SCENE:
"""


def post(payload, key, timeout=300):
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


def extract_json(text):
    text = text.strip()
    text = re.sub(r"^```(?:json)?\s*", "", text)
    text = re.sub(r"\s*```$", "", text)
    start = text.find("{")
    end = text.rfind("}")
    if start >= 0 and end > start:
        return json.loads(text[start:end + 1])
    raise ValueError("no JSON object in response: " + text[:300])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--wav", required=True)
    ap.add_argument("--source", required=True)
    ap.add_argument("--image", default="")
    ap.add_argument("--model", default="gemini-2.5-flash")
    ap.add_argument("--note", default="")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    key = os.environ.get("ROUTER_API_KEY")
    if not key:
        raise SystemExit("ROUTER_API_KEY is required")

    scene = open(args.source, encoding="utf-8").read()
    audio = base64.b64encode(open(args.wav, "rb").read()).decode()
    note = (args.note.strip() + "\n\n") if args.note.strip() else ""
    content = [{"type": "text", "text": PROMPT + note + scene}]
    if args.image and os.path.exists(args.image):
        image = base64.b64encode(open(args.image, "rb").read()).decode()
        content.append({
            "type": "image_url",
            "image_url": {"url": "data:image/png;base64," + image},
        })
        content.append({
            "type": "text",
            "text": "The attached screenshot is the cast sheet the user sees for this scene.",
        })
    audio_format = "mp3" if args.wav.lower().endswith(".mp3") else "wav"
    content.append({"type": "input_audio", "input_audio": {"data": audio, "format": audio_format}})

    payload = {
        "model": args.model,
        "temperature": 0,
        "messages": [{"role": "user", "content": content}],
    }
    last = None
    for attempt in range(5):
        try:
            data = post(payload, key)
            text = (data.get("choices", [{}])[0].get("message", {}).get("content") or "")
            print("raw response:\n", text[:3000])
            parsed = extract_json(text)
            result = {
                "model": args.model,
                "audio": args.wav,
                "image": args.image,
                "usage": data.get("usage"),
                "ratings": parsed,
            }
            json.dump(result, open(args.out, "w"), indent=2)
            print("\nparsed ratings:", json.dumps(parsed, indent=2))
            return
        except urllib.error.HTTPError as e:
            body = e.read().decode("utf-8", "replace")[:300]
            last = f"HTTP {e.code}: {body}"
            print(last)
            if e.code in (403, 408, 413, 429, 500, 502, 503, 504):
                time.sleep(15 + attempt * 10)
                continue
            raise
        except Exception as e:
            last = repr(e)
            print(last)
            time.sleep(5)
    raise SystemExit("judge failed: " + str(last))


if __name__ == "__main__":
    main()
