# Story Cast Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a narrator + character cast to NovelSpeak that performs the author's exact text with distinct on-device voices, offline by default.

**Architecture:** A deterministic dialogue parser and per-book cast feed a voice planner; the existing `Reader` queue carries a `voiceId` per segment, and both TTS engines switch voices without reloading a model. An optional cloud LLM reviews only low-confidence spans.

**Tech Stack:** Kotlin, Android `Activity`/`WebView`, sherpa-onnx, existing `TtsEngine`/`Reader`/`PcmPlayer` pipeline, standalone Kotlin test mains (no Gradle).

**Spec:** `docs/superpowers/specs/2026-11-04-story-cast-design.md`

## Global Constraints

- Supertonic 3 `sid` mapping is fixed and verified: `0..4 = F1..F5`, `5..9 = M1..M5`.
- The app's current `sid 1 = M1` label is wrong; correct it to `F2`.
- No shared API key in the APK; cloud analysis is BYO-key or proxy.
- Keep the author's text exactly; never rewrite or generate dialogue.
- No new Gradle/dependency; plain Kotlin and the existing `build.sh`.
- 4 threads default; no 8-thread recommendation.
- Preserve existing playback, library, and preroll behavior.

## Review Focus

- A dialogue line with no speech tag: must be marked `UNKNOWN` or low confidence rather than silently given a wrong voice.
- A character with an alias ("the woman in the grey coat") must resolve to the same cast member.
- A chapter with more characters than available voices: minor characters must share without stealing the narrator's voice.
- Voice changes while 4–8 sentences are queued: every sentence must use the voice it was queued with.
- A malformed/unsupported chapter: narrator-only playback must still work.

---

### Task 1: Voice catalog and correct Supertonic styles

**Files:**
- Create: `app/src/app/novelspeak/VoiceCatalog.kt`
- Modify: `app/src/app/novelspeak/Prefs.kt`
- Modify: `app/src/app/novelspeak/SherpaEngine.kt`
- Modify: `app/src/app/novelspeak/SettingsActivity.kt`
- Modify: `app/src/app/novelspeak/ModelManager.kt`
- Test: `tools/voice-catalog-test/TestMain.kt`

**Interfaces:**
- Produces: `data class VoiceOption(val id: String, val label: String, val family: String, val gender: String, val accent: String, val tier: String)`
- Produces: `object VoiceCatalog { fun forFamily(family: String): List<VoiceOption>; fun label(family: String, id: String): String; val SUPERTONIC: List<VoiceOption>; val KOKORO: List<VoiceOption> }`

- [ ] **Step 1: Write the failing test**

```kotlin
// tools/voice-catalog-test/TestMain.kt
import app.novelspeak.VoiceCatalog

fun main() {
    var failures = 0
    fun check(name: String, ok: Boolean, detail: String = "") {
        if (ok) println("PASS $name") else { failures++; println("FAILED $name $detail") }
    }
    val st = VoiceCatalog.SUPERTONIC
    check("supertonic has 10 styles", st.size == 10, "size=${st.size}")
    check("first is F1 female", st[0].id == "0" && st[0].label.startsWith("F1") && st[0].gender == "female")
    check("second is F2 female", st[1].id == "1" && st[1].label.startsWith("F2"))
    check("sixth is M1 male", st[5].id == "5" && st[5].label.startsWith("M1") && st[5].gender == "male")
    check("last is M5 male", st[9].id == "9" && st[9].label.startsWith("M5"))
    check("kokoro has 11 voices", VoiceCatalog.KOKORO.size == 11)
    check("label resolves F2", VoiceCatalog.label("supertonic", "1").startsWith("F2"))
    if (failures > 0) kotlin.system.exitProcess(1)
}
```

- [ ] **Step 2: Run it to verify it fails**

Run:
```bash
kotlinc app/src/app/novelspeak/VoiceCatalog.kt tools/voice-catalog-test/TestMain.kt -include-runtime -d /tmp/voice-catalog-test.jar && java -jar /tmp/voice-catalog-test.jar
```
Expected: fails to compile because `VoiceCatalog.kt` does not exist.

- [ ] **Step 3: Implement `VoiceCatalog`**

