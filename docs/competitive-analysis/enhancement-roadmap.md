# NovelSpeak enhancement roadmap — evidence-backed, single branch

**Inputs:** 37 competitor products across paid/free/novel streams, 4 APK teardowns, the NovelSpeak 1.1.0 audit,
and the merged feature matrix (all dated 2026-10-24). Every item below names the evidence for why it matters,
the code it hooks into, a size, a risk, and what "done" means.

**Baseline facts this plan is written against** (from novelspeak-audit.md):
29 Kotlin files / 9,746 lines; framework-only (no AndroidX, no Gradle); signed arm64 APK 15,254,977 bytes;
two TTS engines behind one interface; real streaming pipeline; genuine ad-block engine; **no persistence of the
reading cursor**; **no library**; **no share/file ingestion**; **no sleep timer**; zero analytics/ads SDKs;
build reproducible on the phone via ./build.sh.

**Delivery rule:** one branch, one release at a time, no new heavyweight native dependencies. Every release must
keep: APK size budget (aim ≤ 18 MB arm64), permission set (currently 5), zero third-party SDKs, and the hermetic
Termux build. Run the host harnesses in tools/* plus a 2-hour screen-off soak before tagging.

---

## How priorities were set

1. **Frequency in the corpus** — if 90% of competitors ship it, its absence is conspicuous (library: 18/20 known).
2. **Review pain** — where paid users are angriest, there is a trust opening (billing, background death, OCR lies).
3. **Cheap asymmetry** — things only NovelSpeak can do well because of its architecture (offline neural voices,
   generic extraction, in-process playback, ad blocking, no account).
4. **Cost/risk inside the current constraints** — framework-only, Termux build, one-device evidence.

Sizes: **S** under a day, **M** 1–3 days, **L** 1–2 weeks of focused work on this codebase.

---

## P0 — v1.2 "an app you can live in" (2–3 weeks)

These are the smallest changes with the largest retention and trust effect. They also fix two correctness bugs.

### P0.1 Persist reading position + "Continue reading"
- **Why:** every paid competitor resumes; today only last_url and 8 recent URLs survive a restart and the sentence
  cursor is in memory (MainActivity.kt:136,688-689; Prefs.kt:143-158). Android kills background processes; losing
  your place in a 900-chapter story is the fastest uninstall.
- **Where:** new keys in Prefs (url/title/cursor/blockIndex/updatedAt); write-behind on onSentenceDone
  (Reader.kt:341) and setContent (Reader.kt:158); restore in MainActivity start path; survive process death.
- **Size/risk:** S / low. Plain data, no engine changes.
- **Done:** kill the app mid-chapter, relaunch → prompt/flows straight back to the same sentence; UI copy says
  "Continue chapter 812 — 42%".

### P0.2 Local library + history
- **Why:** library is the most universal competitor feature (18/20 known) and the foundation for bookmarks,
  stats and sync. @Voice/ReadEra/Moon+ all have it; NovelSpeak has 8 recent URLs.
- **Where:** new Library store (JSON in filesDir or a small SQLite table); list screen reachable from overflow;
  Reader state as the source of truth; delete/mark-finished/clear.
- **Size/risk:** S–M / low–medium (needs one simple screen and lifecycle handling).
- **Done:** every opened chapter appears with site, title, last position, timestamp; one tap resumes; survives
  reinstall? (no, local only — state that plainly).

### P0.3 Share-sheet and clipboard ingestion
- **Why:** converts NovelSpeak from "a browser" into "a reader for any text"; @Voice's "Add to List" share target
  and browser extension are a cheap acquisition path. Also the only fast path for text already on screen.
- **Where:** manifest intent filters ACTION_SEND (text/plain) and ACTION_PROCESS_TEXT; MainActivity intent handler;
  synthesize a single Block through the existing List<Block> contract (WebExtractor.kt:65; SentenceSplitter.kt:28).
- **Size/risk:** S / low.
- **Done:** "Share → NovelSpeak" from Chrome/other apps reads the selection or URL; reading starts with one tap.

### P0.4 True per-site settings (bug fix + feature)
- **Why:** the site dialog's "Reader mode" item writes the global preference (MainActivity.kt:1010-1057) — the UI
  implies per-site. This is a correctness bug, not just missing polish. Ad-block allowlist is already per-domain.
- **Where:** Prefs keyed by registrable domain (pattern already used for adblock at Prefs.kt:124-132); site dialog;
  WebExtractor.setReader; migrate the existing global value as the default.
- **Size/risk:** S–M / low.
- **Done:** set reader mode on site A, site B is unaffected; setting survives restart.

### P0.5 Sleep timer with fade-out
- **Why:** bedtime listening is the core novel use case; ElevenReader/Evie list it, @Voice ships an alarm receiver.
  Cheap parity and a retention feature.
- **Where:** player card (MainActivity.kt:403-543); Handler in Reader/PlaybackService; use PcmPlayer gain ramp
  (PcmPlayer.kt:186) to fade 3 s then pause; options 15/30/45/60 min and end-of-chapter.
- **Size/risk:** S / low.
- **Done:** timer fades and pauses; lock-screen notification reflects the remaining time; resuming clears it.

### P0.6 Fix the speed control contract
- **Why:** UI offers 0.5–2.5× (MainActivity.kt:1292-1297) but both engines clamp at 2.0×
  (SystemTtsEngine.kt:333; SherpaEngine.kt:301-302), so 2.05–2.5× silently lie. Paid apps advertise 4–5×.
- **Where:** either raise the engine-side limit where possible, or cap the UI at 2.0× and label "max for this
  engine"; add a 3×/4× path only after measuring quality/RTF on device.
- **Size/risk:** S / low.
- **Done:** UI and engine agree; no silent behaviour differences.

### P0.7 WebView lifecycle + privacy hardening (release blocker for a store listing)
- **Why:** no onPause/onResume/destroy means page timers keep burning battery during screen-off playback and the
  WebView leaks on recreation (MainActivity.kt:1452-1457). Manifest also has allowBackup=true with no rules and an
  exported MediaButtonReceiver (AndroidManifest.xml:20,51-58). Cheap fixes before any Play review.
- **Where:** WebView lifecycle in onPause/onResume/onDestroy; backup rules excluding models/prefs-as-needed;
  android:permission on the receiver or keep it non-exported; review mixed-content + third-party-cookie policy.
- **Size/risk:** S / low.
- **Done:** 2-hour screen-off test shows no idle CPU from the page; adb dumpsys shows no leaked WebView; receiver
  cannot be triggered by a third-party app.

### P0.8 Positioning rewrite + offline proof
- **Why:** the current premise is factually wrong about Chrome (see README finding 2). Trust is the differentiator.
- **Where:** README, first-run copy, Settings "About/Privacy", release notes.
- **Size/risk:** S / low.
- **Done:** no claim that Chrome dies on lock; a visible "works in airplane mode" demo; a short privacy page with the
  full network-destination list (pages, model downloads, filter lists).

### P0.9 Paywall/login detection with honest warning (RE adopt)
- **Why:** @Voice's LoginWallDetect.js is the lawful, honest pattern: detect and warn instead of extracting garbage
  or bypassing. Today a login wall yields partial/garbage text.
- **Where:** extract.js result can carry flags; WebExtractor parses them; MainActivity shows "Login or subscription
  may be required — open this page in the browser and sign in" with a button; never bypass.
- **Size/risk:** S / low–medium (false positives).
- **Done:** a known paywalled chapter shows the banner instead of reading nav junk; normal chapters unaffected.

### P0.10 Playback diagnostics + support bundle (light)
- **Why:** paid reviews show background playback death is the #1 functional complaint; NovelSpeak's DiagLog is flat
  and on shared storage. @Voice/NekoSpeak show a better pattern (exit taxonomy + capped report bundle).
- **Where:** DiagLog taxonomy (ANR / low-memory / native signal / audio focus loss / network); a "Share diagnostics"
  action with size cap and redaction; keep zero remote telemetry.
- **Size/risk:** S–M / low.
- **Done:** a user can send one file that explains why playback stopped; no new permissions.

**P0 exit criteria:** reading position survives process death; library + resume in daily use; sleep timer works from
both engines; share-to-read works; no silent speed mismatch; 2-hour screen-off soak clean; positioning updated.

---

## P1 — v1.3 "parity plus" (3–5 weeks)

### P1.1 Chapter prefetch
- **Why:** auto mode's weakest moment is the 2.5 s + network gap at every chapter boundary. Extract the next chapter
  while the current one plays (a second hidden WebView), append through the existing queue.
- **Where:** MainActivity.loadNextChapter (~1277), WebExtractor.install/extract, Reader.appendContent (Reader.kt:169).
- **Size/risk:** M / medium (second WebView lifecycle, memory, duplicate onPageFinished, adblock injection).
- **Done:** auto mode transitions with no visible stall on a normal connection; queue never duplicates.

### P1.2 Pronunciation dictionary / replacement rules
- **Why:** names and acronyms are the top neural-TTS complaint; ElevenReader/NaturalReader/Legere/@Voice/Audify all
  ship pronunciation control. It is also the cheapest way to make neural output feel expensive.
- **Where:** TtsTextCleaner.kt pre-pass; a small user rule table (source → replacement, optional regex/language);
  Settings "Reading → Pronunciation"; preview button.
- **Size/risk:** S–M / low.
- **Done:** a user can fix "Shen Wei" → "Shen Way" and hear the difference immediately.

### P1.3 Reader typography + accessibility controls
- **Why:** fixed 17 px dark-only reader (extract.js:219-227) vs Legere's 80 pt/OpenDyslexic/3 themes and Moon+'s
  controls. Accessibility is the cheapest quality signal in this category and a prerequisite for tablets.
- **Where:** CSS variables injected by extract.js; WebExtractor.setReader config; Settings "Reading"; honour system
  font scale; announcements on state change.
- **Size/risk:** S–M / low–medium (must not break highlight offsets).
- **Done:** font size/family/line-height/theme/width controls with instant preview; TalkBack announces play/pause.

### P1.4 Word-level read-along highlight
- **Why:** paid table stakes (ElevenReader/NaturalReader/Legere); Speech Central lost reviews when it downgraded to
  sentence-level. NovelSpeak already has block-level data-ns tags.
- **Where:** extract.js highlight function; TTS boundary offsets from SystemTtsEngine (range callbacks) and sherpa
  boundary callbacks; sentence-level fallback when boundaries are absent.
- **Size/risk:** M / medium (alignment accuracy across both engines and pages).
- **Done:** the spoken word highlights within ±1 word on both engines for normal prose; sentence mode remains a toggle.

### P1.5 Volume boost, skip-silence and pitch
- **Why:** three settings every TTS reader is asked for; most of the plumbing exists (PcmPlayer gain/limiter,
  AudioDsp gate, AudioTrack setPlaybackParams API 23+).
- **Where:** PcmPlayer.kt write path and gain envelope; AudioTrack playback params; Settings "Tuning".
- **Size/risk:** S–M / low–medium (clip risk, DSP interaction).
- **Done:** boost 0–200%, skip leading/trailing silence, pitch ±20% without changing speed; no clipping on peaks.

### P1.6 Audio export (WAV) of the current chapter/queue
- **Why:** README roadmap item; @Voice exports MP3 (lame), Audify/TTS Reader WAV. WAV is framework-only and honest;
  MP3 would add a native dep. Useful for offline commutes and for users who want a keepsake.
- **Where:** tee the PcmPlayer writer stream to a file; MediaStore insert via DiagLog's existing pattern
  (DiagLog.kt:48-86); progress UI; estimate ~173 MB/hour at 24 kHz mono 16-bit and say so.
- **Size/risk:** M / medium (disk, cancellation, background service interaction).
- **Done:** export a chapter to Downloads, verify duration/hash; cancel leaves no partial file.

### P1.7 Bookmarks + highlight anchors
- **Why:** library is useless without marks; the data-ns indices already exist (extract.js:64) and can jump back.
- **Where:** store blockIndex + normalized snippet + timestamp in Library; seek via Reader.seek (Reader.kt:288);
  re-match on re-extraction if block count changed.
- **Size/risk:** M / low–medium.
- **Done:** bookmark the current sentence, navigate away, come back and jump to it.

### P1.8 Reading stats
- **Why:** cheap retention feature; gives the team real usage data without telemetry (local only).
- **Where:** counters on onSentenceDone; daily rollup in Prefs/JSON; Settings readout.
- **Size/risk:** S / low.
- **Done:** minutes/sentences per day and per site, visible in Settings; no network.

### P1.9 Signed OTA model/voice catalog (RE adopt)
- **Why:** today model hashes are hardcoded in ModelManager.kt; @Voice ships a signed catalog refreshed over the air
  with minAppVersion gates and retirement, so upstream renames do not brick installs.
- **Where:** ModelManager.kt:38-59 (manifest) + 107-173 (download); a signed JSON/txt fetched from a pinned URL,
  verified with an embedded public key, cached, with a bundled fallback.
- **Size/risk:** M / medium (signing key management, replay protection).
- **Done:** adding/retiring a voice requires no app release; a tampered catalog is rejected and logged.

### P1.10 Diagnostics/support bundle upgrade
- Extend P0.10 with a capped archive (meta.json, prefs snapshot with secrets removed, events log, last crash),
  FileProvider share, 14-day TTL. Size S.

**P1 exit criteria:** chapter transitions feel seamless; word highlight on both engines; typography settings;
export works; model catalog is OTA-able.

---

## P2 — v2.0 "breadth and platform" (quarter+)

| Item | Why | Anchor | Size | Risk |
|---|---|---|---|---|
| EPUB import (zip+OPF+spine) | File readers own formats; share-sheet is the on-ramp | new EpubImporter; java.util.zip + XmlPullParser; feed Reader.kt:158 | L | medium-high |
| PDF text-layer import | Paid rivals accept PDF; @Voice isolates PDF in a :pdfReader process | new PdfTextExtractor; avoid commercial SDKs; page text only | M–L | medium (complex PDFs) |
| Translation/dictionary hand-off | @Voice bilingual view; Prestigio/Librera dictionaries | WebView overlay / ACTION_PROCESS_TEXT; no heavy deps | M | medium |
| Tablet/foldable two-pane | no window-size logic today (MainActivity.kt:148-571) | layout rework, reader + page side by side | M–L | medium |
| Optional encrypted sync | progress only, user-owned (WebDAV/export file); no account | new sync module; opt-in, client-side encryption | L | high (scope/privacy) |
| Optional BYO-key cloud TTS | @Voice proves the pattern; never a subscription | TtsEngine implementation + Settings key store; keys never leave device | M–L | medium (legal/policy) |
| Play listing + one-time Pro unlock + ABI splits | 37 competitors are store-distributed; billing evidence supports $4.99–$6.99 | build.sh AAB/APK set; Play Billing wrapper | M–L | medium (policy) |
| Android Auto / car | commuter niche; MediaBrowserService | new service; reuse MediaSession | M–L | medium (review) |
| Full i18n of UI strings | 350 hardcoded English literals; limits reach | resources extraction; needs UI freeze first | M | low |

---

## Explicit non-goals (do not build)

1. **A book-source / rule-DSL scraper** — Legado's upstream was deleted over infringement (2026-05-27). Stay a
   generic page extractor; no per-site rule marketplace, no content storage/redistribution.
2. **Ad-mediation SDKs** — @Voice carries ~5,000 ad classes for a one-time unlock; that is an APK-size, privacy and
   attack-surface tax that contradicts NovelSpeak's entire trust story.
3. **Cloud TTS subscription / credit meters / per-book paywalls** — the dominant source of 1-star reviews in the
   paid corpus. If cloud voices ever ship, use BYO-key only.
4. **Camera single-page OCR marketed as book scanning** — Speechify's most-attacked promise (17/40 reviews).
5. **Celebrity voice packs / unconsented cloning** — legal/ethical minefield; user-recorded/imported clones only.
6. **Audiobook/eBook store, AI study suite, podcasts/quizzes** — expensive, account-first, and outside the promise.
7. **Heavy AndroidX/Gradle migration for its own sake** — the framework-only build is a feature; migrate only if a
   concrete dependency (Play Billing, Media3) demands it.

---

## Release plan and gates

| Release | Contents | Gate |
|---|---|---|
| **v1.2** | P0.1–P0.10 | host harnesses (adblock/DSP/cleaner/urlinput) + build.sh + 2-hour screen-off soak + process-death test + positioning review |
| **v1.3** | P1.1–P1.10 | above + chapter-boundary soak (auto mode 20 chapters) + export verification + catalog signature test |
| **v2.0** | P2 selected | above + format fixtures (EPUB/PDF corpus) + Play policy review + privacy page + store listing |

**Every release:** no permission additions without justification; APK size reported in the release notes; no new
network destinations without listing them in the privacy page; no silent feature regression (keep the 22/22 adblock
self-test and the URL-scheme tests green).

**Testing gaps to close first (from the audit):** there is no CI and no coverage for Reader/engine interaction,
auto-advance or PlaybackService. Before P1.1, add host-JVM tests for the Reader state machine (queue/cursor/epoch)
and a deterministic extraction fixture; instrumentation tests can wait until a device farm exists.

---

## First two weeks (concrete)

1. Day 1–2: P0.1 position persistence + P0.4 per-site settings (both touch Prefs; do together).
2. Day 2–3: P0.2 library screen on top of the same store.
3. Day 4: P0.3 share-sheet ingestion + P0.6 speed contract.
4. Day 5: P0.5 sleep timer with fade.
5. Day 6–7: P0.7 hardening + P0.8 positioning/offline proof.
6. Day 8: P0.9 login-wall detection spike behind a flag.
7. Day 9–10: P0.10 diagnostics; full soak; tag v1.2.
8. Then start P1.1 chapter prefetch (the highest-risk/most-visible P1 item) with a feature flag.

---

## Success metrics (local-first, no telemetry)

- **Retention proxy:** chapters opened per install per week; resume-after-kill success rate (local counter).
- **Reliability:** screen-off playback minutes per session; playback-death events per 100 sessions (from DiagLog).
- **Quality:** model install completion rate; sentence-boundary complaints in user reports; APK size.
- **Positioning:** share-by-intent usage; "works offline" demo completion in onboarding.
- **Manual:** App-store review themes once listed (compare against the paid corpus complaint table).

---

## Open decisions for the owner

1. Ship P0 as v1.2 on GitHub first, or go straight to a Play listing with billing?
2. Is a one-time Pro unlock ($4.99–$6.99) acceptable, or keep everything free/donation-only?
3. Priority between EPUB import and word-level highlight after v1.2 (both medium-large; different audiences).
4. Do we want optional BYO-key cloud voices at all, given the trust positioning?
5. Target languages for the first expansion (Kokoro multi-lang/Piper bundles; UI i18n cost).

*All evidence links and per-item rationale are in README.md, feature-matrix.md and the five stream documents.*
