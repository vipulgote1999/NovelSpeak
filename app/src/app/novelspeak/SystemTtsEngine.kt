package app.novelspeak

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Android's built-in TextToSpeech engine.
 *
 * This is the always-available backend (works offline, every language the device ships, no model
 * download) and the safety net the user asked for. Sentence pipelining is delegated to the TTS
 * service itself via QUEUE_ADD, which is how every reliable read-aloud implementation does it —
 * the queue lives in the TTS process, so playback survives our process being backgrounded.
 */
class SystemTtsEngine(private val ctx: Context, private val prefs: Prefs) : TtsEngine {

    override val id = "system"
    override var isReady = false
        private set

    private var tts: TextToSpeech? = null
    private val main = Handler(Looper.getMainLooper())
    private val counter = AtomicLong(0)
    private val pending = HashMap<String, (Boolean) -> Unit>()
    private var voiceId: String? = null

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
                        override fun onDone(utteranceId: String?) = finish(utteranceId, true)
                        @Deprecated("deprecated")
                        override fun onError(utteranceId: String?) = finish(utteranceId, false)
                        override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId, false)
                        override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(utteranceId, false)
                    })
                    try {
                        t.language = Locale.getDefault()
                    } catch (e: Throwable) {
                    }
                    isReady = true
                    DiagLog.line(ctx, "tts init ok voices=${t.voices?.size ?: 0}")
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

    private fun finish(utteranceId: String?, ok: Boolean) {
        val id = utteranceId ?: return
        val cb = synchronized(pending) { pending.remove(id) } ?: return
        main.post { cb(ok) }
    }

    override fun speak(text: String, onDone: (Boolean) -> Unit) {
        val t = tts
        if (t == null || !isReady) { main.post { onDone(false) }; return }
        try {
            val utt = "ns-" + counter.incrementAndGet()
            synchronized(pending) { pending[utt] = onDone }
            t.setSpeechRate(prefs.speed.coerceIn(0.5f, 2.0f))
            voiceId?.let { vid ->
                try {
                    val v = t.voices?.firstOrNull { it.name == vid }
                    if (v != null) t.voice = v
                } catch (e: Throwable) {}
            }
            val res = t.speak(text, TextToSpeech.QUEUE_ADD, null, utt)
            if (res != TextToSpeech.SUCCESS) {
                synchronized(pending) { pending.remove(utt) }
                main.post { onDone(false) }
            }
        } catch (t2: Throwable) {
            DiagLog.exception(ctx, "tts speak failed len=${text.length}", t2)
            main.post { onDone(false) }
        }
    }

    override fun stop() {
        synchronized(pending) { pending.clear() }
        try { tts?.stop() } catch (e: Throwable) {}
    }

    override fun release() {
        stop()
        try { tts?.shutdown() } catch (e: Throwable) {}
        tts = null
        isReady = false
    }

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
}
