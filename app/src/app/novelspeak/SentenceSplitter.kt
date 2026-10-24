package app.novelspeak

/**
 * Sentence chunker.
 *
 * Mirrors the splitting NekoSpeak uses before handing text to Kokoro/Pocket:
 *  - primary split on sentence enders: `(?<=[.!?])\s+`, newlines, `(?<=[;])\s+`
 *  - long first sentences are re-split on commas, then hard-wrapped
 *  - chunk size keeps generations short so first audio starts fast (streaming-friendly)
 *
 * Abbreviation and decimal guards are added because novel text is full of "Mr.", "3.5", "Vol. 2".
 */
object SentenceSplitter {

    /**
     * Fragments shorter than this are joined with a neighbour before synthesis.
     * Above the isolated-word range where Kokoro/Pocket misbehave (Kokoro "six" ->
     * "ah-six-ah"; Pocket "weird sounds" on short input, sherpa-onnx#3176) and
     * still below a normal clause, so dialogue keeps its natural flow.
     */
    const val MIN_FRAGMENT_CHARS = 24

    private val ABBREV = setOf(
        "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "etc", "e.g", "i.e",
        "vol", "ch", "pt", "fig", "approx", "dept", "est", "min", "max", "sec"
    )

    fun split(text: String, maxChars: Int = 180): List<String> {
        val clean = text.replace('\u00a0', ' ').replace(Regex("[ \\t]+"), " ").trim()
        if (clean.isEmpty()) return emptyList()

        val out = ArrayList<String>()
        val sb = StringBuilder()

        fun flush() {
            val s = sb.toString().trim()
            if (s.isNotEmpty()) out.add(s)
            sb.setLength(0)
        }

        val chars = clean.toCharArray()
        var i = 0
        while (i < chars.size) {
            val c = chars[i]
            sb.append(c)

            val isEnder = c == '.' || c == '!' || c == '?' || c == ';' || c == '\n'
            if (isEnder) {
                // gather trailing closing punctuation/quotes
                var j = i + 1
                while (j < chars.size && (chars[j] == '"' || chars[j] == '\'' || chars[j] == ')' ||
                            chars[j] == '”' || chars[j] == '’' || chars[j] == ']')
                ) {
                    sb.append(chars[j]); j++
                }
                val nextIsBreak = j >= chars.size || chars[j].isWhitespace()
                if (nextIsBreak && !isFalseBreak(sb)) {
                    flush()
                    while (j < chars.size && chars[j].isWhitespace()) j++
                    i = j
                    continue
                }
                i = j
                continue
            }
            i++
        }
        flush()

        // second pass: split only at real boundaries. Prefer terminal punctuation
        // (never inside a decimal or abbreviation), then a comma, then a space.
        // If no boundary is near the cap, extend to the next space so a word is
        // never cut in half.
        val result = ArrayList<String>(out.size)
        for (s in out) {
            if (s.length <= maxChars) {
                result.add(s)
                continue
            }
            var start = 0
            while (start < s.length) {
                var end = minOf(start + maxChars, s.length)
                if (end < s.length) {
                    var cut = -1
                    var k = end - 1
                    while (k > start + 40) {
                        val c = s[k]
                        val terminal = c == '.' || c == '!' || c == '?' || c == ';'
                        val atBoundary = k + 1 == s.length || s[k + 1].isWhitespace()
                        if (terminal && atBoundary &&
                            !isFalseBreak(StringBuilder(s.substring(start, k + 1)))
                        ) {
                            cut = k + 1
                            break
                        }
                        k--
                    }
                    if (cut < 0) {
                        val comma = s.lastIndexOf(',', end - 1)
                        if (comma > start + 40) cut = comma + 1
                    }
                    if (cut < 0) {
                        val space = s.lastIndexOf(' ', end - 1)
                        if (space > start + 40) cut = space
                    }
                    if (cut < 0) {
                        val nextSpace = s.indexOf(' ', end)
                        if (nextSpace > end) cut = nextSpace
                    }
                    if (cut > start) end = cut
                }
                val piece = s.substring(start, end).trim()
                if (piece.isNotEmpty()) result.add(piece)
                start = end
                while (start < s.length && s[start] == ' ') start++
            }
        }
        return result
    }

    /**
     * Groups adjacent fragments that must be synthesized together.
     *
     * Neural models degrade on very short inputs (an isolated `“Gary!”` is the
     * pathological case in novel dialogue) and fragments with no letters/digits
     * carry no speech at all. Groups are contiguous index ranges into [texts];
     * the caller merges them and attributes the result to the first fragment's
     * block so highlight stays correct. Never exceeds [maxChars].
     */
    fun mergeGroups(
        texts: List<String>,
        maxChars: Int,
        minChars: Int = MIN_FRAGMENT_CHARS
    ): List<IntRange> {
        val groups = ArrayList<IntRange>(texts.size)
        val lengths = ArrayList<Int>(texts.size)
        var i = 0
        while (i < texts.size) {
            if (texts[i].none { it.isLetterOrDigit() }) { i++; continue }
            val start = i
            var end = i
            var length = texts[i].length
            while (end + 1 < texts.size) {
                val next = texts[end + 1]
                if (next.none { it.isLetterOrDigit() }) { end++; continue }
                val nextShort = next.length < minChars
                val curShort = length < minChars
                if (!nextShort && !curShort) break
                if (length + 1 + next.length > maxChars) break
                length += 1 + next.length
                end++
            }
            // A short fragment that could not merge forward belongs to the previous
            // group instead of being synthesized alone.
            if (length < minChars && groups.isNotEmpty()) {
                val prev = groups[groups.size - 1]
                if (prev.last + 1 == start && lengths[lengths.size - 1] + 1 + length <= maxChars) {
                    groups[groups.size - 1] = prev.first..end
                    lengths[lengths.size - 1] = lengths[lengths.size - 1] + 1 + length
                    i = end + 1
                    continue
                }
            }
            groups.add(start..end)
            lengths.add(length)
            i = end + 1
        }
        return groups
    }

    private fun isFalseBreak(sb: StringBuilder): Boolean {
        val s = sb.toString().trim()
        if (s.isEmpty()) return true
        val last = s.last()
        if (last == ';') return false
        // decimal number: "3." followed by digit handled by caller (next char whitespace check)
        val raw = s.trimEnd('.', '!', '?').takeLastWhile { it.isLetter() }
        val word = raw.lowercase()
        if (word.isEmpty()) return false
        if (word in ABBREV) return true
        // Single-letter initial like "J." is a false break, but contraction tails
        // ("don't.", "isn't.") end in a lowercase letter and must break.
        if (word.length == 1) return raw[0].isUpperCase()
        return false
    }
}
