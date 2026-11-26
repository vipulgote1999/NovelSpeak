# Motion audit — the built pages

Date: 2026-11-25. Scope: `site/assets/site.css` (§ motion), `site/assets/site.js`, every page.
Method: source audit plus the static checks in `tools/site-check.mjs`. No renderer is available on
this machine, so nothing here claims a measured frame rate; each row states what the code does and
where the fallback is.

## Rule applied

Motion only ever adds to a page that is already whole. Default state is visible; every entrance
keyframe lives inside `@media (prefers-reduced-motion: no-preference)`, so a browser that refuses
animations still paints the h1, the buttons and the pictures.

## What moves, and why

| # | Where | What | Tokens | Purpose | Reduced motion | No JS |
|---|---|---|---|---|---|---|
| 1 | Hero, every page | h1 rises as one block (16 px, opacity), then lead, actions and the fact line at 80/140/200 ms; the hero plate settles 1.05 → 1 inside `overflow:hidden` | `--m-enter` 700 ms, `--m-ease-enter` | the title arrives before the promise | not applied at all (base state visible) | entrance still runs; it is pure CSS |
| 2 | Landing §03, paper | the Alice sentence lights word by word in `--paper-accent` with a 2 px underline | `--m-read` 120 ms, `--m-read-step` 28 ms (≈1.7 s total) | demonstrates read-along with the page itself | JS refuses to split the sentence; it stays plain body text | sentence is plain text, no split |
| 3 | Landing §02, chapter head | five bars, `scaleY(.35 → 1)`, 3.2 s, three cycles then rest; `animation-play-state: paused` while off-screen | `--m-cycle` 3.2 s, `--ease-io` | a voice is a physical thing, briefly | animation killed globally; bars rest at 0.7 | bars rest still |
| 4 | Buttons | 1 px lift on hover (fine pointer only), fill steps to `--accent-hover`; press sinks 1 px and scales .985 | `--m-in` 150, `--m-press` 90 | every control answers the pointer | instantaneous state change | href works |
| 5 | Links | underline thickens 1 → 2 px; nav and footer words take an underline at 6 px offset | `--m-in` 150 / `--m-out` 120 | links look like links | instantaneous | unchanged |
| 6 | Linked plates | card lifts 2 px, border steps to `--rule-ui`, image scales 1.02 inside the frame | `--m-image` 600 / `--m-image-out` 400 | the picture is a thing you can open | no transform | unchanged |
| 7 | Nav disclosure | panel fades in with a 6 px drop; chevron rotates | `--m-panel` 200, `--m-close` 120 | the menu arrives | instantaneous | native `<details>` |
| 8 | Focus | 2 px ring, 2 px offset, drawn in the same frame (no transition) | — | keyboard is never lost | unchanged | unchanged |

## What does not move, on purpose

No section or card fades in on scroll; no parallax; no scroll-jacking, smooth scrolling or snap; no
ticker, carousel, tab timer or counter; no tilt, glow sweep or shine; no loader or entry gate. The
film does not autoplay and fetches nothing until it is pressed. The waveform is the only loop, and
it stops after three cycles.

## Properties

Animated: `transform`, `opacity`, `color`, `text-decoration-color`, `background-color`,
`border-color`. Never `width`, `height`, `top`, `left`, `margin`, `padding`, `filter` or
`box-shadow`. No `transition: all` anywhere (checked by `site-check`). The read-along's only
non-composited property is `color`/`text-decoration-color`, which is the point of the effect and is
bounded to one sentence once per visit.

## Findings and fixes made during this pass

| Finding | Severity | Fix |
|---|---|---|
| `--m-read` was doing double duty as duration and stagger, giving a ~3.6 s sweep over 60 words | blocking | split into `--m-read` (120 ms transition) and `--m-read-step` (28 ms); total ≈1.7 s |
| `.wave.is-paused` was toggled by script with no CSS behind it | blocking | added `animation-play-state: paused` |
| `--ring-on-accent` was specified in the contract but never used in CSS | minor | removed from both; a primary button's ring sits outside its fill, so `--accent` is correct |
| The contract promised an underline that "draws left to right"; the shipped hover changes thickness instead | minor, recorded | `background-image` would mean a second gradient, which the contract bans. Thickness change kept and the contract amended |
| Nav sheet was planned as a focus-trapped dialog | resolved in round 1 | native `<details>`, so there is no trapped focus to test |

## What still needs a human eye

The entrance timing, the sweep speed and the plate settle cannot be judged from source. Open the
branch on a phone (or the Cloudflare preview) with motion on and off, and check: the h1 is never
invisible, the read-along finishes within two seconds, the five bars stop, and nothing shifts when
the film's still loads. That is the one check this document cannot perform.
