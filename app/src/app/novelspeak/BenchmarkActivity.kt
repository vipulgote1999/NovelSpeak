package app.novelspeak

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * Dev-only benchmark harness for the TTS backends.
 *
 * One model per process, so `load_ms` is a real cold load (ONNX Runtime + model graph) rather
 * than a warm cache hit. The runner in `tools/tts-benchmark/run.sh` force-stops the app between
 * models and pulls the JSON written under external files.
 *
 * Launch:
 *   adb shell am start -W -n app.novelspeak/.BenchmarkActivity \
 *       --es model kokoro-int8-en-v0_19 --ei threads 4 --ei reps 2
 *
 * Results:
 *   /sdcard/Android/data/app.novelspeak/files/bench/result-<model>.json
 */
class BenchmarkActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status = TextView(this).apply {
            text = "NovelSpeak TTS benchmark\nstarting…"
            textSize = 15f
            gravity = Gravity.START
            setPadding(48, 96, 48, 48)
        }
        setContentView(status)
        val model = intent.getStringExtra("model") ?: Prefs.MODEL_KOKORO
        val threads = intent.getIntExtra("threads", 4).coerceAtLeast(1)
        val reps = intent.getIntExtra("reps", 2).coerceIn(1, 5)
        say("model=$model threads=$threads reps=$reps")
        Thread({ runOne(model, threads, reps) }, "ns-benchmark").start()
    }

    private fun runOne(modelId: String, threads: Int, reps: Int) {
        val started = SystemClock.elapsedRealtime()
        val out = try {
            if (modelId == "system") TtsBenchmark.runSystem(this, reps)
            else TtsBenchmark.runNeural(this, modelId, threads, reps)
        } catch (t: Throwable) {
            Log.e(TAG, "benchmark failed", t)
            JSONObject()
                .put("ok", false)
                .put("model", modelId)
                .put("error", t.javaClass.simpleName + ": " + (t.message ?: ""))
        }
        val elapsed = SystemClock.elapsedRealtime() - started
        out.put("wall_ms", elapsed)
        val json = out.toString(2)
        val file = writeResult(modelId, json)
        Log.i(TAG, "BENCH_DONE " + modelId + " file=" + (file?.absolutePath ?: "none") + " ms=" + elapsed)
        say("done in ${elapsed}ms\n${file?.absolutePath ?: "no file"}")
        runOnUiThread {
            status.text = "done in ${elapsed}ms\n${file?.absolutePath ?: "no file"}"
        }
        main.postDelayed({ finish() }, 600)
    }

    private fun writeResult(modelId: String, json: String): File? {
        val safe = modelId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dirs = listOfNotNull(
            File(filesDir, "bench"),
            getExternalFilesDir(null)?.let { File(it, "bench") }
        )
        for (dir in dirs) {
            try {
                dir.mkdirs()
                val file = File(dir, "result-$safe.json")
                file.writeText(json)
                return file
            } catch (t: Throwable) {
                Log.e(TAG, "writeResult failed in " + dir, t)
            }
        }
        return null
    }

    private fun say(text: String) {
        Log.i(TAG, text)
        runOnUiThread { status.text = text }
    }

    private companion object {
        const val TAG = "NovelSpeakBench"
    }
}

/**
 * Pure measurement code. It deliberately owns the sherpa engine instead of driving [SherpaEngine]:
 * the reader attaches an AudioTrack and waits for real-time drain, which would mix playback time
 * into synthesis latency. Configuration is shared through [SherpaConfig] so this still exercises
 * the same model graph and files as the app.
 */
internal object TtsBenchmark {

    private const val TAG = "NovelSpeakBench"
    private const val NUM_STEPS = 8

    private data class Sentence(val id: String, val text: String)

    /**
     * Three lengths spanning the 180-character reader chunk. Keeping the text fixed makes
     * model-to-model RTF comparable and keeps the long sentence under the app's split limit.
     */
    private val SENTENCES = listOf(
        Sentence("short", "The lantern flickered twice, and then the harbour went dark."),
        Sentence(
            "medium",
            "She had promised herself she would not look back, not once, no matter what waited at the edge of the road."
        ),
        Sentence(
            "long",
            "The rain had been falling since dawn, turning the cobbled streets into narrow rivers that carried petals, ash, and the last light of the dying sun toward the sea."
        )
    )

