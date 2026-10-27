# NovelSpeak Maestro E2E — expected results

Last verified: **2026-10-24 ~23:00 IST**, emulator-5554 (API 35, headless), Maestro 2.11.0.
App under test: **app.novelspeak 1.2.0-dev (versionCode 16)**.
The first baseline (01–02) was taken on the previously installed **1.0.0 (versionCode 14)**.

Status key: **PASS** = verified on the installed build; **PENDING** = feature/build not present;
**BLOCKED** = known environment/config prerequisite missing.

## Runner

```bash
tools/maestro/run.sh                         # all flows in flows/
tools/maestro/run.sh 01_launch.yaml 02_settings.yaml
tools/maestro/run.sh --pending               # flows_pending/ only
KEEP=1 tools/maestro/run.sh                  # leave fixture server + adb reverse up
```

The runner checks emulator-5554, starts `python3 -m http.server 8765 --directory tools/test-page`
(pid file in `/tmp`), adds `adb reverse tcp:8765 tcp:8765`, checks the installed APK, runs
Maestro with JUnit output, tears down owned resources unless `KEEP=1`, and prints a summary.

Reports: `tools/maestro/reports/junit.xml` (Maestro 2.11 wants a **file** for `--output`;
`reports/` still holds it), `reports/maestro.log`, and per-run artifacts in `reports/artifacts/`.

## Flow expectations

| # | Flow | Feature | Key explicit assertions | Expected result | Status |
|---|------|---------|-------------------------|-----------------|--------|
| 01 | `flows/01_launch.yaml` | Launch / shell | `Address bar.*`, `Read`, `Next ch`, `Play`, `More options` | Main reader shell renders with a usable player | **PASS** (1.0.0: 6 s) |
| 02 | `flows/02_settings.yaml` | Settings navigation | overflow → `NovelSpeak settings`; `Settings`, `SPEECH ENGINE`, `Changes are saved instantly`; `Back` returns to `Read` | Settings opens and back restores the reader | **PASS** (1.0.0: 21 s) |
| 03 | `flows/03_read_local_chapter.yaml` | Local chapter read | URL in address bar; `Read`; `Sentence N of M`; `Next ch`; `Play`/`Pause` | Fixture chapter is loaded and extracted into the reader | **PASS** (1.2.0-dev: 26 s). Pre-network-config builds are blocked by cleartext policy |
| 04 | `flows/04_next_chapter.yaml` | Next chapter | `Next ch` → address bar `chapter2.html`; `Chapter Two.*`; sentence state | `rel="next"` is followed and chapter 2 is appended | **PASS** (1.2.0-dev: 29 s) |
| 05 | `flows/05_per_site.yaml` | Per-site reader mode | dialog `Reader mode` checked true → off on localhost; checked false persists; checked true on 10.0.2.2; survives relaunch | Site A's opt-out does not leak to site B and persists | **PASS** (1.2.0-dev: 1 m 46 s) |
| 06 | `flows/06_sleep_timer.yaml` | Sleep timer | menu `Sleep timer`; options Off/15/30/45/60/End of chapter; `15 minutes` → `Sleep timer · M:SS`; `Off` clears | Timer arms with a live countdown and disarms cleanly | **PASS** (1.2.0-dev: 37 s) |
| 07 | `flows/07_library_resume.yaml` | Library + resume | `Library`, `Library entry`, `.*Salt Road.*`, `.*[0-9]+%.*`; row reopens chapter2 and shows sentence state; row survives relaunch | Opened chapters are recorded, show progress, and resume | **PASS** (1.2.0-dev: 40 s) |
| 08 | `flows/08_share_intent.txt` | Share-sheet ingestion | adb `ACTION_SEND text/plain` → `Shared text` status, `Sentence N of M`, `Read` | Shared prose reaches the reader | **PASS** (script, 1.2.0-dev) |
| 09 | `flows/09_privacy_about.yaml` | Privacy / about | overflow `About & privacy`; `Privacy & licences`, `PRIVACY`, `.*no analytics.*`; `Back` | Privacy page states the no-analytics posture and returns | **PASS** (1.2.0-dev: 11 s) |

### Full gate run (1.2.0-dev / versionCode 16)

2026-10-24 23:03 IST, `tools/maestro/run.sh` with all eight YAML flows (08 is a script, run separately):

```
[Passed] 07 library: opened chapter appears with progress and resumes (43s)
[Passed] 05 per-site reader mode: localhost off does not affect 10.0.2.2; survives relaunch (1m 48s)
[Passed] 06 sleep timer: set 15 minutes, countdown appears, Off clears it (36s)
[Passed] 09 privacy: About & privacy -> Privacy & licences -> back (10s)
[Passed] 03 read local chapter: URL loads, Read extracts, player shows sentence state (21s)
[Passed] 02 settings: overflow opens the Settings screen and back returns (10s)
[Passed] 01 launch: address bar and player are visible (3s)
[Passed] 04 next chapter: Next ch follows rel=next to chapter2 (23s)
8/8 Flows Passed in 4m 14s
tests=8 failures=0 errors=0 skipped=0
== RESULT: PASS
```

`tools/maestro/flows/08_share_intent.txt` separately: **PASS** (`Shared text`, `Sentence 1 of 1`, `Read`).
Archived as `reports/gate-1.2.0-dev-junit.xml` / `reports/gate-1.2.0-dev-maestro.log`.

Runner teardown was verified separately: after an owned run, the fixture server, pid file and
`adb reverse tcp:8765` were all gone.

### Baseline (previous install, 1.0.0 / versionCode 14)

