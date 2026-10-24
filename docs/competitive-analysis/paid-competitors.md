# Paid & subscription TTS-reader competitors — evidence pack

**Stream:** paid (paid-primary + popular paid tiers) · **Prepared:** 2026-10-24 · **Analyst:** paid-intel
**Machine-readable twin:** [data/paid.json](data/paid.json) (10 competitor cards + 21 sources, shared schema)
**Raw captures (not committed):** `/tmp/competitive/paid/` — Play HTML snapshots, pricing pages, 6 × 40-review JSON pulls.

Every factual claim below is tagged Observed (seen in store listing / official page / review text),
Inferred (reasoned from observed data) or Unknown (not found). All source URLs and accessed dates are
in the [Sources appendix](#sources-appendix); inline markers [S1]…[S27] map to it. Quotes are verbatim and
≤25 words. Pricing is USD as displayed on 2026-10-24 in the US storefront.

---

## 0. TL;DR — ten findings that should drive the roadmap

1. **The paid market has split into two price regimes.** Cloud-AI readers sell subscriptions at
   **$9.99–$29/month** (Readwise $9.99/mo billed yearly → Speechify $29/mo); offline/document readers sell
   **one-time licences at $7.49–$11.99** (Legere $7.49, Speech Central $8.99, Moon+ Pro $11.99) [S2][S11][S15][S17][S20].
2. **Free tiers are shrinking and users punish it.** 27/40 sampled ElevenReader 1-star reviews and 21/40
   NaturalReader 1-star reviews contain paywall/free/subscription language (keyword count); ElevenReader's
   2025 paywall shift is the single most-cited grievance in its recent low-star corpus [S7][S10].
3. **Word-level read-along highlighting is table stakes** for the subscription tier: ElevenReader,
   NaturalReader and Legere all advertise synchronized highlighting; Speech Central was slammed after
   dropping word-level for sentence-level ("words do not highlight individually anymore") [S11][S4][S8][S22].
4. **Cloud TTS + no account is not offered by any paid competitor.** Every cloud-AI reader requires an
   account (Speechify, NaturalReader, ElevenReader, Readwise, Listening); no-account readers rely on
   system/offline voices by default (Moon+, Legere, Evie) or bring-your-own cloud keys (Speech Central)
   [S1][S4][S8][S14][S24][S17][S11][S27][S20]. That gap is NovelSpeak's structural opening.
5. **Offline neural voices already exist in a paid competitor.** Speech Central ships a free
   "Speech Central Pack: Kokoro" offline voice app alongside its $8.99 one-time reader [S20][S21].
   NovelSpeak's Kokoro/Pocket pipeline is therefore differentiated by *integration + zero setup*, not by existence.
6. **Speechify's most damaging ad claim is the OCR one.** 17/40 sampled 1-star Speechify reviews contain
   scan/OCR/camera language, overwhelmingly attacking the scan-a-book-cover pitch — the app reads only
   photographed pages ("you have to scan each individual page of my book") [S1][S3]. Camera OCR is an
   expensive, badly-served feature — not a required one.
7. **Background/screen-off playback is where paid apps visibly fail.** NaturalReader's loudest functional
   complaint is playback stopping on screen lock/app switch [S7]; Moon+ Pro users report the same and add
   offline Play-licence checks that block reading [S19]. NovelSpeak already ships this as a core feature.
8. **Web-page ingestion is rare among paid readers.** Only Speechify, NaturalReader, ElevenReader,
   Readwise and Listening accept a URL/page input; Moon+, Legere and Evie are document-first [S1][S4][S8][S14][S24][S17][S11][S27].
   Generic *novel-site* extraction + auto-next-chapter remains an uncontested niche in this set.
9. **One-time pricing anchors are $7.49–$11.99; annual subscription anchors are $59–$159.**
   A paid NovelSpeak unlock in the **$3.99–$6.99 one-time** band would undercut all paid peers while
   staying above the "free-with-ads" tier that reviewers distrust [S11][S17][S20][S25][S5].
10. **Privacy claims are becoming a paid differentiator.** Moon+ Pro and Speech Central both advertise
    "no data collected"; Speech Central's site leads with "no subscription. No ads. No data collection."
    Both sit at 4.2–4.4 stars, competitive with account-based peers [S17][S20][S21].

---

## 1. Method & evidence discipline

| Item | Detail |
|---|---|
| Store data | Google Play details pages fetched directly (curl, US storefront, `hl=en&gl=US`), 2026-10-24. Installs are Play buckets ("10M+"), ratings and counts are as rendered. |
| Pricing | Official pricing pages / help-centre pricing articles; where a page renders only one billing toggle, the other period was read from embedded page data and is marked as such. |
| Reviews | 6 × 40 one-star reviews pulled via Firecrawl's Play review provider: Speechify, NaturalReader, ElevenReader (required sample) + Legere, Moon+ Pro, Speech Central. Sorted by date; ranges shown in §4. Quoted text is verbatim from the pull. |
| Credits | 34 Firecrawl credits total (6 × 5-credit review pulls + 2 × 2-credit searches). Within the ≤120 budget. |
| Evidence tags | **Observed** = present in the store listing/official page/review text. **Inferred** = derived (e.g., free cap only from reviews). **Unknown** = not published/reachable. |
| Limits | Play install counts are tiers not exact values; IAP "ranges" are Google's per-item min–max across all SKUs (may include legacy SKUs). Region-priced totals vary. No accounts created, no paywalls touched. |

---

## 2. Market structure and pricing positioning

### 2.1 Store facts (Observed, 2026-10-24)

| App | Package | Android installs | Rating (count) | Last update | Monetisation badge |
|---|---|---|---:|---|---|
| Speechify | `com.cliffweitzman.speechify2` | 10M+ | 4.0 (284K) | 2026-10-20 | In-app purchases |
| NaturalReader | `com.naturalsoft.personalweb` | 5M+ | 4.3 (60.3K) | 2026-10-14 | In-app purchases |
| ElevenReader | `io.elevenlabs.readerapp` | 1M+ | 4.5 (70.2K) | 2026-10-21 | In-app purchases |
| Readwise Reader | `com.readermobile` | 100K+ | 4.3 (583) | 2026-10-08 | In-app purchases |
| Moon+ Reader Pro | `com.flyersoft.moonreaderp` | 1M+ | 4.2 (107K) | 2026-10-17 | Paid app, no IAP |
| Speech Central | `com.labsiisoftware.speechcentral` | 100K+ | 4.4 (1.63K) | 2026-10-17 | In-app purchases |
| Listening | `io.listening.main` | 100K+ | 4.0 (7.31K) | 2026-10-14 | In-app purchases |
| Evie | `com.lenntt.evoicereader` | 100K+ | 4.3 (4.28K) | 2026-10-05 | In-app purchases |
| TTS Reader Pro | `voice.reader.ai` | 10K+ | 4.6 (796) | 2026-09-26 | In-app purchases + **Contains ads** |
| Legere Reader | `voicedream.reader` | 10K+ | 3.3 (987) | 2026-06-02 | Paid app + IAP |

Sources: [S1][S4][S8][S14][S17][S20][S24][S27][S23][S11]. Reach does not map cleanly to price model:
the three highest-install cloud apps (Speechify 10M+, NaturalReader 5M+, ElevenReader 1M+) are
subscriptions, but Moon+ Reader Pro proves a one-time $11.99 app can also reach 1M+ installs.
**Card confidence** (see `data/paid.json`): high for Speechify, NaturalReader, ElevenReader, Legere,
Moon+, Speech Central; medium for Readwise, Listening, Evie; low for TTS Reader Pro.

### 2.2 Price points and packaging (Observed unless noted)

| App | Free tier | Monthly | Annual | One-time | Notes |
|---|---|---|---|---|---|
| Speechify | 1.5x speed, 10 "robotic sounding" voices, TTS only [S2] | $29 | $139 | — | Play IAP $0.49–$139.99; 3-day trial + card reported by users (Inferred) [S1][S3] |
| NaturalReader | "Get started for free"; reviews report ~5 min/day cap (Inferred) | Lite $13.90 · Plus $20.90 · Pro $25.90 | Lite $79 · Plus $119 · Pro $159 | — | Official help-centre article, updated 2026-08-13 [S5] |
| ElevenReader | 10 h/month text-to-audio, 1,000+ voices, 4x speed, sleep timer [S9] | $11 (Ultra) | $99 ($8.25/mo) | — | Ultra: unlimited import listening, 200K+ premium books, offline downloads [S9] |
| Readwise Reader | 30-day trial, no card upfront [S14] | $12.99 Full / $8.99 Reader-only | $119.88 ($9.99/mo) Full · $95.88 ($7.99/mo) Reader-only | — | Highlights-only plans $7.99/$5.59 per mo, yearly [S15] |
| Listening | 2 voices, 1.2x cap, 3 papers/week, PDF/URL only [S25] | $12.99 | $59 ($4.92/mo) | — | 3-day trial, card required; "SAVE 62%" yearly [S25] |
| Legere Reader | none | — | — | $7.49 | Voice IAPs $3.99–$4.99 (Acapela, 24 languages) [S11] |
| Moon+ Reader Pro | none (separate free app) | — | — | $11.99 | 30-day money-back guarantee stated [S17] |
| Speech Central | free with daily/monthly add caps; free for blind users & schools [S20] | — | — | $8.99 | "One-time purchase. No ads. No data collection." [S21] |
| Evie | full app free with system TTS, no ads [S27] | — | — | pay-as-you-go IAP $0.99–$33.99 | Polly cloud voices + translation cost credits [S27] |
| TTS Reader Pro | "unlimited hours" claimed, ads present [S23] | Unknown | Unknown | — | IAP $2.99–$99.99; tiers not published [S23] |

**Packaging observations.**
- Subscription prices cluster in three bands: **$8.99–$12.99** (reader-first), **$20.90–$29** (AI-suite upsell), **$59–$159/yr**.
- Trials are short where the product is cloud-cost-heavy (3 days: Speechify, ElevenReader, Listening) and
  long where the product is a workflow tool (30 days: Readwise). NovelSpeak has no marginal inference cost,
  so a *generous* or no trial is economically rational.
- The only ads-supported paid-adjacent card is TTS Reader Pro — a review-quality risk and a signal that
  "cheap + ads" is the low end of this market [S23].

### 2.3 Price anchors worth stealing (and avoiding)

| Anchor | Observed value | Implication for NovelSpeak |
|---|---|---|
| One-time app price | $7.49 / $8.99 / $11.99 | A $4.99–$6.99 one-time Play unlock is defensible; $11.99 is the ceiling for a document reader. |
| Entry subscription | $9.99–$12.99/mo | Any subscription would need cloud value NovelSpeak deliberately does not have. |
| Annual | $59–$159 | $59 (Listening) is the value floor; $139 (Speechify) the brand ceiling. |
| Voice/OCR add-ons | $3.99–$4.99 per voice pack (Legere) | Users accept micro-IAPs for voice content, not for basic playback. |
| Library add-on | ~$25 reported (Speechify, Inferred) [S3] | Bundling a library behind a second paywall is a review landmine. |

---

## 3. Capability matrices

### 3.1 TTS engine, voices, offline

| App | Engine type | Engines | Voice count | Languages | Offline | Cloning |
|---|---|---|---|---|---|---|
| Speechify | cloud | proprietary AI voices | 1,000+ (Premium) | 60+ | Unknown | Unknown |
| NaturalReader | hybrid | AI cloud (Gemini/ChatGPT/Azure per site) + device voices | 1,000+ | 90–100+ | Partial (MP3 export; streaming needs net) | Yes, 2 voices (Plus/Pro) |
| ElevenReader | cloud | ElevenLabs AI | 1,000+ | 30+ | Partial (downloaded audio, Ultra) | "Designed" voices |
| Readwise Reader | cloud | Readwise AI | Unknown | Dozens | Partial (reading offline; TTS undocumented) | No |
| Legere Reader | hybrid | Android TTS + Acapela packs | 1 device + Acapela (24 langs) | ~24 | **Yes** | No |
| Moon+ Reader Pro | system | Android system TTS | Device engines | Device languages | **Yes** | No |
| Speech Central | hybrid | system/offline third-party + **Kokoro offline pack** + BYO cloud keys (OpenAI/Azure/Google/xAI) | Depends on engine | Depends on engine | **Yes** | No |
| Listening | cloud | Listening AI voices | 2 free + paid | Unknown | Unknown | No |
| Evie | hybrid | Android TTS + Amazon Polly | Device + Polly | Device + 70+ (translation) | **Yes** (system path) | No |
| TTS Reader Pro | cloud | unnamed premium AI | Unknown | 50+ | Unknown | No |

Sources: [S2][S6][S9][S16][S11][S17][S20][S25][S27][S23].

**Read-throughs.** Cloud voice quality is the paid market's core value, and it is the one thing
NovelSpeak deliberately replaces with on-device neural voices. The competitive bar for offline quality is
Kokoro — already shipped by Speech Central as a free companion [S20], and by NovelSpeak. Read-aloud
*speed*: paid apps advertise 4x (ElevenReader), 4.5–5x (Speechify), 50–700 wpm (Legere) [S9][S1][S11].

### 3.2 Inputs and extraction

| App | Web URL / page | PDF | EPUB | Office/other | Camera OCR | Read-along highlight | Auto-scroll |
|---|---|---|---|---|---|---|---|
| Speechify | Yes (+extension, web app) | Yes | — | Docs, email, Drive/Dropbox/OneDrive | Yes, per page | Yes | Unknown |
| NaturalReader | Yes (Chrome extension) | Yes | Yes | DOCX/PPTX/Numbers/Pages | Yes | Yes | Unknown |
| ElevenReader | Yes (links/articles) | Yes | Yes | DOCX, MD, TXT | Unknown | Yes | Unknown |
| Readwise Reader | Yes (articles, RSS, newsletters, X, YouTube) | Yes | Yes | — | OCR to capture physical books (marketing) | Unknown | Unknown |
| Legere Reader | Files only (built-in browser downloads) | Yes | DRM-free | DOC/DOCX/RTF/MOBI→ePub, DAISY | No | Yes (word/line) | Yes |
| Moon+ Reader Pro | Files/OPDS only | Yes | Yes | AZW3/MOBI/FB2/CHM/CBZ/DOCX/ODT/MD… | No | Partial | Yes (5 modes) |
| Speech Central | Yes (headlines, articles, RSS, share-to) | Yes | Yes | Documents, Instapaper | No | Partial (sentence) | Unknown |
| Listening | Yes (URL + extension) | Yes | Yes | Word, MOBI, paste, camera pages | Yes | Yes | Unknown |
| Evie | Files only | Yes | Yes | MOBI/AZW/FB2/DOCX/RTF/HTML/TXT | No | Unknown | Unknown |
| TTS Reader Pro | Yes (web pages) | Yes | Yes | TXT | Yes ("Scan & Listen") | Unknown | Unknown |

Sources: [S1][S4][S8][S14][S11][S17][S20][S24][S27][S23].

**Structural insight.** Paid apps are strongest at *documents you already own* and weakest at *the live
web*. Speechify/NaturalReader/ElevenReader accept a URL only as a one-shot import, not as a browser; none
of them can follow a serialized novel's next-chapter link. Moon+/Legere/Evie cannot ingest a web page at all.
NovelSpeak's "any URL + auto-next-chapter + ad blocking" is a genuinely distinct product shape.

### 3.3 Playback, library, accessibility

| App | Screen-off | Media keys/headset | Sleep timer | Library/bookmarks | Progress sync | Accessibility posture |
|---|---|---|---|---|---|---|
| Speechify | Unknown | Unknown | Unknown | Yes (free cap ~5 files per reviews) | Yes, cross-device | Dyslexia/ADHD framing |
| NaturalReader | **Partial — reported to stop** | Unknown | Unknown | Yes | Yes (account) | Dyslexia font, captions |
| ElevenReader | Unknown | Unknown | **Yes** | Yes | Yes (web+mobile) | ADHD/dyslexia highlighting |
| Readwise Reader | Unknown | Unknown | Unknown | Yes (first-class highlights) | Yes | Power-reader workflow |
| Legere Reader | **Yes** | Yes (remote/gestures) | Unknown | Yes | Partial (resume per doc) | Screen reader, braille, OpenDyslexic, 80pt |
| Moon+ Reader Pro | Partial (review issues) | Yes (Pro) | Unknown | Yes | Yes (Dropbox/WebDAV) | Night mode, eye care |
| Speech Central | **Yes** | Unknown | Unknown | Yes | Unknown | TalkBack auto-modes, ADHD/dyslexia modes |
| Listening | Unknown | Unknown | Unknown | Yes | Yes | Academic focus |
| Evie | **Yes** | Yes (Bluetooth/wired) | **Yes** | Yes | Unknown | Equalizer, language learning |
| TTS Reader Pro | Unknown | Unknown | Unknown | Unknown | Unknown | None published |

Sources: [S7][S10][S11][S17][S19][S20][S27].

### 3.4 Privacy and accounts

| App | Ads | Account required | Data-safety posture (Play self-report) |
|---|---|---|---|
| Speechify | No | **Yes** | Encrypted in transit; no third-party sharing; deletion supported |
| NaturalReader | No | **Yes** | Encrypted in transit; deletion supported |
| ElevenReader | No | **Yes** | Encrypted in transit; deletion supported |
| Readwise Reader | No | **Yes** | Encrypted in transit; no third-party sharing; deletion supported |
| Legere Reader | No | No | Encrypted in transit; no third-party sharing |
| Moon+ Reader Pro | No | No | **No data collected**; no sharing |
| Speech Central | No | No | **No data collected**; no sharing |
| Listening | No | Yes | Encrypted in transit; deletion supported |
| Evie | No | Optional (paid cloud/Wear OS) | Encrypted in transit; no third-party sharing |
| TTS Reader Pro | **Yes** | Unknown | Encrypted in transit; no third-party sharing |

Sources: Play Data-safety sections on [S1][S4][S8][S14][S11][S17][S20][S24][S27][S23] (self-reported by developers).

**What this proves.** "No account, no collection" is a live paid positioning (Moon+ Pro, Speech Central) and
it correlates with competitive paid-app ratings in this set (4.2 and 4.4, versus 4.3–4.6 for account-based
peers). It is also the cleanest defensible
claim NovelSpeak can make — but it must be *verifiable from the APK* (no trackers, no analytics SDKs).

---

## 4. Review-pain synthesis (primary data: 240 one-star reviews)

Six pulls × 40 one-star reviews each. Dates are the date range of the 40 sampled reviews after sorting.
Counts below are keyword-hit counts across the 40 texts — directional, not a coded survey.

| App | Sample dates | cancel/refund | scan/OCR | skips text | paywall/free | crashes/stops | voice issues |
|---|---|---:|---:|---:|---:|---:|---:|
| Speechify | 2025-08-02 → 2026-09-18 | 13/40 | **17/40** | 8/40 | 19/40 | 4/40 | 7/40 |
| NaturalReader | 2022-02-03 → 2026-09-12 | 4/40 | 2/40 | 2/40 | 21/40 | 3/40 | **23/40** |
| ElevenReader | 2024-08-19 → 2026-09-15 | 4/40 | 1/40 | 3/40 | **27/40** | 8/40 | 7/40 |
| Legere Reader | 2018-09-23 → 2025-12-16 | 8/40 | 1/40 | 1/40 | 2/40 | 7/40 | **14/40** |
| Moon+ Reader Pro | 2019-06-27 → 2026-10-15 | 3/40 | 1/40 | 0/40 | 6/40 | 6/40 | 0/40 |
| Speech Central | 2019-08-26 → 2026-07-29 | 2/40 | 2/40 | 1/40 | 6/40 | 4/40 | **13/40** |

### 4.1 Speechify — the "false promise + upsell" failure mode

- **OCR ad mismatch (17/40).** "The advertisement is misleading. I thought it read the book to you when
  you scanned a page" [S3]. Another: "you have to scan each individual page of my book" [S3].
- **Skipped text / bad highlight sync.** "it skips lines and as it reads its supposed to highlight… the
  highlight is well over a paragraph… further down" [S3].
- **Cancellation friction (13/40).** "This app makes it nearly impossible to cancel your subscription!" [S3].
- **Voice paywall churn.** "They locked every single normal sounding voice behind a pay wall." [S3]
- **Stacked paywalls.** "Pay for a subscription. Pay for library access. Pay for books, too." [S3]
- **Reliability.** "It defaults to the robot voice instead of the voice I selected, and it randomly stops reading" [S3].

### 4.2 NaturalReader — the "tier maze" failure mode

- **Free tier is perceived as bait (21/40).** "you've reached the five minute limit for today" [S7].
- **Tier upsell confusion.** "They tell you to pay $10 for a plus subscription… Now it wants another $19" [S7].
- **Price resistance.** "20 dollars a month is insane" [S7].
- **Forced login.** "you literally can't use it without logging in" [S7].
- **Background playback.** "Playback keeps stopping as soon as I leave the app (also when screen is locked)" [S7].
- **Feature gating inside a paid tier.** "PRO version shows that I can download to MP3… then it tells me I need to upgrade once more" [S7].

### 4.3 ElevenReader — the "great voice, hostile billing" failure mode

- **Paywall transition dominates (27/40).** "putting a pay wall up has absolutely killed any enthusiasm I had" [S10].
- **Refund policy.** "if you cancel, they bill you for the time and won't issue a refund" [S10].
- **Free-tier policing.** "2 hours into use (out of 10), I was flagged as suspicious and told I would need to upgrade" [S10].
- **"Unlimited" is not unlimited.** "even with the unlimited version, it caps at 24 hours a day… it calculates by 1x speed" [S10].
- **Stability.** "it started crashing every time I tried to play anything" [S10].
- **PDF layout/verbatim errors.** "uploads change the layout of the sentences. A sentence will be broken in the middle" [S10].
- **Battery/thermal.** "drains my battery life so fast it's insane" [S10].

### 4.4 Legere Reader — accessibility gem, maintenance risk

- **Voice quality after purchase (14/40).** "Terrible mechanical sounding voices… they sound good in preview but after you download you find they are terrible" [S13].
- **Thin included content.** "it only comes with ONE voice, in addition to the built-in voices" [S13].
- **Purchase durability.** "I had purchased several voices and now need to pay for them again because I re-installed" [S13].
- **Update breakage.** "After updating app does not work at all" [S13].
- 3.3★ and the oldest last-update in this set (2026-06-02) [S11].

### 4.5 Moon+ Reader Pro — licence checks vs offline promises

- **Offline contradiction.** "if I'm offline, moon reader launches play store and won't let me access my reading material" [S19].
- **Sync regressions.** "since the latest update I can no longer sync to drive" [S19].
- **TTS freezes.** "the only reason I paid for the pro version was Text to speech… it freezes the app" [S19].
- **TTS + auto-scroll gap.** "when the sentence goes below the screen, the pdf doesn't scroll down with it" [S19].
- **UI sprawl.** "settings are spread out through a LABYRINTHINE MESS of a dozen different menus" [S19].

### 4.6 Speech Central — redesign regression + BYO-cloud friction

- **Redesign regression.** "after the latest major update, it's useless… the reading will stop half way" [S22].
- **Highlight downgrade.** "words do not highlight individually anymore. it highlights in a complete sentence" [S22].
- **System-voice surprise.** "you download a text to speech app you expect it to get use its own system to text to speech, not the android system" [S22].
- **BYO API key cost/burden.** "Voices are terrible, you need to pay extra for AI voices services" [S22].
- **Free-tier thinness.** "Only 2 free readings a day" [S22].

### 4.7 Cross-cutting complaint clusters (all six samples)

1. **Billing/refund friction** — the most damaging theme in the two biggest subscription apps (Speechify 13/40, ElevenReader 4/40 but with explicit refund policy complaints).
2. **"Free" that is not free** — almost universal; reviewers treat free tiers as evidence of bad faith, not as trials.
3. **Highlight/voice sync bugs** — skipping paragraphs, highlight ahead of audio, voice reverting to robot.
4. **Background playback reliability** — screen lock, app switch, offline licence checks.
5. **Update regressions** — features/data lost after redesign (Speech Central, Moon+, Legere).
6. **Voice inventory economics** — "one included voice + paid packs" is resented (Legere) even at $7.49.

**Implication for NovelSpeak:** the paid market's trust deficit is concentrated exactly where NovelSpeak is
strong (no billing, no account, offline playback) and where it is weak (no cloud voices, no camera OCR,
no library). The roadmap should sell trust and reliability, not feature count.

---

## 5. Per-app deep dives

### 5.1 Speechify — `com.cliffweitzman.speechify2`

**Facts (Observed, 2026-10-24).** 10M+ Android installs; 4.0★ from 284K ratings; last update 2026-10-20;
Play IAP range $0.49–$139.99 [S1]. Play copy claims "50M+ users" and "1,000+ voices across 60+ languages"
including licensed celebrity voices [S1]. Platforms: Android, iOS/iPadOS, Chrome + Edge extensions, web app,
macOS, Windows [S2].

**Pricing.** Free: 1.5x max speed, 10 "robotic sounding" voices, TTS only; Premium $29/month or $139/year
("SAVE 60%") [S2]. Users report a 3-day trial requiring a card, and an extra ~$25 library purchase before
per-book purchases (Inferred from reviews) [S3].

**Strengths.** Largest distribution in the category; cross-device library sync; OCR scan; AI assistant,
summaries, podcasts, voice typing — it is an "AI reading suite", not just TTS; celebrity voice licensing.

**Weaknesses (from 40 one-star reviews).** Per-page OCR advertised as whole-book scanning; skipped
paragraphs and drifting highlight; cancellation friction; voices moved behind higher tiers between updates;
report of features disappearing from an already-paid annual plan [S3].

**NovelSpeak read.** Speechify validates that *speed + natural voice + one-tap import* is worth $29/mo to
millions — but its OCR promise is the single most-attacked feature. NovelSpeak should not attempt book-scan
OCR to compete; it should instead own the *page you are already looking at* (WebView extraction), which
Speechify treats as a one-shot import.

### 5.2 NaturalReader — `com.naturalsoft.personalweb`

**Facts.** 5M+ installs; 4.3★ from 60.3K; last update 2026-10-14; IAP range $9.99–$159.99 [S4].
Official plans (help centre, updated 2026-08-13): Lite $13.90/mo or $79/yr; Plus $20.90/mo or $119/yr;
Pro $25.90/mo or $159/yr [S5]. All plans bundle web app + mobile app + Chrome extension; personal-use
licence only [S5]. Voices: 1,000+ across 90–100+ languages, claimed to be powered by Gemini/ChatGPT/Azure
[S6]. MP3 export (1M characters/month), OCR, pronunciation editor, AI Smart Filter, voice cloning (2 voices
on Plus/Pro) [S5]. Supported inputs: PDF, EPUB, DOCX/PPTX, Numbers/Pages, images/camera, web pages [S4][S5].

**Weaknesses.** Free tier is ~5 min/day (reviews); repeated tier upsell; forced login; background playback
stops; mispronunciation and PDF-parsing issues; support complaints [S7].

**NovelSpeak read.** NaturalReader is the closest match to NovelSpeak's *inputs* (documents + pages + OCR +
MP3) but is hostile to free use and account-first. Its Lite price ($13.90/mo) is the category's "cheap
subscription" anchor — evidence that a $5–7 one-time app would look exceptional value.

