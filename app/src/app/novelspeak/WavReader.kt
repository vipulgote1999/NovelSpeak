package app.novelspeak

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

/**
 * Minimal RIFF/WAVE reader + cleanup chain, mirroring NekoSpeak's `loadAudioFile()` /
 * `encodeVoiceFromWav()` preprocessing (MIT):
 *
 *   decode PCM 8/16/24/32-bit or float32 -> mix to mono -> resample to 24 kHz (linear)
 *   -> peak normalize -> 80 Hz one-pole high-pass -> trim silence (480-sample windows, 0.02)
 *   -> peak normalize again -> truncate to 30 s (720,000 samples)
 *
 * The same chain is what feeds Pocket's speaker encoder and what we feed to
 * `GenerationConfig.referenceAudio`, so cloned/default voices behave the same as in the app it was
 * verified in.
 */
object WavReader {

    const val TARGET_RATE = 24000
    const val MAX_SAMPLES = 720000 // 30 s @ 24 kHz, NekoSpeak's hard cap

    /** Reads any supported WAV and returns 24 kHz mono float samples, or null. */
    fun readForCloning(file: File): FloatArray? = try {
        val bytes = file.readBytes()
        val pcm = parse(bytes) ?: return null
        var s = pcm.samples
        if (pcm.sampleRate != TARGET_RATE) s = resample(s, pcm.sampleRate, TARGET_RATE)
        s = peakNormalize(s)
        s = highPass(s, 80f, TARGET_RATE)
        s = trimSilence(s, 0.02f)
        s = peakNormalize(s)
        if (s.size > MAX_SAMPLES) s.copyOf(MAX_SAMPLES) else s
    } catch (e: Throwable) {
        null
    }

    class Pcm(val samples: FloatArray, val sampleRate: Int)

    fun parse(b: ByteArray): Pcm? {
        if (b.size < 44) return null
        if (String(b, 0, 4, Charsets.US_ASCII) != "RIFF" || String(b, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        var pos = 12
        var fmt = 1
        var channels = 1
        var rate = 24000
        var bits = 16
        var dataOff = -1
        var dataLen = 0
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4, Charsets.US_ASCII)
            val len = le32(b, pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> if (body + 16 <= b.size) {
                    fmt = le16(b, body)
                    channels = max(1, le16(b, body + 2))
                    rate = le32(b, body + 4).coerceAtLeast(8000)
                    bits = le16(b, body + 14)
                }
                "data" -> {
                    dataOff = body
                    dataLen = minOf(len, b.size - body)
                }
            }
            pos = body + len + (len and 1)
            if (dataOff >= 0) break
        }
        if (dataOff < 0 || dataLen <= 0) return null
        val bytesPer = bits / 8
        if (bytesPer <= 0) return null
        val frameBytes = bytesPer * channels
        if (frameBytes <= 0) return null
        val frames = dataLen / frameBytes
        val out = FloatArray(frames)
        val isFloat = fmt == 3
        for (i in 0 until frames) {
            var acc = 0f
            for (c in 0 until channels) {
                acc += sample(b, dataOff + i * frameBytes + c * bytesPer, bits, isFloat)
            }
            out[i] = acc / channels
        }
        var peak = 0f
        for (v in out) peak = max(peak, abs(v))
        if (peak > 1f) for (i in out.indices) out[i] = out[i] / peak
        return Pcm(out, rate)
    }

    private fun sample(b: ByteArray, off: Int, bits: Int, isFloat: Boolean): Float {
        if (isFloat && bits == 32) return ByteBuffer.wrap(b, off, 4).order(ByteOrder.LITTLE_ENDIAN).float
        return when (bits) {
            8 -> ((b[off].toInt() and 0xFF) - 128) / 128f
            16 -> le16s(b, off) / 32768f
            24 -> {
                val v = (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or ((b[off + 2].toInt() and 0xFF) shl 16)
                val signed = if (v and 0x800000 != 0) v or -0x1000000 else v
                signed / 8388608f
            }
            32 -> le32(b, off).toFloat() / 2147483648f
            else -> 0f
        }
    }

    fun resample(input: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || input.isEmpty()) return input
        val outLen = (input.size.toLong() * to / from).toInt().coerceAtLeast(1)
        val out = FloatArray(outLen)
        val step = from.toDouble() / to
        for (i in 0 until outLen) {
            val src = i * step
            val i0 = src.toInt()
            val i1 = minOf(i0 + 1, input.size - 1)
            val t = (src - i0).toFloat()
            out[i] = input[i0] * (1 - t) + input[i1] * t
        }
        return out
    }

    fun peakNormalize(s: FloatArray): FloatArray {
        var peak = 0f
        for (v in s) peak = max(peak, abs(v))
        if (peak <= 0f || peak == 1f) return s
        val out = FloatArray(s.size)
        for (i in s.indices) out[i] = s[i] / peak
        return out
    }

    fun highPass(s: FloatArray, hz: Float, rate: Int): FloatArray {
        if (s.isEmpty()) return s
        val rc = 1f / (hz * 6.2831855f)
        val a = rc / ((1f / rate) + rc)
        val out = FloatArray(s.size)
        out[0] = s[0]
        for (i in 1 until s.size) out[i] = ((out[i - 1] + s[i]) - s[i - 1]) * a
        return out
    }

    fun trimSilence(s: FloatArray, threshold: Float): FloatArray {
        if (s.isEmpty()) return s
        val win = 480
        var start = 0
        var end = s.size
        var i = 0
        while (i + win <= s.size) {
            var sum = 0f
            for (k in i until i + win) sum += abs(s[k])
            if (sum / win > threshold) { start = i; break }
            i += win
        }
        var j = s.size - win
        while (j >= 0) {
            var sum = 0f
            for (k in j until j + win) sum += abs(s[k])
            if (sum / win > threshold) { end = j + win; break }
            j -= win
        }
        if (end <= start) return s
        return s.copyOfRange(start, end)
    }

    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun le16s(b: ByteArray, o: Int): Int {
        val v = le16(b, o)
        return if (v and 0x8000 != 0) v or -0x10000 else v
    }
    private fun le32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
                ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
}
