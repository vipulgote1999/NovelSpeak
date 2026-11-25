# Round 2 — final adversarial critique

Date: 2026-11-25. Scope: the built site, every image, the copy, the deploy wiring. Method: six
lenses again (art direction, copy, motion, accessibility/responsive, performance, adversarial
critic), executed as consecutive written passes by one agent — there is no subagent runtime in this
harness — plus direct inspection of every shipped PNG (the `read` tool renders images, so the alts
and the choice of captures could finally be checked against reality). No browser was available, so
nothing below claims a rendered pass.

Severity: **B** blocking, fixed before completion · **N** non-blocking, fixed cheaply · **R**
recorded limitation, not fixed.

## Art direction

- **AD2-1 (B)** §02 and §04 were two more text/phone splits, making four near-identical rows. §02 is
  now a full-width voice ledger (name / one clause / size) and §04 a full-width 16:9 film, so the
  page reads: split, ledger, paper, film, split, card, list.
- **AD2-2 (B)** The film's kicker line sat above the section head, before the chapter numeral. It
  now sits inside the film's column, directly above the frame.
- **AD2-3 (B)** Section rules were arbitrary (01 and 04 had them, 02 and 05 did not). Rules now
  separate same-ground neighbours only: 02, 05, 06, 07 carry a hairline; 01, 03 (paper) and 04
  (after the paper) do not.
- **AD2-4 (B)** The hero was vertically centred, pushing the h1 ~165 px down the first screen. It is
  top-aligned again, with the reader plate `fit-content` and right-aligned.
- **AD2-5 (R)** The whole page is still dark ink except the paper section. That is the concept, not
  an accident, but it does mean the site has one strong colour moment rather than several. Judged
  correct for a reading app; the user may disagree on sight.

## Copy

- **CO2-1 (B)** The hero lead began with "It", leaving the product unnamed above the fold. It now
  begins "NovelSpeak opens the chapter you are on…".
- **CO2-2 (N)** "4–5/5 voice distinction" is a number without a scale. On `/features/` it now reads
  "4–5/5 judged distinction between characters", still tied to the dated benchmark link.
- **CO2-3 (N)** Every "in review" claim stayed out of the build; the Play state is "coming", and the
  FAQ says the listing is not live rather than guessing at a review status. Verified by grep.
- **CO2-4 (R)** "Four neural voices, downloaded once" sits above five rows (four voices + Android's
  system voice) and ZipVoice's row shows two files (`104 MB + 52 MB`). The heading counts voices,
  the rows count downloads; the row is explicit, so the tension is visible rather than hidden.

## Motion

- **MO2-1 (B)** The read-along's stagger was 60 ms per word — about 3.6 s for the sentence, long
  enough to feel broken. Split into a 120 ms colour transition and a 28 ms step (≈1.7 s).
- **MO2-2 (B)** `.wave.is-paused` was toggled by script with no CSS rule behind it. Added.
- **MO2-3 (R)** The hover layer changes underline thickness instead of drawing a line from left to
  right, because a drawn underline needs a gradient and the contract allows exactly one gradient
  (the lamp). Recorded in `docs/website/reviews/motion.md`; the contract was amended to match.

## Accessibility and responsive

- **AR2-1 (B)** Alt text described things the captures do not contain. Every shipped PNG was opened:
  the "cast roster" is a dialog; the settings "voice list" is a hub of five rows; the library
  capture shows chapter rows, not search and filters. All alts rewritten.
