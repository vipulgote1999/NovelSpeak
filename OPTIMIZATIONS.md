# Verified optimizations reused from the reference apps

Everything below was read out of the shipped APKs (`~/re/apks/*_src`, apktool 3.0.3 + jadx 1.5.5)
and re-implemented as values/structure in NovelSpeak. No GPL code was copied: HayaiTTS is GPL-3.0,
so only its *architecture and constants* are reused, on top of the same Apache-2.0 library
(sherpa-onnx) it uses.

| # | Optimization | Where it came from (verified) | What NovelSpeak does |
|---|---|---|---|
| 1 | Sentence chunking for low first-audio latency | NekoSpeak `KokoroEngine`: split regex `(?<=[.!?])\s+\|\n+\|(?<=[;])\s+`, long first sentence re-split on commas then hard-wrapped, `MAX_TOKENS = 150` batches | `SentenceSplitter.kt`: same enders, abbreviation/decimal guards, terminal punctuation → comma → next-space boundary (never mid-word), 180-char default (setting, V4 migration) |
| 2 | Stream while generating, never wait for full clip | NekoSpeak per-batch callbacks; HayaiTTS 2.5.1 actually calls blocking `generate()` and then `streamPcm` only re-chunks the completed float[] (no progressive synthesis; 2,000-char cap) | `SherpaEngine` writes every callback chunk straight into `PcmPlayer` and returns `1=continue / 0=stop` per sherpa-onnx `offline-tts.h`; generation of sentence *n+1* runs while *n* plays (boundary + monitor threads) |
| 3 | Low-latency PCM playback | HayaiTTS preview player (decompiled `u8.k` / `androidx.room.w.u()`): `USAGE_MEDIA`+`CONTENT_TYPE_MUSIC`, mono 16-bit, `bufferSize = max(pcmBytes, 4096)`, `MODE_STREAM`, `PERFORMANCE_MODE_LOW_LATENCY`, max volume | `PcmPlayer.kt` uses exactly those values, plus drain tracking so highlighting follows *heard* audio |
| 4 | Kokoro model choice | NekoSpeak downloads `kokoro-v1.0.int8.onnx` + `voices-v1.0.bin`; HayaiTTS catalog lists `kokoro-int8-en-v0_19` (103 MB, tier "high", narration/audiobook) | Model manifest pins `kokoro-int8-en-v0_19` (same int8 bundle: `model.int8.onnx`, `voices.bin`, `tokens.txt`, `espeak-ng-data/`) |
| 5 | Pocket-TTS runtime values | NekoSpeak `PocketTtsEngine`: 24 kHz, `LATENT_DIM=32`, `EMBED_DIM=1024`, `ODE_STEPS=20`, decode chunk 15 frames (80 ms each), LSD steps 10, temperature 0.7, frames-after-EOS 3 | Pocket handled inside sherpa-onnx (same 5 ONNX graph + SentencePiece pipeline); we pass `numSteps = 8` from HayaiTTS and `voiceEmbeddingCacheCapacity = 16` |
| 6 | Cloning generation config | HayaiTTS `i9/l.java`: `GenerationConfig(speed=1.0, silenceScale=1.0, sid=0, referenceAudio, referenceSampleRate, referenceText, numSteps=8)`; gate `family ∈ {ZIPVOICE, POCKET}` | `SherpaEngine.generationLoop()` builds the identical config for the Pocket path; Pocket needs audio only, transcript reserved for ZipVoice |
| 7 | Reference-clip preprocessing | NekoSpeak `loadAudioFile` + `encodeVoiceFromWav`: PCM 8/16/24/32-bit & float32, mono mix, resample 24 kHz, 80 Hz one-pole high-pass, trim silence (480-sample windows, 0.02), peak normalize, **30 s / 720 000 sample cap** | `WavReader.kt` implements the same chain with the same constants; it feeds both the bundled Pocket narrator clip and future user clones |
| 8 | Voice-embedding caching | HayaiTTS `OfflineTtsPocketModelConfig.voiceEmbeddingCacheCapacity`; NekoSpeak persists latents to `.bin` + `.emb` and caches in memory | sherpa cache capacity 16 for Pocket; Kokoro voices are loaded once per engine instance |
| 9 | Engine reuse instead of re-init | HayaiTTS keeps an LRU of 2 `OfflineTts` instances; NekoSpeak caches the ORT sessions per engine | one live engine per selected backend, released on switch (`Reader.ensureEngine`), so switching sentences/chapters never reloads 100 MB of weights |
| 10 | Thread count / session tuning | NekoSpeak `PrefsManager.getCpuThreads()` default **6**, ORT `ALL_OPT` + memory-pattern optimization; HayaiTTS exposes "Synthesis Threads" and NNAPI ("Hardware Acceleration") | `numThreads` from settings (default 4, capped at core count), `provider = "cpu"` with optional `"nnapi"` and automatic CPU retry if the provider is rejected |
| 11 | Model delivery | HayaiTTS `VoiceDownloadWorker`: tar.bz2 from the k2-fsa `tts-models` release, SHA-256 verified, extracted to files dir; NekoSpeak: per-file download with progress state | `ModelManager.kt`: streaming download with % progress, SHA-256 verify, tar.bz2 extraction (Commons Compress), install/delete, never bundle models in the APK |
| 12 | Keep playing with the screen off | The thing Chrome fails at. HayaiTTS/NekoSpeak both live in a TTS process/foreground service, so audio survives backgrounding | `PlaybackService`: `mediaPlayback` foreground service + `PARTIAL_WAKE_LOCK` + `MediaSession` (lock-screen play/pause/next/stop) |
| 13 | Generic text extraction | NekoSpeak/HayaiTTS read text handed to them by other apps (they never solve page extraction) — that gap is ours | `assets/readability.js` (Mozilla Readability, Apache-2.0) + density fallback in `extract.js`, dotted-obfuscation normalization, visibility checks that skip `display:none` blocks, `data-ns` block tags for highlight/scroll, `rel=next` / “Next chapter” link detection |

