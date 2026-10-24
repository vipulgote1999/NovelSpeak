package app.novelspeak

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsPocketModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsZipVoiceModelConfig
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Neural backend built on sherpa-onnx — the same library HayaiTTS uses (GPL-3.0 app, Apache-2.0
 * library), so the tuning values are already validated on-device:
 *
 *   - one OfflineTts per voice, engine cached (HayaiTTS keeps an LRU of 2)
 *   - numThreads from settings (NekoSpeak default 6, HayaiTTS exposes it; we default 4)
 *   - provider "cpu" (HayaiTTS also exposes qnn; NNAPI is offered as a retry-on-failure option)
 *   - maxNumSentences = 1 because we chunk sentences ourselves (NekoSpeak: 150-token Kokoro
 *     batches / 15-frame Pocket decode chunks)
 *   - Pocket uses GenerationConfig(referenceAudio=clip, numSteps=8, silenceScale=1.0) exactly like
 *     HayaiTTS' cloning path; Pocket needs audio only, ZipVoice would need a transcript
 *   - PCM is streamed into the low-latency AudioTrack while generation runs, and the next sentence
 *     is generated during playback (pipeline), which is how both apps hide first-audio latency.
 */
class SherpaEngine(
    private val ctx: Context,
    private val prefs: Prefs,
    private val player: PcmPlayer
) : TtsEngine {

    override val id = "sherpa"

    /**
     * Kokoro/Pocket stay intelligible up to ~3x. The old 2.0 clamp made 2.05-2.5x in the UI a
     * silent lie (P0.6); the UI now caps at this value and [generationSpeed] clamps to it.
     */
    override val maxSpeed: Float get() = MAX_SPEED

    @Volatile override var isReady = false
        private set

    private var tts: OfflineTts? = null
    private var family = ""
    private var sampleRate = 24000
    private var reference: FloatArray? = null
    private var referenceRate = 24000

    @Volatile private var stopFlag = false

    /**
     * Monotonic cancellation token. [stop] increments it; [speak] never resets it. An
     * in-flight sherpa generation captures the epoch and its callback returns 0 once the
     * token moves, so a seek/pause aborts the native synthesis instead of spending seconds
     * finishing audio nobody will hear. (Clearing a single stopFlag from speak() is what
     * let stale generations run to completion.)
     */
    @Volatile private var cancelEpoch = 0
    @Volatile private var peakLogged = false
    private val main = Handler(Looper.getMainLooper())

    private class Task(val text: String, val epoch: Int, val onDone: (Boolean) -> Unit)
    private class Boundary(val endFrame: Long, val epoch: Int, val onDone: (Boolean) -> Unit)

    /**
     * sherpa's JNI looks up invoke([F)Ljava/lang/Integer; on the callback object. Kotlin 2.2
     * compiles lambdas with invokedynamic, whose generated class only has invoke(Object), so
     * that lookup aborts the process. A named Function1 subclass keeps the specialised bridge.
     */
    private inner class SampleSink(private val epoch: Int) : (FloatArray) -> Int {
        var wrote = false
        var total = 0L

        /**
         * Set when the engine emits the int8 saturation artifact instead of speech:
         * a large share of samples pinned at the quantisation rail (k2-fsa/sherpa-onnx#3754
         * reports int8 Kokoro on Android/ARM returning whole sentences pinned at ~+1.0).
         * Normal TTS output peaks transiently (~0.35 measured), so >50% of a chunk at
         * the rail cannot be speech.
         */
        var garbage = false

        /** At least one non-empty chunk arrived (audio may still have been trimmed to nothing). */
        var produced = false

        /** PcmPlayer rejected a write (stop/reset); abort like the inline path did before. */
        var writeFailed = false

        /** Skip-silence is read once per sentence so a Settings toggle cannot split a sentence. */
        private val trim = prefs.ttsSkipSilence
        private val rate = if (sampleRate > 0) sampleRate else 24000

        /**
         * With skip-silence ON, one chunk is held back: the last chunk can only lose its trailing
         * silence once we know generation has ended. Costs one callback of extra first-audio
         * latency, only for users who opted into the feature; OFF (default) writes immediately,
         * byte-for-byte the previous behaviour.
         */
        private var pending: FloatArray? = null

        override fun invoke(samples: FloatArray): Int {
            // sherpa-onnx offline-tts callback contract: 1 = continue generating,
            // 0 = stop. The previous values were inverted, so every sentence stopped
            // after the first 15-frame / 1.2 s chunk.
            if (stopFlag) return 0
            if (epoch != cancelEpoch) return 0
            if (garbage) return 0
            if (samples.isEmpty()) return 1
            // Judge BEFORE writing: the first callback arrives while the AudioTrack is
            // still pre-buffering, so a bad chunk can be dropped without ever playing.
            if (looksRailPinned(samples)) {
                garbage = true
                return 0
            }
            // Safety cap: a normal ≤180-char sentence is ~10-15 s of audio.
            // If the LM misses EOS (stray quote/asterisk/punct-only input slipping
            // through), it babbles indefinitely — the reported "continuous noise
            // for some time". Stop the generation at 45 s so at most a truncated
            // fragment plays instead of minutes of babble (NekoSpeak caps Pocket
            // at ~40 s / 500 frames for the same reason).
            val cap = rate * 45L
            if (total >= cap) return 0
            if (!peakLogged) {
                peakLogged = true
                var peak = 0f
                for (s in samples) {
                    val a = if (s < 0f) -s else s
                    if (a > peak) peak = a
                }
                DiagLog.line(ctx, "tts chunk peak=${"%.3f".format(peak)} samples=${samples.size}")
            }
            produced = true
            return if (trim) acceptDeferred(samples) else if (writeChunk(samples, false, false)) 1 else 0
        }

        /**
         * Flushes the held-back final chunk, trimming its trailing silence. Must be called once
         * after generation ends (a no-op when skip-silence is off or nothing is pending).
         *
         * @return false only when PcmPlayer rejected the write (stop/reset), so the caller can
         *         fail the sentence instead of hanging on a boundary that will never drain.
         */
        fun flushPending(): Boolean {
            val p = pending ?: return true
            pending = null
            if (stopFlag || epoch != cancelEpoch || garbage) return true
            return writeChunk(p, trimStart = !wrote, trimEnd = true)
        }

        private fun acceptDeferred(samples: FloatArray): Int {
            val prev = pending
            if (prev != null) {
                // The previous chunk is not final: trim its leading silence only when it is the
                // first audio of the sentence, never its tail (a tail could be a real pause).
                if (!writeChunk(prev, trimStart = !wrote, trimEnd = false)) {
                    pending = null
                    return 0
                }
            }
            // Copy: the native callback owns the array; keeping a reference across calls is not
            // part of the JNI contract. One live chunk at a time, released on the next callback.
            pending = samples.copyOf()
            return 1
        }

        private fun writeChunk(samples: FloatArray, trimStart: Boolean, trimEnd: Boolean): Boolean {
            val out = if (trim && (trimStart || trimEnd)) {
                try {
                    AudioDsp.trimSilence(
                        samples,
                        rate,
                        trimStart = trimStart,
                        trimEnd = trimEnd
                    )
                } catch (e: Throwable) {
                    // Trimming is cosmetic; never lose speech over it.
                    samples
                }
            } else {
                samples
            }
            if (out.isEmpty()) return true // pure-silence chunk dropped; keep generating
            if (!player.write(out, fadeIn = !wrote)) {
                writeFailed = true
                return false
            }
            wrote = true
            total += out.size
            return true
        }

        private fun looksRailPinned(samples: FloatArray): Boolean {
            if (samples.size < 480) return false // <20 ms: not enough evidence
            var pinned = 0
            var peak = 0f
            for (s in samples) {
                val a = if (s < 0f) -s else s
                if (a > peak) peak = a
                if (a >= 0.995f) pinned++
            }
            return peak >= 0.995f && pinned * 2 > samples.size
        }
    }

    private val genQueue = LinkedBlockingQueue<Task>()
    private val boundaries = LinkedBlockingQueue<Boundary>()
    private var genThread: Thread? = null
    private var monThread: Thread? = null
    private var voiceId: String = ""

    override fun prepare(onResult: (Boolean, String?) -> Unit) {
        if (isReady) { onResult(true, null); return }
        Thread {
            val started = System.currentTimeMillis()
            try {
                val model = ModelManager.model(prefs.modelId)
                if (model == null) {
                    DiagLog.line(ctx, "engine prepare: unknown model ${prefs.modelId}")
                    main.post { onResult(false, "Unknown model") }
                    return@Thread
                }
                val dir = ModelManager.modelDir(ctx, model.id)
                if (!ModelManager.isInstalled(ctx, model.id)) {
                    DiagLog.line(ctx, "engine prepare: model missing dir=$dir")
                    main.post { onResult(false, "Model not installed: ${model.label}") }
                    return@Thread
                }
                family = model.family
                DiagLog.line(
                    ctx,
                    "engine prepare model=${model.id} family=$family threads=${prefs.threads} " +
                        "nnapi=${prefs.nnapi} provider=${if (prefs.nnapi) "nnapi" else "cpu"}"
                )
                reference = loadReference(dir)
                DiagLog.line(ctx, "engine reference samples=${reference?.size ?: 0} @${referenceRate}Hz")
                // The denoiser targets vocoder hiss; enable it for the neural path.
                player.setDenoiseEnabled(true)
                val config = buildConfig(dir, model.family)
                val engine = try {
                    // Models live in filesDir, not in the APK assets. Passing a non-null
                    // AssetManager makes sherpa's ReadFile treat the absolute paths as assets,
                    // and it calls exit(255) when the lookup fails (uncatchable process death).
                    OfflineTts(null, config)
                } catch (e: Throwable) {
                    DiagLog.exception(ctx, "engine init failed, retrying cpu", e)
                    // Retry on plain CPU if the optional provider was rejected.
                    val fallback = buildConfig(dir, model.family, provider = "cpu")
                    OfflineTts(null, fallback)
                }
                tts = engine
                sampleRate = engine.sampleRate().takeIf { it > 0 } ?: model.sampleRate
                DiagLog.line(ctx, "engine ready rate=$sampleRate speakers=${engine.numSpeakers()} in ${System.currentTimeMillis() - started}ms")
                player.ensure(sampleRate)
                // Shared PcmPlayer may still be stopped from a previous engine; clear that state
                // so the new engine's first write is accepted.
                player.reset()
                startWorkers()
                isReady = true
                main.post { onResult(true, null) }
            } catch (e: Throwable) {
                isReady = false
                DiagLog.exception(ctx, "engine prepare FAILED", e)
                main.post { onResult(false, e.message ?: e.javaClass.simpleName) }
            }
        }.start()
    }

    private fun loadReference(dir: File): FloatArray? {
        val custom = prefs.pocketVoicePath
        val file = if (custom.isNotEmpty() && File(custom).isFile) File(custom) else File(dir, "test_wavs/bria.wav")
        val samples = if (file.isFile) WavReader.readForCloning(file) else null
        referenceRate = WavReader.TARGET_RATE
        return samples
    }

    private fun buildConfig(dir: File, family: String, provider: String = if (prefs.nnapi) "nnapi" else "cpu"): OfflineTtsConfig {
        val kokoro: OfflineTtsKokoroModelConfig
        val pocket: OfflineTtsPocketModelConfig
        if (family == "pocket") {
            kokoro = OfflineTtsKokoroModelConfig()
            pocket = OfflineTtsPocketModelConfig(
                ModelManager.findFile(dir, listOf("lm_flow.int8.onnx", "lm_flow.onnx"))!!.absolutePath,
                ModelManager.findFile(dir, listOf("lm_main.int8.onnx", "lm_main.onnx"))!!.absolutePath,
                ModelManager.findFile(dir, listOf("encoder.onnx", "encoder.int8.onnx"))!!.absolutePath,
                ModelManager.findFile(dir, listOf("decoder.int8.onnx", "decoder.onnx"))!!.absolutePath,
                File(dir, "text_conditioner.onnx").absolutePath,
                File(dir, "vocab.json").absolutePath,
                File(dir, "token_scores.json").absolutePath,
                16 // HayaiTTS: pocket voice embedding cache capacity
            )
        } else {
            pocket = OfflineTtsPocketModelConfig()
            kokoro = OfflineTtsKokoroModelConfig(
                ModelManager.findFile(dir, listOf("model.int8.onnx", "model.onnx"))!!.absolutePath,
                File(dir, "voices.bin").absolutePath,
                File(dir, "tokens.txt").absolutePath,
                File(dir, "espeak-ng-data").absolutePath,
                "", // lexicon: en bundle relies on espeak-ng-data
                "en-us",
                "", // dictDir
                1.0f
            )
        }
        val modelConfig = OfflineTtsModelConfig(
            OfflineTtsVitsModelConfig(),
            OfflineTtsMatchaModelConfig(),
            kokoro,
            OfflineTtsZipVoiceModelConfig(),
            OfflineTtsKittenModelConfig(),
            pocket,
            OfflineTtsSupertonicModelConfig(),
            prefs.threads.coerceIn(1, Runtime.getRuntime().availableProcessors()),
            false,
            provider
        )
        // maxNumSentences = 1: we already split text, so the engine must not re-chunk it.
        return OfflineTtsConfig(modelConfig, "", "", 1, 0.2f)
    }

    private fun startWorkers() {
        if (genThread == null) {
            genThread = Thread({ generationLoop() }, "ns-sherpa-gen").apply { isDaemon = true; start() }
        }
        if (monThread == null) {
            monThread = Thread({ monitorLoop() }, "ns-sherpa-mon").apply { isDaemon = true; start() }
        }
    }

    private fun generationLoop() {
        while (true) {
            val task = try {
                genQueue.poll(500, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                null
            } ?: continue
            val engine = tts ?: run { main.post { task.onDone(false) }; continue }
            if (stopFlag || task.epoch != cancelEpoch) {
                main.post { task.onDone(false) }
                continue
            }
            // Neural-frontend guard: Kokoro (espeak-ng) and Pocket (SentencePiece
            // LM) hallucinate on stray quotes / asterisks / censored stars /
            // punctuation-only chunks ("...", '"', "*", "—"). Clean first; skip
            // unspeakable sentences by advancing (success, no audio) instead of
            // feeding "" / '"' to the model. See TtsTextCleaner.
            var cleaned = try {
                TtsTextCleaner.cleanForNeural(task.text)
            } catch (e: Throwable) {
                // Never let a sanitizer failure kill the generation thread (playback
                // would hang). Fall back to the raw text only if it is speakable.
                DiagLog.exception(ctx, "tts clean failed, using raw", e)
                task.text.takeIf { t -> t.any { it.isLetterOrDigit() } }
            }
            if (cleaned == null) {
                DiagLog.line(ctx, "tts skip unspeakable len=${task.text.length} raw=\"${task.text.take(48).replace('\n', ' ')}\"")
                main.post { task.onDone(true) }
                continue
            }
            if (cleaned != task.text) {
                DiagLog.line(
                    ctx,
                    "tts clean \"${task.text.take(40).replace('\n', ' ')}\" -> " +
                        "\"${cleaned.take(40).replace('\n', ' ')}\""
                )
            }
            try {
                player.ensure(sampleRate)
                val cfg = GenerationConfig(
                    generationSpeed(), // speed from the player controls, clamped to this engine
                    1.0f,               // silenceScale: HayaiTTS value
                    if (family == "pocket") 0 else prefs.kokoroSid,
                    reference ?: FloatArray(0),
                    referenceRate,
                    "",                 // Pocket needs no reference transcript
                    8,                  // numSteps: HayaiTTS default for cloning generation
                    emptyMap()
                )
                val sink = SampleSink(task.epoch)
                val started = System.currentTimeMillis()
                val label = cleaned.take(28).replace('\n', ' ')
                DiagLog.line(ctx, "gen start \"$label\" queue=${player.queueDepth()}")
                engine.generateWithConfigAndCallback(cleaned, cfg, sink)
                // Skip-silence: generation has ended, so the held-back last chunk can now lose
                // its trailing silence. No-op when the feature is off (nothing was held back).
                if (!sink.garbage && !sink.flushPending()) {
                    main.post { task.onDone(false) }
                    continue
                }
                if (sink.garbage) {
                    // Deterministic (Kokoro) or sampled (Pocket) saturation: drop the
                    // sentence instead of playing noise, and advance so the reader
                    // does not stall. Logged so the offending input can be inspected.
                    DiagLog.line(
                        ctx,
                        "tts garbage (rail-pinned) skipped \"${cleaned.take(48)}\""
                    )
                    main.post { task.onDone(true) }
                    continue
                }
                // Safety: make sure the track is running before tracking the drain boundary.
                player.startIfBuffered()
                // Ramp the sentence tail to silence before the next one starts.
                if (sink.wrote) player.endSentence()
                val end = player.writtenFrames
                DiagLog.line(
                    ctx,
                    "gen done \"$label\" in ${System.currentTimeMillis() - started}ms " +
                        "frames=$end queue=${player.queueDepth()}" +
                        if (sink.total >= (if (sampleRate > 0) sampleRate else 24000) * 45L) " CAPPED" else ""
                )
                if (sink.wrote && end > 0) {
                    boundaries.put(Boundary(end, player.epoch(), task.onDone))
                } else if (sink.produced && !sink.writeFailed) {
                    // Skip-silence dropped every chunk (a sentence the vocoder rendered as pure
                    // silence). Advance without audio instead of stalling the reader.
                    main.post { task.onDone(true) }
                } else {
                    // Never advance the reader when nothing reached the audio track.
                    main.post { task.onDone(false) }
                }
            } catch (e: Throwable) {
                DiagLog.exception(ctx, "generation failed for \"${cleaned.take(40)}…\"", e)
                main.post { task.onDone(false) }
            }
        }
    }

    private fun monitorLoop() {
        while (true) {
            val b = try {
                boundaries.poll(250, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                null
            } ?: continue
            if (stopFlag) { main.post { b.onDone(false) }; continue }
            player.awaitDrained(b.endFrame, b.epoch) { stopFlag }
            val ok = !stopFlag
            main.post { b.onDone(ok) }
        }
    }

    override fun speak(text: String, onDone: (Boolean) -> Unit) {
        if (!isReady) { main.post { onDone(false) }; return }
        if (stopFlag) {
            stopFlag = false
            player.reset()
        }
        genQueue.offer(Task(text, cancelEpoch, onDone))
    }

    override fun stop() {
        stopFlag = true
        cancelEpoch++
        genQueue.clear()
        while (true) {
            val b = boundaries.poll() ?: break
            main.post { b.onDone(false) }
        }
        player.stop()
    }

    override fun release() {
        stop()
        try { tts?.release() } catch (e: Throwable) {}
        tts = null
        isReady = false
    }

    override fun voices(): List<Pair<String, String>> {
        return if (family == "pocket") {
            listOf("" to "Default (bria) + imported clip")
        } else {
            Prefs.KOKORO_VOICES.mapIndexed { i, p -> i.toString() to p.second }
        }
    }

    override fun setVoice(id: String) {
        voiceId = id
        if (family == "pocket") return
        id.toIntOrNull()?.let { prefs.kokoroSid = it }
    }

    override fun bufferedSeconds(): Float = player.bufferedSeconds()

    /**
     * Effective generation speed for the sherpa frontend: the player-control value clamped to
     * this engine's [maxSpeed] contract. A corrupt/NaN pref falls back to 1.0 instead of
     * poisoning the native config.
     */
    private fun generationSpeed(): Float {
        val s = prefs.speed
        return if (s.isFinite()) s.coerceIn(MIN_SPEED, MAX_SPEED) else 1.0f
    }

    private companion object {
        /** Lowest supported speed (unchanged from the previous contract). */
        const val MIN_SPEED = 0.5f

        /** Highest speed Kokoro/Pocket still pronounce intelligibly; also reported as maxSpeed. */
        const val MAX_SPEED = 3.0f
    }
}
