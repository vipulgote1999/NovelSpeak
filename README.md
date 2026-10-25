# NovelSpeak

An Android browser that reads any serialized novel aloud — with neural voices that work offline,
screen-off controls, and no account, meter or telemetry.

**Why it exists.** Chrome's *Listen to this page* keeps playing while the screen is locked (Google's
help doc says so), but it is limited to supported sites: *"This feature isn't available yet on all
websites."* It also needs the *Make searches and browsing better* data-sharing setting. NovelSpeak
instead extracts the chapter you are actually on — on any novel site — with Readability plus a
density fallback, keeps reading through screen-off with a foreground service, and does the voices
on-device after a one-time model download. Free file readers (ReadEra, PocketBook, Moon+) can read
documents but cannot open a URL; paid AI readers are good at documents but bill subscriptions and
require accounts. This app is the combination none of them ship.

**Home tab** (v1.2): opening the app lands on a quiet new-tab page — wordmark, one search field, a
curated shortcut grid for novel sites (and Library), and *Continue reading* cards. No feed, no ads,
no personalization profile.

**Around the reader** (v1.2): local library with reading position and *Continue reading*; share-sheet
and selected-text intake; true per-site reader mode; sleep timer (minutes or end-of-chapter); reader
typography (font, size, family, theme, width); pronunciation dictionary applied to both engines;
volume boost, pitch and skip-silence; offline audio capture to WAV; paywall/login **detection** with
an honest warning (no bypass); diagnostics bundle and a privacy page; no ads, analytics or accounts.
Also: chapter prefetch (next chapter extracted in a hidden WebView so playback never waits), word-level
read-along, bookmarks (saved sentences with snippets), EPUB/PDF/plain-text import (PDF is text-layer
only, no OCR), translation hand-off to the system browser, a two-pane tablet/landscape layout, and
signed model-catalog verification (`tools/model-catalog-sign.sh`).

- **Any URL**: full WebView browser with URL bar, back/forward, reload.
- **Generic extraction**: Mozilla Readability + a text-density fallback; works on empirenovel and
  any similar serialized-novel site (chapter pages, next-chapter detection, append-as-you-go).
- **Two TTS backends**, switchable in settings:
  - **Android system TTS** — instant, offline, every language the device ships, no download.
  - **Neural voices (sherpa-onnx)** — **Kokoro** (11 English voices, 103 MB int8) or
    **Pocket TTS** (zero-shot voice cloning, 98 MB int8), downloaded on demand from upstream.
- **Screen-off playback**: media-playback foreground service + wake lock + MediaSession controls.
  Wired/Bluetooth headset buttons (play/pause, next, previous) work for both engines: system TTS is
  rendered to PCM and played in-process so Android routes media keys to NovelSpeak. See
  [docs/media-controls.md](docs/media-controls.md).
- **Streaming pipeline**: sentences are pre-split, the next 1–2 are generated while the current one
  plays, PCM goes straight into a low-latency AudioTrack — no waiting for a whole chapter.
- **Read-along**: the spoken paragraph is highlighted and auto-scrolled on the page itself.
- **Auto mode** (setting): pages are extracted and read automatically, and at the end of a
  chapter the reader prompts, opens the next chapter, waits 2.5 s and continues. Off by default.
- **Generation ahead of playback**: sentences are synthesized up to ~12 s of buffered audio
  while the current one plays, so sentence changes do not wait for synthesis.
- **Audio cleanup**: vocoder hiss is suppressed and sentence boundaries are faded, so quotes /
  dialogue boundaries do not click or hiss. See [docs/audio-quality.md](docs/audio-quality.md).
- **Ad blocking**: EasyList/EasyPrivacy/uBlock lists, a token-indexed matcher, cosmetic filtering,
  popup/redirect blocking and a per-site bypass. Pure Kotlin, no native dependency. See
  [docs/adblocker.md](docs/adblocker.md).

Tuning values are not guesses: they were reverse-engineered from two shipped apps (HayaiTTS,
NekoSpeak) and are listed with their provenance in [OPTIMIZATIONS.md](OPTIMIZATIONS.md).
The reverse-engineering notes are in [docs/re-natural-ai-voices.md](docs/re-natural-ai-voices.md).

## Install

