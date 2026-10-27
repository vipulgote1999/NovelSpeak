package app.novelspeak

/**
 * Site-agnostic fallback for chapter navigation when a page has no usable next link.
 *
 * Many chapter sites number their URLs (…/novel/book/143). If the DOM has no "next" anchor
 * (icon-only link, script-driven nav, verification interstitial), incrementing that trailing
 * path number is the only reliable next-chapter guess left. The hint check keeps this from
 * firing on non-chapter URLs that merely end in a number.
 */
object ChapterUrls {

    private val HINT = Regex("(?i)(chapter|novel|read|book|story|episode|part|serial)")
    private val TRAILING = Regex("^(.*?)(\\d+)(/?)$")

    /** Returns the same URL with the trailing path number incremented, or "" if not safe. */
    fun nextNumbered(url: String): String {
        if (url.isBlank()) return ""
        val hash = url.indexOf('#')
        val query = url.indexOf('?')
        val cut = when {
            query < 0 -> if (hash < 0) url.length else hash
            hash < 0 -> query
            else -> minOf(query, hash)
        }
        val base = url.substring(0, cut)
        val suffix = url.substring(cut)
        val match = TRAILING.find(base) ?: return ""
        val stem = match.groupValues[1]
        if (!HINT.containsMatchIn(stem)) return ""
        val number = match.groupValues[2].toLongOrNull() ?: return ""
        if (number >= Long.MAX_VALUE) return ""
        return stem + (number + 1) + match.groupValues[3] + suffix
    }
}
