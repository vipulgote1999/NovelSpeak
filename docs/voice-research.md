# Research: Best-quality voices we can add under the current engine (sherpa-onnx)

Date: 2026-10-27 · Engine: k2-fsa/sherpa-onnx (AAR already in deps/sherpa.aar)

## TL;DR

Our app currently wires 2 of the 8 families sherpa-onnx supports:

- Kokoro int8 (English, 11 fixed voices)
- Pocket TTS int8 (zero-shot cloning from a reference clip)

The sherpa-onnx AAR in deps/sherpa.aar already contains the config classes for
all families, so the following best-quality / most-realistic models are compatible
with our current engine without changing native deps — only SherpaEngine.buildConfig,
ModelManager, and the settings UI need new branches.

Recommended additions (Tier 1 — naturalness tier):

| Family | Bundle | Size (int8) | Fixed voices | Languages | Cloning | Notes |
|---|---|---|---|---|---|---|
| Supertonic 3 | sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2 | 122.8 MB | 20 (M0-M9, F0-F9) | en | no | 66M params, up to 167x real-time, OpenRAIL license, voice-style based |
| Kokoro multi-lang v1_1 | kokoro-int8-multi-lang-v1_1.tar.bz2 | 140.2 MB | 103 | zh + en | no | superset of the 11 en voices we already ship; adds 92 more speakers |
| ZipVoice (distill int8) | sherpa-onnx-zipvoice-distill-int8-zh-en-emilia.tar.bz2 | 104.1 MB | 0 fixed (zero-shot) | zh + en | yes (audio + verbatim transcript) | highest fidelity when a transcript is available |
| Pocket TTS (already wired) | sherpa-onnx-pocket-tts-int8-2026-01-26.tar.bz2 | 93.8 MB | 0 fixed (8 Kyutai stock + cloning) | en | yes (audio only) | already in app |

Tier 2 (acceptable, lighter): Kitten TTS nano (8 voices, 25-30 MB, fast but lower naturalness than Kokoro/Pocket).

Tier 3 (legacy / not recommended for realistic reading): Matcha (1 speaker), VITS/Piper (hundreds of speakers but older, more robotic quality).

## How many best quality voices we can add

Counting only Tier 1 fixed (non-cloning) voices that are new to the app:

- Supertonic 3: 20 distinct styles
- Kokoro multi-lang v1_1: 103 speakers (superset of the 11 we ship, so +92 net new)
- Kitten (Tier 2): 8 voices

Total fixed Tier-1 voices after adding Supertonic + Kokoro multi-lang: 123 distinct voices
(already-installed Kokoro en v0_19's 11 are included in the 103).

Plus unlimited cloned voices via Pocket (audio only) and ZipVoice (audio + transcript),
which is the realistic-voice route for sound like a specific narrator.

## Compatibility check (against our current sherpa-onnx AAR)

All of the following config classes exist in deps/sherpa.aar, so the JNI surface is
already present and no native rebuild is required:

- OfflineTtsSupertonicModelConfig (durationPredictor, textEncoder, vectorEstimator, vocoder, ttsJson, unicodeIndexer, voiceStyle)
- OfflineTtsZipVoiceModelConfig (tokens, encoder, decoder, vocoder, dataDir, lexicon, featScale, tShift, targetRms, guidanceScale)
- OfflineTtsKokoroModelConfig (already used)
- OfflineTtsPocketModelConfig (already used)
- OfflineTtsKittenModelConfig, OfflineTtsMatchaModelConfig, OfflineTtsVitsModelConfig

GenerationConfig is unified (speed, sid, numSteps, silenceScale, referenceAudio,
referenceSampleRate, referenceText, extra), so cloning flows already work for Pocket
and ZipVoice.

## What compatible means for each - effort to wire

1. Supertonic - new family branch in SherpaEngine.buildConfig (7 file paths),
   model id + SHA in ModelManager, voices() returns M0-M9/F0-F9 (sid index into
   voiceStyle), no espeak-ng data dir needed (unicodeIndexer instead). Medium effort.
2. Kokoro multi-lang v1_1 - same code path as Kokoro en; only file names
   (model.onnx/int8) and sid range 0-102 change, plus lexicon list (us-en, zh, etc.)
   and data dir. Small effort; biggest win (92 new voices, +Chinese).
3. ZipVoice - new family branch; needs a transcript field in the cloning UI
   (HayaiTTS pattern: audio + verbatim text). Medium effort; highest realism for
   zh/en cloning.
4. Kitten - same shape as Kokoro (model, voices, tokens, dataDir, lengthScale).
   Small effort; good low-RAM fallback.

## Realism ranking (for a novel reader)

1. ZipVoice (cloned, with transcript) - highest fidelity, zh+en.
2. Pocket TTS (cloned, audio only) - very natural, en; already in app.
3. Supertonic 3 - natural prosody, fastest, 20 styles; en only.
4. Kokoro multi-lang - 103 distinct speakers, good quality, multilingual.
5. Kokoro en v0_19 - current 11 voices (subset of above).
6. Kitten - lighter/less natural.
7. Matcha / VITS / Piper - legacy.

## Storage / RAM budget (if all Tier 1 int8 installed)

~122.8 + 140.2 + 104.1 + 93.8 = ~461 MB of model files; Pocket int8 alone needs
~200 MB RAM at runtime. Recommend gating Supertonic/ZipVoice/Kokoro-multi behind a
RAM check (>=3 GB) and download on first use, as already done.

## Licensing

- Supertonic: OpenRAIL (model weights).
- Kokoro: Apache-2.0.
- Pocket: MIT (Kyutai).
- ZipVoice: check upstream (Emilia-trained) - verify before shipping.
- sherpa-onnx + onnxruntime: Apache-2.0 / MIT.

## Recommendation

Add in this order for maximum realism per effort:

1. Kokoro multi-lang v1_1 (small effort, +92 voices, +Chinese).
2. Supertonic 3 (medium effort, 20 natural styles, fastest RTF).
3. ZipVoice distill int8 (medium effort, best cloning fidelity, zh+en).
4. Keep Pocket (already) as the audio-only cloning path.
5. Add Kitten as a low-RAM fallback only.
