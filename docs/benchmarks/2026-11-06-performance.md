# NovelSpeak performance trace — 2026-11-06

**Build:** debug x86_64 APK, branch `feat/story-cast` (`7c05fc6` plus the pending performance
instrumentation-free build).
**Device:** `emulator-5554`, Android 15 (API 35), 1080×1920 @ 420 dpi, **60 Hz**, SwiftShader
software GPU. Font scale 1.0, animator scale 1.0.
**Scope:** cold/warm startup, Home and Library scrolling, and a 12-second Perfetto trace.

This is an **emulator** trace. It is useful for finding UI-thread work and relative differences,
but SwiftShader’s swap cost makes absolute frame times pessimistic compared with a phone.

---

## 1. Startup

`am start -W` after `am force-stop`, five cold runs:

| Run | TotalTime | WaitTime |
|---|---:|---:|
| 1 | 803 ms | 806 ms |
| 2 | 795 ms | 798 ms |
| 3 | 800 ms | 801 ms |
| 4 | 812 ms | 813 ms |
| 5 | 812 ms | 814 ms |

- **Median cold start: 803 ms; range 795–812 ms.**
- Five warm resumes: `TotalTime: 0 ms`, `WaitTime: 1–4 ms`.

Evidence: `docs/benchmarks/data/2026-11-06-performance/startup-cold.txt`,
`startup-warm.txt`.

---

## 2. Frame jank

`dumpsys gfxinfo` was reset immediately before each scroll pass; six up/down swipes per screen.
Home was measured twice to separate a real regression from emulator load.

| Screen | Frames | Janky (modern) | Janky (legacy >16.7 ms) | p50 | p90 | p95 | p99 |
|---|---:|---:|---:|---:|---:|---:|---:|
| Home run 1 | 365 | 3 (0.82%) | 304 (83.3%) | 26 ms | 32 ms | 34 ms | 36 ms |
| Home run 2 | 399 | 4 (1.00%) | 296 (74.2%) | 25 ms | 32 ms | 32 ms | 36 ms |
| Library | 358 | 2 (0.56%) | 117 (32.7%) | **16 ms** | 23 ms | 25 ms | 28 ms |

`Missed Vsync`, `Slow UI thread`, and `Slow bitmap uploads` were **0** in every run; the only
non-zero counter was `Slow issue draw commands` (3–4 frames).

**Reading:** Library is at 60 fps on average (16 ms) with 0.56% modern jank. Home is consistently
~9–10 ms slower per frame, despite no UI-thread or bitmap-upload stalls. The 60 Hz legacy metric is
harsh on this software renderer; the modern jank percentages are the more useful signal.

Evidence: `gfx-home-1.txt`, `gfx-home-2.txt`, `gfx-library.txt` in the same directory.

---

## 3. 200-entry Library stress test

The audit’s largest open question was the non-lazy Library. I seeded **200 chapters through the
real `LibraryStore`**, backed up the real preferences, measured, and restored the backup. Home caps
Continue cards at three, so this stress test is Library-specific.

| Implementation | Open frames | Janky open | Open p99 | Slow UI (open) | Scroll p50 | Scroll janky | Scroll slow UI |
|---|---:|---:|---:|---:|---:|---:|---:|
| 200 rows built synchronously (old) | 47 | 5 (10.6%) | **250 ms** | 3 | 21 ms | 0.55% | 0 |
| Incremental 20-row batches | 39 | 8 (20.5%) | 113 ms | 6 | 21 ms | 0.84% | 0 |
| **Recycled ListView adapter (final)** | 19 | 2 (10.5%) | **48 ms** | **1** | 21 ms | 0.56% | **0** |

- The final adapter is a **5.2× reduction in the worst open frame** (250 → 48 ms) and cuts slow
  UI-thread frames from 3 to 1. Only visible rows are inflated now.