    fun runNeural(context: Context, modelId: String, threads: Int, reps: Int): JSONObject {
        val model = ModelManager.model(modelId)
            ?: return error(modelId, "unknown model")
        if (!ModelManager.isInstalled(context, modelId)) {
            return error(modelId, "model not installed at " + ModelManager.modelDir(context, modelId))
        }
        val dir = ModelManager.modelDir(context, modelId)
        val reference = loadReference(context, dir, model.family)
        val transcript = if (model.family == "zipvoice") findTranscript(dir) else ""
        val base = deviceJson()
            .put("ok", true)
            .put("engine", "sherpa-onnx")
            .put("model", modelId)
            .put("family", model.family)
            .put("threads", threads)
            .put("provider", "cpu")
            .put("reps", reps)
            .put("speed", 1.0)
            .put("num_steps", NUM_STEPS)
            .put("reference_samples", reference?.first?.size ?: 0)
            .put("reference_rate", reference?.second ?: 0)
            .put("reference_transcript", transcript)

        val before = memory(context)
        val loadStart = SystemClock.elapsedRealtimeNanos()
        val tts = OfflineTts(null, SherpaConfig.build(dir, model.family, threads, "cpu"))
        val loadMs = msSince(loadStart)
        val sampleRate = tts.sampleRate().takeIf { it > 0 } ?: model.sampleRate
        val afterLoad = memory(context)
        Log.i(TAG, "loaded " + modelId + " in " + loadMs + "ms rate=" + sampleRate)

        val rows = JSONArray()
        val audioFiles = JSONArray()
        // Warm up with the short sentence, then discard. First-call cost includes JIT/allocator
        // warmup and is not part of steady-state RTF.
        try {
            generate(tts, SENTENCES[0], sampleRate, reference, model.family, transcript, null)
            for (rep in 0 until reps) {
                for (sentence in SENTENCES) {
                    val outFile = File(
                        benchDir(context),
                        model.family + "-" + sentence.id + "-r" + rep + ".wav"
                    )
                    val row = generate(
                        tts, sentence, sampleRate, reference, model.family, transcript, outFile
                    )
                    row.put("rep", rep)
                    rows.put(row)
                    if (outFile.isFile) audioFiles.put(outFile.absolutePath)
                    Log.i(TAG, "row " + modelId + " " + sentence.id + " r" + rep + " " + row)
                }
            }
        } finally {
            try { tts.release() } catch (t: Throwable) { }
        }
        val after = memory(context)
        return base
            .put("load_ms", loadMs)
            .put("sample_rate", sampleRate)
            .put("memory", memoryJson(before, afterLoad, after))
            .put("rows", rows)
            .put("summary", summary(rows))
            .put("audio_files", audioFiles)
    }