Create the object with the verified Supertonic order:
```kotlin
val SUPERTONIC = listOf(
    VoiceOption("0", "F1 (female)", "supertonic", "female", "US", "primary"),
    VoiceOption("1", "F2 (female)", "supertonic", "female", "US", "primary"),
    VoiceOption("2", "F3 (female)", "supertonic", "female", "US", "primary"),
    VoiceOption("3", "F4 (female)", "supertonic", "female", "US", "primary"),
    VoiceOption("4", "F5 (female)", "supertonic", "female", "US", "primary"),
    VoiceOption("5", "M1 (male)", "supertonic", "male", "US", "primary"),
    VoiceOption("6", "M2 (male)", "supertonic", "male", "US", "primary"),
    VoiceOption("7", "M3 (male)", "supertonic", "male", "US", "primary"),
    VoiceOption("8", "M4 (male)", "supertonic", "male", "US", "primary"),
    VoiceOption("9", "M5 (male)", "supertonic", "male", "US", "primary")
)
```
Add Kokoro with ids `"0".."10"` matching `Prefs.KOKORO_VOICES` order and the labels already used by
the app (`Heart (US female)`, `Adam (US male)`, etc.). `forFamily` returns the right list; `label`
falls back to `id`.

- [ ] **Step 4: Wire it in**

- `Prefs`: keep `KOKORO_CURATED`/`KOKORO_VOICES` for compatibility, add a comment that Supertonic now
  exposes all 10 via `VoiceCatalog`.
- `SherpaEngine.voices()`: `supertonic -> VoiceCatalog.SUPERTONIC.map { it.id to it.label }`.
- `SherpaEngine.currentVoiceLabel()`: `supertonic -> "Supertonic · " + VoiceCatalog.label("supertonic", prefs.supertonicStyle.toString())`.
- `SettingsActivity.voicesForModel` and `currentVoiceLabelText`: use the same catalog.
- `ModelManager` Supertonic label: "Supertonic 3 int8 (English, 10 styles)".

- [ ] **Step 5: Run the test**

Run the command from Step 2. Expected: PASS for all checks, exit 0.

- [ ] **Step 6: Commit**

```bash
git add app/src/app/novelspeak/VoiceCatalog.kt app/src/app/novelspeak/Prefs.kt \
  app/src/app/novelspeak/SherpaEngine.kt app/src/app/novelspeak/SettingsActivity.kt \
  app/src/app/novelspeak/ModelManager.kt tools/voice-catalog-test/TestMain.kt
git commit -m "feat(cast): expose all 10 Supertonic voices and fix sid mapping"
```

---

### Task 2: Per-segment voice contract

**Files:**
- Modify: `app/src/app/novelspeak/TtsEngine.kt`
- Modify: `app/src/app/novelspeak/Reader.kt`
- Modify: `app/src/app/novelspeak/SherpaEngine.kt`
- Modify: `app/src/app/novelspeak/SystemTtsEngine.kt`
- Test: `tools/cast-voice-test/TestMain.kt` (pure model/queue test if extracted; otherwise compile check)

**Interfaces:**
- Produces: `TtsEngine.speak(text: String, onDone: (Boolean) -> Unit, onRendered: ((Boolean) -> Unit)? = null, voiceId: String? = null)`
- Produces: `Reader.Block(text, blockIndex, voiceId: String? = null, speakerId: String? = null, charStart: Int = -1, charEnd: Int = -1)`
- Produces: `Reader.currentSpeaker(): String` and `Reader.currentVoiceId(): String?` for the player card.

- [ ] **Step 1: Write a failing test for the pure `CastVoicePlan`/queue contract**

Use a tiny fake engine in `tools/cast-voice-test/TestMain.kt` that records the `voiceId` passed to
`speak`. Feed three `Reader.Block`s with different voices and assert the fake saw `F1`, `M1`, `F3`
in order. If `Reader` is too Android-bound for a host test, extract the queue-voice selection into a
pure `VoicePlan` and test that instead.

- [ ] **Step 2: Run it to verify it fails**

Run:
```bash
kotlinc app/src/app/novelspeak/Reader.kt tools/cast-voice-test/TestMain.kt -include-runtime -d /tmp/cast-voice-test.jar
```
Expected: compile failure or missing `voiceId`.

- [ ] **Step 3: Add the parameter and thread it through**

- `TtsEngine.speak`: add `voiceId: String? = null` last.
- `Reader.Block`: add the four fields with defaults; update `queueSentence(index)` to pass
  `sentences[index].voiceId`.
