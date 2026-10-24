package app.novelspeak

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Android's built-in TextToSpeech engine.
 *
 * This is the always-available backend (works offline, every language the device ships, no model
 * download) and the safety net the user asked for.
 *
 * ## Why synthesis goes to a file first
 *
 * `TextToSpeech.speak()` hands playback to the TTS *provider process* (e.g. Google TTS). Audio then
 * belongs to that app's UID, so Android's MediaSessionManager never associates it with our
 * MediaSession: `updateMediaButtonSessionIfNeeded()` only walks active audio-playback client UIDs
 * and skips any UID without a session (AOSP MediaSessionStack). With no media-button session the
 * system falls back to the last remembered MediaButtonReceiver — which is null for us — and simply
 * discards headset/Bluetooth keys. That is why headphone controls did nothing on the system engine
 * while the neural engine (which owns its AudioTrack) worked.
 *
 * So: `synthesizeToFile()` renders the sentence in the provider, we decode the WAV, and play it
 * through the app's own [PcmPlayer]. The audio client UID is now NovelSpeak, the MediaSession is
 * selected as the media-button session, and media keys reach [PlaybackService]. It also keeps the
 * screen-off path in-process, like the neural engine.
 *
 * If the active provider does not support file synthesis, the old direct-speak path is used as a
 * fallback so playback (minus headset control) still works.
 *
 * Sentence pipelining is unchanged: [Reader] submits up to `prefetch` sentences ahead, and this
 * engine renders the next one while the current one plays.
 */
class SystemTtsEngine(private val ctx: Context, private val prefs: Prefs) : TtsEngine {

    override val id = "system"
    override var isReady = false
        private set

    private var tts: TextToSpeech? = null
    private val main = Handler(Looper.getMainLooper())
    private val counter = AtomicLong(0)
    private val pending = HashMap<String, (Boolean) -> Unit>()   // legacy speak() callbacks
    private var voiceId: String? = null

    private class Task(val text: String, val onDone: (Boolean) -> Unit)
    private class Boundary(
        val endFrame: Long,
        val playerEpoch: Int,
        val epoch: Int,
        val onDone: (Boolean) -> Unit
    )

    private val queue = LinkedBlockingQueue<Task>()
    private val boundaries = LinkedBlockingQueue<Boundary>()
    private var worker: Thread? = null
    private var monitor: Thread? = null
    @Volatile private var stopFlag = false
    @Volatile private var monitorRun = true
    @Volatile private var fileSynthesisSupported = true
    private var sampleRate = 24000

    /** Bumped by [stop]; a task dequeued before the stop must not resume playback. */
    private val stopEpoch = java.util.concurrent.atomic.AtomicInteger(0)

