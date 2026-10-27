# Serialized-novel platforms with built-in read-aloud — competitive analysis

**Stream:** novel · **Research date:** 2026-10-24 (Asia/Calcutta) · **Author:** novel-intel (Team: NovelSpeak competitive research)
**Scope:** 12 content platforms that sell serialized fiction and ship (or claim) read-aloud: WebNovel, Wattpad, GoodNovel, Pocket Novel, Hinovel, JoyRead, Readink, AlphaNovel, Starynovel/Dreame, NovelUP, Light Novel (Not A Simple Studio), AnyStories. Audio-first siblings (Dreame FM, Pocket FM) are used only as structural evidence.
**Method:** Play Store listings (curl, US storefront), Play reviews (newest + 1-star, 150 per app, free scraper), Apple App Store US pages (exact in-app-purchase price lists), iTunes lookup API, official help/Q&A pages, and web search via Firecrawl. No APK was decompiled in this pass, so **TTS engine type is Unknown for every app unless stated**; the claims below are about observable behaviour and pricing, not about which engine renders the voice.
**Evidence labels:** *Observed* = seen on the cited page/listing/review on 2026-10-24. *Inferred* = reasoned from observed data. *Unknown* = not established; no guess. Review quotes are user-reported and are labelled as such.

---

## 1. TL;DR — what the data says

1. **This category is huge but unhappy.** The 12 apps cover **~153.6M+ Play installs** (sum of Play buckets, lower bound, Inferred) yet six of the seven apps with 5M+ installs sit at **3.58–4.13 stars**, with GoodNovel (4.55) the outlier [S1,S2,S4,S5,S6,S10]. Complaints are dominated by **coin economics and broken audio**, not by catalog quality.
2. **Built-in read-aloud is now table stakes at the top, but it is an accessory, not the product.** WebNovel has voice reading with **voice + speed selection and a separate "listen to novel" button** [S16]; Wattpad's TTS is an explicit **beta, English-only subset** [S15]; JoyRead sells a **"pro audiobook voice"** [S6]; NovelUP and Light Novel ship read-aloud that **users report broken** [S10,S11].
3. **Nobody offers a competent, reliable listen-while-locked experience.** Recent Play reviews report WebNovel audio **"keeps stopping my audio and have to restart manually"** (2026-10-23) [S1], JoyRead audio **"stops after 3 chapters"** and repeats on Bluetooth (2026-10-22) [S6], and a Wattpad user asking for **auto-next chapter in TTS** (2026-10-16) [S2]. This is exactly NovelSpeak's feature set (foreground service + MediaSession + auto mode).
4. **Monetization is coin-cliff + subscription stacking.** Exact US IAP lists (2026-10-24): WebNovel 50 coins $0.99 → 5,000 coins $99.99 and $9.99/month membership [S17]; GoodNovel 200 coins $1.99 → 5,000 coins $49.99 plus **$9.99/week** subscription [S18]; JoyRead $9.99–$19.99/month memberships [S20]; Hinovel $5.99/month or $5.99/week [S19]. Users report chapter prices **jumping from 10–15 to 35 coins after chapter 3** (GoodNovel, 2026-10-22) [S3] and **26,208 coins for one novel** (JoyRead, 2026-10-22) [S6].
5. **Structurally, none of them can read the open web.** Every app in this set is a **catalog storefront**: owned/licensed content only, chapter-locked, account-based [S1–S12]. NovelSpeak's "any URL → extract → read aloud" is not a feature gap these companies can close without cannibalising coin revenue and taking on copyright exposure (Inferred from the business model).
6. **Audio is being split into separate products.** Dreame's audiobooks live in **Dreame FM** ("Professional narrators, High-quality recording" [S13]); Pocket FM is an **audio-dramas platform** (4.38, 4,668,895 ratings [S14]) while Pocket Novel is the reading app [S4]. Reading-app TTS is therefore under-invested at exactly the companies with the deepest catalogs.

