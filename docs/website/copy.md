# Website copy deck

Status: round 1, final strings. Evidence ids refer to `brief.md` § Claims ledger.
Voice: plain declaratives, specific numbers, British prose spelling, sentence case, no exclamation
marks, no marketing adjectives without a number behind them.

## Voice card (one line)

Write like a careful person who built the thing and respects your time: say what it does, say what
it costs, say what it will not do.

## Never

powerful · seamless · revolutionary · AI-powered · effortless · unlock · elevate · supercharge ·
game-changing · your new reading companion · immersive experience · take control of · "simply" ·
"just" as a minimiser · exclamation marks · "we believe" · invented users, ratings, quotes, awards.

---

# `/` — landing

<title>NovelSpeak — read web novels aloud on Android</title>
<meta description>An Android reader that extracts the chapter you are on and speaks it with voices that run on your phone. Screen-off playback, no account, no ads, no telemetry.</meta>

**Header**: wordmark `NovelSpeak` · Features · Voices · Changelog · GitHub · `Download the APK`

**Hero** (`data-enter`)
- eyebrow (mono): `ANDROID 7.0+ · ARM64 · APACHE-2.0`
- h1: **Read web novels out loud.**
- lead: It opens the chapter you are on, extracts the text, and speaks it with voices that run on
  your phone. Screen off. No account, no ads, no telemetry.
- primary: `Download the APK` → GitHub release asset v1.3.0
- secondary: `See what it does` → `./features/`
- fact line (mono, small): `v1.3.0 · 15.5 MB · arm64-v8a · SHA-256 published`
- plate: `reader-540.png`, alt: "The NovelSpeak reader. The sentence being spoken is highlighted on
  the page, with the player card and its progress bar underneath."

**Dock** (three facts, one row, still)
- `No account` — nothing to sign in to.
- `No ads or analytics` — no advertising ID, no crash SDK.
- `Voices on the device` — after one download, no network.

**01 — Read**
- h2: **The chapter, not the chrome.**
- body: NovelSpeak is a browser with one job. Open a chapter from the shortcut grid, your library,
  or any HTTPS link, and it extracts the main text with Readability, falling back to a
  text-density pass when a site has its own idea about layout. Navigation, ad slots and cookie
  strips stay out of the queue.
- specs: `Extraction` Readability + density fallback · `Shortcuts` Royal Road, Scribble Hub,
  WebNovel, Wattpad, Project Gutenberg, Wikipedia · `Reader mode` per site, one switch ·
  `Paywalls` detected, never bypassed
- plate: `home-540.png`, alt: "The NovelSpeak home screen: wordmark, search field and the shortcut
  grid of novel sites, with Continue reading below."

**02 — Listen**
- h2: **Four neural voices, downloaded once.**
- body: Use Android's own voice for instant offline playback in every language the phone already
  ships, or install a neural voice. Kokoro, Supertonic 3, Pocket and ZipVoice run on the phone after
  a one-time, SHA-256 verified download. Nothing is bundled in the APK.
- rows (five, compact; full comparison on `/voices/`):
  - **Supertonic 3** · `123 MB` · Two curated styles from twenty. The fast default once installed.
  - **Kokoro** · `103 MB` · Two curated English voices from eleven. Audiobook pacing, about 3× slower.
  - **Pocket TTS** · `98 MB` · Clones a voice from a short reference clip.
  - **ZipVoice** · `104 MB + 52 MB vocoder` · Cloning with a verbatim transcript, Chinese and English.
  - **Android system TTS** · `0 MB` · Every language the device supports, no download.
- link: `Which voice should I pick?` → `./voices/`

**03 — Read-along** (paper section, the dealt moment)
- h2: **The sentence you hear is the sentence you see.**
- body: Word-level highlighting follows the voice inside the page itself, and the page scrolls with
  it. Long sentences break at punctuation, and the next chapter is extracted in a hidden view so
  playback does not wait.
- paper page (public domain):
  > Alice was beginning to get very tired of sitting by her sister on the bank, and of having
  > nothing to do: once or twice she had peeped into the book her sister was reading, but it had no
  > pictures or conversations in it, “and what is the use of a book,” thought Alice, “without
  > pictures or conversations?”
  - source line: `Lewis Carroll, Alice's Adventures in Wonderland (1865). Public domain, via Project Gutenberg.`
- specs: `Highlight` word-level · `Scroll` follows the voice · `Prefetch` next chapter, hidden view ·
  `Pause` resumes at the start of the current sentence

