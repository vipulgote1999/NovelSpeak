# Reflection

A short account of what the review actually changed, written after the build, so the next person
knows which decisions were argued and which were merely made.

## The plan lost two fights before a line of code existed

Round 1 killed the Play badge as a button ("coming" is a status, not a thing to press), killed
"listing in review" (nothing in the repo proves submission), killed the film's caption (a caption
explains a picture; a mono kicker above it does not), and killed the full-screen nav sheet in favour
of a native `<details>`. It also changed the h1 from "Read any web novel out loud" to "Read web
novels out loud", because "any" is contradicted by the app's own list of limits.

## The build exposed three plan errors

1. **Aria-label on a paragraph.** The plan said to give the read-along sentence an `aria-label` and
   hide its word spans. A `<p>` has no usable accessible name in ARIA 1.2. The shipped version keeps
   whole-word spans visible instead, so reading order and pronunciation are unchanged, and the
   contract now says the opposite of what the plan said. The correction is recorded in
   `reviews/round-1-plan.md`, not silently rewritten.
2. **The read-along was too slow.** 60 ms per word over a 60-word sentence is 3.6 seconds of
   watching a sentence light up. It is 28 ms per word now — about 1.7 seconds — which reads as one
   gesture rather than a progress bar.
3. **Two screenshots were not usable as proof.** The library capture showed QA fixture rows
   ("Adblock race regression"), and the 2× text capture shows the player ellipsising its own labels.
   The 2× capture was removed rather than published as evidence for the accessibility claim it
   weakens. The library capture was first swapped for another one — and round 2 then reported that as
   a fix. The independent audit found the replacement still showed `10.0.2.2` and `localhost` row
   sources, a "Just a moment…" interstitial captured as a title, and duplicate titles; and that a
   second library screenshot, a 26-row test dump, was still shipping on `/features/`. Both were
   removed. The library is now text and specs only. See `reviews/round-3-audit.md`.

## The review changed the page's shape, not just its details

The landing page originally planned four text/phone splits in a row. Round 2 rebuilt two of them: the
voices section is now a full-width ledger with sizes in mono, and the screen-off section leads with
the film at full width. Section rules became systematic (they separate same-ground neighbours,
nothing else), and the hero went back to top-aligned so the h1 starts where the contract says it
does.

## What remains a known limitation

- **Nothing was rendered.** There is no Chromium on this machine and no way to install one. Every
  visual claim is a source-level claim; the five things that need eyes are listed as a checklist in
  `reviews/a11y.md` § 7. The site-check is a static contract, and the report says so instead of
  implying a browser pass.
- **Play is not live.** The only working install is the signed 1.3.0 APK. The site says "coming",
  and the changelog says plainly that 1.4.0 is store-ready but not downloadable.
- **The film has no poster.** The only supplied poster states 9:16 · 1080 × 1920 while the served
  film is 16:9, and there is no JPEG encoder here to re-cut it. The pre-play state is the app’s
  reader still with a plain MP4 link under it.
- **The display face is a system stack.** Android renders Noto Serif where iOS renders Iowan or
  Palatino. Zero webfont bytes, zero third-party origins, at the cost of one less piece of control.
- **A sample story is visible** in the reader, cast and player captures ("The Lantern", "Marshal
  Rennick"). It is the app's own test content, not a fixture row: no dev host, no harness title and
  no duplicate ships. Every screenshot was opened and checked after the round-3 crops.
- **The 404 duplicates its styles.** It is served at unknown depths, so a shared stylesheet would
  need a root-absolute path, which would break the GitHub Pages subpath. Duplication is the correct
  trade.

## The honest summary

The site is a static, dependency-free, claim-audited front matter for a reading app: a serif title
page, one cream page where a sentence lights up word by word, real screenshots at real sizes, and a
limits section written in the same voice as the benefits. It has been checked harder than it has
been looked at. That order is wrong for design work, and it is the one order this machine allowed.
