#!/usr/bin/env python3
"""Story Cast host benchmark: synthesize the real cast queue with an on-device TTS family.

Input is the JSON emitted by ParserMain.kt, so the text and speaker order are exactly what the
reader queues. Each chunk is synthesized with the same speaker id the app uses, concatenated with
250 ms of silence, and measured for latency, RTF, clipping, F0 and spectral balance.

    /tmp/opencode/castbench-venv/bin/python tools/cast-benchmark/run_benchmark.py \
      --chunks /tmp/opencode/scene-chunks.json \
      --family supertonic \
      --model-dir /tmp/opencode/models/supertonic \
      --out /tmp/opencode/cast-bench

    ... --family kokoro --model-dir /tmp/opencode/models/kokoro/kokoro-int8-en-v0_19

Outputs: metrics.json, summary.md, scene.wav, chunks/*.wav
"""
import argparse
import json
import os
import re
import time
import wave

import numpy as np
import sherpa_onnx

# Canonical cast for the fixture. Matches VoiceCastPlanner's gender-aware assignment.
# Supertonic 0..4 = F1..F5, 5..9 = M1..M5.
# Kokoro 0=Heart, 1=Bella, 5=Adam, 6=Michael (US female/female/male/male).
CAST = {
    "supertonic": {
        "sid": {
            "NARRATOR": 0,
            "UNKNOWN": 0,
            "marshal-rennick": 5,
            "mara": 1,
            "tomas": 6,
        },
        "label": {0: "F1", 1: "F2", 5: "M1", 6: "M2"},
        "default_rate": 22050,
        "model": "sherpa-onnx-supertonic-3-tts-int8-2026-05-11",
    },
    "kokoro": {
        "sid": {
            "NARRATOR": 0,
            "UNKNOWN": 0,
            "marshal-rennick": 5,
            "mara": 1,
            "tomas": 6,
        },
        "label": {0: "Heart", 1: "Bella", 5: "Adam", 6: "Michael"},
        "default_rate": 24000,
        "model": "kokoro-int8-en-v0_19",
    },
}

GAP_SECONDS = 0.25
FRAME = 1024
HOP = 256


def kokoro_lexicon(model_dir):
    """The en-v0_19 bundle has no lexicon; add the cast names so Kokoro does not skip them."""
    source = os.path.join(model_dir, "lexicon-us-en.txt")
    patched = os.path.join(model_dir, "lexicon-cast-en.txt")
    if os.path.exists(source) and not os.path.exists(patched):
        with open(source, encoding="utf-8") as f:
            text = f.read()
        text += (
            "\nrennick ɹ ˈ ɛ n ɪ k\n"
            "tomas t ˈ O m ə s\n"
            "mara m ˈ ɑ ɹ ə\n"
        )
        with open(patched, "w", encoding="utf-8") as f:
            f.write(text)
    if os.path.exists(patched):
        return patched
    return source


def build_tts(model_dir, threads, family):
    if family == "kokoro":
        kokoro = sherpa_onnx.OfflineTtsKokoroModelConfig(
            model=os.path.join(model_dir, "model.int8.onnx"),
            voices=os.path.join(model_dir, "voices.bin"),
            tokens=os.path.join(model_dir, "tokens.txt"),
            data_dir=os.path.join(model_dir, "espeak-ng-data"),
            # The en-v0_19 tarball omits the lexicon that the Python binding needs; the
            # Android AAR uses eSpeak directly. Use the matching US English lexicon plus
            # the three cast names that would otherwise be skipped as OOV.
            lexicon=kokoro_lexicon(model_dir),
            dict_dir="",
            lang="en-us",
            length_scale=1.0,
        )
        model = sherpa_onnx.OfflineTtsModelConfig(
            kokoro=kokoro, num_threads=threads, provider="cpu"
        )
    else:
        model = sherpa_onnx.OfflineTtsModelConfig(
            supertonic=sherpa_onnx.OfflineTtsSupertonicModelConfig(
                duration_predictor=os.path.join(model_dir, "duration_predictor.int8.onnx"),
                text_encoder=os.path.join(model_dir, "text_encoder.int8.onnx"),
                vector_estimator=os.path.join(model_dir, "vector_estimator.int8.onnx"),
                vocoder=os.path.join(model_dir, "vocoder.int8.onnx"),
                tts_json=os.path.join(model_dir, "tts.json"),
                unicode_indexer=os.path.join(model_dir, "unicode_indexer.bin"),
                voice_style=os.path.join(model_dir, "voice.bin"),
            ),
            num_threads=threads,
            provider="cpu",
        )
    return sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=model))


