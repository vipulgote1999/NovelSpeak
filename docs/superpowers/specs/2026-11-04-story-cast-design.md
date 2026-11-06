# Story Cast — Multi-Voice Storytelling Design

**Date:** 2026-11-04
**Status:** Approved approach; implementation in progress on `feat/story-cast`

## Intent

Turn a chapter into a cast performance — a narrator plus distinct character voices — without
rewriting the author's words. It must work on arbitrary web chapters and imported books, preserve
the existing read-along highlight, and stay useful offline. This is the feature that turns
NovelSpeak from "a good voice reading a page" into "a story being performed".

## Why this, why now

- Éist proves the on-device foundation: Flutter + sherpa-onnx, Kokoro/Piper/Supertonic, separate
  SHA-256-checked model downloads, offline-first playback, optional Cloud Assist.
- AudiFlo and Folio advertise character voices; BookNPC sells full-cast analysis; Lyraboo sells
  cloud auto-casting. None combines arbitrary web extraction, offline neural voices, automatic
  casting, no account and no meter.
- NovelSpeak already owns the hard parts: generic extraction, library/resume, sentence queue,
  prefetch/preroll, screen-off playback, two TTS engines, and an ad blocker.

## Goals

1. Split a chapter into narration and dialogue, attribute each dialogue span to a character, and
   report a confidence for every attribution.
2. Persist a per-book cast (names, aliases, gender, voice, overrides) and merge new chapters into it.
3. Assign distinct on-device voices, with a separate narrator voice, and avoid duplicate voices in
   the same scene.
4. Switch voices per segment inside the existing queue without reloading a model.
5. Keep the author's exact text and the existing block/character-offset highlight mapping.
6. Work offline by default; allow an optional cloud LLM only for low-confidence spans.
7. Give the user a Cast sheet: roster, real-line preview, recast, fix-speaker, narrator-only mode.

## Non-goals

- Rewriting prose into a screenplay or generating new dialogue.
- Cloud TTS for user chapters.
- Per-character voice cloning in v1 (Pocket/ZipVoice can come later).
- Non-English casting in v1 (narrator-only remains available in every language).
- Series-level memory across books (later; the data model leaves room for it).
- Audio export changes.

## Constraints

- **Supertonic 3 is the primary cast engine.** Its `voice.bin` contains 10 speakers. Verified by
  decoding the header and matching every style vector against the official Supertonic 3 JSONs with
  zero error:
  - `sid 0..4` = `F1..F5`
  - `sid 5..9` = `M1..M5`
  The current app labels `sid 1` as "M1"; that is wrong — it is `F2`. Fix this first.
- **Kokoro is the quality option, not the default cast engine.** 11 voices in one model, but
  measured RTF is 1.17 on the iQOO and 3.48 in Éist's Android cohort. Use it for the narrator or
  pre-rendered chapters on capable devices.
- **Piper is a fallback.** Fastest, but one voice per model; do not load multiple Piper models for
  a cast.
- **System TTS is the instant/ low-end fallback.** Per-utterance voice switching is supported by
  the existing `synthesizeToFile` path.
- No shared API key may ship in the APK. Cloud analysis is BYO-key or a server-side proxy.
- 4 threads by default; 8 threads is slower and hotter on the reference phone.
- Plain Kotlin, no Gradle/new dependency; host tests are standalone `kotlin` mains.

## Approaches considered

### A. Cloud-first (LLM attribution + cloud TTS)
Best raw quality and simplest on-device code. Rejected: it breaks the offline/no-account promise,
adds per-book cost, makes the key extractable, and turns NovelSpeak into a worse Lyraboo.

### B. Fully offline heuristics only
Zero marginal cost, no privacy risk, works on any device. Rejected as the only path because long
unattributed exchanges are the hard case; a heuristic alone will guess wrong and erode trust.

### C. Hybrid — offline default, cloud only for uncertainty (chosen)
- Deterministic parser handles explicit tags, alternating dialogue, pronouns, aliases.
- Optional cloud LLM reviews only low-confidence spans, cached per chapter.
- Supertonic 10 voices provide the cast at ~zero marginal cost.
- Kokoro is available when the user prioritizes voice quality over speed.
This keeps the brand promise, keeps cost near zero, and reserves cloud for the 10–20% of lines
where it actually improves the result.

## Architecture

```
WebExtractor / ImportedBook
        │ blocks
        ▼
DialogueParser (offline) ──► CastAnalysis (segments + cast deltas + confidence)
        │                         │
        │ optional low-confidence └──► CloudCastAnalyzer (opt-in, cached)
        ▼
CastTracker + CastStore (per-book cast, overrides)
        ▼
VoiceCastPlanner (narrator + character voices)
        ▼
Reader queue (SpeechSegment: text, block, char offsets, speaker, voiceId)
        ▼
TtsEngine.speak(text, voiceId) ──► SherpaEngine.Task(voiceId) / SystemTtsEngine.Task(voiceId)
        ▼
PcmPlayer (one continuous stream; no model reload between segments)
```