> **2026-10-22 re-analysis correction.** HayaiTTS 2.5.1 has no streaming call site: it calls
> blocking `generate()` and `streamPcm` only chunks the finished `float[]`; NekoSpeak's 75-char
> re-split applies only to the first sentence (later batches 148/398 tokens); both silently cap
> input (2,000 chars / 500 Pocket frames). The sherpa-onnx callback contract is `1=continue,
> 0=stop`; the inverted return was the cause of the 1.2 s cutoffs fixed in `9df3ba9`. See
> `docs/reader-fixes.md` and `docs/reader-fixes-evidence/`.

## Values we deliberately did NOT copy

- **HayaiTTS source code** (GPL-3.0). We use the same public library and the same numeric
  parameters, but the Kotlin here is ours.
- **NekoSpeak's celebrity voice packs** (13 WAVs from a HF dataset) — legal/ethical problem, and
  the README says so too. NovelSpeak ships the model's own `test_wavs/bria.wav` as the Pocket
  narrator and will only add *user-recorded/imported* clones.
- **GTCRN denoiser**: bundled in NekoSpeak but the analysis shows no call site actually applies it
  (only `initialize()`/`close()`), so we did not add a dependency on it. The high-pass + trim +
  normalize chain is what their pipeline really relies on.

## Numbers at a glance

| Constant | Value | Source |
|---|---|---|
| Sample rate (Kokoro & Pocket) | 24 000 Hz | both apps |
| Kokoro style dim / max tokens | 256 / 150 | NekoSpeak |
| Pocket latent dim / embed dim / ODE steps | 32 / 1024 / 20 | NekoSpeak |
| Pocket LSD steps / temperature / frames-after-EOS | 10 / 0.7 / 3 | NekoSpeak defaults |
| Pocket decode chunk | 15 frames (80 ms each) | NekoSpeak default |
| Cloning `numSteps` | 8 | HayaiTTS |
| `silenceScale` | 1.0 (generation) / 0.2 (engine) | HayaiTTS / sherpa default |
| AudioTrack buffer | `max(pcmBytes, 4096)` | HayaiTTS |
| CPU threads | 6 (NekoSpeak) → 4 default here, user-tunable | NekoSpeak / HayaiTTS |
| Engine cache | 2 | HayaiTTS |
| Reference clip cap | 30 s (720 000 samples) | NekoSpeak |
| High-pass / silence threshold / trim window | 80 Hz / 0.02 / 480 samples | NekoSpeak |
| Model sizes | Kokoro int8 103 MB · Pocket int8 98 MB | HayaiTTS catalog (SHA-256 pinned here) |
