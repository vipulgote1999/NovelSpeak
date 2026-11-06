# NovelSpeak — Expert UI/UX Audit & Continuous Improvement Direction

**Date:** 2026-11-06
**Scope:** Home, reader chrome, player, Library, Settings, Story Cast sheet, phone and tablet widths.
**Evidence:** rendered screenshots in `docs/media/ui-audit/`:
[phone home](../../media/ui-audit/phone-home.png) ·
[phone reader](../../media/ui-audit/phone-reader.png) ·
[phone library](../../media/ui-audit/phone-library.png) ·
[phone settings](../../media/ui-audit/phone-settings.png) ·
[phone cast](../../media/ui-audit/phone-cast.png) ·
[tablet home](../../media/ui-audit/tablet-home.png) ·
[tablet reader](../../media/ui-audit/tablet-reader.png)

Devices: phone 1080×1920 @ 420 dpi; tablet 1200×1920 @ 240 dpi (800 dp wide).

---

## 1. Verdict

NovelSpeak already has a real identity: **Harbor ink**, a warm amber rule, a mint signal, a serif
wordmark, and readable long-form typography. Settings is the strongest screen, the reader is
legible, and the Story Cast feature is now visible and functional.

The app does **not** yet feel like an expert-designed product because the surfaces are individual
programmatic views, not one system. The three gaps that matter most:

1. **No adaptive content frame.** Home, Settings and Library stretch to the full 800 dp tablet
   width; the shortcut grid floats apart, the continue cards become long bands, and the Settings
   cards become enormous. The reader is the only screen with a real two-pane layout.
2. **Browser chrome leaks into the product.** The floating overflow circle, the 50 dp address bar,
   the separate title/ad/“Reader on” row, and the `Ad 0/122` string make the reader feel like a
   WebView with a player bolted on.
3. **The player and cast surfaces are functional but not designed.** The phone player card is a
   tall stack of six rows; Read/Next/Cast are 38 dp buttons; the cast sheet is a stock gray
   `AlertDialog` with no confidence or per-line correction.

The highest-leverage first move is a **design-token + adaptive-frame layer**, then rebuilding Home
and the player around it. That single foundation fixes the tablet embarrassment and makes every
later screen cheaper to polish.

---

## 2. Strengths to keep

- **Palette and contrast.** `Palette.kt` is semantic and documented; the mint accent against ink is
  distinctive, and the amber rule gives the brand a literary warmth.
- **Reader typography.** Serif long-form text at a comfortable measure; dark theme is restful.
- **Settings hub.** Clear title/subtitle/chevron cards, one tap to a short page, instant-save
  footer. This is the pattern the rest of the app should follow.
- **Library progress.** The thin mint progress bar and finished state are immediately readable.
- **Story Cast is real.** Three on-device voices, a speaker label, recast, and persisted cast.
- **Accessibility baseline.** Most icon buttons have content descriptions; the reader exposes
  “Sentence N of M”, “Current voice”, and status labels.

---

## 3. Findings

### 🔴 Blocking

**B1. Tablet Home has no content frame or adaptive grid.**
`buildHomeView()` (`MainActivity.kt:794`) uses a full-width `ScrollView` with fixed 24 dp padding
and two fixed rows of four shortcut cells (`:854-866`). At 800 dp the 4 cells spread across
1120 px, the search pill becomes a 1120 px band, and the continue cards are 1120 px long. The
wordmark sits at the far left; the screen has no editorial center.
**Fix:** `Design.contentWidth(widthDp)` capped at 720 dp, centered with adaptive gutters; use
4 columns on compact, 4–6 on medium and 6–8 on expanded only when the item count justifies it.
**Why:** a tablet must feel composed, not like a stretched phone.

**B2. The Story Cast sheet is a stock `AlertDialog`.**
`showCastDialog()` (`MainActivity.kt:2950+`) uses the platform dialog: gray panel, all-caps
buttons, no brand surface, no confidence, no per-line fix. It is the feature's front door.
**Fix:** branded bottom sheet with the same `SURFACE`/`BORDER`/mint system; cast rows with a voice
chip, confidence dot, “Preview”, and a “Fix this line” action for `UNKNOWN` lines.

