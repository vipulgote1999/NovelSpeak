package app.novelspeak

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.roundToInt

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

    private companion object {
        // Kokoro's raw output peaks around 0.35 (measured on-device), so the reader sounds
        // quiet at full media volume. Lift it with a hard limiter; this is output gain only.
        const val OUTPUT_GAIN = 2.2f

        // Spectral denoising of the vocoder's low-level broadband hiss. It runs before the
        // output gain, so the limiter still sees the cleaned signal. Set false to A/B the raw
        // TTS path; the rest of PcmPlayer does not change.
        const val DENOISE_ENABLED = true
    }

    @Volatile private var track: AudioTrack? = null
    @Volatile private var sampleRate = 24000
    @Volatile private var stopped = false

    /** Denoiser for the current track; rebuilt with the track so its filters match the rate. */
    @Volatile private var denoise: AudioDsp? = null

    /**
     * The spectral denoiser targets the neural vocoder's low-level hiss. System TTS output is
     * already clean, so it is switched off there (no processing, no CPU, no timbre risk).
     */
    @Volatile private var denoiseEnabled = DENOISE_ENABLED

    fun setDenoiseEnabled(on: Boolean) {
        if (denoiseEnabled == on) return
        denoiseEnabled = on
        denoise = if (on && track != null) AudioDsp(sampleRate) else null
    }

    // PCM writer thread: write() enqueues and returns immediately so sherpa can generate the
    // next sentence while the current sentence is still playing. Without this, AudioTrack.write
    // blocks the generation thread for the full sentence and each sentence boundary has a
    // generation-sized silence.
    private class Chunk(
        val epoch: Int,
        val bytes: ByteArray,
        val fadeIn: Boolean = false,
        val tailFrames: Int = 0,
        val lastSample: Float = 0f
    )
    private val playQueue = LinkedBlockingQueue<Chunk>()
    @Volatile private var writer: Thread? = null
    @Volatile private var writerRun = false
    @Volatile private var playbackEpoch = 0

    /** Smoothed output gain: drops instantly, rises slowly, so chunk peaks cannot pump. */
    private var gainNow = 0f

    private fun fadeFrames(): Int = (sampleRate * 6 / 1000).coerceAtLeast(24)

    /** Playback is held until enough PCM is buffered, which avoids the initial underrun. */
    @Volatile private var started = false

    /** Frames handed to the track since the last reset (sentence boundary bookkeeping). */
    @Volatile var writtenFrames: Long = 0
        private set

    // ---- audio export (record while listening) -------------------------------
    @Volatile private var capture: WavSink? = null
    @Volatile private var captureFile: File? = null

    /** Start capturing everything this player writes to a 24 kHz mono 16-bit WAV file. */
    @Synchronized
    fun startCapture(file: File): Boolean {
        return try {
            stopCapture()
            capture = WavSink(FileOutputStream(file))
            captureFile = file
            true
        } catch (t: Throwable) {
            Log.w("NovelSpeak", "startCapture failed", t)
            false
        }
    }

    /** Stop capturing and return the written file (null if no capture was active). */
    @Synchronized
    fun stopCapture(): File? {
        val f = captureFile
        try { capture?.close() } catch (t: Throwable) { }
        capture = null
        captureFile = null
        return f
    }

    fun isCapturing(): Boolean = capture != null

    fun ensure(sampleRateHz: Int) {
        if (track != null && sampleRate == sampleRateHz) {
            // A stopped/paused track rejects the first write; bring it back up.
            stopped = false
            val t = track
            if (t != null && t.playState != AudioTrack.PLAYSTATE_PLAYING) {
                try { t.play() } catch (e: Throwable) {}
                started = true
            }
            startWriter()
            return
        }
        release()
        sampleRate = sampleRateHz
        denoise = if (denoiseEnabled) AudioDsp(sampleRateHz) else null
        val minBytes = AudioTrack.getMinBufferSize(
            sampleRateHz, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        // One second of mono 16-bit PCM: enough to pre-buffer ~0.35 s and ride out a slow
        // generation chunk. HayaiTTS uses a small buffer because it synthesizes a full clip
        // before play; we stream, so a slightly larger ring is useful.
        val bufferBytes = maxOf(minBytes.coerceAtLeast(4096), sampleRateHz * 2)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
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
        applyPlaybackParams(t)
        track = t
        writtenFrames = 0
        started = true
        stopped = false
        playbackEpoch++
        startWriter()
        Log.i("NovelSpeak", "PcmPlayer ensure rate=$sampleRateHz buffer=$bufferBytes min=$minBytes")
    }

    /** Apply user pitch without changing speed (AudioTrack PlaybackParams, API 23+). */
    private fun applyPlaybackParams(t: AudioTrack) {
        try {
            val pitch = NovelSpeakApp.instance.prefs.ttsPitch
            t.playbackParams = android.media.PlaybackParams()
                .setSpeed(1.0f)
                .setPitch(pitch.coerceIn(0.8f, 1.2f))
        } catch (t2: Throwable) {
            Log.w("NovelSpeak", "playback params rejected", t2)
        }
    }

    fun reset() {
        writtenFrames = 0
        stopped = false
        gainNow = 0f
        playbackEpoch++
        playQueue.clear()
        // Lock-free: AudioDsp clears itself at the start of the next write.
        denoise?.reset()
        track?.pause()
        track?.flush()
        track?.play()
        started = true
        Log.i("NovelSpeak", "PcmPlayer reset state=${track?.playState}")
    }

    /** Blocking write of float samples in [-1, 1]. Returns false if stopped.
     *
     * [fadeIn] ramps the first ~6 ms, so a sentence never starts with a step from the
     * previous one. Call [endSentence] after the last chunk of a sentence to ramp the
     * tail to silence (the writer knows the last written sample).
     */
    fun write(samples: FloatArray, fadeIn: Boolean = false): Boolean {
        val t = track ?: return false
        if (stopped) return false
        if (writer == null) startWriter()
        if (writtenFrames == 0L) {
            Log.i("NovelSpeak", "PcmPlayer first write samples=${samples.size} state=${t.playState}")
        }
        // Denoise before the output gain so the limiter sees the cleaned signal. process()
        // returns an array of the same length, so the rest of this method is unchanged.
        val pcm = denoise?.process(samples) ?: samples
        val bytes = ByteArray(pcm.size * 2)
        // Peak-limit per chunk: quiet TTS chunks get the full lift, loud ones back off just
        // enough to avoid hard clipping (Kokoro peaks ranged ~0.30-0.50 in testing).
        var peak = 0f
        for (s in pcm) {
            val a = if (s < 0f) -s else s
            if (a > peak) peak = a
        }
        val boost = try {
            NovelSpeakApp.instance.prefs.ttsVolumeBoost
        } catch (t: Throwable) {
            1.0f
        }
        val gain = OUTPUT_GAIN * boost
        val target = if (peak > 0.001f) minOf(gain, 0.98f / peak) else gain
        if (gainNow <= 0f) gainNow = target
        val fade = if (fadeIn) fadeFrames() else 0
        var k = 0
        var i = 0
        var last = 0f
        for (s in pcm) {
            // Gain drops immediately (clipping safety) and rises slowly, so a chunk with
            // a different peak cannot step the level.
            gainNow = if (target < gainNow) target else gainNow + (target - gainNow) * 0.0008f
            var v = s * gainNow
            if (i < fade) v *= i.toFloat() / fade
            last = v
            val clipped = when {
                v >= 1.0f -> 32767
                v <= -1.0f -> -32768
                else -> (v * 32767f).roundToInt()
            }
            bytes[k++] = (clipped and 0xFF).toByte()
            bytes[k++] = ((clipped shr 8) and 0xFF).toByte()
            i++
        }
        if (stopped) return false
        playQueue.offer(Chunk(playbackEpoch, bytes, lastSample = last))
        writtenFrames += samples.size
        maybeStart()
        if (writtenFrames <= samples.size) {
            Log.i(
                "NovelSpeak",
                "PcmPlayer queued=${writtenFrames} queue=${playQueue.size} state=${t.playState}"
            )
        }
        return true
    }

    /**
     * Marks the end of a synthesized sentence: the writer appends a ~6 ms ramp from the
     * last written sample to silence. Without it a sentence ending mid-waveform and the
     * next starting at a different value produce an audible click.
     */
    fun endSentence() {
        if (stopped) return
        val n = fadeFrames()
        playQueue.offer(Chunk(playbackEpoch, ByteArray(0), tailFrames = n))
        writtenFrames += n
    }

    /** Number of sentences/chunks waiting for the writer thread (diagnostics). */
    fun queueDepth(): Int = playQueue.size

    /** Frames written to the track but not yet heard (drives the reader's look-ahead). */
    fun bufferedFrames(): Long {
        val written = writtenFrames
        val played = playedFrames()
        return (written - played).coerceAtLeast(0)
    }

    fun bufferedSeconds(): Float =
        if (sampleRate > 0) bufferedFrames().toFloat() / sampleRate else 0f

    private fun maybeStart() {
        if (started || writtenFrames < (sampleRate * 35L / 100L)) return
        startIfBuffered()
    }

    /** Force playback to begin once generation is done, even for a very short sentence. */
    fun startIfBuffered() {
        if (started || writtenFrames <= 0) return
        val t = track ?: return
        Log.i("NovelSpeak", "PcmPlayer play() frames=$writtenFrames state=${t.playState}")
        try {
            t.play()
        } catch (e: Throwable) {
            Log.w("NovelSpeak", "PcmPlayer play failed", e)
        }
        started = true
        Log.i("NovelSpeak", "PcmPlayer after play state=${t.playState}")
    }

    @Synchronized
    private fun startWriter() {
        if (writer != null) return
        writerRun = true
        writer = Thread({ writerLoop() }, "ns-pcm-writer").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    private fun stopWriter() {
        writerRun = false
        playQueue.clear()
        playQueue.offer(Chunk(-1, ByteArray(0)))
        val w = writer
        writer = null
        try { w?.interrupt() } catch (e: Throwable) {}
        try { w?.join(300) } catch (e: InterruptedException) {}
    }

    private fun writerLoop() {
        var writerEpoch = -1
        var lastWritten = 0f
        fun writeAll(bytes: ByteArray, t: AudioTrack): Boolean {
            var off = 0
            while (off < bytes.size && writerRun && !stopped && writerEpoch == playbackEpoch) {
                val n = try {
                    t.write(bytes, off, bytes.size - off)
                } catch (e: Throwable) {
                    -1
                }
                if (n <= 0) {
                    Log.w("NovelSpeak", "PcmPlayer writer write failed n=$n state=${t.playState}")
                    return false
                }
                off += n
            }
            return true
        }
        while (writerRun) {
            val chunk = try {
                playQueue.take()
            } catch (e: InterruptedException) {
                break
            }
            if (chunk.epoch < 0) continue
            if (chunk.epoch != playbackEpoch) continue
            if (stopped) {
                playQueue.clear()
                continue
            }
            val t = track ?: continue
            if (chunk.epoch != writerEpoch) {
                writerEpoch = chunk.epoch
                lastWritten = 0f
            }
            // Sentence tail: ramp the last written sample to zero so the next sentence
            // cannot produce a step discontinuity (an audible click).
            if (chunk.tailFrames > 0) {
                val n = chunk.tailFrames
                val tail = ByteArray(n * 2)
                for (i in 0 until n) {
                    val v = lastWritten * (1f - i.toFloat() / (n - 1).coerceAtLeast(1))
                    val s = (v * 32767f).roundToInt().coerceIn(-32768, 32767)
                    tail[i * 2] = (s and 0xFF).toByte()
                    tail[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                }
                if (!writeAll(tail, t)) break
                try { capture?.writePcm16(tail, tail.size) } catch (t2: Throwable) { }
                lastWritten = 0f
                continue
            }
            if (chunk.bytes.isEmpty()) continue
            if (!writeAll(chunk.bytes, t)) break
            try { capture?.writePcm16(chunk.bytes, chunk.bytes.size) } catch (t2: Throwable) { }
            lastWritten = chunk.lastSample
        }
    }

    fun playedFrames(): Long {
        val t = track ?: return writtenFrames
        return try { t.playbackHeadPosition.toLong() and 0xFFFFFFFFL } catch (e: Throwable) { writtenFrames }
    }

    /** Increments on every reset/stop, so a waiter can detect that its frame target is stale. */
    fun epoch(): Int = playbackEpoch

    /**
     * Blocks until the given absolute frame count has been played.
     *
     * [epochAtStart] guards against the flush race: stop()/reset() clears the AudioTrack and its
     * head position returns to 0, so a target captured before the flush can never be reached.
     * Without this, a seek during playback leaves the engine thread waiting forever.
     */
    fun awaitDrained(targetFrames: Long, epochAtStart: Int, stopFlag: () -> Boolean) {
        while (!stopFlag() && playbackEpoch == epochAtStart && playedFrames() < targetFrames) {
            try { Thread.sleep(15) } catch (e: InterruptedException) { return }
        }
    }

    fun pause() { try { track?.pause() } catch (e: Throwable) {} }
    fun resume() {
        try { track?.play() } catch (e: Throwable) {}
        if (writtenFrames > 0) started = true
    }
    fun isPlaying(): Boolean = try { track?.playState == AudioTrack.PLAYSTATE_PLAYING } catch (e: Throwable) { false }

    fun stop() {
        stopped = true
        started = false
        playbackEpoch++
        playQueue.clear()
        denoise?.reset()
        Log.i("NovelSpeak", "PcmPlayer stop state=${track?.playState}")
        try {
            track?.pause()
            track?.flush()
            track?.stop()
        } catch (e: Throwable) {}
        writtenFrames = 0
    }

    fun release() {
        stopped = true
        started = false
        playbackEpoch++
        stopWriter()
        denoise = null
        val t = track ?: return
        track = null
        try { t.pause() } catch (e: Throwable) {}
        try { t.flush() } catch (e: Throwable) {}
        try { t.release() } catch (e: Throwable) {}
    }
}
