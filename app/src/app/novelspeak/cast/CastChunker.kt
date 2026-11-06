package app.novelspeak.cast

import app.novelspeak.SentenceSplitter

/**
 * Turns one cast segment into TTS chunks without separating a character's short line from the
 * sentence that belongs to it.
 *
 * The parser deliberately works on whole paragraphs so `"Tomas. I told you..."` stays one turn.
 * The TTS queue still needs sentence-sized units, but splitting naively reintroduced a pause after
 * `"Tomas."`. This applies [SentenceSplitter.mergeGroups], so fragments below
 * [SentenceSplitter.MIN_FRAGMENT_CHARS] are joined with their neighbour inside the same segment.
 *
 * Offsets are relative to the segment text; the caller adds the dialogue's block offset.
 */
object CastChunker {

    data class Chunk(val text: String, val start: Int, val end: Int)

    fun chunks(text: String, maxChars: Int = 180): List<Chunk> {
        if (text.isBlank()) return emptyList()
        val pieces = SentenceSplitter.split(text, maxChars)
        if (pieces.isEmpty()) return emptyList()
        val groups = SentenceSplitter.mergeGroups(pieces, maxChars)
        val out = ArrayList<Chunk>(groups.size)
        var cursor = 0
        for (group in groups) {
            val first = pieces[group.first]
            val last = pieces[group.last]
            val start = text.indexOf(first, cursor).let { if (it >= 0) it else cursor }
            val lastAt = text.indexOf(last, start)
            val end = if (lastAt >= 0) lastAt + last.length else start + first.length
            val merged = if (group.first == group.last) {
                first
            } else {
                pieces.subList(group.first, group.last + 1).joinToString(" ")
            }
            out.add(Chunk(merged, start, end))
            cursor = end
        }
        return out
    }
}
