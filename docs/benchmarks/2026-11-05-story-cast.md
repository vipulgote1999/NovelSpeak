# Story Cast — first on-device verification (2026-11-05)

**Scope:** MedTrail_API35 x86_64 emulator, Android 15 (API 35), NovelSpeak debug build,
Supertonic 3 int8, 4 threads. Local chapter `tools/test-page/story.html` served at
`http://10.0.2.2:8000/story.html`.

**What was exercised:** open the chapter, tap **Read**, tap **Play**, long-press **Cast** to
inspect the roster.

## Observed

```
cast chapter=3 speakable unresolved=2 segments=19
tts cast voice family=supertonic sid=5 requested=5
tts cast voice family=supertonic sid=1 requested=1
tts cast voice family=supertonic sid=6 requested=6
```

Cast sheet:

```
Marshal Rennick · M1 (male, US)
Mara · F2 (female, US)
Tomas · M2 (male, US)
```

- Three distinct Supertonic speakers were synthesised in one continuous playback session.
- `sid 5 = M1`, `sid 1 = F2`, `sid 6 = M2`; this matches the verified model order
  (`0..4 = F1..F5`, `5..9 = M1..M5`).
- The two remaining `unresolved` lines are the genuinely unattributable
  `"He's right here."` (a third voice) and `"Mine,"` (a voice from the yard). They are kept in the
  narrator path and counted for review, not silently given a wrong character.

## Host checks

| Harness | Checks |
|---|---:|
| `tools/library-test` | 211 |
| `tools/feature-test` | 130 |
| `tools/cast-parser-test` | 34 |
| `tools/cast-store-test` | 26 |
| `tools/voice-catalog-test` | 18 |
| `tools/cast-voice-test` | 7 |
| **Total** | **426** |

The parser fixtures cover *Pride and Prejudice* chapter 1 (explicit tags) and the unattributed
Rennick scene (no tags, aliases, a third voice). The auto-discovery case starts with **no
pre-seeded cast** and must find Rennick, Mara and Tomas from tags, vocatives and descriptions.

## Limits

- This is an emulator smoke test, not a phone performance benchmark. No RTF, battery, thermal or
  first-audio claim is made here.
- The parser now receives paragraph-level text (so a quote with an internal period stays one turn)
  and then re-splits cast segments into sentence-sized TTS chunks. Imported books whose block
  index is per sentence (plain text/PDF) still parse one sentence at a time.
- Two unresolved lines are expected in this scene; the UI exposes the count but the current
  cast sheet does not yet offer per-line correction.
