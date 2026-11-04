# Changelog

All notable changes to NovelSpeak are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.3.0] - 2026-11-04

### Added

- **Supertonic 3 and ZipVoice neural voices.** Two more on-device engine families join Kokoro
  and Pocket TTS: Supertonic 3 with two curated fast styles (F1 female, M1 male; 20 styles
  ship upstream, 123 MB int8) and ZipVoice zero-shot cloning driven by a reference clip plus
  its verbatim transcript (zh + en; 104 MB int8 plus the 52 MB 24 kHz vocoder). Model rows show
  download progress inline, each family keeps a curated voice list instead of duplicated
  entries, and the reader and benchmark now build the same sherpa-onnx graph through one
  shared config builder.
- **On-device TTS benchmark.** A debug-gated activity measures every installed neural engine
  and system TTS in a fresh process: cold load, time to first audio, total synthesis, RTF,
  peak/RMS and RSS. `tools/tts-benchmark/run.sh` is device-agnostic, and the emulator and iQOO
  I2214 results are committed in `docs/benchmarks/2026-11-03-tts-benchmark.md`.
- **Ad blocking before the page runs.** AndroidX WebKit is vendored for document-start
  injection: a popup guard is installed before any page script in both the visible and
  prefetch WebViews, the prefetched chapter runs the same network/cosmetic/behavioural
  pipeline with its own bridge, low-frequency procedural operators (`:has-text`,
  `:matches-attr`, `:matches-css`, …) are supported, and a bundled local supplement covers
  first-party popunders and ad slots that upstream lists miss (for example
  `empirenovel.com/po.js`, `.pub` and `#reader-bottom`).
- Host regressions for the audio write policy, preroll policy, procedural operators, hidden-ad
  extraction and the prefetch view, plus a Maestro pause/resume flow.

### Changed

- **TTS first-audio and sentence-boundary latency.** The selected neural engine is now
  prewarmed when reading content is queued, and up to 8 s / 4 sentences are prerendered while
  playback is held. Play starts as soon as the cursor sentence's first PCM arrives, without a
  model reload, and the lead removes the short→long sentence gap measured on the iQOO.
  Voice changes update the live engine instead of reloading the model. `PcmPlayer` processes
  denoise/gain/PCM conversion on its writer thread and never writes to a paused AudioTrack.
  The default neural model is Supertonic 3 when installed (the benchmark's speed/memory
  winner); explicit user choices are preserved.
- Version 1.3.0 (versionCode 19).

### Fixed

- **Pause, calls, and screen changes no longer leave TTS silent or stuck.** A blocking
  `AudioTrack.write()` interrupted by `pause()`/`flush()` returns a short transfer count; the
  PCM writer treated that as fatal and exited permanently, so resume showed "Playing" with no
  audio. Short writes now abort only the current chunk, the writer thread is self-healing, and
  `stop()`/`reset()` use `pause()`+`flush()` instead of leaving a stopped track. The writer runs
  at urgent-audio priority and keeps a 2 s ring to survive page/screen CPU spikes.
- **Transient audio focus loss resumes when the call/interruption ends.** The service keeps the
  focus request alive for transient loss instead of abandoning it, resumes on `AUDIOFOCUS_GAIN`,
  and defers Play with "Waiting for audio focus…" when a call owns the route. A user pause still
  clears the resume intent.
- **Pause during "Preparing audio…" is honored.** It now cancels the pending prepare/preroll
  instead of letting playback start after the user paused.
- **Opening Settings no longer slows the saved speech speed.** Slider rows no longer write
  preferences while the page is being built; a cold Settings visit used to clamp a saved 3.0×
  speed to the 2.0× fallback. Both shipped engines report their 3.0× ceiling before preparation.
- **System TTS cannot replay a stale sentence after a quick pause/resume.** Each queued task
  carries its cancellation epoch and is validated before rendering and before writing audio.
  See `docs/tts-playback-resilience.md` for the root-cause analysis and regression checks.
- **ZipVoice no longer crashes when selected.** The bundled sherpa-onnx AAR exits with code 255
  when a ZipVoice model has no data directory; the engine now points it at the bundled
  `espeak-ng-data`.
- **Document-start setting changes apply immediately.** Toggling document-start injection in
  site settings used to wait for the next page; it now updates the live WebView.
- **Ad text was still read aloud by both TTS engines.** Readability parses a detached clone
  without computed styles, so an element hidden by an adblock cosmetic rule could still become a
  reader block. Extraction now drops a block when its only live match is hidden
  (`display:none`, `visibility:hidden` or zero client rects), while keeping blocks with no live
  counterpart. The first-page race was fixed too: extraction waits (bounded) for the filter
  engine and the cosmetic CSS before cloning the page, and the first generic class/id batch
  is now flushed synchronously instead of after a 120 ms debounce. `tools/extract-test/` is a
  Chrome regression for the hidden-ad case.

## [1.2.1] - 2026-10-27

### Fixed