---

## 2. Landscape

Play Store data observed 2026-10-24, US storefront. Installs are Google Play buckets, not unique users.

| App | Package | Developer | Installs | Rating | Ratings | Ads | Offline | Read-aloud | Business model |
|---|---|---|---|---|---|---|---|---|---|
| Wattpad | `wp.wattpad` | Wattpad Corp | 100M+ | 3.72 | 5,020,507 | Yes | Yes | Beta, English subset [S15] | Free + ads + Premium |
| WebNovel | `com.qidian.Int.reader` | Cloudary | 10M+ | 4.00 | 450,803 | Yes | Unknown | Yes, voice+speed [S16] | Coins + membership |
| GoodNovel | `com.read.goodnovel` | Singapore New Reading Tech | 10M+ | 4.55 | 1,062,103 | No | Yes | Unknown (no evidence) | Coins + weekly sub |
| JoyRead | `com.whaledream.novel` | UREAD Global | 10M+ | 3.76 | 380,216 | Yes | Unknown | Yes, narrators + paid pro voice [S6] | Coins + memberships |
| Pocket Novel | `com.pocketfm.novel` | Pocket FM Corp | 10M+ | 4.13 | 26,501 | Yes | Unknown | Unknown | Coins/unknown |
| Hinovel | `com.aynovel.vixs` | Hong Kong RiMing | 5M+ | 3.58 | 83,351 | No | Unknown | Unknown | Coins + VIP |
| NovelUP | `com.cloudview.novel` | Colorful Point | 5M+ | 4.01 | 69,492 | Yes | Yes | Yes, broken [S10] | VIP + ads |
| AlphaNovel | `io.alpha.novel` | Brailion Ventures | 1M+ | 4.63 | 98,842 | Yes | Unknown | Audiobooks (indirect) [S8] | Coins + Plus |
| Readink | `com.app.android.qw.readink` | Readink | 1M+ | 4.56 | 89,031 | No | Unknown | Yes, "choppy" [S7] | Unknown |
| Light Novel | `com.notasimplestudio.webnovelfree` | Not A Simple Studio | 1M+ | 4.67 | 20,801 | Yes | Yes | Yes, paid voice editing [S11] | Free + ads + coins |
| AnyStories | `com.yj.anystories.android` | Hong Kong Zhonghe | 500K+ | 4.36 | 17,290 | Yes | Unknown | Unknown | Coins + unlimited |
| Starynovel (Dreame) | `com.dreame.unlimited.gp` | Stary / Dreame Media | 100K+ | 3.29 | 1,527 | No | Unknown | Unknown (audio in Dreame FM) [S13] | Subscription |

**Read of the table.** Scale and satisfaction are inversely related here: the two highest-rated apps (Light Novel 4.67, AlphaNovel 4.63) are the smallest of the majors, while the 5M–100M install apps carry the coin-cliff and TTS complaints. The lowest-rated app, Starynovel (3.29), is the newest subscription-first entrant — a warning that "all you can read" pricing does not by itself win users.

---

## 3. Read-aloud / TTS capability, app by app

No public documentation identifies the TTS engine for any of these apps. What is observable is the *shape* of the feature: where the button is, whether voice/speed can be chosen, whether it is free or gated, and what users say breaks.

