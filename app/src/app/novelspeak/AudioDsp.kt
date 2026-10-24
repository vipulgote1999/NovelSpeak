package app.novelspeak

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Streaming broadband-noise suppressor for mono float PCM from sherpa/Kokoro (24 kHz).
 *
 * Pipeline, per 512-sample frame with a 256-sample hop (50 % overlap):
 *
 *   1. 2nd-order Butterworth high-pass at 75 Hz (removes DC / rumble, leaves the voice band
 *      untouched). A gentle 11 kHz low-pass is implemented but disabled: the audible hiss sits
 *      mostly below it, so filtering only dulled sibilance.
 *   2. Per-bin noise estimate with minimum statistics: a smoothed periodogram is tracked over a
 *      ~1 s sliding window and the minimum is taken as the noise floor, with a bias factor
 *      (a minimum is always below the mean of a random PSD).
 *   3. Decision-directed Wiener gain with soft over-subtraction. The gain is smoothed in time
 *      and across neighbouring bins, and clamped to a -16.5 dB floor. The floor is the important
 *      part: it caps the maximum attenuation, which is what keeps a spectral gate from turning
 *      into "musical noise" / metallic warble between words.
 *   4. sqrt-Hann analysis + sqrt-Hann synthesis, so the squared windows sum to exactly 1.0 at a
 *      50 % hop; overlap-add reconstructs the signal without amplitude ripple.
 *
 * Parameters are deliberately conservative: the gain is always in [0.15, 1.0], so at worst a bin
 * is attenuated by 16.5 dB, and the noise estimate can never *boost* anything. Speech-dominant
 * bins get gain close to 1, so speech attenuation is well under 1 dB in practice.
 *
 * Threading: [process] is @Synchronized and is called only by PcmPlayer.write() on the sherpa
 * generation thread, sequentially. [reset] is lock-free: it sets a volatile flag that the next
 * process() call consumes, so PcmPlayer.reset()/stop() on the UI thread can never block behind a
 * long chunk. Between the reset and the next chunk the previous audio has already been flushed
 * from the AudioTrack, so clearing at the chunk boundary is inaudible.
 *
 * Allocation: process() allocates one FloatArray of the input length per call. That is not on the
 * AudioTrack callback (writes are streamed from the generation thread), so it is acceptable, and
 * it keeps the returned buffer safe to use after the lock is released.
 */
class AudioDsp(sampleRateHz: Int = 24000) {

    private companion object {
        // ---- STFT ----
        const val FRAME = 512          // 21.3 ms @ 24 kHz
        const val HOP = 256            // 50 % overlap
        const val BINS = FRAME / 2 + 1 // 257

        // ---- noise estimate (minimum statistics) ----
        const val NOISE_FRAMES = 96    // 96 * 10.67 ms = 1.02 s sliding minimum
        const val PS_SMOOTH = 0.70f    // periodogram smoothing (per frame)
        const val MIN_BIAS = 1.2f      // minimum < mean; compensates the downward bias
        const val NOISE_EST_SMOOTH = 0.85f // smooths the min so it cannot jump frame to frame

        // ---- gain rule ----
        const val OVER_SUB = 1.1f      // soft over-subtraction (further suppresses residual hiss)
        const val DD_ALPHA = 0.96f     // decision-directed a priori SNR memory
        const val GAIN_FLOOR = 0.15f   // -16.5 dB: maximum attenuation, kills musical noise
        const val GAIN_TIME = 0.55f    // temporal gain smoothing
        const val GAIN_FREQ = 0.35f    // 3-tap gain smoothing across bins

        // ---- filters ----
        const val HPF_HZ = 75.0
        const val LPF_HZ = 11000.0
        const val LPF_ENABLED = false

        // ---- startup ----
        // The first frames have a provisional noise estimate; fade the wet signal in so no gain
        // step is audible if the first frame happens to be a loud syllable.
        const val WET_RAMP_FRAMES = 24 // 0.26 s

        // ---- low-level noise gate ----
        // The vocoder occasionally emits short high-frequency bursts below speech level
        // (measured ~-48 dBFS between words on Pocket). Minimum-statistics denoising cannot
        // learn a non-stationary burst, so a slow downward expander removes it without
        // touching speech (narration sits above -30 dBFS).
        const val GATE_OPEN_DB = -38.0
        const val GATE_CLOSE_DB = -52.0
        const val GATE_FLOOR = 0.03f
        const val GATE_SMOOTH = 0.25f

        // Below this, biquad state is flushed to zero (denormal arithmetic is slow on ARM).
        const val DENORMAL_EPS = 1e-25f
    }

