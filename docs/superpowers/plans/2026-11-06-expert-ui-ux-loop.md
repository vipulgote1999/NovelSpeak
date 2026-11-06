# Expert UI/UX Continuous Improvement Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn NovelSpeak’s functional programmatic UI into a coherent, adaptive, expert-grade reading product without breaking the reader, playback, library, or Story Cast.

**Architecture:** A shared `Design` token/frame layer feeds every touched screen. Home, the player, Library and Settings are refactored in small waves; each wave is gated by host tests, Maestro regression flows, and phone/tablet screenshots.

**Tech Stack:** Kotlin, Android `Activity`/`View` UI (no Gradle/AndroidX), existing `Palette`, `Reader`, `TtsEngine`, `LibraryStore`, `CastStore`, Maestro.

**Spec:** `docs/superpowers/specs/2026-11-06-expert-ui-ux-audit.md`

## Global Constraints

- No regression in the existing 427 host checks.
- No regression in Maestro reader/transport flows (`03`, `16`) and launch/home flows (`01`, `13`).
- Preserve Story Cast behavior (3 characters, recast, speaker label, persisted voices).
- Keep the no-AndroidX/no-Gradle build working; use platform widgets only.
- Touch targets: 48 dp standard, 44 dp dense, 56 dp primary play.
- Content frame: max 720 dp on Home/Settings/Library; reader keeps its two-pane layout.
- Respect text scaling, TalkBack labels, and reduced motion.
- No hardcoded new hex colors; add semantic tokens to `Design`/`Palette`.

## Review Focus

- A tablet (800 dp) Home must center its content and not stretch the shortcut grid or cards.
- A phone (411 dp) Home must not clip labels at 1.3× font scale.
- The player must keep Play/Pause reachable and all transport targets ≥44 dp.
- The Cast sheet must remain usable after the sheet redesign; recast and narrator-only must still work.
- Library delete must never remove an entry without confirmation.

---

## Wave 1 — Foundation, Home, player ergonomics

### Task 1: Design tokens and adaptive frame

**Files:**
- Create: `app/src/app/novelspeak/Design.kt`
- Create: `tools/design-test/TestMain.kt`

**Interfaces:**
- Produces:
```kotlin
object Design {
    const val SPACE_XS = 4; const val SPACE_SM = 8; const val SPACE_MD = 12
    const val SPACE_LG = 16; const val SPACE_XL = 24; const val SPACE_XXL = 32
    const val RADIUS_SM = 8; const val RADIUS_MD = 14; const val RADIUS_LG = 20
    const val MAX_CONTENT_DP = 720
    const val TOUCH_DP = 48; const val DENSE_TOUCH_DP = 44
    fun gutter(widthDp: Int): Int
    fun contentWidth(widthDp: Int, max: Int = MAX_CONTENT_DP): Int
    fun contentPadding(widthDp: Int, max: Int = MAX_CONTENT_DP): Int
    fun isMedium(widthDp: Int): Boolean
    fun isExpanded(widthDp: Int): Boolean
}
```

- [ ] **Step 1:** Write failing host checks: compact 411 dp → gutter 20, content 371, padding 20; medium 700 dp → gutter 24, content 652; expanded 800 dp → gutter 28, content 720, padding 40; 1200 dp → content 720; `isMedium(700)=true`, `isExpanded(840)=true`.
- [ ] **Step 2:** Run `kotlinc app/src/app/novelspeak/Design.kt tools/design-test/TestMain.kt -include-runtime -d /tmp/design-test.jar && java -jar /tmp/design-test.jar`; expect failure (missing `Design`).
- [ ] **Step 3:** Implement `Design` exactly as above. `contentWidth` clamps to `max` and at least 280; `contentPadding` is `((widthDp - contentWidth).coerceAtLeast(0) / 2)`.
- [ ] **Step 4:** Re-run; expect all checks pass.
- [ ] **Step 5:** Commit `feat(ui): add design tokens and adaptive content frame`.

