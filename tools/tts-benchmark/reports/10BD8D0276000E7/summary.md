# NovelSpeak TTS benchmark

| Model | Family | Threads | OK | Cold load | Median synth | TTFC | Audio | RTF | xRT | RSS after load |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| kokoro-int8-en-v0_19 | kokoro | 4 | yes | 2417 ms | 7833 ms | 7833 ms | 6700 ms | 1.169 | 0.86 | 421 MB |
| sherpa-onnx-pocket-tts-int8-2026-01-26 | pocket | 4 | yes | 1534 ms | 3312 ms | 2808 ms | 4960 ms | 0.665 | 1.51 | 573 MB |
| sherpa-onnx-supertonic-3-tts-int8-2026-05-11 | supertonic | 4 | yes | 4838 ms | 5112 ms | 5109 ms | 7060 ms | 0.784 | 1.28 | 362 MB |
| sherpa-onnx-zipvoice-distill-int8-zh-en-emilia | zipvoice | 8 | yes | 4723 ms | 48384 ms | 48383 ms | 15424 ms | 3.368 | 0.30 | 494 MB |
| system | system | - | yes | 275 ms | 281 ms | 281 ms | 6406 ms | 0.046 | 21.89 | 184 MB |
