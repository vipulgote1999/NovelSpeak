# TTS engine benchmark — 2026-11-03

Scope: all five selectable speech backends in NovelSpeak — Android system TTS, Kokoro int8,
Pocket TTS int8, Supertonic 3 int8 and ZipVoice distill int8. The emulator matrix is complete.
The iQOO I2214 physical-device run is complete for all five at 8 threads, plus the app-default
4-thread setting for system/Kokoro/Pocket/Supertonic; ZipVoice at 4 threads was not completed
because the USB connection dropped before that run.

Raw data: [`tools/tts-benchmark/reports/emulator-5554`](../../tools/tts-benchmark/reports/emulator-5554)
and [`tools/tts-benchmark/reports/10BD8D0276000E7`](../../tools/tts-benchmark/reports/10BD8D0276000E7).
Runner and method: [`tools/tts-benchmark/README.md`](../../tools/tts-benchmark/README.md).

## Environment

| | |
|---|---|
| Device | Google `sdk_gphone64_x86_64` |
| Android | 15 (API 35), x86_64 |
| CPU | 4 vCPU emulator on a 16-core host |
| AVD RAM | 2048 MB |
| Display / audio | headless, `-no-audio` (playback is not part of the measured path) |
| Model location | `/data/data/app.novelspeak/files/models/` |
| Bundle hashes | `reports/emulator-5554/models/upstream.sha256` |

All neural runs used CPU, 4 threads, `speed=1.0`, `numSteps=8`, `maxNumSentences=1`, and one
model per fresh app process. A warmup generation was discarded. The measured corpus is three
fixed sentences of 60, 106 and 162 characters; two reps each. `load_ms` therefore includes the
cold ONNX Runtime + model load. Synthesis time excludes AudioTrack playback.

System TTS used the app's actual `synthesizeToFile` path, with unique text per rep because Google
TTS caches synthesized utterances by text (a cached call returns in 5–8 ms and is not a
measurement).

## Emulator overall results

Medians across the three sentence lengths; `RTF = synthesis time / audio time`, lower is better.

| Engine | Cold load | Median synth | First audio | Audio length | RTF | Real-time speed | RSS after load |
|---|---:|---:|---:|---:|---:|---:|---:|
| **Supertonic 3** | 799 ms | 2165 ms | 2164 ms | 7060 ms | **0.307** | **3.26×** | 336 MB |
| Pocket TTS | 788 ms | 3060 ms | 2758 ms | 5600 ms | 0.561 | 1.78× | **523 MB** |
| Kokoro | 1155 ms | 5248 ms | 5248 ms | 6675 ms | 0.766 | 1.31× | 372 MB |
| ZipVoice | 1643 ms | 28 342 ms | 28 340 ms | 15 424 ms | 1.909 | 0.53× | 448 MB |
| Android system TTS | 463 ms | 141 ms | 141 ms | 6404 ms | **0.022** | **44.5×** | 151 MB |

System TTS is not directly comparable: it is a platform provider, its voice/model is outside the
app, and the emulator's local Google TTS is unusually fast. It is included as the baseline the
neural engines are trying to beat.

### iQOO I2214 (Android 15, 8 cores, 11.7 GB)

The physical-device matrix was run on the iQOO, with the app-default **4 threads** where possible.
The first pass used 8 threads; it ran hot and was slower, so those numbers are kept only to show
the setting is counterproductive on this phone.

| Engine | Threads | Cold load | Median synth | First audio | Audio length | RTF | Real-time speed | RSS after load |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Pocket TTS | 4 | 1534 ms | 3312 ms | 2808 ms | 4960 ms | **0.665** | **1.51×** | 573 MB |
| Supertonic 3 | 4 | 4838 ms | 5112 ms | 5109 ms | 7060 ms | 0.784 | 1.28× | 362 MB |
| Kokoro | 4 | 2417 ms | 7833 ms | 7833 ms | 6700 ms | 1.169 | 0.86× | 421 MB |
| ZipVoice | 8 | 4723 ms | 48 384 ms | 48 383 ms | 15 424 ms | 3.368 | 0.30× | 494 MB |
| Android system TTS | — | 275 ms | 281 ms | 281 ms | 6406 ms | **0.046** | **21.9×** | 184 MB |

Notes:

- The iQOO was USB-powered and warm. During the 8-thread pass the thermal service reported
  **status 2 (moderate)**, CPU peak **90.5 °C**, skin **44.9 °C**. The 4-thread runs were made
  after cooldowns, mostly at status 1.
- The 8-thread pass was substantially slower: Supertonic 12.3 s vs 5.1 s and Kokoro 12.3 s vs
  7.8 s at 4 threads. For the record, its median RTFs were Kokoro 1.872, Pocket 3.919,
  Supertonic 1.670, ZipVoice 3.368 and system 0.046. Keep the app default of 4 on this phone; do
  not set 8 just because the CPU has 8 cores.
- Pocket is the fastest neural engine on this particular phone (1.51× real time), while
  Supertonic is fastest on the emulator (3.26×). This is a real architecture/scheduling
  difference, not a typo: the phone's ARM int8 kernels and big.LITTLE scheduler behave
  differently from the x86 host.
- ZipVoice at 4 threads is missing because the USB connection dropped immediately after the
  8-thread pass. Treat the 8-thread ZipVoice row as the only phone measurement for that model.
- Supertonic's 4-thread row used one rep (3 sentences); the other 4-thread rows used two reps
  (6 sentences). The raw JSON records `reps` per engine.
- Device RSS is higher than the emulator for Pocket (573 MB vs 523 MB), still within the phone's
  11.7 GB but relevant to low-RAM devices.

The same three sentences were used. ZipVoice used the bundled `news-female.wav` reference and its
`prompt.txt` transcript. The installed benchmark build is release-signed with the existing
NovelSpeak release key, so it updated the iQOO in place without erasing app data. It is also
`debuggable=true` so the runner can retrieve JSON via `run-as`.

## Per-sentence detail

First measured rep (`rep 0`) after the discarded warmup; this is what the first user sentence
actually experiences. The second rep is kept in the raw JSON. System rep 1 uses text prefixed with
`Once more, ` to avoid the provider cache.

| Engine | Sentence | Chars | Synth ms | First audio ms | Audio ms | RTF | Peak | RMS |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| System | short | 60 | 104 | 104 | 3198 | 0.033 | 0.631 | 0.1232 |
| System | medium | 106 | 138 | 138 | 5963 | 0.023 | 0.551 | 0.1185 |
| System | long | 162 | 187 | 187 | 8524 | 0.022 | 0.710 | 0.1154 |
| Kokoro | short | 60 | 3442 | 3442 | 4425 | 0.778 | 0.298 | 0.0513 |
| Kokoro | medium | 106 | 5372 | 5372 | 6675 | 0.805 | 0.347 | 0.0534 |
| Kokoro | long | 162 | 8019 | 8018 | 10 500 | 0.764 | 0.313 | 0.0525 |
| Pocket | short | 60 | 1623 | 1498 | 2880 | 0.564 | 0.874 | 0.1411 |
| Pocket | medium | 106 | 2950 | 2664 | 5440 | 0.542 | 0.753 | 0.1260 |
| Pocket | long | 162 | 4554 | 4086 | 7840 | 0.581 | 0.743 | 0.1234 |
| Supertonic | short | 60 | 1432 | 1432 | 4157 | 0.344 | 0.285 | 0.0515 |
| Supertonic | medium | 106 | 2199 | 2198 | 7060 | 0.311 | 0.357 | 0.0569 |
| Supertonic | long | 162 | 3139 | 3137 | 10 657 | 0.295 | 0.385 | 0.0533 |
| ZipVoice | short | 60 | 16 905 | 16 903 | 8181 | 2.066 | 0.066 | 0.0130 |
| ZipVoice | medium | 106 | 27 787 | 27 786 | 15 424 | 1.802 | 0.073 | 0.0133 |
| ZipVoice | long | 162 | 45 954 | 45 940 | 22 794 | 2.016 | 0.095 | 0.0137 |

`*` ZipVoice has large run-to-run variance: individual RTFs ranged **1.26–7.68** and one short
sentence in rep 1 took 62.8 s. The median in the overall table includes that outlier. Treat
ZipVoice's speed as “slower than real time and highly variable on this 2 GB emulator”, not as a
precise number.

No model produced rail-pinned/garbage output (`garbage=false` everywhere). WAVs were checked with
`ffprobe` (durations match the reported sample counts) and peak/RMS is non-zero for all models.