def write_wav(path, samples, sample_rate):
    data = np.clip(samples, -1.0, 1.0)
    pcm = (data * 32767.0).astype("<i2").tobytes()
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sample_rate)
        w.writeframes(pcm)


def frames(samples):
    if len(samples) < FRAME:
        return np.zeros((1, FRAME), dtype=np.float32)
    count = 1 + (len(samples) - FRAME) // HOP
    return np.stack([samples[i * HOP:i * HOP + FRAME] for i in range(count)])


def f0_track(samples, sample_rate):
    """Autocorrelation F0 on voiced 46 ms frames. Returns Hz values only."""
    out = []
    min_lag = int(sample_rate / 350.0)
    max_lag = int(sample_rate / 60.0)
    for frame in frames(samples):
        rms = float(np.sqrt(np.mean(frame * frame)))
        if rms < 0.01:
            continue
        centered = frame - frame.mean()
        corr = np.correlate(centered, centered, mode="full")[len(centered) - 1:]
        if corr[0] <= 0:
            continue
        corr = corr / corr[0]
        lo = min(max_lag, len(corr) - 1)
        if lo <= min_lag:
            continue
        lag = int(np.argmax(corr[min_lag:lo]) + min_lag)
        strength = float(corr[lag])
        if strength < 0.35:
            continue
        out.append(sample_rate / lag)
    return out


def spectral_centroid(samples, sample_rate):
    spec = np.abs(np.fft.rfft(samples * np.hanning(len(samples))))
    freqs = np.fft.rfftfreq(len(samples), 1.0 / sample_rate)
    total = spec.sum()
    return float((spec * freqs).sum() / total) if total > 0 else 0.0