| App | Evidence of TTS | Engine | Voice choice | Free or paid | Recent quality evidence |
|---|---|---|---|---|---|
| **WebNovel** | *Observed* official Q&A: "Press the Read button to select the voice and speed you want to read aloud." Separate "listen to novel" button with chapter choice [S16] | Unknown | Yes (voice + speed) | Free feature; chapters coin-locked | *Observed* reviews Oct 2026: "it completely broke the TTS function. it just doesn't read anything anymore." (2026-10-21); "it keeps stopping my audio and have to restart manually again" (2026-10-23) [S1] |
| **Wattpad** | *Observed* official support: "Text-to-speech is a beta feature on Wattpad and it was added to thousands of stories written in English." [S15] | Unknown | Unknown | Free beta | *Observed*: request for auto-next chapter (2026-10-16) [S2]; no other TTS complaints in 150 newest reviews |
| **JoyRead** | *Observed* reviews: "quality narrators" for some stories; a paid "pro audiobook voice" [S6] | Unknown (narrators for some titles) | Yes, some titles | Partly premium: user paid "$50 for the Novel Subscription" for the pro voice and reports "It did not work" (2026-09-08) [S6] | *Observed*: "Still stops after 3 chapters in audio. If you're using Bluetooth, it will repeat the same chapter" (2026-10-22) [S6] |
| **Readink** | *Observed*: "I love tat you have the option of reading or listening" (2026-10-07) [S7] | Unknown | Unknown | Unknown | *Observed*: "readings a little choppy wish it was more natural" (2026-10-20) [S7] |
| **AlphaNovel** | *Inferred*: reviews "I love audio books" (2026-10-05) and an audiobook complaint (2026-09-20); no official audio documentation found [S8] | Unknown | Unknown | Unknown | Thin evidence; treat as partial |
| **NovelUP** | *Observed*: audiobook UI exists but is broken — "you can't even use the audiobook" (2026-10-09); "My audiobook isn't working" (2026-08-28) [S10] | Unknown; quality consistent with system TTS (*Inferred*) | Unknown | Unknown | *Observed*: "the audio part dosnt sound like english" (2026-10-17) [S10] |
| **Light Novel** | *Observed*: "love the download option and voice reading" (2026-10-01); separate paid "editing of the voice" (2026-10-17); next/prev controls while listening (2026-10-12) [S11] | Unknown | Yes, with paid editing | Free base; voice editing monetised (user-reported) | *Observed*: listening controls "practically" unusable per the same review [S11] |
| **GoodNovel** | **Unknown.** No TTS claim in the store description and **zero** TTS/audio mentions in 144 recent reviews [S3]. Description advertises "voiceovers, sound effects" for anime-style content — that is not evidence of novel TTS. A Facebook search snippet claiming GoodNovel "added the audio feature" to a story was not verifiable | Unknown | Unknown | Unknown | Unknown |
| **Hinovel** | **Unknown.** No TTS claim in description; zero TTS mentions in 148 recent reviews [S5]. A TikTok search hit ("How to Do Read Out Loud on Hinovel") is unverified | Unknown | Unknown | Unknown | Unknown |
| **Pocket Novel** | **Unknown.** No TTS claim; one Hindi review asks to "add voice to every book", implying voice is not universal [S4] | Unknown | Unknown | Unknown | Unknown |
| **Starynovel/Dreame** | **Unknown in the reader.** Dreame's audiobooks are a separate app, Dreame FM, with "Professional narrators, High-quality recording" [S13]. A 2023 review says screen readers/TTS did not work with Dreame [S9] | Unknown | Unknown | Subscription-first | Historically broken (2023, user-reported) |
| **AnyStories** | **Unknown.** No TTS/audio claim found [S12] | Unknown | Unknown | Unknown | Unknown |

**Cross-cutting observations.**
- *Observed:* WebNovel is the only app in the set with a documented **pre-playback voice + speed picker** [S16]. JoyRead and Light Novel gate better voices behind payment [S6,S11].
- *Observed:* TTS reliability is a live problem in Oct 2026 for WebNovel, JoyRead and NovelUP [S1,S6,S10]. That is the market opening: **a reader that never loses your place and keeps playing with the screen off**.
- *Unknown:* whether any of these apps highlights the spoken sentence (read-along). No store description or review in this sample mentions TTS-linked highlighting. Do not market "they don't have it" — market "NovelSpeak has it".

---

## 4. Monetization economics (exact numbers where public)

All prices below are **Apple App Store US in-app-purchase listings observed 2026-10-24** [S17–S24], unless marked user-reported. Play IAP ranges are from the Play listings [S1–S12].