### 5.3 ElevenReader — `io.elevenlabs.readerapp`

**Facts.** 1M+ installs; 4.5★ from 70.2K (highest rating in this paid set); last update 2026-10-21;
IAP range $0.30–$330.00 [S8]. Free: 10 hours/month text-to-audio (~400-page book), 1,000+ voices,
free audiobooks, 4.0x speed, sleep timer, bookmarks, sync [S9]. Ultra: $11/month or $99/year ($8.25/mo);
unlimited import listening, 200K+ premium audiobooks/eBooks, custom voices, offline downloads [S9].
Formats: PDF, DOCX, EPUB, Markdown, TXT, links, paste [S9]. Audio cannot be exported; offline downloads
are for in-app listening only [S9]. 30+ languages [S8].

**Weaknesses.** The 2025 paywall/credit transition dominates 27/40 sampled 1-star reviews; refund policy;
free-tier "abuse" flags; "unlimited" 24h/day cap measured at 1x; crashes/unknown errors; PDF layout errors;
battery drain [S10].

**NovelSpeak read.** ElevenReader proves a 10-hours/month free tier is enough to build a 1M-install base
and a 4.5★ rating — generosity buys goodwill. It also proves that *removing* generosity later burns it
instantly. NovelSpeak's "no meter at all, because inference is local" is a cleaner promise than any
competitor's credit system.

