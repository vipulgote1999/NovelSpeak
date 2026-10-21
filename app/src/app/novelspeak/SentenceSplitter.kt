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

    private val ABBREV = setOf(
        "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "etc", "e.g", "i.e",
        "no", "vol", "ch", "pt", "fig", "approx", "dept", "est", "min", "max", "sec"
    )

    fun split(text: String, maxChars: Int = 220): List<String> {
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

        // second pass: split anything still too long on commas, then hard-wrap
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
                    val comma = s.lastIndexOf(',', end)
                    if (comma > start + 40) end = comma + 1
                    else {
                        val space = s.lastIndexOf(' ', end)
                        if (space > start + 40) end = space
                    }
                }
                val piece = s.substring(start, end).trim()
                if (piece.isNotEmpty()) result.add(piece)
                start = end
                while (start < s.length && s[start] == ' ') start++
            }
        }
        return result
    }

    private fun isFalseBreak(sb: StringBuilder): Boolean {
        val s = sb.toString().trim()
        if (s.isEmpty()) return true
        val last = s.last()
        if (last == ';') return false
        // decimal number: "3." followed by digit handled by caller (next char whitespace check)
        val word = s.trimEnd('.', '!', '?').takeLastWhile { it.isLetter() }.lowercase()
        if (word.isEmpty()) return false
        if (word in ABBREV) return true
        // single-letter initial like "J." / "A."
        if (word.length == 1) return true
        return false
    }
}
