# Lawful APK Reverse-Engineering Teardown — competitor TTS readers

**Stream:** APK RE (task-4) · **Analyst:** apk-re · **Date:** 2026-10-24 · **Working tree:** uncommitted (no commits, no app-source edits)
**Scope:** static analysis of freely distributable builds of competitor apps, for interoperability and competitive research. No paywall/licence/DRM bypass, no credentials, no modified APKs, no redistribution.

---

## 1. Verdict first

| App | APK obtained | Depth | Single most important finding for NovelSpeak |
|---|---|---|---|
| **@Voice Aloud Reader 40.9.1** | ✅ official dev site, 73.7 MB | Deep (manifest, 6 dex, assets, res) | Extraction is **WebView + injected JS bundle** (`ExtractBrowser.min.js`, `GetDisplayedHTML.min.js`, `LoginWallDetect.min.js`) — and it ships a **BYO-API-key cloud-TTS matrix** (Google/Azure/Polly/OpenAI/OpenAI-compatible/xAI) plus a **remotely-signed model catalog**. |
| **NekoSpeak 1.5.0** | ✅ GitHub Release, 94.5 MB | Deep (APK + full upstream source) | It is a **system TTS engine** (`TextToSpeechService`), not a reader: 5×ONNX Pocket-TTS + Piper + Kokoro behind `android.intent.action.TTS_SERVICE`, with **Kotlin Misaki G2P** and bundled `espeak-ng` data. |
| **Moon+ Reader free 4.5.4** | ✅ Aptoide public listing, 12.8 MB | Medium (manifest, dex census) | A **file-based** reader with no extraction at all: 6 permissions, no MediaSession, legacy Play Billing v1 AIDL, Radaee PDF SDK. Free build is stale (4.5.4) vs Play (updated 2026-10-17). |
| **Legado (io.legado.app.release)** | ❌ Unknown | Metadata + upstream only | **Upstream repo was legally emptied on 2026-05-27**; releases/F-Droid/Aptoide all gone. Uptodown still lists "Legado 3.25". Strong legal signal: do **not** clone its book-source rule engine. |
| **Audify** | ❌ Unknown | Play metadata only | Play-only distribution; no lawful APK channel found (dev site has no APK link; Aptoide/Uptodown 404). |
| **Speechify / ElevenReader** | ❌ Not attempted | — | Play-only; no lawful public APK channel identified. Marked Unknown rather than sourced from mirror sites of unknown provenance. |

---

## 2. Method, tools, and what was deliberately not done

**Toolchain (all local, verified this session):** `apktool 3.0.3` (~/.local/bin), `aapt2 36.0.0` (~/Android/Sdk/build-tools), `jadx 1.5.6` (installed, not needed — apktool+smali sufficed), `unzip`, `sha256sum`, `apksigner`, `git`, `curl`. `garlic`/DuckDB call-graph was **not** used (duckdb download stalled; not required for the findings below).

**Legality boundary applied:**
- Only builds from the **developer's own site**, **GitHub Releases**, or **public store listings** (Aptoide API). No APKPure-style mirrors, no cracked/modded builds, no Play-store APK scraping.
- No account creation, no API keys exercised, no paywall/DRM/licence code touched. The one DRM artefact observed (`liblcp.so`, Readium LCP in @Voice) was **recorded, not exercised**.
- Only short string/number excerpts are quoted here; no decompiled code bodies are copied into the repo. Full decompiled trees stayed in `/tmp/re-work/`.
- **@Voice is ad-supported with a paid "Premium License"**; its APK is the free build offered by the developer. Moon+ Reader free is exactly the free package. **Moon+ Reader *Pro* was deliberately NOT downloaded** even though `moondownload.com` exposes `mrp_*.apk` — that is the paid product.

---

## 3. Acquisition ledger (Observed)

| App | Package | versionName / code | sha256 | Bytes | Channel |
|---|---|---|---|---|---|
| @Voice Aloud Reader | `com.hyperionics.avar` | 40.9.1 / 1640090140 | `7f48c57afe3376013f028f4902a114fb73a075e193ee88faba598482f2f02cbb` | 73,697,675 | `https://hyperionics.com/downloads/atVoice/atVoice.apk` |
| Moon+ Reader (free) | `com.flyersoft.moonreader` | 4.5.4 / 454001 | `e71fab6821ef8c8a497a668210a05ee44e5602e04b6a06165531dcd0ace6abeb` | 12,812,004 | Aptoide API `ws75.aptoide.com/api/7/app/get/package_name=com.flyersoft.moonreader` → `pool.apk.aptoide.com/appupdater/...apk` |
| NekoSpeak | `com.nekospeak.tts` | 1.5.0 / 17 | `0108360a8aafa07cbd769839a0436deda0d348ff7e5b9214261691febc2bbc64` | 94,472,186 | `https://github.com/siva-sub/NekoSpeak/releases/download/v1.5.0/app-arm64-v8a-release.apk` |
| Legado | `io.legado.app.release` | — (Uptodown lists 3.25) | — | — | **Unknown** — see §7 |

Evidence: `evidence/re/{atvoice,moonreader,nekospeak,legado,audify}/`.

> Note on methodology: a first NekoSpeak download was resumed with `curl -C -` and came out 1,032,192 bytes larger than the GitHub asset size, with a corrupted zip local-header offset. It was discarded and re-downloaded cleanly (94,472,186 B = GitHub asset size exactly). **Only the clean hash is reported.**

---

## 4. @Voice Aloud Reader 40.9.1 — `com.hyperionics.avar`

### 4.1 Identity and fingerprint (Observed)

