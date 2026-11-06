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
import app.novelspeak.cast.CastStore
import app.novelspeak.cast.PrefsCastStorage
import java.io.File

class NovelSpeakApp : Application() {

    lateinit var prefs: Prefs
        private set
    lateinit var player: PcmPlayer
        private set
    lateinit var reader: Reader
        private set
    lateinit var castStore: CastStore
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
        castStore = CastStore(PrefsCastStorage(this))
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

    /**
     * One speakable unit. [voiceId]/[speakerId] are optional so existing extraction and imported
     * books keep working unchanged; a cast fills them in later.
     *
     * [charStart]/[charEnd] are offsets inside the original block for dialogue highlighting;
     * `-1` means "highlight the whole block".
     */
    data class Block(
        val text: String,
        val blockIndex: Int,
        val voiceId: String? = null,
        val speakerId: String? = null,
        val charStart: Int = -1,
        val charEnd: Int = -1,
        val speakerName: String = "",
        val trimSilence: Boolean = false
    )

    interface Listener {
        fun onReaderState(state: State)
        fun onHighlight(blockIndex: Int)

        /** The last queued sentence finished playing; auto mode uses this to advance. */
        fun onQueueFinished() {}

        /** Approximate word-level progress inside the spoken sentence (char offsets). */
        fun onWordHighlight(blockIndex: Int, charStart: Int, charEnd: Int) {}

        /**
         * The current segment's exact source range. Used by the cast view to highlight the
         * dialogue span (and to show who is speaking) without disturbing the sentence highlight.
         */
        fun onSegmentHighlight(blockIndex: Int, charStart: Int, charEnd: Int, speaker: String) {}
    }

