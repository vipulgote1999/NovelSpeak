# RE: "Natural AI voices" — Kokoro + Pocket-TTS on Android

Date: 2026-10-22 · Host: Termux aarch64 · Tools: apktool 3.0.3, jadx 1.5.5, OpenJDK 21
(REA's JADX provider refuses to run on android/arm64, so analysis used local jadx + apktool.)

## 0. TL;DR

Two Android apps implement exactly the feature set in question:

| App | Version | Package | Size | License | Engines |
|---|---|---|---|---|---|
| HayaiApp/HayaiTTS | v2.5.1 (2026-06-15) | `dev.ahmedmohamed.hayaitts` | 38 MB arm64 | **GPL-3.0** | sherpa-onnx wrapper: Piper, VITS, Matcha, Kokoro, Kitten, Supertonic, ZipVoice, **Pocket** |
| siva-sub/NekoSpeak | v1.4.2 (2026-03-07) | `com.nekospeak.tts` | 88 MB arm64 | **MIT** | Direct ONNX Runtime: **Kokoro**, Kitten, Piper, **Pocket** (+ Mimi codec + GTCRN denoiser) |

Artifacts (kept in `~/re/apks/`):

- `hayaitts-arm64-v2.5.1.apk` — sha256 `43ff2cf33b4b7da64182f90f7787c9b691db0e35d5ef66393204ed93a3a71a07`
- `nekospeak-arm64-v1.4.2.apk` — sha256 `fabd52784a110e2abc751ead7b4b96249839cd01418cfacd07226ba2d861d428`

**Recommendation for our app:** use the **sherpa-onnx** route (HayaiTTS' approach) rather than
re-implementing Pocket/Kokoro by hand (NekoSpeak's approach). sherpa-onnx already ships
`OfflineTtsKokoroModelConfig`, `OfflineTtsPocketModelConfig`, `GenerationConfig` (reference audio +
speed + steps), callback-based streaming, and NNAPI/QNN providers. Copying HayaiTTS' *code* is not
allowed (GPL-3.0) and is unnecessary — it is itself a thin sherpa-onnx wrapper plus a JSON catalog.
Cloning follows from the same call: Pocket clones from audio alone, ZipVoice needs audio +
verbatim transcript (§3).

## 1. How HayaiTTS works (reference architecture)

Structure:

- `TextToSpeechService` implementation `dev.ahmedmohamed.hayaitts.tts.HayaiTtsService` — registers
  system-wide voices, so any app (including a browser/WebView) can select them.
- UI: `MainActivity`, `ManageVoices`, `SpeakerPickerActivity`, `SettingsActivity`,
  `SpeakSelectionActivity` (read selected text), `HelpActivity`.
- Permissions: `INTERNET`, `RECORD_AUDIO` (voice cloning), `FOREGROUND_SERVICE_DATA_SYNC`,
  `POST_NOTIFICATIONS`, `WAKE_LOCK`, `REQUEST_INSTALL_PACKAGES` (self-update from GitHub releases).
- Native: `libsherpa-onnx-jni.so` + `libsherpa-onnx-c-api.so` + `libsherpa-onnx-cxx-api.so` +
  `libonnxruntime.so` (25.8 MB), arm64 split only in the analyzed APK.

Voice catalog (`assets/catalog/v1/models.json`, 1.5 MB, schema `version:1`):

- 630 entries with fields: `id`, `family`, `languages`, `approxSizeMb`, `license`, `sha256`,
  `bundleUrl`, `sourceUrl`, `demoUrl`, `sampleAudioUrl`, `speakerSamples[]`, `recommendedUseCases`.
- Family counts: `piper 536`, `vits 68`, `kitten 7`, `kokoro 6`, `matcha 5`, `zipvoice 4`,
  `pocket 2`, `supertonic 2`.
- Bundles are downloaded as `tar.bz2` from `github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models`
  and extracted into the voice's directory; the decoder resolves known per-family filenames
  (e.g. `model.onnx`, `kokoro-*.onnx`, `encoder.int8.onnx`, `decoder.int8.onnx`,
  `vocos-24khz.onnx`, `date.fst`/`number.fst`/`phone.fst`).
- Strings expose the families as “Kokoro” and “Pocket”, and the help text explicitly positions
  “Supertonic / ZipVoice / Pocket” as the *naturalness* tier, with a “Recommended for your device”
  pill driven by a hardware tier.

Engine layer (sherpa-onnx Kotlin bindings decompiled in `com.k2fsa.sherpa.onnx`):

- `OfflineTtsConfig` → `OfflineTtsModelConfig{vits, matcha, kokoro, zipvoice, kitten, pocket,
  supertonic, numThreads, debug, provider}`; provider strings seen: `cpu`, `qnn`; NNAPI toggle in
  settings.
- Kokoro config fields: `model`, `voices`, `tokens`, `dataDir`, `lexicon`, `lang`, `dictDir`,
  `lengthScale`.
- Pocket config fields: `lmFlow`, `lmMain`, `encoder`, `decoder`, `textConditioner`, `vocabJson`,
  `tokenScoresJson`, `voiceEmbeddingCacheCapacity`.
- Generation is unified: `generate(text, sid, speed)` / `generateWithCallback(...)` /
  `generateWithConfig(GenerationConfig)` where `GenerationConfig = {speed, sid, numSteps,
  silenceScale, referenceAudio, referenceSampleRate, referenceText, extra}` — i.e. **cloning is
  “generate with reference audio”**, and pocket/zipvoice use it natively.
- `HayaiTtsService.streamPcm()` converts float PCM → 16-bit little-endian chunks bounded by
  `SynthesisCallback.getMaxBufferSize()`, checking a `stopRequested` flag (barge-in/stop).
- Engines are cached in a small LRU (capacity 2) keyed by voice, so switching between two voices
  does not reload 100–300 MB models repeatedly.

## 2. How NekoSpeak works (the “do it yourself” architecture)

- `TextToSpeechService` (`NekoTtsService`) plus the AOSP voice-data contract:
  `CheckVoiceData`, `GetSampleText`, `InstallVoiceData`.
- Native: `libonnxruntime.so` (16 MB) + `libttsespeak.so` (0.8 MB espeak-ng JNI). Assets:
  `espeak-ng-data/**` (full G2P dictionaries), `us_gold.json` / `us_silver.json` /
  `gb_gold.json` / `gb_silver.json` (IPA lexicon overlays), bundled Piper `en_US-amy-low.onnx`,
  and `pocket/models/gtcrn_simple.onnx` (speech denoiser).
- `EngineFactory`: `pocket_v1` → `PocketTtsEngine`; `kitten_nano` / `piper*` → `PiperEngine`;
  everything else → `KokoroEngine`.
- Model manager (`data/ModelRepository`): per-model file list with URL + size label, download to
  app files dir, progress state map, delete/resume. Sources:
  - Kokoro: `kokoro-v1.0.int8.onnx` (103 MB) + `voices-v1.0.bin` — rehosted on the app's own
    GitHub release `v1.0.0`.
  - Pocket: `mimi_encoder.onnx` (73 MB), `text_conditioner.onnx` (16 MB),
    `flow_lm_main_int8.onnx` (76 MB), `flow_lm_flow_int8.onnx` (10 MB),
    `mimi_decoder_int8.onnx` (23 MB), `tokenizer.model` (59 KB) from the HF Space
    `KevinAHM/pocket-tts-web` (`/resolve/main/onnx/...`), plus 8 reference WAVs from
    `huggingface.co/kyutai/tts-voices` (`alba-mackenna/casual.wav`, `voice-donations/*`,
    `vctk/p244_023.wav`, …).
  - Celebrity cloning voices (`data/PocketVoiceRepository`): 13 WAVs from
    `huggingface.co/datasets/sdialog/voices-celebrities`.

### Kokoro (NekoSpeak `engine/KokoroEngine`)

| Fact | Value |
|---|---|
| Runtime | onnxruntime-android, single `OrtSession` |
| Model | `kokoro-v1.0.int8.onnx` |
| Voices | `voices-v1.0.bin` → one style vector per voice |
| Inputs | `input_ids` int64 `[1,T]`, `style` float `[1,256]`, `speed` (int tensor if `useIntSpeed`, else float) |
| Constants | `MAX_TOKENS = 150`, `STYLE_DIM = 256`, `SAMPLE_RATE = 24000` |
| Text pipeline | `Phonemizer` = espeak-ng (JNI) + misaki G2P (`engine/misaki`) + JSON lexicons + `Num2Words`; token list → ids |
| Chunking | split on `.!?;\n`, then comma / 60-char split when a sentence is long; 150-token batches |
| Streaming | first batch synchronous, then batches streamed to callback; `stopFlag` checked between batches |
| Kitten reuse | same engine class, `voices.npz` instead of `voices-v1.0.bin`, speed pinned to 1.0 |

### Pocket-TTS (NekoSpeak `engine/pocket/PocketTtsEngine`, ~1.9k lines)

| Fact | Value |
|---|---|
| Runtime | 5 `OrtSession`s: `mimi_encoder`, `text_conditioner`, `flow_lm_main_int8`, `flow_lm_flow_int8`, `mimi_decoder_int8` + `tokenizer.model` (SentencePiece) |
| Constants | `SAMPLE_RATE = 24000`, `LATENT_DIM = 32`, `EMBED_DIM = 1024`, `ODE_STEPS = 20`, 80 ms audio frames |
| Speaker | reference WAV → GTCRN denoise → `mimi_encoder` → latent frames (`PocketVoiceState`, persisted to file, cached per voice) |
| Generation | text → SentencePiece tokens → `text_conditioner` (`text_embeddings` `[1,T,1024]`) → `flow_lm_main` (sequence `[1,T,32]` + text embeddings) → `flow_lm_flow` Euler/ODE steps → latents `[1,F,32]` → `mimi_decoder` → PCM |
| Tuning | defaults: LSD steps `10`, temperature `0.7`, frames-after-EOS `3`, `ODE steps 20`; peak normalize before playback |
| Voices | 8 bundled Kyutai voices + 13 downloadable celebrity WAVs + user-recorded cloning (`RECORD_AUDIO`) |
| Denoiser | `assets/pocket/models/gtcrn_simple.onnx` (535 KB) is bundled and the denoiser is initialized, but no call site feeds reference audio through it in this build — treat it as optional preprocessing, not a dependency |

## 3. Voice cloning (deep dive)

### 3.1 Two different cloning math models

| | Pocket-TTS cloning | ZipVoice cloning |
|---|---|---|
| Reference input | audio only | audio **+ exact transcript** |
| sherpa switch | `OfflineTtsPocketModelConfig` | `OfflineTtsZipVoiceModelConfig` |
| Conditioning | Mimi-encoder latents `[frames,1024]` injected into the flow LM as the speaker state | speaker embedding from encoder + text conditioning; decoder + vocoder synthesize |
| Mobile size | 98 MB int8 / 168 MB fp32 bundle | 109 MB int8 distill / 478–635 MB fp32 |
| Languages | English (model-dependent) | zh + en (Emilia-trained) |
| Best for | “clone anyone from a clip”, no typing | highest fidelity **if** transcript is verbatim |

Upstream confirms the asymmetry: PocketTTS needs `--reference-audio` only, ZipVoice needs
`--reference-audio` **and** `--reference-text` (exact transcript) —
`k2-fsa.github.io/sherpa/onnx/tts/pocket.html`, `.../zipvoice.html`.

### 3.2 How NekoSpeak clones (Pocket, no transcript)

`PocketTtsEngine.cloneVoice(audioPath, voiceName)` → `encodeVoiceFromWav()`:

1. Parse WAV manually (RIFF/fmt/data): accepts PCM 8/16/24/32-bit **and** float32, mixes channels
   to mono, linear-resamples to **24 kHz**, peak-normalizes.
2. 80 Hz one-pole high-pass → trim leading/trailing silence (480-sample windows, 0.02 threshold)
   → peak normalize → **truncate to 30 s (720,000 samples)**.
3. `mimi_encoder` with input `audio` `[1,1,N]` → output `[1,F,1024]`; `F` = latent frames.
4. Persist two artifacts and register the voice:
   - `files/pocket/cloned_voices/<id>.bin` — little-endian: `MAGIC 1398164304`, `VERSION 1`,
     `u16 pad`, id, displayName, `numFrames`, `isBundled`, `createdAt`, then `float32[]` latents.
   - `files/pocket/voice_cache/<id>.emb` — `int32 frames` + `float32[frames*1024]` (fast reload).
5. Voice id `cloned_<timestamp>`; `TtsEngine` exposes `cloneVoice(...)` with a no-op default, so
   Kokoro/Piper simply do not support it — a clean seam to copy in our app.

Notes: the analysis APK only ever calls `initialize()`/`close()` on the GTCRN denoiser, so its
pipeline effectively relies on high-pass + trim + normalize; a 30 s cap ≈ 375 latent frames
(Mimi runs at 12.5 Hz) ≈ 1.5 MB embedding (do not store embeddings in SQLite blobs). Also: Neko's
importer accepts **WAV only** and resamples linearly (aliasing on downsample) — our app should
decode m4a/ogg/opus via `MediaExtractor`/`MediaCodec` into 24 kHz float mono with a proper
resampler, then reuse the same cleanup chain.

### 3.3 How HayaiTTS clones (sherpa, both families)

- UI `VoiceCloningViewModel` + cloning strings: *“Record or upload a short reference clip, type
  what it says, then type the new text.”* State = `referenceClip(samples: FloatArray,
  sampleRate, durationMs)`, `referenceText`, `targetText`.
- Gate: `installedVoice.family.supportsCloning()` is true only for `ZIPVOICE` and `POCKET`
  (enum `b9.l.a()`); otherwise it throws “Family … does not support voice cloning” before touching
  the native layer.
- Generation: one unified call with the reference in the config —
  `GenerationConfig(speed=1.0, silenceScale=1.0, sid=0, referenceAudio, referenceSampleRate,
  referenceText, numSteps=8, extra)` → `generateWithConfig(text, cfg)`.
- Pocket ignores the transcript; ZipVoice requires it. `numSteps` 8 is the app’s mobile default
  (Pocket’s engine-side defaults remain ODE 20 / LSD 10 / temp 0.7 — see §2).
- `OfflineTtsPocketModelConfig.voiceEmbeddingCacheCapacity` caches encoded speaker embeddings, so
  reusing a clip across chapters does not re-run Mimi encoding every sentence.
- Because sherpa takes `referenceAudio: FloatArray` + `referenceSampleRate`, the recorder/import
  path can hand over any sample rate (44.1/48 kHz phone captures included); resampling to the
  model’s rate happens inside the native layer, unlike NekoSpeak which resamples in Kotlin.

### 3.4 Storage layout to use in our app

```
files/voices/<voiceId>/
  reference.wav      # original upload/recording (re-derive + export)
  embedding.emb      # int32 frames + float32[frames*embedDim]  (Pocket)
  meta.json          # id,name,engine,modelId,lang,sampleRate,durationMs,
                     # sha256(reference),createdAt,source=record|import,consent=true
```

- Dedupe by `sha256(reference.wav)`; re-clone only when the file or model changes.
- Version the format (`format:1`) and keep `meta.json` the single source of truth; embeddings are a
  cache, always re-derivable from the WAV.
- Keep the clip under 30 s (Pocket hard cap) and ≥3 s (below that the speaker state is too sparse).

### 3.5 Capture/preprocessing recipe (what actually sounds good)

| Step | Value | Why |
|---|---|---|
| Clip length | 10–20 s (max 30 s) | enough frames for a stable speaker state; longer just costs memory |
| Format | 24 kHz+ mono, quiet room, one speaker | Mimi/token pipeline is 24 kHz; music/noise poisons the embedding |
| Convert | mono mix → 24 kHz → peak normalize | matches NekoSpeak’s chain; also what sherpa does internally |
| Cleanup | 80 Hz high-pass, trim silence | removes rumble and dead air that skew the latent average |
| Transcript | required **only** for ZipVoice, verbatim, same language, punctuation included | mismatch degrades alignment badly |
| Preview | synthesize one fixed sentence before saving | catches bad clips immediately |
| Consent | explicit checkbox + on-device only + “cloned” badge | legal/ethical baseline |

### 3.6 Integration sketch (sherpa path)

```kotlin
// one cloneable seam; Kokoro/Piper stay non-cloneable
interface CloneableVoice {
    suspend fun clone(ref: ReferenceAudio, transcript: String?, name: String): VoiceHandle
    suspend fun preview(handle: VoiceHandle, text: String): FloatArray
}

// generation uses the verified sherpa types
val cfg = GenerationConfig(
    speed = speed, silenceScale = 1.0f, sid = 0,
    referenceAudio = ref.samples, referenceSampleRate = ref.sampleRate,
    referenceText = if (family == ZipVoice) transcript!! else "",
    numSteps = 8, extra = emptyMap()
)
engine.generateWithConfigAndCallback(text, cfg) { chunk -> player.write(chunk) }
```

Then per-voice: `family == POCKET` → cache the embedding and hide the transcript field;
`family == ZIPVOICE` → require transcript, show zh/en note; engine LRU 2 keeps the active clone
warm between chapters.

### 3.7 Rules before shipping

1. Ship stock voices + **user-recorded/imported** clones; no celebrity packs (NekoSpeak ships 13
   celebrity WAVs — do not copy that dataset or those ids).
2. Record consent for every cloned voice, store it in `meta.json`, expose delete that wipes
   wav + embedding + DB row.
3. Label cloned output in the UI and in exported audio metadata.
4. Deny cloning when RAM/ABI can’t hold the model; Pocket int8 needs ~200 MB of weights.
5. Test matrix: 5/10/20/30 s clips × en/zh × clean/noisy × transcript mismatch (ZipVoice) ×
   2 GB RAM device.

## 4. Kokoro and Pocket compared for our use case

| | Kokoro | Pocket-TTS |
|---|---|---|
| Params / size | 82M; int8 ONNX ≈ 103 MB + ~0.5 MB styles | ~200 MB total int8 (5 files) + tokenizer |
| Voice model | fixed style vectors (per-voice `.bin`), 11–206 speakers per model | zero-shot from a 10–60 s reference WAV; cloning for free |
| Text frontend | phonemizer required (espeak-ng + lexicon); English/multi-lang | SentencePiece tokenizer (simpler), English |
| Speed control | `speed` input tensor, true time-stretch | sampling speed only |
| Latency | low (~150-token chunks, ~24 kHz) | higher (LM + ODE steps); needs chunked streaming to feel responsive |
| Licensing | Apache-2.0 (model), MIT/Apache tooling | **MIT** (kyutai-labs/pocket-tts) |
| Failure modes | G2P gaps, long-sentence drift, register/thread tuning | OOM on low-RAM devices, slow first token, EOS/loop handling |

## 5. Model sources (canonical, not the apps' rehosts)

- Kokoro: `hexgrad/Kokoro-82M` (HF), ONNX + `voices-v1.0.bin` via `thewh1teagle/kokoro-onnx`;
  sherpa bundles: `k2-fsa/sherpa-onnx` → `tts-models` release (`kokoro-en-v0_19`,
  `kokoro-multi-lang-v1_0/v1_1`, int8 variants).
- Pocket: `kyutai-labs/pocket-tts` (MIT) → ONNX export `KevinAHM/pocket-tts-onnx-export`
  (HF space `KevinAHM/pocket-tts-web`), sherpa port `sherpa-onnx-pocket-tts-2026-01-26`
  (+ int8), reference voices `kyutai/tts-voices`.
- Tooling: `k2-fsa/sherpa-onnx` (Apache-2.0), `onnxruntime-android` (MIT).

## 6. Compliance notes

- **Do not copy HayaiTTS source** (GPL-3.0) into a closed app. Its *architecture* (sherpa-onnx +
  JSON catalog) is fine to reimplement.
- NekoSpeak is MIT — reusable with attribution, but prefer upstream sherpa/Kyutai code.
- Kokoro model Apache-2.0; Kyutai Pocket TTS MIT; **cloned celebrity voices** are a legal/ethical
  minefield (persona rights). Ship stock voices + user-recorded cloning only.
- sherpa-onnx + onnxruntime are the only native deps needed; keep ABI splits (`arm64-v8a`,
  `armeabi-v7a`) and download models on demand (never bundle 100–300 MB in the APK).

## 7. Suggested build for `android-browser-tts-light-novel-app`

1. **Engine adapter seam** (one interface, several backends):
   `init(voiceDir)`, `speakers()`, `generate(text, sid, speed, refAudio?, onChunk)`, `stop()`,
   `release()`. Kokoro and Pocket become two implementations; Piper/Kitten as low-end fallback.
2. **Model manager**: JSON manifest (id, family, files[{url,sha256,bytes}], sampleRate, license,
   minRam) → resumable download to `filesDir/models/<family>/<id>/`, sha256 verify, tar.bz2
   extraction for sherpa bundles, per-model delete, “download on first use”, Wi-Fi-only toggle.
3. **Streaming player**: decode float PCM → 16-bit LE into `AudioTrack` (24 kHz mono), sentence
   look-ahead prefetch from the chapter text, cancel on scroll/tab change (mirror HayaiTTS'
   callback loop and NekoSpeak's `stopFlag`).
4. **Light-novel specifics**: split chapter into sentences client-side, keep a queue, pre-synthesize
   the next 1–2 sentences, persist (chapter, sentence, voice, speed) position, handle WebView
   selection → speak-selection path.
5. **Voices UX**: “Natural AI voices” section listing engine → voice; show size + quality tier;
   Kokoro = many fixed voices, Pocket = clone from mic/import; expose speed, and for Pocket
   temperature/LSD steps in an advanced drawer.
6. **Cloning UX**: one `CloneableVoice` seam; record/import → 24 kHz mono → high-pass/trim/normalize
   → ≤30 s → preview sentence → save with consent flag; transcript field only for ZipVoice;
   dedupe by reference sha256; delete wipes wav + embedding + row (see §3).
7. **Hardware gates**: RAM/ABI check before offering Pocket; `numThreads` = min(4, cores);
   optional NNAPI toggle; LRU engine cache (2) so switching voices does not thrash memory.
8. **Licensing screen** listing Kokoro (Apache-2.0), Pocket (MIT), sherpa-onnx, onnxruntime, and
   voice-pack provenance.

Suggested first milestone: arm64-only debug build with sherpa-onnx + Kokoro int8
(`kokoro-en-v0_19` or multi-lang int8) and Pocket int8, one hard-coded chapter text, streaming to
`AudioTrack`, measured RTF (real-time factor) on this device. Everything else (downloads, UI,
voice cloning) layers on top.

## 8. Open items

- Confirm the *actual* target app if it is a third one (this analysis covers HayaiTTS as the
  reference plus NekoSpeak, the only other Android app with both Kokoro and Pocket).
- On-device benchmark numbers (RTF, peak RSS) still need a real synthesis run.
- Decide whether the browser part needs system-TTS integration (`TextToSpeechService`, like
  HayaiTTS) or in-app playback only (simpler, no system settings dependency).

## 9. Reproduction commands

```bash
pkg install -y openjdk-21 jadx
apktool d -s -f -o hayaitts_apk hayaitts-arm64-v2.5.1.apk      # manifest/resources/smali
jadx --no-res -j 2 -d hayai_src hayaitts-arm64-v2.5.1.apk
jadx --no-res -j 2 -d neko_src  nekospeak-arm64-v1.4.2.apk
grep -rE 'kokoro|pocket|generateWithConfig|OfflineTts' hayai_src/sources/com/k2fsa/sherpa/onnx
```
