#!/usr/bin/env python3
"""Multi-round speaker-switch probe for Story Cast.

The scene's speaker changes are confounded by sentence length. This holds the text constant and
alternates sids, so the only difference is the speaker embedding. Three rounds by default.

    /tmp/opencode/castbench-venv/bin/python tools/cast-benchmark/switch_probe.py \
      --family supertonic --model-dir /tmp/opencode/models/supertonic \
      --out /tmp/opencode/switch-supertonic.json
"""
import argparse
import json
import os
import statistics
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from run_benchmark import build_tts  # noqa: E402

TEXT = "The lantern flickered once and the room went quiet."
SIDS = [5, 1, 6]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--family", choices=["supertonic", "kokoro"], required=True)
    ap.add_argument("--model-dir", required=True)
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--speed", type=float, default=1.0)
    ap.add_argument("--rounds", type=int, default=3)
    ap.add_argument("--reps", type=int, default=9)
    ap.add_argument("--out", default="")
    args = ap.parse_args()

    tts = build_tts(args.model_dir, args.threads, args.family)
    tts.generate("Warm up the model.", sid=0, speed=args.speed)
    same_ids = [5] * args.reps
    alt_ids = [SIDS[i % len(SIDS)] for i in range(args.reps)]

    def timed(sid):
        started = time.perf_counter()
        tts.generate(TEXT, sid=sid, speed=args.speed)
        return (time.perf_counter() - started) * 1000.0

    rounds = []
    for r in range(args.rounds):
        # Alternate the order each round so thermal drift does not always favour one block.
        if r % 2 == 0:
            same = [timed(s) for s in same_ids]
            alt = [timed(s) for s in alt_ids]
        else:
            alt = [timed(s) for s in alt_ids]
            same = [timed(s) for s in same_ids]
        same_mean = statistics.mean(same)
        alt_mean = statistics.mean(alt)
        rounds.append({
            "round": r + 1,
            "sameMeanMs": round(same_mean, 1),
            "alternatingMeanMs": round(alt_mean, 1),
            "overheadMs": round(alt_mean - same_mean, 1),
        })
        print(json.dumps(rounds[-1]))

    overheads = [r["overheadMs"] for r in rounds]
    result = {
        "family": args.family,
        "threads": args.threads,
        "repsPerBlock": args.reps,
        "rounds": rounds,
        "medianOverheadMs": round(statistics.median(overheads), 1),
        "minOverheadMs": min(overheads),
        "maxOverheadMs": max(overheads),
    }
    print(json.dumps(result, indent=2))
    if args.out:
        json.dump(result, open(args.out, "w"), indent=2)


if __name__ == "__main__":
    main()