## What the numbers mean

### Supertonic 3 is the speed/memory winner

It is ~2.4× faster than Kokoro and ~1.8× faster than Pocket on the same 4 threads, while using
the least memory of the neural engines after load (336 MB). On a 4-core emulator it still stays
ahead of real time (3.26×). First audio is 1.4–3.2 s for these sentences, which is the best neural
result. It is the natural default for a low-latency reader.

### Pocket is the clone-quality option, at a cost

It is 1.78× real time and the only neural engine that demonstrably delivered multiple PCM chunks
during generation (TTFC ~1.5–4.1 s vs 2.9–8.0 s total). It has the highest loaded RSS (523 MB),
which is significant on a 2 GB device. Its raw peaks are high (0.78–0.90), but the existing
`PcmPlayer` limiter handles that; no rail-pinned chunks were observed. Keep it for cloning, not as
the default on memory-constrained phones.

### Kokoro is the slowest fixed-voice neural engine here

At 1.31× real time it is usable, but first audio waits 3.4–8.0 s per sentence. Its first callback
arrived at the end of synthesis (TTFC ≈ total), so the “streaming” path did not reduce first-audio
latency for this model on the tested AAR. Its 11 voices and consistent output remain its reason to
exist; on this emulator it is not the speed choice.

### ZipVoice is not usable as a real-time reader yet

Three independent problems surfaced:

1. **It crashed the app before the benchmark could run.** The AAR's ZipVoice frontend (v1.13.8)
   requires `dataDir`; `SherpaConfig` passed `""`, and sherpa called `exit(255)` with
   `Please provide data dir for this model`. The process exit is recorded in
   `dumpsys activity exit-info` as `reason=EXIT_SELF status=255`. I fixed this by passing the
   bundle's `espeak-ng-data` directory. This is a production bug, not just a benchmark issue.
2. **It is slower than real time** even after that fix: ~0.53× real time, and the first PCM chunk
   arrives only after the whole sentence (28 s median). A reader would sit silent for tens of
   seconds on the first sentence.
3. **Its output is very quiet** (peak 0.06–0.09, RMS ~0.013 vs ~0.05 for Kokoro/Supertonic).
   The player gain will lift it, but this needs a listening/quality pass, not just a speed pass.

The app also has no transcript editor for ZipVoice (`SettingsActivity` only shows the voice
label), while `zipVoiceTranscript` defaults to empty. The benchmark had to take the reference
transcript from `test_wavs/prompt.txt`. Without a transcript, ZipVoice's cloning quality is not
meaningful. The bundle's `lexicon.txt` is Chinese-only; English input relies on eSpeak data, and
the upstream example uses a separate English lexicon. That path needs verification before shipping
ZipVoice as an English reader.

The in-app install path for the separate vocoder is also suspect: `ModelManager.install` always
extracts a `tar.bz2`, but `MODEL_VOCOS24` points at a raw `.onnx` file. The benchmark staged
`vocos_24khz.onnx` manually, so this did not block the measurement, but a user installing ZipVoice
from Settings would not get a working bundle.

### System TTS is the latency baseline, not a like-for-like model

`load=463 ms`, and a real (uncached) render is 102–195 ms for these sentences — 44× real time on
this emulator. That is a platform provider result; a phone's Google TTS voice, network state and
voice data will change it. The benchmark deliberately uses unique text per rep because Google TTS
returns cached utterances in 5–8 ms, which would otherwise make the table look 20× faster than a
cold render.

## Recommendations

1. **Pick the default per device class.** Supertonic wins on the x86 emulator (3.26× vs Pocket
   1.78×), but Pocket wins on the iQOO (1.51× vs Supertonic 1.28×). Do not ship a single ranking
   derived from the emulator; run the same matrix on the phones you support.
2. **Do not make ZipVoice the default** until first-audio latency is addressed. Try `numSteps=4`
   (upstream example) and re-measure; 4 steps should roughly halve the compute but will not fix
   the tens-of-seconds first chunk by itself.
3. **Keep the ZipVoice `dataDir` fix** (`SherpaConfig` now passes `espeak-ng-data`); without it the
   app dies with status 255. Add an `espeak-ng-data` presence check to `ModelManager.isInstalled`
   so a partial bundle is reported, not fatal.
