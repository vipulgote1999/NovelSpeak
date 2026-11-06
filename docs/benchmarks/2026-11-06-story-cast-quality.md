# Story Cast — TTS quality and latency evaluation (2026-11-06)

**Scope:** the shipped Story Cast path — deterministic dialogue parser → per-speaker Supertonic
3 int8 synthesis → one continuous playback queue. The fixture is the unattributed Rennick scene
(`tools/cast-parser-test/fixtures/unattributed-scene.txt`), which is deliberately hard: aliases,
vocatives, a third voice, and two genuinely unattributable lines.

**Verdict up front:** voice identity and intelligibility are strong; latency is well inside
real-time; **naturalness is mid and emotional range is weak**. It is good enough to keep as an
experimental Story Cast mode, not yet good enough to call flagship storytelling quality. Three
concrete defects are listed at the end.

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
2. **Real model.** `sherpa-onnx-supertonic-3-tts-int8-2026-05-11`, 4 CPU threads, speed 1.0.
3. **Host synthesis.** `tools/cast-benchmark/run_benchmark.py` synthesizes every cue, measures
   wall time, audio duration, RTF, peak, RMS, F0 and spectral centroid, and concatenates the scene
   with 250 ms between cues.
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

Model load: **630.6 ms**; first chunk: **767.8 ms** (cold); scene: **98.39 s** of audio from
**11.71 s** of synthesis.

| Metric | Value |
|---|---:|
| Overall RTF | **0.119** |
| Chunk synthesis p50 / p95 / max | **653.8 / 828.7 / 876.7 ms** |
| Controlled same-speaker mean | 753.0 ms |
| Controlled alternating-speaker mean | 752.5 ms |
| **Speaker-switch overhead** | **−0.5 ms** (noise) |
| Clipping chunks (peak ≥ 0.999) | **0** |
| Peak sample | 0.552 |
| Cues | 18 |

The switch result is the important one: changing `sid` does **not** reload the model or add a
measurable pause. The 4-thread Supertonic engine runs ~8.4× faster than real time on this host.

### On-device (emulator, x86_64)

Warm start, 18 cues, all three character voices:

| Metric | Value |
|---|---:|
| Total synthesis | **20.81 s** for 98.4 s audio |
| RTF | **0.21** |
| Per-cue p50 / mean / max | **1076.5 / 1155.9 / 1869 ms** |
| Play tap → first audio | **2733 ms** |
| Queue start gap after a cue finishes (median) | **5 ms** |
| Voices used | sid 5 (Rennick), sid 2 (Mara F3), sid 6 (Tomas) |

The 2.7 s first-audio latency includes the first cue's 1.32 s synthesis plus player preparation.
Once playback is running, the next cue starts within ~5 ms of the previous synthesis finishing, so
speaker changes are not the bottleneck; the queue is pre-rendered ahead of playback.

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
Those are spelling variants, not intelligibility failures. After normalisation only three real
differences remain:

1. **`"The woman in the grey coat didn't sit."` → `"… didn't"`.** The final word is missing from
   the audio, not just the transcript. This is a real truncation bug.
2. **`"Whose?"` → `"Who's Who"`.** A one-word line is unstable and gains an extra word.
3. **`"two"` → `"too"`.** A homophone; the least serious of the three.

The full WER table and per-cue references are in
`docs/benchmarks/data/2026-11-06-story-cast/asr_flash.json`.

---

## 6. Speaker separation

Median F0 and spectral centroid per voice (host, 22.05 kHz):

| Voice | F0 median | Centroid | Chunks |
|---|---:|---:|---:|
| Narrator F1 | 186.1 Hz | 2705.6 Hz | 8 |
| Rennick M1 | 129.8 Hz | 2216.1 Hz | 4 |
| Mara F2 | 201.4 Hz | 2277.2 Hz | 3 |
| Tomas M2 | 102.6 Hz | 1796.9 Hz | 1 |
| Unknown F1 | 173.7 Hz | 2774.9 Hz | 2 |

Pairwise F0 separation: **Mara↔Rennick 35.6%**, **Rennick↔Tomas 21.0%**,
**Mara↔Tomas 49.1%**. This matches the judges: character distinction was the strongest dimension
(4–5 / 5), and Tomas is the closest to the narrator on timbre, which the Lite judge also noticed.

---

## 7. Audio judge

The router caps request bodies at **1,000,000 bytes**, so the full 98-second WAV cannot be sent.
Each row records exactly what the judge heard.

| Audio given | Model | Naturalness | Distinction | Emotional fit | Pacing | Intelligibility | Overall |
|---|---|---:|---:|---:|---:|---:|---:|
| 40 s, 64 kbps MP3 + screenshot | gemini-2.5-flash | 1 | 4 | 1 | 2 | 2 | **1** |
| 20 s, 16 kHz PCM montage | gemini-2.5-flash | 4 | 5 | 2 | 1* | 5 | 2* |
| 20 s, 16 kHz PCM montage | gemini-2.5-flash-lite | 3.5 | 4 | 3 | 3.5 | 4 | **3.5** |
| 45 s, 48 kbps MP3 + screenshot | gemini-2.5-flash-lite | 3.5 | 4 | 3 | 3.5 | 4 | **3.5** |

