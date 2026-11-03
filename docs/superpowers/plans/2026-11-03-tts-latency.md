# TTS Latency Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Hide the neural TTS cold load and give playback a prerendered lead so the first word starts sooner and sentence boundaries do not go silent.

**Architecture:** Prewarm the selected engine when content is queued. While playback is held, render up to 8 s / 4 sentences into the existing `PcmPlayer` queue. Start as soon as the cursor clip is rendered. Move output DSP to the writer thread and make the AudioTrack prebuffer gate real. Keep the engine loaded across voice changes.

**Tech Stack:** Kotlin, Android AudioTrack, sherpa-onnx `OfflineTts`, the repo's no-Gradle `build.sh`.

**Spec:** `docs/superpowers/specs/2026-11-03-tts-latency-design.md`

## Global Constraints

- Same model, voice, `numSteps`, text cleaner, speed, gain, and denoiser; no quality change.
- Reader/engine state is main-thread confined.
- `PcmPlayer` remains shared by neural and system TTS.
- Release builds are non-debuggable; `DEBUGGABLE=1` is opt-in.
- No new dependencies; plain Kotlin + Android APIs.
- Bounded preroll: `PREROLL_SECONDS = 8f`, `PREROLL_MAX_SENTENCES = 4`.

## Review Focus

- Play tapped during prewarm: exactly one engine load, playback starts when the first clip renders.
- Play tapped before any preroll: first audio is not worse than the current load+first-sentence path.
- Short sentence followed by long sentence: the preroll lead prevents the 4.2 s silence.
- Voice change: no model reload; old pre-rendered audio is discarded.
- Seek/stop while preroll is in flight: stale callbacks cannot start audio or advance the cursor.
- Skipped/empty sentence ahead of the cursor: the queue advances instead of stalling.

---

### Task 1: PcmPlayer writer-side DSP and playback hold

**Files:**
- Modify: `app/src/app/novelspeak/PcmPlayer.kt`
- Test: build + host-independent checks; device log verification in Task 6.

**Interfaces:**
- Produces: `fun setPlaybackHold(hold: Boolean)`, `fun startIfBuffered()`, `fun bufferedSeconds(): Float`, `fun write(samples: FloatArray, fadeIn: Boolean = false): Boolean`.
- Produces: `trackFrames` diagnostic; no API change outside the class.

- [ ] **Step 1: Replace byte chunks with raw float chunks**

`Chunk` becomes `(epoch, samples: FloatArray?, fadeIn, tailFrames)`. `write()` copies
`samples` and enqueues; it increments `writtenFrames` and returns without DSP.

- [ ] **Step 2: Process in the writer thread**

In `writerLoop`, denoise (`denoise?.process`), compute peak/gain, apply fade, quantize to
little-endian PCM, write to the track, capture, and keep `lastWritten`. Reset the local
gain/last-sample state when the epoch changes.

- [ ] **Step 3: Add track frames and hold**

Add `trackFrames` incremented after successful `AudioTrack.write`, `startRequested`, and
`holdPlayback`. `ensure()`/`reset()` must not call `track.play()`; they leave the track
paused. `startIfBuffered()` sets `startRequested` and starts only when not held and
`trackFrames > 0`. `maybeStart()` starts after 350 ms of actual track frames or on a
pending start request.

- [ ] **Step 4: Keep stop/reset/release consistent**

Reset `trackFrames`, `startRequested`, `started`, queue, DSP history, and epoch on
`reset`/`stop`/`release`. `reset()` must not auto-play. `release()` clears the hold.

- [ ] **Step 5: Compile**

Run: `./build.sh` (or `tools/dev-build.sh`)
Expected: exit 0.

---

### Task 2: Engine rendered callback and preroll capability

**Files:**
- Modify: `app/src/app/novelspeak/TtsEngine.kt`
- Modify: `app/src/app/novelspeak/SherpaEngine.kt`
- Modify: `app/src/app/novelspeak/SystemTtsEngine.kt`