### 5.4 Legere Reader (Voice Dream Reader heritage) — `voicedream.reader`

**Facts.** 10K+ installs; 3.3★ from 987; last update 2026-06-02 (stalest update in this set);
Play price $7.49 one-time; IAP range $3.99–$4.99 per item for Acapela premium voices (24 languages) [S11][S12].
Formats: PDF (original layout or text-only), DRM-free EPUB, DAISY, DOC→PDF, DOCX/RTF/MOBI→ePub, TXT/HTML;
Dropbox/Box/OneDrive, Bookshare, Gutenberg, built-in browser [S11]. Accessibility: screen-reader support,
braille display support, Voice Control, Dynamic Type, OpenDyslexic, font up to 80pt, 3 themes, synchronized
word/line highlighting, auto-scrolling reduced text area, 50–700 wpm [S11]. Continues reading when the
screen is locked [S11]. Word-level highlighting not supported for built-in voices [S11].

**Weaknesses.** Mechanical-sounding packaged voices; only one premium voice included; purchases reportedly
lost on reinstall; history of updates breaking the app; reviewers say it is far behind the iOS Voice Dream
Reader feature set; tiny install base [S13].

**NovelSpeak read.** The accessibility bar (braille, screen reader, dyslexia typography) is real but is a
different product axis. The relevant lesson: *one included premium voice plus paid voice packs* is
resented at $7.49; NovelSpeak's bundled Kokoro (11 voices) + Pocket cloning is objectively more generous.

