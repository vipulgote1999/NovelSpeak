# Story Cast benchmark

- Model: `kokoro-int8-en-v0_19`
- Threads: 4; speed 1.0×; 18 chunks
- Scene audio: **32.92 s**; synthesis: **25.29 s**; overall RTF **0.768**; 189.6 words/min
- Model load: 1052.1 ms; first chunk: 2127.9 ms
- Synth latency p50/p95/max: 1273.5 / 2566.1 / 3004.0 ms
- Speaker switches: 17 (every cue changes speaker except the opening narration)
- Controlled switch probe (9 reps, identical text): same-speaker 2031.1 ms vs alternating 2233.8 ms → overhead **202.6 ms**
- Clipping chunks: 0; peak 0.6617

| # | speaker | voice | synth ms | audio s | RTF | F0 median | centroid |
|---|---|---|---:|---:|---:|---:|---:|
| 0 | NARRATOR | Heart | 2127.9 | 2.582 | 0.824 | 198.3 | 3672.5 |
| 1 | marshal-rennick | Adam | 890.2 | 0.882 | 1.009 | 127.3 | 1689.4 |
| 2 | NARRATOR | Heart | 1841.4 | 2.101 | 0.876 | 192.0 | 3371.9 |
| 3 | mara | Bella | 862.0 | 0.791 | 1.089 | 211.5 | 4062.0 |
| 4 | marshal-rennick | Adam | 1120.2 | 1.363 | 0.822 | 126.3 | 2073.1 |
| 5 | mara | Bella | 1162.4 | 1.24 | 0.937 | 200.0 | 3934.7 |
| 6 | NARRATOR | Heart | 1529.3 | 1.768 | 0.865 | 210.5 | 2446.1 |
| 7 | UNKNOWN | Heart | 1037.2 | 0.881 | 1.177 | 224.3 | 3155.9 |
| 8 | NARRATOR | Heart | 1744.3 | 2.202 | 0.792 | 194.3 | 3726.6 |
| 9 | marshal-rennick | Adam | 1126.6 | 1.436 | 0.784 | 124.4 | 2550.0 |
| 10 | NARRATOR | Heart | 3004.0 | 3.259 | 0.922 | 200.0 | 3022.4 |
| 11 | tomas | Michael | 1244.4 | 1.479 | 0.841 | 115.4 | 2556.1 |
| 12 | NARRATOR | Heart | 1773.7 | 2.137 | 0.83 | 192.0 | 3257.9 |
| 13 | mara | Bella | 2488.8 | 2.795 | 0.891 | 206.0 | 4121.3 |
| 14 | NARRATOR | Heart | 1302.5 | 1.294 | 1.007 | 212.4 | 3788.5 |
| 15 | marshal-rennick | Adam | 206.5 | 0.183 | 1.13 | 109.6 | 1652.4 |
| 16 | UNKNOWN | Heart | 385.7 | 0.343 | 1.124 | 201.7 | 2146.8 |
| 17 | NARRATOR | Heart | 1446.0 | 1.681 | 0.86 | 181.8 | 3695.8 |

## Speaker profiles

| speaker | voice | F0 median | centroid | chunks |
|---|---|---:|---:|---:|
| NARRATOR | Heart | 196.3 | 3522.2 | 8 |
| marshal-rennick | Adam | 125.3 | 1881.2 | 4 |
| mara | Bella | 206.0 | 4062.0 | 3 |
| UNKNOWN | Heart | 213.0 | 2651.4 | 2 |
| tomas | Michael | 115.4 | 2556.1 | 1 |

## Pairwise distance

| pair | F0 Δ | centroid Δ |
|---|---:|---:|
| marshal-rennick vs tomas | 0.079 | 0.264 |
| mara vs marshal-rennick | 0.392 | 0.537 |
| mara vs tomas | 0.44 | 0.371 |