**Interfaces:**
- Produces: `fun speak(text: String, onDone: (Boolean) -> Unit, onRendered: ((Boolean) -> Unit)? = null)`
- Produces: `val supportsPreRender: Boolean`

- [ ] **Step 1: Extend `TtsEngine`**

Add the optional `onRendered` parameter and `supportsPreRender` default `true`.

- [ ] **Step 2: Notify from `SherpaEngine`**

Add `onRendered` to `Task`. Post `onRendered(true)` once after the sentence's audio is
queued (`player.write` + `endSentence`), and `onRendered(false)` for skipped, garbage,
empty, or failed output. Keep `onDone` exactly once.

- [ ] **Step 3: Notify from `SystemTtsEngine`**

Add `onRendered` to `Task`. Post true after `player.write`, false for blank/failed
renders. Override `supportsPreRender = false` so the legacy provider playback is never
pre-rendered.

- [ ] **Step 4: Compile**

Run: `./build.sh`
Expected: exit 0.

---

### Task 3: Reader prewarm, bounded preroll, and start policy

**Files:**
- Modify: `app/src/app/novelspeak/Reader.kt`
- Create: `app/src/app/novelspeak/PrerollPolicy.kt` (pure decisions)
- Test: `tools/preroll-policy-test/TestMain.kt`

**Interfaces:**
- Consumes: `TtsEngine.speak(text, onDone, onRendered)`, `TtsEngine.supportsPreRender`, `PcmPlayer.setPlaybackHold`, `PcmPlayer.startIfBuffered`.
- Produces: `Reader.setVoice(id: String)`.
- Produces: `PrerollPolicy.shouldQueueNext(submitted, cursor, total, bufferedSeconds, lastRendered, maxSentences, targetSeconds)` and `PrerollPolicy.canStart(playRequested, playing, cursorRendered)`.

- [ ] **Step 1: Write the failing policy test**

Test cases: first clip queues; no next before the previous rendered; stops at 8 s; stops
at 4 sentences; starts only when requested and cursor rendered; a skipped cursor is not
startable.

- [ ] **Step 2: Run it and see it fail**

Run: `kotlinc app/src/app/novelspeak/PrerollPolicy.kt tools/preroll-policy-test/TestMain.kt -include-runtime -d /tmp/preroll-test.jar && java -jar /tmp/preroll-test.jar`
Expected: compilation failure because `PrerollPolicy` does not exist.

- [ ] **Step 3: Implement `PrerollPolicy`**

Pure object with the two decision functions above, no Android imports.

- [ ] **Step 4: Add Reader prewarm/preroll state**

Add `prerolling`, `playRequested`, `rendered`, `completed`, and `prepareInFlight`
fields. Add `prepareForPlayback`, `beginPreroll`, `pumpPreroll`, `queueSentence`,
`onClipRendered`, `maybeStartPlayback`, `startPlayback`, `cancelPreroll`, and
`prerollTick`. `setContent`/`appendContent`/`seek` schedule preparation. `play()` sets
`playRequested` and starts immediately when the cursor is rendered.

- [ ] **Step 5: Fix completion advancement**

`onSentenceDone` advances through consecutive completed indices, removes rendered
entries, reports finish/error, and pumps or starts as appropriate.

- [ ] **Step 6: Add the in-flight prepare guard**

One prepare at a time. Queue callbacks, drain on completion, discard stale results, and
release them. Prewarm and Play share the same prepare.

- [ ] **Step 7: Add voice retention**

`Reader.setVoice(id)` stops/re-prerolls without releasing the engine. Update
`SettingsActivity.applyVoice` to call it.

- [ ] **Step 8: Run the policy test and compile**

Run: `kotlinc app/src/app/novelspeak/PrerollPolicy.kt tools/preroll-policy-test/TestMain.kt -include-runtime -d /tmp/preroll-test.jar && java -jar /tmp/preroll-test.jar`
Expected: all PASS, exit 0.

