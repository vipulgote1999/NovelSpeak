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

- `node tools/site-check.mjs` → `SITE: PASS` — 6 pages, 9 images (8 derived + the poster).
- `assets/img` total: 498 KB + the 145 KB lazy poster, down from 770 KB.
- Both cropped images were opened and read after regeneration: no status bar, no address bar, no
  host string anywhere in either frame.
- `grep -rn "library-540\|tablet-library" site/` → nothing.
- The `<img>` dimensions in `/features/` match the regenerated files (540×854, 540×848).

## The lesson, written down

The review opened some screenshots and trusted the rest. A claim of "verified" that covers only the
files someone happened to look at is not verification; it is sampling described as coverage. The
auditor did what the review should have done first: look at every image that ships, at the size it
ships.

Round 3 status: **closed** with the library unillustrated. That is a gap in the page, not a defect in
its honesty — and a cleaner capture is the obvious follow-up when a device is available.
