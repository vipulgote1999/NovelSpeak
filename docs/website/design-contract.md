# NovelSpeak website — design contract

Status: round 1, binding for every page until a later round amends it in writing.
Kind: `software` (see `brief.md`). Primary action: **Download the APK**.

## 0. The idea in one paragraph

The page is the front matter of a book, set in the app's own ink. One serif voice for the title
pages, one sans voice for instructions, one mono voice for numbers. Chapter numerals count the
sections; a running rule closes each one. The product is shown at true size, never tilted, never in
a generic dashboard mockup: the reader page with its lit sentence, the library, the cast roster,
the settings hub. Half way down, the ink room opens onto one actual cream page — the only light
surface on the site — and the sentence on it lights up word by word, because that is the feature.
No gradients except one lamp glow, no second accent, no testimonial, no counter, no cookie banner.

## 1. Tokens

One token file: the `:root` block at the top of `site/assets/site.css`. No component CSS may
introduce a raw colour, radius, duration or easing. `tools/site-check.mjs` fails the build if a hex
value appears outside `:root`.

### Colour

| Token | Value | Role | Contrast |
|---|---|---|---|
| `--ink` | `#0e1318` | page ground (app's own background) | — |
| `--ink-2` | `#161d24` | surface: plates, cards, header | — |
| `--ink-3` | `#1d262e` | raised surface inside a card | text 14.1:1 |
| `--rule` | `#2c3944` | decorative hairline only | 1.6:1, decoration |
| `--rule-ui` | `#5b6f80` | boundaries of controls and inputs | 3.6:1 on ink, 3.3:1 on surface |
| `--text` | `#f1f6fa` | body and headings on ink | 17.2:1 |
| `--text-2` | `#b9c6d2` | secondary prose on ink | 10.7:1 |
| `--text-3` | `#9aa9b8` | labels, captions, meta on ink | 7.8:1 |
| `--accent` | `#6fe7be` | the one accent: links, marks, the lit word | 12.3:1 on ink |
| `--accent-ink` | `#06231a` | text on an accent fill | 11.0:1 |
| `--paper` | `#f2ece2` | the one light section | — |
| `--paper-2` | `#e8dfd0` | paper surface inset | — |
| `--paper-accent` | `#0f5138` | the accent on paper (lit word, underline, focus) | 7.9:1 |
| `--ring-on-accent` | `#06231a` | focus ring on an accent-filled button | 11.0:1 |
| `--paper-rule` | `#cfc3ae` | hairline on paper | 1.4:1, decoration |
| `--paper-text` | `#191c1f` | body on paper | 14.6:1 |
| `--paper-text-2` | `#4b5157` | secondary on paper | 6.8:1 |
| `--lamp` | `radial-gradient(60% 50% at 70% 30%, rgba(111,231,190,.10), transparent 70%)` | the single glow, hero only | non-text |

No amber, no violet, no second accent. The app's own amber warning colour is deliberately not
carried over: caution is expressed with a label and a rule, not a hue. `--paper-accent` is the same
hue family rendered for the one light surface, not a second brand colour; it exists because mint on
cream is 1.5:1 and would be unreadable (round 1: MO-3).

### Type

Faces: no download, no third-party origin.

```
--serif: ui-serif, "Iowan Old Style", "Palatino Linotype", Palatino, "Book Antiqua", Georgia, "Times New Roman", serif;
--sans: ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
--mono: ui-monospace, SFMono-Regular, Menlo, Consolas, "Liberation Mono", monospace;
```

Trade-off, accepted: Android has no Georgia/Palatino and falls back to Noto Serif, so the display
face varies by platform. That is the price of zero webfont weight and zero third-party requests;
it is recorded rather than hidden.

| Token | Value | Use |
|---|---|---|
| `--t-display` | `clamp(2.5rem, 1.35rem + 5.4vw, 5.25rem)` | h1, serif, `line-height:1.02`, `letter-spacing:-.02em` |
| `--t-h2` | `clamp(1.85rem, 1.15rem + 2.5vw, 3rem)` | section heads, serif, `line-height:1.08` |
| `--t-h3` | `clamp(1.1rem, 1rem + .45vw, 1.3rem)` | card and item titles, sans 600 |
| `--t-lede` | `clamp(1.05rem, .98rem + .5vw, 1.25rem)` | hero lead and section intros, sans, `line-height:1.55`, max 46ch |
| `--t-body` | `1rem` / `1.65` | prose, max 62ch |
| `--t-small` | `.875rem` / `1.5` | notes, limits |
| `--t-label` | `.72rem` / `1`, `letter-spacing:.14em`, uppercase, mono | chapter numerals, specs, kickers |

Weights used: 400, 500, 600. No 700+ (ink on dark gains nothing). `text-wrap: balance` on h1–h2,
`pretty` on prose.

### Space, radius, grid

```
--s1 4  --s2 8  --s3 12  --s4 16  --s5 24  --s6 32
--s7 48 --s8 64 --s9 96 --s10 128
--r1 4px (chips, marks) --r2 10px (buttons, inputs) --r3 18px (plates, cards) --r4 999px (status pill)
```
Grid: `.wrap` max **1200px**, gutter `clamp(20px, 5vw, 48px)`. 12 columns; prose 7, split 6/6 or
5/7; nothing sits outside `.wrap` except a full-bleed ground.

Section box: `padding-block: clamp(72px, 11vh, 132px)`, `min-height: min(88svh, 980px)`,
`scroll-margin-top: calc(var(--nav-h) + 16px)` so an anchor never lands under the sticky header.
Hero: `min-height: 100svh`, content top-aligned at `--nav-h + clamp(32px, 7vh, 72px)`, split 5/7 at
≥ 900 px with the reader plate in the 7 column and never narrower than 380 px (round 1: AD-4, AR-3).
Every section opens with its chapter rule and h2; no section is vertically centred; no section
carries a second h2.

## 2. Components (one of each, reused)

| Component | Classes | Spec |
|---|---|---|
| Skip link | `.skip` | first focusable, visible on focus, jumps to `#main` |
| Header | `.site-header` | 64 px, ink with a 1 px `--rule` bottom, real app icon 24 px + wordmark, links, one primary button; sticky only at ≥ 900 px |
| Mobile nav | `<details class="more">` + `.more__panel` | native disclosure, no script, no focus trap: `summary` is the button and says its state; Escape closes (native); panel drops 6 px and fades in 200 ms, out 120 ms (round 1: AR-1) |
| Buttons | `.btn`, `.btn--primary`, `.btn--ghost`, `.btn--sm` | primary = accent fill/ink text; ghost = `--rule-ui` border, text; one pair only; 44 px min height; 1 px step on hover, 1 px sink + `.985` on press |
| Status text | `.status` | plain mono label `GOOGLE PLAY — COMING`, no border, no hover, not a control; becomes `<a>` only when `SITE.playUrl` is set (round 1: XA-2) |
| Chapter head | `.chapter`, `.chapter__num`, `.chapter__title` | mono numeral `01`, 48 px rule, serif h2, optional kicker |
| Plate | `.plate`, `.plate--phone`, `.plate--tablet` | `--ink-2` frame, 1 px `--rule`, `--r3`, image with explicit dimensions; no caption; zooms 1.02 inside on hover when linked |
| Film | `.film`, `.film__play` | `<video preload="none" controls poster>` plus a visible overlay button that calls `play()`; poster 800×450; no autoplay; placed in §04 where screen-off playback is the argument (round 1: AD-2) |
| Spec list | `.specs`, `.spec` | `<dl>`: mono label, value; one line each; tabular numerals |
| Model row | `.model`, `.model__size`, `.model__meta` | five compact rows on the landing page (name, size, one clause); the full comparison is stacked `<dl>` rows on `/voices/`, never a wide table (round 1: AD-5, AR-2) |
| Waveform | `.wave` | 5 bars, 3.2 s cycle, three cycles then rest, one instance on the page, `aria-hidden` |
| Paper page | `.paper`, `.page`, `.readalong` | cream ground, one page sheet, serif 20–24 px, the read-along sentence inside |
| Limits block | `.limits`, `.limit` | honest "what it does not do"; 1 px left rule, no colour |
| FAQ | `.faq`, `.faq__item` | every answer open; `<details open>` only if JS is absent? No: plain `<h3>` + `<p>` |
| CTA block | `.cta` | one primary + one ghost, the SHA-256 line in mono, Play status chip |
| Footer | `.closing` | close statement + link columns + legal line; one screen |
| Prose | `.prose` | privacy and changelog body; 62ch measure; h2/h3 only |

## 3. Motion

Three layers, nothing else. All of it transform/opacity/colour; no width, height, top, left,
margin, padding; no bounce; reduced motion rests everything.

| # | Motion | Tokens | Purpose |
|---|---|---|---|
| 1 | Hero entrance, once on load: h1 rises as one block (opacity + 16 px, 700 ms), then lead and buttons fade up 12 px (520 ms, 60 ms apart); hero plate settles 1.05 → 1 over 1.1 s inside `overflow:hidden`. CSS only, no word masks, wrapped in `prefers-reduced-motion: no-preference` (round 1: MO-1, MO-2) | `--m-enter`, `--m-ease-enter` | the title arrives first, then what to do |
| 2 | Read-along: the sentence's words take `--paper-accent` in 60 ms steps as the section is 35 % visible; underline sweep follows; once. The paragraph carries `aria-label` with the full sentence and `aria-hidden` word spans (round 1: MO-5) | `--m-read` 60 ms, `--m-ease` | shows the feature using the page |
| 3 | Waveform: five bars, 3.2 s cycle, three cycles then stop; `animation-play-state: paused` while off-screen | `--m-cycle` 3.2 s, linear | a voice is a physical thing; no perpetual motion |
| 4 | Hover: buttons step 1 px and their fill steps 14 % toward ink; links draw a 1 px underline left→right; linked plates lift 2 px and darken their border, the image zooms 1.02; arrow nudges 3 px | `--m-in` 150, `--m-out` 120 | every control answers the pointer |
| 5 | Press: 1 px down + `scale(.985)` | `--m-press` 90 | the press is felt |
| 6 | Nav sheet: fade + 6 px drop in, 120 ms out | `--m-panel` 200, `--m-close` 120 | the menu is a place, it arrives |
| 7 | Focus: 2 px ring — `--accent` on ink, `--paper-accent` on paper, `--ring-on-accent` on an accent fill — 2 px offset, no transition (round 1: AR-4) | — | keyboard is never lost |

Curves: `--ease: cubic-bezier(.2,.8,.2,1)`, `--ease-enter: cubic-bezier(.22,1,.36,1)`,
`--ease-io: cubic-bezier(.65,0,.35,1)` (travel only). No curve overshoots.

Banned: scroll-triggered fades on sections or cards, parallax, scroll-jacking, smooth-scroll
libraries, tickers, carousels, timed tabs, tilts, glow sweeps, blinking, loaders, entry gates.

`prefers-reduced-motion: reduce` → every animation and transition `none`, entrance content
visible, read-along words rest in normal ink, waveform a still frame, sheet swaps instantly.

## 4. Content rules

- h1 names the category and the buyer's action; one hard fact on the first screen; ≤ 3 lines at
  every width.
- Every heading contains a noun. Buttons are verb + object ("Download the APK", "Read the source").
  At most two hero actions, one action per screen repeated at most twice.
- Say the size, the version, the date of the download. Numbers in mono, tabular.
- No "powerful", "seamless", "revolutionary", "AI-powered", "effortless", "unlock", "elevate",
  "supercharge", "game-changing", "your new reading companion". No exclamation marks.
- Limits get their own section, written in the same voice as the benefits.
- Every claim traces to `brief.md` § Claims ledger.

## 5. What this site refuses to contain

Testimonials, ratings, download counts, "trusted by", award ribbons, logo walls, pricing tables,
newsletter capture, cookie banner, chat bubble, back-to-top button, countdown, "coming soon" email
form, drawn phone mockups, tilted screenshots, stock photography, webfonts, trackers, external
scripts, autoplaying video with sound, fake Play badge, disabled buttons that look active.

## 6. States

| Surface | States covered |
|---|---|
| Nav | rest, hover, focus, current (`aria-current="page"`), sheet open/closed, reduced motion |
| Install CTA | Play "coming" status text, Play live (link, only when configured), APK path always working, SHA line, no-JS identical |
| Film | poster, playing (native controls + pause), reduced motion (no autoplay) |
| Links | rest, hover, focus-visible, visited (accent → accent, no colour shift), external (arrow + `rel="noreferrer"`) |
| Images | alt text on all; decorative wave marked `aria-hidden`; explicit width/height so nothing shifts |
| 404 | own title, home link, source link, no fake search |
| No-JS | every page reads and every link works; the read-along rests lit-in-normal-ink; the Play chip stays a status |

No forms exist on this site: contact is the GitHub issue tracker, which is stated once in the
footer. Nothing on the site mutates data, so there is no loading or error state to invent.

## 7. Anti-slop checklist (counted, not judged)

1 accent hue · 1 radius scale (4/10/18/999) · 0 gradients except `--lamp` · 0 shadows except the
paper sheet · 0 emoji · 0 stock photos · 0 invented figures · 0 testimonials · 0 inert controls ·
0 cookie/consent UI · 0 newsletter · 0 carousel · 0 hover tilt · 1 button pair · 1 type scale ·
1 icon source (the real app icon) · 1 motion moment per page (landing: two — read-along and the
waveform).

## 8. Deviations from the reference skill, stated openly

The `design-taste-frontend` (tasteskill 2.5) system asks for exactly one or two screens of height
per section, run through `gate.mjs`, `consistency.mjs`, `shoot.mjs` and `perf.mjs` in headless
Chrome. Chrome is not installed here and cannot be installed on this device, so:

| Deviation | Why | Consequence |
|---|---|---|
| Sections use `min-height` + generous padding instead of a fixed `height: calc(h * 100svh)` | fixed heights clip silently and cannot be checked without a renderer | a section may exceed one screen on a small phone; content never clips |
| No `gate.mjs` / `shoot.mjs` / `consistency.mjs` / `perf.mjs` | requires Chrome | replaced by `tools/site-check.mjs` (static + numeric), which is not a visual check |
| No shader or WebGL effect | not needed for a reading app; costs weight and battery | the signature moment is type-led |
| System font stacks instead of a self-hosted display face | zero-webfont constraint and no licence to redistribute a face | Android renders Noto Serif where iOS renders Iowan/Palatino |
| Waveform runs three cycles then rests | a perpetual loop needs a pause control, which would clutter a card | motion is bounded; no pause button needed |
| Native `<details>` nav instead of a full-screen sheet | a sheet needs focus trapping, inert content and focus return, none verifiable here | the menu is keyboard-native and works without script; the footer repeats every link |
| No word-mask entrance | line measurement and font fallback cannot be checked without a renderer | the h1 enters as one block; nothing can stay hidden |
| `/voices/` uses stacked rows, not a table | a five-column table overflows at 320 px | no horizontal scrolling at any width |

## 9. Verification contract

`node tools/site-check.mjs` must print `SITE: PASS` and cover:

1. Structure: doctype, `lang`, one `h1`, heading order, `main`/`header`/`footer`, skip link,
   `title` ≤ 60 chars, meta description 50–160 chars.
2. Paths: every `href`/`src` resolves from its page directory, stays inside `site/`, is not
   root-absolute, is not protocol-relative; external links only to `github.com/vipulgote1999/…`
   (and the Play URL once configured).
3. Assets: every `<img>` has `alt`, `width`, `height`, and `loading="lazy"` unless marked
   `fetchpriority="high"`; no `<video autoplay>`; poster present.
4. Motion: `prefers-reduced-motion` block exists; every `animation:` and `transition:` in
   component rules is covered by it; no `transition: all`.
5. Tokens: no hex outside `:root`; only the documented radius values; only the documented spacings.
6. Budgets: each HTML ≤ 40 KB, CSS ≤ 45 KB, JS ≤ 15 KB, images ≤ 900 KB total, video not preloaded.
7. Contrast: recompute the documented pairs from the CSS tokens and assert text ≥ 4.5:1,
   secondary ≥ 4.5:1, UI boundaries ≥ 3:1.
8. Host parity: resolve every internal link both as Cloudflare root and as `/NovelSpeak/` subpath;
   fail if the two disagree.
9. Round-1 regressions: no `chip`-styled inert control (status text must have no border), the
   read-along paragraph must have an `aria-label`, every section must carry `scroll-margin-top`, and
   every entrance keyframe must sit inside a `prefers-reduced-motion: no-preference` block.

Visual judgement remains with the user: open the branch on a phone, or connect the Cloudflare
Pages project and review the preview. The report must say so rather than claim a browser pass.
