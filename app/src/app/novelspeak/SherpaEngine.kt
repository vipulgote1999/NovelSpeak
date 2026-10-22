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

    @Volatile override var isReady = false
        private set

    private var tts: OfflineTts? = null
    private var family = ""
    private var sampleRate = 24000
    private var reference: FloatArray? = null
    private var referenceRate = 24000

    @Volatile private var stopFlag = false
    private val main = Handler(Looper.getMainLooper())

    private class Task(val text: String, val onDone: (Boolean) -> Unit)
    private class Boundary(val endFrame: Long, val onDone: (Boolean) -> Unit)

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
                val config = buildConfig(dir, model.family)
                val engine = try {
                    OfflineTts(ctx.assets, config)
                } catch (e: Throwable) {
                    DiagLog.exception(ctx, "engine init failed, retrying cpu", e)
                    // Retry on plain CPU if the optional provider was rejected.
                    val fallback = buildConfig(dir, model.family, provider = "cpu")
                    OfflineTts(ctx.assets, fallback)
                }
                tts = engine
                sampleRate = engine.sampleRate().takeIf { it > 0 } ?: model.sampleRate
                DiagLog.line(ctx, "engine ready rate=$sampleRate speakers=${engine.numSpeakers()} in ${System.currentTimeMillis() - started}ms")
                player.ensure(sampleRate)
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
            if (stopFlag) { main.post { task.onDone(false) }; continue }
            try {
                player.ensure(sampleRate)
                val cfg = GenerationConfig(
                    1.0f,               // speed: HayaiTTS uses 1.0 for the cloning/streaming path
                    1.0f,               // silenceScale: HayaiTTS value
                    if (family == "pocket") 0 else prefs.kokoroSid,
                    reference ?: FloatArray(0),
                    referenceRate,
                    "",                 // Pocket needs no reference transcript
                    8,                  // numSteps: HayaiTTS default for cloning generation
                    emptyMap()
                )
                val generated = engine.generateWithConfigAndCallback(task.text, cfg) { samples ->
                    if (stopFlag) 1
                    else if (player.write(samples)) 0
                    else 1
                }
                val end = player.writtenFrames
                if (end > 0) {
                    boundaries.put(Boundary(end, task.onDone))
                } else {
                    main.post { task.onDone(generated.samples.isNotEmpty() && !stopFlag) }
                }
            } catch (e: Throwable) {
                DiagLog.exception(ctx, "generation failed for \"${task.text.take(40)}…\"", e)
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
            player.awaitDrained(b.endFrame) { stopFlag }
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
        genQueue.offer(Task(text, onDone))
    }

    override fun stop() {
        stopFlag = true
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
}
