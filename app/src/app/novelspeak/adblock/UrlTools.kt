package app.novelspeak.adblock

import java.util.Locale

/** URL parsing and request classification helpers shared by the engine and the Android layer. */
object UrlTools {

    /** Extracts the lower-cased host from an absolute URL without allocating a Uri. */
    fun host(url: String): String? {
        val start = hostStart(url) ?: return null
        if (url[start] == '[') {
            val close = url.indexOf(']', start)
            if (close <= start) return null
            return url.substring(start + 1, close).lowercase(Locale.US)
        }
        var end = start
        while (end < url.length && url[end] != '/' && url[end] != '?' && url[end] != '#' && url[end] != ':') end++
        if (end <= start) return null
        return url.substring(start, end).lowercase(Locale.US)
    }

    /** Index of the first host character, or null when the URL has no authority. */
    fun hostStart(url: String): Int? {
        val scheme = url.indexOf("://")
        if (scheme <= 0) return null
        var start = scheme + 3
        var authorityEnd = url.length
        var i = start
        while (i < url.length) {
            val c = url[i]
            if (c == '/' || c == '?' || c == '#') {
                authorityEnd = i
                break
            }
            i++
        }
        val at = url.lastIndexOf('@', authorityEnd - 1)
        if (at >= start) start = at + 1
        if (start >= url.length) return null
        return start
    }

    fun scheme(url: String): String {
        val i = url.indexOf("://")
        if (i <= 0) return ""
        return url.substring(0, i).lowercase(Locale.US)
    }

    fun isHttpUrl(url: String): Boolean {
        val s = scheme(url)
        return s == "http" || s == "https"
    }

    fun isThirdParty(requestUrl: String, documentUrl: String?): Boolean {
        if (documentUrl.isNullOrEmpty()) return true
        val a = host(requestUrl) ?: return true
        val b = host(documentUrl) ?: return true
        return !PublicSuffixList.sameSite(a, b)
    }

    /** All host suffixes, most specific first: a.b.example.com, b.example.com, example.com, com. */
    fun suffixes(host: String): List<String> {
        val out = ArrayList<String>(5)
        out.add(host)
        var idx = host.indexOf('.')
        while (idx >= 0 && idx + 1 < host.length) {
            out.add(host.substring(idx + 1))
            idx = host.indexOf('.', idx + 1)
        }
        return out
    }

    /**
     * Tokens are alphanumeric runs of length >= 2, lower-cased. This mirrors adblock-rust's
     * `fast_tokenizer_no_regex` closely enough for bucketing; token collisions are resolved by the
     * full pattern check, so the tokenizer only has to be stable, not exact.
     */
    fun tokens(url: String): ArrayList<String> {
        val out = ArrayList<String>(32)
        val sb = StringBuilder(16)
        for (c in url) {
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9') {
                sb.append(c)
            } else {
                if (sb.length >= 2) out.add(sb.toString().lowercase(Locale.US))
                sb.setLength(0)
                if (out.size >= 255) return out
            }
        }
        if (sb.length >= 2) out.add(sb.toString().lowercase(Locale.US))
        return out
    }

    /** Token candidates from a rule pattern, used for frequency-based bucket selection. */
    fun patternTokens(pattern: String): ArrayList<String> {
        val out = ArrayList<String>(8)
        val sb = StringBuilder(16)
        var preceding: Char = ' '
        for (c in pattern) {
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9') {
                sb.append(c)
            } else {
                // A token touching '*' is only a fragment of a URL token (e.g. "banner" in
                // "banner*.gif"), so it cannot be used as an exact bucket key.
                if (sb.length >= 2 && preceding != '*' && c != '*') {
                    out.add(sb.toString().lowercase(Locale.US))
                }
                sb.setLength(0)
                preceding = c
            }
        }
        if (sb.length >= 2 && preceding != '*') out.add(sb.toString().lowercase(Locale.US))
        return out
    }

    /**
     * WebView exposes no MIME type on WebResourceRequest, so infer one from Accept and the path.
     * The engine treats `types == 0` as "all network types", and rules with `$script` etc. only
     * match when the inferred type agrees.
     */
    fun typeFromHeaders(accept: String?, url: String): Int {
        val a = accept?.lowercase(Locale.US) ?: ""
        if (a.startsWith("text/css")) return ResourceType.STYLESHEET
        if (a.startsWith("image/")) return ResourceType.IMAGE
        if (a.startsWith("font/") || a.contains("font-woff") || a.contains("application/font")) return ResourceType.FONT
        if (a.startsWith("text/html") || a.startsWith("application/xhtml")) return ResourceType.SUBDOCUMENT
        if (a.contains("javascript") || a.contains("ecmascript")) return ResourceType.SCRIPT
        if (a.contains("json") || a.contains("xmlhttprequest")) return ResourceType.XHR
        if (a.startsWith("audio/") || a.startsWith("video/")) return ResourceType.MEDIA
        if (a.contains("text/plain") || a.contains("application/octet-stream")) return ResourceType.OTHER

        val path = path(url).lowercase(Locale.US)
        return when {
            path.endsWith(".js") || path.endsWith(".mjs") -> ResourceType.SCRIPT
            path.endsWith(".css") -> ResourceType.STYLESHEET
            path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") ||
                path.endsWith(".gif") || path.endsWith(".webp") || path.endsWith(".svg") ||
                path.endsWith(".ico") || path.endsWith(".avif") -> ResourceType.IMAGE
            path.endsWith(".woff") || path.endsWith(".woff2") || path.endsWith(".ttf") ||
                path.endsWith(".otf") -> ResourceType.FONT
            path.endsWith(".mp4") || path.endsWith(".webm") || path.endsWith(".mp3") ||
                path.endsWith(".m4a") || path.endsWith(".ogg") -> ResourceType.MEDIA
            else -> ResourceType.OTHER
        }
    }

    fun path(url: String): String {
        val scheme = url.indexOf("://")
        if (scheme < 0) return url
        val slash = url.indexOf('/', scheme + 3)
        if (slash < 0) return "/"
        val q = url.indexOf('?', slash)
        val h = url.indexOf('#', slash)
        var end = url.length
        if (q in 0 until end) end = q
        if (h in 0 until end) end = h
        return url.substring(slash, end)
    }

    /** True when the URL's host is [site] or a subdomain of it. */
    fun hostMatches(host: String, site: String): Boolean {
        if (site.isEmpty()) return false
        if (host == site) return true
        return host.length > site.length && host.endsWith(site) && host[host.length - site.length - 1] == '.'
    }
}
