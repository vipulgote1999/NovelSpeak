# Website asset audit

Status: round 1. Every file here already exists in the repo; the site ships nothing it did not
already own. No stock photography, no third-party icon packs, no webfonts.

## Constraints that decide the pipeline

- Hosts: Cloudflare Pages serves `site/` at `/`; GitHub Pages serves `site/` at `/NovelSpeak/`.
  Every reference is **relative**, resolved from the page, never root-absolute.
- No build step and no npm: the bytes committed under `site/assets/` are the bytes served.
- No image encoder on this machine: `identify`, `convert`, `ffmpeg`, `sharp` and Python are all
  absent. Half-size PNGs are produced by `tools/site-assets.mjs` (Node `zlib` only: decode, 2× box
  filter, re-encode). If that tool fails a visual check, the fallback is native size plus
  `loading="lazy"` and explicit `width`/`height`, and this file records the fallback.
- Screens are presented at device scale (a 360 dp phone screen shown at ~300–360 CSS px), so a
  540 px-wide PNG is still ~1.5× and stays sharp.

## Usable sources

| Source | Dimensions | Weight | Use |
|---|---|---|---|
| `docs/play-store/screenshots/phone-01-home.png` | 1080×1920 | 145 KB | home screen, features page |
| `docs/play-store/screenshots/phone-02-reading.png` | 1080×1920 | 357 KB | hero plate (reader with read-along) |
| `docs/play-store/screenshots/phone-03-settings.png` | 1080×1920 | 177 KB | settings hub, features |
| `docs/play-store/screenshots/phone-04-privacy.png` | 1080×1920 | 269 KB | privacy page figure |
| `docs/media/ui-audit/library-grid-phone.png` | 540×960 | 105 KB | **rejected on the final audit**, see below: the row sources read `10.0.2.2` / `localhost`, one row is a Cloudflare interstitial (“Just a moment…”) captured as a book, and titles repeat. No clean library capture exists in the repo, so the library is described in text and specs, not illustrated. |
| `docs/media/ui-audit/phone-cast.png` | 1080×1920 | 200 KB | story cast, features — cropped below the browser chrome (212 px) because the address bar showed an emulator host |
| `docs/media/ui-audit/scale20-reader.png` | 1080×1920 | 190 KB | **rejected**, see below: at 2× the compact player ellipsises its own labels |
| `docs/media/ui-audit/tablet-reader.png` | 1200×1920 | 212 KB | **rejected**, see below: about 40% of the frame is empty black below the two panes |
| `docs/media/ui-audit/library-grid-tablet.png` | 780×1248 | 224 KB | **rejected on the final audit**, see below: a 26-row test-harness dump (“Prefetch regression”, “Adblock race regression”, “Error response”, “Auto One”) at legible size |
| `docs/media/ui-audit/chrome-compact-phone.png` | 540×960 | 115 KB | player card, features — cropped below the browser chrome (112 px) for the same reason |
| `docs/media/novelspeak-launch-landscape.mp4` | 16:9, 23 s, 1.9 MB | — | film: reader still first, click to play |
| `docs/media/novelspeak-launch-poster.jpg` | 1600×900 | 145 KB | **not shipped**, see below: its baked caption reads “9:16 · 1080 × 1920 · NO NARRATION” while the page plays the 16:9 cut |
| `docs/media/novelspeak-launch-contact-sheet.jpg` | 1104×1944 | 125 KB | not used (internal review artifact) |
| `docs/play-store/graphics/icon-512.png` | 512×512 | 4 KB | favicon + nav mark |
| `docs/play-store/graphics/feature-graphic-1024x500.png` | 1024×500 | 48 KB | not used (store asset) |

## Rejected sources, and why

