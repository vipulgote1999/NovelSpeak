# Free competitors: what listeners already get without paying

**Stream:** `free` · **Research date:** 2026-10-24 (Asia/Calcutta) · **Author:** free-intel
**Companion data:** [data/free.json](data/free.json) (17 competitor cards, 37 sources)
**Scope:** free / freemium / open-source Android apps and browser-native read-aloud (Chrome, Edge) that compete with
NovelSpeak's "extract any web page and read it aloud" job. Paid subscription apps are covered by the `paid` stream,
serialized-novel platforms by `novel`, APK teardowns by `apk-re`.

**Method.** Google Play pages were fetched directly (raw HTML, no credits) and parsed for the structured
app-details block (installs, rating, ratings count, version, last update, IAP range, badges, description, three
user reviews) plus the Play "Data safety" summary. Vendor sites, GitHub API, F-Droid API and official
Google/Microsoft help docs were fetched with `curl`. Firecrawl was used for 4 calls only
(2 searches + 2 target scrapes; credit usage kept minimal because the account is shared). Every claim below is tagged **[Observed]** (seen on the cited store/doc/repo page on
2026-10-24), **[Inferred]** (reasoned from observed evidence) or **[Unknown]**.

---

## 1. TL;DR — the free baseline and the real openings

1. **Free already covers "file → speech" completely.** ReadEra (50M+, 4.86★) is free with **no ads and no in-app
   purchases**; PocketBook (10M+, 4.23★) is "free, offline, without ads and registration" with 26 formats, DRM/LCP,
   cloud sync and TTS; AlReader (5M+, 4.34★) is free with no ads. None of them can open a URL. **[Observed]**
2. **Free already covers "web page → speech".** @Voice (10M+, 4.28★) ships an in-app reader plus a desktop
   Connector extension; Audify (500K+, 4.45★) is a literal read-aloud web browser with **auto-next-page for web
   novels** and word-by-word highlighting; Chrome's *Listen to this page* is free with 25 languages, voice picker,
   highlighting/auto-scroll and **locked-screen playback**. **[Observed]**
3. **Free already covers "natural voices" — but only in the cloud.** Edge Read aloud and Chrome use cloud natural
   voices; Edge's offline mode has "only a few voice options"; Chrome requires the *Make searches and browsing
   better* data-sharing setting. Fully offline neural voices *do* exist in free apps (NekoSpeak MIT, HayaiTTS
   GPL-3.0) — but those are TTS-only: they have no browser, no extraction, no library. **No free competitor combines
   offline neural voices with web-page read-aloud**; that combination is NovelSpeak's. **[Observed / Inferred]**