### 5.5 Readwise Reader — `com.readermobile`

**Facts.** 100K+ installs; 4.3★ from 583; last update 2026-10-08; IAP range $12.99–$119.99 [S14].
Pricing: Full Readwise $9.99/mo billed yearly ($119.88/yr) or $12.99 monthly; Reader-only $7.99/mo billed
yearly ($95.88/yr) or $8.99 monthly; 30-day free trial, no card upfront [S15][S14]. Sources: articles,
email newsletters, RSS, YouTube, X threads, PDFs, EPUBs, read-it-later imports [S14]. TTS: "Reader turns any
article, PDF, or ebook into audio with natural AI voices, adjustable speed, and support for dozens of
languages, on every platform" [S16]. Offline reading supported; TTS offline not documented [S16].
Highlights export to Obsidian/Notion/Roam/Evernote/Logseq; Ghostreader AI for summarize/define/translate [S14].

**Weaknesses.** TTS is a feature of a $120/yr workflow product — expensive for a listen-only user; no free
tier after the trial; small Android footprint (583 ratings); TTS playback details undocumented on Play.

**NovelSpeak read.** Readwise proves people pay ~$10/mo for a *reading workflow* with TTS included, not for
TTS alone. NovelSpeak should not chase sync/export workflows; but local highlighting + resume-position +
export of annotations would be cheap credibility for students.

