package app.novelspeak.cast

/** What a segment is: the narrator's prose or a character's spoken line. */
enum class SegmentType { NARRATION, DIALOGUE }

/** How strongly the parser believes a speaker assignment. */
enum class Confidence { HIGH, MEDIUM, LOW }

/** Gender is only used to pick a plausible voice; it is never shown as a fact. */
enum class Gender { FEMALE, MALE, UNKNOWN }

/**
 * One character in a book's cast.
 *
 * [id] is stable for the life of the book, so changing a voice or adding an alias never changes
 * which character owns previously analysed lines.
 */
data class CastMember(
    val id: String,
    val name: String,
    val aliases: List<String> = emptyList(),
    val gender: Gender = Gender.UNKNOWN,
    val voiceId: String? = null,
    val confidence: Confidence = Confidence.LOW,
    val userLocked: Boolean = false,
    /** Dialogue lines in the chapter(s) analysed so far; drives cast priority. */
    val dialogueLines: Int = 0
) {
    fun matches(phrase: String): Boolean {
        val p = CastIds.normalize(phrase)
        if (p.isEmpty()) return false
        if (CastIds.normalize(name) == p) return true
        return aliases.any { CastIds.normalize(it) == p }
    }
}

/** One source block, already sliced by the reader/extractor. */
data class CastBlock(val text: String, val blockIndex: Int)

/**
 * A contiguous piece of one source block.
 *
 * [text] is the exact source substring (quotes included) so the reader can still highlight the
 * original text. [charStart]/[charEnd] are offsets into that block; `[charStart, charEnd)`.
 */
data class SpeechSegment(
    val text: String,
    val blockIndex: Int,
    val charStart: Int,
    val charEnd: Int,
    val type: SegmentType,
    val speakerId: String,
    val confidence: Confidence
) {
    val isDialogue: Boolean get() = type == SegmentType.DIALOGUE
}

/** Result of analysing one chapter. */
data class CastAnalysis(
    val segments: List<SpeechSegment>,
    val members: List<CastMember>,
    val unresolved: Int
)

/** Persistent cast for one book. */
data class BookCast(
    val bookKey: String,
    val title: String = "",
    val narratorVoiceId: String? = null,
    val members: List<CastMember> = emptyList()
)

/** Stable ids and phrase normalisation shared by the parser and the store. */
object CastIds {
    const val NARRATOR = "NARRATOR"
    const val UNKNOWN = "UNKNOWN"

    fun slug(name: String): String {
        val s = normalize(name)
        if (s.isEmpty()) return UNKNOWN.lowercase()
        return s.replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { UNKNOWN.lowercase() }
    }

    fun normalize(value: String): String =
        value.lowercase()
            .replace('’', '\'')
            .replace(Regex("[\\p{Punct}&&[^' ]]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
