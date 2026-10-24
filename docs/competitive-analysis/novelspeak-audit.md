# NovelSpeak v1.1.0 — ground-truth capability audit

**Audit date:** 2026-10-24 (Asia/Calcutta) · **Auditor:** app-audit (Agent Team, task-5) · **Repo:** /home/vipul/Project/NovelSpeak @ 89e4809 (release: NovelSpeak 1.1.0, working tree clean)
**Scope:** the shipped app + its build/verification docs. **No external competitor research is included here** — other streams own that. This document is the "what NovelSpeak actually is" baseline the roadmap must be written against.

**Evidence policy.** Every claim is tagged **Observed** (read directly in a repo file,
a repo evidence log, or the built APK on 2026-10-24) or **Inferred** (deduced by combining
observed facts) or **Unknown** (not verifiable from this repo, or not independently fetched).
Anchors use the form `path:line`. Verbatim quotes are ≤25 words. Numbers that appear in the
project's own docs are labelled "repo doc" and are *not* independently re-measured here; where the
underlying log artifact exists in the repo it is cited as well.

**What was read (all of it):** 29 Kotlin files / 9,746 lines (task brief said "~7.9k / 26 files";
the tree is larger — correction recorded); app/assets/extract.js (424 lines), app/assets/adblock/content.js
(597), app/assets/readability.js (2,786, vendored Mozilla Readability, Apache-2.0), app/assets/adblock
lists (5.14 MB) + NOTICE; build.sh; app/AndroidManifest.xml; README.md, OPTIMIZATIONS.md,
CHANGELOG.md, RELEASING.md; docs/{adblocker, audio-quality, media-controls, permissions-and-playback,
reader-fixes, re-natural-ai-voices, security-alignment-2026-10-22, tts-text-normalization}.md;
docs/reader-fixes-evidence/*; tools/{adblock-test, audio-dsp-test, tts-cleaner-test, url-input-test};
built artifacts under build/.

**What was not done:** no APK was installed or executed; no network calls to third-party stores; no
edit of any source file (write scope was these two deliverables only). Claims about runtime
behaviour come from the repo's on-device evidence logs, which are quoted as such.

---

## 0. TL;DR

| # | Finding | Status |
|---|---|---|
| 1 | Framework-only Kotlin app: 29 files / 9,746 lines, zero AndroidX, 1 Android native dependency (sherpa-onnx AAR) + Commons Compress/IO. | Observed |
| 2 | **15,254,977 B (14.5 MiB)** signed arm64 APK; 41,947,675 B uncompressed of which **31,927,176 B is .so** (onnxruntime 22.2 MB) and 4,641,640 B is dex. | Observed (unzip) |
| 3 | Two TTS backends: Android system TTS rendered to PCM via `synthesizeToFile` + WavReader, and sherpa-onnx Kokoro (11 EN voices) / Pocket (zero-shot clone, default narrator clip). Both play through one in-process `PcmPlayer`. | Observed |
| 4 | Streaming reader: sentence queue with prefetch + **12 s / 8-sentence** generation-ahead cap, pump tick every 500 ms, epoch-based cancellation for seek/pause. | Observed |
| 5 | Extraction is generic: Readability + density fallback + live-DOM re-tagging for highlight, with dotted-obfuscation normalisation, hidden-node skipping, and a four-strategy next-chapter finder (rel=next, text incl. CJK, href regex, numeric +1). | Observed |
| 6 | Ad blocker is a real engine: 122,884 network / 32,403 cosmetic / 4,612 scriptlet rules indexed into 111,922 host + 3,449 token buckets; deterministic self-test 22/22. | Observed (repo docs + logs) |
| 7 | Measured audio quality: p20 noise floor **−51.6 → −80.3 dBFS** with speech p50 unchanged (−22.6 → −23.3 dBFS). | Observed (repo doc/docs evidence) |
| 8 | Biggest product gap: **no persistent library, no saved reading position** — only `last_url` and 8 recent URLs survive a restart; the cursor lives in memory only. | Observed |
| 9 | Security posture is good but not complete: immutable PendingIntents, non-exported service, URL scheme allowlist, cleartext off; **but** exported `MediaButtonReceiver`, mixed-content COMPATIBILITY, third-party cookies on, `allowBackup=true` with no rules, and no `WebView.onPause()/destroy()`. | Observed |
| 10 | Neural generation on the reference phone ran at roughly **0.8–1.1× real time** (computed from the repo's own gen-done log), so the 12 s buffer is load-bearing rather than cosmetic. | Inferred from observed log |

---

## 1. Architecture

### 1.1 Component inventory (all Observed)

| Component | File (LOC) | Responsibility | Key anchors |
|---|---|---|---|
| `NovelSpeakApp` (Application) | Reader.kt:15-92 (390 total) | Process singleton; crash handler → MediaStore Download file; owns Prefs, PcmPlayer, Reader; starts AdBlocker load | 24-46, 48-86 |
| `MainActivity` | MainActivity.kt (1772) | WebView browser, address bar/suggestions, player card, Read/Next-ch, reader mode, site dialog, auto-advance, insets | 128-138, 391-401, 597-778, 1170-1290 |
| `WebExtractor` | WebExtractor.kt (122) | JS injection, JSON→blocks, sentence split + short-fragment merge, highlight/scroll, reader CSS toggle, next link | 25-32, 34-58, 65-83, 85-105 |
| `Reader` | Reader.kt:103-390 | Engine-agnostic state machine: queue, cursor, generation token, pump/prefetch, seek, listeners | 158-176, 180-207, 209-232, 305-339, 341-366 |
| `TtsEngine` | TtsEngine.kt (37) | Interface: prepare/speak/stop/release/voices/setVoice/bufferedSeconds | 4-37 |
| `SystemTtsEngine` | SystemTtsEngine.kt (417) | android.speech.tts + `synthesizeToFile` → 24 kHz WAV → PcmPlayer; direct-speak fallback; boundary monitor | 75-123, 190-257, 264-309, 367-382 |
| `SherpaEngine` | SherpaEngine.kt (407) | sherpa-onnx OfflineTts (Kokoro/Pocket), streaming callback sink, generation config, saturation guard, 45 s cap | 144-199, 209-251, 262-349, 73-136 |
| `PcmPlayer` | PcmPlayer.kt (389) | One AudioTrack per chapter, streaming writer thread, denoise hook, output gain/limiter, sentence fades, drain bookkeeping/epoch | 87-136, 159-210, 217-221, 277-331, 349-353 |
| `AudioDsp` | AudioDsp.kt (453) | Streaming spectral denoiser (512/256 STFT, minimum statistics, DD Wiener gain) + low-level gate | 42-83, 160-187, 222-359 |
| `WavReader` | WavReader.kt (173) | RIFF PCM 8/16/24/32/float decode, mono mix, linear resample, HPF, trim, 30 s cap | 27-39, 43-91, 108-121, 142-163 |
| `TtsTextCleaner` | TtsTextCleaner.kt (215) | Neural input sanitiser: NFKC, quotes removed, glyph allowlist, ellipsis→pause, EOS guarantee | 69-190 |
| `ModelManager` | ModelManager.kt (299) | Manifest + SHA-256 download + tar.bz2 extract with progress and 45 s stall watchdog | 38-59, 107-173, 234-285 |
| `PlaybackService` | PlaybackService.kt (679) | Foreground media service, MediaSession, MediaStyle notification, wake lock, audio focus, media-key dispatch | 55-139, 141-186, 315-343, 454-527 |
| `MediaButtonReceiver` | MediaButtonReceiver.kt (58) | Manifest receiver for the restart/no-session case; ACTION_DOWN only, PLAY/PLAY_PAUSE/HEADSETHOOK on 26+ | 28-57 |
| `AppPermissions` | AppPermissions.kt (133) | POST_NOTIFICATIONS ask-once + system-settings entry points + status strings | 40-57, 59-132 |
| `Prefs` | Prefs.kt (208) | 17 settings, 4 tuning migrations, adblock allowlist, recent URLs; Kokoro voice table | 23-47, 50-158, 194-206 |
| `DiagLog` | DiagLog.kt (259) | Log file in /sdcard/Download via MediaStore (API 29+) or app-external fallback; per-line synchronous append | 33-84, 226-250 |
| `SettingsActivity` | SettingsActivity.kt (986) | Engine/model/voice/tuning/reading/permissions/adblock UI; install + list-update runners | 176-337, 340-422, 625-702 |
| `AdBlocker` + `AdBlockBridge` | AdBlocker.kt (591), AdBlockBridge.kt (54) | WebView adapter: intercept, popup/redirect guard, dialog suppressor, cosmetic injection, list updates, stats | 136-196, 217-258, 261-316, 376-401, 469-501 |
| adblock package (8 files, 1,859 LOC) | AdBlockEngine.kt, FilterParser.kt, AdBlockModels.kt, UrlTools.kt, PublicSuffixList.kt, Scriptlets.kt, Surrogates.kt, AdBlockSelfTest.kt | Token-indexed matcher; ABP/uBO parser; PSL; 7 scriptlets; 10 surrogates; 22-assertion self-test | Engine 37-116, 394-536; Parser 51-232; SelfTest 25-95 |
| `UrlInput` | UrlInput.kt (59) | Address-bar normaliser; non-web schemes become search queries | 15-58 |
| App-side JS | extract.js (424), content.js (597), readability.js (2,786) | Page extraction/reader CSS/highlight/next-link; cosmetic+behavioural adblock; vendored Readability | extract.js 9-200, 202-341, 402-416; content.js 308-597 |

Non-source components: `build.sh` (119 lines: aapt2 → kotlinc → d8/R8 → zip → zipalign → apksigner;
Maven/GitHub deps fetched by hand), `app/assets/adblock/*.txt` (5.14 MB bundled lists + PSL),
`tools/*-test` (4 JVM harnesses: adblock engine, AudioDsp, SentenceSplitter+TtsTextCleaner, UrlInput).

### 1.2 Data flow (Observed; the shape to give archify)

```
[address bar] UrlInput.normalize ──▶ WebView.loadUrl
      │
      ├─ onPageStarted ─▶ AdBlocker.onPageStarted(url)          (stats reset, doc host)
      ├─ onPageCommitVisible ─▶ AdBlocker.inject(web,url)        (content.js + configJson + scriptlets)
      ├─ shouldInterceptRequest ─▶ AdBlockEngine.check ─▶ null | empty/pixel/surrogate response
      ├─ shouldOverrideUrlLoading ─▶ popup/intent/TLD heuristics ─▶ block | allow
      └─ onPageFinished ─▶ [dedupe lastExtractedUrl] ─▶ WebExtractor.install(readability.js, extract.js)
                                                       └─▶ extract.js __nsExtract()
                                                             Readability(clone, charThreshold:200)
                                                             └ fallback: density container
                                                             └ tagLive() re-marks live DOM (data-ns)
                            ◀── JSON {title, blocks[i], chars, url}
                    SentenceSplitter.split(block, maxChars=180)
                    mergeGroups(short fragments <24 chars)
                            ▼
Reader.setContent / appendContent ──▶ sentences[Block(text, blockIndex)], cursor, generation
      │
      ├─ play() ─▶ ensureEngine(prefs.engine) ─▶ TtsEngine.prepare()
      ├─ pump() every 500 ms while playing:
      │     while submitted < cursor+prefetch(≤5)      → e.speak()
      │     while submitted < cursor+8 && buffered<12s → e.speak()
      │
      ├─ SherpaEngine: genQueue → generationLoop
      │     TtsTextCleaner.cleanForNeural (skip if unspeakable)
      │     GenerationConfig(speed, silenceScale=1.0, sid, reference, numSteps=8)
      │     generateWithConfigAndCallback → SampleSink.invoke(FloatArray)→1/0 (rail-pinned guard, 45 s cap)
      │                                 └─▶ PcmPlayer.write → playQueue → writer thread → AudioTrack
      │     Boundary(endFrame, epoch) → monitorLoop → awaitDrained → onDone
      │
      └─ SystemTtsEngine: queue → workerLoop
            synthesizeToFile(.wav, 45 s latch) → WavReader.parse/resample(24 kHz) → 6 ms edge fades
            → PcmPlayer.write → Boundary → monitorLoop (independent of render loop)
                            ▼
onSentenceDone(index==cursor) ─▶ cursor++ ─▶ notifyHighlight ─▶ extract.js __nsHighlight(+scrollIntoView)
                            ▼
Reader.notifyState ─▶ MainActivity.onReaderState (player card, seek bar, glyphs)
                   ─▶ PlaybackService.onReaderState (MediaSession state/metadata, notification, wake lock, focus)
```

### 1.3 Threading and state ownership (Observed)

| Thread | Created at | Work |
|---|---|---|
| main / UI | Android | Reader state machine (`Handler` in Reader.kt:124), all engine callbacks re-posted to main, WebView calls, UI |
| `ns-adblock-load` | AdBlocker.kt:49-51 | Parse ~168k lines, build index, publish immutable engine (`loading/reloadPending` guard) |
| `ns-sherpa-gen` | SherpaEngine.kt:255 | One generation at a time; TTS text clean + native generate + PcmPlayer.write |
| `ns-sherpa-mon` | SherpaEngine.kt:258 | Drain waiting per sentence boundary (250 ms poll) |
| `ns-system-tts` | SystemTtsEngine.kt:154 | Render sentence to WAV, decode, fade, write PCM |
| `ns-system-mon` | SystemTtsEngine.kt:160 | Drain waiting (500 ms poll) |
| `ns-pcm-writer` | PcmPlayer.kt:260 | Only writer of AudioTrack (`writeAll`), applies sentence-tail ramps |
| model install | SettingsActivity.kt:649 | Download/verify/extract (bzip2 single-threaded), UI progress via runOnUiThread |
| adblock list update | SettingsActivity.kt:685 | Sequential HTTP downloads, format validation, then reload() |

State ownership: **Reader** owns queue/cursor/generation token/playing; **PcmPlayer** owns
AudioTrack, `writtenFrames`, `playbackEpoch`, gain state, playQueue; **engines** own native sessions
and their own task queues; **PlaybackService** owns wake lock, audio focus, MediaSession and
notification; **Prefs** is the only persistence (SharedPreferences `novelspeak`); **DiagLog** is a
sink, never read back by the app.

---

## 2. Verified capability inventory (by subsystem)

### 2.1 Extraction

| Capability | Status | Anchor | Evidence / limit |
|---|---|---|---|
| Full browser (URL bar, back/forward, reload, stop, progress) | Present | MainActivity.kt:165-169, 597-778, 896-903 | Address bar expands on tap, select-all, clear, 8 recent-URL suggestions (1096-1167) |
| Address input hardening | Present | UrlInput.kt:15-43 | 13 non-web schemes (javascript:, file:, content:, intent:, data:, blob:, mailto:, tel:, ftp:, ws:, wss:, chrome:, resource:) are turned into a Google search query, never loaded |
| JS injection order (no race) | Present | WebExtractor.kt:25-32 | `install()` awaits readability.js then extract.js, then calls onReady; `extract()` only after (1179-1180) |
| Readability extraction | Present | extract.js:135-148 | Clone + `new Readability(clone,{charThreshold:200})`; blocks collected from a hidden holder with `textContent` |
| Density fallback | Present | extract.js:71-88, 151-154 | Candidate selector list incl. chapter/content/read/text/entry/post; `score = t.length - links*80 + paragraphs*40`, min 400 chars; triggers when Readability yields <2 blocks |
| Last-resort whole-body block | Present | extract.js:156-160 | Guarantees something is spoken on hostile pages (quality risk: nav noise) |
| Hidden-node skipping | Present | extract.js:29-39, 53-58 | `display/visibility/opacity/getClientRects`; SKIP_SEL drops nav/header/footer/aside/form/iframe |
| Dotted obfuscation normalisation | Present | extract.js:12-26 (norm), TtsTextCleaner.kt:108-116 | Collapses 3+ letter/dot (+/-) runs unless all-uppercase; "cla.s.sroom"→"classroom", keeps e.g./i.e./decimals; defense-in-depth again in the cleaner |
| Block tagging for read-along | Present | extract.js:64, 100-126 | `data-ns` indices; Readability parses a clone so `tagLive()` re-matches blocks to live leaf nodes by normalised text (exact=100, prefix=60) |
| Read-along highlight + auto-scroll | Present | extract.js:402-416; WebExtractor.kt:85-90 | Toggles `.ns-active`, heading variant, `scrollIntoView({block:'center',behavior:'smooth'})`; auto-scroll toggle in Prefs:104-106 |
| Short repeated dialogue preserved | Present | extract.js:62 | Dedupe only for blocks >80 chars |
| Reader mode (strip chrome, dark typography, layout reset) | Present | extract.js:202-341, 343-398 | ~140 CSS rules: dark surfaces, 760 px column, Georgia 17 px/1.7, table→real table layout, hide nav/banner/modal/cookie/challenge classes; JS also hides large fixed/sticky layers and siblings before/after the first/last block |
| Next-chapter detection (4 strategies) | Present | extract.js:167-200 | (1) `a[rel~=next], a.next, a[class*=next], a[id*=next]`; (2) link-text regex incl. `下一[章节頁页]?` and `次へ`; (3) href containing /next/; (4) numeric URL +1 sibling |
| Auto mode | Present | MainActivity.kt:1222-1244, 1259-1290 | Off by default (Prefs.kt:91-93); starts reading on extraction, on queue end advances and plays after 2.5 s (MainActivity.kt:1559); runaway guard 40 chapters (1562) |
| Append-as-you-go queue | Present | Reader.kt:169-176; MainActivity.kt:1210-1212 | `appendContent` keeps prior sentences, keeps title of first chapter unless overridden, resumes pump if playing |
| 404/Cloudflare guard before appending | Present | MainActivity.kt:1189-1202 | Title match on not found/404/just a moment/security verification/cloudflare/attention required; never appends such a page |
| Duplicate onPageFinished guard | Present | MainActivity.kt:692-713 | Same URL does not re-extract while playing; weak extraction (0–10 blocks) retries once; skip path reinstalls scripts + reader CSS only |
| Per-site extraction rules cache | Absent | — | Site dialog (1010-1057) writes the **global** `prefs.readerMode`, not a per-site value; no selector overrides |
| Extraction quality metric | Absent | — | `chars` is computed (extract.js:162-164) but discarded by WebExtractor.kt:34-58; no confidence gate |

### 2.2 Reader engine

| Capability | Status | Anchor | Evidence / limit |
|---|---|---|---|
| Sentence queue + cursor | Present | Reader.kt:127-133, 158-167 | Immutable list of `Block(text, blockIndex)`; cursor advances only when audio for that sentence has drained |
| Chunking | Present | SentenceSplitter.kt:28-119 | Enders `.!?;` + newline, closing-quote gather, abbreviation/decimal/initial guards (171-185); over-cap re-split prefers terminal punctuation → comma → space → next space, never mid-word; default 180 chars (Prefs.kt:167-168) |
| Short-fragment merge | Present | SentenceSplitter.kt:130-168; WebExtractor.kt:65-83 | MIN_FRAGMENT_CHARS = 24 (SentenceSplitter.kt:21); merges forward, then backward into previous group; punctuation-only groups dropped; merged block keeps the first fragment's `data-ns` |
| Prefetch / generation-ahead | Present | Reader.kt:305-339, 381-389 | Look-ahead `prefetch` 0–5 (default 4); second loop fills to 8 sentences while `bufferedSeconds() < 12f`; paused/woken every `PUMP_INTERVAL_MS = 500` |
| Engine switching | Present | Reader.kt:180-207 | One live engine; switching releases the old one, re-prepares asynchronously; DiagLog line `engine requested=` |
| Pause semantics | Present | Reader.kt:251-259; README.md:180-181 | Stop engine, bump generation, resume restarts the current sentence ("system TTS has no pause primitive; this is what all shipped readers do") |
| Seek / skip | Present | Reader.kt:280-303 | Clamps index, bumps generation, stops engine, re-pumps; Player epoch guards stale drain waits (PcmPlayer.kt:349-353) |
| State machine + listeners | Present | Reader.kt:107-122, 143-155 | `State(playing,title,cursor,total,engine,status)` broadcast to MainActivity + PlaybackService; `onQueueFinished()` hook for auto mode |
| Error recovery | Partial | Reader.kt:333-338, 341-350; SherpaEngine.kt:344-347 | Errors flip `playing=false` and stop the service, surface a status string; no retry/backoff/auto-engine-fallback at the Reader level |
| Engine switch keeps position | Present | Reader.kt:180-207 | Cursor untouched; but prepare blocks on 100 MB model load (no cached second engine) |
| Buffered-seconds telemetry | Present | TtsEngine.kt:31-36; PcmPlayer.kt:228-235 | `writtenFrames − playbackHeadPosition` converted to seconds |
| Persist reading position | Absent | MainActivity.kt:136, 688-689 | Only `last_url` in raw SharedPreferences; cursor/sentence index is in-memory. Cold start re-opens the URL but not the position |

### 2.3 TTS backing engines

| Capability | Status | Anchor | Evidence / limit |
|---|---|---|---|
| `TtsEngine` abstraction | Present | TtsEngine.kt:4-37 | prepare/speak/stop/release/voices/setVoice + default `bufferedSeconds()=0` for engines that cannot report |
| Android system TTS | Present | SystemTtsEngine.kt:75-123, 330-340 | `Locale.getDefault()` at init, all provider voices listed and selectable, speech rate 0.5–2.0 |
| System TTS rendered to PCM (media-key ownership) | Present | SystemTtsEngine.kt:22-38, 190-257, 264-309 | `synthesizeToFile` + WavReader decode/resample to 24 kHz; fallback to direct `speak()` if the provider rejects file synthesis (199-227, 312-328); 45 s latch (279) |
| System TTS voice/speed application | Present | SystemTtsEngine.kt:330-340 | Rate + voice applied per task; denoiser disabled for system path (110) |
| Neural Kokoro | Present | SherpaEngine.kt:209-251; Prefs.kt:194-206 | 11 English voices (af/af_bella/af_nicole/af_sarah/af_sky, am_adam/am_michael, bf_emma/bf_isabella, bm_george/bm_lewis), sid selectable in Settings; model.int8.onnx + voices.bin + tokens.txt + espeak-ng-data |
| Neural Pocket (zero-shot clone) | Partial | SherpaEngine.kt:212-223, 301-310; Prefs.kt:64-67 | Engine + GenerationConfig(referenceAudio) wired, default `test_wavs/bria.wav`; `prefs.pocketVoicePath` can point at a file but there is **no UI to import/record a clip** |
| ZipVoice (transcript cloning) | Absent | SherpaEngine.kt:241 | Config object constructed empty and never used; README.md:184-186 lists it as not wired |
| Streaming callback | Present | SherpaEngine.kt:86-123 | Contract 1=continue / 0=stop; epoch + stop checks inside the callback; first chunk `fadeIn` |
| Generation-ahead | Present | Reader.kt:305-339; SherpaEngine.kt:262-349 | Generation thread + monitor thread so n+1 synthesises while n plays |
| NNAPI / thread tuning | Present | SherpaEngine.kt:237-248; Prefs.kt:73-80 | Provider `nnapi`|`cpu`, threads clamped to core count (default 4); automatic CPU retry on init failure (172-182) |
| Speed control | Present | SherpaEngine.kt:301-302; SystemTtsEngine.kt:333; MainActivity.kt:1292-1297 | UI range 0.5–2.5× in 0.05 steps, **engines clamp to 0.5–2.0** → 2.05–2.5× is silently 2.0× (Inferred) |
| Saturation / babble guards | Present | SherpaEngine.kt:95-135, 99-106 | Rail-pinned chunk (>50 % at ±0.995) judged before writing and dropped; hard 45 s generation cap; garbage → advance without audio |
| Neural text safety | Present | TtsTextCleaner.kt:69-190; SherpaEngine.kt:279-298 | 11-step sanitiser; returns null → engine skips and advances; raw-text fallback only if it has alnum |
| Reference-clip preprocessing | Present | WavReader.kt:27-39; docs 10-19 | 8/16/24/32-bit + float decode → mono → 24 kHz linear → peak normalise → 80 Hz HPF → trim (480-sample, 0.02) → normalise → 30 s / 720,000 cap |
| Model download + verify + extract | Present | ModelManager.kt:107-173, 234-285 | Manual redirect following (max 5), size/format checks, SHA-256 pin, storage pre-check `need = size*2 + 64 MB` (114), tar.bz2 extract with truncation check (272-274) and 45 s stall watchdog (278-280) |
| Model delete / activate | Present | ModelManager.kt:175; SettingsActivity.kt:391-419 | Delete with confirmation dialog; "Use" sets active model and stops playback |
| Kokoro voice count | Present (11) | Prefs.kt:194-206 | Matches README ("11 English voices") |
| Other-language neural voices | Absent | — | Only `kokoro-int8-en-v0_19` (en-us) and English Pocket bundle are in the manifest (ModelManager.kt:38-59); multilingual would need different bundles + espeak locale wiring |

### 2.4 Audio pipeline

| Capability | Status | Anchor | Evidence / limit |
|---|---|---|---|
| Low-latency streaming AudioTrack | Present | PcmPlayer.kt:109-136 | USAGE_MEDIA + CONTENT_TYPE_SPEECH, mono 16-bit, MODE_STREAM, PERFORMANCE_MODE_LOW_LATENCY, max volume, ring = `max(minBytes≥4096, sampleRate*2)` = 1.0 s at 24 kHz (108) |
| Writer thread (non-blocking generation) | Present | PcmPlayer.kt:59-71, 277-331 | `write()` enqueues and returns; only the writer touches AudioTrack; epoch invalidates queued chunks after reset/stop |
| Output gain + limiter | Present | PcmPlayer.kt:32, 170-198 | `OUTPUT_GAIN=2.2`, per-chunk peak limit `0.98/peak`, gain falls instantly / rises 0.08 %/sample (~80 ms), hard clip guard |
| Sentence fades (declick) | Present | PcmPlayer.kt:78, 179, 217-221, 315-327; SystemTtsEngine.kt:345-354 | 6 ms fade-in per first chunk and 6 ms tail ramp from last sample to zero |
| Spectral denoiser + gate | Present | AudioDsp.kt:42-83, 160-187, 222-359 | 512/256 STFT, 96-frame (1.02 s) sliding minimum noise floor, DD Wiener with soft over-subtraction, gain floor 0.15, sqrt-Hann OLA, bootstrap at 10 % of frame 1, wet ramp 24 frames, downward expander open −38 dB / close −52 dB / floor 0.03 |
| Denoise policy per engine | Present | PcmPlayer.kt:47-57; SystemTtsEngine.kt:110; SherpaEngine.kt:170 | Enabled for neural, disabled for system TTS |
| Drain tracking for "heard" position | Present | PcmPlayer.kt:334-337, 349-353 | `playbackHeadPosition` vs `writtenFrames`; epoch abort prevents post-flush deadlock |
| Volume boost (user control) | Partial | PcmPlayer.kt:32, 177 | Fixed +2.2× limiter, not a user-adjustable boost; no per-voice normalisation beyond per-chunk peak |
| Skip silence | Partial | AudioDsp.kt:338-352; WavReader.kt:142-163 | Gate suppresses sub-speech residual; no explicit leading/trailing silence trim of generated sentences |
| Pitch control | Absent | — | No `PlaybackParams`/pitch usage anywhere in app/src |

### 2.5 Playback service / media

| Capability | Status | Anchor | Evidence / limit |
|---|---|---|---|
| Screen-off playback | Present | PlaybackService.kt:128-132, 188-207; Manifest:51-55 | PARTIAL_WAKE_LOCK `novelspeak:playback` acquired only while playing (6 h cap), released on pause/stop/destroy; foreground service type mediaPlayback |
| MediaSession + MediaStyle notification | Present | PlaybackService.kt:59-127, 454-527, 562-578 | Play/Pause/Prev/Next/Stop actions, compact view 0/1/2, VISIBILITY_PUBLIC, CATEGORY_TRANSPORT, low-importance silent channel `novelspeak.playback`, chapter title + `status · cursor/total` metadata |
| Lock-screen controls | Present | PlaybackService.kt:59-127 | Session active, metadata + PlaybackState(playing/paused/stopped/none) + speed |
| Headset / Bluetooth keys | Present | MediaButtonReceiver.kt:28-57; PlaybackService.kt:75-82, 265-285, 610-650 | API 31+ broadcast receiver on the session, pre-31 PendingIntent; manifest receiver covers restart; only ACTION_DOWN, repeat 0; 26+ limited to PLAY/PLAY_PAUSE/HEADSETHOOK |
| Audio focus | Present | PlaybackService.kt:44-51, 315-357 | AUDIOFOCUS_GAIN with willPauseWhenDucked; LOSS/LOSS_TRANSIENT pauses the reader; abandoned on pause/stop |
| Android Auto / car media browsing | Absent | — | No `MediaBrowserService`/`MediaLibraryService` component; MediaSession alone gives lock screen/shade/AVRCP but not Auto browse UI |
| Sleep timer | Absent | — | No timer, no end-of-chapter/fade-out stop |
| Service hygiene | Present | PlaybackService.kt:141-186, 211-214, 427-452 | START_NOT_STICKY; foreground-start failure stops itself; onTaskRemoved keeps a playing service, stops a paused one; explicit immutable PendingIntents (540-560) |
| Notification permission flow | Present | AppPermissions.kt:40-57, 119-132; MainActivity.kt:475-491, 1396-1410 | Asked once on first Play with content; denial is non-blocking and surfaces an inline hint + Settings status |

### 2.6 Ad blocking

| Capability | Status | Anchor | Evidence / limit |
|---|---|---|---|
| Network rule engine | Present | adblock/AdBlockEngine.kt:37-116, 394-536 | Token-indexed; host bucket → token bucket → regex 3-char bucket → fallback; `$important` short-circuit, any `@@` exception cancels |
| Filter parser | Present | adblock/FilterParser.kt:51-85, 108-232, 232-416 | ABP/uBO syntax incl. `||host^|`, `||…^$options`, regex, `badfilter` (FNV-1a canonical hash), `$domain=`, `$third-party`, methods, type options; unsupported options drop the rule instead of approximating |
| Cosmetic filtering | Present | AdBlockEngine.kt:222-298; content.js:64-118, 481-534 | Host-specific selectors + generic selectors requested by class/id through the bridge; batches of 120; `$elemhide`/`$generichide` policies; up to 4000 host / 800 generic selectors |
| Procedural filters | Present | content.js:141-297 | `:has-text()`, `:matches-css()`, `:upward()`, `:xpath()` and actions `:style`, `:remove`, `:remove-attr`, `:remove-class` |
| Scriptlets | Present | adblock/Scriptlets.kt:10-81 | 7 supported: set-constant, abort-on-property-read/-write, noeval, nowebworker, prevent-setTimeout/-Interval; `trusted-*` refused by design |
| Surrogates | Present | adblock/Surrogates.kt:12-75 | ga/gtag/dataLayer, googletagmanager, googlesyndication+doubleclick, fbq, apstag, taboola, outbrain, COMSCORE, quantserve |
| Popup / redirect behavioural blocking | Present | AdBlocker.kt:261-316, 335-351 | `$popup` rule for non-gesture main-frame; intent:/market:/app: always refused; suspicious-TLD set (24 TLDs) and ad-shaped query keys; same-site bypass via PSL; toast on block |
| Overlay / nag heuristics | Present | content.js:308-479; AdBlocker.kt:354-374 | Large fixed/absolute overlays (z-index ≥1000, ≥35 %×25 % viewport) with ad text/nested iframe; fake "Chrome Mobile WebView"/push nags incl. same-origin about:srcdoc iframes; JS alert/confirm/prompt suppression |
| window.open neutralisation | Present | content.js:560-575 | `window.open = () => null` + strips `target=_blank` on click (belt and braces with setSupportMultipleWindows(false)) |
| Public Suffix List | Present | adblock/PublicSuffixList.kt:25-104 | Bundled PSL (338,010 B), registrable domain + sameSite; immutable snapshot swapped on reload |
| Per-site bypass | Present | AdBlocker.kt:419-436; Prefs.kt:124-132 | Allowlist keyed by registrable domain; URL-bar "Ad" indicator toggles it (MainActivity.kt:371-380, 1367-1377) |
| List updates | Present | AdBlocker.kt:469-550 | 5 HTTPS sources to filesDir/adblock with size/format validation (507-517); bundled copies are the fallback; no signature check (documented) |
| Self-test | Present | adblock/AdBlockSelfTest.kt:25-95; docs/reader-fixes.md:142 | 22 deterministic assertions over inline rules (anchors, exceptions, third-party, domain, type, regex, document, cosmetic, scriptlet, PSL); on-device 22/22 |
| Engine scale | Present | docs/adblocker.md:63-70; verification.txt:30 | 122,884 network / 32,403 cosmetic / 4,612 scriptlet rules; 111,922 host buckets / 3,449 token / 43 regex / 151 fallback; JVM build ~380 ms, device 114–365 ms |
| Response-body / redirect-target / CSP filtering | Absent | docs/adblocker.md:203-205 | Platform limit of WebViewClient, documented rather than faked |

### 2.7 UI / settings / preferences

| Capability | Status | Anchor | Evidence / limit |
|---|---|---|---|
| Player card | Present | MainActivity.kt:403-543 | Status dot + text, engine chip, seek bar with sentence label, notification hint, Read / Next ch, −/+ speed, prev/play/next/stop |
| Seek bar semantics | Present | MainActivity.kt:546-568, 1315-1349 | Announces "Sentence n of N", survives interrupted drags (4 s watchdog), does not fight user drag (`seeking && isPressed`) |
| Address bar | Present | MainActivity.kt:176-316, 820-982 | Tap-to-expand, select-all, clear, cancel, recent suggestions, progress, reload/stop, overflow menu (Home/Reload/Copy/Share/Open in browser/Site settings/App settings) |
| Site dialog | Partial | MainActivity.kt:1010-1057 | Shows host + HTTPS state; "Reader mode" writes the **global** preference; "Block ads on this site" is genuinely per-site; changing ads reloads the page |
| Settings — engine/model/voice | Present | SettingsActivity.kt:181-230 | Two engine choices; per-model install/delete/use with ACTIVE badge; voice list for Kokoro (11) or Pocket (default narrator); system voices delegated to OS settings (213-217) |
| Settings — tuning | Present | SettingsActivity.kt:232-255 | Speed 0.5–2.5×, look-ahead 0–5, threads 1..cores, max sentence 80–400 chars; "Reset to tuned defaults" (161-172) |
| Settings — reading toggles | Present | SettingsActivity.kt:257-279 | NNAPI, highlight, auto-scroll, auto mode, reader mode |
| Settings — permissions & background | Present | SettingsActivity.kt:281-291, 753-863 | Notification + channel status with deep links, battery-optimisation entry point, explicit "no mic/camera/location/contacts" note |
| Settings — ad blocking | Present | SettingsActivity.kt:293-336 | Master/cosmetic/popup toggles, status, Update lists, Self-test |
| Recent URLs | Present | Prefs.kt:143-158 | 8 entries, http(s) only, newest first, used by address-bar suggestions |
| Onboarding / first-run | Absent | — | First launch goes straight to `DEFAULT_URL` (empirenovel, MainActivity.kt:1549); notification ask is deferred to first Play |
| Localisation | Absent | — | All UI strings are hardcoded English literals in Kotlin; no strings.xml/values-* resources |
| Themes | Partial | MainActivity.kt:1568-1579; SettingsActivity.kt:976-986 | One fixed dark palette duplicated in two private objects; reader theme is a single built-in dark CSS; no light/system theme |
| Dynamic type / font size | Absent | extract.js:219-227 | Reader CSS hardcodes 17 px/1.7; no user control, no `fontScale` respect |

### 2.8 Build, constraints, security posture

| Item | Status | Anchor | Evidence / limit |
|---|---|---|---|
| Framework-only (no AndroidX) | Present | build.sh:44-50; README.md:186-204 | Kotlin against `android.jar` API 34 only; deps: sherpa AAR classes, commons-compress, commons-io, kotlin-stdlib |
| On-device Termux build | Present | build.sh:1-119 | 7 steps: aapt2 link → unpack AAR jni → kotlinc → d8/R8 → zip dex+libs → zipalign → apksigner; debug keystore auto-created; secrets passed via env (KS_PASS/KEY_PASS) |
| API level | Present | Manifest:6 | minSdk 24, targetSdk 34, versionCode 15, versionName 1.1.0 |
| ABIs | Partial | build.sh:14; README.md:196-198 | Default arm64-v8a only; `ABI=armeabi-v7a` supported; x86_64 builds exist in build/ (dev only) — Play distribution would need per-ABI AAB or 32-bit support decision |
| APK size | Present | unzip of build/NovelSpeak-1.1.0-arm64-v8a.apk | 15,254,977 B total; dex 4,641,640 B; native libs 31,927,176 B (onnxruntime 22,249,560 B alone); assets 5,368,775 B; install footprint ~42 MB uncompressed |
| Permissions | Present | Manifest:8-13; docs/permissions-and-playback.md:10-24 | Only INTERNET, ACCESS_NETWORK_STATE, WAKE_LOCK, FOREGROUND_SERVICE(+MEDIA_PLAYBACK), POST_NOTIFICATIONS; RECORD_AUDIO removed; no storage/camera/location/contacts |
| Exported components | Partial | Manifest:22-63 | MainActivity exported (LAUNCHER, singleTask) and **MediaButtonReceiver exported=true with no permission** — any app can send it ACTION_MEDIA_BUTTON; PlaybackService/SettingsActivity non-exported |
| WebView hardening | Partial | MainActivity.kt:597-627 | JS/DOM storage on (needed), popups off, multiple windows off, `usesCleartextTraffic=false`, debug CDP only when debuggable; **but** `MIXED_CONTENT_COMPATIBILITY_MODE`, third-party cookies accepted, `addJavascriptInterface` bridge always attached, no explicit `setAllowFileAccess/ContentAccess(false)` |
| PendingIntent hygiene | Present | PlaybackService.kt:540-560 | Explicit component + FLAG_IMMUTABLE for notification actions and session activity (documented in docs/security-alignment-2026-10-22.md) |
| URL scheme allowlist | Present | UrlInput.kt:15-43 | Non-web schemes converted to search; evaluated by tools/url-input-test |
| WebView lifecycle | Absent | MainActivity.kt:1452-1457 | No `web.onPause()/onResume()/destroy()`, no `saveState/restoreState`; WebView timers keep running during screen-off playback and the WebView outlives the Activity |
| Backup policy | Partial | Manifest:20 | `allowBackup=true` with no backup rules → SharedPreferences and (subject to 25 MB Auto Backup quota) filesDir models are in backup scope |
| Crash reporting / analytics | Partial | Reader.kt:30-86; DiagLog.kt:15-31 | Crash → `/sdcard/Download/novelspeak-crash.txt` via MediaStore; rolling log `novelspeak-log.txt`; zero remote reporting, zero third-party SDKs |
| Tests / CI | Partial | tools/*/TestMain.kt; repo root | 4 host-JVM harnesses (adblock engine+self-test, AudioDsp, SentenceSplitter+TtsTextCleaner, UrlInput) + in-app self-test; **no instrumentation tests, no CI workflows, no Gradle project** |
| Model integrity | Present | ModelManager.kt:42-56, 131-137 | SHA-256 pinned for both bundles (PKI-free but deterministic); GitHub release URL only |
| Data exfiltration surface | Present (clean) | grep: no analytics/telemetry deps | Only network destinations: loaded pages, GitHub model release, easylist.to, raw.githubusercontent.com (uAssets) — Observed from AdBlocker.kt:96-102 and ModelManager.kt:38-59 |

