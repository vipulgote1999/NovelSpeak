# Story Cast benchmark

- Model: `sherpa-onnx-supertonic-3-tts-int8-2026-05-11`
- Threads: 4; speed 1.0×; 18 chunks
- Scene audio: **98.39 s**; synthesis: **11.71 s**; overall RTF **0.119**
- Model load: 630.6 ms; first chunk: 767.8 ms
- Synth latency p50/p95/max: 653.8 / 828.7 / 876.7 ms
- Speaker switches: 17 (every cue changes speaker except the opening narration)
- Controlled switch probe (9 reps, identical text): same-speaker 753.0 ms vs alternating 752.5 ms → overhead **-0.5 ms**
- Clipping chunks: 0; peak 0.5521

| # | speaker | voice | synth ms | audio s | RTF | F0 median | centroid |
|---|---|---|---:|---:|---:|---:|---:|
| 0 | NARRATOR | F1 | 767.8 | 3.197 | 0.24 | 181.5 | 2962.8 |
| 1 | marshal-rennick | M1 | 659.8 | 1.99 | 0.331 | 128.9 | 1968.8 |
| 2 | NARRATOR | F1 | 753.8 | 2.566 | 0.294 | 200.5 | 2105.6 |
| 3 | mara | F2 | 538.9 | 1.947 | 0.277 | 250.6 | 2930.5 |
| 4 | marshal-rennick | M1 | 620.8 | 3.214 | 0.193 | 135.7 | 2131.7 |
| 5 | mara | F2 | 714.3 | 2.306 | 0.31 | 170.3 | 2383.4 |
| 6 | NARRATOR | F1 | 683.3 | 2.846 | 0.24 | 175.3 | 2126.7 |
| 7 | UNKNOWN | F1 | 645.6 | 1.996 | 0.323 | 184.5 | 2516.4 |
| 8 | NARRATOR | F1 | 563.1 | 2.741 | 0.205 | 173.6 | 3283.8 |
| 9 | marshal-rennick | M1 | 876.7 | 3.821 | 0.229 | 143.6 | 2523.5 |
| 10 | NARRATOR | F1 | 784.2 | 4.118 | 0.19 | 186.1 | 2966.9 |
| 11 | tomas | M2 | 647.9 | 2.491 | 0.26 | 100.6 | 1753.6 |
| 12 | NARRATOR | F1 | 686.8 | 2.785 | 0.247 | 186.9 | 2403.4 |
| 13 | mara | F2 | 820.2 | 4.105 | 0.2 | 196.0 | 2227.4 |
| 14 | NARRATOR | F1 | 495.9 | 1.848 | 0.268 | 170.3 | 2316.7 |
| 15 | marshal-rennick | M1 | 384.8 | 1.389 | 0.277 | 165.2 | 2782.1 |
| 16 | UNKNOWN | F1 | 470.1 | 1.369 | 0.343 | 168.3 | 1850.1 |
| 17 | NARRATOR | F1 | 596.2 | 2.218 | 0.269 | 164.6 | 3216.3 |

## Speaker profiles

| speaker | voice | F0 median | centroid | chunks |
|---|---|---:|---:|---:|
| NARRATOR | F1 | 178.4 | 2683.1 | 8 |
| marshal-rennick | M1 | 139.6 | 2327.6 | 4 |
| mara | F2 | 196.0 | 2383.4 | 3 |
| UNKNOWN | F1 | 176.4 | 2183.2 | 2 |
| tomas | M2 | 100.6 | 1753.6 | 1 |

## Pairwise distance

| pair | F0 Δ | centroid Δ |
|---|---:|---:|
| marshal-rennick vs tomas | 0.279 | 0.247 |
| mara vs marshal-rennick | 0.288 | 0.023 |
| mara vs tomas | 0.487 | 0.264 |