Download the latest APK from [GitHub Releases](https://github.com/vipulgote1999/NovelSpeak/releases/latest):

- `NovelSpeak-1.1.0-arm64-v8a.apk` — Android 7.0 (API 24)+, arm64-v8a.
- `NovelSpeak-1.1.0-arm64-v8a.apk.sha256` — verify with `sha256sum -c`.

The release APK is signed with a dedicated release key. If you previously installed a
debug-signed build, uninstall it first; that erases its settings and downloaded models.

For development, `./build.sh` produces a debug-signed `build/NovelSpeak-debug.apk`:

```bash
termux-open build/NovelSpeak-debug.apk      # opens the system installer
```

arm64-v8a only by default (the neural `.so`s are ~32 MB). First run asks for notification
permission (needed for the playback notification).

## Use

1. Open a chapter (default start page: `https://www.empirenovel.com/`).
2. Tap **Read** — the page is extracted and queued.
3. **▶ / ⏸**, **⏮ / ⏭** move by sentence, **■** stops, **− / +** change speed.
4. **Next ch** follows the page's own "next chapter" link and appends it to the queue.
5. **⚙** → choose engine, install a neural model, pick a voice, tune threads/NNAPI/prefetch.

### Ad blocking

Ads are blocked at three levels, because a WebView cannot be a mini-uBO:

* **Network** — `shouldInterceptRequest` matches each subresource against EasyList,
  EasyPrivacy and uBlock rules and returns an empty body. Rules are indexed by a rare
  token (the same strategy as Brave's `adblock-rust`), so a request checks a handful
  of buckets instead of 120k patterns.
* **Cosmetic** — a content script hides site-specific selectors and asks native code
  for generic selectors that match the classes/ids actually present on the page.
  It also handles `:has-text`, `:upward`, `:style`, `:remove` and friends.
* **Behavioural** — popups/redirects are blocked when a `$popup` rule matches a
  non-gesture navigation, `window.open` is neutralised, and blocked tracker scripts
  get small surrogates (`ga`, `googletag`, `fbq`, …) so the page keeps working.
  Full-page jumps to suspicious ad-landing domains (for example
  `yourbestplace.top` or `ho.beefycasease.cfd`) are refused even when an invisible
  click-layer makes them look gesture-driven. Fake “Chrome Mobile WebView Privacy”
  / notification-permission nags and transparent full-screen click-layers are
  removed — including nags inside same-origin `srcdoc` iframes — while real
  page content and cross-origin Cloudflare-style security widgets are left alone.

The **Ad** button in the URL bar disables blocking for the current site and reloads.
Settings → **Ad blocking** has the master switch, cosmetic/popup toggles, **Update
lists** and a deterministic **Self-test**. The lists are bundled (≈5 MB); updates
are downloaded to `filesDir/adblock/` and override the bundled copies.

Limits worth knowing: WebView exposes no resource type (it is inferred from `Accept`
and the URL extension), no redirect targets, and no response bodies. `$csp`,
`$removeparam`, trusted scriptlets and system-wide VPN/DNS blocking are out of scope.
Licences and sources are in `app/assets/adblock/NOTICE.txt`.

### Reader reliability

- The sherpa-onnx callback now follows the library contract (`1 = continue`,
  `0 = stop`); the previous inverted return truncated every sentence after the
  first 1.2 s chunk.
- Duplicate `onPageFinished` events for the same URL no longer rewind playback.
- Extraction normalizes site-level dotted obfuscation, skips CSS-hidden nodes, and
  keeps short repeated dialogue.
- Long sentences split at punctuation/whitespace boundaries, with a 180-char
  default and a migration for the old 90-char default.
- **Neural text safety**: raw chapter text is sanitized before Kokoro/Pocket.
  Quote glyphs (which Kokoro renders as audible junk at every dialogue boundary),
  asterisks, ellipses and dashes are normalized without touching words, punctuation-only
  fragments are skipped, short dialogue fragments are merged, and a saturated/garbage
  output chunk is dropped instead of played. See
  [docs/tts-text-normalization.md](docs/tts-text-normalization.md).
- The reader and settings screens respect status/navigation bar insets.

Root causes and on-device evidence: [docs/reader-fixes.md](docs/reader-fixes.md).

### Neural voices

`⚙ → Neural model → Install` downloads and SHA-256-verifies the upstream sherpa-onnx bundle:

| Model | Size | Voices |
|---|---|---|
| Kokoro int8 (`kokoro-int8-en-v0_19`) | 103 MB | 11 fixed English voices |
| Pocket TTS int8 (`sherpa-onnx-pocket-tts-int8-2026-01-26`) | 98 MB | zero-shot clone from a reference clip; ships `test_wavs/bria.wav` as the default narrator |

Nothing is bundled in the APK. Downloads come from the official
`k2-fsa/sherpa-onnx` `tts-models` release; hashes are pinned in `ModelManager.kt`.

## Build

### On-device (Termux, no Android SDK/Gradle)

```bash
pkg install -y openjdk-21 kotlin d8 aapt2 apksigner zip
# platform android.jar (API 34) — aapt2 in Termux cannot read the API 35/36 jars
mkdir -p ~/build/cache && cd ~/build/cache
curl -LO https://dl.google.com/android/repository/platform-34-ext7_r03.zip
unzip -o platform-34-ext7_r03.zip "android-34/android.jar" -d sdk34
# deps
mkdir -p ~/build/deps && cd ~/build/deps
curl -LO https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar
curl -LO https://repo1.maven.org/maven2/org/apache/commons/commons-compress/1.27.1/commons-compress-1.27.1.jar
curl -LO https://repo1.maven.org/maven2/commons-io/commons-io/2.20.0/commons-io-2.20.0.jar
# d8 9.x from Google Maven (Termux's d8 3.3 crashes on commons-io)
curl -LO https://dl.google.com/dl/android/maven2/com/android/tools/r8/9.5.28/r8-9.5.28.jar && mv r8-9.5.28.jar r8.jar
cd - && ./build.sh
```

`build.sh` runs: `aapt2 link` → `kotlinc` → `d8` → package dex + `lib/arm64-v8a/*.so` → `zipalign`
→ `apksigner`. The version comes from `app/AndroidManifest.xml`; debug builds use
`build/debug.keystore` and are generated on first run. For a signed release build, set
`KEYSTORE`, `KS_ALIAS`, `KS_PASS`, `KEY_PASS`, and `OUT_APK` — see [RELEASING.md](RELEASING.md).
Use `ABI=armeabi-v7a ./build.sh` for 32-bit devices.

### Android Studio

The sources are plain Kotlin with **no AndroidX** (framework APIs only), so the project opens
anywhere. For AGP 8 remove the `package="app.novelspeak"` attribute from `app/AndroidManifest.xml`
and set `namespace = "app.novelspeak"` in the module's `build.gradle.kts`; add
`implementation(files("libs/sherpa-onnx.aar"))`, `implementation("org.apache.commons:commons-compress:1.27.1")`,
`implementation("commons-io:commons-io:2.20.0")`.

## Architecture

```
MainActivity        WebView browser + player bar + Read/Next-chapter
WebExtractor        injects readability.js + extract.js, parses blocks, highlight/scroll, next link
Reader              sentence queue, cursor, prefetch pipeline, engine-agnostic state machine
TtsEngine           interface
 ├ SystemTtsEngine  android.speech.tts, QUEUE_ADD pipelining, voice/speed
 └ SherpaEngine     sherpa-onnx OfflineTts (Kokoro/Pocket), streaming callback, boundaries
PcmPlayer           low-latency mono 16-bit AudioTrack, drain tracking
ModelManager        download + SHA-256 + tar.bz2 extract of model bundles
WavReader           24 kHz mono conversion + NekoSpeak cleanup chain (for Pocket reference clips)
PlaybackService     foreground service + MediaSession + wake lock (screen-off playback)
```

## Known limits

- **Cloudflare-protected sites** (empirenovel included) may show a challenge inside the WebView;
  solve it once in the browser view and the session cookie persists. Extraction then works on the
  real chapter page.
- **Pause** stops the engine and resumes at the start of the current sentence (system TTS has no
  pause primitive; this is what all shipped readers do).
- **ZipVoice** (transcript-based cloning) is not wired yet — Pocket already gives zero-shot cloning
  without a transcript, which is the better fit for a reader. ZipVoice fields are documented in
  `docs/re-natural-ai-voices.md` §3.
- Model downloads are large; use Wi-Fi. Settings shows download %, then per-file extraction
  progress (`Extracting model.int8.onnx 42% (56/134 MB)`). Extraction is single-threaded bzip2 and
  takes roughly 1–3 minutes on a phone; a wake lock + keep-screen-on is held during install so it
  cannot be suspended. A 45 s no-progress watchdog aborts with a readable error.
- The default build is arm64-v8a only.

## License

NovelSpeak is licensed under the [Apache License 2.0](LICENSE). Bundled ad-blocking lists are
redistributed unmodified under their own licenses; see
[app/assets/adblock/NOTICE.txt](app/assets/adblock/NOTICE.txt).

## Roadmap

1. Voice-clone UI (record/import clip → preview → save) using the already-implemented
   `WavReader` chain and `GenerationConfig.referenceAudio`.
2. Per-site extraction rules cache + "always use reader view" toggle.
3. Chapter prefetch (extract next chapter while the current one plays).
4. ZipVoice int8 for multilingual cloning (transcript field already reserved in the engine).
5. Export the spoken chapter as an audio file.