package app.novelspeak.manga

/**
 * HTML parsing for the two providers whose reader is easiest to read from the server response:
 * Webtoons puts every page in `img._images[data-url]`, and Tapas puts every page in
 * `img.content__img[data-src]`. Parsing the response avoids relying on a WebView renderer and
 * works even when the mobile page shows an "open in the app" notice.
 *
 * Kept Android-free so the fixtures run in the host suite.
 */
object MangaHtmlParser {

    private val IMG_TAG = Regex("""(?is)<img\b[^>]*>""")
    private val TITLE_TAG = Regex("""(?is)<title[^>]*>(.*?)</title>""")

    fun webtoonPageUrls(html: String): List<String> {
        val out = ArrayList<String>()
        for (tag in IMG_TAG.findAll(html)) {
            val text = tag.value
            if (!classNames(text).contains("_images")) continue
            val url = attr(text, "data-url") ?: attr(text, "src") ?: continue
            addImage(out, url)
        }
        if (out.isNotEmpty()) return out
        // Fallback for a server-rendered page where the class is on a parent element.
        for (tag in IMG_TAG.findAll(html)) {
            val url = attr(tag.value, "data-url") ?: continue
            if (url.contains("webtoon-phinf", ignoreCase = true)) addImage(out, url)
        }
        return out
    }

    fun tapasPageUrls(html: String): List<String> {
        val out = ArrayList<String>()
        for (tag in IMG_TAG.findAll(html)) {
            val text = tag.value
            val cls = classNames(text)
            if (!cls.contains("content__img") && !cls.contains("js-lazy")) continue
            addImage(out, attr(text, "data-src") ?: attr(text, "data-url") ?: attr(text, "src"))
        }
        if (out.isNotEmpty()) return out
        // Fallback for lazy attributes outside the known class.
        val re = Regex("""(?is)data-(?:src|url)\s*=\s*["']([^"']*us-a\.tapas\.io[^"']*)["']""")
        for (match in re.findAll(html)) addImage(out, match.groupValues[1])
        return out
    }

    fun webtoonTitle(html: String): String? {
        val raw = title(html) ?: return null
        // "Tower of God - [Season 1] Ep. 0" is already readable.
        val parts = raw.split(" | ").map { it.trim() }.filter { it.isNotEmpty() }
        return if (parts.size >= 2) {
            // Desktop pages use "[Episode] | Series".
            "${parts.last()} · ${parts.first()}"
        } else {
            raw
        }
    }

    fun tapasTitle(html: String): String? {
        val raw = title(html) ?: return null
        val match = Regex("""^Read\s+(.*?)\s*::\s*(.*?)\s*\|.*$""").find(raw)
        if (match != null) {
            return "${match.groupValues[1].trim()} · ${match.groupValues[2].trim()}"
        }
        return raw
    }

    private fun title(html: String): String? {
        val raw = TITLE_TAG.find(html)?.groupValues?.get(1)?.let { decode(it).trim() }
        return raw?.takeIf { it.isNotEmpty() }
    }

    private fun classNames(tag: String): String =
        attr(tag, "class")?.lowercase().orEmpty()

    private fun attr(tag: String, name: String): String? {
        val match = Regex(
            """(?is)\b${Regex.escape(name)}\s*=\s*(?:"([^"]*)"|'([^']*)')"""
        ).find(tag) ?: return null
        val value = match.groupValues[1].ifEmpty { match.groupValues[2] }
        return decode(value).trim().ifEmpty { null }
    }

    private fun addImage(out: MutableList<String>, raw: String?) {
        if (raw.isNullOrBlank()) return
        val clean = decode(raw).trim()
        val path = clean.substringBefore('?').substringBefore('#').lowercase()
        if (!path.endsWith(".jpg") && !path.endsWith(".jpeg") &&
            !path.endsWith(".png") && !path.endsWith(".webp")
        ) {
            return
        }
        if (out.none { it == clean }) out.add(clean)
    }

    private fun decode(value: String): String = value
        .replace("&amp;", "&")
        .replace("&#38;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
}