- Scrolling is unchanged (~21 ms p50) because the old ScrollView also drew only what was visible;
  a 6-entry library was 16 ms p50, so the extra 5 ms is additional visible content, not UI work.
- The one residual 48 ms open frame is initial visible-row inflation on SwiftShader. It needs a
  phone check before further row simplification; scrolling has **zero** slow UI-thread frames.
- Home with 200 entries: 356 frames, 10 janky (2.81%), p99 48 ms, 4 slow UI frames. Home still
  renders only three Continue cards; the extra cost is the 200-entry store scan/sort, not 200 views.

Code: `LibraryActivity` now uses a `ListView` + `BaseAdapter` (`LibraryAdapter`) with a transparent
8 dp divider; search/filter replaces the adapter’s row list and resets to the top. Delete, search,
and filter Maestro flows still pass.

Renders/evidence: [200-row Library](../../media/benchmarks/library-200-stress.png) ·
`gfx-library-200-open.txt`, `gfx-library-200-scroll.txt`,
`gfx-library-200-list-open.txt`, `gfx-library-200-list-scroll.txt`, `gfx-home-200.txt`,
`library-200-seed.txt`.

## 4. Perfetto trace: where the time goes

12-second trace (`sched gfx wm am`, 64 MB buffer) covering Home and Library scrolling.
Trace: `home-library.perfetto-trace.gz` (14.2 MB raw, 2.8 MB gzip).

| Query | Result |
|---|---:|
| RenderThread CPU time (12 s window) | **5,757 ms** |
| App UI-thread CPU time (12 s window) | **317 ms** |
| `eglSwapBuffersWithDamageKHR` calls | 511 |
| Swap average / max | **14.1 / 31.1 ms** |
| Swap total | 7,198 ms |
| Frame timeline (system-wide) | 1,020 frames, avg 14.7 ms, max 69.4 ms |

The top RenderThread slices are all `eglSwapBuffersWithDamageKHR` (25–31 ms). The UI thread is
essentially idle (0.32 s across the whole trace), and `Slow UI thread` is zero in gfxinfo. The
frame-timeline row is system-wide (compositor + app); the app-specific jank figures are the
`dumpsys gfxinfo` table above.

**Conclusion:** the Home/Library cost is **GPU/swap-bound on SwiftShader**, not application
layout or text work. Home’s extra cost is consistent with its larger light shortcut plates and
overdraw. Do not optimize the Compose/View hierarchy based on this emulator trace alone.

Evidence query output: `docs/benchmarks/data/2026-11-06-performance/trace-queries.txt`.

---

## 5. Limits and next actions

- **Phone trace is still required** before making a device performance claim. The emulator’s
  software GPU dominates both absolute and relative frame numbers.
- The 200-entry Library is now recycled, but the first open still has one 48 ms frame on
  SwiftShader. Confirm on a phone before simplifying the row further.
- ASR validation is blocked until the router quota resets (~18 h from this run).
- Human listening comparison is blocked on a human listener; the corrected
  `story-cast-supertonic.mp3` and `story-cast-kokoro.mp3` are ready.

**Recommended order:** phone Perfetto/gfxinfo trace → decide whether Home needs a shortcut-plate
overdraw/caching change → ASR/listening when unblocked.

---

## 6. Repro

```bash
# startup
adb shell am force-stop app.novelspeak
adb shell am start -W -n app.novelspeak/.MainActivity

# frame stats
adb shell dumpsys gfxinfo app.novelspeak reset
# ...scroll Home or Library...
adb shell dumpsys gfxinfo app.novelspeak

# Perfetto (tools from google/perfetto):
./record_android_trace -t 12s -b 64mb -n -a app.novelspeak \
  -o home-library.perfetto-trace sched gfx wm am
gzip -9 home-library.perfetto-trace

# analyze
trace_processor query home-library.perfetto-trace \
  "select count(*) n, round(avg(dur)/1e6,1) avg_ms \
   from slice where name='eglSwapBuffersWithDamageKHR';"
```
