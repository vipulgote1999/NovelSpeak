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
- `tools/maestro/flows/22_manga_launch.yaml`, `23_manga_ocr_read.yaml`

## Known limits

- ML Kit OCR now ships its native pipeline library (`libmlkit_google_ocr_pipeline.so`) and Latin model assets inside the APK (see build.sh "2b/7" and the merged asset dir), so OCR works on the emulator. Other scripts (Japanese/Chinese/Korean/Devanagari) still rely on Play Services to fetch their models on first use.
- Multi-page reading comes from browser extraction or the image picker; there is no CBZ/ZIP
  folder import in v1.
- The browser flow extracts page images from the rendered page; image-only sites whose pages are
  canvas-rendered or behind a cross-origin `blob:` still need a dedicated source.
- OCR reading order follows ML Kit's text-block order. Right-to-left panel sorting is not applied;
  the bubble boxes still let the user jump to any line.
- Translation models download on first use; until then text is read in the source language.