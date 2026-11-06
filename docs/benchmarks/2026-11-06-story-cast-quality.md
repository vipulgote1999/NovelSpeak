# Story Cast — TTS quality and latency evaluation (2026-11-06)

**Scope:** the shipped Story Cast path — deterministic dialogue parser → per-speaker Supertonic
3 int8 synthesis → one continuous playback queue, plus a Kokoro quality-mode comparison. The
fixture is the unattributed Rennick scene
(`tools/cast-parser-test/fixtures/unattributed-scene.txt`), which is deliberately hard: aliases,
vocatives, a third voice, and two genuinely unattributable lines.

**Verdict up front:** voice identity and intelligibility are strong; Supertonic is comfortably
inside real-time and Kokoro is viable but roughly **3× slower in absolute synthesis**.
**Naturalness is acceptable (3.5–4/5 on the valid clean-PCM excerpt) while emotional range is weak
(2–3/5)**. Keep Supertonic as the default cast engine and Kokoro as the quality option pending a
human listening test. The measurement correction and remaining defects are documented below.

---

## 1. Model access: what “Spark 1.3” actually allowed

The request was to evaluate with Spark 1.3 (image + text + audio). That was not possible on the
available router, and the evaluation is explicit about the substitution:

| Check | Result |
|---|---|
| `GET /v1/models` contains `muse-spark-1.3` | **No** |
| `muse-spark-1.2` accepts an `input_audio` part | **No** — HTTP 400, content parts must be `text` |
| `muse-spark-1.2` plain text request | Initially worked (schema probe); later `auth_error` / 403, so no text critique was completed |
| Meta documentation | Muse Spark 1.3 audio is **not fully supported**; Meta recommends 1.2 for audio |
| Audio-capable route that worked | **`gemini-2.5-flash` / `gemini-2.5-flash-lite`** (`vision,audio,text`) |
| `/v1/audio/transcriptions` | HTTP 403 (not available) |

So: **Spark 1.3 was not used**, because it is not exposed and its audio support is officially
degraded. The multimodal judge is Gemini 2.5 Flash (and Flash Lite as a second opinion). This is a
real limitation of the evaluation, not a claim that Spark was tested.

The router key is **not** stored in this repository and is not in the APK. All commands below read
`ROUTER_API_KEY` from the environment.

---

## 2. Method

1. **Real parser output.** `tools/cast-benchmark/ParserMain.kt` compiles the actual
   `DialogueParser` + `CastChunker` and prints the exact 18 cues the reader queues. No
   hand-written segment list.
2. **Real models.** `sherpa-onnx-supertonic-3-tts-int8-2026-05-11` as the shipped engine, and
   `kokoro-int8-en-v0_19` as the quality option; 4 CPU threads, speed 1.0.
3. **Host synthesis.** `tools/cast-benchmark/run_benchmark.py` synthesizes every cue, measures
   wall time, audio duration, RTF, peak, RMS, F0 and spectral centroid, and concatenates the scene
   with 250 ms between cues. The scene sample rate is taken from the model output, not hardcoded.
4. **Controlled switch probe.** The same sentence is synthesized 9× with one speaker and 9× while
   alternating sids 5/1/6. This isolates speaker-switch cost from sentence length.
5. **ASR.** `tools/cast-benchmark/transcribe.py` sends each cue to `gemini-2.5-flash` with **no
   source text in the prompt** and computes WER afterwards.
6. **Audio judge.** `tools/cast-benchmark/judge_audio.py` sends audio + source + the cast-sheet
   screenshot to `gemini-2.5-flash-lite` and asks for strict JSON ratings.
7. **On-device run.** The debug x86_64 build on `emulator-5554` (Android 15, API 35) played the
   same scene; `adb logcat` supplied real synthesis durations and queue gaps.

---

## 3. Cast and voices

| Character | Voice | sid | Gender match |
|---|---|---:|---|
| Narrator / unresolved | Supertonic F1 | 0 | — |
| Marshal Rennick | Supertonic M1 | 5 | male |
| Mara (benchmark) | Supertonic F2 | 1 | female |
| Mara (on-device, after recast) | Supertonic F3 | 2 | female |
| Tomas | Supertonic M2 | 6 | male |

The on-device run used the persisted recast **Mara F3 (sid 2)**, which also proves that a user
override survives a process restart and reaches the synthesis queue. The host benchmark used the
planner's original F2 (sid 1).

The two unresolved lines — `"He's right here."` and `"Mine,"` — intentionally stay on F1. The
judge found this confusing, which is useful product evidence for the planned per-line *Fix
speaker* control.

---