### 5.6 Moon+ Reader Pro — `com.flyersoft.moonreaderp`

**Facts.** 1M+ installs; 4.2★ from 107K; last update 2026-10-17; **$11.99 one-time** (no IAP); Play states
"1,000,000+ downloads, The #1 Paid EBook Reader in Google Play, 30-Day Money Back Guarantee" [S17]; the
developer site claims "50000+ five star ratings, the No.1 best selling ebook reader in Google Play" [S18].
Formats: EPUB, PDF, DJVU, AZW3, MOBI, FB2, PRC, CHM, CBZ, CBR, UMD, DOCX, ODT, RTF, TXT, HTML, MHT, MD,
OPDS/Calibre [S17]. TTS is system-engine only; Pro adds "Shake the phone to speak (Text-to-speech, TTS
engine support)", headset/Bluetooth key control, auto-scroll (5 modes), Dropbox/WebDAV position sync,
dictionary/translation, 10+ themes, widget [S17]. Data safety: "No data collected" [S17].

**Weaknesses.** Offline licence checks send users to Play Store; Google Drive sync regressions; TTS freezes
and no PDF auto-scroll; labyrinthine settings; Android-only [S19].

**NovelSpeak read.** Moon+ is the canonical "one-time, no-account, system-TTS reader" and still sells at
$11.99 with 1M+ installs — direct evidence that a paid one-time Android reader is viable. Its weakness is
exactly NovelSpeak's strength: Moon+'s TTS quality is whatever the device ships, and its UX is cluttered.