    private val fs = if (sampleRateHz > 0) sampleRateHz else 24000

    // ---- STFT buffers ----
    private val re = FloatArray(FRAME)
    private val im = FloatArray(FRAME)
    private val win = FloatArray(FRAME)
    private val frame = FloatArray(FRAME)       // raw (HPF'd) input history, oldest at index 0
    private var fill = 0
    private val acc = FloatArray(FRAME)         // overlap-add accumulator
    private val outBlock = FloatArray(HOP)
    private var outPos = HOP                    // HOP == block empty

    // ---- noise tracker ----
    private val psm = FloatArray(BINS)
    private val ring = Array(NOISE_FRAMES) { FloatArray(BINS) }
    private var ringSlot = 0
    private val minPow = FloatArray(BINS)
    private val noiseEst = FloatArray(BINS)

    // ---- gain state ----
    private val gain = FloatArray(BINS)
    private val gainTmp = FloatArray(BINS)
    private val gainPrev = FloatArray(BINS)
    private val gammaPrev = FloatArray(BINS)

    private var frameCount = 0
    private var wet = 0f
    private var gate = 1f
    @Volatile private var resetPending = false

    // ---- FFT tables ----
    private val rev = IntArray(FRAME)
    private val cosT = FloatArray(FRAME / 2)
    private val sinT = FloatArray(FRAME / 2)

    // ---- biquads ----
    private var hpB0 = 1f; private var hpB1 = 0f; private var hpB2 = 0f
    private var hpA1 = 0f; private var hpA2 = 0f
    private var hpX1 = 0f; private var hpX2 = 0f; private var hpY1 = 0f; private var hpY2 = 0f
    private var lpB0 = 1f; private var lpB1 = 0f; private var lpB2 = 0f
    private var lpA1 = 0f; private var lpA2 = 0f
    private var lpX1 = 0f; private var lpX2 = 0f; private var lpY1 = 0f; private var lpY2 = 0f

    init {
        // periodic sqrt-Hann: win^2 is a periodic Hann, whose 50 %-hop overlap-add is exactly 1.
        for (i in 0 until FRAME) {
            win[i] = sqrt(0.5 * (1.0 - cos(2.0 * PI * i / FRAME))).toFloat()
        }
        var bits = 0
        while ((1 shl bits) < FRAME) bits++
        for (i in 0 until FRAME) {
            var r = 0
            var x = i
            for (b in 0 until bits) {
                r = (r shl 1) or (x and 1)
                x = x shr 1
            }
            rev[i] = r
        }
        for (i in 0 until FRAME / 2) {
            val a = 2.0 * PI * i / FRAME
            cosT[i] = cos(a).toFloat()
            sinT[i] = sin(a).toFloat()
        }
        designHighPass(HPF_HZ)
        designLowPass(LPF_HZ)
    }

    // ------------------------------------------------------------------ public API

    /**
     * Denoise one chunk of mono float samples. Any length is accepted; overlap and all estimator
     * state are carried across calls, so streaming chunk boundaries are inaudible. Returns a new
     * array of the same length. Non-finite input samples are treated as silence.
     */
    @Synchronized
    fun process(samples: FloatArray): FloatArray {
        if (resetPending) {
            resetPending = false
            clearState()
        }
        val n = samples.size
        val out = FloatArray(n)
        var oi = 0
        var i = 0
        while (i < n) {
            var x = samples[i]
            if (!x.isFinite()) x = 0f
            x = highPass(x)
            if (LPF_ENABLED) x = lowPass(x)

            frame[fill++] = x
            if (fill == FRAME) {
                analyseSynthesise()
                System.arraycopy(frame, HOP, frame, 0, FRAME - HOP)
                fill = FRAME - HOP
                outPos = 0
            }
            out[oi++] = if (outPos < HOP) outBlock[outPos++] else 0f
            i++
        }
        return out
    }

    /**
     * Clears every filter, estimator and overlap-add buffer. Call on seek / stop / new clip.
     * Lock-free: the clear happens at the start of the next [process] call, so a UI-thread reset
     * never waits for a chunk that is already being denoised.
     */
    fun reset() {
        resetPending = true
    }

