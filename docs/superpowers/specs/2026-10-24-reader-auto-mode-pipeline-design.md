# Reader auto mode, single-pass extraction, reader-mode rendering, generation-ahead

Date: 2026-10-24 · Status: approved · Scope: Android app (`app/src/app/novelspeak`)

## Problem

1. A page is extracted and sentence-split up to three times: duplicate `onPageFinished`
   runs a discarded full extract, `retryWeakPage` re-extracts, and `extractNow` races
   `install()` (async JS injection) so the first extraction can return 0 blocks.
2. Pages are only read after the user taps Read. There is no continuous listening mode.
3. Reader mode only recolors a fixed set of elements; site surfaces (`#centralNotice`,
   infobox tables with `display:flex`, light `.hatnote` panels) survive and produce
   unreadable contrast and clipped content.
4. Generation does not always run ahead of playback. The system engine blocks on
   `awaitDrained` before dequeuing the next sentence, and a seek does not cancel an
   in-flight sherpa generation (`stopFlag` is cleared by the next `speak()`).

## Design

### 1. One extraction per document

- `WebExtractor.install(web, onReady)` chains script injection and calls `onReady`
  only after `extract.js` has run.
- `WebExtractor.extract` stays pure (parse + split).
- `MainActivity.extractNow` installs-then-extracts and is single-flight per URL.
- The duplicate-`onPageFinished` branch no longer calls `extract` with a no-op
  callback; it only re-applies reader mode.
- Weak-page retry remains (one additional extraction for challenge/empty pages).

### 2. Auto mode

- `Prefs.autoMode` (default false), one switch in Settings → Playback:
  "Auto mode — read pages automatically and continue to the next chapter".
- On a successful extraction in auto mode: start playback.
- `Reader.Listener.onQueueFinished()` fires when the last sentence drains.
- `MainActivity` handles it: toast (screen on only) → resolve next-chapter link →
  navigate → append the new chapter → wait 2.5 s after extraction → seek to the first
  appended sentence → play.
- Guards: no next link stops with a message; blocked/404 never autoplays; 40-chapter
  cap; user Stop cancels the pending advance; auto mode off = today's behaviour.

### 3. Reader mode

- Surface reset: transparent background, no background-image/box-shadow for all
  non-media elements, then a dark card for `pre`/`code`.
- Explicit light text for structural elements; links keep the accent color.
- Force real table layout (`table`/`caption`/`thead`/`tbody`/`tr`/`td`/`th`), allow
  wrapping, `max-width:100%`, `pre` scrolls.
- Hide `#centralNotice`, `.cbnnr-*`, fundraising and the existing chrome patterns.
- `overflow-x:hidden` on the reader page; highlight uses `inline:'nearest'`.
- Reader mode stays applied when extraction finds no text (except Cloudflare
  interstitials, which must remain interactive).

### 4. Generation ahead of playback

- `PcmPlayer.bufferedFrames()` and `TtsEngine.bufferedSeconds()`.
- `Reader` keeps a real-time pump while playing: submit until
  `bufferedSeconds >= 12` or 12 sentences queued, with the user's `prefetch` as the
  minimum lookahead.
- `SystemTtsEngine`: render loop writes PCM and pushes boundaries; a monitor thread
  awaits them and reports `onDone`. Rendering never waits for playback.
- `SherpaEngine`: monotonic `cancelEpoch`; `stop()` increments it and `speak()` never
  clears it. `SampleSink` and queued tasks check the epoch, so a seek aborts the
  in-flight generation instead of spending seconds finishing stale audio.

## Verification

- kotlinc compile against API 34 + sherpa classes; host TTS harness (28 checks).
- Emulator, empirenovel ch 80 only:
  - extraction appears exactly once per load (grep `extract url`);
  - Kokoro: sanitized dialogue, generation-ahead (`gen done` → `gen start` same
    second), seek aborts within ~1 s, media keys;
  - Pocket: same checks;
  - auto mode: page load starts playback; chapter end advances and resumes;
  - reader mode screenshots at the emulator width, no clipped table, readable contrast.

## Implementation notes (added during verification)

- `peek` of the actual output found sub-speech vocoder bursts (~-48 dBFS) and boundary
  steps. Added a smoothed downward expander in `AudioDsp` and 6 ms sentence fades plus
  gain smoothing in `PcmPlayer`; see `docs/audio-quality.md` (floor -51.6 → -80.3 dBFS,
  harness attenuation 44.9 dB, tone loss 0.05 dB).
- The denoiser bootstrap now seeds the noise window at 10 % of the first frame; treating
  the first frame as the floor suppressed the first second of speech by ~14 dB.
- The seek bar exposes "Sentence N of M" as its accessibility value and ignores a stuck
  drag flag (`isPressed` + 4 s timeout + max-change reset), so it cannot freeze while the
  label advances across a chapter append.
- Appending a chapter updates the reader title (previously it kept the old chapter name).
- Model deletion asks for confirmation before removing 100 MB of files.
- Dotted obfuscation now also collapses single-letter runs whose separators stack
  (`s.h.i.+ning` → `shining`), in both `extract.js` and `TtsTextCleaner`.