```
$ tools/maestro/run.sh 01_launch.yaml 02_settings.yaml
[Passed] 01 launch: address bar and player are visible (6s)
[Passed] 02 settings: overflow opens the Settings screen and back returns (21s)
2/2 Flows Passed in 27s
tests=2 failures=0 errors=0 skipped=0
== RESULT: PASS
```

Saved as `reports/baseline-v1.0.0-junit.xml` and `reports/baseline-v1.0.0-maestro.log`.
Note: the task brief said the installed build was 1.1.0; the device actually reported 1.0.0.

### Pre-build failure that is now fixed

On 1.0.0, `03_read_local_chapter` failed at `Extracted [0-9]+ sentences`: the manifest had
`usesCleartextTraffic="false"`, so `http://localhost:8765/chapter1.html` never loaded. The 1.2.0-dev
build ships `res/xml/network_security_config.xml` (localhost / 127.0.0.1 / 10.0.2.2 only), and the
flow was then updated for the new player status wording (`Playing`, not `Extracted N sentences`).

## Selector inventory (discovered with `uiautomator dump`, not invented)

| UI | Selector used |
|----|---------------|
| Address bar | contentDescription `Address bar` / `Address bar, <url>`; inner `Edit address` |
| Player | text `Read`, `Next ch`; contentDescription `Play`/`Pause`, `Sentence N of M`, `Playback speed …` |
| Overflow | contentDescription `More options`; items `Site settings`, `Library`, `Continue reading`, `Sleep timer`, `NovelSpeak settings`, `About & privacy` |
| Settings | text `Settings`, `SPEECH ENGINE`, `Changes are saved instantly`; cd `Back` |
| Site dialog | items `Reader mode` and `Block ads on this site`; `checked` attribute is reliable |
| Sleep timer | dialog title/menu `Sleep timer`; options `Off`, `15 minutes` … `End of chapter`; status text `Sleep timer · M:SS` |
| Library | title/cd `Library`; row cd `Library entry`; row title and `site · N% · time`; `Clear library` |
| Privacy | `About & privacy` (overflow); title `Privacy & licences`; cd `Back` |

## Selector / contentDescription requests (for the app)

1. **Library rows are indistinguishable in accessibility.** Every row is `contentDescription="Library entry"`.
   Please make it e.g. `Library entry, <title>, <percent>%` so tests (and TalkBack) can address a row.
2. **Reader status line has no contentDescription** and its text changed between builds
   (`Ready · N sentences` → `Extracted N sentences` → `Playing`). A stable `Reader status, …`
   contentDescription would remove wording coupling.
3. **Sleep-timer countdown** is a TextView whose text is the countdown and whose cd is just
   `Sleep timer` (same as the menu item/dialog title). Add `Sleep timer, <M>:<SS> remaining` (or a
   state cd) for unambiguous assertions.
4. **`Read` / `Next ch` are text-only TextViews.** `Read` also appears in dialogs; cds
   `Read chapter` / `Next chapter` would be safer.
5. **Share ingestion confirmation** is text `Shared text` in this build (newer source says
   `Shared text · N sentences`). A stable cd is desirable.
6. **Privacy entry is inconsistent between builds**: installed build has only overflow
   `About & privacy`; working-tree Settings has `Open privacy`. Keep one stable cd on both paths.
7. Good as-is: address bar URL cd, `Play/Pause`, `Sentence N of M`, `Playback speed`, `Back`,
   site-dialog `checked` state, and WebView text exposure.

## Known issues / notes

- **Per-site label bug (residual):** `MainActivity.updateReaderButton()` still reads the *global*
  `prefs.readerMode`, while the dialog and page application use `readerModeForSite()`. The flow
  asserts the dialog checkbox (authoritative) and deliberately does **not** assert the top-bar label.
- **Emulator memory:** on this host the emulator was OOM-killed twice with default RAM. It was run
  with `-qemu -m 1536` (guest 1.5 GB) for these runs. The runner only checks connectivity; it does
  not start the emulator.
- **Shared emulator:** another agent's app (Navora) stole focus during a probe. Re-run a flow if it
  fails on an element that the source says exists; the JUnit failure text makes this obvious.
- **No `clearState`:** clearing app data would delete downloaded neural models (103 MB + 98 MB).
  Flows launch fresh and assert state tolerantly instead.
- **Fixture titles:** Readability trims the `Chapter Two:` prefix when storing the library title
  (`The Salt Road — The Wick Chronicle`); flow 07 asserts the observed title.
- **08 is a shell script, not YAML** (`.txt`), because `ACTION_SEND` cannot be delivered by
  `launchApp`. Maestro ignores it when scanning `flows/`; run it directly or add it to CI.
- **External drafts:** another process wrote alternative 05–09 flows at 22:42 while this harness was
  being built. They are preserved in `tools/maestro/_external-2242/`; the verified flows above are
  the ones wired into `flows/`.

## Additional checks (v1.2.0-dev)

| Flow / script | Feature | Assertions | Expected | Status |
|---|---|---|---|---|
| 10_bookmark_sentence.yaml | bookmarks | "Bookmark sentence" -> Library "BOOKMARKS" -> reopen | sentence saved and reopened | PASS |
| 11_import_picker.yaml | EPUB/PDF import | "Import EPUB or PDF" opens the document picker | picker opens and Back returns | PASS |
| adaptive.sh | two-pane layout | rotate landscape/portrait; address bar + Read present | no crash, controls preserved | PASS |
| intents.sh | share intake | ACTION_SEND text reaches the reader | "Shared text"/sentence state | PASS |

| 13_home.yaml | home tab | wordmark/search/shortcuts/continue; shortcut opens page | PASS |
| 14_read_restart.yaml | reader | Read restarts current chapter at sentence 1 | PASS |
| 15_next_fallback.yaml | reader | Next ch advances numbered URL without a next anchor | PASS |
