# Merge audit — rebase onto origin/main 3deb352

Read-only audit of `vipulgote1999/feat-adblocker-integration`.

## Result

* `git diff origin/main..HEAD --stat` contains only adblock assets/Kotlin plus
  `MainActivity.kt`, `Prefs.kt`, `Reader.kt`, `SettingsActivity.kt`, `README.md`,
  `docs/`, and the reader fixes.
* Main-touched files have an **empty diff** against `origin/main`:
  - `app/AndroidManifest.xml`
  - `app/assets/extract.js` (before the reader-fix commit; final diff is the
    dotted-word/visibility fix described in `docs/reader-fixes.md`)
  - `AudioDsp.kt`
  - `DiagLog.kt`
  - `PcmPlayer.kt`
  - `PlaybackService.kt`
  - `SentenceSplitter.kt` (final diff is the boundary/default fix)
  - `SherpaEngine.kt` (final diff is the callback-contract fix)
  - `WebExtractor.kt`
* The rebase is linear: `3deb352` → `724e251` → `a720278` → `a5a0eb6` →
  `9df3ba9` → `f114806`.
* No conflict markers remain.
* Main's reader-mode overlay, navigation, settings cards, prefetch/tuning
  migration, and extraction/reader pipeline are present and wired. The adblock
  section is additive.

## Conclusion

The reported sentence/audio problems are **not** caused by lost main changes.
They are the pre-existing/main-era callback inversion (`SherpaEngine`) plus
extraction/splitting defects, fixed in `9df3ba9`; the adblock integration only
added the cold-start/visibility interactions addressed by the extractor changes.
