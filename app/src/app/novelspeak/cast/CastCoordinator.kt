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
        chapterKey: String,
        title: String,
        narratorVoiceId: String?,
        family: String,
        blocks: List<CastBlock>
    ): Result {
        val existing = store.load(bookKey)?.members ?: emptyList()
        val parsed = DialogueParser.analyze(blocks, existing)
        val fixes = store.lineFixes(bookKey, chapterKey)
        val segments = applyFixes(parsed.segments, fixes)
        val fixedSpeakers = segments.filter { it.confidence == Confidence.HIGH }
            .map { it.speakerId }
            .toSet()
        val counts = segments.filter { it.isDialogue }
            .groupingBy { it.speakerId }
            .eachCount()
        val analysis = parsed.copy(
            segments = segments,
            members = parsed.members.map { member ->
                member.copy(
                    dialogueLines = counts[member.id] ?: 0,
                    confidence = if (member.id in fixedSpeakers) Confidence.HIGH
                    else member.confidence
                )
            },
            unresolved = segments.count { it.isDialogue && it.speakerId == CastIds.UNKNOWN }
        )
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

    /**
     * Apply user corrections to whole dialogue segments. A fix stores the offset of any TTS chunk
     * of the segment, so it is matched by containment rather than exact equality.
     */
    private fun applyFixes(
        segments: List<SpeechSegment>,
        fixes: List<LineFix>
    ): List<SpeechSegment> {
        if (fixes.isEmpty()) return segments
        return segments.map { segment ->
            if (!segment.isDialogue) {
                segment
            } else {
                val fix = fixes.lastOrNull {
                    it.blockIndex == segment.blockIndex &&
                        it.charStart >= segment.charStart &&
                        it.charStart < segment.charEnd
                }
                if (fix == null) segment
                else segment.copy(speakerId = fix.speakerId, confidence = Confidence.HIGH)
            }
        }
    }
}
