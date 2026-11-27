# Manga (image-based) support — research & plan

## References (reverse-engineered)

- **Mihon** (mihonapp/mihon, Apache-2.0): Android manga reader; image-page rendering, CBZ/ZIP/folder import, reading direction. No TTS (images).
- **Chimahon** (Chimahon/chimahon, Mihon fork): on-device OCR via ML Kit (Latin/Japanese/Chinese/Korean/Devanagari), `.mokuro` pre-OCR format, panel-by-panel navigation via LiteRT. OCR runs per page; text becomes selectable overlay.
- **Mokuro / Manga OCR**: offline OCR pipeline producing JSON with bounding boxes; reference for reading-order and block granularity.
- **@Voice Aloud Reader**: OCR-aware reading for scanned PDFs; establishes the "OCR then TTS" UX pattern.

## Architecture decisions

1. OCR engine: Google ML Kit on-device text recognition (play-services-mlkit-text-recognition-*), chosen for offline, script-specific models and bounding boxes. Recognizer selected from user's preferred source language.
2. Translation: ML Kit Translation (optional), source->preferred language, best-effort (falls back to original on failure).
3. TTS: reuse the existing `Reader`/`TtsEngine` pipeline; each OCR line becomes a `Reader.Block` with `voiceId=null` (narrator only) — story cast / dialogue parsing disabled for manga.
4. Tracking: `Reader.Listener.onHighlight(blockIndex)` maps to a `MangaCue` that carries the page
   index and OCR bubble box. The reader view switches to that page and highlights the whole box;
   page images and OCR text are therefore always shown from the same page.
5. Storage: bundled sample asset + file picker; no library DB changes for v1.

## Performance notes

- OCR runs on a background thread; UI shows "Running OCR…" status.
- ML Kit models download via Google Play Services on first use (requires network once).
- Translation models downloaded on demand via `downloadModelIfNeeded`.

## Files

- `app/src/app/novelspeak/manga/MangaModels.kt`
- `app/src/app/novelspeak/manga/MangaOcr.kt`
- `app/src/app/novelspeak/manga/MangaTranslator.kt`
- `app/src/app/novelspeak/manga/MangaPageLoader.kt` (bounded bitmap + disk cache)
- `app/src/app/novelspeak/manga/MangaSession.kt` (per-page load → OCR → translate pipeline)
- `app/src/app/novelspeak/manga/MangaPageView.kt` (page renderer + OCR box overlay)
- `app/src/app/novelspeak/MangaReaderActivity.kt`
- `app/src/app/novelspeak/Prefs.kt` (manga settings)
- `app/AndroidManifest.xml` (ML Kit init provider + activity)
- `tools/maestro/flows/31_manga_launch.yaml`, `32_manga_ocr_read.yaml`,
  `33_manga_page_sync.yaml`, `34_manga_tts_page_turn.yaml`

## 2026-11-27 upgrade

- **Continuous feed.** `MangaFeedView` stacks every page in one scroll surface, keeps only a
  window of bitmaps decoded, and scrolls the spoken bubble into view. The old single-page view
  remains the page renderer inside the feed.
- **Real-site extraction.** `app/assets/manga-extract.js` recovers a chapter from rendered DOM,
  lazy attributes, `srcset`, CSS backgrounds, preload links and the framework's serialized page
  list, then keeps the longest numbered run and rejects chrome/avatars. Regression fixture:
  `tools/manga-extract-test/`.
- **Reading order and chunk validation.** `MangaTextLayout` sorts bubbles into rows/columns,
  honours right-to-left pages, repairs line-break hyphens, drops non-speech fragments, and splits
  long text at the configured cap. `MangaSession` joins only vertically adjacent short bubbles
  in the same column, so unrelated panels are not read as one sentence.
- **Read-along.** The manga reader turns on word-level highlighting through a Reader override,
  shows the current chunk, and exposes the full chunk list.
- **Validation.** Host harness `tools/manga-text-test/` (reading order and chunk rules), browser
  fixture `tools/manga-extract-test/`, and Maestro flows `33`/`34` for feed sync and TTS scrolling.
  Real One Piece chapter 1: 57/57 pages, 56 with text, 708 chunks, max 177/180 chars.
- **Provider matrix (2026-11-27).** MangaDex resolves through its public at-home API (52 pages),
  Webtoons through desktop HTML `img._images[data-url]` (8 pages), Tapas through episode HTML
  `img.content__img[data-src]` (19 pages), and WeebCentral through the generic extractor
  (57 pages). See [docs/manga-providers.md](../../manga-providers.md) for the full matrix and
  the providers that remain blocked (MangaFire VRF, Comick/Asura Cloudflare, Manga Plus DRM).

## Known limits

- ML Kit OCR now ships its native pipeline library (`libmlkit_google_ocr_pipeline.so`) and Latin model assets inside the APK (see build.sh "2b/7" and the merged asset dir), so OCR works on the emulator. Other scripts (Japanese/Chinese/Korean/Devanagari) still rely on Play Services to fetch their models on first use.
- Multi-page reading comes from browser extraction or the image picker; there is no CBZ/ZIP
  folder import in v1.
- The browser flow extracts page images from the rendered page; image-only sites whose pages are
  canvas-rendered or behind a cross-origin `blob:` still need a dedicated source.
- Reading order is block-based, not panel-segmented: a row of unrelated panels can still order
  bubbles left-to-right by row. The same-column merge guard prevents the worst cross-panel joins,
  and tapping a box always corrects playback.
- Translation models download on first use; until then text is read in the source language.