| Property | Value | How |
|---|---|---|
| versionName / versionCode | **40.9.1** / 1640090140 | `aapt2 dump badging` |
| minSdk / targetSdk / compileSdk | **26 / 37 / 37** | `aapt2`, `apktool.yml` |
| DEX files | 6 × `classes*.dex` | `unzip -l` |
| Application class | `com.hyperionics.avar.TtsApp`, `largeHeap=true`, `allowAudioPlaybackCapture=true` | manifest |
| Native libs (4 ABIs each) | `libHyperionics_avar.so` (app core), `libHyperionics_pdf.so` (PDF text), `libEbookConv.so` (ebook conversion), `liblame.so` (**MP3 encode**), `liblcp.so` (**Readium LCP DRM**), `libcrashlytics*.so`, `libapplovin-native-crash-reporter.so`, `libunitycoherencenative.so`, `libvxwuyvyac.so` (unidentified, no HTTP/TTS strings) | `unzip -l` |
| UI framework | Jetpack Compose (Material3 + `m3expressive` resources) on AndroidX AppCompat | `apktool.yml` `doNotCompress` version markers |
| HTML stack | **jsoup — 313 classes**; custom `VsWebView`; **OkHttp3 — 321 classes**; Cronet provider string declared | smali census |
| Obfuscation | **Partial**: app packages kept (`com.hyperionics.*`, 107 classes in `avar`), but shared/UI code is R8-flattened to `a.smali`, `b1.smali`, `j69.smali`, `qn.smali` … | smali listing |

### 4.2 Manifest findings (Observed)

**Permissions (27 declared, unique):** `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `FOREGROUND_SERVICE_DATA_SYNC`, `WAKE_LOCK`, `POST_NOTIFICATIONS`, `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `MANAGE_EXTERNAL_STORAGE`, `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`, `QUERY_ALL_PACKAGES`, `RECORD_AUDIO`, `BLUETOOTH`, `BLUETOOTH_CONNECT`, `VIBRATE`, `READ_BASIC_PHONE_STATE`, `ACCESS_LOCAL_NETWORK`, `com.android.vending.CHECK_LICENSE`, `com.google.android.gms.permission.AD_ID`, `ACCESS_ADSERVICES_{AD_ID,ATTRIBUTION,TOPICS}`, `com.amazon.privacypass.ATTEST`.

**Services:**

```xml
<service android:name="com.hyperionics.avar.SpeakService"
         android:exported="true" android:enabled="true"
         android:foregroundServiceType="mediaPlayback"/>
<service android:name="com.hyperionics.PdfNativeLib.PdfExtractService"
         android:process=":pdfReader" android:foregroundServiceType="dataSync"/>
<service android:name="com.hyperionics.avar.ReaderSearchRegexService"
         android:process=":readerSearch"/>
```

→ **Playback is a foreground `mediaPlayback` service**, mirroring NovelSpeak's design. Unusual: it is `exported="true"` (NovelSpeak's `PlaybackService` is `exported="false"`, `app/AndroidManifest.xml:46-54`). PDF extraction is isolated in its own `:pdfReader` process with `dataSync` FGS type — a pattern directly applicable to NovelSpeak (see §10).

**Receivers:** `MediaButtonIntentReceiver` (exported=true but `enabled="false"` in manifest, presumably toggled at runtime), `BluetoothConnectReceiver` (exported), `SleepTimerAlarmReceiver`, `PackageReplacedReceiver`, `DeskWidget.AvarWidget` (exported widget).

**Providers (own):** `androidx.core.content.FileProvider`, plus **`com.hyperionics.ai.AiUsageProvider`** and **`com.hyperionics.ai.AiCredentialsProvider`** — dedicated content providers that expose AI key/usage state internally.

**Exported activities:** `StartupActivity` (+ aliases), `SaveOnlyActivity` ("add to list" share target), `SpeakReferenceActivity` (launcher, `singleTask`), `DropboxSync.DropboxAuthCallbackActivity`, `SpeakActivity` via reference activity.

### 4.3 Extraction approach — WebView + injected JS bundle (Observed)

This is the closest thing to NovelSpeak's architecture in the corpus, and it validates the approach:

- Java side: `ExtractBrowserActivity`, `ExtractBrowser$RlJavaCallback`, `ExtractBrowser$SandboxJavaCallback`, `ContentLoaderBrowser$RlJavaCallback`, `VsWebView`, `JavaCallback` — i.e. a **hidden/sandboxed WebView whose JS calls back into Java** with extracted text.
- JS side (**`assets/js/`**, sizes from `ls -la`):
  `ExtractBrowser.min.js` 13.7 KB · `GetDisplayedHTML.min.js` 10.6 KB · `PageTurnCapture.min.js` 16.5 KB · **`LoginWallDetect.min.js` 18.5 KB** · `CaptureReader.min.js` · `CaptureBrowser.min.js` · `ReplaceIframes.min.js` · `UnicodeWords.min.js` 14.1 KB · `FindRegEx.min.js` · `docReady.min.js` 12.5 KB · `Boldify.min.js` · `WpsDocumentImport.js` · `mark.min.js` 16.9 KB (highlighting) · `jquery-3.6.3.min.js` 89.9 KB.
- Server-side parse: jsoup (313 classes) for files/EPUB and post-processing.
- Site-specific filters ship as data: `assets/Filters/` (`WhatsAppChat.json`, `WhatsAppChat-replace.txt`) and `filtnote.html` — i.e. **a tiny rule pack, not the Legado-style global rule DSL**.

**Reading between the lines (Inferred):** "RlJavaCallback" + "SandboxJavaCallback" indicate they run page JS in a *separate sandbox WebView* from the one the user sees, and pass extracted HTML to Java for jsoup cleanup. `LoginWallDetect.min.js` is consistent with the user-facing string `login_required_short = "Login or subscription may be required"` — they **detect paywall/login walls and warn** instead of bypassing. That is exactly the lawful behaviour NovelSpeak should copy.

