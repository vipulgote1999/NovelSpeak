package app.novelspeak.manga

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches a provider's reader HTML and lets [MangaHtmlParser] pull the page list out of it.
 *
 * Webtoons and Tapas both render page images server-side. Reading that HTML directly is more
 * reliable than depending on a WebView renderer: Tapas' heavy mobile page has crashed the
 * emulator's Chromium renderer, and Webtoons serves a mobile "open in the app" notice inside
 * some WebViews. The WebView remains the browser; this is only the chapter resolver.
 */
object MangaHtmlResolver {

    data class Result(val title: String, val urls: List<String>)

    private const val TAG = "NovelSpeakMangaHtml"
    private const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    private const val DESKTOP_UA =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    fun resolve(kind: MangaProvider.Kind, url: String, cookie: String?): Result? {
        val target = when (kind) {
            MangaProvider.Kind.WEBTOONS -> webtoonDesktopUrl(url)
            MangaProvider.Kind.TAPAS -> tapasUrl(url)
            else -> return null
        }
        val ua = if (kind == MangaProvider.Kind.WEBTOONS) DESKTOP_UA else MOBILE_UA
        val html = fetch(target, ua, cookie) ?: return null
        val urls = when (kind) {
            MangaProvider.Kind.WEBTOONS -> MangaHtmlParser.webtoonPageUrls(html)
            MangaProvider.Kind.TAPAS -> MangaHtmlParser.tapasPageUrls(html)
            else -> emptyList()
        }
        if (urls.isEmpty()) {
            Log.i(TAG, "${kind.name.lowercase()} no pages target=${target.take(100)} bytes=${html.length}")
            return null
        }
        val title = when (kind) {
            MangaProvider.Kind.WEBTOONS -> MangaHtmlParser.webtoonTitle(html)
            MangaProvider.Kind.TAPAS -> MangaHtmlParser.tapasTitle(html)
            else -> null
        } ?: "Manga chapter"
        Log.i(TAG, "${kind.name.lowercase()} pages=${urls.size} title=${title.take(80)}")
        return Result(title, urls)
    }

    /** The desktop response carries the `_imageList` data-url tags; the mobile one does not. */
    private fun webtoonDesktopUrl(url: String): String {
        var fixed = url
            .replace("://m.webtoons.com/", "://www.webtoons.com/")
            .replace("&webtoon-platform-redirect=true", "")
            .replace("?webtoon-platform-redirect=true&", "?")
            .replace("?webtoon-platform-redirect=true", "")
        if (fixed.endsWith("?")) fixed = fixed.dropLast(1)
        return fixed
    }

    private fun tapasUrl(url: String): String =
        url.replace("://m.tapas.io/", "://tapas.io/")

    private fun fetch(url: String, userAgent: String, cookie: String?): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 25000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                if (!cookie.isNullOrBlank()) setRequestProperty("Cookie", cookie)
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "HTTP $code for ${url.take(100)}")
                return null
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            if (body.length > 4_000_000) body.substring(0, 4_000_000) else body
        } catch (t: Throwable) {
            Log.w(TAG, "fetch failed ${url.take(100)}: ${t.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }
}