**04 — Away from the screen**
- h2: **Pocket the phone. It keeps reading.**
- body: A foreground service, a wake lock and a media session keep the chapter going with the screen
  off. Wired and Bluetooth buttons move by sentence, play and pause. The sleep timer counts minutes
  or stops at the end of a chapter.
- specs: `Screen off` yes · `Headset buttons` play, pause, next, previous · `Sleep timer` minutes or
  end of chapter · `Auto mode` continues chapter to chapter, off by default
- film plate: the reader still fills a 16:9 stage, an overlay `Play the film` button sits on it, and
  `<video preload="none" controls hidden>` swaps in on the press; line above: `Real recording ·
  23 s · sound on`; link under the frame: `Watch the 23-second film — MP4, 1.9 MB, sound on.`
  No poster attribute: the only supplied poster states 9:16 while the film is 16:9.
- plates: `library-540.png` alt "The library: search, All / Chapters / Imported / Finished filters
  and a list of books with cover initials." · `tablet-reader-600.png` alt "The reader on a tablet:
  two panes, the chapter on the left and the player on the right."
- body: The library is local: search, filters for chapters, imports and finished books, a two-column
  grid from 600 dp. EPUB, PDF with a text layer, and plain text import into app-private storage.

**05 — Quiet**
- h2: **Nothing to sign up for. Nothing to report back.**
- body: There is no NovelSpeak server, so there is nowhere for your reading to go. No account, no
  advertising ID, no analytics SDK, no crash-reporting SDK, no push service. Ad blocking runs inside
  NovelSpeak's own browser view and touches nothing else on the phone.
- plate: `privacy-540.png`, alt "The in-app privacy page: a plain-language list of what stays on the
  device and what leaves it."
- specs: `Account` none · `Analytics` none · `Ads` none · `Diagnostics` created and shared only when
  you ask, redacted before writing
- limits heading: **What it does not do**
- limits: It does not solve a Cloudflare challenge, bypass a login, or read a page it cannot see. It
  detects those pages, says so, and does not read them aloud.
- link: `Read the privacy policy` → `./privacy/`

**06 — Install**
- h2: **Install it from a file you can check.**
- body: The APK is signed with the project's release key. Download it, compare the SHA-256, and
  install. Android asks once whether your browser may install apps.
- primary: `Download NovelSpeak 1.3.0 — 15.5 MB`
- secondary: `Release notes` → `./changelog/`
- status line (mono label, plain text, no border, no hover): `GOOGLE PLAY — COMING`
- verify (mono): `sha256sum -c NovelSpeak-1.3.0-arm64-v8a.apk.sha256`
- notes: Android 7.0 or newer · arm64-v8a · neural voices download separately (98–156 MB) · a
  debug-signed build must be uninstalled first, and that erases its data.

**Questions** (all answers open)
1. **Which sites work?** Any HTTPS page opens in the browser. Extraction is best on text-first
   serials and articles. Sites behind a Cloudflare challenge or a login are detected and left alone.
2. **Does it need the internet?** To fetch a chapter, yes. Playback with an installed voice does not
   use the network.
3. **How big is it, really?** The app is 15.5 MB. A neural voice is 98–156 MB depending on the
   model; Android's own voice adds nothing.
4. **Can I listen with the screen off?** Yes. Playback runs in a foreground service with a wake
   lock, and lock-screen, notification and headset controls work with both engines.
5. **Is it free?** Yes. Apache-2.0, no account, no ads, no paid tier. The source is on GitHub.
6. **Why is it not on Google Play yet?** The listing is not live. 1.4.0 is the store-ready build;
   until the listing publishes, the signed 1.3.0 APK from GitHub Releases is the supported install.

**Close** (`footer.closing`)
- statement: **A quiet reader that speaks.**
- links: Features · Voices · Changelog · Privacy · Source · Releases
- legal: Apache-2.0 · 1.3.0 · Built for Android 7.0+
- line: NovelSpeak is not affiliated with any website or publisher. The text stays the author's.

---

# `/features/`

<title>Features — NovelSpeak</title>
<meta description>Extraction, reader mode, four neural voices, screen-off playback, library, EPUB and PDF import, ad blocking and story cast — with the limits written down.</meta>

- h1: **What it does, in detail.**
- lede: Every feature here is in the app today. The last section is what it does not do.