| App | Coins (US IAP) | Implied $/coin (Inferred) | Subscription (US IAP) | Play IAP range | User-reported chapter cost |
|---|---|---|---|---|---|
| WebNovel | 50=$0.99 · 250=$4.99 · 500=$9.99 · 1000=$19.99 · 2500=$49.99 · 5000=$99.99 | ~$0.020 | Membership_Monthly $9.99; Membership $4.99; Weekly short-story membership $19.99 | $0.99–$299.99 | "a book was 160 chapters, 1 chapter costs 10 coins" (2026-10-21) → ~$0.20/chapter |
| GoodNovel | 200=$1.99 · 500=$4.99 · 600=$5.99 · 1000=$9.99 · 1500=$14.99 · 2000=$19.99 · 5000=$49.99 | ~$0.010 | Weekly Subscription $9.99; Weekly Membership $19.99 | $0.10–$199.99 | "from 10-15 to 35 coins" after chapter 3 (2026-10-22) → ~$0.10–$0.35/chapter |
| JoyRead | 350=$4.99 · 560=$7.99 · 630=$8.99 · 700=$9.99 · 1400=$19.99 · 1680=$23.99 · 2100=$29.99 | ~$0.014 | Monthly Membership $19.99; subscription-month $9.99; Weekly Shorts Pass $19.99 | $0.59–$359.99 | "26208 coins" to finish one novel (2026-10-22) → ~$374 (Inferred) |
| Hinovel | 300=$2.99 · 500=$4.99 · 1000=$9.99 · 1500=$14.99 · 2000=$19.99 · 3000=$29.99 | ~$0.010 | Monthly $5.99; Weekly $5.99; Short Stories $19.99 | $0.99–$269.99 | "subscription of 20$ a week" (2026-08-12) [S5] |
| AlphaNovel | 100=$0.99 · 500=$4.99 · 1000=$9.99 · 1500=$14.99 · 2000=$19.99 · 3000=$29.99 | ~$0.010 | AlphaNovel Plus $6.99 / $9.99; Plus+300 coins $2.99; Plus+1500 coins $9.99 | $0.99–$99.99 | "They want 9.99 a week" (2026-10-15) [S8] |
| AnyStories | 300=$2.99 · 500=$4.99 · 1000=$9.99 · 1500=$14.99 · 2000=$19.99 · 3000=$29.99 | ~$0.010 | Unlimited $19.99/$29.99/$39.99; Weekly Membership $39.99 | $0.49–$299.99 | No data |
| NovelUP | — | — | Weekly VIP $2.99; Monthly VIP $9.99; Yearly VIP $79.99; consumables $0.49–$17.99 | $0.49–$79.99 | No data |
| Starynovel | — | — | Monthly Subscription $14.99; Membership $18.99–$22.99; Monthly Bundle $14.99; Weekly Membership $19.99 | $0.99–$175.99 | No data |
| Wattpad | — | — | Premium price Unknown (Apple page HTTP 429; Play IAP range $0.99–$89.99) | $0.99–$89.99 | Ads between chapters (2026-10-22) [S2] |
| Pocket Novel | — | — | Unknown (Apple page HTTP 429) | $0.49–$99.99 | No data |
| Readink | — | — | Unknown (no US iOS listing found) | $0.49–$239.99 | "The chapters are too expensive" (2026-10-12) [S7] |
| Light Novel | — | — | Unknown | $1.99–$79.99 | "100 chapters with 100 coins" was the old deal (2026-10-20) [S11] |