---

## 3. Performance evidence (numbers from the repo; not re-measured here)

| Metric | Value | Anchor | Status |
|---|---|---|---|
| Noise floor p20, before → after DSP gate | **−51.6 → −80.3 dBFS** | docs/audio-quality.md:21-25 | Observed (repo doc, capture method documented at :5-8) |
| Speech level p50 / p90 (unchanged) | −22.6 / −11.3 → −23.3 / −11.9 dBFS | docs/audio-quality.md:24-25 | Observed (repo doc) |
| Host DSP regression | −46.6 dBFS noise bed attenuated **44.9 dB**, tones lose **0.04–0.05 dB**, ALL PASS | docs/audio-quality.md:29-30 | Observed (repo doc; harness at tools/audio-dsp-test) |
| Startup speech attenuation bug (fixed) | ~**14 dB** suppressed by denoiser bootstrap | docs/audio-quality.md:38-40; CHANGELOG.md:70-71 | Observed (repo doc) |
| sherpa callback truncation (before fix) | **238 / 269** generations ended at exactly **28,800 samples = 1.2 s** | docs/reader-fixes.md:14-15, 24-25; evidence log:14-20 | Observed (repo doc + log artifact) |
| After fix: first sentence | 80,002 samples (3.33 s) in **3,789 ms** | evidence log:22 | Observed artifact |
| After fix: later sentences | 10,233 / 11,663 / 11,540 / 5,249 / 11,814 / 5,049 ms for 305,385 / 248,726 / 231,743 / 100,480 / 218,822 / 92,383 cumulative-frame deltas | evidence log:22-29 | Observed artifact |
| Neural generation throughput | ≈**0.80–1.14× real time** (wall-seconds per audio-second), i.e. ~break-even on iqoo I2214 | computed from the rows above | Inferred |
| Extraction (empirenovel ch.67) | 39 blocks, 8,172 chars, **108 spoken sentences**, no nav noise, dotted obfuscation normalised | docs/reader-fixes-evidence/extraction-check.json; verification.txt:36-37 | Observed artifact (CDP on device) |
| Adblock engine | 122,884 network / 32,403 cosmetic / 4,612 scriptlet rules; build **114 ms** (API 35 emulator) / **365 ms** (iqoo); index ready <1 s after start | docs/adblocker.md:63-70, 173-181; verification.txt:30 | Observed (repo docs/logs) |
| Adblock coverage on device | "Ad 3/127" home, "Ad 0/125" chapter (125 cosmetic selectors applied) | docs/adblocker.md:179-190 | Observed (repo doc) |
| Self-test | 22/22 (in-app and JVM) | docs/reader-fixes.md:142; verification.txt:31 | Observed (repo doc + screenshot) |
| Audio buffer target | 12 s ≈ 288,000 frames ≈ 1.15 MB float, hard cap 8 sentences, pump 500 ms | Reader.kt:305-339, 381-389 | Observed + arithmetic (Inferred on MB) |
| AudioTrack ring | 1.0 s (48,000 B at 24 kHz mono 16-bit) or min buffer if larger | PcmPlayer.kt:102-108 | Observed |
| Denoiser cost | 512-sample FFT/256 hop ⇒ 46.9 frames/s at 24 kHz; 1 FloatArray alloc per write | AudioDsp.kt:42-45, 36-38 | Observed (design note) |
| APK / dex / native / assets | 15,254,977 B · 4,641,640 B · 31,927,176 B · 5,368,775 B | unzip + sha256sum, 2026-10-24 | Observed |
| APK SHA-256 (built artifact present in repo) | `8cf212f67ac969b61df4382efe5e8daf9ea4a70b07ea2007cc8fba823acda956` | sha256sum build/NovelSpeak-1.1.0-arm64-v8a.apk | Observed (not compared to the GitHub release asset) |

