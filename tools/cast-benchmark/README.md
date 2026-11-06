# Story Cast benchmark harness

Measures the Story Cast pipeline the app actually runs: the deterministic parser, the exact TTS
chunks it queues, Supertonic 3 synthesis, speaker-switch cost, ASR word error rate, and an
audio-capable judge.

Everything here is host-side. It does not modify the APK and it never needs the router key in a
file — pass it as `ROUTER_API_KEY` in the environment.

## 1. Parser chunks

```bash
kotlinc app/src/app/novelspeak/SentenceSplitter.kt \
  app/src/app/novelspeak/cast/CastModels.kt \
  app/src/app/novelspeak/cast/DialogueParser.kt \
  app/src/app/novelspeak/cast/CastChunker.kt \
  tools/cast-benchmark/ParserMain.kt -include-runtime -d /tmp/parser-cli.jar

java -jar /tmp/parser-cli.jar tools/cast-parser-test/fixtures/unattributed-scene.txt \
  > /tmp/scene-chunks.json
```

The CLI uses `CastChunker`, the same unit the reader uses, so short fragments such as
`"Tomas. I told you..."` stay one cue instead of gaining a pause after `"Tomas."`.

## 2. Synthesize and measure

Requires a Python env with `numpy` and `sherpa-onnx`, plus the Supertonic 3 int8 model directory:

```bash
python3 -m venv /tmp/castbench
/tmp/castbench/bin/pip install numpy sherpa-onnx
# model: https://huggingface.co/csukuangfj2/sherpa-onnx-supertonic-3-tts-int8-2026-05-11

/tmp/castbench/bin/python tools/cast-benchmark/run_benchmark.py \
  --chunks /tmp/scene-chunks.json \
  --model-dir /tmp/models/supertonic \
  --out /tmp/cast-bench --threads 4
```

Outputs `metrics.json`, `summary.md`, `scene.wav` and one WAV per cue. The script runs:

- a warm-up excluded from steady-state latency;
- a controlled switch probe: the same sentence with repeated `sid 5` versus alternating
  `5/1/6`, so speaker-switch cost is not confounded by sentence length;
- per-cue synthesis time, audio duration, RTF, peak, RMS, F0 and spectral centroid.

## 3. ASR word error rate

```bash
ROUTER_API_KEY=... python tools/cast-benchmark/transcribe.py \
  --chunks /tmp/scene-chunks.json \
  --audio-dir /tmp/cast-bench/chunks \
  --out /tmp/cast-bench/asr.json \
  --model gemini-2.5-flash
```

The model transcribes each cue without the source text in the prompt. WER and exact-match counts
are computed afterward. Runs are resumable; `--sleep` controls pacing for the router's quota.

## 4. Audio judge

```bash
ROUTER_API_KEY=... python tools/cast-benchmark/judge_audio.py \
  --wav /tmp/cast-bench/scene_45s_48k.mp3 \
  --source tools/cast-parser-test/fixtures/unattributed-scene.txt \
  --image docs/media/ui-audit/phone-cast.png \
  --note "45s excerpt of a 98s scene; do not penalize the excerpt length" \
  --model gemini-2.5-flash-lite \
  --out /tmp/cast-bench/judge.json
```

The judge receives audio + source + cast screenshot and returns strict JSON ratings. **The router
caps request bodies at 1,000,000 bytes**, so the full 98-second WAV cannot be sent. Use a
compressed excerpt; the report records which excerpt and codec each judge heard.

## Model access notes (2026-11-06)

- `muse-spark-1.3` is **not exposed** by the router.
- `muse-spark-1.2` accepts only `text` content parts: an `input_audio` part returns HTTP 400
  (`value is not one of the allowed values ['text']`).
- Meta's own docs say Muse Spark 1.3 audio is not fully supported and recommend 1.2 for audio.
- `gemini-2.5-flash` and `gemini-2.5-flash-lite` accept `input_audio` and were used instead.
- The router's `/v1/audio/transcriptions` endpoint is not available (HTTP 403), so ASR uses the
  chat-completions audio path.

Never commit `ROUTER_API_KEY`, and never put it in the APK.