### Task 2: Home adaptive frame and visual polish

**Files:**
- Modify: `app/src/app/novelspeak/MainActivity.kt` (`buildHomeView`, `homeShortcutCell`, `refreshHome`, `showHome`, `hideHome`, `onConfigurationChanged`, fields).
- Test: Maestro `tools/maestro/flows/01_launch.yaml`, `13_home.yaml`.

- [ ] **Step 1:** Add `homeColumn: LinearLayout`, `homeTopSpacer: Space`.
- [ ] **Step 2:** Add `applyHomeWidth()`: use `Design.contentPadding(resources.configuration.screenWidthDp)` for horizontal padding, keep 24 dp vertical; call from `buildHomeView`, `showHome`, `onConfigurationChanged`.
- [ ] **Step 3:** Right-align the Home overflow: add `homeTopSpacer` (weight 1) before `overflowButton`; `VISIBLE` on Home, `GONE` otherwise.
- [ ] **Step 4:** Shortcut tile: plate 50 dp, icon 28 dp, label 12 sp, vertical padding 6 dp; use `Design` spacing; keep light plate but soften to a new `Palette.PLATE` (`0xFFE9EEF2`) rather than pure `ICON_PLATE`.
- [ ] **Step 5:** Continue cards: add `percent: Int` to `HomeResume`, set it from `LibraryEntry.percent`/`ImportedBook.percent`, add a 3 dp progress bar and a 16 dp chevron; title 15 sp, meta 12 sp, card padding 16/14.
- [ ] **Step 6:** Wordmark 32 sp; search 52 dp; section eyebrow 11 sp with `letterSpacing = 0.08f`.
- [ ] **Step 7:** Build (`./build.sh`, x86_64 debug for emulator), run Maestro `01` and `13`, capture phone + tablet Home screenshots.
- [ ] **Step 8:** Commit `feat(ui): compose home on an adaptive frame`.

### Task 3: Player ergonomics and touch targets

**Files:**
- Modify: `app/src/app/novelspeak/MainActivity.kt` (player card build `:522-717`, top bar `:269-430`, `updateReaderButton`, `onReaderState`).
- Test: Maestro `16_transport.yaml`.

- [ ] **Step 1:** Top bar icon buttons 48 dp; address pill 46 dp; overflow 48 dp.
- [ ] **Step 2:** Read/Next/Cast 44 dp; speed −/+ 40 dp; stop 44 dp; prev/next 48 dp.
- [ ] **Step 3:** Text: status 14 sp, voice 12 sp, speaker 12 sp, speed 14 sp; keep engine chip 10.5 sp.
- [ ] **Step 4:** Group actions into a single 8 dp-gap strip; remove redundant padding; ensure the card does not exceed the visible frame on 411 dp.
- [ ] **Step 5:** Run `16_transport.yaml`; confirm sentence navigation, speed label, stop/restart, playing state.
- [ ] **Step 6:** Capture phone/tablet reader screenshots; compare with the audit.
- [ ] **Step 7:** Commit `feat(ui): tighten reader chrome and player targets`.

### Task 4: Library safety and touch target

**Files:**
- Modify: `app/src/app/novelspeak/LibraryActivity.kt` (`deleteButton`, add `confirmDelete`).
- Test: Maestro Library flow (existing if present; otherwise a small flow).

- [ ] **Step 1:** Add `confirmDelete(entry)` with title and message naming the entry; `Cancel` / `Delete`.
- [ ] **Step 2:** Delete target 48 dp; keep the `✕` glyph but expand the hit area.
- [ ] **Step 3:** Run library host test; Maestro library flow; verify cancel leaves the entry, delete removes it.
- [ ] **Step 4:** Commit `fix(library): confirm single-entry delete`.

### Task 5: Regression gate and evidence

