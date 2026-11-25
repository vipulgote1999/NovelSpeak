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
| `docs/media/ui-audit/library-grid-phone.png` | 540×960 | 105 KB | library with search + filters, landing §04 and /features/ |
| `docs/media/ui-audit/phone-cast.png` | 1080×1920 | 200 KB | story cast, features |
| `docs/media/ui-audit/scale20-reader.png` | 1080×1920 | 190 KB | **rejected**, see below: at 2× the compact player ellipsises its own labels |
| `docs/media/ui-audit/tablet-reader.png` | 1200×1920 | 212 KB | **rejected**, see below: about 40% of the frame is empty black below the two panes |
| `docs/media/ui-audit/library-grid-tablet.png` | 780×1248 | 224 KB | tablet library grid |
| `docs/media/ui-audit/chrome-compact-phone.png` | 540×960 | 115 KB | compact player / chrome |
| `docs/media/novelspeak-launch-landscape.mp4` | 16:9, 23 s, 1.9 MB | — | film, poster-first, click to play |
| `docs/media/novelspeak-launch-poster.jpg` | 1600×900 | 145 KB | film poster (shipped native; no JPEG encoder) |
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
| `docs/media/ui-audit/phone-library.png` | 179 KB | QA fixture rows (“Adblock race regression”, “Prefetch regression”) are visible; `library-grid-phone.png` shows the search and filters instead |
| `docs/media/ui-audit/tablet-reader.png` | 212 KB | two-pane view with about 40% empty black below it; it would read as a broken frame |
| `docs/media/ui-audit/scale20-reader.png` | 190 KB | at 2× the player's status and voice labels ellipsise (“Ready · 18 senten…”). Publishing it as proof of the accessibility claim would undercut the claim; the claim stays, the picture goes |

## Ship list (derived, target sizes)

PNG downscale is exact 2× (no resampling artefacts, predictable weight). Derive once, commit:

| Output | From | Target | Budget |
|---|---|---|---|
| `assets/img/reader-540.png` | phone-02-reading | 540×960 | ≤ 110 KB |
| `assets/img/home-540.png` | phone-01-home | 540×960 | ≤ 70 KB |
| `assets/img/library-540.png` | library-grid-phone | 540×960 (native) | ≤ 110 KB |
| `assets/img/cast-540.png` | phone-cast | 540×960 | ≤ 80 KB |
| `assets/img/settings-540.png` | phone-03-settings | 540×960 | ≤ 80 KB |
| `assets/img/privacy-540.png` | phone-04-privacy | 540×960 | ≤ 90 KB |
| `assets/img/tablet-library-390.png` | library-grid-tablet | 390×624 | ≤ 70 KB |
| `assets/img/player-540.png` | chrome-compact-phone | 540×960 | ≤ 60 KB |
| `assets/img/film-poster.jpg` | novelspeak-launch-poster | native 1600×900 | 145 KB — no JPEG encoder on this device, so the poster ships native and lazy; recorded as a deviation |
| `assets/img/icon-96.png` | icon-512 | 96×96 | ≤ 8 KB |
| `assets/img/icon-192.png` | icon-512 | 192×192 | ≤ 20 KB |

Every image now ships at most at its source resolution; nothing is upscaled. Total image budget for the whole site: **≤ 900 KB** (lazy-loaded except the hero plate and icons).
The film MP4 (1.9 MB) is never fetched until the visitor presses play: `<video preload="none">`
with `poster`, plus a visible play control and a pause control.

## Dual-host path rules

1. Root pages (`site/index.html`) reference `assets/...`, `./features/`, `./privacy/`.
2. Subpages (`site/features/index.html`) reference `../assets/...`, `../features/`, `../privacy/`.
3. `404.html` cannot know its depth, so its home link is `/` and a 6-line inline script rewrites it
   to `/NovelSpeak/` when `location.pathname` starts with that prefix. With JS off the link still
   reads "Home" and lands on the Cloudflare root.
4. No `<base>`, no root-absolute asset paths, no protocol-relative URLs, no external origins.
5. `_headers` and `_redirects` are Cloudflare-only and are ignored by GitHub Pages; nothing depends
   on them for correctness.

`tools/site-check.mjs` resolves every `href`/`src` against the page's own directory and fails on any
that leaves the site, is root-absolute, or points at a missing file.
