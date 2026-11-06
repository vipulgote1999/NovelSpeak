# Story Cast benchmark

- Model: `sherpa-onnx-supertonic-3-tts-int8-2026-05-11`
- Threads: 4; speed 1.0×; 18 chunks
- Scene audio: **51.45 s**; synthesis: **8.62 s**; overall RTF **0.168**; 121.3 words/min
- Model load: 476.0 ms; first chunk: 500.6 ms
- Synth latency p50/p95/max: 461.4 / 617.9 / 676.4 ms
- Speaker switches: 17 (every cue changes speaker except the opening narration)
- Controlled switch probe (9 reps, identical text): same-speaker 636.8 ms vs alternating 512.6 ms → overhead **-124.2 ms**
- Clipping chunks: 0; peak 0.739

| # | speaker | voice | synth ms | audio s | RTF | F0 median | centroid |
|---|---|---|---:|---:|---:|---:|---:|
| 0 | NARRATOR | F1 | 500.6 | 3.197 | 0.157 | 187.7 | 2836.6 |
| 1 | marshal-rennick | M1 | 523.0 | 1.99 | 0.263 | 130.9 | 1756.3 |
| 2 | NARRATOR | F1 | 600.1 | 2.566 | 0.234 | 188.1 | 3469.9 |
| 3 | mara | F2 | 329.4 | 1.947 | 0.169 | 249.2 | 3481.1 |
| 4 | marshal-rennick | M1 | 463.2 | 3.214 | 0.144 | 107.0 | 2230.4 |
| 5 | mara | F2 | 412.4 | 2.306 | 0.179 | 180.0 | 2259.0 |
| 6 | NARRATOR | F1 | 456.0 | 2.846 | 0.16 | 183.8 | 1980.6 |
| 7 | UNKNOWN | F1 | 542.7 | 1.996 | 0.272 | 176.4 | 2826.4 |
| 8 | NARRATOR | F1 | 459.7 | 2.741 | 0.168 | 190.1 | 3060.1 |
| 9 | marshal-rennick | M1 | 581.7 | 3.821 | 0.152 | 132.4 | 2450.2 |
| 10 | NARRATOR | F1 | 676.4 | 4.118 | 0.164 | 185.3 | 2800.9 |
| 11 | tomas | M2 | 423.5 | 2.491 | 0.17 | 106.3 | 1610.8 |
| 12 | NARRATOR | F1 | 436.5 | 2.785 | 0.157 | 180.7 | 2342.0 |
| 13 | mara | F2 | 607.6 | 4.105 | 0.148 | 202.3 | 2289.6 |
| 14 | NARRATOR | F1 | 532.2 | 1.848 | 0.288 | 177.1 | 2181.5 |
| 15 | marshal-rennick | M1 | 351.3 | 1.389 | 0.253 | 148.6 | 3287.2 |
| 16 | UNKNOWN | F1 | 330.3 | 1.369 | 0.241 | 180.4 | 1989.6 |
| 17 | NARRATOR | F1 | 397.1 | 2.218 | 0.179 | 165.2 | 2866.8 |

## Speaker profiles

| speaker | voice | F0 median | centroid | chunks |
|---|---|---:|---:|---:|
| NARRATOR | F1 | 184.6 | 2818.8 | 8 |
| marshal-rennick | M1 | 131.7 | 2340.3 | 4 |
| mara | F2 | 202.3 | 2289.6 | 3 |
| UNKNOWN | F1 | 178.4 | 2408.0 | 2 |
| tomas | M2 | 106.3 | 1610.8 | 1 |

## Pairwise distance

| pair | F0 Δ | centroid Δ |
|---|---:|---:|
| marshal-rennick vs tomas | 0.193 | 0.312 |
| mara vs marshal-rennick | 0.349 | 0.022 |
| mara vs tomas | 0.475 | 0.296 |
