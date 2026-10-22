# Reader/TTS fixes — 2026-10-22

This change follows the reader/settings redesign on `main`. Three independent defects
were making playback feel broken; the adblock integration itself did not cause them
(see the merge audit below).

## 1. sherpa callback was inverted (primary audio bug)

`sherpa-onnx`'s offline TTS callback contract is **`1 = continue generating`**,
**`0 = stop`** (`offline-tts.h`; the JNI `CallCallback` returns the Java `Int` as
`should_continue`). `SampleSink` had it backwards: a successful write returned `0`
(stop), and a stop/error returned `1` (continue).

Because Kokoro emits one callback per sentence/batch, nearly every generation ended
after the first 15-frame chunk: **28,800 samples = 1.2 s at 24 kHz**. That is why
sentences ended mid-phrase at a similar duration rather than at a text boundary.

Fix in `SherpaEngine.kt`: return `1` after `player.write(...)` succeeds, `0` when
stopping or when the write is rejected. The stop flag now also returns `0`, so a
seek/stop actually cancels an in-flight generation.

### Evidence

Legacy diagnostic log analysis: 238 of 269 generations ended at exactly 28,800
samples (1.2 s). After the fix, the same chapter produced:

```
13:43:42 gen done "Chapter 67: Who is he?" in 3789ms frames=79975
13:43:56 gen done "The teaching faculty provide" ... frames=385360
13:44:08 gen done "Although none of them had ou" ... frames=634086
13:44:20 gen done "The only thing they couldn't" ... frames=865829
13:44:37 gen done "He came running in, pushed I" ... frames=1185131
```

`frames` is the cumulative `writtenFrames`; the first 3.3 s sentence alone emitted
80,002 samples, and playback continued through the chapter.

## 2. Duplicate `onPageFinished` rewound the reader

Ad-driven reloads/navigations can fire `onPageFinished` again for the same document.
`MainActivity` re-extracted unconditionally, and `Reader.setContent()` calls `stop()`
and resets `cursor = 0`. Playback appeared to restart at the top for no reason.

`MainActivity` now tracks `lastExtractedUrl` / `lastExtractedBlocks`:

* a same-URL finish while playing does **not** re-extract;
* a same-URL finish with a weak extraction (challenge page, ≤10 blocks) and not
  playing retries once the real content arrives;
* the skip path reinstalls the page-side extractor and reader mode, and refreshes
  `data-ns` marks with `WebExtractor.install/extract`, so highlight and next-link
  still work without touching native playback.

Verified on the iqoo: reloading the chapter during playback left
`extract url=…` count unchanged (`before=2 after=2`), audio kept advancing, and
CDP after the reload reported `hasExtract: true`, `hasAdblock: true`, 39 blocks.

## 3. Extraction noise and hidden text

* **Dotted obfuscation** — the site DOM contains `cla.s.sroom`, `b.a.s.t.a.r.d`,
  `n.o.body`. `extract.js` now collapses tokens made of three or more
  letter/dot segments (`cla.s.sroom → classroom`) before speaking. `e.g.`,
  `i.e.`, `U.S.A.` and decimals are left alone.
* **Hidden nodes** — `innerText || textContent` was falling back to raw text for
  `display:none` subtrees. `collect()` now requires a visible element
  (`display`, `visibility`, `opacity`, `getClientRects`) and never falls back to
  `textContent` for page blocks. The Readability path is the exception: its
  article holder is intentionally `display:none`, so that call passes
  `useTextContent=true`.
* **Short repeats** — dedupe now applies only to blocks longer than 80 chars,
  so repeated short dialogue lines survive.

CDP `window.__nsExtract()` on the live chapter returned 39 blocks, 8,172 chars,
`dotted: []`, `classroom: true`, `bastard: true`, `hasNavNoise: false`.

## 4. Sentences cut at commas/words