- `SherpaEngine.Task`: add `voiceId: String?`; `speak` creates `Task(..., voiceId)`; the generation
  loop chooses `val sid = task.voiceId?.toIntOrNull() ?: ...` instead of reading the shared
  `voiceId` field. Keep `setVoice` for the global narrator/preview.
- `SystemTtsEngine.Task`: add `voiceId: String?`; `applyVoiceAndRate(voiceId)` selects the requested
  voice for that utterance, falling back to the saved global voice.
- `Reader.State`: keep the existing `voice` (engine label) and add `speaker: String` and
  `segmentVoice: String` (default `""`) so the player card can show "Mrs. Bennet · Bella" later.

- [ ] **Step 4: Run the test and the existing feature test**

```bash
kotlinc app/src/app/novelspeak/Reader.kt tools/cast-voice-test/TestMain.kt -include-runtime -d /tmp/cast-voice-test.jar && java -jar /tmp/cast-voice-test.jar
```
Expected: PASS.

- [ ] **Step 5: Compile the app**

Run `./build.sh` (or the no-device compile path used by the repo). Expected: `kotlinc` completes and
the APK is produced.

- [ ] **Step 6: Commit**

```bash
git add app/src/app/novelspeak/TtsEngine.kt app/src/app/novelspeak/Reader.kt \
  app/src/app/novelspeak/SherpaEngine.kt app/src/app/novelspeak/SystemTtsEngine.kt \
  tools/cast-voice-test/TestMain.kt
git commit -m "feat(cast): carry a voice id per queued sentence"
```

---

### Task 3: Cast model and deterministic dialogue parser

**Files:**
- Create: `app/src/app/novelspeak/cast/CastModels.kt`
- Create: `app/src/app/novelspeak/cast/DialogueParser.kt`
- Test: `tools/cast-parser-test/TestMain.kt`
- Fixture: `tools/cast-parser-test/fixtures/pride-and-prejudice-ch1.txt`
- Fixture: `tools/cast-parser-test/fixtures/unattributed-scene.txt`

**Interfaces:**
- Produces: `data class SpeechSegment(val text: String, val blockIndex: Int, val charStart: Int, val charEnd: Int, val type: SegmentType, val speakerId: String, val confidence: Confidence)`
- Produces: `data class CastMember(val id: String, val name: String, val aliases: List<String>, val gender: Gender, val voiceId: String?)`
- Produces: `data class CastAnalysis(val segments: List<SpeechSegment>, val members: List<CastMember>, val unresolved: Int)`
- Produces: `object DialogueParser { fun analyze(blocks: List<CastBlock>, existing: List<CastMember> = emptyList()): CastAnalysis }`

- [ ] **Step 1: Write the failing parser tests**

Test at least:
1. *Pride and Prejudice* ch.1: 20 segments; `Mrs. Bennet` and `Mr. Bennet` are the only dialogue
   speakers; no `UNKNOWN`.
2. The Marshal Rennick scene: `Rennick`, `Mara`, `Tomas` resolved for the explicit/alternating
   lines; the unattributed `"He's right here."` is `UNKNOWN` or Tomas with `LOW` confidence — never
   silently assigned to Mara.
3. Every non-whitespace character in the input appears exactly once across the segments.
4. Aliases: "the woman in the grey coat" resolves to `Mara`.

- [ ] **Step 2: Run the tests to verify they fail**

```bash
kotlinc app/src/app/novelspeak/cast/CastModels.kt app/src/app/novelspeak/cast/DialogueParser.kt \
  tools/cast-parser-test/TestMain.kt -include-runtime -d /tmp/cast-parser-test.jar && java -jar /tmp/cast-parser-test.jar
```
Expected: compile failure (missing files) or FAILED checks.

- [ ] **Step 3: Implement the parser**

- Normalize nothing that changes offsets; keep the original text.
- Find quote spans for `"..."`, `“...”`, `‘...’`, `«...»`, `「...」`, and em-dash dialogue.
- Find tags in the surrounding narration with the verb list from the design.
- Resolve names/aliases/pronouns; alternate untagged quotes between the last two speakers.
- Emit `UNKNOWN` + `LOW` when evidence is weak.
- Keep narration spans as `NARRATOR`.
- Assert coverage in the test: concatenating segment text (with whitespace ignored) equals the input.

