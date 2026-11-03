# NovelSpeak TTS benchmark

| Model | Family | Threads | OK | Cold load | Median synth | TTFC | Audio | RTF | xRT | RSS after load |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| kokoro-int8-en-v0_19 | kokoro | 4 | yes | 1155 ms | 5248 ms | 5248 ms | 6675 ms | 0.766 | 1.31 | 372 MB |
| sherpa-onnx-pocket-tts-int8-2026-01-26 | pocket | 4 | yes | 788 ms | 3060 ms | 2758 ms | 5600 ms | 0.561 | 1.78 | 523 MB |
| sherpa-onnx-supertonic-3-tts-int8-2026-05-11 | supertonic | 4 | yes | 799 ms | 2165 ms | 2164 ms | 7060 ms | 0.307 | 3.26 | 336 MB |
| sherpa-onnx-zipvoice-distill-int8-zh-en-emilia | zipvoice | 4 | yes | 1643 ms | 28342 ms | 28340 ms | 15424 ms | 1.909 | 0.53 | 448 MB |
| system | system | - | yes | 463 ms | 141 ms | 141 ms | 6404 ms | 0.022 | 44.48 | 151 MB |
