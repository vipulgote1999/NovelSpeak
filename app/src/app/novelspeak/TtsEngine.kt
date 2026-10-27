package app.novelspeak

/** Common surface for the two backends selectable in settings. */
interface TtsEngine {

    val id: String

    /** True once the backend can accept [speak] calls. */
    val isReady: Boolean

    /** Prepare (load model / init system engine). Callback runs on the main thread. */
    fun prepare(onResult: (ok: Boolean, message: String?) -> Unit)

    /**
     * Speak one sentence. Implementations must call [onDone] exactly once per call when the audio
     * for that sentence has finished playing, or with `false` on error. Calls may be issued
     * back-to-back; backends pipeline internally (system TTS queues, sherpa streams).
     */
    fun speak(text: String, onDone: (ok: Boolean) -> Unit)

    /** Stop everything immediately and drop queued work. */
    fun stop()

    fun release()

    /** Voice choices: id -> human label. Empty for backends with no selectable voices. */
    fun voices(): List<Pair<String, String>>

    fun setVoice(id: String)

    /** Human label of the voice this engine will speak with (for the player card). */
    fun currentVoiceLabel(): String = ""

    /**
     * Seconds of audio already synthesized but not yet played. Reader uses it to keep
     * generation ahead of playback instead of only refilling when a sentence drains.
     * 0 means the engine cannot report a buffer (treated as "keep the sentence lookahead").
     */
    fun bufferedSeconds(): Float = 0f

    /**
     * Highest speed multiplier this backend can honour.
     *
     * The UI must clamp its speed control to the *active* engine's value instead of using a
     * single global maximum, otherwise values above it are silently rounded down (the audit
     * finding behind P0.6). Backends clamp their own generation/synthesis rate to
     * `0.5f..maxSpeed` as well, so a stale pref can never produce out-of-contract output.
     *
     * Default 2.0 keeps the original two-backend contract for any engine that does not
     * override it.
     */
    val maxSpeed: Float get() = 2.0f
}