- [ ] **Step 4: Run the tests to verify they pass**

Run the command from Step 2. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/app/novelspeak/cast/CastModels.kt app/src/app/novelspeak/cast/DialogueParser.kt \
  tools/cast-parser-test/
git commit -m "feat(cast): deterministic dialogue segmentation and speaker attribution"
```

---

### Task 4: Cast store and voice planner

**Files:**
- Create: `app/src/app/novelspeak/cast/CastStore.kt`
- Create: `app/src/app/novelspeak/cast/VoiceCastPlanner.kt`
- Modify: `app/src/app/novelspeak/library/LibraryStore.kt` only if a shared storage key is needed.
- Test: `tools/cast-store-test/TestMain.kt`

**Interfaces:**
- `CastStore(storage: LibraryStorage)` with `load(bookKey): BookCast?`, `save(book)`, `merge(bookKey, analysis)`.
- `VoiceCastPlanner.plan(cast, narratorVoiceId, availableVoices): Map<String, String>`.

- [ ] **Step 1: Write failing store/planner tests** — round-trip, merge aliases, manual override wins,
  narrator is never assigned to a character, >9 characters reuse voices.
- [ ] **Step 2: Run to fail.**
- [ ] **Step 3: Implement** a line-based store compatible with `LibraryStorage` (same pattern as
  `LibraryStore`) and the planner.
- [ ] **Step 4: Run to pass.**
- [ ] **Step 5: Commit.**

---

### Task 5: Reader integration and read-along

**Files:**
- Modify: `app/src/app/novelspeak/MainActivity.kt`
- Modify: `app/src/app/novelspeak/WebExtractor.kt`
- Modify: `app/src/app/novelspeak/Reader.kt`

- [ ] **Step 1:** After extraction, run `DialogueParser.analyze` and build `Reader.Block`s with
  `voiceId`, `speakerId`, `charStart`, `charEnd`.
- [ ] **Step 2:** In `onHighlight`, call `WebExtractor.highlightWord` with the segment offsets for
  dialogue, and `highlight` for narration. Add a `speaker` label to the player card.
- [ ] **Step 3:** Add a "Narrator only" fallback: if parsing fails, keep the current single-voice
  queue unchanged.
- [ ] **Step 4:** Compile and run the parser/feature tests.
- [ ] **Step 5:** Commit.

---

### Task 6: Cast sheet UI

**Files:**
- Modify: `app/src/app/novelspeak/MainActivity.kt`
- Create: `app/src/app/novelspeak/CastSheet.kt` (or a dialog inside MainActivity if that stays small)

- [ ] **Step 1:** Add a Cast button to the player card.
- [ ] **Step 2:** Show narrator + character rows, voice labels, confidence badges, and a preview
  button that speaks the character's real line.
- [ ] **Step 3:** Recast cycles through available voices; persist; apply to queued segments.
- [ ] **Step 4:** "Fix speaker" from the current line opens the cast picker and updates that segment.
- [ ] **Step 5:** Compile, test, commit.

---

### Task 7: Optional cloud LLM analyzer

**Files:**
- Create: `app/src/app/novelspeak/cast/CloudCastAnalyzer.kt`
- Create: `app/src/app/novelspeak/cast/CastAnalyzer.kt`
- Modify: `app/src/app/novelspeak/Prefs.kt`
- Test: `tools/cast-llm-test/TestMain.kt` with a fake transport.

- [ ] **Step 1:** Define the `CastAnalyzer` interface and the JSON schema/offset contract.
- [ ] **Step 2:** Implement the heuristic analyzer as the default and the cloud analyzer behind an
  opt-in pref; send only low-confidence spans; cache by chapter hash + cast version.
- [ ] **Step 3:** Add a deterministic fake-transport test: valid JSON, invalid JSON, timeout,
  wrong-speaker response.
- [ ] **Step 4:** Do not ship a key; read BYO key/proxy from settings.
- [ ] **Step 5:** Commit.

---

### Task 8: Evaluation and docs

- [ ] Add the fixtures and a small accuracy report to `docs/benchmarks/`.
- [ ] Measure: first audio, RTF, voice-switch overhead, attribution accuracy, correction rate.
- [ ] Update `README.md` and `docs/` with the feature, privacy note, and limitations.
- [ ] Run the full test suite and `./build.sh`.
- [ ] Commit.
