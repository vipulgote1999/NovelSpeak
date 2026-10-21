package app.novelspeak

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * Low-latency mono 16-bit streaming sink.
 *
 * Configuration is taken verbatim from HayaiTTS' voice preview player
 * (decompiled `u8.k` / `androidx.room.w.u()`), which is the only in-app PCM playback path we
 * found in either reference app:
 *
 *   AudioAttributes  usage=USAGE_MEDIA, contentType=CONTENT_TYPE_MUSIC
 *   AudioFormat      16-bit PCM, mono, engine sample rate (24 kHz for Kokoro/Pocket)
 *   bufferSize       max(pcmBytes, 4096)
 *   transferMode     MODE_STREAM
 *   performanceMode  PERFORMANCE_MODE_LOW_LATENCY
 *   volume           max
 *
 * We keep one track for the whole chapter instead of one per clip, and add drain tracking so the
 * reader can advance its highlight when audio is *heard*, not when it is generated.
 */
class PcmPlayer {

    @Volatile private var track: AudioTrack? = null
    @Volatile private var sampleRate = 24000
    @Volatile private var stopped = false

    /** Frames handed to the track since the last reset (sentence boundary bookkeeping). */
    @Volatile var writtenFrames: Long = 0
        private set

    fun ensure(sampleRateHz: Int) {
        if (track != null && sampleRate == sampleRateHz) return
        release()
        sampleRate = sampleRateHz
        val minBytes = AudioTrack.getMinBufferSize(
            sampleRateHz, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferBytes = maxOf(minBytes.coerceAtLeast(4096), 8192)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRateHz)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        t.setVolume(AudioTrack.getMaxVolume())
        t.play()
        track = t
        stopped = false
    }

    fun reset() {
        writtenFrames = 0
        track?.pause()
        track?.flush()
        track?.play()
        stopped = false
    }

    /** Blocking write of float samples in [-1, 1]. Returns false if stopped. */
    fun write(samples: FloatArray): Boolean {
        val t = track ?: return false
        if (stopped) return false
        val bytes = ByteArray(samples.size * 2)
        var k = 0
        for (s in samples) {
            val v = when {
                s >= 1.0f -> 32767
                s <= -1.0f -> -32768
                else -> (s * 32767f).toInt()
            }
            bytes[k++] = (v and 0xFF).toByte()
            bytes[k++] = ((v shr 8) and 0xFF).toByte()
        }
        var off = 0
        while (off < bytes.size) {
            if (stopped) return false
            val n = t.write(bytes, off, bytes.size - off)
            if (n <= 0) return false
            off += n
        }
        writtenFrames += samples.size
        return true
    }

    fun playedFrames(): Long {
        val t = track ?: return writtenFrames
        return try { t.playbackHeadPosition.toLong() and 0xFFFFFFFFL } catch (e: Throwable) { writtenFrames }
    }

    /** Blocks until the given absolute frame count has been played (or stop). */
    fun awaitDrained(targetFrames: Long, stopFlag: () -> Boolean) {
        while (!stopFlag() && playedFrames() < targetFrames) {
            try { Thread.sleep(15) } catch (e: InterruptedException) { return }
        }
    }

    fun pause() { try { track?.pause() } catch (e: Throwable) {} }
    fun resume() { try { track?.play() } catch (e: Throwable) {} }
    fun isPlaying(): Boolean = try { track?.playState == AudioTrack.PLAYSTATE_PLAYING } catch (e: Throwable) { false }

    fun stop() {
        stopped = true
        try {
            track?.pause()
            track?.flush()
            track?.stop()
        } catch (e: Throwable) {}
        writtenFrames = 0
    }

    fun release() {
        stopped = true
        val t = track ?: return
        track = null
        try { t.pause() } catch (e: Throwable) {}
        try { t.flush() } catch (e: Throwable) {}
        try { t.release() } catch (e: Throwable) {}
    }
}