**B3. Single-row delete has no confirmation.**
`LibraryActivity.deleteButton()` (`:433`) removes the entry immediately on tap. The user can lose a
reading position with one accidental tap; the 40 dp target is also small.
**Fix:** 48 dp overflow/delete target and a confirm dialog naming the title; keep “Clear” confirmation.

### 🟠 Important

**I1. Home reads as a browser new-tab page, not a reading app.**
The 34 sp wordmark, the detached floating menu at the top-left, the pure-white 56 dp shortcut
plates, and the flat continue cards create a “Chrome mockup” impression. The brand is quieter and
more literary than that.
**Fix:** top bar with wordmark + menu aligned to the content frame; 48–50 dp shortcut plates with
28 dp marks and 12 sp labels; continue cards with a cover/initial, a 3 dp progress bar, and a
chevron; reduce the wordmark to 30–32 sp on compact, 34 on expanded.

**I2. Player card is too tall and has no hierarchy beyond rows.**
At phone width the card is ~420 px tall: status, voice, speaker, progress, Read/Next/Cast + speed,
transport. `Read`, `Next ch`, `Cast on` are 38 dp high; speed buttons are 34 dp; the stop button is
40 dp.
**Fix:** 44–48 dp targets, group the action row into a single segmented strip, move speed into a
compact 1.0× chip with −/+ on either side, and add a collapse affordance that leaves a 56 dp mini
bar (status + play/pause + progress). Keep all controls reachable.

**I3. Reader chrome repeats itself. ✅ Fixed (Wave 4).**
Top bar (50 dp) + title row + `Ad 0/122` + `Reader on` competes with the page. The title is shown
twice (browser title and WebView title), and the ad string is cryptic.
**Fix shipped:** one app bar. Reader and the ad shield are 44 dp inline controls inside the
address pill, the chapter title moved into the player card (and the mini bar), Forward hides when
there is no history, and the cryptic ad string became a shield state in the content description.
The second chrome row is gone; the page gains its ~32 dp back.

**I4. Library is a flat file-manager list. ✅ Fixed (Waves 2–4).**
`LibraryActivity` rendered every entry as a full-width card with title, meta, progress and a bare ✕,
with no search, filter, grouping, or empty-state illustration; tablets kept full-width rows.
**Shipped:** search, All / Chapters / Imported / Finished filters, a designed empty state, 48 dp
delete targets with confirmation, a recycled `ListView` (open p99 250 → 48 ms on a 200-entry
stress test), and a **two-column grid at ≥600 dp**. Cards pair inside one recycled adapter row, so
a tablet still inflates only the visible screen. Headers stay full width; an odd tail leaves a
spacer instead of stretching the last card.

**I5. Settings is not adaptive and lacks icons/sectioning.**
The hub rows (`SettingsActivity.kt:806`) are full-width 64 dp cards with no icon column. On tablet
they become 1120 px bands.
**Fix:** max 720 dp centered frame; section labels (“READING”, “VOICE”, “APP”); 24 dp leading icon
per row; two-column hub at ≥840 dp; keep the subtitle to one line.

**I6. Touch targets are inconsistent.**
Top bar icon buttons 40 dp (`MainActivity.kt:279-283`, `:427`), Library delete 40 dp, player
Read/Next/Cast 38 dp, speed ± 34 dp, stop 40 dp. Android guidance is 48 dp.
**Fix:** 48 dp for top bar/transport/row actions; 44 dp minimum for dense card controls; expand hit
area without growing the visible glyph.

**I7. No motion system.**
Screen changes are default; the player card appears abruptly; the cast sheet pops. There is no
respect for reduced motion.
**Fix:** shared durations/easings (`Design.MOTION_FAST=140`, `MOTION_NORMAL=220`,
`MOTION_SLOW=320`), fade/slide for sheet and card, 80–150 ms press feedback, and an animator-scale
check that disables motion when the system asks.