4. **Free players are weak on reliability and long-form novel flow** — the complaints are remarkably consistent:
   read-aloud stops suddenly (Audify's own FAQ), audio shuts down at random (Prestigio), 5–10 s pauses at every
   period and reset-after-hours behaviour (Google Reading mode, 2.76★), freezes/mispronunciations (Listening).
   **[Observed]**
5. **The serialized-novel scraping niche is legally radioactive.** Legado (47,154★, the most popular open-source
   novel reader) has had its upstream source deleted after a Chinese copyright-infringement notice; it has no
   license file and **no Play listing** (both package IDs 404). NovelSpeak should stay a *generic page extractor*,
   never a book-source catalog. **[Observed]**

> **Correction to NovelSpeak's own pitch (important for the roadmap).** The repo README says NovelSpeak exists
> because Chrome's *Listen to this page* "stops when the screen locks". Google's current help doc says the
> opposite: *"You can continue to listen to text even if your screen is locked."* **[Observed, accessed
> 2026-10-24]**. The defensible differentiators are instead: **generic extraction on arbitrary novel sites**
> (Chrome: *"This feature isn't available yet on all websites."*), **fully offline neural voices**, **auto-next
> chapter**, and **no data-sharing switch**. Recommend the Lead updates the market positioning accordingly, and
> that someone verify the lock-screen claim on a real device before it is used in any store listing.

---

## 2. The free baseline at a glance

All numbers from the Google Play app-details block, accessed 2026-10-24. "Screen-off" = documented by the vendor
(not merely hoped for). "Web extraction" = can take an arbitrary URL/web page, not only local files.

| App | Tier / price | Installs | Rating (count) | TTS engine | Offline natural voices | Screen-off (documented) | Web extraction | Auto-next chapter | Ads |
|---|---|---|---|---|---|---|---|---|---|
| @Voice Aloud Reader | freemium · $15 one-time Premium | 10M+ | 4.28 (137,634) | system + **BYO cloud keys** | no | not documented | **yes** (built-in reader + Connector) | yes ("auto-advance to the next article") | yes |
| ReadEra | free | 50M+ | **4.86** (1,597,738) | system | no | **no** (Premium only) | no (files only) | n/a | **no** |
| ReadEra Premium | paid · **$14.99** one-time | 100K+ | 4.81 (66,770) | system | no | **yes** ("even with the screen locked") | no | n/a | no |
| Librera | OSS · free+ads / PRO **$5.99** | 10M+ | 3.98 (137,891) | system (any installed engine) | no | not documented | no | n/a | yes |
| TTS Reader (Librera) | freemium · free+ads | 1M+ | 3.57 (12,405) | system | no | **yes** | share-sheet only | n/a | yes |
| AlReader | free | 5M+ | 4.34 (105,236) | system | no | not documented | no | n/a | **no** |
| PocketBook reader | free (+store IAP) | 10M+ | 4.23 (102,304) | system | no | not documented | no | n/a | **no** |
| eReader Prestigio | freemium · ads + IAP | 10M+ | 4.63 (556,687) | system + purchasable voices | no | not documented (users report random stops) | no | n/a | yes |
| **Audify read aloud web browser** | freemium · ads / ad-free IAP | 500K+ | 4.45 (4,734) | system (inferred) | no | not documented | **yes** (in-app browser) | **yes** ("next page … automatically") | yes |
| Listening: Text to Speech | subscription · $12.99/mo or $59/yr | 100K+ | 4.03 (7,312) | **cloud** | no | not documented | yes (server-side URL/PDF) | unknown | no |
| Google Reading mode | free (Google) | 1M+ | **2.76** (6,129) | unknown Google long-form voices | unknown | not documented | accessibility reading view | no | no |
| Microsoft Edge Read aloud | free (browser) | n/a | n/a | **cloud natural + few offline** | **partial** | not documented | yes | no | no |
| Chrome *Listen to this page* | free (browser) | n/a | n/a | **cloud** (+ AI podcast mode) | no (undocumented) | **yes** | yes, "not … all websites" | unknown | no |
| Legado (开源阅读) | OSS · free | not on Play | n/a | system + imported online engines | no | unknown | **yes via user scrapers** | yes | **no** |
| KOReader | OSS (AGPL-3.0) | sideload only | n/a | **none found** | n/a | n/a | no | n/a | no |

---

## 3. Where the free baseline is weak (with evidence)

### 3.1 Reliability of background/long playback — the single biggest gap

| App | Documented/observed failure | Evidence |
|---|---|---|
| Audify | Own troubleshooting section: **"Q: It cannot read aloud suddenly"** → "Swipe to close the app and reopen it" | Play description, 2026-10-24 **[Observed]** |
| eReader Prestigio | User: *"I also have a huge problem with the app shutting down the audio at random."* | Play review, 2026-10-24 **[Observed]** |
| Google Reading mode (2.76★) | *"every period means a pause that lasts anywhere from 5-10 seconds, sometimes even more than 30 seconds"*; *"every single time I go to use it after not using it for more than a few hours, it no longer works"* | Play reviews **[Observed]** |
| Listening | *"it freezes every few minutes so it's completely useless if you're driving"*; *"randomly spells some words instead of reading them"* | Play reviews **[Observed]** |
| TTS Reader (3.57★) | zoom/highlight persistence complaints; lowest rating of its family | Play reviews **[Observed]** |
| @Voice | *"sharing an article with @voice no longer starts reading it automatically"* (regression in a recent build) | Play review **[Observed]** |

**Implication:** NovelSpeak's foreground service + wake lock + MediaSession + sentence pipeline is not a
nice-to-have; it is *the* quality axis where free competitors fail. This is cheap to differentiate on
(explicit "works with the screen off, all night" claim + soak tests), and expensive for them to fix.

### 3.2 Extraction coverage on serialized-novel sites

* Chrome admits: *"This feature isn't available yet on all websites."* **[Observed]**
* Audify's reader mode leaks ads: user says *"the reader mode includes hyperlinks from ads on the page"*;
  needs "skip header/paragraph/hyperlinks" and reports the skip-words feature stopped working. **[Observed]**
* @Voice's PDF parsing is "not perfect"; users ask for tutorials. **[Observed]**
* Legado solved this with user-authored book-source scrapers — and was taken down (see §8.1). **[Observed]**

**Implication:** the *generic* extractor (Readability + density fallback) with dotted-obfuscation handling and
next-chapter detection is a real moat; it must stay generic and page-scoped to stay lawful.

### 3.3 Voices: free apps either sound like 2008 or need the cloud

* Free readers use the **device TTS engine** (ReadEra, Librera, TTS Reader, AlReader, PocketBook, Prestigio, Audify
  – all "system"/"whatever TTS engine installed"). **[Observed]**
* @Voice is the only free app with an escape hatch: bring your own Google Cloud / Microsoft / Amazon key
  (*"pay only for what you use"*). **[Observed]**
* Browser-native is cloud: Edge natural voices (**"only a few voice options are available while offline"**);
  Chrome 25 languages but requires *"Make searches and browsing better"*. **[Observed]**
* Reading mode offers *"high quality long-form voices"* but is the worst-rated app in the set (2.76★). **[Observed]**

**Implication:** NovelSpeak's Kokoro/Pocket on-device path is the only "natural voice, offline, no account, no
data sharing" offer in this comparison. That is the headline differentiator.

### 3.4 Serialized-fiction ergonomics are missing almost everywhere

Only **Audify** (*"By clicking the next page button of a web novel automatically, it allows users to enjoy web
novels without turning on and off the screen all the time."*) and **@Voice** (auto-advance / follow multi-part web
fiction) advertise auto-next for web fiction. Chrome and Edge are per-page; ReadEra/PocketBook/AlReader cannot open
a URL at all. **[Observed]**

### 3.5 Privacy and ads

* Ads: @Voice, Librera, TTS Reader, Prestigio, Audify. No ads: ReadEra, PocketBook, AlReader, Chrome, Edge,
  Reading mode, Legado (claims none), KOReader. **[Observed]**
* @Voice is unusually clean: *"No account required. No subscription. No data collection."* (Play Data safety:
  "No data collected"). **[Observed]**
* Google Reading mode collects *"App activity, App info and performance, and Device or other IDs"*. Chrome's
  listen feature is gated on a data-sharing setting. **[Observed]**
* Data-safety fields that declare third-party sharing: Librera, TTS Reader, Prestigio, Audify, Listening. **[Observed]**

---

## 4. The closest architectural competitor: Audify read aloud web browser

Package `com.tangerinesoftwarehouse.audify` · Tangerine Software House · 500K+ installs · **4.45★ (4,734)** ·
v11.5.88, updated **Aug 29, 2026** · ads + in-app purchases ($0.99–$104.99 range). **[Observed]**

Why it matters: it is the same product idea — a browser whose primary verb is "read this aloud".

* **Extraction/flow:** in-app browser, reader mode, *"Auto next page"*, multi-tab, "Read aloud from the position"
  (double-tap), search-in-page, hide images. **[Observed]**
* **TTS:** system voices, adjustable rate, *"Highlight words one by one while reading aloud"*, repeat
  sentence/paragraph, pronunciation correction, skip words/symbols/**headers and footers**, WAV export. **[Observed]**
* **UX:** night mode, blue-light filter, brightness, font size/bold, full screen, translation, sleep timer,
  bookmarks with user categories, playlist, downloads, cloud import. **[Observed]**
* **Weaknesses:** its own FAQ admits read-aloud stopping; skip-features reported broken; reader mode includes ad
  links; **no screen-off/foreground-service claim anywhere** (Play description + vendor site, 2026-10-24) — so
  overnight serialized-novel listening likely dies with the screen. **[Observed / Inferred]**
* Business model: free with non-intrusive ads (*"Even free all the features work and the ads are not intrusive"*)
  and a one-time ad-free purchase; exact price **[Unknown]** (Play only exposes the $0.99–$104.99 IAP range).

**Takeaway for NovelSpeak:** match Audify's novel ergonomics (auto-next page, skip header/footer/links, pronunciation
rules, WAV export) *and* win on what it lacks (screen-off service, offline neural voices, blocklist ad blocking).

---

## 5. File-first readers — free, polished, but blind to the web

| App | Free offer | Formats / sources | TTS reality | What NovelSpeak should copy |
|---|---|---|---|---|
| **ReadEra** (50M+, 4.86★, v26.05.20, May 22 2026) | *"neither contains ads nor imposes internal purchases"* | PDF, EPUB, MOBI/AZW3, FB2, DJVU, DOC/DOCX/RTF/ODT, TXT, CHM, comics, ZIP/RAR | system TTS; background TTS is **Premium-only** | Library/collection model, quotes/notes, page-memory; zero-cost positioning |
| **ReadEra Premium** (100K+, 4.81★, v26.09.29, Oct 20 2026) | **$14.99 one-time** (Play offer data) | same | *"Read aloud in the background TTS … even with the screen locked"* | One-time price anchor; Drive sync of progress/quotes |
| **PocketBook** (10M+, 4.23★, v5.65.0, Jul 28 2026) | *"free, offline, without ads and registration"* | 26 formats, DRM/LCP, audiobooks, comics, free cloud | TTS + audiobook player | Free cloud sync; DRM awareness; 26-format coverage |
| **eReader Prestigio** (10M+, 4.63★, May 15 2026) | ads + IAP $0.99–$99.99 | EPUB/FB2/PDF + 50,000-book store, cloud import | *"Text-To-Speech (read along)"* + purchasable voices | Read-along concept, widget, 25-language UI |
| **AlReader** (5M+, 4.34★, **last update Feb 24 2021**) | free, no ads, no IAP | FB2/FBZ, TXT, EPUB (no DRM), HTML, DOC/DOCX, ODT, RTF, MOBI (no DRM), PRC, TCR, OPDS | system TTS; reviewers praise pronunciation | Four independent display profiles; wave/slide autoscroll; 20-language hyphenation |
| **Librera** (10M+, 3.98★, v9.4.20, Oct 19 2026) | GPL-3.0, free+ads; PRO **$5.99** | huge list incl. EPUB3, DJVU, XPS, MHT, OPDS | *"reading out loud, using whatever TTS engine installed on your device"* | Musician's-mode autoscroll; custom CSS; OpenDroid-style theming |
| **TTS Reader** (1M+, 3.57★, v9.6.40, Oct 19 2026) | free+ads | EPUB/MOBI/TXT/FB2/PDF/DJVU/RTF/AZW/HTML/ODT; share-sheet web pages | *"Listen to books in the background with your screen turned off"*, *"Status-bar and lock-screen notification w/ controls"*, headset **remote bookmarks**, sleep timer, WAV export | This is the closest feature list to NovelSpeak's playback; copy the headset-bookmark idea |

**Key observation:** the highest-rated free app (ReadEra, 4.86★) has **no** web extraction and **no** free
background TTS; the app with the best TTS feature list (TTS Reader, 3.57★) has the **worst** rating. Users reward
polish and library quality, and punish TTS reliability. **[Inferred from observed ratings]**

---

## 6. Subscription and Google-owned listening

### 6.1 Listening: Text to Speech (io.listening.main)
100K+ installs, **4.03★ (7,312)**, v6.0.4, Oct 14 2026. Cloud voices, PDF/papers/Zotero, 0.5×–4× speed and
read-along notes. Pricing from the vendor (accessed 2026-10-24): **$12.99/month** or **$59.00/year**
(≈$4.92/month, "SAVE 62%"), **"3-day free trial … New subscribers only"** — i.e. effectively no free tier.
Reviews are brutal about reliability (*"freezes every few minutes"*, *"randomly spells some words"*,
*"I am forced to sign out and sign back in every few minutes"*). **[Observed]**
**Lesson:** cloud quality alone does not retain users when playback is fragile; a free offline baseline is a
credible substitute at $0.

### 6.2 Google Reading mode (com.google.android.accessibility.reader)
Free, 1M+ installs, **2.76★ (6,129)** — the lowest in this set — v1.3.1, Sep 21 2026. Accessibility reading view
across apps/web, TTS with *"high quality long-form voices"*, rewind/FF/speed controls, contrast/text-size options.
It collects App activity, App info/performance and Device IDs per Play Data safety. Reviews: reset-after-hours,
missing floating icon on Pixel 9a, and long pauses at punctuation. **[Observed]**
**Lesson:** even Google ships a read-aloud that users rate below 3★ because of reliability; NovelSpeak's TTS
robustness work (text normalization, callbacks, sentence merging — see repo docs) is directly relevant.

---

## 7. Browser-native read-aloud — the free default, and its two gaps

### 7.1 Microsoft Edge Read aloud (**free**, official feature page)
* Available online **and offline**: *"Read aloud is available in both online and offline modes, however, only a
  few voice options are available while offline."* **[Observed]**
* Natural voices: *"Available in a variety of natural-sounding voices and accents."* **[Observed]**
* Also on mobile: *"Yes, Read aloud is available on Microsoft Edge mobile app on iOS and Android systems."* **[Observed]**
* Can read only a selection ("Read aloud selection") and launch outside Immersive Reader. **[Observed]**
* **Gaps:** per-page only (no auto-next chapter), no library/sleep timer, offline voice set is small, feature
  availability varies by market/device. **[Observed]**

### 7.2 Chrome *Listen to this page* (**free**, official Google help doc 14768725)
* Two modes: **Standard playback** ("Reads all the words on the page") and **AI playback**
  ("Generates podcast-like conversations from the page", default when available, English browser only). **[Observed]**
* 25 languages listed for Standard: Arabic, Bengali, Chinese, English, French, German, Greek, Hebrew, Hindi,
  Indonesian, Italian, Japanese, Malayalam, Marathi, Polish, Portuguese, Romanian, Russian, Spanish, Tamil, Telugu,
  Thai, Turkish, Ukrainian, Vietnamese. **[Observed]**
* Controls: play/pause/rewind/FF, speed (1.0×…), voice picker with preview, **"Turn on or off text highlighting &
  auto scroll"**. **[Observed]**
* **Locked-screen playback is documented**: *"You can continue to listen to text even if your screen is locked."*
  Also keeps playing while browsing/switch tabs. **[Observed]**
* **Gaps:** *"This feature isn't available yet on all websites."*; requires turning on
  *"Make searches and browsing better"*; AI playback is English-only; no library/sleep timer/auto-next; community
  threads report the menu entry disappearing for months on some builds. **[Observed]**

**Conclusion for the roadmap:** the "free browser read-aloud" baseline is strong on voices and basic controls, and
weak exactly where serialized-novel listeners live: **site coverage, auto-next chapter, offline/private neural
voices, and a book-like library**. NovelSpeak's existing feature set maps onto that gap; the pitch must be fixed
(see §1 correction) and the reliability claims must be provable.

---

## 8. Open source: what is safe to emulate

| Project | License (verified) | Stars / activity | Read-aloud? | What is safe to reimplement |
|---|---|---|---|---|
| **Legado** (开源阅读, `io.legado.app.release`) | **No license file observed** (GitHub API `license: null`) → all rights reserved by default | **47,154★**, Kotlin, last push 2026-05-27; **upstream repo emptied** | system TTS + imported online TTS engines ("在线朗读引擎") | **Nothing code-wise.** Study the UX idea only (bookshelf + reader + read-aloud). Do **not** build a book-source scraper (see below). |
| **KOReader** (`org.koreader.launcher`) | **AGPL-3.0** (README badge + `COPYING`) | 30,557★, active (v2026.07.1, 2026-08-01) | **None found** | Typesetting/reflow ideas (K2pdfopt concept), OPDS, dictionary/translate integration — but AGPL code reuse would force copyleft. |
| **Librera** (`com.foobnix.pdf.reader`) | **GPL-3.0** (`LICENSE.txt` observed) | 4,952★; README: development "frozen for an unpredictable time" | system TTS | Format-coverage list, autoscroll modes, settings taxonomy. GPL code itself must not be copied. |
| **NekoSpeak** | **MIT** (per repo RE docs) | reference app, v1.4.2 | on-device Kokoro/Pocket/cloning | **Safe to learn from / reuse with attribution** — the on-device TTS architecture and preprocessing constants. |
| **HayaiTTS** | **GPL-3.0** (per repo RE docs) | reference app, v2.5.1 | sherpa-onnx wrapper | Architecture and constants only; *"No GPL code was copied"* is the repo's own rule. |

### 8.1 Legado is a legal cautionary tale, not a template

* Upstream repo now contains **only** `README.md` and `公告链接.png` (verified via GitHub contents API). **[Observed]**
* The README is a Chinese legal notice stating the project involved infringement and that project content was
  deleted (原文摘要: *"本项目涉及侵权行为的违法 … 删除项目内容"* — "this project involves infringing conduct … delete the
  project content"), linking 阅文 (China Literature) IP-protection announcement. **[Observed]**
* Neither `io.legado.app.release` nor `io.legado.play.release` exists on Google Play (both 404 on 2026-10-24). **[Observed]**
* Its defining feature (per an active fork's README) was *"自定义书源，自己设置规则，抓取网页数据"*
  ("custom book sources; set your own rules; scrape web data"). **[Observed, secondary source]**

**Rule for NovelSpeak:** extract the page the user is *currently viewing* (a browser action the user initiated),
never ship or accept third-party "book source" catalogs that systematically scrape copyrighted serialized fiction.
Keep the extractor generic, keep no content library, and document the distinction.

### 8.2 KOReader: excellent reader, zero read-aloud

KOReader is not on Google Play (*"KOReader isn't currently available on the Google Playstore."* — official wiki),
not in the main F-Droid repo (`NOT_FOUND` from the F-Droid API), and its plugin directory (37 plugins observed)
contains **no TTS plugin**; the Android device layer (`frontend/device/android/device.lua`) has no audio
references and none of the last 60 release notes mention TTS. Its AGPL-3.0 license and Lua codebase make code
reuse impractical. **Verdict:** no read-aloud threat; useful as a typesetting/format-support reference only.
**[Observed / Inferred]**

---

## 9. What NovelSpeak should build next (ranked by evidence × cost)

### P0 — defend and prove what free apps do not have
1. **"Fully offline neural voices, no account, no data sharing" as the headline.** Every *read-aloud* app in this
   set either uses robotic system TTS or the cloud (Chrome/Edge/Listening). The only free offline neural TTS apps
   (NekoSpeak, HayaiTTS) cannot open a web page at all. NovelSpeak already has Kokoro/Pocket; add a first-run
   explainer ("no cloud, works in airplane mode") and a measurable proof (airplane-mode demo). **[Observed gap]**
2. **Reliability program for screen-off playback.** Every free competitor has playback-death complaints
   (Audify FAQ, Prestigio reviews, Reading mode 2.76★, Listening freezes). Add: soak test (≥2 h screen-off),
   battery-optimization exemption prompt, watchdog restarts, and a user-visible "keep playing when locked" toggle.
   **[Observed gap]**
3. **Generic extraction + auto-next-chapter polish.** Chrome: "not available on all websites"; Audify: reader mode
   leaks ad links; ReadEra/PocketBook/AlReader: cannot open a URL at all. Improve next-link detection (rel=next,
   "next chapter" text, chapter lists) and ship a per-site debug view. **[Observed gap]**
4. **Keep ad blocking as a differentiator.** None of the read-aloud browsers claim list-based (EasyList/uBlock)
   blocking; @Voice only says it "removes ads and clutter". **[Observed]**

### P1 — parity features users already expect for free
5. **Sleep timer with shake-to-restart** (@Voice), **headset remote bookmarks** (TTS Reader/@Voice),
   **notification + lock-screen transport** (TTS Reader). Low cost, high perceived value.
6. **Pronunciation dictionary / replacement rules + skip words/headers/footers/links** (@Voice "custom
   pronunciation rules", "user-contributed files"; Audify "pronunciation correction", "skip header and footer").
   This directly answers the most common neural-TTS complaint (names/acronyms).
7. **Audio export (WAV/MP3) of the queued text** (@Voice MP3/OGG/WAV, Audify WAV, TTS Reader WAV). Cheap with the
   existing PCM pipeline; creates an offline artifact users can keep.

### P2 — retention and breadth
8. **Library/reading list + resume across sessions** (@Voice reading lists with per-item progress; ReadEra
   collections). Even a lightweight "Recently read pages/chapters" list beats none.
9. **Translation/bilingual and dictionary hand-off** (@Voice bilingual view with per-language voices; Prestigio,
   Librera dictionary integrations). Evaluate after P0/P1.
10. **Android Auto** (@Voice ships a separate plugin) — niche; defer.

### Explicit non-goals (legal)
* No book-source/scraper catalog (Legado precedent).
* No third-party DRM/LCP circumvention. @Voice advertises *legal* LCP support; NovelSpeak reads only what the
  browser displays.
* No copying GPL/AGPL code (Librera GPL-3.0, HayaiTTS GPL-3.0, KOReader AGPL-3.0). NekoSpeak is MIT; Mozilla
  Readability is Apache-2.0 (already used).

---

## 10. Pricing summary (free vs paid anchors)

| Product | Free tier | One-time | Subscription |
|---|---|---|---|
| @Voice Aloud Reader | full app + ads | **$15 USD** Premium (ad removal + continuous recording) | none |
| ReadEra | full app, no ads | — | — |
| ReadEra Premium | — | **$14.99 USD** | — |
| Librera | full app + ads | **$5.99 USD** (PRO app) | — |
| Audify | full features + ads | ad-free IAP (price unknown; IAP range $0.99–$104.99) | — |
| Listening | 3-day trial only | — | **$12.99/mo** or **$59/yr ($4.92/mo)** |
| Google Reading mode / Chrome / Edge / AlReader / PocketBook | full app, no charge | — | — |

**Read:** the free market has settled on *one-time ≤$15* or *free with ads*. Subscriptions only survive when tied
to a cloud service (Listening). NovelSpeak, being free/Apache-2.0, competes with the *free* tier directly — its
wedge is capability, not price.

---

## 11. Gaps and unknowns (honest list)

* **Audify Pro price** — [Unknown]: Play only exposes the $0.99–$104.99 IAP range; the vendor site has no price page.
* **ReadEra free read-aloud scope** — [Inferred]: Play description omits read-aloud entirely, Premium description
  lists background TTS as premium, and a readera.org user testimonial says the app reads aloud. Whether free
  ReadEra reads aloud in the foreground only is not documented; medium confidence.
* **PocketBook/Prestigio/AlReader TTS engine details** (system vs bundled voices) — only vendor copy observed;
  no APK inspection (owned by `apk-re`).
* **KOReader "no TTS"** — [Inferred from absence] (plugins, device layer, README, user guide, 60 release notes);
  a third-party plugin could exist that we did not find.
* **Legado feature list** — [Secondary source] an active fork's README, because upstream content was deleted.
  We did not inspect Deleted-code mirrors, by design (legal).
* **Review themes** — based on the 3 reviews Play renders per app (raw page), not a score-filtered distribution.
  The Firecrawl Play-reviews contract was not exercised (credit budget shared with 4 agents; ≈7 credits used).
* **Edge/Chrome install bases** — bundled with the browsers; no separate install count exists.
* **Speech Central** (`com.labsiisoftware.speechcentral`) — page fetched but the structured block was absent and the
  optional app was dropped in favour of finishing the required list.

---

## 12. Sources appendix

All accessed **2026-10-24**. Store numbers are as rendered by Google Play (US/English) at fetch time.
Machine-readable version with per-competitor mapping: [data/free.json](data/free.json).

### Google Play (raw page → structured app-details block + Data safety + 3 reviews)
| URL | Supports |
|---|---|
| https://play.google.com/store/apps/details?id=com.hyperionics.avar | @Voice 10M+, 4.28/137,634, v40.9.1, updated Oct 24 2026, description quotes (no account/data collection, BYO cloud keys, headset buttons), reviews |
| https://play.google.com/store/apps/details?id=org.readera | ReadEra 50M+, 4.86/1,597,738, v26.05.20+2300, May 22 2026, "neither contains ads nor imposes internal purchases" |
| https://play.google.com/store/apps/details?id=org.readera.premium | ReadEra Premium $14.99 offer, 100K+/4.81, v26.09.29+2320, Oct 20 2026, "Read aloud in the background TTS … even with the screen locked" |
| https://play.google.com/store/apps/details?id=com.foobnix.pdf.reader | Librera 10M+, 3.98/137,891, v9.4.20, Oct 19 2026, "reading out loud, using whatever TTS engine installed on your device" |
| https://play.google.com/store/apps/details?id=com.foobnix.pro.pdf.reader | Librera PRO $5.99, 50K+, 4.43/7,081 |
| https://play.google.com/store/apps/details?id=tts.reader | TTS Reader 1M+, 3.57/12,405, v9.6.40, Oct 19 2026, screen-off + lock-screen controls + WAV export quotes |
| https://play.google.com/store/apps/details?id=com.neverland.alreader | AlReader 5M+, 4.34/105,236, **Feb 24 2021** last update, TTS line, format list |
| https://play.google.com/store/apps/details?id=com.obreey.reader | PocketBook 10M+, 4.23/102,304, v5.65.0, Jul 28 2026, 26 formats/DRM/LCP/TTS, "without ads and registration" |
| https://play.google.com/store/apps/details?id=com.prestigio.ereader | Prestigio 10M+, 4.63/556,687, May 15 2026, ads + $0.99–$99.99 IAP, read-along TTS, "shutting down the audio at random" |
| https://play.google.com/store/apps/details?id=com.tangerinesoftwarehouse.audify | Audify 500K+, 4.45/4,734, v11.5.88, Aug 29 2026, feature list incl. auto-next-page & word highlighting, FAQ "It cannot read aloud suddenly" |
| https://play.google.com/store/apps/details?id=io.listening.main | Listening 100K+, 4.03/7,312, v6.0.4, Oct 14 2026, subscription wording, freeze/mispronunciation reviews |
| https://play.google.com/store/apps/details?id=com.google.android.accessibility.reader | Reading mode 1M+, **2.76**/6,129, v1.3.1, Sep 21 2026, TTS voice wording, reviews, Data safety |
| https://play.google.com/store/apps/details?id=io.legado.play.release | HTTP 404 — no Play listing for Legado (2026-10-24) |

### Vendor / official docs
| URL | Supports |
|---|---|
| https://www.hyperionics.com/atVoice/ | "Free with ads. An optional Premium license removes them.", **$15 USD** permanent licence, Android Auto plugin, Connector |
| https://readera.org/ | ReadEra free/no-ads/no-registration, format list, user testimonial "reading the text aloud - you can listen to your books" |
| https://librera.mobi/ | Librera formats, "more than 10 million downloads", PRO upsell |
| https://www.tangerinesoftwarehouse.com/ | Audify official features: auto next page, WAV conversion, translate, read-from-position |
| https://www.tangerinesoftwarehouse.com/download/ | Audify direct APK downloads, latest audify11.5.88 (2026-09-08) |
| https://listening.com/pricing | $12.99/mo · $59.00/yr ($4.92/mo) · "3-day free trial … New subscribers only" |
| https://www.microsoft.com/en-us/edge/features/read-aloud | Edge: online+offline modes, "only a few voice options … offline", natural voices, mobile availability, selection read aloud |
| https://support.google.com/chrome/answer/14768725?hl=en | Chrome: Standard vs AI playback, 25-language list, locked-screen playback, highlight & auto scroll, "not available yet on all websites", data-sharing setting |

### Code hosts / licences / APIs
| URL | Supports |
|---|---|
| https://github.com/foobnix/LibreraReader (+ raw `LICENSE.txt`) | Librera GPL-3.0; 4,952★; README "development and support of Librera is frozen" |
| https://f-droid.org/api/v1/packages/com.foobnix.pro.pdf.reader | Librera PRO on F-Droid (10.0.0-fdroid) |
| https://api.github.com/repos/gedoor/legado | Legado 47,154★, Kotlin, `license: null`, last push 2026-05-27 |
| https://api.github.com/repos/gedoor/legado/contents/ | Repo root now only README.md + 公告链接.png (source deleted) |
| https://raw.githubusercontent.com/gedoor/legado/main/README.md | Chinese legal notice: infringement, "删除项目内容" (delete the project content) |
| https://github.com/huajideshutiao/legado | Active fork (310★) carrying the original feature list incl. "自定义书源…抓取网页数据" and online TTS engine import |
| https://github.com/koreader/koreader (+ raw README) | KOReader AGPL-3.0, 30,557★, format list, no TTS in feature list |
| https://github.com/koreader/koreader/wiki/Installation-on-Android-devices | "KOReader isn't currently available on the Google Playstore." |
| https://api.github.com/repos/koreader/koreader/releases/latest | KOReader v2026.07.1 (2026-08-01); android-arm64 APK 30,084,751 bytes |
| https://api.github.com/repos/koreader/koreader/contents/plugins | 37 plugins, no TTS plugin |
| https://raw.githubusercontent.com/koreader/koreader/master/frontend/device/android/device.lua | Android device layer has no TTS/audio references |
| https://f-droid.org/api/v1/packages/org.koreader.launcher | NOT_FOUND — KOReader absent from main F-Droid repo |
| https://alreader.com/ | AlReader official site, direct APK download |
| https://www.reddit.com/r/ReadEra/comments/1ouf0x7/text_to_speech/ | Community: ReadEra Premium "doesn't provide any special voice just the Android Text to speech" |

### Repo-local RE evidence (do not re-derive)
| Path | Supports |
|---|---|
| [docs/re-natural-ai-voices.md](../re-natural-ai-voices.md) | HayaiTTS v2.5.1 GPL-3.0 / NekoSpeak v1.4.2 MIT; engines; architecture; "use the sherpa-onnx route" |
| [OPTIMIZATIONS.md](../../OPTIMIZATIONS.md) | Which constants were reused; "No GPL code was copied"; NekoSpeak/HayaiTTS "never solve page extraction — that gap is ours" |

---

## 13. One-paragraph answer for the Lead

Free competitors already give users file-reading with TTS (ReadEra, PocketBook, AlReader — free, no ads),
web-page read-aloud (@Voice, Audify), and cloud natural voices (Chrome, Edge). None of them combine
**generic novel-site extraction + auto-next-chapter + fully offline neural voices + screen-off reliability +
zero data sharing** — that union is NovelSpeak's defensible free-tier position, and it is supported by the observed
weaknesses: Chrome "not available on all websites", Edge "only a few voices offline", Audify's own FAQ admitting
read-aloud stops, and Google Reading mode at 2.76★. The biggest messaging risk is the README's "Chrome stops when
the screen locks" claim, which Google's own help page contradicts — fix the pitch, keep building reliability,
novel-flow ergonomics (skip header/footer/link, pronunciation rules, sleep timer, audio export), and stay far away
from Legado-style book-source scraping.
