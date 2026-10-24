# Evidence — APK reverse-engineering stream ("apk-re", task-4)

All artefacts were obtained **2026-10-24** from lawful public channels only:
developer websites, GitHub Releases and the Aptoide public listing API.
No cracked/modded builds, no paywall/DRM/licence bypass, no accounts or credentials,
no Play-store APK scraping. Moon+ Reader **Pro** was deliberately not downloaded.

| Dir | Artifact | sha256 | Channel |
|---|---|---|---|
| `atvoice/` | @Voice Aloud Reader 40.9.1 (73,697,675 B) | `7f48c57afe3376013f028f4902a114fb73a075e193ee88faba598482f2f02cbb` | hyperionics.com/downloads/atVoice/atVoice.apk |
| `moonreader/` | Moon+ Reader free 4.5.4 (12,812,004 B) | `e71fab6821ef8c8a497a668210a05ee44e5602e04b6a06165531dcd0ace6abeb` | Aptoide API (com.flyersoft.moonreader) |
| `nekospeak/` | NekoSpeak 1.5.0 arm64-v8a (94,472,186 B) | `0108360a8aafa07cbd769839a0436deda0d348ff7e5b9214261691febc2bbc64` | GitHub Release v1.5.0 |
| `legado/` | — no APK obtained | — | upstream deleted 2026-05-27 (takedown) |
| `audify/` | — no APK obtained | — | Play-only; dev site has no APK link; Aptoide/Uptodown 404 |

## Contents

- `atvoice/hashes.txt` — identity block (sha256, size, versionName/Code, min/target SDK, dex count, apksigner certs)
- `atvoice/manifest.txt` — permissions, services with `foregroundServiceType`, receivers, custom providers
- `atvoice/packages.txt` — smali class census per package (ad SDKs, jsoup, OkHttp, app packages)
- `atvoice/classes.txt` — full class list of `com.hyperionics.avar` (107 entries: ExtractBrowser*, ContentLoaderBrowser, VsWebView, ProcessExitDiagnostics, editor/readlist/speech packages ...)
- `atvoice/assets.txt` — `assets/js/` inventory with byte sizes + `CloudTtsConfig.txt` header comments (verbatim)
- `atvoice/strings-excerpts.txt` — verbatim `res/values/strings.xml` rows for cloud TTS, AI, monetization, paywall strings
- `atvoice/raw-inventory.txt` — 58 `res/raw` entries (per-language `*_js` text-processing resources)
- `moonreader/hashes.txt`, `manifest.txt`, `architecture.txt` — identity, 6-permission manifest, package/behaviour census
- `nekospeak/hashes.txt` — identity + native libs + 371 bundled `espeak-ng-data` assets
- `nekospeak/source-architecture.txt` — upstream source extract: `NekoTtsService : TextToSpeechService`, engine classes, `ModelRepository.kt` model URLs, `cloneVoice()` entry point, latest `RELEASE_NOTES.md` heading (v1.4.2 vs shipped v1.5.0)
- `legado/upstream-status.txt` — cloned repo state, git log, verbatim takedown README, API probe results
- `audify/play-metadata.txt` — Play metadata only; APK acquisition attempts and failures

## Reproduce

Raw work dirs are not committed (by instruction): `/tmp/apks` (artifacts),
`/tmp/re-work` (apktool/jadx output), `/tmp/competitive/apk-re` (scratch).
Full exact command list is in §13 of `../reverse-engineering.md`.

## Handling rules applied

- Only file names, class counts, manifest lines and short resource strings are reproduced here.
- No decompiled method bodies, no proprietary assets, no rule packs, no model weights.
- The single DRM artefact observed (@Voice `liblcp.so`, Readium LCP) was recorded, never exercised.