### 🟡 Polish

- **P1. Type scale is ad hoc.** 34/15/14.5/13.5/13/12.5/12/11.5/11/10.5/10 sp coexists. Introduce
  display/title/headline/body/label/caption tokens and migrate touched screens.
- **P2. Section labels.** “Shortcuts” is sentence case at 13 sp; Library uses “CHAPTERS” at 10 sp.
  Pick one eyebrow style: 11 sp, 0.08 em tracking, muted.
- **P3. Icons are inconsistent.** Favicon plates are pure white; transport glyphs are 2 px line
  icons; chevrons are text `›`; delete is `✕`. Standardise the icon family and sizes.
- **P4. Status dot is color-only.** Add text (already there) and a state icon; keep the dot as a
  secondary cue.
- **P5. Empty/loading states.** Consistent copy and skeleton/spinner treatment for Library, Home,
  cast analysis, and model install.
- **P6. Cast confidence.** Show `HIGH/MEDIUM/LOW` as a dot + label, never color alone.
- **P7. Dialog typography.** All-caps platform buttons clash with the sentence-case product;
  branded sheets with sentence-case actions.

---

## 4. Design direction: “Quiet editorial”

The brand is a **quiet reading room**, not a browser. Keep Harbor ink; make the surfaces feel
edited.

### Tokens

| Token | Value |
|---|---|
| Spacing | 4, 8, 12, 16, 24, 32, 48 dp |
| Radii | 8, 14, 20, 28 dp; pill = 999 |
| Type | display 32/38 serif · title 20/28 · headline 17/24 · body 15/24 · label 12/16 · caption 11/14 |
| Color | `Palette` unchanged; `FAINT` only on `BG`/`SURFACE`, never `SURFACE_HIGH` |
| Touch | 48 dp standard, 44 dp dense, 56 dp primary play |
| Motion | 140 ms press · 220 ms sheet/card · 320 ms screen entrance |
| Content frame | max 720 dp on Home/Settings/Library; 960 dp for two-column Library |

### Component rules

- **App bar:** 48 dp actions, 46 dp address pill, content aligned to the frame.
- **Shortcut tile:** 50 dp plate, 28 dp mark, 12 sp label, 8 dp gap; plate `#E9EEF2` not pure white.
- **Continue card:** 44 dp initial/cover, title 15 sp, meta 12 sp, 3 dp progress, 16 dp chevron.
- **Player:** mini bar (status, play, progress) + expanded sheet; 48 dp transport; speed chip.
- **Cast sheet:** bottom sheet, 20 dp top radius, rows with name/voice/confidence, Recast + Fix line.
- **Library row:** 44 dp initial/cover, title 15 sp, meta 12 sp, progress, 48 dp overflow menu.
- **Settings row:** 24 dp icon, title 15 sp, subtitle 12 sp, chevron; 8 dp row gap.

### Adaptive contract

| Width | Layout |
|---|---|
| < 600 dp | single column; 20 dp gutters; 4 shortcut columns; full-width cards |
| 600–839 dp | 24–28 dp gutters; 4–6 shortcut columns; reader two-pane; Library 2 columns |
| ≥ 840 dp | 32 dp gutters; 6–8 shortcut columns; Settings 2 columns; Library 2–3 columns; optional nav rail |

All top-level screens center a max-width content frame. The reader keeps the existing page + card
two-pane; the card becomes a side panel with a sticky mini bar on compact.

---

## 5. Performance & accessibility

- **Library/Home view cost.** Library now uses a platform **ListView + recycled adapter**; a
  200-entry stress test reduced the initial-render p99 from **250 ms to 48 ms** and slow UI-thread
  frames from 3 to 1. Scrolling is 21 ms p50 with 0 slow UI-thread frames. Home still renders only
  three Continue cards; its remaining cost is the 200-entry store scan/sort, not 200 views.
- **Home refresh.** `refreshHome()` rebuilds all continue cards on every `showHome`; cache the
  rendered state and diff by id/updatedAt.