### 5.7 Speech Central — `com.labsiisoftware.speechcentral`

**Facts.** 100K+ installs; 4.4★ from 1.63K; last update 2026-10-17; one-time $8.99 via Play IAP [S20][S21].
Vendor: "One-time purchase. No ads. No data collection." [S20][S21]; "doesn't track you or collect your
data. Period." [S20]. Platforms: Android, iPhone/iPad, macOS, Windows [S21]. TTS: system + third-party
offline voices, "Speech Central Pack: Kokoro" free offline voices, and optional cloud voices with your own
API keys (OpenAI-compatible/self-hosted, Azure Speech, Google Cloud TTS, xAI) [S20]. Inputs: PDF (reading
order), EPUB, web/RSS, share-to-read, Instapaper; export annotated text to .docx and optional audio [S20].
80+ settings; ADHD/dyslexia/TalkBack modes [S20]. Free for blind users with TalkBack and managed schools [S20].

**Weaknesses.** 2025 redesign broke workflows and lost data (reviews); word-level highlighting downgraded to
sentence-level; AI voices require user-supplied keys and separate cost; free tier caps adds; review quotes
about support tone [S22].

**NovelSpeak read.** This is the closest philosophical peer: one-time, private, offline-first, Kokoro
offline voices, no account. It validates the entire NovelSpeak model *and* shows where it can be beaten:
Speech Central's cloud voices are BYO-key, its Kokoro integration is a companion app, and its redesign
regressed highlighting. NovelSpeak can win by shipping Kokoro integrated (already done), keeping word-level
read-along, and never breaking playback.

### 5.8 TTS Reader Pro Read Text Aloud — `voice.reader.ai`

**Facts.** 10K+ installs; 4.6★ from 796; last update 2026-09-26; IAP $2.99–$99.99; **Contains ads** [S23].
Description claims "Unlimited hours and real premium AI voice", 50+ languages, PDF/EPUB/TXT/web pages,
"Scan & Listen" [S23]. No pricing tiers, engine provider, offline mode, or playback details are published;
the developer's site did not resolve when fetched on 2026-10-24 (Unknown) [S23].

**Weaknesses.** Ads inside a TTS product; opaque pricing from $2.99 to $99.99 SKUs; tiny footprint; no
accessibility/privacy positioning. **Confidence: low** — treat as a price-band data point, not a feature benchmark.

### 5.9 Listening: Text to Speech — `io.listening.main`

**Facts.** 100K+ installs; 4.0★ from 7.31K; last update 2026-10-14; IAP $6.99–$139.99 [S24].
Pricing: Free plan (2 "ultra-realistic" voices, 1.2x speed cap, up to 3 papers/week, PDF and URL only —
Word/MOBI/EPUB excluded); Monthly $12.99; Yearly $59 ($4.92/mo, "SAVE 62%"); 3-day free trial for new
subscribers (card requirement Inferred from the billing language) [S25]. Academic positioning: section-level selection (e.g., abstract + results only), auto-skip of citations/
references/footnotes, Zotero integration, browser extension, mobile/desktop/web apps [S26][S25]. Sources:
PDF, Word, MOBI, EPUB, paste, URL, camera pages, Chrome extension [S26].

**Weaknesses.** Free tier is very thin; 4.0★ with 7.31K ratings suggests support/quality gaps; engine,
languages, offline mode and playback details unpublished.

**NovelSpeak read.** Listening proves niche verticals (academic papers) support $59–$156/yr pricing and
that citation/footnote skipping is a valued extraction feature. NovelSpeak's chapter/serial-novel layout is
an analogous extraction specialisation for fiction readers.

### 5.10 Evie - The eVoice book reader — `com.lenntt.evoicereader`

