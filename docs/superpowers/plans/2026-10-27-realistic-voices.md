# Realistic voices implementation plan

> For agentic workers: execute task-by-task, commit after each.

Goal: Add Supertonic 3 and ZipVoice (curated, <=5 voices) to NovelSpeak without native changes.
Architecture: extend ModelManager.MODELS, SherpaEngine.buildConfig/voices, Prefs for style index and ZipVoice transcript; keep Pocket.
Tech stack: Kotlin, sherpa-onnx OfflineTts, ModelManager tar.bz2 installer.
Spec: docs/superpowers/specs/2026-10-27-realistic-voices-design.md

## Global constraints
- SHA-256 pinned from GitHub release digests.
- No native rebuild; use existing AAR.
- Family strings: supertonic, zipvoice.
- Curated voices only: Kokoro Heart+Adam; Supertonic F1+M1.

## Task 1: ModelManager entries
Files: app/src/app/novelspeak/ModelManager.kt, Prefs.kt
- Add MODEL_SUPERTONIC, MODEL_ZIPVOICE, MODEL_VOCOS constants.
- Add Model entries with urls/sha/size.
- isInstalled checks for supertonic required files and zipvoice required files + vocoder.

## Task 2: SherpaEngine buildConfig
Files: SherpaEngine.kt
- Add supertonic branch using OfflineTtsSupertonicModelConfig.
- Add zipvoice branch using OfflineTtsZipVoiceModelConfig + vocoder.
- voices() returns curated lists per family.
- setVoice maps style index for supertonic.

## Task 3: Prefs
Files: Prefs.kt
- Add supertonicStyle index.
- Add zipVoiceTranscript (optional).

## Task 4: Build + emulator test
- ./build.sh
- install on emulator-5554
- install models, synthesize test sentence.