- **Images.** Home icons are loaded from assets and cached in a `HashMap`; library covers are
  absent. If covers are added, decode to the target size and cache.
- **Reduced motion.** Respect `Settings.Global.ANIMATOR_DURATION_SCALE`; no animation if 0.
- **TalkBack.** Keep content descriptions; add selected/on/off state to Reader/Cast/Story Cast;
  ensure focus order follows visual order; announce cast-analysis completion and errors.
- **Text scaling.** Test at 1.3× and 2× font scale; current fixed-height cards (status row, action
  row) will clip. Use `minHeight` rather than fixed heights where text can grow.
- **Contrast.** The `Palette` comments already assert ratios; re-run a script after any color
  change and keep `FAINT` off `SURFACE_HIGH`.

---

## 6. Continuous improvement loop

| Wave | Theme | Deliverables | Gate |
|---|---|---|---|
| **0** | Audit | this document + screenshots | reviewed |
| **1** | Foundation + Home + player | `Design.kt`; adaptive frame; Home tiles/cards/top bar; 44–48 dp targets; Library delete confirm | build + host tests + Maestro 01/03/13/16 + phone/tablet screenshots |
| **2** | Library + Settings | search/filter/2-column; covers/initials; overflow menu; Settings icons/sections/2-column; Story Cast row | same + Maestro library/settings flows |
| **3** | Player + cast | mini player + expanded sheet; branded cast sheet; confidence; Fix line; voice preview | Maestro cast flow + on-device sids |
| **4** | Motion + a11y + perf | shared motion; reduced motion; TalkBack states; ListView/adapter; text-scale audit | contrast script + 1.3×/2× screenshots + perf trace |
| **5** | Parity features | reading stats card, themes, import flow, translation hand-off, chapter sheet | feature tests + full Maestro suite |

Every wave follows the same loop: **screenshot → critique → implement → build → host tests →
Maestro regression → screenshot → compare → next finding**. A wave is not done until the previous
wave's flows still pass and the new screenshots show a measurable improvement.

---

## 7. Wave 1 result (2026-11-06)

Shipped on `feat/story-cast`:

- **`Design.kt`** — spacing/radii/type/motion/touch/content-frame tokens; **15 host checks**.
- **Home** — centered 720 dp frame on tablets, right-aligned overflow, 50 dp/28 dp shortcut
  plates, 12 sp labels, progress-bearing continue cards with chevrons, 32 sp wordmark.
- **Player** — 48 dp top-bar/transport targets, 44 dp dense actions, 40 dp speed controls,
  12–14 sp labels.
- **Library** — single-row delete now confirms; 48 dp delete targets; unique TalkBack labels
  (“Delete entry: …”).
- **Regression** — Maestro `01`, `13`, `03`, `16`, and new `20_library_delete_confirm` pass;
  host suite **442 checks**.

Before/after: [phone home](../../media/ui-audit/wave1-home-phone.png) ·
[tablet home](../../media/ui-audit/wave1-home-tablet.png) ·
[phone reader](../../media/ui-audit/wave1-reader-phone.png).

Still open from the audit: the reader chrome/title row, the stock cast dialog, Library search/
grid, and Settings icons/two-column layout. Those are Wave 2–3.

## 8. Wave 2 result (2026-11-06)

- **Settings** — centered 720 dp frame, sectioned hub (READING / VOICE & PLAYBACK / STORY /
  PRIVACY & APP), eyebrow typography, working **Story cast** toggle with an on/off summary.
- **Library** — search field, All/Chapters/Imported/Finished filters, filtered empty state,
  centered frame, 44 dp chips and clear action. Search/filter are covered by a new Maestro flow.
- **Regression** — Maestro `01`, `13`, `18`, `20`, `21`, `03`, `16` pass (106 commands).

Still open: the reader chrome/title row, Library grid on tablets, Settings leading icons and
two-column hub, and Wave 4 motion/accessibility/performance. The stock cast dialog and the player
mini/expanded split were completed in Wave 3.

