# Story Cast benchmark harness

Measures the Story Cast pipeline the app actually runs: the deterministic parser, the exact TTS
chunks it queues, Supertonic 3 or Kokoro synthesis, speaker-switch cost, ASR word error rate, and an
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

Requires a Python env with `numpy` and `sherpa-onnx`, plus a model directory.

**Supertonic 3** (44.1 kHz, 10 voices):

```bash
# https://huggingface.co/csukuangfj2/sherpa-onnx-supertonic-3-tts-int8-2026-05-11
/tmp/castbench/bin/python tools/cast-benchmark/run_benchmark.py \
  --chunks /tmp/scene-chunks.json --family supertonic \
  --model-dir /tmp/models/supertonic --out /tmp/cast-bench --threads 4
```

**Kokoro** (24 kHz, 11 voices):

```bash
# https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-en-v0_19.tar.bz2
# sha256 c9f0dd393615805b0bab050c340834d5e684e732aec91c0e860cd30e982c08bd
# Extract, then provide the US lexicon (the en-v0_19 bundle omits it):
curl -L -o /tmp/models/kokoro/kokoro-int8-en-v0_19/lexicon-us-en.txt \
  https://huggingface.co/csukuangfj/kokoro-multi-lang-v1_0/resolve/main/lexicon-us-en.txt

/tmp/castbench/bin/python tools/cast-benchmark/run_benchmark.py \
  --chunks /tmp/scene-chunks.json --family kokoro \
  --model-dir /tmp/models/kokoro/kokoro-int8-en-v0_19 \
  --out /tmp/cast-bench-kokoro --threads 4
```

The harness appends `Rennick`, `Tomas` and `Mara` to `lexicon-cast-en.txt` so Kokoro does not skip
the cast names as OOV. The Android AAR uses eSpeak directly, so device output may differ.

Outputs `metrics.json`, `summary.md`, `scene.wav` and one WAV per cue. The script runs:

- a warm-up excluded from steady-state latency;
- a controlled switch probe: the same sentence with repeated `sid 5` versus alternating
  `5/1/6`, so speaker-switch cost is not confounded by sentence length;
- per-cue synthesis time, audio duration, RTF, peak, RMS, F0 and spectral centroid;
- scene duration, words/min, and the model's actual sample rate.

**Sample-rate guard.** The scene rate is read from the generated audio, not hardcoded. The script
refuses to write a scene if a chunk reports a different rate. An earlier version wrote 44.1 kHz
Supertonic audio into a 22.05 kHz scene, doubling the apparent duration (98 s instead of 51 s) and
invalidating the full-scene MP3 judges; that is why the guard exists.

## 3. Multi-round speaker-switch probe

```bash
/tmp/castbench/bin/python tools/cast-benchmark/switch_probe.py \
  --family supertonic --model-dir /tmp/models/supertonic \
  --out /tmp/switch-supertonic.json
```

Three rounds of nine same-speaker and nine alternating-voice generations with identical text.
Reports median/min/max overhead, which is more stable than the single probe embedded in a scene run.

## 4. ASR word error rate

```bash
ROUTER_API_KEY=... /tmp/castbench/bin/python tools/cast-benchmark/transcribe.py \
  --chunks /tmp/scene-chunks.json \
  --audio-dir /tmp/cast-bench/chunks \
  --out /tmp/cast-bench/asr.json \
  --model gemini-2.5-flash
```

The model transcribes each cue without the source text in the prompt. WER and exact-match counts
are computed afterward. Runs are resumable; `--sleep` controls pacing for the router's quota.
Per-cue WAVs carry the model's correct sample rate, so ASR is not affected by the historical scene
labeling bug.

## 5. Audio judge

```bash
ROUTER_API_KEY=... /tmp/castbench/bin/python tools/cast-benchmark/judge_audio.py \
  --wav docs/media/benchmarks/story-cast-scene-45s.mp3 \
  --source tools/cast-parser-test/fixtures/unattributed-scene.txt \
  --image docs/media/ui-audit/phone-cast.png \
  --note "45s excerpt of a 51s scene; do not penalize the excerpt length" \
  --model gemini-2.5-flash-lite \
  --out /tmp/cast-bench/judge.json
```

The judge receives audio + source + cast screenshot and returns strict JSON ratings. **The router
caps request bodies at 1,000,000 bytes**, so a 51-second WAV cannot be sent. Use a compressed
excerpt; the report records which excerpt and codec each judge heard.

The valid judge data in this repository is the **20-second PCM montage built from the per-cue
WAVs**. The earlier 40 s/45 s MP3 judge runs were generated from the mislabeled scene and played at
half speed; they are retained as `judge-INVALID-halfspeed-*` and must not be cited as quality
evidence. The corrected 45-second excerpt has not been judged yet (router quota exhausted).

## Model access notes (2026-11-06)

- `muse-spark-1.3` is **not exposed** by the router.
- `muse-spark-1.2` accepts only `text` content parts: an `input_audio` part returns HTTP 400
  (`value is not one of the allowed values ['text']`).
- Meta's own docs say Muse Spark 1.3 audio is not fully supported and recommend 1.2 for audio.
- `gemini-2.5-flash` and `gemini-2.5-flash-lite` accept `input_audio` and were used instead.
- The router's `/v1/audio/transcriptions` endpoint is not available (HTTP 403), so ASR uses the
  chat-completions audio path.

Never commit `ROUTER_API_KEY`, and never put it in the APK.
