package app.novelspeak

import android.app.Application
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
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
        DiagLog.init(this)
        DiagLog.line(this, "app start")

        // No adb on this device: persist crashes to /sdcard/Download/novelspeak-crash.txt
        // (MediaStore insert works without storage permission on API 29+; Termux can read it).
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                DiagLog.exception(this, "FATAL thread=${thread?.name}", error)
                dumpCrash("thread=${thread?.name}\n" + Log.getStackTraceString(error))
            } catch (ignored: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }

        prefs = Prefs(this)
        player = PcmPlayer()
        reader = Reader(this)
        AdBlocker.init(this)
        // Pronunciation dictionary: parsed once at startup; Settings re-applies on save.
        try {
            TtsOverrides.set(
                PronunciationRules.parse(prefs.pronunciationRules).map { it.from to it.to }
            )
        } catch (t: Throwable) {
            DiagLog.exception(this, "pronunciation rules load failed", t)
        }
    }

    private fun dumpCrash(text: String) {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                // Reuse one crash file; repeated inserts were creating " (1)", " (2)" clutter.
                var uri: Uri? = null
                contentResolver.query(
                    collection,
                    arrayOf(MediaStore.Downloads._ID),
                    "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
                    arrayOf("novelspeak-crash.txt", "Download%"),
                    null
                )?.use { c ->
                    if (c.moveToFirst()) uri = ContentUris.withAppendedId(collection, c.getLong(0))
                }
                if (uri == null) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, "novelspeak-crash.txt")
                        put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    uri = contentResolver.insert(collection, values)
                }
                val u = uri
                if (u != null) {
                    contentResolver.openOutputStream(u, "wt")?.use { it.write(text.toByteArray()) }
                    // Clear IS_PENDING or the file stays invisible/unreadable to other apps.
                    val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    contentResolver.update(u, done, null, null)
                    return
                }
            } catch (ignored: Throwable) {
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

        /** The last queued sentence finished playing; auto mode uses this to advance. */
        fun onQueueFinished() {}

        /** Approximate word-level progress inside the spoken sentence (char offsets). */
        fun onWordHighlight(blockIndex: Int, charStart: Int, charEnd: Int) {}
    }

    data class State(
        val playing: Boolean,
        val title: String,
        val cursor: Int,
        val total: Int,
        val engine: String,
        val voice: String,
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

    // Word-level read-along: the engine boundary APIs are chunk-based, so progress inside a
    // sentence is estimated from elapsed time and the current speed. Sentence highlight is
    // authoritative; word highlight is an enhancement and can be turned off in Settings.
    private var wordTick: Runnable? = null
    private var sentenceStartAt = 0L
    private var sentenceChars = 0

    fun addListener(l: Listener) { if (!listeners.contains(l)) listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    fun state() = State(
        playing, title, cursor, sentences.size, engineName,
        engine?.currentVoiceLabel() ?: "", status
    )

    private fun notifyState() {
        val s = state()
        main.post { for (l in ArrayList(listeners)) l.onReaderState(s) }
    }

    private fun notifyHighlight() {
        val b = sentences.getOrNull(cursor) ?: return
        main.post { for (l in ArrayList(listeners)) l.onHighlight(b.blockIndex) }
    }

    private fun notifyQueueFinished() {
        main.post { for (l in ArrayList(listeners)) l.onQueueFinished() }
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

    fun appendContent(blocks: List<Block>, newTitle: String = "") {
        if (blocks.isEmpty()) return
        if (newTitle.isNotEmpty()) title = newTitle
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
        DiagLog.line(app, "engine requested=$wanted")
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

    private fun startWordTracking() {
        stopWordTracking()
        if (!playing) return
        if (!app.prefs.readerWordHighlight) return
        val block = sentences.getOrNull(cursor) ?: return
        sentenceStartAt = android.os.SystemClock.uptimeMillis()
        sentenceChars = block.text.length
        if (sentenceChars <= 0) return
        val run = object : Runnable {
            override fun run() {
                if (!playing) return
                val textBlock = sentences.getOrNull(cursor) ?: return
                val elapsedSec = (android.os.SystemClock.uptimeMillis() - sentenceStartAt) / 1000f
                // ~13.5 chars/second at 1x for English prose; speed scales speech rate.
                val charsPerSec = 13.5f * app.prefs.speed.coerceIn(0.5f, 3.0f)
                val end = (elapsedSec * charsPerSec).toInt()
                    .coerceIn(0, textBlock.text.length)
                var start = end
                while (start > 0 && !textBlock.text[start - 1].isWhitespace()) start--
                var stop = end
                while (stop < textBlock.text.length && !textBlock.text[stop].isWhitespace()) stop++
                main.post {
                    for (l in ArrayList(listeners)) {
                        l.onWordHighlight(textBlock.blockIndex, start, stop)
                    }
                }
                main.postDelayed(this, 140L)
            }
        }
        wordTick = run
        main.post(run)
    }

    private fun stopWordTracking() {
        wordTick?.let { main.removeCallbacks(it) }
        wordTick = null
    }

    fun play() {
        if (sentences.isEmpty()) { status = "Nothing to read"; notifyState(); return }
        if (playing) return
        DiagLog.line(app, "play clicked engine=${app.prefs.engine} cursor=$cursor total=${sentences.size}")
        ensureEngine { e ->
            if (e == null) return@ensureEngine
            try {
                playing = true
                status = "Playing"
                generation++
                submitted = cursor - 1
                pump()
                schedulePump()
                startWordTracking()
                notifyState()
                PlaybackService.start(app)
                DiagLog.line(app, "play started engine=${e.id} sentence=$cursor/" + sentences.size)
            } catch (t: Throwable) {
                playing = false
                DiagLog.exception(app, "play FAILED", t)
                status = "Play error: ${t.javaClass.simpleName}: ${t.message}"
                notifyState()
            }
        }
    }

    /**
     * Re-runs pump() while playing. Without this the queue is only refilled when a sentence
     * drains, so generation stops as soon as the initial lookahead is rendered.
     */
    private fun schedulePump() {
        main.removeCallbacks(pumpTick)
        main.postDelayed(pumpTick, PUMP_INTERVAL_MS)
    }

    private val pumpTick = object : Runnable {
        override fun run() {
            if (!playing) return
            pump()
            main.postDelayed(this, PUMP_INTERVAL_MS)
        }
    }

    fun pause() {
        if (!playing) return
        playing = false
        generation++
        main.removeCallbacks(pumpTick)
        stopWordTracking()
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
        main.removeCallbacks(pumpTick)
        stopWordTracking()
        engine?.stop()
        PlaybackService.stop(app)
    }

    fun toggle() { if (playing) pause() else play() }

    /**
     * Drops the prepared engine so a changed engine/voice applies on the next play. Without
     * this, Settings wrote the new preference but the cached engine kept the old voice.
     */
    fun invalidateEngine() {
        stopInternal()
        engine?.release()
        engine = null
        engineName = ""
        status = if (sentences.isEmpty()) "Idle" else "Ready · " + sentences.size + " sentences"
        notifyState()
    }

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
        if (wasPlaying) {
            pump()
            schedulePump()
            startWordTracking()
        } else {
            main.removeCallbacks(pumpTick)
        }
    }

    private fun pump() {
        if (!playing) return
        val e = engine ?: return
        try {
            val gen = generation
            val minAhead = app.prefs.prefetch.coerceIn(0, 5)
            // 1. Always keep the user's sentence lookahead.
            while (submitted < cursor + minAhead && submitted < sentences.size - 1) {
                submitted++
                val index = submitted
                e.speak(sentences[index].text) { ok -> onSentenceDone(gen, index, ok) }
            }
            // 2. Then keep synthesizing ahead while the audio buffer is below the target,
            //    bounded by a hard cap so memory stays flat. pump() is re-run every 500 ms
            //    while playing, so the buffer refills without waiting for a sentence drain.
            while (submitted < cursor + HARD_LOOKAHEAD &&
                submitted < sentences.size - 1 &&
                e.bufferedSeconds() < TARGET_BUFFER_SECONDS
            ) {
                submitted++
                val index = submitted
                e.speak(sentences[index].text) { ok -> onSentenceDone(gen, index, ok) }
            }
            if (submitted < cursor) {
                submitted = cursor
                val index = cursor
                e.speak(sentences[index].text) { ok -> onSentenceDone(gen, index, ok) }
            }
        } catch (t: Throwable) {
            DiagLog.exception(app, "pump/speak failed at sentence $submitted", t)
            playing = false
            status = "Speech error: ${t.javaClass.simpleName}: ${t.message}"
            notifyState()
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
                    stopWordTracking()
                    status = "Finished"
                    notifyState()
                    PlaybackService.stop(app)
                    notifyQueueFinished()
                    return@Runnable
                }
                cursor++
                notifyHighlight()
                startWordTracking()
                notifyState()
                pump()
            }
        })
    }

    /** Engine-reported ceiling; UI clamp uses this so 2.0-3.0x is not silently dropped. */
    fun maxSpeed(): Float = engine?.maxSpeed ?: 2.0f

    /** Index of the sentence currently being spoken (for progress persistence). */
    fun currentIndex(): Int = cursor

    /** Text of one queued sentence (bookmarks/stats); empty when out of range. */
    fun sentenceText(index: Int): String = sentences.getOrNull(index)?.text ?: ""

    /**
     * Audio export v1: record what actually plays to a 24 kHz mono WAV.
     * First call starts capture; the second stops, publishes to Downloads (API 29+) and
     * returns a user-facing status string. Settings shows the return value as a Toast.
     * (Full offline chapter rendering without playback is a later iteration.)
     */
    fun exportCurrentChapter(): String {
        val player = app.player
        if (player.isCapturing()) {
            val file = player.stopCapture() ?: return "No recording in progress"
            return publishCapture(file)
        }
        if (!hasContent()) return "Start reading a chapter first"
        val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "exports")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "novelspeak-" + System.currentTimeMillis() + ".wav")
        return if (player.startCapture(file)) {
            "Recording… play the chapter, then tap Export again to save the WAV"
        } else {
            "Could not start audio export"
        }
    }

    private fun publishCapture(file: File): String {
        if (file.length() <= 44L) return "Export was empty — play some audio first"
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                    put(MediaStore.Downloads.MIME_TYPE, "audio/wav")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = app.contentResolver.insert(collection, values)
                if (uri != null) {
                    app.contentResolver.openOutputStream(uri)?.use { out ->
                        file.inputStream().use { it.copyTo(out) }
                    }
                    val done = ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                    app.contentResolver.update(uri, done, null, null)
                    return "Saved " + file.name + " to Downloads"
                }
            } catch (t: Throwable) {
                DiagLog.exception(app, "export publish failed", t)
            }
        }
        return "Saved " + file.absolutePath
    }

    fun setSpeed(v: Float) {
        app.prefs.speed = v
        // Speed is shown in the player bar; keep the status focused on playback state.
        notifyState()
    }

    fun release() {
        stopInternal()
        engine?.release()
        engine = null
        app.player.release()
    }

    private companion object {
        /** Keep roughly this much synthesized audio queued ahead of the speaker. */
        const val TARGET_BUFFER_SECONDS = 12f

        /** Hard cap on queued sentences, so a fast engine cannot buffer a whole chapter. */
        const val HARD_LOOKAHEAD = 8

        const val PUMP_INTERVAL_MS = 500L
    }
}
