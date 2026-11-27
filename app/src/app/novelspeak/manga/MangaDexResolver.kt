package app.novelspeak.manga

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches a MangaDex chapter's page list from the public API.
 *
 * MangaDex's reader page can show a browser-verification challenge inside a WebView even though
 * its API is public. Resolving here sidesteps that and also avoids rendering a page that would
 * otherwise only produce a challenge screen. Chapters that are hosted externally (Manga Plus,
 * official publisher links) are rejected so the normal browser flow can open the source instead.
 */
object MangaDexResolver {

    data class Result(val title: String, val urls: List<String>)

    fun resolve(chapterId: String): Result? {
        Log.i(TAG, "resolve chapter=$chapterId")
        val chapter = getJson("https://api.mangadex.org/chapter/$chapterId")
            ?: return null
        val data = chapter.optJSONObject("data") ?: return null
        val attributes = data.optJSONObject("attributes") ?: return null
        val external = attributes.optString("externalUrl").orEmpty()
            .takeUnless { it == "null" || it.isBlank() }
            .orEmpty()
        val pages = attributes.optInt("pages", 0)
        if (external.isNotBlank() || pages <= 0) {
            Log.i(TAG, "chapter unavailable pages=$pages external=${external.take(80)}")
            return null
        }

        val mangaId = mangaId(data)
        val title = mangaTitle(mangaId) ?: "MangaDex chapter"
        Log.i(TAG, "chapter pages=$pages title=${title.take(80)}")

        val atHome = getJson("https://api.mangadex.org/at-home/server/$chapterId")
            ?: return null
        val baseUrl = atHome.optString("baseUrl")
        val chapterData = atHome.optJSONObject("chapter") ?: return null
        val hash = chapterData.optString("hash")
        val original = chapterData.optJSONArray("data")
        val saver = chapterData.optJSONArray("dataSaver")

        val originalFiles = stringList(original)
        val urls = if (originalFiles.isNotEmpty()) {
            MangaProvider.pageUrls(baseUrl, hash, originalFiles)
        } else {
            MangaProvider.pageUrls(baseUrl, hash, stringList(saver), dataSaver = true)
        }
        if (urls.isEmpty()) return null
        Log.i(TAG, "resolved pages=${urls.size} first=${urls.first().take(100)}")
        return Result(title, urls)
    }

    private fun mangaId(chapter: JSONObject): String? {
        val relationships = chapter.optJSONArray("relationships") ?: return null
        for (i in 0 until relationships.length()) {
            val rel = relationships.optJSONObject(i) ?: continue
            if (rel.optString("type") == "manga") return rel.optString("id").takeIf { it.isNotBlank() }
        }
        return null
    }

    private fun mangaTitle(mangaId: String?): String? {
        if (mangaId.isNullOrBlank()) return null
        val manga = getJson("https://api.mangadex.org/manga/$mangaId") ?: return null
        val titles = manga.optJSONObject("data")
            ?.optJSONObject("attributes")
            ?.optJSONObject("title")
            ?: return null
        return titles.optString("en").takeIf { it.isNotBlank() }
            ?: titles.keys().asSequence().map { titles.optString(it) }.firstOrNull { it.isNotBlank() }
    }

    private fun stringList(array: org.json.JSONArray?): List<String> {
        if (array == null) return emptyList()
        val out = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            val value = array.optString(i)
            if (value.isNotBlank()) out.add(value)
        }
        return out
    }

    private const val TAG = "NovelSpeakMangaDex"

    private fun getJson(url: String): JSONObject? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 20000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "NovelSpeak/1.4 (Android)")
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "HTTP $code for ${url.take(100)}")
                return null
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body)
        } catch (t: Throwable) {
            Log.w(TAG, "request failed ${url.take(100)}: ${t.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }
}