def percentile(values, p):
    return float(np.percentile(values, p)) if values else 0.0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--chunks", required=True)
    ap.add_argument("--model-dir", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--family", choices=sorted(CAST), default="supertonic")
    ap.add_argument("--model-id", default="")
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--speed", type=float, default=1.0)
    ap.add_argument("--gap-ms", type=int, default=250)
    args = ap.parse_args()

    global GAP_SECONDS
    GAP_SECONDS = args.gap_ms / 1000.0
    family_cfg = CAST[args.family]
    sid_map = family_cfg["sid"]
    label_map = family_cfg["label"]
    model_id = args.model_id or family_cfg["model"]
    os.makedirs(args.out, exist_ok=True)
    os.makedirs(os.path.join(args.out, "chunks"), exist_ok=True)

    doc = json.load(open(args.chunks))
    chunks = doc["chunks"]

    t0 = time.perf_counter()
    tts = build_tts(args.model_dir, args.threads, args.family)
    load_ms = (time.perf_counter() - t0) * 1000.0

    # Warm-up is excluded from steady-state latency but reported separately.
    warm = tts.generate("Warm up the model.", sid=0, speed=args.speed)
    warm_ms = (time.perf_counter() - t0) * 1000.0 - load_ms
    scene_rate = int(warm.sample_rate) or family_cfg["default_rate"]

    # Controlled speaker-switch probe: identical text, alternating sids. This isolates the cost
    # of changing speaker from the cost of a longer sentence (which confounds the scene rows).
    probe_text = "The lantern flickered once and the room went quiet."
    probe_sids = [5, 1, 6, 5, 1, 6, 5, 1, 6]
    same_times = []
    for _ in range(len(probe_sids)):
        s = time.perf_counter()
        tts.generate(probe_text, sid=5, speed=args.speed)
        same_times.append((time.perf_counter() - s) * 1000.0)
    switch_times = []
    for sid in probe_sids:
        s = time.perf_counter()
        tts.generate(probe_text, sid=sid, speed=args.speed)
        switch_times.append((time.perf_counter() - s) * 1000.0)
    controlled_overhead = float(np.mean(switch_times)) - float(np.mean(same_times))

    rows = []
    scene = []
    silence = np.zeros(int(scene_rate * GAP_SECONDS), dtype=np.float32)
    previous_speaker = None
    switch_synth_ms = []
    same_synth_ms = []
    play_clock = 0.0

    print(f"loaded in {load_ms:.0f} ms, warm-up {warm_ms:.0f} ms, {len(chunks)} chunks")
    for i, c in enumerate(chunks):
        speaker = c["speakerId"]
        sid = sid_map.get(speaker, 0)
        text = c["text"]
        started = time.perf_counter()
        audio = tts.generate(text, sid=sid, speed=args.speed)
        synth_ms = (time.perf_counter() - started) * 1000.0
        samples = np.asarray(audio.samples, dtype=np.float32)
        rate = int(audio.sample_rate) or scene_rate
        if rate != scene_rate:
            raise SystemExit(
                f"chunk {i} sample rate {rate} != scene rate {scene_rate}; refusing to write a "
                "mislabelled scene"
            )
        duration = len(samples) / rate
        rtf = synth_ms / 1000.0 / duration if duration > 0 else 0.0
        peak = float(np.max(np.abs(samples))) if len(samples) else 0.0
        rms = float(np.sqrt(np.mean(samples * samples))) if len(samples) else 0.0
        f0 = f0_track(samples, rate)
        is_switch = previous_speaker is not None and speaker != previous_speaker
        if previous_speaker is not None:
            (switch_synth_ms if is_switch else same_synth_ms).append(synth_ms)

        row = {
            "index": i,
            "blockIndex": c["blockIndex"],
            "type": c["type"],
            "speakerId": speaker,
            "sid": sid,
            "voice": label_map.get(sid, str(sid)),
            "text": text,
            "chars": len(text),
            "synth_ms": round(synth_ms, 1),
            "audio_s": round(duration, 3),
            "rtf": round(rtf, 3),
            "peak": round(peak, 4),
            "rms": round(rms, 4),
            "f0_median": round(float(np.median(f0)), 1) if f0 else None,
            "f0_iqr": [round(percentile(f0, 25), 1), round(percentile(f0, 75), 1)] if f0 else None,
            "centroid_hz": round(spectral_centroid(samples, rate), 1) if len(samples) else 0.0,
            "switch": is_switch,
            "play_start_s": round(play_clock, 3),
        }
        rows.append(row)
        write_wav(os.path.join(args.out, "chunks", f"{i:02d}_{speaker}.wav"), samples, rate)
        scene.append(samples)
        scene.append(silence)
        play_clock += duration + GAP_SECONDS
        previous_speaker = speaker
        print(f"{i:02d} {label_map.get(sid, str(sid)):7} {synth_ms:7.0f}ms {duration:6.2f}s "
              f"rtf={rtf:.2f} {text[:56]}")

    scene_audio = np.concatenate(scene) if scene else np.zeros(1, dtype=np.float32)
    write_wav(os.path.join(args.out, "scene.wav"), scene_audio, scene_rate)

    # Speaker feature profiles.
    profiles = {}
    for row in rows:
        key = row["speakerId"]
        profiles.setdefault(key, {"voice": row["voice"], "f0": [], "centroid": [], "rms": []})
        if row["f0_median"]:
            profiles[key]["f0"].append(row["f0_median"])
        profiles[key]["centroid"].append(row["centroid_hz"])
        profiles[key]["rms"].append(row["rms"])
    profile_out = {}
    for key, p in profiles.items():
        profile_out[key] = {
            "voice": p["voice"],
            "f0_median_hz": round(float(np.median(p["f0"])), 1) if p["f0"] else None,
            "centroid_hz": round(float(np.median(p["centroid"])), 1),
            "rms": round(float(np.median(p["rms"])), 4),
            "chunks": len(p["centroid"]),
        }

    # Pairwise voice distance: relative F0 plus normalised centroid.
    keys = [k for k in profile_out if k not in ("NARRATOR", "UNKNOWN")]
    distances = {}
    for a in keys:
        for b in keys:
            if a >= b:
                continue
            fa, fb = profile_out[a]["f0_median_hz"], profile_out[b]["f0_median_hz"]
            ca, cb = profile_out[a]["centroid_hz"], profile_out[b]["centroid_hz"]
            f0_delta = abs(fa - fb) / max(fa or 1, fb or 1) if fa and fb else 0.0
            c_delta = abs(ca - cb) / max(ca or 1, cb or 1)
            distances[f"{a} vs {b}"] = {
                "f0_relative_delta": round(f0_delta, 3),
                "centroid_relative_delta": round(c_delta, 3),
            }

    synth = [r["synth_ms"] for r in rows]
    scene_seconds = len(scene_audio) / scene_rate
    words = sum(len(re.findall(r"[A-Za-z0-9']+", c["text"])) for c in chunks)
    summary = {
        "family": args.family,
        "model": model_id,
        "threads": args.threads,
        "speed": args.speed,
        "sample_rate": scene_rate,
        "words": words,
        "wordsPerMinute": round(words / scene_seconds * 60.0, 1) if scene_seconds else 0.0,
        "chunks": len(rows),
        "characters": len(keys),
        "unresolvedLines": doc.get("unresolved", 0),
        "modelLoadMs": round(load_ms, 1),
        "warmupMs": round(warm_ms, 1),
        "sceneAudioSeconds": round(scene_seconds, 2),
        "totalSynthMs": round(sum(synth), 1),
        "overallRtf": round(sum(synth) / 1000.0 / scene_seconds, 3),
        "firstChunkLatencyMs": rows[0]["synth_ms"] if rows else 0,
        "synthMs": {
            "mean": round(float(np.mean(synth)), 1) if synth else 0,
            "p50": round(percentile(synth, 50), 1),
            "p95": round(percentile(synth, 95), 1),
            "max": round(max(synth), 1) if synth else 0,
        },
        "rtf": {
            "p50": round(percentile([r["rtf"] for r in rows], 50), 3),
            "p95": round(percentile([r["rtf"] for r in rows], 95), 3),
        },
        "speakerSwitches": len(switch_synth_ms),
        "controlledProbe": {
            "text": probe_text,
            "sameSpeakerMeanMs": round(float(np.mean(same_times)), 1),
            "alternatingMeanMs": round(float(np.mean(switch_times)), 1),
            "switchOverheadMs": round(controlled_overhead, 1),
            "reps": len(probe_sids),
        },
        "interChunkSilenceMs": args.gap_ms,
        "clippingChunks": sum(1 for r in rows if r["peak"] >= 0.999),
        "peakMax": round(max((r["peak"] for r in rows), default=0.0), 4),
        "profiles": profile_out,
        "voiceDistances": distances,
        "rows": rows,
    }
    with open(os.path.join(args.out, "metrics.json"), "w") as f:
        json.dump(summary, f, indent=2)

    lines = [
        "# Story Cast benchmark",
        "",
        f"- Model: `{summary['model']}`",
        f"- Threads: {args.threads}; speed {args.speed}×; {summary['chunks']} chunks",
        f"- Scene audio: **{summary['sceneAudioSeconds']} s**; synthesis: "
        f"**{summary['totalSynthMs'] / 1000.0:.2f} s**; overall RTF **{summary['overallRtf']}**; "
        f"{summary['wordsPerMinute']} words/min",
        f"- Model load: {summary['modelLoadMs']} ms; first chunk: "
        f"{summary['firstChunkLatencyMs']} ms",
        f"- Synth latency p50/p95/max: {summary['synthMs']['p50']} / "
        f"{summary['synthMs']['p95']} / {summary['synthMs']['max']} ms",
        f"- Speaker switches: {summary['speakerSwitches']} (every cue changes speaker except the "
        f"opening narration)",
        f"- Controlled switch probe ({len(probe_sids)} reps, identical text): "
        f"same-speaker {summary['controlledProbe']['sameSpeakerMeanMs']} ms vs alternating "
        f"{summary['controlledProbe']['alternatingMeanMs']} ms → overhead "
        f"**{summary['controlledProbe']['switchOverheadMs']} ms**",
        f"- Clipping chunks: {summary['clippingChunks']}; peak {summary['peakMax']}",
        "",
        "| # | speaker | voice | synth ms | audio s | RTF | F0 median | centroid |",
        "|---|---|---|---:|---:|---:|---:|---:|",
    ]
    for r in rows:
        f0 = r["f0_median"] if r["f0_median"] is not None else "—"
        lines.append(
            f"| {r['index']} | {r['speakerId']} | {r['voice']} | {r['synth_ms']} | "
            f"{r['audio_s']} | {r['rtf']} | {f0} | {r['centroid_hz']} |"
        )
    lines += ["", "## Speaker profiles", "", "| speaker | voice | F0 median | centroid | chunks |",
              "|---|---|---:|---:|---:|"]
    for key, p in profile_out.items():
        lines.append(f"| {key} | {p['voice']} | {p['f0_median_hz'] or '—'} | "
                     f"{p['centroid_hz']} | {p['chunks']} |")
    lines += ["", "## Pairwise distance", "", "| pair | F0 Δ | centroid Δ |", "|---|---:|---:|"]
    for pair, d in distances.items():
        lines.append(f"| {pair} | {d['f0_relative_delta']} | {d['centroid_relative_delta']} |")
    with open(os.path.join(args.out, "summary.md"), "w") as f:
        f.write("\n".join(lines) + "\n")
    print("\n".join(lines[:16]))
    print(f"\nwrote {args.out}/metrics.json and scene.wav")


if __name__ == "__main__":
    main()
