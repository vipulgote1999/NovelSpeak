package app.novelspeak

/**
 * Static metadata for every selectable on-device voice.
 *
 * The engine still owns the actual audio; this catalog only answers "what can I pick, what does it
 * sound like, and what gender/accent should the cast planner assume?".
 *
 * Supertonic 3 is special: its `voice.bin` contains 10 speakers and sherpa-onnx selects one with
 * `GenerationConfig.speakerId`. The order was verified by decoding the 517,168-byte `voice.bin`
 * (header: 10 speakers × 50 × 256 style, 10 × 8 × 16 duration style) and matching every slice
 * against the official Supertonic 3 `voice_styles` JSON vectors with zero error:
 *
 *   sid 0..4 = F1..F5
 *   sid 5..9 = M1..M5
 *
 * The old app label "1 = M1" was wrong; sid 1 is F2.
 */
data class VoiceOption(
    val id: String,
    val label: String,
    val family: String,
    val gender: String,
    val accent: String,
    val tier: String
) {
    val isFemale: Boolean get() = gender == "female"
    val isMale: Boolean get() = gender == "male"
}

object VoiceCatalog {

    /** Supertonic 3's 10 speakers, in the exact order stored in `voice.bin`. */
    val SUPERTONIC: List<VoiceOption> = listOf(
        VoiceOption("0", "F1 (female, US)", "supertonic", "female", "US", "primary"),
        VoiceOption("1", "F2 (female, US)", "supertonic", "female", "US", "primary"),
        VoiceOption("2", "F3 (female, US)", "supertonic", "female", "US", "primary"),
        VoiceOption("3", "F4 (female, US)", "supertonic", "female", "US", "primary"),
        VoiceOption("4", "F5 (female, US)", "supertonic", "female", "US", "primary"),
        VoiceOption("5", "M1 (male, US)", "supertonic", "male", "US", "primary"),
        VoiceOption("6", "M2 (male, US)", "supertonic", "male", "US", "primary"),
        VoiceOption("7", "M3 (male, US)", "supertonic", "male", "US", "primary"),
        VoiceOption("8", "M4 (male, US)", "supertonic", "male", "US", "primary"),
        VoiceOption("9", "M5 (male, US)", "supertonic", "male", "US", "primary")
    )

    /**
     * Kokoro's 11 English speakers, in `voices.bin` order (0..10).
     *
     * Tier is a cast-planning hint, not a hard rule: Heart/Bella/Emma are the best-trained
     * speakers; Adam is the lowest-graded and is kept as a last-resort male voice.
     */
    val KOKORO: List<VoiceOption> = listOf(
        VoiceOption("0", "Heart (US female)", "kokoro", "female", "US", "quality"),
        VoiceOption("1", "Bella (US female)", "kokoro", "female", "US", "quality"),
        VoiceOption("2", "Nicole (US female)", "kokoro", "female", "US", "standard"),
        VoiceOption("3", "Sarah (US female)", "kokoro", "female", "US", "standard"),
        VoiceOption("4", "Sky (US female)", "kokoro", "female", "US", "fallback"),
        VoiceOption("5", "Adam (US male)", "kokoro", "male", "US", "fallback"),
        VoiceOption("6", "Michael (US male)", "kokoro", "male", "US", "standard"),
        VoiceOption("7", "Emma (UK female)", "kokoro", "female", "UK", "quality"),
        VoiceOption("8", "Isabella (UK female)", "kokoro", "female", "UK", "standard"),
        VoiceOption("9", "George (UK male)", "kokoro", "male", "UK", "standard"),
        VoiceOption("10", "Lewis (UK male)", "kokoro", "male", "UK", "fallback")
    )

    fun forFamily(family: String): List<VoiceOption> = when (family) {
        "supertonic" -> SUPERTONIC
        "kokoro" -> KOKORO
        else -> emptyList()
    }

    fun option(family: String, id: String): VoiceOption? =
        forFamily(family).firstOrNull { it.id == id }

    fun label(family: String, id: String): String =
        option(family, id)?.label ?: id

    /** Voices a character may use, excluding the narrator's reserved voice. */
    fun characterVoices(family: String, narratorVoiceId: String?): List<VoiceOption> =
        forFamily(family).filter { it.id != narratorVoiceId }
}