**Facts.** 100K+ installs; 4.3★ from 4.28K; last update 2026-10-05; IAP $0.99–$33.99 [S27]. Free with any
installed Android TTS engine, no ads: "Evie is free. Really free, without any ads." [S27]. Paid add-ons:
Amazon Polly cloud voices and Amazon Neural translation (70+ languages) via "simple in-app purchases in a
pay-as-you-go model" [S27]. Playback: background/screen-off reading, Bluetooth and wired headset buttons,
car-stereo controls, lock-screen/notification controls, sleep timer, equalizer, Wear OS companion (requires
internet + Evie account) [S27]. Formats: EPUB, PDF, MOBI, AZW/AZW3, FB2, DOCX, RTF, HTML, TXT; per-language
voice memory; sentence-repeat for language learning [S27]. Vendor: "Evie does not require any permissions to
run" [S27].

**Weaknesses.** Pay-as-you-go credits make cloud voice cost opaque; no read-along/highlight claims; Wear OS
requires an account; solo-developer scale.

**NovelSpeak read.** Evie is the best model of *free core + metered premium* without a subscription, and its
playback feature set (headset buttons, car stereo, sleep timer) is what users praise. NovelSpeak already has
most of this; adding sleep timer and a proper auto-scroll would close the remaining gap.

---

## 6. What NovelSpeak can credibly copy / not copy / charge for

### 6.1 Copy (cheap, high-signal)

| Feature | Who proves it matters | NovelSpeak effort |
|---|---|---|
| **Sleep timer** | ElevenReader and Evie list it; Evie's swipe-activated timer is praised [S9][S27] | Small — timer stops playback and keeps position |
| **Speed range up to 4x, with natural pitch** | ElevenReader 0.25–4x, Speechify 4.5–5x, Legere 50–700 wpm [S9][S1][S11] | Small — sherpa-onnx supports speed control natively |
| **Word-level read-along highlight with auto-scroll** | ElevenReader, NaturalReader, Legere; Speech Central regressed and got hammered [S8][S4][S11][S22] | Medium — WebView highlighting of the spoken sentence/word |
| **Resume position per page/chapter + bookmark** | All paid readers have it; Moon+/Readwise sync it [S17][S14] | Small — local persistence; no account needed |
| **Pronunciation dictionary / overrides** | ElevenReader, NaturalReader, Legere ship one [S8][S4][S11] | Small — user word→phoneme overrides |
| **"No data collected, no account" privacy page** | Moon+ Pro and Speech Central use it as a paid claim, both rated 4.2–4.4 [S17][S20] | Small — document permissions and network usage honestly |
| **Export/share of highlights** | Legere exports text/highlights/notes; Speech Central exports .docx [S11][S20] | Medium — optional, for students |
| **Continuous page capture (not per-page OCR)** | Speechify's per-page OCR is its most-complained feature (17/40) [S3] | Optional — only if done as "scan pages in sequence, auto-advance" |

### 6.2 Do not copy (costly, legally/UX risky, or contradicts the product)

- **Cloud TTS subscriptions at $9.99–$29/mo.** Requires per-minute inference cost and an account — the exact
  things NovelSpeak is built not to need [S2][S15].
- **Credit/meter systems.** ElevenReader's 27/40 paywall complaints and 2025 backlash show meters destroy
  goodwill [S10].
- **Audiobook/eBook stores (200K+ titles).** Licensing, DRM, and catalogue operations [S9].
- **AI study suites (podcasts, quizzes, chat).** LLM inference cost and account-first design; users already
  compare these to "paying for an LLM" [S7][S1].
- **Per-book/per-library paywalls and cancellation friction.** The top review-damage themes [S3][S7].
- **Ads in the listening experience.** TTS Reader Pro's "Contains ads" badge undercuts its 4.6 rating position [S23].
- **Camera single-page OCR marketed as whole-book scanning.** Actively litigated in reviews as false advertising [S3].

### 6.3 Charge for (what the market data supports)

| Option | Price band supported by data | Rationale |
|---|---|---|
| **One-time Play unlock ("Pro")** | **$3.99–$6.99** | One-time peers sell $7.49–$11.99 [S11][S17][S20]; a lower price with more bundled voices (Kokoro 11 + Pocket cloning) is a clear value claim. |
| Optional voice/model packs | $1.99–$3.99 | Legere proves micro-IAPs for voices are accepted, but resentment appears when the included voice is thin [S13]; keep base model free. |
| Optional "supporter" tier (hosting/model downloads) | $5–$15 one-time or yearly donation | No competitor data; only viable as goodwill, not gatekeeping. |
| **Never**: subscription for local TTS | — | Contradicts "no server, no account"; every subscription competitor's complaints are billing-related [S3][S7][S10]. |

**Positioning sentence backed by this evidence:** *"Offline neural voices, any web page, screen-off
playback, no account, no meter — a one-time purchase, like the readers people trust (Moon+, Speech
Central) but with the voice quality of the subscription apps."*

---

## 7. Gaps and unknowns

| Gap | Why | Impact |
|---|---|---|
| Speechify offline playback / media-session details | Not documented on Play or pricing page; no APK analysis permitted in this stream | Unknown field in JSON; may need one device test before claiming parity |
| NaturalReader free-tier cap | Official pricing article lists only Lite/Plus/Pro; ~5 min/day comes from reviews | Free-tier comparison is Inferred, not vendor-confirmed |
| TTS Reader Pro pricing tiers & engine | Developer site did not resolve; Play page publishes no tiers | Card marked low confidence; price-band only |
| Listening engine/languages/offline | Not published on Play or pricing page | Unknown fields; feature matrix uses "unknown" |
| Readwise TTS voice count/languages/offline | Vendor says "dozens of languages" without specifics; offline TTS undocumented | TTS comparison limited to "cloud AI, included in $9.99/mo" |
| Install counts beyond 10M+ | Play clamps at 10M+ and Speechify claims 50M+ users cross-platform | Cannot compare reach precisely |
| Current app version numbers | Google Play details pages no longer publish the version string; only the last-updated date is public | Last-updated date is used as the recency proxy; review JSON includes per-review app versions where useful |
| Review sample representativeness | 40 one-star reviews per app, keyword counts, sorted by date; one-star only (no 3-star) | Themes directional; not a coded survey |
| Regional pricing | US storefront only | Non-US price gaps (India, EU VAT) not covered |