### 4.4 TTS architecture — system TTS + BYO-key cloud TTS + optional native (Observed strings)

`com.hyperionics.CloudTts` (15 classes) is the cloud TTS client. Per-provider key screens are literally separate activities:

```
CloudTts/{CloudTts, GogleApiKeyActivity, OpenAiApiKeyActivity,
          OpenAiCompatApiKeyActivity, PollyApiKeyActivity,
          XaiApiKeyActivity, ZureApiKeyActivity}.smali
```

Verbatim resource strings (res/values/strings.xml):

| key | value (verbatim, ≤25 words) |
|---|---|
| `hts_vmgr_am_polly_cloud` | "Use Amazon Polly voices (paid service)" |
| `hts_vmgr_ms_azure_cloud` | "Use Microsoft Azure voices (paid service)" |
| `hts_vmgr_xai` | "Use xAI Grok voices (paid service)" |
| `hts_openai_compat_key` | "Enter the address of your OpenAI-compatible TTS server. Only the server URL is required; the API key is optional" |
| `hts_openai_key` | "…it is stored only on this device (it is not included in @Voice backups)" |
| `ai_provider_google_cloud` | "Google Cloud" |

So @Voice ships **six cloud TTS back-ends, all user-funded BYO key**, next to the device's system TTS engine. It does **not** bundle a neural TTS model (contrast: NovelSpeak's Kokoro/Pocket via sherpa-onnx, `README.md:11-12`).

**Remote model catalog (Observed, `assets/CloudTtsConfig.txt` header comments):**
> "; @Voice cloud TTS model catalog, schema 1. Official provider endpoints stay in app code."
> "Signing uses SHA1withRSA and avarcfg, over UTF-8 trimmed nonempty/non-comment lines + LF."
> "Unknown providers/protocols and minAppVersion gates allow future catalog growth"
> "OpenAI's October 1, 2026 deprecation notice confirms January 6, 2027 for tts-1, tts-1-hd"

→ They **update the model/voice catalog over the air as a signed text file** (`.changetimes.txt` refresh), with `enabled:false`/`shutdownOn` to retire models gracefully and `minAppVersion` gates for old clients. This is a genuinely strong pattern for NovelSpeak's `ModelManager.kt` (today: hashes pinned in code, `README.md:122`).

**Other TTS/reading mechanics (Observed strings/packages):** `ttssetup/VoiceSelectorActivity`, `speech/{roman,textproc,voicepatch}`, `SetupRecordActivity`, `DlgVoices/{VoicesActivity,ApplyVcActivity}`, `utillib/CldWrapper` (compact-language-detection for auto language), `res/raw/` ships **58 per-language `*_js`** text-processing resources (`en_js`, `en_gb_js`, `el_polyton_js`, `hi_js`, …) — i.e. their own per-language normalisation rules, comparable to NovelSpeak's `docs/tts-text-normalization.md` and `TtsCleaner`. Sleep timer (`SleepTimerAlarmReceiver`), auto-scroll follow-read (`follow_read = "Auto-scroll the sentence read aloud into view"`), and PDF line-box highlighting while reading (`pdf_line_boxes`).

### 4.5 AI features (Observed strings) — the "PDF → clean EPUB" angle

`com.hyperionics.ai` (5 classes) + `assets/assistants.json`:
- `ai_intro`: "Convert this PDF to a clean, well-structured EPUB using an AI model — usually a much better reading and listening experience than the original PDF." (`ai_intro` is longer; this is the leading clause)
- `ai_billing_reminder`: "Uses your AI account — billed by the provider."
- `ai_blocked_note`: provider content filters can refuse pages ("can happen on perfectly ordinary text").
- `assets/assistants.json` lists external AI assistants to hand text to: `me.proton.android.lumo`, `com.openai.chatgpt`, `com.anthropic.claude`, Google — with per-app capability flags (`pastePrompt`, `pasteText`, `extraText`).
- Anthropic key supported (`ai_anthropic_key_label = "Anthropic API key"`).

### 4.6 Monetization, ads, trackers (Observed class census)

| SDK | Classes | Note |
|---|---|---|
| `io.bidmachine` | **1,663** | ad mediation |
| `com.applovin` | **1,360** | AppLovin MAX (+ native crash reporter .so) |
| `com.unity3d.ads` | **1,297** | Unity Ads |
| `com.vungle` | **668** | Vungle |
| `com.google.android.gms.ads` | 99 | AdMob |
| `com.google.firebase.crashlytics` | 10 | crash reporting |
| `com.google.android.gms.measurement` | 19 | Google Analytics for Firebase |
| `com.dropbox` | 200 | Dropbox cloud sync |

**Monetization = one-time ad removal, not a subscription.** Verbatim: `upgrade = "Remove ads!"`, `ad_free = "Support the developer and enjoy @Voice without ads. Buy @Voice Premium License!"`, `upgrade_note` (bolded in source) states purchasing removes *all* ads including banners. Licence gate: `com.android.vending.CHECK_LICENSE` + `InvalidPremiumLicenseActivity`; strings `invalid_lic_message`, `invalid_lic_checking_note`. Cloud/AI features are **not** gated by subscription — they are gated by the user's own provider account.

**Cloud sync:** Dropbox only, folder `/Apps/@Voice` (`cloud_add_info1`), plus **Pocket** article import (`add_from_pocket = "Sync articles with Pocket"`) and a desktop "Add to List" browser-extension workflow (`cloud_add_info2`).

### 4.7 Reliability engineering worth stealing (Observed class names)