\* The Flash PCM run rated pacing/overall low because the 20-second montage omitted lines and it
treated the omission as a defect. Its naturalness (4), distinction (5) and intelligibility (5) are
valid; its pacing/overall are not.

The 1/5 Flash run is an **outlier caused by the 64 kbps MP3**: the same model gave naturalness 4
and intelligibility 5 on clean PCM. That run also produced timestamps beyond the 40-second clip,
so its segment timestamps are not reliable. The consistent, valid result is the **3.5/5 overall**
from Flash Lite on the 45-second continuous excerpt.

Judge comments worth keeping:

- “Rennick's voice is appropriately gruff and authoritative.”
- “Mara's voice is clear and distinct, conveying a sense of weary pragmatism.”
- “Tomas's voice is a bit too similar to the narrator's.”
- “The unresolved lines … sound like the narrator, which is confusing.”
- “`Whose?` is a bit too flat.”
- “`Mine,` … sounds abrupt and unresolved.”

---

## 8. What this means

**Strengths**

- Voice identity is real and measurable: 4–5/5 distinction, 21–49% F0 separation, three distinct
  sids in one continuous playback session.
- Intelligibility is good: 96.2% word accuracy after spelling normalisation, 12/18 exact cues.
- Latency is excellent: RTF 0.12 host / 0.21 emulator, p95 under 0.83 s host, no model reload and
  no measurable speaker-switch cost.
- No clipping; peak 0.552 leaves headroom.
- The parser's deliberate `UNKNOWN` policy is correct: it never silently gives the two hard lines
  a wrong character.

**Weaknesses**

- Emotional range is mid (3/5): the judge describes the narrator as clear but flat, and the scene's
  tension is not carried.
- Ultra-short dialogue is unstable: `"Whose?"` became `"Who's Who"` and `"Mine,"` was called
  robotic. This is the single biggest quality defect.
- One narration cue loses its final word (`… didn't sit.`).
- Unresolved lines on the narrator voice are audibly confusing; the cast UI needs the planned
  per-line fix.

---

## 9. Top three fixes (in order)

1. **Guard one-word dialogue.** Synthesize short lines with a neutral carrier phrase and trim it
   (or explicitly pad them), then add an ASR regression for `"Whose?"`, `"Mine,"` and `"Gary!"`.
   Do not let a line under ~3 words go straight to Supertonic.
2. **Fix the truncated tail.** `"The woman in the grey coat didn't sit."` lost `sit`. Add a
   trailing-space/punctuation check and an end-of-audio energy assertion in the TTS regression
   harness; re-run the ASR table until the cue is exact.
3. **Raise emotional delivery.** Supertonic's fixed styles are the ceiling here. Benchmark Kokoro
   (the app's quality option, 11 voices) on the same scene, and expose per-character rate/pitch
   only if it measurably helps. In parallel, ship **Fix speaker** for the two unresolved lines.

A Kokoro comparison was **not** run in this pass; it is the next benchmark, not a claim.

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
- **Verification**: 500 host checks and Maestro 7 flows / 106 commands pass on the new build.

The original top-three list is otherwise unchanged: the truncated `"…didn't sit."` tail and the
emotional range still need work, and Kokoro has not been benchmarked yet.

## 10. Limitations

- The on-device run is an **x86_64 emulator**, not a phone. Host RTF 0.12 and emulator RTF 0.21
  are not phone performance claims; the earlier phone evidence in
  `docs/benchmarks/2026-11-05-story-cast.md` covers voice switching only.
- The judge is an AI listening to compressed excerpts, not a human MOS panel. The 1/5 MP3 result
  and the 3.5/5 PCM result show how much the input codec changes the verdict; treat the ratings as
  directional, not absolute.
- The router has aggressive per-minute and per-day quotas. ASR and judge runs are resumable, but a
  full rerun may need to wait for the quota window.
- Spark 1.3 was unavailable; Spark 1.2 rejected audio. No Spark audio rating exists.
- The 45-second judge excerpt covers all four voices but not the final reveal; pacing is judged on
  that excerpt only.

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
  --chunks /tmp/scene.json --model-dir /tmp/models/supertonic \
  --out /tmp/cast-bench --threads 4

# 3. ASR (key from environment; never commit it)
ROUTER_API_KEY=... /tmp/castbench/bin/python tools/cast-benchmark/transcribe.py \
  --chunks /tmp/scene.json --audio-dir /tmp/cast-bench/chunks \
  --out /tmp/cast-bench/asr.json --model gemini-2.5-flash

# 4. judge (45 s excerpt stays under the router's 1 MB body cap)
ROUTER_API_KEY=... /tmp/castbench/bin/python tools/cast-benchmark/judge_audio.py \
  --wav /tmp/cast-bench/scene_45s_48k.mp3 \
  --source tools/cast-parser-test/fixtures/unattributed-scene.txt \
  --image docs/media/ui-audit/phone-cast.png \
  --note "45s excerpt of a 98s scene; do not penalize the excerpt length" \
  --model gemini-2.5-flash-lite --out /tmp/cast-bench/judge.json
```

Raw evidence: `docs/benchmarks/data/2026-11-06-story-cast/` (metrics, ASR, judge JSONs,
on-device log extract). Audio: `docs/media/benchmarks/story-cast-scene-45s.mp3`.
Harness: `tools/cast-benchmark/README.md`.
