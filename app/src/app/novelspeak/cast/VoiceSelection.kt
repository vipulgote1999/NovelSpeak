package app.novelspeak.cast

/**
 * Pure voice-selection rules shared by the engines.
 *
 * The reader queues several sentences ahead, so a voice is a property of the *task*, not of the
 * engine. A task's requested voice wins; otherwise the engine's saved global voice is used.
 */
object VoiceSelection {

    /** Supertonic/Kokoro speaker ids are integers; an absent or malformed request falls back. */
    fun speakerId(requested: String?, fallback: Int): Int =
        requested?.toIntOrNull() ?: fallback

    /** System TTS voice names are strings; blank requests fall back to the saved voice. */
    fun systemVoice(requested: String?, fallback: String?): String? =
        requested?.takeIf { it.isNotBlank() } ?: fallback
}
