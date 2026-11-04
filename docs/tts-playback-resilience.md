# TTS playback resilience: pause, interruption, and speed

**Date:** 2026-11-04
**Scope:** the shared `PcmPlayer`, both TTS engines, audio-focus handling, and the speed slider.

## Reported symptoms

1. During a phone call or after navigating away from the reader, the reading voice slowed down
   or stopped making progress.
2. Pausing, a call, or any audio interruption could leave the player "stuck": the status said
   **Playing**, but the sentence never advanced and no audio was heard.
3. The saved speed could become slower after visiting Settings.

## Root causes

### 1. A short `AudioTrack.write()` killed the PCM writer thread

Android documents that a blocking `AudioTrack.write()` returns a **short transfer count** when
the track is paused/stopped, or when another thread interrupts the write with `pause()`/`flush()`.
`PcmPlayer.stop()` used exactly that pair, and the writer treated any short count as a fatal
write failure:

```kotlin
if (n <= 0) return false
...
if (!writeAll(bytes, t)) break   // exits writerLoop permanently
```

The `writer` field was never cleared, so `startWriter()` (which returned when `writer != null`)
could never create a replacement. A pause that landed while the writer was inside
`AudioTrack.write()` therefore killed audio for the rest of the process. The UI stayed on
**Playing** because `Reader` had no way to know the writer was gone.

**Evidence on the emulator:** after one pause, `ps -T` showed `ns-pcm-writer` present before the
pause and absent after it. After Play, `PcmPlayer` logged `play started ... buffered=9.23s` but
the buffer grew (17 s, 26 s, ...) and the cursor never advanced.

### 2. Transient audio focus was abandoned and never regained

`PlaybackService` paused on `AUDIOFOCUS_LOSS_TRANSIENT`, then `onReaderState` immediately called
`abandonAudioFocusRequest()`. Abandoning the request removes the callback, so the later
`AUDIOFOCUS_GAIN` that a call would normally deliver never arrived. Playback stayed paused
until the user pressed Play manually.

**Evidence:** a simulated incoming call delivered `onAudioFocusChange(-2)`; the log showed the
app pausing and abandoning focus. No GAIN arrived when the call ended.

### 3. Pause was ignored while a Play was still preparing

`Reader.pause()` returned unless `playing == true`. If the user paused during
"Preparing audio…" or during the preroll hold, the pending `playRequested` survived and playback
started anyway. The same race applied to a focus loss during preparation.

### 4. Opening Settings rewrote the speed preference

`SettingsActivity.sliderRow()` called its `onChange` callback while building the view to produce
the initial label. The speed slider's callback wrote `prefs.speed`, and the slider ceiling came
from `reader.maxSpeed()`, which fell back to 2.0x when the engine was not prepared. Merely opening
**Settings → Playback & sleep** on a cold start silently clamped a saved 3.0x speed to 2.0x.

**Evidence:** with `speed=3.0` in prefs and no engine prepared, opening that page changed the
preference to `2.0`.

### 5. System TTS captured the cancellation epoch after dequeuing

`SystemTtsEngine.workerLoop()` read `stopEpoch` after taking a task from the queue. A `stop()`
followed by a quick resume could clear `stopFlag` before that read, letting a stale sentence
render and write after the new playback started.

## Fixes

### PCM writer lifecycle

- `AudioWritePolicy` classifies every write result: `COMPLETE`, `PROGRESS`, `WAIT`, `ABORT`, or
  `ERROR`. A short/zero write while paused/stopped aborts only the current chunk; it never kills
  the writer.
- The writer loop is wrapped in `try/finally`. It clears its `writer` field on exit, and
  `startWriter()` restarts a dead thread. One bad chunk is isolated and cannot take down the loop.
- `stop()`/`reset()` now use `pause()` + `flush()` (the documented immediate-stop pair) and keep
  the track restartable. `stop()` no longer leaves an `AudioTrack` in `PLAYSTATE_STOPPED`.
- `startTrack()` retries `play()` instead of marking a failed start as running, and
  `setPlaybackHold(false)` starts a track that already has buffered frames.
- A real `AudioTrack` error marks the current epoch broken. The sentence fails through
  `onDone(false)` instead of advancing silently; the next `ensure()` rebuilds the track.
- `awaitDrained()` has a two-minute watchdog and the engine monitors verify both the engine epoch
  and the player epoch before reporting success.
- The writer runs at `THREAD_PRIORITY_URGENT_AUDIO`; the AudioTrack ring grew from 1 s to 2 s.
  Both reduce underruns when WebView/page work or a screen transition competes for CPU.

### Audio focus

- Transient loss (`LOSS_TRANSIENT`, including duck) pauses via `Reader.pauseForFocus()` and keeps
  the focus request alive. `AUDIOFOCUS_GAIN` schedules a 250 ms resume.
- A user pause clears the resume intent, so a later call ending cannot start audio the user did
  not ask for.
- Permanent loss stops and abandons focus.
- Requests accept delayed focus gain. If a call owns audio, Play is deferred and the status says
  **Waiting for audio focus…**; it starts on GAIN instead of playing into a muted route and
  silently consuming text.
- The service no longer abandons focus while the Reader is paused by an interruption.

### Reader state

- `pause()` cancels a pending prepare/preroll, not only active playback.
- Focus-pause and focus-wait state is explicit (`pausedByFocus`, `waitingForFocus`) and is cleared
  by user pause/stop/seek/content changes.
- `prepareForPlayback`, `maybeStartPlayback`, and `startPlayback` refuse to start while focus is
  interrupted.

### Settings speed

- `sliderRow()` separates the display formatter from the change callback. Building the screen no
  longer writes any slider value to prefs.
- `Reader.maxSpeed()` returns the known 3.0x ceiling for both shipped engines even before the
  engine is prepared, so the UI does not display or persist a false 2.0x limit.

### System TTS epoch

- Each `Task` carries the `stopEpoch` captured at enqueue time. The worker validates it before
  rendering, before writing, and when creating the drain boundary.

## Regression guards

- `tools/audio-write-policy-test/` — host test for short/zero/negative write classification.
  Includes the exact pause-during-write regression.
- `tools/preroll-policy-test/` — existing preroll/start policy test (still passes).
- `tools/maestro/flows/19_pause_resume.yaml` — pauses during playback, resumes, and waits for the
  sentence cursor to advance. The dead-writer regression would sit on the same sentence forever.

## Verification performed (emulator-5554, Android 15)

- Pause while `ns-pcm-writer` was inside a blocking write: thread remained alive, resume advanced
  the cursor (Sherpa and system engines).
- Five rapid pause/resume cycles: writer alive, cursor advanced from sentence 5 to 6.
- Simulated incoming call (`adb emu gsm call`): transient focus pause logged; `gsm cancel` produced
  `audio focus GAIN resume=true` and playback resumed automatically (Sherpa and system engines).
- Cold start with `speed=3.0`, open Settings → Playback & sleep: prefs stayed `3.0`, UI showed
  `3.00×`.
- Maestro flows 03 (read), 16 (transport), 18 (settings hub), 19 (pause/resume) pass.

## Prevention rules

- Never treat a short `AudioTrack.write()` as fatal. A pause/stop is an epoch change, not a
  writer failure.
- Never `break` out of the PCM writer loop for a per-chunk condition; abort the chunk and keep the
  thread alive.
- Keep the focus request alive across a transient interruption if playback should resume.
- UI builders must not write preferences while rendering. Separate display formatting from user
  actions.