    private fun clearState() {
        java.util.Arrays.fill(frame, 0f)
        java.util.Arrays.fill(acc, 0f)
        java.util.Arrays.fill(outBlock, 0f)
        java.util.Arrays.fill(psm, 0f)
        java.util.Arrays.fill(minPow, 0f)
        java.util.Arrays.fill(noiseEst, 0f)
        java.util.Arrays.fill(gain, 0f)
        java.util.Arrays.fill(gainTmp, 0f)
        java.util.Arrays.fill(gainPrev, 0f)
        java.util.Arrays.fill(gammaPrev, 0f)
        for (slot in ring) java.util.Arrays.fill(slot, 0f)
        ringSlot = 0
        fill = 0
        outPos = HOP
        frameCount = 0
        wet = 0f
        gate = 1f
        hpX1 = 0f; hpX2 = 0f; hpY1 = 0f; hpY2 = 0f
        lpX1 = 0f; lpX2 = 0f; lpY1 = 0f; lpY2 = 0f
    }

    // ------------------------------------------------------------------ STFT frame

    private fun analyseSynthesise() {
        // ---- analysis window ----
        for (i in 0 until FRAME) {
            re[i] = frame[i] * win[i]
            im[i] = 0f
        }
        fft(re, im, false)

        // ---- smoothed periodogram + sliding minimum (noise floor) ----
        val cur = ring[ringSlot]
        for (k in 0 until BINS) {
            val rr = re[k]
            val ii = im[k]
            val p = rr * rr + ii * ii
            val ps = PS_SMOOTH * psm[k] + (1f - PS_SMOOTH) * p
            psm[k] = ps
            cur[k] = ps
        }
        if (frameCount == 0) {
            // Bootstrap: assume the first frame is ~10 dB above the noise floor rather than
            // representative of it. Treating it as the floor suppressed the first second of
            // speech by ~14 dB (measured); a quiet prior lets speech through while the
            // sliding minimum still settles within ~1 s.
            for (s in 0 until NOISE_FRAMES) {
                if (s != ringSlot) {
                    for (k in 0 until BINS) ring[s][k] = cur[k] * 0.1f
                }
            }
        }
        var k = 0
        while (k < BINS) {
            var m = Float.MAX_VALUE
            var s = 0
            while (s < NOISE_FRAMES) {
                val v = ring[s][k]
                if (v < m) m = v
                s++
            }
            minPow[k] = m
            k++
        }
        ringSlot++
        if (ringSlot == NOISE_FRAMES) ringSlot = 0

        for (k in 0 until BINS) {
            noiseEst[k] = NOISE_EST_SMOOTH * noiseEst[k] +
                (1f - NOISE_EST_SMOOTH) * (minPow[k] * MIN_BIAS)
        }

        // ---- decision-directed Wiener gain ----
        for (k in 0 until BINS) {
            val rr = re[k]
            val ii = im[k]
            val p = rr * rr + ii * ii
            var denom = noiseEst[k] * OVER_SUB
            if (!(denom > 1e-12f)) denom = 1e-12f
            var gamma = p / denom
            if (!gamma.isFinite() || gamma < 0f) gamma = 0f
            val post = if (gamma > 1f) gamma - 1f else 0f
            var xi = DD_ALPHA * gainPrev[k] * gainPrev[k] * gammaPrev[k] +
                (1f - DD_ALPHA) * post
            if (!xi.isFinite() || xi < 0f) xi = 0f
            var g = xi / (1f + xi)
            if (!g.isFinite()) g = 1f
            if (g < GAIN_FLOOR) g = GAIN_FLOOR
            g = GAIN_TIME * gainPrev[k] + (1f - GAIN_TIME) * g
            gammaPrev[k] = gamma
            gainTmp[k] = g
        }

        // 3-tap smoothing across frequency, clamped to the floor.
        gain[0] = gainTmp[0]
        for (k in 1 until BINS - 1) {
            var g = gainTmp[k] + GAIN_FREQ * (gainTmp[k - 1] + gainTmp[k + 1] - 2f * gainTmp[k])
            if (g < GAIN_FLOOR) g = GAIN_FLOOR
            if (g > 1f) g = 1f
            gain[k] = g
        }
        gain[BINS - 1] = gainTmp[BINS - 1]
        for (k in 0 until BINS) gainPrev[k] = gain[k]

        // ---- apply gain (real-signal symmetry) ----
        re[0] *= gain[0]
        im[0] *= gain[0]
        re[BINS - 1] *= gain[BINS - 1]
        im[BINS - 1] *= gain[BINS - 1]
        for (k in 1 until BINS - 1) {
            val g = gain[k]
            re[k] *= g
            im[k] *= g
            val m = FRAME - k
            re[m] *= g
            im[m] *= g
        }

        fft(re, im, true)

        // ---- synthesis window + overlap-add ----
        for (i in 0 until FRAME) {
            acc[i] += re[i] * win[i]
        }

        // The oldest HOP accumulator samples are now complete; blend against dry input while the
        // noise estimate is still warming up.
        val c = wet
        for (i in 0 until HOP) {
            val dry = frame[i]
            var y = dry + c * (acc[i] - dry)
            if (!y.isFinite()) y = 0f
            if (y > 1f) y = 1f
            if (y < -1f) y = -1f
            outBlock[i] = y
        }
        System.arraycopy(acc, HOP, acc, 0, FRAME - HOP)
        java.util.Arrays.fill(acc, FRAME - HOP, FRAME, 0f)

        // Slow downward expander: open for speech, close for sub-speech residual.
        var sum = 0.0
        for (i in 0 until HOP) {
            val y = outBlock[i]
            sum += y.toDouble() * y
        }
        val rms = kotlin.math.sqrt(sum / HOP)
        val db = 20.0 * kotlin.math.log10(rms + 1e-9)
        val target = when {
            db > GATE_OPEN_DB -> 1f
            db < GATE_CLOSE_DB -> GATE_FLOOR
            else -> ((db - GATE_CLOSE_DB) / (GATE_OPEN_DB - GATE_CLOSE_DB)).toFloat()
        }
        gate += (target - gate) * GATE_SMOOTH
        for (i in 0 until HOP) outBlock[i] *= gate

        frameCount++
        if (wet < 1f) {
            wet += 1f / WET_RAMP_FRAMES
            if (wet > 1f) wet = 1f
        }
    }

