# NovelSpeak competitive intelligence — 2026-10-24

**Question:** what do paid, popular and free competitors actually offer, how are they built, and what should
NovelSpeak build to become the obvious choice for "read any web novel aloud"?

**Answer in one line:** the market is split between cloud-AI subscription readers (billing trust is their weak
point) and file-first free readers (they cannot touch the live web). Nobody combines **on-device neural voices +
generic web extraction + auto-next-chapter + screen-off playback + no account**. NovelSpeak already has the hard
parts of that combination; what it lacks is product completeness (library, resume, ingestion, sleep timer).

**Corpus:** 39 competitor cards across three streams → **37 unique products** after de-duplication
(10 paid, 15 free/OSS/browser-native, 12 serialized-novel platforms), plus **4 APK teardowns** (@Voice 40.9.1,
NekoSpeak 1.5.0, Moon+ Reader 4.5.4 free, Legado metadata), a **repo ground-truth audit** of NovelSpeak 1.1.0,
a **merged feature matrix** of 38 products, and **240 sampled 1-star reviews** for six paid apps.
All sources and per-claim evidence live in the stream documents linked below; every number here is traceable to one.

---

## TL;DR — ten findings that should drive the roadmap

1. **Two clean price regimes.** Cloud-AI readers sell subscriptions — Speechify $29/mo or $139/yr
   (10M+, 4.0★), NaturalReader Lite $13.90/mo → Pro $25.90/mo (5M+, 4.3★), ElevenReader 10 free hours/mo then
   Ultra $11/mo or $99/yr (1M+, 4.5★), Readwise $12.99/mo, Listening $12.99/mo or $59/yr. Offline/document
   readers sell one-time licences — Legere $7.49, Speech Central $8.99, Moon+ Reader Pro $11.99 (1M+, 4.2★).
   Free giants prove the ceiling of the no-pay model: **ReadEra 50M+, 4.86★, no ads and no in-app purchases**,
   **@Voice 10M+, 4.28★**, **Audify 500K+, 4.45★**.
2. **NovelSpeak's README premise is factually wrong and must be fixed.** Google's current help doc for Chrome
   *Listen to this page* says: *"You can continue to listen to text even if your screen is locked."* The real
   Chrome gaps are **site coverage** (*"This feature isn't available yet on all websites."*) and the
   *"Make searches and browsing better"* data-sharing setting. Do not claim "Chrome dies when locked" in any
   store listing; reposition to **generic extraction on real novel sites + offline neural voices + no data sharing**.
3. **The winning combination is unoccupied.** Free offline neural TTS exists only in TTS-only apps (NekoSpeak MIT,
   HayaiTTS GPL-3.0) that cannot open a web page; free read-aloud browsers (Chrome, Edge, @Voice, Audify) use
   system or cloud voices; file-first readers (ReadEra, PocketBook, AlReader, Moon+) cannot open a URL at all.
   **NovelSpeak is the only product in the corpus with on-device neural voices + arbitrary-URL extraction +
   auto-next-chapter + screen-off playback + no account.**
4. **The paid market's wound is billing, not voices.** In 240 sampled 1-star reviews: ElevenReader 27/40 hit
   paywall/free-tier complaints ("putting a pay wall up has absolutely killed any enthusiasm"), NaturalReader
   21/40 ("They tell you to pay $10... Now it wants another $19"), Speechify 13/40 cancellation friction
   ("nearly impossible to cancel") and 17/40 attacking the per-page OCR ad promise. **Never copy meters,
   credit systems or stacked paywalls**; they are the fastest way to earn the same reviews.
5. **Novel platforms own catalogue, not the web.** Across 12 platforms (WebNovel, Wattpad, GoodNovel, Pocket
   Novel, Hinovel, JoyRead, Readink, AlphaNovel, Starynovel/Dreame, NovelUP, Light Novel app, AnyStories),
   monetisation is coins/VIP (Play IAP ranges $0.10–$359.99; chapter unlocks, weekly passes, $9.99–$19.99/mo
   memberships). Read-aloud is absent, beta, or visibly bad: WebNovel reviews report freezes and language
   switching, Wattpad TTS is English beta, JoyRead audio "stops after 3 chapters", NovelUP reviewer says
   "the audio part dosnt sound like english". **They cannot read arbitrary sites — that is NovelSpeak's wedge.**