## 4. Host synthesis and latency

Corrected baseline (same 18 cues, 4 threads, speed 1.0): model load **476 ms**, first chunk
**500.6 ms**, scene **51.45 s** from **8.62 s** of synthesis.

| Metric | Value |
|---|---:|
| Overall RTF | **0.168** |
| Chunk synthesis p50 / p95 / max | **461.4 / 617.9 / 676.4 ms** |
| Controlled same-speaker mean | 636.8 ms |
| Controlled alternating-speaker mean | 512.6 ms |
| **Speaker-switch overhead** | **−124.2 ms** (single run; 3-round median −12 ms) |
| Clipping chunks (peak ≥ 0.999) | **0** |
| Peak sample | 0.739 |
| Cues / words | 18 / 104 |
| Pace | **121.3 words/min** |

Changing `sid` does **not** reload the model or add a measurable pause. The 4-thread Supertonic
engine runs ~6× faster than real time on this host.

**Measurement correction.** An earlier version of this report said 98.39 s / RTF 0.119 / 63.4 wpm.
That was a sample-rate labeling bug: the model emits 44.1 kHz, but the scene WAV was written at
22.05 kHz, doubling its apparent duration. The per-cue ASR used the correct rate and is unaffected;
the full-scene MP3 judges were not. The harness now derives the scene rate from the model and
refuses to write a scene with mismatched chunk rates. The numbers above supersede the old ones, and
the affected judge rows are explicitly marked invalid in §7.

### On-device (emulator, x86_64)

Warm start, 18 cues, all three character voices:

| Metric | Value |
|---|---:|
| Total synthesis | **20.81 s** for ~42.0 s of written audio |
| RTF | **~0.50** |
| Per-cue p50 / mean / max | **1076.5 / 1155.9 / 1869 ms** |
| Play tap → first audio | **2733 ms** |
| Queue start gap after a cue finishes (median) | **5 ms** |
| Voices used | sid 5 (Rennick), sid 2 (Mara F3), sid 6 (Tomas) |

The on-device audio duration is computed from the cumulative written frames (1,851,089 at 44.1 kHz),
not the old mislabeled scene. It excludes the silence gaps between cues. The 2.7 s first-audio
latency includes the first cue's 1.32 s synthesis plus player preparation. Once playback is running,
the next cue starts within ~5 ms of the previous synthesis finishing, so speaker changes are not the
bottleneck; the queue is pre-rendered ahead of playback.

---

## 5. Intelligibility (ASR)

`gemini-2.5-flash`, 18 cues, no source text in the prompt:

| Metric | Value |
|---|---:|
| Raw word error rate | **8.7%** |
| Name-normalised word error rate | **3.8%** |
| Exact-match cues | **12 / 18** |
| Narration WER | 9.4% |
| Mara WER | 3.0% |
| Tomas WER | 0% |
| Rennick WER | 53.6% (dominated by one short line; see below) |

Name normalisation maps `Renick → Rennick`, `Marshall → Marshal`, `Thomas → Tomas`, `gray → grey`.
Those are spelling variants, not intelligibility failures. After normalisation only three
differences remain, and waveform inspection changes how two of them should be read:

1. **`"The woman in the grey coat didn't sit."` → `"… didn't"`.** This is **not a truncation**:
   the waveform contains **8 voiced bursts**, one per word, and the final burst is present at
   −22.9 dBFS. The ASR simply omitted the last word. It still shows that the final word is easy
   to miss, so it is an intelligibility risk rather than missing speech.
2. **`"Whose?"` → `"Who's Who"`.** The waveform has one main voiced burst plus a −35 dBFS tail;
   it does **not** contain a second word. The ASR hallucinated on a 0.2 s word. The real defect is
   the ~0.6 s of padding around it, now trimmed for cast dialogue.
3. **`"two"` → `"too"`.** A homophone; the least serious of the three.

Waveform evidence: `docs/media/benchmarks/` stores the scene waveform; the burst counts above were
computed from the exact synthesized cues (`didnt_sit.wav`, `whose_q.wav`) with a −40 dBFS gate.

The full WER table and per-cue references are in
`docs/benchmarks/data/2026-11-06-story-cast/asr_flash.json`.

---

## 6. Speaker separation

Median F0 and spectral centroid per voice (host, 44.1 kHz):

| Voice | F0 median | Centroid | Chunks |
|---|---:|---:|---:|
| Narrator F1 | 184.6 Hz | 2818.8 Hz | 8 |
| Rennick M1 | 131.7 Hz | 2340.3 Hz | 4 |
| Mara F2 | 202.3 Hz | 2289.6 Hz | 3 |
| Tomas M2 | 106.3 Hz | 1610.8 Hz | 1 |
| Unknown F1 | 178.4 Hz | 2408.0 Hz | 2 |

