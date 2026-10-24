# Neural TTS text safety (Kokoro / Pocket)

Date: 2026-10-24 · Symptom: "when we get sentences with `"` or some special chars our TTS
hallucinates or generates random continuous noise for some time".

Sample input: `my-werewolf-system` chapters 80–82 (empirenovel). Reproduction fixture:
`tools/tts-cleaner-test/sample-chapters.txt`.

## 1. Reproduction

The text-only reproduction used a standalone script that mirrored `SentenceSplitter` exactly and
fed the chapter's sentences through the real pipeline. **40 of 73** sentences reached the model
with no normalization at all. The worst four were punctuation-only chunks that the splitter
creates from stray quote/dash lines:

| Input | What the model receives today |
|---|---|
| `...` | empty phoneme sequence |
| `"` | one quote token |
| `*` | one asterisk |
| `—` | one dash |

Chapter text also produced unbalanced dialogue shells (`"What was that?` / `…any Altered I ever
saw!”`), `*Ahh-wooo!!!*`, censored words (`sh*t`, `f*ck`), and nested single quotes
(`‘He keeps calling them ‘Werewolves’…’`).

## 2. Root cause

`sherpa-onnx` was given raw extracted text: `WebExtractor` → `SentenceSplitter` (only collapses
NBSP/whitespace) → `SherpaEngine.generateWithConfigAndCallback(task.text)`. There was no
sanitization, no empty/punct-only guard, and no output cap.

Upstream evidence that this is the *expected* failure mode of these frontends, not a NovelSpeak
bug alone:

* **hexgrad/kokoro#365** — "Punctuation tokens book real duration and fill it with audible
  signal (voiced junk after final punctuation…)" — describes "a breathy/static tail glued to
  every exclamation or quoted-dialogue boundary… recurs at nearly every dialogue boundary
  (`."` and `!"` endings)". Quote glyphs are tokens with booked frames, i.e. audible junk.
* **kokoro#365, finding 2** — the *leading* quote token also carries the next word's onset.
  Gating/trimming those frames afterwards produces clicks and 30–40 ms holes. The correct fix is
  to remove quote glyphs from the **text before synthesis**, never to trim them out of the audio.
* **hexgrad/kokoro#325** — hard 510-phoneme limit; manual chunking is the supported workaround.
* **k2-fsa/sherpa-onnx#3176** — upstream Pocket-TTS returns "inaudible and weird sounds" on very
  short inputs ("Hello World") while long text works; reproduced without sherpa, so it is a model
  property.
* **Kokoro isolated-word reports** (explainx/HN): `"six"` can come back as `"ah-six-ah"`.
* **k2-fsa/sherpa-onnx#3754** — int8 Kokoro on Android/ARM can intermittently return a whole
  sentence pinned at ~+1.0 (fp32 unaffected). Separate from text, but produces the same
  "continuous noise" symptom.
* TTS vendor guidance (Sestek, ElevenLabs) — prefer punctuation that supports prosody over
  display-style quoting; remove unnecessary quotation marks; too many pause tags make output
  unstable/noisy.

## 3. Fix

`TtsTextCleaner.cleanForNeural()` (`app/src/app/novelspeak/TtsTextCleaner.kt`) runs in
`SherpaEngine.generationLoop()` before every generation. It changes presentation only — words,
casing, contractions, numbers, and sentence punctuation are preserved:

| Step | Result |
|---|---|
| NFKC + strip control/format/zero-width | fullwidth/ligature/zero-width junk gone |
| `“ ” ‘ ’ „ « » \`` unified | then **all quote glyphs removed** (kokoro#365) |
| apostrophe kept only inside a word | `don't` stays, `wolves’` loses the stray mark |
| `…` / `...` / `—` / `–` / `--` → comma pause | `There are… two of them!` → `There are, two of them!` |
| `* _ ~ ^ \|` deleted | `sh*t` → `sht`, `*Ahh-wooo!!!*` → `Ahh-wooo!` |
| symbols → space, `&` → "and" | words never fuse (`and/or` ≠ `andor`) |
| `!!!` / `?!` runs collapsed | one terminal mark |
| residuals like `ma.s.s` collapsed | `mass` (defense in depth) |
| punctuation-only → `null` | engine **skips** the sentence and advances |
| terminal punctuation guaranteed | model sees EOS |

Two supporting changes:

1. **Short-fragment merge** (`SentenceSplitter.mergeGroups`, used by `WebExtractor`): fragments
   under 24 chars are joined with a neighbour, capped at `maxSentenceChars`. Novel dialogue
   produces many isolated `“Gary!”` / `“Tom!”` fragments, which is exactly the short-input regime
   where Kokoro and Pocket misbehave. Blocks with no letters/digits are dropped. The merged block
   keeps the first fragment's `data-ns` index, so read-along highlight still works.
2. **Saturation guard + 45 s cap** (`SherpaEngine.SampleSink`): a chunk with >50 % of samples at
   ±0.995 is the int8 saturation artifact, not speech (normal output peaks transiently ~0.35). It
   is judged **before** writing, so during pre-buffering it never reaches the AudioTrack; the
   sentence is skipped and logged. The cap stops LM-EOS failure from babbling indefinitely.

## 4. Deliberately not done (quality protection)

* **No audio-domain gating/trimming of punctuation frames.** kokoro#365 shows this amputates word
  onsets and clicks. Fix the text, not the waveform.
* **No rewording, lowercasing, number spelling, or abbreviation expansion.** Those change what is
  read and can regress prosody; the models already handle `Tier-3`, `999`, `3.5`, `U.S.A.`.
* **No SSML / stage cues.** Kokoro has no SSML parser and reads bracketed cues as phonemes.
* **No blanket punctuation stripping.** `. , ! ? ; :` are prosody and are preserved; only glyphs
  that are not spoken (quotes, asterisks, brackets) are removed.

## 5. Verification

Host harness (no Android needed):

```bash
kotlinc app/src/app/novelspeak/SentenceSplitter.kt app/src/app/novelspeak/TtsTextCleaner.kt \
  tools/tts-cleaner-test/TestMain.kt -include-runtime -d /tmp/tts-cleaner-test.jar
java -jar /tmp/tts-cleaner-test.jar
```

It asserts: punct-only → skip, quote/asterisk/ellipsis removal, ellipsis/dash → pause, censored
words, nested quotes, dotted obfuscation, acronym/decimal preservation, terminal punctuation, and
a full chapter-80–82 fixture run (no risky character survives, no empty output, cap respected).

On-device check after installing:

* `/sdcard/Download/novelspeak-log.txt` should show `tts clean "…" -> "…"` for dialogue lines and
  `tts skip unspeakable` for `...` / stray-quote lines.
* No `gen done … frames=` line should exceed ~1,080,000 frames (45 s). `CAPPED` or
  `tts garbage (rail-pinned)` indicates a remaining problem; send the preceding line.

## 6. Remaining risks

* A chapter that ends on a one-word fragment can still synthesize it alone (merge cannot cross an
  extract boundary). If it shows up, lower `MIN_FRAGMENT_CHARS` or merge at chapter append.
* If `rail-pinned` skips repeat, the device is hitting the int8 path; the options are more threads,
  the fp32 Kokoro bundle, or Pocket (different quantisation).
* Text normalization cannot fix a bad speaker embedding from a noisy Pocket reference clip; that
  remains a separate quality axis (`WavReader` cleanup chain).
