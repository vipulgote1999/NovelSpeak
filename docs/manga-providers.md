# Manga providers

The manga reader has two layers:

1. **Generic extractor** (`app/assets/manga-extract.js`) for image-based chapters. It reads
   rendered `<img>`/`<picture>` elements, lazy `data-*` attributes, `srcset`, CSS backgrounds,
   preload links and serialized framework payloads. It keeps the longest numbered page run, or
   falls back to the largest repeated viewer stack in DOM order, and rejects logos, avatars,
   thumbnails, covers and recommendation cards.
2. **Native resolvers** for providers whose reader is easier to read from an API or from the
   server response than from a rendered WebView.

## Verified providers

| Provider | Path used | Pages | How it runs |
|---|---|---|---|
| **WeebCentral** | `/chapters/<id>` | 57 | Generic extractor; page list found in the rendered images/payload |
| **MangaDex** | `/chapter/<uuid>` | 52 | `MangaDexResolver` calls `/chapter/{id}` + `/at-home/server/{id}` |
| **Webtoons** | `/<lang>/<genre>/<series>/<episode>/viewer?title_no=…&episode_no=…` | 8 | `MangaHtmlResolver` fetches the desktop viewer HTML and reads `img._images[data-url]` |
| **Tapas** | `/episode/<id>` | 19 | `MangaHtmlResolver` fetches the episode HTML and reads `img.content__img[data-src]` |
| **Generic image sites** | any chapter URL | varies | Browser extractor (verified with a 57-page One Piece chapter) |

The provider URLs are opened directly from the address bar: MangaDex, Webtoons and Tapas resolve
without rendering their reader page in the WebView. This is deliberate — Tapas' mobile page and
Webtoons' mobile redirect can crash or block a WebView renderer on some devices, while their
server responses are small and complete. Other sites still use the WebView and the **Manga**
button.

## Regressions

- `tools/manga-extract-test/` — headless Chromium fixtures for:
  - consecutive numbered pages from a serialized payload;
  - Webtoons-style `_images[data-url]` pages with placeholder `src` and declared dimensions;
  - Tapas-style `content__img[data-src]` pages across unique CDN directories.
- `tools/manga-provider-test/` — provider URL detection, MangaDex page-URL building, and
  Webtoons/Tapas HTML parsing/title cleanup.
- `tools/manga-text-test/` — reading order, hyphenation, non-speech filtering and chunk size.

## Not supported / known limits

These were checked and are not currently reliable:

- **MangaFire** — the reader uses a signed `vrf` token and a JavaScript decryptor for the page
  list; the DOM only exposes the first few swiper slides.
- **Comick** — the page and API are behind a Cloudflare challenge (403/522 in the test network).
- **Asura Scans** — Cloudflare challenge (`Just a moment…`) in the headless test.
- **MANGA Plus** — official viewer uses protected/encrypted delivery rather than plain page
  images.
- **MangaHere** — current chapter paths returned 404 in the test network.
- **Bato.to** — the site displays a copyright-closure page.
- Sites that render pages into a canvas, use cross-origin `blob:` images, or require a solved
  challenge before showing the page list.

If a provider serves plain page HTML, the generic extractor is likely to work without a new
adapter. Add a fixture to `tools/manga-extract-test/` first, then a native resolver only if the
reader page is too hostile for a WebView.