- Prefetched **Next ch**: when pressed near the end of a chapter, the next chapter's opening
  sentences were submitted to the TTS engine twice (once by appending while playback was still
  live, then again by the follow-up seek). The reader now stops before appending the cached
  chapter, so each sentence is generated exactly once.
- Version 1.2.1 (versionCode 18).

## [1.2.0] - 2026-10-27

### Added
- On-device voice previews are now a dedicated section in Settings, independent of the active
  engine: every installed Kokoro speaker and the Pocket narrator has a ▶ that samples a short
  line through the offline engine, even while system TTS is selected. Previews pause/resume the
  reader, temporarily load the auditioned model, restore the active model and selected voice,
  and refuse on low-memory/ABI-mismatch devices instead of crashing.
- System TTS voice previews remain available in the Voice card (20-voice shortlist plus a
  Browse-all picker for the 400+ device voices) for comparison with the offline voices.
- Current voice is shown in the player card ("Voice · Kokoro · Bella (US female)"), including
  device voice selection, and voice/engine changes now drop the cached engine so they apply at
  the next play instead of after a process restart.
- Home tab / new-tab page: NovelSpeak wordmark, one search pill, a curated shortcut grid for
  popular novel sites (plus Library), and Continue-reading cards from the local library.
- Local library with saved reading position, Continue reading, bookmarks and reading stats.
- Share-sheet and selected-text intake; EPUB/PDF/plain-text import (PDF is text-layer only).
- Sleep timer (15/30/45/60 min or end of chapter), engine-capped speed up to 3x, volume boost,
  pitch and skip-silence.
- Reader typography controls (size, family, theme, line height, width) and word-level read-along.
- Pronunciation dictionary applied to both TTS engines; paywall/login detection with an honest
  warning (no bypass); diagnostics bundle; privacy and licences page.
- Chapter prefetch: the next chapter is extracted in a hidden WebView and appended without a stall.
- Translation hand-off to the system browser; two-pane tablet/landscape layout.
- Signed model catalog verification plus an explicit OTA check; tools/model-catalog-sign.sh.
- Maestro E2E harness (fixtures, 10 flows, share/adaptive checks) and host test suites for the
  library, settings features, TTS cleaner, audio DSP, EPUB and PDF importers.

### Changed
- Settings is now a categorized hub (Reading aloud, Voices & previews, Playback & sleep,
  Reading & display, Privacy & data, Advanced, About) with one short page per category instead
  of one 14-section scroll. Summaries show the current engine/voice, speed, theme, ad-block state
  and performance at a glance; the overlapping sticky footer is replaced by an inline status line,
  section labels are sentence case, and Reset moved to Advanced.
- Colour system unified into one semantic Palette (removed three duplicated palettes):
  ink base #0E1318 with layered surfaces, secondary/faint text tiers, mint signal #6FE7BE,
  warm amber #F4C66A, softer red #F87C7C, light icon plate #EEF2F5. All text pairs are
  WCAG-AA or better; cards gained 1dp borders and the reader CSS uses the same tokens.
- Version 1.2.0 (versionCode 17); allowBackup disabled; exported media receiver permission;
  WebView lifecycle fixed; cleartext allowed only for loopback test hosts; model catalog in assets.
- README positioning corrected: Chrome does support lock-screen playback; the real gap is site
  coverage and the data-sharing setting.

### Fixed
- Read now always restarts the current page from sentence 1 in every state, including auto
  mode, paused playback, an in-flight extraction, and a verification interstitial (reloads once
  instead of silently doing nothing). Auto-advance is cancelled while restarting.
- Next ch now resolves the next chapter reliably: the prefetched chapter is only reused when it
  was created from the current page (stale caches are dropped), page scripts are installed on
  demand if Next ch is pressed before extraction, the link lookup is retried while the page
  loads, and numbered chapter URLs fall back to +1 when no anchor exists.
- Imported EPUB/PDF/text books are now durable: extracted sentences are stored in app-private
  files (filesDir/imports/<id>.txt + index.json), listed in Library → Imported books and Home →
  Continue reading, reopen in the reader for TTS, and save playback position. Previously they
  existed only in the reader's memory and silently vanished.
- EPUB import on Android: the XML parser now falls back to a plain namespace-aware parser when a
  vendor build rejects a hardening feature (the previous silent "corrupt OPF" failure).
- Per-site reader mode now actually writes per site; top-bar label reflects it.
- Sleep-timer/import/bookmark actions reachable from the overflow menu; speed UI no longer lies.
- Stale stopping behaviour after seek while a long sentence plays (carried from 1.1.0 evidence).

## [1.1.0] - 2026-10-24

### Added

- Auto mode: extract and start reading on page load, then prompt and advance to the next
  chapter, wait 2.5 s and continue. Off by default.
- Generation-ahead pipeline: synthesis continues while audio plays, targeting ~12 s of buffered
  audio (8-sentence hard cap), so sentence transitions do not wait for the model.
- Reader-mode surface/layout reset: dark surfaces, real table layout, hidden site banners, no
  horizontal clipping; applied even when a page yields no text.
