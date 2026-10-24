import app.novelspeak.AudioDsp
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Host-side regression for the streaming denoiser + low-level noise gate.
 *
 *   kotlinc app/src/app/novelspeak/AudioDsp.kt tools/audio-dsp-test/TestMain.kt \
 *     -include-runtime -d /tmp/audio-dsp-test.jar
 *   java -jar /tmp/audio-dsp-test.jar
 *
 * Verifies that a -45 dBFS noise bed between two speech-level tones is attenuated
 * (the "little noise" between words) while the tones keep their level.
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
    fun check(name: String, ok: Boolean) {
        println((if (ok) "PASS " else "FAILED ") + name)
        if (!ok) failures++
    }
    check("noise attenuated >= 12 dB", inNoise - outNoise >= 12.0)
    check("tone A preserved (<= 1.5 dB loss)", inToneA - outToneA <= 1.5)
    check("tone B preserved (<= 1.5 dB loss)", inToneB - outToneB <= 1.5)
    check("output finite", out.all { it.isFinite() })
    if (failures > 0) {
        println("$failures FAILURES")
        kotlin.system.exitProcess(1)
    }
    println("ALL PASS")
}
