# NovelSpeak website — brief

Status: round 1 (before any page code). Owner: site/redesign.

## Line 1 — the kind

**software** (`<html data-type="software">`, `<meta name="page-type" content="software">`,
`<meta name="site-kind" content="software">`). The client sells its own Android app; nothing is
sold at a door and nothing ships in a box.

## What is sold

An Android app that reads web novels aloud. You open a chapter in its own browser, it extracts the
chapter text, and it speaks it with either the phone's system voice or an on-device neural voice,
including with the screen off. Free, Apache-2.0, no account, no ads, no analytics, no server.

## Who buys it

Primary: people who read long serialized web novels on a phone (Royal Road, Scribble Hub, WebNovel,
Wattpad, Gutenberg) and want to keep reading while walking, cooking, driving or with the screen off.

Secondary: Android users who tried Chrome's *Listen to this page* and found it unavailable on their
site, or who tried a subscription AI reader and did not want an account or a monthly bill.

What they already know / believe:

- "Read aloud" features are usually cloud services with accounts and meters.
- Large neural voices usually mean huge downloads and a login.
- Privacy policies usually mean analytics.

What they need to see before downloading:

1. It works on the sites they actually read, including paywalled and Cloudflare-protected ones
   (it detects them and does not bypass anything).
2. It really keeps playing with the screen off, with headset buttons.
3. The claims about privacy are structural, not marketing.
4. How big the download is, and what the voices cost in storage.
5. How to install an APK safely, and whether the Play listing exists yet (it does not: in review).

## The category's default page, and its predictable opposite

Default page for an Android utility: dark SaaS landing, violet-to-blue gradient hero, a tilted
phone mockup, "AI-powered" pill, three feature cards with line icons, a fake testimonial row, a
pricing table, a cookie banner.

Predictable opposite (also a rut): pure black page, one enormous sentence, no product visible,
"coming soon" email capture.

What this product has that neither shows: **it is a reading instrument**. The app's own design is a
low-glare ink theme; its signature feature is read-along — the sentence you hear is lit on the page.
So the site is set like the front matter of a book (folios, running heads, chapter numerals, a
contents list), shows the real reading surface at true size, and uses read-along itself as the
page's one type-led moment. One section is actual paper: a cream page inside the ink room.

## Primary action

**Download the APK** (v1.3.0, arm64-v8a, 15.5 MB) — the only transaction that works today.
Secondary: see the features, read the privacy policy, browse the source.

Google Play: **not live** (`play.google.com/store/apps/details?id=app.novelspeak` → 404). The
listing is in review for 1.4.0. The site shows that state as a labelled status, never as a button
that does nothing.

## Information architecture

| Path | Job | Length |
|---|---|---|
| `/` | Convince and convert: what it is, proof it works, honest install | 9 screens |
| `/features/` | Answer "does it do the thing I need?" in detail | 6 screens |
| `/voices/` | Answer "which voice, how big, does it need the internet?" | 5 screens |
| `/changelog/` | Prove the project is maintained; state what is actually downloadable | 5 screens |
| `/privacy/` | The Play Console privacy policy, restyled, substance unchanged | reading page |
| `404.html` | Recover to home or the source | 1 screen |

Shared: `assets/site.css` (tokens + components, one file), `assets/site.js` (progressive
enhancement, ≤ 15 KB), `assets/img/*` (derived from repo media), `_headers`, `_redirects`.

## Claims ledger

Every product statement on the site must appear here with evidence. "Soften" means it may be said
only in the form given; "drop" means it must not appear.