### Story Cast quality benchmark

`docs/benchmarks/2026-11-06-story-cast-quality.md` measured the actual cast path: 18 cues,
**51.45 s** audio, Supertonic RTF **0.168** host / **~0.50** emulator, three-round switch median
**−12 ms**, raw ASR WER **8.7%** (name-normalised **3.8%**), and 4–5/5 voice distinction from the
valid clean-PCM judge. Kokoro was benchmarked on the same cues: ~3× slower in absolute synthesis
(RTF 0.768) but closer to normal audiobook pacing (190 vs 121 words/min); it remains the quality
option pending a listening test. Two concrete defects were found and one was fixed in this wave:
`CastChunker` now joins short same-speaker fragments, so `"Tomas. I told you…"` is one cue instead
of two. Remaining quality work: ultra-short-line recognition (`"Whose?"` was an ASR hallucination,
the waveform has one word), a quiet final word (`… didn't sit.` is present but missed), and
emotional range.

## 9. Wave 3 result (partial): branded cast sheet (2026-11-06)

- `CastSheet.kt` replaces the stock `AlertDialog` with a Harbor-ink bottom sheet: drag handle,
  STORY CAST eyebrow, chapter title, “Narrator + N characters”, a cast row per speaker, voice
  label, confidence label, line count, unresolved-line banner, and per-character **Change**.
- The sheet caps at 560 dp on tablets, uses 44–48 dp targets, and includes TalkBack descriptions
  for every row and action.
- The parser now promotes a member’s confidence from the best evidence among their lines, and
  `CastStore` persists it. Before this, every character loaded as “Review” even when the parser
  was confident.
- **Per-line Fix speaker** is live. The unresolved banner exposes **Fix current line**, opens a
  narrator/character picker, persists a chapter-scoped `LineFix`, and rebuilds the queue. On-device:
  `"He's right here."` → Tomas, `unresolved 2 → 1`, `sid 6`, and the correction survived a process
  restart. Render: [Fix current line](../../media/ui-audit/wave3-cast-fixline.png) ·
  [after fix](../../media/ui-audit/wave3-cast-fixed.png).
- **Dialogue silence trim.** Supertonic pads every short line to ~1.35 s (0.4 s lead + 0.6 s
  tail). Cast dialogue now trims that padding per cue; a measured short line went from 59,724 to
  24,102 samples (1.6 s less dead air). Ordinary playback and the user’s skip-silence setting are
  unchanged.
- **Mini player.** The full card collapses to a 56 dp bar (status, voice/speaker, play/pause,
  sentence progress, expand). On phones it returns ~350 dp to the page; on tablets the page gets
  the full width with the bar across the bottom. Player visibility rules are host-tested
  (`PlayerChrome`). Renders: [phone collapsed](../../media/ui-audit/wave3-mini-phone.png) ·
  [phone playing](../../media/ui-audit/wave3-mini-playing.png) ·
  [tablet collapsed](../../media/ui-audit/wave3-mini-tablet.png).
- **Character voice preview.** Every cast row has **Preview**, which auditions the character's
  persisted real line with their assigned voice. The preview parks the reader engine first, so the
  book cannot synthesize underneath the sample; playback resumes only after the preview finishes.
  On device: `voice preview neural voice=2 label=Mara · F3`, `voice preview line chars=56`,
  `sid=2 requested=2`, and no reader generation during the sample. The same change fixes the
  Settings preview regression where `speak` no longer received the preview voice.
  Render: [cast preview](../../media/ui-audit/wave3-cast-preview.png).
- **516 host checks** pass (parser 42, store 42, library 211, feature 130, design 15, catalog 18,
  selection 7, chunker 10, audio DSP 32, player chrome 9).
- **Maestro 7 flows / 106 commands** pass on the preview build (launch, home, settings, library
  delete, library search, read, transport). Mini-player states were also verified on phone and
  tablet, including play/pause from the bar.

Still open: an ASR re-check of one-word lines after the trim, a human listening pass, and Wave 4
performance tracing.