    // ------------------------------------------------------------------ FFT

    private fun fft(re: FloatArray, im: FloatArray, inverse: Boolean) {
        for (i in 0 until FRAME) {
            val j = rev[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= FRAME) {
            val half = len shr 1
            val step = FRAME / len
            var i = 0
            while (i < FRAME) {
                var k = 0
                var j = i
                val end = i + half
                while (j < end) {
                    val wr = cosT[k]
                    val wi = if (inverse) sinT[k] else -sinT[k]
                    val ur = re[j]
                    val ui = im[j]
                    val vr0 = re[j + half]
                    val vi0 = im[j + half]
                    val vr = vr0 * wr - vi0 * wi
                    val vi = vr0 * wi + vi0 * wr
                    re[j] = ur + vr
                    im[j] = ui + vi
                    re[j + half] = ur - vr
                    im[j + half] = ui - vi
                    k += step
                    j++
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) {
            val s = 1f / FRAME
            for (i in 0 until FRAME) {
                re[i] *= s
                im[i] *= s
            }
        }
    }

    // ------------------------------------------------------------------ biquads

    private fun designHighPass(hz: Double) {
        val w0 = 2.0 * PI * hz / fs
        val cw = cos(w0)
        val sw = sin(w0)
        val alpha = sw / (2.0 * 0.70710678)
        val a0 = 1.0 + alpha
        hpB0 = ((1.0 + cw) / 2.0 / a0).toFloat()
        hpB1 = (-(1.0 + cw) / a0).toFloat()
        hpB2 = ((1.0 + cw) / 2.0 / a0).toFloat()
        hpA1 = (-2.0 * cw / a0).toFloat()
        hpA2 = ((1.0 - alpha) / a0).toFloat()
    }

    private fun designLowPass(hz: Double) {
        val w0 = 2.0 * PI * hz / fs
        val cw = cos(w0)
        val sw = sin(w0)
        val alpha = sw / (2.0 * 0.70710678)
        val a0 = 1.0 + alpha
        lpB0 = ((1.0 - cw) / 2.0 / a0).toFloat()
        lpB1 = ((1.0 - cw) / a0).toFloat()
        lpB2 = ((1.0 - cw) / 2.0 / a0).toFloat()
        lpA1 = (-2.0 * cw / a0).toFloat()
        lpA2 = ((1.0 - alpha) / a0).toFloat()
    }

    private fun highPass(x: Float): Float {
        var y = hpB0 * x + hpB1 * hpX1 + hpB2 * hpX2 - hpA1 * hpY1 - hpA2 * hpY2
        // Flush tiny IIR tails: denormal floats are very slow on some ARM cores.
        if (y > -DENORMAL_EPS && y < DENORMAL_EPS) y = 0f
        hpX2 = hpX1; hpX1 = x
        hpY2 = hpY1; hpY1 = y
        return y
    }

    private fun lowPass(x: Float): Float {
        var y = lpB0 * x + lpB1 * lpX1 + lpB2 * lpX2 - lpA1 * lpY1 - lpA2 * lpY2
        if (y > -DENORMAL_EPS && y < DENORMAL_EPS) y = 0f
        lpX2 = lpX1; lpX1 = x
        lpY2 = lpY1; lpY1 = y
        return y
    }
}