**Monetization patterns worth naming.**
- **Coin cliff:** the first chapters are cheap or free, then per-chapter cost steps up (GoodNovel 10–15 → 35 coins) [S3]. Users call it bait-and-switch: "the ads are a lie … it wants you to pay 20 dollars to read" (2026-10-22) [S3].
- **Subscription stacking:** paying for membership does not stop coin charges — "even after paying for a membership they make you pay more money to get coins" (GoodNovel, 2026-10-22) [S3]; "subscription does not let you get through a book!" (2026-10-22) [S3].
- **Free-currency loops** are the retention counterweight: GoodNovel "Complete daily reading quests to earn free coins"; Hinovel "Check in to gain coins"; JoyRead "Coins and Pearls are available everyday" + VIP group + Lucky Draw [S3,S5,S6].
- **Ad-unlock** exists but is disliked: "I don't mind having to complete ad tasks to unlock chapters but I do mind being interrupted mid read" (JoyRead, 2026-10-21) [S6]; Light Novel users: "either you pay or watch too many stupid ads" (2026-09-24) [S11].
- **Cancellation friction is a theme**: "NO WAY TO UNSUBSCRIBE. searched everywhere." (Hinovel, 2026-09-30) [S5]; "My subscription expired back in February, but I am still getting charged." (Readink, 2026-10-16) [S7]; "can't even cancel the subscription" (Dreame, 2026-04-30) [S9].

> **Implication for NovelSpeak:** the competitor price anchor is **$0.10–$0.35 per chapter and $5.99–$19.99/month**, with content quality complaints attached. NovelSpeak's zero-marginal-cost model (user brings the URL, TTS runs on device) can be positioned as "no coins, no chapter cliff, no ads" — a claim none of these 12 apps can make.

---

## 5. Reading / TTS UX patterns these platforms own (copy these)

1. **"Read" as a first-class action on the book page.** WebNovel's detail page shows progress bar, remaining chapters, a Read button that opens voice+speed selection, and a separate Listen button with chapter picker [S16]. *NovelSpeak:* add a one-tap "Read aloud" sheet (voice, speed, start sentence) from the URL bar, and a "start from chapter" list for detected chapter pages.
2. **Chapter progression as the unit of UX.** Every app is chapter-based; WebNovel surfaces "progress bar of the novel, the remaining chapter information" [S16]. *NovelSpeak:* show "Chapter 12/—" + sentence progress in the player, persist per-site position.
3. **A library of saved places, not just a bookmark.** WebNovel "My Bookshelf" [S16]; Wattpad "Build your own free library" [S2]; Readink "Auto-synced personal library across all devices" [S7]. *NovelSpeak:* a "Sites/Stories" shelf with per-site resume position (local, no account).
4. **A "listen mode" distinct from reading mode** with its own controls and chapter queue [S16]. *NovelSpeak:* already has the queue — expose it as a visible chapter list in the player, not just Next.
5. **Daily free-currency habit loop** (check-in, quests, lucky draw) [S3,S5,S6]. NovelSpeak does not need currency, but the *habit loop* (daily streak / "continue listening" nudge) is worth borrowing in a privacy-respecting form.
6. **Free-first-chapters funnel** (AlphaNovel "free first chapters" [S8]) — copy the funnel transparency, not the cliff: NovelSpeak can honestly say "every chapter of every site you can already read".
7. **Audio quality as a paid upsell** (JoyRead "pro audiobook voice" [S6]; Light Novel paid voice editing [S11]). *NovelSpeak:* free on-device neural voices (Kokoro/Pocket) are a direct answer — "audiobook voice, no subscription".

**Patterns NOT to copy:** coin cliffs, subscription+coins stacking, ads mid-chapter, audio that stops after 3 chapters, subscription cancellation friction, 5-page chapters engineered to inflate chapter count ("A book might have 200 chapters of less than 5 pages each", AlphaNovel, 2026-09-20) [S8].

---

## 6. Where these platforms structurally cannot compete

