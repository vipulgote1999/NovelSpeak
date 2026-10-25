# Changelog

All notable changes to NovelSpeak are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.2.0] - 2026-10-25

### Added
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
- Colour system unified into one semantic Palette (removed three duplicated palettes):
  ink base #0E1318 with layered surfaces, secondary/faint text tiers, mint signal #6FE7BE,
  warm amber #F4C66A, softer red #F87C7C, light icon plate #EEF2F5. All text pairs are
  WCAG-AA or better; cards gained 1dp borders and the reader CSS uses the same tokens.
- Version 1.2.0-dev (versionCode 16); allowBackup disabled; exported media receiver permission;
  WebView lifecycle fixed; cleartext allowed only for loopback test hosts; model catalog in assets.
- README positioning corrected: Chrome does support lock-screen playback; the real gap is site
  coverage and the data-sharing setting.

### Fixed
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