    /**
     * System TTS is measured through the same `synthesizeToFile` path the app uses, so it includes
     * provider-side render but not AudioTrack playback. The emulator may have no provider; that is
     * reported as `ok=false` rather than silently scoring it as zero.
     */
    fun runSystem(context: Context, reps: Int): JSONObject {
        val base = deviceJson()
            .put("engine", "android.speech.tts")
            .put("model", "system")
            .put("family", "system")
            .put("threads", 0)
            .put("provider", "device")
            .put("reps", reps)
            .put("speed", 1.0)

        val before = memory(context)
        var tts: TextToSpeech? = null
        val initLatch = CountDownLatch(1)
        var initOk = false
        val initStart = SystemClock.elapsedRealtimeNanos()
        tts = TextToSpeech(context) { status ->
            initOk = status == TextToSpeech.SUCCESS
            initLatch.countDown()
        }
        val initDone = initLatch.await(20, TimeUnit.SECONDS)
        val loadMs = msSince(initStart)
        if (!initDone || !initOk || tts == null) {
            try { tts?.shutdown() } catch (t: Throwable) { }
            return base
                .put("ok", false)
                .put("error", if (!initDone) "init timeout" else "init status != SUCCESS")
                .put("load_ms", loadMs)
        }
        val engine = tts
        var lang = "default"
        try {
            val res = engine.setLanguage(Locale.US)
            lang = if (res == TextToSpeech.LANG_MISSING_DATA ||
                res == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                val fallback = engine.setLanguage(Locale.getDefault())
                "fallback:" + Locale.getDefault().toLanguageTag() + ":" + fallback
            } else {
                "en-US:" + res
            }
        } catch (t: Throwable) {
            lang = "error:" + t.javaClass.simpleName
        }
        val voice = try { engine.voice?.name ?: "default" } catch (t: Throwable) { "unknown" }
        val afterLoad = memory(context)
        Log.i(TAG, "system tts ready in " + loadMs + "ms lang=" + lang + " voice=" + voice)

        val rows = JSONArray()
        val audioFiles = JSONArray()
        var completedCount = 0
        var failedCount = 0
        try {
            // Warmup with text that is never measured. System providers cache synthesized
            // utterances by text; reusing a measured sentence here would make it look 100x
            // faster on subsequent calls.
            renderSystem(
                engine, context,
                Sentence("warmup", "This sentence only warms up the speech provider."),
                null
            )
            for (rep in 0 until reps) {
                for (sentence in SENTENCES) {
                    val outFile = File(
                        benchDir(context),
                        "system-" + sentence.id + "-r" + rep + ".wav"
                    )
                    // Unique text per rep so a provider-side audio cache cannot turn the
                    // second measurement into an 8 ms "synthesis".
                    val spoken = if (rep == 0) sentence
                    else sentence.copy(text = "Once more, " + sentence.text)
                    val row = renderSystem(engine, context, spoken, outFile)
                    row.put("rep", rep)
                    if (row.optBoolean("completed", false)) {
                        rows.put(row)
                        completedCount++
                        if (outFile.isFile) audioFiles.put(outFile.absolutePath)
                        Log.i(TAG, "row system " + sentence.id + " r" + rep + " " + row)
                    } else {
                        failedCount++
                        Log.w(TAG, "system render failed " + sentence.id + " r" + rep + " " + row)
                        // Do not spend 15 s per remaining sentence if the provider is absent:
                        // report the backend unavailable instead.
                        break
                    }
                }
                if (failedCount > 0) break
            }
        } finally {
            try { engine.shutdown() } catch (t: Throwable) { }
        }
        val after = memory(context)
        return base
            .put("ok", completedCount > 0)
            .put("completed_count", completedCount)
            .put("failed_count", failedCount)
            .put("error", if (completedCount == 0) "no successful synthesizeToFile renders" else "")
            .put("load_ms", loadMs)
            .put("sample_rate", 24000)
            .put("language", lang)
            .put("voice", voice)
            .put("memory", memoryJson(before, afterLoad, after))
            .put("rows", rows)
            .put("summary", summary(rows))
            .put("audio_files", audioFiles)
    }

    /**
     * Named Function1 (not a lambda) so the JNI callback keeps the specialised
     * `invoke([F)Ljava/lang/Integer;` signature. See [SherpaEngine.SampleSink].
     */
    private class BenchSink(private val startNanos: Long) : (FloatArray) -> Int {
        var firstChunkMs = -1L
            private set
        var samples = 0L
            private set

        override fun invoke(chunk: FloatArray): Int {
            if (firstChunkMs < 0) {
                firstChunkMs = (SystemClock.elapsedRealtimeNanos() - startNanos) / 1_000_000L
            }
            samples += chunk.size
            return 1
        }
    }

    private fun generate(
        tts: OfflineTts,
        sentence: Sentence,
        sampleRate: Int,
        reference: Pair<FloatArray, Int>?,
        family: String,
        transcript: String,
        saveTo: File?
    ): JSONObject {
        val cfg = GenerationConfig(
            1.0f,
            1.0f,
            when (family) {
                "supertonic" -> 0
                else -> 0
            },
            reference?.first ?: FloatArray(0),
            reference?.second ?: 24000,
            if (family == "zipvoice") transcript else "",
            NUM_STEPS,
            emptyMap()
        )
        var firstChunkMs = -1L
        var callbackSamples = 0L
        val start = SystemClock.elapsedRealtimeNanos()
        // Do not pass a Kotlin lambda here: sherpa's JNI looks up invoke([F)Ljava/lang/Integer;
        // and Kotlin 2.2 lambdas only expose invoke(Object), which aborts the process inside
        // NewFloatArray. The reader uses the same named Function1 workaround in SherpaEngine.
        val sink = BenchSink(start)
        val audio = tts.generateWithConfigAndCallback(sentence.text, cfg, sink)
        firstChunkMs = sink.firstChunkMs
        callbackSamples = sink.samples
        val genMs = msSince(start)
        val samples = audio.samples
        val audioMs = if (audio.sampleRate > 0) samples.size * 1000L / audio.sampleRate else 0L
        var peak = 0f
        var sumSq = 0.0
        for (s in samples) {
            val a = if (s < 0f) -s else s
            if (a > peak) peak = a
            sumSq += a.toDouble() * a.toDouble()
        }
        val rms = if (samples.isEmpty()) 0.0 else sqrt(sumSq / samples.size)
        if (saveTo != null) {
            try { audio.save(saveTo.absolutePath) } catch (t: Throwable) {
                Log.w(TAG, "save wav failed " + saveTo, t)
            }
        }
        return JSONObject()
            .put("sentence", sentence.id)
            .put("chars", sentence.text.length)
            .put("gen_ms", genMs)
            .put("first_chunk_ms", firstChunkMs)
            .put("audio_ms", audioMs)
            .put("rtf", if (audioMs > 0) genMs.toDouble() / audioMs else 0.0)
            .put("x_realtime", if (genMs > 0) audioMs.toDouble() / genMs else 0.0)
            .put("samples", samples.size)
            .put("callback_samples", callbackSamples)
            .put("sample_rate", audio.sampleRate.takeIf { it > 0 } ?: sampleRate)
            .put("peak", peak.toDouble())
            .put("rms", rms)
            .put("garbage", peak >= 0.995f)
    }