`ProcessExitDiagnostics` with 8 dedicated exceptions: `ExcessiveResourceExitException`, `HistoricalAnrException`, `HistoricalNativeCrashException`, `InitializationExitException`, `MemoryLimiterExitException`, `PlaybackLowMemoryException`, `PlaybackSignalException`, `ResourceAnomalyExitException`. **NovelSpeak has `DiagLog.kt` only.** Distinguishing "playback died because the OS killed us for memory" from "we crashed" is the difference between a fixable bug report and a mystery.

---

## 5. NekoSpeak 1.5.0 — `com.nekospeak.tts`

### 5.1 Identity (Observed)

| Property | Value |
|---|---|
| versionName / versionCode | **1.5.0 / 17** |
| minSdk / targetSdk / compileSdk | **26 / 35 / 35** |
| Released | **2026-04-15** (GitHub API `releases`) — note: later than the "v1.4.2" baseline in the task brief |
| Native libs (arm64-v8a only for this asset) | `libnekospeak.so`, `libttsespeak.so` (eSpeak-NG JNI), `libonnxruntime.so` (**16,276,816 B**), `libonnxruntime4j_jni.so` |
| Bundled assets | **371 `espeak-ng-data` files** (`*_dict`, `voices/!v/*`) + `cmudict_korean.txt`, `cmudict_phonemes.txt` |
| Permissions | `FOREGROUND_SERVICE`, `INTERNET`, `ACCESS_NETWORK_STATE`, `RECORD_AUDIO`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` |
| Ads/analytics SDKs | **none** (no AdMob/Firebase/analytics packages in the dependency set) |

### 5.2 It is a TTS **engine**, not a reader (Observed — APK manifest + source)

```xml
<service android:name=".NekoTtsService" android:exported="true">
  <intent-filter><action android:name="android.intent.action.TTS_SERVICE"/></intent-filter>
  <meta-data android:name="android.speech.tts" android:resource="@xml/tts_engine"/>
</service>
<activity android:name=".CheckVoiceData" android:exported="true">   <!-- CHECK_TTS_DATA -->
<activity android:name=".GetSampleText" android:exported="true">   <!-- GET_SAMPLE_TEXT -->
<activity android:name=".InstallVoiceData" android:exported="true"><!-- INSTALL_TTS_DATA -->
```

`class NekoTtsService : TextToSpeechService()` at `app/src/main/java/com/nekospeak/tts/NekoTtsService.kt:37`. Strategy is: **be the device's TTS engine**, and let every reader app (Moon Reader, @Voice, …) drive you. **That is the exact opposite of NovelSpeak's in-app engine.**

### 5.3 Engine matrix and on-device inference (Observed source+APK)

| Engine | Provider class | Model stack | Where models come from |
|---|---|---|---|
| **Pocket-TTS** (recommended, zero-shot clone) | `engine/pocket/PocketTtsEngine.kt` | 5 ONNX sessions: `mimi_encoder.onnx` 73 MB, `text_conditioner.onnx` 16 MB, `flow_lm_main_int8.onnx` 76 MB, `flow_lm_flow_int8.onnx` 10 MB, `mimi_decoder_int8.onnx` 23 MB | HuggingFace `KevinAHM/pocket-tts-web` (`HF_BASE`), voices from `kyutai/tts-voices` |
| **Piper** (bundled Amy-Low default) | `engine/piper/PiperEngine.kt` + `EspeakWrapper.kt` | ONNX + **eSpeak-NG** for phonemes (`libttsespeak.so`) | `rhasspy/piper-voices` on HuggingFace; one Tamil dataset-backed voice |
| **Kokoro v1.0** | `engine/KokoroEngine.kt` | `kokoro-v1.0.int8.onnx` + `voices-v1.0.bin` | GitHub Release `v1.0.0` of NekoSpeak itself |
| **Kitten TTS Nano** | — | `kitten_tts_nano_v0_1.onnx` (~23 MB) | GitHub Release `v1.0.0` |

Text front-end: **Misaki G2P re-implemented in Kotlin** — `engine/misaki/{G2P,Lexicon,Num2Words,TokenContext,MToken}.kt`, bridged to Piper IPA by `engine/piper/MisakiToPiperIPA.kt`. Cloning path: `cloneVoice(audioPath, voiceName)` at `PocketTtsEngine.kt:1526`, with `MimiCodec.kt` (encode/decode), `GtcrnDenoiser.kt` (GTCRN speech denoiser for recorded clips), `ui/components/AudioRecorder.kt` + `ui/screens/VoiceRecorderScreen.kt`.

**Delta vs the task brief (Observed):** the brief said "repo docs describe v1.4.2". Confirmed — `RELEASE_NOTES.md`'s newest heading is still `# Release v1.4.2 - Clone Stability & In-App Diagnostics`, while GitHub ships **v1.5.0 (2026-04-15)**. Therefore **v1.5.0's changes are undocumented in-repo**; treat any v1.5.0 feature claim as Unknown except what the APK itself proves (identity, manifest, libs, assets above). One documented ADR does exist for the 32-bit crash class: `docs/adr/ADR-005-sigbus-fix-armv7-int8-models.md` — root cause "Memory-mapped model loading caused unaligned memory access on ARMv7", fixed by byte-array loading on 32-bit ARM.

**Retention/diagnostics design worth copying** (`RELEASE_NOTES.md` v1.4.2): support report = `meta.json` + `prefs_snapshot.json` + `events.log` + `last_crash.txt`, capped at 10 archives / 20 MB / 14-day TTL, `events.log` rolling 1 MB, shared via FileProvider.

---

## 6. Moon+ Reader free 4.5.4 — `com.flyersoft.moonreader`

**Identity (Observed):** versionName **4.5.4** / code 454001; **minSdk 14, targetSdk 26**; 2 dex; native `libimagepipeline.so` (Fresco) + `librdpdf.so` (**Radaee PDF**, confirmed by `com/radaee` package).