What the numbers imply for the roadmap:
1. **The streaming design is load-bearing, not decorative.** At ~1× real time, a 12 s buffer buys
   roughly 12 s of slack against a slow sentence; losing it (e.g. engine reload, seek) is audible.
   Anything that increases per-sentence synthesis cost (longer chunks, fp32 model, more DSP) eats
   that slack directly.
2. **Retrofit cost of persistence is tiny.** The queue/cursor already exist as plain data
   (Reader.kt:127-176); persisting them is a small, low-risk change with outsized user impact.
3. **The blocker for "more sources" is not extraction, it is ingestion plumbing.** Extracted blocks
   are already just `List<Block>`; EPUB/DOCX/clipboard only need a producer for that list.

---

## 4. Strengths (differentiators vs a typical TTS reader)

1. **Reads arbitrary pages, not just shared text.** Readability + density fallback + live-DOM
   re-tagging + four next-chapter strategies (extract.js:135-200) is a materially different product
   from "paste text into a TTS app", and it is the only part of the market the reference apps
   (HayaiTTS/NekoSpeak, per repo docs) never solved.
2. **Both engines own their audio, in-process.** Rendering system TTS to PCM (SystemTtsEngine.kt:22-38,
   190-257) is an unusual fix that makes headset keys, lock-screen controls and screen-off playback
   work identically on the zero-download backend. The root cause (audio-client UID vs MediaSession)
   is documented with dumpsys evidence (docs/media-controls.md:19-50).