    override fun prepare(onResult: (Boolean, String?) -> Unit) {
        if (isReady) { onResult(true, null); return }
        DiagLog.line(ctx, "tts init start")
        tts = TextToSpeech(ctx) { status ->
            try {
                if (status == TextToSpeech.SUCCESS) {
                    val t = tts
                    if (t == null) { onResult(false, "TTS unavailable"); return@TextToSpeech }
                    t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {
                            finish(utteranceId, true)
                            fileDone(utteranceId, true)
                        }
                        @Deprecated("deprecated")
                        override fun onError(utteranceId: String?) {
                            finish(utteranceId, false)
                            fileDone(utteranceId, false)
                        }
                        override fun onError(utteranceId: String?, errorCode: Int) {
                            finish(utteranceId, false)
                            fileDone(utteranceId, false)
                        }
                        override fun onStop(utteranceId: String?, interrupted: Boolean) {
                            finish(utteranceId, false)
                            fileDone(utteranceId, false)
                        }
                    })
                    try {
                        t.language = Locale.getDefault()
                    } catch (e: Throwable) {
                    }
                    isReady = true
                    DiagLog.line(ctx, "tts init ok voices=${t.voices?.size ?: 0}")
                    // System TTS output is clean; keep the vocoder denoiser for neural voices.
                    novelSpeakPlayer().setDenoiseEnabled(false)
                    startWorker()
                    onResult(true, null)
                } else {
                    isReady = false
                    DiagLog.line(ctx, "tts init failed status=$status")
                    onResult(false, "TextToSpeech init failed ($status)")
                }
            } catch (t: Throwable) {
                isReady = false
                DiagLog.exception(ctx, "tts init callback failed", t)
                onResult(false, "TextToSpeech init error: ${t.javaClass.simpleName}")
            }
        }
    }

    // ------------------------------------------------------------------ legacy speak() path

    private fun finish(utteranceId: String?, ok: Boolean) {
        val id = utteranceId ?: return
        if (id.startsWith(FILE_PREFIX)) return
        val cb = synchronized(pending) { pending.remove(id) } ?: return
        main.post { cb(ok) }
    }

    // ------------------------------------------------------------------ file synthesis path

    private class Render(val id: String) {
        val latch = CountDownLatch(1)
        @Volatile var ok = false
    }

    private val renders = HashMap<String, Render>()

    private fun fileDone(utteranceId: String?, ok: Boolean) {
        val id = utteranceId ?: return
        if (!id.startsWith(FILE_PREFIX)) return
        val r = synchronized(renders) { renders[id] } ?: return
        r.ok = ok
        r.latch.countDown()
    }

    private fun startWorker() {
        if (worker == null) {
            worker = Thread({ workerLoop() }, "ns-system-tts").apply {
                isDaemon = true
                start()
            }
        }
        if (monitor == null) {
            monitor = Thread({ monitorLoop() }, "ns-system-mon").apply {
                isDaemon = true
                start()
            }
        }
    }

    /**
     * Waits for each rendered sentence to be heard. Kept off the render loop so the next
     * sentence is synthesized while the current one is still playing.
     */
    private fun monitorLoop() {
        while (monitorRun) {
            val b = try {
                boundaries.poll(500, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                null
            } ?: continue
            if (stopFlag || b.epoch != stopEpoch.get()) {
                main.post { b.onDone(false) }
                continue
            }
            novelSpeakPlayer().awaitDrained(b.endFrame, b.playerEpoch) {
                stopFlag || b.epoch != stopEpoch.get()
            }
            val ok = !stopFlag && b.epoch == stopEpoch.get()
            main.post { b.onDone(ok) }
        }
    }

    private fun workerLoop() {
        while (true) {
            val task = try {
                queue.poll(500, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                null
            } ?: continue
            val epoch = stopEpoch.get()
            if (stopFlag) { main.post { task.onDone(false) }; continue }
            if (!fileSynthesisSupported) {
                if (epoch == stopEpoch.get()) legacySpeak(task) else main.post { task.onDone(false) }
                continue
            }
            try {
                applyVoiceAndRate()
                val pcm = try {
                    renderToPcm(task.text)
                } catch (e: UnsupportedOperationException) {
                    null
                }
                if (epoch != stopEpoch.get()) { main.post { task.onDone(false) }; continue }
                if (pcm == null) {
                    // Provider cannot synthesize to file: fall back and stay there.
                    fileSynthesisSupported = false
                    DiagLog.line(ctx, "tts file synthesis unsupported, using direct speak")
                    if (!stopFlag) legacySpeak(task) else main.post { task.onDone(false) }
                    continue
                }
                if (pcm.isEmpty()) {
                    // Provider supports files but this render failed (or was cancelled):
                    // speak directly once unless playback was stopped meanwhile.
                    if (stopFlag || epoch != stopEpoch.get()) {
                        main.post { task.onDone(false) }
                    } else {
                        DiagLog.line(ctx, "tts render empty, fallback direct speak")
                        legacySpeak(task)
                    }
                    continue
                }
                if (stopFlag) { main.post { task.onDone(false) }; continue }
                // Ramp both edges of the rendered sentence so it neither clicks against
                // the previous tail nor cuts off abruptly.
                applyEdgeFade(pcm, SAMPLE_RATE)
                novelSpeakPlayer().ensure(sampleRate)
                // Epoch captured before writing: a seek flushes the track and makes this
                // target unreachable, so the waiter must abort on the flush too.
                val playerEpoch = novelSpeakPlayer().epoch()
                // One write per sentence: PcmPlayer peak-normalises per call, so slicing
                // would make the gain pump every slice. A single call also matches the
                // neural path and keeps stop() bounded by the AudioTrack buffer, not the
                // sentence length (stop() flushes the track from another thread).
                if (!novelSpeakPlayer().write(pcm)) {
                    main.post { task.onDone(false) }
                    continue
                }
                novelSpeakPlayer().startIfBuffered()
                val end = novelSpeakPlayer().writtenFrames
                if (end <= 0) { main.post { task.onDone(false) }; continue }
                // Do not wait for playback here: record the boundary and immediately render
                // the next queued sentence. The monitor thread reports completion when this
                // sentence has actually been heard.
                boundaries.put(Boundary(end, playerEpoch, epoch, task.onDone))
            } catch (e: Throwable) {
                DiagLog.exception(ctx, "system tts render failed len=${task.text.length}", e)
                main.post { task.onDone(false) }
            }
        }
    }

    /** Renders [text] to 24 kHz mono float PCM.
     *
     * @throws UnsupportedOperationException when the provider rejects file synthesis.
     * @return PCM, or an empty array when the provider supports files but this render failed.
     */
    private fun renderToPcm(text: String): FloatArray? {
        val t = tts ?: throw UnsupportedOperationException("no engine")
        val id = FILE_PREFIX + counter.incrementAndGet()
        val dir = File(ctx.cacheDir, "tts")
        dir.mkdirs()
        val out = File(dir, "$id.wav")
        val r = Render(id)
        synchronized(renders) { renders[id] = r }
        return try {
            val res = t.synthesizeToFile(text, null, out, id)
            if (res != TextToSpeech.SUCCESS) {
                DiagLog.line(ctx, "tts synthesizeToFile rejected res=$res")
                throw UnsupportedOperationException("synthesizeToFile=$res")
            }
            // Generous bound: a 180-char sentence is a few seconds even on a cold engine.
            if (!r.latch.await(45, TimeUnit.SECONDS)) {
                DiagLog.line(ctx, "tts synthesizeToFile timeout")
                return FloatArray(0)
            }
            if (!r.ok) {
                DiagLog.line(ctx, "tts synthesizeToFile failed")
                return FloatArray(0)
            }
            val bytes = out.readBytes()
            val parsed = WavReader.parse(bytes) ?: run {
                DiagLog.line(ctx, "tts wav parse failed bytes=${bytes.size}")
                return FloatArray(0)
            }
            var pcm = parsed.samples
            if (parsed.sampleRate != SAMPLE_RATE) {
                pcm = WavReader.resample(pcm, parsed.sampleRate, SAMPLE_RATE)
            }
            sampleRate = SAMPLE_RATE
            val peak = pcm.maxOfOrNull { if (it < 0f) -it else it } ?: 0f
            DiagLog.line(ctx, "tts render ok rate=${parsed.sampleRate} samples=${pcm.size} peak=${"%.2f".format(peak)}")
            pcm
        } catch (e: UnsupportedOperationException) {
            throw e
        } catch (e: Throwable) {
            DiagLog.exception(ctx, "tts synthesizeToFile error", e)
            FloatArray(0)
        } finally {
            synchronized(renders) { renders.remove(id) }
            out.delete()
        }
    }

    /** Provider can't write files: let it play, and report done from its own callback. */
    private fun legacySpeak(task: Task) {
        val t = tts
        if (t == null || !isReady) { main.post { task.onDone(false) }; return }
        try {
            applyVoiceAndRate()
            val utt = "ns-" + counter.incrementAndGet()
            synchronized(pending) { pending[utt] = task.onDone }
            val res = t.speak(task.text, TextToSpeech.QUEUE_ADD, null, utt)
            if (res != TextToSpeech.SUCCESS) {
                synchronized(pending) { pending.remove(utt) }
                main.post { task.onDone(false) }
            }
        } catch (t2: Throwable) {
            DiagLog.exception(ctx, "tts speak failed len=${task.text.length}", t2)
            main.post { task.onDone(false) }
        }
    }

    private fun applyVoiceAndRate() {
        val t = tts ?: return
        try {
            t.setSpeechRate(prefs.speed.coerceIn(0.5f, 2.0f))
            voiceId?.let { vid ->
                val v = t.voices?.firstOrNull { it.name == vid }
                if (v != null) t.voice = v
            }
        } catch (e: Throwable) {
        }
    }

    private fun novelSpeakPlayer() = NovelSpeakApp.instance.player

    /** 6 ms linear fade at both ends (the neural engine gets the same shape in PcmPlayer). */
    private fun applyEdgeFade(pcm: FloatArray, rate: Int) {
        if (pcm.size < 32) return
        val n = (rate * 6 / 1000).coerceAtMost(pcm.size / 2)
        if (n <= 1) return
        for (i in 0 until n) {
            val g = i.toFloat() / n
            pcm[i] *= g
            pcm[pcm.size - 1 - i] *= g
        }
    }

    // ------------------------------------------------------------------ TtsEngine

    override fun speak(text: String, onDone: (Boolean) -> Unit) {
        if (!isReady) { main.post { onDone(false) }; return }
        if (stopFlag) {
            stopFlag = false
            novelSpeakPlayer().reset()
        }
        queue.offer(Task(text, onDone))
    }

    override fun stop() {
        stopFlag = true
        stopEpoch.incrementAndGet()
        queue.clear()
        while (true) {
            val b = boundaries.poll() ?: break
            main.post { b.onDone(false) }
        }
        synchronized(pending) { pending.clear() }
        try { tts?.stop() } catch (e: Throwable) {}
        // Unblock a worker waiting on a render that stop() just cancelled.
        synchronized(renders) {
            for (r in renders.values) r.latch.countDown()
        }
        novelSpeakPlayer().stop()
    }

    override fun release() {
        stop()
        monitorRun = false
        try { monitor?.interrupt() } catch (e: Throwable) {}
        try { tts?.shutdown() } catch (e: Throwable) {}
        tts = null
        isReady = false
        worker = null
        monitor = null
    }

    override fun bufferedSeconds(): Float = NovelSpeakApp.instance.player.bufferedSeconds()

    override fun voices(): List<Pair<String, String>> {
        val t = tts ?: return emptyList()
        return try {
            (t.voices ?: emptySet<Voice>())
                .filter { it.locale != null }
                .sortedWith(compareBy({ it.locale.toLanguageTag() }, { it.name }))
                .map { it.name to "${it.locale.displayName} · ${it.name}" }
        } catch (e: Throwable) {
            emptyList()
        }
    }

    override fun setVoice(id: String) {
        voiceId = id.ifEmpty { null }
    }

    private companion object {
        const val SAMPLE_RATE = 24000
        const val FILE_PREFIX = "nsf-"
    }
}