Pairwise F0 separation: **Mara↔Rennick 34.9%**, **Rennick↔Tomas 19.3%**,
**Mara↔Tomas 47.5%**. This matches the valid PCM judges: character distinction was the strongest
dimension (5/5 Flash, 4/5 Lite), with Tomas the closest male voice to Rennick in pitch.

---

## 7. Audio judge

The router caps request bodies at **1,000,000 bytes**, so the full 51-second WAV cannot be sent.
The valid judges heard a **20-second 16 kHz PCM montage** built from the per-cue WAVs, which were
written at the model's correct 44.1 kHz rate.

| Audio given | Model | Naturalness | Distinction | Emotional fit | Pacing | Intelligibility | Overall |
|---|---|---:|---:|---:|---:|---:|---:|
| 20 s, 16 kHz PCM montage | gemini-2.5-flash | 4 | 5 | 2 | 1* | 5 | 2* |
| 20 s, 16 kHz PCM montage | gemini-2.5-flash-lite | 3.5 | 4 | 3 | 3.5 | 4 | **3.5** |

\* The Flash PCM run rated pacing/overall low because the 20-second montage omitted lines and it
treated the omission as a defect. Its naturalness (4), distinction (5) and intelligibility (5) are
valid; its pacing/overall are not. The Lite run is the most complete valid excerpt and gives the
**3.5/5 overall** headline.

**Invalidated rows.** Two earlier judge runs (40 s and 45 s 64/48 kbps MP3 with the cast
screenshot) were produced from the mislabeled scene WAV and therefore played at **half speed**.
Their ratings — including a 1/5 naturalness run — are not evidence about the TTS and are retained
only as `judge-INVALID-halfspeed-*` in the data directory. The MP3 codec was not the cause; the
sample-rate labeling was. The valid files are
`judge-valid-pcm-gemini-flash.json` and `judge-valid-pcm-gemini-flash-lite.json`. A fresh judge run
after the quota resets is the next validation step.

Judge comments from the valid PCM run:

- “Rennick's voice is appropriately gruff and authoritative.”
- “Mara's voice is calm and measured, fitting her role.”
- “Tomas's voice is youthful and defiant,” with “a slight robotic flatness.”
- “The narrator's voice is clear but lacks the emotional nuance of the characters.”
- “The unresolved lines … sound slightly jarring in the narrator's voice.”

---

## 8. What this means

**Strengths**

- Voice identity is real and measurable: 4–5/5 distinction, 19–48% F0 separation, three distinct
  sids in one continuous playback session.
- Intelligibility is good: 96.2% word accuracy after spelling normalisation, 12/18 exact cues.
- Latency is excellent on Supertonic: RTF **0.168 host / ~0.50 emulator**, p95 0.62 s host, no
  model reload and no measurable speaker-switch cost (3-round median **−12 ms**).
- No clipping; peak 0.739 leaves headroom.
- The parser's deliberate `UNKNOWN` policy is correct: it never silently gives the two hard lines
  a wrong character.

**Weaknesses**

- Emotional range is weak (2–3/5): the narrator is clear but flat, and the scene's tension is not
  carried.
- Ultra-short dialogue is hard to recognize and was called robotic; the ASR hallucinated an extra
  word on `"Whose?"` even though the waveform contains only one. The per-cue trim reduces the dead
  air, but a short-line policy is still needed.
- ASR missed the final word of one narration cue (`… didn't sit.`) even though the waveform shows
  it is present; the final word is quiet enough to be an intelligibility risk.
- Unresolved lines on the narrator voice are audibly confusing; the cast UI now has the per-line
  **Fix speaker** control.

---

## 9. Top three fixes (in order)

1. **Guard one-word dialogue.** Synthesize short lines with a neutral carrier phrase and trim it
   (or explicitly pad them), then add an ASR regression for `"Whose?"`, `"Mine,"` and `"Gary!"`.
   Do not let a line under ~3 words go straight to Supertonic.
2. **Protect final-word salience.** The waveform shows `sit` is present, but the ASR missed it.
   Add a final-word energy check to the harness and test whether the per-cue trim or a slower
   delivery improves recognition. Do not call it a truncation; the audio is there.
3. **Raise emotional delivery.** Supertonic's fixed styles are the ceiling here. Use the Kokoro
   comparison in §9b to decide whether the quality option is worth the latency, and expose
   per-character rate/pitch only if it measurably helps. In parallel, add a human listening pass
   for the unresolved lines.

