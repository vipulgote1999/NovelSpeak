# Round 1 — specialist panel review of the plan

Date: 2026-11-25 · Scope: `brief.md`, `assets.md`, `design-contract.md`, `copy.md`, before any page
code. Method note: this harness has no subagent runtime, so the six lenses below are separate,
sequentially written passes with the same evidence, not independent agents. Findings are written
before the fixes; each blocking finding is resolved in the revision table at the end.

Severity: **B** = blocking (must change before code) · **N** = non-blocking (fix if cheap).

## 1. Art direction

- **AD-1 (B) — The hero is a pile, not a composition.** Eyebrow + h1 + lead + two buttons + fact
  line + Play chip + plate is seven elements. The reference bar allows one notice line, the h1, a
  lead, at most two actions and the picture. Drop the Play chip from the hero; it belongs to the
  install section where it has a job.
- **AD-2 (B) — The film is planned but never placed.** The strongest evidence that this is real
  software (23 s of the actual app, with audio) appears in no section. Put it in "Away from the
  screen" as a poster-first plate with a play control; that section is where watching the screen go
  dark makes the argument.
- **AD-3 (B) — Numbered chapters can read as template decoration.** They earn their place only if
  they are a running head that names the section (`01 / READ`) and matches the nav. Keep them on the
  landing page only, set them small and mono; no numerals on subpages.
- **AD-4 (N) — "Literary magazine" risk.** Serif + cream page + numerals is a mood. The product must
  lead on screen one: the reader plate needs ≥ 40 % of the hero width at ≥ 900 px and must be the
  first thing the eye lands on after the h1, not a decorative margin note.
- **AD-5 (N) — Model cards on the landing page duplicate the voices page.** Five cards is a table in
  disguise. Keep five compact rows on the landing page (name, size, one clause); the full table and
  the install steps live on `/voices/`.

## 2. Copy

- **CO-1 (B) — "Read any web novel out loud" overclaims.** The app cannot read every site (Cloudflare,
  logins, non-text pages). The h1 is where trust is won or lost. Change to "Read web novels out
  loud."
- **CO-2 (B) — "listing in review" is not evidenced.** The repo has a store packet and a 404 listing;
  nothing in it proves submission. Replace every "in review" with "coming to Google Play" and have
  the FAQ say the listing is not live yet, with the 1.4.0 build named as the store-ready release.
- **CO-3 (B) — Kokoro's "quality option" needs its qualifier.** The project's own note says it remains
  the quality option "pending a listening test". Say "slower, audiobook pacing" and link the voices
  page where the caveat is written.
- **CO-4 (B) — Cast benchmark numbers on the landing page are unearned.** They are real but belong
  with their benchmark name and date, on `/features/` and `/voices/`. Remove from the landing page.
- **CO-5 (N) — "Four neural voices, one download each" is false for ZipVoice** (model + vocoder =
  two files). Change to "downloaded once".
- **CO-6 (N) — "No telemetry" is repeated four times.** Keep it in the hero lead, §05, and the legal
  line; cut it from the dock.

## 3. Motion

- **MO-1 (B) — The word-mask hero entrance cannot be built safely without a renderer.** Line
  measurement at load, font fallback differences and a 700 ms invisible window are all failure modes,
  and reduced motion must never leave the h1 hidden. Replace with a CSS-only entrance: h1 as one
  block (opacity + 16 px rise, 700 ms), lead and buttons staggered after it, plate settles inside
  `overflow: hidden`.
- **MO-2 (B) — The entrance must be opt-in, not opt-out.** Wrap every entrance keyframe and delay in
  `@media (prefers-reduced-motion: no-preference)` so the default state is fully visible. A
  `reduce` query that sets `animation: none` after the fact still leaves pre-animation styles
  applied if the CSS is reordered.
- **MO-3 (B) — The read-along highlight colour fails on paper.** `--accent` (mint) on `--paper`
  is ~1.5:1. Define `--paper-accent: #0f5138` (7.9:1) and use it for the lit word, its underline and
  the focus ring inside the paper section.
- **MO-4 (N) — The waveform must not run forever.** Three cycles then rest is right; also pause when
  off-screen with `animation-play-state`, so a phone in a pocket is not animating.
- **MO-5 (N) — Read-along needs a screen-reader guard.** Word spans read letter by letter in some
  screen readers; give the paragraph an `aria-label` with the sentence and `aria-hidden` the spans.

## 4. Accessibility and responsive

- **AR-1 (B) — A custom full-screen nav sheet is the riskiest component on the site.** It needs focus
  trapping, inert content, Escape, focus return and a no-JS fallback, and none of it can be verified
  here. Replace it with a native `<details>` disclosure in the header (keyboard and Escape behaviour
  free), and keep every page link in the footer regardless.
- **AR-2 (B) — A five-column comparison table will overflow at 320 px.** Replace the `/voices/`
  table with stacked spec rows (`<dl>`), no horizontal scrolling.
- **AR-3 (B) — Sticky header overlap.** At ≥ 900 px the header is sticky; every section needs
  `scroll-margin-top` equal to the header height, and the skip link target must account for it.
