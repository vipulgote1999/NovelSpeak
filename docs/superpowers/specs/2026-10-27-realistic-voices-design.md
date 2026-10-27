# Realistic voices: add Tier-1 families to NovelSpeak

Date: 2026-10-27
Branch: feat/realistic-voices

## Intent and why

NovelSpeak reads novels aloud. Today only Kokoro (11 English voices) and Pocket TTS (zero-shot cloning) are wired. Users want more realistic, varied voices, including multilingual (Chinese) and cloning with a transcript for higher fidelity. We will add the Tier-1 families sherpa-onnx already supports, behind the existing model manager + settings UI, with no native dependency changes.

## Goals

- Curate Kokoro to the 2 community-favorite voices only (Heart US female, Adam US male); hide the other 9.
- Add Supertonic 3 but expose only 2 curated styles (F1 female, M1 male).
- Add ZipVoice distill int8 (zh+en cloning with transcript) as a new family branch, including the separate 24 kHz vocoder dependency.
- Keep Pocket TTS (already wired) as the audio-only cloning path.
- Skip Kitten and Kokoro multi-lang in v1 to keep the voice list to <=5 (revisit later).
- Surface all new models in Settings via the existing ModelManager.MODELS loop.
- Surface new voice lists in the voice picker for each family.
- Gate installs behind storage/RAM checks; download on first use.

## Non-goals

- No celebrity voice packs.
- No cloud TTS or new native deps.
- No changes to the streaming player pipeline.
- No system TTS service registration in this round.

## Constraints

- Engine: sherpa-onnx AAR already in deps/sherpa.aar. All config classes present (verified).
- Storage: int8 bundles ~100-140 MB each; total ~461 MB if all installed.
- RAM: Pocket int8 ~200 MB at runtime; gate behind >=3 GB RAM.
- Offline only; models downloaded from k2-fsa/sherpa-onnx releases with pinned SHA-256.

## Approaches considered

A. Full Tier-1 in one pass.
B. Incremental by family, one chunk per family, each independently testable. Recommended.
C. Cloning-only (ZipVoice + Pocket), skip fixed-voice expansion.

Chosen: B (incremental), in order: Kokoro multi-lang -> Supertonic -> ZipVoice -> Kitten.

## Parts of the how decided

- Model entries live in ModelManager.MODELS with SHA-256 from the release digest.
- isInstalled() gets family-specific required-file checks.
- SherpaEngine.buildConfig() gets a branch per family; family string comes from Model.family.
- voices() returns family-appropriate list.
- ZipVoice cloning requires a transcript; add a text field to cloning UI only when family == zipvoice.
- Vocoder (vocos_24khz.onnx) is a separate Model entry; ZipVoice isInstalled requires it.
- Kitten uses the same shape as Kokoro (model, voices, tokens, dataDir, lengthScale).

## What is left to the builder

- Exact UI placement of transcript field and style picker.
- RAM detection heuristic thresholds.
- Test harness on emulator (maestro flows).

## Success criteria

- Build passes (./build.sh).
- App installs and launches on emulator.
- Each new model installs, loads, and synthesizes a test sentence.
- Voice picker shows the right count for each family.
- Cloning works for Pocket (existing) and ZipVoice (new, with transcript).