| Source | Weight | Why not |
|---|---|---|
| `docs/media/novelspeak-launch.gif` (portrait) | 6.5 MB | GIF, no audio, 4× the MP4's weight |
| `docs/media/novelspeak-launch-landscape.gif` | 3.4 MB | only useful as an autoplay loop; the page never autoplays 3 MB |
| `docs/media/novelspeak-launch.mp4` (portrait) | 8.7 MB | the 16:9 cut is enough; portrait is a store asset |
| `docs/media/ui-audit/*` duplicates (wave1/wave3/chrome-13/2x variants) | 100–270 KB each | same screens twice; the set above is one per screen |
| `docs/play-store/screenshots/phone-01..04` beyond the two above | — | the store set duplicates the audit set |
| `docs/media/ui-audit/phone-library.png` | 179 KB | QA fixture rows (“Adblock race regression”, “Prefetch regression”) are visible |
| `docs/media/ui-audit/library-grid-phone.png` | 105 KB | search and filters are visible, but the sources read `10.0.2.2` / `localhost`, a “Just a moment…” interstitial is captured as a title, and two titles repeat |
| `docs/media/ui-audit/library-grid-tablet.png` | 224 KB | a 26-row test-harness dump: “Prefetch regression”, “Adblock race regression”, “Error response” ×3, “Fallback Chapter 1”, “Auto One/Two”, all at 0% |
| `docs/media/novelspeak-launch-poster.jpg` (as a video poster) | 145 KB | it states 9:16 · 1080 × 1920 in baked text while the served film is 16:9; there is no JPEG encoder here to re-cut it, and publishing a caption the file contradicts is not an option |
| `docs/media/ui-audit/tablet-reader.png` | 212 KB | two-pane view with about 40% empty black below it; it would read as a broken frame |
| `docs/media/ui-audit/scale20-reader.png` | 190 KB | at 2× the player's status and voice labels ellipsise (“Ready · 18 senten…”). Publishing it as proof of the accessibility claim would undercut the claim; the claim stays, the picture goes |

## Ship list (derived, target sizes)

PNG downscale is an integer box filter (exact 2×, no resampling artefacts) after an optional crop of
the browser chrome. Derive once, commit:

| Output | From | Target | Budget |
|---|---|---|---|
| `assets/img/reader-540.png` | phone-02-reading | 540×960 | ≤ 110 KB |
| `assets/img/home-540.png` | phone-01-home | 540×960 | ≤ 70 KB |
| `assets/img/cast-540.png` | phone-cast, cropped 212 px | 540×854 | ≤ 80 KB |
| `assets/img/settings-540.png` | phone-03-settings | 540×960 | ≤ 80 KB |
| `assets/img/privacy-540.png` | phone-04-privacy | 540×960 | ≤ 90 KB |
| `assets/img/player-540.png` | chrome-compact-phone, cropped 112 px | 540×848 | ≤ 110 KB |
| `assets/img/icon-96.png` | icon-512 | 96×96 | ≤ 8 KB |
| `assets/img/icon-192.png` | icon-512 | 192×192 | ≤ 20 KB |

Every image now ships at most at its source resolution; nothing is upscaled. Total image weight as
shipped: **498 KB** across eight files, against a 900 KB ceiling.

The film MP4 (1.9 MB) is never fetched until the visitor presses play: `<video preload="none">`
hidden behind the reader still, with a visible play control and a plain MP4 link under the frame.
Native controls take over once it plays.

`cropTop` in `tools/site-assets.mjs` removes browser chrome before downscaling. Cropping is a
legitimate edit — the pixels shown are still the app's own — whereas blurring a dev host or
painting over a row would be a fabrication, which is why the library captures were dropped instead
of retouched.

The film has no poster attribute. Its pre-play state is the app’s own reader still inside a 16:9
frame, with a visible play control and a plain MP4 link under it; with JavaScript off, the still and
the link remain and the video stays hidden. Nothing on the page claims an aspect ratio, a
resolution or an audio track the file does not have.

## Dual-host path rules

1. Root pages (`site/index.html`) reference `assets/...`, `./features/`, `./privacy/`.
2. Subpages (`site/features/index.html`) reference `../assets/...`, `../features/`, `../privacy/`.
3. `404.html` cannot know its depth, so it cannot use a relative home link. Its static href is the
   absolute GitHub Pages URL, which always resolves; a 4-line inline script then rewrites it to `/`
   on any host that is not `github.io`, and to `/NovelSpeak/` when the path already starts there. With
   JS off, both hosts send the visitor to the GitHub Pages site rather than to a broken path — the
   one case where a working cross-host link beats host accuracy. The page carries its own styles for
   the same reason (no relative asset paths at unknown depth).
4. No `<base>`, no root-absolute asset paths, no protocol-relative URLs, no external origins.
5. `_headers` and `_redirects` are Cloudflare-only and are ignored by GitHub Pages; nothing depends
   on them for correctness.

`tools/site-check.mjs` resolves every `href`/`src` against the page's own directory and fails on any
that leaves the site, is root-absolute, or points at a missing file.