| Structural constraint | Evidence | Consequence for NovelSpeak |
|---|---|---|
| **Owned catalog only** — no arbitrary URL import in any of the 12 | All store listings describe their own libraries [S1–S12]; no "browser" or "add URL" claim anywhere | NovelSpeak can serve the sites these apps will never license: empirenovel, lightnovelworld, royalroad, aggregator sites, public-domain catalogs |
| **Revenue depends on chapter locks** | Coin/subscription economics above [S17–S24] | They cannot make paywalled chapters free; NovelSpeak never holds the content, so it has no reason to lock it |
| **TTS is an accessory** | Wattpad TTS beta/English-only [S15]; WebNovel TTS regressions [S1]; NovelUP audiobook broken [S10]; audio split into Dreame FM/Pocket FM [S13,S14] | A reader whose *core* is playback (streaming pipeline, screen-off, MediaSession, read-along) can be materially better at the one thing they treat as a side feature |
| **Account + ads + tracking** | All apps account-based; 8 of 12 carry "Contains ads" [S1–S12] | NovelSpeak can be account-free, ad-free (blocker built in), and offline-first — a privacy position none of them can adopt without breaking their model |
| **English-centric TTS or per-title voices** | Wattpad beta English subset [S15]; JoyRead pro voice paid and reported broken [S6] | Device system TTS covers the user's languages instantly; neural voices are free and on-device |
| **No read-along evidence** | No TTS-linked highlighting found in descriptions/reviews (Unknown) | NovelSpeak's paragraph highlight + auto-scroll is a visible differentiator (market it as a capability, not as their absence) |
| **Cancellation/support friction** | Hinovel "NO WAY TO UNSUBSCRIBE" [S5]; Readink charged after cancel [S7] | "No subscription" is a trust claim NovelSpeak can make honestly |

**Legal boundary.** NovelSpeak should keep doing what the repo already does: user-initiated extraction of pages the user can already open, no paywall/DRM bypass, no content hosting/redistribution. Competitor coin models exist because they license content; NovelSpeak's positioning is a *reader*, not a *library*.

---

## 7. Positioning and partnership angle

**Positioning statement (evidence-backed):** *"The listen-anywhere reader for the web novels the big apps don't carry."* Every claim maps to an observed competitor gap:

- **"Any site, not just our catalog"** — none of the 12 accepts a URL [S1–S12].
- **"Screen off, chapter after chapter"** — competitors' audio stops mid-book or after 3 chapters [S1,S6]; Wattpad users are still asking for auto-next [S2].
- **"No coins, no chapter cliff"** — competitor chapter economics are $0.10–$0.35 and step up after chapter 3 [S3,S17–S24].
- **"Audiobook voices without a subscription"** — competitors charge for pro voices [S6,S11] or ship none (GoodNovel, Hinovel, Pocket Novel, Starynovel — Unknown).
- **"Read-along highlight"** — no competitor evidence of TTS-linked highlighting (Unknown; opportunity).

**Partnership / go-to-market angles:**
1. **Long-tail web-novel sites** (empirenovel, lightnovelworld, royalroad and similar) — offer a "Listen in NovelSpeak" deep link / share target. Their content is free-to-read; NovelSpeak adds listening time without touching their ads. This is additive, not competitive.
2. **RoyalRoad / Wattpad authors** — authors want audio for their own works; a "read my story aloud" flow with user-recorded Pocket voice clones is a legitimate creator tool (NovelSpeak already supports zero-shot cloning from a reference clip).
3. **Public-domain presets** (Project Gutenberg etc.) — a legal, zero-friction demo catalog that shows the reader at its best, with no rights issues.
4. **Anti-positioning against coin apps** — comparison landing page: "the same chapters you already read, read aloud, for free, with the screen off". Cite the observed competitor complaints (anonymised) as social proof.

---

## 8. What NovelSpeak should build next (from this stream)

**P0 — close the "listen like an audiobook" loop (already 80% there):**
- Per-site **resume position** and a **Sites shelf** (competitor norm: bookshelf + progress) [S16,S7].
- **Chapter list panel** in the player with "start here" (WebNovel listen-with-chapter-choice pattern) [S16].
- **Sleep timer** and a "continue listening" notification action (no competitor evidence of a good one; standard audiobook expectation).
- **Offline text cache**: extracted chapters stored for playback without network (Wattpad/GoodNovel/NovelUP all sell offline reading [S2,S3,S10]).