3. **Real streaming pipeline with bounded memory.** Prefetch + 12 s / 8-sentence generation-ahead
   (Reader.kt:305-339) plus an async PCM writer (PcmPlayer.kt:59-71) hides first-audio latency and
   keeps the queue flat.
4. **Neural text safety derived from upstream evidence.** Quote glyphs removed pre-synthesis rather
   than gated in audio (TtsTextCleaner.kt:5-50), short-fragment merging for the known
   short-input failure regime, and an int8 rail-pinning detector (SherpaEngine.kt:125-135) — an
   unusually evidence-backed text pipeline for a hobby-scale app.
5. **Measured DSP, not vibes.** A hand-written 512-point STFT denoiser with minimum statistics,
   decision-directed Wiener gain and a floor that avoids musical noise (AudioDsp.kt:8-39), validated
   by a host regression and a documented −80.3 dBFS floor.
6. **A genuine ad-blocking engine in pure Kotlin.** Token+host indexed matcher, procedural filters,
   scriptlets, surrogates, PSL, deterministic self-test (docs/adblocker.md), no native dependency and
   no extension host.
7. **Hermetic, framework-only build.** 29 files, no AndroidX, no Gradle, no Play services, no
   telemetry; the app can be rebuilt on the phone it runs on (build.sh). Nothing phones home except
   GitHub model release + filter-list URLs.
