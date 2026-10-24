import app.novelspeak.AudioDsp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Host-side regression for the streaming denoiser + low-level noise gate, plus the P1.5
 * skip-silence trimmer ([AudioDsp.trimSilence]).
 *
 *   kotlinc app/src/app/novelspeak/AudioDsp.kt tools/audio-dsp-test/TestMain.kt \
 *     -include-runtime -d /tmp/audio-dsp-test.jar
 *   java -jar /tmp/audio-dsp-test.jar
 *
 * Part 1 verifies that a -45 dBFS noise bed between two speech-level tones is attenuated
 * (the "little noise" between words) while the tones keep their level. Part 2 verifies leading/
 * trailing silence detection: ~10 ms pad, min-silence gate, per-side flags, threshold behavior,
 * all-silent/short-buffer safety and sample preservation.
 */
fun main() {
    val sr = 24000
    fun tone(seconds: Double, amp: Float, freq: Double = 440.0): FloatArray {
        val n = (sr * seconds).toInt()
        return FloatArray(n) { (amp * sin(2.0 * PI * freq * it / sr)).toFloat() }
    }
    fun noise(seconds: Double, amp: Float, seed: Int = 7): FloatArray {
        val n = (sr * seconds).toInt()
        var x = seed
        return FloatArray(n) {
            x = x * 1103515245 + 12345
            amp * (((x ushr 16) and 0x7FFF) / 16384f - 1f)
        }
    }
    fun rms(s: FloatArray, from: Int, to: Int): Double {
        var sum = 0.0
        for (i in from until to) sum += s[i].toDouble() * s[i]
        return sqrt(sum / (to - from).coerceAtLeast(1))
    }
    fun db(v: Double) = 20.0 * log10(v + 1e-9)

    // Realistic ordering: quiet lead-in (lets the noise estimate settle), speech,
    // then a long noise bed between phrases, then speech again.
    val lead = noise(0.60, 0.003f)       // ≈ -55 dBFS
    val a = tone(0.40, 0.20f)
    val n = noise(2.00, 0.008f)          // ≈ -45 dBFS
    val b = tone(0.50, 0.20f)
    val input = lead + a + n + b

    // Streaming: the DSP must carry state across arbitrary chunk boundaries.
    val dsp = AudioDsp(sr)
    val out = FloatArray(input.size)
    var off = 0
    while (off < input.size) {
        val len = minOf(4096, input.size - off)
        val chunk = input.copyOfRange(off, off + len)
        val processed = dsp.process(chunk)
        System.arraycopy(processed, 0, out, off, len)
        off += len
    }

    val aFrom = (sr * 0.75).toInt()
    val aTo = (sr * 0.95).toInt()
    val nFrom = (sr * 2.30).toInt()
    val nTo = (sr * 2.60).toInt()
    val bFrom = (sr * 3.20).toInt()
    val bTo = (sr * 3.40).toInt()

    val inToneA = db(rms(input, aFrom, aTo))
    val outToneA = db(rms(out, aFrom, aTo))
    val inNoise = db(rms(input, nFrom, nTo))
    val outNoise = db(rms(out, nFrom, nTo))
    val inToneB = db(rms(input, bFrom, bTo))
    val outToneB = db(rms(out, bFrom, bTo))

    println("tone A  in=${"%.1f".format(inToneA)} out=${"%.1f".format(outToneA)} dBFS")
    println("noise   in=${"%.1f".format(inNoise)} out=${"%.1f".format(outNoise)} dBFS")
    println("tone B  in=${"%.1f".format(inToneB)} out=${"%.1f".format(outToneB)} dBFS")
    println("noise attenuation=${"%.1f".format(inNoise - outNoise)} dB")
    println("tone loss A=${"%.2f".format(inToneA - outToneA)} B=${"%.2f".format(inToneB - outToneB)} dB")

    var failures = 0
    fun check(name: String, ok: Boolean, detail: String = "") {
        println((if (ok) "PASS " else "FAILED ") + name + if (!ok && detail.isNotEmpty()) " $detail" else "")
        if (!ok) failures++
    }
    check("noise attenuated >= 12 dB", inNoise - outNoise >= 12.0)
    check("tone A preserved (<= 1.5 dB loss)", inToneA - outToneA <= 1.5)
    check("tone B preserved (<= 1.5 dB loss)", inToneB - outToneB <= 1.5)
    check("output finite", out.all { it.isFinite() })
    // =====================================================================================
    // Part 2: skip-silence trimming (P1.5)
    // =====================================================================================
    // Threshold used by the default trimSilence config (-45 dBFS), mirrored here for assertions.
    val trimThreshold = Math.pow(10.0, -45.0 / 20.0).toFloat()
    val padSamples = sr / 100 // 10 ms pad at 24 kHz
    fun silenceAt(rate: Int, seconds: Double) = FloatArray((rate * seconds).toInt())
    fun toneAt(rate: Int, seconds: Double, amp: Float, freq: Double = 440.0): FloatArray {
        val n = (rate * seconds).toInt()
        return FloatArray(n) { (amp * sin(2.0 * PI * freq * it / rate)).toFloat() }
    }
    fun concat(vararg parts: FloatArray): FloatArray {
        var n = 0
        for (p in parts) n += p.size
        val out = FloatArray(n)
        var off = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, off, p.size)
            off += p.size
        }
        return out
    }
    fun above(v: Float) = v.isFinite() && abs(v) > trimThreshold
    fun firstSpeech(s: FloatArray): Int {
        for (i in s.indices) if (above(s[i])) return i
        return -1
    }
    fun lastSpeech(s: FloatArray): Int {
        for (i in s.indices.reversed()) if (above(s[i])) return i
        return -1
    }

    // 0.50 s lead + 0.40 s "speech" + 0.80 s tail: both sides must go, with a ~10 ms pad kept.
    val speech = toneAt(sr, 0.40, 0.25f)
    val padded = concat(silenceAt(sr, 0.50), speech, silenceAt(sr, 0.80))
    val trimmed = AudioDsp.trimSilence(padded, sr)
    check("trim reduces length", trimmed.size < padded.size)
    check("trim keeps speech", trimmed.size >= speech.size)
    check("trim non-empty for non-silent input", trimmed.isNotEmpty())
    val firstInTrimmed = firstSpeech(trimmed)
    val lastInTrimmed = lastSpeech(trimmed)
    check(
        "trim keeps ~10ms pad before speech",
        firstInTrimmed in 1..(padSamples + 2),
        "first=$firstInTrimmed pad=$padSamples"
    )
    check(
        "trim keeps ~10ms pad after speech",
        lastInTrimmed >= 0 && (trimmed.size - 1 - lastInTrimmed) in (padSamples - 2)..(padSamples + 2),
        "tail=${trimmed.size - 1 - lastInTrimmed} pad=$padSamples"
    )
    // The retained samples are the original ones (no resampling/attenuation).
    val firstSpeechInPadded = firstSpeech(padded)
    check(
        "trim preserves samples",
        firstSpeechInPadded >= padSamples && firstInTrimmed >= 1 &&
            trimmed[firstInTrimmed] == padded[firstSpeechInPadded]
    )

    // No trim when the buffer is already tight (no long silent runs).
    check("already-tight untouched", AudioDsp.trimSilence(speech, sr).size == speech.size)

    // minSilenceMs gates short lead-ins: a 50 ms lead (below the 120 ms default) is kept.
    val shortLead = concat(silenceAt(sr, 0.05), toneAt(sr, 0.20, 0.25f))
    check("min-silence keeps short lead", AudioDsp.trimSilence(shortLead, sr).size == shortLead.size)
    check(
        "min-silence=0 trims short lead",
        AudioDsp.trimSilence(shortLead, sr, minSilenceMs = 0).size < shortLead.size
    )

    // Per-side flags: Sherpa needs leading-only on the first chunk and trailing-only on the last.
    val leadOnly = AudioDsp.trimSilence(padded, sr, trimStart = false)
    val trailOnly = AudioDsp.trimSilence(padded, sr, trimEnd = false)
    check("trimStart=false keeps lead", leadOnly.size > trimmed.size)
    check("trimEnd=false keeps tail", trailOnly.size > trimmed.size)
    check("leadOnly starts with kept silence", firstSpeech(leadOnly) > padSamples)

    // Threshold: a sub-threshold (-62 dBFS) noise bed is silence, the speech-level tone is not.
    val quietBed = concat(noise(0.50, 0.0008f), toneAt(sr, 0.20, 0.25f), silenceAt(sr, 0.40))
    val bedTrimmed = AudioDsp.trimSilence(quietBed, sr)
    check("threshold trims sub-threshold bed", bedTrimmed.size < quietBed.size)
    check("threshold keeps speech", bedTrimmed.size >= (sr * 0.20).toInt())

    // Safety: empty / all-silent / sub-10ms buffers must never throw or mis-trim.
    check("empty buffer safe", AudioDsp.trimSilence(FloatArray(0), sr).isEmpty())
    check("all-silent returns empty", AudioDsp.trimSilence(silenceAt(sr, 0.50), sr).isEmpty())
    check("tiny-silent safe", AudioDsp.trimSilence(FloatArray(5) { 0f }, sr).isEmpty())
    val tinySpeech = FloatArray(5) { if (it == 2) 0.5f else 0f }
    check("tiny-speech kept", AudioDsp.trimSilence(tinySpeech, sr).size == 5)
    check("negative sampleRate safe", AudioDsp.trimSilence(padded, -1).size < padded.size)
    check("NaN threshold safe", AudioDsp.trimSilence(FloatArray(0), sr, Float.NaN).isEmpty())

    // Non-24 kHz rate: pad derives from the actual sample rate.
    val sr16 = 16000
    val padded16 = concat(
        silenceAt(sr16, 0.30),
        toneAt(sr16, 0.25, 0.25f),
        silenceAt(sr16, 0.50)
    )
    val trimmed16 = AudioDsp.trimSilence(padded16, sr16)
    val firstIn16 = firstSpeech(trimmed16)
    check("16k trims both sides", trimmed16.size < padded16.size)
    check(
        "16k pad scales with rate",
        firstIn16 in 1..(sr16 / 100 + 2),
        "first=$firstIn16 pad=${sr16 / 100}"
    )

    if (failures > 0) {
        println("$failures FAILURES")
        kotlin.system.exitProcess(1)
    }
    println("ALL PASS")
}