4. **Add a ZipVoice transcript field/default** and verify the English lexicon path before exposing
   the model in Settings. Today the UI can select a model that is not actually usable for English
   novels.
5. **Keep 4 threads on the iQOO.** The 8-thread pass was slower and hotter (Supertonic 12.3 s vs
   5.1 s, Kokoro 12.3 s vs 7.8 s). If you test 6 threads, cool the phone first and compare on the
   same corpus; do not assume more threads is faster on a big.LITTLE phone.
6. **First-audio latency is the user-facing metric**, not just RTF. Only Pocket showed a first
   callback materially before total synthesis; Kokoro/Supertonic/ZipVoice reported their first
   PCM at the end of the sentence in this AAR. If the reader needs instant speech, it must
   pre-synthesize the first sentence or accept the measured TTFC.

## Physical device

The iQOO I2214 (Android 15, 8 cores, 11.7 GB, MediaTek MT6895) was benchmarked. The existing
release-signed app was **updated in place** with a release-key-signed, debuggable benchmark build,
so settings and downloaded models were preserved. Verified install:

```text
Success
versionName=1.2.1 versionCode=18
pkgFlags=[ DEBUGGABLE HAS_CODE ALLOW_CLEAR_USER_DATA ]
```

The 8-thread pass covered all five engines. The app-default 4-thread pass covered system, Kokoro,
Pocket and Supertonic. The USB connection dropped before the ZipVoice 4-thread run; reconnect the
phone and run:

```bash
DEVICE=10BD8D0276000E7 THREADS=4 REPS=2 \
  MODELS="sherpa-onnx-zipvoice-distill-int8-zh-en-emilia" \
  tools/tts-benchmark/run.sh
```

The arm64 release-signed benchmark APK is `build/NovelSpeak-bench-arm64-v8a-release.apk`
(15,448,334 bytes). `build/NovelSpeak-bench-arm64-v8a.apk` is the debug-key-signed equivalent and
cannot update an installed release build.

## App-level preroll verification (2026-11-03)

This benchmark measures synthesis only. The reader now also has a prewarm/preroll path
(`docs/superpowers/specs/2026-11-03-tts-latency-design.md`). On the iQOO with Supertonic 3,
4 threads, CPU, content prewarmed and prerendered before Play:

- `play clicked` → `PcmPlayer play()` = **11 ms**, → `AudioTrack.start` = **16 ms**.
- No duplicate `engine prepare` when Play was tapped during prewarm.
- Voice change logged `sherpa setVoice` with **no** subsequent `engine prepare`/`engine ready`.
- No `AudioTrack` underrun during steady playback once the preroll lead was present.

The engine harness owns its own `OfflineTts`, so the numbers in the tables above are
unchanged by the reader pipeline work.

## Reproduce

```bash
# emulator (x86_64)
ABI=x86_64 DEBUGGABLE=1 OUT_APK=$PWD/build/NovelSpeak-bench-x86_64.apk ./build.sh
adb -s emulator-5554 install -r build/NovelSpeak-bench-x86_64.apk
DEVICE=emulator-5554 THREADS=4 REPS=2 tools/tts-benchmark/run.sh

# iQOO: sign with the release key so the update is in-place, then run
source "$HOME/.keys/novelspeak-release.env"
KEYSTORE="$HOME/.keys/novelspeak-release.jks" KS_ALIAS=novelspeak \
  KS_PASS="$NOVELSPEAK_KS_PASS" KEY_PASS="$NOVELSPEAK_KEY_PASS" \
  ABI=arm64-v8a DEBUGGABLE=1 OUT_APK=build/NovelSpeak-bench-arm64-v8a-release.apk ./build.sh
adb -s 10BD8D0276000E7 install -r build/NovelSpeak-bench-arm64-v8a-release.apk
DEVICE=10BD8D0276000E7 THREADS=4 REPS=2 tools/tts-benchmark/run.sh
```

The emulator became unavailable during two later ZipVoice repeat attempts (host memory pressure;
the 2 GB AVD was near its limit). The emulator results above use the successful per-model runs.
The raw JSON, summary CSV/JSON/Markdown and upstream SHA-256 values are in
`tools/tts-benchmark/reports/emulator-5554/` and `tools/tts-benchmark/reports/10BD8D0276000E7/`.