## 10. Wave 4 result (partial): reduced motion and text scale (2026-11-06)

**Reduced motion.** `Motion` holds the pure decision and `MotionAndroid` reads the system animator
duration scale; `areAnimatorsEnabled()` is used on API 26+, with the Settings fallback below that.
Home entry and the cast-sheet window animation are gated. With the system scale at 0 the device log
shows `home shown motion=false` and `cast sheet motion=false`; with the scale restored it shows
`home shown motion=true`.

**1.3× / 2× text scale.** The audit found and fixed:

- Four-column shortcut labels truncated at 2× (“Empire…”, “Royal …”). Home now uses **two
  columns above 1.5×**, so all eight labels fit.
- The Library filter strip cut “Finished” at 2×. It now uses **two rows of two** above 1.5×.
- The anchored `PopupMenu` fell off the screen at 2×. Above 1.5× the overflow is a **scrollable
  list dialog**, so “NovelSpeak settings” and “About & privacy” remain reachable.
- Fixed-height text buttons/chips (32/44/48 dp) clipped at 2×. Text controls now use a minimum
  height with wrap-content height; the player’s speed controls move to their own row at ≥1.2×.
- The cast sheet exceeded the screen at 2×. It is now a **measured, scrollable sheet** with a
  visible footer; confidence labels shorten to High/Medium/Review at large text.

Renders: [1.3 Home](../../media/ui-audit/scale13-home.png) ·
[1.3 Library](../../media/ui-audit/scale13-library.png) ·
[2.0 Home](../../media/ui-audit/scale20-home.png) ·
[2.0 Library](../../media/ui-audit/scale20-library.png) ·
[2.0 Settings](../../media/ui-audit/scale20-settings.png) ·
[2.0 Reader](../../media/ui-audit/scale20-reader.png) ·
[2.0 Cast](../../media/ui-audit/scale20-cast.png) ·
[2.0 Cast scrolled](../../media/ui-audit/scale20-cast-scrolled.png).

**Secondary Settings pages (2×).** All seven pages were opened and captured at the top and bottom:
Reading aloud, Voices & previews, Playback & sleep, Reading & display, Privacy & data, Advanced,
and About NovelSpeak. The audit found two more real failures:

- **Export current chapter** clipped its second line because every Settings action used a fixed
  38 dp height. `pillButton` now uses a 44 dp minimum with wrap-content height and vertical
  padding; the export label shows “Export current chapter (WAV)” in full.
- **Privacy’s three-pill row** squeezed “Status” into a one-letter-wide vertical column at 2×.
  Action groups now stack vertically at ≥1.5×; Update lists, Self-test and Status are all
  full-width and readable. The same guard covers diagnostics, audio, pronunciation and model
  actions.

No control was unreachable: engine/model cards, voice preview buttons, sliders, reading-width
choices, permissions, advanced sliders, reset, and the full About page all scroll correctly. The
long unbroken `FOREGROUND_SERVICE_MEDIA_PLAYBACK` identifier wraps mid-token, which is expected for
an underscore identifier; it is fully visible.

Renders: [hub](../../media/ui-audit/settings2x-hub.png) ·
[engine top](../../media/ui-audit/settings2x-engine-top.png) ·
[engine bottom](../../media/ui-audit/settings2x-engine-bottom.png) ·
[voices](../../media/ui-audit/settings2x-voices-top.png) ·
[playback](../../media/ui-audit/settings2x-playback-bottom.png) ·
[reading](../../media/ui-audit/settings2x-reading-bottom.png) ·
[privacy top](../../media/ui-audit/settings2x-privacy-top.png) ·
[privacy bottom](../../media/ui-audit/settings2x-privacy-bottom.png) ·
[advanced top](../../media/ui-audit/settings2x-advanced-top.png) ·
[advanced bottom](../../media/ui-audit/settings2x-advanced-bottom.png) ·
[about top](../../media/ui-audit/settings2x-about-top.png) ·
[about bottom](../../media/ui-audit/settings2x-about-bottom.png).