### Data model

- `CastMember`: stable id, display name, aliases, gender, age hint, voiceId, confidence,
  first/last chapter, notes, `userLocked`.
- `BookCast`: book key, title, narratorVoiceId, members, schema version.
- `SpeechSegment`: exact text, blockIndex, charStart/charEnd, type (narration/dialogue),
  speakerId, voiceId, confidence.
- `CastAnalysis`: segments, new/updated cast members, unresolved spans.

### Parser

1. Normalize block text without changing offsets (quotes/dashes are kept).
2. Find quote spans using language-aware pairs (`"..."`, `“...”`, `‘...’`, `«...»`, `「...」`,
   em-dash dialogue). Apostrophes inside words are not quotes.
3. Find speech tags in the surrounding narration: `said X`, `X said`, `replied`, `asked`, `cried`,
   `whispered`, `muttered`, `shouted`, `answered`, `returned`, `continued`, `murmured`, `snapped`,
   `growled`, `sighed`, `laughed`, `called`, `told`.
4. Resolve names against the cast and aliases; resolve pronouns (`he/she/they`) by gender and
   recency; use alternating speakers for untagged exchanges.
5. Emit `UNKNOWN` rather than guessing when evidence is weak. Confidence is `HIGH` for explicit
   tags, `MEDIUM` for alternation/pronoun, `LOW` for unresolved.
6. Preserve every non-whitespace character exactly once so the reader can still highlight.

### Voice planning

- Narrator = the user's current voice.
- Characters are ranked by dialogue count. Main characters get distinct voices; minor characters
  share or stay narrator.
- Gender match when known; avoid a voice already active in the scene; never assign the narrator's
  voice to a character unless the cast exceeds the available voices.
- Supertonic: 9 character slots (10 total minus narrator). Kokoro: 10 slots (11 minus narrator).
- Manual override always wins and is persisted.

### Engine contract

- `TtsEngine.speak(text, onDone, onRendered, voiceId: String?)`.
- `Reader.Block` carries `voiceId`, `speakerId`, `charStart`, `charEnd` (defaults keep old callers).
- `SherpaEngine.Task` carries `voiceId`; the generation loop reads it, not a shared mutable field.
  This is required because the reader queues 4–8 sentences ahead.
- `SystemTtsEngine.Task` carries `voiceId`; `applyVoiceAndRate` selects it before each
  `synthesizeToFile`.
- No engine reload on voice change for Supertonic/Kokoro (same model, different `sid`).

### UI

- Cast button in the player card.
- Cast sheet: narrator row, character rows with voice, real-line preview, recast, confidence badge.
- Current speaker label under the player status; current line highlighted in the character's color.
- One-tap "Fix speaker" from the reader; updates that line and optionally the rest of the chapter.
- "Narrator only" toggle; "Cast this chapter" action; progress while analyzing.

## Privacy and security

- Device-only is the default; no text leaves the device.
- Cloud analysis is opt-in per book, clearly labelled, and sends only the low-confidence spans
  (plus the cast names needed to resolve them), never the whole book.
- No shared router key in the APK. Release builds use BYO key or a proxy with per-install limits.
- Cache analysis locally; no account, no telemetry, no retention assumptions.

## Performance and cost

- Supertonic: one 123 MB int8 model, ~362 MB RSS, 0.78 RTF on the reference phone. Voice switches
  are `sid` changes, not model loads.
- Analyze the current chapter on open; prefetch the next in the background; cache segments and
  rendered audio.
- LLM only for low-confidence spans; `gpt-oss-120b` with `reasoning_effort=low` did a 20-segment
  chapter in 2.5 s / 856 tokens. The router's 8k tokens/min is a prototyping quota, not a
  production backend.
- No cloud TTS: BookNPC's 25¢/finished audio-hour and Lyraboo's subscription are the costs the
  on-device path avoids.

## Evaluation

Fixtures:
- Explicit tags: *Pride and Prejudice* chapter 1.
- Unattributed, pronoun-heavy scene (the Marshal Rennick scene) — the hard case.
- Aliases ("the woman in the grey coat"), multi-quote paragraphs, interrupted quotes.
- Non-English: narrator-only fallback must not break.

Metrics:
- Attribution accuracy before correction (explicit ≥90%, unattributed ≥70%).
- Correction rate and time-to-fix.
- Voice-switch overhead and first-audio latency; no model reload log line.
- Playback regressions: existing feature/library/preroll tests stay green.

## What is left to the builder

- Exact parser regexes and edge cases; expand fixtures as real chapters are tested.
- Persistence format (simple line/JSON-compatible; host-testable).
- Cast sheet layout and color palette.
- LLM prompt/schema and provider fallback; keep it behind an interface.
- Whether Kokoro quality mode is user-selectable per book or only a global engine choice.