---

## 9a. Fixes shipped after this benchmark

- **Short-fragment cueing** (`CastChunker`): `"Tomas. I told you…"` is now one TTS cue instead of
  two. The cast scene is 18 cues, not 19.
- **Per-line Fix speaker**: the cast sheet exposes **Fix current line** for unresolved cues. On
  device, `"He's right here."` was assigned to Tomas, `unresolved` fell from 2 to 1, synthesis
  switched to `sid 6`, and the correction persisted across a process restart.
- **Dialogue silence trim**: Supertonic pads every short line to ~1.35 s regardless of
  `silence_scale` (0.4 s lead + 0.6 s tail). Cast dialogue now trims that padding per cue through
  the existing DSP trimmer; one measured line dropped from 59,724 to 24,102 samples. This reduces
  the dead air that made `"Whose?"` and `"Mine,"` feel like pauses rather than turns. It does not
  prove the pronunciation fix: a fresh ASR pass is still needed once the router quota resets.
- **Verification**: 516 host checks and Maestro 7 flows / 106 commands pass on the current build.

The original top-three list is otherwise unchanged: final-word salience, one-word delivery and the
emotional range still need work. Kokoro is benchmarked in §9b; a human listening comparison is
still pending.

## 9b. Kokoro quality-mode comparison

Same 18 cues, same speaker mapping, 4 CPU threads, speed 1.0. Kokoro voices: narrator
**Heart (0)**, Rennick **Adam (5)**, Mara **Bella (1)**, Tomas **Michael (6)**.

| Metric | Supertonic 3 int8 | Kokoro int8 (en-v0_19) |
|---|---:|---:|
| Scene audio | **51.45 s** | **32.92 s** |
| Pace | 121.3 words/min | **189.6 words/min** |
| Total synthesis | **8.62 s** | 25.29 s (range 25.3–28.3 s) |
| Overall RTF | **0.168** | 0.768 (range 0.768–0.861) |
| Chunk p50 / p95 | **461 / 618 ms** | 1274 / 2566 ms |
| First chunk | **500.6 ms** | 2127.9 ms |
| Model load | **476 ms** | 1052 ms |
| Switch overhead (3-round median) | **−12 ms** | +219 ms |
| Peak sample | 0.739 | 0.662 |
| Sample rate | 44.1 kHz | 24 kHz |

Voice separation:

| Voice | Supertonic F0 / centroid | Kokoro F0 / centroid |
|---|---:|---:|
| Narrator | 184.6 Hz / 2818.8 Hz (F1) | 196.3 Hz / 3522.2 Hz (Heart) |
| Rennick | 131.7 Hz / 2340.3 Hz (M1) | 125.3 Hz / 1881.2 Hz (Adam) |
| Mara | 202.3 Hz / 2289.6 Hz (F2) | 206.0 Hz / 4062.0 Hz (Bella) |
| Tomas | 106.3 Hz / 1610.8 Hz (M2) | 115.4 Hz / 2556.1 Hz (Michael) |

Pairwise F0 separation is 19.3–47.5% for Supertonic and 7.9–44.0% for Kokoro. Kokoro’s Rennick and
Tomas are closer in pitch (7.9%), but their spectral centroids differ by 26.4%, so they may still
be distinguishable; a listening test is required.

**What the comparison says**

- **Supertonic is the latency choice.** It is ~3× faster in absolute synthesis, ~4.6× lower RTF,
  ~4× faster to first audio, and its speaker-switch cost is in the noise. Its 121 words/min is on
  the slow side at speed 1.0, so the app may want a higher default cast speed or a per-engine
  default.
- **Kokoro is the pacing/quality candidate.** It produces 190 words/min at speed 1.0, closer to a
  normal audiobook, but takes ~25–28 s to synthesize 33 s of audio on this host. Its 3-round switch
  median is +219 ms (one 1.7 s outlier); the pre-render queue can hide that, but it is not free.
- **Do not claim a quality winner yet.** Kokoro has no ASR or judge rating in this pass: the router
  audio quota is exhausted, and the Android AAR uses eSpeak while the Python harness needed a US
  lexicon. The next step is a human/audio-judge comparison of the corrected 45 s excerpts.

Kokoro setup for reproduction: the en-v0_19 tarball has no lexicon; the harness uses
`lexicon-us-en.txt` from the multi-lang bundle and appends `Rennick`, `Tomas` and `Mara` to
`lexicon-cast-en.txt` so those names are not skipped. On Android the same model uses the AAR’s
eSpeak path, so device output may differ from this host run.

