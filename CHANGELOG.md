# Changelog

All notable changes to NovelSpeak are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
