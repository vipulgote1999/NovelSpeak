#!/usr/bin/env python3
"""Probe whether a router model accepts an audio content part.

The router is OpenAI-compatible. Some models advertise `audio` in /v1/models but reject an
`input_audio` part, so this sends a real WAV and prints the exact status/body. It never prints the
API key.

    ROUTER_API_KEY=... python tools/cast-benchmark/probe_audio.py \
      --wav /tmp/opencode/cast-bench/wavs/01_marshal-rennick.wav \
      --models muse-spark-1.2 gemini-2.5-flash
"""
import argparse
import base64
import json
import os
import urllib.request

BASE = os.environ.get("ROUTER_BASE", "https://free-llm-router.vipulgote5.workers.dev/v1")


def post(path, payload, key, timeout=180):
    req = urllib.request.Request(
        BASE + path,
        data=json.dumps(payload).encode(),
        headers={
            "Authorization": "Bearer " + key,
            "Content-Type": "application/json",
            "User-Agent": "novelspeak-cast-benchmark/1.0",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:
        return -1, repr(e)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--wav", required=True)
    ap.add_argument("--models", nargs="+", required=True)
    ap.add_argument("--prompt", default="Transcribe this audio exactly. Return only the words.")
    args = ap.parse_args()
    key = os.environ.get("ROUTER_API_KEY")
    if not key:
        raise SystemExit("ROUTER_API_KEY is required")
    audio = base64.b64encode(open(args.wav, "rb").read()).decode()
    for model in args.models:
        payload = {
            "model": model,
            "temperature": 0,
            "messages": [{
                "role": "user",
                "content": [
                    {"type": "text", "text": args.prompt},
                    {"type": "input_audio", "input_audio": {"data": audio, "format": "wav"}},
                ],
            }],
        }
        status, body = post("/chat/completions", payload, key)
        print(f"=== {model} status={status}")
        try:
            parsed = json.loads(body)
            message = parsed.get("choices", [{}])[0].get("message", {})
            print("content:", (message.get("content") or "")[:600])
            if parsed.get("error"):
                print("error:", json.dumps(parsed["error"])[:400])
        except Exception:
            print(body[:600])
    # Transcription endpoint is not guaranteed by the router; report the exact status.
    req = urllib.request.Request(
        BASE + "/audio/transcriptions",
        data=json.dumps({"model": "whisper-1", "file": "inline"}).encode(),
        headers={"Authorization": "Bearer " + key, "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            print("=== /audio/transcriptions status", r.status, r.read().decode()[:300])
    except Exception as e:
        code = getattr(e, "code", "-1")
        body = e.read().decode("utf-8", "replace")[:300] if hasattr(e, "read") else repr(e)
        print(f"=== /audio/transcriptions status {code} {body}")


if __name__ == "__main__":
    main()