- **AR-4 (B) — Focus visibility on paper and on accent fills.** Two rings are needed: `--accent` on
  ink, `--paper-accent` on paper, and `--accent-ink` ring on an accent-filled button. A single mint
  ring disappears on the primary button.
- **AR-5 (N) — 200 % text at 320 px.** The h1 clamp has a 2.5 rem floor; add `overflow-wrap:
  anywhere` to headings and ensure plates use `width:100%;max-width:...`, never fixed widths.
- **AR-6 (N) — Footer links.** Inline links need 44 px targets (padding, not line-height), and 8 px
  minimum spacing so neighbouring targets are not ambiguous.

## 5. Performance

- **PE-1 (B) — 900 KB of images is a ceiling, not a target.** Mark only the hero plate
  `fetchpriority="high"`; everything else lazy. Keep the landing page's above-the-fold weight under
  250 KB including CSS.
- **PE-2 (N) — The film must not fetch anything before a press:** `preload="none"`, `poster`, no
  `<source>` with `autoplay`. One MP4, no GIFs.
- **PE-3 (N) — One CSS file, one JS file, no `@import`, no preconnect to any origin** (there are no
  external origins; adding one would break the privacy claim).

## 6. Adversarial critic

- **XA-1 (B) — The page currently proves nothing.** Prose + numerals + a screenshot can still read
  as a template. Force proof into every section: a real screenshot, a real spec with numbers, or a
  real benchmark. Planned sections 02, 04 and 05 have no picture; 02 gets the model rows with sizes,
  04 gets the film and the library plate, 05 gets the privacy screen plate.
- **XA-2 (B) — The Play chip is an inert control in disguise.** A bordered chip that looks like a
  button but does nothing is worse than a status line. Render it as plain text with a mono label
  ("Google Play — coming"), no border, no hover; JS upgrades it to a link only when configured.
- **XA-3 (N) — Honesty is the differentiator here.** The "What it does not do" block must be visible
  on the landing page (it is), not buried on `/features/`. Keep it at section 05 and repeat the
  paywall/Cloudflare limit in the FAQ.
- **XA-4 (N) — Two moments, one page.** Read-along and the film both claim attention; keep read-along
  as the dealt type moment and treat the film as a product slot, not a third moment.

## Resolutions (applied to the plan documents before code)

| Finding | Resolution |
|---|---|
| AD-1, XA-2 | Hero keeps eyebrow, h1, lead, two actions, one mono fact line, plate. Play status moves to §06 as plain mono text with no border; JS upgrades it to a link when `SITE.playUrl` is set. |
| AD-2, XA-4 | §04 gains the film: poster 800×450, `preload="none"`, native controls, an overlay play button, one line "Real recording, 23 s, audio on." |
| AD-3 | Chapter running heads `01 / READ` … `06 / INSTALL`, landing only, `--t-label` mono. |
| AD-4 | Hero split 5/7 at ≥ 900 px with the plate in the 7; minimum plate width 380 px. |
| AD-5 | Landing model list becomes five rows: `Supertonic 3 · 123 MB · fast default`; the comparison lives on `/voices/`. |
| CO-1 | h1 = "Read web novels out loud." |
| CO-2 | All "in review" copy replaced with "coming to Google Play"; FAQ states the listing is not live and names 1.4.0 as the store-ready release. Brief's claim ledger row 28 adjusted. |
| CO-3 | Kokoro row: "Audiobook pacing. About 3× slower to synthesise." `/voices/` carries the "pending a listening test" caveat. |
| CO-4 | Cast numbers removed from landing; kept on `/features/` and `/voices/` with the benchmark name and date. |
| CO-5, CO-6 | Model heading "Four neural voices, downloaded once." Dock: "No account", "No ads or analytics", "Voices on the device" with telemetry kept out of the dock. |
| MO-1, MO-2 | Entrance is CSS-only and inside `prefers-reduced-motion: no-preference`; no word masks, no JS dependency for the hero. |
| MO-3, AR-4 | Add `--paper-accent: #0f5138` and `--ring-on-accent: #06231a`; document three ring colours. |
| MO-4, MO-5 | Waveform pauses off-screen; read-along paragraph gets `aria-label` + `aria-hidden` spans. |
| AR-1 | Native `<details>` nav disclosure; no JS sheet; footer carries the full link set on every page. |
| AR-2 | `/voices/` uses stacked `<dl>` spec rows. |
| AR-3 | `scroll-margin-top: calc(var(--nav-h) + 16px)` on all sections; skip link targets `#main` with the same margin. |
| AR-5, AR-6 | `overflow-wrap: anywhere` on headings; footer link padding 12px vertical, 8px gap. |
| PE-1, PE-2, PE-3 | Only the hero plate is eager (`fetchpriority="high"`); film `preload="none"`; one CSS, one JS, zero external origins. |
| XA-1 | §02 model rows, §04 film + library plate, §05 privacy plate: every landing section now has a picture, a spec with numbers, or both. |
| XA-3 | "What it does not do" stays on the landing page and is echoed in the FAQ. |

Round 1 status: **closed**. No blocking finding remains in the plan. Code starts from the revised
documents; any later change to this table is recorded in round 2.
