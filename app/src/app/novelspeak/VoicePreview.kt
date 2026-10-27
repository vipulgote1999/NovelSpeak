package app.novelspeak

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File

/**
 * Short disposable voice samples for Settings.
 *
 * Each preview owns its engine, pauses the reader if it is speaking, and resumes it when the
 * sample finishes so auditioning voices never talks over the book. System previews use the
 * provider's own playback; neural previews use a temporary [SherpaEngine] writing to the app
 * player. Previews are cancellable: tapping another voice bumps the generation and the stale
 * callback is ignored.
 */
class VoicePreview(private val context: Context, private val prefs: Prefs) {

    private val main = Handler(Looper.getMainLooper())
    private val sample = "The lantern flickered twice, and then the harbour went dark."
    private var tts: TextToSpeech? = null
    private var sherpa: SherpaEngine? = null
    private var generation = 0
    private var resumeReader = false
    private var listening = false

    private companion object {
        /** Below this, loading libonnxruntime + a neural model can abort the process. */
        const val PREVIEW_MIN_FREE_MB = 700L
    }

    val isActive: Boolean get() = listening

    fun previewSystem(voiceId: String, label: String, onStatus: (String) -> Unit) {
        stopInternal()
        val gen = ++generation
        pauseReader()
        listening = true
        DiagLog.line(context, "voice preview system voice=" + voiceId + " label=" + label)
        report(onStatus, "Previewing " + label + "…")

        var created: TextToSpeech? = null
        created = TextToSpeech(context) { status ->
            if (gen != generation) {
                try { created?.shutdown() } catch (t: Throwable) { }
                return@TextToSpeech
            }
            if (status != TextToSpeech.SUCCESS) {
                finish(gen, "System TTS is unavailable", onStatus)
                return@TextToSpeech
            }
            val engine = created ?: return@TextToSpeech
            tts = engine
            if (voiceId.isNotEmpty()) {
                try {
                    engine.voices?.firstOrNull { it.name == voiceId }?.let { engine.voice = it }
                } catch (t: Throwable) { }
            }
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    main.post { finish(gen, "Preview finished · " + label, onStatus) }
                }
                @Deprecated("deprecated")
                override fun onError(utteranceId: String?) {
                    main.post { finish(gen, "Preview failed · " + label, onStatus) }
                }
                override fun onError(utteranceId: String?, errorCode: Int) {
                    main.post { finish(gen, "Preview failed · " + label, onStatus) }
                }
            })
            try {
                val ok = engine.speak(sample, TextToSpeech.QUEUE_FLUSH, null, "voice-preview-" + gen)
                if (ok != TextToSpeech.SUCCESS) finish(gen, "Preview failed · " + label, onStatus)
            } catch (t: Throwable) {
                finish(gen, "Preview failed · " + label, onStatus)
            }
        }
    }

    fun previewNeural(voiceId: String, label: String, onStatus: (String) -> Unit) {
        if (!nativeAbiMatchesApk()) {
            listening = false
            DiagLog.line(context, "voice preview refused: ABI mismatch")
            report(
                onStatus,
                "Neural preview needs this APK's native library for this device."
            )
            return
        }
        // Loading ONNX Runtime + a model is a large native allocation. On a low-memory device
        // the dynamic linker aborts the process inside libonnxruntime, which cannot be caught;
        // refuse before touching the engine instead of crashing the app.
        val freeMb = availableMemoryMb()
        if (freeMb in 0 until PREVIEW_MIN_FREE_MB) {
            listening = false
            DiagLog.line(
                context,
                "voice preview refused: low memory (" + freeMb + " MB free) voice=" + voiceId
            )
            report(
                onStatus,
                "Not enough free memory for a neural preview (" + freeMb +
                    " MB free). Close pages and retry."
            )
            return
        }
        stopInternal()
        val gen = ++generation
        pauseReader()
        listening = true
        DiagLog.line(context, "voice preview neural voice=" + voiceId + " label=" + label)
        report(onStatus, "Loading " + label + "…")
        val app = NovelSpeakApp.instance
        val engine = SherpaEngine(app, prefs, app.player)
        sherpa = engine
        engine.setVoice(voiceId)
        engine.prepare { ok, message ->
            if (gen != generation) {
                engine.release()
                return@prepare
            }
            if (!ok) {
                finish(gen, message ?: "Model not ready", onStatus)
                return@prepare
            }
            report(onStatus, "Previewing " + label + "…")
            try {
                engine.speak(sample) { done ->
                    main.post {
                        finish(
                            gen,
                            if (done) "Preview finished · " + label
                            else "Preview failed · " + label,
                            onStatus
                        )
                    }
                }
            } catch (t: Throwable) {
                finish(gen, "Preview failed · " + label, onStatus)
            }
        }
    }

    fun stop() {
        generation++
        listening = false
        stopInternal()
        maybeResume()
    }

    fun release() {
        generation++
        listening = false
        stopInternal()
        maybeResume()
    }

    /**
     * The shipping APK is arm64-only. Installing it on an x86_64 emulator leaves the arm64
     * .so next to an x86 process, and dlopen aborts inside ONNX Runtime. Compare the app's
     * native-library ABI with the runtime arch and refuse preview when they differ.
     */
    private fun nativeAbiMatchesApk(): Boolean {
        return try {
            val dir = context.applicationInfo.nativeLibraryDir
            if (!File(dir, "libonnxruntime.so").isFile) {
                false
            } else {
                val arch = System.getProperty("os.arch") ?: ""
                val expected = when {
                    arch.contains("aarch64") -> "arm64-v8a"
                    arch.contains("x86_64") -> "x86_64"
                    arch.contains("x86") -> "x86"
                    arch.contains("arm") -> "armeabi-v7a"
                    else -> ""
                }
                expected.isNotEmpty() && dir.endsWith("/" + expected)
            }
        } catch (t: Throwable) {
            false
        }
    }

    private fun availableMemoryMb(): Long = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        info.availMem / (1024L * 1024L)
    } catch (t: Throwable) {
        -1L
    }

    private fun pauseReader() {
        try {
            val reader = NovelSpeakApp.instance.reader
            resumeReader = reader.state().playing
            if (resumeReader) reader.pause()
        } catch (t: Throwable) {
            resumeReader = false
        }
    }

    private fun maybeResume() {
        if (!resumeReader) return
        resumeReader = false
        try { NovelSpeakApp.instance.reader.play() } catch (t: Throwable) { }
    }

    private fun finish(gen: Int, message: String, onStatus: (String) -> Unit) {
        if (gen != generation) return
        listening = false
        stopInternal()
        report(onStatus, message)
        maybeResume()
    }

    private fun report(onStatus: (String) -> Unit, message: String) {
        main.post { onStatus(message) }
    }

    private fun stopInternal() {
        try { tts?.stop() } catch (t: Throwable) { }
        try { tts?.shutdown() } catch (t: Throwable) { }
        tts = null
        try { sherpa?.stop() } catch (t: Throwable) { }
        try { sherpa?.release() } catch (t: Throwable) { }
        sherpa = null
    }
}
