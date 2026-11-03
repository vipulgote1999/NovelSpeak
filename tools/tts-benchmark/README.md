# TTS benchmark

This directory contains a repeatable benchmark for NovelSpeak's TTS backends. It is dev tooling,
not part of the reader UI.

## What is measured

The benchmark runs one model per app process, so `load_ms` is a real cold load (native library +
ONNX Runtime + model graph), not a warm cache hit.

For each model and each of three fixed sentence lengths (68 / 110 / 171 chars):

| Metric | Meaning |
|---|---|
| `load_ms` | time from `OfflineTts(...)` construction to a usable engine |
| `first_chunk_ms` | time until sherpa's callback delivers the first PCM chunk |
| `gen_ms` | wall time to synthesize the whole sentence |
| `audio_ms` | duration of the generated PCM (`samples / sample_rate`) |
| `rtf` | `gen_ms / audio_ms`; lower is better, `<1.0` is faster than real time |
| `x_realtime` | inverse RTF; higher is better |
| `rss_after_load_mb` / `rss_after_gen_mb` | process RSS after load / after all measured generations |
| `peak`, `rms`, `garbage` | output sanity checks; `garbage=true` means the int8 saturation rail |
| `rows`, `summary` | per-sentence samples and median summary |

The measured time excludes AudioTrack playback. It includes text cleaning and the sherpa callback
(which writes PCM to a queue), matching the synthesis half of the reader pipeline. System TTS uses
the same `synthesizeToFile` path as `SystemTtsEngine`, so its `first_chunk_ms` is the render time
(the provider returns only after the file is complete). System texts are made unique per rep
because Google TTS caches by utterance text; reusing a sentence would report a 5–8 ms “synthesis”
that never happened.

## Run

Build a debuggable APK containing `BenchmarkActivity`, install it, then run:

```bash
# x86_64 emulator; use ABI=arm64-v8a for a phone
ABI=x86_64 DEBUGGABLE=1 ./build.sh
adb install -r build/NovelSpeak-debug.apk
tools/tts-benchmark/run.sh
```

`DEBUGGABLE=1` is required because the runner pulls JSON through `run-as` and the activity refuses
to run in a non-debuggable build. It is opt-in so a release invocation (`KEYSTORE=...`) does not
ship a debuggable manifest.

By default it benchmarks:

- Android system TTS
- Kokoro int8 (`kokoro-int8-en-v0_19`)
- Pocket TTS int8 (`sherpa-onnx-pocket-tts-int8-2026-01-26`)
- Supertonic 3 int8 (`sherpa-onnx-supertonic-3-tts-int8-2026-05-11`)
- ZipVoice distill int8 (`sherpa-onnx-zipvoice-distill-int8-zh-en-emilia`)

Options:

```bash
DEVICE=<adb-serial> THREADS=8 REPS=3 tools/tts-benchmark/run.sh
MODELS="kokoro-int8-en-v0_19 sherpa-onnx-supertonic-3-tts-int8-2026-05-11" tools/tts-benchmark/run.sh
```

The runner force-stops the app between models, pulls the JSON from the app's private files via
`run-as` (debug build), and writes `reports/<device>/summary.{csv,json,md}`. Generated WAVs are
under the app's external files directory (`Android/data/app.novelspeak/files/bench/`).

## Preparing model bundles

The app installs models through Settings; the benchmark can also be run after staging the
upstream bundles directly. For the dev emulator, models were extracted from the exact URLs and
SHA-256 values in `ModelManager.MODELS` and copied to:

```
/data/data/app.novelspeak/files/models/<model-id>/
/data/data/app.novelspeak/files/models/vocos-24khz/vocos_24khz.onnx
```

ZipVoice has no vocoder inside its bundle, so the separate `vocos-24khz` model is required. The
AAR's ZipVoice frontend (v1.13.8) also requires `espeak-ng-data` in its `dataDir`; `SherpaConfig`
passes that path. Without it sherpa calls `exit(255)` and the app dies. The benchmark selects
`test_wavs/news-female.wav` and its transcript from `test_wavs/prompt.txt` as the ZipVoice reference.

## Fairness notes

- The same three sentences and generation settings (`speed=1.0`, `numSteps=8`, `maxNumSentences=1`)
  are used for all sherpa families.
- All runs use CPU (`provider=cpu`). NNAPI is opt-in in the app and is not exercised here because
  emulator NNAPI support varies; add a provider sweep only when testing a specific phone.
- The emulator's absolute numbers are not representative of a phone. Compare within the same
  device/run, and use the same runner on a physical device for shipping decisions.
- A model process that crashes cannot write a result; the runner reports a timeout and preserves
  logcat in `reports/<device>/<model>.log`.