**Freshness caveat (Observed):** this is the free package as carried by Aptoide; the Play listing for the same package says **"Updated on Oct 17, 2026"**. So the mirror build is **stale** — anything below describes the 4.5.x free line, not necessarily today's Play build. This is a real competitive fact: **Moon+ free still ships a targetSdk-26 APK on public mirrors**, which means it has not adopted modern FGS/notification/permission models in that lineage.

**Manifest (Observed):** only **six** permissions — `INTERNET`, `ACCESS_NETWORK_STATE`, `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`, `GET_ACCOUNTS`, `com.android.vending.BILLING`. No `POST_NOTIFICATIONS`, no FGS types, no wake lock. Services: `BookDownloadService` (no `foregroundServiceType`), plus GMS sign-in `RevocationBoundService`. Receivers: `OpenFile_Receiver` (exported), `AnalyticsReceiver` (legacy Google Analytics, exported).

**Architecture (Observed):**
- **No extraction at all.** No WebView browser, no readability, no network fetch of articles. It opens local files and OPDS catalogs (`com/flyersoft/{books,components,material,moonreader,opds,staticlayout}`).
- **No MediaSession, no foreground playback service.** `grep -rl MediaSession com/flyersoft` → empty. TTS is driven from `ActivityTxt` (only two classes reference `android.speech.tts.TextToSpeech`), with an inlined `A$TtsLine` helper. → **TTS keeps running only while the activity is alive / relies on the system TTS service**; no explicit media-playback FGS.
- **Billing:** legacy Play Billing **v1 AIDL** (`IInAppBillingService`, `IInAppBillingService$Stub`) — the `com.flyersoft.moonreaderp` Pro package is the paid tier (`moondownload.com/download.html` lists Pro benefits: "Ad-free", "Shake the phone to speak ( TTS engine support)").
- **Network:** no first-party hosts. Only third-party lookup hosts appear as literals: `dictionary.com/browse/`, `search.yahoo.com`, `images.search.yahoo.com`, `baike.baidu.com/search`, `pic.sogou.com/pics`, `app.qq.com`, plus `opds-spec.org` namespace URIs. → dictionary/image lookup, no cloud sync of its own (Dropbox/Facebook SDKs are present for legacy integrations).

**Relevance to NovelSpeak:** Moon+ is the *incumbent that NekoSpeak was written to serve* — its free build has no neural TTS and no offline playback service, which is precisely the wedge NovelSpeak occupies.

---

## 7. Legado — `io.legado.app.release`: **upstream was taken down** (Observed)

This is the single most legally consequential finding of this stream, and it changes the roadmap input.

- GitHub API: `repos/gedoor/legado/releases` → **no releases**; `releases/latest` → **HTTP 404**.
- Cloning the repo gives a **two-file repo**: `README.md` + `公告链接.png` ("announcement link"). Last commit `9bb0569` — **2026-05-27**, message "公告" (announcement).
- The README (verbatim, Chinese) begins:
  > "# 公告"
  > "本项目涉及侵权行为的违法，也为此承担了相应的法律责任，在此郑重发布公告，删除项目内容并规劝所有人："

  (Translation, mine: "The project's conduct involved unlawful infringement, and we have borne the corresponding legal responsibility. We hereby solemnly announce: the project's content is deleted, and we urge everyone…") It continues: "请立刻停止一切侵权相关行为，不要再效仿、从事、传播各类侵权活动" — "immediately stop all infringement-related conduct; do not imitate, undertake or disseminate any kind of infringing activity." It links a **阅文 (China Literature) IP-protection announcement** (`mp.weixin.qq.com/s/bcTbqBQA1T0YoRwq76xcWQ`).
- F-Droid API `io.legado.app.release` → `{"error":"NOT_FOUND"}`; Aptoide API → HTTP 404.
- Uptodown **still lists it**: `https://legado.en.uptodown.com/android/versions` → `<title>Download Legado 3.25 for Android | Uptodown.com` (HTTP 200). Its download button is JS/token-gated; no APK was fetched inside the timebox → **APK analysis: Unknown**.

**Why I did not chase the APK further (Inferred, deliberate):** the takedown was expressly about the **book-source / scraping rule engine** — the exact feature Legado was famous for — and a mirror build cannot be attributed to a lawful distribution channel. Static analysis would be lawful, but its *value* is low next to the *risk* signal. The competitive lesson is not the code; it is the **liability model**.

**Roadmap consequence (Inferred, high confidence):** NovelSpeak must **not** ship a Legado-style JSON "book source" / rule-DSL engine that scrapes arbitrary novel sites, and must not market "read any novel site for free". Extraction should stay generic (readability + density heuristics on a page the *user* opened) + paywall/login **detection** (the @Voice `LoginWallDetect.min.js` pattern), never site-specific bypass rules.

---

## 8. Audify / Speechify / ElevenReader — APK: Unknown

**Audify (`com.tangerinesoftwarehouse.audify`) — Observed Play metadata only** (`curl` of the Play page, `hl=en&gl=US`, 2026-10-24): title "Audify read aloud web browser"; description "Read aloud (text to speech, TTS) web pages, web novels, and ebooks."; "Updated on Aug 29, 2026". The developer site `tangerinesoftwarehouse.com/download/` contains **no APK link** (Play-only). Aptoide API → HTTP 404. Uptodown slug → HTTP 404. **No lawful APK channel found → fingerprint, manifest, TTS/extraction internals all Unknown.**

**Speechify (`com.cliffweitzman.speechify2`) and ElevenReader (`io.elevenlabs.readerapp`)** — Play-only, no lawful public APK channel identified this session. **Not attempted further** rather than risk a mirror of unknown provenance. Marked Unknown.