---

## 8. What to hand the roadmap owner (3 concrete bets)

1. **Trust moat, not feature count.** Ship a verifiable "no account, no trackers, no meter" page and keep
   every listed feature offline-capable. This is the only consistent differentiator against all ten cards,
   and it is the exact axis where Speechify/NaturalReader/ElevenReader lose 1-star reviews.
2. **Close the playback UX gap in one release.** Sleep timer, 4x speed, media-session robustness on
   screen-off, and reliable resume/bookmark. Paid apps list these; several ship them badly
   (NaturalReader stops on screen lock; Moon+ TTS freezes) [S7][S19].
3. **Monetise once, at $4.99–$6.99, with everything included.** One-time peers sit at $7.49–$11.99 with
   fewer bundled voices; a lower-priced, no-account, offline-neural bundle is defensible without any
   subscription infrastructure.

---

## Sources appendix

All URLs accessed **2026-10-24**. "Supports" states the specific claim(s) drawn from each source.

| ID | URL | Supports |
|---|---|---|
| S1 | https://play.google.com/store/apps/details?id=com.cliffweitzman.speechify2 | Speechify installs 10M+, 4.0★/284K, update 2026-10-20, IAP $0.49–$139.99, features, data-safety |
| S2 | https://speechify.com/pricing/ | Free vs Premium: $29/mo, $139/yr, 1.5x/10-voice free tier, Premium features |
| S3 | https://play.google.com/store/apps/details?id=com.cliffweitzman.speechify2&showAllReviews=true | 40 one-star Speechify reviews (2025-08-02→2026-09-18) for complaint quotes |
| S4 | https://play.google.com/store/apps/details?id=com.naturalsoft.personalweb | NaturalReader installs 5M+, 4.3★/60.3K, update 2026-10-14, IAP $9.99–$159.99, features |
| S5 | https://help.naturalreaders.com/en/articles/8854700-plans-pricing-personal-version | Official Lite $13.90/$79, Plus $20.90/$119, Pro $25.90/$159, feature limits |
| S6 | https://www.naturalreaders.com/webapp.html | Gemini/ChatGPT/Azure voice sourcing, 90+ languages, 3-app bundle, MP3 download |
| S7 | https://play.google.com/store/apps/details?id=com.naturalsoft.personalweb&showAllReviews=true | 40 one-star NaturalReader reviews (2022-02-03→2026-09-12) for complaint quotes |
| S8 | https://play.google.com/store/apps/details?id=io.elevenlabs.readerapp | ElevenReader installs 1M+, 4.5★/70.2K, update 2026-10-21, IAP $0.30–$330.00, features |
| S9 | https://elevenreader.io/ | Free 10 h/month, Ultra $11/mo or $99/yr, formats, offline downloads, no audio export, FAQ |
| S10 | https://play.google.com/store/apps/details?id=io.elevenlabs.readerapp&showAllReviews=true | 40 one-star ElevenReader reviews (2024-08-19→2026-09-15) for complaint quotes |
| S11 | https://play.google.com/store/apps/details?id=voicedream.reader | Legere price $7.49, 10K+, 3.3★/987, update 2026-06-02, IAP $3.99–$4.99, accessibility/format description |
| S12 | https://www.voicedream.com/ | Voice Dream Reader heritage, accessibility positioning, 14K+ App Store reviews claim |
| S13 | https://play.google.com/store/apps/details?id=voicedream.reader&showAllReviews=true | 40 one-star Legere reviews (2018-09-23→2025-12-16) for complaint quotes |
| S14 | https://play.google.com/store/apps/details?id=com.readermobile | Readwise Reader 100K+, 4.3★/583, update 2026-10-08, IAP $12.99–$119.99, features, 30-day trial |
| S15 | https://readwise.io/pricing | Full Readwise $9.99/mo billed yearly; Reader-only $7.99/mo; monthly $12.99/$8.99 from page data |
| S16 | https://readwise.io/reader | TTS on every platform, offline reading, sources, sync |
| S17 | https://play.google.com/store/apps/details?id=com.flyersoft.moonreaderp | Moon+ Pro $11.99, 1M+, 4.2★/107K, update 2026-10-17, formats, features, "No data collected" |
| S18 | https://www.moondownload.com/ | Moon+ Reader developer site positioning ("No.1 best selling ebook reader in Google Play") |
| S19 | https://play.google.com/store/apps/details?id=com.flyersoft.moonreaderp&showAllReviews=true | 40 one-star Moon+ Pro reviews (2019-06-27→2026-10-15) for complaint quotes |
| S20 | https://play.google.com/store/apps/details?id=com.labsiisoftware.speechcentral | Speech Central 100K+, 4.4★/1.63K, update 2026-10-17, $8.99 IAP, one-time/offline/Kokoro/BYO-cloud description |
| S21 | https://www.speechcentral.net/ | "One-time purchase. No ads. No data collection.", platforms, free for blind users/schools, 80+ settings |
| S22 | https://play.google.com/store/apps/details?id=com.labsiisoftware.speechcentral&showAllReviews=true | 40 one-star Speech Central reviews (2019-08-26→2026-07-29) for complaint quotes |
| S23 | https://play.google.com/store/apps/details?id=voice.reader.ai | TTS Reader Pro 10K+, 4.6★/796, update 2026-09-26, IAP $2.99–$99.99, "Contains ads" |
| S24 | https://play.google.com/store/apps/details?id=io.listening.main | Listening 100K+, 4.0★/7.31K, update 2026-10-14, IAP $6.99–$139.99, features |
| S25 | https://listening.io/pricing | Listening free plan limits, $12.99 monthly, $59 yearly ($4.92/mo), 3-day trial |
| S26 | https://listening.io/ | Academic positioning: section selection, citation skipping, Zotero, formats |
| S27 | https://play.google.com/store/apps/details?id=com.lenntt.evoicereader | Evie 100K+, 4.3★/4.28K, update 2026-10-05, IAP $0.99–$33.99, free+Polly/translation/playback description |

*Raw evidence artifacts under `/tmp/competitive/paid/`: `play/*.html`, `site/*.html`, `*_reviews.json`, `play_meta.json`.
They are intentionally not committed to the repository.*
