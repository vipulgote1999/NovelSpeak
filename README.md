# NovelSpeak

An Android browser that reads any web page aloud — built because Chrome's *"Listen to this page"*
fails on novel sites and stops when the screen locks.

- **Any URL**: full WebView browser with URL bar, back/forward, reload.
- **Generic extraction**: Mozilla Readability + a text-density fallback; works on empirenovel and
  any similar serialized-novel site (chapter pages, next-chapter detection, append-as-you-go).
- **Two TTS backends**, switchable in settings:
  - **Android system TTS** — instant, offline, every language the device ships, no download.
  - **Neural voices (sherpa-onnx)** — **Kokoro** (11 English voices, 103 MB int8) or
    **Pocket TTS** (zero-shot voice cloning, 98 MB int8), downloaded on demand from upstream.
- **Screen-off playback**: media-playback foreground service + wake lock + MediaSession controls.
- **Streaming pipeline**: sentences are pre-split, the next 1–2 are generated while the current one
  plays, PCM goes straight into a low-latency AudioTrack — no waiting for a whole chapter.
- **Read-along**: the spoken paragraph is highlighted and auto-scrolled on the page itself.

Tuning values are not guesses: they were reverse-engineered from two shipped apps (HayaiTTS,
NekoSpeak) and are listed with their provenance in [OPTIMIZATIONS.md](OPTIMIZATIONS.md).
The reverse-engineering notes are in [docs/re-natural-ai-voices.md](docs/re-natural-ai-voices.md).

## Install

A signed debug build is produced at `build/NovelSpeak-debug.apk`:

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

`build.sh` runs: `aapt2 link` → `kotlinc` → `d8` → package dex + `lib/arm64-v8a/*.so` → `apksigner`
(debug keystore generated on first run). Use `ABI=armeabi-v7a ./build.sh` for 32-bit devices.

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

## Roadmap

1. Voice-clone UI (record/import clip → preview → save) using the already-implemented
   `WavReader` chain and `GenerationConfig.referenceAudio`.
2. Per-site extraction rules cache + "always use reader view" toggle.
3. Chapter prefetch (extract next chapter while the current one plays).
4. ZipVoice int8 for multilingual cloning (transcript field already reserved in the engine).
5. Export the spoken chapter as an audio file.
