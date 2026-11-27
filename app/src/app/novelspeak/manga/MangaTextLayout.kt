package app.novelspeak.manga

import app.novelspeak.SentenceSplitter

/**
 * One OCR line in image coordinates. Kept Android-free so the ordering and chunking rules can
 * run in the host test harness without an emulator.
 */
data class MangaLine(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

/**
 * Reading-order and speech-chunk policy for manga pages.
 *
 * ML Kit returns text blocks in detection order, which for a comic page is often not the order a
 * person reads: panels are laid out in two dimensions and the blocks are emitted by confidence or
 * detector sweep. This object sorts blocks into rows (top-to-bottom) and, for right-to-left manga,
 * right-to-left inside each row. It deliberately works on block boxes rather than line boxes: a
 * speech bubble is normally one ML Kit block, so bubbles are the unit that should be spoken.
 *
 * [chunks] then validates each bubble for TTS: line-break hyphenation is repaired, whitespace is
 * collapsed, non-speech fragments are dropped, and long bubbles are split at sentence/clause
 * boundaries into chunks no longer than [maxChars]. A chunk is always at least one letter long, so
 * page numbers and SFX glyphs never reach the engine as a "sentence".
 */
object MangaTextLayout {

    /** Minimum characters for a chunk worth synthesising on its own. */
    const val MIN_SPEAKABLE_CHARS = 24

    /**
     * Returns indices into [lines] in reading order. Boxes with no area are kept at the end in
     * their original order because they cannot be placed on the page.
     */
    fun readingOrder(lines: List<MangaLine>, rightToLeft: Boolean): List<Int> {
        if (lines.size < 2) return lines.indices.toList()
        val placed = lines.indices.filter { !lines[it].isEmpty }
        val unplaced = lines.indices.filter { lines[it].isEmpty }
        if (placed.isEmpty()) return lines.indices.toList()

        val sorted = placed.sortedWith(
            compareBy<Int>({ lines[it].top }, { lines[it].left })
        )
        val rows = ArrayList<MutableList<Int>>()
        for (index in sorted) {
            val line = lines[index]
            val row = rows.lastOrNull()
            if (row != null && sameRow(lines[row[0]], line)) {
                row.add(index)
            } else {
                rows.add(mutableListOf(index))
            }
        }
        val order = ArrayList<Int>(lines.size)
        for (row in rows) {
            // Split a row into side-by-side columns. Lines that overlap horizontally belong to
            // one column (a stylised bubble lettered line by line) and must stay in top-to-bottom
            // order; only separate columns are ordered left-to-right (or right-to-left).
            val byLeft = row.sortedBy { lines[it].left }
            val columns = ArrayList<MutableList<Int>>()
            for (index in byLeft) {
                val column = columns.lastOrNull()
                if (column != null && horizontalOverlap(lines[column.last()], lines[index])) {
                    column.add(index)
                } else {
                    columns.add(mutableListOf(index))
                }
            }
            if (rightToLeft) columns.reverse()
            for (column in columns) {
                column.sortBy { lines[it].top }
                order.addAll(column)
            }
        }
        order.addAll(unplaced)
        return order
    }

    /**
     * Splits one OCR bubble into speakable chunks. Returns an empty list when the text has no
     * speech content (only punctuation, a page number, a URL, or a single letter).
     *
     * The splitter is the same one used for novels, so abbreviations and decimals survive; short
     * fragments are merged back together up to [maxChars] so a two-word bubble is not sent to the
     * neural engine as an isolated fragment.
     */
    fun chunks(raw: String, maxChars: Int = 180): List<String> {
        val text = normalize(raw)
        if (!isSpeakable(text)) return emptyList()
        val cap = maxChars.coerceIn(60, 400)
        val pieces = SentenceSplitter.split(text, cap)
            .map { it.trim() }
            .filter { isSpeakable(it) }
        if (pieces.isEmpty()) return emptyList()
        if (pieces.size == 1) return pieces
        val groups = SentenceSplitter.mergeGroups(pieces, cap, MIN_SPEAKABLE_CHARS)
        val out = ArrayList<String>(groups.size)
        for (group in groups) {
            val joined = pieces.subList(group.first, group.last + 1).joinToString(" ").trim()
            if (isSpeakable(joined)) out.add(joined)
        }
        return out
    }

    /**
     * Repairs the text ML Kit produces when it joins the lines of a bubble:
     *  - a hyphen at a visual line break ("exam- ple") is not a real hyphen
     *  - non-breaking spaces, zero-width characters and repeated whitespace are normalised
     *  - stray control characters from OCR are removed
     *
     * Quotes and other styled punctuation are left for [app.novelspeak.TtsTextCleaner], which
     * already owns the "what the neural voice should actually hear" policy.
     */
    fun normalize(raw: String): String {
        var text = raw
            .replace('\u00a0', ' ')
            .replace('\u00ad', '-')
        // A hyphen followed by whitespace is a visual line break in lettered manga, whether
        // the next word starts lower- or upper-case ("SOME- TIMES" -> "SOMETIMES").
        text = text.replace(Regex("(?<=[\\p{L}])-\\s+(?=\\p{L})"), "")
        text = text.replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), " ")
        text = text.replace(Regex("\\s+"), " ").trim()
        return text
    }

    /** True when the fragment contains enough language to be worth speaking. */
    fun isSpeakable(text: String): Boolean {
        if (text.isBlank()) return false
        var letters = 0
        var alnum = 0
        for (ch in text) {
            if (ch.isLetter()) letters++
            if (ch.isLetterOrDigit()) alnum++
        }
        if (letters < 2) return false
        // A bare URL or a page number is not dialogue. Keep real one-word shouts ("NO!!").
        if (text.startsWith("http://") || text.startsWith("https://")) return false
        if (alnum == 0) return false
        return true
    }

    private fun horizontalOverlap(a: MangaLine, b: MangaLine): Boolean {
        val overlap = minOf(a.right, b.right) - maxOf(a.left, b.left)
        if (overlap <= 0f) return false
        val shorter = minOf(a.width, b.width).coerceAtLeast(1f)
        return overlap / shorter >= 0.30f
    }

    private fun sameRow(a: MangaLine, b: MangaLine): Boolean {
        val overlap = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        if (overlap <= 0f) return false
        val shorter = minOf(a.height, b.height).coerceAtLeast(1f)
        if (overlap / shorter >= 0.55f) return true
        // Short blocks (a line of dialogue) can sit inside a taller block's vertical span. Treat
        // them as the same row when their centres are within two line heights.
        val centreGap = kotlin.math.abs(a.centerY - b.centerY)
        return centreGap <= maxOf(a.height, b.height) * 0.55f
    }

    private val MangaLine.isEmpty: Boolean
        get() = right <= left + 1f || bottom <= top + 1f

    private val MangaLine.width: Float
        get() = right - left

    private val MangaLine.height: Float
        get() = bottom - top

    private val MangaLine.centerX: Float
        get() = (left + right) / 2f

    private val MangaLine.centerY: Float
        get() = (top + bottom) / 2f
}