| # | Claim on the site | Evidence | Status |
|---|---|---|---|
| 1 | Reads any HTTPS page you open; extraction targets the chapter, not the page chrome | README §Why it exists, §Any URL; `docs/reader-fixes.md` | say |
| 2 | Readability plus a text-density fallback | README §Generic extraction | say |
| 3 | Works on Royal Road, Scribble Hub, WebNovel, Wattpad, Project Gutenberg, Wikipedia shortcuts | README §Use step 1 | say |
| 4 | Two engines: Android system TTS, and on-device sherpa-onnx neural voices | README §Two TTS backends | say |
| 5 | Neural models are 98–123 MB (ZipVoice + vocoder: 156 MB), downloaded only after you confirm the size, SHA-256 verified | README §Neural voices table; `ModelManager` hashes; CHANGELOG 1.4.0 "download-size confirmation" | say, with the per-model numbers |
| 6 | Nothing is bundled in the APK | README §Neural voices | say |
| 7 | Screen-off playback via a foreground service, wake lock and MediaSession | README §Screen-off playback | say |
| 8 | Headset/Bluetooth media buttons work for both engines | README §Screen-off playback, `docs/media-controls.md` | say |
| 9 | Sentence-level queue with look-ahead synthesis; ~12 s generated ahead | README §Streaming pipeline, §Generation ahead | say |
| 10 | Read-along highlight and auto-scroll on the page itself | README §Read-along | say |
| 11 | Auto mode continues chapter to chapter after a 2.5 s pause; off by default | README §Auto mode | say |
| 12 | Local library, continue reading, bookmarks, reading stats | README §Around the reader; CHANGELOG 1.2.0/Unreleased | say |
| 13 | Imports EPUB, PDF (text layer only, no OCR) and plain text | README §Imports; `docs/play-store/listing.md` GOOD TO KNOW | say — PDF limit must be stated |
| 14 | Per-site reader mode and adjustable typography | README §Around the reader | say |
| 15 | Ad blocking at network, cosmetic and behavioural levels; EasyList/EasyPrivacy/uBlock; ~5 MB bundled lists; per-site bypass | README §Ad blocking | say |
| 16 | Blocking runs only inside NovelSpeak's WebView, never device-wide | `docs/play-store/listing.md` QUIET BY DEFAULT | say |
| 17 | No account, no ads, no analytics, no telemetry, no advertising ID, no crash SDK, no push service | README first line, §Around the reader; listing.md; `docs/play-store/DATA_SAFETY.md` | say |
| 18 | No NovelSpeak server; the library and settings stay on the device | listing.md; README §Known limits | say |
| 19 | Paywall and login pages are detected, never bypassed; Cloudflare challenges are not solved for you | README §Known limits; listing.md GOOD TO KNOW | say — this is a trust claim |
| 20 | Diagnostics are created and shared only on request, redacted before writing | CHANGELOG 1.4.0; `docs/play-store/REVIEW_NOTES.md` | say |
| 21 | Sleep timer (minutes or end of chapter), speed, pitch, volume boost, skip-silence | README §Around the reader | say |
| 22 | Story cast: narrator plus characters with different on-device voices; requires Supertonic or Kokoro | README §Story cast; `docs/benchmarks/2026-11-06-story-cast-quality.md` | say, and say it is experimental |
| 23 | Cast quality measured: 4–5/5 voice distinction, 96.2% word accuracy after spelling normalisation; Supertonic RTF 0.168 host / ~0.50 emulator | `docs/benchmarks/2026-11-06-story-cast-quality.md` via README | say **only** with the benchmark named and dated |
| 24 | Reduced-motion support; 1.3×/2× text-scale layouts; 56 dp mini player | CHANGELOG 1.4.0; README §Around the reader | say |
| 25 | Two-pane tablet/landscape layout, two-column library at ≥ 600 dp | README §Around the reader, §Library | say |
| 26 | Android 7.0 (API 24)+, arm64-v8a | README §Install; release notes v1.3.0 | say |
| 27 | v1.3.0 APK is 15,526,233 bytes and signed with the release key | GitHub release v1.3.0 assets; README §Install note | say |
| 28 | 1.4.0 targets API 36, adds AAB signing and WebView hardening | CHANGELOG 1.4.0 | say, labelled **in review** |
| 29 | Apache-2.0 licensed | LICENSE; README §License | say |
| 30 | Open source, issue tracker on GitHub | README links | say |
| — | Ratings, download counts, "trusted by N readers", awards | none in repo | **drop** |
| — | "Best", "fastest", "most natural" voice | no comparative evidence | **drop** |
| — | "Works on every site" | explicitly contradicted by Known limits | **soften** to "text-first pages" + limits |
| — | "Bypasses paywalls / Cloudflare" | explicitly refused by the app | **drop** |
| — | "AI-powered" / "revolutionary" / "seamless" | slop, and untrue of a deterministic pipeline | **drop** |
| — | Play Store availability | listing returns 404 | **drop**; show "in review" |

## Voice

Plain, specific, slightly literary (it is a reading app), never cute. Sentence case. No exclamation
marks. Numbers where numbers exist. Say what the app does, and say what it does not do. Second
person only in instructions. British spelling in prose ("colour", "organise") because the app's own
strings use it; keep product names as the project writes them.

## Boundaries for this work

- Pages only: no change to app code, tests, build, or Play listing copy.
- No invented facts, customers, quotes, ratings, benchmarks or dates.
- Privacy policy text keeps its substance and its URL.
- Both hosts must work: `https://<project>.pages.dev/` and
  `https://vipulgote1999.github.io/NovelSpeak/` (GitHub Pages serves `site/` from a subpath).

## Honest constraints discovered while planning

- No Chromium on this machine (the repo's own browser tests skip), so the taste-skill gate
  (`gate.mjs`) and screenshot checks cannot run here. Verification is static + reproducible by the
  user; see `docs/website/design-contract.md` § Verification.
- No image encoder is installed (no ImageMagick, ffmpeg, sharp). Derived image sizes need either a
  small zlib-based PNG downscaler in `tools/site-assets.mjs` or shipping native sizes with lazy
  loading. Decided in the asset audit.
- The repo's latest GitHub release is **v1.3.0** while the app is at 1.4.0 (no v1.4.0 tag): the
  site must not offer a 1.4.0 APK.