`SentenceSplitter` hard-wrapped at 90 chars, preferring any comma and otherwise
cutting at a space or even mid-word. The default is now **180 chars** with a V4
migration for the old 90 default (user-set values are preserved). Long sentences
are cut at terminal punctuation first (excluding decimals/abbreviations), then a
comma, then a space, and if none exists the splitter extends to the next space
rather than cutting a word.

The single-letter initial guard was also too broad: `don't.` / `isn't.` end in a
lowercase `t` and were treated as initials, merging the next sentence. It now only
treats an uppercase single letter as an initial, and `"no"` was removed from the
abbreviation set.

## 5. System-bar insets

The redesigned screens draw edge-to-edge but did not inset the top bar. The
Settings back button was clipped under the status bar. Both activities now apply
`WindowInsets.systemBars()` as root padding, keeping the top bar below the status
bar and the footer/player above the navigation bar.

## 6. Merge audit

The feature branch was rebased onto `origin/main` (`3deb352`). At the rebase
commit (`a5a0eb6`), the following main-touched files were byte-identical to main
(empty `git diff origin/main..HEAD`):

`app/AndroidManifest.xml`, `app/assets/extract.js`, `AudioDsp.kt`, `DiagLog.kt`,
`PcmPlayer.kt`, `PlaybackService.kt`, `SentenceSplitter.kt`, `SherpaEngine.kt`,
`WebExtractor.kt`.

The subsequent reader-fix commits intentionally change `extract.js`,
`SentenceSplitter.kt`, and `SherpaEngine.kt` (the dotted-word, boundary, and
callback-contract fixes above). No main functionality was lost. The only reader
changes after the rebase are the fixes above.

## 7. Reference-app evidence (REA)

The shipped reference APKs were re-analysed with JADX 1.5.6; both hashes match
`docs/re-natural-ai-voices.md`:

| App | Version | Package | SHA-256 | Licence |
|---|---|---|---|---|
| HayaiTTS | 2.5.1 | `dev.ahmedmohamed.hayaitts` | `43ff2cf3…a71a07` | GPL-3.0 |
| NekoSpeak | 1.4.2 | `com.nekospeak.tts` | `fabd5278…d861d428` | MIT |

Findings that informed this fix:

* **HayaiTTS 2.5.1 does not use a streaming callback.** The service calls the
  blocking `generate(text, sid, speed)` and `streamPcm` only re-chunks the completed
  `float[]` into 16-bit LE writes. It caps text at 2,000 chars and can cut mid-word.
* **NekoSpeak 1.4.2** splits on `(?<=[.!?])\s+|\n+|(?<=[;])\s+`, re-splits only the
  **first** sentence when >75 chars (comma, then 60-char hard split), and batches
  later sentences to 148 tokens (Kokoro) / 398 (Kitten). It emits one callback per
  batch, never per token, because token-level splitting stutters.
* The sherpa callback contract (`1=continue, 0=stop`) is the one used by our JNI
  path; neither reference app contradicts it.
* No GPL code was copied. NekoSpeak is MIT and was used as prior-art evidence for
  the first-sentence latency trick, which our 180-char boundary splitter improves
  on without the mid-word cut.

The full re-analysis corrections are recorded in
`docs/re-natural-ai-voices.md` §9.

## 8. Verification summary

* Kotlin 2.2.20 full app compile: 0 errors.
* JVM `AdBlockSelfTest`: 22/22.
* `node --check` on `extract.js` and `content.js`: OK.
* Final APKs: normal `b2ff8a01…9f7f`, test `fd29597…b57f8` (15 MB each).
* iqoo I2214 (Android 15): `adblock ready network=122884 cosmetic=32403
  scriptlets=4612 build=365ms`; live chapter `Ad 0/125`, 108 sentences;
  self-test 22/22; audio frames above; reload guard above.
* The iqoo disconnected after the final reload check, so the self-test screenshot
  in `docs/reader-fixes-evidence/` is from the same merged engine build, taken
  immediately before the reload-only commit.