**P1 — differentiation they cannot copy:**
- **Read-along polish**: highlight follows heard audio (already implemented) + tap-a-paragraph-to-jump.
- **Free neural voice picker** as a visible feature ("audiobook voice, no subscription") vs JoyRead's paid pro voice [S6].
- **"Auto mode" marketing**: auto-next chapter with a visible queue (competitors: missing/broken [S1,S2,S6]).
- **Ad-free + account-free** trust page, with the built-in blocker as proof [README].

**P2 — ecosystem:**
- Deep-link/share-target "Listen in NovelSpeak" for partner sites.
- Creator mode: import a reference clip → Pocket voice clone for the user's own writing (NovelSpeak already ships Pocket TTS) [README].
- Optional encrypted sync/export of the Sites shelf (competitor parity with Readink/Starynovel sync claims [S7,S9]) — only if it can stay account-free.

---

## 9. Evidence gaps and honest unknowns

- **TTS engine type is Unknown for all 12.** No APK was decompiled in this pass. Determining system vs cloud vs neural would require downloading free APKs (e.g. from Uptodown/APKMirror) and checking for `android.speech.tts` usage / TTS SDK strings — a follow-up task, not a guess.
- **TTS presence unresolved for 5 apps:** GoodNovel, Hinovel, Pocket Novel, Starynovel, AnyStories. Marked Unknown in the data file. Absence of evidence in 144–150 recent reviews is suggestive but not proof.
- **Exact subscription prices Unknown for Wattpad Premium, Pocket Novel, Readink, Light Novel** — Apple App Store pages returned HTTP 429 or no US listing; only Play IAP ranges are known.
- **Read-along / auto-next / MediaSession behaviour** is not documented publicly for most apps; conclusions rest on user reviews, which are self-selected and can be astroturfed. Quotes are labelled user-reported.
- **Installs are Play buckets** (e.g. "10M+") on the US storefront; not unique users or revenue.
- **Country focus and language support** are only partially established: Wattpad states 25+ languages [S2]; WebNovel/NovelUP/Light Novel are translation-led; others Unknown.
- **Reviews sampled**: 150 newest + 150 one-star per app on 2026-10-24; older or 5-star-dominated praise may be missing.

---

## 10. Sources

All accessed 2026-10-24. Play listings observed via US storefront; Apple IAP prices are US storefront.