- **AR2-2 (B)** The library capture showed QA fixture rows ("Adblock race regression", "Prefetch
  regression"). Replaced with `library-grid-phone.png`, which shows the search field and the four
  filters.
- **AR2-3 (B)** The 2× text capture shows the compact player ellipsising its own labels
  ("Ready · 18 senten…", "Voice · Supertonic · F1 (female, U…"). Publishing it as proof of the
  accessibility claim would undercut the claim. Removed; the section now carries the documented
  figures only.
- **AR2-4 (B)** `tablet-reader-600.png` is about 40 % empty black below the two panes; it would read
  as a broken frame. Removed.
- **AR2-5 (N)** `aria-current="page"` existed nowhere. Added on all four subpages, in both the
  desktop nav and the phone panel (not in the footer).
- **AR2-6 (N)** The brand link was 40 px, the skip link ~35 px, release links bare inline anchors.
  All now ≥ 44 px.
- **AR2-7 (R)** The five things that need eyes on a phone remain unverified: 320 px overflow, hero
  fold at 390 px, 200 % text reflow, screen-reader output, focus ring against the sticky header.
  They are listed in `docs/website/reviews/a11y.md` § 7 as a checklist, not as claims.

## Performance

- **PE2-1 (B)** The landing page shipped two tall screenshots side by side in §04, pushing the
  section past two screens for no extra information. One remains; the tablet library moved to
  `/features/`.
- **PE2-2 (N)** Total image weight dropped from 871 KB to 625 KB, and no image is upscaled past its
  source. The film poster (145 KB, native JPEG — no encoder on this device) stays lazy, behind
  `preload="none"`.
- **PE2-3 (R)** The poster is 145 KB because there is no JPEG encoder here; a 60 KB 800×450 version
  would need one. Recorded in `assets.md`.

## Adversarial critic

- **XA2-1 (B)** Two sections on `/features/` and one on `/voices/` had a second `h2` inside the same
  section (the CTA, and the voices block). CTA titles are now `h3` with a serif `.cta__title`; the
  voices ledger is its own section.
- **XA2-2 (B)** `.model` rows relied on DOM order for a three-column desktop grid; a row that put
  the description before the size would have mis-placed every cell on a phone. Grid areas now
  decide, so the markup can be read in any order.
- **XA2-3 (N)** The CSP had `style-src 'unsafe-inline'` with no inline style in any HTML file, then
  was tightened to `'self'` — and then set back with a comment: the read-along sets a per-word `--i`
  through CSSOM, which should not be covered by `style-src`, but an unverifiable browser
  difference is not worth a broken signature moment. No HTML file contains a `style` attribute.
- **XA2-4 (N)** No `robots.txt`. Added an allow-all file with a comment explaining the deliberate
  absence of `Sitemap` (the two origins would need different absolute URLs).
- **XA2-5 (R)** The 404 page duplicates ~40 lines of tokens and layout because it is served at
  unknown depths. A shared stylesheet would be a root-absolute path, which is exactly what must not
  happen on the GitHub Pages subpath. Duplication is the correct trade here.

## Re-check after fixes

| Check | Result |
|---|---|
| `node tools/site-check.mjs` | `SITE: PASS` — 6 pages, 11 images, 625 KB of images |
| All links and assets resolve relative to their page | pass (checker resolves every `href`/`src`/`poster`) |
| Heading order, one `h1` per page, no duplicate ids | pass |
| Every image alt checked against the opened PNG | pass (12 alts rewritten or verified) |
| Every motion gated behind `prefers-reduced-motion: no-preference` | pass (checker) |
| Hex colours only in `:root`; radius only from the scale | pass (checker) |
| 13 contrast pairs ≥ 4.5:1 text / 3:1 UI | pass (checker recomputes from tokens) |
| Workflow YAML parses; deploy job no-ops without secrets | pass (`yaml` parse + explicit `ready` guard) |
| Privacy policy substance preserved | 0 words lost against the previous file |
| App test suite | **28/28 host harnesses pass** (`tools/test-all.sh --quick`) when run under `proot -b <writable>:/tmp`: `epub-test` and `pdf-test` hard-code `/tmp`, and this device's `/tmp` is not writable. Browser regressions skip (no Chromium); device tests are skipped by `--quick` |

## Residual limitations (not fixed, stated)

1. No browser, no screenshots: the site has never been rendered. Visual claims are source-level.
2. System serif stacks mean Android shows Noto Serif where iOS shows Iowan/Palatino.
3. The Play listing is not live; the only working install is the 1.3.0 APK.
4. Some captures still show fixture data (a `10.0.2.2` source line, a sample story). They are real
   app screens, not mockups; the cleanest available captures were chosen.
5. The film poster is native-size because this machine cannot encode JPEG.

Round 2 status: **closed** — every blocking finding is fixed and re-checked. The remaining items are
recorded limitations, not open defects.
