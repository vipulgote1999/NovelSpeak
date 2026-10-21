package app.novelspeak

import android.app.Application
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import java.io.File

class NovelSpeakApp : Application() {

    lateinit var prefs: Prefs
        private set
    lateinit var player: PcmPlayer
        private set
    lateinit var reader: Reader
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        // No adb on this device: persist crashes to /sdcard/Download/novelspeak-crash.txt
        // (MediaStore insert works without storage permission on API 29+; Termux can read it).
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                dumpCrash("thread=${thread?.name}\n" + Log.getStackTraceString(error))
            } catch (ignored: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }

        prefs = Prefs(this)
        player = PcmPlayer()
        reader = Reader(this)
    }

    private fun dumpCrash(text: String) {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "novelspeak-crash.txt")
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                return
            }
        }
        // Fallback: external app dir (readable via adb/shell on older builds).
        val dir = getExternalFilesDir(null) ?: filesDir
        File(dir, "crash.txt").writeText(text)
    }

    companion object {
        lateinit var instance: NovelSpeakApp
            private set
    }
}

/**
 * Sentence queue + playback state machine.
 *
 * Pipeline shape mirrors both reference apps:
 *  - text is pre-split into short sentences (NekoSpeak chunking)
 *  - up to `prefetch` sentences are handed to the engine ahead of the cursor, so the next
 *    generation runs while the current sentence is playing (HayaiTTS streaming model)
 *  - engine-agnostic: SystemTtsEngine queues internally, SherpaEngine streams PCM
 */
class Reader(private val app: NovelSpeakApp) {

    data class Block(val text: String, val blockIndex: Int)

    interface Listener {
        fun onReaderState(state: State)
        fun onHighlight(blockIndex: Int)
    }

    data class State(
        val playing: Boolean,
        val title: String,
        val cursor: Int,
        val total: Int,
        val engine: String,
        val status: String
    )

    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<Listener>()

    private var sentences: List<Block> = emptyList()
    private var title = ""
    private var cursor = 0
    private var submitted = -1
    private var playing = false
    private var generation = 0
    private var status = "Idle"

    private var engine: TtsEngine? = null
    private var engineName = ""

    fun addListener(l: Listener) { if (!listeners.contains(l)) listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    fun state() = State(playing, title, cursor, sentences.size, engineName, status)

    private fun notifyState() {
        val s = state()
        main.post { for (l in ArrayList(listeners)) l.onReaderState(s) }
    }

    private fun notifyHighlight() {
        val b = sentences.getOrNull(cursor) ?: return
        main.post { for (l in ArrayList(listeners)) l.onHighlight(b.blockIndex) }
    }

    /** Replace the reading queue with newly extracted content. */
    fun setContent(newTitle: String, blocks: List<Block>, append: Boolean = false) {
        stopInternal()
        title = if (append && title.isNotEmpty()) title else newTitle
        sentences = if (append) sentences + blocks else blocks
        cursor = 0
        submitted = -1
        status = if (sentences.isEmpty()) "No readable text found" else "Ready · ${sentences.size} sentences"
        notifyState()
        notifyHighlight()
    }

    fun appendContent(blocks: List<Block>) {
        if (blocks.isEmpty()) return
        sentences = sentences + blocks
        status = "Chapter appended · ${sentences.size} sentences"
        notifyState()
        if (playing) pump()
    }

    fun hasContent() = sentences.isNotEmpty()

    private fun ensureEngine(onReady: (TtsEngine?) -> Unit) {
        val wanted = app.prefs.engine
        val current = engine
        if (current != null && engineName == wanted) { onReady(current); return }
        current?.release()
        engine = null
        engineName = wanted
        status = "Preparing $wanted engine…"
        notifyState()
        val e: TtsEngine = if (wanted == Prefs.ENGINE_SHERPA) {
            SherpaEngine(app, app.prefs, app.player)
        } else {
            SystemTtsEngine(app, app.prefs)
        }
        e.prepare { ok, message ->
            if (ok) {
                engine = e
                status = "Ready · ${sentences.size} sentences"
                onReady(e)
            } else {
                status = "Engine error: ${message ?: "unknown"}"
                e.release()
                onReady(null)
            }
            notifyState()
        }
    }

    fun play() {
        if (sentences.isEmpty()) { status = "Nothing to read"; notifyState(); return }
        if (playing) return
        ensureEngine { e ->
            if (e == null) return@ensureEngine
            playing = true
            status = "Playing"
            generation++
            submitted = cursor - 1
            pump()
            notifyState()
            PlaybackService.start(app)
        }
    }

    fun pause() {
        if (!playing) return
        playing = false
        generation++
        engine?.stop()
        status = "Paused"
        notifyState()
    }

    fun resume() = play()

    fun stop() {
        stopInternal()
        status = "Stopped"
        notifyState()
    }

    private fun stopInternal() {
        playing = false
        generation++
        submitted = cursor - 1
        engine?.stop()
        PlaybackService.stop(app)
    }

    fun toggle() { if (playing) pause() else play() }

    fun nextSentence() {
        if (cursor < sentences.size - 1) seek(cursor + 1)
    }

    fun prevSentence() {
        if (cursor > 0) seek(cursor - 1) else seek(0)
    }

    fun seek(index: Int) {
        if (sentences.isEmpty()) return
        val wasPlaying = playing
        cursor = index.coerceIn(0, sentences.size - 1)
        generation++
        engine?.stop()
        submitted = cursor - 1
        notifyHighlight()
        notifyState()
        if (wasPlaying) pump()
    }

    private fun pump() {
        if (!playing) return
        val e = engine ?: return
        val gen = generation
        val lookahead = app.prefs.prefetch.coerceIn(0, 5)
        while (submitted < cursor + lookahead && submitted < sentences.size - 1) {
            submitted++
            val index = submitted
            e.speak(sentences[index].text) { ok -> onSentenceDone(gen, index, ok) }
        }
        if (submitted < cursor) {
            submitted = cursor
            val index = cursor
            e.speak(sentences[index].text) { ok -> onSentenceDone(gen, index, ok) }
        }
    }

    private fun onSentenceDone(gen: Int, index: Int, ok: Boolean) {
        main.post(Runnable {
            if (gen != generation || !playing) return@Runnable
            if (!ok) {
                status = "Playback stopped (engine error)"
                playing = false
                notifyState()
                PlaybackService.stop(app)
                return@Runnable
            }
            if (index == cursor) {
                if (cursor >= sentences.size - 1) {
                    playing = false
                    status = "Finished"
                    notifyState()
                    PlaybackService.stop(app)
                    return@Runnable
                }
                cursor++
                notifyHighlight()
                notifyState()
                pump()
            }
        })
    }

    fun setSpeed(v: Float) {
        app.prefs.speed = v
        status = "Speed ${"%.2f".format(v)}×"
        notifyState()
    }

    fun release() {
        stopInternal()
        engine?.release()
        engine = null
        app.player.release()
    }
}