6. **Legado's takedown is a roadmap constraint, not trivia.** The 47,154★ open-source novel reader's upstream
   repo was legally emptied on 2026-05-27 (README now carries a Chinese copyright-infringement notice; releases,
   F-Droid and Aptoide listings are gone; no licence file). **Do not build a book-source / rule-DSL scraper.**
   Stay a generic, page-scoped extractor with detect-don't-bypass behaviour. This is also the honest position
   for a public open-source app.
7. **@Voice 40.9.1 is the real architectural competitor to study.** Deep teardown: hidden WebView + injected JS
   bundle (ExtractBrowser/GetDisplayedHTML/**LoginWallDetect**) bridged to Java + jsoup; **six BYO-API-key cloud
   TTS providers** (Google, Azure, Polly, OpenAI, OpenAI-compatible, xAI); a **signed OTA model catalog** with
   minAppVersion gates and retirement; foreground mediaPlayback service; ~5,000 ad-mediation classes and 27
   permissions. Adopt the signed catalog, paywall **detection**, playback-exit diagnostics and separate-process
   extraction; avoid the ad stack, exported service and storage/package permissions.
8. **The free baseline is complete on files, weak on web reliability and novel ergonomics.** ReadEra/PocketBook/
   Moon+/AlReader cover PDF/EPUB/26 formats but no URLs. In the read-aloud apps, documented auto-next-chapter
   appears in only 3 of 16 cards where known; background playback death is a recurring complaint (Audify's own
   FAQ: "It cannot read aloud suddenly"; NaturalReader "playback keeps stopping as soon as I leave the app").
   **Reliability is the differentiator, not the feature list.**
9. **NovelSpeak already has unusual strengths and should market them.** Measured audio work (noise floor
   −51.6 → −80.3 dBFS), real streaming with 12 s generation-ahead at ~0.8–1.14× real time, a genuine
   EasyList/uBlock-style blocker (122,884 network rules, 22/22 self-test) that no read-aloud browser in the
   corpus ships, in-process system-TTS PCM (why headset keys work on the zero-download engine), and a
   **15.25 MB APK with zero ad/TTS/analytics SDKs** versus @Voice's 73.7 MB and NekoSpeak's 94.5 MB.
10. **The gap is product completeness, not capability.** No persisted reading position (cursor is in-memory;
    only last_url + 8 recent URLs survive), no library/history/bookmarks, no share-sheet or clipboard ingestion,
    no sleep timer, no reader typography/dynamic type, no pitch/volume controls, no audio export, no
    Android Auto. The per-site "Reader mode" toggle actually writes the **global** preference — a correctness
    bug — and the 2.5× speed UI silently clamps to 2.0× in both engines. These are small, high-impact fixes.

---

## 1. What is in this package

| File | What it is |
|---|---|
| **README.md** (this file) | Executive synthesis, market map, strategy, risks, evidence ledger |
| **feature-matrix.md** | 38-product matrix (paid/free/novel/ours) + capability prevalence + CSV twin |
| **enhancement-roadmap.md** | Prioritised build plan (P0/P1/P2) with code anchors, size, risk, acceptance |
| **paid-competitors.md** | 10 paid apps: pricing, capability matrices, 240-review pain synthesis, deep dives |
| **free-competitors.md** | 15 free/OSS/browser-native products, free baseline, licence map |
| **novel-platforms.md** | 12 serialized-novel platforms: TTS reality, coins/VIP, UX patterns, structural gaps |
| **reverse-engineering.md** | Lawful APK teardown: @Voice, NekoSpeak, Moon+, Legado; adopt/avoid, licensing, repro commands |
| **novelspeak-audit.md** | Ground-truth audit of NovelSpeak 1.1.0: architecture, threads, capabilities, performance, 20 gaps, top-15 candidates |
| **data/** | Machine-readable: paid.json, free.json, novel.json, re.json, novelspeak.json, play-metadata.json (39 listings), feature-matrix.csv, apps-normalized.json |
| **evidence/re/** | Hashes, manifest excerpts, package censuses, verbatim string excerpts for the teardown |
| **../.archify/dataflow-novelspeak-pipeline-.../pipeline.html** | Interactive pipeline diagram (validated, browser-checked) |
| **../.archify/architecture-novelspeak-v12-.../target.html** | Target architecture for the roadmap (design) |

**How to use it:** read the TL;DR and section 9 for decisions; use the matrix for feature comparisons; use the
roadmap for execution; use the stream docs when you need the source behind a number.

---

## 2. Method and evidence rules

| Stream | What was studied | Primary evidence | Limits |
|---|---|---|---|
| Paid | Speechify, NaturalReader, ElevenReader, Readwise, Legere/Voice Dream, Moon+ Pro, Speech Central, Listening, Evie, TTS Reader Pro | Official pricing/help pages, Play listings, 6×40 one-star reviews via Play | 1-star, US storefront, keyword counts are directional |
| Free | @Voice, ReadEra (+Premium), Librera, AlReader, PocketBook, Prestigio, Audify, Listening, Google Reading mode, Edge Read aloud, Chrome Listen, Legado, KOReader, NekoSpeak/HayaiTTS (repo docs) | Play raw HTML, official help docs, vendor sites, GitHub/F-Droid APIs | No per-app review sampling; installs are Play buckets |
| Novel platforms | WebNovel, Wattpad, GoodNovel, Pocket Novel, Hinovel, JoyRead, Readink, AlphaNovel, Starynovel, NovelUP, Light Novel reader, AnyStories | Play + Apple US IAP lists, official support pages, recent reviews | Several TTS engines unknown; prices are the store's SKU list, not a plan name |
| APK RE | @Voice 40.9.1, NekoSpeak 1.5.0, Moon+ Reader free 4.5.4, Legado metadata | Official dev site / GitHub Release / Aptoide public listing; apktool, aapt2, smali census, jadx available | Audify/Speechify/ElevenReader: no lawful APK channel → Unknown. No runtime execution, no network calls, no DRM/licence code exercised |
| Ours | NovelSpeak 1.1.0 at commit 89e4809 | Full source, manifest, build, APK hashes, repo evidence logs | Single-device evidence; no independent re-measurement |

**Rules applied throughout:** every claim is tagged Observed / Inferred / Unknown in the stream docs;
quotes are ≤25 words; pricing is what the page displayed on **2026-10-24** in the US storefront; no accounts
were created, no paywalls bypassed, no cracked/modded APKs, no GPL/AGPL code copied. Architecture/UX patterns
are facts and fair to learn from; code, assets, rule packs, model weights and voice datasets are not.
NovelSpeak's own pitch error (finding 2) is the kind of correction this discipline exists to catch.

**Popularity cross-check:** a separate 39-listing Play snapshot (data/play-metadata.json) was parsed directly
from Play HTML for installs, rating, rating count, last-update date, ads badge and IAP range, so the matrix does
not depend on any single stream's numbers.

---

## 3. Market map and 2026 signals

### 3.1 Tiers

| Tier | Products (examples) | Scale | Price anchor | What they sell |
|---|---|---|---|---|
| Cloud AI reader | Speechify, NaturalReader, ElevenReader, Readwise, Listening | 10M+/5M+/1M+/100K+ | $9–$29/mo, $59–$159/yr | Voice quality, OCR/import, cross-device library, AI extras |
| One-time document reader | Legere, Speech Central, Moon+ Pro | 10K+/100K+/1M+ | $7.49–$11.99 one-time | Format breadth, accessibility, no subscription |
| Free file reader | ReadEra 50M+, PocketBook 10M+, AlReader 5M+, Prestigio 10M+ | 5M–50M+ | free | Reading experience, formats, offline libraries |
| Free read-aloud | @Voice 10M+, Audify 500K+, browser-native Chrome/Edge | 500K–10M+ | free / ads / $5.99–$15 unlock | Web/file reading with TTS |
| Novel platform | WebNovel, Wattpad 100M+, GoodNovel 10M+, JoyRead 10M+ | 10M–100M+ | coins + $9.99–$19.99/mo VIP | Serialized catalogue, chapter unlocks, community |

### 3.2 2026 signals worth acting on (headline scan via Google News RSS, 2026-10-24)

- **ElevenLabs valued at $22B** (Reuters/TechCrunch, 2026-09-30) and **ElevenReader added 200K audiobooks +
  a premium tier** (Publishers Weekly, 2026-05-28): AI voice is consolidating into a well-funded content
  platform, not a feature. NovelSpeak should not fight it head-on; it should own the local, no-account side.
- **Speechify launched voice typing** (Jun 2026) and keeps pushing real-time voice; its rating is still
  **4.0** across 284K ratings — scale does not fix trust.
- **The Consumer Federation of America filed a complaint against Speechify over AI voice-cloning impersonation
  scams** (2026-07-27). Cloning is a live legal/ethical risk: ship user-recorded/imported clones only, with
  consent, no celebrity packs (NekoSpeak's celebrity dataset is a case study in what not to copy).
- **Pocket shut down and readers are re-homing** (TechCrunch, 2026-08-14). Read-it-later demand is up for grabs;
  a share-sheet + library + resume experience is a credible land-grab for NovelSpeak.

*These are headline-level signals from a news scan; the linked stream docs carry the store/doc evidence that the
matrix and roadmap actually rest on.*

---

## 4. Positioning correction (do this first)

The current README says NovelSpeak exists because Chrome's *Listen to this page* "fails on novel sites and stops
when the screen locks". The second half is no longer true, and the first half is only half true:

| Claim | Reality (Observed, 2026-10-24) | Defensible replacement |
|---|---|---|
| "Chrome stops when screen locks" | Google help: "You can continue to listen to text even if your screen is locked." | Do not use. |
| "Chrome fails on novel sites" | Google help: "This feature isn't available yet on all websites." | **"Chrome only reads some pages; NovelSpeak reads the chapter you are on, on any site."** |
| "Chrome is private" | Chrome feature requires the "Make searches and browsing better" data-sharing setting | **"No data-sharing switch, no account, no telemetry — it works in airplane mode."** |
| "Free apps don't have natural voices" | Free apps use system or cloud voices; free offline neural exists only in TTS-only apps | **"Neural voices that work offline, on the page you are reading."** |

**Recommended positioning sentence:**
*"Read any web novel aloud, with studio-quality voices that work offline, screen-off controls that keep going,
and no account, no meter, no telemetry."*
This is defensible across every stream in the corpus: paid apps cannot match the no-account/no-meter/offline
combination; free readers cannot read arbitrary URLs; novel platforms cannot leave their catalogue.

---

## 5. What competitors do better (and are worth copying)

| # | Pattern | Who proves it | Evidence | NovelSpeak status |
|---|---|---|---|---|
| 1 | Persisted position + resume | all paid readers | Moon+/Readwise sync; @Voice lists per-item progress | **absent** (cursor in-memory) |
| 2 | Library / reading list | @Voice, ReadEra, Moon+, every novel app | Play descriptions + deep dives | **absent** (8 recent URLs only) |
| 3 | Sleep timer | ElevenReader, Evie, @Voice (alarm receiver) | paid matrix + free report | **absent** |
| 4 | Speed range 3–5× with natural pitch | ElevenReader 4×, Speechify 4.5–5×, Legere 50–700 wpm | paid docs | partial (0.5–2.0× effective) |
| 5 | Word-level read-along highlight | ElevenReader, NaturalReader, Legere | paid matrix; Speech Central regressed and got hammered | sentence-level only |
| 6 | Pronunciation dictionary / replacement rules | ElevenReader, NaturalReader, Legere, @Voice, Audify | paid/vendor docs | **absent** |
| 7 | Share-sheet / clipboard / ACTION_PROCESS_TEXT ingestion | @Voice "add to list", Speech Central share-to | RE + free docs | **absent** (browser only) |
| 8 | Audio export (WAV/MP3) | @Voice (lame), Audify WAV, TTS Reader WAV | RE + free docs | **absent** |
| 9 | Paywall/login **detection** with honest warning | @Voice LoginWallDetect | RE section 4.3 | **absent** |
| 10 | Signed OTA model/voice catalog | @Voice CloudTtsConfig.txt | RE section 4.4 | hardcoded hashes |
| 11 | Background-playback soak + diagnostics | paid reviews show this is where rivals fail | 240-review synthesis | partial (works, but no soak/exit taxonomy) |
| 12 | Reader typography / dynamic type / themes | Legere (80pt, OpenDyslexic), Moon+ | paid docs | fixed 17 px dark only |

**Where the bar is lower than it looks:** only 5 of 6 apps with a documented sleep timer in the corpus show it
at all; documented auto-next-chapter exists in 3 of 16 read-aloud cards where known; and several paid apps ship
screen-off resume badly. The features are cheap to beat *if shipped reliably*.

---

## 6. Where NovelSpeak already leads (validated by the audit)

1. **Generic extraction that actually works on novel sites** — Readability + density fallback + live-DOM
   re-tagging + four next-link strategies incl. CJK and numeric +1 (extract.js). No free/paid app in the corpus
   combines arbitrary-URL extraction with auto-next-chapter except @Voice/Audify, and neither has neural voices.
2. **On-device neural voices with a working streaming pipeline** — Kokoro 11 voices + Pocket zero-shot clone,
   12 s / 8-sentence generation-ahead, measured ~0.8–1.14× real time on the reference phone.
3. **Both engines own their audio in-process** — system TTS is rendered via synthesizeToFile→PcmPlayer, which is
   why headset keys and lock-screen controls work on the zero-download engine. @Voice and NekoSpeak do not do this.
4. **A real ad-blocking engine** — token/host-indexed EasyList/EasyPrivacy/uBO with cosmetic, procedural filters,
   scriptlets, surrogates and a 22/22 self-test. None of the read-aloud browsers in the corpus ships this.
5. **Measured audio quality** — noise floor −51.6 → −80.3 dBFS with speech level unchanged; the kind of evidence
   paid competitors do not publish.
6. **Trust posture** — 15.25 MB APK, zero analytics/ads/TTS SDKs, 5 permissions, no account, model downloads
   SHA-256 pinned. @Voice: 73.7 MB, 27 permissions, ~5,000 ad classes; NekoSpeak: 94.5 MB payload.
7. **A hermetic build** — reproducible on the phone, no Gradle/AndroidX; rare and worth protecting.

---

## 7. Reverse-engineering findings (lawful teardown)

Full detail + hashes + reproduction commands: **reverse-engineering.md**.

| App | Obtained | One thing that matters |
|---|---|---|
| **@Voice 40.9.1** (73.7 MB) | official dev-site APK, sha256 7f48c57a… | Extraction is WebView + injected JS bundle, **login-wall detection**, jsoup post-processing; **6 BYO-key cloud TTS**; signed OTA model catalog; 27 permissions; ~5,000 ad-mediation classes; exported playback service. |
| **NekoSpeak 1.5.0** (94.5 MB) | GitHub Release, sha256 0108360a… | It is a **system TextToSpeechService**, not a reader: ONNX runtime + Piper/Kokoro/Pocket, Kotlin Misaki G2P, zero ads/trackers, MIT. |
| **Moon+ Reader free 4.5.4** (12.8 MB) | Aptoide public listing, sha256 e71fab68… | File-only incumbent: 6 permissions, targetSdk 26, **no MediaSession/FGS**, activity-scoped TTS, legacy billing v1 — the thing NovelSpeak displaces. |
| **Legado** | no lawful APK | Upstream emptied 2026-05-27 over copyright infringement; releases/listings gone. **Do not clone its book-source engine.** |
| Audify / Speechify / ElevenReader | no lawful channel found | Play metadata only; internals Unknown. |

**Adopt (ranked):** signed OTA model catalog; paywall/login detection; playback-exit diagnostics taxonomy;
separate-process extraction with dataSync FGS; per-language normalisation data; support-report bundle;
optional BYO-key cloud TTS (only if ever needed); share-target "add to list".
**Avoid:** book-source rule DSL; ad-mediation SDKs; MANAGE_EXTERNAL_STORAGE/QUERY_ALL_PACKAGES; exported
playback service; targetSdk laziness; unmaintained single-URL model supply.

**Licensing boundary:** @Voice/Moon+ proprietary (analyse, don't copy); NekoSpeak MIT (architecture fact, not
code); Legado deleted (no reuse); novel-platform catalogues are licensed content (never scrape/store).
Readability is Apache-2.0 and already used. No GPL/AGPL code. This is the same discipline the repo already
documented for HayaiTTS/NekoSpeak.

---

## 8. Feature matrix: the numbers that matter

From **feature-matrix.md** (known-only denominators; 37 competitors):

| Capability | Yes | Known | Share | Read |
|---|---:|---:|---:|---|
| Library | 18 | 20 | 90% | table stakes |
| Progress sync | 12 | 19 | 63% | expected in paid tier |
| Read-along highlight | 9 | 16 | 56% | table stakes for paid |
| Offline natural voices | 11 | 20 | 55% | usually system-voice, not neural |
| PDF ingestion | 19 | 37 | 51% | file readers own this |
| EPUB ingestion | 20 | 37 | 54% | file readers own this |
| Web URL ingestion | 15 | 37 | 41% | **NovelSpeak's home turf** |
| Documented screen-off | 7 | 11 | 64% | claimed, often broken |
| No account required | 7 | 24 | 29% | rare and valuable |
| Auto-next chapter | 3 | 16 | 19% | **rare — differentiator** |
| Voice cloning | 3 | 33 | 9% | novelty; handle ethically |
| Sleep timer | 5 | 6 | 83% | small known sample, but cheap parity |

**Reading the matrix:** NovelSpeak should not chase PDF/EPUB breadth yet (free incumbents win on formats);
it should close the library/resume/sleep-timer gap and double down on what only it has (URL + neural + auto-next
+ no account).

---

## 9. Strategy

### 9.1 What to build (summary — detail in the roadmap)

| Priority | Theme | Items | Why now |
|---|---|---|---|
| **P0** | "An app you can live in" | persist position + Continue reading; library/history; share-sheet/clipboard ingestion; true per-site settings (bug); sleep timer + fade; fix speed UI clamp; WebView/backup/receiver hardening; positioning rewrite + offline proof | smallest changes with the largest retention/trust effect; every paid competitor has these, and @Voice's share target is a cheap acquisition path |
| **P1** | "Parity plus" | chapter prefetch; pronunciation dictionary; reader typography/theme/dynamic type; word-level highlight; volume boost/skip silence/pitch; audio export (WAV); bookmarks; reading stats; signed OTA model catalog; playback diagnostics/support bundle | matches paid-tier expectations without subscriptions or cloud |
| **P2** | "Breadth and platform" | EPUB import; PDF (text-layer) import; translation/dictionary hand-off; tablet/two-pane; optional encrypted sync; optional BYO-key cloud voices; Play listing with billing + ABI split; Android Auto | expands market only after the core loop is complete |

### 9.2 Monetisation (evidence-based)

- **Recommended:** a **one-time Play "Pro" unlock at $4.99–$6.99** with everything included, and keep the
  Apache-2.0 source/free APK available. One-time peers sit at $7.49–$11.99 with fewer bundled voices; a lower
  price with 11 Kokoro voices + Pocket cloning is a clear value claim, and it avoids the billing-trust damage
  that dominates paid-app reviews.
- **Optional:** voice/model packs at $1.99–$3.99 (only if the base stays generous); a "supporter" donation tier.
- **Never:** subscriptions for local inference, credits/meters, per-book or per-library paywalls, ads in the
  listening experience. Every one of those is a top review-damage theme.
- **Distribution:** GitHub APK (current), F-Droid (source build), Play (billing + ABI splits). Keep the free build
  fully featured; Pro can add convenience or support, not gate the core promise.

### 9.3 Positioning and launchproof

1. Rewrite the premise: **any chapter, offline neural voices, screen-off, no account/data/meter.**
2. Ship a visible **"offline proof"**: airplane-mode badge/demo and a privacy page listing the only network
   destinations (pages you open, model/filter downloads).
3. Publish a **reliability claim with a soak test**: 2-hour screen-off playback, battery-optimisation guidance,
   and the already-measured audio numbers.
4. Keep the **ad blocker** as a headline differentiator (no read-aloud browser in the corpus ships list-based
   blocking).
5. Target the **serialized-novel reader** who is currently fighting ads and TTS bugs on novel sites; do not
   compete with WebNovel/Wattpad catalogues.

---

## 10. Risks and open questions

| Risk | Evidence | Mitigation |
|---|---|---|
| **Copyright/scraping liability** | Legado upstream deleted 2026-05-27; @Voice ships login-wall detection rather than bypass | generic page-scoped extraction only; no book-source catalog; detect paywalls and warn; respect robots/login walls; no content storage/redistribution |
| **Voice-cloning impersonation** | CFA complaint against Speechify (2026-07-27); NekoSpeak celebrity packs | user-recorded/imported clones only, explicit consent, on-device, "cloned" label, delete wipes all artefacts |
| **Play policy** (foreground service, notifications, storage, accessibility) | @Voice carries heavy permissions plus ad SDKs; Moon+ is targetSdk 26 | stay at targetSdk 34+, keep 5 permissions, no MANAGE_EXTERNAL_STORAGE, document FGS type, no misleading claims |
| **GPL/AGPL contamination** | HayaiTTS GPL-3.0, Librera GPL-3.0, KOReader AGPL-3.0 | Apache-2.0 repo stays clean; learn architecture, never copy code/assets |
| **Model supply chain** | NekoSpeak pulls from GitHub releases + HF datasets + an HF Space | signed catalog + mirrors + hashes (the @Voice pattern) |
| **Scope creep** | paid rivals' feature lists (OCR, AI suites, stores) are expensive and heavily criticised | ship the P0 loop first; say no to OCR book scanning, audiobook stores, AI study suites |

**Open questions:** real-device Chrome/Edge lock-screen parity check before publishing the claim; ReadEra free
read-aloud scope (foreground-only?) worth one device test; Audify ad-free price and screen-off behaviour
(no lawful APK); whether Play distribution is intended now or after the P0 loop; target markets/languages for
the first Play listing.

---

## 11. Evidence ledger

| Claim | Source | Confidence |
|---|---|---|
| Pricing and store scale | paid-competitors.md section 2, data/play-metadata.json | high (official pages / Play, 2026-10-24) |
| Review-pain counts | paid-competitors.md section 4 (6×40 one-star reviews) | medium (directional keyword counts, US, 1-star only) |
| Free baseline and Chrome/Edge facts | free-competitors.md sections 2 and 7; Google help doc 14768725 | high (official docs) |
| Novel-platform monetisation and TTS reality | novel-platforms.md, data/novel.json | medium-high (store/IAP/support pages; several TTS engines unknown) |
| APK architecture claims | reverse-engineering.md sections 3–11 + evidence/re/ | high (static analysis, hashes recorded; no runtime) |
| NovelSpeak capability anchors | novelspeak-audit.md (file:line) | high (source read in full) |
| Market context (valuations, launches) | headline scan 2026-10-24 (Google News RSS) | medium (headline-level, not independently fetched article text) |

**Explicit unknowns kept as unknowns:** Speechify/ElevenReader/Audify internals; Legado APK internals;
Readwise/Listening TTS voice counts; NaturalReader free-tier cap (review-inferred); TTS Reader Pro tiers;
exact install bases (Play buckets only); regional pricing.

---

*Generated by the Agent Team on 2026-10-24. Streams: paid-intel, free-intel, novel-intel, apk-re, app-audit;
synthesis, matrix and diagrams by the Lead.*
