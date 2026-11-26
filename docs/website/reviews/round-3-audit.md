# Round 3 — independent audit, and the correction it forced

Date: 2026-11-25. This round exists because an independent auditor rejected the first completion
request, and it was right. The earlier record claimed a fix it had not made. That is worse than the
original defect, so this file states both plainly.

## What the auditor found

1. **`tablet-library-390.png` was shipping on `/features/`** with the alt "The same library on a
   tablet: two columns of books at 600 dp." The frame is a 26-row test-harness dump — "Prefetch
   regression", "Adblock race regression", "Error response" three times, "Fallback Chapter 1",
   "Auto One/Two", "Long Prefetch" — every row at 0 %, sources `localhost` and `10.0.2.2`, dates
   2026-10-27. Displayed near 1:1, so the strings were legible.
2. **`library-540.png` was shipping on `/` and `/features/`** with an alt promising search and
   filters. The rows read `10.0.2.2 · 0% · just now` and `localhost · 0% · 28 min ago`; one row is a
   Cloudflare interstitial ("Just a moment…") captured as a book title; two titles repeat.
3. **`player-540.png` and `cast-540.png`** showed `10.0.2.2/story.html` in the address bar.
4. **The record was inaccurate.** Round 2 rated this class blocking (AR2-2) and said the QA capture
   had been "replaced with the search/filters capture"; `assets.md` presented that file as the clean
   substitute; the PR body repeated the claim. The only acknowledgement, "a `10.0.2.2` source line",
   materially understated what was on screen.
5. **Size row wrong:** `assets.md` listed `player-540.png` at 540×960 while the file was 270×480.

## What changed

| Finding | Fix |
|---|---|
| Tablet library dump on `/features/` | Image deleted; figure removed. The library section is now text + specs. |
| Phone library with dev hosts, interstitial row and duplicates | Image deleted; the landing plate and the features figure are gone. There is no clean library capture in the repository, so the library is not illustrated. The claims stay as text and figures. |
| Address bars showing `10.0.2.2` in cast and player | Both captures are now cropped below the browser chrome — 212 px and 112 px respectively — in `tools/site-assets.mjs` (`cropTop`). Cropping keeps the app's own pixels; blurring or painting over the host would be a fabrication, which is why the library images were dropped instead of retouched. |
| Alts describing frames that no longer exist | Removed with their figures. The cast and player alts were re-read against the cropped images. |
| `assets.md` presented a rejected file as clean | All three library captures are now in the rejected table with what is actually visible in each. The ship list matches the eight images that exist. |
| Wrong size row | `player-540.png` is 540×848 after cropping; the row and the `<img>` dimensions agree. |
| Round-2 AR2-2 claimed a fix it had not made | AR2-2 rewritten to say what happened: one capture was swapped for another defective one, the tablet capture was never checked, and the independent audit caught both. |

## Re-checked after the fixes

- `node tools/site-check.mjs` → `SITE: PASS` — 6 pages, 8 images (the poster was removed in the second audit round).
- `assets/img` total: 498 KB across eight files, down from 770 KB.
- Both cropped images were opened and read after regeneration: no status bar, no address bar, no
  host string anywhere in either frame.
- `grep -rn "library-540\|tablet-library" site/` → nothing.
- The `<img>` dimensions in `/features/` match the regenerated files (540×854, 540×848).

## Second audit round: the poster came out

The second independent audit found the film poster still shipping after round 3. Its baked caption
reads “9:16 · 1080 × 1920 · NO NARRATION” while the page plays the 16:9 cut, so the primary page made
a false statement about its own asset; disclosing it in `assets.md` was not enough.

The poster file is no longer served. The film now has a designed pre-play state: the app’s reader
still fills a 16:9 stage, a visible `Play the film` button sits on it, and a plain MP4 link
(“MP4, 1.9 MB”) is visible under the frame. With JavaScript off, the still and the link remain and
the video stays hidden. `og:image` moved to `reader-540.png`, which is shipped.

Total image weight fell to 498 KB across eight files.

## The lesson, written down

The review opened some screenshots and trusted the rest. A claim of "verified" that covers only the
files someone happened to look at is not verification; it is sampling described as coverage. The
auditor did what the review should have done first: look at every image that ships, at the size it
ships.

Round 3 status: **closed** with the library unillustrated; reopened by the third audit above and closed again after its fixes. That is a gap in the page, not a defect in
its honesty — and a cleaner capture is the obvious follow-up when a device is available.

## Third audit round: a real overflow, a stale record, a contradicted property list

The next independent audit rejected completion on three findings. All three were correct.

1. **`/privacy/` scrolled sideways at 320 px and 360 px.** The permissions list contains
   `FOREGROUND_SERVICE_MEDIA_PLAYBACK` — 33 characters with no break opportunity — inside
   `<strong>`. At 16 px Roboto that word measures about 315 px; the content box at 320 px is 256 px
   (320 − 2×20 gutter − 24 list padding) and 296 px at 360 px. Nothing in the stylesheet applied
   `overflow-wrap` to prose, so the line box ran past the viewport. The a11y audit had listed the
   320 px check as "for a person", which was how a measurable defect survived three rounds.
   Fixed with `body { overflow-wrap: break-word }`: it inherits, so the permission token, its
   `<strong>` and the long issue-tracker URL all break. `site-check` now fails if that rule
   disappears.
2. **The round-2 record still carried the drift the summary claimed to have swept.** Two lines: a
   duplicated "source." left by an earlier edit, and a limitation bullet still saying "some captures
   still show fixture data (a `10.0.2.2` source line…)" after the crops removed every dev host. Both
   corrected; the bullet now says a sample story is visible and no dev host, harness title or
   duplicate ships.
3. **The motion layer was not GPU-only.** `.btn`, `.plate` and `.film__play` transitioned
   `background-color`, `border-color` and `color`, and the read-along transitioned `color` and
   `text-decoration-color`. The contract claimed transform/opacity only. Fixed properly rather than
   reworded: every colour, fill and border change is now instant, and the read-along is a 2 px
   accent underline that sweeps under each word via `transform: scaleX(0 → 1)`. Nothing but
   `transform` and `opacity` animates, and the checker fails any `transition` that names another
   property (both guards were negative-tested: injecting `transition: color` and deleting the
   `overflow-wrap` rule each produced a FAIL before the file was restored).

Non-blocking notes from the same audit were also taken: the design contract's `--lamp` gradient now
matches the CSS (72 % 28 %), §9 no longer claims checks the harness does not run, and the 404 and
`og:image` cross-host behaviour is stated as a limitation in the PR.

Status after this round: `SITE: PASS` (6 pages, 8 images, 498 KB), with two new regression guards
(body break rule; GPU-only transitions).
