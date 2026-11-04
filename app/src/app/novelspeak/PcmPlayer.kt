package app.novelspeak

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
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
 *
 * CPU work (denoise, gain, float→PCM) runs on the writer thread, not the caller's generation
 * thread. That keeps sentence N+1 generation from waiting behind sentence N's output processing.
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

        // A paused/flushed AudioTrack returns a short write instead of blocking. Wait this long
        // for the owner to resume or for stop()/reset() to invalidate the epoch, then fail the
        // chunk instead of spinning forever. Never kill the writer thread for this.
        const val WRITE_STALL_TIMEOUT_MS = 15_000L

        // Absolute watchdog for a sentence boundary. Sentences are capped at 45 s of audio, so
        // two minutes cannot be a legitimate drain; it means the audio route lost frames.
        const val MAX_DRAIN_WAIT_MS = 120_000L

        // Two seconds of mono 16-bit PCM. The Reader keeps up to 12 s of sentences queued, but
        // only this ring protects playback from a CPU spike (page load, screen transition,
        // call setup). The old 1 s ring underran during those transitions on the iQOO.
        const val BUFFER_SECONDS = 2
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
        val samples: FloatArray? = null,
        val fadeIn: Boolean = false,
        val tailFrames: Int = 0
    )
    private val playQueue = LinkedBlockingQueue<Chunk>()
    @Volatile private var writer: Thread? = null
    @Volatile private var writerRun = false
    @Volatile private var playbackEpoch = 0

    /** Frames handed to the writer (and counted by the Reader's lead calculation). */
    @Volatile var writtenFrames: Long = 0
        private set

    /**
     * Frames the writer has actually handed to the AudioTrack. Playback start is gated on this,
     * not on [writtenFrames], so a slow denoiser cannot start an empty track.
     */
    @Volatile private var trackFrames: Long = 0

    /** Playback has begun for the current epoch. */
    @Volatile private var started = false

    /** When true, writes are buffered but the AudioTrack stays paused. */
    @Volatile private var holdPlayback = false

    /**
     * The AudioTrack rejected a write (for example ERROR_DEAD_OBJECT after an audio-route
     * change). The player reports the failure to the waiting sentence and rebuilds the track on
     * the next ensure(), instead of leaving the writer dead or the Reader waiting forever.
     */
    @Volatile private var trackBroken = false

    /** Epoch whose audio could not be written; -1 means the current epoch is healthy. */
    @Volatile private var writeErrorEpoch = -1

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

    /** Hold or release AudioTrack playback without dropping queued PCM. */
    fun setPlaybackHold(hold: Boolean) {
        if (holdPlayback == hold) return
        holdPlayback = hold
        if (hold) {
            // The track may already be playing (for example a quick pause/resume race). Mark it
            // stopped so releasing the hold explicitly starts it again instead of assuming the
            // paused track is still running.
            started = false
            try { track?.pause() } catch (e: Throwable) {}
        } else {
            startIfBuffered()
        }
    }

    fun ensure(sampleRateHz: Int) {
        if (track != null && sampleRate == sampleRateHz && !trackBroken) {
            stopped = false
            startWriter()
            // A paused track returns a short write. If real frames are already buffered, make
            // sure it is playing again; if it is empty the writer starts it on its first write.
            if (!holdPlayback && trackFrames > 0) startTrack()
            return
        }
        // Keep an active pre-roll hold across the track rebuild; the Reader owns that state.
        val keepHold = holdPlayback
        release()
        holdPlayback = keepHold
        sampleRate = sampleRateHz
        trackBroken = false
        writeErrorEpoch = -1
        denoise = if (denoiseEnabled) AudioDsp(sampleRateHz) else null
        val minBytes = AudioTrack.getMinBufferSize(
            sampleRateHz, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferBytes = maxOf(minBytes.coerceAtLeast(4096), sampleRateHz * 2 * BUFFER_SECONDS)
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
        applyPlaybackParams(t)
        track = t
        writtenFrames = 0
        trackFrames = 0
        started = false
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

    @Synchronized
    fun reset() {
        writtenFrames = 0
        trackFrames = 0
        stopped = false
        started = false
        trackBroken = false
        writeErrorEpoch = -1
        playbackEpoch++
        playQueue.clear()
        // Lock-free: AudioDsp clears itself at the start of the next write.
        denoise?.reset()
        // pause()+flush() is the documented immediate-stop pair. Keeping the track paused
        // (instead of stop()) also keeps play()/write() restartable on every device.
        try {
            track?.pause()
            track?.flush()
        } catch (e: Throwable) {}
        Log.i("NovelSpeak", "PcmPlayer reset state=${track?.playState} hold=$holdPlayback")
    }

    /** Enqueue float samples in [-1, 1]. Returns false if stopped.
     *
     * The samples are copied and processed by the writer thread. [fadeIn] ramps the first
     * ~6 ms, so a sentence never starts with a step from the previous one. Call
     * [endSentence] after the last chunk of a sentence to ramp the tail to silence.
     */
    fun write(samples: FloatArray, fadeIn: Boolean = false): Boolean {
        val t = track ?: return false
        if (stopped || trackBroken) return false
        startWriter()
        if (writtenFrames == 0L) {
            Log.i("NovelSpeak", "PcmPlayer first write samples=${samples.size} state=${t.playState}")
        }
        if (samples.isEmpty()) return true
        val copy = samples.copyOf()
        val chunk = Chunk(playbackEpoch, copy, fadeIn)
        // Enqueue under the same lock as reset()/stop(): a chunk captured just before a seek
        // must not inflate writtenFrames after the new epoch has cleared the queue.
        synchronized(this) {
            if (stopped || trackBroken || chunk.epoch != playbackEpoch) return false
            playQueue.offer(chunk)
            writtenFrames += copy.size
        }
        if (writtenFrames <= copy.size) {
            Log.i(
                "NovelSpeak",
                "PcmPlayer queued=${writtenFrames} queue=${playQueue.size} state=${t.playState} hold=$holdPlayback"
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
        if (stopped || trackBroken) return
        val n = fadeFrames()
        val chunk = Chunk(playbackEpoch, tailFrames = n)
        synchronized(this) {
            if (stopped || trackBroken || chunk.epoch != playbackEpoch) return
            playQueue.offer(chunk)
            writtenFrames += n
        }
    }

    /** Number of sentences/chunks waiting for the writer thread (diagnostics). */
    fun queueDepth(): Int = playQueue.size

    /** Frames written to the queue but not yet heard (drives the reader's look-ahead). */
    fun bufferedFrames(): Long {
        val written = writtenFrames
        val played = playedFrames()
        return (written - played).coerceAtLeast(0)
    }

    fun bufferedSeconds(): Float =
        if (sampleRate > 0) bufferedFrames().toFloat() / sampleRate else 0f

    /**
     * Start the track once the writer has pushed real frames into it. A released preroll is
     * processed before this returns; starting earlier would play an empty track while the
     * writer denoises the first sentence.
     */
    fun startIfBuffered() {
        if (stopped || trackBroken || holdPlayback || trackFrames <= 0) return
        startTrack()
    }

    private fun startTrack() {
        if (holdPlayback) return
        val t = track ?: return
        if (t.playState == AudioTrack.PLAYSTATE_PLAYING) {
            started = true
            return
        }
        Log.i("NovelSpeak", "PcmPlayer play() queued=$writtenFrames state=${t.playState}")
        try {
            t.play()
            started = t.playState == AudioTrack.PLAYSTATE_PLAYING
        } catch (e: Throwable) {
            // Do not mark the track as started when play() failed: the next write/ensure()
            // must be able to retry instead of believing audio is running.
            started = false
            Log.w("NovelSpeak", "PcmPlayer play failed", e)
        }
        Log.i("NovelSpeak", "PcmPlayer after play state=${t.playState} started=$started")
    }

    private fun fadeFrames(): Int = (sampleRate * 6 / 1000).coerceAtLeast(24)

    @Synchronized
    private fun startWriter() {
        val existing = writer
        if (existing != null && existing.isAlive) return
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
        playQueue.offer(Chunk(-1))
        val w = writer
        writer = null
        try { w?.interrupt() } catch (e: Throwable) {}
        try { w?.join(1000) } catch (e: InterruptedException) {}
    }

    private enum class WriteResult { DONE, ABORTED, ERROR }

    private fun writerLoop() {
        // Audio output must win against page loads, WebView layout and screen transitions. The
        // writer only converts and writes PCM; URGENT_AUDIO keeps its buffer fed.
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (t: Throwable) {
        }

        val me = Thread.currentThread()
        var writerEpoch = -1
        var lastWritten = 0f
        var gainNow = 0f

        /**
         * AudioTrack.write returns a short count (including 0) while the track is paused or
         * when pause()/flush() interrupts a blocking write. A chunk is fully processed
         * (denoise/gain/quantise) before this wait, so a released preroll starts immediately
         * from the already-prepared bytes instead of waiting for DSP.
         */
        fun waitForHold(chunkEpoch: Int): Boolean {
            while (holdPlayback && writerRun && writer === me && !stopped &&
                chunkEpoch == playbackEpoch
            ) {
                try { Thread.sleep(5) } catch (e: InterruptedException) { return false }
            }
            return writerRun && writer === me && !stopped && chunkEpoch == playbackEpoch
        }

        /**
         * Writes the whole chunk. A pause/stop is not an error: it invalidates the epoch or
         * sets stopped, and this returns ABORTED so the writer can continue with the next
         * chunk. Only a real AudioTrack error marks the track broken, and even then the writer
         * thread stays alive so a later reset/ensure can rebuild and resume.
         */
        fun writeAll(bytes: ByteArray, t: AudioTrack): WriteResult {
            var off = 0
            var stalledMs = 0L
            while (off < bytes.size) {
                if (!writerRun || writer !== me) return WriteResult.ABORTED
                if (holdPlayback) {
                    try { Thread.sleep(5) } catch (e: InterruptedException) {
                        return WriteResult.ABORTED
                    }
                    continue
                }
                val n = try {
                    t.write(bytes, off, bytes.size - off)
                } catch (e: Throwable) {
                    -1
                }
                when (AudioWritePolicy.classify(
                    returned = n,
                    requested = bytes.size - off,
                    written = off,
                    stopped = stopped,
                    epochChanged = writerEpoch != playbackEpoch
                )) {
                    AudioWritePolicy.Outcome.COMPLETE,
                    AudioWritePolicy.Outcome.PROGRESS -> {
                        off += n
                        trackFrames += n / 2
                        stalledMs = 0L
                    }
                    AudioWritePolicy.Outcome.WAIT -> {
                        // The track is paused/flushed or another thread interrupted the write.
                        // Wait for resume, hold release, or stop/reset; never kill the writer.
                        if (!started && !holdPlayback) startTrack()
                        stalledMs += 10L
                        if (stalledMs >= WRITE_STALL_TIMEOUT_MS) {
                            Log.w(
                                "NovelSpeak",
                                "PcmPlayer write stalled ms=$stalledMs state=${t.playState} " +
                                    "started=$started hold=$holdPlayback"
                            )
                            markTrackBroken("stalled write")
                            return WriteResult.ERROR
                        }
                        try { Thread.sleep(10) } catch (e: InterruptedException) {
                            return WriteResult.ABORTED
                        }
                    }
                    AudioWritePolicy.Outcome.ABORT -> return WriteResult.ABORTED
                    AudioWritePolicy.Outcome.ERROR -> {
                        Log.w(
                            "NovelSpeak",
                            "PcmPlayer write error n=$n state=${t.playState} " +
                                "hold=$holdPlayback started=$started"
                        )
                        markTrackBroken("write error $n")
                        return WriteResult.ERROR
                    }
                }
            }
            return WriteResult.DONE
        }

        try {
            while (writerRun && writer === me) {
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
                    gainNow = 0f
                    // A stale chunk may have advanced the DSP while a reset was racing; start
                    // every new playback epoch from a clean filter history.
                    denoise?.reset()
                }

                try {
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
                        if (!waitForHold(chunk.epoch)) continue
                        if (!started && !holdPlayback) startTrack()
                        if (writeAll(tail, t) == WriteResult.DONE) {
                            try { capture?.writePcm16(tail, tail.size) } catch (t2: Throwable) { }
                            lastWritten = 0f
                        }
                        continue
                    }

                    val samples = chunk.samples ?: continue
                    if (samples.isEmpty()) continue

                    // Denoise before the output gain so the limiter sees the cleaned signal.
                    val dsp = denoise
                    val pcm = try {
                        dsp?.process(samples) ?: samples
                    } catch (e: Throwable) {
                        samples
                    }

                    // Peak-limit per chunk: quiet TTS chunks get the full lift, loud ones back
                    // off just enough to avoid hard clipping (Kokoro peaks ranged ~0.30-0.50).
                    var peak = 0f
                    for (s in pcm) {
                        val a = if (s < 0f) -s else s
                        if (a > peak) peak = a
                    }
                    val boost = try {
                        NovelSpeakApp.instance.prefs.ttsVolumeBoost
                    } catch (e: Throwable) {
                        1.0f
                    }
                    val gain = OUTPUT_GAIN * boost
                    val target = if (peak > 0.001f) minOf(gain, 0.98f / peak) else gain
                    if (gainNow <= 0f) gainNow = target
                    val fade = if (chunk.fadeIn) fadeFrames() else 0
                    val bytes = ByteArray(pcm.size * 2)
                    var k = 0
                    var i = 0
                    var last = 0f
                    for (s in pcm) {
                        // Gain drops immediately (clipping safety) and rises slowly, so a chunk
                        // with a different peak cannot step the level.
                        gainNow = if (target < gainNow) target
                            else gainNow + (target - gainNow) * 0.0008f
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
                    // A stop() that lands during DSP invalidates this chunk; keep the writer
                    // alive so the next reset/play can reuse it.
                    if (stopped) continue
                    // The first chunk can be prepared while a preroll hold is active; only the
                    // actual AudioTrack write waits for the hold to be released.
                    if (!waitForHold(chunk.epoch)) continue
                    if (!started && !holdPlayback) startTrack()
                    if (writeAll(bytes, t) == WriteResult.DONE) {
                        try { capture?.writePcm16(bytes, bytes.size) } catch (t2: Throwable) { }
                        lastWritten = last
                    }
                } catch (t: Throwable) {
                    // One malformed/oversized chunk must never take the whole writer down.
                    Log.w("NovelSpeak", "PcmPlayer chunk failed epoch=${chunk.epoch}", t)
                }
            }
        } finally {
            synchronized(this) {
                if (writer === me) writer = null
            }
            Log.i(
                "NovelSpeak",
                "PcmPlayer writer exit run=$writerRun stopped=$stopped broken=$trackBroken"
            )
        }
    }

    private fun markTrackBroken(reason: String) {
        trackBroken = true
        writeErrorEpoch = playbackEpoch
        Log.w("NovelSpeak", "PcmPlayer track broken: $reason")
    }

    fun playedFrames(): Long {
        val t = track ?: return writtenFrames
        return try { t.playbackHeadPosition.toLong() and 0xFFFFFFFFL } catch (e: Throwable) { writtenFrames }
    }

    /** Increments on every reset/stop, so a waiter can detect that its frame target is stale. */
    fun epoch(): Int = playbackEpoch

    /** True when the current epoch lost audio to an AudioTrack error (monitor must fail). */
    fun hasWriteError(): Boolean = trackBroken && writeErrorEpoch == playbackEpoch

    /**
     * Blocks until the given absolute frame count has been played.
     *
     * [epochAtStart] guards against the flush race: stop()/reset() clears the AudioTrack and its
     * head position returns to 0, so a target captured before the flush can never be reached.
     * Without this, a seek during playback leaves the engine thread waiting forever.
     *
     * The wall-clock cap is a last-resort watchdog: if an audio route change silently loses
     * frames without an epoch change, the sentence fails instead of leaving "Playing" forever.
     */
    fun awaitDrained(targetFrames: Long, epochAtStart: Int, stopFlag: () -> Boolean) {
        val startedAt = SystemClock.elapsedRealtime()
        while (!stopFlag() && playbackEpoch == epochAtStart &&
            !hasWriteError() && playedFrames() < targetFrames
        ) {
            if (SystemClock.elapsedRealtime() - startedAt > MAX_DRAIN_WAIT_MS) {
                Log.w(
                    "NovelSpeak",
                    "PcmPlayer drain timeout target=$targetFrames played=${playedFrames()} " +
                        "state=${track?.playState} broken=$trackBroken"
                )
                return
            }
            try { Thread.sleep(15) } catch (e: InterruptedException) { return }
        }
    }

    fun pause() {
        started = false
        try { track?.pause() } catch (e: Throwable) {}
    }

    fun resume() {
        if (holdPlayback) return
        startIfBuffered()
    }

    fun isPlaying(): Boolean = try {
        track?.playState == AudioTrack.PLAYSTATE_PLAYING
    } catch (e: Throwable) {
        false
    }

    @Synchronized
    fun stop() {
        stopped = true
        started = false
        playbackEpoch++
        playQueue.clear()
        denoise?.reset()
        trackBroken = false
        writeErrorEpoch = -1
        Log.i("NovelSpeak", "PcmPlayer stop state=${track?.playState}")
        // pause()+flush() discards queued audio immediately and keeps the track restartable.
        // stop() would leave a stopped track whose first write may be rejected/short.
        try {
            track?.pause()
            track?.flush()
        } catch (e: Throwable) {}
        writtenFrames = 0
        trackFrames = 0
    }

    @Synchronized
    fun release() {
        stopped = true
        started = false
        holdPlayback = false
        playbackEpoch++
        stopWriter()
        denoise = null
        trackBroken = false
        writeErrorEpoch = -1
        writtenFrames = 0
        trackFrames = 0
        val t = track ?: return
        track = null
        try { t.pause() } catch (e: Throwable) {}
        try { t.flush() } catch (e: Throwable) {}
        try { t.release() } catch (e: Throwable) {}
    }
}
