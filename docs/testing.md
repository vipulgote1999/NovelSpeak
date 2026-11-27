# NovelSpeak testing

NovelSpeak is deliberately Gradle-free, so its tests use four layers:

| Layer | What it proves | Command |
|---|---|---|
| Host Kotlin harnesses | Pure policy, parsers, queues, storage, redaction, URL and model validation | `tools/test-host.sh` |
| Headless Chromium | Real `extract.js` / `content.js` behavior in a browser DOM | `tools/extract-test/run.sh`, `tools/adblock-test/procedural.sh` |
| Maestro + emulator | Real app journeys, navigation, playback controls, library and settings | `tools/maestro/run.sh` |
| Device scripts | Share intent, rotation, 2× font scale, 320 dp phone at 1×/2× | `08_share_intent.txt`, `adaptive.sh`, `text-scale.sh`, `narrow.sh` |

The host suite currently has **28 harnesses**. The device suite has **28 Maestro flows**
plus the four device scripts.

## Quick commands

```bash
# Everything that can run without a device (CI default)
tools/test-all.sh --quick

# Focused host regression while working
tools/test-changed.sh
tools/test-host.sh --area=playback,tts
tools/test-host.sh --list

# One harness
tools/test-host.sh --area=library

# Full gate, including a connected emulator
tools/maestro/run.sh
tools/test-all.sh
```

`tools/test-host.sh --check` fails if a `tools/*-test/TestMain.kt` is not registered in
`tools/host-tests.tsv`, or if a manifest source path disappears.

## When to run what

| Area | Host areas | Browser | Maestro tags |
|---|---|---|---|
| Playback cursor, drain, engine teardown, voice/focus | `playback,tts` | — | `reader,tts,regression` |
| Privacy, diagnostics redaction, logs | `privacy,diagnostics` | — | `privacy` |
| Extraction, next links, oversized pages | `extraction,url` | `extract-test` | `reading,fixture,url` |
| Ad blocking, filter parsing, URL policy | `adblock,url` | `adblock-test/procedural.sh` | — |
| Library, bookmarks, reading stats | `library` | — | `library` |
| Story cast parser/chunker/store/voices | `cast,tts` | — | no device flow yet |
| Model install, archive shape, required files | `model,tts` | — | no download flow |
| Reader/home/settings layout | `ui` | — | `smoke,settings,home` |
| Adaptive layout and text scale | `ui` | — | `adaptive.sh`, `text-scale.sh` |
| EPUB/PDF import | `import` | — | `import` |

`tools/test-changed.sh` reads the current diff and maps source paths to these areas. It runs
the host and browser layers immediately and prints the matching `tools/maestro/run.sh
--tag=...` command. Pass `--run-device` to execute that device focus too. Unknown source
files intentionally select the entire host suite rather than skipping tests silently.

## Device test setup

The Play release denies cleartext, while the Maestro fixture server is HTTP on `localhost`.
Build and install the test-only APK with the `res-dev` network config:

```bash
sed 's#@xml/network_security_config#@xml/network_security_config_dev#' \
  app/AndroidManifest.xml > /tmp/AndroidManifest-maestro.xml
PATH="$HOME/.local/share/kotlin-2.2.20/bin:$HOME/Android/Sdk/build-tools/36.0.0:$PATH" \
  KOTLIN_STDLIB="$HOME/.local/share/kotlin-2.2.20/lib/kotlin-stdlib.jar" \
  ANDROID_JAR="$HOME/Android/Sdk/platforms/android-36/android.jar" \
  DEPS="$PWD/deps" TARGET_SDK=36 \
  MANIFEST=/tmp/AndroidManifest-maestro.xml EXTRA_RES_DIR="$PWD/app/res-dev" \
  DEBUGGABLE=1 OUT_APK=/tmp/NovelSpeak-maestro.apk ./build.sh
adb uninstall app.novelspeak
adb install -r /tmp/NovelSpeak-maestro.apk
```

Run with the existing AVD:

```bash
android emulator start --headless MedTrail_API35
tools/maestro/run.sh
tools/maestro/run.sh --tag=library
tools/maestro/run.sh --list-tags
tools/maestro/adaptive.sh
tools/maestro/text-scale.sh
tools/maestro/flows/08_share_intent.txt
```

`tools/maestro/run.sh` starts the fixture server, sets `adb reverse tcp:8765`, checks the
installed `app.novelspeak`, and writes JUnit/logs under `tools/maestro/reports/`. The tests
must use the test APK above; the release APK will correctly refuse the HTTP fixture.

## Adding tests

### Host harness

1. Create `tools/<name>-test/TestMain.kt` with a `main()` that exits non-zero on failure.
2. Add one tab-separated line to `tools/host-tests.tsv`:
   `name<TAB>areas<TAB>sources`.
3. Run `tools/test-host.sh --check` and `tools/test-host.sh --area=<area>`.
4. Prefer pure production helpers. If a fix is buried in an Android class, extract the
   decision into a small Android-free object (for example `ModelValidation`, `DrainGuard`,
   `DiagnosticsRedaction`) and test that object.

### Browser regression

Add a fixture under `tools/extract-test/` or `tools/adblock-test/`, include the real
`app/assets` script, and assert on the JSON/text payload from Chrome. Add it to the matching
`run.sh`/`procedural.sh`.

### Maestro flow

1. Add the flow under `tools/maestro/flows/` with a stable `appId: app.novelspeak` and
   `tags:` covering its feature and level (`smoke`, `reader`, `url`, `library`, etc.).
2. Make the flow self-contained: create any reading/library state it needs.
3. Run it alone, then run the full suite from a clean app state (`adb shell pm clear`).
4. Update [`tools/maestro/EXPECTED.md`](../tools/maestro/EXPECTED.md).

## CI

`.github/workflows/host-tests.yml` runs the host manifest check, the complete host suite and
the two headless Chromium regressions on every push/PR to `main`. Device tests are local
because they need an emulator and the fixture server; `tools/test-all.sh` runs them when a
device is connected and skips them (loudly) otherwise.

## Known coverage gaps

- The sherpa native synthesis call and real neural audio are not exercised on the host; the
  device flows cover system TTS and UI playback, not a downloaded model.
- Model downloads and filter-list downloads are not automated (size/network dependency).
- `Reader`’s full cursor/state machine has policy tests but no host fake-engine integration
  test; Maestro covers pause/resume, next chapter, restart and transport.
- Audio route changes (`ERROR_DEAD_OBJECT`) still have no on-device recovery test.
- Release APK startup/settings are smoke-tested, but Store review and Play pre-launch report
  remain manual.

## Website

The marketing site is checked statically, because it is plain HTML with no build step:

```bash
node tools/site-check.mjs          # structure, relative paths, assets, motion gating, tokens, contrast, budgets
node tools/site-check.mjs --verbose
```

It fails on a root-absolute URL (which would break the GitHub Pages subpath), a broken link or
image, a missing `alt`/dimensions, an animation outside the reduced-motion opt-in block, a hex
colour outside `:root`, a contrast pair below its threshold, or a page over its byte budget. The
`.github/workflows/cloudflare-pages.yml` workflow runs it on every change under `site/`. It is not a
renderer: the visual checklist it cannot perform is in `docs/website/design-contract.md`.