Run: `./build.sh`
Expected: exit 0.

---

### Task 4: Default neural model

**Files:**
- Modify: `app/src/app/novelspeak/Prefs.kt`
- Modify: `app/src/app/novelspeak/ModelManager.kt`
- Test: build + Settings inspection.

**Interfaces:**
- Produces: `ModelManager.preferredNeuralModel(ctx: Context): String`.
- `Prefs.modelId` returns the saved id, else the preferred installed model.

- [ ] **Step 1: Add the preference helper**

Return Supertonic if installed, else the first installed non-vocoder model, else Kokoro.

- [ ] **Step 2: Use it only when no model was saved**

Keep explicit choices. Cache the resolved fallback for the process.

- [ ] **Step 3: Compile**

Run: `./build.sh`
Expected: exit 0.

---

### Task 5: Host policy regression

**Files:**
- Create: `tools/preroll-policy-test/TestMain.kt`

- [ ] **Step 1: Run the test**

Run: `kotlinc app/src/app/novelspeak/PrerollPolicy.kt tools/preroll-policy-test/TestMain.kt -include-runtime -d /tmp/preroll-test.jar && java -jar /tmp/preroll-test.jar`
Expected: `PASS` for every check and exit 0.

---

### Task 6: Build, install, and verify on the iQOO

**Files:**
- Modify: `docs/benchmarks/2026-11-03-tts-benchmark.md` (verification note only)
- Build: `build/NovelSpeak-tts-preload-debug.apk`

- [ ] **Step 1: Build a debuggable, release-signed APK**

```bash
source "$HOME/.keys/novelspeak-release.env"
KEYSTORE="$HOME/.keys/novelspeak-release.jks" KS_ALIAS=novelspeak \
  KS_PASS="$NOVELSPEAK_KS_PASS" KEY_PASS="$NOVELSPEAK_KEY_PASS" \
  ABI=arm64-v8a DEBUGGABLE=1 OUT_APK=build/NovelSpeak-tts-preload-debug.apk \
  tools/dev-build.sh
```

Expected: `apksigner verify` succeeds and the APK is listed.

- [ ] **Step 2: Install over the existing app**

`adb -s 10BD8D0276000E7 install -r build/NovelSpeak-tts-preload-debug.apk`
Expected: `Success`; app data and downloaded models preserved.

- [ ] **Step 3: Verify the pipeline logs**

Use a shared-text or page Play flow with a deliberate pause before Play. Check
`/sdcard/Download/novelspeak-log*.txt` or logcat for:
- `engine prepare` once, before `play clicked`
- `preroll start` / `preroll rendered`
- `playback start` immediately after the cursor render
- voice change without a second `engine prepare`

- [ ] **Step 4: Re-run the Supertonic engine benchmark**

```bash
DEVICE=10BD8D0276000E7 THREADS=4 REPS=2 \
  MODELS="sherpa-onnx-supertonic-3-tts-int8-2026-05-11" \
  tools/tts-benchmark/run.sh
```
Expected: load/synth within normal run-to-run variance of the recorded 4838/5112 ms.

- [ ] **Step 5: Reinstall the normal release APK**

`adb -s 10BD8D0276000E7 install -r build/NovelSpeak-main-arm64-release.apk`
Expected: `Success`.

- [ ] **Step 6: Commit**

```bash
git add app/src tools/preroll-policy-test docs
git commit -m "perf(tts): prewarm and prerender sentences before playback"
```

---

## Self-review

- Spec coverage: prewarm (Task 3), preroll (Tasks 1/3), start policy (Task 3), voice retention (Task 3), DSP move (Task 1), in-flight guard (Task 3), default model (Task 4), validation (Tasks 5/6).
- Type consistency: `onRendered: ((Boolean) -> Unit)?`, `supportsPreRender`, `setPlaybackHold`, `bufferedSeconds`, `PrerollPolicy` signatures used consistently.
- Review focus tests are mapped to Task 3/Task 5; device checks in Task 6.