**Reading**
- The browser takes any HTTPS URL; the shortcut grid opens six sites that serialise chapters.
- Readability extracts the article; when a site's markup defeats it, a text-density pass takes over.
- Reader mode is per site, so one stubborn site does not change the others.
- The next chapter is found from the page's own next link and extracted in a hidden view.
- Auto mode opens it, waits 2.5 s and continues. It is off by default.
- Paywall and login pages are detected. They are never bypassed and never read aloud.

**Voices**
- Android system TTS: instant, offline, no download.
- Neural: Supertonic 3, Kokoro, Pocket TTS, ZipVoice — on-device through sherpa-onnx.
- Sentences are pre-split and the next one or two are synthesised while the current one plays; up to
  about 12 s of audio is buffered ahead.
- Before neural synthesis, text is normalised for the model: quote glyphs, asterisks, ellipses and
  dashes, without touching the words.
- A saturated or garbage chunk is dropped instead of played.
- Vocoder hiss is suppressed and sentence boundaries are faded, so dialogue does not click.

**Controls**
- Foreground service + wake lock + media session: screen off, still reading.
- Wired and Bluetooth buttons play, pause, and move by sentence.
- Speed, pitch, volume boost, skip-silence.
- Sleep timer by minutes or end of chapter.
- A 56 dp mini player when the page needs the space; the full card shows the current voice.

**Library**
- Local library with reading position, search and filters (All, Chapters, Imported, Finished).
- Bookmarks save the sentence, not just the page.
- Reading statistics: time listened, sentences, active days.
- EPUB, PDF (text layer only, no OCR) and plain text import into app-private storage.
- Two-column grid from 600 dp; two-pane tablet and landscape layout.

**Story cast** (experimental)
- The narrator and each character can take different on-device voices.
- Analysis runs locally and preserves the author's text; it only adds speaker labels and voice ids.
- Supertonic exposes ten speakers in one model, so a cast does not download a model per character.
- Lines the parser cannot resolve stay with the narrator and are counted as *to review*.
- Requires Supertonic or Kokoro; system TTS, Pocket and ZipVoice fall back to a narrator-only queue.
- Measured in the project's 2026-11-06 story-cast benchmark: 4–5/5 voice distinction, 96.2 % word
  accuracy after spelling normalisation. Ultra-short lines and emotional range are still open.

**Ad blocking**
- Network: subresources are matched against EasyList, EasyPrivacy and uBlock rules.
- Cosmetic: site selectors are hidden, including `:has-text`, `:upward`, `:style` and `:remove`.
- Behavioural: popups, redirects and fake notification nags are refused, with small surrogates for
  tracker scripts so pages keep working.
- A per-site switch sits in the address bar. Lists are bundled (~5 MB) and can be updated.
- Limits: a WebView exposes no resource type, no redirect targets and no response bodies. `$csp`,
  `$removeparam`, trusted scriptlets and device-wide VPN or DNS blocking are out of scope.

**Accessibility**
- The reader honours the system reduced-motion setting.
- Layouts reflow at 1.3× and 2× text scale: two-column shortcuts, two-row filters, scrollable sheets.
- Two-pane layout on tablets and in landscape.
- 48 dp back targets on legal and reading pages.
- Status and navigation bar insets are respected on every screen.

**What it does not do** (same voice, no hedging)
- It does not host, publish or distribute books, and it is not affiliated with any site or publisher.
- It does not bypass a paywall, a login or a Cloudflare challenge.
- It does not OCR a scanned PDF.
- It does not run on 32-bit-only devices in the default build.
- It does not sync between devices. There is no account to sync with.

---

# `/voices/`

<title>Voices — NovelSpeak</title>
<meta description>Android system TTS and four on-device neural voices: Supertonic 3, Kokoro, Pocket TTS and ZipVoice. Sizes, speaker counts, download and verification.</meta>

- h1: **Voices that run on the phone.**
- lede: Two ways to speak: the voice Android already has, or a neural model you install once. Both
  work offline. Neither sends your text anywhere.

**System voice**
- Instant and offline, in every language the device supports.
- No download, no licence step, works on the first launch.
- Quality depends on the phone's engine.

**Neural voices** (stacked spec rows, no table: a definition list that collapses to one column)
- **Supertonic 3** · 123 MB · 2 curated styles (20 upstream) · Speed and memory; the default once installed.
- **Kokoro** · 103 MB · 2 curated English voices (11 upstream) · Audiobook pacing; about 3× slower in
  absolute synthesis. Still the quality option pending a listening test.