    private fun renderSystem(
        tts: TextToSpeech,
        context: Context,
        sentence: Sentence,
        keep: File?
    ): JSONObject {
        val id = "bench-" + System.nanoTime()
        val tmp = File(context.cacheDir, id + ".wav")
        val latch = CountDownLatch(1)
        var ok = false
        val listener = object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                ok = true
                latch.countDown()
            }
            @Deprecated("deprecated")
            override fun onError(utteranceId: String?) { latch.countDown() }
            override fun onError(utteranceId: String?, errorCode: Int) { latch.countDown() }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { latch.countDown() }
        }
        tts.setOnUtteranceProgressListener(listener)
        val start = SystemClock.elapsedRealtimeNanos()
        val queued = tts.synthesizeToFile(sentence.text, null, tmp, id)
        val done = queued == TextToSpeech.SUCCESS && latch.await(15, TimeUnit.SECONDS)
        val genMs = msSince(start)
        val bytes = if (done && ok) tmp.readBytes() else ByteArray(0)
        val parsed = WavReader.parse(bytes)
        if (keep != null && parsed != null) {
            try { keep.writeBytes(bytes) } catch (t: Throwable) {
                Log.w(TAG, "copy system wav failed", t)
            }
        }
        tmp.delete()
        val samples = parsed?.samples ?: FloatArray(0)
        var peak = 0f
        var sumSq = 0.0
        for (s in samples) {
            val a = if (s < 0f) -s else s
            if (a > peak) peak = a
            sumSq += a.toDouble() * a.toDouble()
        }
        val rate = parsed?.sampleRate ?: 24000
        val audioMs = if (rate > 0) samples.size * 1000L / rate else 0L
        return JSONObject()
            .put("sentence", sentence.id)
            .put("chars", sentence.text.length)
            .put("gen_ms", genMs)
            .put("first_chunk_ms", genMs) // provider renders the whole utterance before onDone
            .put("audio_ms", audioMs)
            .put("rtf", if (audioMs > 0) genMs.toDouble() / audioMs else 0.0)
            .put("x_realtime", if (genMs > 0) audioMs.toDouble() / genMs else 0.0)
            .put("samples", samples.size)
            .put("callback_samples", samples.size)
            .put("sample_rate", rate)
            .put("peak", peak.toDouble())
            .put("rms", if (samples.isEmpty()) 0.0 else sqrt(sumSq / samples.size))
            .put("garbage", peak >= 0.995f)
            .put("synthesize_result", queued)
            .put("completed", done && ok)
    }

    private fun loadReference(
        context: Context,
        dir: File,
        family: String
    ): Pair<FloatArray, Int>? {
        if (family == "supertonic" || family == "kokoro") return null
        val custom = NovelSpeakApp.instance.prefs.pocketVoicePath
        var file = when {
            family == "zipvoice" -> File(dir, "test_wavs/news-female.wav")
            custom.isNotEmpty() && File(custom).isFile -> File(custom)
            else -> File(dir, "test_wavs/bria.wav")
        }
        if (!file.isFile) {
            file = File(dir, "test_wavs").listFiles()
                ?.firstOrNull { it.isFile && it.name.endsWith(".wav", true) }
                ?: file
        }
        if (!file.isFile) return null
        val samples = WavReader.readForCloning(file)
        return if (samples == null) null else samples to WavReader.TARGET_RATE
    }

    /** ZipVoice needs the verbatim transcript of the reference clip it is cloning. */
    private fun findTranscript(dir: File): String {
        val tw = File(dir, "test_wavs")
        // ZipVoice bundles list "file.wav <verbatim transcript>" in prompt.txt.
        val prompt = File(tw, "prompt.txt")
        if (prompt.isFile) {
            prompt.readLines().firstOrNull { it.startsWith("news-female.wav ") }
                ?.removePrefix("news-female.wav ")?.trim()
                ?.takeIf { it.isNotEmpty() }?.let { return it }
            prompt.readLines().firstOrNull { it.isNotBlank() }
                ?.substringAfter(' ', "")?.trim()
                ?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        val candidates = listOf(
            File(tw, "bria.txt"),
            File(tw, "transcript.txt"),
            File(dir, "transcript.txt"),
            File(tw, "bria.wav.txt")
        )
        for (f in candidates) {
            if (f.isFile) return f.readText().trim()
        }
        return ""
    }

    private fun summary(rows: JSONArray): JSONObject {
        if (rows.length() == 0) return JSONObject()
        val gen = ArrayList<Long>()
        val first = ArrayList<Long>()
        val audio = ArrayList<Long>()
        val rtf = ArrayList<Double>()
        val xrt = ArrayList<Double>()
        for (i in 0 until rows.length()) {
            val r = rows.getJSONObject(i)
            gen.add(r.optLong("gen_ms"))
            first.add(r.optLong("first_chunk_ms"))
            audio.add(r.optLong("audio_ms"))
            rtf.add(r.optDouble("rtf"))
            xrt.add(r.optDouble("x_realtime"))
        }
        return JSONObject()
            .put("n", rows.length())
            .put("gen_ms_median", median(gen))
            .put("first_chunk_ms_median", median(first))
            .put("audio_ms_median", median(audio))
            .put("rtf_median", median(rtf))
            .put("x_realtime_median", median(xrt))
            .put("rtf_min", rtf.minOrNull() ?: 0.0)
            .put("rtf_max", rtf.maxOrNull() ?: 0.0)
    }

    private fun <T : Comparable<T>> median(values: List<T>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            (sorted[mid] as Number).toDouble()
        } else {
            ((sorted[mid - 1] as Number).toDouble() + (sorted[mid] as Number).toDouble()) / 2.0
        }
    }

    private fun benchDir(context: Context): File {
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        return File(root, "bench").apply { mkdirs() }
    }

    private fun deviceJson(): JSONObject = JSONObject()
        .put("schema", 1)
        .put("device", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL)
        .put("sdk", android.os.Build.VERSION.SDK_INT)
        .put("abis", android.os.Build.SUPPORTED_ABIS.joinToString(","))
        .put("cores", Runtime.getRuntime().availableProcessors())
        .put("timestamp", System.currentTimeMillis())

    private fun memory(context: Context): Map<String, Long> {
        val rss = try {
            File("/proc/self/status").readLines()
                .firstOrNull { it.startsWith("VmRSS:") }
                ?.trim()?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull() ?: 0L
        } catch (t: Throwable) { 0L }
        val java = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        val pss = try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val info = Debug.MemoryInfo()
            am.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid()))[0]
        } catch (t: Throwable) { null }
        return mapOf(
            "rss_mb" to rss / 1024,
            "java_mb" to java / 1048576,
            "pss_mb" to (pss?.totalPss ?: 0) / 1024L
        )
    }

    private fun memoryJson(before: Map<String, Long>, afterLoad: Map<String, Long>, after: Map<String, Long>): JSONObject =
        JSONObject()
            .put("rss_before_mb", before["rss_mb"])
            .put("rss_after_load_mb", afterLoad["rss_mb"])
            .put("rss_after_gen_mb", after["rss_mb"])
            .put("pss_after_load_mb", afterLoad["pss_mb"])
            .put("pss_after_gen_mb", after["pss_mb"])
            .put("java_after_load_mb", afterLoad["java_mb"])
            .put("java_after_gen_mb", after["java_mb"])

    private fun msSince(startNanos: Long): Long =
        (SystemClock.elapsedRealtimeNanos() - startNanos) / 1_000_000L

    private fun error(model: String, message: String): JSONObject = JSONObject()
        .put("ok", false)
        .put("model", model)
        .put("error", message)
}