| ID | URL | Supports |
|---|---|---|
| S1 | https://play.google.com/store/apps/details?id=com.qidian.Int.reader&hl=en&gl=US | WebNovel: 10M+, 4.00/450,803, Contains ads, IAP range; recent reviews on TTS breakage/ads |
| S2 | https://play.google.com/store/apps/details?id=wp.wattpad&hl=en&gl=US | Wattpad: 100M+, 3.72/5,020,507, offline download, 25+ languages, ads-between-chapters reviews |
| S3 | https://play.google.com/store/apps/details?id=com.read.goodnovel&hl=en&gl=US | GoodNovel: 10M+, 4.55/1,062,103, no ads badge, offline reading, daily quests; coin-cliff reviews |
| S4 | https://play.google.com/store/apps/details?id=com.pocketfm.novel&hl=en&gl=US | Pocket Novel: 10M+, 4.13/26,501, Contains ads; stability reviews |
| S5 | https://play.google.com/store/apps/details?id=com.aynovel.vixs&hl=en&gl=US | Hinovel: 5M+, 3.58/83,351, check-in coins; unsubscribe/price reviews |
| S6 | https://play.google.com/store/apps/details?id=com.whaledream.novel&hl=en&gl=US | JoyRead: 10M+, 3.76/380,216, Contains ads; narrator/pro-audiobook-voice/audio-stops reviews |
| S7 | https://play.google.com/store/apps/details?id=com.app.android.qw.readink&hl=en&gl=US | Readink: 1M+, 4.56/89,031, cross-device library; listening-mode and choppy-voice reviews |
| S8 | https://play.google.com/store/apps/details?id=io.alpha.novel&hl=en&gl=US | AlphaNovel: 1M+, 4.63/98,842, Contains ads; audiobook and $9.99/week reviews |
| S9 | https://play.google.com/store/apps/details?id=com.dreame.unlimited.gp&hl=en&gl=US | Starynovel: 100K+, 3.29/1,527, sync claim; subscription/TTS reviews |
| S10 | https://play.google.com/store/apps/details?id=com.cloudview.novel&hl=en&gl=US | NovelUP: 5M+, 4.01/69,492, offline downloads, Ad+Free; audiobook-failure reviews |
| S11 | https://play.google.com/store/apps/details?id=com.notasimplestudio.webnovelfree&hl=en&gl=US | Light Novel: 1M+, 4.67/20,801, offline download, voice reading; ads/voice-editing reviews |
| S12 | https://play.google.com/store/apps/details?id=com.yj.anystories.android&hl=en&gl=US | AnyStories: 500K+, 4.36/17,290, Contains ads, IAP range |
| S13 | https://play.google.com/store/apps/details?id=com.joyreading.dreamefm&hl=en&gl=US | Dreame FM audiobook sibling: 4.44/31,992; "Professional narrators, High-quality recording" |
| S14 | https://play.google.com/store/apps/details?id=com.radio.pocketfm&hl=en&gl=US | Pocket FM audio-dramas sibling: 4.38/4,668,895 |
| S15 | https://support.wattpad.com/hc/en-us/articles/360049082211-Text-to-speech | Wattpad TTS beta, thousands of English stories |
| S16 | https://m.webnovel.com/ask/q333045156705338 | WebNovel voice reading: Read button, voice + speed, listen-to-novel button, chapter choice |
| S17 | https://apps.apple.com/us/app/id1234939196 | WebNovel US IAP: 50 coins $0.99 … 5,000 coins $99.99; Membership_Monthly $9.99 |
| S18 | https://apps.apple.com/us/app/id1503128132 | GoodNovel US IAP: 200 coins $1.99 … 5,000 coins $49.99; Weekly Subscription $9.99 |
| S19 | https://apps.apple.com/us/app/id1492422313 | Hinovel US IAP: 300 coins $2.99 … 3,000 coins $29.99; Monthly/Weekly $5.99 |
| S20 | https://apps.apple.com/us/app/id1551079603 | JoyRead US IAP: 350 coins $4.99 … 2,100 coins $29.99; Monthly Membership $19.99 |
| S21 | https://apps.apple.com/us/app/id1585933612 | AlphaNovel US IAP: 100 coins $0.99 … 3,000 coins $29.99; Plus $6.99/$9.99 |
| S22 | https://apps.apple.com/us/app/id1562061999 | Starynovel US IAP: Monthly Subscription $14.99; Membership $18.99–$22.99 |
| S23 | https://apps.apple.com/us/app/id6446337291 | NovelUP US IAP: Weekly VIP $2.99, Monthly VIP $9.99, Yearly VIP $79.99 |
| S24 | https://apps.apple.com/us/app/id1500217654 | AnyStories US IAP: coins, Unlimited $19.99/$29.99/$39.99, Weekly Membership $39.99 |
| S25 | https://itunes.apple.com/lookup?id=1234939196&country=us | iOS ratings/counts (same method for all app IDs); cross-platform popularity |
| S26 | https://www.reddit.com/r/Webnovel/comments/1muiywk/webnovel_texttospeech_feature_needs_fixes_android/ | Search-result title: "WebNovel Text-to-Speech Feature Needs Fixes (Android Auto / Calls ...)" (not opened; used only as corroboration) |