    data class State(
        val playing: Boolean,
        val title: String,
        val cursor: Int,
        val total: Int,
        val engine: String,
        val voice: String,
        val status: String,
        val speaker: String = "",
        val segmentVoiceId: String = "",
        val speakerId: String = ""
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

    // Exactly one engine prepare may run at a time. Prewarm (content load) and Play both use
    // this path; without it an immediate Play during prewarm starts a second model load.
    private var preparing = false
    private var prepareWanted = ""
    private var prepareToken = 0
    private val prepareWaiters = ArrayList<(TtsEngine?) -> Unit>()

    // Opportunistic preroll: while not playing, synthesize the first sentences into the held
    // PcmPlayer queue. `rendered` records onRendered callbacks; `completed` records onDone
    // callbacks so a skipped sentence ahead of the cursor cannot stall the queue.
    private var prerolling = false
    private var playRequested = false

    // Audio-focus interruption state. `pausedByFocus` is an automatic pause that should resume
    // when Android returns focus; `waitingForFocus` is a Play request deferred because focus is
    // held by a call/another app. A user pause clears both so a later focus gain cannot start
    // playback the user did not ask for.
    private var pausedByFocus = false
    private var waitingForFocus = false
    private val rendered = HashMap<Int, Boolean>()
    private val completed = HashSet<Int>()

    // Word-level read-along: the engine boundary APIs are chunk-based, so progress inside a
    // sentence is estimated from elapsed time and the current speed. Sentence highlight is
    // authoritative; word highlight is an enhancement and can be turned off in Settings.
    private var wordTick: Runnable? = null
    private var sentenceStartAt = 0L
    private var sentenceChars = 0

    fun addListener(l: Listener) { if (!listeners.contains(l)) listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    /** The exact cue the reader is on, including its cast/source offsets. */
    fun currentBlock(): Block? = sentences.getOrNull(cursor)

    fun state(): State {
        val current = sentences.getOrNull(cursor)
        return State(
            playing, title, cursor, sentences.size, engineName,
            engine?.currentVoiceLabel() ?: "", status,
            current?.speakerName?.ifEmpty { current.speakerId } ?: "",
            current?.voiceId ?: "",
            current?.speakerId ?: ""
        )
    }

    private fun notifyState() {
        val s = state()
        main.post { for (l in ArrayList(listeners)) l.onReaderState(s) }
    }

    private fun notifyHighlight() {
        val b = sentences.getOrNull(cursor) ?: return
        main.post {
            for (l in ArrayList(listeners)) {
                l.onHighlight(b.blockIndex)
                l.onSegmentHighlight(b.blockIndex, b.charStart, b.charEnd, b.speakerName)
            }
        }
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
        if (sentences.isNotEmpty()) prepareForPlayback(forPlay = false)
    }

    fun appendContent(blocks: List<Block>, newTitle: String = "") {
        if (blocks.isEmpty()) return
        if (newTitle.isNotEmpty()) title = newTitle
        sentences = sentences + blocks
        status = "Chapter appended · ${sentences.size} sentences"
        notifyState()
        if (playing) {
            pump()
        } else {
            prepareForPlayback(forPlay = false)
        }
    }

    fun hasContent() = sentences.isNotEmpty()

    private fun ensureEngine(onReady: (TtsEngine?) -> Unit) {
        val wanted = app.prefs.engine
        val current = engine
        if (current != null && engineName == wanted && current.isReady) {
            onReady(current)
            return
        }
        prepareWaiters.add(onReady)
        if (preparing) {
            // The latest preference wins. The in-flight result is discarded when it differs.
            prepareWanted = wanted
            return
        }
        startPrepare(wanted)
    }

    private fun startPrepare(wanted: String) {
        engine?.release()
        engine = null
        engineName = wanted
        preparing = true
        prepareWanted = wanted
        val token = ++prepareToken
        status = "Preparing $wanted engine…"
        DiagLog.line(app, "engine requested=$wanted token=$token")
        notifyState()
        val e: TtsEngine = if (wanted == Prefs.ENGINE_SHERPA) {
            SherpaEngine(app, app.prefs, app.player)
        } else {
            SystemTtsEngine(app, app.prefs)
        }
        e.prepare { ok, message ->
            // The backends already post to the main thread; posting here keeps the state
            // machine single-threaded even if a future backend calls back inline.
            main.post { finishPrepare(token, wanted, e, ok, message) }
        }
    }

    private fun finishPrepare(
        token: Int,
        wanted: String,
        e: TtsEngine,
        ok: Boolean,
        message: String?
    ) {
        if (token != prepareToken || wanted != prepareWanted) {
            // Engine/model changed while the model was loading. Never let two models stay live,
            // and start the latest request only after this one has been released.
            try { e.release() } catch (ignored: Throwable) {}
            val pending = prepareWanted.isNotEmpty() || prepareWaiters.isNotEmpty()
            if (preparing && pending) {
                val next = if (prepareWanted.isNotEmpty()) prepareWanted else app.prefs.engine
                preparing = false
                startPrepare(next)
            } else {
                preparing = false
            }
            return
        }
        preparing = false
        if (ok) {
            engine = e
            engineName = wanted
            status = "Ready · ${sentences.size} sentences"
        } else {
            status = "Engine error: ${message ?: "unknown"}"
            try { e.release() } catch (ignored: Throwable) {}
            engine = null
            engineName = ""
        }
        val waiters = ArrayList(prepareWaiters)
        prepareWaiters.clear()
        notifyState()
        for (w in waiters) w(if (ok) e else null)
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
        pausedByFocus = false
        waitingForFocus = false
        if (PlaybackService.isFocusInterrupted()) {
            // A call/another exclusive app owns audio. Keep the request and let the focus
            // gain start it; starting now would play into a muted route and silently skip text.
            waitingForFocus = true
            playRequested = true
            status = "Waiting for audio focus…"
            notifyState()
            return
        }
        DiagLog.line(app, "play clicked engine=${app.prefs.engine} cursor=$cursor total=${sentences.size}")
        playRequested = true
        maybeStartPlayback()
        if (!playing) {
            status = if (rendered[cursor] == true) "Starting…" else "Preparing audio…"
            notifyState()
            prepareForPlayback(forPlay = true)
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
        val wasWaiting = waitingForFocus
        pausedByFocus = false
        waitingForFocus = false
        // Pause must also cancel a Play that is still preparing/prerolling. Previously it
        // returned unless audio was already playing, so a pause during "Preparing audio…"
        // was ignored and playback started anyway.
        if (!playing && !playRequested && !prerolling && !wasWaiting) return
        pauseInternal("Paused", allowPreroll = true)
    }

    /** Pause caused by Android taking audio away; [onFocusGained] may resume it. */
    fun pauseForFocus(): Boolean {
        if (!playing && !playRequested) return false
        pausedByFocus = true
        waitingForFocus = false
        pauseInternal("Paused", allowPreroll = false)
        return true
    }

    /** Defer a Play request until audio focus is granted (call in progress, exclusive app). */
    fun waitForFocus() {
        if (sentences.isEmpty()) return
        playing = false
        pausedByFocus = false
        waitingForFocus = true
        cancelPreroll()
        // Keep the intent even when the request came from a media button while the Reader was
        // idle; otherwise a delayed focus grant would have nothing to resume.
        playRequested = true
        status = "Waiting for audio focus…"
        notifyState()
    }

    /** Called by PlaybackService when AUDIOFOCUS_GAIN arrives after an interruption. */
    fun onFocusGained() {
        if (!pausedByFocus && !waitingForFocus) return
        pausedByFocus = false
        waitingForFocus = false
        play()
    }

    /** True while a user Play must not start because focus is interrupted or deferred. */
    fun isFocusBlocked(): Boolean = pausedByFocus || waitingForFocus

    /** Cancel a deferred Play (focus request failed or the service is going away). */
    fun cancelFocusWait(message: String? = null) {
        val wasBlocked = pausedByFocus || waitingForFocus
        pausedByFocus = false
        waitingForFocus = false
        if (!wasBlocked && !playing && !playRequested && !prerolling) return
        playing = false
        playRequested = false
        cancelPreroll()
        if (message != null) status = message
        notifyState()
    }

    fun resume() = play()

    fun stop() {
        pausedByFocus = false
        waitingForFocus = false
        stopInternal()
        status = "Stopped"
        notifyState()
    }

    private fun stopInternal() {
        pausedByFocus = false
        waitingForFocus = false
        playing = false
        playRequested = false
        cancelPreroll()
        PlaybackService.stop(app)
    }

    private fun pauseInternal(statusText: String, allowPreroll: Boolean) {
        playing = false
        playRequested = false
        cancelPreroll()
        status = statusText
        notifyState()
        // Make resume cheap for a user pause. Do not synthesize while focus is interrupted:
        // the call/navigation prompt owns the audio route and the CPU is better left alone.
        if (allowPreroll && !pausedByFocus && !waitingForFocus) {
            prepareForPlayback(forPlay = false)
        }
    }

    fun toggle() { if (playing) pause() else play() }

    /**
     * Drops the prepared engine so a changed engine/model applies on the next prepare. Voice
     * changes use [setVoice] instead and keep the model loaded.
     */
    fun invalidateEngine() {
        stopInternal()
        pausedByFocus = false
        waitingForFocus = false
        if (preparing) {
            // The in-flight prepare is allowed to finish and release itself; its result must
            // not be installed. A new request queued below starts after that release.
            prepareWanted = ""
            prepareToken++
            prepareWaiters.clear()
        }
        engine?.release()
        engine = null
        engineName = ""
        status = if (sentences.isEmpty()) "Idle" else "Ready · " + sentences.size + " sentences"
        notifyState()
        if (sentences.isNotEmpty()) prepareForPlayback(forPlay = false)
    }

    /**
     * Applies a voice without reloading the model. The engine reads the voice per generation,
     * so only the queued/pre-rendered audio has to be discarded.
     */
    fun setVoice(id: String) {
        val wasPlaying = playing
        playing = false
        pausedByFocus = false
        waitingForFocus = false
        cancelPreroll()
        if (wasPlaying) PlaybackService.stop(app)
        engine?.setVoice(id)
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
        playing = false
        pausedByFocus = false
        waitingForFocus = false
        cancelPreroll()
        cursor = index.coerceIn(0, sentences.size - 1)
        notifyHighlight()
        notifyState()
        if (wasPlaying) playRequested = true
        prepareForPlayback(forPlay = wasPlaying)
    }

    private fun pump() {
        if (!playing) return
        val e = engine ?: return
        try {
            val minAhead = app.prefs.prefetch.coerceIn(0, 5)
            // 1. Always keep the user's sentence lookahead.
            while (submitted < cursor + minAhead && submitted < sentences.size - 1) {
                queueSentence(submitted + 1)
            }
            // 2. Then keep synthesizing ahead while the audio buffer is below the target,
            //    bounded by a hard cap so memory stays flat. pump() is re-run every 500 ms
            //    while playing, so the buffer refills without waiting for a sentence drain.
            while (submitted < cursor + HARD_LOOKAHEAD &&
                submitted < sentences.size - 1 &&
                e.bufferedSeconds() < TARGET_BUFFER_SECONDS
            ) {
                queueSentence(submitted + 1)
            }
            if (submitted < cursor) queueSentence(cursor)
        } catch (t: Throwable) {
            DiagLog.exception(app, "pump/speak failed at sentence $submitted", t)
            failPlayback("Speech error: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * Prewarm the engine and, for neural backends, render an opportunistic lead while playback
     * is held. Called on content load, seek, voice change, and Play.
     */
    private fun prepareForPlayback(forPlay: Boolean) {
        if (sentences.isEmpty() || playing) return
        if (forPlay && (pausedByFocus || waitingForFocus || PlaybackService.isFocusInterrupted())) {
            waitForFocus()
            return
        }
        if (!forPlay && app.prefs.engine == Prefs.ENGINE_SHERPA &&
            !ModelManager.isInstalled(app, app.prefs.modelId)
        ) return
        if (forPlay) playRequested = true
        val gen = generation
        val expectedCursor = cursor
        DiagLog.line(app, "prepare requested engine=${app.prefs.engine} cursor=$cursor play=$forPlay")
        ensureEngine { e ->
            if (e == null) {
                if (forPlay) {
                    playRequested = false
                    status = "Engine error: could not prepare"
                    notifyState()
                }
                return@ensureEngine
            }
            if (gen != generation || playing || cursor != expectedCursor) return@ensureEngine
            if (!e.supportsPreRender) {
                // The system backend is already fast and its legacy path plays outside
                // PcmPlayer. Keep the engine warm and use the normal immediate pump.
                if (forPlay) startPlayback()
                return@ensureEngine
            }
            if (!prerolling) beginPreroll()
            if (forPlay) {
                status = if (rendered[cursor] == true) "Starting…" else "Preparing audio…"
                notifyState()
            }
            maybeStartPlayback()
        }
    }

    private fun beginPreroll() {
        if (playing || sentences.isEmpty()) return
        prerolling = true
        submitted = cursor - 1
        rendered.clear()
        completed.clear()
        app.player.setPlaybackHold(true)
        app.player.reset()
        DiagLog.line(
            app,
            "preroll start cursor=$cursor total=${sentences.size} target=${PREROLL_SECONDS}s " +
                "max=$PREROLL_MAX_SENTENCES"
        )
        pumpPreroll()
        main.removeCallbacks(prerollTick)
        main.postDelayed(prerollTick, PREROLL_TICK_MS)
    }

    private fun pumpPreroll() {
        if (!prerolling || playing) return
        val e = engine ?: return
        val lastRendered = submitted < cursor || rendered.containsKey(submitted)
        if (PrerollPolicy.shouldQueueNext(
                submitted = submitted,
                cursor = cursor,
                total = sentences.size,
                bufferedSeconds = e.bufferedSeconds(),
                lastRendered = lastRendered,
                maxSentences = PREROLL_MAX_SENTENCES,
                targetSeconds = PREROLL_SECONDS
            )
        ) {
            queueSentence(if (submitted < cursor) cursor else submitted + 1)
        }
    }

    private fun prerollHasMore(): Boolean {
        if (!prerolling || playing) return false
        val e = engine ?: return false
        return PrerollPolicy.shouldQueueNext(
            submitted = submitted,
            cursor = cursor,
            total = sentences.size,
            bufferedSeconds = e.bufferedSeconds(),
            lastRendered = submitted < cursor || rendered.containsKey(submitted),
            maxSentences = PREROLL_MAX_SENTENCES,
            targetSeconds = PREROLL_SECONDS
        )
    }

    private fun queueSentence(index: Int) {
        if (index < 0 || index >= sentences.size) return
        val e = engine ?: return
        submitted = index
        val gen = generation
        e.speak(
            sentences[index].text,
            onDone = { ok -> onSentenceDone(gen, index, ok) },
            onRendered = { hasAudio -> onClipRendered(gen, index, hasAudio) },
            voiceId = sentences[index].voiceId,
            trimSilence = sentences[index].trimSilence
        )
    }

    private fun onClipRendered(gen: Int, index: Int, hasAudio: Boolean) {
        main.post {
            if (gen != generation) return@post
            rendered[index] = hasAudio
            DiagLog.line(
                app,
                "clip rendered index=$index audio=$hasAudio buffered=" +
                    "%.2f".format(engine?.bufferedSeconds() ?: 0f)
            )
            if (!playing) {
                if (prerolling) pumpPreroll()
                maybeStartPlayback()
            }
        }
    }

    private fun maybeStartPlayback() {
        if (pausedByFocus || waitingForFocus || PlaybackService.isFocusInterrupted()) return
        if (PrerollPolicy.canStart(
                playRequested = playRequested,
                playing = playing,
                cursorRendered = rendered[cursor] == true
            )
        ) {
            startPlayback()
        }
    }

    private fun startPlayback() {
        if (playing || sentences.isEmpty()) return
        if (pausedByFocus || waitingForFocus || PlaybackService.isFocusInterrupted()) {
            waitForFocus()
            return
        }
        if (cursor >= sentences.size) {
            finishQueue()
            return
        }
        playing = true
        playRequested = false
        prerolling = false
        main.removeCallbacks(prerollTick)
        app.player.setPlaybackHold(false)
        app.player.startIfBuffered()
        status = "Playing"
        notifyState()
        schedulePump()
        startWordTracking()
        PlaybackService.start(app)
        DiagLog.line(app, "play started cursor=$cursor/${sentences.size} buffered=${"%.2f".format(engine?.bufferedSeconds() ?: 0f)}s")
        pump()
    }

    private fun cancelPreroll() {
        generation++
        playRequested = false
        prerolling = false
        main.removeCallbacks(prerollTick)
        main.removeCallbacks(pumpTick)
        stopWordTracking()
        app.player.setPlaybackHold(false)
        try { engine?.stop() } catch (ignored: Throwable) {}
        try { app.player.stop() } catch (ignored: Throwable) {}
        rendered.clear()
        completed.clear()
        submitted = cursor - 1
    }

    private fun failPlayback(message: String) {
        playing = false
        pausedByFocus = false
        waitingForFocus = false
        cancelPreroll()
        status = message
        notifyState()
        PlaybackService.stop(app)
    }

    private fun finishQueue() {
        playing = false
        playRequested = false
        prerolling = false
        pausedByFocus = false
        waitingForFocus = false
        main.removeCallbacks(prerollTick)
        main.removeCallbacks(pumpTick)
        stopWordTracking()
        app.player.setPlaybackHold(false)
        rendered.clear()
        completed.clear()
        status = "Finished"
        notifyState()
        PlaybackService.stop(app)
        notifyQueueFinished()
    }

    private val prerollTick = object : Runnable {
        override fun run() {
            if (playing) return
            if (prerolling) pumpPreroll()
            maybeStartPlayback()
            if (!playing && (playRequested || prerollHasMore())) {
                main.postDelayed(this, PREROLL_TICK_MS)
            }
        }
    }

    private fun onSentenceDone(gen: Int, index: Int, ok: Boolean) {
        main.post(Runnable {
            if (gen != generation) return@Runnable
            if (!ok) {
                failPlayback("Playback stopped (engine error)")
                return@Runnable
            }
            if (index >= cursor) completed.add(index)
            var advanced = false
            while (completed.remove(cursor)) {
                advanced = true
                rendered.remove(cursor)
                if (cursor >= sentences.size - 1) {
                    finishQueue()
                    return@Runnable
                }
                cursor++
                notifyHighlight()
                if (playing) startWordTracking()
                notifyState()
            }
            if (advanced && !playing) maybeStartPlayback()
            if (playing) {
                pump()
            } else if (prerolling) {
                pumpPreroll()
            }
        })
    }

    /** Engine-reported ceiling; UI clamp uses this so 2.0-3.0x is not silently dropped. */
    fun maxSpeed(): Float = engine?.maxSpeed ?: when (app.prefs.engine) {
        // Both shipped backends support 3.0x. Before the engine is prepared (for example when
        // Settings opens on a cold start) the UI must not fall back to the old 2.0x ceiling,
        // because the speed slider writes its displayed value back to prefs.
        Prefs.ENGINE_SYSTEM, Prefs.ENGINE_SHERPA -> 3.0f
        else -> 2.0f
    }

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
        // Queued/prerendered audio was generated at the old rate. Outside playback it is cheap
        // to discard and rebuild; during playback the current lead finishes and the next
        // sentences use the new rate.
        if (!playing && sentences.isNotEmpty() && !isFocusBlocked()) {
            cancelPreroll()
            prepareForPlayback(forPlay = false)
        }
        // Speed is shown in the player bar; keep the status focused on playback state.
        notifyState()
    }

    fun release() {
        stopInternal()
        prepareWaiters.clear()
        preparing = false
        prepareWanted = ""
        prepareToken++
        engine?.release()
        engine = null
        app.player.release()
    }

    private companion object {
        /** Keep roughly this much synthesized audio queued ahead of the speaker. */
        const val TARGET_BUFFER_SECONDS = 12f

        /** Opportunistic preroll lead; bounded so a long sentence cannot render a chapter. */
        const val PREROLL_SECONDS = 8f

        /** Maximum sentences rendered while playback is held. */
        const val PREROLL_MAX_SENTENCES = 4

        const val PREROLL_TICK_MS = 100L

        /** Hard cap on queued sentences, so a fast engine cannot buffer a whole chapter. */
        const val HARD_LOOKAHEAD = 8

        const val PUMP_INTERVAL_MS = 500L
    }
}
