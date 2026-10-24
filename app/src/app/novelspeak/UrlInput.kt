package app.novelspeak

import java.net.URLEncoder

/**
 * Turns free-form address-bar text into a URL the WebView is allowed to load.
 *
 * Only http(s) and about: URLs are produced. Input that is not a host is sent to the default
 * search engine, matching the behaviour users expect from a mobile browser. Dangerous schemes
 * (javascript:, file:, content:, intent:) are never handed to the WebView.
 */
object UrlInput {
    private const val SEARCH = "https://www.google.com/search?q="

    private val NON_WEB_SCHEMES = setOf(
        "javascript", "file", "content", "intent", "data", "blob",
        "mailto", "tel", "ftp", "ws", "wss", "chrome", "resource"
    )

    fun normalize(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        val lower = text.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) return text
        if (lower.startsWith("about:")) return text

        // Anything that already carries a non-web scheme is treated as a search query. A bare
        // "localhost:3000" is not a scheme; it falls through to the host check below.
        val colon = text.indexOf(':')
        if (colon in 2..24) {
            val scheme = text.substring(0, colon).lowercase()
            val followedByAuthority = text.startsWith("$scheme://", ignoreCase = true)
            if (scheme.all { it.isLetter() } &&
                (followedByAuthority || scheme in NON_WEB_SCHEMES)
            ) {
                return search(text)
            }
        }

        if (looksLikeHost(text)) return "https://$text"
        return search(text)
    }

    fun looksLikeHost(text: String): Boolean {
        if (text.any { it.isWhitespace() }) return false
        if (text.startsWith("localhost")) return true
        val host = text.substringBefore('/').substringBefore('?').substringBefore('#')
        if (host.isEmpty() || host.startsWith('.') || host.endsWith('.')) return false
        if (!host.contains('.')) return false
        return host.all {
            it.isLetterOrDigit() || it == '.' || it == '-' || it == ':' ||
                it == '[' || it == ']' || it == '%'
        }
    }

    private fun search(text: String): String =
        SEARCH + URLEncoder.encode(text, "UTF-8")
}
