package app.novelspeak.cast

import app.novelspeak.VoiceCatalog

/**
 * One-call bridge between the parser/store/planner and the reader.
 *
 * The UI converts its blocks to [CastBlock], calls [analyze], then maps the returned segments back
 * into reader blocks. Keeping this class free of Android types lets the host tests exercise the
 * full cast pipeline without a device.
 */
class CastCoordinator(private val store: CastStore) {

    data class Result(
        val analysis: CastAnalysis,
        val cast: BookCast,
        val voices: Map<String, String>,
        val names: Map<String, String>
    ) {
        fun voiceFor(speakerId: String): String? = voices[speakerId]
        fun nameFor(speakerId: String): String =
            if (speakerId == CastIds.NARRATOR) "Narrator"
            else if (speakerId == CastIds.UNKNOWN) "Unknown"
            else names[speakerId] ?: speakerId
    }

    /**
     * Analyze [blocks], merge the result into the book's cast, and assign voices.
     *
     * [family] is a [ModelManager] family string ("supertonic", "kokoro", ...). Unknown families
     * produce no character voices, so the caller keeps the narrator-only path.
     */
    fun analyze(
        bookKey: String,
        title: String,
        narratorVoiceId: String?,
        family: String,
        blocks: List<CastBlock>
    ): Result {
        val existing = store.load(bookKey)?.members ?: emptyList()
        val analysis = DialogueParser.analyze(blocks, existing)
        val merged = store.merge(bookKey, title, analysis, narratorVoiceId)
        val plan = VoiceCastPlanner.plan(merged, VoiceCatalog.forFamily(family))
        val voices = plan.associate { it.memberId to it.voiceId }
        // Persist the first assignment so the cast sheet and the next chapter agree. A user lock
        // or an existing voice is never overwritten.
        val withVoices = merged.copy(
            members = merged.members.map { member ->
                val chosen = voices[member.id]
                if (chosen != null && member.voiceId == null) member.copy(voiceId = chosen)
                else member
            }
        )
        if (withVoices != merged) store.save(withVoices)
        val names = withVoices.members.associate { it.id to it.name }
        return Result(analysis, withVoices, voices, names)
    }
}