- **Pocket TTS** · 98 MB · clones from a reference clip · A narrator in a voice you supply.
- **ZipVoice** · 104 MB + 52 MB vocoder · clones with a verbatim transcript, Chinese and English.
- **Vocoder 24 kHz** · 52 MB · required by ZipVoice.

**How an install works**
1. You pick a model and confirm the download size.
2. The bundle comes from the upstream `k2-fsa/sherpa-onnx` `tts-models` release.
3. Every file is checked against a SHA-256 pinned in the app.
4. Extraction shows per-file progress and holds a wake lock so the phone does not sleep through it.
   It takes about one to three minutes. A 45 s no-progress watchdog aborts with a readable error.

**Measured, not asserted**
- Story cast, 2026-11-06 benchmark: 4–5/5 voice distinction and 96.2 % word accuracy after spelling
  normalisation, with near-zero speaker-switch cost.
- Supertonic real-time factor: 0.168 on the host, roughly 0.50 on the emulator.
- The harness is in the repository (`tools/cast-benchmark`, `tools/tts-benchmark`), so the numbers
  can be reproduced.

**Not measured yet**
- A listening test for Kokoro's pacing.
- Emotional range.
- Ultra-short lines, which are the weakest case for the cast parser.

**No network after install**
- Playback of an installed voice does not touch the network.
- Previewing a voice does not disturb the active engine.
- Changing the model updates the live engine instead of reloading it.

---

# `/changelog/`

<title>Changelog — NovelSpeak</title>
<meta description>Every NovelSpeak release with dates, what changed, and which version is actually downloadable today.</meta>

- h1: **Every release, and what you can download.**
- lede: The app is at 1.4.0, which is the store-ready build. The Play listing is not live yet, so the
  newest signed APK you can install today is 1.3.0.

**1.4.0 — 22 November 2026** · status: **store-ready; listing not live; not downloadable**
- Android 16 (API 36) target, signed AAB path, 16 KB page-size alignment, adaptive launcher icon.
- WebView hardening: no reflected JavaScript bridge, HTTPS-only intents, file and content access off,
  mixed content never, third-party cookies off, Safe Browsing on.
- Download-size confirmation for neural voices and filter lists.
- Diagnostics and crash logs redacted before they are written or shared.
- Reader: 56 dp mini player, two-column library at tablet widths, reduced-motion support, 1.3×/2×
  text-scale layouts.

**1.3.0 — 4 November 2026** · [Download the APK](https://github.com/vipulgote1999/NovelSpeak/releases/download/v1.3.0/NovelSpeak-1.3.0-arm64-v8a.apk) (15.5 MB) · [SHA-256](https://github.com/vipulgote1999/NovelSpeak/releases/download/v1.3.0/NovelSpeak-1.3.0-arm64-v8a.apk.sha256)
- Supertonic 3 and ZipVoice join Kokoro and Pocket TTS.
- Faster first audio: the engine is prewarmed when content is queued, up to 8 s / 4 sentences are
  prerendered while playback is held.
- Playback survives pause, calls and screen changes; a blocking write no longer kills the PCM writer.
- Ad text stays out of the reader: blocks whose only live match is hidden are dropped.
- An on-device TTS benchmark activity, with committed emulator and phone results.

**1.2.1 — 27 October 2026**
- Fixes for extraction and playback regressions reported after 1.2.0.

**1.2.0 — 27 October 2026**
- Home tab, share-sheet intake, library with continue reading, bookmarks, reading statistics.
- Sleep timer, reader typography, offline WAV capture, diagnostics bundle.
- EPUB, PDF (text layer) and plain text import.

**1.1.0 — 24 October 2026**
- Reader mode per site, ad blocking, voice previews.

**1.0.0 — 22 October 2026**
- First release: browser, extraction, system TTS and Kokoro, screen-off playback.

- tail note: Older APKs are not re-signed. Install only from this repository's releases, and check
  the SHA-256 beside each file.
- link: `All releases on GitHub` → releases page

---

# `/privacy/`

The existing policy text is carried over verbatim in substance; only the shell, typography and
navigation change. Title: **Privacy policy — NovelSpeak**. Intro line stays: what stays on the
device and what leaves it. No new promises, no removed caveats. Every heading in the current file
is kept, in the same order, so the Play Console URL still answers the same questions.

---

# `404.html`

- h1: **That page is not here.**
- body: The link may be old, or the chapter may have moved. The app is still where you left it.
- primary: `Home`
- secondary: `Source and releases` → GitHub
