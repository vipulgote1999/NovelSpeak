# Audio quality: noise, clicks, and gain

Date: 2026-10-24 · Scope: neural (Kokoro/Pocket) and system TTS playback

## What was measured

Capture: emulator audio monitor (`parecord` on the sink monitor), Pocket int8, empirenovel
ch 80, 24 kHz mono. Analysis: 20 ms windows, RMS/dBFS and max sample-to-sample delta.

Before the fix:

| Metric | Value |
|---|---|
| Quiet windows | 30 % exactly zero (between sentences) |
| Low-level non-zero windows | 12 % of windows, up to −45 dBFS |
| Noise burst | samples alternating ±300–800 at ≈ −48 dBFS between words |
| Isolated transients | delta 1691–2804 with local RMS 139–206 (clicks/steps) |

After the gate + fades:

| Metric | Before | After |
|---|---|---|
| p20 noise floor | −51.6 dBFS | **−80.3 dBFS** |
| Speech p50 | −22.6 dBFS | −23.3 dBFS |
| Speech p90 | −11.3 dBFS | −11.9 dBFS |

The speech level is unchanged; the sub-speech noise is ~29 dB lower.

Host regression (`tools/audio-dsp-test`): a −46.6 dBFS noise bed between two −17 dBFS tones is
attenuated by **44.9 dB**, and both tones lose **0.04–0.05 dB**. `ALL PASS`.

## Changes

1. **Low-level noise gate in `AudioDsp`** — a smoothed downward expander after the Wiener gain:
   open above −38 dBFS, floor 0.03 below −52 dBFS, 25 % per 10.7 ms block. It removes
   non-stationary vocoder bursts that minimum-statistics denoising cannot learn, and leaves
   narration untouched.
2. **Noise-estimate bootstrap fix** — the first STFT frame used to seed the whole 1 s noise
   window, which suppressed the first second of speech by ~14 dB (caught by the new DSP test).
   It now seeds at 10 % of the first frame, so speech passes while the estimate settles.
3. **Sentence fades** — `PcmPlayer.endSentence()` appends a 6 ms ramp from the last written
   sample to zero; the first chunk of each sherpa sentence fades in. System TTS gets the same
   6 ms fades on the rendered buffer. This removes the step discontinuities that caused clicks
   at sentence boundaries.
4. **Gain smoothing** — output gain drops instantly but rises over ~80 ms instead of stepping
   per chunk, so a quieter/louder sentence cannot pump the level.
5. **Policy** — the denoiser + gate are enabled for neural voices (vocoder hiss). System TTS
   output is clean, so only the fades are applied there; no unnecessary colouring.

## Verification

```bash
kotlinc app/src/app/novelspeak/AudioDsp.kt tools/audio-dsp-test/TestMain.kt \
  -include-runtime -d /tmp/audio-dsp-test.jar
java -jar /tmp/audio-dsp-test.jar
```

Also re-capture with `parecord` while reading empirenovel ch 80 and compare p20/p50/p90 as in
the table above.
