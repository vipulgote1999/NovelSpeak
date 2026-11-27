# Test Suite Overhaul Implementation Plan

> **For agentic workers:** Use this plan with native execution in this session. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make NovelSpeak’s tests fast, focused, and comprehensive: every newer feature/fix has a regression, every change can select the relevant tests, and one command runs the full gate.

**Architecture:** Keep the existing Gradle-free harness style. Add a tab-separated host-test manifest and a manifest-driven runner with area filters. Add a changed-file focus script that maps source paths to host areas and Maestro tags. Add focused device checks for newer behavior (bad next link, 2× text scale) and make Maestro tags usable as a filter. Add a single `test-all` gate and document the matrix.

**Tech Stack:** Bash, Kotlin/JVM host harnesses, headless Chromium, Maestro 2.11, Android emulator, GitHub Actions.

**Spec:** No separate spec; this plan implements the requested test-suite improvement directly from the verified gaps in the 2026-11-22 review.

## Global Constraints

- Do not introduce Gradle, Hilt, Robolectric, Espresso or JUnit: the project is deliberately framework-free and uses host Kotlin harnesses plus Maestro.
- Kotlin 2.2+ is required.
- Host tests must run without an Android SDK or emulator.
- Device tests must use the existing `app.novelspeak` package and fixture server.
- Release network config must remain cleartext-blocked; only `app/res-dev` may allow loopback.
- No test may depend on a previous flow having run unless the flow itself creates the state.

## Review Focus

- The test runner must fail when a `tools/*-test/TestMain.kt` exists but is not registered.
- Focus selection must be conservative: unknown changed files run all host tests rather than silently selecting none.
- Device tests must be skippable when no emulator is attached, and must not be reported as passing when skipped.
- New device fixtures must not expose the release build to cleartext; use the dev APK only.
- New tests should assert behavior, not implementation details or test-only helpers.

---

### Task 1: Manifest-driven host runner with areas

**Files:**
- Create: `tools/host-tests.tsv`
- Modify: `tools/test-host.sh`

**Interfaces:**
- Manifest columns: `name<TAB>areas<TAB>sources`.
- `tools/test-host.sh --list`
- `tools/test-host.sh --area=playback,tts`
- `tools/test-host.sh --check`

- [ ] Write the manifest for all 25 current/new harnesses.
- [ ] Rewrite the runner to read the manifest, filter by area, and fail if a harness is unregistered.
- [ ] Run `tools/test-host.sh --check` and `tools/test-host.sh --area=playback`.

### Task 2: New host coverage for newer code

**Files:**
- Create: `app/src/app/novelspeak/ModelValidation.kt`
- Create: `tools/model-validation-test/TestMain.kt`
- Create: `tools/url-tools-test/TestMain.kt`
- Modify: `app/src/app/novelspeak/ModelManager.kt`
- Modify: `tools/diagnostics-redaction-test/TestMain.kt`
- Modify: `tools/drain-guard-test/TestMain.kt`

- [ ] Add failing `ModelValidation` tests for kokoro/pocket/supertonic/zipvoice/vocoder completeness and missing required files.
- [ ] Implement `ModelValidation` and delegate `ModelManager.isInstalled` to it.
- [ ] Add `UrlTools` host tests for schemes, hosts, loopback and tokenization.
- [ ] Expand redaction and drain-guard edge cases.
- [ ] Run the new tests and the full host suite.

### Task 3: Browser/device coverage for newer behavior

**Files:**
- Create: `tools/extract-test/next-link.html`
- Modify: `tools/extract-test/run.sh`
- Create: `tools/test-page/chapter-bad-next.html`
- Create: `tools/maestro/flows/22_bad_next_link.yaml`
- Create: `tools/maestro/text-scale.sh`
- Modify: `tools/maestro/run.sh`
- Modify: Maestro flow tags.

- [ ] Add a Chromium assertion for `__nsNextLink()` returning the expected next URL.
- [ ] Add a Maestro flow proving a `file:` next link is refused and leaves the reader on the chapter.
- [ ] Add a 2× font-scale device check and reset the setting afterward.
- [ ] Add `--tag=` and `--list-tags` to the Maestro runner; give every flow accurate tags.
- [ ] Run the new Chrome checks and the new Maestro flow on the emulator.

### Task 4: Focus and full-gate tooling

**Files:**
- Create: `tools/test-changed.sh`
- Create: `tools/test-all.sh`
- Modify: `.github/workflows/host-tests.yml`
- Create: `docs/testing.md`
- Create: `AGENTS.md`
- Modify: `README.md`

- [ ] Implement `test-changed.sh` mapping changed source paths to host areas, Chrome checks and Maestro tags; unknown files select all host tests.
- [ ] Implement `test-all.sh` running host + Chrome, and device tests only when an emulator is connected.
- [ ] Run browser regressions in CI.
- [ ] Document the test matrix, commands, and known gaps.
- [ ] Verify the full gate and rebuild release artifacts if production code changed.