- [ ] **Step 1:** Run all host checks: library 211, feature 130, parser 34, store 27, catalog 18, voice 7, design (new). Total ≥434.
- [ ] **Step 2:** Run Maestro `01`, `03`, `13`, `16` with Story Cast enabled and disabled.
- [ ] **Step 3:** Capture phone and tablet screenshots into `docs/media/ui-audit/wave1-*`.
- [ ] **Step 4:** Update the audit’s “Current state” with before/after notes.
- [ ] **Step 5:** Commit `docs(ui): record wave 1 evidence`.

---

## Wave 2 — Library and Settings

- Library 2.0: search field, segmented filter (All/Chapters/Imported/Finished), 2-column grid at
  ≥600 dp, covers/initials, 48 dp overflow with confirm, designed empty state, max-width frame.
- Settings: max 720 dp frame, section labels, 24 dp leading icons, two-column hub at ≥840 dp,
  Story Cast row (on/off + “Manage from reader”), text-scale-safe rows.
- Gate: library + settings Maestro flows, 1.3×/2× font-scale screenshots.

## Wave 3 — Player sheet and cast sheet

- Mini player (56 dp: status, play/pause, progress) + expanded bottom sheet.
- Branded cast bottom sheet: cast rows, voice chip, confidence dot+label, Preview, Fix this line.
- Per-line speaker override for `UNKNOWN` lines; persisted.
- Gate: Maestro cast flow, recast, narrator-only, on-device sids 5/1/6, unresolved → fixed.

## Wave 4 — Motion, accessibility, performance

- `Design.MOTION_*`; fade/slide sheet and card; reduced-motion check.
- 1.3×/2× text-scale layout audit; 48 dp everywhere; TalkBack selected/state labels.
- Library/Home lazy list/adapter or cached diff; image decode sizing.
- Gate: contrast script, text-scale screenshots, perf trace, full Maestro suite.

## Wave 5 — Parity features

- Reading stats card, themes expansion, import flow redesign, chapter sheet, translation
  hand-off, per-character voice preview, optional cloud LLM for low-confidence lines.
- Gate: full feature tests + store/release docs.

---

## Progress

- **Wave 1 — done.** `Design.kt`, adaptive Home frame, player targets, Library delete confirm;
  442 host checks and Maestro 01/13/03/16/20 green.
- **Wave 2 — done.** Settings frame + sections + Story Cast toggle; Library search/filter/empty
  state; Maestro 18/20/21 updated and the 7-flow suite green (106 commands).
- **Parallel — done.** Story Cast quality/latency benchmark with Supertonic 3, ASR and an
  audio-capable judge; `CastChunker` fix for short same-speaker fragments. See
  `docs/benchmarks/2026-11-06-story-cast-quality.md`.
- **Wave 3 — done.** Branded cast sheet, confidence, per-line **Fix speaker**, per-dialogue silence
  trim, the 56 dp mini player, character preview from a real line, and the Kokoro comparison are
  complete. Kokoro is ~3× slower in absolute synthesis but closer to normal audiobook pacing;
  Supertonic remains the latency default.
- **Wave 4 — in progress.** Reduced-motion support, the 1.3×/2× text-scale audit, all seven
  secondary Settings pages at 2×, and an emulator performance trace are done. Cold start is
  **803 ms median**, warm resume is instant, and scrolling is GPU/swap-bound on SwiftShader rather
  than UI-thread-bound. 521 host checks and Maestro 7/106 pass. Next: a physical-phone trace,
  large-library stress test, and the remaining ASR/listening validation.

## Execution notes

- Never refactor a screen and change its behavior in the same commit; keep UI-only changes separate
  from data/engine changes.
- Every UI change must be exercised on phone (1080×1920 @420) and tablet (1200×1920 @240) before
  it is called done.
- If a Maestro flow fails because a label changed, update the flow intentionally and note it.
- Keep the existing `Palette` values unless a contrast failure is measured; add new semantic
  colors instead of repainting the brand.