*(If the Lead wants these, the lawful substitute is Play listing + vendor docs + app-store review corpus, which the paid-intel stream is better placed to do.)*

---

## 9. Cross-app architecture matrix (Observed)

| Capability | **NovelSpeak 1.1.0** | @Voice 40.9.1 | NekoSpeak 1.5.0 | Moon+ 4.5.4 free | Legado |
|---|---|---|---|---|---|
| Role | WebView reader + TTS | Reader + TTS | **TTS engine only** | File reader | Novel reader |
| Article extraction | WebView + **Mozilla Readability** + density fallback (`WebExtractor.kt:11-12`) | WebView + injected JS bundle (`ExtractBrowser*.js`) + jsoup | none | **none** | rule DSL (removed upstream) |
| Paywall/login handling | — | **detect + warn** (`LoginWallDetect.min.js`) | n/a | n/a | bypass-oriented (illegal) |
| System TTS | ✅ `SystemTtsEngine.kt` | ✅ + `SpeakService` | ❌ is the engine | ✅ (activity-scoped) | ✅ |
| Bundled neural TTS | ✅ sherpa-onnx Kokoro/Pocket (on-demand) | ❌ | ✅ ONNX ×4 engines | ❌ | ❌ |
| Cloud TTS | ❌ | ✅ **6 BYO-key providers** | ❌ | ❌ | ✅ (some builds) |
| FGS media playback | ✅ `exported=false`, `mediaPlayback` (`AndroidManifest.xml:46-54`) | ✅ `exported=true`, `mediaPlayback` | n/a | ❌ | ✅ |
| MediaSession | ✅ (`README.md:13`) | ✅ | n/a | ❌ | ✅ |
| Media-button receiver | ✅ `MediaButtonReceiver` exported | ✅ (runtime-enabled) | n/a | headset keys = Pro | ✅ |
| Ad blocking | ✅ EasyList/EasyPrivacy/uBlock + cosmetic (`AdBlocker.kt`, `adblock/`) | partial ("disable ad blocker" string; no EasyList engine) | n/a | ❌ | ✅ |
| Read-along highlight | ✅ | ✅ + PDF line boxes | n/a | ❌ | ✅ |
| Offline cache | ? (app-audit stream) | ✅ files/EPUB/PDF + MP3 export (`liblame`) | model cache | ✅ local files | ✅ |
| Cloud sync | ❌ | Dropbox `/Apps/@Voice` + Pocket | ❌ | legacy Dropbox SDK | ❌ |
| Monetization | none (Apache-2.0) | Ads ×5 networks → **one-time** ad removal | none (OSS) | legacy IAP + Pro tier | none |
| OTA-updatable catalog | ❌ (hashes in `ModelManager.kt`) | ✅ **signed model catalog** | model list in code | n/a | book sources (illegal) |

---

## 10. Adopt / Avoid for NovelSpeak

### ADOPT (ranked by effort→impact)

