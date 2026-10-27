package app.novelspeak

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * Optional random-access capability for [WavSink] targets that are not a
 * [ByteArrayOutputStream] or a [FileOutputStream] (for example a custom file wrapper).
 * [writeAt] must overwrite bytes at an absolute offset without moving subsequent writes.
 */
interface RandomAccessSink {
    fun writeAt(offset: Long, data: ByteArray)
}

/**
 * Streaming 16-bit PCM WAV writer (24 kHz mono by default, matching the TTS pipeline).
 *
 * Usage: create one per export, call [write] (ShortArray samples) or [writePcm16] (raw
 * little-endian PCM bytes) as audio arrives, then [close]. Nothing is buffered in memory beyond
 * small conversion chunks, so a one-hour chapter can be written with flat memory use.
 *
 * The RIFF/WAVE header is a fixed 44-byte block written at creation; [close] patches the two
 * size fields by counting the bytes actually written. Patching needs a seekable target:
 * [ByteArrayOutputStream], [FileOutputStream] and any [RandomAccessSink] are supported. For any
 * other [OutputStream] the header is written up-front with the streaming "unknown size"
 * convention (0xFFFFFFFF) because a plain stream cannot be rewound — check [exactHeaderSizes].
 *
 * Not part of the host-tested surface of Android: this file is pure JVM and is covered by
 * `tools/feature-test/TestMain.kt`.
 *
 * @param out destination stream; for a correct header pass a seekable target (see above).
 * @param sampleRate Hz; invalid values fall back to [DEFAULT_SAMPLE_RATE].
 * @param channels 1..8; invalid values fall back to [DEFAULT_CHANNELS].
 * @param bits 8/16/24/32; invalid values fall back to [DEFAULT_BITS]. [write] only emits 16-bit
 *   samples, so pass 16 for the normal TTS path.
 * @param closeUnderlying close [out] as part of [close] (default true). Pass false when the
 *   caller owns the stream and will close it after [close].
 */