**Performance trace.** On the 60 Hz SwiftShader emulator: cold start **803 ms median**
(795–812 ms), warm resume **0 ms total / 1–4 ms wait**. Library scroll is **16 ms p50, 0.56%
modern jank** (6 entries) and 21 ms p50 / 0.56% with 200 entries; Home is **25–26 ms p50,
0.8–1.0% modern jank**. A 12-second Perfetto trace shows **5.76 s RenderThread CPU vs 0.32 s
UI-thread CPU**, with 511 swaps averaging 14.1 ms — software-GPU swap/overdraw, not app layout.
The 200-entry Library was then converted from a ScrollView of 200 cards to a recycled ListView:
open p99 **250 → 48 ms**, slow UI-thread frames **3 → 1**, scroll unchanged. A phone trace is
still required before changing the hierarchy. Full report:
`docs/benchmarks/2026-11-06-performance.md`.

**Verification:** **531 host checks** (library grid 10, motion 5, player chrome 9, audio DSP 32,
parser 42, store 42, library 211, feature 130, design 15, catalog 18, selection 7, chunker 10) and
**Maestro 7 flows / 103 CLI steps** pass at 1.0× with animator scale 1.

**Compact reader chrome.** The title row is gone. The chapter title now sits at the top of the
player card (and in the mini bar when no character is speaking); Reader is a book icon with an
on/off accent and state description; the ad control is a shield whose description carries the
blocked/hidden counts. Both are 44 dp targets inside the address pill. On phones Forward moves to
the overflow menu when history exists (a visible arrow left almost no address width); on tablets
it stays inline. Measured at 1.0×/1.3×/2.0× and tablet width. Turning Reader off also exposed a
legibility bug: unstyled pages painted dark text on the app’s dark WebView backdrop. The WebView
now uses the web-default white when Reader is off and the app surface when it is on.

Renders: [phone](../../media/ui-audit/chrome-compact-phone.png) ·
[1.3×](../../media/ui-audit/chrome-compact-13.png) ·
[2.0×](../../media/ui-audit/chrome-compact-20.png) ·
[tablet](../../media/ui-audit/chrome-compact-tablet.png) ·
[mini bar title](../../media/ui-audit/chrome-compact-mini.png) ·
[reader off, readable](../../media/ui-audit/chrome-reader-off.png) ·
[phone with Forward history](../../media/ui-audit/chrome-forward-overflow.png) ·
[Forward in the overflow](../../media/ui-audit/chrome-forward-menu.png) ·
[tablet inline Forward](../../media/ui-audit/chrome-tablet-forward.png).

**Adaptive Library grid.** At ≥600 dp the Library pairs cards into two columns inside the same
recycled `ListView` adapter (`LibraryGrid.columns/pairs`). Headers span the full width, an odd
tail leaves a spacer, and `onConfigurationChanged` re-renders when the window crosses the
breakpoint. Verified on an 800 dp tablet at 1.0× and 2.0×: cards share equal heights with no
clipping; a phone still renders one full-width column. The grid rule has 10 host checks.

Renders: [tablet 1.0×](../../media/ui-audit/library-grid-tablet.png) ·
[tablet 2.0×](../../media/ui-audit/library-grid-tablet-2x.png) ·
[phone one column](../../media/ui-audit/library-grid-phone.png).

Remaining Wave 4: physical-phone performance trace and the remaining ASR/listening validation.
The large-library stress test and adaptive Library grid are complete.

## 11. Acceptance metrics

- No blocking finding remains on phone or tablet.
- Every interactive target ≥48 dp (dense ≥44 dp).
- Home/Settings/Library content is centered and capped at 720 dp on tablet.
- The reader chrome is ≤48 dp tall; the player has a mini and expanded state.
- Cast sheet is branded and exposes confidence + Fix line.
- All host checks (531) and Maestro reader/transport flows pass.
- Text at 1.3×/2× does not clip on Home, Library, Settings, reader, or the cast sheet.
- No color-only status.
