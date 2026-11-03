# TTS first-audio and sentence-boundary latency design

**Date:** 2026-11-03
**Status:** Approved (approach A)
**Benchmark:** `docs/benchmarks/2026-11-03-tts-benchmark.md`

## Intent

Reduce the time from Play to the first spoken word and remove the silence between
sentences on the neural TTS path. The selected engine is Supertonic 3, the benchmark's
overall speed/memory winner. The pipeline must not change text normalization, voice,
speed, generation steps, or model output.

## Goals

- Hide the 4.8 s Supertonic cold load on the iQOO by preparing the selected engine when
  reading content is queued, not when Play is tapped.
- Have the first sentence (and, when the user takes a moment before Play, the next
  sentence) synthesized before playback starts.
- Start playback immediately when a pre-rendered first sentence is available, and as soon
  as the first sentence is rendered when Play is tapped during preparation.
- Prevent the short-sentence → long-sentence underrun caused by serial generation.
- Keep the engine loaded when only the voice changes.
- Keep audio output identical: same model, `numSteps`, text cleaner, speed, gain, and
  denoiser. Moving DSP between threads must not change the samples.

## Non-goals

- Changing the model, `numSteps`, sentence-splitting rules, or voice quality.
- Streaming inside a Supertonic sentence (the AAR returns the first PCM only at the end).
- Rewriting playback as a media3/ExoPlayer pipeline.
- Background synthesis of whole chapters or cross-chapter prefetch.

## Constraints

- No Gradle; the APK is built by `build.sh` with plain Kotlin.
- `PcmPlayer` is shared by the neural and system TTS engines.
- Reader/engine state is main-thread confined; engine prepare/render callbacks are posted
  to the main thread.
- The release build must stay non-debuggable; benchmark/testing builds opt in with
  `DEBUGGABLE=1`.
- A single `OfflineTts` generation thread; the model returns one sentence at a time for
  Supertonic/Kokoro and a few chunks for Pocket.

## Measured problem

On the iQOO, Supertonic 3 at 4 threads:

- cold load 4838 ms
- first PCM 3304 / 5109 / 8349 ms for 60 / 106 / 162 characters
- audio 4157 / 7060 / 10657 ms
- RTF 0.784

First Play is therefore `load + first sentence` = 8.1–13.2 s. Warm, it is 3.3–8.3 s.
A 60-character sentence followed by a 162-character sentence gives
`8351 - 4157 = 4194 ms` of silence because generation of the next sentence starts only
after the current sentence finishes generating. `prefetch=4` queues work but creates no
lead at startup.

## Approaches considered

### A. Supertonic + prewarm/preroll pipeline (selected)

Prepare the engine on content load. While playback is held, render the first sentences
into the existing `PcmPlayer` queue. Start playback as soon as the first sentence is
ready (or immediately if it was already rendered). Keep the engine across voice changes.
Move output DSP to the writer thread. Add an in-flight prepare guard.

**Why:** quality-preserving, uses the existing queue and engine, no duplicate audio cache,
and the bounded preroll removes the cold-load wait and the first short→long gap.

### B. Minimal prewarm/tuning

Prewarm, retain the engine, fix the prebuffer, move DSP. Smaller change, but the first
Play still waits for the whole first sentence and startup gaps remain.

### C. Device-adaptive engine

Use Pocket on ARM phones (faster on the iQOO) and Supertonic on x86. Best raw latency,
but a different voice and +211 MB RSS, so it needs a quality decision. Not selected.

## Design

### 1. PcmPlayer: real prebuffer + deferred DSP

`PcmPlayer` currently starts the AudioTrack in `ensure()`/`reset()` and processes
denoise/gain/float→PCM on the caller (generation) thread. Changes:

- `write(FloatArray)` copies the samples, enqueues a raw chunk, and returns. No DSP on the
  generation thread.
- The writer thread performs denoise, gain/limiting, float→PCM, capture, and the 6 ms
  tail ramp. `gainNow`, `lastWritten`, and the writer epoch are writer-local.
- Add `trackFrames`, incremented only after `AudioTrack.write` succeeds. Playback starts
  from `trackFrames`, not enqueued frames, so a slow denoiser cannot start a track with
  no data.
- Add `setPlaybackHold(Boolean)`. While held, `ensure()`/`reset()` leave the track paused
  and `maybeStart()` does nothing. `startIfBuffered()` records a start request but does
  not play until hold is released and at least one frame is in the track.
- `startIfBuffered()` sets `startRequested`; the writer starts a short (<350 ms) first
  clip as soon as it has data, and `maybeStart()` starts after 350 ms of actual track data.
- `reset()` clears the queue, frames, start request, gain state, and DSP history, then
  pauses/flushes; it never starts playback. `stop()`/`release()` clear the same state.
- `bufferedSeconds()` continues to report enqueued minus played frames, which is the
  correct lead for the Reader's preroll/lookahead decisions.

### 2. TtsEngine: rendered notification + preroll capability

Add to the engine contract:

```kotlin
fun speak(text: String, onDone: (Boolean) -> Unit, onRendered: ((Boolean) -> Unit)? = null)

/** False when the backend cannot safely render ahead without playing (system fallback). */
val supportsPreRender: Boolean get() = true
```

`onRendered(hasAudio)` fires once when the sentence's audio has been queued into
`PcmPlayer` (not when it finishes playing), on the main thread. It lets the Reader start
playback exactly when the first clip is ready.

- `SherpaEngine` fires `onRendered(true)` after `player.write`/`endSentence`, and
  `onRendered(false)` for skipped/garbage/empty output. The task carries the callback.
- `SystemTtsEngine` fires it after `player.write`, and false for blank/failed renders.
  It sets `supportsPreRender = false` because the legacy direct-speak fallback plays
  through the provider, outside `PcmPlayer`.

### 3. Reader: prewarm + bounded preroll + start policy

Add explicit state:

- `prerolling`: synthesis is queued into the held player while not playing.
- `playRequested`: the user asked for playback; start when the cursor clip is rendered.
- `rendered: Map<Int, Boolean>`: per-sentence `hasAudio`.
- `completed: Set<Int>`: sentences whose `onDone` arrived; advance through consecutive
  completions even if the callback arrived ahead of the cursor (fixes skipped sentences).

Flow:

1. `setContent`, `appendContent`, `seek`, and `invalidateEngine` schedule
   `prepareForPlayback()`.
2. `ensureEngine` has one in-flight prepare. Prewarm and Play share it; callbacks are
   queued and drained with the result. A changed engine/model discards the stale result,
   releases it, and starts the latest request after the current load finishes.
3. After the engine is ready, `beginPreroll()` sets `player.setPlaybackHold(true)`, resets
   the player, and queues the cursor sentence.
4. `pumpPreroll()` queues the next sentence only after the previous one rendered, until
   `PREROLL_SECONDS` (8 s) or `PREROLL_MAX_SENTENCES` (4) is reached. This bounds CPU and
   memory while giving the next sentence a lead.
5. `play()` sets `playRequested`. If the cursor is already rendered, `startPlayback()`
   immediately. Otherwise it stays in "Preparing audio…" and starts on the cursor's
   `onRendered`.
6. `startPlayback()` releases the hold, starts the track, schedules the normal pump
   (`prefetch`, 12 s target, 8-sentence hard cap), starts word tracking, and starts the
   foreground service.
7. `onSentenceDone` advances through `completed` consecutive indices, not only
   `index == cursor`; a skipped sentence ahead of the cursor cannot stall the queue.
8. `seek`/`stop`/`setContent`/`invalidateEngine`/voice or speed changes call
   `cancelPreroll()`: increment `generation`, stop the engine/player, clear rendered and
   completed state, and reset `submitted`.

Start policy: preroll is opportunistic. If the user takes at least the preroll time before
tapping Play, the first 1–2 sentences are ready and first audio is immediate. If Play is
tapped immediately, playback starts as soon as the first sentence is rendered; the
pipeline never waits for the second sentence, so first audio is no worse than today once
the engine load is hidden.

### 4. Engine retention on voice change

`Reader.setVoice(id)` stops playback, calls `engine.setVoice(id)` when an engine is live,
and re-prerolls from the cursor without releasing the model. `SettingsActivity.applyVoice`
uses it. `VoicePreview` keeps `invalidateEngine()` because it must free the model before
loading a different one for audition.

### 5. Default neural model

`Prefs.modelId` keeps an explicitly saved choice. When no model has been saved, it returns
Supertonic if installed, otherwise the first installed non-vocoder model, otherwise
Kokoro. This makes the benchmark winner the default neural path for new/updated installs
without breaking users who only have Kokoro. The system engine remains the no-download
default; installing a neural model still selects it.

## Validation

- Host test for the pure preroll policy: target/limit decisions, first-clip start, skipped
  sentence advancement.
- Compile with `./build.sh`.
- Build a debuggable, release-signed APK and install it over the existing app (preserves
  data), then verify logs:
  - `prewarm` starts on content set and completes before Play.
  - `preroll` renders at least one sentence while not playing.
  - Play starts within one frame after the cursor clip is rendered.
  - Voice change does not log `engine prepare` again.
  - No duplicate engine prepare when Play is tapped during prewarm.
- Run `BenchmarkActivity` for Supertonic to confirm engine load/generation unchanged.
- Reinstall the normal release APK after verification.

## Risks and mitigations

- **Paused-track writes:** the writer may block once the 1 s AudioTrack buffer is full;
  the queued PCM is the preroll buffer. `startPlayback()` pauses/flushes only on resets,
  and `stop()` unblocks via `pause/flush/stop`.
- **Thread movement changes DSP results:** keep the same `AudioDsp` instance and the same
  per-sample gain loop order on the writer thread; only the thread changes.
- **Preroll wastes CPU/battery:** bounded to 8 s / 4 sentences, cancelled on navigation,
  stop, voice, speed, and engine/model changes.
- **Skipped sentences:** completed-set advancement and `onRendered(false)` prevent
  stalls.
- **System TTS provider playback:** pre-render is disabled for the system engine; it keeps
  the existing fast path.