1. **Signed remote model/voice catalog** (@Voice `CloudTtsConfig.txt` pattern). NovelSpeak pins SHA-256 in `ModelManager.kt`; @Voice ships a *signed* plain-text catalog refreshed at runtime, with `minAppVersion` gates, `enabled:false`/`shutdownOn` retirement and graceful "your saved model was retired" recovery. **Impact:** lets you add/retire Kokoro/Pocket models and voices without an app release, and stops pinning you to a dead upstream URL when sherpa-onnx renames a release. **Effort:** M (one signed file + parser + refresh).
2. **Paywall/login-wall detection with a non-bypass warning** (@Voice `LoginWallDetect.min.js` + `login_required_short`). NovelSpeak today extracts whatever Readability returns; a page behind a login yields garbage or partial text. Detecting it and saying "Login or subscription may be required — open this page in NovelSpeak's browser and log in there" is **lawful, honest, and better UX**. **Effort:** S.
3. **Playback-death diagnostics taxonomy** (@Voice `ProcessExitDiagnostics`). NovelSpeak's `DiagLog.kt` is flat. Adding distinct exit reasons for "OS killed us", "low memory during playback", "audio signal loss", "ANR history" turns user bug reports into actionable data. **Effort:** S–M.
4. **Isolate heavy extraction in a separate process with a `dataSync` FGS** (@Voice `:pdfReader` / `PdfExtractService`). WebView extraction is the most likely OOM source in NovelSpeak; putting it in `:extract` keeps playback alive. **Effort:** M.
5. **Per-language normalisation resources** (@Voice ships 58 `res/raw/*_js`). NovelSpeak has `docs/tts-text-normalization.md` + a cleaner; the packaging pattern (language-keyed data, not code) is worth matching as you add languages. **Effort:** S.
6. **Support-report bundle with hard caps** (NekoSpeak `SupportReportManager`): `meta.json`/`prefs_snapshot.json`/`events.log`/`last_crash.txt`, 10 archives / 20 MB / 14-day TTL, 1 MB rolling log, FileProvider share. **Effort:** S. Pairs with #3.
7. **BYO-key cloud TTS as an *optional* tier** (@Voice's 6 providers). If NovelSpeak ever adds cloud voices, the @Voice answer is: **no subscription, user's own key, key never leaves the device, excluded from backups**. That is also the cleanest legal posture. **Effort:** M–L. Do not build before #1/#2.
8. **"Add to list" share target + overflow launcher aliases** (@Voice `SaveOnlyActivity` + `@Voice Add to List` browser extension → Dropbox). A share-target activity that silently appends a URL to the queue is a cheap acquisition feature. **Effort:** S.

### AVOID

1. **Do not build a book-source / rule-DSL scraping engine.** Legado's upstream was legally emptied in 2026-05-27 precisely over this. Even a generic "any site" scraper with per-site rules invites the same treatment. Keep extraction generic + detection-based.
2. **Do not add ad mediation SDKs merely to ship free.** @Voice carries **≈5,000 ad-mediation classes** (BidMachine 1,663 + AppLovin 1,360 + Unity 1,297 + Vungle 668 + GMS 99) for a one-time ad-removal IAP. That is a permanent APK-size, privacy and attack-surface tax. NovelSpeak's zero-SDK Apache-2.0 posture is a *feature* — market it.
3. **Do not let `MANAGE_EXTERNAL_STORAGE` / `QUERY_ALL_PACKAGES` in** (@Voice has both). `Queries` snippets are the modern Play-policy-safe substitute.
4. **Do not copy `exported="true"` on the playback service.** @Voice exposes `SpeakService`; NovelSpeak correctly keeps `exported="false"` (`app/AndroidManifest.xml:48`). Keep it, and add `android:permission` if you ever must export.
5. **Do not chase targetSdk 26 laziness** (Moon+ free line). NovelSpeak is targetSdk 34; the FGS/notification restrictions are here to stay.
6. **Do not depend on an unmaintained single upstream URL for models** (NekoSpeak's models are split across GitHub releases, HuggingFace datasets and even a HuggingFace *Space* — a fragile supply chain). Catalog + mirror + hash (#1).

---

## 11. Licensing / legal notes per artefact

| Artefact | Licence / status | What we may do | Note |
|---|---|---|---|
| @Voice APK | Proprietary, free (ad-supported) + paid ad-removal | Static analysis for interoperability; quote short excerpts | Do not extract code/assets into NovelSpeak. `liblcp.so` = Readium **LCP DRM**; do not exercise. Regex/JS extraction logic is **not** reusable code. |
| NekoSpeak APK + repo | **MIT License** (observed: `LICENSE` in repo, "Copyright (c) 2026 NekoSpeak Team") | Read/summarise; the *architecture* (TextToSpeechService + ONNX engines) is standard Android, freely re-implementable | Its Kokoro/Pocket ONNX models are **third-party** (Kyutai, HF) with their own licences — NovelSpeak's sherpa-onnx path already has a different (cleaner) model supply. |
| Moon+ free APK | Proprietary | Static analysis | Radaee PDF is a **commercial** SDK; do not adopt. |
| Legado | Upstream **deleted** amid an infringement action | Do not redistribute, do not reuse rules | Legal red flag, not a code source. |
| Audify | Proprietary, Play-only | Nothing beyond public metadata | APK Unknown. |

> General rule applied throughout: **architecture and UX patterns are facts and are fair to learn from; code, assets, rule packs and model weights are not.** Nothing decompiled was copied into this repo — only file names, class counts, manifest lines and short resource strings, all reproducible from the public artefacts.

---

## 12. Unknowns (explicit)

1. **Legado APK internals** — not obtained (upstream deleted; Uptodown download JS/token-gated). Its book-source DSL, TTS pipeline and cache design are **Unknown from the artifact**; only metadata + the takedown are Observed.
2. **Audify, Speechify, ElevenReader internals** — no lawful APK channel identified → framework, extraction technique, TTS path all **Unknown**.
3. **@Voice's bundled "TTS native" role** — `TtsNativeLib/{D2TWrapper,EbookConverter}` and `libHyperionics_avar.so` exist, but whether any on-device neural voice synthesis happens in-app is **Unknown** (no model assets, no voice-weight files were found; `res/raw` holds text rules, not weights). Current read: system TTS + cloud TTS only (**Inferred**).
4. **`libvxwuyvyac.so`** — present in all 4 ABIs, no HTTP/TTS strings, random-looking name. **Unknown** (likely an ad-SDK helper or anti-tamper). Not investigated further.
5. **@Voice exact cloud request wire formats / endpoints** — "Official provider endpoints stay in app code" per their own comment; endpoints were not extracted (would require deeper smali work than the timebox allowed).
6. **Install counts / revenue** — not extracted reliably from Play HTML this session. **Unknown**; not estimated.
7. **NekoSpeak v1.5.0 changelog** — absent from `RELEASE_NOTES.md`; **Unknown** beyond APK-observed facts.
8. **Moon+ free current Play build** — the analysed APK is Aptoide's 4.5.4; the Play build updated 2026-10-17 may differ.

---

## 13. Reproduction commands (exact, for spot-check)

```bash
# ---------- acquisition ----------
curl -sL -A 'Mozilla/5.0' -o /tmp/apks/atvoice-universal.apk   https://hyperionics.com/downloads/atVoice/atVoice.apk
curl -s "https://ws75.aptoide.com/api/7/app/get/package_name=com.flyersoft.moonreader"   # -> file.path = APK url
curl -sL -o /tmp/apks/neko-fresh.apk   https://github.com/siva-sub/NekoSpeak/releases/download/v1.5.0/app-arm64-v8a-release.apk
sha256sum /tmp/apks/*.apk

# ---------- identity ----------
~/Android/Sdk/build-tools/36.0.0/aapt2 dump badging  <apk> | head -5
~/Android/Sdk/build-tools/36.0.0/aapt2 dump permissions <apk>
~/Android/Sdk/build-tools/36.0.0/apksigner verify --print-certs <apk>

# ---------- decode ----------
apktool d -f <apk> -o /tmp/re-work/<app>-apktool
# manifest / components
grep -oE '<service[^>]*|<receiver[^>]*|<provider[^>]*' /tmp/re-work/atvoice-apktool/AndroidManifest.xml
grep -oE 'foregroundServiceType="[^"]*"' /tmp/re-work/atvoice-apktool/AndroidManifest.xml | sort -u
# SDK / package census (per directory)
for p in com/applovin com/unity3d/ads io/bidmachine com/vungle org/jsoup okhttp3; do   echo -n "$p "; find /tmp/re-work/atvoice-apktool/smali* -type f -path "*/$p/*" | wc -l; done
# strings that carry the feature story
grep -E 'hts_vmgr_|ai_provider_|ai_intro|login_required_short'   /tmp/re-work/atvoice-apktool/res/values/strings.xml
head -20 /tmp/re-work/atvoice-apktool/assets/CloudTtsConfig.txt
ls -la /tmp/re-work/atvoice-apktool/assets/js/

# ---------- NekoSpeak: APK vs source ----------
unzip -l /tmp/apks/neko-fresh.apk | grep -E 'espeak-ng-data' | wc -l      # 371
unzip -p /tmp/apks/neko-fresh.apk classes.dex | strings | grep -i onnx | head
git clone --depth 1 https://github.com/siva-sub/NekoSpeak /tmp/re-work/nekospeak-src
grep -n 'fun cloneVoice' /tmp/re-work/nekospeak-src/app/src/main/java/com/nekospeak/tts/engine/pocket/PocketTtsEngine.kt
grep -nE 'HF_BASE|ModelFile(' /tmp/re-work/nekospeak-src/app/src/main/java/com/nekospeak/tts/data/ModelRepository.kt
head -1 /tmp/re-work/nekospeak-src/RELEASE_NOTES.md        # -> v1.4.2 (delta vs 1.5.0 release)

# ---------- Legado: the finding is the repo, not the APK ----------
gh api repos/gedoor/legado/releases/latest        # HTTP 404
git clone --depth 1 https://github.com/gedoor/legado /tmp/re-work/legado && ls -A /tmp/re-work/legado
cat /tmp/re-work/legado/README.md                 # takedown announcement, 2026-05-27
curl -s https://f-droid.org/api/v1/packages/io.legado.app.release   # {"error":"NOT_FOUND"}
```

---

## 14. Sources

All URLs accessed **2026-10-24** (Asia/Calcutta). "Supports" = which claim it backs.

| # | URL | Supports |
|---|---|---|
| 1 | https://hyperionics.com/atVoice/ | @Voice office download page exposing `/downloads/atVoice/atVoice.apk` (APK page listing: universal, arm64-v8a, x86_64) |
| 2 | https://hyperionics.com/downloads/atVoice/atVoice.apk | @Voice 40.9.1 artefact actually analysed (HTTP 200, 73,697,675 B) |
| 3 | https://play.google.com/store/apps/details?id=com.hyperionics.avar&hl=en&gl=US | @Voice Play listing: description "Text-to-speech for PDFs, eBooks & web. Translate and listen in many languages"; "Updated on Oct 24, 2026" |
| 4 | https://play.google.com/store/apps/details?id=com.flyersoft.moonreader&hl=en&gl=US | Moon+ Reader Play listing: "EBook reader with rich features."; "Updated on Oct 17, 2026" (freshness delta vs mirror APK) |
| 5 | https://play.google.com/store/apps/details?id=com.tangerinesoftwarehouse.audify&hl=en&gl=US | Audify Play listing: "Read aloud (text to speech, TTS) web pages, web novels, and ebooks."; "Updated on Aug 29, 2026" |
| 6 | https://www.moondownload.com/download.html | Moon+ developer download page: Pro benefits list ("Ad-free", "Shake the phone to speak ( TTS engine support)"), Pro distributed via Play (`com.flyersoft.moonreaderp`) — basis for NOT downloading Pro |
| 7 | https://ws75.aptoide.com/api/7/app/get/package_name=com.flyersoft.moonreader | Moon+ free APK 4.5.4 (vercode 454001, 12,812,004 B) public listing used for acquisition |
| 8 | https://github.com/siva-sub/NekoSpeak/releases | NekoSpeak v1.5.0 assets (arm64 94,472,186 B), published 2026-04-15 |
| 9 | https://github.com/siva-sub/NekoSpeak (source @ main) | `NekoTtsService : TextToSpeechService`, engine classes, `ModelRepository.kt` URLs, `cloneVoice()`, `RELEASE_NOTES.md` stopping at v1.4.2 |
| 10 | https://github.com/gedoor/legado | Repo reduced to `README.md` + `公告链接.png`; commit `9bb0569` "公告" 2026-05-27; README takedown text |
| 11 | https://mp.weixin.qq.com/s/bcTbqBQA1T0YoRwq76xcWQ | 阅文 (China Literature) IP-protection announcement linked from the Legado README (linked, not independently fetched) |
| 12 | https://legado.en.uptodown.com/android/versions | Uptodown still lists "Legado 3.25" (page title) — the only surviving distribution claim; download JS/token-gated |
| 13 | https://f-droid.org/api/v1/packages/io.legado.app.release | `{"error":"NOT_FOUND"}` — no F-Droid build |
| 14 | https://codeload.github.com/siva-sub/NekoSpeak/zip/refs/heads/main | NekoSpeak source snapshot used for the architecture evidence file |
| 15 | Local repo: `README.md:11-13,26,65,122`, `app/AndroidManifest.xml:7-14,46-62`, `app/src/app/novelspeak/WebExtractor.kt:11-12` | NovelSpeak side of every comparison row (Readability extraction, FGS mediaPlayback, exported=false, EasyList ad blocking, pinned model hashes) |

**Evidence files in this repo:** `docs/competitive-analysis/evidence/re/{atvoice,moonreader,nekospeak,legado,audify}/` — hashes, manifest excerpts, package censuses, string excerpts, source-architecture extract, upstream-status snapshot. Raw work dirs (`/tmp/apks`, `/tmp/re-work`, `/tmp/competitive/apk-re`) are intentionally not committed.