- Media controls: seek bar announces its position and survives interrupted drags.
- Chrome-style address bar: tap to expand, select the full URL, clear, cancel,
  recent-URL suggestions, page-load progress, reload/stop, and an overflow menu for
  home, copy/share, external browser, site settings, and app settings.
- Site settings dialog: per-site reader mode and ad-blocking toggles.
- Permissions & background section in Settings with notification status, channel
  status, system-settings shortcuts, battery-settings entry point, and a summary of
  every permission the app uses.
- Proper MediaStyle playback notification backed by the existing MediaSession,
  with chapter metadata, public lock-screen visibility, and dynamic
  play/pause, previous, next, and stop controls.

### Changed

- Extraction is single-pass: script injection is awaited before extraction, duplicate
  `onPageFinished` no longer re-parses and re-splits the page, and repeated Read taps share one
  in-flight extraction.
- Notification permission is requested only once, on the first Play action with
  loaded content. Opening the app or Settings no longer opens the system dialog.
  If the permission is denied, an inline player-card hint and Settings status row
  link to the system controls instead of asking again.
- Playback service uses `START_NOT_STICKY`, releases audio focus and the wake lock
  when paused, and stops cleanly for paused sessions when the task is removed.
- Removed the unused `RECORD_AUDIO` permission. NovelSpeak now requests only
  notifications at runtime; network, wake lock, and media-playback foreground
  service permissions are normal install-time permissions.
- `PlaybackService` is explicitly non-exported and uses only explicit, immutable
  PendingIntents for notification and MediaSession actions.

### Fixed

- Audio: low-level vocoder noise and clicks at sentence boundaries. Measured floor improved
  from -51.6 to -80.3 dBFS with speech level unchanged; see `docs/audio-quality.md`.
- Startup speech was attenuated ~14 dB by the denoiser bootstrap (caught by a new DSP test).
- Chapter title stayed on the previous chapter after an auto-advance; it now updates on append.
- Delete on a neural model now asks for confirmation before removing files.
- Headset/Bluetooth media buttons did nothing (or could not resume) on the system
  TTS engine. System TTS now renders to PCM and plays in-process, so NovelSpeak owns
  the audio client and Android routes media keys to its MediaSession; a manifest
  media-button receiver covers the restart case. See `docs/media-controls.md`.
- Playback stalling after seeking while a long sentence was playing: the drain waiter
  now aborts when the AudioTrack is flushed (`PcmPlayer.epoch()`).
- Neural TTS hallucinating or playing continuous noise on quotes and special
  characters: raw text reached Kokoro/Pocket unsanitized. Text is now normalized
  (quotes removed, asterisks/censored words handled, ellipsis/dash turned into a
  pause, punctuation-only sentences skipped), short dialogue fragments are merged,
  and a rail-pinned garbage chunk is dropped before it reaches the audio track.
  See `docs/tts-text-normalization.md`.
- Repeated notification permission prompts on every Play tap and Settings visit.
- Media playback notification not appearing as a proper system media control.
- URL bar being too narrow to edit long chapter URLs.

## [1.0.0] - 2026-10-22

First stable release.

### Added

- Full WebView browser with URL bar, back/forward/reload, and next-chapter navigation.
- Generic page extraction using Mozilla Readability plus a text-density fallback. Works on
  serialized-novel sites; handles dotted obfuscation, hidden nodes, and short repeated dialogue.
- Read-along highlighting and auto-scroll on the page itself.
- Two TTS backends:
  - Android system TTS with voice and speed selection.
  - sherpa-onnx neural TTS with Kokoro (11 English voices) and Pocket TTS (zero-shot cloning).
- Streaming sentence pipeline that synthesizes ahead while audio plays.
- Low-latency mono 16-bit AudioTrack playback with drain tracking.
- Screen-off playback through a media-playback foreground service, wake lock, and MediaSession
  transport controls.
- On-demand model downloads with SHA-256 verification and tar.bz2 extraction.
- Ad blocking: EasyList/EasyPrivacy/uBlock network rules, cosmetic filtering, popup and redirect
  blocking, per-site bypass, list updates, and a deterministic self-test.
- Reader and settings redesign, diagnostics log, and reader preferences.

### Fixed

- sherpa-onnx callback now follows the `1 = continue`, `0 = stop` contract; the inverted return
  truncated every sentence after the first 1.2 s chunk.
- Duplicate `onPageFinished` events for the same URL no longer rewind playback.
- Same-URL reloads reinstall the extractor correctly.
- TTS truncation, extraction noise, and reader rewind issues found during on-device testing.
- Media focus and transport controls behave correctly during playback.

### Known limits

- Cloudflare-protected sites may require solving a challenge once inside the WebView.
- Pause stops the engine and resumes at the start of the current sentence.
- Neural model downloads are 98–103 MB and extraction can take 1–3 minutes on a phone.
- The release APK is arm64-v8a only.
- ZipVoice transcript-based cloning is not wired yet.

[1.0.0]: https://github.com/vipulgote1999/NovelSpeak/releases/tag/v1.0.0