Listen to both rendered scenes:
[Supertonic full scene](../../media/benchmarks/story-cast-supertonic.mp3) ·
[Kokoro full scene](../../media/benchmarks/story-cast-kokoro.mp3). The corrected 45-second excerpt
is [here](../../media/benchmarks/story-cast-scene-45s.mp3).

## 10. Limitations

- The on-device run is an **x86_64 emulator**, not a phone. Host RTF 0.168 and emulator RTF ~0.50
  are not phone performance claims; the earlier phone evidence in
  `docs/benchmarks/2026-11-05-story-cast.md` covers voice switching only.
- The judge is an AI listening to a **20-second PCM montage**, not a human MOS panel. The montage
  omits parts of the scene, so its pacing/overall are not a full-scene verdict; naturalness,
  distinction and intelligibility are the usable dimensions.
- The earlier full-scene MP3 judge runs were invalidated by the 22.05/44.1 kHz labeling bug and are
  retained only for audit. The corrected 45-second excerpt in `docs/media/benchmarks/` has not been
  judged yet because the router audio quota is exhausted.
- Kokoro was measured on the host with a US English lexicon assembled from a different bundle; the
  Android AAR uses its own eSpeak path. Its numbers are indicative, not a device guarantee, and no
  Kokoro ASR/judge rating exists.
- The host harness synthesizes the parser’s raw chunk text. The app applies `TtsTextCleaner`
  (quotes removed, terminal punctuation guaranteed) before synthesis, so device audio differs
  slightly. Both engines received the same raw text here, so the comparison is fair.
- The router has aggressive per-minute and per-day quotas. ASR and judge runs are resumable, but a
  full rerun may need to wait for the quota window.
- Spark 1.3 was unavailable; Spark 1.2 rejected audio. No Spark audio rating exists.

---

## 11. Reproduce

```bash
# 1. parser + exact TTS cues
kotlinc app/src/app/novelspeak/SentenceSplitter.kt \
  app/src/app/novelspeak/cast/CastModels.kt \
  app/src/app/novelspeak/cast/DialogueParser.kt \
  app/src/app/novelspeak/cast/CastChunker.kt \
  tools/cast-benchmark/ParserMain.kt -include-runtime -d /tmp/parser-cli.jar
java -jar /tmp/parser-cli.jar tools/cast-parser-test/fixtures/unattributed-scene.txt > /tmp/scene.json

# 2. synthesis + timing (numpy + sherpa-onnx + model dir)
/tmp/castbench/bin/python tools/cast-benchmark/run_benchmark.py \
  --chunks /tmp/scene.json --family supertonic --model-dir /tmp/models/supertonic \
  --out /tmp/cast-bench --threads 4

# Kokoro: download kokoro-int8-en-v0_19.tar.bz2, then add the US lexicon
# (the en bundle omits it; the harness appends the cast names automatically)
/tmp/castbench/bin/python tools/cast-benchmark/run_benchmark.py \
  --chunks /tmp/scene.json --family kokoro \
  --model-dir /tmp/models/kokoro/kokoro-int8-en-v0_19 \
  --out /tmp/cast-bench-kokoro --threads 4

# multi-round speaker-switch probe
/tmp/castbench/bin/python tools/cast-benchmark/switch_probe.py \
  --family supertonic --model-dir /tmp/models/supertonic \
  --out /tmp/switch-supertonic.json

# 3. ASR (key from environment; never commit it)
ROUTER_API_KEY=... /tmp/castbench/bin/python tools/cast-benchmark/transcribe.py \
  --chunks /tmp/scene.json --audio-dir /tmp/cast-bench/chunks \
  --out /tmp/cast-bench/asr.json --model gemini-2.5-flash

# 4. judge on a corrected-speed excerpt (not run in this pass; quota exhausted)
ROUTER_API_KEY=... /tmp/castbench/bin/python tools/cast-benchmark/judge_audio.py \
  --wav docs/media/benchmarks/story-cast-scene-45s.mp3 \
  --source tools/cast-parser-test/fixtures/unattributed-scene.txt \
  --image docs/media/ui-audit/phone-cast.png \
  --note "45s excerpt of a 51s scene; do not penalize the excerpt length" \
  --model gemini-2.5-flash-lite --out /tmp/cast-bench/judge.json
```

Raw evidence: `docs/benchmarks/data/2026-11-06-story-cast/` (Supertonic and Kokoro metrics,
ASR, valid PCM judge JSONs, invalid half-speed judge JSONs, switch probes, on-device extract).
Audio: `docs/media/benchmarks/story-cast-scene-45s.mp3`. Harness:
`tools/cast-benchmark/README.md`.