8. **Verification culture that is rare at this scale.** Every hard-won number has a doc, a log
   artifact or a host harness (docs/evidence, tools/*), model hashes are pinned, and the ad blocker
   ships a self-test the user can run without adb.

---

## 5. Weaknesses and risks

### 5.1 Product gaps (highest user impact first)

1. **No reading position.** Cold start restores only `last_url` (MainActivity.kt:136, 688-689); the
   sentence cursor is in-memory. Android will kill the process during a long reading session.
2. **No library / history / bookmarks / notes.** Only 8 recent URLs (Prefs.kt:144-158). There is no
   model of "a book" or "a chapter", so nothing can be resumed, synced, or exported.
3. **Pause is a restart.** Documented (README.md:180-181) and consistent with peers, but it means
   long sentences replay from the start; with 180-char chunks that is up to ~12 s of audio.
4. **Reader mode is global, presented as per-site.** Site dialog item 0 writes `prefs.readerMode`
   (MainActivity.kt:1033-1036) — the UI implies per-site. This is a correctness/UX bug, not just a
   missing feature.
5. **Speed UI exceeds engine capability.** UI allows 2.5× (MainActivity.kt:1292-1297), engines clamp
   to 2.0× (SystemTtsEngine.kt:333; SherpaEngine.kt:302). Silent mismatch.
6. **No ingestion path from other apps.** No ACTION_SEND / ACTION_PROCESS_TEXT filter in the
   manifest (Observed: only MAIN/LAUNCHER at Manifest:28-40), so text a user already has elsewhere
   cannot be read.

### 5.2 Engineering debt

7. **Single live engine, no cache.** Every switch back to neural pays a 100 MB model load
   (Reader.kt:180-207). The reference apps keep an LRU of 2 (OPTIMIZATIONS.md:20, item 9); the doc
   describes the trade-off but the cold-switch cost is real.
8. **No WebView lifecycle.** No `onPause/onResume/onDestroy` on the WebView (MainActivity.kt:1452-1457):
   page timers/JS keep running while reading with the screen off (battery), and the WebView is never
   destroyed (leak on activity recreation). Likely a framework-only fix.
9. **Error recovery is terminal.** Engine failure sets `playing=false` and stops the service
   (Reader.kt:333-350); no retry, no automatic fallback to system TTS when a neural model is
   corrupt/missing at runtime.
10. **Doc/code drift (verified, worth fixing).** PcmPlayer.kt:17-22 claims
    `contentType=CONTENT_TYPE_MUSIC` and `bufferSize=max(pcmBytes,4096)`; the code uses
    `CONTENT_TYPE_SPEECH` (PcmPlayer.kt:113) and a 1.0 s ring (PcmPlayer.kt:108). OPTIMIZATIONS.md:38
    repeats the stale buffer claim. Similarly README.md:186-204 says sources open "anywhere", but the
    manifest still carries `package=` (removal is documented as manual).
11. **Per-line synchronous logging.** Every `DiagLog.line` opens/writes a MediaStore stream under a
    lock (DiagLog.kt:226-250); it is called from the generation loop at sentence start/end
    (SherpaEngine.kt:314, 332). Not fatal, but measurable I/O on the synthesis thread.
12. **No tests for the risky parts.** Reader/engine interaction, auto-advance, and PlaybackService
    have no automated coverage (tools/* only cover 4 pure modules). No CI at all (.github absent), so
    release regression evidence depends on a physically connected phone.
13. **Two hand-rolled design systems.** Palette duplicated (MainActivity.kt:1568-1579 vs
    SettingsActivity.kt:976-986); every screen builds views programmatically (no XML), which is fine
    but makes tablet/adaptive layout work more expensive later.

### 5.3 Security / privacy / policy risks

14. **Exported receiver without permission.** MediaButtonReceiver is `android:exported="true"` with no
    `android:permission` (Manifest:51-58). Any app can broadcast media-button intents at it; impact is
    currently bounded (it can start the playback service with PLAY/PLAY_PAUSE/HEADSETHOOK when content
    is loaded), but it is a needless attack surface and a Play policy reviewer question.
15. **WebView is permissive where it does not need to be.** `MIXED_CONTENT_COMPATIBILITY_MODE`
    (MainActivity.kt:605) and third-party cookies (614-616) are on for all sites; the `NSAdBlock`
    bridge is attached unconditionally (617-620). Page JS can reach `lookup` and `hidden`
    (rate-limited, JSON-schema-validated — AdBlocker.kt:440-460, AdBlockBridge.kt:14-53), so the
    blast radius is small, but "no JS interface on untrusted pages" would be stricter.
16. **`allowBackup=true` with no rules** (Manifest:20): prefs (incl. history-like recent URLs) and
    potentially model files enter Android Auto Backup. Easy to scope.
17. **No remote crash visibility.** The app writes crash text to shared Downloads (Reader.kt:48-86)
    only; users will not send it. Privacy-positive, diagnosability-negative — an opt-in "share log"
    action is a cheap middle ground.
18. **Neural model licensing/provenance.** Kokoro/Pocket bundles are fetched from an upstream GitHub
    release with pinned hashes (ModelManager.kt:42-56); there is no in-app licence display for the
    models or the 5 MB filter lists beyond NOTICE.txt on disk (assets/adblock/NOTICE.txt). Play data
    policy and Apache-2.0 redistribution obligations are currently satisfied only by repo files.

### 5.4 Single-device / environment constraints (context for feasibility scoring)

19. Verification was done on one API 35 emulator + one iqoo I2214 (Android 15); no data for
    foldables/tablets, Android 7–12 devices, or non-arm64 hardware (docs/reader-fixes.md:141-150).
20. Build needs hand-fetched `android.jar` (API 34 — aapt2 in Termux cannot read 35/36), a sherpa
    AAR, and R8 9.5.28 (README.md:158-180); it is reproducible but not one-command.
21. Model install is 98–103 MB over the network and 1–3 min of single-threaded bzip2 on-device
    (README.md:190-196); this constrains any "try neural by default" onboarding idea.

---

## 6. Capability gap table

Legend: **present** = implemented and reachable by the user; **partial** = implemented but limited,
hidden, or only one variant; **absent** = no implementation found in app/src or the page-side JS.
Anchors are the strongest evidence for the status.

| # | Capability category | Status | Anchor | Note |
|---|---|---|---|---|
| 1a | TTS engines / voices / languages / offline | **present** | TtsEngine.kt:4-37; SystemTtsEngine.kt:75-123; SherpaEngine.kt:144-199; Prefs.kt:194-206 | System TTS (device voices/languages) + Kokoro 11 EN + Pocket; fully offline after model install; speed 0.5–2.0× effective |
| 1b | Voice cloning | **partial** | SherpaEngine.kt:212-223, 301-310; WavReader.kt:27-39; Prefs.kt:64-67 | Engine accepts `referenceAudio` and a path preference exists, but there is no record/import UI; ships with bria.wav only. ZipVoice unwired (SherpaEngine.kt:241) |
| 2 | Sources: web / PDF / EPUB / DOCX / clipboard / email / OCR-camera | **partial** | MainActivity.kt:391-401, 597-778; Manifest:28-40 | Web only, via a real browser. No share-intent receiver, no file import, no clipboard read, no OCR. Texting/email sources would be ingestion until proven otherwise |
| 3 | Library / continue-reading / bookmarks / notes / highlights | **absent** | Prefs.kt:143-158; MainActivity.kt:136, 688-689 | Only 8 recent URLs + `last_url`; cursor not persisted; no bookmark/note model; `data-ns` indices exist (extract.js:64) but are never stored |
| 4 | Progress sync / account / cloud | **absent** | — (no network API calls beyond page/model/list downloads) | No account, no sync, no server. Privacy-positive; also means no cross-device resume |
| 5a | Speed / pitch | **partial** | MainActivity.kt:1292-1297; SystemTtsEngine.kt:333; SherpaEngine.kt:301-302 | Speed present (engine-clamped); pitch absent (no `PlaybackParams` anywhere) |
| 5b | Sleep timer / volume boost / skip silence | **partial** | PcmPlayer.kt:32, 170-198; AudioDsp.kt:338-352 | Fixed +2.2× limiter and a low-level gate exist; no user boost control, no explicit skip-silence, no sleep timer |
| 6 | Android Auto / car / lock-screen | **partial** | PlaybackService.kt:59-127, 454-527; MediaButtonReceiver.kt:28-57 | Lock screen, shade, Bluetooth keys, audio focus all work; no MediaBrowserService ⇒ no Android Auto browse/voice integration |
| 7 | Translation / dictionary | **absent** | — | Nothing in Kotlin or JS |
| 8 | Export audio | **absent** | — | Rendering exists in-memory only (PcmPlayer); no file writer, no MediaStore export, no MP3/Opus encoder |
| 9 | Per-site rules | **partial** | AdBlocker.kt:419-436; MainActivity.kt:1010-1057 | Ad-block allowlist is truly per registrable domain; reader mode toggle writes the global pref; no per-site extraction selectors/cache |
| 10 | Reading stats | **absent** | — | Only DiagLog lines; no counters, no daily totals, no UI |
| 11 | Accessibility | **partial** | MainActivity.kt:1521-1546 (contentDescription, 40–56 dp targets); extract.js:219-227 (fixed 17 px) | Reasonable labels/target sizes; no dynamic type, no reader typography controls, no TalkBack announcements for state changes, highlight is colour-only |
| 12 | Tablet / foldables | **absent** | MainActivity.kt:148-571 | Single-column phone layout, no window-size-class logic, no multi-pane (WebView + text side by side), rotation handled via configChanges only (Manifest:32) |
| 13 | Onboarding | **absent** | MainActivity.kt:128-138, 1549 | No first-run tour, no engine/model explanation before first Read, no permission priming beyond the Play-tap ask |
| 14 | Monetization | **absent** | LICENSE; no billing imports | Apache-2.0, no IAP/subscription/ads code paths |
| 15 | Crash reporting / analytics | **partial** | Reader.kt:30-86; DiagLog.kt:15-31 | Local file only (MediaStore Downloads / app external), no remote transport, no analytics SDK (privacy strength, support weakness) |
| 16 | WebView hardening / lifecycle | **partial** | MainActivity.kt:597-627, 1452-1457; Manifest:20 | Scheme allowlist, cleartext off, debug-gated CDP; but mixed content, 3rd-party cookies, always-on JS bridge, exported receiver, no WebView pause/destroy, allowBackup unscoped |
| 17 | Test automation / CI | **partial** | tools/*-test; .github absent | 4 JVM harnesses + in-app self-test; no instrumentation tests, no CI, no Gradle build |
| 18 | Offline reading cache | **absent** | MainActivity.kt:604 | `LOAD_DEFAULT` WebView cache only; no saved-chapter store (extracted text is discarded except in the live queue) |
| 19 | Localisation / i18n | **absent** | MainActivity.kt, SettingsActivity.kt (hardcoded literals) | English-only UI; no resources, no RTL consideration |
| 20 | Background/lifecycle robustness | **partial** | PlaybackService.kt:141-214; Reader.kt:209-232 | Foreground service survives task removal; but no state save/restore, no process-death recovery of queue/cursor |

---

## 7. Top 15 enhancement candidates (framework-only, Termux build)

Feasibility rules used: no AndroidX, no new heavyweight native deps (a pure-JVM library is
acceptable only if it is small, Apache/MIT, and dexable by the current pipeline); each candidate
names the exact code it would hook into; **S** ≈ under a day, **M** ≈ 1–3 days, **L** ≈ 1–2 weeks of
focused work on this codebase.

| Rank | Candidate | Where it hooks (anchor) | Size | Risk | Why it matters |
|---|---|---|---|---|---|
| 1 | **Persist reading position + "Continue reading"** | Prefs.kt (new keys: url/title/cursor/blockIndex/updatedAt); Reader.kt:158 setContent + :341 onSentenceDone (write-behind); MainActivity.kt:136 (restore) | S | Low — plain data, no engine changes | Closes the single biggest gap vs paid readers; cold start after process death currently loses the place |
| 2 | **Local library + history** (JSON file in filesDir or SharedPreferences set) | New `Library.kt`; MainActivity overflow (984-1008) + a list screen; Reader.kt state as source | S/M | Low-Med — storage only, but needs a simple screen and delete/mark-read semantics | Foundation for candidates 3, 9, 10; also the only way to survive Android's process reclamation |
| 3 | **Sleep timer with fade-out** (15/30/45/60 min, end-of-chapter) | PcmPlayer.kt:186 (gain ramp) + :355 pause; Reader pause/stop; MainActivity player card (403-543) | S | Low — one Handler + gain envelope | Bedtime listening is the core use case for a novel reader; competitors treat this as table stakes |
| 4 | **True per-site settings** (reader mode + extraction override + adblock) | Prefs.kt:124-132 pattern (registrable-domain key); MainActivity.kt:1010-1057 dialog; WebExtractor.kt:97 setReader; extract.js:343 `__nsReader` | S/M | Low — bug-fix-adjacent; migration of existing global pref | Fixes a misleading toggle (global write from a "site" dialog) and is roadmap item #2 |
| 5 | **Chapter prefetch** (extract next chapter while current plays) | MainActivity.kt:1277 loadNextChapter + :1170 extractNow; WebExtractor.kt:25-58; Reader.kt:169 appendContent | M | Med — second WebView lifecycle, memory, duplicate-finish interplay, adblock injection on a hidden view | Removes the 2.5 s + network gap at every chapter boundary (auto mode's weakest moment) |
| 6 | **Voice-clone import UI** (pick WAV → cleanup → preview → save) | SettingsActivity.kt:210-230 voice section; WavReader.kt:27 readForCloning; SherpaEngine.kt:201-207 loadReference; Prefs.kt:64 | M | Med — SAF picker, validation UX, quality expectations; must gate on Pocket installed | README roadmap #1; all the DSP/plumbing already exists (WavReader + GenerationConfig.referenceAudio) |
| 7 | **Share-sheet / clipboard text ingestion** (ACTION_SEND + ACTION_PROCESS_TEXT → Reader) | Manifest:28-40 (new intent-filter); MainActivity.kt:128-138 (handle intent); SentenceSplitter.kt:28 + WebExtractor.kt:65 merge | S | Low — reuses the existing `List<Block>` contract with synthetic block index 0 | Converts NovelSpeak from "browser only" to a reader for any app's text, at very low cost |
| 8 | **EPUB import** (zip + OPF/spine → chapter list → queue) | New `EpubImporter.kt` using `java.util.zip` + framework `XmlPullParser`; feed Reader.kt:158; needs candidate 2 for the book model | L | Med-High — EPUB parsing edge cases vs statement "no heavyweight deps" | Paid competitors' core; only worth doing after the library exists |
| 9 | **Bookmarks + highlight anchors from live blocks** (store `data-ns` + snippet, jump back) | extract.js:64 (data-ns), :402 __nsHighlight; Reader.kt:288 seek; new store | M | Low-Med — reuses block tagging; handling of re-extracted pages | Turns the existing read-along machinery into user-visible value; cheap once position persistence exists |
| 10 | **Volume boost + skip-silence + pitch** (user-facing) | PcmPlayer.kt:159 write (pre-write trim + gain ceiling), AudioTrack `setPlaybackParams` for pitch; Settings "Tuning" card (232-255) | S/M | Low-Med — AudioTrack params API 23+, trim must not clip words | Three settings users ask for in every TTS reader; most of the plumbing (gain, DSP) is already there |
| 11 | **Export spoken chapter to audio file** (WAV via PCM tap, then MediaStore) | PcmPlayer.kt:277-331 writer loop (tee to file); DiagLog.kt:48-86 pattern for MediaStore insert; Settings action | M | Med — disk size (24 kHz mono 16-bit ≈ 173 MB/h), no MP3/Opus encoder in-tree | README roadmap #5; users want offline audio; WAV is honest and framework-only |
| 12 | **Reading stats** (sentences/minutes per day, per site) | Reader.kt:341 onSentenceDone (counter); Prefs/JSON store; Settings readout | S | Low — counters only | Cheap retention feature; also gives the team real usage data without telemetry |
| 13 | **Android Auto / car support** (framework `MediaBrowserService`) | New service + manifest; reuse Reader.kt:107-122 state + PlaybackService.kt:59-127 session | M/L | Med (distribution: Auto needs Play review/category; sideloaded Auto needs developer mode) | Differentiator for commuters; MediaSession already provides most of the transport work |
| 14 | **Reader typography + accessibility controls** (font size/family/line-height/theme, live-region announcements, honour system font scale) | extract.js:212-227 (CSS vars), WebExtractor.kt:97 setReader(on, config); MainActivity.kt:1315-1349 (announce state); Settings "Reading" card | S/M | Low-Med — CSS injection surface is already there; must not break highlight offsets | Accessibility is the cheapest quality signal in this category; also a tablet prerequisite |
| 15 | **WebView lifecycle + backup/privacy hardening** | MainActivity.kt:597-627 configureWebView, :1443-1457 onResume/onDestroy; Manifest:20, 51-58 (allowBackup rules, receiver permission, mixed-content/cookie policy) | S | Low — mechanical, testable by inspection | Removes a WebView leak, cuts off-screen JS battery drain, and closes the exported-receiver/backup questions before any store listing |

**Deliberately deferred (real but lower value-per-risk now):** ZipVoice multilingual cloning
(SherpaEngine.kt:241 + a 100 MB+ bundle; roadmap #4 — the multilingual bundle choice is the actual
work, not the wiring); dictionary/translation long-press overlay (needs a data source decision);
two-pane tablet layout (only worth it after the library screen exists); full i18n extraction of
~350 Kotlin string literals (mechanical but noisy diff, better after UI freeze); remote crash
reporting (needs an endpoint + privacy policy, contradicts the current zero-telemetry posture).

**Recommended sequence if only three land first:** 1 → 2 → 4. They are the smallest changes that
convert "a clever prototype" into "an app you can live in", and each is independent of the others.

---

## 8. Unknowns (explicitly not verifiable from this repo)

| Item | Why unknown |
|---|---|
| Whether the shipped GitHub release asset is byte-identical to `build/NovelSpeak-1.1.0-arm64-v8a.apk` | No release-asset download/hash comparison was performed (off-scope, and no network fetch to github.com was needed) |
| Real-device behaviour on Android 7–12, foldables, tablets, 32-bit ABI | Only emulator API 35 + iqoo I2214 (Android 15) evidence exists in the repo |
| Whether `synthesizeToFile` works on the majority of OEM TTS providers | Fallback exists (SystemTtsEngine.kt:199-227), but no fleet data; the app logs it |
| Memory ceiling of WebView + model + 12 s buffer on low-RAM devices | Not measured anywhere in the repo |
| Actual user-facing latency (tap Read → first audio) end-to-end | Not logged as a single metric; only per-generation times |
| Translation/dictionary feasibility without new deps | Framework-only options exist (WebView overlay / ACTION_PROCESS_TEXT / system translator APIs vary by OEM) but were not investigated in depth |

---

## 9. Sources appendix

All entries accessed **2026-10-24** (Asia/Calcutta). "Supports" names the claim the source backs.
Because this is an internal audit, sources are repository artifacts; each was read in full unless a
line range is given.

| Source (path) | Supports |
|---|---|
| README.md | Product scope, install/build, architecture diagram, known limits, roadmap (lines 1-202) |
| OPTIMIZATIONS.md | Provenance of tuning constants; reference-app claims (lines 1-58) |
| CHANGELOG.md | 1.1.0/1.0.0 feature + fix inventory, measured audio improvement (lines 1-108) |
| build.sh | Toolchain, 7 build steps, ABI/min/target, debug/release signing (lines 1-119) |
| app/AndroidManifest.xml | Permissions, exported components, service/receiver config, allowBackup, cleartext (lines 1-64) |
| app/src/app/novelspeak/*.kt (29 files, 9,746 lines) | All code claims, anchors cited inline |
| app/src/app/novelspeak/adblock/*.kt (8 files, 1,859 lines) | Engine/parser/PSL/scriptlets/surrogates/self-test claims |
| app/assets/extract.js (424 lines) | Extraction, reader CSS, highlight, next-link, obfuscation claims |
| app/assets/adblock/content.js (597 lines) | Cosmetic/procedural/overlay/nag/window.open claims |
| app/assets/readability.js (2,786 lines) | Vendored Mozilla Readability (Apache-2.0 header at lines 1-16) |
| app/assets/adblock/{easylist,easyprivacy,ublock-*}.txt, public_suffix_list.dat, NOTICE.txt | Bundled list identity/size (5.14 MB) and licensing pointer |
| docs/adblocker.md | Engine scale (122,884/32,403/4,612), WebView platform limits, matching precedence, device stats (lines 1-227) |
| docs/audio-quality.md | Noise-floor measurement method and before/after numbers (lines 1-59) |
| docs/reader-fixes.md | Callback-bug evidence (238/269 at 1.2 s), extraction counts, splitter changes, verification (lines 1-150) |
| docs/media-controls.md | Media-key root cause (AOSP rules), fix, on-device verification table (lines 1-122) |
| docs/permissions-and-playback.md | Permission inventory, notification flow, background policy (lines 1-102) |
| docs/tts-text-normalization.md | Reproduction (40/73 sentences unsafe), upstream issue citations, sanitizer steps, guards (lines 1-124) |
| docs/security-alignment-2026-10-22.md | PendingIntent/permission hardening diffs (lines 1-140) |
| docs/reader-fixes-evidence/verification.txt, audio-and-extraction.log, extraction-check.json, merge-audit.md, *.png | On-device numbers (apk hashes/sizes, generation frames/times, extraction 39 blocks/8,172 chars, self-test 22/22, screenshots) |
| tools/{adblock-test,audio-dsp-test,tts-cleaner-test,url-input-test}/TestMain.kt | Existence and scope of host-side regression harnesses |
| build/NovelSpeak-1.1.0-arm64-v8a.apk (unzip + sha256sum) | Artifact facts: size, entry list, dex/native/assets breakdown, SHA-256 |

**External references cited by repo docs, not independently fetched here** (status: Unknown at
source; do not quote them as first-hand in downstream work without fetching):
hexgrad/kokoro issues #325 and #365; k2-fsa/sherpa-onnx issues #3176 and #3754 (all referenced in
docs/tts-text-normalization.md:36-50 and TtsTextCleaner.kt:9-27). Competitor claims in
OPTIMIZATIONS.md (HayaiTTS 2.5.1 GPL-3.0, NekoSpeak 1.4.2 MIT) are the RE stream's evidence, not
re-verified in this audit.

---

## Appendix A — APK facts (Observed, 2026-10-24)

```
build/NovelSpeak-1.1.0-arm64-v8a.apk
sha256  8cf212f67ac969b61df4382efe5e8daf9ea4a70b07ea2007cc8fba823acda956
size    15,254,977 bytes (14.5 MiB)
entries 20, uncompressed total 41,947,675 bytes
  classes.dex                                 4,641,640
  lib/arm64-v8a/libonnxruntime.so            22,249,560
  lib/arm64-v8a/libsherpa-onnx-jni.so         4,771,760
  lib/arm64-v8a/libsherpa-onnx-c-api.so       4,465,168
  lib/arm64-v8a/libsherpa-onnx-cxx-api.so       440,688
  assets/extract.js                              18,188
  assets/readability.js                          89,980
  assets/adblock/*.txt|dat|js|NOTICE          5,260,587
  AndroidManifest.xml 4,488 · resources.arsc 40 · META-INF 5,556
```

## Appendix B — audit method

```
# inventory
find app/src -name '*.kt' | wc -l            # 29
find app/src -name '*.kt' -exec wc -l {} +   # 9746 total
wc -l app/assets/extract.js app/assets/adblock/content.js app/assets/readability.js
unzip -l build/NovelSpeak-1.1.0-arm64-v8a.apk ; sha256sum …
# reads: every .kt file, extract.js, content.js, build.sh, manifest, all docs, all evidence logs
# no source edits, no commits; only docs/competitive-analysis/{novelspeak-audit.md,data/novelspeak.json} written
```

*End of audit. Companion machine-readable file: [data/novelspeak.json](data/novelspeak.json).*