class WavSink(
    private val out: OutputStream,
    sampleRate: Int = DEFAULT_SAMPLE_RATE,
    channels: Int = DEFAULT_CHANNELS,
    bits: Int = DEFAULT_BITS,
    private val closeUnderlying: Boolean = true
) : Closeable {

    companion object {
        /** RIFF/WAVE header bytes: RIFF(4) + size(4) + WAVE(4) + fmt(24) + data(8). */
        const val HEADER_BYTES = 44
        const val DEFAULT_SAMPLE_RATE = 24000
        const val DEFAULT_CHANNELS = 1
        const val DEFAULT_BITS = 16

        /** The RIFF chunk-size field is unsigned 32-bit; max data payload it can describe. */
        const val MAX_DATA_BYTES: Long = 0xFFFFFFFFL - HEADER_BYTES

        private const val UNKNOWN_SIZE: Long = 0xFFFFFFFFL

        /**
         * Bytes a WAV file will occupy for [durationMs] of audio (header + PCM payload).
         * 1 h at 24 kHz mono 16-bit is 172,844,044 bytes (~165 MiB / ~173 MB decimal).
         */
        fun estimatedBytes(
            durationMs: Long,
            sampleRate: Int = DEFAULT_SAMPLE_RATE,
            channels: Int = DEFAULT_CHANNELS,
            bits: Int = DEFAULT_BITS
        ): Long {
            if (durationMs <= 0L) return HEADER_BYTES.toLong()
            val sr = if (sampleRate > 0) sampleRate else DEFAULT_SAMPLE_RATE
            val ch = if (channels in 1..8) channels else DEFAULT_CHANNELS
            val b = if (bits in intArrayOf(8, 16, 24, 32)) bits else DEFAULT_BITS
            return HEADER_BYTES + durationMs * sr * ch * (b / 8) / 1000L
        }
    }

    /** Effective sample rate written into the header (validated in the constructor). */
    val sampleRate: Int = if (sampleRate > 0) sampleRate else DEFAULT_SAMPLE_RATE

    /** Effective channel count written into the header (validated in the constructor). */
    val channels: Int = if (channels in 1..8) channels else DEFAULT_CHANNELS

    /** Effective bits per sample written into the header (validated in the constructor). */
    val bits: Int = if (bits in intArrayOf(8, 16, 24, 32)) bits else DEFAULT_BITS

    /** True when [close] can patch the exact sizes (seekable target). */
    val exactHeaderSizes: Boolean =
        out is ByteArrayOutputStream || out is FileOutputStream || out is RandomAccessSink

    private val blockAlign: Int = channels * (bits / 8)
    private val lock = Any()
    private val conversion = ByteArray(8192)
    private var dataBytes: Long = 0L
    private var closed = false

    init {
        out.write(buildHeader(if (exactHeaderSizes) 0L else UNKNOWN_SIZE))
    }

    /** PCM payload bytes written so far (does not include the 44-byte header). */
    val pcmBytes: Long
        get() = synchronized(lock) { dataBytes }

    /** Playback duration of the audio written so far, in milliseconds (rounded down). */
    fun durationMs(): Long {
        val bytes = pcmBytes
        if (bytes <= 0L) return 0L
        val bytesPerSecond = sampleRate.toLong() * blockAlign
        return if (bytesPerSecond <= 0L) 0L else bytes * 1000L / bytesPerSecond
    }

    /**
     * Writes mono/stereo/interleaved 16-bit samples as little-endian PCM.
     * @throws IllegalStateException after [close] or when the sink was opened with bits != 16.
     */
    fun write(samples: ShortArray) {
        checkOpen()
        if (bits != 16) {
            throw IllegalStateException("write(ShortArray) needs bits=16, this sink is bits=$bits")
        }
        if (samples.isEmpty()) return
        synchronized(lock) {
            var offset = 0
            val maxPerChunk = conversion.size / 2
            while (offset < samples.size) {
                val count = minOf(samples.size - offset, maxPerChunk)
                for (i in 0 until count) {
                    val s = samples[offset + i].toInt()
                    conversion[i * 2] = (s and 0xFF).toByte()
                    conversion[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                }
                out.write(conversion, 0, count * 2)
                dataBytes += count * 2L
                offset += count
            }
        }
    }

    /**
     * Writes the first [len] bytes of [bytes] verbatim as PCM. [len] is clamped to the array
     * size, so a truncated final chunk never throws. Odd [len] values are written as-is; the
     * normal pipeline always writes whole 16-bit samples.
     */
    fun writePcm16(bytes: ByteArray, len: Int = bytes.size) {
        checkOpen()
        val n = len.coerceIn(0, bytes.size)
        if (n == 0) return
        synchronized(lock) {
            out.write(bytes, 0, n)
            dataBytes += n.toLong()
        }
    }

    /**
     * Flushes pending audio and patches the RIFF/WAVE sizes. Idempotent; after the first call
     * the sink is closed and subsequent writes throw.
     */
    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            try {
                val dataSize = if (dataBytes > MAX_DATA_BYTES) UNKNOWN_SIZE else dataBytes
                patchHeader(buildHeader(dataSize))
                out.flush()
            } catch (ignored: Throwable) {
                // A failing flush on a half-full disk must not mask the original export error;
                // the caller reports the export as failed either way.
            } finally {
                if (closeUnderlying) {
                    try {
                        out.close()
                    } catch (ignored: Throwable) {
                    }
                }
            }
        }
    }

    private fun checkOpen() {
        synchronized(lock) {
            if (closed) throw IllegalStateException("WavSink is closed")
        }
    }

    private fun patchHeader(header: ByteArray) {
        when (val target = out) {
            is ByteArrayOutputStream -> {
                // The buffer already holds placeholder+payload; rewrite it in place. This is the
                // host-test path and it keeps the "no full buffering by the sink" rule (the
                // target itself is the buffer).
                val all = target.toByteArray()
                val payload = all.size - HEADER_BYTES
                target.reset()
                target.write(header)
                if (payload > 0) target.write(all, HEADER_BYTES, payload)
            }
            is FileOutputStream -> {
                val channel = target.channel
                val end = channel.position()
                channel.position(0L)
                channel.write(ByteBuffer.wrap(header))
                channel.position(end)
            }
            is RandomAccessSink -> target.writeAt(0L, header)
            else -> {
                // Non-seekable: the streaming header written at creation already advertises the
                // unknown-size convention; there is nothing that can be patched.
            }
        }
    }

    private fun buildHeader(dataSize: Long): ByteArray {
        val b = ByteArray(HEADER_BYTES)
        val unknown = dataSize < 0L || dataSize >= UNKNOWN_SIZE
        val payload = if (unknown) UNKNOWN_SIZE else dataSize
        val riffSize = if (unknown) UNKNOWN_SIZE else HEADER_BYTES - 8 + payload
        writeAscii(b, 0, "RIFF")
        writeIntLe(b, 4, riffSize)
        writeAscii(b, 8, "WAVE")
        writeAscii(b, 12, "fmt ")
        writeIntLe(b, 16, 16L) // fmt chunk size for PCM
        writeShortLe(b, 20, 1) // audio format = PCM
        writeShortLe(b, 22, channels.toLong())
        writeIntLe(b, 24, sampleRate.toLong())
        writeIntLe(b, 28, sampleRate.toLong() * blockAlign)
        writeShortLe(b, 32, blockAlign.toLong())
        writeShortLe(b, 34, bits.toLong())
        writeAscii(b, 36, "data")
        writeIntLe(b, 40, payload)
        return b
    }

    private fun writeAscii(target: ByteArray, offset: Int, text: String) {
        for (i in text.indices) target[offset + i] = text[i].code.toByte()
    }

    private fun writeIntLe(target: ByteArray, offset: Int, value: Long) {
        val v = (value and 0xFFFFFFFFL).toInt()
        target[offset] = (v and 0xFF).toByte()
        target[offset + 1] = ((v ushr 8) and 0xFF).toByte()
        target[offset + 2] = ((v ushr 16) and 0xFF).toByte()
        target[offset + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    private fun writeShortLe(target: ByteArray, offset: Int, value: Long) {
        val v = (value and 0xFFFFL).toInt()
        target[offset] = (v and 0xFF).toByte()
        target[offset + 1] = ((v ushr 8) and 0xFF).toByte()
    }
}

/**
 * Small helpers shared by the export UI and any export pipeline: duration/size estimates and
 * human-readable formatting. Pure JVM so the host harness can cover it.
 */
object AudioExporter {

    /** 24 kHz mono 16-bit — the pipeline's format (see [WavSink]). */
    const val SAMPLE_RATE = 24000
    const val CHANNELS = 1
    const val BITS = 16

    /** Bytes for [durationMs] of exported audio, header included. */
    fun estimatedBytes(durationMs: Long): Long =
        WavSink.estimatedBytes(durationMs, SAMPLE_RATE, CHANNELS, BITS)

    /** "165 MB" / "1.2 GB" — decimal units, one decimal below 10. */
    fun humanSize(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble() / 1024.0
        var unit = 0
        while (value >= 1024.0 && unit < units.size - 1) {
            value /= 1024.0
            unit++
        }
        return if (value >= 10.0) {
            "${Math.round(value)} ${units[unit]}"
        } else {
            "${Math.round(value * 10.0) / 10.0} ${units[unit]}"
        }
    }
}
