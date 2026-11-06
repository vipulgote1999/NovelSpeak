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

## 3. Perfetto trace: where the time goes

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

## 4. Limits and next actions

- **Phone trace is still required** before making a device performance claim. The emulator’s
  software GPU dominates both absolute and relative frame numbers.
- The library in this pass had **6 entries**, not the 200-entry stress case from the audit. A lazy
  list/adapter or incremental rendering remains a valid follow-up if a large-library measurement
  shows `Slow UI thread` or `Slow issue draw commands`.
- ASR validation is blocked until the router quota resets (~18 h from this run).
- Human listening comparison is blocked on a human listener; the corrected
  `story-cast-supertonic.mp3` and `story-cast-kokoro.mp3` are ready.

**Recommended order:** phone Perfetto/gfxinfo trace → decide whether Home needs a shortcut-plate
overdraw/caching change → large-library stress test → ASR/listening.

---

## 5. Repro

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
